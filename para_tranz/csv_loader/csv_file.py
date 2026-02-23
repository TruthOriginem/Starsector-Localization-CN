import re
from _csv import reader, writer
from ast import literal_eval
from csv import DictReader
from dataclasses import asdict
from pathlib import Path
from typing import Dict, List, Optional, Set, Tuple, Union

from para_tranz.csv_loader.csv_util import (
    rules_csv_extract_highlight_targets_from_script,
    rules_csv_find_missing_csv_tokens,
    rules_csv_find_text_highlight_targets_adjacent_to_non_space,
)
from para_tranz.utils.config import (
    EXPORTED_STRING_CONTEXT_PREFIX,
    EXPORTED_STRING_CONTEXT_PREFIX_PREFIX,
    IGNORE_CONTEXT_PREFIX_MISMATCH_STRINGS,
    REMOVE_TRANSLATION_WHEN_ORIGINAL_IS_EMPTY,
)
from para_tranz.utils.mapping import PARA_TRANZ_MAP, CsvMapItem
from para_tranz.utils.util import (
    DataFile,
    String,
    contains_chinese,
    contains_english,
    make_logger,
    relative_path,
    replace_weird_chars,
)


class CsvFile(DataFile):
    logger = make_logger('CsvFile')

    def __init__(
        self,
        path: Path,
        id_column_name: str,
        text_column_names: Set[str],
        original_path: Optional[Path] = None,
        translation_path: Optional[Path] = None,
        type: str = 'csv',
    ):
        super().__init__(path, type, original_path, translation_path)

        # csv 中作为 id 的列名
        self.id_column_name: Union[str, List[str]] = (
            id_column_name  # 作为id的列名，可能为多个
        )
        self.text_column_names: Set[str] = text_column_names  # 包含要翻译文本的列名

        self.column_names: List[str] = []

        # 原文数据，以及id列内容到数据的映射
        self.original_data: List[Dict] = []
        self.original_id_data: Dict[Tuple, Dict] = {}
        # 译文数据，以及id列内容到数据的映射
        self.translation_data: List[Dict] = []
        self.translation_id_data: Dict[Tuple, Dict] = {}

        self.load_from_file()

    # 将数据转换为 ParaTranz 词条数据对象
    def get_strings(self) -> List[String]:
        strings = []
        for row_id, row in self.original_id_data.items():
            # 只导出id不为空且没有被注释行内的词条
            first_column = row[list(row.keys())[0]]
            if not any(row_id) or first_column.startswith('#'):
                continue

            context = self.generate_row_context(row)
            for col in self.text_column_names:
                key = self.generate_string_key(row_id, col)
                original = row[col]
                translation = ''
                stage = 0
                # 如果已翻译，则使用译文覆盖
                if row_id in self.translation_id_data:
                    translation = self.translation_id_data[row_id][col]
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

    def generate_string_key(self, row_id: Tuple, column: str) -> str:
        # 将行ID转换为字符串，以便作为词条的key
        # 如果行ID只有一个元素，则直接转换为字符串，否则转换为元组字符串
        if len(row_id) == 1:
            row_id_str = row_id[0]
        else:
            row_id_str = str(row_id)
        return f'{self.path.name}#{row_id_str}${column}'  # 词条的id由 文件名-行id-列名 组成

    # 将传入的 ParaTranz 词条数据对象中的译文数据合并到现有数据中
    def update_strings(
        self, strings: Set[String], version_migration: bool = False
    ) -> None:
        for s in strings:
            _, row_id_str, column = re.split('[#$]', s.key)
            # 将行ID字符串转换为元组，以便作为译文数据的key
            # 如果行ID是普通字符串，则转换为单元素元组，否则转换为元组
            row_id = literal_eval(row_id_str) if ',' in row_id_str else (row_id_str,)
            if IGNORE_CONTEXT_PREFIX_MISMATCH_STRINGS and not s.context.startswith(
                EXPORTED_STRING_CONTEXT_PREFIX_PREFIX
            ):
                self.logger.debug(
                    f'文件 {self.path} 中词条 key={s.key} 的上下文前缀与当前上下文前缀不匹配，跳过词条'
                )
                continue
            if row_id in self.translation_id_data and row_id in self.original_id_data:
                # 如果词条已翻译并且译文不为空
                if s.stage > 0 and s.translation:
                    if self.original_id_data[row_id][column] == '':
                        self.logger.warning(
                            f'文件 {self.path} 中 {self.id_column_name}="{row_id}" 的行中 "{column}" 列原文为空，'
                            f'但更新的译文数据不为空，未更新该词条。原文可能已删除，请考虑删除该译文词条'
                        )
                        if REMOVE_TRANSLATION_WHEN_ORIGINAL_IS_EMPTY:
                            self.logger.warning(
                                '已设置 REMOVE_TRANSLATION_WHEN_ORIGINAL_IS_EMPTY 为 True，'
                                '将该译文设为空字符串'
                            )
                            self.translation_id_data[row_id][column] = ''
                    else:
                        # 更新译文数据
                        self.translation_id_data[row_id][column] = s.translation
                elif contains_chinese(self.translation_id_data[row_id][column]):
                    self.logger.warning(
                        f'文件 {self.path} 中 {self.id_column_name}="{row_id}" 的行已被翻译，'
                        f'但更新的译文数据未翻译该词条，保持原始翻译不变'
                    )
            else:
                self.logger.warning(
                    f'在文件 {self.path} 中没有找到 {self.id_column_name}="{row_id}" 的行，未更新该词条。原文可能已删除，请考虑删除该译文词条'
                )

    def validate_before_save(self) -> None:
        self.logger.info(
            f'开始在保存前校验 {relative_path(self.translation_path)} 中的译文数据'
        )
        for row_id, translated_row in self.translation_id_data.items():
            original_row = self.original_id_data[row_id]
            # 检查译文是否包含中文引号
            for col, translated_value in translated_row.items():
                if '“' in translated_value or '”' in translated_value:
                    raise ValueError(
                        f'key="{self.generate_string_key(row_id, col)}" 的词条中译文数据包含中文引号，请移除后再保存'
                    )
            # 针对 rules.csv 的特殊检查
            if self.path.name == 'rules.csv':
                # TODO: 此部分需要重构，以支持同时检测高亮文本在 text 和 option 中的覆盖情况
                # 如果译文不为空
                if translated_row['text']:
                    # 检查译文是否包含原文里的每一个格式为 $var 的变量名
                    for col, translated_value in translated_row.items():
                        original_value = original_row[col]
                        missing_tokens = rules_csv_find_missing_csv_tokens(
                            original_value, translated_value
                        )
                        if missing_tokens:
                            self.logger.warning(
                                f'key="{self.generate_string_key(row_id, col)}" 的词条中译文数据缺失了原文中的token {missing_tokens}，请检查'
                            )
                        # 检查原文与译文行数是否一致
                        if translated_value and (
                            original_value.count('\n') != translated_value.count('\n')
                        ):
                            self.logger.warning(
                                f'key="{self.generate_string_key(row_id, col)}" 的词条中原文行数'
                                f'({original_value.count(chr(10)) + 1})与译文行数'
                                f'({translated_value.count(chr(10)) + 1})不一致，请检查'
                            )
                    # 检查译文中的高亮目标是否存在，且被空格包围
                    # SetTextHighlights 的目标可以出现在 text 或 options 任意一列中
                    script = translated_row['script']
                    highlights = rules_csv_extract_highlight_targets_from_script(script)
                    original_combined = (
                        original_row['text'] + '\n' + original_row.get('options', '')
                    )
                    translated_combined = (
                        translated_row['text']
                        + '\n'
                        + translated_row.get('options', '')
                    )

                    missing_highlights_original = {
                        highlight
                        for highlight in highlights
                        if highlight.startswith('$')
                        and highlight not in original_combined
                    }
                    missing_highlights = {
                        highlight
                        for highlight in highlights
                        if highlight not in translated_combined
                    } - missing_highlights_original
                    if missing_highlights:
                        self.logger.warning(
                            f'key="{self.generate_string_key(row_id, "text")}" / "{self.generate_string_key(row_id, "options")}" 的译文数据中缺失了高亮命令目标 {missing_highlights}，请检查译文数据或script列内容(key="{self.generate_string_key(row_id, "script")}")'
                        )

                    not_surrounded_highlights = (
                        rules_csv_find_text_highlight_targets_adjacent_to_non_space(
                            translated_combined, highlights
                        )
                        - missing_highlights
                        - missing_highlights_original
                    )
                    if not_surrounded_highlights:
                        self.logger.warning(
                            f'key="{self.generate_string_key(row_id, "text")}" / "{self.generate_string_key(row_id, "options")}" 的译文数据中高亮命令目标 {not_surrounded_highlights} 左右存在非英文标点和空格的字符，请检查'
                        )
        self.logger.info(
            f'校验 {relative_path(self.translation_path)} 中的译文数据完成'
        )

    # 将译文数据写回译文csv中
    def save_file(self) -> None:
        self.validate_before_save()

        with open(
            self.translation_path,
            'r',
            errors='surrogateescape',
            newline='',
            encoding='utf-8',
        ) as f:
            csv = reader(f, strict=True)
            real_column_names = csv.__next__()

        # 由于部分csv包含多个空列，在用DictReader读取时会被丢弃，为了与源文件保持一致，在此根据原文件重新添加
        real_column_index = {
            col: real_column_names.index(col) for col in self.column_names if col
        }

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

    # 检查当前数据的有效性，在读取完数据后调用
    def validate_after_load(self):
        # 检查指定的id列和文字列在游戏文件中是否存在
        if (
            isinstance(self.id_column_name, str)
            and self.id_column_name not in self.column_names
        ) and (not set(self.id_column_name).issubset(set(self.column_names))):
            raise ValueError(
                f'从 {self.path} 中未找到指定的id列 "{self.id_column_name}"，请检查配置文件中的设置。可用的列包括： {self.column_names}'
            )
        if not set(self.text_column_names).issubset(set(self.column_names)):
            raise ValueError(
                f'从 {self.path} 中未找到指定的文字列 {self.text_column_names}，请检查配置文件中的设置。可用的列包括： {self.column_names}'
            )
        # 检查原文与译文数量是否匹配
        if len(self.original_data) != len(self.translation_data):
            self.logger.warning(
                f'文件 {relative_path(self.path)} 所加载的原文与译文数据量不匹配：加载原文 {len(self.original_data)} 条，译文 {len(self.translation_data)} 条'
            )
        if len(self.original_id_data) != len(self.translation_id_data):
            self.logger.warning(
                f'文件 {relative_path(self.path)} 所加载的未被注释且不为空的原文与译文数据量不匹配：加载有效原文 {len(self.original_id_data)} 条，有效译文 {len(self.translation_id_data)} 条'
            )

    # 从原文和译文csv中读取数据
    def load_from_file(self) -> None:
        self.column_names, self.original_data, self.original_id_data = self.load_csv(
            self.original_path, self.id_column_name
        )
        self.logger.info(
            f'从 {relative_path(self.original_path)} 中加载了 {len(self.original_data)} 行原文数据，其中未被注释且不为空的行数为 {len(self.original_id_data)}'
        )
        if self.translation_path.exists():
            _, self.translation_data, self.translation_id_data = self.load_csv(
                self.translation_path, self.id_column_name
            )
            self.logger.info(
                f'从 {relative_path(self.translation_path)} 中加载了 {len(self.translation_data)} 行译文数据，其中未被注释且不为空的行数为 {len(self.translation_id_data)}'
            )

        self.validate_after_load()

    @classmethod
    def load_csv(
        cls, path: Path, id_column_name: Union[str, List[str]]
    ) -> Tuple[List[str], List[Dict], Dict[Tuple, Dict]]:
        """
        从csv中读取数据，并返回列名列表，数据以及id列内容到数据的映射
        :param path: csv文件路径
        :param id_column_name: id列名称，只有一列的话传入列名，有多列传入列名list
        :return: (列名列表, 数据list, id列内容到数据的映射dict)
        """
        data = []
        id_data = {}
        with open(path, 'r', errors='surrogateescape', encoding='utf-8') as csv_file:
            # 替换不可识别的字符，并将原文中的 \n 转换为 ^n，以与csv中的直接换行进行区分
            csv_lines = [
                replace_weird_chars(line).replace('\\n', '^n') for line in csv_file
            ]
            rows: List[Dict[str, str]] = list(DictReader(csv_lines))
            columns = list(rows[0].keys())
            for i, row in enumerate(rows):
                if isinstance(id_column_name, str):
                    row_id = tuple([row[id_column_name]])
                else:  # 存在多个 id column
                    row_id = tuple([row[id] for id in id_column_name])

                # 检查行内数据长度是否与文件一致
                for col in row:
                    if row[col] is None:
                        row[col] = ''
                        cls.logger.warning(
                            f'文件 {path} 第 {i} 行 {id_column_name}="{row_id}" 内的值数量不够，可能是缺少逗号'
                        )

                first_column = row[columns[0]]
                # 只在 id-row mapping 中存储没有被注释, 且不为空的行
                if not first_column.startswith('#') and any(row_id):
                    if row_id in id_data:
                        raise ValueError(
                            f'文件 {path} 第 {i} 行 {id_column_name}="{row_id}" 的值在文件中不唯一'
                        )
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
        files = [
            cls(**asdict(item))
            for item in PARA_TRANZ_MAP
            if isinstance(item, CsvMapItem)
        ]
        cls.logger.info('游戏csv数据读取完成')
        return files

    # 根据行ID，生成该行的词条上下文内容，用于辅助翻译
    def generate_row_context(self, row: dict) -> str:
        row_num = self.original_data.index(row)
        return f'{EXPORTED_STRING_CONTEXT_PREFIX}{self.path.name}第{str(row_num + 1).zfill(5)}行'
