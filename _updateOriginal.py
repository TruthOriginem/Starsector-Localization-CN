# 根据source_folder文件夹更新到target_folder文件夹
import os
import shutil

source_folder = '096 data'
target_folder = 'original'

for root, sub_folders, filenames in os.walk(target_folder):
    for file in filenames:
        filepath = os.path.join(root, file)
        filepath_in_source = os.path.join(
            root.replace(target_folder, source_folder, 1), file)
        if os.path.exists(filepath_in_source):
            print("更新{}至{}".format(filepath_in_source, filepath))
            os.remove(filepath)
            shutil.copy(filepath_in_source, filepath)
