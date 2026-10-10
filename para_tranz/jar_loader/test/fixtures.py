"""独立于游戏文件和被测编码器的最小 Java class 测试数据。"""

import struct


def make_class(
    values: tuple[str, str, str] = (
        'Unknown Location',
        'Unknown Location',
        'Unknown Location',
    ),
) -> bytes:
    def utf8(value: str) -> bytes:
        data = value.encode('utf-8')
        return b'\x01' + struct.pack('>H', len(data)) + data

    # 相同值位于独立 UTF8 常量 #5/#7/#9，另外保留一个无关字符串 #11。
    pool = (
        utf8('example/Test')
        + b'\x07\x00\x01'
        + utf8('java/lang/Object')
        + b'\x07\x00\x03'
        + utf8(values[0])
        + b'\x08\x00\x05'
        + utf8(values[1])
        + b'\x08\x00\x07'
        + utf8(values[2])
        + b'\x08\x00\x09'
        + utf8('Other')
        + b'\x08\x00\x0b'
    )
    header = b'\xca\xfe\xba\xbe' + struct.pack('>HHH', 0, 51, 13)
    # public class Test extends Object，无接口、字段、方法或附加属性。
    return header + pool + struct.pack('>7H', 0x21, 2, 4, 0, 0, 0, 0)
