"""
制作汉化安装包（.exe）
调用 Inno Setup 编译 .iss 脚本，根据当前 git 分支自动选择变体名称。

用法：
  python -X utf8 packaging/make_exe.py --package all           # 两种安装包
  python -X utf8 packaging/make_exe.py --package translation   # 仅独立汉化包
  python -X utf8 packaging/make_exe.py --package full          # 仅含游戏完整包

输出文件名由 ISS 脚本的 OutputBaseFilename 决定，格式如：
  Starsector(远行星号) 0.98a-RC8 独立汉化包(黑体版) v1.0.0 [远星汉化组].exe
  Starsector(远行星号) 0.98a-RC8 独立汉化包(黑体版) v1.0.0 2026.04.05 [远星汉化组].exe  （INCLUDE_DATE=true 时）

配置（在 packaging/.env 中设置，参考 packaging/.env.example）：
  ISCC_PATH                    - Inno Setup 6 编译器路径，留空则自动搜索常见安装位置（不支持 Inno Setup 5）
  ORIGINAL_GAME_FOLDER         - 原版游戏文件夹路径，用于制作含游戏完整安装包；
                                 请求完整包时必须存在并通过原版文件树校验
  GAME_VERSION                 - 覆盖游戏版本号（留空则从 localization_version.json 读取）
  APP_VERSION                  - 覆盖汉化版本号（留空则从 localization_version.json 读取）
  INCLUDE_DATE                 - 文件名是否包含日期后缀，true/false（默认 false）
  BRANCH_VARIANT_<分支名>      - 各分支对应的变体名，如：
                                 BRANCH_VARIANT_master=(黑体版)
                                 BRANCH_VARIANT_font-simsong=(宋体版)
"""

import argparse
import os
import subprocess
import sys
from datetime import date
from pathlib import Path

from game_integrity import validate_original_game_folder
from package_utils import load_env, load_package_metadata, read_env_bool

PACKAGING_DIR = Path(__file__).parent
REPO_ROOT = PACKAGING_DIR.parent
LOCALIZATION_DIR = REPO_ROOT / 'localization'
OUTPUT_DIR = PACKAGING_DIR / 'Output'

# 安装脚本用的 settings.json 补丁片段：由 localization 的 settings.json 派生，
# 每次打包重新生成，避免上游改了势力名翻译后片段与之脱节。
SETTINGS_JSON = LOCALIZATION_DIR / 'data' / 'config' / 'settings.json'
DESIGN_TYPE_FRAGMENT = PACKAGING_DIR / 'settings_patch' / 'designTypeColors_zh.txt'
# 首尾标记须与 ss_translation_pack_installer.iss 中的常量一致。安装器靠这两个
# 标记定位旧块并整段替换——译文来自 ParaTranz，玩家更新汉化包时必须能刷新。
FRAGMENT_MARKER = '# CN-DESIGN-TYPE-COLORS'
FRAGMENT_END_MARKER = '# CN-DESIGN-TYPE-COLORS-END'

ISCC_SEARCH_PATHS = [
    r'C:\Program Files (x86)\Inno Setup 6\ISCC.exe',
    r'C:\Program Files\Inno Setup 6\ISCC.exe',
]


def create_argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description='生成 Starsector 中文 EXE 安装包。无参数时只显示本帮助。',
        epilog=(
            '示例：\n'
            '  python -X utf8 packaging/make_exe.py --package all\n'
            '  python -X utf8 packaging/make_exe.py --package translation\n'
            '  python -X utf8 packaging/make_exe.py --package full'
        ),
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument(
        '--package',
        choices=('all', 'translation', 'full'),
        required=True,
        metavar='{all,translation,full}',
        help='all=两种安装包，translation=仅独立汉化包，full=仅含游戏完整包',
    )
    return parser


def parse_args(argv: list[str]) -> argparse.Namespace:
    return create_argument_parser().parse_args(argv)


def find_iscc() -> Path:
    iscc_path = os.environ.get('ISCC_PATH', '')
    if iscc_path:
        p = Path(iscc_path)
        if p.exists():
            return p
        raise RuntimeError(f'.env 中 ISCC_PATH 指向的路径不存在：{iscc_path}')

    for candidate in ISCC_SEARCH_PATHS:
        p = Path(candidate)
        if p.exists():
            return p

    raise RuntimeError(
        '未找到 Inno Setup 编译器（ISCC.exe）。\n'
        '请安装 Inno Setup 6，或在 packaging/.env 中设置 ISCC_PATH=<路径>。'
    )


def run_iscc(iscc: Path, iss_file: Path, defines: dict[str, str]) -> None:
    define_args = [f'/D{k}={v}' for k, v in defines.items()]
    output_dir_arg = f'/O{OUTPUT_DIR}'
    cmd = [str(iscc), output_dir_arg, *define_args, str(iss_file)]

    print(f'\n编译: {iss_file.name}')
    print(f'  输出目录: {OUTPUT_DIR}')
    for k, v in defines.items():
        print(f'  /D{k}={v}')

    result = subprocess.run(cmd, cwd=PACKAGING_DIR)
    if result.returncode != 0:
        raise RuntimeError(f'ISCC 编译失败，返回码 {result.returncode}')


def generate_design_type_fragment() -> None:
    """从 localization 的 settings.json 提取 designTypeColors 里的中文条目，
    生成安装脚本要插入的片段。

    安装时不整体覆盖 settings.json（会丢玩家自定义设置），而是就地补三处，
    其中中文颜色映射需要这份片段。片段必须与 settings.json 同源——势力名和
    设计类型名的译文来自 ParaTranz，上游一改，这里就得跟着变，故每次打包重新
    生成而非手工维护副本。

    提取方式是「块内所有含非 ASCII 字符的行」，不依赖中英条目的先后顺序。
    """
    if not SETTINGS_JSON.is_file():
        raise RuntimeError(f'未找到 {SETTINGS_JSON.relative_to(REPO_ROOT)}')
    src = SETTINGS_JSON.read_text(encoding='utf-8')

    start = src.find('"designTypeColors"')
    if start < 0:
        raise RuntimeError(
            'settings.json 中未找到 designTypeColors（游戏版本可能已变）'
        )
    brace = src.find('{', start)
    if brace < 0:
        raise RuntimeError('designTypeColors 之后未找到 {')
    depth = 0
    end = -1
    for i in range(brace, len(src)):
        if src[i] == '{':
            depth += 1
        elif src[i] == '}':
            depth -= 1
            if depth == 0:
                end = i
                break
    if end < 0:
        raise RuntimeError('settings.json 的 designTypeColors 块大括号不匹配')

    body = src[brace + 1 : end]
    cn_lines = [
        ln.rstrip() for ln in body.splitlines() if any(ord(ch) > 127 for ch in ln)
    ]
    if not cn_lines:
        raise RuntimeError('settings.json 的 designTypeColors 中未找到中文条目')
    # 片段若含 } 会提前闭合 designTypeColors 对象，插入后整个文件结构报废
    for ln in cn_lines:
        if '}' in ln or '{' in ln:
            raise RuntimeError(f'提取到的行含大括号，会破坏 JSON 结构：{ln.strip()}')

    # 片段会被原样插进玩家的 settings.json，故抬头只能用 # 注释（游戏的 JSON
    # 解析器接受）。首行标记同时供安装脚本做幂等判断。
    header = [
        '',
        f'\t\t{FRAGMENT_MARKER}  以下为汉化包安装时插入，请勿手动编辑此段',
        '\t\t# ',
        '\t\t# 本段由 packaging/make_exe.py 从 localization/data/config/settings.json',
        '\t\t# 的 designTypeColors 中自动提取（取其中含非 ASCII 字符的行），每次打包',
        '\t\t# 重新生成。译文源自 ParaTranz，改动请在平台上进行，勿改此处或片段文件',
        '\t\t# packaging/settings_patch/designTypeColors_zh.txt —— 两者都会被覆盖。',
        '\t\t# ',
        '\t\t# 安装器不整体覆盖 settings.json（会丢玩家自定义设置），只就地补三处：',
        '\t\t# cjkMode、showCNTranslationCredits，以及这段中文设计类型颜色映射。',
        '\t\t# 重装或升级汉化包时，首尾标记之间的内容会被整段替换。',
        '',
    ]
    footer = ['', f'\t\t{FRAGMENT_END_MARKER}', '\t\t']
    # 行尾统一 CRLF，与 settings.json 保持一致
    text = '\n'.join(header + cn_lines + footer)
    text = text.replace('\r\n', '\n').replace('\n', '\r\n')

    DESIGN_TYPE_FRAGMENT.parent.mkdir(parents=True, exist_ok=True)
    old = DESIGN_TYPE_FRAGMENT.read_bytes() if DESIGN_TYPE_FRAGMENT.is_file() else b''
    new = text.encode('utf-8')
    DESIGN_TYPE_FRAGMENT.write_bytes(new)
    status = '内容未变' if old == new else ('已更新' if old else '已创建')
    print(
        f'settings.json 补丁片段：{len(cn_lines)} 个中文条目，{status}'
        f'（{DESIGN_TYPE_FRAGMENT.relative_to(REPO_ROOT)}）'
    )


def build_installers(args: argparse.Namespace) -> None:
    load_env(PACKAGING_DIR / '.env')
    metadata = load_package_metadata(REPO_ROOT, LOCALIZATION_DIR)
    version = metadata.version
    game_version = metadata.game_version

    build_translation = args.package in ('all', 'translation')
    build_full = args.package in ('all', 'full')
    game_folder: Path | None = None
    if build_full:
        original_game_folder = os.environ.get('ORIGINAL_GAME_FOLDER', '')
        if not original_game_folder:
            raise RuntimeError(
                '请求生成含游戏完整包，但 .env 未配置 ORIGINAL_GAME_FOLDER。'
            )
        game_folder = Path(original_game_folder)
        if not game_folder.is_dir():
            raise RuntimeError(f'原版游戏目录不存在：{game_folder}')
        validate_original_game_folder(game_folder, game_version)

    generate_design_type_fragment()

    branch = metadata.branch
    variant = metadata.variant
    if metadata.used_fallback_variant:
        print(
            f'警告：当前分支 "{branch}" 没有对应的变体名'
            f'（BRANCH_VARIANT_{branch} 未配置），回退到 master 变体。'
        )

    include_date = read_env_bool('INCLUDE_DATE', default=False)
    output_suffix = f' {date.today().strftime("%Y.%m.%d")}' if include_date else ''

    iscc = find_iscc()
    print(f'Inno Setup: {iscc}')
    print(
        f'版本: {version}  游戏版本: {game_version}  变体: {variant or "(无)"}  '
        f'分支: {branch}  日期后缀: {output_suffix or "(无)"}'
    )
    print(f'打包类型: {args.package}')

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

    common_defines = {
        'MyAppVersion': version,
        'GameVersion': game_version,
        'TranslationPackVarient': variant,
        'OutputSuffix': output_suffix,
    }

    if build_translation:
        pack_iss = PACKAGING_DIR / 'ss_translation_pack_installer.iss'
        run_iscc(iscc, pack_iss, common_defines)

    if build_full:
        assert game_folder is not None
        with_game_iss = PACKAGING_DIR / 'ss_translation_pack_with_game_installer.iss'
        with_game_defines = {**common_defines, 'OriginalGameFolder': str(game_folder)}
        run_iscc(iscc, with_game_iss, with_game_defines)

    print(f'\n完成，输出目录：{OUTPUT_DIR}')


def main(argv: list[str] | None = None) -> int:
    arguments = list(sys.argv[1:] if argv is None else argv)
    parser = create_argument_parser()
    if not arguments:
        parser.print_help()
        return 0
    args = parser.parse_args(arguments)
    try:
        build_installers(args)
    except RuntimeError as exc:
        print(f'错误：{exc}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
