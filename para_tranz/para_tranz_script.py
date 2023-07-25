from para_tranz.csv_loader.csv_file import CsvFile
from para_tranz.jar_loader.jar_file import JavaJarFile
from para_tranz.utils.util import make_logger

logger = make_logger('ParaTranzScript')

# 选择要处理的文件类型
# loaders = [JavaJarFile]
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


def mian():
    print('欢迎使用 远行星号 ParaTranz 词条导入导出工具')
    print('请选择您要进行的操作：')
    print('1 - 从原始(original)和汉化(localization)文件导出 ParaTranz 词条')
    print('2 - 将 ParaTranz 词条写回汉化(localization)文件')
    # TODO: jar版本迁移还没写好
    # print('3 - 将 ParaTranz 词条写回新版本游戏的汉化(localization)文件（版本迁移时使用，主要针对jar文件）')

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
        else:
            print('无效选项！')

    logger.info("程序执行完毕，请按回车键退出")
    input()


if __name__ == '__main__':
    # 如果是main就执行mian捏
    mian()
