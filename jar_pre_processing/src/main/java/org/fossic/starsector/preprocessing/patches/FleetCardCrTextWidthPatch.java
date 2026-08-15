package org.fossic.starsector.preprocessing.patches;

import org.fossic.starsector.preprocessing.AsmUtil;
import org.fossic.starsector.preprocessing.JarPatch;
import org.fossic.starsector.preprocessing.JarWorkspace;
import org.fossic.starsector.preprocessing.PatchContext;
import org.fossic.starsector.preprocessing.PatchException;
import org.fossic.starsector.preprocessing.PatchResult;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Widen the fixed CR percentage label in fleet member cards for dynamic victor10. */
public final class FleetCardCrTextWidthPatch implements JarPatch {
    private static final String TARGET_CLASS = "com/fs/starfarer/coreui/O0oo$o.class";
    private static final float ORIGINAL_WIDTH = 26.0f;
    private static final float PATCHED_WIDTH = 40.0f;
    private static final String SET_SIZE_DESC = "(FF)Lcom/fs/starfarer/ui/OO0O;";

    @Override
    public String id() {
        return "fleet-card-cr-text-width";
    }

    @Override
    public String targetJar() {
        return JarWorkspace.OBF_JAR;
    }

    @Override
    public Set<String> targetClasses() {
        return Set.of(TARGET_CLASS);
    }

    @Override
    public PatchResult applyAndVerify(ClassNode classNode, PatchContext context) {
        MethodNode advance = requireAdvanceMethod(classNode, context);
        requireString(advance, "%", context);
        requireString(advance, "/day", context);
        requireString(advance, "replacement chassis:", context);

        List<LdcInsnNode> anchors = findWidthsBeforeSetSize(advance, ORIGINAL_WIDTH);
        if (anchors.size() != 1) {
            throw new PatchException(id() + " failed for " + context.classPath()
                    + ": expected one " + ORIGINAL_WIDTH
                    + " CR label width anchor, found " + anchors.size());
        }
        anchors.get(0).cst = PATCHED_WIDTH;

        int verified = findWidthsBeforeSetSize(advance, PATCHED_WIDTH).size() == 1
                && findWidthsBeforeSetSize(advance, ORIGINAL_WIDTH).isEmpty() ? 1 : 0;
        return PatchResult.of(id(), context.classPath(), 1, 1, verified,
                "fleet card CR label " + ORIGINAL_WIDTH + " -> " + PATCHED_WIDTH);
    }

    private static MethodNode requireAdvanceMethod(ClassNode classNode, PatchContext context) {
        List<MethodNode> matches = classNode.methods.stream()
                .filter(method -> "advanceImpl".equals(method.name) && "(F)V".equals(method.desc))
                .toList();
        if (matches.size() != 1) {
            throw new PatchException("fleet-card-cr-text-width failed for "
                    + context.classPath() + ": expected one advanceImpl(F)V, found "
                    + matches.size());
        }
        return matches.get(0);
    }

    private static void requireString(MethodNode method, String value, PatchContext context) {
        for (AbstractInsnNode node : AsmUtil.instructions(method)) {
            if (AsmUtil.isStringLdc(node, value)) return;
        }
        throw new PatchException("fleet-card-cr-text-width failed for "
                + context.classPath() + ": missing string anchor " + value);
    }

    private static List<LdcInsnNode> findWidthsBeforeSetSize(MethodNode method, float value) {
        List<LdcInsnNode> matches = new ArrayList<>();
        for (AbstractInsnNode node : method.instructions) {
            if (node instanceof LdcInsnNode ldc
                    && ldc.cst instanceof Float actual
                    && Float.compare(actual, value) == 0
                    && hasLabelSetSizeSoon(node)) {
                matches.add(ldc);
            }
        }
        return matches;
    }

    private static boolean hasLabelSetSizeSoon(AbstractInsnNode start) {
        int executable = 0;
        for (AbstractInsnNode node = start.getNext(); node != null && executable < 6;
             node = node.getNext()) {
            if (node.getOpcode() < 0) continue;
            executable++;
            if (node instanceof MethodInsnNode call
                    && "com/fs/starfarer/ui/d".equals(call.owner)
                    && "setSize".equals(call.name)
                    && SET_SIZE_DESC.equals(call.desc)) {
                return true;
            }
        }
        return false;
    }
}
