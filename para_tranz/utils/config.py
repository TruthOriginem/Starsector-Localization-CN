import logging
from datetime import datetime
from pathlib import Path

# [日志输出]
LOG_LEVEL = logging.INFO

# [路径配置]
# 设置游戏原文，译文和Paratranz数据文件路径
PROJECT_DIRECTORY = Path(__file__).parent.parent.parent
ORIGINAL_PATH = PROJECT_DIRECTORY / 'original'
TRANSLATION_PATH = PROJECT_DIRECTORY / 'localization'
PARA_TRANZ_PATH = PROJECT_DIRECTORY / 'para_tranz' / 'output'
MAP_PATH = PROJECT_DIRECTORY / 'para_tranz' / 'para_tranz_map.json'

# [通用配置]
# 在导出字符串时是否覆盖已导出字符串的翻译stage状态
OVERRIDE_STRING_STATUS = False
# 导出词条上下文前缀文本
EXPORTED_STRING_CONTEXT_PREFIX_PREFIX = '版本：0.98-RC8 词条格式：v2'
EXPORTED_STRING_CONTEXT_PREFIX = f'{EXPORTED_STRING_CONTEXT_PREFIX_PREFIX}\n'
# 在将译文写回游戏文件时，是否跳过上下文前缀与当前上下文前缀不匹配的译文
IGNORE_CONTEXT_PREFIX_MISMATCH_STRINGS = True
# Paratranz 平台允许的最大词条key长度
MAX_STRING_KEY_LENGTH = 256

# [jar_loader 配置]
MAGIC = b'\xca\xfe\xba\xbe'
MIN_CLASS_VER: int = 0x31  # 1.5
MAX_CLASS_VER = 0x3D  # 17

# 原文匹配时是否忽略首尾空白字符
ORIGINAL_TEXT_MATCH_IGNORE_WHITESPACE_CHARS = True
# 在将译文写回jar文件时，是否允许空译文
UPDATE_STRING_ALLOW_EMPTY_TRANSLATION = True

# [csv_loader 配置]
# 在将译文写回csv文件时，是否删除原文为空的译文
REMOVE_TRANSLATION_WHEN_ORIGINAL_IS_EMPTY = True
