from dataclasses import dataclass, asdict
from typing import List, Tuple

from para_tranz.jar_loader.jar_file import JavaJarFile
from para_tranz.utils.mapping import JarMapItem, ClassFileMapItem, PARA_TRANZ_MAP
from para_tranz.utils.util import make_logger, colorize, RED, GREEN

logger = make_logger('JarStringSearch')

@dataclass
class StringSearchResult:
    jar_name: str
    class_path: str
    string: str
    is_in_mapping: bool
    is_excluded: bool = False

    def __hash__(self):
        return hash((self.jar_name, self.class_path, self.string))

    def __str__(self):
        string = self.string
        if self.is_excluded:
            string = colorize(string, RED)
        elif self.is_in_mapping:
            string = colorize(string, GREEN)
        return f'<{self.jar_name}:{self.class_path}>\n"{string}"'

def search_for_string_in_jar_files(pattern:str) -> List[StringSearchResult]:
    # 首先在当前映射表中查找
    results = set()

    logger.info(f'正在在映射表中查找字符串 "{pattern}"...')
    jar_file_items = [item for item in PARA_TRANZ_MAP.items if isinstance(item, JarMapItem)]
    for jar_item in jar_file_items:
        for class_file_item in jar_item.class_files:
            included, excluded = class_file_item.search_for_string(pattern)

            for string in included:
                results.add(StringSearchResult(jar_item.path, class_file_item.path, string, True, False))
            for string in excluded:
                results.add(StringSearchResult(jar_item.path, class_file_item.path, string, True, True))

    # 然后在所有类文件中查找
    logger.info(f'正在在所有类文件中查找字符串 "{pattern}"，耗时较长请耐心等待...')

    jar_files = [JavaJarFile(**asdict(item), no_auto_load=True) for item in jar_file_items]
    for jar_file in jar_files:
        jar_file.load_all_classes_in_jar(False, True)

        for class_file in jar_file.class_files.values():
            strings = class_file.get_strings()
            for s in strings:
                if pattern in s.original:
                    result = StringSearchResult(jar_file.path, class_file.path, s.original, False, False)
                    if result not in results:
                        results.add(result)

    logger.info(f'查找到 {len(results)} 个结果')
    return list(sorted(results, key=lambda x: (x.jar_name, x.class_path, x.string)))