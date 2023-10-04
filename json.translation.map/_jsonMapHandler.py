#!/usr/bin/env python
# -*- coding: utf-8 -*
import os
import re
import json5
import csv

workplace_path = os.path.dirname(os.path.realpath(__file__))
starsector_comment_line = re.compile(r'\s*#')
json_map_collection_path = "_jsonMapCollection.csv"
source_folder = "../original"
target_folder = "../localization"


def getStarsectorjsonStr(file_path):
    # Alex将 # 用在json文件中作为注释...
    json_string = ''
    with open(file_path, 'r', encoding='utf-8', errors='ignore') as f:
        lines = f.readlines()
        for line in lines:
            if starsector_comment_line.search(line):
                continue
            json_string += line
    return json_string


class MapItem(object):
    def __init__(self, prefix, ext, key, path, mapKey):
        """prefix 映射文件名前缀 ext 检索文件限定后缀 key 检索文件指定键 path 检索文件路径"""
        self.prefix = prefix
        self.ext = ext
        self.key = key
        self.path = path
        self.mapKey = mapKey
        self.key_name_re = re.compile(rf'"{key}"' + r'\s*:\s*"((?:[^"\\]|\\.)*)"')
        if mapKey:
            self.map_file_path = f"map_{prefix}_{ext[1:]}_{mapKey}.json"
        else:
            self.map_file_path = f"map_{prefix}_{ext[1:]}_{key}.json"
        self.source_path = f"{source_folder}/{path}"
        self.target_path = f"{target_folder}/{path}"
        self.source_path = os.path.normpath(os.path.join(workplace_path, self.source_path))
        self.target_path = os.path.normpath(os.path.join(workplace_path, self.target_path))
        self.item_map_file = os.path.join(workplace_path, self.map_file_path)

    def searchFile(self, file_path, value_list):
        hasValue = 0
        hasIndieValue = 0
        with open(file_path, 'r', encoding='utf-8') as f:
            lines = f.readlines()
            for line in lines:
                display_match = self.key_name_re.search(line)
                if display_match:
                    value = display_match.group(1)
                    if value:
                        hasValue += 1
                        if not value_list.__contains__(value):
                            hasIndieValue += 1
                            value_list.append(value)
        return (hasValue, hasIndieValue)

    def updateMap(self):
        print("-" * 50)
        print("当前来源路径为:" + self.source_path)
        print("保存映射文件为:" + self.map_file_path)
        value_list = list()
        processed_times = 0
        processed_not_duplicated = 0
        total_files = 0
        if os.path.isdir(self.source_path):
            for root, sub_folders, filenames in os.walk(self.source_path):
                for filename in filenames:
                    total_files += 1
                    file_path = os.path.join(root, filename)
                    ignored, ext = os.path.splitext(file_path)
                    if not ext == self.ext:
                        continue
                    (hasValue, hasIndieValue) = self.searchFile(file_path, value_list)
                    processed_times += hasValue
                    processed_not_duplicated += hasIndieValue
                    print("\r已搜索到{}个条目(搜索文件总和:{})，已检索到{}个独特条目...".format(processed_times, total_files, processed_not_duplicated), end=" ")
        else:
            (hasValue, hasIndieValue) = self.searchFile(self.source_path, value_list)
            processed_times += hasValue
            processed_not_duplicated += hasIndieValue
            print("\r已搜索到{}个条目(搜索文件总和:{})，已检索到{}个独特条目...".format(processed_times, total_files, processed_not_duplicated), end=" ")
        print()

        value_list.sort()

        # for value in value_list:
        #     print(value)

        print("开始更新映射...")
        if os.path.isfile(self.item_map_file):
            item_map_json = json5.load(open(self.item_map_file, 'r', encoding='utf-8'))
        else:
            item_map_json = {}

        is_map_changed = False

        for value in value_list:
            value_lower = value.lower()
            if not item_map_json.__contains__(value_lower):
                is_map_changed = True
                item_map_json[value_lower] = value

        # print(item_map_json)
        json5.dump(item_map_json,
                   open(self.item_map_file, 'w+', encoding='utf-8'),
                   indent=4,
                   quote_keys=True,
                   trailing_commas=False,
                   sort_keys=True,
                   ensure_ascii=False)
        if is_map_changed:
            print("已完成更新")
        else:
            print("映射文件未变化")
        print("-" * 50)
        return True

    def updateFile(self, file_path, new_lines, item_map_json, item_map_values, no_match_item_list):
        line_changed = False
        key_times = 0
        line_count = 0
        with open(file_path, 'r', encoding='utf-8') as f:
            lines = f.readlines()
            for line in lines:
                display_match = self.key_name_re.search(line)
                if display_match:
                    value = display_match.group(1)
                    if value:
                        key = value.lower()
                        if item_map_json.__contains__(key):
                            if item_map_json[key].lower() != key:
                                key_times += 1
                                line = line.replace(value, item_map_json[key])
                                line_changed = True
                        elif not item_map_values.__contains__(value):
                            no_match_item_list.append(f"{file_path}({line_count}):{value}")
                new_lines.append(line)
                line_count += 1
        return (line_changed, key_times)

    def updateTargetItems(self):
        print("-" * 50)
        print("当前处理路径为:" + self.target_path)
        print("读取映射文件为:" + self.map_file_path, end=" ")
        item_map_json = dict(json5.load(open(self.item_map_file, 'r', encoding='utf-8')))
        item_map_values = list(item_map_json.values())
        print("读取完毕")
        no_match_item_list = []
        processed_times = 0
        processed_files = 0
        total_files = 0
        if os.path.isdir(self.target_path):
            for root, sub_folders, filenames in os.walk(self.target_path):
                for filename in filenames:
                    total_files += 1
                    file_path = os.path.join(root, filename)
                    ignored, ext = os.path.splitext(file_path)
                    if not ext == self.ext:
                        continue
                    new_lines = list()
                    (line_changed, key_times) = self.updateFile(file_path, new_lines, item_map_json, item_map_values, no_match_item_list)
                    processed_times += key_times
                    if line_changed:
                        processed_files += 1
                        with open(file_path, 'w', encoding='utf-8') as f:
                            f.writelines(new_lines)
                    print("\r已替换{}个文件，共计替换{}次(搜索文件总和:{})...".format(processed_files, processed_times, total_files), end=" ")
        else:
            file_path = self.target_path
            total_files += 1
            new_lines = list()
            (line_changed, key_times) = self.updateFile(file_path, new_lines, item_map_json, item_map_values, no_match_item_list)
            processed_times += key_times
            if line_changed:
                processed_files += 1
                with open(file_path, 'w', encoding='utf-8') as f:
                    f.writelines(new_lines)
            print("\r已替换{}个文件，共计替换{}次(搜索文件总和:{})...".format(processed_files, processed_times, total_files), end=" ")
        if no_match_item_list:
            print("以下的条目没有正确映射:")
            for item in no_match_item_list:
                print(item)
        print("已完成替换")
        print("-" * 50)
        return True


items = []
with open(json_map_collection_path, "r", encoding="utf-8") as f:
    # 实例化类 DictReader，得到DictReader对象
    # 以字典的形式获取 csv 文件信息
    dr = csv.DictReader(f)
    # 打印字典的数据
    for row in dr:
        # print(row)
        items.append(MapItem(row['prefix'], row['ext'], row['key'], row['path'], row['mapKey']))


def chooseAction():
    print("此脚本用于映射json条目翻译操作...")
    print("所有文本都会被转化为小写...")
    action = input("输入1来根据指定源目录更新映射文件\n输入2来根据映射文件更新指定目标目录\n输入3远程下载装配映射文件(文件来源于汉化项目中，注意!会覆盖本地文件)\n输入其他字符或回车自动退出\n")
    if action == '1':
        for item in items:
            item.updateMap()
        chooseAction()
    elif action == '2':
        for item in items:
            item.updateTargetItems()
        chooseAction()
    # elif action == '3':
    #     if downloadItemMap():
    #         chooseAction()
    else:
        exit()
    return


# def downloadItemMap():
#     print("-" * 50)
#     print("下载中...")
#     try:
#         with request.urlopen('https://raw.githubusercontent.com/TruthOriginem/Starsector-096-Localization/main/variant_name_map.json',
#                              timeout=1.0) as rf:
#             data = rf.read()
#             print("下载成功!")
#             if data:
#                 data = data.decode('utf-8')
#                 with open(item_map_file, 'w+', encoding='utf-8') as f:
#                     f.write(data)
#                     print("保存成功!")
#     except:
#         print("下载出现错误!请检查你的网络连接!")
#     print("-" * 50)
#     return True

if __name__ == '__main__':

    chooseAction()
    # updateMap()
