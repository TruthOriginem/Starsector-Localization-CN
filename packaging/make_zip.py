"""制作汉化 ZIP 包，参数与 make_exe.py 一致。

用法：python -X utf8 packaging/make_zip.py --package all|translation|full
配置见 packaging/.env.example；日期默认关闭，输出名称与 EXE 仅扩展名不同。
独立包保留 localization/；完整包将汉化覆盖到原版的 starsector-core/。
"""

import argparse
import os
import sys
import tempfile
import zipfile
from datetime import date
from pathlib import Path

from game_integrity import validate_original_game_folder
from package_utils import load_env, load_package_metadata, read_env_bool

PACKAGING_DIR = Path(__file__).parent
REPO_ROOT = PACKAGING_DIR.parent
LOCALIZATION_DIR = REPO_ROOT / 'localization'
OUTPUT_DIR = PACKAGING_DIR / 'Output'


def create_argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description='生成 Starsector 中文 ZIP 包。无参数时只显示本帮助。',
        epilog=(
            '示例：\n'
            '  python -X utf8 packaging/make_zip.py --package all\n'
            '  python -X utf8 packaging/make_zip.py --package translation\n'
            '  python -X utf8 packaging/make_zip.py --package full'
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


def collect_entries(game_folder: Path | None) -> dict[str, Path]:
    """合并原版与汉化路径，避免 ZIP 内出现同名条目，并保留空目录。"""
    entries: dict[str, Path] = {}
    if game_folder is not None:
        for path in sorted(game_folder.rglob('*')):
            entries[path.relative_to(game_folder).as_posix()] = path
    prefix = 'starsector-core' if game_folder is not None else 'localization'
    for path in sorted(LOCALIZATION_DIR.rglob('*')):
        relative = path.relative_to(LOCALIZATION_DIR)
        if 'rules分段' in relative.parts:
            continue
        entries[f'{prefix}/{relative.as_posix()}'] = path
    return entries


def build_zip(args: argparse.Namespace) -> list[Path]:
    load_env(PACKAGING_DIR / '.env')
    metadata = load_package_metadata(REPO_ROOT, LOCALIZATION_DIR)
    version = metadata.version
    game_version = metadata.game_version
    variant = metadata.variant
    if metadata.used_fallback_variant:
        print(
            f'警告：当前分支 "{metadata.branch}" 没有对应的变体名'
            f'（BRANCH_VARIANT_{metadata.branch} 未配置），回退到 master 变体。'
        )

    game_folder: Path | None = None
    if args.package in ('all', 'full'):
        original_game_folder = os.environ.get('ORIGINAL_GAME_FOLDER', '')
        if not original_game_folder:
            raise RuntimeError(
                '请求生成含游戏完整包，但 .env 未配置 ORIGINAL_GAME_FOLDER。'
            )
        game_folder = Path(original_game_folder)
        if not game_folder.is_dir():
            raise RuntimeError(f'原版游戏目录不存在：{game_folder}')
        validate_original_game_folder(game_folder, game_version)

    if not LOCALIZATION_DIR.is_dir():
        raise RuntimeError(f'汉化目录不存在：{LOCALIZATION_DIR}')
    include_date = read_env_bool('INCLUDE_DATE', default=False)
    suffix = f' {date.today().strftime("%Y.%m.%d")}' if include_date else ''
    packages = ('translation', 'full') if args.package == 'all' else (args.package,)
    outputs = []
    for package in packages:
        label = '独立汉化包' if package == 'translation' else '中文汉化版'
        name = (
            f'Starsector(远行星号) {game_version} {label}{variant}'
            f' v{version}{suffix} [远星汉化组].zip'
        )
        entries = collect_entries(game_folder if package == 'full' else None)
        outputs.append(write_zip(name, entries))
    return outputs


def write_zip(zip_name: str, entries: dict[str, Path]) -> Path:
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
            compresslevel=6,
        ) as archive:
            for arcname, file in sorted(entries.items()):
                archive.write(file, arcname)
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
    try:
        build_zip(args)
    except RuntimeError as exc:
        print(f'错误：{exc}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
