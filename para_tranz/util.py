import dataclasses
import json
import logging
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, Set, List, Union

from para_tranz.config import PROJECT_DIRECTORY, ORIGINAL_PATH, TRANSLATION_PATH, PARA_TRANZ_PATH


def relative_path(path: Path) -> Path:
    try:
        return path.relative_to(PROJECT_DIRECTORY)
    except Exception as _:
        return path


def make_logger(name: str) -> logging.Logger:
    # 设置日志输出
    logging.root.setLevel(logging.NOTSET)
    logger = logging.getLogger(name)

    ch = logging.StreamHandler()
    ch.setLevel(logging.DEBUG)

    formatter = logging.Formatter("[%(name)s][%(levelname)s] %(message)s")

    ch.setFormatter(formatter)
    logger.addHandler(ch)

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
        pass

    def update_strings(self, strings: Set[String]):
        pass

    def save_json(self, ensure_ascii=False, indent=4) -> None:
        strings = [s for s in self.get_strings() if s.original]  # 只导出原文不为空的词条

        # 如果Paratranz json文件已存在，则从中同步任何已翻译词条的状态
        if self.para_tranz_path.exists():
            self.logger.info(
                f"Paratranz 平台数据文件 {relative_path(self.para_tranz_path)} 已存在，从中读取已翻译词条的词条状态")

            special_stages = (1, 2, 3, 5, 9, -1)
            para_strings = self._read_json_strings(self.para_tranz_path)
            para_key_strings = {s.key: s for s in para_strings if
                                s.stage in special_stages}  # type:Dict[str, String]
            for s in strings:
                if s.key in para_key_strings:
                    para_s = para_key_strings[s.key]
                    if s.stage != para_s.stage:
                        self.logger.debug(f"更新词条{s.key}的stage：{s.stage}->{para_s.stage}")
                        s.stage = para_s.stage

        self._write_json_strings(self.para_tranz_path, strings, ensure_ascii, indent)

        self.logger.info(
            f'从 {relative_path(self.path)} 中导出了 {len(strings)} 个词条到 {relative_path(self.para_tranz_path)}')

    def update_from_json(self) -> None:
        if self.para_tranz_path.exists():
            strings = self._read_json_strings(self.para_tranz_path)
            self.update_strings(strings)
            self.logger.info(
                f'从 {relative_path(self.para_tranz_path)} 加载了 {len(strings)} 个词条到 {relative_path(self.translation_path)}')
        else:
            self.logger.warning(f'未找到 {self.path} 所对应的 ParaTranz 数据 ({self.para_tranz_path})，未更新词条')

    def save_file(self) -> None:
        pass

    def load_from_file(self) -> None:
        pass

    @classmethod
    def load_files(cls) -> List['DataFile']:
        pass

    @staticmethod
    def _read_json_strings(path: Path) -> List[String]:
        strings = []
        with open(path, 'r', encoding='utf-8-sig') as f:
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


# From processWithWiredChars.py
# 由于游戏原文文件中可能存在以Windows-1252格式编码的字符（如前后双引号等），所以需要进行转换
def replace_weird_chars(s: str) -> str:
    return s.replace('\udc94', '""') \
        .replace('\udc93', '""') \
        .replace('\udc92', "'") \
        .replace('\udc91', "'") \
        .replace('\udc96', "-") \
        .replace('\udc85', '...')
