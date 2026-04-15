import json
import re
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Dict, List, Optional, Set, Tuple

from para_tranz.jar_loader.constant_table import ConstantTable, Utf8Constant
from para_tranz.config import (
    EXPORTED_STRING_CONTEXT_PREFIX,
    EXPORTED_STRING_CONTEXT_PREFIX_PREFIX,
    IGNORE_CONTEXT_PREFIX_MISMATCH_STRINGS,
    MAGIC,
    MAX_CLASS_VER,
    MAX_STRING_KEY_LENGTH,
    MIN_CLASS_VER,
    ORIGINAL_TEXT_MATCH_IGNORE_WHITESPACE_CHARS,
    UPDATE_STRING_ALLOW_EMPTY_TRANSLATION,
)
from para_tranz.utils.mapping import ClassFileMapItem, IncludeStringRule
from para_tranz.utils.util import (
    String,
    contains_chinese,
    contains_english,
    hash_string,
    make_logger,
)


@dataclass(frozen=True)
class JarStringContext:
    jar_path: str
    class_path: str
    original: str
    occurrence_index: Optional[int] = None


@dataclass(frozen=True)
class StringOccurrence:
    """
    表示同一 class 文件中，某个原文字符串在常量池中的一次出现。

    occurrence_index（同值序号）：0-based，在"同一 class 内原文相同的 UTF-8 常量"中按
        常量池索引（constant_index）升序编号。
        只统计被至少一个 StringConstant 直接引用的 UTF-8 常量
        （即 get_utf8_constants_with_string_ref() 返回的条目）。
        注意：若某 UTF-8 常量同时被字段名、方法名等非 String 属性引用（extra_ref），
        写回时会跳过并 WARNING；这类常量通常是解耦前的共享常量，应从 map 的 occurs 列表中删除。
        当 occurrence_total > 1 时，occurrence_index 写入 context 和 key；唯一出现时省略。

    occurrence_total：同一 class 内该原文被 StringConstant 引用的 UTF-8 常量总数。
    """

    original_constant: Utf8Constant
    translated_constant: Utf8Constant
    occurrence_index: int
    occurrence_total: int


class JavaClassFile:
    """
    用于表示游戏文件中可以提取原文和译文的class文件

    @param jar_file: 该class文件所在的jar文件对象
    @param path: 该class文件在jar文件中的路径
    @param include_strings: 该class文件中需要提取的原文字符串列表，留空则提取全部字符串
    """

    logger = make_logger('JavaClassFile')
    # context 格式（导出时写入，导入时解析）：
    #   文件：xxx.jar
    #   类：xxx.class
    #   常量号：NNNN          （仅供人工核对，不作为导入定位主键）
    #   同值序号：N            （可选，仅当 occurrence_total > 1 时存在）
    #   原始数据："..."
    #   译文数据："..."
    # 导入时以 jar + class + original + 同值序号 唯一定位词条，不依赖 key。
    re_context = re.compile(
        r'文件：(?P<jar>.*?\.jar)\n'
        r'类：(?P<class>.*?\.class)\n'
        r'常量号：(?P<constant>\d+)\n'
        r'(?:同值序号：(?P<occurrence>\d+)\n)?'
        r'原始数据："(?P<original>.*?)"\n'
        r'译文数据：',
        re.DOTALL,
    )

    def __init__(
        self,
        jar_file: 'JavaJarFile',
        path: str,
        include_strings: Optional[List] = None,
        no_auto_load: bool = False,
        **kwargs,
    ) -> None:
        self.path_str = path
        self.path = PurePosixPath(path)
        self.map_item = ClassFileMapItem(path, include_strings)

        if ORIGINAL_TEXT_MATCH_IGNORE_WHITESPACE_CHARS:
            include_rules = []
            for rule in self.map_item.get_include_rules():
                val = rule.val.strip(' \t')
                include_rules.append(IncludeStringRule(val, rule.occurs))
            self.map_item = ClassFileMapItem(
                path, [rule.to_json_value() for rule in include_rules]
            )

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

    def validate(self) -> None:
        # 检查是否为java class文件
        if self.original_bytes[:4] != MAGIC:
            raise ValueError(
                f'原文jar文件 {self.jar_file.path} 中的 {self.path} 不是有效的 java class 文件'
            )
        if self.translation_bytes[:4] != MAGIC:
            raise ValueError(
                f'译文jar文件 {self.jar_file.path} 中的 {self.path} 不是有效的 java class 文件'
            )

        # 检查class文件版本
        if (
            self.get_original_version() < MIN_CLASS_VER
            or self.get_original_version() > MAX_CLASS_VER
        ):
            raise ValueError(
                f'原文jar文件 {self.jar_file.path} 中的 {self.path} 的版本不在1.5-1.7之间'
            )
        if (
            self.get_translation_version() < MIN_CLASS_VER
            or self.get_translation_version() > MAX_CLASS_VER
        ):
            raise ValueError(
                f'译文jar文件 {self.jar_file.path} 中的 {self.path} 的版本不在1.5-1.7之间'
            )

        # TODO: 添加更多的检查

    def get_utf8_constant_pairs(
        self,
    ) -> List[Tuple[Utf8Constant, Optional[Utf8Constant]]]:
        """
        获取原文和译文中被引用过的的utf8常量对。
        返回结果会根据include_strings进行过滤。
        :return: utf8常量对
        """

        return [
            (occurrence.original_constant, occurrence.translated_constant)
            for occurrence in self._get_included_string_occurrences()
        ]

    def _normalize_original_string(self, original: str) -> str:
        if ORIGINAL_TEXT_MATCH_IGNORE_WHITESPACE_CHARS:
            return original.strip(' \t')
        return original

    @staticmethod
    def _format_occurrence_index(occurrence_index: Optional[int]) -> str:
        if occurrence_index is None:
            return ''
        return f'（同值序号：{occurrence_index}）'

    def _get_original_string_constants_mapping(self) -> Dict[str, List[Utf8Constant]]:
        """
        返回原文 class 中，所有被 StringConstant 引用的 UTF-8 常量，按原文分组。
        同一原文的常量按 constant_index 升序排列，下标即为该常量的同值序号（occurrence_index）。

        注意：仅扫描被 StringConstant 引用的 UTF-8 常量（get_utf8_constants_with_string_ref()）。
        被字段名等非 String 属性同时引用的常量（extra_ref）也会出现在结果中，
        但 update_strings() 会在写回时检测并跳过，并发出 WARNING。
        这类常量通常对应解耦前的共享常量，应通过在 map 的 occurs 列表中显式排除对应序号来处理。
        """
        constants_by_original: Dict[str, List[Utf8Constant]] = {}

        for original_constant in (
            self.original_constant_table.get_utf8_constants_with_string_ref()
        ):
            original_string = self._normalize_original_string(original_constant.string)
            constants_by_original.setdefault(original_string, []).append(
                original_constant
            )

        for constants in constants_by_original.values():
            constants.sort(key=lambda c: c.constant_index)

        return constants_by_original

    def _get_translation_constants_by_index(self) -> Dict[int, Utf8Constant]:
        return {
            c.constant_index: c
            for c in self.translation_constant_table.get_utf8_constants_with_string_ref()
        }

    def _get_included_string_occurrences(self) -> List[StringOccurrence]:
        constants_by_original = self._get_original_string_constants_mapping()
        translated_constant_index_constants = self._get_translation_constants_by_index()
        include_rules = {rule.val: rule for rule in self.map_item.get_include_rules()}
        has_include_rules = bool(include_rules)
        added_strings = set()
        occurrences = []

        for original_string, original_constants in constants_by_original.items():
            rule = include_rules.get(original_string)
            if has_include_rules and rule is None:
                continue

            if rule is not None and rule.occurs is not None:
                max_occurrence = len(original_constants) - 1
                out_of_range = sorted(
                    occurrence
                    for occurrence in rule.occurs
                    if occurrence > max_occurrence
                )
                if out_of_range:
                    raise ValueError(
                        f'在 {self.jar_file.path}:{self.path} 中原文 "{original_string}" '
                        f'只出现 {len(original_constants)} 次，但 include_strings 指定了序号 {out_of_range}'
                    )

            for occurrence_index, original_constant in enumerate(original_constants):
                if rule is not None and rule.occurs is not None:
                    if occurrence_index not in rule.occurs:
                        continue

                constant_index = original_constant.constant_index
                try:
                    translated_constant = translated_constant_index_constants[
                        constant_index
                    ]
                except KeyError:
                    self.logger.warning(
                        f'在 {self.jar_file.path}:{self.path} 的译文中未找到'
                        f'原文 "{original_string}"{self._format_occurrence_index(occurrence_index)}'
                        f' 对应的常量编号为 {constant_index} 的字符串，未进行提取'
                    )
                    continue

                occurrences.append(
                    StringOccurrence(
                        original_constant=original_constant,
                        translated_constant=translated_constant,
                        occurrence_index=occurrence_index,
                        occurrence_total=len(original_constants),
                    )
                )
                added_strings.add(original_string)

        not_found_strings = self.map_item.get_include_values() - added_strings
        for s in not_found_strings:
            rule = self.map_item.get_include_rule(s)
            occurs = ''
            if rule and rule.occurs is not None:
                occurs = f'（include_strings 指定同值序号：{sorted(rule.occurs)}）'
            self.logger.warning(
                f'在 {self.jar_file.path}:{self.path} 中未找到mapping中指定需要提取的字符串 "{s}"{occurs}，未进行提取'
            )

        return occurrences

    def generate_string_key(
        self, original_constant: Utf8Constant, occurrence_index: Optional[int] = None
    ) -> str:
        # 生成词条key
        # 唯一原文格式：  jar文件路径:类文件路径.class#"原文内容"
        # 重复原文格式：  jar文件路径:类文件路径.class#同值序号:"原文内容"
        # 同值序号（occurrence_index）仅在 occurrence_total > 1 时传入，见 get_strings()。
        # key 只用于唯一标识和平台同步；导入时以 context 中的同值序号定位，不从 key 解析。
        if occurrence_index is None:
            full_key = f'{self.jar_file.path}:{self.path}#"{original_constant.string}"'
        else:
            full_key = (
                f'{self.jar_file.path}:{self.path}#'
                f'{occurrence_index}:"{original_constant.string}"'
            )

        # 测量key长度
        full_key_escaped = json.dumps(full_key)[1:-1]
        if len(full_key_escaped) <= MAX_STRING_KEY_LENGTH:
            return full_key

        # 如果key长度超过最大长度限制，则缩短key长度
        # 生成的key格式： jar文件路径:类文件路径~#"原文内容~"@hash(key)
        # '~'用于表示字段过长被截断，如果没有被截断则不添加
        new_length: int = int(MAX_STRING_KEY_LENGTH * 0.8)
        new_path_length: int = int(new_length * 0.4)
        new_string_length: int = new_length - new_path_length

        key_hash = hash_string(full_key)
        path_str = str(self.path)
        new_path = (
            path_str
            if len(path_str) <= new_path_length
            else path_str[: new_path_length - 1] + '~'
        )
        new_string = (
            original_constant.string
            if len(original_constant.string) <= new_string_length
            else original_constant.string[: new_string_length - 1] + '~'
        )

        if occurrence_index is None:
            return f'{self.jar_file.path}:{new_path}#"{new_string}"@{key_hash}'
        return f'{self.jar_file.path}:{new_path}#{occurrence_index}:"{new_string}"@{key_hash}'

    def get_strings(self) -> List[String]:
        strings = []
        for occurrence in self._get_included_string_occurrences():
            original_constant = occurrence.original_constant
            translated_constant = occurrence.translated_constant
            # 只有 occurrence_total > 1 时才在 key/context 中写入同值序号。
            # 唯一原文不写同值序号，保持旧格式 key，与平台已有词条兼容。
            occurrence_index = (
                occurrence.occurrence_index
                if occurrence.occurrence_total > 1
                else None
            )

            key = self.generate_string_key(original_constant, occurrence_index)

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

            context = (
                f'{EXPORTED_STRING_CONTEXT_PREFIX}'
                f'文件：{self.jar_file.path}\n'
                f'类：{self.path}\n'
                f'常量号：{str(original_constant.constant_index).zfill(4)}\n'
            )
            if occurrence.occurrence_total > 1:
                context += f'同值序号：{occurrence.occurrence_index}\n'
            context += (
                f'原始数据："{original_constant.string}"\n'
                f'译文数据："{translated_constant.string}"'
            )

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

    @classmethod
    def parse_jar_string_context(cls, context: str) -> JarStringContext:
        match = cls.re_context.search(context)
        if not match:
            raise ValueError(f'无法从上下文中解析 jar 词条定位信息：\n{context}')

        occurrence_index = match.group('occurrence')
        return JarStringContext(
            jar_path=match.group('jar'),
            class_path=match.group('class'),
            original=match.group('original'),
            occurrence_index=int(occurrence_index)
            if occurrence_index is not None
            else None,
        )

    def update_strings(self, strings: List[String]) -> int:
        """
        根据传入的 strings 更新译文
        :param strings: 包含译文的string列表
        :return: 更新成功的词条数量
        """
        constants_by_original = self._get_original_string_constants_mapping()
        translated_constant_index_constants = self._get_translation_constants_by_index()
        const_ref_by_other_attrs = (
            self.original_constant_table.get_utf8_constants_with_extra_ref()
        )
        include_values = self.map_item.get_include_values()
        update_success_count = 0

        for s in strings:
            if IGNORE_CONTEXT_PREFIX_MISMATCH_STRINGS and not s.context.startswith(
                EXPORTED_STRING_CONTEXT_PREFIX_PREFIX
            ):
                self.logger.debug(
                    f'在 {self.jar_file.path}:{self.path} 中词条 key={s.key} 的词条上下文前缀与当前上下文前缀不匹配，跳过词条'
                )
                continue

            context = self.parse_jar_string_context(s.context)
            if context.jar_path != str(self.jar_file.path):
                raise ValueError(
                    f'词条 key={s.key}{self._format_occurrence_index(context.occurrence_index)} 的上下文 jar 为 {context.jar_path}，'
                    f'但当前正在更新 {self.jar_file.path}'
                )
            if context.class_path != str(self.path):
                raise ValueError(
                    f'词条 key={s.key}{self._format_occurrence_index(context.occurrence_index)} 的上下文 class 为 {context.class_path}，'
                    f'但当前正在更新 {self.path}'
                )

            original = self._normalize_original_string(context.original)
            if include_values and original not in include_values:
                self.logger.warning(
                    f'在 {self.jar_file.path}:{self.path} 中原文为 "{context.original}"'
                    f'{self._format_occurrence_index(context.occurrence_index)} 的词条不在 include_strings 中，'
                    f'请从平台上删除该词条 key={s.key} 或修改 include_strings'
                )
                continue

            # 检查 occurrence_index 是否在 include_strings 规则的 occurs 中
            if include_values and context.occurrence_index is not None:
                rule = self.map_item.get_include_rule(original)
                if rule is not None and rule.occurs is not None and context.occurrence_index not in rule.occurs:
                    self.logger.warning(
                        f'在 {self.jar_file.path}:{self.path} 中原文为 "{context.original}"'
                        f'（同值序号：{context.occurrence_index}） 的词条，'
                        f'该同值序号不在 include_strings 的 occurs 列表 {sorted(rule.occurs)} 中，'
                        f'请从平台上删除该词条 key={s.key} 或修改 include_strings'
                    )
                    continue

            if original not in constants_by_original:
                self.logger.warning(
                    f'在 {self.jar_file.path}:{self.path} 中没有找到原文为 "{context.original}"'
                    f'{self._format_occurrence_index(context.occurrence_index)} 的常量，'
                    f'未写入词条 key={s.key} 的译文'
                )
                continue

            original_constants = constants_by_original[original]
            if context.occurrence_index is None:
                if len(original_constants) != 1:
                    raise ValueError(
                        f'词条 key={s.key} 的上下文没有 同值序号，但在 {self.jar_file.path}:{self.path} 中'
                        f'原文 "{context.original}" 出现了 {len(original_constants)} 次，无法唯一定位'
                    )
                original_constant = original_constants[0]
            else:
                if context.occurrence_index >= len(original_constants):
                    raise ValueError(
                        f'词条 key={s.key}{self._format_occurrence_index(context.occurrence_index)} 指定的同值序号越界，'
                        f'但在 {self.jar_file.path}:{self.path} 中原文 "{context.original}" '
                        f'只出现了 {len(original_constants)} 次'
                    )
                original_constant = original_constants[context.occurrence_index]

            translation = translated_constant_index_constants.get(
                original_constant.constant_index
            )
            if translation is None:
                self.logger.warning(
                    f'在 {self.jar_file.path}:{self.path} 的译文中未找到原文 "{context.original}"'
                    f'{self._format_occurrence_index(context.occurrence_index)} 对应的'
                    f'常量编号为 {original_constant.constant_index} 的字符串，未写入词条 key={s.key}'
                )
                continue

            # 如果原文在原文jar中只被常量引用
            if original_constant not in const_ref_by_other_attrs:
                # 如果译文已被翻译且不为空（这个条件写在里面是因为要优先报出“也被其他非string属性引用”的警告）
                if s.stage > 0 and (
                    s.translation or UPDATE_STRING_ALLOW_EMPTY_TRANSLATION
                ):
                    translation.string = s.translation
                    update_success_count += 1
                else:
                    # 如果词条尚未翻译，且译文文件内容与原文不同，则写入原文
                    if translation.string != context.original:
                        self.logger.warning(
                            f'在 {self.jar_file.path}:{self.path} 中原文为 "{context.original}"'
                            f'{self._format_occurrence_index(context.occurrence_index)} 的译文词条尚未翻译，'
                            f'但译文文件中内容与原文不同，将写入原文'
                        )
                        translation.string = context.original
                        update_success_count += 1
            else:
                self.logger.warning(
                    f'在 {self.jar_file.path}:{self.path} 中原文为 "{context.original}"'
                    f'{self._format_occurrence_index(context.occurrence_index)} 的常量'
                    f'也被其他非string属性引用，未更新该词条，需要手动更新'
                )

        return update_success_count

    def generate_translated_bytecode(self) -> bytes:
        const_table_end_index = self.translation_constant_table.table_end_index

        new_translation_bytes = self.translation_bytes[
            : 4 + 2 + 2
        ]  # magic, minor_ver, major_ver
        new_translation_bytes += self.translation_constant_table.to_bytes()  # 常量表
        new_translation_bytes += self.translation_bytes[
            const_table_end_index:
        ]  # 剩余部分

        return new_translation_bytes

    def load_from_file(self) -> None:
        path_str = str(self.path)

        self.logger.debug(f'正在读取 {self.jar_file.path}:{path_str} ...')

        self.original_bytes = self.jar_file.read_original_class_file(path_str)
        self.original_constant_table = ConstantTable(self.original_bytes)

        self.translation_bytes = self.jar_file.read_translation_class_file(path_str)
        self.translation_constant_table = ConstantTable(self.translation_bytes)

        self.logger.debug(f'class读取完成: {self.jar_file.path}:{path_str} ')

    def export_map_item(self) -> ClassFileMapItem:
        item = ClassFileMapItem(self.path_str)
        constants_by_original = self._get_original_string_constants_mapping()
        for original, constants in constants_by_original.items():
            if len(constants) == 1:
                item.add_include_rule(IncludeStringRule(original, None))
            else:
                item.add_include_rule(
                    IncludeStringRule(original, set(range(len(constants))))
                )
        return item

    def _debug_load_from_standalone_file(self) -> None:
        with open(self.path, 'rb') as f:
            self.original_bytes = f.read()
            self.original_constant_table = ConstantTable(self.original_bytes)
            self.translation_bytes = f.read()
            self.translation_constant_table = ConstantTable(self.original_bytes)


if __name__ == '__main__':
    from para_tranz.jar_loader.jar_file import JavaJarFile

    fake_jar_file = JavaJarFile(Path(r'starfarer.api.jar'), [])
    class_file = JavaClassFile(
        fake_jar_file,
        r'C:\Users\jinan\Desktop\ProcGenTestPluginImpl.class',
        no_auto_load=True,
    )
    class_file._debug_load_from_standalone_file()
    for s in class_file.get_strings():
        print(s)
