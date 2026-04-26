import json
import re
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Dict, List, Optional, Sequence, Union

from para_tranz.config import (
    EXPORTED_STRING_CONTEXT_PREFIX,
    EXPORTED_STRING_CONTEXT_PREFIX_PREFIX,
    IGNORE_CONTEXT_PREFIX_MISMATCH_STRINGS,
    MAX_STRING_KEY_LENGTH,
    ORIGINAL_PATH,
    UPDATE_STRING_ALLOW_EMPTY_TRANSLATION,
)
from para_tranz.utils.mapping import IncludeStringRule, JavaMapItem, PARA_TRANZ_MAP
from para_tranz.utils.util import (
    DataFile,
    String,
    contains_chinese,
    contains_english,
    hash_string,
    make_logger,
    relative_path,
    should_write_translation,
)


@dataclass(frozen=True)
class JavaStringLiteral:
    value: str
    start: int
    end: int
    raw: str
    source_index: int


@dataclass(frozen=True)
class JavaStringOccurrence:
    original_literal: JavaStringLiteral
    translation_literal: JavaStringLiteral
    occurrence_index: int
    occurrence_total: int
    include_occurrence_index: bool


@dataclass(frozen=True)
class JavaStringContext:
    path: str
    source_index: int
    original: str
    occurrence_index: Optional[int] = None


class JavaSourceFile(DataFile):
    """Java 源码文件中的普通字符串字面量导入导出。"""

    logger = make_logger('JavaSourceFile')
    re_context = re.compile(
        r'文件：(?P<path>.*?\.java)\n'
        r'源码序号：(?P<source_index>\d+)\n'
        r'(?:同值序号：(?P<occurrence>\d+)\n)?'
        r'原始数据："(?P<original>.*?)"\n'
        r'译文数据：',
        re.DOTALL,
    )

    def __init__(
        self,
        path: Union[str, Path],
        include_strings: Optional[List[Union[str, dict]]] = None,
        type: str = 'java',
        **kwargs,
    ):
        super().__init__(path, type)
        self.map_item = JavaMapItem(type=type, path=str(path), include_strings=include_strings or [])
        self.original_text = ''
        self.translation_text = ''
        self.original_literals: List[JavaStringLiteral] = []
        self.translation_literals: List[JavaStringLiteral] = []
        self.load_from_file()

    def load_from_file(self) -> None:
        self.original_text = self._read_file(self.original_path)
        self.translation_text = self._read_file(self.translation_path)
        self.original_literals = self._parse_string_literals(self.original_text)
        self.translation_literals = self._parse_string_literals(self.translation_text)

    def _read_file(self, path: Path) -> str:
        if not path.exists():
            self.logger.warning(f'文件不存在：{relative_path(path)}')
            return ''
        with open(path, 'r', encoding='utf-8') as f:
            return f.read()

    @staticmethod
    def _decode_java_string(raw_inner: str) -> str:
        result = []
        i = 0
        while i < len(raw_inner):
            char = raw_inner[i]
            if char != '\\':
                result.append(char)
                i += 1
                continue

            i += 1
            if i >= len(raw_inner):
                result.append('\\')
                break

            escaped = raw_inner[i]
            simple_escapes = {
                'b': '\b',
                't': '\t',
                'n': '\n',
                'f': '\f',
                'r': '\r',
                '"': '"',
                "'": "'",
                '\\': '\\',
            }
            if escaped in simple_escapes:
                result.append(simple_escapes[escaped])
                i += 1
                continue

            if escaped == 'u':
                i += 1
                while i < len(raw_inner) and raw_inner[i] == 'u':
                    i += 1
                hex_digits = raw_inner[i : i + 4]
                if len(hex_digits) == 4 and all(c in '0123456789abcdefABCDEF' for c in hex_digits):
                    result.append(chr(int(hex_digits, 16)))
                    i += 4
                else:
                    result.append('\\u')
                continue

            if escaped in '01234567':
                octal = escaped
                i += 1
                max_extra = 2 if escaped in '0123' else 1
                while (
                    max_extra > 0
                    and i < len(raw_inner)
                    and raw_inner[i] in '01234567'
                ):
                    octal += raw_inner[i]
                    i += 1
                    max_extra -= 1
                result.append(chr(int(octal, 8)))
                continue

            result.append(escaped)
            i += 1

        return ''.join(result)

    @staticmethod
    def _encode_java_string(value: str) -> str:
        escaped = []
        for char in value:
            if char == '\\':
                escaped.append('\\\\')
            elif char == '"':
                escaped.append('\\"')
            elif char == '\n':
                escaped.append('\\n')
            elif char == '\r':
                escaped.append('\\r')
            elif char == '\t':
                escaped.append('\\t')
            elif char == '\b':
                escaped.append('\\b')
            elif char == '\f':
                escaped.append('\\f')
            else:
                escaped.append(char)
        return '"' + ''.join(escaped) + '"'

    @classmethod
    def _parse_string_literals(cls, text: str) -> List[JavaStringLiteral]:
        literals = []
        i = 0
        source_index = 0
        length = len(text)

        while i < length:
            char = text[i]
            next_char = text[i + 1] if i + 1 < length else ''

            if char == '/' and next_char == '/':
                i += 2
                while i < length and text[i] not in '\r\n':
                    i += 1
                continue

            if char == '/' and next_char == '*':
                i += 2
                while i + 1 < length and not (text[i] == '*' and text[i + 1] == '/'):
                    i += 1
                i = min(i + 2, length)
                continue

            if char == "'":
                i += 1
                while i < length:
                    if text[i] == '\\':
                        i += 2
                    elif text[i] == "'":
                        i += 1
                        break
                    else:
                        i += 1
                continue

            if char != '"':
                i += 1
                continue

            if text.startswith('"""', i):
                i += 3
                while i < length and not text.startswith('"""', i):
                    i += 1
                i = min(i + 3, length)
                continue

            start = i
            i += 1
            raw_inner = []
            while i < length:
                if text[i] == '\\':
                    raw_inner.append(text[i])
                    if i + 1 < length:
                        raw_inner.append(text[i + 1])
                    i += 2
                    continue
                if text[i] == '"':
                    i += 1
                    raw = text[start:i]
                    literals.append(
                        JavaStringLiteral(
                            value=cls._decode_java_string(''.join(raw_inner)),
                            start=start,
                            end=i,
                            raw=raw,
                            source_index=source_index,
                        )
                    )
                    source_index += 1
                    break
                raw_inner.append(text[i])
                i += 1

        return literals

    @staticmethod
    def _format_occurrence_index(occurrence_index: Optional[int]) -> str:
        if occurrence_index is None:
            return ''
        return f'（同值序号：{occurrence_index}）'

    def _path_key(self) -> str:
        return self.path.as_posix()

    def _get_original_string_literals_mapping(self) -> Dict[str, List[JavaStringLiteral]]:
        literals_by_original: Dict[str, List[JavaStringLiteral]] = {}
        for literal in self.original_literals:
            literals_by_original.setdefault(literal.value, []).append(literal)
        return literals_by_original

    def _get_included_string_occurrences(self) -> List[JavaStringOccurrence]:
        literals_by_original = self._get_original_string_literals_mapping()
        include_rules = {rule.val: rule for rule in self.map_item.get_include_rules()}
        has_include_rules = bool(include_rules)
        added_strings = set()
        occurrences = []

        for original_string, original_literals in literals_by_original.items():
            rule = include_rules.get(original_string)
            if has_include_rules and rule is None:
                continue
            if not has_include_rules:
                continue

            if rule is not None and rule.occurs is not None:
                max_occurrence = len(original_literals) - 1
                out_of_range = sorted(
                    occurrence
                    for occurrence in rule.occurs
                    if occurrence > max_occurrence
                )
                if out_of_range:
                    raise ValueError(
                        f'在 {self.path} 中原文 "{original_string}" '
                        f'只出现 {len(original_literals)} 次，但 include_strings 指定了序号 {out_of_range}'
                    )

            for occurrence_index, original_literal in enumerate(original_literals):
                if rule is not None and rule.occurs is not None:
                    if occurrence_index not in rule.occurs:
                        continue

                try:
                    translation_literal = self.translation_literals[
                        original_literal.source_index
                    ]
                except IndexError:
                    self.logger.warning(
                        f'在 {self.path} 的译文中未找到原文 "{original_string}"'
                        f'{self._format_occurrence_index(occurrence_index)} 对应的源码序号 '
                        f'{original_literal.source_index}，未进行提取'
                    )
                    continue

                occurrences.append(
                    JavaStringOccurrence(
                        original_literal=original_literal,
                        translation_literal=translation_literal,
                        occurrence_index=occurrence_index,
                        occurrence_total=len(original_literals),
                        include_occurrence_index=(
                            len(original_literals) > 1
                            or (rule is not None and rule.occurs is not None)
                        ),
                    )
                )
                added_strings.add(original_string)

        not_found_strings = self.map_item.get_include_values() - added_strings
        for original in not_found_strings:
            rule = self.map_item.get_include_rule(original)
            occurs = ''
            if rule and rule.occurs is not None:
                occurs = f'（include_strings 指定同值序号：{sorted(rule.occurs)}）'
            self.logger.warning(
                f'在 {self.path} 中未找到 mapping 中指定需要提取的字符串 "{original}"{occurs}，未进行提取'
            )

        return occurrences

    def generate_string_key(
        self, literal: JavaStringLiteral, occurrence_index: Optional[int] = None
    ) -> str:
        path_key = self._path_key()
        if occurrence_index is None:
            full_key = f'{path_key}#"{literal.value}"'
        else:
            full_key = f'{path_key}#"{literal.value}":{occurrence_index}'

        full_key_escaped = json.dumps(full_key)[1:-1]
        if len(full_key_escaped) <= MAX_STRING_KEY_LENGTH:
            return full_key

        new_length = int(MAX_STRING_KEY_LENGTH * 0.8)
        new_path_length = int(new_length * 0.4)
        new_string_length = new_length - new_path_length

        key_hash = hash_string(full_key)
        path_str = path_key
        new_path = (
            path_str
            if len(path_str) <= new_path_length
            else path_str[: new_path_length - 1] + '~'
        )
        new_string = (
            literal.value
            if len(literal.value) <= new_string_length
            else literal.value[: new_string_length - 1] + '~'
        )

        if occurrence_index is None:
            return f'{new_path}#"{new_string}"@{key_hash}'
        return f'{new_path}#"{new_string}":{occurrence_index}@{key_hash}'

    def get_strings(self) -> List[String]:
        strings = []
        for occurrence in self._get_included_string_occurrences():
            original_literal = occurrence.original_literal
            translation_literal = occurrence.translation_literal
            occurrence_index = (
                occurrence.occurrence_index
                if occurrence.include_occurrence_index
                else None
            )
            key = self.generate_string_key(original_literal, occurrence_index)

            translation = translation_literal.value
            stage = 1 if translation else 0
            if translation:
                if not contains_english(translation):
                    stage = 1
                elif not contains_chinese(translation):
                    stage = 0

            context = (
                f'{EXPORTED_STRING_CONTEXT_PREFIX}'
                f'文件：{self._path_key()}\n'
                f'源码序号：{original_literal.source_index}\n'
            )
            if occurrence.include_occurrence_index:
                context += f'同值序号：{occurrence.occurrence_index}\n'
            context += (
                f'原始数据："{original_literal.value}"\n'
                f'译文数据："{translation_literal.value}"'
            )

            strings.append(
                String(
                    key=key,
                    original=original_literal.value,
                    translation=translation,
                    stage=stage,
                    context=context,
                )
            )
        return strings

    @classmethod
    def parse_java_string_context(cls, context: str) -> JavaStringContext:
        match = cls.re_context.search(context)
        if not match:
            raise ValueError(f'无法从上下文中解析 Java 源码词条定位信息：\n{context}')

        occurrence_index = match.group('occurrence')
        return JavaStringContext(
            path=match.group('path'),
            source_index=int(match.group('source_index')),
            original=match.group('original'),
            occurrence_index=int(occurrence_index)
            if occurrence_index is not None
            else None,
        )

    def update_strings(self, strings: List[String]) -> None:
        literals_by_original = self._get_original_string_literals_mapping()
        include_rules = {rule.val: rule for rule in self.map_item.get_include_rules()}
        replacements: Dict[int, str] = {}

        for s in strings:
            if IGNORE_CONTEXT_PREFIX_MISMATCH_STRINGS and not s.context.startswith(
                EXPORTED_STRING_CONTEXT_PREFIX_PREFIX
            ):
                self.logger.debug(
                    f'在 {self.path} 中词条 key={s.key} 的词条上下文前缀与当前上下文前缀不匹配，跳过词条'
                )
                continue

            context = self.parse_java_string_context(s.context)
            if context.path != self._path_key():
                raise ValueError(
                    f'词条 key={s.key}{self._format_occurrence_index(context.occurrence_index)} '
                    f'的上下文 Java 文件为 {context.path}，但当前正在更新 {self._path_key()}'
                )

            rule = include_rules.get(context.original)
            if rule is None:
                self.logger.warning(
                    f'在 {self.path} 中原文为 "{context.original}"'
                    f'{self._format_occurrence_index(context.occurrence_index)} 的词条不在 include_strings 中，'
                    f'请从平台上删除该词条 key={s.key} 或修改 include_strings'
                )
                continue

            if rule.occurs is not None:
                if context.occurrence_index is None:
                    self.logger.warning(
                        f'词条 key={s.key} 的上下文没有同值序号，但 include_strings 对原文 '
                        f'"{context.original}" 指定了 occurs，未写入译文'
                    )
                    continue
                if context.occurrence_index not in rule.occurs:
                    self.logger.warning(
                        f'词条 key={s.key}{self._format_occurrence_index(context.occurrence_index)} '
                        f'不在 include_strings 指定的 occurs 中，未写入译文'
                    )
                    continue

            original_literals = literals_by_original.get(context.original)
            if not original_literals:
                self.logger.warning(
                    f'在 {self.path} 中找不到原文 "{context.original}"，未写入词条 key={s.key}'
                )
                continue

            if context.occurrence_index is not None:
                if context.occurrence_index >= len(original_literals):
                    self.logger.warning(
                        f'词条 key={s.key}{self._format_occurrence_index(context.occurrence_index)} '
                        f'指定的同值序号越界，未写入译文'
                    )
                    continue
                original_literal = original_literals[context.occurrence_index]
            else:
                if len(original_literals) > 1:
                    self.logger.warning(
                        f'词条 key={s.key} 的上下文没有同值序号，但原文在 {self.path} 中出现 '
                        f'{len(original_literals)} 次，未写入译文'
                    )
                    continue
                original_literal = original_literals[0]

            if original_literal.source_index != context.source_index:
                self.logger.warning(
                    f'词条 key={s.key}{self._format_occurrence_index(context.occurrence_index)} '
                    f'的源码序号从 {context.source_index} 变为 {original_literal.source_index}，'
                    f'仍按 original 当前定位写入'
                )

            if original_literal.source_index >= len(self.translation_literals):
                self.logger.warning(
                    f'在译文文件 {relative_path(self.translation_path)} 中找不到源码序号 '
                    f'{original_literal.source_index}，未写入词条 key={s.key}'
                )
                continue

            if should_write_translation(s, UPDATE_STRING_ALLOW_EMPTY_TRANSLATION):
                replacements[original_literal.source_index] = s.translation

        if not replacements:
            return

        self.translation_text = self._replace_translation_literals(replacements)
        self.translation_literals = self._parse_string_literals(self.translation_text)

    def _replace_translation_literals(self, replacements: Dict[int, str]) -> str:
        parts = []
        last_index = 0
        for literal in self.translation_literals:
            if literal.source_index not in replacements:
                continue
            parts.append(self.translation_text[last_index:literal.start])
            parts.append(self._encode_java_string(replacements[literal.source_index]))
            last_index = literal.end
        parts.append(self.translation_text[last_index:])
        return ''.join(parts)

    def save_file(self) -> None:
        self.translation_path.parent.mkdir(parents=True, exist_ok=True)
        with open(self.translation_path, 'w', encoding='utf-8') as f:
            f.write(self.translation_text)
        self.logger.info(f'已保存译文文件：{relative_path(self.translation_path)}')

    @classmethod
    def load_files_from_config(cls) -> Sequence['JavaSourceFile']:
        cls.logger.info('开始读取 Java 源码数据')
        files = [
            cls(**asdict(item)) for item in PARA_TRANZ_MAP if isinstance(item, JavaMapItem)
        ]
        cls.logger.info('Java 源码数据读取完成')
        return files

    def export_map_item(self) -> JavaMapItem:
        item = JavaMapItem(type='java', path=self._path_key())
        literals_by_original = self._get_original_string_literals_mapping()
        for original, literals in literals_by_original.items():
            if len(literals) == 1:
                item.add_include_rule(IncludeStringRule(original, None))
            else:
                item.add_include_rule(IncludeStringRule(original, set(range(len(literals)))))
        return item
