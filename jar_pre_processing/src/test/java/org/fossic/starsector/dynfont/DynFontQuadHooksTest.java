package org.fossic.starsector.dynfont;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void everyGlyphMovesRigidlyWithoutChangingQuadSize() throws Exception {
        Object glyph = new Object();
        Object state = state();
        PixelTransform base = new PixelTransform(1.95f, 0.25f, 1.95f, -0.4f);
        PixelTransform transform = (PixelTransform) field(state, "transform").get(state);
        transform.setTranslatedFrom(base, 0f, 0f);
        set(state, "active", true);
        set(state, "originWindowX", base.translateX());
        set(state, "originWindowY", base.translateY());
        DynFontQuadHooks.beginGlyph(glyph);

        float[][] raw = {{4.20f, 10.15f}, {4.20f, 2.55f},
                {11.55f, 2.55f}, {11.55f, 10.15f}};
        float[][] snapped = new float[4][2];
        for (int i = 0; i < raw.length; i++) {
            long packed = DynFontQuadHooks.transform(raw[i][0], raw[i][1]);
            snapped[i][0] = DynFontQuadHooks.unpackX(packed);
            snapped[i][1] = DynFontQuadHooks.unpackY(packed);
        }

        float leftWindow = base.scaleX() * snapped[0][0] + base.translateX();
        float topWindow = base.scaleY() * snapped[0][1] + base.translateY();
        assertEquals(Math.round(leftWindow), leftWindow, 0.0001f);
        assertEquals(Math.round(topWindow), topWindow, 0.0001f);
        assertEquals(raw[2][0] - raw[0][0], snapped[2][0] - snapped[0][0],
                0.0001f);
        assertEquals(raw[1][1] - raw[0][1], snapped[1][1] - snapped[0][1],
                0.0001f);
        DynFontQuadHooks.end();
    }

    private static Object state() throws Exception {
        Field holder = DynFontQuadHooks.class.getDeclaredField("STATE");
        holder.setAccessible(true);
        return ((ThreadLocal<?>) holder.get(null)).get();
    }

    private static Field field(Object state, String name) throws Exception {
        Field field = state.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void set(Object state, String name, Object value) throws Exception {
        field(state, name).set(state, value);
    }
}
