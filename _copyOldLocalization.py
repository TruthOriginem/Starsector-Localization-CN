# 将original.old文件夹中所有文件和original文件夹中所有文件比较，遍历时如果两者的文件内容相同，将相应路径的localization.old文件夹中的文件复制到localization文件夹中
# （同时判断localization是否存在该文件，如果存在，是否和旧文件一致）
# 只建议在项目更新开始时运行该脚本
import filecmp
import os
import shutil


def copy_if_identical(file1, file2, destination):
    print(f'{file1} | to | {file2}')
    if os.path.exists(file2):
        if filecmp.cmp(file1, file2):
            return  # 文件已经存在且内容一致，跳过复制
    # print("{file1}to{destination}")

    os.makedirs(destination, exist_ok=True)  # 确保目标目录存在
    shutil.copy2(file1, destination)  # 复制文件到目标路径


def compare_and_copy_dirs(dir1, dir2, old_loc_dir, new_loc_dir):
    for root, dirs, files in os.walk(dir1):
        for file in files:
            relative_path = os.path.relpath(root, dir1)

            original_old_file = os.path.join(root, file)
            original_new_file = os.path.join(dir2, relative_path, file)
            old_loc_file = os.path.join(old_loc_dir, relative_path, file)
            new_loc_file = os.path.join(new_loc_dir, relative_path, file)

            if os.path.isfile(original_old_file) and os.path.isfile(original_new_file):
                if filecmp.cmp(
                    original_old_file, original_new_file
                ):  # 判断文件内容是否一致
                    if os.path.isfile(old_loc_file):
                        copy_if_identical(
                            old_loc_file,
                            new_loc_file,
                            os.path.join(new_loc_dir, relative_path),
                        )  # 如果一致就复制文件


# 定义路径
original_old_dir = 'original.old'
original_new_dir = 'original'
localization_old_dir = 'localization.old'
localization_new_dir = 'localization'

# 调用函数
compare_and_copy_dirs(
    original_old_dir, original_new_dir, localization_old_dir, localization_new_dir
)
