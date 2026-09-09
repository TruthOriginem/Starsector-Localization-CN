import os
import sys
import tempfile
import unittest
import zipfile
from contextlib import ExitStack
from datetime import date
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import make_exe  # noqa: E402
import make_zip  # noqa: E402
from package_utils import PackageMetadata  # noqa: E402


class ZipTest(unittest.TestCase):
    def setUp(self):
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        self.repo = Path(self.stack.enter_context(tempfile.TemporaryDirectory()))
        self.localization = self.repo / 'localization'
        self.game = self.repo / 'game'
        self.output = self.repo / 'output'
        for relative, content in {
            'localization/data/text.txt': '译文',
            'localization/rules分段/片段.txt': '排除',
            'localization/data/config/settings.json': '中文配置',
            'game/starsector.exe': '游戏',
            'game/starsector-core/data/text.txt': 'English',
            'game/starsector-core/data/config/settings.json': 'original',
        }.items():
            path = self.repo / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding='utf-8')
        (self.game / 'mods').mkdir()
        self.stack.enter_context(mock.patch.dict(os.environ, {}, clear=True))
        for name, value in {
            'REPO_ROOT': self.repo,
            'LOCALIZATION_DIR': self.localization,
            'OUTPUT_DIR': self.output,
        }.items():
            self.stack.enter_context(mock.patch.object(make_zip, name, value))
        self.stack.enter_context(mock.patch.object(make_zip, 'load_env'))
        self.stack.enter_context(
            mock.patch.object(
                make_zip,
                'load_package_metadata',
                return_value=PackageMetadata(
                    '1.2.3', '0.98a-RC8', 'master', '(黑体版)', False
                ),
            )
        )
        self.validate = self.stack.enter_context(
            mock.patch.object(make_zip, 'validate_original_game_folder')
        )

    def build(self, package):
        return make_zip.build_zip(make_zip.parse_args(['--package', package]))

    def test_arguments_match_exe_and_no_args_only_print_help(self):
        for package in ('all', 'translation', 'full'):
            args = ['--package', package]
            self.assertEqual(
                vars(make_zip.parse_args(args)), vars(make_exe.parse_args(args))
            )
        with mock.patch.object(make_zip, 'build_zip') as build:
            self.assertEqual(make_zip.main([]), 0)
        build.assert_not_called()
        for args in (['build'], ['--package', 'translation', '--no-date']):
            with self.assertRaises(SystemExit):
                make_zip.parse_args(args)

    def test_translation_name_contents_and_no_game_dependency(self):
        os.environ['ORIGINAL_GAME_FOLDER'] = 'Z:/missing'
        (result,) = self.build('translation')
        self.assertEqual(
            result.name,
            'Starsector(远行星号) 0.98a-RC8 独立汉化包(黑体版) v1.2.3 [远星汉化组].zip',
        )
        self.validate.assert_not_called()
        with zipfile.ZipFile(result) as archive:
            self.assertEqual(
                archive.read('localization/data/text.txt'), '译文'.encode()
            )
            self.assertFalse(any('rules分段' in name for name in archive.namelist()))
        self.assertEqual(list(self.output.glob('*.tmp')), [])

    def test_all_merges_full_without_duplicates_preserves_empty_dirs_and_source(self):
        os.environ['ORIGINAL_GAME_FOLDER'] = str(self.game)
        os.environ['INCLUDE_DATE'] = 'true'
        with mock.patch.object(make_zip, 'date') as today:
            today.today.return_value = date(2026, 9, 9)
            results = self.build('all')
        self.assertEqual(len(results), 2)
        self.assertEqual(
            results[1].name,
            'Starsector(远行星号) 0.98a-RC8 中文汉化版(黑体版) v1.2.3 2026.09.09 [远星汉化组].zip',
        )
        self.validate.assert_called_once_with(self.game, '0.98a-RC8')
        with zipfile.ZipFile(results[1]) as archive:
            self.assertEqual(len(archive.namelist()), len(set(archive.namelist())))
            self.assertEqual(
                archive.read('starsector-core/data/text.txt'), '译文'.encode()
            )
            self.assertEqual(
                archive.read('starsector-core/data/config/settings.json'),
                '中文配置'.encode(),
            )
            self.assertIn('mods/', archive.namelist())
            self.assertIn('starsector.exe', archive.namelist())
            self.assertFalse(any('rules分段' in name for name in archive.namelist()))
        self.assertEqual(
            (self.game / 'starsector-core/data/text.txt').read_text(encoding='utf-8'),
            'English',
        )

    def test_full_only_and_missing_or_invalid_source(self):
        with self.assertRaises(RuntimeError):
            self.build('full')
        os.environ['ORIGINAL_GAME_FOLDER'] = str(self.repo / 'missing')
        with self.assertRaises(RuntimeError):
            self.build('full')
        os.environ['ORIGINAL_GAME_FOLDER'] = str(self.game)
        self.validate.side_effect = RuntimeError('mismatch')
        self.assertEqual(make_zip.main(['--package', 'all']), 1)
        self.assertFalse(self.output.exists())
        self.validate.side_effect = None
        (result,) = self.build('full')
        self.assertIn('中文汉化版', result.name)

    def test_failed_write_preserves_existing_zip_and_cleans_temporary_file(self):
        (result,) = self.build('translation')
        original = result.read_bytes()
        with mock.patch.object(
            zipfile.ZipFile, 'write', side_effect=OSError('disk full')
        ):
            with self.assertRaises(RuntimeError):
                self.build('translation')
        self.assertEqual(result.read_bytes(), original)
        self.assertEqual(list(self.output.glob('*.tmp')), [])


if __name__ == '__main__':
    unittest.main()
