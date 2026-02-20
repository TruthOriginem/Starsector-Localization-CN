# 将父级目录加入到环境变量中，以便从命令行中运行本脚本
import sys
from enum import Enum
from os.path import abspath, dirname

sys.path.append(dirname(dirname(dirname(dirname(abspath(__file__))))))

from para_tranz.utils.config import TRANSLATION_PATH


def validate_csv(file_path):
    with open(file_path, 'r', encoding='utf-8') as f:
        content = f.read()

    # 你知道吗，Alex在读csv文件的时候，会把中文引号替换成英文引号哦！
    # 真是™绝了
    content = content.replace('“', '"').replace('”', '"')

    data = parse_csv_jn(content)

    print('CSV文件验证通过 - JN 实现')
    print('有效数据行数：', len(data), '+ 1 行表头')

    data = CSVParserAlex.parseCSV(content)

    print('CSV文件验证通过 - Alex 实现')
    print('有效数据行数：', len(data), '+ 1 行表头')


import json
from typing import List, Union


# 以下为参考 Alex 的 java 代码实现的 CSV 解析器
class CSVParserAlex:
    class ParseState(Enum):
        IN_CELL = 'IN_CELL'
        START = 'START'
        IN_OBJECT = 'IN_OBJECT'

    def __init__(self) -> None:
        pass

    @staticmethod
    def parseCSV(csvContent: str) -> List[dict]:
        resultArray: List[dict] = []
        csvContent = csvContent.replace('\r\n', '\n')
        headerList: List[str] = []
        newlineIndex: int = csvContent.find('\n')

        if newlineIndex != -1 and len(csvContent) > newlineIndex + 1:
            headerLine: str = csvContent[:newlineIndex]
            headerArray: List[str] = headerLine.split(',')
            headerElements: List[str] = headerArray
            headerCount: int = len(headerArray)

            for headerIndex in range(headerCount):
                header: str = headerElements[headerIndex].strip()
                if header.startswith('"'):
                    header = header[1:]

                if header.endswith('"'):
                    header = header[:-1]

                header = header.replace('""', '"')
                headerList.append(header)

            csvContent = csvContent[newlineIndex + 1 :] + '\n'
            currentRow: Union[dict, None] = None
            currentCellBuffer: Union[List[str], None] = None
            columnIndex: int = 0
            lastQuotedString: List[str] = []
            lastRowString: Union[str, None] = None
            inQuotes: bool = False
            isNewRow: bool = False
            parseState: 'CSVParser.ParseState' = CSVParser.ParseState.START
            isCommentRow: bool = False

            charIndex: int = 0
            while charIndex < len(csvContent):
                currentChar: str = csvContent[charIndex]
                nextChar: str = (
                    csvContent[charIndex + 1]
                    if charIndex + 1 < len(csvContent)
                    else ' '
                )

                if parseState == CSVParser.ParseState.START:
                    if currentChar == '#':
                        isCommentRow = True
                    else:
                        isCommentRow = False

                    currentRow = {}
                    columnIndex = 0
                    isFirstRow = True
                    parseState = CSVParser.ParseState.IN_OBJECT
                else:
                    isFirstRow = False

                if parseState == CSVParser.ParseState.IN_OBJECT:
                    currentCellBuffer = []
                    parseState = CSVParser.ParseState.IN_CELL

                if parseState == CSVParser.ParseState.IN_CELL:
                    if currentChar == '"':
                        if nextChar == '"':
                            currentCellBuffer.append(currentChar)
                            lastQuotedString.append(currentChar)
                            charIndex += 1  # Move to skip the second quote
                        else:
                            inQuotes = not inQuotes
                            if inQuotes:
                                lastQuotedString = []
                    elif (currentChar == ',' or currentChar == '\n') and not inQuotes:
                        if columnIndex < len(headerList):
                            currentRow[headerList[columnIndex]] = ''.join(
                                currentCellBuffer
                            )
                        if currentChar == ',':
                            columnIndex += 1
                            parseState = CSVParser.ParseState.IN_OBJECT
                        else:
                            if not isFirstRow and not isCommentRow:
                                resultArray.append(currentRow)
                                lastRowString = json.dumps(
                                    currentRow, indent=2, ensure_ascii=False
                                )
                            parseState = CSVParser.ParseState.START
                    else:
                        currentCellBuffer.append(currentChar)
                        lastQuotedString.append(currentChar)

                charIndex += 1  # Move to the next character

            if inQuotes:
                raise ValueError(
                    f'Mismatched quotes in the string; last quote: [{"".join(lastQuotedString)}], last added row:\n{lastRowString}',
                )
            else:
                return resultArray
        else:
            return resultArray


# 以下为自己手搓的 CSV 解析器
def parse_csv_jn(csv_content: str) -> List[dict]:
    # 提取第一行作为表头
    header_str, body_str = csv_content.split('\n', 1)
    headers = header_str.split(',')

    # 读取每一行的数据
    data = []
    pos = 0
    in_quote = False
    last_quote_start = (0, 0)  # 开始引号的位置 (行数,行内位置)

    row_data = {}
    cell_data = ''
    cell_quoted = False
    header_index = 0

    line_count = 2
    pos_in_line = 1

    while pos < len(body_str):
        if body_str[pos] == '"':
            if in_quote:
                if pos + 1 < len(body_str) and body_str[pos + 1] == '"':
                    pos += 1
                    cell_data += '"'
                else:
                    in_quote = False
            else:
                if cell_quoted:
                    raise ValueError(
                        f'行 {line_count} 列 {headers[header_index]} 存在多余的引号，位置 {(line_count, pos_in_line)}'
                    )
                in_quote = True
                cell_quoted = True
                last_quote_start = (line_count, pos_in_line)

        elif body_str[pos] == ',' and not in_quote:
            row_data[headers[header_index]] = cell_data
            cell_data = ''
            header_index += 1
            cell_quoted = False

        elif body_str[pos] == '\n':
            if not in_quote:  # row结束
                if header_index < len(headers) - 1:
                    raise ValueError(
                        f'行 {line_count} 数据不完整，缺少列 {headers[header_index:]}'
                    )
                elif header_index == len(headers) - 1:
                    if in_quote:
                        raise ValueError(
                            f'行 {line_count} 数据不完整，从 {last_quote_start} 开始的引号未闭合'
                        )
                    data.append(row_data)
                    row_data = {}
                    header_index = 0
            else:
                cell_data += body_str[pos]

            line_count += 1
            pos_in_line = 1

        else:
            cell_data += body_str[pos]

        pos += 1
        pos_in_line += 1

    return data


if __name__ == '__main__':
    file_path = TRANSLATION_PATH / 'data' / 'campaign' / 'rules.csv'
    validate_csv(file_path)
