package org.fossic.starsector.dynfont;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DynFontRenderHooksTest {
    @AfterEach
    void reset() {
        DynFontRenderHooks.resetForTests();
    }

    @Test
    void exposesLogicalNominalForProxyAtEveryScale() {
        Object proxy = new Object();
        DynFontRenderHooks.registerProxyForTests(proxy, 1248f, 10f);

        assertEquals(10, DynFontRenderHooks.logicalNominal(proxy, 1248));
        assertEquals(2f, DynFontRenderHooks.logicalNominal(proxy, 1248) * 0.2f);
        assertEquals(0.1f, DynFontRenderHooks.logicalNominal(proxy, 1248) * 0.01f,
                0.000001f);

        Object negativeProxy = new Object();
        DynFontRenderHooks.registerProxyForTests(negativeProxy, -1248f, -10f);
        assertEquals(-10, DynFontRenderHooks.logicalNominal(negativeProxy, -1248));
    }

    @Test
    void leavesUnmanagedFontsUntouched() {
        Object unmanaged = new Object();

        assertFalse(DynFontRenderHooks.isProxyFont(unmanaged));
        assertEquals(1248, DynFontRenderHooks.logicalNominal(unmanaged, 1248));
    }

    @Test
    void recognizesRegisteredProxy() {
        Object proxy = new Object();
        DynFontRenderHooks.registerProxyForTests(proxy, 1248f, 10f);

        assertTrue(DynFontRenderHooks.isProxyFont(proxy));
    }

    @Test
    void failureAfterPublicationKeepsExistingProxyMappingConsistent() {
        Object base = new Object();
        Object proxy = new Object();
        DynFontRenderHooks.registerMappingForTests(base, proxy, 1248f, 10f);

        DynFontRenderHooks.failForTests();

        assertTrue(DynFontRenderHooks.isBrokenForTests());
        assertEquals(proxy, DynFontRenderHooks.resolveFont(base));
        assertTrue(DynFontRenderHooks.isProxyFont(proxy));
        assertEquals(10, DynFontRenderHooks.logicalNominal(proxy, 1248));
        Object lateUnknown = new Object();
        assertEquals(lateUnknown, DynFontRenderHooks.resolveFont(lateUnknown));
    }
}
