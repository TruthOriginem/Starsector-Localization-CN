# 将父级目录加入到环境变量中，以便从命令行中运行本脚本
import json
import sys
from dataclasses import asdict
from os.path import abspath, dirname
sys.path.append(dirname(dirname(abspath(__file__))))

from para_tranz.utils.mapping import PARA_TRANZ_MAP
from para_tranz.csv_loader.csv_file import CsvFile
from para_tranz.jar_loader.jar_file import JavaJarFile
from para_tranz.utils.util import make_logger, SetEncoder

logger = make_logger('ParaTranzScript')

# 选择要处理的文件类型
# loaders = [JavaJarFile]
# loaders = [CsvFile]
loaders = [JavaJarFile, CsvFile]


def game_to_paratranz():
    for Loader in loaders:
        for file in Loader.load_files_from_config():
            file.save_json()
    logger.info('ParaTranz 词条导出完成')


def paratranz_to_game():
    for Loader in loaders:
        for file in Loader.load_files_from_config():
            file.update_from_json()
            file.save_file()
    logger.info('ParaTranz 词条导入到译文数据完成')


def paratranz_to_game_new_version():
    for Loader in loaders:
        for file in Loader.load_files_from_config():
            file.update_from_json(version_migration=True)
            file.save_file()

def generate_class_file_mapping():
    print('请输入java jar文件及其中类文件的路径，格式为 jar_file.jar:com/example/Example.class')
    print('例如：starfarer.api.jar:com/fs/starfarer/api/campaign/FleetAssignment.class')
    class_file_path = input('类文件路径：')

    result = PARA_TRANZ_MAP.get_jar_and_class_file_item(class_file_path, create=True, one_class_only=True)

    if not result:
        logger.error('生成类文件映射项失败')

    jar_item, class_item = result

    # 清空原有的字符串过滤条目，以输出所有字符串
    original_include_strings = class_item.include_strings
    class_item.include_strings = set()
    class_item.exclude_strings = set()

    jar_file = JavaJarFile(**asdict(jar_item))
    class_item_with_strings = jar_file.class_files[class_item.path].export_map_item()

    print('以下是生成的类文件映射项：')
    print(json.dumps(asdict(class_item_with_strings), indent=2, cls=SetEncoder))


def mian():
    print('欢迎使用 远行星号 ParaTranz 词条导入导出工具')
    print('请选择您要进行的操作：')
    print('1 - 从原始(original)和汉化(localization)文件导出 ParaTranz 词条')
    print('2 - 将 ParaTranz 词条写回汉化(localization)文件')
    # TODO: jar版本迁移还没写好
    # print('3 - 将 ParaTranz 词条写回新版本游戏的汉化(localization)文件（版本迁移时使用，主要针对jar文件）')
    print('4 - 对指定类文件，生成包含所有string的类文件映射项(用于添加新类到para_tranz_map.json)')

    while True:
        option = input('请输入选项数字：')
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
            generate_class_file_mapping()
            break
        else:
            print('无效选项！')

    logger.info("程序执行完毕，请按回车键退出")
    input()


if __name__ == '__main__':
    # 如果是main就执行mian捏
    mian()
