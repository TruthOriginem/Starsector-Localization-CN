import dataclasses
import json
import re
from dataclasses import dataclass
from typing import Dict, Iterator, List, Optional, Set, Tuple, Union, overload

from para_tranz.config import MAP_PATH
from para_tranz.utils.util import SetEncoder, make_logger

logger = make_logger('MappingLoader')


@dataclass(frozen=True)
class IncludeStringRule:
    val: str
    occurs: Optional[Set[int]] = None

    def to_json_value(self) -> Union[str, dict]:
        if self.occurs is None:
            return self.val
        return {'val': self.val, 'occurs': sorted(self.occurs)}


@dataclass
class ParaTranzMapItem:
    type: str
    path: str

    @classmethod
    def from_dict(cls, d: dict):
        if d['type'] == 'csv':
            return CsvMapItem(**d)
        elif d['type'] == 'jar':
            return JarMapItem.from_dict(d)
        elif d['type'] == 'java':
            return JavaMapItem(**d)
        elif d['type'] == 'json':
            return JsonMapItem(**d)
        elif d['type'] == 'txt':
            return TxtMapItem(**d)
        else:
            raise ValueError(f'Unknown type: {d["type"]}')

    def as_json(self) -> str:
        return json.dumps(
            dataclasses.asdict(self), indent=2, cls=SetEncoder, ensure_ascii=False
        )


@dataclass
class JsonMapItem(ParaTranzMapItem):
    text_paths: List[str]
    combined_output: Optional[str] = None


@dataclass
class TxtMapItem(ParaTranzMapItem):
    combined_output: Optional[str] = None


@dataclass
class CsvMapItem(ParaTranzMapItem):
    id_column_name: Union[str, List[str]]
    text_column_names: List[str]


@dataclass
class JavaMapItem(ParaTranzMapItem):
    include_strings: List[Union[str, dict]] = dataclasses.field(default_factory=list)

    def __post_init__(self):
        normalized = ClassFileMapItem(self.path, self.include_strings)
        self.include_strings = normalized.include_strings

    def get_include_rules(self) -> List[IncludeStringRule]:
        return ClassFileMapItem(self.path, self.include_strings).get_include_rules()

    def get_include_rule(self, val: str) -> Optional[IncludeStringRule]:
        return ClassFileMapItem(self.path, self.include_strings).get_include_rule(val)

    def get_include_values(self) -> Set[str]:
        return ClassFileMapItem(self.path, self.include_strings).get_include_values()

    def add_include_rule(self, rule: IncludeStringRule) -> None:
        item = ClassFileMapItem(self.path, self.include_strings)
        item.add_include_rule(rule)
        self.include_strings = item.include_strings

    def merge_from(self, other: 'JavaMapItem') -> None:
        for rule in other.get_include_rules():
            self.add_include_rule(rule)

    def search_for_string(self, pattern: str) -> List[str]:
        return ClassFileMapItem(self.path, self.include_strings).search_for_string(pattern)


@dataclass
class ClassFileMapItem:
    path: str
    include_strings: List[Union[str, dict]] = dataclasses.field(default_factory=list)

    def __post_init__(self):
        self.include_strings = self._normalize_include_strings(self.include_strings)

    def _normalize_include_strings(
        self, include_strings: Optional[Union[Set[str], List[Union[str, dict]]]]
    ) -> List[Union[str, dict]]:
        if not include_strings:
            return []

        rules_by_val = {}
        normalized = []

        for item in include_strings:
            rule = self._parse_include_string_rule(item)
            if rule.val in rules_by_val:
                raise ValueError(
                    f'类 {self.path} 的 include_strings 中重复声明了原文 "{rule.val}"，'
                    f'请合并或删除重复规则'
                )
            rules_by_val[rule.val] = rule
            normalized.append(rule.to_json_value())

        return sorted(
            normalized,
            key=lambda item: item if isinstance(item, str) else item['val'],
        )

    def _parse_include_string_rule(self, item: Union[str, dict]) -> IncludeStringRule:
        if isinstance(item, str):
            return IncludeStringRule(item, None)

        if not isinstance(item, dict):
            raise ValueError(
                f'类 {self.path} 的 include_strings 只能包含字符串或对象，发现：{item!r}'
            )

        if set(item.keys()) != {'val', 'occurs'}:
            raise ValueError(
                f'类 {self.path} 的 include_strings 对象必须只包含 val 和 occurs 字段，发现：{item!r}'
            )

        val = item['val']
        occurs = item['occurs']
        if not isinstance(val, str):
            raise ValueError(
                f'类 {self.path} 的 include_strings 对象 val 必须是字符串，发现：{item!r}'
            )

        if not isinstance(occurs, list) or len(occurs) == 0:
            raise ValueError(
                f'类 {self.path} 中原文 "{val}" 的 occurs 必须是非空整数数组'
            )

        occurs_set = set()
        for occurrence_index in occurs:
            if (
                not isinstance(occurrence_index, int)
                or isinstance(occurrence_index, bool)
                or occurrence_index < 0
            ):
                raise ValueError(
                    f'类 {self.path} 中原文 "{val}" 的 occurs 只能包含非负整数，发现：{occurrence_index!r}'
                )
            if occurrence_index in occurs_set:
                raise ValueError(
                    f'类 {self.path} 中原文 "{val}" 的 occurs 包含重复序号 {occurrence_index}'
                )
            occurs_set.add(occurrence_index)

        return IncludeStringRule(val, occurs_set)

    def get_include_rules(self) -> List[IncludeStringRule]:
        return [self._parse_include_string_rule(item) for item in self.include_strings]

    def get_include_rule(self, val: str) -> Optional[IncludeStringRule]:
        for rule in self.get_include_rules():
            if rule.val == val:
                return rule
        return None

    def get_include_values(self) -> Set[str]:
        return {rule.val for rule in self.get_include_rules()}

    def add_include_rule(self, rule: IncludeStringRule) -> None:
        if self.get_include_rule(rule.val) is not None:
            raise ValueError(
                f'类 {self.path} 的 include_strings 中重复声明了原文 "{rule.val}"'
            )
        self.include_strings.append(rule.to_json_value())
        self.include_strings = self._normalize_include_strings(self.include_strings)

    def merge_from(self, other: 'ClassFileMapItem') -> None:
        for rule in other.get_include_rules():
            self.add_include_rule(rule)

    def as_json(self) -> str:
        return json.dumps(
            dataclasses.asdict(self), indent=2, cls=SetEncoder, ensure_ascii=False
        )

    def search_for_string(self, pattern: str) -> List[str]:
        included = set()

        for rule in self.get_include_rules():
            if pattern in rule.val:
                included.add(rule.val)

        return sorted(list(included))


@dataclass
class JarMapItem(ParaTranzMapItem):
    class_files: List[ClassFileMapItem]

    def add_class_file_item(
        self,
        path: str,
        include_strings: Optional[List[Union[str, dict]]] = None,
    ):
        self.class_files.append(
            ClassFileMapItem(path, include_strings or [])
        )

    def get_class_file_item(
        self, path: str, create: bool = False
    ) -> Optional[ClassFileMapItem]:
        for item in self.class_files:
            if item.path == path:
                return item

        if create:
            item = ClassFileMapItem(path)
            self.class_files.append(item)
            return item

        return None

    @classmethod
    def from_dict(cls, d: dict):
        d['class_files'] = [
            ClassFileMapItem(
                path=item['path'],
                include_strings=item.get('include_strings') or [],
            )
            for item in d['class_files']
        ]
        return cls(**d)


class ParaTranzMap:
    def __init__(self):
        self.items: List[ParaTranzMapItem] = []

    @overload
    def __getitem__(self, item: int) -> ParaTranzMapItem: ...

    @overload
    def __getitem__(self, item: slice) -> List[ParaTranzMapItem]: ...

    def __getitem__(self, item: Union[int, slice]) -> Union[ParaTranzMapItem, List[ParaTranzMapItem]]:
        return self.items[item]

    def __iter__(self) -> Iterator[ParaTranzMapItem]:
        return iter(self.items)

    def get_item_by_path(self, path: str) -> Optional[ParaTranzMapItem]:
        for item in self.items:
            if item.path == path:
                return item
        return None

    def get_jar_and_class_file_item_by_class_path(
        self, path: str
    ) -> Optional[Tuple[JarMapItem, ClassFileMapItem]]:
        for item in self.items:
            if isinstance(item, JarMapItem):
                for class_file in item.class_files:
                    if class_file.path == path:
                        return item, class_file
        return None

    def load(self) -> None:
        with open(MAP_PATH, 'r', encoding='utf-8') as f:
            self.items = [ParaTranzMapItem.from_dict(item) for item in json.load(f)]

    def save(self) -> None:
        def _drop_none(d: dict) -> dict:
            return {k: v for k, v in d.items() if v is not None}

        data = [_drop_none(dataclasses.asdict(item)) for item in self.items]
        json_str = json.dumps(data, indent=2, cls=SetEncoder, ensure_ascii=False)
        # 将 "occurs" 数组折叠为单行（序号已在 to_json_value() 中排好序）
        json_str = re.sub(
            r'"occurs": \[([^\]]*)\]',
            lambda m: '"occurs": ['
            + ', '.join(x.strip().rstrip(',') for x in m.group(1).split('\n') if x.strip())
            + ']',
            json_str,
        )
        with open(MAP_PATH, 'w', encoding='utf-8') as f:
            f.write(json_str)

    def format(self) -> int:
        """整理 map：合并重复 jar class 和重复 java 文件项。
        include_strings 严格禁止同一 val 重复声明。
        返回合并掉的重复条目数量。"""
        merged = 0
        java_items_by_path: Dict[str, JavaMapItem] = {}
        formatted_items: List[ParaTranzMapItem] = []

        for item in self.items:
            if isinstance(item, JarMapItem):
                seen: Dict[str, ClassFileMapItem] = {}
                for cls in item.class_files:
                    if cls.path in seen:
                        seen[cls.path].merge_from(cls)
                        merged += 1
                    else:
                        seen[cls.path] = cls
                item.class_files = sorted(seen.values(), key=lambda c: c.path)
                formatted_items.append(item)
                continue

            if isinstance(item, JavaMapItem):
                existing = java_items_by_path.get(item.path)
                if existing is not None:
                    existing.merge_from(item)
                    merged += 1
                else:
                    java_items_by_path[item.path] = item
                    formatted_items.append(item)
                continue

            formatted_items.append(item)

        self.items = formatted_items
        return merged


PARA_TRANZ_MAP = ParaTranzMap()
PARA_TRANZ_MAP.load()

if __name__ == '__main__':
    # cls1 = ClassFileMapItem(path='path.class', include_strings=['include', 'include'])
    # cls2 = ClassFileMapItem(path='path2.class', include_strings=['include2'])
    # jar = JarMapItem(type='jar', path='path.jar', class_files=[cls1, cls2])
    # print(dict(**dataclasses.asdict(jar)))
    PARA_TRANZ_MAP.save()
