# 设置日志输出
import re
from pathlib import Path
from typing import List, Tuple

from para_tranz.utils.config import PARA_TRANZ_PATH
from para_tranz.utils.util import make_logger, String, DataFile

logger = make_logger(f'StringFormatter')

# 要执行的替换
CSV_RULES = [
    # 去除中文句子中的空格，需要替换两次；不匹配前面是"级"或者后面五个字内出现"-"的情况，以免误伤船名
    ('((?!级)[\u4e00-\u9fa5，。；：？！]) +([\u4e00-\u9fa5，。；：？！])(?!.{0,5}[-])', '$1$2'),
    # 去除中文句子中的空格，需要替换两次；不匹配前面是"级"或者后面五个字内出现"-"的情况，以免误伤船名
    ('((?!级)[\u4e00-\u9fa5，。；：？！]) +([\u4e00-\u9fa5，。；：？！])(?!.{0,5}[-])', '$1$2'),
    # 去除英文标点符号后空格
    ('([\u4e00-\u9fa5][!?,.;:])( )', '$1'),
    # 英文标点换中文
    ('([\u4e00-\u9fa5\\[\\]()])!', '$1！'),
    # 英文标点换中文
    ('([\u4e00-\u9fa5\\[\\]()])\\?', '$1？'),
    # 英文标点换中文
    ('([\u4e00-\u9fa5\\[\\]()]),', '$1，'),
    # 英文标点换中文
    ('([\u4e00-\u9fa5\\[\\]()])\\.(?!\\.)', '$1。'),
    # 英文标点换中文
    ('([\u4e00-\u9fa5\\[\\]()]);', '$1；'),
    # 英文标点换中文
    ('([\u4e00-\u9fa5\\[\\]()]):', '$1：'),
    # 花括号替换成空格
    ('[{}]', ' '),
    # 前后有中文的token两边空格
    ('(?<=[\u4e00-\u9fa5，。；：？！])( ?)(\\$[a-zA-Z0-9_\\.]+)( ?)', ' $2 '),
    # 前后有中文的token两边空格
    ('( ?)(\\$[a-zA-Z0-9_\\.]+)( ?)(?=[\u4e00-\u9fa5，。；：？！])', ' $2 '),
    # 人称代词和$shipOrFleet两边不空格
    (
        r'( ?)(\$.{0,6}([Hh]eOrShe|[Hh]isOrHer|[Hh]imOrHer|[Hh]imOrHerself|[Mm]anOrWoman|[Ss]hipOrFleet))( ?)',
        '$2'),
    # 双引号用英文
    ('[“”]', '"'),
    # 单引号用英文
    ('[‘’]', "'"),
    # 使用破折号替代多个连字符
    ('--+', "——"),
    # 折号两边空格
    ('( ?)——( ?)', ' —— '),
    # 英文圆括号前空格
    (r'(（| ?\()', ' ('),
    # 英文圆括号后空格
    (r'(）|\) ?)', ') '),
    # 英文方括号前空格
    (r'( ?\[)', ' ['),
    # 英文方括号后空格
    ('(] ?)', '] '),
    # 避免连续空格
    ('( +)', ' '),
    # 标点前后不空格
    ('( ?)([，。；：？！])( ?)', '$2'),
    # 标点之间不空格
    (r'([，。；：？！\[\]()"\'\.])( )([，。；：？！\[\]()\.])', '$1$3'),
    # 标点之间不空格
    (r'([，。；：？！\[\]()\.])( )([，。；：？！\[\]()"\'\.])', '$1$3'),
    # 开头结尾不空格
    ('(^ |(?<=\n) )', ''),
    # 开头结尾不空格
    ('( $| (?=\n))', ''),
]

JAR_RULES = [
    # 去除英文标点符号后空格
    ('([\u4e00-\u9fa5][!?,.;:])( )', '$1'),
    # 英文标点换中文
    ('([\u4e00-\u9fa5\\[\\]()])!', '$1！'),
    # 英文标点换中文
    ('([\u4e00-\u9fa5\\[\\]()])\\?', '$1？'),
    # 英文标点换中文
    ('([\u4e00-\u9fa5\\[\\]()]),', '$1，'),
    # 英文标点换中文
    ('([\u4e00-\u9fa5\\[\\]()])\\.(?!\\.)', '$1。'),
    # 英文标点换中文
    ('([\u4e00-\u9fa5\\[\\]()]);', '$1；'),
    # 英文标点换中文
    ('([\u4e00-\u9fa5\\[\\]()]):', '$1：'),
    # 花括号替换成空格
    ('[{}]', ' '),
    # 前后有中文的token两边空格
    ('(?<=[\u4e00-\u9fa5，。；：？！])( ?)(\\$[a-zA-Z0-9_\\.]+)( ?)', ' $2 '),
    # 前后有中文的token两边空格
    ('( ?)(\\$[a-zA-Z0-9_\\.]+)( ?)(?=[\u4e00-\u9fa5，。；：？！])', ' $2 '),
    # 人称代词和$shipOrFleet两边不空格
    (
        r'( ?)(\$.{0,6}([Hh]eOrShe|[Hh]isOrHer|[Hh]imOrHer|[Hh]imOrHerself|[Mm]anOrWoman|[Ss]hipOrFleet))( ?)',
        '$2'),
    # 双引号用英文
    ('[“”]', '"'),
    # 单引号用英文
    ('[‘’]', "'"),
    # 使用破折号替代多个连字符
    ('--+', "——"),
    # 折号两边空格
    ('( ?)——( ?)', ' —— '),
    # 英文圆括号前空格
    (r'(（| ?\()', ' ('),
    # 英文圆括号后空格
    (r'(）|\) ?)', ') '),
    # 英文方括号前空格
    (r'( ?\[)', ' ['),
    # 英文方括号后空格
    ('(] ?)', '] '),
    # 标点前后不空格
    ('( ?)([，。；：？！])( ?)', '$2'),
    # 标点之间不空格
    (r'([，。；：？！\[\]()"\'\.])( )([，。；：？！\[\]()\.])', '$1$3'),
    # 标点之间不空格
    (r'([，。；：？！\[\]()\.])( )([，。；：？！\[\]()"\'\.])', '$1$3'),
]

ACTIVE_RULE_SET = JAR_RULES


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
    if input(f"确定在{len(files)}个文件上应用{len(ACTIVE_RULE_SET)}条替换规则？(y/n)").lower().startswith('y'):
        for file in files:
            apply_rules(ACTIVE_RULE_SET, file)
            file.save()
    logger.info("程序执行完毕，请按回车键退出")
    input()
