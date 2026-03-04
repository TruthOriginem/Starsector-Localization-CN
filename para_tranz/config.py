import logging
import os
from pathlib import Path

# 从项目根目录的 .env 文件中加载环境变量（可选）
_env_path = Path(__file__).parent.parent / '.env'
if _env_path.exists():
    with open(_env_path, encoding='utf-8') as _f:
        for _line in _f:
            _line = _line.strip()
            if _line and not _line.startswith('#') and '=' in _line:
                _key, _, _value = _line.partition('=')
                os.environ.setdefault(_key.strip(), _value.strip())

# [日志输出]
LOG_LEVEL = logging.INFO

# [路径配置]
# 设置游戏原文，译文和Paratranz数据文件路径
PROJECT_DIRECTORY = Path(__file__).parent.parent
ORIGINAL_PATH = PROJECT_DIRECTORY / 'original'
TRANSLATION_PATH = PROJECT_DIRECTORY / 'localization'
PARA_TRANZ_PATH = PROJECT_DIRECTORY / 'para_tranz' / 'output'
MAP_PATH = PROJECT_DIRECTORY / 'para_tranz' / 'para_tranz_map.json'

# [处理的文件类型]
# 可选：'jar'、'csv'，或两者都包含
ENABLED_LOADERS = ['jar', 'csv']

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

# [ParaTranz 平台配置]
# 从 .env 文件中读取，详见 .env.example
PARATRANZ_PROJECT_ID: int = int(os.environ.get('PARATRANZ_PROJECT_ID', 0))
PARATRANZ_API_KEY: str = os.environ.get('PARATRANZ_API_KEY', '')
