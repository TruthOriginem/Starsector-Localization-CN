import datetime
import zipfile
from dataclasses import asdict
from pathlib import Path
from typing import Dict, List, Optional, Union

from para_tranz.jar_loader.class_file import JavaClassFile
from para_tranz.config import (
    EXPORTED_STRING_CONTEXT_PREFIX_PREFIX,
    IGNORE_CONTEXT_PREFIX_MISMATCH_STRINGS,
    ORIGINAL_PATH,
    OVERRIDE_STRING_STATUS,
    TRANSLATION_PATH,
)
from para_tranz.utils.mapping import ClassFileMapItem, IncludeStringRule, PARA_TRANZ_MAP, JarMapItem
from para_tranz.utils.util import DataFile, String, make_logger, rename_class_path


class JavaJarFile(DataFile):
    """
    用于表示游戏文件中可以提取原文和译文的jar文件
    """

    logger = make_logger('JavaJarFile')
    export_empty_strings = True

    def __init__(
        self,
        path: Union[Path, str],
        class_files: List[dict],
        type: str = 'jar',
        no_auto_load: bool = False,
        **kwargs,
    ):
        super().__init__(path, type)

        self.path = path
        self.original_path = ORIGINAL_PATH / path
        self.translation_path = TRANSLATION_PATH / path

        self.original_file: Optional[zipfile.ZipFile] = None
        self.translation_file: Optional[zipfile.ZipFile] = None
        self.open_files()

        self.class_files: Dict[str, JavaClassFile] = {}

        if not no_auto_load:
            self.logger.info(
                f'开始读取 {self.path} 中指定的class文件，共 {len(class_files)} 个'
            )
            for class_file_info in class_files:
                self.load_class_file(**class_file_info)
            self.logger.info(f'jar读取完成: {self.path}')

    def __del__(self) -> None:
        self.close_files()

    def open_files(self) -> None:
        self.original_file = zipfile.ZipFile(self.original_path, 'r')
        self.translation_file = zipfile.ZipFile(self.translation_path, 'r')

    def close_files(self) -> None:
        if self.original_file is not None:
            self.original_file.close()
        if self.translation_file is not None:
            self.translation_file.close()

    def get_strings(self) -> List[String]:
        strings = []
        for class_file in self.class_files.values():
            strings.extend(class_file.get_strings())
        return strings

    def load_class_file(
        self,
        path: str,
        include_strings: Optional[List] = None,
        override: bool = False,
    ) -> Optional['JavaClassFile']:
        if not override and path in self.class_files:
            return self.class_files[path]
        try:
            class_file = JavaClassFile(self, path, include_strings)
            self.class_files[path] = class_file
        except Exception as e:
            self.logger.warning(f'在 {self.path} 中读取 class 文件 {path} 时出错：{e}')
            return None

        return class_file

    def save_json(self, ensure_ascii: bool = False, indent: int = 4) -> None:
        strings = [s for s in self.get_strings() if s.original or self.export_empty_strings]

        if not strings:
            self.logger.info(f'从 {self.path} 中未提取到可翻译词条，跳过导出')
            return

        # 从已有 output 同步 stage（保留平台上的翻译状态），但不保留任何旧词条。
        # jar 导出结果严格等于当前 map 定义的内容，不使用 other_strings 合并机制。
        if self.para_tranz_path.exists() and not OVERRIDE_STRING_STATUS:
            special_stages = (1, 2, 3, 5, 9, -1)
            existing_stages = {
                s.key: s.stage
                for s in self.read_json_strings(self.para_tranz_path)
                if s.stage in special_stages
            }
            for s in strings:
                if s.key in existing_stages and s.stage != existing_stages[s.key]:
                    self.logger.debug(f'更新词条 {s.key} 的stage：{s.stage}->{existing_stages[s.key]}')
                    s.stage = existing_stages[s.key]

        self.write_json_strings(self.para_tranz_path, strings, ensure_ascii, indent)
        self.logger.info(f'从 {self.path} 中导出了 {len(strings)} 个词条到 {self.para_tranz_path}')

    def update_strings(
        self, strings: List[String], version_migration: bool = False
    ) -> None:
        class_file_path_strings_mapping = {
            class_file_path: [] for class_file_path in self.class_files
        }  # type: Dict[str, List[String]]

        strings_without_class = []

        for s in strings:
            try:
                parsed_context = JavaClassFile.parse_jar_string_context(s.context)
            except ValueError as e:
                if (
                    IGNORE_CONTEXT_PREFIX_MISMATCH_STRINGS
                    and not s.context.startswith(EXPORTED_STRING_CONTEXT_PREFIX_PREFIX)
                ):
                    self.logger.debug(
                        f'在 {self.path} 中词条 key={s.key} 的词条上下文前缀与当前上下文前缀不匹配，跳过词条'
                    )
                    continue
                raise e

            if parsed_context.jar_path != str(self.path):
                raise ValueError(
                    f'词条 key={s.key}{JavaClassFile._format_occurrence_index(parsed_context.occurrence_index)} '
                    f'的上下文 jar 为 {parsed_context.jar_path}，'
                    f'但当前正在更新 {self.path}'
                )
            class_file_path = parsed_context.class_path

            class_file = self.class_files.get(class_file_path, None)

            if class_file is None:
                if not version_migration:
                    if (
                        IGNORE_CONTEXT_PREFIX_MISMATCH_STRINGS
                        and not s.context.startswith(
                            EXPORTED_STRING_CONTEXT_PREFIX_PREFIX
                        )
                    ):
                        self.logger.debug(
                            f'在 {self.path} 中词条 key={s.key}{JavaClassFile._format_occurrence_index(parsed_context.occurrence_index)} '
                            f'的词条上下文前缀与当前上下文前缀不匹配，跳过词条'
                        )
                    else:
                        self.logger.warning(
                            f'在更新词条 {s.key}{JavaClassFile._format_occurrence_index(parsed_context.occurrence_index)} 时，'
                            f'在文件 {self.path} 中找不到类 {class_file_path}。未更新该词条。'
                        )
                else:
                    strings_without_class.append(s)
                    self.logger.debug(
                        f'在更新词条 {s.key}{JavaClassFile._format_occurrence_index(parsed_context.occurrence_index)} 时，'
                        f'在文件 {self.path} 中找不到类 {class_file_path}。稍后尝试进行模糊匹配。'
                    )
                continue

            class_file_path_strings_mapping[class_file_path].append(s)

        for class_file_path, strings in class_file_path_strings_mapping.items():
            self.class_files[class_file_path].update_strings(strings)

        if not version_migration or len(strings_without_class) == 0:
            return

        # 版本迁移时，为找不到类的词条进行模糊匹配
        self.logger.info(
            '存在未找到类的词条，准备进行模糊匹配，开始加载jar中的所有类文件'
        )
        self.load_all_classes_in_jar()

        self.logger.info(f'开始进行模糊匹配，共 {len(strings_without_class)} 个词条')
        match_success_count = 0

        strings_by_class = {}
        for s in strings_without_class:
            class_path = JavaClassFile.parse_jar_string_context(s.context).class_path
            if class_path not in strings_by_class:
                strings_by_class[class_path] = []
            strings_by_class[class_path].append(s)

        for class_file_path, strings in strings_by_class.items():
            self.logger.info(f'开始为类 {class_file_path} 进行模糊匹配')
            matched_class = self._fuzzy_match_class_file(class_file_path, strings)
            if matched_class is None:
                self.logger.warning(
                    f'在更新词条时，未能通过模糊匹配在文件 {self.path} 中找到类 {class_file_path} 对应的新类。未更新该词条。'
                )
                continue
            match_success_count += matched_class.update_strings(strings)

            # 更新mapping文件，添加该类的原文映射
            jar_map_item = PARA_TRANZ_MAP.get_item_by_path(self.path)
            if jar_map_item is None:
                raise ValueError(
                    f'在更新词条时，未能在mapping文件中找到 jar 文件 {self.path} 对应的映射'
                )
            class_map_item = jar_map_item.get_class_file_item(
                str(matched_class.path), create=True
            )  # type: ClassFileMapItem
            original_strings = {s.original for s in strings}
            for original in original_strings:
                class_map_item.add_include_rule(IncludeStringRule(original, None))

        self.logger.info(
            f'模糊匹配完成，共 {match_success_count} / {len(strings_without_class)} 个词条成功匹配'
        )

        PARA_TRANZ_MAP.save()
        self.logger.info('保存mapping文件完成')

    def _fuzzy_match_class_file(
        self, class_file_path: str, strings: Optional[List[String]]
    ) -> Optional['JavaClassFile']:
        normalized_path = rename_class_path(class_file_path)
        matched_classes: List[JavaClassFile] = []

        normalized_class_files = {}

        for class_file in self.class_files.values():
            normalized_name = rename_class_path(str(class_file.path))
            if normalized_name not in normalized_class_files:
                normalized_class_files[normalized_name] = [class_file]
            else:
                normalized_class_files[normalized_name].append(class_file)

        if normalized_path in normalized_class_files:
            matched_classes = normalized_class_files[normalized_path]

        if not matched_classes:
            self.logger.info(
                f'在文件 {self.path} 按类名模糊匹配 {class_file_path} 时，未能找到可能的结果'
            )
            return None

        if strings is None:
            self.logger.info(
                f'在文件 {self.path} 中成功建立匹配关系，类 {class_file_path} ==> {matched_classes[0].path}'
            )
            return matched_classes[0]

        best_match = None
        best_match_rate = 0

        for matched_class in matched_classes:
            string_match_count = 0
            matched_class_original_strings = {
                s.original for s in matched_class.get_strings()
            }
            for s in strings:
                if s.original in matched_class_original_strings:
                    string_match_count += 1

            match_rate = string_match_count / len(strings)
            if match_rate >= best_match_rate:
                best_match = matched_class
                best_match_rate = match_rate

        assert best_match is not None  # matched_classes 非空时循环必定赋值
        if best_match_rate >= 0.5:
            self.logger.info(
                f'在文件 {self.path} 中成功建立匹配关系，类 {class_file_path} => {best_match.path}，匹配率 {best_match_rate}'
            )
            return best_match
        else:
            self.logger.info(
                f'在文件 {self.path} 中找到匹配关系，类 {class_file_path} => {best_match.path}，但匹配率 {best_match_rate} 过低不予采用'
            )
            return None

    def save_file(self) -> None:
        # 对于每一个已读取的class文件，生成新的字节码
        updated_file_contents = {}
        for class_file in self.class_files.values():
            new_bytecode = class_file.generate_translated_bytecode()
            # 如果字节码有变化，则将新的字节码加入 updated_file_contents
            if new_bytecode != class_file.translation_bytes:
                updated_file_contents[str(class_file.path)] = new_bytecode

        # 生成新的jar文件，写入新的class文件，并将老jar中的其它文件也复制进去
        with zipfile.ZipFile(self.translation_path.with_suffix('.temp'), 'w') as zf:
            with zipfile.ZipFile(self.translation_path) as old_zf:
                for info in old_zf.infolist():
                    # 复制老文件
                    if info.filename not in updated_file_contents:
                        zf.writestr(info, old_zf.read(info.filename))
                    # 写入新文件
                    else:
                        # 将文件修改日期设置为当前时间
                        info.date_time = datetime.datetime.now().timetuple()[:6]
                        zf.writestr(info, updated_file_contents[info.filename])

        # 关闭读模式的文件
        self.close_files()
        # 删除老jar文件，将新jar文件重命名为老jar文件
        self.translation_path.unlink()
        self.translation_path.with_suffix('.temp').rename(self.translation_path)
        # 重新打开文件
        self.open_files()

    def load_from_file(self) -> None:
        for class_file in self.class_files.values():
            class_file.load_from_file()

    def read_original_class_file(self, class_file_path: str) -> bytes:
        path = zipfile.Path(self.original_file, class_file_path)
        if not path.exists():
            raise FileNotFoundError(
                f'在原始jar文件 {self.original_path} 中找不到class文件 {class_file_path}'
            )
        return path.read_bytes()

    def read_translation_class_file(self, class_file_path: str) -> bytes:
        path = zipfile.Path(self.translation_file, class_file_path)
        if not path.exists():
            raise FileNotFoundError(
                f'在译文jar文件 {self.translation_path} 中找不到class文件 {class_file_path}'
            )
        return path.read_bytes()

    def load_all_classes_in_jar(
        self, from_translation: bool = False, override_loaded: bool = False
    ) -> None:
        jar_path = (
            ORIGINAL_PATH / self.path
            if not from_translation
            else TRANSLATION_PATH / self.path
        )

        with zipfile.ZipFile(jar_path) as zf:
            class_files = [
                {'path': info.filename}
                for info in zf.infolist()
                if info.filename.endswith('.class')
            ]
            self.logger.info(
                f'在jar文件 {jar_path} 中找到了 {len(class_files)} 个class文件。'
            )

        for class_file_info in class_files:
            self.load_class_file(path=class_file_info['path'], override=override_loaded)

    @classmethod
    def load_files_from_config(cls) -> List['JavaJarFile']:
        cls.logger.info('开始读取游戏jar数据')
        files = [
            cls(**asdict(item)) for item in PARA_TRANZ_MAP if isinstance(item, JarMapItem)
        ]
        cls.logger.info('游戏jar数据读取完成')
        return files


if __name__ == '__main__':
    # jar_file = JavaJarFile.load_files_from_config()[0]
    # print(jar_file.path)
    # print(jar_file.class_files)
    # print(jar_file.para_tranz_path)
    # for class_file in jar_file.class_files.values():
    #     print(class_file.class_name)
    #     print(f'{class_file.get_original_version():#x}')
    #     strings = class_file.get_strings()
    #     for string in strings:
    #         # print(string)
    #         pass
    #     print(class_file.translation_bytes)
    #     print(class_file.generate_translated_bytecode())
    pass
