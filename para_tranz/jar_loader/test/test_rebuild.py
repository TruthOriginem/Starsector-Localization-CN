import json
import tempfile
import unittest
import zipfile
from contextlib import ExitStack
from dataclasses import asdict
from pathlib import Path
from unittest.mock import patch

from para_tranz.jar_loader import jar_file
from para_tranz.utils import util
from para_tranz.utils.mapping import JarMapItem


class RebuildJarTest(unittest.TestCase):
    def setUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.patches = ExitStack()
        self.addCleanup(self.patches.close)
        self.root = Path(self.directory.name)
        self.original = self.root / 'original'
        self.target = self.root / 'localization'
        self.output = self.root / 'output'
        for directory in (self.original, self.target, self.output):
            directory.mkdir()
        self.name = 'starfarer.api.jar'
        self.entry = 'com/fs/starfarer/api/impl/campaign/world/TTBlackSite.class'
        source = Path(__file__).resolve().parents[3] / 'original' / self.name
        with zipfile.ZipFile(source) as archive:
            self.raw = archive.read(self.entry)
        self.mapping = JarMapItem(
            type='jar',
            path=self.name,
            class_files=[
                {
                    'path': self.entry,
                    'include_strings': [
                        {'val': 'Unknown Location', 'occurs': [2]},
                    ],
                }
            ],
        )
        for module in (jar_file, util):
            for key, value in [
                ('ORIGINAL_PATH', self.original),
                ('TRANSLATION_PATH', self.target),
            ]:
                self.patches.enter_context(patch.object(module, key, value))
        self.patches.enter_context(patch.object(util, 'PARA_TRANZ_PATH', self.output))
        self.patches.enter_context(
            patch.object(jar_file, 'PARA_TRANZ_MAP', [self.mapping])
        )
        self.write_jar(self.original / self.name, self.raw)
        self.write_jar(self.target / self.name, self.raw)
        file = jar_file.JavaJarFile(self.name, self.mapping.class_files)
        try:
            row = file.get_strings()[0].as_dict()
        finally:
            file.close_files()
        row.update(translation='未知地点', stage=1)
        self.row = row
        self.json_path = self.output / 'starfarer.api.json'
        self.json_path.write_text(
            json.dumps([row], ensure_ascii=False), encoding='utf-8'
        )

    def write_jar(self, path: Path, contents: bytes) -> None:
        with zipfile.ZipFile(path, 'w') as archive:
            archive.writestr(self.entry, contents)
            archive.writestr('resource.txt', b'resource')

    def values(self) -> list[str]:
        file = jar_file.JavaJarFile(self.name, self.mapping.class_files)
        try:
            cls = file.class_files[self.entry]
            original = cls._get_original_string_constants_mapping()['Unknown Location']
            translated = cls._get_translation_constants_by_index()
            return [translated[c.constant_index].string for c in original]
        finally:
            file.close_files()

    def test_rebuild_clears_stale_translation_and_is_idempotent(self) -> None:
        file = jar_file.JavaJarFile(self.name, [{'path': self.entry}])
        try:
            cls = file.class_files[self.entry]
            for c in cls.translation_table.get_utf8_constants_with_string_ref():
                if c.string == 'Unknown Location':
                    c.string = '旧译文'
            file.save_file()
        finally:
            file.close_files()
        source_bytes = (self.original / self.name).read_bytes()
        jar_file.JavaJarFile.rebuild_from_config()
        self.assertEqual(
            ['Unknown Location', 'Unknown Location', '未知地点'], self.values()
        )
        target = self.target / self.name
        previous = (target.read_bytes(), target.stat().st_mtime_ns)
        jar_file.JavaJarFile.rebuild_from_config()
        self.assertEqual(previous, (target.read_bytes(), target.stat().st_mtime_ns))
        self.assertEqual(source_bytes, (self.original / self.name).read_bytes())

    def test_missing_target_and_empty_json(self) -> None:
        (self.target / self.name).unlink()
        self.json_path.write_text('[]', encoding='utf-8')
        jar_file.JavaJarFile.rebuild_from_config()
        self.assertEqual(['Unknown Location'] * 3, self.values())

    def test_loaded_mapping_dataclasses_and_export(self) -> None:
        mapping = JarMapItem.from_dict(asdict(self.mapping))
        with patch.object(jar_file, 'PARA_TRANZ_MAP', [mapping]):
            jar_file.JavaJarFile.rebuild_from_config()
        file = jar_file.JavaJarFile(self.name, self.mapping.class_files)
        try:
            rows = file.get_strings()
            self.assertEqual(1, len(rows))
            self.assertEqual(self.row['key'], rows[0].key)
            self.assertEqual('未知地点', rows[0].translation)
        finally:
            file.close_files()

    def test_deleted_translation_and_removed_string_restore_original(self) -> None:
        jar_file.JavaJarFile.rebuild_from_config()
        self.json_path.write_text('[]', encoding='utf-8')
        jar_file.JavaJarFile.rebuild_from_config()
        self.assertEqual(['Unknown Location'] * 3, self.values())
        self.json_path.write_text(json.dumps([self.row]), encoding='utf-8')
        jar_file.JavaJarFile.rebuild_from_config()
        self.mapping.class_files[0]['include_strings'] = ['Alpha Site']
        jar_file.JavaJarFile.rebuild_from_config()
        self.assertEqual(['Unknown Location'] * 3, self.values())

    def test_removed_class_restores_original(self) -> None:
        self.mapping.class_files = []
        self.json_path.write_text('[]', encoding='utf-8')
        self.write_jar(self.target / self.name, b'stale class')
        jar_file.JavaJarFile.rebuild_from_config()
        with zipfile.ZipFile(self.target / self.name) as archive:
            self.assertEqual(self.raw, archive.read(self.entry))
            self.assertEqual(b'resource', archive.read('resource.txt'))

    def test_missing_json_preserves_target(self) -> None:
        self.json_path.unlink()
        old = (self.target / self.name).read_bytes()
        jar_file.JavaJarFile.rebuild_from_config()
        self.assertEqual(old, (self.target / self.name).read_bytes())

    def test_failures_preserve_target_and_clean_temporary_files(self) -> None:
        target = self.target / self.name
        old = target.read_bytes()
        for failure in ('json', 'class', 'context', 'write', 'replace'):
            with self.subTest(failure=failure):
                self.mapping.class_files[0]['path'] = self.entry
                row = dict(self.row)
                if failure == 'context':
                    row['context'] = row['context'].replace(
                        '文件：starfarer.api.jar', '文件：wrong.jar'
                    )
                self.json_path.write_text(
                    '{' if failure == 'json' else json.dumps([row]), encoding='utf-8'
                )
                if failure == 'class':
                    self.mapping.class_files[0]['path'] = 'missing.class'
                if failure in ('write', 'replace'):
                    function = '_rewrite_jar' if failure == 'write' else 'os.replace'
                    with patch(
                        'para_tranz.jar_loader.jar_file.' + function,
                        side_effect=OSError('模拟失败'),
                    ):
                        with self.assertRaises(Exception):
                            jar_file.JavaJarFile.rebuild_from_config()
                else:
                    with self.assertRaises(Exception):
                        jar_file.JavaJarFile.rebuild_from_config()
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
                row = dict(self.row, stage=stage, translation=text)
                row['context'] = row['context'].replace(
                    '同值序号：2', f'同值序号：{occurrence}'
                )
                self.json_path.write_text(json.dumps([row]), encoding='utf-8')
                jar_file.JavaJarFile.rebuild_from_config()
                self.assertEqual(
                    ['Unknown Location', 'Unknown Location', expected], self.values()
                )

    def test_logs_report_success_skip_and_failure(self) -> None:
        with self.assertLogs('JavaJarFile', level='INFO') as logs:
            jar_file.JavaJarFile.rebuild_from_config()
            jar_file.JavaJarFile.rebuild_from_config()
        self.assertTrue(any('汉化 jar 重建完成' in line for line in logs.output))
        self.assertTrue(any('跳过替换' in line for line in logs.output))
        self.json_path.write_text('{', encoding='utf-8')
        with self.assertLogs('JavaJarFile', level='ERROR') as logs:
            with self.assertRaises(ValueError):
                jar_file.JavaJarFile.rebuild_from_config()
        self.assertTrue(any('目标文件未替换' in line for line in logs.output))
