import dataclasses
import hashlib
import json
import logging
import sys
import urllib.parse
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence, Union

from para_tranz.config import (
    LOG_FILE_PATH,
    LOG_LEVEL,
    ORIGINAL_PATH,
    OVERRIDE_STRING_STATUS,
    PARA_TRANZ_PATH,
    PROJECT_DIRECTORY,
    TRANSLATION_PATH,
)


def relative_path(path: Path) -> Path:
    try:
        return path.relative_to(PROJECT_DIRECTORY)
    except Exception as _:
        return path


def normalize_class_path(class_path: str) -> str:
    """
    将类路径标准化为以/分隔的形式，并确保以.class结尾
    """
    if class_path.endswith('.class'):
        class_path = class_path.removesuffix('.class')

    class_path = class_path.replace('.', '/')

    return class_path + '.class'


GREY = '\x1b[38;20m'
GREEN = '\x1b[32;20m'
YELLOW = '\x1b[33;20m'
RED = '\x1b[31;20m'
BOLD_RED = '\x1b[31;1m'
BG_YELLOW = '\x1b[43m'
RESET = '\x1b[0m'


def colorize(s: str, color: str) -> str:
    return color + s + RESET


# From: https://stackoverflow.com/questions/384076/how-can-i-color-python-logging-output
class CustomFormatter(logging.Formatter):
    format_str = '[%(name)s][%(levelname)s] %(message)s \n'

    FORMATS = {
        logging.DEBUG: GREY + format_str + RESET,
        logging.INFO: GREY + format_str + RESET,
        logging.WARNING: YELLOW + format_str + RESET,
        logging.ERROR: RED + format_str + RESET,
        logging.CRITICAL: BOLD_RED + format_str + RESET,
    }

    def format(self, record: logging.LogRecord) -> str:
        log_fmt = self.FORMATS.get(record.levelno)
        formatter = logging.Formatter(log_fmt)
        return formatter.format(record)


_file_handler_initialized = False


def _init_file_handler() -> None:
    global _file_handler_initialized
    if _file_handler_initialized:
        return
    _file_handler_initialized = True

    file_handler = logging.FileHandler(LOG_FILE_PATH, mode='w', encoding='utf-8')
    file_handler.setLevel(logging.DEBUG)
    file_handler.terminator = ''
    file_handler.setFormatter(logging.Formatter('[%(name)s][%(levelname)s] %(message)s \n'))
    logging.root.addHandler(file_handler)


def make_logger(name: str) -> logging.Logger:
    # 设置日志输出
    logging.root.setLevel(logging.NOTSET)
    logger = logging.getLogger(name)
    logger.setLevel(logging.NOTSET)

    if not any(getattr(handler, '_para_tranz_stdout', False) for handler in logger.handlers):
        handle_out = logging.StreamHandler(sys.stdout)
        handle_out.setLevel(LOG_LEVEL)
        handle_out.terminator = ''
        setattr(handle_out, '_para_tranz_stdout', True)

        formatter = CustomFormatter()

        handle_out.setFormatter(formatter)
        logger.addHandler(handle_out)

    _init_file_handler()

    return logger


@dataclass
class String:
    key: str
    original: str
    translation: str
    stage: int = 0  # 词条翻译状态，0为未翻译，1为已翻译，2为有疑问，3为已校对，5为已审核（二校），9为已锁定，-1为已隐藏
    context: str = ''  # 词条的备注信息

    def __post_init__(self) -> None:
        # 如果从 ParaTranz 输出的 json 导入，则需要将\\n替换回\n
        # 本程序输出的 json 不应包含 \\n，原文中的\\n使用^n替代
        self.original = self.original.replace('\\n', '\n')
        self.translation = self.translation.replace('\\n', '\n')

    def as_dict(self) -> Dict:
        return dataclasses.asdict(self)


def should_write_translation(string: String, allow_empty: bool = False) -> bool:
    return string.stage > 0 and (bool(string.translation) or allow_empty)


class DataFile:
    logger = make_logger('util.py - DataFile')
    export_empty_strings = False  # jar子类覆盖为True以允许导出空原文词条

    def __init__(
        self,
        path: Union[str, Path],
        type: str,
        original_path: Optional[Path] = None,
        translation_path: Optional[Path] = None,
        output_path: Optional[Path] = None,
    ) -> None:
        self.path = Path(path)  # 相对 original 或者 localization 文件夹的路径
        self.original_path = ORIGINAL_PATH / Path(
            original_path if original_path else path
        )
        self.translation_path = TRANSLATION_PATH / Path(
            translation_path if translation_path else path
        )
        if output_path is not None:
            self.para_tranz_path = output_path
        else:
            self.para_tranz_path = PARA_TRANZ_PATH / self.path.with_suffix('.json')

    def get_strings(self) -> List[String]:
        raise NotImplementedError

    def update_strings(self, strings: List[String]) -> None:
        raise NotImplementedError

    def save_json(self, ensure_ascii: bool = False, indent: int = 4) -> None:
        self.save_json_files([self], ensure_ascii, indent)

    @classmethod
    def save_json_files(
        cls,
        files: List['DataFile'],
        ensure_ascii: bool = False,
        indent: int = 4,
    ) -> None:
        output_path_files: Dict[Path, List['DataFile']] = defaultdict(list)
        for file in files:
            output_path_files[file.para_tranz_path].append(file)

        for output_path, grouped_files in output_path_files.items():
            strings: List[String] = []
            for file in grouped_files:
                file_strings = [
                    s
                    for s in file.get_strings()
                    if s.original or file.export_empty_strings
                ]
                if not file_strings:
                    file.logger.info(
                        f'从 {relative_path(file.path)} 中未提取到可翻译词条，跳过导出'
                    )
                    continue
                strings.extend(file_strings)
                file.logger.info(
                    f'从 {relative_path(file.path)} 中提取了 {len(file_strings)} 个词条'
                )

            if not strings:
                if output_path.exists():
                    cls.write_json_strings(output_path, [], ensure_ascii, indent)
                    cls.logger.info(
                        f'当前配置未导出任何词条，已清空 {relative_path(output_path)}'
                    )
                continue

            if output_path.exists() and not OVERRIDE_STRING_STATUS:
                cls.logger.debug(
                    f'Paratranz 平台数据文件 {relative_path(output_path)} 已存在，从中读取已翻译词条的词条stage状态'
                )
                special_stages = (1, 2, 3, 5, 9, -1)
                existing_stages = {
                    s.key: s.stage
                    for s in cls.read_json_strings(output_path)
                    if s.stage in special_stages
                }
                for s in strings:
                    if s.key in existing_stages and s.stage != existing_stages[s.key]:
                        cls.logger.debug(
                            f'更新词条 {s.key} 的stage：{s.stage}->{existing_stages[s.key]}'
                        )
                        s.stage = existing_stages[s.key]

            cls.write_json_strings(output_path, strings, ensure_ascii, indent)

            source_text = (
                str(relative_path(grouped_files[0].path))
                if len(grouped_files) == 1
                else f'{len(grouped_files)} 个文件'
            )
            cls.logger.info(
                f'从 {source_text} 中导出了 {len(strings)} 个词条到 {relative_path(output_path)}'
            )

    def update_from_json(self) -> None:
        """
        从json文件读取 ParaTranz 词条数据对象中的译文数据合并到现有数据中
        :return:
        """
        if self.para_tranz_path.exists():
            strings = self.read_json_strings(self.para_tranz_path)
            self.update_strings(strings)
            self.logger.info(
                f'从 {relative_path(self.para_tranz_path)} 加载了 {len(strings)} 个词条到 {relative_path(self.translation_path)}'
            )
        else:
            self.logger.info(
                f'未找到 {self.path} 所对应的 ParaTranz 数据 ({self.para_tranz_path})，未更新词条'
            )

    def save_file(self) -> None:
        raise NotImplementedError

    def load_from_file(self) -> None:
        raise NotImplementedError

    @classmethod
    def load_files_from_config(cls) -> Sequence['DataFile']:
        raise NotImplementedError

    @staticmethod
    def read_json_strings(path: Path) -> List[String]:
        strings = []
        with open(path, 'r', encoding='utf-8') as f:
            data = json.load(f)  # type:List[Dict]
        for d in data:
            strings.append(
                String(
                    d['key'],
                    d['original'],
                    d.get('translation', ''),
                    d['stage'],
                    d.get('context', ''),
                )
            )
        return strings

    @staticmethod
    def write_json_strings(
        path: Path,
        strings: List[String],
        ensure_ascii: bool = False,
        indent: int = 4,
        sort: bool = True,
    ) -> None:
        if sort:
            strings = sorted(strings, key=lambda s: s.key)

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
    return (
        s.replace('\udc94', '""')
        .replace('\udc93', '""')
        .replace('\udc92', "'")
        .replace('\udc91', "'")
        .replace('\udc96', '-')
        .replace('\udc85', '...')
    )


def url_encode(s: str) -> str:
    return urllib.parse.quote(s)


def hash_string(s: str, length: int = 4) -> str:
    """
    生成字符串的hash值

    Args:
        s (str): 要hash的字符串
        length (int, optional): hash值的长度，生成的hash值长度为length*2。默认为4。

    Returns:
        str: hash值字符串
    """
    return hashlib.shake_128(s.encode()).hexdigest(length)


class SetEncoder(json.JSONEncoder):
    """
    From: https://stackoverflow.com/questions/8230315/how-to-json-serialize-sets
    """

    def default(self, o: Any) -> Any:
        if isinstance(o, set):
            return sorted(list(o))
        return json.JSONEncoder.default(self, o)


if __name__ == '__main__':
    # print(normalize_class_path(
    #     'com/fs/starfarer/renderers/A/OooOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOO.class'))
    # print(normalize_class_path('com/fs/starfarer/launcher/opengl/GLModPickerV2.class'))
    print(url_encode('submarkets.csv#storage$name'))
