"""
制作汉化补丁压缩包
输出文件名格式：远行星号 {game_version} 汉化补丁 v{version} [{date}] {variant}

配置（在 packaging/.env 中设置，参考 packaging/.env.example）：
  GAME_VERSION                 - 覆盖游戏版本号（留空则从 localization_version.json 读取）
  APP_VERSION                  - 覆盖汉化版本号（留空则从 localization_version.json 读取）
  INCLUDE_DATE                 - 文件名是否包含日期后缀，true/false（默认 true）
  BRANCH_VARIANT_<分支名>      - 各分支对应的变体名，如：
                                 BRANCH_VARIANT_master=(黑体版)
                                 BRANCH_VARIANT_font-simsong=(宋体版)
"""

import json
import os
import re
import subprocess
import zipfile
from datetime import date
from pathlib import Path

PACKAGING_DIR = Path(__file__).parent
REPO_ROOT = PACKAGING_DIR.parent
LOCALIZATION_DIR = REPO_ROOT / 'localization'
OUTPUT_DIR = PACKAGING_DIR / 'Output'


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


def get_git_branch() -> str:
    result = subprocess.run(
        ['git', 'rev-parse', '--abbrev-ref', 'HEAD'],
        capture_output=True,
        text=True,
        cwd=REPO_ROOT,
    )
    return result.stdout.strip()


def format_game_version(raw: str) -> str:
    """'0.98a-RC8' -> '0.98 RC-8'"""
    m = re.match(r'(\d+\.\d+)[a-zA-Z]?-RC(\d+)', raw)
    if m:
        return f'{m.group(1)} RC-{m.group(2)}'
    return raw


def main() -> None:
    load_env()

    version_file = LOCALIZATION_DIR / 'localization_version.json'
    info = json.loads(version_file.read_text(encoding='utf-8'))
    version: str = os.environ.get('APP_VERSION', '') or info['version']
    game_version: str = format_game_version(
        os.environ.get('GAME_VERSION', '') or info['game_version']
    )

    branch = get_git_branch()
    variant = os.environ.get(f'BRANCH_VARIANT_{branch}', '')
    if not variant:
        fallback = os.environ.get('BRANCH_VARIANT_master', '')
        print(f'警告：当前分支 "{branch}" 没有对应的变体名（BRANCH_VARIANT_{branch} 未配置），回退到 master 变体。')
        variant = fallback

    include_date = os.environ.get('INCLUDE_DATE', 'true').lower() != 'false'
    today = date.today().strftime('%Y.%m.%d')

    name = f'远行星号 {game_version} 汉化补丁 v{version}'
    if include_date:
        name += f' {today}'
    if variant:
        name += f' {variant}'
    zip_name = name + '.zip'

    OUTPUT_DIR.mkdir(exist_ok=True)
    output_path = OUTPUT_DIR / zip_name

    print(f'打包中: {zip_name}')
    with zipfile.ZipFile(output_path, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as zf:
        for file in sorted(LOCALIZATION_DIR.rglob('*')):
            if file.is_file():
                arcname = file.relative_to(REPO_ROOT)
                zf.write(file, arcname)
                print(f'  {arcname}')

    size_mb = output_path.stat().st_size / 1024 / 1024
    print(f'完成: {output_path}  ({size_mb:.1f} MB)')


if __name__ == '__main__':
    main()
