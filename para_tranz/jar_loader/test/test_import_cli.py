import unittest
from unittest.mock import Mock, patch

from para_tranz import para_tranz_script as script


class ImportCliTest(unittest.TestCase):
    def test_flag_reaches_import_commands(self) -> None:
        for option, function in [
            ('2', 'paratranz_to_game'),
            ('3', 'download_and_import_from_paratranz'),
        ]:
            for args, enabled in [([], False), (['--rebuild-jars'], True)]:
                with self.subTest(option=option, enabled=enabled):
                    with (
                        patch.object(script.sys, 'argv', ['script', option, *args]),
                        patch.object(script, function) as action,
                    ):
                        script.mian()
                        action.assert_called_once_with(rebuild_jars=enabled)

    def test_invalid_flags_fail_before_work(self) -> None:
        for args in [
            ('1', '--rebuild-jars'),
            ('2', '--unknown'),
            ('3', '--rebuild-jars', '--rebuild-jars'),
        ]:
            with self.subTest(args=args):
                with (
                    patch.object(script.sys, 'argv', ['script', *args]),
                    patch.object(script, 'game_to_paratranz') as export,
                    patch.object(script, 'paratranz_to_game') as load,
                ):
                    with self.assertRaises(SystemExit):
                        script.mian()
                    export.assert_not_called()
                    load.assert_not_called()

    def test_search_argument_is_not_parsed_as_flag(self) -> None:
        with (
            patch.object(script.sys, 'argv', ['script', '5', '--rebuild-jars']),
            patch.object(script, 'search_string_in_jar_files') as search,
        ):
            script.mian()
            search.assert_called_once_with('--rebuild-jars')

    def test_only_jar_loader_uses_rebuild_path(self) -> None:
        other = Mock()
        other_file = Mock()
        other.load_files_from_config.return_value = [other_file]
        with (
            patch.object(script, 'loaders', [script.JavaJarFile, other]),
            patch.object(script.JavaJarFile, 'rebuild_from_config') as rebuild,
            patch.object(script.JavaJarFile, 'load_files_from_config') as load,
        ):
            script.paratranz_to_game(rebuild_jars=True)
            rebuild.assert_called_once()
            load.assert_not_called()
            other_file.update_from_json.assert_called_once()
            other_file.save_file.assert_called_once()

    def test_default_import_uses_existing_loader(self) -> None:
        file = Mock()
        with (
            patch.object(script, 'loaders', [script.JavaJarFile]),
            patch.object(
                script.JavaJarFile, 'load_files_from_config', return_value=[file]
            ),
            patch.object(script.JavaJarFile, 'rebuild_from_config') as rebuild,
        ):
            script.paratranz_to_game()
            rebuild.assert_not_called()
            file.update_from_json.assert_called_once()
            file.save_file.assert_called_once()

    def test_download_failure_and_import_failure_do_not_export(self) -> None:
        with (
            patch.object(script, 'download_paratranz_export', return_value=False),
            patch.object(script, 'paratranz_to_game') as load,
            patch.object(script, 'game_to_paratranz') as export,
        ):
            script.download_and_import_from_paratranz(True)
            load.assert_not_called()
            export.assert_not_called()
        with (
            patch.object(script, 'download_paratranz_export', return_value=True),
            patch.object(
                script, 'paratranz_to_game', side_effect=ValueError('校验失败')
            ) as load,
            patch.object(script, 'game_to_paratranz') as export,
        ):
            with self.assertRaises(ValueError):
                script.download_and_import_from_paratranz(True)
            load.assert_called_once_with(rebuild_jars=True)
            export.assert_not_called()
