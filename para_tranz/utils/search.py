from dataclasses import asdict, dataclass
from typing import List, Optional

from para_tranz.jar_loader.jar_file import JavaJarFile
from para_tranz.utils.mapping import PARA_TRANZ_MAP, JarMapItem
from para_tranz.utils.util import GREEN, RED, colorize, make_logger

logger = make_logger('JarStringSearch')

# 缓存已加载的 jar 文件，避免每次搜索都重新解析所有 class 文件
_jar_files_cache: Optional[List[JavaJarFile]] = None


def _get_loaded_jar_files() -> List[JavaJarFile]:
    global _jar_files_cache
    if _jar_files_cache is None:
        logger.info('首次加载，正在解析所有 jar 文件中的 class，耗时较长请耐心等待...')
        jar_file_items = [
            item for item in PARA_TRANZ_MAP.items if isinstance(item, JarMapItem)
        ]
        _jar_files_cache = [
            JavaJarFile(**asdict(item), no_auto_load=True) for item in jar_file_items
        ]
        for jar_file in _jar_files_cache:
            jar_file.load_all_classes_in_jar(False, True)
        logger.info('加载完成，后续搜索将直接复用缓存')
    return _jar_files_cache


@dataclass
class StringSearchResult:
    jar_name: str
    class_path: str
    string: str
    is_in_mapping: bool
    is_excluded: bool = False

    def __hash__(self):
        return hash((self.jar_name, self.class_path, self.string))

    def __eq__(self, other):
        return (self.jar_name, self.class_path, self.string) == (
            other.jar_name,
            other.class_path,
            other.string,
        )

    def __str__(self):
        string = self.string
        if self.is_excluded:
            string = colorize(string, RED)
        elif self.is_in_mapping:
            string = colorize(string, GREEN)
        return f'{self.jar_name}:{self.class_path}\n\t"{string}"'


def search_for_string_in_jar_files(pattern: str) -> List[StringSearchResult]:
    # 首先在当前映射表中查找
    results = set()

    logger.info(f'正在在映射表中查找字符串 "{pattern}"...')
    jar_file_items = [
        item for item in PARA_TRANZ_MAP.items if isinstance(item, JarMapItem)
    ]
    for jar_item in jar_file_items:
        for class_file_item in jar_item.class_files:
            included, excluded = class_file_item.search_for_string(pattern)

            for string in included:
                results.add(
                    StringSearchResult(
                        jar_item.path, class_file_item.path, string, True, False
                    )
                )
            for string in excluded:
                results.add(
                    StringSearchResult(
                        jar_item.path, class_file_item.path, string, True, True
                    )
                )

    # 然后在所有类文件中查找（jar 文件首次使用时加载，后续复用缓存）
    logger.info(f'正在在所有类文件中查找字符串 "{pattern}"...')

    for jar_file in _get_loaded_jar_files():
        for class_file in jar_file.class_files.values():
            strings = class_file.get_strings()
            for s in strings:
                if pattern in s.original:
                    result = StringSearchResult(
                        jar_file.path, str(class_file.path), s.original, False, False
                    )
                    if result not in results:
                        results.add(result)

    logger.info(f'查找到 {len(results)} 个结果')
    return list(sorted(results, key=lambda x: (x.jar_name, x.class_path, x.string)))


def print_search_results(results: List[StringSearchResult]) -> None:
    """打印 search_for_string_in_jar_files 的结果"""
    if not results:
        print('未找到任何结果')
    else:
        for r in results:
            print(r)
