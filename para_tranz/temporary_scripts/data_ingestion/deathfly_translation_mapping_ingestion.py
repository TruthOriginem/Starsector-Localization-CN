import csv
import json
import re
from collections import defaultdict

from para_tranz.utils.config import PROJECT_DIRECTORY, PARA_TRANZ_PATH
from para_tranz.utils.util import make_logger, contains_english, contains_chinese

SCRIPT_PATH = PROJECT_DIRECTORY / 'para_tranz' / 'temporary_scripts' / 'data_ingestion'

CSV_PATH = SCRIPT_PATH  / 'deathfly_098_obf_mapping.csv'

MAPPING_OUTPUT_PATH = SCRIPT_PATH / 'deathfly_098_obf_mapping.json'

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
        "path": "starfarer_obf.jar",
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

    class_to_data = {}

    for row in valid_data:
        class_name = row[1]
        if class_name not in class_to_data:
            class_to_data[class_name] = dict()
        if '"' in row[2]:
            class_to_data[class_name][row[0][1:-1]] = row[2][1:-1]
        else:
            # 如果没有翻译，就用 None 代替
            class_to_data[class_name][row[0][1:-1]] = None

    with open(PARATRANZ_STRINGS_PATH, 'r', encoding='utf-8') as f:
        paratranz_strings = json.load(f)

    for string in paratranz_strings:
        class_name = string['key'].split(':')[1].split('#')[0].removesuffix('.class')
        if class_name in class_to_data:
            class_strings = class_to_data[class_name]

            # 将 original 字段转换为对照表中的格式
            original = string['original'].replace('\n', '\\n').replace('\t', '\\t').replace("'", "\'")
            # 将连续的空格转换为单个空格
            original = re.sub(r' +', ' ', original)

            if original in class_strings:
                if class_strings[original] is not None:
                    string['translation'] = class_strings[original]

                    if not contains_english(string['original']):  # 如果原文不包含英文，则直接设为已翻译
                        string['stage'] = max(string['stage'], 1)
                    else:
                        if contains_chinese(string['translation']):  # 如果翻译中包含中文，则设为已翻译
                            string['stage'] = max(string['stage'], 1)
                        else:  # 否则设为待翻译
                            string['stage'] = max(string['stage'], 0)
            else:
                print(f'在对照表中未找到原文字符串 "{string["original"]}"，类 {class_name}')
        else:
            print(f'在对照表中未找到类 {class_name}')

    with open(PARATRANZ_STRINGS_PATH, 'w', encoding='utf-8') as f:
        json.dump(paratranz_strings, f, indent=2, ensure_ascii=False)


if __name__ == '__main__':
    # 先运行下面这个函数，生成mapping文件
    convert_deathfly_csv_to_paratranz_mapping()

    # 然后把生成的mapping文件加到para_tranz_map.json里面
    # 然后运行脚本，选1导出string文件

    # 然后运行下面这个函数，把渡鸦提取的旧版本翻译加到para_tranz_map.json里面
    # add_translation_to_exported_strings()

    # 然后运行脚本，选2导入string文件
    pass