package org.fossic.starsector.preprocessing.patches;

import org.fossic.starsector.preprocessing.PatchContext;
import org.fossic.starsector.preprocessing.PatchException;
import org.fossic.starsector.preprocessing.PatchResult;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.file.Path;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class FleetCardCrTextWidthPatchTest {
    private static final String TARGET_CLASS = "com/fs/starfarer/coreui/O0oo$o.class";

    @Test
    void widensReal098Rc8FleetCardCrTextWithoutChangingOtherLabels() throws Exception {
        ClassNode classNode = readRealClass();

        PatchResult result = apply(classNode);
        result.requireSuccess();

        MethodNode advance = uniqueMethod(classNode, "advanceImpl", "(F)V");
        assertEquals(0, countWidthBeforeSetSize(advance, 26.0f));
        assertEquals(1, countWidthBeforeSetSize(advance, 40.0f));
    }

    @Test
    void rejectsRealClassWhenFixedWidthAnchorDrifts() throws Exception {
        ClassNode classNode = readRealClass();
        MethodNode advance = uniqueMethod(classNode, "advanceImpl", "(F)V");
        LdcInsnNode width = findWidthBeforeSetSize(advance, 26.0f);
        width.cst = 27.0f;

        assertThrows(PatchException.class, () -> apply(classNode).requireSuccess());
    }

    private static PatchResult apply(ClassNode classNode) {
        return new FleetCardCrTextWidthPatch().applyAndVerify(
                classNode,
                new PatchContext("starfarer_obf.jar", TARGET_CLASS)
        );
    }

    private static ClassNode readRealClass() throws Exception {
        Path jar = Path.of("..", "game data", "starfarer_obf.jar").toAbsolutePath();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entry = zip.getEntry(TARGET_CLASS);
            if (entry == null) throw new IllegalStateException("missing " + TARGET_CLASS);
            ClassNode node = new ClassNode();
            new ClassReader(zip.getInputStream(entry)).accept(node, 0);
            return node;
        }
    }

    private static MethodNode uniqueMethod(ClassNode classNode, String name, String desc) {
        var matches = classNode.methods.stream()
                .filter(method -> name.equals(method.name) && desc.equals(method.desc))
                .toList();
        if (matches.size() != 1) {
            throw new IllegalStateException("expected one " + name + desc + ", found " + matches.size());
        }
        return matches.get(0);
    }

    private static int countWidthBeforeSetSize(MethodNode method, float value) {
        int count = 0;
        for (AbstractInsnNode node : method.instructions) {
            if (node instanceof LdcInsnNode ldc
                    && ldc.cst instanceof Float actual
                    && Float.compare(actual, value) == 0
                    && nextSetSize(node) != null) {
                count++;
            }
        }
        return count;
    }

    private static LdcInsnNode findWidthBeforeSetSize(MethodNode method, float value) {
        for (AbstractInsnNode node : method.instructions) {
            if (node instanceof LdcInsnNode ldc
                    && ldc.cst instanceof Float actual
                    && Float.compare(actual, value) == 0
                    && nextSetSize(node) != null) {
                return ldc;
            }
        }
        throw new IllegalStateException("missing width " + value);
    }

    private static MethodInsnNode nextSetSize(AbstractInsnNode start) {
        int remaining = 6;
        for (AbstractInsnNode node = start.getNext(); node != null && remaining-- > 0;
             node = node.getNext()) {
            if (node instanceof MethodInsnNode call
                    && "com/fs/starfarer/ui/d".equals(call.owner)
                    && "setSize".equals(call.name)
                    && "(FF)Lcom/fs/starfarer/ui/OO0O;".equals(call.desc)) {
                return call;
            }
        }
        return null;
    }
}
