import csv
import json
from collections import defaultdict
from dataclasses import asdict

from para_tranz.jar_loader.jar_file import JavaJarFile
from para_tranz.utils.config import PARA_TRANZ_PATH, PROJECT_DIRECTORY
from para_tranz.utils.mapping import PARA_TRANZ_MAP, JarMapItem
from para_tranz.utils.util import contains_chinese, make_logger

SCRIPT_PATH = PROJECT_DIRECTORY / 'para_tranz' / 'temporary_scripts' / 'data_ingestion'

CSV_PATH = SCRIPT_PATH / 'deathfly_098_api_mapping.csv'

MAPPING_OUTPUT_PATH = SCRIPT_PATH / 'deathfly_098_api_mapping.json'

JAR_NAME = 'starfarer.api.jar'

PARATRANZ_STRINGS_PATH = PARA_TRANZ_PATH / (JAR_NAME.removesuffix('.jar') + '.json')

logger = make_logger('deathfly_translation_mapping_ingestion.py')


def load_deathfly_data():
    with open(CSV_PATH, 'r', encoding='utf-8') as f:
        reader = csv.reader(f)
        data = list(reader)

    valid_data = []

    for row in data[1:]:
        if '"' in row[0]:
            if row[3] == '#N/A' or row[3] == '#VALUE!':
                row[3] = ''
            valid_data.append(row)

    return valid_data


def remove_deathfly_escaping(text: str) -> str:
    # 渡鸦提供的对照表中，\n\t是转义字符，需要转换为真正的换行符和制表符
    # '\"'是转义字符，需要转换为真正的双引号
    text = (
        text.replace('<\\n', '<')
        .replace('>\\n', '>')
        .replace('\\n', '\n')
        .replace('\\t', '\t')
        .replace('\\"', '"')
        .replace('&amp;', '&')
        .replace('&lt;', '<')
        .replace('&gt;', '>')
    )
    return text


def convert_deathfly_csv_to_paratranz_mapping():
    valid_data = load_deathfly_data()

    class_to_data = defaultdict(set)

    for row in valid_data:
        raw_labels = row[0].split(' - ')
        if len(raw_labels) == 3:
            original_text = row[2]
            class_name = raw_labels[0].split('.')[0].strip()

            if original_text.strip():
                class_to_data[class_name].add(original_text)

    paratranz_mapping = {'type': 'jar', 'path': JAR_NAME, 'class_files': []}

    class_to_data_sorted = sorted(class_to_data.items(), key=lambda x: x[0])

    class_files = []

    for class_name, data in class_to_data_sorted:
        include_strings = sorted(list(data))
        # 渡鸦提供的对照表中，\n\t是转义字符，需要转换为真正的换行符和制表符
        # '\"'是转义字符，需要转换为真正的双引号
        include_strings = [remove_deathfly_escaping(s) for s in include_strings]

        class_desc = {'path': class_name + '.class', 'include_strings': include_strings}

        class_files.append(class_desc)

    paratranz_mapping['class_files'] = class_files

    with open(MAPPING_OUTPUT_PATH, 'w', encoding='utf-8') as f:
        json.dump(paratranz_mapping, f, indent=2, ensure_ascii=False)


def add_translation_to_exported_strings():
    valid_data = load_deathfly_data()

    class_to_data = defaultdict(dict)

    for row in valid_data:
        raw_labels = row[0].split(' - ')
        if len(raw_labels) == 3:
            original_text = remove_deathfly_escaping(row[2])
            class_name = raw_labels[0].split('.')[0].strip()
            class_to_data[class_name][original_text] = remove_deathfly_escaping(row[3])

    jar_file_items = [
        item
        for item in PARA_TRANZ_MAP.items
        if isinstance(item, JarMapItem) and item.path == JAR_NAME
    ]
    jar_files = [
        JavaJarFile(**asdict(item), no_auto_load=False) for item in jar_file_items
    ]

    for jar_file in jar_files:
        if jar_file.path == JAR_NAME:
            for klass_name, klass in jar_file.class_files.items():
                klass_name = klass_name.removesuffix('.class')
                if klass_name in class_to_data:
                    original_to_translation = class_to_data[klass_name]
                    updated_strings = []
                    for string_item in klass.get_strings():
                        original_text = string_item.original
                        if original_text in original_to_translation:
                            new_translation = original_to_translation[original_text]
                            if (
                                contains_chinese(new_translation)
                                and new_translation != string_item.translation
                            ):
                                string_item.stage = 1  # 标记为已翻译
                                string_item.translation = new_translation
                                updated_strings.append(string_item)
                        else:
                            logger.warning(
                                f'类 {klass_name} 中的词条 "{original_text}" 不在对照表中，跳过'
                            )
                    klass.update_strings(updated_strings)
                else:
                    logger.warning(f'类 {klass_name} 不在对照表中，跳过')

            jar_file.save_json()


if __name__ == '__main__':
    # 先运行下面这个函数，生成mapping文件
    # convert_deathfly_csv_to_paratranz_mapping()

    # 然后把生成的mapping文件加到para_tranz_map.json里面
    # 然后运行脚本，选1导出string文件

    # 然后运行下面这个函数，把渡鸦提取的旧版本翻译加到para_tranz_map.json里面
    add_translation_to_exported_strings()

    # 然后运行脚本，选2导入string文件
    pass
