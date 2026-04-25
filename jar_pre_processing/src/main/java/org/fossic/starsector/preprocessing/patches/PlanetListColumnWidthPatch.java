package org.fossic.starsector.preprocessing.patches;

import org.fossic.starsector.preprocessing.AsmUtil;
import org.fossic.starsector.preprocessing.JarPatch;
import org.fossic.starsector.preprocessing.JarWorkspace;
import org.fossic.starsector.preprocessing.PatchContext;
import org.fossic.starsector.preprocessing.PatchResult;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;
import java.util.Set;

public final class PlanetListColumnWidthPatch implements JarPatch {
    private static final String TARGET_CLASS = "com/fs/starfarer/campaign/ui/intel/PlanetListV2.class";

    @Override
    public String id() {
        return "planet-list-column-width";
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
        int applied = 0;
        int verified = 0;
        for (MethodNode method : classNode.methods) {
            applied += replaceColumnWidth(method, "SL", 50.0f, 75.0f);
            applied += replaceColumnWidth(method, "Class", 65.0f, 60.0f);
            verified += verifyColumnWidth(method, "SL", 75.0f) ? 1 : 0;
            verified += verifyColumnWidth(method, "Class", 60.0f) ? 1 : 0;
        }
        return PatchResult.of(id(), context.classPath(), 2, applied, verified,
                "Planet list SL/Class column widths");
    }

    private static int replaceColumnWidth(MethodNode method, String label, float from, float to) {
        List<AbstractInsnNode> nodes = AsmUtil.instructions(method);
        int count = 0;
        for (int i = 0; i < nodes.size(); i++) {
            if (!AsmUtil.isStringLdc(nodes.get(i), label)) {
                continue;
            }
            int widthIndex = findFloatBeforeAddColumn(nodes, i, from);
            if (widthIndex >= 0) {
                ((LdcInsnNode) nodes.get(widthIndex)).cst = to;
                count++;
            }
        }
        return count;
    }

    private static boolean verifyColumnWidth(MethodNode method, String label, float expected) {
        List<AbstractInsnNode> nodes = AsmUtil.instructions(method);
        for (int i = 0; i < nodes.size(); i++) {
            if (AsmUtil.isStringLdc(nodes.get(i), label)
                    && findFloatBeforeAddColumn(nodes, i, expected) >= 0) {
                return true;
            }
        }
        return false;
    }

    private static int findFloatBeforeAddColumn(List<AbstractInsnNode> nodes, int start, float value) {
        int widthIndex = -1;
        int limit = Math.min(nodes.size(), start + 18);
        for (int i = start + 1; i < limit; i++) {
            AbstractInsnNode node = nodes.get(i);
            if (AsmUtil.isFloatLdc(node, value)) {
                widthIndex = i;
            }
            if (node instanceof MethodInsnNode call && "addColumn".equals(call.name)) {
                return widthIndex;
            }
        }
        return -1;
    }
}
