import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import game_integrity  # noqa: E402


class TreeIntegrityTest(unittest.TestCase):
    def test_hash_is_deterministic_and_covers_empty_directories(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / 'empty').mkdir()
            (root / 'data').mkdir()
            (root / 'data' / 'a.txt').write_bytes(b'alpha')

            first = game_integrity.calculate_tree_integrity(root)
            second = game_integrity.calculate_tree_integrity(root)
            self.assertEqual(first, second)
            self.assertEqual(first.files, 1)
            self.assertEqual(first.directories, 2)
            self.assertEqual(first.bytes, 5)

            (root / 'another-empty').mkdir()
            self.assertNotEqual(first, game_integrity.calculate_tree_integrity(root))

    def test_hash_detects_file_content_and_path_changes(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = root / 'source.bin'
            source.write_bytes(b'original')
            original = game_integrity.calculate_tree_integrity(root)

            source.write_bytes(b'modified')
            self.assertNotEqual(original, game_integrity.calculate_tree_integrity(root))

            source.write_bytes(b'original')
            source.rename(root / 'renamed.bin')
            self.assertNotEqual(original, game_integrity.calculate_tree_integrity(root))

    def test_validation_rejects_unknown_version_and_mismatch(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            with self.assertRaisesRegex(RuntimeError, '尚未登记'):
                game_integrity.validate_original_game_folder(root, 'unknown')
            with self.assertRaisesRegex(RuntimeError, '与官方原版不一致'):
                game_integrity.validate_original_game_folder(root, '0.98a-RC8')


if __name__ == '__main__':
    unittest.main()
