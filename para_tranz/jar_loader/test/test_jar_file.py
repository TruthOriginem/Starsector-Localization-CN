import tempfile
import unittest
import zipfile
from pathlib import Path

from para_tranz.jar_loader.jar_file import _rewrite_jar


class RewriteJarTest(unittest.TestCase):
    def test_rewrite_retains_timestamps_and_is_reproducible(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'source.jar'
            first = root / 'first.jar'
            second = root / 'second.jar'
            timestamp = (2001, 2, 3, 4, 5, 6)

            with zipfile.ZipFile(source, 'w') as archive:
                self._write(archive, 'META-INF/MANIFEST.MF', b'manifest', timestamp)
                self._write(archive, 'example/Test.class', b'original', timestamp)

            updates = {'example/Test.class': b'translated'}
            _rewrite_jar(source, first, updates)
            _rewrite_jar(source, second, updates)

            self.assertEqual(first.read_bytes(), second.read_bytes())
            with zipfile.ZipFile(first) as archive:
                self.assertEqual(
                    timestamp, archive.getinfo('META-INF/MANIFEST.MF').date_time
                )
                self.assertEqual(
                    timestamp, archive.getinfo('example/Test.class').date_time
                )
                self.assertEqual(b'manifest', archive.read('META-INF/MANIFEST.MF'))
                self.assertEqual(b'translated', archive.read('example/Test.class'))

    @staticmethod
    def _write(
        archive: zipfile.ZipFile,
        name: str,
        contents: bytes,
        timestamp: tuple[int, int, int, int, int, int],
    ) -> None:
        info = zipfile.ZipInfo(name, timestamp)
        info.compress_type = zipfile.ZIP_DEFLATED
        archive.writestr(info, contents)


if __name__ == '__main__':
    unittest.main()
