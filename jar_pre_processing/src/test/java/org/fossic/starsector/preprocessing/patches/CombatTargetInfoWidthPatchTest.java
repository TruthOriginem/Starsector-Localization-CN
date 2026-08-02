package org.fossic.starsector.preprocessing.patches;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
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

final class CombatTargetInfoWidthPatchTest {
    private static final String TARGET =
            "com/fs/starfarer/renderers/A/null.class";

    @Test
    void widensExactlyTheTwoRealTargetValueColumns()
            throws IOException {
        ClassNode targetInfo = load();

        new CombatTargetInfoWidthPatch().applyAndVerify(
                targetInfo,
                new PatchContext(JarWorkspace.OBF_JAR, TARGET))
                .requireSuccess();
        ClassNode roundTripped =
                GameDataPatchVerifier.roundTrip(targetInfo);

        MethodNode layout = roundTripped.methods.stream()
                .filter(CombatTargetInfoWidthPatchTest::hasLayoutAnchors)
                .findFirst()
                .orElseThrow();
        assertEquals(0, countWidthBeforeSetSize(layout, 58.0f));
        assertEquals(2, countWidthBeforeSetSize(layout, 80.0f));
    }

    private static boolean hasLayoutAnchors(MethodNode method) {
        return containsString(method, "RANGE")
                && containsString(method, "SPEED")
                && containsString(method, "----m")
                && containsString(method, "----m/s");
    }

    private static boolean containsString(
            MethodNode method, String value) {
        return AsmUtil.instructions(method).stream()
                .anyMatch(node -> AsmUtil.isStringLdc(node, value));
    }

    private static int countWidthBeforeSetSize(
            MethodNode method, float width) {
        List<AbstractInsnNode> nodes = AsmUtil.instructions(method);
        int count = 0;
        for (int index = 0; index < nodes.size(); index++) {
            if (!AsmUtil.isFloatLdc(nodes.get(index), width)) {
                continue;
            }
            int limit = Math.min(nodes.size(), index + 6);
            for (int next = index + 1; next < limit; next++) {
                if (nodes.get(next) instanceof MethodInsnNode call
                        && "setSize".equals(call.name)
                        && "(FF)Lcom/fs/starfarer/ui/OO0O;"
                                .equals(call.desc)) {
                    count++;
                    break;
                }
            }
        }
        return count;
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
