# 删除指定目录里不需要对字符串翻译的.java文件（通过筛选是否有实质性内容）
import fnmatch
import os
import re


def find_and_delete_files(directory, pattern='*.java'):
    for root, dirs, files in os.walk(directory):
        for basename in files:
            if fnmatch.fnmatch(basename, pattern):
                filename = os.path.join(root, basename)
                with open(filename, 'r') as file:
                    file_content = file.read()
                    # 移除每一行中"//"后的所有内容
                    file_content = re.sub(r'//.*', '', file_content)
                    # 移除块注释
                    file_content = re.sub(
                        r'/\*.*?\*/', '', file_content, flags=re.DOTALL
                    )
                    # 移除注解
                    file_content = re.sub(r'@\w+', '', file_content)
                    # 检查是否存在双引号内包含至少一个字母或数字的字符串，如果不存在，则在关闭文件后删除它
                    # 非贪心匹配，确保返回的是最短的由两个双引号包围的字符串
                    matches = re.findall(r'\".*?\"', file_content)
                    if not any(re.search('[a-zA-Z0-9]', match) for match in matches):
                        file.close()
                        os.remove(filename)
                        print(f'Removed file: {filename}')


# 使用方式如下：
find_and_delete_files('original/data')
