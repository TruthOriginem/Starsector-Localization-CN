import logging
from pathlib import Path

# [日志输出]
LOG_LEVEL = logging.INFO
# 是否在打印DEBUG级别的日志信息时覆盖前一行的日志信息（即只输出回车\r而不是换行\n+回车\r）
LOG_DEBUG_OVERWRITE = False

# [路径配置]
# 设置游戏原文，译文和Paratranz数据文件路径
PROJECT_DIRECTORY = Path(__file__).parent.parent.parent
ORIGINAL_PATH = PROJECT_DIRECTORY / 'original'
TRANSLATION_PATH = PROJECT_DIRECTORY / 'localization'
PARA_TRANZ_PATH = PROJECT_DIRECTORY / 'para_tranz' / 'output'
MAP_PATH = PROJECT_DIRECTORY / 'para_tranz' / 'para_tranz_map.json'

# [通用配置]
PARA_TRANZ_PROJECT_ID = 3489
# 在导出字符串时是否覆盖已导出字符串的翻译stage状态
OVERRIDE_STRING_STATUS = False

# [jar_loader 配置]
MAGIC = b'\xca\xfe\xba\xbe'
MIN_CLASS_VER: int = 0x31  # 1.5
MAX_CLASS_VER = 0x33  # 1.7

# 原文匹配时是否忽略首尾空白字符
ORIGINAL_TEXT_MATCH_IGNORE_WHITESPACE_CHARS = True
