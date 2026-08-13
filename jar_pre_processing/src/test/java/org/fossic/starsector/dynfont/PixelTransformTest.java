package org.fossic.starsector.dynfont;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class PixelTransformTest {
    @Test
    void snapsThroughAxisAlignedPhysicalScaleAndTranslation() {
        float[] model = identity();
        model[0] = 1.95f;
        model[5] = 1.95f;
        model[12] = 0.37f;
        model[13] = -0.21f;
        float[] projection = orthographic(0f, 100f, 0f, 50f);

        PixelTransform transform = PixelTransform.fromOpenGl(
                model, projection, new int[]{0, 0, 200, 100});

        float x = transform.snapX(7.25f);
        float y = transform.snapY(4.75f);
        assertEquals(Math.round(transform.scaleX() * 7.25f + transform.translateX()),
                transform.scaleX() * x + transform.translateX(), 0.0001f);
        assertEquals(Math.round(transform.scaleY() * 4.75f + transform.translateY()),
                transform.scaleY() * y + transform.translateY(), 0.0001f);
    }

    @Test
    void keepsIntegerScaleCoordinatesStable() {
        PixelTransform transform = PixelTransform.fromOpenGl(
                identity(), orthographic(0f, 100f, 0f, 50f),
                new int[]{0, 0, 200, 100});

        assertEquals(12f, transform.snapX(12f), 0.0001f);
        assertEquals(9f, transform.snapY(9f), 0.0001f);
    }

    @Test
    void rejectsRotationShearPerspectiveAndSingularScale() {
        float[] projection = orthographic(0f, 100f, 0f, 50f);
        float[] rotated = identity();
        rotated[1] = 0.1f;
        assertNull(PixelTransform.fromOpenGl(rotated, projection,
                new int[]{0, 0, 200, 100}));

        float[] perspective = identity();
        perspective[3] = 0.01f;
        assertNull(PixelTransform.fromOpenGl(identity(), perspective,
                new int[]{0, 0, 200, 100}));

        float[] singular = identity();
        singular[0] = 0f;
        assertNull(PixelTransform.fromOpenGl(singular, projection,
                new int[]{0, 0, 200, 100}));
    }

    @Test
    void preservesAtLeastOnePhysicalPixelForNonEmptyRange() {
        PixelTransform transform = new PixelTransform(1f, 0f, 1f, 0f);

        float start = transform.snapX(4.20f);
        float end = transform.snapXEnd(4.20f, start, 4.45f);

        assertEquals(1f, end - start, 0.0001f);
    }

    @Test
    void composesOriginalDrawPassTranslationBeforeSnapping() {
        PixelTransform base = new PixelTransform(1.95f, 0.25f, 1.95f, -0.4f);
        PixelTransform translated = base.translated(10.2f, 3.4f);

        float x = translated.snapX(2.75f);
        float y = translated.snapY(1.25f);

        assertEquals(Math.round(1.95f * (2.75f + 10.2f) + 0.25f),
                1.95f * (x + 10.2f) + 0.25f, 0.0001f);
        assertEquals(Math.round(1.95f * (1.25f + 3.4f) - 0.4f),
                1.95f * (y + 3.4f) - 0.4f, 0.0001f);
    }

    private static float[] identity() {
        return new float[]{
                1, 0, 0, 0,
                0, 1, 0, 0,
                0, 0, 1, 0,
                0, 0, 0, 1
        };
    }

    private static float[] orthographic(float left, float right,
                                        float bottom, float top) {
        float near = -1f;
        float far = 1f;
        return new float[]{
                2f / (right - left), 0, 0, 0,
                0, 2f / (top - bottom), 0, 0,
                0, 0, -2f / (far - near), 0,
                -(right + left) / (right - left),
                -(top + bottom) / (top - bottom),
                -(far + near) / (far - near), 1
        };
    }
}
