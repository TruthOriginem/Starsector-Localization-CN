"""
制作汉化补丁压缩包
输出文件名格式：远行星号 {game_version} 汉化补丁 v{version} [{date}] {variant}

用法：
  python -X utf8 packaging/make_zip.py build
  python -X utf8 packaging/make_zip.py build --include-date
  python -X utf8 packaging/make_zip.py build --no-date

配置（在 packaging/.env 中设置，参考 packaging/.env.example）：
  GAME_VERSION                 - 覆盖游戏版本号（留空则从 localization_version.json 读取）
  APP_VERSION                  - 覆盖汉化版本号（留空则从 localization_version.json 读取）
  INCLUDE_DATE                 - 文件名是否包含日期后缀，true/false（默认 true）
  BRANCH_VARIANT_<分支名>      - 各分支对应的变体名，如：
                                 BRANCH_VARIANT_master=(黑体版)
                                 BRANCH_VARIANT_font-simsong=(宋体版)
"""

import argparse
import os
import re
import sys
import tempfile
import zipfile
from datetime import date
from pathlib import Path

from package_utils import load_env, load_package_metadata, read_env_bool

PACKAGING_DIR = Path(__file__).parent
REPO_ROOT = PACKAGING_DIR.parent
LOCALIZATION_DIR = REPO_ROOT / 'localization'
OUTPUT_DIR = PACKAGING_DIR / 'Output'


def compression_level(value: str) -> int:
    try:
        level = int(value)
    except ValueError as exc:
        raise argparse.ArgumentTypeError('压缩级别必须是 0 到 9 的整数') from exc
    if not 0 <= level <= 9:
        raise argparse.ArgumentTypeError('压缩级别必须是 0 到 9 的整数')
    return level


def create_argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description='生成 Starsector 中文汉化 ZIP 补丁。无参数时只显示本帮助。',
        epilog=(
            '示例：\n'
            '  python -X utf8 packaging/make_zip.py build\n'
            '  python -X utf8 packaging/make_zip.py build --no-date\n'
            '  python -X utf8 packaging/make_zip.py build --compression-level 9'
        ),
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    subparsers = parser.add_subparsers(dest='command', metavar='{build}')
    build = subparsers.add_parser(
        'build',
        help='把 localization 目录打包为 ZIP',
        description='把 localization 目录原子地写入一个 ZIP 汉化补丁。',
    )
    date_group = build.add_mutually_exclusive_group()
    date_group.add_argument(
        '--include-date',
        dest='include_date',
        action='store_true',
        default=None,
        help='文件名包含当天日期（覆盖 INCLUDE_DATE）',
    )
    date_group.add_argument(
        '--no-date',
        dest='include_date',
        action='store_false',
        help='文件名不包含日期（覆盖 INCLUDE_DATE）',
    )
    build.add_argument(
        '--compression-level',
        type=compression_level,
        default=6,
        metavar='0-9',
        help='Deflate 压缩级别，默认 6',
    )
    return parser


def format_game_version(raw: str) -> str:
    """'0.98a-RC8' -> '0.98 RC-8'"""
    m = re.match(r'(\d+\.\d+)[a-zA-Z]?-RC(\d+)', raw)
    if m:
        return f'{m.group(1)} RC-{m.group(2)}'
    return raw


def build_zip(args: argparse.Namespace) -> Path:
    load_env(PACKAGING_DIR / '.env')
    metadata = load_package_metadata(REPO_ROOT, LOCALIZATION_DIR)
    version = metadata.version
    game_version = format_game_version(metadata.game_version)
    variant = metadata.variant
    if metadata.used_fallback_variant:
        print(
            f'警告：当前分支 "{metadata.branch}" 没有对应的变体名'
            f'（BRANCH_VARIANT_{metadata.branch} 未配置），回退到 master 变体。'
        )

    include_date = (
        read_env_bool('INCLUDE_DATE', default=True)
        if args.include_date is None
        else args.include_date
    )
    today = date.today().strftime('%Y.%m.%d')

    name = f'远行星号 {game_version} 汉化补丁 v{version}'
    if include_date:
        name += f' {today}'
    if variant:
        name += f' {variant}'
    zip_name = name + '.zip'

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    output_path = OUTPUT_DIR / zip_name

    print(f'打包中: {zip_name}')
    temp_path: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            prefix=f'.{zip_name}.', suffix='.tmp', dir=OUTPUT_DIR, delete=False
        ) as temp_file:
            temp_path = Path(temp_file.name)
        with zipfile.ZipFile(
            temp_path,
            'w',
            zipfile.ZIP_DEFLATED,
            compresslevel=args.compression_level,
        ) as archive:
            for file in sorted(LOCALIZATION_DIR.rglob('*')):
                if file.is_file():
                    arcname = file.relative_to(REPO_ROOT)
                    archive.write(file, arcname)
                    print(f'  {arcname}')
        os.replace(temp_path, output_path)
    except (OSError, zipfile.BadZipFile) as exc:
        if temp_path is not None:
            temp_path.unlink(missing_ok=True)
        raise RuntimeError(f'ZIP 打包失败：{exc}') from exc

    size_mb = output_path.stat().st_size / 1024 / 1024
    print(f'完成: {output_path}  ({size_mb:.1f} MB)')
    return output_path


def main(argv: list[str] | None = None) -> int:
    arguments = list(sys.argv[1:] if argv is None else argv)
    parser = create_argument_parser()
    if not arguments:
        parser.print_help()
        return 0
    args = parser.parse_args(arguments)
    if args.command is None:
        parser.print_help()
        return 0
    try:
        build_zip(args)
    except RuntimeError as exc:
        print(f'错误：{exc}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
