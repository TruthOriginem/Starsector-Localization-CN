import dataclasses
import json
import logging
import re
import sys
import urllib.parse
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, Set, List, Union

from para_tranz.utils.config import PROJECT_DIRECTORY, ORIGINAL_PATH, TRANSLATION_PATH, PARA_TRANZ_PATH, LOG_LEVEL, \
    LOG_DEBUG_OVERWRITE, OVERRIDE_STRING_STATUS


def relative_path(path: Path) -> Path:
    try:
        return path.relative_to(PROJECT_DIRECTORY)
    except Exception as _:
        return path


class CustomFormatter(logging.Formatter):
    def format(self, record):
        if LOG_DEBUG_OVERWRITE and record.levelno == logging.DEBUG:
            self._style._fmt = "\r[%(name)s][%(levelname)s] %(message)s"
        else:
            self._style._fmt = "[%(name)s][%(levelname)s] %(message)s \n"
        return super().format(record)


def make_logger(name: str) -> logging.Logger:
    # 设置日志输出
    logging.root.setLevel(logging.NOTSET)
    logger = logging.getLogger(name)

    handle_out = logging.StreamHandler(sys.stdout)
    handle_out.setLevel(LOG_LEVEL)
    handle_out.terminator = ''

    formatter = CustomFormatter()

    handle_out.setFormatter(formatter)
    logger.addHandler(handle_out)

    return logger


@dataclass
class String:
    key: str
    original: str
    translation: str
    stage: int = 0  # 词条翻译状态，0为未翻译，1为已翻译，2为有疑问，3为已校对，5为已审核（二校），9为已锁定，-1为已隐藏
    context: str = ''  # 词条的备注信息

    def __post_init__(self):
        # 如果从 ParaTranz 输出的 json 导入，则需要将\\n替换回\n
        # 本程序输出的 json 不应包含 \\n，原文中的\\n使用^n替代
        self.original = self.original.replace('\\n', '\n')
        self.translation = self.translation.replace('\\n', '\n')

    def as_dict(self) -> Dict:
        return dataclasses.asdict(self)


class DataFile:
    logger = make_logger('util.py - DataFile')

    def __init__(self, path: Union[str, Path], type: str, original_path: Path = None, translation_path: Path = None):
        self.path = Path(path)  # 相对 original 或者 localization 文件夹的路径
        self.original_path = ORIGINAL_PATH / Path(original_path if original_path else path)
        self.translation_path = TRANSLATION_PATH / Path(
            translation_path if translation_path else path)
        self.para_tranz_path = PARA_TRANZ_PATH / self.path.with_suffix('.json')

    def get_strings(self) -> List[String]:
        raise NotImplementedError

    def update_strings(self, strings: List[String], version_migration: bool = False) -> None:
        raise NotImplementedError

    def save_json(self, ensure_ascii=False, indent=4) -> None:
        strings = [s for s in self.get_strings() if s.original]  # 只导出原文不为空的词条

        # 如果Paratranz json文件已存在，则从中同步任何已翻译词条的状态
        if not OVERRIDE_STRING_STATUS and self.para_tranz_path.exists():
            self.logger.info(
                f"Paratranz 平台数据文件 {relative_path(self.para_tranz_path)} 已存在，从中读取已翻译词条的词条stage状态")

            special_stages = (1, 2, 3, 5, 9, -1)
            para_strings = self._read_json_strings(self.para_tranz_path)
            para_key_strings = {s.key: s for s in para_strings if
                                s.stage in special_stages}  # type:Dict[str, String]
            for s in strings:
                if s.key in para_key_strings:
                    para_s = para_key_strings[s.key]
                    if s.stage != para_s.stage:
                        self.logger.debug(f"更新词条 {s.key} 的stage：{s.stage}->{para_s.stage}")
                        s.stage = para_s.stage

        self._write_json_strings(self.para_tranz_path, strings, ensure_ascii, indent)

        self.logger.info(
            f'从 {relative_path(self.path)} 中导出了 {len(strings)} 个词条到 {relative_path(self.para_tranz_path)}')

    def update_from_json(self, version_migration: bool = False) -> None:
        """
        从json文件读取 ParaTranz 词条数据对象中的译文数据合并到现有数据中
        :return:
        """
        if self.para_tranz_path.exists():
            strings = self._read_json_strings(self.para_tranz_path)
            self.update_strings(strings, version_migration)
            self.logger.info(
                f'从 {relative_path(self.para_tranz_path)} 加载了 {len(strings)} 个词条到 {relative_path(self.translation_path)}')
        else:
            self.logger.warning(f'未找到 {self.path} 所对应的 ParaTranz 数据 ({self.para_tranz_path})，未更新词条')

    def save_file(self) -> None:
        raise NotImplementedError

    def load_from_file(self) -> None:
        raise NotImplementedError

    @classmethod
    def load_files_from_config(cls) -> List['DataFile']:
        raise NotImplementedError

    @staticmethod
    def _read_json_strings(path: Path) -> List[String]:
        strings = []
        with open(path, 'r', encoding='utf-8') as f:
            data = json.load(f)  # type:List[Dict]
        for d in data:
            strings.append(
                String(d['key'], d['original'], d.get('translation', ''), d['stage']))
        return strings

    @staticmethod
    def _write_json_strings(path: Path, strings: List[String], ensure_ascii=False, indent=4) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        with open(path, 'w', encoding='utf-8') as f:
            data = []
            for string in strings:
                data.append(string.as_dict())
            json.dump(data, f, ensure_ascii=ensure_ascii, indent=indent)


# https://segmentfault.com/a/1190000017940752
# 判断是否包含中文字符
def contains_chinese(s: str) -> bool:
    for _char in s:
        if '\u4e00' <= _char <= '\u9fa5':
            return True
    return False


def contains_english(s: str) -> bool:
    for _char in s:
        if 'a' <= _char <= 'z' or 'A' <= _char <= 'Z':
            return True
    return False


# From processWithWiredChars.py
# 由于游戏原文文件中可能存在以Windows-1252格式编码的字符（如前后双引号等），所以需要进行转换
def replace_weird_chars(s: str) -> str:
    return s.replace('\udc94', '""') \
        .replace('\udc93', '""') \
        .replace('\udc92', "'") \
        .replace('\udc91', "'") \
        .replace('\udc96', "-") \
        .replace('\udc85', '...')


def normalize_class_path(class_path: str) -> str:
    """
    将类路径中可能因混淆产生的部分替换为一个标准形式以便模糊匹配
    """
    segments = class_path.split('/')

    def normalize(s: str) -> str:
        if re.fullmatch(r'[Oo0]+', s):
            return 'O'
        elif re.fullmatch(r'[a-zA-Z0-9]', s):
            return 'X'
        elif re.fullmatch(r'([A-Z][a-z0-9]*)+', s):
            # 返回驼峰的所有开头字母
            return ''.join([c for c in s if c.isupper() or c.isdigit()]).lower()
        else:
            return s

    name_segments = segments[-1].removesuffix('.class').split('$')
    class_name = normalize(name_segments[0])
    if len(name_segments) > 1:
        subclass_name = normalize(name_segments[1])
        class_name += '$' + subclass_name

    class_name += '.class'

    return '/'.join([normalize(s) for s in segments[:-1]] + [class_name])

def url_encode(s: str) -> str:
    return urllib.parse.quote(s)

class SetEncoder(json.JSONEncoder):
    """
    From: https://stackoverflow.com/questions/8230315/how-to-json-serialize-sets
    """

    def default(self, obj):
        if isinstance(obj, set):
            return sorted(list(obj))
        return json.JSONEncoder.default(self, obj)


if __name__ == '__main__':
    # print(normalize_class_path(
    #     'com/fs/starfarer/renderers/A/OooOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOO.class'))
    # print(normalize_class_path('com/fs/starfarer/launcher/opengl/GLModPickerV2.class'))
    print(url_encode('submarkets.csv#storage$name'))