import json
import pprint
import re
from _csv import reader, writer
from csv import DictReader
from dataclasses import asdict
from pathlib import Path
from typing import Set, Union, List, Dict, Tuple

from para_tranz.utils.config import MAP_PATH, REMOVE_TRANSLATION_WHEN_ORIGINAL_IS_EMPTY, EXPORTED_STRING_CONTEXT_PREFIX
from para_tranz.utils.mapping import PARA_TRANZ_MAP, CsvMapItem
from para_tranz.utils.util import relative_path, String, DataFile, contains_chinese, replace_weird_chars, make_logger, \
    contains_english


class CsvFile(DataFile):
    logger = make_logger(f'CsvFile')

    def __init__(self, path: Path, id_column_name: str, text_column_names: Set[str],
                 original_path: Path = None, translation_path: Path = None, type: str = 'csv'):
        super().__init__(path, type, original_path, translation_path)

        # csv 中作为 id 的列名
        self.id_column_name = id_column_name  # type:Union[str, List[str]] # 作为id的列名，可能为多个
        self.text_column_names = text_column_names  # type:Set[str]  # 包含要翻译文本的列名

        self.column_names = []  # type:List[str]

        # 原文数据，以及id列内容到数据的映射
        self.original_data = []  # type:List[Dict]
        self.original_id_data = {}  # type:Dict[str, Dict]
        # 译文数据，以及id列内容到数据的映射
        self.translation_data = []  # type:List[Dict]
        self.translation_id_data = {}  # type:Dict[str, Dict]

        self.load_from_file()

        self.validate()

    # 读取完毕后检查数据有效性
    def validate(self):
        # 检查指定的id列和文字列在游戏文件中是否存在
        if (type(self.id_column_name) == str and self.id_column_name not in self.column_names) and (
                not set(self.id_column_name).issubset(set(self.column_names))):
            raise ValueError(
                f'从 {self.path} 中未找到指定的id列 "{self.id_column_name}"，请检查配置文件中的设置。可用的列包括： {self.column_names}')
        if not set(self.text_column_names).issubset(set(self.column_names)):
            raise ValueError(
                f'从 {self.path} 中未找到指定的文字列 {self.text_column_names}，请检查配置文件中的设置。可用的列包括： {self.column_names}')
        # 检查原文与译文数量是否匹配
        if len(self.original_data) != len(self.translation_data):
            self.logger.warning(
                f'文件 {relative_path(self.path)} 所加载的原文与译文数据量不匹配：加载原文 {len(self.original_data)} 条，译文 {len(self.translation_data)} 条')
        if len(self.original_id_data) != len(self.translation_id_data):
            self.logger.warning(
                f'文件 {relative_path(self.path)} 所加载的未被注释且不为空的原文与译文在去数据量不匹配：加载有效原文 {len(self.original_id_data)} 条，有效译文 {len(self.translation_id_data)} 条')

    # 将数据转换为 ParaTranz 词条数据对象
    def get_strings(self) -> List[String]:
        strings = []
        for row_id, row in self.original_id_data.items():

            # 只导出不为空且没有被注释行内的词条
            first_column = row[list(row.keys())[0]]
            if not first_column or first_column[0] == '#':
                continue

            context = self.generate_row_context(row)
            for col in self.text_column_names:
                key = f'{self.path.name}#{row_id}${col}'  # 词条的id由 文件名-行id-列名 组成
                original = row[col]
                translation = ''
                stage = 0
                # 如果已翻译，则使用译文覆盖
                if row_id in self.translation_id_data:
                    translation = self.translation_id_data[row_id][col]
                    stage = 1
                # 特殊规则：如果rules.csv里的script列中不包含'"'（双引号），则视为已翻译
                if (self.path.name == 'rules.csv') and (col == 'script') and (
                        '"' not in original):
                    stage = 1
                # 如果原文不包含英文，则设定为已翻译
                elif not contains_english(original):
                    stage = 1
                # 如果尚未翻译（不包含中文），则设定为未翻译
                elif not contains_chinese(translation):
                    translation = ''
                    stage = 0

                strings.append(String(key, original, translation, stage, context))
        return strings

    # 将传入的 ParaTranz 词条数据对象中的译文数据合并到现有数据中
    def update_strings(self, strings: List[String], version_migration:bool=False) -> None:
        for s in strings:
            _, id, column = re.split('[#$]', s.key)
            if id in self.translation_id_data and id in self.original_id_data:
                # 如果词条已翻译并且译文不为空
                if s.stage > 0 and s.translation:
                    if self.original_id_data[id][column] == "":
                        self.logger.warning(f'文件 {self.path} 中 {self.id_column_name}="{id}" 的行中 "{column}" 列原文为空，'
                                            f'但更新的译文数据不为空，未更新该词条。原文可能已删除，请考虑删除该译文词条')
                        if REMOVE_TRANSLATION_WHEN_ORIGINAL_IS_EMPTY:
                            self.logger.warning(f'已设置 REMOVE_TRANSLATION_WHEN_ORIGINAL_IS_EMPTY 为 True，'
                                                f'将该译文设为空字符串')
                            self.translation_id_data[id][column] = ''
                    else:
                        # 更新译文数据
                        self.translation_id_data[id][column] = s.translation
                elif contains_chinese(self.translation_id_data[id][column]):
                    self.logger.warning(f'文件 {self.path} 中 {self.id_column_name}="{id}" 的行已被翻译，'
                                        f'但更新的译文数据未翻译该词条，保持原始翻译不变')
            else:
                self.logger.warning(f'在文件 {self.path} 中没有找到 {self.id_column_name}="{id}" 的行，未更新该词条。原文可能已删除，请考虑删除该译文词条')

    # 将译文数据写回译文csv中
    def save_file(self) -> None:
        with open(self.translation_path, 'r', errors='surrogateescape', newline='', encoding='utf-8') as f:
            csv = reader(f, strict=True)
            real_column_names = csv.__next__()

        # 由于部分csv包含多个空列，在用DictReader读取时会被丢弃，为了与源文件保持一致，在此根据原文件重新添加
        real_column_index = {col: real_column_names.index(col) for col in self.column_names if col}

        rows = [real_column_names]

        for dict_row in self.translation_data:
            row = ['' for _ in range(len(real_column_names))]
            for col, value in dict_row.items():
                if col:
                    # 将csv行内换行的\n替换为\r\n以避免csv写入时整个文件变成\n换行(LF)的问题
                    # 将读取csv时使用的^n替换回\\n
                    value = value.replace('^n', '\\n').replace('\n', '\r\n')
                    row[real_column_index[col]] = value
            rows.append(row)
        with open(self.translation_path, 'w', newline='', encoding='utf-8') as f:
            writer(f, strict=True).writerows(rows)

    # 从原文和译文csv中读取数据
    def load_from_file(self) -> None:
        self.column_names, self.original_data, self.original_id_data = self.load_csv(
            self.original_path,
            self.id_column_name)
        self.logger.info(
            f'从 {relative_path(self.original_path)} 中加载了 {len(self.original_data)} 行原文数据，其中未被注释且不为空的行数为 {len(self.original_id_data)}')
        if self.translation_path.exists():
            _, self.translation_data, self.translation_id_data = self.load_csv(
                self.translation_path,
                self.id_column_name)
            self.logger.info(
                f'从 {relative_path(self.translation_path)} 中加载了 {len(self.translation_data)} 行译文数据，其中未被注释且不为空的行数为 {len(self.translation_id_data)}')

    @classmethod
    def load_csv(cls, path: Path, id_column_name: Union[str, List[str]]) -> Tuple[
        List[str], List[Dict], Dict[str, Dict]]:
        """
        从csv中读取数据，并返回列名列表，数据以及id列内容到数据的映射
        :param path: csv文件路径
        :param id_column_name: id列名称，只有一列的话传入列名，有多列传入列名list
        :return: (列名列表, 数据list, id列内容到数据的映射dict)
        """
        data = []
        id_data = {}
        with open(path, 'r', errors="surrogateescape", encoding='utf-8') as csv_file:
            # 替换不可识别的字符，并将原文中的 \n 转换为 ^n，以与csv中的直接换行进行区分
            csv_lines = [replace_weird_chars(l).replace('\\n', '^n') for l in csv_file]
            rows = list(DictReader(csv_lines))
            columns = list(rows[0].keys())
            for i, row in enumerate(rows):
                if type(id_column_name) == str:
                    row_id = row[id_column_name]  # type:str
                else:  # 存在多个 id column
                    row_id = str(tuple([row[id] for id in id_column_name]))  # type:str

                # 检查行内数据长度是否与文件一致
                for col in row:
                    if row[col] is None:
                        row[col] = ''
                        cls.logger.warning(
                            f'文件 {path} 第 {i} 行 {id_column_name}="{row_id}" 内的值数量不够，可能是缺少逗号')

                first_column = row[columns[0]]
                # 只在 id-row mapping 中存储不为空且没有被注释的行
                if first_column and not first_column[0] == '#':
                    if row_id in id_data:
                        raise ValueError(f'文件 {path} 第 {i} 行 {id_column_name}="{row_id}" 的值在文件中不唯一')
                    id_data[row_id] = row
                data.append(row)
        return columns, data, id_data

    @classmethod
    def load_files_from_config(cls) -> List['CsvFile']:
        """
        根据 para_tranz_map.json 设置，批量读取原文、译文csv文件
        :return: CsvFile文件对象列表

        para_tranz_map.json 的格式如下：
        [
            {
                "path": "csv文件路径，使用'/'作分隔符.csv",
                "id_column_name": "csv中作为id的列名",
                "text_column_names": [
                  "需要翻译列的列名1",
                  "需要翻译列的列名2"
                ]
            },
            {
                "path": "csv文件路径，使用'/'作分隔符.csv",
                "id_column_name": ["作为id的列名1", "作为id的列名2"],
                "text_column_names": [
                  "需要翻译列的列名1",
                  "需要翻译列的列名2"
                ]
            }
        ]
        """
        cls.logger.info('开始读取游戏csv数据')
        files = [cls(**asdict(item)) for item in PARA_TRANZ_MAP if type(item) == CsvMapItem]
        cls.logger.info('游戏csv数据读取完成')
        return files

    # 根据行ID，生成该行的词条上下文内容，用于辅助翻译
    def generate_row_context(self, row: dict) -> str:
        row_num = self.original_data.index(row)

        return f"{EXPORTED_STRING_CONTEXT_PREFIX}{self.path.name}第{str(row_num + 1).zfill(4)}行\n[本行原始数据]\n{pprint.pformat(row, sort_dicts=False)}"
