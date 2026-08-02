import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import build


class DynFontAssetTests(unittest.TestCase):
    def test_manifest_is_normalized_and_validated(self):
        assets = build.parse_dynfont_asset_manifest({
            "version": 1,
            "fonts": ["中文.ttf", "Orbitron.ttf"],
            "kerning": [
                {"font": "Orbitron.ttf", "weight": 900, "table": "Orbitron_w900.kern.txt"},
                {"font": "Orbitron.ttf", "weight": 800, "table": "Orbitron_w800.kern.txt"},
            ],
        })

        self.assertEqual(assets.fonts, ("Orbitron.ttf", "中文.ttf"))
        self.assertEqual([item.weight for item in assets.kerning], [800, 900])
        canonical = json.loads(build._asset_manifest_bytes(assets))
        self.assertEqual(canonical["version"], 1)

    def test_manifest_rejects_path_traversal(self):
        with self.assertRaises(SystemExit):
            build.parse_dynfont_asset_manifest({
                "version": 1,
                "fonts": ["../outside.ttf"],
                "kerning": [],
            })

    def test_sync_generates_required_tables_and_removes_stale_tables(self):
        with tempfile.TemporaryDirectory() as temp:
            fonts_dir = Path(temp)
            (fonts_dir / "Orbitron.ttf").write_bytes(b"font")
            (fonts_dir / "Orbitron_w700.kern.txt").write_text("stale", encoding="utf-8")
            assets = build.DynFontAssets(
                ("Orbitron.ttf",),
                (
                    build.KerningAsset("Orbitron.ttf", 800, "Orbitron_w800.kern.txt"),
                    build.KerningAsset("Orbitron.ttf", 900, "Orbitron_w900.kern.txt"),
                ),
            )

            def fake_export(command, **kwargs):
                jobs = []
                for index, value in enumerate(command):
                    if value == "--table":
                        jobs.append((command[index + 1], command[index + 2]))
                for weight, filename in jobs:
                    (fonts_dir / filename).write_text(
                        f"weight={weight}\n", encoding="utf-8")
                return subprocess.CompletedProcess(command, 0)

            with (
                patch.object(build, "DYNFONT_FONTS_DIR", fonts_dir),
                patch.object(build, "DYNFONT_KERNING_EXPORTER", fonts_dir / "export.py"),
                patch.object(build.subprocess, "run", side_effect=fake_export) as run,
            ):
                outputs = build.sync_kerning_tables(assets)

            self.assertEqual({path.name for path in outputs}, {
                "Orbitron_w800.kern.txt", "Orbitron_w900.kern.txt",
            })
            self.assertFalse((fonts_dir / "Orbitron_w700.kern.txt").exists())
            command = run.call_args.args[0]
            self.assertIn(["--table", "800", "Orbitron_w800.kern.txt"], [
                command[index:index + 3] for index, value in enumerate(command) if value == "--table"
            ])
            self.assertIn(["--table", "900", "Orbitron_w900.kern.txt"], [
                command[index:index + 3] for index, value in enumerate(command) if value == "--table"
            ])


class BuildArgumentsTest(unittest.TestCase):
    def test_release_default_does_not_inject_profiling(self) -> None:
        args = build.parse_args([])

        self.assertEqual("off", args.profiling)


if __name__ == "__main__":
    unittest.main()
