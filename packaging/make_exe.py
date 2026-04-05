"""
制作汉化安装包（.exe）
调用 Inno Setup 编译 .iss 脚本，根据当前 git 分支自动选择变体名称。

输出文件名由 ISS 脚本的 OutputBaseFilename 决定，格式如：
  Starsector(远行星号) 0.98a-RC8 独立汉化包(黑体版) 1.0.0 [远星汉化组].exe
  Starsector(远行星号) 0.98a-RC8 中文汉化版(黑体版) 1.0.0 [远星汉化组].exe

配置（在 packaging/.env 中设置，参考 packaging/.env.example）：
  ISCC_PATH                    - Inno Setup 6 编译器路径，留空则自动搜索常见安装位置（不支持 Inno Setup 5）
  ORIGINAL_GAME_FOLDER         - 原版游戏文件夹路径，用于制作含游戏完整安装包；
                                 留空或路径不存在则跳过 with_game 版本
  GAME_VERSION                 - 覆盖游戏版本号（留空则从 localization_version.json 读取）
  APP_VERSION                  - 覆盖汉化版本号（留空则从 localization_version.json 读取）
  BRANCH_VARIANT_<分支名>      - 各分支对应的变体名，如：
                                 BRANCH_VARIANT_master=(黑体版)
                                 BRANCH_VARIANT_font-simsong=(宋体版)
"""

import json
import os
import subprocess
import sys
from pathlib import Path

PACKAGING_DIR = Path(__file__).parent
REPO_ROOT = PACKAGING_DIR.parent
LOCALIZATION_DIR = REPO_ROOT / 'localization'
OUTPUT_DIR = PACKAGING_DIR / 'Output'

ISCC_SEARCH_PATHS = [
    r'C:\Program Files (x86)\Inno Setup 6\ISCC.exe',
    r'C:\Program Files\Inno Setup 6\ISCC.exe',
]


def load_env() -> None:
    env_file = PACKAGING_DIR / '.env'
    if not env_file.exists():
        return
    for line in env_file.read_text(encoding='utf-8').splitlines():
        line = line.strip()
        if not line or line.startswith('#') or '=' not in line:
            continue
        key, _, value = line.partition('=')
        os.environ.setdefault(key.strip(), value.strip())


def find_iscc() -> Path:
    iscc_path = os.environ.get('ISCC_PATH', '')
    if iscc_path:
        p = Path(iscc_path)
        if p.exists():
            return p
        print(f'错误：.env 中 ISCC_PATH 指向的路径不存在：{iscc_path}', file=sys.stderr)
        sys.exit(1)

    for candidate in ISCC_SEARCH_PATHS:
        p = Path(candidate)
        if p.exists():
            return p

    print(
        '错误：未找到 Inno Setup 编译器（ISCC.exe）。\n'
        '请安装 Inno Setup 6，或在 packaging/.env 中设置 ISCC_PATH=<路径>。',
        file=sys.stderr,
    )
    sys.exit(1)


def get_git_branch() -> str:
    result = subprocess.run(
        ['git', 'rev-parse', '--abbrev-ref', 'HEAD'],
        capture_output=True,
        text=True,
        cwd=REPO_ROOT,
    )
    return result.stdout.strip()


def run_iscc(iscc: Path, iss_file: Path, defines: dict[str, str]) -> bool:
    define_args = [f'/D{k}={v}' for k, v in defines.items()]
    output_dir_arg = f'/O{OUTPUT_DIR}'
    cmd = [str(iscc), output_dir_arg, *define_args, str(iss_file)]

    print(f'\n编译: {iss_file.name}')
    print(f'  输出目录: {OUTPUT_DIR}')
    for k, v in defines.items():
        print(f'  /D{k}={v}')

    result = subprocess.run(cmd, cwd=PACKAGING_DIR)
    if result.returncode != 0:
        print(f'错误：ISCC 编译失败，返回码 {result.returncode}', file=sys.stderr)
        return False
    return True


def main() -> None:
    load_env()

    version_file = LOCALIZATION_DIR / 'localization_version.json'
    info = json.loads(version_file.read_text(encoding='utf-8'))
    version: str = os.environ.get('APP_VERSION', '') or info['version']
    game_version: str = os.environ.get('GAME_VERSION', '') or info['game_version']

    branch = get_git_branch()
    variant = os.environ.get(f'BRANCH_VARIANT_{branch}', '')
    if not variant:
        fallback = os.environ.get('BRANCH_VARIANT_master', '')
        print(f'警告：当前分支 "{branch}" 没有对应的变体名（BRANCH_VARIANT_{branch} 未配置），回退到 master 变体。')
        variant = fallback

    iscc = find_iscc()
    print(f'Inno Setup: {iscc}')
    print(f'版本: {version}  游戏版本: {game_version}  变体: {variant or "(无)"}  分支: {branch}')

    OUTPUT_DIR.mkdir(exist_ok=True)

    common_defines = {
        'MyAppVersion': version,
        'GameVersion': game_version,
        'TranslationPackVarient': variant,
    }

    # 独立汉化包
    pack_iss = PACKAGING_DIR / 'ss_translation_pack_installer.iss'
    if not run_iscc(iscc, pack_iss, common_defines):
        sys.exit(1)

    # 含游戏完整安装包（可选）
    original_game_folder = os.environ.get('ORIGINAL_GAME_FOLDER', '')
    game_folder = Path(original_game_folder) if original_game_folder else None
    if not game_folder or not game_folder.exists():
        if original_game_folder:
            print(f'\n警告：.env 中 ORIGINAL_GAME_FOLDER 路径不存在：{original_game_folder}，跳过含游戏安装包。')
        else:
            print('\n未配置 ORIGINAL_GAME_FOLDER，跳过含游戏安装包。')
    else:
        with_game_iss = PACKAGING_DIR / 'ss_translation_pack_with_game_installer.iss'
        with_game_defines = {**common_defines, 'OriginalGameFolder': str(game_folder)}
        if not run_iscc(iscc, with_game_iss, with_game_defines):
            sys.exit(1)

    print(f'\n完成，输出目录：{OUTPUT_DIR}')


if __name__ == '__main__':
    main()
