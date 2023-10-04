#!/usr/bin/env python
# -*- coding: utf-8 -*
import os
import re
import json5
from urllib import request

key_name = "descriptionPrefix"
modify_ext = ".skin"
starsector_comment_line = re.compile(r'\s*#')
key_name_re = re.compile(r'"{}"'.format(key_name) + r'\s*:\s*"(.*?)"')
# 映射文件
map_file_path = "skin_descriptionPrefix_map.json"
# 在这里更改抓取文件夹的目录
source_folder_path = "../original/data/hulls/skins"
# 在这里更改更新目标文件夹的目录
target_folder_path = "../localization/data/hulls/skins"


def getSSjsonString(file_path):
    # Alex将 # 用在json文件中作为注释...
    json_string = ''
    with open(file_path, 'r', encoding='utf-8', errors='ignore') as f:
        lines = f.readlines()
        for line in lines:
            if starsector_comment_line.search(line):
                continue
            json_string += line
    return json_string


root_folder = os.path.dirname(os.path.realpath(__file__))
source_folder = os.path.join(root_folder, source_folder_path)
target_folder = os.path.join(root_folder, target_folder_path)
item_map_file = os.path.join(root_folder, map_file_path)

def chooseAction():
    print("此脚本用于映射json条目翻译操作...")
    action = input(
        "输入1来根据指定源目录更新映射文件\n输入2来根据映射文件更新指定目标目录\n输入3远程下载装配映射文件(文件来源于汉化项目中，注意!会覆盖本地文件)\n输入其他字符或回车自动退出\n")
    if action == '1':
        if updateMap():
            chooseAction()
    elif action == '2':
        if updateTargetItems():
            chooseAction()
    elif action == '3':
        if downloadItemMap():
            chooseAction()
    else:
        exit()
    return


def updateMap():
    print("-"*50)
    print("当前处理目录为:" + source_folder_path)
    print("保存映射文件为:" + map_file_path)
    value_list = list()

    processed = 0
    processed_not_duplicated = 0
    processed_files = 0
    for root, sub_folders, filenames in os.walk(source_folder):
        for filename in filenames:
            processed_files += 1
            file_path = os.path.join(root, filename)
            ignored, ext = os.path.splitext(file_path)
            if not ext == modify_ext:
                continue
            with open(file_path, 'r', encoding='utf-8') as f:
                lines = f.readlines()
                for line in lines:
                    display_match = key_name_re.search(line)
                    if display_match:
                        value = display_match.group(1)
                        if value:
                            processed += 1
                            if value and not value_list.__contains__(value):
                                processed_not_duplicated += 1
                                value_list.append(value)
                print("\r已搜索{}个文件(搜索文件总和:{})，已检索{}个独特条目...".format(
                    processed, processed_files, processed_not_duplicated), end=" ")
    print()

    value_list.sort()

    for value in value_list:
        print(value)

    print("开始更新映射...")
    if os.path.isfile(item_map_file):
        item_map_json = json5.load(
            open(item_map_file, 'r', encoding='utf-8'))
    else:
        item_map_json = {}

    for value in value_list:
        if not item_map_json.__contains__(value):
            item_map_json[value] = value

    print(item_map_json)
    json5.dump(item_map_json, open(
        item_map_file, 'w+', encoding='utf-8'), indent=4, quote_keys=True, trailing_commas=False, sort_keys=True, ensure_ascii=False)
    print("已完成更新")
    print("-"*50)
    return True


def updateTargetItems():
    print("-"*50)
    print("当前处理目录为:" + target_folder_path)
    print("读取映射文件为:" + map_file_path)
    print("\n读取映射文件中")
    item_map_json = dict(json5.load(
        open(item_map_file, 'r', encoding='utf-8')))
    item_map_values = list(item_map_json.values())
    print("读取完毕")
    print("\n开始替换...")

    no_match_item_dict = dict()
    processed = 0
    processed_not_item = 0
    processed_files = 0
    for root, sub_folders, filenames in os.walk(target_folder):
        for filename in filenames:
            processed_files += 1
            file_path = os.path.join(root, filename)
            ignored, ext = os.path.splitext(file_path)
            if not ext == '.variant':
                processed_not_item += 1
                continue
            new_lines = list()
            line_changed = False
            with open(file_path, 'r', encoding='utf-8') as f:
                lines = f.readlines()
                for line in lines:
                    if line_changed:
                        new_lines.append(line)
                    else:
                        display_match = key_name_re.search(line)
                        if display_match:
                            name = display_match.group(1)
                            if name:
                                if item_map_json.__contains__(name):
                                    processed += 1
                                    line = line.replace(
                                        name, item_map_json[name])
                                    line_changed = True
                                elif not item_map_values.__contains__(name):
                                    no_match_item_dict[file_path] = name
                        new_lines.append(line)

            if line_changed:
                with open(file_path, 'w', encoding='utf-8') as f:
                    f.writelines(new_lines)
            print("\r已替换{}个文件(搜索文件总和:{})...".format(
                processed, processed_files), end=" ")
    print()
    if no_match_item_dict:
        print("以下文件的条目没有对应翻译:")
        for (key, value) in no_match_item_dict.items():
            print(key + " : " + value)
    print("已完成替换")
    print("-"*50)
    return True


def downloadItemMap():
    print("-"*50)
    print("下载中...")
    try:
        with request.urlopen('https://raw.githubusercontent.com/TruthOriginem/Starsector-096-Localization/main/variant_name_map.json', timeout=1.0) as rf:
            data = rf.read()
            print("下载成功!")
            if data:
                data = data.decode('utf-8')
                with open(item_map_file, 'w+', encoding='utf-8') as f:
                    f.write(data)
                    print("保存成功!")
    except:
        print("下载出现错误!请检查你的网络连接!")
    print("-"*50)
    return True


if __name__ == '__main__':
    chooseAction()
    # updateMap()
