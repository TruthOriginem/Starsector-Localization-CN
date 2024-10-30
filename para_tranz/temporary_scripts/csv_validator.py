# 将父级目录加入到环境变量中，以便从命令行中运行本脚本
import sys
from os.path import abspath, dirname
sys.path.append(dirname(dirname(dirname(abspath(__file__)))))

from para_tranz.utils.config import TRANSLATION_PATH

def validate_csv(file_path):
    with open(file_path, 'r', encoding='utf-8') as f:
        content = f.read()

    # 提取第一行作为表头
    header_str, body_str = content.split('\n', 1)
    headers = header_str.split(',')
    
    # 读取每一行的数据
    data = []
    pos = 0
    in_quote = False
    last_quote_start = (0, 0) # 开始引号的位置 (行数,行内位置)
    
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
                    raise ValueError(f'行 {line_count} 列 {headers[header_index]} 存在多余的引号，位置 {(line_count, pos_in_line)}')
                in_quote = True
                cell_quoted = True
                last_quote_start = (line_count, pos_in_line)
        
        elif body_str[pos] == ',' and not in_quote:
            row_data[headers[header_index]] = cell_data
            cell_data = ''
            header_index += 1
            cell_quoted = False
        
        elif body_str[pos] == '\n':
            if not in_quote: # row结束
                if header_index < len(headers) - 1:
                    raise ValueError(f'行 {line_count} 数据不完整，缺少列 {headers[header_index:]}')
                elif header_index == len(headers) - 1:
                    if in_quote:
                        raise ValueError(f'行 {line_count} 数据不完整，从 {last_quote_start} 开始的引号未闭合')
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
        
    print('CSV文件验证通过')
    print('有效数据行数：', len(data), '+ 1 行表头')
    
    


if __name__ == "__main__":
    file_path = TRANSLATION_PATH / 'data' / 'campaign' / 'rules.csv'
    validate_csv(file_path)