import dataclasses
import json
from dataclasses import dataclass
from typing import Union, List, Iterator, Optional, Set, Tuple

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
        return json.dumps(dataclasses.asdict(self), indent=2, cls=SetEncoder, ensure_ascii=False)


@dataclass
class CsvMapItem(ParaTranzMapItem):
    id_column_name: Union[str, List[str]]
    text_column_names: List[str]


@dataclass
class ClassFileMapItem:
    path: str
    include_strings: Optional[Set[str]] = dataclasses.field(default_factory=set)
    exclude_strings: Optional[Set[str]] = dataclasses.field(default_factory=set)

    def as_json(self) -> str:
        return json.dumps(dataclasses.asdict(self), indent=2, cls=SetEncoder, ensure_ascii=False)

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

    def add_class_file_item(self, path: str, include_strings: Optional[List[str]] = None,
                            exclude_strings: Optional[List[str]] = None):
        self.class_files.append(ClassFileMapItem(path, include_strings, exclude_strings))

    def get_class_file_item(self, path: str, create: bool = False) -> Optional[ClassFileMapItem]:
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

    def get_jar_and_class_file_item_by_class_path(self, path: str) -> Optional[Tuple[JarMapItem, ClassFileMapItem]]:
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


PARA_TRANZ_MAP = ParaTranzMap()
PARA_TRANZ_MAP.load()

if __name__ == '__main__':
    # cls1 = ClassFileMapItem(path='path.class', include_strings=['include', 'include'], exclude_strings=['exclude'])
    # cls2 = ClassFileMapItem(path='path2.class', include_strings=['include2'], exclude_strings=['exclude2'])
    # jar = JarMapItem(type='jar', path='path.jar', class_files=[cls1, cls2])
    # print(dict(**dataclasses.asdict(jar)))
    PARA_TRANZ_MAP.save()
