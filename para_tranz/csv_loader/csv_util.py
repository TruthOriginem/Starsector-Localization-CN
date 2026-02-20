import re
from typing import List, Set

REGEX_CSV_TOKEN = re.compile(r'\$[a-zA-Z0-9][a-zA-Z0-9_\.]+[a-zA-Z0-9]')
REGEX_IGNORED_TOKENS = re.compile(
    r'([Pp]ersonLastName|[Pp]layerSirOrMadam|[Pp]layerName|ranks?|[Oo]nOrAt|[Ii]sOrAre|[Hh]isOrHer|[Hh]eOrShe|[Hh]imOrHer|[Hh]imOrHerself|shipOrFleet|[Aa]OrAn|[Bb]rotherOrSister|marketFactionArticle)'
)
REGEX_HIGHLIGHT_TARGET = re.compile(
    r'^(SetTextHighlights|Highlight) (.*)(?:\n|$)', re.MULTILINE
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
        highlights.update(parse_only_quoted_strings(param))
    return highlights


def parse_only_quoted_strings(input_text: str) -> List[str]:
    """
    Parses a string and extracts only the quoted parts as a list of strings.

    Args:
        input_text (str): The input string containing quoted and unquoted text.

    Returns:
        list: A list of quoted strings.
    """
    result = []
    current = []
    in_quotes = False
    escape = False

    for char in input_text:
        if escape:
            # Add the escaped character to the current string
            current.append(char)
            escape = False
        elif char == '\\':
            # Mark the next character as escaped
            escape = True
        elif char == '"':
            if in_quotes:
                # End of quoted string
                result.append(''.join(current))
                current = []
                in_quotes = False
            else:
                # Start of a new quoted string
                in_quotes = True
        elif in_quotes:
            # Append characters within quotes
            current.append(char)

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
