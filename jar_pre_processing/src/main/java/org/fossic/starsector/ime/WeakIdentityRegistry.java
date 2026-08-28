package org.fossic.starsector.ime;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

/**
 * 单线程使用的弱身份登记表。
 *
 * <p>游戏 UI 对象可能自定义 {@code equals/hashCode}，因此这里只以对象身份（{@code ==}）
 * 判断同一个控件。弱引用在读写时惰性清理，避免登记表延长 mod UI 的生命周期。
 */
final class WeakIdentityRegistry<T> {
    private final ReferenceQueue<T> queue = new ReferenceQueue<>();
    private final Map<IdentityWeakReference<T>, Boolean> references = new HashMap<>();

    WeakIdentityRegistry() {
    }

    void add(T value) {
        if (value == null) {
            return;
        }
        purgeCleared();
        references.putIfAbsent(new IdentityWeakReference<>(value, queue), Boolean.TRUE);
    }

    boolean contains(T value) {
        if (value == null) {
            return false;
        }
        purgeCleared();
        return references.containsKey(new IdentityWeakReference<>(value, null));
    }

    int sizeForTest() {
        purgeCleared();
        return references.size();
    }

    private void purgeCleared() {
        IdentityWeakReference<T> reference;
        while ((reference = poll()) != null) {
            references.remove(reference);
        }
    }

    @SuppressWarnings("unchecked")
    private IdentityWeakReference<T> poll() {
        return (IdentityWeakReference<T>) queue.poll();
    }

    private static final class IdentityWeakReference<T> extends WeakReference<T> {
        private final int identityHash;

        private IdentityWeakReference(T value, ReferenceQueue<T> queue) {
            super(value, queue);
            identityHash = System.identityHashCode(value);
        }

        @Override
        public int hashCode() {
            return identityHash;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof IdentityWeakReference<?> reference)
                    || identityHash != reference.identityHash) {
                return false;
            }
            Object value = get();
            return value != null && value == reference.get();
        }
    }
}
