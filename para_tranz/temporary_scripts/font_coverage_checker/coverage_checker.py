# 将父级目录加入到环境变量中，以便从命令行中运行本脚本
import sys
from os.path import abspath, dirname
from typing import Set

from para_tranz.config import PROJECT_DIRECTORY
from para_tranz.csv_loader.csv_file import CsvFile
from para_tranz.jar_loader.jar_file import JavaJarFile
from para_tranz.utils.util import make_logger

sys.path.append(dirname(dirname(dirname(dirname(abspath(__file__))))))

USED_CHARS_PATH = (
    PROJECT_DIRECTORY
    / 'para_tranz'
    / 'temporary_scripts'
    / 'font_coverage_checker'
    / 'used_chars.txt'
)

logger = make_logger('FontCoverageChecker')
loaders = [JavaJarFile, CsvFile]


def collect_used_chars() -> Set[str]:
    used_chars = set()
    for Loader in loaders:
        for file in Loader.load_files_from_config():
            strings = file.get_strings()
            for string in strings:
                for char in string.original + string.translation:
                    used_chars.add(char)
    return used_chars


if __name__ == '__main__':
    logger.info('开始收集译文中使用的字符')
    used_chars = collect_used_chars()
    logger.info(f'收集完成，共找到{len(used_chars)}个字符')

    sorted_chars = sorted(used_chars, key=ord)
    with open(USED_CHARS_PATH, 'w', encoding='utf-8') as f:
        f.write('\n'.join(sorted_chars))
    logger.info(f'已输出到 {USED_CHARS_PATH}')
