from pathlib import Path, PurePosixPath
from typing import Union, Set, Optional, List, Tuple, Dict

from para_tranz.jar_loader.constant_table import ConstantTable, Utf8Constant
from para_tranz.utils.config import EXPORTED_STRING_CONTEXT_PREFIX_PREFIX, IGNORE_CONTEXT_PREFIX_MISMATCH_STRINGS, \
    MAGIC, MAX_STRING_KEY_LENGTH, MIN_CLASS_VER, MAX_CLASS_VER, ORIGINAL_TEXT_MATCH_IGNORE_WHITESPACE_CHARS, \
    EXPORTED_STRING_CONTEXT_PREFIX, UPDATE_STRING_ALLOW_EMPTY_TRANSLATION
from para_tranz.utils.mapping import ClassFileMapItem
from para_tranz.utils.util import hash_string, make_logger, String, contains_chinese, contains_english, url_encode


class JavaClassFile:
    """
    用于表示游戏文件中可以提取原文和译文的class文件

    @param jar_file: 该class文件所在的jar文件对象
    @param path: 该class文件在jar文件中的路径
    @param include_strings: 该class文件中需要提取的原文字符串列表，留空则提取全部字符串
    @param exclude_strings: 该class文件中不需要提取的原文字符串列表，留空则提取全部字符串
    """
    logger = make_logger(f'JavaClassFile')

    def __init__(self, jar_file: 'JavaJarFile', path: str, include_strings: Set[str] = None,
                 exclude_strings: Set[str] = None, no_auto_load: bool = False, **kwargs):
        self.path_str = path
        self.path = PurePosixPath(path)
        self.include_strings = set(include_strings) if include_strings else set()
        self.exclude_strings = set(exclude_strings) if exclude_strings else set()

        if ORIGINAL_TEXT_MATCH_IGNORE_WHITESPACE_CHARS:
            self.include_strings = {s.strip(' \t') for s in self.include_strings}
            self.exclude_strings = {s.strip(' \t') for s in self.exclude_strings}

        self.jar_file = jar_file
        self.class_name = path.replace('/', '.').replace('.class', '')

        self.original_bytes = b''
        self.original_constant_table = None  # type: Optional[ConstantTable]

        self.translation_bytes = b''
        self.translation_constant_table = None  # type: Optional[ConstantTable]

        if not no_auto_load:
            self.load_from_file()
            self.validate()

    def get_original_version(self) -> int:
        return self.original_bytes[7]

    def get_translation_version(self) -> int:
        return self.translation_bytes[7]

    def validate(self):
        # 检查是否为java class文件
        if self.original_bytes[:4] != MAGIC:
            raise ValueError(f'原文jar文件 {self.jar_file.path} 中的 {self.path} 不是有效的 java class 文件')
        if self.translation_bytes[:4] != MAGIC:
            raise ValueError(f'译文jar文件 {self.jar_file.path} 中的 {self.path} 不是有效的 java class 文件')

        # 检查class文件版本
        if (
                self.get_original_version() < MIN_CLASS_VER or self.get_original_version() > MAX_CLASS_VER):
            raise ValueError(f'原文jar文件 {self.jar_file.path} 中的 {self.path} 的版本不在1.5-1.7之间')
        if (
                self.get_translation_version() < MIN_CLASS_VER or self.get_translation_version() > MAX_CLASS_VER):
            raise ValueError(f'译文jar文件 {self.jar_file.path} 中的 {self.path} 的版本不在1.5-1.7之间')

        # TODO: 添加更多的检查

    def get_utf8_constant_pairs(self) -> List[Tuple[Utf8Constant, Optional[Utf8Constant]]]:
        """
        获取原文和译文中被引用过的的utf8常量对。
        返回结果会根据include_strings和exclude_strings进行过滤。
        :return: utf8常量对
        """

        pairs = []

        translated_constant_index_constants = {c.constant_index: c for c in
                                               self.translation_constant_table.get_utf8_constants_with_string_ref()}

        added_strings = set()

        original_constants = self.original_constant_table.get_utf8_constants_with_string_ref()
        for original_constant in original_constants:

            original_string = original_constant.string
            if ORIGINAL_TEXT_MATCH_IGNORE_WHITESPACE_CHARS:
                original_string = original_string.strip(' \t')

            # 过滤掉不需要翻译的字符串
            if self.include_strings and (original_string not in self.include_strings):
                continue
            if self.exclude_strings and (original_string in self.exclude_strings):
                continue

            constant_index = original_constant.constant_index

            try:
                translated_constant = translated_constant_index_constants[constant_index]
            except KeyError:
                self.logger.warning(
                    f'在 {self.jar_file.path}:{self.path} 的译文中未找到常量编号为 {constant_index} 的字符串，未进行提取')
                continue

            pairs.append((original_constant, translated_constant))
            added_strings.add(
                original_constant.string if not ORIGINAL_TEXT_MATCH_IGNORE_WHITESPACE_CHARS else original_string)

        # 如果在include_strings中的字符串未在译文中出现，则输出警告
        not_found_strings = self.include_strings - self.exclude_strings - added_strings
        for s in not_found_strings:
            self.logger.warning(
                f'在 {self.jar_file.path}:{self.path} 中未找到mapping中指定需要提取的字符串 "{s}"，未进行提取')

        return pairs

    def _get_original_string_to_const_pairs_mapping(self) -> Dict[
        str, List[Tuple[Utf8Constant, Utf8Constant]]]:
        """
        构建原文内容到 constant pair(s) 的映射。如果多个constant的string相同，则一个原文内容对应多个constant pair
        :return: 原文内容到 UTF-8 constant pair(s) 的映射
        """

        original_string_to_const_pairs = {}  # type: Dict[str, List[Tuple[Utf8Constant, Optional[Utf8Constant]]]]

        for original, translation in self.get_utf8_constant_pairs():
            if original.string not in original_string_to_const_pairs:
                original_string_to_const_pairs[original.string] = [(original, translation)]
            else:
                original_string_to_const_pairs[original.string].append((original, translation))

        return original_string_to_const_pairs

    def generate_string_key(self, original_constant: Utf8Constant) -> str:
        # 生成词条key
        # 格式： jar文件路径:类文件路径.class#"原文内容"
        full_key = f'{self.jar_file.path}:{self.path}#"{original_constant.string}"'
        
        if len(full_key) <= MAX_STRING_KEY_LENGTH:
            return full_key
        
        # 如果key长度超过最大长度限制，则缩短key长度
        # 生成的key格式： jar文件路径:类文件路径~#"原文内容~"@hash(key)
        # '~'用于表示字段过长被截断，如果没有被截断则不添加
        new_length:int = int(MAX_STRING_KEY_LENGTH * 0.8)
        new_path_length:int = int(new_length * 0.4)
        new_string_length:int = new_length - new_path_length
        
        key_hash = hash_string(full_key)
        path_str = str(self.path)
        new_path = path_str if len(path_str) <= new_path_length else path_str[:new_path_length-1] + '~'
        new_string = original_constant.string if len(original_constant.string) <= new_string_length else original_constant.string[:new_string_length-1] + '~'
        
        return f'{self.jar_file.path}:{new_path}#"{new_string}"@{key_hash}'
    
    def get_strings(self) -> List[String]:
        strings = []
        for _, pairs in self._get_original_string_to_const_pairs_mapping().items():
            original_constant, translated_constant = pairs[0]

            key = self.generate_string_key(original_constant)

            original = original_constant.string
            translation = ''
            stage = 0

            if translated_constant:
                translation = translated_constant.string
                stage = 1

                if not contains_english(translation):
                    stage = 1
                elif not contains_chinese(translation):
                    # translation = ''
                    stage = 0

            # 生成词条上下文信息
            context = ''
            for original_constant, translated_constant in pairs:
                # 此部分上下文信息也被用于在类名或原文内容过长时，还原词条的完整key使用
                # 因此在修改格式时必须一并修改 jar_file.py 中的 construct_string_key_from_context 方法
                context += f'{EXPORTED_STRING_CONTEXT_PREFIX}' \
                           f'提取自 {self.jar_file.path}:{self.path} 的第{str(original_constant.constant_index).zfill(4)}个常量\n' \
                           f'原始数据："{original_constant.string}"\n' \
                           f'译文数据："{translated_constant.string}"'
                context += '\n\n'

            strings.append(String(key, original, translation, stage, context))

        # 按已有的上下文信息排序
        # sorted_strings = sorted(strings, key=lambda s: s.context)
        # extra_context = '[同文件中的词条]\n'

        # 上下文信息：class文件中的其他string
        # for s in sorted_strings:
        #     extra_context += f'"{s.original}" => "{s.translation}"\n'
        #
        # for s in strings:
        #     s.context += extra_context
        return strings

    def update_strings(self, strings: List[String]) -> int:
        """
        根据传入的 strings 更新译文
        :param strings: 包含译文的string列表
        :return: 更新成功的词条数量
        """
        original_string_to_const_pairs = self._get_original_string_to_const_pairs_mapping()
        const_ref_by_other_attrs = self.original_constant_table.get_utf8_constants_with_extra_ref()
        update_success_count = 0

        for s in strings:
            if IGNORE_CONTEXT_PREFIX_MISMATCH_STRINGS and not s.context.startswith(EXPORTED_STRING_CONTEXT_PREFIX_PREFIX):
                self.logger.debug(
                    f'在 {self.jar_file.path}:{self.path} 中词条 key={s.key} 的词条上下文前缀与当前上下文前缀不匹配，跳过词条')
                continue
            # 如果原文在原文jar中存在
            if s.original in original_string_to_const_pairs:
                for original, translation in original_string_to_const_pairs[s.original]:
                    # 如果原文在原文jar中只被常量引用
                    if original not in const_ref_by_other_attrs:
                        # 如果译文已被翻译且不为空（这个条件写在里面是因为要优先报出“也被其他非string属性引用”的警告）
                        if s.stage > 0 and (s.translation or UPDATE_STRING_ALLOW_EMPTY_TRANSLATION):
                            translation.string = s.translation
                            update_success_count += 1
                        else:
                            # 如果词条尚未翻译，且译文文件内容与原文不同，则写入原文
                            if translation.string != s.original:
                                self.logger.warning(
                                    f'在 {self.jar_file.path}:{self.path} 中原文为 "{s.original}" 的译文词条尚未翻译，'
                                    f'但译文文件中内容与原文不同，将写入原文')
                                translation.string = s.original
                                update_success_count += 1
                    else:
                        self.logger.warning(
                            f'在 {self.jar_file.path}:{self.path} 中原文为 "{s.original}" 的常量'
                            f'也被其他非string属性引用，未更新该词条，需要手动更新')
            else:
                self.logger.warning(
                    f'在 {self.jar_file.path}:{self.path} 中没有找到原文为 "{s.original}" 的常量，未更新该词条')

        return update_success_count

    def generate_translated_bytecode(self) -> bytes:
        const_table_end_index = self.translation_constant_table.table_end_index

        new_translation_bytes = self.translation_bytes[:4 + 2 + 2]  # magic, minor_ver, major_ver
        new_translation_bytes += self.translation_constant_table.to_bytes()  # 常量表
        new_translation_bytes += self.translation_bytes[const_table_end_index:]  # 剩余部分

        return new_translation_bytes

    def load_from_file(self) -> None:
        path_str = str(self.path)

        self.logger.debug(f'正在读取 {self.jar_file.path}:{path_str} ...')

        self.original_bytes = self.jar_file.read_original_class_file(path_str)
        self.original_constant_table = ConstantTable(self.original_bytes)

        self.translation_bytes = self.jar_file.read_translation_class_file(path_str)
        self.translation_constant_table = ConstantTable(self.translation_bytes)

        self.logger.debug(
            f'class读取完成: {self.jar_file.path}:{path_str} ')

    def export_map_item(self) -> ClassFileMapItem:
        item = ClassFileMapItem(self.path_str)
        for s in self.get_strings():
            item.include_strings.add(s.original)
        return item

    def _debug_load_from_standalone_file(self) -> None:
        with open(self.path, 'rb') as f:
            self.original_bytes = f.read()
            self.original_constant_table = ConstantTable(self.original_bytes)
            self.translation_bytes = f.read()
            self.translation_constant_table = ConstantTable(self.original_bytes)


if __name__ == '__main__':
    from para_tranz.jar_loader.jar_file import JavaJarFile

    fake_jar_file = JavaJarFile(Path(r"starfarer.api.jar"), [])
    class_file = JavaClassFile(fake_jar_file, r"C:\Users\jinan\Desktop\ProcGenTestPluginImpl.class", no_auto_load=True)
    class_file._debug_load_from_standalone_file()
    for s in class_file.get_strings():
        print(s)
