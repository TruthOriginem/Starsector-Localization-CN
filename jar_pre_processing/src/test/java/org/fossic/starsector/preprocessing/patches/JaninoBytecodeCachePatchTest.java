package org.fossic.starsector.preprocessing.patches;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.fossic.starsector.preprocessing.AsmUtil;
import org.fossic.starsector.preprocessing.JarWorkspace;
import org.fossic.starsector.preprocessing.PatchContext;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.junit.jupiter.api.Test;

final class JaninoBytecodeCachePatchTest {
    private static final String TARGET =
            "com/fs/starfarer/loading/scripts/ScriptStore.class";
    private static final String INDEXED =
            "org/fossic/starsector/optimization/"
                    + "IndexedDeduplicatingJavaSourceClassLoader";
    private static final String CACHING =
            "org/fossic/starsector/optimization/"
                    + "CachingIndexedDeduplicatingJavaSourceClassLoader";
    private static final String CONSTRUCTOR =
            "(Ljava/lang/ClassLoader;"
                    + "Lorg/codehaus/janino/util/resource/ResourceFinder;"
                    + "Ljava/lang/String;)V";

    @Test
    void replacesIndexedLoaderAndPublishesAfterTheUniqueWorkerJoin()
            throws IOException {
        ClassNode scriptStore = load();
        PatchContext context = new PatchContext(
                JarWorkspace.OBF_JAR, TARGET);
        new JaninoCompilationUnitDedupPatch().applyAndVerify(
                scriptStore, context);
        new JaninoSourceIndexPatch().applyAndVerify(scriptStore, context);

        new JaninoBytecodeCachePatch().applyAndVerify(scriptStore, context);

        assertEquals(0, JaninoCompilationUnitDedupPatch.countAllocations(
                scriptStore, INDEXED));
        assertEquals(0, AsmUtil.countMethodCall(
                scriptStore, INDEXED, "<init>", CONSTRUCTOR));
        assertEquals(1, JaninoCompilationUnitDedupPatch.countAllocations(
                scriptStore, CACHING));
        assertEquals(1, AsmUtil.countMethodCall(
                scriptStore, CACHING, "<init>", CONSTRUCTOR));
        assertEquals(1, AsmUtil.countMethodCall(
                scriptStore,
                "org/fossic/starsector/optimization/"
                        + "JaninoBytecodeCacheHooks",
                "finish",
                "(Lorg/codehaus/janino/JavaSourceClassLoader;"
                        + "Ljava/lang/Throwable;)V"));
    }

    private static ClassNode load() throws IOException {
        Path jar = Path.of("..", "game data", "starfarer_obf.jar");
        try (ZipFile input = new ZipFile(jar.toFile())) {
            ClassNode node = new ClassNode();
            new ClassReader(input.getInputStream(input.getEntry(TARGET)))
                    .accept(node, 0);
            return node;
        }
    }
}
