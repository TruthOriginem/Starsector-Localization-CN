"""
jar 预处理构建编排 —— Python 指挥 native 编译、Java 管线与产物分发。

职责划分（与仓库整体"构建脚本以 Python 为主"的约定一致）：
  - 本脚本（Python）：编译 native 库（g++ 编 ssime.dll、CMake+Ninja 编
    ss_dyn_font.dll）、调 mvnw 运行 Java 管线、所有产物打包与复制分发
    （两个 dll → localization/native/windows/；
      动态字体源资产打成单文件数据包 → localization/graphics/fonts/dyn_font/typefaces.dat）
  - Java 管线（mvnw clean compile exec:java）：jar 的 ASM patch、字符串解耦、
    运行时类注入、original/ 与 localization/ 的 jar 写出

localization/ 是直接用于打包分发的内容，只放运行时需要的文件；字体源 TTF 与
自动生成的 kerning 固化表都在 jar_pre_processing/native/dyn_font/fonts/ 下，
不纳入 git。两者经确定性打包生成的 typefaces.dat 作为预构建分发资产入库；
native CLI 导出的 assets.json 是实际资产依赖清单。

native 库是**提交入库的预编译产物**，日常构建不重编：确定性构建参数
（-Wl,--no-insert-timestamp）只保证同一编译器下源码不变则产物字节一致，
编译器升级仍会产生二进制 diff，故重编应是显式操作并连同产物一起提交。

用法见 `python build.py --help`。
"""

import argparse
import hashlib
import importlib.util
import json
import os
import shutil
import struct
import subprocess
import sys
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

PROJECT_DIR = Path(__file__).parent
REPO_ROOT = PROJECT_DIR.parent

NATIVE_DIST_DIR = REPO_ROOT / 'localization' / 'native' / 'windows'

IME_DIR = PROJECT_DIR / 'native' / 'ime'
IME_SOURCE = IME_DIR / 'ssime.cpp'
IME_DLL = IME_DIR / 'ssime.dll'
IME_TEST_SOURCE = IME_DIR / 'tests' / 'ssime_smoke_test.cpp'
IME_TEST_DIR = PROJECT_DIR / 'target' / 'native-tests'
IME_TEST_EXE = IME_TEST_DIR / 'ssime_smoke_test.exe'
IME_TEST_LOG = IME_TEST_DIR / 'starsector_ime_native.log'

DYNFONT_DIR = PROJECT_DIR / 'native' / 'dyn_font'
DYNFONT_DLL = DYNFONT_DIR / 'ss_dyn_font.dll'
DYNFONT_BUILD_DIR = DYNFONT_DIR / 'build'
DYNFONT_CLI = DYNFONT_BUILD_DIR / (
    'dynfont_cli.exe' if os.name == 'nt' else 'dynfont_cli'
)
DYNFONT_FONTS_DIR = DYNFONT_DIR / 'fonts'
DYNFONT_ASSET_MANIFEST = DYNFONT_DIR / 'assets.json'
DYNFONT_KERNING_EXPORTER = DYNFONT_DIR / 'tools' / 'export_kerning.py'
FREETYPE_DIR = DYNFONT_DIR / 'third_party' / 'freetype'
# 2.13.2 与 fnt_composer 用的 freetype-py 内嵌版本一致（金标准逐字形 diff 的前提）
FREETYPE_TAG = 'VER-2-13-2'
# 不只依赖可被移动的 tag：发布构建必须使用这一确切源码树。
FREETYPE_COMMIT = '920c5502cc3ddda88f6c7d85ee834ac611bb11cc'
DYNFONT_DIST_DIR = REPO_ROOT / 'localization' / 'graphics' / 'fonts' / 'dyn_font'
DYNFONT_DATA_PACK = DYNFONT_DIST_DIR / 'typefaces.dat'

# 发布构建参数显式固定，避免既有 CMake cache 或工具链默认值改变实际产物。
DYNFONT_CMAKE_DEFINES = (
    '-DCMAKE_BUILD_TYPE=Release',
    '-DBUILD_TESTING=ON',
    '-DDYNFONT_ENABLE_IPO=ON',
    '-DDYNFONT_PROJECT_OPTIMIZATION=-O2',
    # CMake 4.x 兼容 FreeType 2.13.2 的旧 cmake_minimum_required
    '-DCMAKE_POLICY_VERSION_MINIMUM=3.5',
)


@dataclass(frozen=True)
class KerningAsset:
    font: str
    weight: int
    table: str


@dataclass(frozen=True)
class DynFontAssets:
    fonts: tuple[str, ...]
    kerning: tuple[KerningAsset, ...]


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, 'rb') as f:
        while chunk := f.read(65536):
            digest.update(chunk)
    return digest.hexdigest()


def write_if_changed(path: Path, data: bytes) -> bool:
    """仅在内容变化时覆写生成文件，避免无意义的时间戳与工作区扰动。"""
    if path.is_file() and path.read_bytes() == data:
        return False
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    return True


def find_jni_include() -> Path:
    java_home = os.environ.get('JAVA_HOME', '')
    include = Path(java_home) / 'include'
    if not java_home or not (include / 'jni.h').is_file():
        sys.exit('错误：JAVA_HOME 未设置或其 include/ 下没有 jni.h（需要 JDK 17+）')
    return include


def _freetype_git_output(*args: str) -> str:
    """在 FreeType checkout 中执行只读 git 查询，并转换为可操作的构建错误。"""
    result = subprocess.run(
        ['git', '-C', str(FREETYPE_DIR), *args],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding='utf-8',
        errors='replace',
    )
    if result.returncode != 0:
        detail = (
            result.stderr.strip()
            or result.stdout.strip()
            or f'exit {result.returncode}'
        )
        sys.exit(f'错误：无法校验 FreeType checkout ({" ".join(args)}): {detail}')
    return result.stdout.strip()


def ensure_freetype_checkout() -> None:
    """准备并校验确切、干净的 FreeType 源码，避免静默编入本地改动。"""
    if not os.path.lexists(FREETYPE_DIR):
        print(f'[native] 首次构建：clone FreeType {FREETYPE_TAG} 源码 ...')
        subprocess.run(
            [
                'git',
                'clone',
                '--depth',
                '1',
                '--branch',
                FREETYPE_TAG,
                'https://github.com/freetype/freetype.git',
                str(FREETYPE_DIR),
            ],
            check=True,
        )
    if not FREETYPE_DIR.is_dir() or not (FREETYPE_DIR / 'CMakeLists.txt').is_file():
        sys.exit(
            f'错误：FreeType 路径不是完整源码 checkout: {FREETYPE_DIR}\n'
            '该目录已被仓库 .gitignore 忽略；请删除它后重跑以自动重建。'
        )

    head = _freetype_git_output('rev-parse', 'HEAD').lower()
    if head != FREETYPE_COMMIT:
        sys.exit(
            f'错误：FreeType HEAD 不是已锁定的 {FREETYPE_TAG}\n'
            f'  期望: {FREETYPE_COMMIT}\n  实际: {head}\n'
            '请删除被忽略的 third_party/freetype 目录后重跑。'
        )
    dirty = _freetype_git_output('status', '--porcelain=v1', '--untracked-files=all')
    if dirty:
        preview = '\n'.join(dirty.splitlines()[:20])
        suffix = '\n  ...' if len(dirty.splitlines()) > 20 else ''
        sys.exit(
            '错误：FreeType checkout 含本地改动，拒绝生成不可复现的 native 库:\n'
            f'{preview}{suffix}\n'
            '请还原改动，或删除被忽略的 third_party/freetype 目录后重跑。'
        )


def run_dynfont_native_tests() -> None:
    """运行 CMake 注册的全部 native 测试；零测试也视为构建失败。"""
    print('[native] 运行动态字体 native 测试 ...')
    subprocess.run(
        [
            'ctest',
            '--test-dir',
            str(DYNFONT_BUILD_DIR),
            '-C',
            'Release',
            '--output-on-failure',
            '--no-tests=error',
        ],
        check=True,
        cwd=DYNFONT_DIR,
    )


def build_ssime() -> None:
    """g++ 编译输入法原生库（参数含义见 native/ime/ssime.cpp 头注释）。"""
    if shutil.which('g++') is None:
        sys.exit('错误：PATH 中找不到 g++（需要 MinGW-w64）')
    include = find_jni_include()
    cmd = [
        'g++',
        '-std=c++17',
        '-shared',
        '-O2',
        '-s',
        '-Wall',
        '-Wextra',
        '-Werror',
        '-static',
        '-static-libgcc',
        '-static-libstdc++',
        # 去除 PE 头时间戳：源码不变时重建产物字节一致
        '-Wl,--no-insert-timestamp',
        f'-I{include}',
        f'-I{include / "win32"}',
        '-o',
        str(IME_DLL),
        str(IME_SOURCE),
        '-limm32',
        '-lgdi32',
        '-luser32',
    ]
    print(f'[native] 编译 {IME_DLL.name} ...')
    subprocess.run(cmd, check=True, cwd=PROJECT_DIR)
    IME_TEST_DIR.mkdir(parents=True, exist_ok=True)
    test_cmd = [
        'g++',
        '-std=c++17',
        '-O2',
        '-Wall',
        '-Wextra',
        '-Werror',
        '-o',
        str(IME_TEST_EXE),
        str(IME_TEST_SOURCE),
        '-limm32',
    ]
    print('[native] 编译并运行 ssime 隐藏窗口 smoke test ...')
    subprocess.run(test_cmd, check=True, cwd=PROJECT_DIR)
    IME_TEST_LOG.unlink(missing_ok=True)
    try:
        subprocess.run(
            [str(IME_TEST_EXE), str(IME_DLL.resolve())],
            check=True,
            cwd=IME_TEST_DIR,
            timeout=30,
        )
    finally:
        IME_TEST_LOG.unlink(missing_ok=True)
    print(f'[native] 完成  sha256={sha256(IME_DLL)}')


def build_dynfont() -> None:
    """CMake+Ninja 编译动态字体原生库（FreeType 静态链接，见 native/dyn_font/CMakeLists.txt）。"""
    for tool in ('cmake', 'ctest', 'ninja', 'g++', 'git'):
        if shutil.which(tool) is None:
            sys.exit(f'错误：PATH 中找不到 {tool}（需要 CMake + Ninja + MinGW-w64）')
    find_jni_include()  # CMake 从 JAVA_HOME 取 JNI 头，先行校验

    ensure_freetype_checkout()

    print(f'[native] 编译 {DYNFONT_DLL.name} (CMake + Ninja) ...')
    subprocess.run(
        ['cmake', '-B', str(DYNFONT_BUILD_DIR), '-G', 'Ninja', *DYNFONT_CMAKE_DEFINES],
        check=True,
        cwd=DYNFONT_DIR,
    )
    # 构建默认 all 目标，让当前及未来新增的 CTest 可执行文件都不会漏掉。
    subprocess.run(
        ['cmake', '--build', str(DYNFONT_BUILD_DIR), '--config', 'Release'],
        check=True,
        cwd=DYNFONT_DIR,
    )
    run_dynfont_native_tests()
    # dll 产物提交入库；CLI（dynfont_cli.exe）留在 build/ 供离线调试与金标准 diff
    shutil.copyfile(DYNFONT_BUILD_DIR / DYNFONT_DLL.name, DYNFONT_DLL)
    print(f'[native] 完成  sha256={sha256(DYNFONT_DLL)}')
    assets = refresh_dynfont_asset_manifest()
    sync_kerning_tables(assets)


def run_java_pipeline(
    optimizations: str,
    disabled_patch_groups: list[str],
    profiling: bool,
) -> None:
    """从干净 classes 目录运行 jar patch/解耦/注入/写出（详见 README）。"""
    mvnw = PROJECT_DIR / ('mvnw.cmd' if os.name == 'nt' else 'mvnw')
    print('[java] 运行 jar 预处理管线 (mvnw clean compile exec:java) ...')
    subprocess.run(
        [
            str(mvnw),
            '-Dfile.encoding=UTF-8',
            f'-Dstarsector.preprocess.optimizations={optimizations}',
            '-Dstarsector.preprocess.disabledPatchGroups='
            + ','.join(disabled_patch_groups),
            '-Dstarsector.preprocess.profiling=' + ('true' if profiling else 'false'),
            # 防止 IDE/增量编译器遗留的错误桩或旧 runtime helper 被注入发布 jar。
            'clean',
            'compile',
            'exec:java',
        ],
        check=True,
        cwd=PROJECT_DIR,
    )


def _validate_asset_name(value: object, field: str) -> str:
    if not isinstance(value, str) or not value:
        sys.exit(f'错误：{DYNFONT_ASSET_MANIFEST.name} 的 {field} 必须是非空字符串')
    path = Path(value)
    if path.is_absolute() or path.name != value or value in {'.', '..'}:
        sys.exit(
            f'错误：{DYNFONT_ASSET_MANIFEST.name} 的 {field} 只能是文件名: {value!r}'
        )
    return value


def parse_dynfont_asset_manifest(raw: object) -> DynFontAssets:
    """校验 native CLI 产出的资产清单；该清单是打包输入的唯一来源。"""
    if not isinstance(raw, dict) or raw.get('version') != 1:
        sys.exit(f'错误：{DYNFONT_ASSET_MANIFEST.name} 格式错误或版本不受支持')

    raw_fonts = raw.get('fonts')
    raw_kerning = raw.get('kerning')
    if not isinstance(raw_fonts, list) or not isinstance(raw_kerning, list):
        sys.exit(f'错误：{DYNFONT_ASSET_MANIFEST.name} 缺少 fonts/kerning 数组')

    fonts = tuple(
        sorted(
            (_validate_asset_name(value, 'fonts[]') for value in raw_fonts),
            key=lambda value: value.encode('utf-8'),
        )
    )
    if len(fonts) != len(set(fonts)):
        sys.exit(f'错误：{DYNFONT_ASSET_MANIFEST.name} 的 fonts 存在重复项')

    kerning: list[KerningAsset] = []
    for index, item in enumerate(raw_kerning):
        if not isinstance(item, dict):
            sys.exit(f'错误：kerning[{index}] 必须是对象')
        font = _validate_asset_name(item.get('font'), f'kerning[{index}].font')
        table = _validate_asset_name(item.get('table'), f'kerning[{index}].table')
        weight = item.get('weight')
        if isinstance(weight, bool) or not isinstance(weight, int) or weight < 0:
            sys.exit(f'错误：kerning[{index}].weight 必须是非负整数')
        if font not in fonts:
            sys.exit(f'错误：kerning[{index}] 引用了 fonts 中不存在的字体: {font}')
        if not table.endswith('.kern.txt'):
            sys.exit(f'错误：kerning[{index}].table 后缀必须是 .kern.txt: {table}')
        kerning.append(KerningAsset(font, weight, table))

    kerning.sort(key=lambda item: item.table.encode('utf-8'))
    if len({item.table for item in kerning}) != len(kerning):
        sys.exit(f'错误：{DYNFONT_ASSET_MANIFEST.name} 的 kerning 表名存在重复项')
    if len({(item.font, item.weight) for item in kerning}) != len(kerning):
        sys.exit(f'错误：{DYNFONT_ASSET_MANIFEST.name} 的 font/weight 组合存在重复项')
    return DynFontAssets(fonts, tuple(kerning))


def _asset_manifest_bytes(assets: DynFontAssets) -> bytes:
    payload = {
        'version': 1,
        'fonts': list(assets.fonts),
        'kerning': [
            {'font': item.font, 'weight': item.weight, 'table': item.table}
            for item in assets.kerning
        ],
    }
    return (json.dumps(payload, ensure_ascii=False, indent=2) + '\n').encode('utf-8')


def load_dynfont_asset_manifest() -> DynFontAssets:
    if not DYNFONT_ASSET_MANIFEST.is_file():
        sys.exit(
            f'错误：缺少 {DYNFONT_ASSET_MANIFEST.relative_to(REPO_ROOT)}；'
            '请先运行 `python build.py dynfont` 从 native 规格生成'
        )
    try:
        raw = json.loads(DYNFONT_ASSET_MANIFEST.read_text(encoding='utf-8'))
    except (OSError, json.JSONDecodeError) as exc:
        sys.exit(f'错误：无法读取动态字体资产清单: {exc}')
    return parse_dynfont_asset_manifest(raw)


def refresh_dynfont_asset_manifest() -> DynFontAssets:
    """从刚编译的 native CLI 导出资产清单，消除 Python/C++ 双份字体参数。"""
    if not DYNFONT_CLI.is_file():
        sys.exit(f'错误：缺少 {DYNFONT_CLI}，无法从 native 规格导出资产清单')
    result = subprocess.run(
        [str(DYNFONT_CLI), '--list-assets'],
        check=True,
        cwd=DYNFONT_DIR,
        stdout=subprocess.PIPE,
        text=True,
        encoding='utf-8',
    )
    try:
        assets = parse_dynfont_asset_manifest(json.loads(result.stdout))
    except json.JSONDecodeError as exc:
        sys.exit(f'错误：dynfont_cli --list-assets 输出了无效 JSON: {exc}')
    changed = write_if_changed(DYNFONT_ASSET_MANIFEST, _asset_manifest_bytes(assets))
    state = '已更新' if changed else '无变化'
    print(
        f'[assets] native 资产清单 {state}: {DYNFONT_ASSET_MANIFEST.relative_to(REPO_ROOT)}'
    )
    return assets


def sync_kerning_tables(assets: DynFontAssets) -> tuple[Path, ...]:
    """按资产清单生成所需表，并删除清单未引用的旧 kerning 表。"""
    if assets.kerning and importlib.util.find_spec('fontTools') is None:
        sys.exit('错误：生成 kerning 固化表需要 Python 包 fontTools')
    by_font: dict[str, list[KerningAsset]] = defaultdict(list)
    for item in assets.kerning:
        by_font[item.font].append(item)

    for font, tables in sorted(
        by_font.items(), key=lambda item: item[0].encode('utf-8')
    ):
        source = DYNFONT_FONTS_DIR / font
        if not source.is_file():
            sys.exit(f'错误：缺少 kerning 字体源: {source.relative_to(REPO_ROOT)}')
        command = [
            sys.executable,
            '-X',
            'utf8',
            str(DYNFONT_KERNING_EXPORTER),
            '--font',
            str(source),
            '--output-dir',
            str(DYNFONT_FONTS_DIR),
        ]
        for table in sorted(tables, key=lambda item: item.table.encode('utf-8')):
            command.extend(('--table', str(table.weight), table.table))
        subprocess.run(command, check=True, cwd=PROJECT_DIR)

    expected_names = {item.table for item in assets.kerning}
    removed = []
    for path in DYNFONT_FONTS_DIR.glob('*.kern.txt'):
        if path.name not in expected_names:
            path.unlink()
            removed.append(path.name)
    if removed:
        print(f'[kern] 删除未引用固化表: {", ".join(sorted(removed))}')

    missing = sorted(
        name for name in expected_names if not (DYNFONT_FONTS_DIR / name).is_file()
    )
    if missing:
        sys.exit('错误：kerning 导出后仍缺少清单要求的表:\n  ' + '\n  '.join(missing))
    return tuple(DYNFONT_FONTS_DIR / item.table for item in assets.kerning)


def collect_dynfont_pack_files(assets: DynFontAssets) -> tuple[Path, ...]:
    names = [*assets.fonts, *(item.table for item in assets.kerning)]
    missing = sorted(name for name in names if not (DYNFONT_FONTS_DIR / name).is_file())
    if missing:
        sys.exit(
            '错误：动态字体数据包缺少资产（来源见 fonts/README.md）:\n  '
            + '\n  '.join(missing)
        )
    return tuple(
        sorted(
            (DYNFONT_FONTS_DIR / name for name in names),
            key=lambda path: path.name.encode('utf-8'),
        )
    )


def write_data_pack(files: tuple[Path, ...]) -> None:
    """按确定性 SSDF v1 格式写出动态字体数据包。"""
    payload = bytearray(b'SSDF')
    payload.extend(struct.pack('<II', 1, len(files)))
    for path in files:
        name = path.name.encode('utf-8')
        if len(name) > 0xFFFF:
            sys.exit(f'错误：数据包条目名超过 uint16 上限: {path.name}')
        content = path.read_bytes()
        payload.extend(struct.pack('<H', len(name)))
        payload.extend(name)
        payload.extend(struct.pack('<Q', len(content)))
        payload.extend(content)
    changed = write_if_changed(DYNFONT_DATA_PACK, bytes(payload))
    state = '已更新' if changed else '无变化'
    print(
        f'[dist] 字体数据包 {state} ({len(files)} 个条目) -> '
        f'{DYNFONT_DATA_PACK.relative_to(REPO_ROOT)}  sha256={sha256(DYNFONT_DATA_PACK)}'
    )


def build_data_pack() -> None:
    """同步 native 所需 kerning 后，打包清单中实际引用的 TTF 与固化表。"""
    assets = load_dynfont_asset_manifest()
    sync_kerning_tables(assets)
    write_data_pack(collect_dynfont_pack_files(assets))


def distribute() -> None:
    """分发运行时产物：两个 dll → localization/native/windows/（游戏
    java.library.path，System.loadLibrary 按库名加载）；动态字体源资产
    打包 → localization/graphics/fonts/dyn_font/typefaces.dat。"""
    for dll in (IME_DLL, DYNFONT_DLL):
        if not dll.is_file():
            sys.exit(f'错误：缺少预编译的 {dll}（运行对应 build.py 步骤生成）')
        NATIVE_DIST_DIR.mkdir(parents=True, exist_ok=True)
        target = NATIVE_DIST_DIR / dll.name
        shutil.copyfile(dll, target)
        print(
            f'[dist] {dll.name} -> {target.relative_to(REPO_ROOT)}  sha256={sha256(target)}'
        )
    build_data_pack()


def run_jar_step(
    optimizations: str,
    disabled_patch_groups: list[str],
    profiling: bool,
) -> None:
    run_java_pipeline(optimizations, disabled_patch_groups, profiling)
    distribute()


# 步骤注册表：执行顺序固定为此处声明顺序（native 先于 jar），与命令行输入顺序无关
STEPS: dict[str, tuple[str, Callable[[], None]]] = {
    'ime': ('编译输入法原生库 ssime.dll（g++）', build_ssime),
    'dynfont': ('编译动态字体原生库，并刷新资产清单/kerning', build_dynfont),
    'jar': (
        'Java 管线（mvnw：ASM patch/字符串解耦/运行时注入/jar 写出）+ 产物分发',
        run_jar_step,
    ),
}


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    step_lines = '\n'.join(f'  {name:<11}{desc}' for name, (desc, _) in STEPS.items())
    parser = argparse.ArgumentParser(
        description='jar 预处理构建编排：native 编译（g++/CMake）、Java 管线（mvnw）与产物分发。',
        epilog=(
            f'步骤（可单独、组合或用 all 全量运行；执行顺序固定为 native 先于 jar）：\n'
            f'{step_lines}\n'
            f'  all      依次运行以上全部步骤\n'
            f'\n'
            f'默认（无参数）等价于 `build.py jar`：日常流程，使用已提交的预编译 native 库。\n'
            f'native 库是提交入库的预编译产物：重编（ime/dynfont 步骤）依赖 PATH 中的\n'
            f'MinGW-w64 g++、CMake 与 Ninja（dynfont 首次构建还需网络 clone FreeType），\n'
            f'且应连同产物一起提交（编译器升级会产生二进制 diff）。\n'
            f'jar 步骤依赖 JAVA_HOME 指向 JDK 17+。\n'
            f'\n'
            f'示例：\n'
            f'  python build.py                  # 日常：Java 管线 + 分发\n'
            f'  python build.py dynfont          # 重编动态字体并刷新资产清单/kerning\n'
            f'  python build.py ime dynfont      # 重编两个 native 库\n'
            f'  python build.py dynfont jar      # 重编动态字体库后走完整流程\n'
            f'  python build.py jar --optimizations none\n'
            f'  python build.py jar --disable-patch-group texture-cache\n'
            f'  python build.py jar --profiling on\n'
            f'  python build.py all              # 全部'
        ),
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument(
        'steps',
        nargs='*',
        metavar='step',
        choices=[*STEPS, 'all'],
        default=['jar'],
        help=f'要运行的步骤：{" / ".join([*STEPS, "all"])}（默认 jar）',
    )
    parser.add_argument(
        '--optimizations',
        default='all',
        metavar='SPEC',
        help='启用的优化组：all、none 或逗号分隔组名（默认 all）',
    )
    parser.add_argument(
        '--disable-patch-group',
        action='append',
        default=[],
        metavar='GROUP',
        help=(
            '显式禁用一个已请求的 patch 组；可重复使用。不会递归禁用依赖方，'
            '若仍启用的组缺少依赖则构建报错'
        ),
    )
    parser.add_argument(
        '--profiling',
        choices=('on', 'off'),
        default='off',
        help='是否注入启动阶段 profiling patch 与 runtime（默认 off；基准测试时显式 on）',
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> None:
    args = parse_args(argv)
    selected = set(STEPS) if 'all' in args.steps else set(args.steps)
    for name, (_, step) in STEPS.items():  # 按注册表顺序执行
        if name in selected:
            if name == 'jar':
                run_jar_step(
                    args.optimizations, args.disable_patch_group, args.profiling == 'on'
                )
            else:
                step()
    print(f'[done] 完成步骤: {" ".join(n for n in STEPS if n in selected)}')


if __name__ == '__main__':
    main()
