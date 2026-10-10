import os
import shutil
import tempfile
import zipfile
from dataclasses import asdict
from pathlib import Path
from typing import Dict, List, Optional, Sequence, Union

from para_tranz.config import (
    EXPORTED_STRING_CONTEXT_PREFIX_PREFIX,
    IGNORE_CONTEXT_PREFIX_MISMATCH_STRINGS,
    ORIGINAL_PATH,
    TRANSLATION_PATH,
)
from para_tranz.jar_loader.class_file import JavaClassFile
from para_tranz.utils.mapping import JarMapItem
from para_tranz.utils.util import DataFile, String, make_logger, relative_path


def _rewrite_jar(
    source_path: Path,
    target_path: Path,
    updated_file_contents: Dict[str, bytes],
) -> None:
    """Rewrite a jar while retaining every source entry's stable timestamp."""
    with zipfile.ZipFile(target_path, 'w') as target:
        with zipfile.ZipFile(source_path) as source:
            for info in source.infolist():
                contents = updated_file_contents.get(info.filename)
                if contents is None:
                    contents = source.read(info)
                target.writestr(info, contents)


def _jar_contents_equal(first: Path, second: Path) -> bool:
    """比较完整条目内容，忽略压缩方式、时间戳和条目顺序。"""
    try:
        with zipfile.ZipFile(first) as left, zipfile.ZipFile(second) as right:
            # 稳定排序保留同名重复条目的先后顺序。
            left_entries = sorted(left.infolist(), key=lambda info: info.filename)
            right_entries = sorted(right.infolist(), key=lambda info: info.filename)
            if len(left_entries) != len(right_entries):
                return False
            for a, b in zip(left_entries, right_entries):
                if a.filename != b.filename or a.file_size != b.file_size:
                    return False
                if left.read(a) != right.read(b):
                    return False
            return True
    except zipfile.BadZipFile:
        # 旧目标损坏时仍允许用完整的新产物替换。
        return False


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
        translation_path: Optional[Path] = None,
        original_path: Optional[Path] = None,
        **kwargs,
    ):
        super().__init__(path, type)

        self.path = Path(path)
        self.original_path = (
            original_path if original_path is not None else ORIGINAL_PATH / self.path
        )
        self.translation_path = (
            translation_path
            if translation_path is not None
            else TRANSLATION_PATH / self.path
        )

        self.original_file: Optional[zipfile.ZipFile] = None
        self.translation_file: Optional[zipfile.ZipFile] = None
        try:
            self.open_files()
        except Exception:
            self.close_files()
            raise

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

    def update_strings(self, strings: List[String]) -> None:
        class_file_path_strings_mapping = {
            class_file_path: [] for class_file_path in self.class_files
        }  # type: Dict[str, List[String]]

        for s in strings:
            try:
                parsed_context = JavaClassFile.parse_jar_string_context(s.context)
            except ValueError as e:
                if IGNORE_CONTEXT_PREFIX_MISMATCH_STRINGS and not s.context.startswith(
                    EXPORTED_STRING_CONTEXT_PREFIX_PREFIX
                ):
                    self.logger.debug(
                        f'在 {self.path} 中词条 key={s.key} 的词条上下文前缀与当前上下'
                        f'文前缀不匹配，跳过词条'
                    )
                    continue
                raise e

            if parsed_context.jar_path != str(self.path):
                raise ValueError(
                    f'词条 key={s.key}'
                    f'{JavaClassFile._format_occurrence_index(parsed_context.occurrence_index)}'
                    f' '
                    f'的上下文 jar 为 {parsed_context.jar_path}，'
                    f'但当前正在更新 {self.path}'
                )
            class_file_path = parsed_context.class_path

            class_file = self.class_files.get(class_file_path, None)

            if class_file is None:
                if IGNORE_CONTEXT_PREFIX_MISMATCH_STRINGS and not s.context.startswith(
                    EXPORTED_STRING_CONTEXT_PREFIX_PREFIX
                ):
                    self.logger.debug(
                        f'在 {self.path} 中词条 key={s.key}'
                        f'{JavaClassFile._format_occurrence_index(parsed_context.occurrence_index)}'
                        f' '
                        f'的词条上下文前缀与当前上下文前缀不匹配，跳过词条'
                    )
                else:
                    self.logger.warning(
                        f'在更新词条 {s.key}'
                        f'{JavaClassFile._format_occurrence_index(parsed_context.occurrence_index)}'
                        f' 时，'
                        f'在文件 {self.path} 中找不到类 {class_file_path}。未更新该词'
                        f'条。'
                    )
                continue

            class_file_path_strings_mapping[class_file_path].append(s)

        for class_file_path, strings in class_file_path_strings_mapping.items():
            self.class_files[class_file_path].update_strings(strings)

    def save_file(self) -> None:
        # 对于每一个已读取的class文件，生成新的字节码
        updated_file_contents = {}
        for class_file in self.class_files.values():
            new_bytecode = class_file.generate_translated_bytecode()
            # 如果字节码有变化，则将新的字节码加入 updated_file_contents
            if new_bytecode != class_file.translation_bytes:
                updated_file_contents[str(class_file.path)] = new_bytecode

        if not updated_file_contents:
            self.logger.info(f'{self.path} 中没有 class 字节码变化，跳过重写 jar')
            return

        temp_path = self.translation_path.with_name(
            self.translation_path.name + '.temp'
        )

        # 生成新的jar文件，写入新的class文件，并将老jar中的其它文件也复制进去。
        # 保留源 entry 时间，避免相同译文因构建时间不同而产生二进制漂移。
        _rewrite_jar(self.translation_path, temp_path, updated_file_contents)

        # 关闭读模式的文件
        self.close_files()
        # 删除老jar文件，将新jar文件重命名为老jar文件
        self.translation_path.unlink()
        temp_path.rename(self.translation_path)
        # 重新打开文件
        self.open_files()

    def load_from_file(self) -> None:
        for class_file in self.class_files.values():
            class_file.load_from_file()

    def read_original_class_file(self, class_file_path: str) -> bytes:
        assert self.original_file is not None
        path = zipfile.Path(self.original_file, class_file_path)
        if not path.exists():
            raise FileNotFoundError(
                f'在原始jar文件 {self.original_path} 中找不到class文件 '
                f'{class_file_path}'
            )
        return path.read_bytes()

    def read_translation_class_file(self, class_file_path: str) -> bytes:
        assert self.translation_file is not None
        path = zipfile.Path(self.translation_file, class_file_path)
        if not path.exists():
            raise FileNotFoundError(
                f'在译文jar文件 {self.translation_path} 中找不到class文件 '
                f'{class_file_path}'
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
    def rebuild_from_config(cls) -> None:
        from para_tranz.utils.mapping import PARA_TRANZ_MAP

        updated = unchanged = skipped = 0
        for item in PARA_TRANZ_MAP:
            if not isinstance(item, JarMapItem):
                continue
            result = cls.rebuild_jar(
                item,
                ORIGINAL_PATH / item.path,
                TRANSLATION_PATH / item.path,
                DataFile(item.path, 'jar').para_tranz_path,
            )
            if result == 'updated':
                updated += 1
            elif result == 'unchanged':
                unchanged += 1
            else:
                skipped += 1
        cls.logger.info(
            f'jar 重建导入完成：更新 {updated} 个，内容未变化 {unchanged} 个，'
            f'缺少词条文件跳过 {skipped} 个'
        )

    @classmethod
    def rebuild_jar(
        cls,
        item: JarMapItem,
        original: Path,
        target: Path,
        translations: Path,
    ) -> str:
        # 单个 Jar 的输入路径显式传入，不读取全局映射，也不依赖已有目标。
        if not translations.exists():
            cls.logger.info(
                f'未找到 {item.path} 所对应的 ParaTranz 数据 '
                f'({relative_path(translations)})，跳过重建，目标文件保持不变'
            )
            return 'skipped'

        cls.logger.info(f'开始重建汉化 jar：{item.path}')
        published = False
        try:
            strings = DataFile.read_json_strings(translations)
            target.parent.mkdir(parents=True, exist_ok=True)
            # 同盘临时目录保证最终替换不跨文件系统，失败时不改动正式产物。
            with tempfile.TemporaryDirectory(
                prefix='.jar-rebuild-', dir=target.parent
            ) as directory:
                temporary = Path(directory) / target.name
                shutil.copyfile(original, temporary)
                cls.logger.debug(f'jar 重建临时文件：{temporary}')
                class_files = asdict(item)['class_files']
                file = cls(
                    item.path,
                    class_files,
                    no_auto_load=True,
                    translation_path=temporary,
                    original_path=original,
                )
                try:
                    for class_info in class_files:
                        if file.load_class_file(**class_info) is None:
                            raise ValueError(
                                f'无法加载映射中的 class 文件：{class_info["path"]}'
                            )
                    file.update_strings(strings)
                    cls.logger.info(
                        f'从 {relative_path(translations)} 加载了 '
                        f'{len(strings)} 个词条，用于重建 {item.path}'
                    )
                    file.save_file()
                finally:
                    file.close_files()

                if target.exists() and _jar_contents_equal(temporary, target):
                    cls.logger.info(f'{item.path} 重建结果与现有汉化内容一致，跳过替换')
                    return 'unchanged'
                os.replace(temporary, target)
                published = True
            cls.logger.info(f'汉化 jar 重建完成：{relative_path(target)}')
            return 'updated'
        except Exception as e:
            state = (
                '目标文件已替换，临时文件清理失败' if published else '目标文件未替换'
            )
            cls.logger.error(f'重建 {item.path} 失败，{state}：{e}')
            raise

    @classmethod
    def load_files_from_config(cls) -> Sequence['JavaJarFile']:
        from para_tranz.utils.mapping import PARA_TRANZ_MAP

        cls.logger.info('开始读取游戏jar数据')
        files = [
            cls(**asdict(item))
            for item in PARA_TRANZ_MAP
            if isinstance(item, JarMapItem)
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
