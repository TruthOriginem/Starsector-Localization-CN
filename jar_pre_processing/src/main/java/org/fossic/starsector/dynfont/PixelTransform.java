package org.fossic.starsector.dynfont;

/** 轴对齐对象坐标到 framebuffer window 坐标的可逆变换。 */
record PixelTransform(float scaleX, float translateX,
                      float scaleY, float translateY) {
    private static final float EPSILON = 0.00001f;

    static PixelTransform fromOpenGl(float[] modelView, float[] projection,
                                     int[] viewport) {
        if (modelView == null || modelView.length < 16
                || projection == null || projection.length < 16
                || viewport == null || viewport.length < 4
                || viewport[2] <= 0 || viewport[3] <= 0) {
            return null;
        }
        float[] combined = multiplyColumnMajor(projection, modelView);
        // z=0 的 UI quad 必须保持轴对齐且 w 为常量；旋转、shear、透视均回退。
        if (!nearZero(combined[1]) || !nearZero(combined[4])
                || !nearZero(combined[3]) || !nearZero(combined[7])
                || !Float.isFinite(combined[15]) || Math.abs(combined[15]) < EPSILON) {
            return null;
        }
        float invW = 1f / combined[15];
        float sx = combined[0] * invW * viewport[2] * 0.5f;
        float sy = combined[5] * invW * viewport[3] * 0.5f;
        float tx = (combined[12] * invW + 1f) * viewport[2] * 0.5f + viewport[0];
        float ty = (combined[13] * invW + 1f) * viewport[3] * 0.5f + viewport[1];
        if (!Float.isFinite(sx) || !Float.isFinite(sy)
                || !Float.isFinite(tx) || !Float.isFinite(ty)
                || Math.abs(sx) < EPSILON || Math.abs(sy) < EPSILON) {
            return null;
        }
        return new PixelTransform(sx, tx, sy, ty);
    }

    float snapX(float x) {
        return (Math.round(scaleX * x + translateX) - translateX) / scaleX;
    }

    float snapY(float y) {
        return (Math.round(scaleY * y + translateY) - translateY) / scaleY;
    }

    PixelTransform translated(float x, float y) {
        return new PixelTransform(scaleX, translateX + scaleX * x,
                scaleY, translateY + scaleY * y);
    }

    float snapXEnd(float start, float snappedStart, float end) {
        return snapEnd(scaleX, translateX, start, snappedStart, end);
    }

    float snapYEnd(float start, float snappedStart, float end) {
        return snapEnd(scaleY, translateY, start, snappedStart, end);
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

    private static boolean nearZero(float value) {
        return Float.isFinite(value) && Math.abs(value) < EPSILON;
    }

    private static float[] multiplyColumnMajor(float[] left, float[] right) {
        float[] out = new float[16];
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                float value = 0f;
                for (int k = 0; k < 4; k++) {
                    value += left[k * 4 + row] * right[column * 4 + k];
                }
                out[column * 4 + row] = value;
            }
        }
        return out;
    }
}
