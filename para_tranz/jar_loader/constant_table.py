from enum import Enum
from typing import List


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
    ConstantType.InvokeDynamic: 5,
}


class ConstantTable:
    """
    用于表示class文件中的常量表
    """

    # TODO: 1. 增加检测同一个UTF8常量是否被多次引用的功能，尤其是被其他非String类型的常量引用
    # TODO: 2. 考虑将被String引用的常量单独复制并添加到常量表尾部，以避免被其他非String类型的常量引用
    # TODO: 3. 分析class文件的剩余部分，找出String被引用的方法名

    def __init__(self, class_bytes: bytes) -> None:
        self.class_bytes = class_bytes
        self.constant_count = int.from_bytes(class_bytes[8:10], 'big')

        self.table_end_index = None

        self.constants = []  # type: List[Union[BaseConstant, bytes]]
        self.utf8_string_references = set()  # type: Set[int]
        self.utf8_other_references = set()  # type: Set[int]

        self._load_constants()

    def _load_constants(self) -> None:
        byte_index = (
            4 + 2 + 2 + 2
        )  # magic + minor_version + major_version + constant_count
        constant_index = 1

        while constant_index < self.constant_count:
            constant_type = ConstantType(self.class_bytes[byte_index])

            if constant_type == ConstantType.Utf8:
                constant = Utf8Constant(self.class_bytes[byte_index:], constant_index)
                byte_index += len(constant.to_bytes())
                self.constants.append(constant)
            elif constant_type == ConstantType.String:
                constant = StringConstant(
                    self.class_bytes[
                        byte_index : byte_index + CONSTANT_LENGTHS[constant_type]
                    ],
                    constant_index,
                )
                self.utf8_string_references.add(constant.string_index)
                byte_index += CONSTANT_LENGTHS[constant_type]
                self.constants.append(constant)
            elif constant_type == ConstantType.Class:
                constant = ClassConstant(
                    self.class_bytes[
                        byte_index : byte_index + CONSTANT_LENGTHS[constant_type]
                    ],
                    constant_index,
                )
                self.utf8_other_references.add(constant.name_index)
                byte_index += CONSTANT_LENGTHS[constant_type]
                self.constants.append(constant)
            elif constant_type == ConstantType.NameAndType:
                constant = NameAndTypeConstant(
                    self.class_bytes[
                        byte_index : byte_index + CONSTANT_LENGTHS[constant_type]
                    ],
                    constant_index,
                )
                self.utf8_other_references.add(constant.name_index)
                self.utf8_other_references.add(constant.descriptor_index)
                byte_index += CONSTANT_LENGTHS[constant_type]
                self.constants.append(constant)
            else:
                # 将原始字节存入常量表
                length = CONSTANT_LENGTHS[constant_type]
                self.constants.append(
                    self.class_bytes[byte_index : byte_index + length]
                )
                byte_index += length

            constant_index += 1

            if (
                constant_type == ConstantType.Long
                or constant_type == ConstantType.Double
            ):
                # long 和 double 类型的常量占两个位置
                constant_index += 1
                self.constants.append(b'')  # 占位以保证数组索引与常量索引一致

        self.table_end_index = byte_index

    def get_utf8_constants_with_string_ref(self) -> List['Utf8Constant']:
        """
        获取常量表中所有被String类型常量引用的Utf8常量
        """
        return [
            self.constants[i - 1]
            for i in self.utf8_string_references
            if isinstance(self.constants[i - 1], Utf8Constant)
        ]

    def get_utf8_constants_with_extra_ref(self) -> List['Utf8Constant']:
        """
        获取常量表中所有被String常量和其他常量同时引用的Utf8常量
        """
        return [
            self.constants[i - 1]
            for i in (self.utf8_other_references & self.utf8_string_references)
            if isinstance(self.constants[i - 1], Utf8Constant)
        ]

    def to_bytes(self) -> bytes:
        """
        将常量表转换为字节流
        """
        bytes = self.constant_count.to_bytes(2, 'big')
        for constant in self.constants:
            if isinstance(constant, BaseConstant):
                bytes += constant.to_bytes()
            else:
                bytes += constant

        return bytes


class BaseConstant:
    def __init__(self, bytes: bytes, constant_index: int) -> None:
        self.constant_index = constant_index

    def to_bytes(self) -> bytes:
        pass


class Utf8Constant(BaseConstant):
    def __init__(self, bytes: bytes, constant_index: int) -> None:
        super().__init__(bytes, constant_index)
        self.length = int.from_bytes(bytes[1:3], 'big')
        self.string = bytes[3 : 3 + self.length].decode('utf-8')

    def to_bytes(self) -> bytes:
        string_bytes = self.string.encode('utf-8')
        self.length = len(string_bytes)

        return (
            ConstantType.Utf8.value.to_bytes(1, 'big')
            + self.length.to_bytes(2, 'big')
            + string_bytes
        )

    def __str__(self) -> str:
        return self.string

    def __repr__(self) -> str:
        return f'UTF8("{self.string}")'


class StringConstant(BaseConstant):
    def __init__(self, bytes: bytes, constant_index: int) -> None:
        super().__init__(bytes, constant_index)
        self.string_index = int.from_bytes(bytes[1:3], 'big')

    def to_bytes(self) -> bytes:
        return ConstantType.String.value.to_bytes(
            1, 'big'
        ) + self.string_index.to_bytes(2, 'big')

    def __str__(self) -> str:
        return repr(self)

    def __repr__(self) -> str:
        return f'String(# {self.string_index})'


class NameAndTypeConstant(BaseConstant):
    def __init__(self, bytes: bytes, constant_index: int) -> None:
        super().__init__(bytes, constant_index)
        self.name_index = int.from_bytes(bytes[1:3], 'big')
        self.descriptor_index = int.from_bytes(bytes[3:5], 'big')

    def to_bytes(self) -> bytes:
        return (
            ConstantType.NameAndType.value.to_bytes(1, 'big')
            + self.name_index.to_bytes(2, 'big')
            + self.descriptor_index.to_bytes(2, 'big')
        )

    def __str__(self) -> str:
        return repr(self)

    def __repr__(self) -> str:
        return f'NameAndType(#{self.name_index}, #{self.descriptor_index})'


class ClassConstant(BaseConstant):
    def __init__(self, bytes: bytes, constant_index: int) -> None:
        super().__init__(bytes, constant_index)
        self.name_index = int.from_bytes(bytes[1:3], 'big')

    def to_bytes(self) -> bytes:
        return ConstantType.Class.value.to_bytes(1, 'big') + self.name_index.to_bytes(
            2, 'big'
        )

    def __str__(self) -> str:
        return repr(self)

    def __repr__(self) -> str:
        return f'Class(#{self.name_index})'
