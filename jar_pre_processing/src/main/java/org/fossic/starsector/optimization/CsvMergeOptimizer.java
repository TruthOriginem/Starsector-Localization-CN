package org.fossic.starsector.optimization;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * 保持原 CSV 合并顺序，同时消除逐行头插和逐行线性覆盖查找。
 *
 * <p>通用 CSV 合并先读取 mod 行，最后把 base 行按顺序插到列表头部；
 * {@link BaseFirstRows} 把这段连续增长的前缀单独保存，最终迭代时再与 mod 后缀拼接。
 * 三参数 merged-spreadsheet API 使用按 key 索引的链表：覆盖时删除最早的同键节点再追加，
 * 精确保留原版“覆盖行移动到末尾”以及异常 CSV 中重复 key 的行为。base 行的 key 仍按
 * 原控制流惰性读取，确保缺失 key 时不会比原版更早抛错。
 */
public final class CsvMergeOptimizer {
    private CsvMergeOptimizer() {
    }

    public static <E> List<E> baseFirstRows() {
        return new BaseFirstRows<>();
    }

    public static <E> List<E> overrideRows() {
        return new OverrideRows<>();
    }

    public static <E> void putMovingToEnd(
            List<E> rows,
            String keyColumn,
            String key,
            E row) {
        if (!(rows instanceof OverrideRows<?> rawRows)) {
            throw new IllegalArgumentException(
                    "rows must come from CsvMergeOptimizer.overrideRows()"
            );
        }
        @SuppressWarnings("unchecked")
        OverrideRows<E> overrideRows = (OverrideRows<E>) rawRows;
        overrideRows.putMovingToEnd(keyColumn, key, row);
    }

    private static final class BaseFirstRows<E>
            extends AbstractList<E> {
        private final ArrayList<E> prefix = new ArrayList<>();
        private final ArrayList<E> suffix = new ArrayList<>();
        private ArrayList<E> materialized;
        private boolean growingPrefix;

        @Override
        public E get(int index) {
            checkElementIndex(index);
            if (materialized != null) {
                return materialized.get(index);
            }
            return index < prefix.size()
                    ? prefix.get(index)
                    : suffix.get(index - prefix.size());
        }

        @Override
        public int size() {
            return materialized != null
                    ? materialized.size()
                    : prefix.size() + suffix.size();
        }

        @Override
        public boolean add(E element) {
            add(size(), element);
            return true;
        }

        @Override
        public void add(int index, E element) {
            checkPositionIndex(index);
            if (materialized != null) {
                materialized.add(index, element);
            } else if (index == size()) {
                suffix.add(element);
            } else if (!suffix.isEmpty()
                    && index == prefix.size()
                    && (growingPrefix || index == 0)) {
                growingPrefix = true;
                prefix.add(element);
            } else {
                materialize();
                materialized.add(index, element);
            }
            modCount++;
        }

        @Override
        public E set(int index, E element) {
            checkElementIndex(index);
            if (materialized != null) {
                return materialized.set(index, element);
            }
            return index < prefix.size()
                    ? prefix.set(index, element)
                    : suffix.set(index - prefix.size(), element);
        }

        @Override
        public E remove(int index) {
            checkElementIndex(index);
            E removed;
            if (materialized != null) {
                removed = materialized.remove(index);
            } else if (index < prefix.size()) {
                removed = prefix.remove(index);
            } else {
                removed = suffix.remove(index - prefix.size());
            }
            modCount++;
            return removed;
        }

        private void materialize() {
            materialized = new ArrayList<>(size() + 1);
            materialized.addAll(prefix);
            materialized.addAll(suffix);
            prefix.clear();
            suffix.clear();
        }

        private void checkElementIndex(int index) {
            if (index < 0 || index >= size()) {
                throw new IndexOutOfBoundsException(index);
            }
        }

        private void checkPositionIndex(int index) {
            if (index < 0 || index > size()) {
                throw new IndexOutOfBoundsException(index);
            }
        }
    }

    private static final class OverrideRows<E>
            extends AbstractList<E> {
        private final Map<String, KeyBucket<E>> byKey = new HashMap<>();
        private Node<E> first;
        private Node<E> last;
        private Node<E> firstUnindexed;
        private Node<E> lastUnindexed;
        private int size;
        private boolean keyedRowsStarted;

        @Override
        public E get(int index) {
            if (index < 0 || index >= size) {
                throw new IndexOutOfBoundsException(index);
            }
            Node<E> node = first;
            for (int i = 0; i < index; i++) {
                node = node.next;
            }
            return node.value;
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public Iterator<E> iterator() {
            return new Iterator<>() {
                private Node<E> next = first;

                @Override
                public boolean hasNext() {
                    return next != null;
                }

                @Override
                public E next() {
                    E value = next.value;
                    next = next.next;
                    return value;
                }
            };
        }

        @Override
        public boolean add(E row) {
            if (keyedRowsStarted) {
                throw new IllegalStateException(
                        "Unkeyed base rows must be added before overrides");
            }
            Node<E> node = append(row);
            if (firstUnindexed == null) {
                firstUnindexed = node;
            }
            lastUnindexed = node;
            modCount++;
            return true;
        }

        private void putMovingToEnd(
                String keyColumn, String key, E row) {
            keyedRowsStarted = true;
            Node<E> replaced = knownPrefix(key);
            while (replaced == null && firstUnindexed != null) {
                Node<E> candidate = firstUnindexed;
                if (candidate == lastUnindexed) {
                    firstUnindexed = null;
                    lastUnindexed = null;
                } else {
                    firstUnindexed = candidate.next;
                }
                String candidateKey = readKey(candidate.value, keyColumn);
                KeyBucket<E> bucket = byKey.computeIfAbsent(
                        candidateKey, ignored -> new KeyBucket<>());
                bucket.indexedBase.addLast(candidate);
                if (candidateKey.equals(key)) {
                    replaced = candidate;
                    bucket.indexedBase.removeLast();
                }
            }
            if (replaced == null && firstUnindexed == null) {
                KeyBucket<E> bucket = byKey.get(key);
                if (bucket != null) {
                    replaced = bucket.indexedBase.pollFirst();
                    if (replaced == null) {
                        replaced = bucket.appended.pollFirst();
                    }
                }
            }
            if (replaced != null) {
                unlink(replaced);
            }

            Node<E> appended = append(row);
            byKey.computeIfAbsent(key, ignored -> new KeyBucket<>())
                    .appended.addLast(appended);
            modCount++;
        }

        private Node<E> knownPrefix(String key) {
            KeyBucket<E> bucket = byKey.get(key);
            return bucket == null
                    ? null
                    : bucket.indexedBase.pollFirst();
        }

        private Node<E> append(E value) {
            Node<E> node = new Node<>(value);
            if (last == null) {
                first = node;
            } else {
                last.next = node;
                node.previous = last;
            }
            last = node;
            size++;
            return node;
        }

        private void unlink(Node<E> node) {
            if (node.previous == null) {
                first = node.next;
            } else {
                node.previous.next = node.next;
            }
            if (node.next == null) {
                last = node.previous;
            } else {
                node.next.previous = node.previous;
            }
            size--;
        }

        private static String readKey(Object row, String keyColumn) {
            try {
                Method getString = row.getClass().getMethod(
                        "getString", String.class);
                return (String) getString.invoke(row, keyColumn);
            } catch (InvocationTargetException e) {
                return throwUnchecked(e.getCause());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(
                        "Unable to read merged CSV row key", e);
            }
        }

        @SuppressWarnings("unchecked")
        private static <T extends Throwable, R> R throwUnchecked(
                Throwable error) throws T {
            throw (T) error;
        }
    }

    private static final class KeyBucket<E> {
        private final ArrayDeque<Node<E>> indexedBase =
                new ArrayDeque<>();
        private final ArrayDeque<Node<E>> appended =
                new ArrayDeque<>();
    }

    private static final class Node<E> {
        private final E value;
        private Node<E> previous;
        private Node<E> next;

        private Node(E value) {
            this.value = value;
        }
    }
}
