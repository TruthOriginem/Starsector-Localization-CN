# 将original.old文件夹中所有文件和original文件夹中所有文件比较，遍历时如果两者的文件内容不同，将相应路径的original文件夹中的文件复制到localization文件夹中
# 如果original文件夹中有文件而original.old文件夹中没有，则复制新添加的需要翻译的文件到localization文件夹
import os
import filecmp
import shutil


def compare_and_copy_dirs(dir1, dir2, old_loc_dir, new_loc_dir):
    for root, dirs, files in os.walk(dir2):
        for file in files:
            relative_path = os.path.relpath(root, dir2)

            original_old_file = os.path.join(dir1, relative_path, file)
            original_new_file = os.path.join(dir2, relative_path, file)
            new_loc_file = os.path.join(new_loc_dir, relative_path, file)

            if os.path.isfile(original_old_file) and os.path.isfile(
                    original_new_file):
                if not filecmp.cmp(original_old_file,
                                   original_new_file):  # 判断文件内容是否一致
                    print(f"{original_new_file} | to | {new_loc_file}")

                    os.makedirs(os.path.dirname(new_loc_file), exist_ok=True) # 确保目标目录存在
                    shutil.copy2(original_new_file, new_loc_file)  # 复制文件到目标路径

            # 如果original文件夹中有文件而original.old文件夹中没有，则复制新添加的需要翻译的文件到localization文件夹
            elif os.path.isfile(original_new_file) and not os.path.isfile(
                    original_old_file):
                print(f"{original_new_file} | to | {new_loc_file}")

                os.makedirs(os.path.dirname(new_loc_file), exist_ok=True)  # 确保目标目录存在
                shutil.copy2(original_new_file, new_loc_file)  # 复制文件到目标路径


# 定义路径
original_old_dir = 'original.old'
original_new_dir = 'original'
localization_old_dir = 'localization.old'
localization_new_dir = 'localization'

# 调用函数
compare_and_copy_dirs(original_old_dir, original_new_dir, localization_old_dir,
                      localization_new_dir)
