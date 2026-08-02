package org.fossic.starsector.preprocessing.patches;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.io.IOException;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.fossic.starsector.preprocessing.AsmUtil;
import org.fossic.starsector.preprocessing.JarWorkspace;
import org.fossic.starsector.preprocessing.PatchContext;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.junit.jupiter.api.Test;

final class SoundDecodeWorkerPatchTest {
    private static final String EXECUTORS =
            "java/util/concurrent/Executors";
    private static final String POLICY =
            "org/fossic/starsector/optimization/SoundDecodeWorkerPolicy";
    private static final String EXECUTOR_DESC =
            "(I)Ljava/util/concurrent/ExecutorService;";

    @Test
    void replacesOnlyTheRealResourceLoaderSoundPoolConstant()
            throws IOException {
        ClassNode resourceLoader = load();

        new SoundDecodeWorkerPatch().applyAndVerify(
                resourceLoader,
                new PatchContext(
                        JarWorkspace.OBF_JAR,
                        "com/fs/starfarer/loading/ResourceLoaderState.class"));

        assertEquals(1, AsmUtil.countMethodCall(
                resourceLoader,
                POLICY,
                "workerCount",
                "()I"));
        MethodNode init = resourceLoader.methods.stream()
                .filter(method -> "init".equals(method.name))
                .filter(method -> "(Ljava/util/Map;)V".equals(method.desc))
                .findFirst()
                .orElseThrow();
        MethodInsnNode poolFactory = AsmUtil.instructions(init).stream()
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .filter(call -> EXECUTORS.equals(call.owner))
                .filter(call -> "newFixedThreadPool".equals(call.name))
                .filter(call -> EXECUTOR_DESC.equals(call.desc))
                .findFirst()
                .orElseThrow();
        MethodInsnNode workerCount = assertInstanceOf(
                MethodInsnNode.class,
                previousExecutable(poolFactory));
        assertEquals(POLICY, workerCount.owner);
        assertEquals("workerCount", workerCount.name);
        assertEquals("()I", workerCount.desc);
    }

    private static AbstractInsnNode previousExecutable(
            AbstractInsnNode node) {
        AbstractInsnNode current = node.getPrevious();
        while (current != null && current.getOpcode() < 0) {
            current = current.getPrevious();
        }
        return current;
    }

    private static ClassNode load() throws IOException {
        Path jar = Path.of("..", "game data", "starfarer_obf.jar");
        try (ZipFile input = new ZipFile(jar.toFile())) {
            ClassNode node = new ClassNode();
            new ClassReader(input.getInputStream(input.getEntry(
                    "com/fs/starfarer/loading/ResourceLoaderState.class")))
                    .accept(node, 0);
            return node;
        }
    }
}
