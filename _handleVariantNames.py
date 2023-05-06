#!/usr/bin/env python
# -*- coding: utf-8 -*
import os
import re
import json5
from urllib import request

starsector_comment_line = re.compile(r'\s*#')
variant_display_name_re = re.compile(r'"displayName"\s*:\s*"(.*?)"')


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


# 在这里更改抓取装配文件夹的目录
source_variants_path = "original/data/variants"
# 在这里更改更新目标装配文件夹的目录
target_variants_path = "localization/data/variants"
# 装配映射的文件
map_file_path = "variant_name_map.json"

root_folder = os.path.dirname(os.path.realpath(__file__))
source_variant_folder = os.path.join(root_folder, source_variants_path)
target_variant_folder = os.path.join(root_folder, target_variants_path)
variant_map_file = os.path.join(root_folder, map_file_path)


def chooseAction():
    print("此脚本用于从指定目录中获取所有去除重复的装配文件名或者根据这些装配名的映射更新指定目录的装配名...")
    action = input(
        "输入1来根据指定源目录更新映射文件\n输入2来根据映射文件更新指定目标目录\n输入3远程下载装配映射文件(文件来源于汉化项目中，注意!会覆盖本地文件)\n输入其他字符或回车自动退出\n")
    if action == '1':
        if updateMap():
            chooseAction()
    elif action == '2':
        if updateTargetVariants():
            chooseAction()
    elif action == '3':
        if downloadVariantNameMap():
            chooseAction()
    else:
        exit()
    return


def updateMap():
    print("-"*50)
    print("当前处理目录为:" + source_variants_path)
    print("保存映射文件为:" + map_file_path)
    variant_name_list = list()

    processed = 0
    processed_not_duplicated = 0
    processed_files = 0
    for root, sub_folders, filenames in os.walk(source_variant_folder):
        for filename in filenames:
            processed_files += 1
            file_path = os.path.join(root, filename)
            ignored, ext = os.path.splitext(file_path)
            if not ext == '.variant':
                continue
            with open(file_path, 'r', encoding='utf-8') as f:
                lines = f.readlines()
                for line in lines:
                    display_match = variant_display_name_re.search(line)
                    if display_match:
                        name = display_match.group(1)
                        if name:
                            processed += 1
                            if name and not variant_name_list.__contains__(name):
                                processed_not_duplicated += 1
                                variant_name_list.append(name)
                print("\r已搜索{}个装配文件(搜索文件总和:{})，已检索{}个独特装配名...".format(
                    processed, processed + processed_files, processed_not_duplicated), end=" ")
    print()

    variant_name_list.sort()

    for name in variant_name_list:
        print(name)

    print("开始更新映射...")
    variant_map_json = json5.load(
        open(variant_map_file, 'r', encoding='utf-8'))

    for name in variant_name_list:
        if not variant_map_json.__contains__(name):
            variant_map_json[name] = name

    print(variant_map_json)
    json5.dump(variant_map_json, open(
        variant_map_file, 'w+', encoding='utf-8'), indent=4, quote_keys=True, trailing_commas=False, sort_keys=True, ensure_ascii=False)
    print("已完成更新")
    print("-"*50)
    return True


def updateTargetVariants():
    print("-"*50)
    print("当前处理目录为:" + target_variants_path)
    print("读取映射文件为:" + map_file_path)
    print("\n读取映射文件中")
    variant_map_json = dict(json5.load(
        open(variant_map_file, 'r', encoding='utf-8')))
    variant_map_values = list(variant_map_json.values())
    print("读取完毕")
    print("\n开始替换...")

    no_match_variant_dict = dict()
    processed = 0
    processed_not_variant = 0
    processed_files = 0
    for root, sub_folders, filenames in os.walk(target_variant_folder):
        for filename in filenames:
            processed_files += 1
            file_path = os.path.join(root, filename)
            ignored, ext = os.path.splitext(file_path)
            if not ext == '.variant':
                processed_not_variant += 1
                continue
            new_lines = list()
            line_changed = False
            with open(file_path, 'r', encoding='utf-8') as f:
                lines = f.readlines()
                for line in lines:
                    if line_changed:
                        new_lines.append(line)
                    else:
                        display_match = variant_display_name_re.search(line)
                        if display_match:
                            name = display_match.group(1)
                            if name:
                                if variant_map_json.__contains__(name):
                                    processed += 1
                                    line = line.replace(
                                        name, variant_map_json[name])
                                    line_changed = True
                                elif not variant_map_values.__contains__(name):
                                    no_match_variant_dict[file_path] = name
                        new_lines.append(line)

            if line_changed:
                with open(file_path, 'w', encoding='utf-8') as f:
                    f.writelines(new_lines)
            print("\r已替换{}个装配文件(搜索文件总和:{})...".format(
                processed, processed_files), end=" ")
    print()
    if no_match_variant_dict:
        print("以下装配文件的装配名没有对应翻译:")
        for (key, value) in no_match_variant_dict.items():
            print(key + " : " + value)
    print("已完成替换")
    print("-"*50)
    return True


def downloadVariantNameMap():
    print("-"*50)
    print("下载中...")
    try:
        with request.urlopen('https://raw.githubusercontent.com/TruthOriginem/Starsector-095-Localization/main/variant_name_map.json', timeout=1.0) as rf:
            data = rf.read()
            print("下载成功!")
            if data:
                data = data.decode('utf-8')
                with open(variant_map_file, 'w+', encoding='utf-8') as f:
                    f.write(data)
                    print("保存成功!")
    except:
        print("下载出现错误!请检查你的网络连接!")
    print("-"*50)
    return True


if __name__ == '__main__':
    chooseAction()
    # updateMap()
