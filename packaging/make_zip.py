"""
制作汉化补丁压缩包
输出文件名格式：远行星号 {game_version} 汉化补丁 v{version} {date} {variant}
"""

import json
import re
import subprocess
import zipfile
from datetime import date
from pathlib import Path

REPO_ROOT = Path(__file__).parent.parent
LOCALIZATION_DIR = REPO_ROOT / "localization"
OUTPUT_DIR = Path(__file__).parent / "Output"

BRANCH_VARIANT: dict[str, str] = {
    "master": "(黑体版)",
    "font-simsong": "(宋体版)",
    "font-zongyi": "(综艺体版)",
}


def get_git_branch() -> str:
    result = subprocess.run(
        ["git", "rev-parse", "--abbrev-ref", "HEAD"],
        capture_output=True, text=True, cwd=REPO_ROOT
    )
    return result.stdout.strip()


def format_game_version(raw: str) -> str:
    """'0.98a-RC8' -> '0.98 RC-8'"""
    m = re.match(r"(\d+\.\d+)[a-zA-Z]?-RC(\d+)", raw)
    if m:
        return f"{m.group(1)} RC-{m.group(2)}"
    return raw


def main() -> None:
    version_file = LOCALIZATION_DIR / "localization_version.json"
    info = json.loads(version_file.read_text(encoding="utf-8"))
    version = info["version"]
    game_version = format_game_version(info["game_version"])

    branch = get_git_branch()
    variant = BRANCH_VARIANT.get(branch, "")

    today = date.today().strftime("%Y.%m.%d")

    parts = [f"远行星号 {game_version} 汉化补丁 v{version} {today}"]
    if variant:
        parts.append(variant)
    zip_name = " ".join(parts) + ".zip"

    OUTPUT_DIR.mkdir(exist_ok=True)
    output_path = OUTPUT_DIR / zip_name

    print(f"打包中: {zip_name}")
    with zipfile.ZipFile(output_path, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as zf:
        for file in sorted(LOCALIZATION_DIR.rglob("*")):
            if file.is_file():
                arcname = file.relative_to(REPO_ROOT)
                zf.write(file, arcname)
                print(f"  {arcname}")

    size_mb = output_path.stat().st_size / 1024 / 1024
    print(f"完成: {output_path}  ({size_mb:.1f} MB)")


if __name__ == "__main__":
    main()
