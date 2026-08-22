"""打包脚本共享的配置与仓库元数据读取工具。"""

from __future__ import annotations

import json
import os
import subprocess
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class PackageMetadata:
    version: str
    game_version: str
    branch: str
    variant: str
    used_fallback_variant: bool


def load_env(env_file: Path) -> None:
    """加载简单 KEY=VALUE 文件，但不覆盖调用者显式设置的环境变量。"""
    if not env_file.is_file():
        return
    for raw_line in env_file.read_text(encoding='utf-8').splitlines():
        line = raw_line.strip()
        if not line or line.startswith('#') or '=' not in line:
            continue
        key, _, value = line.partition('=')
        os.environ.setdefault(key.strip(), value.strip())


def get_git_branch(repo_root: Path) -> str:
    try:
        result = subprocess.run(
            ['git', 'rev-parse', '--abbrev-ref', 'HEAD'],
            check=True,
            capture_output=True,
            text=True,
            encoding='utf-8',
            cwd=repo_root,
        )
    except (OSError, subprocess.CalledProcessError) as exc:
        raise RuntimeError(f'无法读取当前 Git 分支：{exc}') from exc
    branch = result.stdout.strip()
    if not branch or branch == 'HEAD':
        raise RuntimeError('当前不在命名 Git 分支上，无法确定安装包字体变体。')
    return branch


def load_package_metadata(repo_root: Path, localization_dir: Path) -> PackageMetadata:
    version_file = localization_dir / 'localization_version.json'
    try:
        info = json.loads(version_file.read_text(encoding='utf-8'))
        version = os.environ.get('APP_VERSION', '') or info['version']
        game_version = os.environ.get('GAME_VERSION', '') or info['game_version']
    except (OSError, KeyError, json.JSONDecodeError) as exc:
        raise RuntimeError(f'无法读取版本文件 {version_file}：{exc}') from exc

    branch = get_git_branch(repo_root)
    variant = os.environ.get(f'BRANCH_VARIANT_{branch}', '')
    used_fallback = not variant
    if used_fallback:
        variant = os.environ.get('BRANCH_VARIANT_master', '')
    return PackageMetadata(
        version=str(version),
        game_version=str(game_version),
        branch=branch,
        variant=variant,
        used_fallback_variant=used_fallback,
    )


def read_env_bool(name: str, *, default: bool) -> bool:
    raw = os.environ.get(name)
    if raw is None or not raw.strip():
        return default
    normalized = raw.strip().lower()
    if normalized in {'1', 'true', 'yes', 'on'}:
        return True
    if normalized in {'0', 'false', 'no', 'off'}:
        return False
    raise RuntimeError(
        f'环境变量 {name} 必须是 true/false、yes/no、on/off 或 1/0，实际为：{raw}'
    )
