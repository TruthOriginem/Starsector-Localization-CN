package org.fossic.starsector.ime;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** 使用缓存的反射元数据读取 LWJGL2 {@code WindowsDisplay.hwnd}。 */
final class LwjglHwndResolver implements HwndResolver {
    private Method getImplementation;
    private Field hwndField;
    private Class<?> implementationClass;
    private Resolution permanentFailure;

    @Override
    public Resolution resolve() {
        if (permanentFailure != null) {
            return permanentFailure;
        }
        try {
            if (getImplementation == null) {
                Class<?> displayClass = Class.forName("org.lwjgl.opengl.Display");
                getImplementation = displayClass.getDeclaredMethod("getImplementation");
                getImplementation.setAccessible(true);
            }

            Object implementation = getImplementation.invoke(null);
            if (implementation == null) {
                return Resolution.retryLater();
            }
            Class<?> currentClass = implementation.getClass();
            if (hwndField == null || implementationClass != currentClass) {
                Field resolved = currentClass.getDeclaredField("hwnd");
                resolved.setAccessible(true);
                hwndField = resolved;
                implementationClass = currentClass;
            }

            long hwnd = hwndField.getLong(implementation);
            return hwnd == 0L ? Resolution.retryLater() : Resolution.ready(hwnd);
        } catch (ReflectiveOperationException | LinkageError | SecurityException error) {
            permanentFailure = Resolution.permanentFailure(
                    "反射获取 LWJGL HWND 失败：" + error.getClass().getSimpleName()
                            + (error.getMessage() == null ? "" : ": " + error.getMessage()));
            return permanentFailure;
        }
    }
}
