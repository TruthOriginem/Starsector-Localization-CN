import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import make_exe  # noqa: E402
from package_utils import PackageMetadata  # noqa: E402

METADATA = PackageMetadata(
    version='2026.08.21',
    game_version='0.98a-RC8',
    branch='master',
    variant='(黑体版)',
    used_fallback_variant=False,
)


class ArgumentTest(unittest.TestCase):
    def test_no_arguments_only_prints_help(self) -> None:
        with mock.patch.object(make_exe, 'build_installers') as build:
            self.assertEqual(make_exe.main([]), 0)
        build.assert_not_called()

    def test_can_select_each_package_type(self) -> None:
        self.assertEqual(make_exe.parse_args(['--package', 'all']).package, 'all')
        self.assertEqual(
            make_exe.parse_args(['--package', 'translation']).package, 'translation'
        )
        self.assertEqual(make_exe.parse_args(['--package', 'full']).package, 'full')


class PackageSelectionTest(unittest.TestCase):
    def test_translation_only_does_not_access_original_game(self) -> None:
        with (
            mock.patch.dict(os.environ, {'ORIGINAL_GAME_FOLDER': 'Z:/does-not-exist'}),
            mock.patch.object(make_exe, 'generate_design_type_fragment'),
            mock.patch.object(make_exe, 'find_iscc', return_value=Path('ISCC.exe')),
            mock.patch.object(make_exe, 'load_package_metadata', return_value=METADATA),
            mock.patch.object(make_exe, 'run_iscc', return_value=True) as run_iscc,
        ):
            result = make_exe.main(['--package', 'translation'])

        self.assertEqual(result, 0)
        run_iscc.assert_called_once()
        self.assertEqual(
            run_iscc.call_args.args[1].name, 'ss_translation_pack_installer.iss'
        )

    def test_full_only_validates_then_builds_only_full_installer(self) -> None:
        with (
            tempfile.TemporaryDirectory() as temp,
            mock.patch.dict(os.environ, {'ORIGINAL_GAME_FOLDER': temp}),
            mock.patch.object(make_exe, 'validate_original_game_folder') as validate,
            mock.patch.object(make_exe, 'generate_design_type_fragment'),
            mock.patch.object(make_exe, 'find_iscc', return_value=Path('ISCC.exe')),
            mock.patch.object(make_exe, 'load_package_metadata', return_value=METADATA),
            mock.patch.object(make_exe, 'run_iscc', return_value=True) as run_iscc,
        ):
            result = make_exe.main(['--package', 'full'])

        self.assertEqual(result, 0)
        validate.assert_called_once_with(Path(temp), '0.98a-RC8')
        run_iscc.assert_called_once()
        self.assertEqual(
            run_iscc.call_args.args[1].name,
            'ss_translation_pack_with_game_installer.iss',
        )

    def test_all_fails_integrity_before_either_installer_runs(self) -> None:
        with (
            tempfile.TemporaryDirectory() as temp,
            mock.patch.dict(os.environ, {'ORIGINAL_GAME_FOLDER': temp}),
            mock.patch.object(
                make_exe,
                'validate_original_game_folder',
                side_effect=RuntimeError('mismatch'),
            ),
            mock.patch.object(make_exe, 'generate_design_type_fragment') as fragment,
            mock.patch.object(make_exe, 'find_iscc', return_value=Path('ISCC.exe')),
            mock.patch.object(make_exe, 'load_package_metadata', return_value=METADATA),
            mock.patch.object(make_exe, 'run_iscc', return_value=True) as run_iscc,
        ):
            result = make_exe.main(['--package', 'all'])

        self.assertEqual(result, 1)
        fragment.assert_not_called()
        run_iscc.assert_not_called()


if __name__ == '__main__':
    unittest.main()
