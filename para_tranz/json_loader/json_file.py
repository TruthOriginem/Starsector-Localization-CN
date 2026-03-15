"""JSON-like 文件（.json/.faction/.skin/.variant/.skill）的 ParaTranz 词条导入导出。

基于 alexson 库解析游戏作者 Alex 风格的非标准 JSON 文件，在保留注释、
格式和非标准语法的前提下提取和写回翻译字符串。
"""

from pathlib import Path
from typing import Iterator, List, Optional, Set, Tuple, Union

from para_tranz.config import (
    EXPORTED_STRING_CONTEXT_PREFIX,
    EXPORTED_STRING_CONTEXT_PREFIX_PREFIX,
    IGNORE_CONTEXT_PREFIX_MISMATCH_STRINGS,
    MAX_STRING_KEY_LENGTH,
    ORIGINAL_PATH,
    PARA_TRANZ_PATH,
)
from para_tranz.json_loader.alexson.parser import AlexsonParser
from para_tranz.json_loader.alexson.syntax_tree import Array, Object, Root
from para_tranz.json_loader.alexson.syntax_tree import String as AlexsonString
from para_tranz.utils.util import DataFile, String, make_logger, relative_path

# ---------------------------------------------------------------------------
# 路径工具
# ---------------------------------------------------------------------------

_SPECIAL_CHARS = set(' "[].$')


def _needs_bracket_notation(key: str) -> bool:
    """判断 key 在 JSONPath 中是否需要使用方括号表示法。"""
    return bool(any(c in _SPECIAL_CHARS for c in key))


def _make_field_path(base: str, key: str) -> str:
    """将字段 key 拼接到路径 base 后，特殊字符使用 ["key"] 表示法。"""
    if _needs_bracket_notation(key):
        return f'{base}["{key}"]'
    return f'{base}.{key}'


def _parse_path_segments(text_path: str) -> List[str]:
    """将 text_path 表达式解析为路径段列表。

    Examples:
        '$.*.name'                  -> ['*', 'name']
        '$.tips[*].tip'             -> ['tips', '[*]', 'tip']
        '$[*]'                      -> ['[*]']
        '$.*'                       -> ['*']
        '$.*.*'                     -> ['*', '*']
        '$.designTypeColors.$key'   -> ['designTypeColors', '$key']
    """
    path = text_path[1:]  # 去掉开头的 '$'
    segments: List[str] = []

    while path:
        if path.startswith('[*]'):
            segments.append('[*]')
            path = path[3:]
        elif path.startswith('.$key'):
            segments.append('$key')
            path = path[5:]
        elif path.startswith('.*'):
            segments.append('*')
            path = path[2:]
        elif path.startswith('.'):
            path = path[1:]
            end = len(path)
            for i, c in enumerate(path):
                if c in '.[]':
                    end = i
                    break
            segments.append(path[:end])
            path = path[end:]
        else:
            raise ValueError(f'无法解析的路径段：{path!r}')

    return segments


def _parse_exact_path(json_path: str) -> List[Union[str, int]]:
    """将具体路径（无通配符）解析为段列表。

    Examples:
        '$.nav_buoy.name'               -> ['nav_buoy', 'name']
        '$.tips[0].tip'                 -> ['tips', 0, 'tip']
        '$.designTypeColors["Low Tech"]'-> ['designTypeColors', 'Low Tech']
        '$[0]'                          -> [0]
    """
    path = json_path[1:]  # 去掉 '$'
    segments: List[Union[str, int]] = []

    while path:
        if path.startswith('['):
            end = path.index(']')
            inner = path[1:end]
            if inner.startswith('"') and inner.endswith('"'):
                segments.append(inner[1:-1])
            else:
                segments.append(int(inner))
            path = path[end + 1 :]
            if path.startswith('.'):
                path = path[1:]
        elif path.startswith('.'):
            path = path[1:]
            end = len(path)
            for i, c in enumerate(path):
                if c in '.[]':
                    end = i
                    break
            segments.append(path[:end])
            path = path[end:]
        else:
            # 字段名在开头（理论上不应出现，但做兼容处理）
            end = len(path)
            for i, c in enumerate(path):
                if c in '.[]':
                    end = i
                    break
            segments.append(path[:end])
            path = path[end:]

    return segments


# ---------------------------------------------------------------------------
# 树遍历
# ---------------------------------------------------------------------------


def _is_translatable_string(node) -> bool:
    """判断节点是否为可翻译的字符串（非空字符串）。"""
    return isinstance(node, AlexsonString) and bool(node.value)


def _traverse_path(
    node,
    segments: List[str],
    current_path: str,
) -> Iterator[Tuple[str, object, Union[str, int], bool]]:
    """递归遍历路径段，生成匹配项。

    Yields:
        (json_path, parent, accessor, is_key_rename)
        - json_path:     词条的 JSONPath 字符串，如 '$.nav_buoy.name'
        - parent:        包含目标的父容器（Object / Array）
        - accessor:      访问目标的 key (str) 或 index (int)
        - is_key_rename: True 表示翻译 key 本身而非 value
    """
    # 透明处理 Root
    if isinstance(node, Root):
        node = node.get_primary_obj()

    if not segments:
        return

    seg = segments[0]
    rest = segments[1:]

    if seg == '*':
        # 展开 Object 的所有 key
        if not isinstance(node, Object):
            return
        for key in list(node.dict.keys()):
            child = node[key]
            new_path = _make_field_path(current_path, key)
            if rest:
                yield from _traverse_path(child, rest, new_path)
            else:
                if _is_translatable_string(child):
                    yield (new_path, node, key, False)

    elif seg == '[*]':
        # 展开 Array 的所有元素
        if not isinstance(node, Array):
            return
        for i, item in enumerate(node.items):
            new_path = f'{current_path}[{i}]'
            if rest:
                yield from _traverse_path(item, rest, new_path)
            else:
                if _is_translatable_string(item):
                    yield (new_path, node, i, False)

    elif seg == '$key':
        # 翻译 Object 的 key 本身
        if not isinstance(node, Object):
            return
        for key in list(node.dict.keys()):
            if key:
                new_path = _make_field_path(current_path, key)
                yield (new_path, node, key, True)

    else:
        # 具名字段访问
        if not isinstance(node, Object) or seg not in node.dict:
            return  # 静默跳过不存在的字段
        child = node[seg]
        new_path = f'{current_path}.{seg}'
        if rest:
            yield from _traverse_path(child, rest, new_path)
        else:
            if _is_translatable_string(child):
                yield (new_path, node, seg, False)


def _navigate_exact(root, segments: List[Union[str, int]]):
    """在树中按精确路径导航，返回目标节点，找不到返回 None。"""
    node = root.get_primary_obj() if isinstance(root, Root) else root
    for seg in segments:
        if isinstance(node, Root):
            node = node.get_primary_obj()
        if isinstance(seg, int):
            if isinstance(node, Array) and 0 <= seg < len(node.items):
                node = node.items[seg]
            else:
                return None
        elif isinstance(node, Object) and seg in node.dict:
            node = node[seg]
        else:
            return None
    return node


# ---------------------------------------------------------------------------
# JsonFile
# ---------------------------------------------------------------------------


class JsonFile(DataFile):
    logger = make_logger('JsonFile')

    def __init__(
        self,
        path: Union[str, Path],
        text_paths: List[str],
        original_path: Optional[Path] = None,
        translation_path: Optional[Path] = None,
        type: str = 'json',
        output_path: Optional[Path] = None,
    ):
        super().__init__(path, type, original_path, translation_path, output_path)
        self.text_paths = text_paths
        self._original_root: Optional[Root] = None
        self._translation_root: Optional[Root] = None
        self.load_from_file()

    @property
    def file_name(self) -> str:
        """完整相对路径，用于生成 ParaTranz key（如 data/config/battle_objectives.json）。"""
        return str(self.path).replace('\\', '/')

    def load_from_file(self) -> None:
        self._original_root = self._parse_file(self.original_path)
        self._translation_root = self._parse_file(self.translation_path)

    def _parse_file(self, path: Path) -> Optional[Root]:
        if not path.exists():
            self.logger.warning(f'文件不存在：{relative_path(path)}')
            return None
        try:
            with open(path, 'r', encoding='utf-8') as f:
                return AlexsonParser(f.read()).parse()
        except Exception as e:
            self.logger.warning(f'解析文件失败 {relative_path(path)}：{e}')
            return None

    def _generate_key(self, json_path: str, is_key_rename: bool = False) -> str:
        """生成 ParaTranz 词条 key：{文件名}{json_path}[#key]

        key 重命名条目追加 #key 后缀，以便与普通 value 翻译区分。
        """
        key = f'{self.file_name}{json_path}'
        if is_key_rename:
            key += '#key'
        if len(key) > MAX_STRING_KEY_LENGTH:
            raise ValueError(
                f'生成的词条 key 超过最大长度 {MAX_STRING_KEY_LENGTH}：{key!r}'
            )
        return key

    def _generate_context(self, json_path: str, is_key_rename: bool = False) -> str:
        context = f'{EXPORTED_STRING_CONTEXT_PREFIX}源文件：{self.path}\n数据路径：{json_path}'
        if is_key_rename:
            context += '\n（词条内容为json key值）'
        return context

    def _iter_strings(
        self, root: Root
    ) -> Iterator[Tuple[str, object, Union[str, int], bool]]:
        """遍历所有 text_paths，生成 (json_path, parent, accessor, is_key_rename)。"""
        for text_path in self.text_paths:
            try:
                segments = _parse_path_segments(text_path)
            except ValueError as e:
                self.logger.warning(f'路径表达式解析失败 {text_path!r}：{e}')
                continue
            yield from _traverse_path(root, segments, '$')

    def get_strings(self) -> List[String]:
        if self._original_root is None:
            return []

        strings: List[String] = []
        seen_keys: Set[str] = set()

        for json_path, orig_parent, accessor, is_key_rename in self._iter_strings(
            self._original_root
        ):
            try:
                key = self._generate_key(json_path, is_key_rename)
            except ValueError as e:
                self.logger.warning(str(e))
                continue

            if key in seen_keys:
                continue
            seen_keys.add(key)

            # 原文
            if is_key_rename:
                original = str(accessor)
            else:
                node = orig_parent[accessor]
                if not isinstance(node, AlexsonString):
                    continue
                original = node.value

            # 译文：从 translation_root 同路径取值
            translation = ''
            if self._translation_root is not None:
                exact_segs = _parse_exact_path(json_path)
                if is_key_rename:
                    # key 重命名：按位置匹配译文树中对应位置的 key
                    parent_segs = exact_segs[:-1]
                    orig_key = str(accessor)
                    trans_parent = _navigate_exact(self._translation_root, parent_segs)
                    if isinstance(trans_parent, Object):
                        orig_keys = list(orig_parent.dict.keys())
                        trans_keys = list(trans_parent.dict.keys())
                        try:
                            idx = orig_keys.index(orig_key)
                            if idx < len(trans_keys):
                                translation = trans_keys[idx]
                        except ValueError:
                            pass
                else:
                    trans_node = _navigate_exact(self._translation_root, exact_segs)
                    if isinstance(trans_node, AlexsonString) and trans_node.value:
                        translation = trans_node.value

            stage = 1 if translation else 0
            strings.append(
                String(
                    key=key,
                    original=original,
                    translation=translation,
                    stage=stage,
                    context=self._generate_context(json_path, is_key_rename),
                )
            )

        return strings

    def update_strings(
        self, strings: List[String], version_migration: bool = False
    ) -> None:
        if self._translation_root is None:
            self.logger.warning(
                f'译文文件不存在，无法更新：{relative_path(self.translation_path)}'
            )
            return
        if self._original_root is None:
            return

        key_to_string = {s.key: s for s in strings}

        for json_path, _orig_parent, accessor, is_key_rename in self._iter_strings(
            self._original_root
        ):
            try:
                key = self._generate_key(json_path, is_key_rename)
            except ValueError:
                continue

            if key not in key_to_string:
                continue

            pt_string = key_to_string[key]

            # 检查 context 版本前缀
            if IGNORE_CONTEXT_PREFIX_MISMATCH_STRINGS and pt_string.context:
                if not pt_string.context.startswith(
                    EXPORTED_STRING_CONTEXT_PREFIX_PREFIX
                ):
                    self.logger.debug(f'跳过版本前缀不匹配的词条：{key}')
                    continue

            translation = pt_string.translation
            if not translation:
                continue

            exact_segs = _parse_exact_path(json_path)

            if is_key_rename:
                parent_segs = exact_segs[:-1]
                old_key = str(accessor)
                trans_parent = _navigate_exact(self._translation_root, parent_segs)
                if isinstance(trans_parent, Object):
                    if old_key in trans_parent.dict:
                        if old_key != translation:
                            try:
                                trans_parent.rename_key(old_key, translation)
                            except KeyError as e:
                                self.logger.warning(f'重命名 key 失败 {key}：{e}')
                    # old_key 不存在但 translation 已存在 → 已翻译，跳过
                else:
                    self.logger.warning(
                        f'在 {self.path} 中没有找到词条 key={key} 对应的位置，未写入译文'
                    )
            else:
                parent_segs = exact_segs[:-1]
                last_seg = exact_segs[-1]
                trans_parent = _navigate_exact(self._translation_root, parent_segs)

                if isinstance(last_seg, int):
                    if isinstance(trans_parent, Array) and 0 <= last_seg < len(
                        trans_parent.items
                    ):
                        trans_parent[last_seg] = AlexsonString(translation)
                    else:
                        self.logger.warning(
                            f'在 {self.path} 中没有找到词条 key={key} 对应的位置，未写入译文'
                        )
                elif isinstance(last_seg, str):
                    if (
                        isinstance(trans_parent, Object)
                        and last_seg in trans_parent.dict
                    ):
                        trans_parent[last_seg] = AlexsonString(translation)
                    else:
                        self.logger.warning(
                            f'在 {self.path} 中没有找到词条 key={key} 对应的位置，未写入译文'
                        )

    def save_file(self) -> None:
        if self._translation_root is None:
            self.logger.warning(
                f'译文文件不存在，无法保存：{relative_path(self.translation_path)}'
            )
            return
        self.translation_path.parent.mkdir(parents=True, exist_ok=True)
        with open(self.translation_path, 'w', encoding='utf-8') as f:
            f.write(self._translation_root.to_alexson())
        self.logger.info(f'已保存译文文件：{relative_path(self.translation_path)}')

    @classmethod
    def load_files_from_config(cls) -> List['JsonFile']:
        from para_tranz.utils.mapping import PARA_TRANZ_MAP, JsonMapItem

        files: List['JsonFile'] = []
        for item in PARA_TRANZ_MAP:
            if not isinstance(item, JsonMapItem):
                continue

            item_path = Path(item.path)
            output_path = (PARA_TRANZ_PATH / item.combined_output) if item.combined_output else None

            # 支持 glob 模式（如 data/missions/*/descriptor.json）
            if '*' in item.path or '?' in item.path:
                matched = sorted(ORIGINAL_PATH.glob(item.path))
                if not matched:
                    cls.logger.warning(f'glob 模式未匹配到任何文件：{item.path}')
                for actual_path in matched:
                    rel_path = actual_path.relative_to(ORIGINAL_PATH)
                    files.append(cls(path=rel_path, text_paths=item.text_paths, output_path=output_path))
            else:
                files.append(cls(path=item_path, text_paths=item.text_paths, output_path=output_path))

        return files
