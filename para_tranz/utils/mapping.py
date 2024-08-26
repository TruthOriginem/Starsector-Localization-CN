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


@dataclass
class CsvMapItem(ParaTranzMapItem):
    id_column_name: Union[str, List[str]]
    text_column_names: List[str]


@dataclass
class ClassFileMapItem:
    path: str
    include_strings: Optional[Set[str]] = dataclasses.field(default_factory=set)
    exclude_strings: Optional[Set[str]] = dataclasses.field(default_factory=set)


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

    def get_jar_and_class_file_item(self, path: str, create: bool = False, one_class_only: bool = False) -> Optional[
            Tuple[JarMapItem, ClassFileMapItem]]:
        """
        根据路径获取jar和类文件映射项
        :param path: 路径，格式为 jar_file.jar:com/example/Example.class
        :param create: 如果不存在是否创建类文件映射项
        :param one_class_only: 返回的jar映射项是否只包含这一个类文件映射项
        """
        jar_path, class_path = path.split(':', 1)

        jar_item = self.get_item_by_path(jar_path)
        if jar_item is None:
            logger.error(f'在配置中未找到jar文件映射项：{jar_path}')
            return None

        class_item = jar_item.get_class_file_item(class_path, create)
        if class_item is None:
            return None

        if one_class_only:
            jar_item = JarMapItem(jar_item.type, jar_item.path, [class_item])

        return jar_item, class_item

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
