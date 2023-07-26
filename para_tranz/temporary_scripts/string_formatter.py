# 设置日志输出
import json
import logging
import re
from pathlib import Path
from typing import List, Tuple

from para_tranz.para_tranz_script import CsvFile
from para_tranz.utils.config import PARA_TRANZ_PATH
from para_tranz.utils.util import make_logger, String, DataFile

logger = make_logger(f'StringFormatter')

# 要执行的替换
REGEX_REPLACEMENTS = [
    ('((?!级)[\u4e00-\u9fa5，。；：？！]) +([\u4e00-\u9fa5，。；：？！])(?!.{0,5}[-])', '$1$2'),
    ('([\u4e00-\u9fa5][!?,.;:])( )', '$1'),
    ('([\u4e00-\u9fa5\\[\\]()])!', '$1！'),
    ('([\u4e00-\u9fa5\\[\\]()])\\?', '$1？'),
    ('([\u4e00-\u9fa5\\[\\]()]),', '$1，'),
    ('([\u4e00-\u9fa5\\[\\]()])\\.(?!\\.)', '$1。'),
    ('([\u4e00-\u9fa5\\[\\]()]);', '$1；'),
    ('([\u4e00-\u9fa5\\[\\]()]):', '$1：'),
    ('[{}]', ' '),
    ('(?<=[\u4e00-\u9fa5，。；：？！])( ?)(\\$[a-zA-Z0-9_\\.]+)( ?)', ' $2 '),
    ('( ?)(\\$[a-zA-Z0-9_\\.]+)( ?)(?=[\u4e00-\u9fa5，。；：？！])', ' $2 '),
    (
        r'( ?)(\$.{0,6}([Hh]eOrShe|[Hh]isOrHer|[Hh]imOrHer|[Hh]imOrHerself|[Mm]anOrWoman|[Ss]hipOrFleet))( ?)',
        '$2'),
    ('[“”]', '"'),
    ('[‘’]', "'"),
    ('--+', "——"),
    ('( ?)——( ?)', ' —— '),
    (r'(（| ?\()', ' ('),
    (r'(）|\) ?)', ') '),
    (r'( ?\[)', ' ['),
    ('(] ?)', '] '),
    ('( +)', ' '),
    ('( ?)([，。；：？！])( ?)', '$2'),
    (r'([，。；：？！\[\]()"\'\.])( )([，。；：？！\[\]()\.])', '$1$3'),
    (r'([，。；：？！\[\]()\.])( )([，。；：？！\[\]()"\'\.])', '$1$3'),
    ('(^ |(?<=\n) )', ''),
    ('( $| (?=\n))', ''),
]


class ParatranzJsonFile:
    def __init__(self, path: Path):
        self.path = path
        self.strings: List[String] = []

    def load(self):
        self.strings = DataFile.read_json_strings(self.path)

    def save(self):
        DataFile.write_json_strings(self.path, self.strings)


def load_json_files(pattern: str) -> List[ParatranzJsonFile]:
    files = []

    for path_object in PARA_TRANZ_PATH.glob(pattern):
        if path_object.is_file() and path_object.suffix == ".json":
            file = ParatranzJsonFile(path_object)
            file.load()
            files.append(file)

    return files


def apply_rules(rules: List[Tuple[str, str]], file: ParatranzJsonFile) -> None:
    logger.info(f"正在处理 '{file.path.relative_to(PARA_TRANZ_PATH)}'")
    rule_count = 1
    for regex, replacement in [(r[0], r[1].replace('$', '\\')) for r in rules]:
        logger.info(f"\t应用规则 #{rule_count} '{regex}' -> '{replacement}'")
        replace_count = 0
        for string in file.strings:
            new_translation = re.sub(regex, replacement, string.translation)
            if new_translation != string.translation:
                string.translation = new_translation
                replace_count += 1
        logger.info(f"\t规则 #{rule_count} 应用完毕，替换了 {replace_count} 条词条内容")
        rule_count += 1
    logger.info(f"处理完毕 '{file.path.relative_to(PARA_TRANZ_PATH)}'")


if __name__ == '__main__':
    pattern = input("请输入要处理的文件名匹配模式(默认为 '**/*')：")
    files = load_json_files(pattern if pattern else '**/*')
    for file in files:
        logger.info(f"找到文件 '{file.path.relative_to(PARA_TRANZ_PATH)}'")
    if input(f"确定在{len(files)}个文件上应用{len(REGEX_REPLACEMENTS)}条替换规则？(y/n)").lower().startswith('y'):
        for file in files:
            apply_rules(REGEX_REPLACEMENTS, file)
            file.save()
    logger.info("程序执行完毕，请按回车键退出")
    input()
