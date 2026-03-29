"""纯文本文件（如 mission_text.txt）的 ParaTranz 词条导入导出。

每个文件整体作为一条词条，key 为相对路径，original/translation 为文件全文。
"""

from pathlib import Path
from typing import List, Optional, Union

from para_tranz.config import (
    EXPORTED_STRING_CONTEXT_PREFIX,
    EXPORTED_STRING_CONTEXT_PREFIX_PREFIX,
    IGNORE_CONTEXT_PREFIX_MISMATCH_STRINGS,
    ORIGINAL_PATH,
    PARA_TRANZ_PATH,
)
from para_tranz.utils.util import DataFile, String, make_logger, relative_path


class TxtFile(DataFile):
    logger = make_logger('TxtFile')

    def __init__(
        self,
        path: Union[str, Path],
        original_path: Optional[Path] = None,
        translation_path: Optional[Path] = None,
        output_path: Optional[Path] = None,
    ):
        super().__init__(path, 'txt', original_path, translation_path, output_path)
        self._original_text: Optional[str] = None
        self._translation_text: Optional[str] = None
        self.load_from_file()

    def load_from_file(self) -> None:
        self._original_text = self._read_file(self.original_path)
        self._translation_text = self._read_file(self.translation_path)

    def _read_file(self, path: Path) -> Optional[str]:
        if not path.exists():
            return None
        try:
            with open(path, 'r', encoding='utf-8') as f:
                return f.read()
        except Exception as e:
            self.logger.warning(f'读取文件失败 {relative_path(path)}：{e}')
            return None

    def _key(self) -> str:
        return str(self.path).replace('\\', '/')

    def _context(self) -> str:
        return f'{EXPORTED_STRING_CONTEXT_PREFIX}源文件：{self.path}'

    def get_strings(self) -> List[String]:
        if self._original_text is None:
            return []
        translation = self._translation_text or ''
        return [
            String(
                key=self._key(),
                original=self._original_text,
                translation=translation,
                stage=1 if translation else 0,
                context=self._context(),
            )
        ]

    def update_strings(self, strings: List[String], version_migration: bool = False) -> None:
        key = self._key()
        for s in strings:
            if s.key != key:
                continue
            if IGNORE_CONTEXT_PREFIX_MISMATCH_STRINGS and s.context:
                if not s.context.startswith(EXPORTED_STRING_CONTEXT_PREFIX_PREFIX):
                    self.logger.debug(f'跳过版本前缀不匹配的词条：{key}')
                    continue
            if not s.translation:
                continue
            self._translation_text = s.translation
            return
        self.logger.warning(
            f'在 {relative_path(self.translation_path)} 中未找到词条 key={key!r}，未写入译文'
        )

    def save_file(self) -> None:
        if self._translation_text is None:
            self.logger.warning(f'译文内容为空，无法保存：{relative_path(self.translation_path)}')
            return
        self.translation_path.parent.mkdir(parents=True, exist_ok=True)
        with open(self.translation_path, 'w', encoding='utf-8') as f:
            f.write(self._translation_text)
        self.logger.info(f'已保存译文文件：{relative_path(self.translation_path)}')

    @classmethod
    def load_files_from_config(cls) -> List['TxtFile']:
        from para_tranz.utils.mapping import PARA_TRANZ_MAP, TxtMapItem

        files: List['TxtFile'] = []
        for item in PARA_TRANZ_MAP:
            if not isinstance(item, TxtMapItem):
                continue
            output_path = (PARA_TRANZ_PATH / item.combined_output) if item.combined_output else None
            if '*' in item.path or '?' in item.path:
                matched = sorted(ORIGINAL_PATH.glob(item.path))
                if not matched:
                    cls.logger.warning(f'glob 模式未匹配到任何文件：{item.path}')
                for actual_path in matched:
                    rel_path = actual_path.relative_to(ORIGINAL_PATH)
                    files.append(cls(path=rel_path, output_path=output_path))
            else:
                files.append(cls(path=Path(item.path), output_path=output_path))
        return files
