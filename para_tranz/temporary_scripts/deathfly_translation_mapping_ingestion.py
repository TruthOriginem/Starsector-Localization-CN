import csv
import json

from para_tranz.utils.config import PROJECT_DIRECTORY, PARA_TRANZ_PATH
from para_tranz.utils.util import make_logger

CSV_PATH = PROJECT_DIRECTORY / 'para_tranz' / 'temporary_scripts' / 'deathfly_096_mapping.csv'

MAPPING_OUTPUT_PATH = PROJECT_DIRECTORY / 'para_tranz' / 'temporary_scripts' / 'deathfly_096_mapping.json'

PARATRANZ_STRINGS_PATH = PARA_TRANZ_PATH / 'starfarer_obf.json'

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
    data = []

    valid_data = load_deathfly_data()

    class_to_data = {}

    for row in valid_data:
        class_name = row[1]
        if class_name not in class_to_data:
            class_to_data[class_name] = set()
        original_text = row[0][1:-1]
        if original_text.strip(' '):
            class_to_data[class_name].add(original_text)

    paratranz_mapping = {
        "type": "jar",
        "path": "starfarer_obf.jar",
        "class_files": []
    }

    class_to_data_sorted = sorted(class_to_data.items(), key=lambda x: x[0])

    class_files = []

    for class_name, data in class_to_data_sorted:
        data = {
            "path": class_name + ".class",
            "include_strings": sorted(list(data))
        }

        class_files.append(data)

    paratranz_mapping["class_files"] = class_files

    with open(MAPPING_OUTPUT_PATH, 'w', encoding='utf-8') as f:
        json.dump(paratranz_mapping, f, indent=2, ensure_ascii=False)

def add_translation_to_exported_strings():

    valid_data = load_deathfly_data()

    class_to_data = {}

    for row in valid_data:
        class_name = row[1]
        if class_name not in class_to_data:
            class_to_data[class_name] = dict()
        if '"' in row[2]:
            class_to_data[class_name][row[0][1:-1]] = row[2][1:-1]

    with open(PARATRANZ_STRINGS_PATH, 'r', encoding='utf-8') as f:
        paratranz_strings = json.load(f)

    for string in paratranz_strings:
        class_name = string['key'].split(':')[1].split('#')[0].removesuffix('.class')
        if class_name in class_to_data:
            class_strings = class_to_data[class_name]
            if string['original'] in class_strings:
                string['translation'] = class_strings[string['original']]
                string['stage'] = max(string['stage'], 1)

    with open(PARATRANZ_STRINGS_PATH, 'w', encoding='utf-8') as f:
        json.dump(paratranz_strings, f, indent=2, ensure_ascii=False)


if __name__ == '__main__':
    # 先运行下面这个函数，生成mapping文件
    # convert_deathfly_csv_to_paratranz_mapping()

    # 然后把生成的mapping文件加到para_tranz_map.json里面
    # 然后运行脚本，选1导出string文件

    # 然后运行下面这个函数，把渡鸦提取的旧版本翻译加到para_tranz_map.json里面
    # add_translation_to_exported_strings()

    # 然后运行脚本，选2导入string文件
    pass