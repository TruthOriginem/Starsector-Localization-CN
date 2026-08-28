package org.fossic.starsector.ime;

import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WeakIdentityRegistryTest {
    @Test
    void usesObjectIdentityInsteadOfEqualsAndDoesNotDuplicateEntries() {
        WeakIdentityRegistry<EqualObject> registry = new WeakIdentityRegistry<>();
        EqualObject first = new EqualObject();
        EqualObject equalButDistinct = new EqualObject();

        registry.add(first);
        registry.add(first);

        assertTrue(registry.contains(first));
        assertFalse(registry.contains(equalButDistinct));
        assertEquals(1, registry.sizeForTest());
    }

    @Test
    void lazilyRemovesClearedReferences() throws ReflectiveOperationException {
        WeakIdentityRegistry<Object> registry = new WeakIdentityRegistry<>();
        Object value = new Object();
        registry.add(value);

        Field referencesField = WeakIdentityRegistry.class.getDeclaredField("references");
        referencesField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<WeakReference<Object>, Boolean> references =
                (Map<WeakReference<Object>, Boolean>) referencesField.get(registry);
        WeakReference<Object> storedReference = references.keySet().iterator().next();
        storedReference.clear();
        storedReference.enqueue();

        assertFalse(registry.contains(new Object()));
        assertEquals(0, registry.sizeForTest());
    }

    private static final class EqualObject {
        @Override
        public boolean equals(Object other) {
            return other instanceof EqualObject;
        }

        @Override
        public int hashCode() {
            return 1;
        }
    }
}
