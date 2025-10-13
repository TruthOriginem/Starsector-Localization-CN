import csv
import json
import re
from collections import defaultdict
from dataclasses import asdict

from para_tranz.jar_loader.jar_file import JavaJarFile
from para_tranz.utils.config import PROJECT_DIRECTORY, PARA_TRANZ_PATH
from para_tranz.utils.mapping import PARA_TRANZ_MAP, JarMapItem
from para_tranz.utils.util import make_logger, contains_english, contains_chinese

SCRIPT_PATH = PROJECT_DIRECTORY / 'para_tranz' / 'temporary_scripts' / 'data_ingestion'

CSV_PATH = SCRIPT_PATH  / 'deathfly_098_obf_mapping.csv'

MAPPING_OUTPUT_PATH = SCRIPT_PATH / 'deathfly_098_obf_mapping.json'

JAR_NAME = 'starfarer_obf.jar'

PARATRANZ_STRINGS_PATH = PARA_TRANZ_PATH / (JAR_NAME.removesuffix('.jar') + '.json')

logger = make_logger(f'deathfly_translation_mapping_ingestion.py')

def load_deathfly_data():
    with open(CSV_PATH, 'r', encoding='utf-8') as f:
        reader = csv.reader(f)
        data = list(reader)

    valid_data = []

    for row in data[1:]:
        if '"' in row[0]:
            valid_data.append(row)

    return valid_data

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

    paratranz_mapping = {
        "type": "jar",
        "path": JAR_NAME,
        "class_files": []
    }

    class_to_data_sorted = sorted(class_to_data.items(), key=lambda x: x[0])

    class_files = []

    for class_name, data in class_to_data_sorted:
        include_strings = sorted(list(data))
        # 渡鸦提供的对照表中，\n\t是转义字符，需要转换为真正的换行符和制表符
        # '\"'是转义字符，需要转换为真正的双引号
        include_strings = [s.replace('\\n', '\n').replace('\\t', '\t').replace('\\"', '"') for s in include_strings]

        class_desc = {
            "path": class_name + ".class",
            "include_strings": include_strings
        }

        class_files.append(class_desc)

    paratranz_mapping["class_files"] = class_files

    with open(MAPPING_OUTPUT_PATH, 'w', encoding='utf-8') as f:
        json.dump(paratranz_mapping, f, indent=2, ensure_ascii=False)

def add_translation_to_exported_strings():
    valid_data = load_deathfly_data()

    class_to_data = defaultdict(dict)

    for row in valid_data:
        raw_labels = row[0].split(' - ')
        if len(raw_labels) == 3:
            original_text = row[2]
            class_name = raw_labels[0].split('.')[0].strip()

            if original_text.strip() and row[3].strip():
                class_to_data[class_name][original_text] = row[3]

    jar_file_items = [item for item in PARA_TRANZ_MAP.items if isinstance(item, JarMapItem) and item.path == JAR_NAME]
    jar_files = [JavaJarFile(**asdict(item), no_auto_load=False) for item in jar_file_items]

    print(jar_files)

if __name__ == '__main__':
    # 先运行下面这个函数，生成mapping文件
    # convert_deathfly_csv_to_paratranz_mapping()

    # 然后把生成的mapping文件加到para_tranz_map.json里面
    # 然后运行脚本，选1导出string文件

    # 然后运行下面这个函数，把渡鸦提取的旧版本翻译加到para_tranz_map.json里面
    add_translation_to_exported_strings()

    # 然后运行脚本，选2导入string文件
    pass