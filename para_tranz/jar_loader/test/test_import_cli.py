import unittest
from unittest.mock import Mock

from para_tranz.cli_args import Command, dispatch_command, parse_args


class ImportCliTest(unittest.TestCase):
    def test_parse_import_flags(self) -> None:
        for option in ('2', '3'):
            self.assertEqual(Command(option), parse_args([option]))
            self.assertEqual(
                Command(option, rebuild_jars=True),
                parse_args([option, '--rebuild-jars']),
            )

    def test_invalid_arguments(self) -> None:
        for args in (
            [],
            ['0'],
            ['1', '--rebuild-jars'],
            ['2', '--unknown'],
            ['3', '--rebuild-jars', '--rebuild-jars'],
        ):
            with self.subTest(args=args):
                with self.assertRaises(ValueError):
                    parse_args(args)

    def test_class_and_search_arguments_are_literal(self) -> None:
        for option in ('4', '5'):
            for text in ('--rebuild-jars', '中文 空格', 'com.fs.SomeClass'):
                self.assertEqual(Command(option, text), parse_args([option, text]))
            self.assertEqual(Command(option), parse_args([option]))

    def test_dispatch(self) -> None:
        for option in ('1', '2', '3', '4', '5', '6'):
            with self.subTest(option=option):
                action = Mock()
                command = Command(option, '测试参数', True)
                dispatch_command(command, {option: action})
                if option in ('2', '3'):
                    action.assert_called_once_with(rebuild_jars=True)
                elif option in ('4', '5'):
                    action.assert_called_once_with('测试参数')
                else:
                    action.assert_called_once_with()
