package org.fossic.starsector.dynfont;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.nio.IntBuffer;
import org.junit.jupiter.api.Test;

final class DynFontQuadHooksTest {
    @Test
    void viewportQueryBufferSatisfiesLwjgl2Minimum() throws Exception {
        Field stateHolderField = DynFontQuadHooks.class.getDeclaredField(
                "STATE");
        stateHolderField.setAccessible(true);
        ThreadLocal<?> stateHolder =
                (ThreadLocal<?>) stateHolderField.get(null);
        Object state = stateHolder.get();

        Field viewportField = state.getClass().getDeclaredField("viewport");
        viewportField.setAccessible(true);
        IntBuffer viewport = (IntBuffer) viewportField.get(state);

        assertTrue(
                viewport.remaining() >= 16,
                "LWJGL2 GL11.glGetInteger requires at least 16 remaining "
                        + "elements even for GL_VIEWPORT");
    }
}
