import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import make_zip  # noqa: E402
from package_utils import PackageMetadata  # noqa: E402


class ArgumentTest(unittest.TestCase):
    def test_no_arguments_only_prints_help(self) -> None:
        with mock.patch.object(make_zip, 'build_zip') as build:
            self.assertEqual(make_zip.main([]), 0)
        build.assert_not_called()

    def test_build_options_are_explicit(self) -> None:
        parser = make_zip.create_argument_parser()
        defaults = parser.parse_args(['build'])
        self.assertEqual(defaults.command, 'build')
        self.assertIsNone(defaults.include_date)
        self.assertEqual(defaults.compression_level, 6)

        custom = parser.parse_args(['build', '--no-date', '--compression-level', '9'])
        self.assertFalse(custom.include_date)
        self.assertEqual(custom.compression_level, 9)


class BuildZipTest(unittest.TestCase):
    def test_build_is_atomic_and_contains_localization_tree(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            repo = Path(temp)
            localization = repo / 'localization'
            output = repo / 'output'
            localization.mkdir()
            (localization / 'data').mkdir()
            (localization / 'data' / 'text.txt').write_text('译文', encoding='utf-8')
            metadata = PackageMetadata(
                version='1.2.3',
                game_version='0.98a-RC8',
                branch='master',
                variant='(黑体版)',
                used_fallback_variant=False,
            )
            args = make_zip.create_argument_parser().parse_args(['build', '--no-date'])

            with mock.patch.object(make_zip, 'REPO_ROOT', repo), \
                    mock.patch.object(make_zip, 'LOCALIZATION_DIR', localization), \
                    mock.patch.object(make_zip, 'OUTPUT_DIR', output), \
                    mock.patch.object(make_zip, 'load_env'), \
                    mock.patch.object(
                        make_zip, 'load_package_metadata', return_value=metadata
                    ):
                result = make_zip.build_zip(args)

            self.assertTrue(result.is_file())
            self.assertEqual(list(output.glob('*.tmp')), [])
            with zipfile.ZipFile(result) as archive:
                self.assertEqual(archive.namelist(), ['localization/data/text.txt'])
                self.assertEqual(archive.read('localization/data/text.txt'),
                                 '译文'.encode())


if __name__ == '__main__':
    unittest.main()
