# original 文件夹应该存放应该被汉化的文件，该脚本用于删除localization文件夹里多余的文件
import os

def remove_excess_files(original_dir, localization_dir):
    for root, dirs, files in os.walk(original_dir):
        # 在localization目录中找到相应的路径
        corresponding_path = root.replace(original_dir, localization_dir)

        if not os.path.exists(corresponding_path):
            # 如果没有相对应的路径，则直接忽视，继续遍历下一个
            continue

        original_files = set(files)
        localization_files = set(os.listdir(corresponding_path))

        # 找到存在于localization文件夹但不存在于original文件夹中的文件
        excess_files = localization_files - original_files
        # 删除多余的文件
        for file in excess_files:
            path_to_remove = os.path.join(corresponding_path, file)
            if os.path.isfile(path_to_remove):
                print('删除{}'.format(path_to_remove))
                os.remove(path_to_remove)

# 调用函数，传入你的原始文件夹路径和本地化文件夹路径
remove_excess_files('original/data', 'localization/data')