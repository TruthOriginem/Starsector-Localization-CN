import dataclasses
import json
from dataclasses import asdict
from typing import Optional, Set, Tuple

from para_tranz.jar_loader.jar_file import JavaJarFile
from para_tranz.utils.mapping import PARA_TRANZ_MAP, ClassFileMapItem, JarMapItem
from para_tranz.utils.util import (
    BG_YELLOW,
    GREEN,
    RED,
    colorize,
    make_logger,
    normalize_class_path,
)

logger = make_logger('MappingGenerator')


def generate_class_mapping_diff_string(
    target_class_map: ClassFileMapItem,
    source_class_map: ClassFileMapItem,
    extra_ref_strings: Optional[Set[str]] = None,
) -> str:
    """
    生成类文件映射项的对比信息
    将检测源类映射 include_strings 中的每个字符串是否在目标类映射中出现/排除

    :param target_class_map: 目标类文件映射
    :param source_class_map: 源类文件映射
    :param extra_ref_strings: 同时被非string属性引用的字符串集合（无法自动写回，用黄色背景标注）

    :return: 可打印的对比信息，带有ANSI颜色标记
    """

    included_strings = target_class_map.include_strings
    excluded_strings = target_class_map.exclude_strings

    diff_str = f'  "path": "{source_class_map.path}",\n'
    diff_str += '  "include_strings": [\n'
    for s in sorted(list(source_class_map.include_strings)):
        # JSON 转义字符串内容（去掉 json.dumps 产生的外层引号），保证含双引号的字符串输出合法
        s_escaped = json.dumps(s, ensure_ascii=False)[1:-1]
        if s in excluded_strings:
            text = colorize(s_escaped, RED)
        elif s in included_strings:
            text = colorize(s_escaped, GREEN)
        else:
            text = s_escaped
        if extra_ref_strings and s in extra_ref_strings:
            text = colorize(text, BG_YELLOW)
        diff_str += f'    "{text}",\n'
    diff_str = diff_str[:-2] + '\n'
    diff_str += '  ]\n'

    return diff_str


def generate_class_file_mapping_by_path(
    class_file_path: str,
) -> Optional[Tuple[Optional[JarMapItem], ClassFileMapItem, Optional[ClassFileMapItem], Set[str]]]:
    """
    通过类文件路径查找类，并生成类文件映射项

    :param class_file_path: 类文件路径，格式为：[jar文件路径:]类文件路径[.class]。其中类文件路径可以使用'/'或'.'分隔每个包名和类名
    :return: 类所处的jar文件映射项、生成的类文件映射项、已存在的类文件映射项（如果存在）
    """

    jar_item = None  # type: Optional[JarMapItem]
    existing_class_item = None  # type: Optional[ClassFileMapItem]
    class_file = None  # type: Optional[JavaClassFile]

    # 如果路径中包含冒号，说明指定了jar文件
    if ':' in class_file_path:
        jar_path, raw_class_path = class_file_path.split(':')
        class_path = normalize_class_path(raw_class_path)

        jar_item = PARA_TRANZ_MAP.get_item_by_path(jar_path)
        if not jar_item:
            logger.error(f'未找到jar文件映射项：{jar_path}')
            return

        existing_class_item = jar_item.get_class_file_item(class_path)
        # 同 else 分支，创建副本避免污染 PARA_TRANZ_MAP
        jar_file_items = [
            dataclasses.replace(jar_item, class_files=[ClassFileMapItem(path=class_path)])
        ]

    # 否则，只有类文件路径，需要尝试在所有jar文件中搜索
    else:
        class_path = normalize_class_path(class_file_path)
        result = PARA_TRANZ_MAP.get_jar_and_class_file_item_by_class_path(class_path)

        # 如果在 para_tranz_map.json 中找到了类文件映射项，那么就可以确定所属的jar文件
        if result:
            jar_item, existing_class_item = result
            # 用 dataclasses.replace 创建副本而非直接修改 jar_item，
            # 避免污染 PARA_TRANZ_MAP 中的原始对象（否则循环调用时后续查找会失败）
            jar_file_items = [
                dataclasses.replace(jar_item, class_files=[ClassFileMapItem(path=class_path)])
            ]

        # 否则，需要手动为每一个jar文件映射添加类文件映射项
        else:
            class_item = ClassFileMapItem(path=class_path)
            jar_file_items = [
                dataclasses.replace(item, class_files=[class_item])  # 同上，创建副本
                for item in PARA_TRANZ_MAP.items
                if isinstance(item, JarMapItem)
            ]

    # 依次尝试在每一个jar中加载类文件
    for item in jar_file_items:
        try:
            jar_file = JavaJarFile(**asdict(item))
            class_file = jar_file.class_files[class_path]
            break
        except Exception:
            pass

    if not class_file:
        logger.error('未找到类文件映射项')
        return

    generated_class_map_item = class_file.export_map_item()
    extra_ref_strings = {
        c.string
        for c in class_file.original_constant_table.get_utf8_constants_with_extra_ref()
    }

    return jar_item, generated_class_map_item, existing_class_item, extra_ref_strings


def print_class_mapping_result(
    result: Optional[Tuple[Optional[JarMapItem], ClassFileMapItem, Optional[ClassFileMapItem], Set[str]]]
) -> None:
    """打印 generate_class_file_mapping_by_path 的结果"""
    if not result:
        return
    jar_item, class_item, existing_class_item, extra_ref_strings = result
    print('所属jar文件：', jar_item.path if jar_item else '未知')
    print('以下是生成的类文件映射项：')
    print(class_item.as_json())
    if existing_class_item:
        print(
            f'以下是与当前存在的映射项的对比'
            f'（{colorize("绿色", GREEN)}=已包含  '
            f'{colorize("红色", RED)}=已排除  '
            f'无色=未包含  '
            f'{colorize("黄色背景", BG_YELLOW)}=同时被非string属性引用，无法自动写回）：'
        )
        print(generate_class_mapping_diff_string(existing_class_item, class_item, extra_ref_strings))
    else:
        print('此类未包含在当前映射表中')
