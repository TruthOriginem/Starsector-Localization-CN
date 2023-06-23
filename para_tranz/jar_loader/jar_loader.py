import json
import logging
import re
import tempfile
import zipfile
from pathlib import Path, PurePosixPath
from typing import List, Dict, Set, Optional, Union, Tuple

from para_tranz.jar_loader.constant_table import ConstantTable, UTF8_Constant
from para_tranz.util import DataFile, String, contains_chinese, relative_path, make_logger

# 设置游戏原文，译文和Paratranz数据文件路径
PROJECT_DIRECTORY = Path(__file__).parent.parent.parent
ORIGINAL_PATH = PROJECT_DIRECTORY / 'original'
TRANSLATION_PATH = PROJECT_DIRECTORY / 'localization'
PARA_TRANZ_PATH = PROJECT_DIRECTORY / 'para_tranz' / 'output'
CONFIG_PATH = PROJECT_DIRECTORY / 'para_tranz' / 'para_tranz_map.json'

# Java设置
MAGIC = b'\xca\xfe\xba\xbe'
MIN_CLASS_VER = 0x31  # 1.5
MAX_CLASS_VER = 0x33  # 1.7


class JavaJarFile(DataFile):
    """
    用于表示游戏文件中可以提取原文和译文的jar文件
    """

    logger = make_logger(f'jar_loader.py - JavaJarFile')

    def __init__(self, path: Path, class_file_paths: List[str], type: str = 'jar'):
        super().__init__(path, type)

        self.path = path
        self.original_path = ORIGINAL_PATH / path
        self.translation_path = TRANSLATION_PATH / path

        self.class_files = {}  # type: Dict[str, JavaClassFile]

        for class_file_path in class_file_paths:
            self.class_files[class_file_path] = JavaClassFile(self, class_file_path, type)

    def get_strings(self) -> List[String]:
        strings = []
        for class_file in self.class_files.values():
            strings.extend(class_file.get_strings())
        return strings

    def update_strings(self, strings: Set[String]):
        class_file_path_strings_mapping = {class_file_path: [] for class_file_path in
                                           self.class_files}  # type: Dict[str, List[String]]

        for s in strings:
            class_file_path = re.split(r'(#|//)', s.key)[2]

            if class_file_path not in self.class_files:
                self.logger.warning(
                    f'在更新词条 {s.key} 时，在文件 {self.path} 中找不到类 {class_file_path}。未更新该词条。')
                continue

            class_file_path_strings_mapping[class_file_path].append(s)

        for class_file_path, strings in class_file_path_strings_mapping.items():
            self.class_files[class_file_path].update_strings(strings)

    def save_file(self) -> None:
        # 对于每一个已读取的class文件，生成新的字节码
        updated_file_contents = {}
        for class_file in self.class_files.values():
            updated_file_contents[str(class_file.path)] = class_file.generate_translated_bytecode()
        # 生成新的jar文件，写入新的class文件，并将老jar中的其它文件也复制进去
        with zipfile.ZipFile(self.translation_path.with_suffix('.temp'), 'w') as zf:
            with zipfile.ZipFile(self.translation_path) as old_zf:
                for info in old_zf.infolist():
                    if info.filename not in updated_file_contents:
                        zf.writestr(info, old_zf.read(info.filename))
                    else:
                        print(info.filename)
                        zf.writestr(info, updated_file_contents[info.filename])
        # 删除老jar文件，将新jar文件重命名为老jar文件
        self.translation_path.unlink()
        self.translation_path.with_suffix('.temp').rename(self.translation_path)

    def load_from_file(self) -> None:
        for class_file in self.class_files.values():
            class_file.load_from_file()

    def original_file_exists(self, class_file_path: str) -> bool:
        return self._file_exists(self.original_path, class_file_path)

    def read_original_class_file(self, class_file_path: str) -> bytes:
        return self._read_class_file(self.original_path, class_file_path)

    def translation_file_exists(self, class_file_path: str) -> bool:
        return self._file_exists(self.translation_path, class_file_path)

    def read_translation_class_file(self, class_file_path: str) -> bytes:
        return self._read_class_file(self.translation_path, class_file_path)

    @staticmethod
    def _read_class_file(jar_path: Path, class_file_path: str) -> bytes:
        with zipfile.ZipFile(jar_path) as zf:
            return zipfile.Path(zf, class_file_path).read_bytes()

    @staticmethod
    def _file_exists(jar_path: Path, class_file_path: str) -> bool:
        with zipfile.ZipFile(jar_path) as zf:
            return class_file_path in zf.namelist()

    @classmethod
    def load_files(cls) -> List['JavaJarFile']:
        with open(CONFIG_PATH, encoding='utf-8') as f:
            d = json.load(f)
        files = [cls(**mapping) for mapping in d if
                 mapping['path'].endswith('.jar') or mapping.get('type', '') == 'jar']
        return files


class JavaClassFile:
    """
    用于表示游戏文件中可以提取原文和译文的class文件
    """
    logger = make_logger(f'jar_loader.py - JavaClassFile')

    def __init__(self, jar_file: JavaJarFile, path: Union[str, Path], type: str):
        self.path = PurePosixPath(path)

        self.jar_file = jar_file
        self.class_name = path.replace('/', '.').replace('.class', '')

        self.original_bytes = b''
        self.original_constant_table = None  # type: Optional[ConstantTable]

        self.translation_bytes = b''
        self.translation_constant_table = None  # type: Optional[ConstantTable]

        self.load_from_file()
        self.validate()

    def get_original_version(self) -> int:
        return self.original_bytes[7]

    def get_translation_version(self) -> int:
        return self.translation_bytes[7]

    def validate(self):
        # 检查是否为java class文件
        if self.original_bytes[:4] != MAGIC:
            raise ValueError(f'原文jar文件 {jar_file.path} 中的 {self.path} 不是有效的 java class 文件')
        if self.translation_bytes and self.translation_bytes[:4] != MAGIC:
            raise ValueError(f'译文jar文件 {jar_file.path} 中的 {self.path} 不是有效的 java class 文件')

        # 检查class文件版本
        if self.get_original_version() < MIN_CLASS_VER or self.get_original_version() > MAX_CLASS_VER:
            raise ValueError(f'原文jar文件 {jar_file.path} 中的 {self.path} 的版本不在1.5-1.7之间')
        if self.translation_bytes and self.get_translation_version() < MIN_CLASS_VER or self.get_translation_version() > MAX_CLASS_VER:
            raise ValueError(f'译文jar文件 {jar_file.path} 中的 {self.path} 的版本不在1.5-1.7之间')

    def _get_utf8_constant_pairs(self) -> List[Tuple[UTF8_Constant, Optional[UTF8_Constant]]]:
        """
        获取原文和译文中被引用过的的utf8常量对，如果译文中没有对应的utf8常量，则译文为None
        :return: utf8常量对
        """

        pairs = []

        translated_constant_index_constants = {c.constant_index: c for c in
                                               self.translation_constant_table.get_utf8_constants_which_have_string_ref()}

        for original_constant in self.original_constant_table.get_utf8_constants_which_have_string_ref():
            constant_index = original_constant.constant_index

            translated_constant = translated_constant_index_constants.get(constant_index, None)

            pairs.append((original_constant, translated_constant))

        return pairs

    def _get_original_string_to_const_pairs_mapping(self) -> Dict[
        str, List[Tuple[UTF8_Constant, Optional[UTF8_Constant]]]]:
        """
        构建原文内容到 constant pair(s) 的映射。如果多个constant的string相同，则一个原文内容对应多个constant pair
        :return: 原文内容到 UTF-8 constant pair(s) 的映射
        """

        original_string_to_const_pairs = {}  # type: Dict[str, List[Tuple[UTF8_Constant, Optional[UTF8_Constant]]]]

        for original, translation in self._get_utf8_constant_pairs():
            if original.string not in original_string_to_const_pairs:
                original_string_to_const_pairs[original.string] = [(original, translation)]
            else:
                original_string_to_const_pairs[original.string].append((original, translation))

        return original_string_to_const_pairs

    def get_strings(self) -> List[String]:
        strings = []
        for _, pairs in self._get_original_string_to_const_pairs_mapping().items():
            original_constant, translated_constant = pairs[0]

            key = f'{self.jar_file.path}//{self.path}#"{original_constant.string}"'

            original = original_constant.string
            translation = ''
            stage = 0

            if translated_constant:
                translation = translated_constant.string
                stage = 1

                if not contains_chinese(translation):
                    translation = ''
                    stage = 0

            context = ''
            for original_constant, translated_constant in pairs:
                context += self.generate_constant_context(original_constant, translated_constant)
                context += '\n\n'

            strings.append(String(key, original, translation, stage, context))
        return strings

    def generate_constant_context(self, original_constant: UTF8_Constant,
                                  translation_constant: Optional[UTF8_Constant]) -> str:
        if not translation_constant:
            return f'提取自 {self.jar_file.path}//{self.path} 的第{str(original_constant.constant_index).zfill(4)}个常量\n' \
                   f'原始数据："{original_constant.string}"\n' \
                   f'译文数据：无对应译文数据'

        return f'提取自 {self.jar_file.path}//{self.path} 的第{str(original_constant.constant_index).zfill(4)}个常量\n' \
               f'原始数据："{original_constant.string}"\n' \
               f'译文数据："{translation_constant.string}"'

    def update_strings(self, strings: List[String]):
        original_string_to_const_pairs = self._get_original_string_to_const_pairs_mapping()

        for s in strings:
            if s.stage > 0 and s.translation:
                if s.original in original_string_to_const_pairs:
                    for original, translation in original_string_to_const_pairs[s.original]:
                        if translation:
                            translation.string = s.translation
                else:
                    self.logger.warning(
                        f'在 {self.jar_file.path}//{self.path} 中没有找到原文为 "{s.original}" 的常量，未更新该词条')

    def generate_translated_bytecode(self) -> bytes:
        const_table_end_index = self.translation_constant_table.table_end_index

        new_translation_bytes = self.translation_bytes[:4 + 2 + 2]  # magic, minor_ver, major_ver
        new_translation_bytes += self.translation_constant_table.to_bytes()  # 常量表
        new_translation_bytes += self.translation_bytes[const_table_end_index:]  # 剩余部分

        return new_translation_bytes

    def load_from_file(self) -> None:
        path_str = str(self.path)

        self.original_bytes = self.jar_file.read_original_class_file(path_str)
        self.original_constant_table = ConstantTable(self.original_bytes)

        if self.jar_file.translation_file_exists(path_str):
            self.translation_bytes = self.jar_file.read_translation_class_file(path_str)
            self.translation_constant_table = ConstantTable(self.translation_bytes)


if __name__ == '__main__':
    jar_file = JavaJarFile.load_files()[0]
    print(jar_file.path)
    print(jar_file.class_files)
    print(jar_file.para_tranz_path)
    for class_file in jar_file.class_files.values():
        print(class_file.class_name)
        print(f'{class_file.get_original_version():#x}')
        strings = class_file.get_strings()
        for string in strings:
            # print(string)
            pass
        print(class_file.translation_bytes)
        print(class_file.generate_translated_bytecode())
