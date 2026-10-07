"""需要仓库原文 Jar 的真实游戏回归，单独执行。"""

import json
import tempfile
import unittest
import zipfile
from pathlib import Path

from para_tranz.jar_loader.constant_table import ConstantTable
from para_tranz.jar_loader.jar_file import JavaJarFile
from para_tranz.utils.mapping import JarMapItem


class TTBlackSiteIntegrationTest(unittest.TestCase):
    def test_real_class_keeps_internal_name_in_english(self) -> None:
        source = Path(__file__).resolve().parents[3] / 'original/starfarer.api.jar'
        self.assertTrue(source.is_file(), f'集成测试需要游戏原文 Jar：{source}')
        entry = 'com/fs/starfarer/api/impl/campaign/world/TTBlackSite.class'
        with zipfile.ZipFile(source) as archive:
            raw = archive.read(entry)
        table = ConstantTable(raw)
        for c in table.get_utf8_constants_with_string_ref():
            if c.string == 'Unknown Location':
                c.string = '旧译文'
        stale = raw[:8] + table.to_bytes() + raw[table.table_end_index :]
        mapping = JarMapItem.from_dict(
            {
                'type': 'jar',
                'path': 'starfarer.api.jar',
                'class_files': [
                    {
                        'path': entry,
                        'include_strings': [{'val': 'Unknown Location', 'occurs': [2]}],
                    }
                ],
            }
        )
        row = {
            'key': f'starfarer.api.jar:{entry}#"Unknown Location":2',
            'original': 'Unknown Location',
            'translation': '未知地点',
            'stage': 1,
            'context': f'版本：0.98-RC8 词条格式：v2\n文件：starfarer.api.jar\n'
            f'类：{entry}\n常量号：0718\n同值序号：2\n'
            '原始数据："Unknown Location"\n译文数据："Unknown Location"',
        }
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            original, target, translations = (
                root / n for n in ('original.jar', 'target.jar', 'words.json')
            )
            for path, data in ((original, raw), (target, stale)):
                with zipfile.ZipFile(path, 'w') as archive:
                    archive.writestr(entry, data)
            translations.write_text(json.dumps([row]), encoding='utf-8')
            self.assertEqual(
                'updated',
                JavaJarFile.rebuild_jar(mapping, original, target, translations),
            )
            with zipfile.ZipFile(target) as archive:
                result = archive.read(entry)
            result_table = ConstantTable(result)
            values = {
                c.constant_index: c.string
                for c in result_table.get_utf8_constants_with_string_ref()
            }
            self.assertEqual('Unknown Location', values[34])
            self.assertEqual('Unknown Location', values[716])
            self.assertEqual('未知地点', values[718])
            self.assertEqual(
                raw[table.table_end_index :], result[result_table.table_end_index :]
            )
            self.assertEqual(
                'unchanged',
                JavaJarFile.rebuild_jar(mapping, original, target, translations),
            )
