"""在新解释器中检查核心模块导入不读取项目数据。"""

import subprocess
import sys
import unittest
from pathlib import Path


class ImportIsolationTest(unittest.TestCase):
    def test_core_imports_have_no_project_io(self) -> None:
        root = Path(__file__).resolve().parents[3]
        code = r"""
import logging
import os
import sys
from pathlib import Path

environment = dict(os.environ)
handlers = list(logging.root.handlers)
def audit(event, args):
    if event == 'open' and isinstance(args[0], (str, bytes)):
        name = Path(os.fsdecode(args[0])).name
        if name in (
            '.env', 'para_tranz_map.json', 'para_tranz_script.log'
        ) or name.endswith('.jar'):
            raise AssertionError('测试不应访问项目文件：' + str(args[0]))
    if event in ('socket.connect', 'subprocess.Popen'):
        raise AssertionError('测试不应访问网络或启动外部工具')
sys.addaudithook(audit)
from para_tranz.utils.cli_args import parse_args
from para_tranz.jar_loader import jar_file
from para_tranz.jar_loader.test.test_rebuild import RebuildJarTest
assert os.environ == environment
assert logging.root.handlers == handlers
assert 'para_tranz.utils.paratranz_api' not in sys.modules
assert 'para_tranz.csv_loader.csv_file' not in sys.modules
assert 'PARA_TRANZ_MAP' not in vars(sys.modules['para_tranz.utils.mapping'])
assert parse_args(['2', '--rebuild-jars']).rebuild_jars
"""
        result = subprocess.run(
            [sys.executable, '-X', 'utf8', '-c', code],
            cwd=root,
            capture_output=True,
            text=True,
            encoding='utf-8',
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
