# 将父级目录加入到环境变量中，以便从命令行中运行本脚本
import sys
from os.path import abspath, dirname
from pathlib import Path
from typing import Set

from para_tranz.csv_loader.csv_file import CsvFile
from para_tranz.jar_loader.jar_file import JavaJarFile
from para_tranz.utils.config import PROJECT_DIRECTORY
from para_tranz.utils.util import make_logger

sys.path.append(dirname(dirname(dirname(dirname(abspath(__file__))))))

CHAR_LIST_PATH = (
    PROJECT_DIRECTORY
    / 'para_tranz'
    / 'temporary_scripts'
    / 'font_coverage_checker'
    / 'characters.txt'
)

logger = make_logger('FontCoverageChecker')
loaders = [JavaJarFile, CsvFile]

chars_ignored = {
    'a',
    'b',
    'c',
    'd',
    'e',
    'f',
    'g',
    'h',
    'i',
    'j',
    'k',
    'l',
    'm',
    'n',
    'o',
    'p',
    'q',
    'r',
    's',
    't',
    'u',
    'v',
    'w',
    'x',
    'y',
    'z',
    'A',
    'B',
    'C',
    'D',
    'E',
    'F',
    'G',
    'H',
    'I',
    'J',
    'K',
    'L',
    'M',
    'N',
    'O',
    'P',
    'Q',
    'R',
    'S',
    'T',
    'U',
    'V',
    'W',
    'X',
    'Y',
    'Z',
    '0',
    '1',
    '2',
    '3',
    '4',
    '5',
    '6',
    '7',
    '8',
    '9',
    ' ',
    '\t',
    '\n',
    '\r',
}


def load_charset(char_list_path: str | Path = CHAR_LIST_PATH) -> Set[str]:
    with open(char_list_path, 'r', encoding='utf-16') as f:
        char_list = f.read().splitlines()
    return set([char.strip() for char in char_list])


def find_missing_chars(charset: Set[str]) -> Set[str]:
    missing_chars = set()
    for Loader in loaders:
        for file in Loader.load_files_from_config():
            strings = file.get_strings()
            for string in strings:
                for char in string.translation:
                    if char not in chars_ignored and char not in charset:
                        missing_chars.add(char)
                        logger.debug(f'找到缺失字符：{char}')
    return missing_chars


if __name__ == '__main__':
    logger.info('开始加载已覆盖字符集')
    charset = load_charset()
    logger.info(f'已加载字符集，共{len(charset)}个字符')
    logger.info('开始查找缺失字符')
    missing_chars = find_missing_chars(charset)
    logger.info(f'查找完成，共找到{len(missing_chars)}个缺失字符')

    for c in sorted(list(missing_chars)):
        print(c)
