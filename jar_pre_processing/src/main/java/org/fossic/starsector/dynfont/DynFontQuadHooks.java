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
            PixelTransform transform = PixelTransform.fromOpenGl(
                    copy(state.model, 16), copy(state.projection, 16),
                    copy(state.viewport, 4));
            if (transform != null) {
                state.baseTransform = transform;
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
            state.transform = state.baseTransform.translated(x, y);
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
                state.left = state.transform.snapX(x);
                state.top = state.transform.snapY(y);
                x = state.left;
                y = state.top;
            } else if (vertex == 1) {
                state.bottom = state.transform.snapYEnd(
                        state.rawTop, state.top, y);
                x = state.left;
                y = state.bottom;
            } else if (vertex == 2) {
                state.right = state.transform.snapXEnd(
                        state.rawLeft, state.left, x);
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

    private static float[] copy(FloatBuffer buffer, int count) {
        float[] out = new float[count];
        for (int i = 0; i < count; i++) out[i] = buffer.get(i);
        return out;
    }

    private static int[] copy(IntBuffer buffer, int count) {
        int[] out = new int[count];
        for (int i = 0; i < count; i++) out[i] = buffer.get(i);
        return out;
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
        final IntBuffer viewport = directInts(16);
        boolean active;
        PixelTransform baseTransform;
        PixelTransform transform;
        int vertexIndex;
        float rawLeft;
        float rawTop;
        float left;
        float top;
        float right;
        float bottom;

        void reset() {
            active = false;
            baseTransform = null;
            transform = null;
            vertexIndex = 0;
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
