package org.fossic.starsector.dynfont;

/** 轴对齐对象坐标到 framebuffer window 坐标的可逆变换。 */
final class PixelTransform {
    private static final float EPSILON = 0.00001f;

    private float scaleX;
    private float translateX;
    private float scaleY;
    private float translateY;

    PixelTransform(float scaleX, float translateX,
                   float scaleY, float translateY) {
        set(scaleX, translateX, scaleY, translateY);
    }

    float scaleX() {
        return scaleX;
    }

    float translateX() {
        return translateX;
    }

    float scaleY() {
        return scaleY;
    }

    float translateY() {
        return translateY;
    }

    static PixelTransform fromOpenGl(float[] modelView, float[] projection,
                                     int[] viewport) {
        PixelTransform result = new PixelTransform(1f, 0f, 1f, 0f);
        float[] combined = new float[16];
        return result.setFromOpenGl(modelView, projection, viewport, combined)
                ? result : null;
    }

    /**
     * 热路径复用调用者提供的对象和矩阵暂存区，避免每段文本分配矩阵及变换对象。
     */
    boolean setFromOpenGl(float[] modelView, float[] projection,
                          int[] viewport, float[] combined) {
        if (modelView == null || modelView.length < 16
                || projection == null || projection.length < 16
                || viewport == null || viewport.length < 4
                || combined == null || combined.length < 16
                || viewport[2] <= 0 || viewport[3] <= 0) {
            return false;
        }
        multiplyColumnMajor(projection, modelView, combined);
        // z=0 的 UI quad 必须保持轴对齐且 w 为常量；旋转、shear、透视均回退。
        if (!nearZero(combined[1]) || !nearZero(combined[4])
                || !nearZero(combined[3]) || !nearZero(combined[7])
                || !Float.isFinite(combined[15]) || Math.abs(combined[15]) < EPSILON) {
            return false;
        }
        float invW = 1f / combined[15];
        float sx = combined[0] * invW * viewport[2] * 0.5f;
        float sy = combined[5] * invW * viewport[3] * 0.5f;
        float tx = (combined[12] * invW + 1f) * viewport[2] * 0.5f + viewport[0];
        float ty = (combined[13] * invW + 1f) * viewport[3] * 0.5f + viewport[1];
        if (!Float.isFinite(sx) || !Float.isFinite(sy)
                || !Float.isFinite(tx) || !Float.isFinite(ty)
                || Math.abs(sx) < EPSILON || Math.abs(sy) < EPSILON) {
            return false;
        }
        set(sx, tx, sy, ty);
        return true;
    }

    float snapX(float x) {
        return (Math.round(scaleX * x + translateX) - translateX) / scaleX;
    }

    float snapY(float y) {
        return (Math.round(scaleY * y + translateY) - translateY) / scaleY;
    }

    /**
     * 以整个文字 render 共享的物理原点吸附坐标。移动只改变原点的整数位置，
     * 字形相对原点的量化结果不随平移相位变化。
     */
    float snapXRelativeTo(float x, float originWindowX) {
        return snapRelative(scaleX, translateX, x, originWindowX);
    }

    float snapYRelativeTo(float y, float originWindowY) {
        return snapRelative(scaleY, translateY, y, originWindowY);
    }

    PixelTransform translated(float x, float y) {
        return new PixelTransform(scaleX, translateX + scaleX * x,
                scaleY, translateY + scaleY * y);
    }

    void setTranslatedFrom(PixelTransform base, float x, float y) {
        set(base.scaleX, base.translateX + base.scaleX * x,
                base.scaleY, base.translateY + base.scaleY * y);
    }

    float snapXEnd(float start, float snappedStart, float end) {
        return snapEnd(scaleX, translateX, start, snappedStart, end);
    }

    float snapYEnd(float start, float snappedStart, float end) {
        return snapEnd(scaleY, translateY, start, snappedStart, end);
    }

    float snapXEndRelativeTo(float start, float snappedStart, float end,
                             float originWindowX) {
        return snapEndRelative(scaleX, translateX, start, snappedStart, end,
                originWindowX);
    }

    float snapYEndRelativeTo(float start, float snappedStart, float end,
                             float originWindowY) {
        return snapEndRelative(scaleY, translateY, start, snappedStart, end,
                originWindowY);
    }

    /** 只吸附起点时让终点跟随相同平移量，严格保留原始跨度。 */
    float preserveEnd(float start, float snappedStart, float end) {
        return snappedStart + (end - start);
    }

    private static float snapRelative(float scale, float translate, float value,
                                      float originWindow) {
        float window = scale * value + translate;
        float snappedWindow = Math.round(originWindow)
                + Math.round(window - originWindow);
        return (snappedWindow - translate) / scale;
    }

    private static float snapEnd(float scale, float translate,
                                 float start, float snappedStart, float end) {
        float startWindow = scale * start + translate;
        float endWindow = scale * end + translate;
        float snappedStartWindow = scale * snappedStart + translate;
        float snappedEndWindow = Math.round(endWindow);
        float span = endWindow - startWindow;
        if (Math.abs(span) >= EPSILON
                && Math.abs(snappedEndWindow - snappedStartWindow) < EPSILON) {
            snappedEndWindow = snappedStartWindow + Math.copySign(1f, span);
        }
        return (snappedEndWindow - translate) / scale;
    }

    private static float snapEndRelative(float scale, float translate,
                                         float start, float snappedStart, float end,
                                         float originWindow) {
        float startWindow = scale * start + translate;
        float endWindow = scale * end + translate;
        float snappedStartWindow = scale * snappedStart + translate;
        float snappedEnd = snapRelative(scale, translate, end, originWindow);
        float snappedEndWindow = scale * snappedEnd + translate;
        float span = endWindow - startWindow;
        if (Math.abs(span) >= EPSILON
                && Math.abs(snappedEndWindow - snappedStartWindow) < EPSILON) {
            snappedEndWindow = snappedStartWindow + Math.copySign(1f, span);
        }
        return (snappedEndWindow - translate) / scale;
    }

    private static boolean nearZero(float value) {
        return Float.isFinite(value) && Math.abs(value) < EPSILON;
    }

    private void set(float newScaleX, float newTranslateX,
                     float newScaleY, float newTranslateY) {
        scaleX = newScaleX;
        translateX = newTranslateX;
        scaleY = newScaleY;
        translateY = newTranslateY;
    }

    private static void multiplyColumnMajor(float[] left, float[] right, float[] out) {
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                float value = 0f;
                for (int k = 0; k < 4; k++) {
                    value += left[k * 4 + row] * right[column * 4 + k];
                }
                out[column * 4 + row] = value;
            }
        }
    }
}
