"""在独立项目副本中检查真实命令入口和中文日志。"""

import json
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

from para_tranz.jar_loader.test.fixtures import make_class


class CliEntryTest(unittest.TestCase):
    def test_rebuild_command_and_logs(self) -> None:
        source = Path(__file__).resolve().parents[2]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            scripts = root / 'para_tranz'
            for path in source.rglob('*.py'):
                target = scripts / path.relative_to(source)
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(path, target)
            mapping = [{'type': 'jar', 'path': 'test.jar', 'class_files': []}]
            (scripts / 'para_tranz_map.json').write_text(
                json.dumps(mapping), encoding='utf-8'
            )
            (scripts / 'output').mkdir()
            (scripts / 'output/test.json').write_text('[]', encoding='utf-8')
            (root / 'original').mkdir()
            with zipfile.ZipFile(root / 'original/test.jar', 'w') as archive:
                archive.writestr('example/Test.class', make_class())
            result = subprocess.run(
                [
                    sys.executable,
                    '-X',
                    'utf8',
                    str(scripts / 'para_tranz_script.py'),
                    '2',
                    '--rebuild-jars',
                ],
                cwd=root,
                capture_output=True,
                text=True,
                encoding='utf-8',
                timeout=30,
            )
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertEqual(
                (root / 'original/test.jar').read_bytes(),
                (root / 'localization/test.jar').read_bytes(),
            )
            log = (scripts / 'para_tranz_script.log').read_text(encoding='utf-8')
            for message in ('汉化 jar 重建完成', '程序执行完毕'):
                self.assertIn(message, result.stdout)
                self.assertIn(message, log)
