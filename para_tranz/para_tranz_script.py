import sys
from os.path import abspath, dirname

# 将父级目录加入到环境变量中，以便从命令行中运行本脚本
sys.path.append(dirname(dirname(abspath(__file__))))

from para_tranz.csv_loader.csv_file import CsvFile
from para_tranz.jar_loader.jar_file import JavaJarFile
from para_tranz.utils.mapping_generation import (
    generate_class_file_mapping_by_path,
    generate_class_mapping_diff_string,
)
from para_tranz.utils.search import search_for_string_in_jar_files
from para_tranz.utils.util import BG_YELLOW, GREEN, RED, colorize, make_logger

logger = make_logger('ParaTranzScript')

# 选择要处理的文件类型
# loaders = [JavaJarFile]
# loaders = [CsvFile]
loaders = [JavaJarFile, CsvFile]


def game_to_paratranz() -> None:
    for Loader in loaders:
        for file in Loader.load_files_from_config():
            file.save_json()
    logger.info('ParaTranz 词条导出完成')


def paratranz_to_game() -> None:
    for Loader in loaders:
        for file in Loader.load_files_from_config():
            file.update_from_json()
            file.save_file()
    logger.info('ParaTranz 词条导入到译文数据完成')


def paratranz_to_game_new_version() -> None:
    for Loader in loaders:
        for file in Loader.load_files_from_config():
            file.update_from_json(version_migration=True)
            file.save_file()


def gen_mapping_by_class_path(class_path: str | None = None) -> None:
    print('请输入java jar文件及其中类文件的路径，以生成类文件映射项')
    print('例如：starfarer.api.jar:com/fs/starfarer/api/campaign/FleetAssignment.class')
    print('例如：com.fs.starfarer.api.campaign.FleetAssignment')

    if class_path is None:
        class_path = input('类文件路径：')
    else:
        print(f'类文件路径：{class_path}')
    result = generate_class_file_mapping_by_path(class_path)

    if result:
        jar_item, class_item, existing_class_item, extra_ref_strings = result
        print('所属jar文件：', jar_item.path if jar_item else '未知')
        print('以下是生成的类文件映射项：')
        print(class_item.as_json())

        # 如果在 para_tranz_map.json 中找到了已有的类文件映射项，那么就可以生成对比信息
        if existing_class_item:
            print(
                f'以下是与当前存在的映射项的对比'
                f'（{colorize("绿色", GREEN)}=已包含  '
                f'{colorize("红色", RED)}=已排除  '
                f'无色=未包含  '
                f'{colorize("黄色背景", BG_YELLOW)}=同时被非string属性引用，无法自动写回）：'
            )
            print(
                generate_class_mapping_diff_string(
                    existing_class_item, class_item, extra_ref_strings
                )
            )
        else:
            print('此类未包含在当前映射表中')

    logger.info('类文件映射项生成完成')


def search_string_in_jar_files(pattern: str | None = None) -> None:
    if pattern is None:
        pattern = input('请输入要查找的字符串：')
    else:
        print(f'查找字符串：{pattern}')
    result = search_for_string_in_jar_files(pattern.strip())

    if not result:
        print('未找到任何结果')
        return
    else:
        for r in result:
            print(r)

    logger.info('字符串查找完成')


def mian() -> None:
    # 支持通过命令行参数直接指定操作，跳过交互式菜单
    # 用法：python para_tranz_script.py [1|2|4|5]
    if len(sys.argv) > 1:
        option = sys.argv[1]
    else:
        print('欢迎使用 远行星号 ParaTranz 词条导入导出工具')
        print('请选择您要进行的操作：')
        print('1 - 从原始(original)和汉化(localization)文件导出 ParaTranz 词条')
        print('2 - 将 ParaTranz 词条写回汉化(localization)文件')
        # TODO: jar版本迁移还没写好
        # print('3 - 将 ParaTranz 词条写回新版本游戏的汉化(localization)文件（版本迁移时使用，主要针对jar文件）')
        print(
            '4 - 对指定类文件，生成包含所有string的类文件映射项(用于添加新类到para_tranz_map.json)'
        )
        print('5 - 在所有jar文件中查找指定原文字符串')
        option = input('请输入选项数字：')

    non_interactive = len(sys.argv) > 1
    arg2 = sys.argv[2] if len(sys.argv) > 2 else None
    while True:
        if option == '1':
            game_to_paratranz()
            break
        elif option == '2':
            paratranz_to_game()
            break
        # elif option == '3':
        #     paratranz_to_game_new_version()
        #     break
        elif option == '4':
            if non_interactive:
                gen_mapping_by_class_path(arg2)
            else:
                while True:
                    gen_mapping_by_class_path()
            break
        elif option == '5':
            if non_interactive:
                search_string_in_jar_files(arg2)
            else:
                while True:
                    search_string_in_jar_files()
            break
        else:
            if non_interactive:
                print(f'无效选项：{option}')
                sys.exit(1)
            print('无效选项！')
            option = input('请输入选项数字：')

    if non_interactive:
        logger.info('程序执行完毕')
    else:
        logger.info('程序执行完毕，请按回车键退出')
        input()


if __name__ == '__main__':
    mian()
