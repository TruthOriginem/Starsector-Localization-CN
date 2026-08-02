package org.fossic.starsector.optimization;

import org.codehaus.janino.util.resource.ResourceFinder;

/** 在 CU 去重 loader 上增加一个 loader 生命周期内的 Janino 逻辑源码索引。 */
public final class IndexedDeduplicatingJavaSourceClassLoader
        extends DeduplicatingJavaSourceClassLoader {
    private final JaninoSourceIndex sourceIndex;

    public IndexedDeduplicatingJavaSourceClassLoader(
            ClassLoader parentClassLoader,
            ResourceFinder sourceFinder,
            String characterEncoding) {
        this(parentClassLoader, new JaninoSourceIndex(sourceFinder),
                characterEncoding);
    }

    private IndexedDeduplicatingJavaSourceClassLoader(
            ClassLoader parentClassLoader,
            JaninoSourceIndex sourceIndex,
            String characterEncoding) {
        super(parentClassLoader, sourceIndex, characterEncoding);
        this.sourceIndex = sourceIndex;
    }

    JaninoSourceIndex sourceIndexForTests() {
        return sourceIndex;
    }
}
