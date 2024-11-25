import json
from dataclasses import asdict
from typing import Optional, Tuple

from para_tranz.jar_loader.class_file import JavaClassFile
from para_tranz.jar_loader.jar_file import JavaJarFile
from para_tranz.utils.mapping import PARA_TRANZ_MAP, ClassFileMapItem, JarMapItem
from para_tranz.utils.util import normalize_class_path, colorize, RED, GREEN, make_logger

logger = make_logger('MappingGenerator')

def generate_class_mapping_diff_string(existing_class_item: ClassFileMapItem, generated_class_map_item: ClassFileMapItem) -> str:
    included_strings = existing_class_item.include_strings
    excluded_strings = existing_class_item.exclude_strings

    diff_str = '  "include_strings": [\n'
    for s in sorted(list(generated_class_map_item.include_strings)):
        if s in excluded_strings:
            diff_str += f'    "{colorize(s, RED)}",\n'
        elif s in included_strings:
            diff_str += f'    "{colorize(s, GREEN)}",\n'
        else:
            diff_str += f'    "{s}",\n'
    diff_str = diff_str[:-2] + '\n'
    diff_str += '  ]\n'

    return diff_str

def generate_class_file_mapping(class_file_path: str) -> Optional[Tuple[JarMapItem, ClassFileMapItem, str]]:
    jar_item = None # type: Optional[JarMapItem]
    existing_class_item = None # type: Optional[ClassFileMapItem]
    class_file = None # type: Optional[JavaClassFile]

    # 如果路径中包含冒号，说明指定了jar文件
    if ':' in class_file_path:
        jar_path, raw_class_path = class_file_path.split(':')
        class_path = normalize_class_path(raw_class_path)

        jar_item = PARA_TRANZ_MAP.get_item_by_path(jar_path)
        if not jar_item:
            logger.error(f'未找到jar文件映射项：{jar_path}')
            return

        existing_class_item = jar_item.get_class_file_item(class_path)
        jar_item.class_files = [existing_class_item or ClassFileMapItem(path=class_path)]

        jar_file_items = [jar_item]

    # 否则，只有类文件路径，需要尝试在所有jar文件中搜索
    else:
        class_path = normalize_class_path(class_file_path)
        result = PARA_TRANZ_MAP.get_jar_and_class_file_item_by_class_path(class_path)

        # 如果在 para_tranz_map.json 中找到了类文件映射项，那么就可以确定所属的jar文件
        if result:
            jar_item, existing_class_item = result
            jar_item.class_files = [existing_class_item]
            jar_file_items = [jar_item]

        # 否则，需要手动为每一个jar文件映射添加类文件映射项
        else:
            class_item = ClassFileMapItem(path=class_path)
            jar_file_items = [item for item in PARA_TRANZ_MAP.items if isinstance(item, JarMapItem)]
            for jar_item in jar_file_items:
                jar_item.class_files = [class_item]

    # 依次尝试在每一个jar中加载类文件
    for item in jar_file_items:
        try:
            jar_file = JavaJarFile(**asdict(item))
            class_file = jar_file.class_files[class_path]
            break
        except Exception as ignored:
            pass

    if not class_file:
        logger.error('未找到类文件映射项')
        return

    generated_class_map_item = class_file.export_map_item()

    diff_str = ''

    # 如果在 para_tranz_map.json 中找到了已有的类文件映射项，那么就可以生成对比信息
    if existing_class_item:
        diff_str = generate_class_mapping_diff_string(existing_class_item, generated_class_map_item)

    return jar_item, generated_class_map_item, diff_str