import json
import tempfile
import unittest
import zipfile
from dataclasses import asdict
from pathlib import Path
from typing import TypedDict
from unittest.mock import patch

from para_tranz.jar_loader import jar_file
from para_tranz.jar_loader.constant_table import ConstantTable
from para_tranz.jar_loader.test.fixtures import make_class
from para_tranz.utils.mapping import ClassFileMapItem, JarMapItem


class TranslationRow(TypedDict):
    key: str
    original: str
    translation: str
    stage: int
    context: str


class RebuildJarTest(unittest.TestCase):
    def setUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.original = self.root / 'original'
        self.target = self.root / 'localization'
        self.output = self.root / 'output'
        for directory in (self.original, self.target, self.output):
            directory.mkdir()
        self.name = 'test.jar'
        self.entry = 'example/Test.class'
        self.raw = make_class()
        self.mapping = JarMapItem(
            type='jar',
            path=self.name,
            class_files=[
                ClassFileMapItem(
                    path=self.entry,
                    include_strings=[
                        {'val': 'Unknown Location', 'occurs': [2]},
                    ],
                )
            ],
        )
        self.write_jar(self.original / self.name, self.raw)
        self.write_jar(self.target / self.name, self.raw)
        row: TranslationRow = {
            'key': 'test.jar:example/Test.class#"Unknown Location":2',
            'original': 'Unknown Location',
            'translation': '未知地点',
            'stage': 1,
            'context': '版本：0.98-RC8 词条格式：v2\n文件：test.jar\n'
            '类：example/Test.class\n常量号：0009\n同值序号：2\n'
            '原始数据："Unknown Location"\n译文数据："Unknown Location"',
        }
        self.row = row
        self.json_path = self.output / 'test.json'
        self.json_path.write_text(
            json.dumps([row], ensure_ascii=False), encoding='utf-8'
        )

    def write_jar(self, path: Path, contents: bytes) -> None:
        with zipfile.ZipFile(path, 'w') as archive:
            archive.writestr(self.entry, contents)
            archive.writestr('resource.txt', b'resource')

    def rebuild(self, mapping: JarMapItem | None = None) -> str:
        return jar_file.JavaJarFile.rebuild_jar(
            mapping if mapping is not None else self.mapping,
            self.original / self.name,
            self.target / self.name,
            self.json_path,
        )

    def values(self) -> list[str]:
        with zipfile.ZipFile(self.target / self.name) as archive:
            table = ConstantTable(archive.read(self.entry))
        values = {
            c.constant_index: c.string
            for c in table.get_utf8_constants_with_string_ref()
        }
        self.assertEqual('Other', values[11])
        return [values[i] for i in (5, 7, 9)]

    def test_rebuild_clears_stale_translation_and_is_idempotent(self) -> None:
        self.write_jar(
            self.target / self.name, make_class(('旧译文', '旧译文', '旧译文'))
        )
        source_bytes = (self.original / self.name).read_bytes()
        self.rebuild()
        self.assertEqual(
            ['Unknown Location', 'Unknown Location', '未知地点'], self.values()
        )
        target = self.target / self.name
        previous = (target.read_bytes(), target.stat().st_mtime_ns)
        self.rebuild()
        self.assertEqual(previous, (target.read_bytes(), target.stat().st_mtime_ns))
        self.assertEqual(source_bytes, (self.original / self.name).read_bytes())

    def test_missing_target_and_empty_json(self) -> None:
        (self.target / self.name).unlink()
        self.json_path.write_text('[]', encoding='utf-8')
        self.rebuild()
        self.assertEqual(['Unknown Location'] * 3, self.values())

    def test_loaded_mapping_dataclasses_and_export(self) -> None:
        mapping = JarMapItem.from_dict(asdict(self.mapping))
        self.assertIsInstance(mapping.class_files[0], ClassFileMapItem)
        self.assertEqual(self.mapping, mapping)
        self.rebuild(mapping)
        file = jar_file.JavaJarFile(
            self.name,
            [asdict(item) for item in mapping.class_files],
            original_path=self.original / self.name,
            translation_path=self.target / self.name,
        )
        try:
            rows = file.get_strings()
            self.assertEqual(1, len(rows))
            self.assertEqual(self.row['key'], rows[0].key)
            self.assertEqual('未知地点', rows[0].translation)
        finally:
            file.close_files()

    def test_deleted_translation_and_removed_string_restore_original(self) -> None:
        self.rebuild()
        self.json_path.write_text('[]', encoding='utf-8')
        self.rebuild()
        self.assertEqual(['Unknown Location'] * 3, self.values())
        self.json_path.write_text(json.dumps([self.row]), encoding='utf-8')
        self.rebuild()
        self.mapping.class_files[0].include_strings = ['Other']
        self.rebuild()
        self.assertEqual(['Unknown Location'] * 3, self.values())

    def test_removed_class_restores_original(self) -> None:
        self.mapping.class_files = []
        self.json_path.write_text('[]', encoding='utf-8')
        self.write_jar(self.target / self.name, b'stale class')
        self.rebuild()
        with zipfile.ZipFile(self.target / self.name) as archive:
            self.assertEqual(self.raw, archive.read(self.entry))
            self.assertEqual(b'resource', archive.read('resource.txt'))

    def test_missing_json_preserves_target(self) -> None:
        self.json_path.unlink()
        old = (self.target / self.name).read_bytes()
        self.rebuild()
        self.assertEqual(old, (self.target / self.name).read_bytes())

    def test_failures_preserve_target_and_clean_temporary_files(self) -> None:
        target = self.target / self.name
        old = target.read_bytes()
        for failure in ('json', 'class', 'context', 'write', 'replace'):
            with self.subTest(failure=failure):
                self.mapping.class_files[0].path = self.entry
                row = self.row.copy()
                if failure == 'context':
                    row['context'] = row['context'].replace(
                        '文件：test.jar', '文件：wrong.jar'
                    )
                self.json_path.write_text(
                    '{' if failure == 'json' else json.dumps([row]), encoding='utf-8'
                )
                if failure == 'class':
                    self.mapping.class_files[0].path = 'missing.class'
                if failure in ('write', 'replace'):
                    function = '_rewrite_jar' if failure == 'write' else 'os.replace'
                    with patch(
                        'para_tranz.jar_loader.jar_file.' + function,
                        side_effect=OSError('模拟失败'),
                    ):
                        with self.assertRaises(OSError):
                            self.rebuild()
                else:
                    error = json.JSONDecodeError if failure == 'json' else ValueError
                    with self.assertRaises(error):
                        self.rebuild()
                self.assertEqual(old, target.read_bytes())
                self.assertEqual([self.name], [p.name for p in self.target.iterdir()])

    def test_translation_stages_empty_values_and_whitelist(self) -> None:
        for stage, text, occurrence, expected in [
            (1, '新译文', 2, '新译文'),
            (0, '不应写入', 2, 'Unknown Location'),
            (1, '', 2, ''),
            (1, '不应写入', 1, 'Unknown Location'),
        ]:
            with self.subTest(stage=stage, text=text, occurrence=occurrence):
                row = self.row.copy()
                row['stage'] = stage
                row['translation'] = text
                row['context'] = row['context'].replace(
                    '同值序号：2', f'同值序号：{occurrence}'
                )
                self.json_path.write_text(json.dumps([row]), encoding='utf-8')
                self.rebuild()
                self.assertEqual(
                    ['Unknown Location', 'Unknown Location', expected], self.values()
                )

    def test_logs_report_success_skip_and_failure(self) -> None:
        with self.assertLogs('JavaJarFile', level='INFO') as logs:
            self.rebuild()
            self.rebuild()
        self.assertTrue(any('汉化 jar 重建完成' in line for line in logs.output))
        self.assertTrue(any('跳过替换' in line for line in logs.output))
        self.json_path.write_text('{', encoding='utf-8')
        with self.assertLogs('JavaJarFile', level='ERROR') as logs:
            with self.assertRaises(ValueError):
                self.rebuild()
        self.assertTrue(any('目标文件未替换' in line for line in logs.output))
