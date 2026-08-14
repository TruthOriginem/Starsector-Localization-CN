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

    @Test
    void sharedPhysicalOriginKeepsGlyphSpacingRigidWhileTextMoves() {
        PixelTransform firstFrame = new PixelTransform(1f, 100.4f, 1f, 40.2f);
        PixelTransform secondFrame = new PixelTransform(1f, 100.6f, 1f, 40.2f);

        float firstAnchor = firstFrame.translateX();
        float secondAnchor = secondFrame.translateX();
        float firstLeft = firstFrame.snapXRelativeTo(0f, firstAnchor);
        float firstNext = firstFrame.snapXRelativeTo(11.3f, firstAnchor);
        float secondLeft = secondFrame.snapXRelativeTo(0f, secondAnchor);
        float secondNext = secondFrame.snapXRelativeTo(11.3f, secondAnchor);
        float firstLeftWindow = firstFrame.scaleX() * firstLeft + firstFrame.translateX();
        float firstNextWindow = firstFrame.scaleX() * firstNext + firstFrame.translateX();
        float secondLeftWindow = secondFrame.scaleX() * secondLeft + secondFrame.translateX();
        float secondNextWindow = secondFrame.scaleX() * secondNext + secondFrame.translateX();

        assertEquals(11f, firstNextWindow - firstLeftWindow, 0.0001f);
        assertEquals(firstNextWindow - firstLeftWindow,
                secondNextWindow - secondLeftWindow, 0.0001f);
        assertEquals(1f, secondLeftWindow - firstLeftWindow, 0.0001f);
        assertEquals(1f, secondNextWindow - firstNextWindow, 0.0001f);
    }

    @Test
    void sharedOriginAlsoKeepsLaterDrawPassOffsetRigid() {
        PixelTransform shadowFirst = new PixelTransform(1f, 100.4f, 1f, 40.2f);
        PixelTransform mainFirst = new PixelTransform(1f, 101.65f, 1f, 41.45f);
        PixelTransform shadowSecond = new PixelTransform(1f, 100.6f, 1f, 40.4f);
        PixelTransform mainSecond = new PixelTransform(1f, 101.85f, 1f, 41.65f);

        float first = mainFirst.snapXRelativeTo(0.2f, shadowFirst.translateX());
        float second = mainSecond.snapXRelativeTo(0.2f, shadowSecond.translateX());
        float firstWindow = mainFirst.scaleX() * first + mainFirst.translateX();
        float secondWindow = mainSecond.scaleX() * second + mainSecond.translateX();

        assertEquals(1f, secondWindow - firstWindow, 0.0001f);
    }

    @Test
    void relativeSnappingPreservesAtLeastOnePhysicalPixel() {
        PixelTransform transform = new PixelTransform(1f, 100.4f, 1f, 40.2f);
        float origin = transform.translateX();

        float start = transform.snapXRelativeTo(4.20f, origin);
        float end = transform.snapXEndRelativeTo(4.20f, start, 4.45f, origin);
        float startWindow = transform.scaleX() * start + transform.translateX();
        float endWindow = transform.scaleX() * end + transform.translateX();

        assertEquals(1f, endWindow - startWindow, 0.0001f);
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
