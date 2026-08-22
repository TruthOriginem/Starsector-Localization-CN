"""含游戏完整包来源目录的确定性完整性校验。"""

from __future__ import annotations

import hashlib
import os
import stat
from dataclasses import dataclass
from pathlib import Path

# 完整包会递归收录 ORIGINAL_GAME_FOLDER 的全部内容。为避免把存档、mod、日志或
# 被修改的游戏文件带进发布包，编译前对路径、空目录和全部文件内容计算确定性树
# 哈希。哈希只与相对路径和内容有关，不受安装时间等文件系统元数据影响。
#
# 游戏升级时必须用官方安装器全新安装对应版本，再更新这里的四项基线。不要在
# 已经启动过游戏或安装过 mod 的目录上生成新基线。
ORIGINAL_GAME_INTEGRITY = {
    '0.98a-RC8': {
        'sha256': '73222ceaaa29516667ee0a7d1ab8e3a1b41ed3b5c4191dd29b041c83a164fb85',
        'files': 5845,
        'directories': 309,
        'bytes': 346541095,
    },
}
TREE_HASH_DOMAIN = b'Starsector original game tree v1\0'
FILE_ATTRIBUTE_REPARSE_POINT = getattr(stat, 'FILE_ATTRIBUTE_REPARSE_POINT', 0x400)
HASH_CHUNK_SIZE = 1024 * 1024


@dataclass(frozen=True)
class TreeIntegrity:
    sha256: str
    files: int
    directories: int
    bytes: int


def _is_reparse_point(path: Path) -> bool:
    return bool(
        getattr(os.lstat(path), 'st_file_attributes', 0)
        & FILE_ATTRIBUTE_REPARSE_POINT
    )


def calculate_tree_integrity(root: Path) -> TreeIntegrity:
    """计算包含空目录、相对路径和文件内容的确定性文件树哈希。"""
    try:
        root_stat = os.lstat(root)
    except OSError as exc:
        raise RuntimeError(f'无法读取原版游戏目录：{root}（{exc}）') from exc
    if not stat.S_ISDIR(root_stat.st_mode) or _is_reparse_point(root):
        raise RuntimeError(f'原版游戏路径必须是真实目录，不能是符号链接或联接点：{root}')

    entries: list[tuple[str, str, Path]] = []
    try:
        for dirpath, dirnames, filenames in os.walk(root, topdown=True, followlinks=False):
            directory = Path(dirpath)
            for name in dirnames:
                path = directory / name
                if _is_reparse_point(path):
                    raise RuntimeError(f'原版游戏目录包含符号链接或联接点：{path}')
                entries.append((path.relative_to(root).as_posix(), 'D', path))
            for name in filenames:
                path = directory / name
                item_stat = os.lstat(path)
                if (
                    getattr(item_stat, 'st_file_attributes', 0)
                    & FILE_ATTRIBUTE_REPARSE_POINT
                ):
                    raise RuntimeError(f'原版游戏目录包含符号链接或联接点：{path}')
                if not stat.S_ISREG(item_stat.st_mode):
                    raise RuntimeError(f'原版游戏目录包含非普通文件：{path}')
                entries.append((path.relative_to(root).as_posix(), 'F', path))
    except OSError as exc:
        raise RuntimeError(f'扫描原版游戏目录失败：{root}（{exc}）') from exc

    # Windows 路径不区分大小写；casefold 主键保证遍历顺序与文件系统返回顺序无关，
    # 原始路径作为次键并参与哈希，因此改名和大小写变化仍会被检测。
    entries.sort(key=lambda item: (item[0].casefold(), item[0]))
    digest = hashlib.sha256()
    digest.update(TREE_HASH_DOMAIN)
    file_count = 0
    directory_count = 0
    total_bytes = 0

    for relative, kind, path in entries:
        relative_bytes = relative.encode('utf-8')
        digest.update(kind.encode('ascii'))
        digest.update(len(relative_bytes).to_bytes(4, 'big'))
        digest.update(relative_bytes)
        if kind == 'D':
            directory_count += 1
            continue

        try:
            before = path.stat()
            digest.update(before.st_size.to_bytes(8, 'big'))
            with path.open('rb') as stream:
                while chunk := stream.read(HASH_CHUNK_SIZE):
                    digest.update(chunk)
            after = path.stat()
        except OSError as exc:
            raise RuntimeError(f'读取原版游戏文件失败：{path}（{exc}）') from exc
        if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
            raise RuntimeError(f'校验期间文件发生变化，请关闭游戏及相关程序后重试：{path}')
        file_count += 1
        total_bytes += before.st_size

    return TreeIntegrity(
        sha256=digest.hexdigest(),
        files=file_count,
        directories=directory_count,
        bytes=total_bytes,
    )


def validate_original_game_folder(root: Path, game_version: str) -> TreeIntegrity:
    expected = ORIGINAL_GAME_INTEGRITY.get(game_version)
    if expected is None:
        raise RuntimeError(
            f'尚未登记游戏版本 {game_version} 的原版文件树哈希，拒绝生成完整包。'
        )

    print(f'校验完整包来源：{root}')
    actual = calculate_tree_integrity(root)
    expected_integrity = TreeIntegrity(**expected)
    if actual != expected_integrity:
        raise RuntimeError(
            '完整包来源目录与官方原版不一致，拒绝打包。\n'
            f'  目录：{root}\n'
            f'  预期：sha256={expected_integrity.sha256}，文件={expected_integrity.files}，'
            f'目录={expected_integrity.directories}，字节={expected_integrity.bytes}\n'
            f'  实际：sha256={actual.sha256}，文件={actual.files}，'
            f'目录={actual.directories}，字节={actual.bytes}\n'
            '请清空该目录并使用对应版本的官方安装包重新安装；不要启动游戏或加入 mod。'
        )
    print(
        f'原版文件树校验通过：{actual.files} 个文件，{actual.directories} 个目录，'
        f'SHA-256 {actual.sha256}'
    )
    return actual
