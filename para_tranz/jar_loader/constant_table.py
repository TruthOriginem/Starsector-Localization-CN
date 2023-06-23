from enum import Enum
from typing import Optional, List, Union, Set


class ConstantType(Enum):
    Class = 7
    Fieldref = 9
    Methodref = 10
    InterfaceMethodref = 11
    String = 8
    Integer = 3
    Float = 4
    Long = 5
    Double = 6
    NameAndType = 12
    Utf8 = 1
    MethodHandle = 15
    MethodType = 16
    InvokeDynamic = 18


CONSTANT_LENGTHS = {
    ConstantType.Class: 3,
    ConstantType.Fieldref: 5,
    ConstantType.Methodref: 5,
    ConstantType.InterfaceMethodref: 5,
    ConstantType.String: 3,
    ConstantType.Integer: 5,
    ConstantType.Float: 5,
    ConstantType.Long: 9,
    ConstantType.Double: 9,
    ConstantType.NameAndType: 5,
    ConstantType.MethodHandle: 4,
    ConstantType.MethodType: 3,
    ConstantType.InvokeDynamic: 5
}


class ConstantTable:
    """
    用于表示class文件中的常量表
    """

    def __init__(self, class_bytes: bytes):
        self.class_bytes = class_bytes
        self.constant_count = int.from_bytes(class_bytes[8:10], 'big')

        self.table_end_index = None

        self.constants = []  # type: List[Union[UTF8_Constant, bytes]]
        self.utf8_string_references = set()  # type: Set[int]

        self._load_constants()

    def _load_constants(self):
        byte_index = 4 + 2 + 2 + 2  # magic + minor_version + major_version + constant_count
        constant_index = 1

        while constant_index < self.constant_count:
            constant_type = ConstantType(self.class_bytes[byte_index])

            if constant_type == ConstantType.Utf8:
                constant = UTF8_Constant(self.class_bytes[byte_index:], constant_index)
                byte_index += 3 + constant.length
                self.constants.append(constant)
            else:
                # 将原始字节存入常量表
                length = CONSTANT_LENGTHS[constant_type]
                self.constants.append(self.class_bytes[byte_index:byte_index + length])

                if constant_type == ConstantType.String:
                    byte_index += 1  # 跳过 tag
                    string_index = int.from_bytes(self.class_bytes[byte_index:byte_index + 2], 'big')
                    # 记录字符串常量的索引
                    self.utf8_string_references.add(string_index)
                    byte_index += 2

                else:
                    byte_index += length

            constant_index += 1

        self.table_end_index = byte_index

        if len(self.constants) != self.constant_count - 1:
            raise ValueError(
                f'常量表中的实际解析出的常量数量({len(self.constants)})与constant_count={self.constant_count} 不符')

    def get_utf8_constants_which_have_string_ref(self) -> List['UTF8_Constant']:
        """
        获取常量表中所有的字符串常量
        """
        return [self.constants[i - 1] for i in self.utf8_string_references
                if type(self.constants[i - 1]) == UTF8_Constant]

    def to_bytes(self) -> bytes:
        """
        将常量表转换为字节流
        """
        bytes = self.constant_count.to_bytes(2, 'big')
        for constant in self.constants:
            if isinstance(constant, UTF8_Constant):
                bytes += constant.to_bytes()
            else:
                bytes += constant

        return bytes


class UTF8_Constant:
    def __init__(self, bytes: bytes, constant_index: int):
        self.length = int.from_bytes(bytes[1:3], 'big')
        self.string = bytes[3:3 + self.length].decode('utf-8')

        self.constant_index = constant_index

    def to_bytes(self) -> bytes:
        return ConstantType.Utf8.value.to_bytes(1, 'big') \
            + self.length.to_bytes(2, 'big') \
            + self.string.encode('utf-8')

    def __str__(self):
        return self.string

    def __repr__(self):
        return f'UTF8_Constant("{self.string}")'
