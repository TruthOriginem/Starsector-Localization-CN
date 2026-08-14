package org.fossic.starsector.dynfont;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** 代理字体最终 glyph quad 的 framebuffer 像素边界吸附。 */
public final class DynFontQuadHooks {
    private static final int GL_MODELVIEW_MATRIX = 0x0BA6;
    private static final int GL_PROJECTION_MATRIX = 0x0BA7;
    private static final int GL_VIEWPORT = 0x0BA2;
    // LWJGL2 对所有 glGetInteger pname 都先检查至少 16 个剩余元素。
    private static final int LWJGL_QUERY_BUFFER_ELEMENTS = 16;

    private static volatile MethodHandle glGetFloat;
    private static volatile MethodHandle glGetInteger;
    private static volatile boolean reflectionReady;
    private static volatile boolean broken;
    private static volatile boolean failureLogged;

    private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);

    private DynFontQuadHooks() {
    }

    /** 整个 renderer.render 入口每次只查询一次 GL 矩阵。 */
    public static void begin(Object font, Object extraTransform) {
        State state = STATE.get();
        state.reset();
        if (broken || extraTransform != null || !DynFontRenderHooks.isProxyFont(font)) {
            return;
        }
        try {
            ensureReflection();
            state.model.clear();
            state.projection.clear();
            state.viewport.clear();
            glGetFloat.invokeExact(GL_MODELVIEW_MATRIX, state.model);
            glGetFloat.invokeExact(GL_PROJECTION_MATRIX, state.projection);
            glGetInteger.invokeExact(GL_VIEWPORT, state.viewport);
            copy(state.model, state.modelValues);
            copy(state.projection, state.projectionValues);
            copy(state.viewport, state.viewportValues);
            if (state.baseTransform.setFromOpenGl(
                    state.modelValues, state.projectionValues,
                    state.viewportValues, state.combinedValues)) {
                state.active = true;
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    public static void end() {
        STATE.get().reset();
    }

    /** 原版每个阴影/正文 pass 的 glTranslatef(f,f2) 在 CPU 侧合入吸附变换。 */
    public static void translate(float x, float y) {
        State state = STATE.get();
        if (state.active) {
            state.transform.setTranslatedFrom(state.baseTransform, x, y);
            if (!state.originSet) {
                // 第一个阴影/正文 pass 的局部原点是整个 render 的共同物理原点。
                // 后续 pass 和全部 glyph 都相对它量化，保证动画平移时刚性移动。
                state.originWindowX = state.transform.translateX();
                state.originWindowY = state.transform.translateY();
                state.originSet = true;
            }
            state.vertexIndex = 0;
        }
    }

    /** 每段文本一次的 ASM 分流；非代理文本之后完全走未改写的原版 glyph 方法。 */
    public static boolean isActive() {
        return STATE.get().active;
    }

    /** 返回打包后的两个 float；ASM 解包后仍由原版直接调用 GL11.glVertex2f。 */
    public static long transform(float x, float y) {
        State state = STATE.get();
        if (state.active) {
            int vertex = state.vertexIndex++ & 3;
            if (vertex == 0) {
                state.rawLeft = x;
                state.rawTop = y;
                state.left = state.transform.snapXRelativeTo(
                        x, state.originWindowX);
                state.top = state.transform.snapYRelativeTo(
                        y, state.originWindowY);
                x = state.left;
                y = state.top;
            } else if (vertex == 1) {
                state.bottom = state.transform.snapYEndRelativeTo(
                        state.rawTop, state.top, y, state.originWindowY);
                x = state.left;
                y = state.bottom;
            } else if (vertex == 2) {
                state.right = state.transform.snapXEndRelativeTo(
                        state.rawLeft, state.left, x, state.originWindowX);
                x = state.right;
                y = state.bottom;
            } else {
                x = state.right;
                y = state.top;
            }
        }
        return ((long) Float.floatToRawIntBits(x) << 32)
                | (Float.floatToRawIntBits(y) & 0xffffffffL);
    }

    public static float unpackX(long packed) {
        return Float.intBitsToFloat((int) (packed >>> 32));
    }

    public static float unpackY(long packed) {
        return Float.intBitsToFloat((int) packed);
    }

    private static synchronized void ensureReflection() throws Throwable {
        if (reflectionReady) {
            return;
        }
        Class<?> gl11 = Class.forName("org.lwjgl.opengl.GL11");
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        glGetFloat = lookup.findStatic(gl11, "glGetFloat",
                MethodType.methodType(void.class, int.class, FloatBuffer.class));
        glGetInteger = lookup.findStatic(gl11, "glGetInteger",
                MethodType.methodType(void.class, int.class, IntBuffer.class));
        reflectionReady = true;
    }

    private static void copy(FloatBuffer buffer, float[] out) {
        for (int i = 0; i < out.length; i++) out[i] = buffer.get(i);
    }

    private static void copy(IntBuffer buffer, int[] out) {
        for (int i = 0; i < out.length; i++) out[i] = buffer.get(i);
    }

    private static void fail(Throwable t) {
        broken = true;
        STATE.get().reset();
        if (!failureLogged) {
            failureLogged = true;
            try {
                DynFontLog.error("物理像素吸附初始化失败，后续关闭吸附", t);
            } catch (Throwable ignored) {
                // 日志失败也不可递归
            }
        }
    }

    private static final class State {
        final FloatBuffer model = directFloats(16);
        final FloatBuffer projection = directFloats(16);
        final IntBuffer viewport = directInts(LWJGL_QUERY_BUFFER_ELEMENTS);
        final float[] modelValues = new float[16];
        final float[] projectionValues = new float[16];
        final float[] combinedValues = new float[16];
        final int[] viewportValues = new int[4];
        final PixelTransform baseTransform = new PixelTransform(1f, 0f, 1f, 0f);
        final PixelTransform transform = new PixelTransform(1f, 0f, 1f, 0f);
        boolean active;
        int vertexIndex;
        float rawLeft;
        float rawTop;
        float left;
        float top;
        float right;
        float bottom;
        boolean originSet;
        float originWindowX;
        float originWindowY;

        void reset() {
            active = false;
            vertexIndex = 0;
            originSet = false;
        }

        private static FloatBuffer directFloats(int count) {
            return ByteBuffer.allocateDirect(count * Float.BYTES)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
        }

        private static IntBuffer directInts(int count) {
            return ByteBuffer.allocateDirect(count * Integer.BYTES)
                    .order(ByteOrder.nativeOrder()).asIntBuffer();
        }
    }
}
