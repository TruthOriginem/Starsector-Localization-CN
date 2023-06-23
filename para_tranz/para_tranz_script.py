import logging

from para_tranz.csv_loader.csv_loader import CsvFile
from para_tranz.jar_loader.jar_loader import JavaJarFile
from para_tranz.util import make_logger

logger = make_logger('para_tranz_script.py')

loaders = [JavaJarFile]

def csv_to_paratranz():
    for Loader in loaders:
        for file in Loader.load_files():
            file.save_json()
    logger.info('ParaTranz 词条导出完成')


def paratranz_to_csv():
    for Loader in loaders:
        for file in Loader.load_files():
            file.update_from_json()
            file.save_file()
    logger.info('ParaTranz 词条导入到译文数据完成')


if __name__ == '__main__':
    print('欢迎使用 远行星号 ParaTranz 词条导入导出工具')
    print('请选择您要进行的操作：')
    print('1 - 从原始和汉化文件导出 ParaTranz 词条')
    print('2 - 将 ParaTranz 词条写回汉化文件(localization)')

    while True:
        option = input('请输入选项数字：')
        if option == '1':
            csv_to_paratranz()
            break
        elif option == '2':
            paratranz_to_csv()
            break
        else:
            print('无效选项！')

    logger.info("程序执行完毕，请按回车键退出")
    input()
