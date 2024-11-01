// Alex 的 com.fs.starfarer.loading.G.class 中的代码，用于解析 CSV 文件
// 此为使用 FernFlower 反编译后的代码，人工重命名了变量名

import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class CSVParser {
    public CSVParser() {
    }

    public static JSONArray parseCSV(String csvContent) throws JSONException {
        JSONArray resultArray = new JSONArray();
        csvContent = csvContent.replaceAll("\r\n", "\n");
        ArrayList<String> headerList = new ArrayList<>();
        int newlineIndex = csvContent.indexOf("\n");
        if (newlineIndex != -1 && csvContent.length() > newlineIndex + 1) {
            String headerLine = csvContent.substring(0, newlineIndex);
            String[] headerArray = headerLine.split(",");
            String[] headerElements = headerArray;
            int headerCount = headerArray.length;

            for (int headerIndex = 0; headerIndex < headerCount; ++headerIndex) {
                String header = headerElements[headerIndex];
                header = header.trim();
                if (header.startsWith("\"")) {
                    header = header.substring(1);
                }

                if (header.endsWith("\"")) {
                    header = header.substring(0, header.length() - 1);
                }

                header = header.replaceAll("\"\"", "\"");
                headerList.add(header);
            }

            csvContent = csvContent.substring(newlineIndex + 1) + "\n";
            JSONObject currentRow = null;
            StringBuffer currentCellBuffer = null;
            int columnIndex = 0;
            StringBuffer lastQuotedString = new StringBuffer();
            String lastRowString = null;
            boolean inQuotes = false;
            boolean isNewRow = false;
            ParseState parseState = CSVParser.ParseState.START;
            boolean isCommentRow = false;

            for (int charIndex = 0; charIndex < csvContent.length(); ++charIndex) {
                char currentChar = csvContent.charAt(charIndex);
                char nextChar;
                if (charIndex + 1 < csvContent.length()) {
                    nextChar = csvContent.charAt(charIndex + 1);
                } else {
                    nextChar = ' ';
                }

                if (parseState == CSVParser.ParseState.START) {
                    if (currentChar == '#') {
                        isCommentRow = true;
                    } else {
                        isCommentRow = false;
                    }

                    currentRow = new JSONObject();
                    columnIndex = 0;
                    isNewRow = true;
                    parseState = CSVParser.ParseState.IN_OBJECT;
                } else {
                    isNewRow = false;
                }

                if (parseState == CSVParser.ParseState.IN_OBJECT) {
                    currentCellBuffer = new StringBuffer();
                    parseState = CSVParser.ParseState.IN_CELL;
                }

                if (parseState == CSVParser.ParseState.IN_CELL) {
                    if (currentChar == '"') {
                        if (nextChar == '"') {
                            currentCellBuffer.append(currentChar);
                            lastQuotedString.append(currentChar);
                            ++charIndex;
                        } else {
                            inQuotes = !inQuotes;
                            if (inQuotes) {
                                lastQuotedString = new StringBuffer();
                            }
                        }
                    } else if ((currentChar == ',' || currentChar == '\n') && !inQuotes) {
                        if (columnIndex < headerList.size()) {
                            currentRow.put(headerList.get(columnIndex), currentCellBuffer.toString());
                        }

                        if (currentChar == ',') {
                            ++columnIndex;
                            parseState = CSVParser.ParseState.IN_OBJECT;
                        } else {
                            if (!isNewRow && !isCommentRow) {
                                resultArray.put(currentRow);
                                lastRowString = currentRow.toString(2);
                            }

                            parseState = CSVParser.ParseState.START;
                        }
                    } else {
                        currentCellBuffer.append(currentChar);
                        lastQuotedString.append(currentChar);
                    }
                }
            }

            if (inQuotes) {
                throw new JSONException("Mismatched quotes in the string; last quote: [" + lastQuotedString + "], last added row:\n" + lastRowString);
            } else {
                return resultArray;
            }
        } else {
            return resultArray;
        }
    }

    private static enum ParseState {
        IN_CELL,
        START,
        IN_OBJECT;

        private ParseState() {
        }
    }
}
