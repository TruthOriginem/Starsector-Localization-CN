import re
from typing import List, Set

REGEX_CSV_TOKEN = re.compile(r'\$[a-zA-Z0-9][a-zA-Z0-9_\.]+[a-zA-Z0-9]')
REGEX_IGNORED_TOKENS = re.compile(
    r'([Pp]ersonLastName|[Pp]layerSirOrMadam|[Pp]layerName|ranks?|[Oo]nOrAt|[Ii]sOrAre|[Hh]isOrHer|[Hh]eOrShe|[Hh]imOrHer|[Hh]imOrHerself|shipOrFleet|[Aa]OrAn|[Bb]rotherOrSister|marketFactionArticle)'
)
REGEX_HIGHLIGHT_TARGET = re.compile(
    r'^(SetTextHighlights|Highlight) (.*)(?:\n|$)', re.MULTILINE
)
REGEX_OPTION_ID = re.compile(r'^\s*((?:\d+:)?[A-Za-z0-9_]+):', re.MULTILINE)
REGEX_HIGHLIGHT_COMMAND = re.compile(
    r'^\s*(?:Highlight|SetTextHighlights)\b', re.MULTILINE
)


def rules_csv_extract_csv_tokens(s: str) -> Set[str]:
    """
    从csv文件中提取token

    Args:
        s (str): csv文件内容

    Returns:
        Set[str]: token集合
    """
    return set(re.findall(REGEX_CSV_TOKEN, s))


class TokenIgnoreCase:
    def __init__(self, name: str):
        self.name = name

    def __eq__(self, other):
        return self.name.lower() == other.name.lower()

    def __hash__(self):
        return hash(self.name.lower())


def rules_csv_find_missing_csv_tokens(original: str, translation: str) -> Set[str]:
    """
    找到原文中的token在译文中缺失的token

    Args:
        original (str): 原文
        translation (str): 译文

    Returns:
        Set[str]: 缺失的token集合
    """
    original_tokens = rules_csv_extract_csv_tokens(original)

    # 去除一些不需要检查的token
    original_token_strs = {
        token for token in original_tokens if not re.search(REGEX_IGNORED_TOKENS, token)
    }
    original_tokens = {TokenIgnoreCase(name) for name in original_token_strs}

    included_tokens = {
        TokenIgnoreCase(name) for name in rules_csv_extract_csv_tokens(translation)
    }

    return {token.name for token in original_tokens - included_tokens}


def rules_csv_extract_option_ids(options: str) -> List[str]:
    """
    从rules的options列中按顺序提取每行行首的选项ID（含可选的数字排序前缀，如 "100:id"）

    Args:
        options (str): options列内容

    Returns:
        List[str]: 选项ID列表
    """
    return REGEX_OPTION_ID.findall(options or '')


def rules_csv_count_highlight_commands(script: str) -> int:
    """
    统计script列中设置正文高亮的命令(Highlight / SetTextHighlights)总行数。

    Args:
        script (str): rules脚本

    Returns:
        int: 高亮命令行数
    """
    return len(REGEX_HIGHLIGHT_COMMAND.findall(script or ''))


def rules_csv_max_highlight_commands_per_paragraph(script: str) -> int:
    """
    统计script中同一段落内正文高亮命令(Highlight / SetTextHighlights)数量的最大值。

    游戏中每条该命令都会重置目标段落的全部已有高亮（后执行的命令会清除先前
    命令的效果），因此同一段落内出现多条时只有最后一条生效，应合并为一条多
    参数命令。AddText 命令会新增一个段落，其后的高亮命令作用于新段落，
    因此以 AddText 为界分段统计。

    Args:
        script (str): rules脚本

    Returns:
        int: 单个段落内高亮命令数量的最大值
    """
    max_count = count = 0
    for line in (script or '').splitlines():
        if line.strip().startswith('AddText'):
            count = 0
        elif REGEX_HIGHLIGHT_COMMAND.match(line):
            count += 1
            max_count = max(max_count, count)
    return max_count


def rules_csv_extract_highlight_targets_from_script(script: str) -> Set[str]:
    """
    从rules的scripts列中提取高亮目标string

    Args:
        script (str): rules脚本

    Returns:
        Set[str]: 高亮string集合
    """
    highlight_command_params = re.findall(REGEX_HIGHLIGHT_TARGET, script)
    highlights = set()
    for command, param in highlight_command_params:
        for s in parse_highlight_params(param):
            # 去除尾部的英文标点（这些标点是句子的一部分，不属于高亮目标本身）
            highlights.add(s.rstrip('.,;:!?'))
    return highlights


_REGEX_HIGHLIGHT_PARAM = re.compile(
    r'"((?:[^"\\]|\\.)*)"|\$[a-zA-Z0-9][a-zA-Z0-9_.]+[a-zA-Z0-9]'
)


def parse_highlight_params(input_text: str) -> List[str]:
    """
    从 Highlight / SetTextHighlights 命令的参数中提取高亮目标。
    支持带引号的字符串（如 "word"）和 $token 形式的变量。

    Args:
        input_text (str): 命令参数部分

    Returns:
        List[str]: 提取到的高亮目标列表
    """
    result = []
    for m in _REGEX_HIGHLIGHT_PARAM.finditer(input_text):
        if m.group(1) is not None:
            result.append(m.group(1).replace('\\"', '"'))
        else:
            result.append(m.group(0))
    return result


def rules_csv_find_text_highlight_targets_adjacent_to_non_space(
    text: str, highlights: Set[str]
) -> Set[str]:
    """
    找到文本中左右存在非空格和英文引号的高亮string

    Args:
        text (str): 文本

    Returns:
        Set[str]: 左右存在非空格和英文引号的高亮string集合
    """
    return {
        highlight
        for highlight in highlights
        if not re.search(
            rf'(?:^|[ {{}}"\']){re.escape(highlight)}(?:$|[ {{}}"\'])',
            text,
            re.MULTILINE,
        )
    }


if __name__ == '__main__':
    script = (
        'Highlight "关闭你的应答器"\nShowPersonVisual\nSetShortcut cutCommLink "ESCAPE"'
    )
    highlights = rules_csv_extract_highlight_targets_from_script(script)
    print(highlights)

    text = '你有种感觉，如果关闭你的应答器隐瞒身份进入港口，事情可能会有更多的进展。'
    print(rules_csv_find_text_highlight_targets_adjacent_to_non_space(text, highlights))
