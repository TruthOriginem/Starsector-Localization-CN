package org.fossic.starsector.optimization;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * 只在 CSV 引号不匹配的异常路径中格式化最后一个成功解析的 JSON row。
 *
 * <p>这里有意不在编译期依赖游戏附带的 {@code org.json} jar；正常解析路径只保存 row
 * 引用，发生错误时才通过反射调用原版 {@code JSONObject.toString(2)}。因此既保留原错误
 * 文本，又不会为每个正常 row 做 pretty-print。
 */
public final class CsvErrorFormatter {
    private CsvErrorFormatter() {
    }

    public static String formatLastRow(Object row) {
        if (row == null) {
            return null;
        }
        try {
            Method method = row.getClass().getMethod("toString", int.class);
            return (String) method.invoke(row, 2);
        } catch (InvocationTargetException e) {
            return throwUnchecked(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Unable to format the last parsed CSV row", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable, R> R throwUnchecked(Throwable error) throws T {
        throw (T) error;
    }
}
