import dataclasses
import json
from dataclasses import dataclass
from typing import Iterator, List, Optional, Set, Tuple, Union

from para_tranz.utils.config import MAP_PATH
from para_tranz.utils.util import SetEncoder, make_logger

logger = make_logger('MappingLoader')


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
        else:
            raise ValueError(f'Unknown type: {d["type"]}')

    def as_json(self) -> str:
        return json.dumps(
            dataclasses.asdict(self), indent=2, cls=SetEncoder, ensure_ascii=False
        )


@dataclass
class CsvMapItem(ParaTranzMapItem):
    id_column_name: Union[str, List[str]]
    text_column_names: List[str]


@dataclass
class ClassFileMapItem:
    path: str
    include_strings: Optional[Set[str]] = dataclasses.field(default_factory=set)
    exclude_strings: Optional[Set[str]] = dataclasses.field(default_factory=set)

    def __post_init__(self):
        # 从 JSON 加载时 include_strings/exclude_strings 是 list，需转为 set 以去重
        if not isinstance(self.include_strings, set):
            self.include_strings = set(self.include_strings) if self.include_strings else set()
        if not isinstance(self.exclude_strings, set):
            self.exclude_strings = set(self.exclude_strings) if self.exclude_strings else set()

    def as_json(self) -> str:
        return json.dumps(
            dataclasses.asdict(self), indent=2, cls=SetEncoder, ensure_ascii=False
        )

    def search_for_string(self, pattern: str) -> Tuple[List[str], List[str]]:
        included = set()
        excluded = set()

        for s in self.include_strings:
            if pattern in s:
                included.add(s)
        for s in self.exclude_strings:
            if pattern in s:
                excluded.add(s)
                included.discard(s)

        return sorted(list(included)), sorted(list(excluded))


@dataclass
class JarMapItem(ParaTranzMapItem):
    class_files: List[ClassFileMapItem]

    def add_class_file_item(
        self,
        path: str,
        include_strings: Optional[List[str]] = None,
        exclude_strings: Optional[List[str]] = None,
    ):
        self.class_files.append(
            ClassFileMapItem(path, include_strings, exclude_strings)
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
        d['class_files'] = [ClassFileMapItem(**item) for item in d['class_files']]
        return cls(**d)


class ParaTranzMap:
    def __init__(self):
        self.items = []

    def __getitem__(self, item) -> Union[CsvMapItem, JarMapItem]:
        return self.items[item]

    def __iter__(self) -> Iterator[Union[CsvMapItem, JarMapItem]]:
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
        data = [dataclasses.asdict(item) for item in self.items]
        with open(MAP_PATH, 'w', encoding='utf-8') as f:
            json.dump(data, f, indent=2, cls=SetEncoder)

    def dedup_and_sort(self) -> int:
        """对所有 jar 条目的类文件列表去重（合并同路径条目）并按路径排序。
        include_strings/exclude_strings 为 set，已自动去重；保存时 SetEncoder 会排序。
        返回合并掉的重复类条目数量。"""
        merged = 0
        for item in self.items:
            if not isinstance(item, JarMapItem):
                continue
            seen: dict[str, ClassFileMapItem] = {}
            for cls in item.class_files:
                if cls.path in seen:
                    seen[cls.path].include_strings |= cls.include_strings
                    seen[cls.path].exclude_strings |= cls.exclude_strings
                    merged += 1
                else:
                    seen[cls.path] = cls
            item.class_files = sorted(seen.values(), key=lambda c: c.path)
        return merged


PARA_TRANZ_MAP = ParaTranzMap()
PARA_TRANZ_MAP.load()

if __name__ == '__main__':
    # cls1 = ClassFileMapItem(path='path.class', include_strings=['include', 'include'], exclude_strings=['exclude'])
    # cls2 = ClassFileMapItem(path='path2.class', include_strings=['include2'], exclude_strings=['exclude2'])
    # jar = JarMapItem(type='jar', path='path.jar', class_files=[cls1, cls2])
    # print(dict(**dataclasses.asdict(jar)))
    PARA_TRANZ_MAP.save()
