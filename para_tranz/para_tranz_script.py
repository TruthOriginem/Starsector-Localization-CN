import sys
from os.path import abspath, dirname

# 直接执行文件时使用当前仓库；安装后的命令入口无需修改搜索路径。
if __name__ == '__main__' and not __package__:
    sys.path.insert(0, dirname(dirname(abspath(__file__))))

from para_tranz.config import ENABLED_LOADERS
from para_tranz.csv_loader.csv_file import CsvFile
from para_tranz.jar_loader.jar_file import JavaJarFile
from para_tranz.java_loader.java_file import JavaSourceFile
from para_tranz.json_loader.json_file import JsonFile
from para_tranz.txt_loader.txt_file import TxtFile
from para_tranz.utils.cli_args import dispatch_command, parse_args
from para_tranz.utils.mapping import PARA_TRANZ_MAP
from para_tranz.utils.mapping_generation import (
    generate_class_file_mapping_by_path,
    print_class_mapping_result,
)
from para_tranz.utils.paratranz_api import download_paratranz_export
from para_tranz.utils.search import print_search_results, search_for_string_in_jar_files
from para_tranz.utils.util import DataFile, configure_logging, make_logger

logger = make_logger('ParaTranzScript')

_LOADER_MAP = {
    'jar': JavaJarFile,
    'csv': CsvFile,
    'json': JsonFile,
    'txt': TxtFile,
    'java': JavaSourceFile,
}
loaders = [_LOADER_MAP[name] for name in ENABLED_LOADERS if name in _LOADER_MAP]


def game_to_paratranz() -> None:
    for Loader in loaders:
        DataFile.save_json_files(Loader.load_files_from_config())
    logger.info('ParaTranz 词条导出完成')


def paratranz_to_game(rebuild_jars: bool = False) -> None:
    if rebuild_jars:
        logger.info(
            '已启用 jar 重建导入，将以 original 中的预处理 jar 为基底生成汉化文件'
        )
    for Loader in loaders:
        if rebuild_jars and Loader is JavaJarFile:
            JavaJarFile.rebuild_from_config()
            continue
        for file in Loader.load_files_from_config():
            file.update_from_json()
            file.save_file()
    logger.info('ParaTranz 词条导入到译文数据完成')


def download_and_import_from_paratranz(rebuild_jars: bool = False) -> None:
    success = download_paratranz_export()
    if success:
        paratranz_to_game(rebuild_jars=rebuild_jars)
        game_to_paratranz()


def gen_mapping_by_class_path(class_path: str | None = None) -> None:
    print('请输入java jar文件及其中类文件的路径，以生成类文件映射项')
    print('例如：starfarer.api.jar:com/fs/starfarer/api/campaign/FleetAssignment.class')
    print('例如：com.fs.starfarer.api.campaign.FleetAssignment')

    if class_path is None:
        class_path = input('类文件路径：')
    else:
        print(f'类文件路径：{class_path}')
    result = generate_class_file_mapping_by_path(class_path)
    print_class_mapping_result(result)
    logger.info('类文件映射项生成完成')


def format_map() -> None:
    merged = PARA_TRANZ_MAP.format()
    PARA_TRANZ_MAP.save()
    logger.info(f'map 格式化完成，合并了 {merged} 个重复条目')


def search_string_in_jar_files(pattern: str | None = None) -> None:
    if pattern is None:
        pattern = input('请输入要查找的字符串：')
    else:
        print(f'查找字符串：{pattern}')
    results = search_for_string_in_jar_files(pattern.strip())
    print_search_results(results)
    logger.info('字符串查找完成')


def main() -> None:
    configure_logging()
    # 支持通过命令行参数直接指定操作，跳过交互式菜单
    # 用法：uv run paratranz [1|2|3|4|5|6] [参数]
    if len(sys.argv) > 1:
        option = sys.argv[1]
    else:
        print('欢迎使用 远行星号 ParaTranz 词条导入导出工具')
        print('请选择您要进行的操作：')
        print('1 - 从原始(original)和汉化(localization)文件导出 ParaTranz 词条')
        print('2 - 将 ParaTranz 词条写回汉化(localization)文件')
        print(
            '3 - 从 ParaTranz 平台下载最新导出并写回汉化文件（需要在 .env 中配置 API Key）'
        )
        print(
            '4 - 对指定类文件，生成包含所有string的类文件映射项(用于添加新类到para_tranz_map.json)'
        )
        print('5 - 在所有jar文件中查找指定原文字符串')
        print('6 - 对 para_tranz_map.json 进行格式化（去重、排序）')
        print(
            '从原文 jar 重新生成汉化：uv run paratranz 2 --rebuild-jars'
        )
        # 7 - jar版本迁移（未实现）
        option = input('请输入选项数字：')

    non_interactive = len(sys.argv) > 1
    actions = {
        '1': game_to_paratranz,
        '2': paratranz_to_game,
        '3': download_and_import_from_paratranz,
        '4': gen_mapping_by_class_path,
        '5': search_string_in_jar_files,
        '6': format_map,
    }
    while True:
        try:
            command = parse_args(sys.argv[1:] if non_interactive else [option])
        except ValueError as e:
            if non_interactive:
                logger.error(str(e))
                sys.exit(1)
            print('无效选项！')
            option = input('请输入选项数字：')
            continue
        if not non_interactive and option in ('4', '5'):
            while True:
                dispatch_command(command, actions)
        dispatch_command(command, actions)
        break

    if non_interactive:
        logger.info('程序执行完毕')
    else:
        logger.info('程序执行完毕，请按回车键退出')
        input()


if __name__ == '__main__':
    main()
