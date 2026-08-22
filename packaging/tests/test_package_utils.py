import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import package_utils  # noqa: E402


class EnvironmentTest(unittest.TestCase):
    def test_load_env_preserves_explicit_environment(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            env_file = Path(temp) / '.env'
            env_file.write_text('EXISTING=file\nNEW_VALUE=loaded\n', encoding='utf-8')
            with mock.patch.dict(os.environ, {'EXISTING': 'caller'}, clear=True):
                package_utils.load_env(env_file)
                self.assertEqual(os.environ['EXISTING'], 'caller')
                self.assertEqual(os.environ['NEW_VALUE'], 'loaded')

    def test_read_env_bool_is_strict(self) -> None:
        with mock.patch.dict(os.environ, {'FLAG': 'yes'}, clear=True):
            self.assertTrue(package_utils.read_env_bool('FLAG', default=False))
        with mock.patch.dict(os.environ, {'FLAG': 'invalid'}, clear=True), \
                self.assertRaisesRegex(RuntimeError, 'FLAG'):
            package_utils.read_env_bool('FLAG', default=False)


if __name__ == '__main__':
    unittest.main()
