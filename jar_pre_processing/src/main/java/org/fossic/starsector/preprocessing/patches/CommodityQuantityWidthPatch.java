package org.fossic.starsector.preprocessing.patches;

import org.fossic.starsector.preprocessing.*;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Keep producer/consumer quantity labels and their following icons aligned. */
public final class CommodityQuantityWidthPatch implements JarPatch {
    static final String TARGET = "com/fs/starfarer/campaign/ui/marketinfo/cdd/A";

    @Override public String id() { return "commodity-quantity-width"; }
    @Override public PatchGroup group() { return PatchGroup.DYNFONT; }
    @Override public String targetJar() { return JarWorkspace.OBF_JAR; }
    @Override public Set<String> targetClasses() { return Set.of(TARGET + ".class"); }

    @Override
    public PatchResult applyAndVerify(ClassNode node, PatchContext context) {
        List<LdcInsnNode> widths = findWidths(node, 24f);
        if (!TARGET.equals(node.name) || widths.size() != 2) {
            throw new PatchException(id() + ": expected both producer/consumer width anchors, found " + widths.size());
        }
        widths.forEach(width -> width.cst = 28f);
        return PatchResult.of(id(), context.classPath(), 2, widths.size(), findWidths(node, 28f).size(),
                "producer/consumer quantity width 24 -> 28; retain fixed icon alignment");
    }

    private static List<LdcInsnNode> findWidths(ClassNode node, float value) {
        List<LdcInsnNode> result = new ArrayList<>();
        for (MethodNode method : node.methods) {
            if (!"createRow".equals(method.name) || !"()V".equals(method.desc)) continue;
            var code = AsmUtil.instructions(method);
            for (int i = 0; i + 4 < code.size(); i++) {
                if (AsmUtil.isFloatLdc(code.get(i), value)
                        && code.get(i + 1) instanceof VarInsnNode store && store.getOpcode() == Opcodes.FSTORE
                        && code.get(i + 2).getOpcode() == Opcodes.ALOAD
                        && code.get(i + 3) instanceof VarInsnNode load && load.getOpcode() == Opcodes.FLOAD
                        && store.var == load.var
                        && code.get(i + 4) instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && "com/fs/starfarer/ui/d".equals(call.owner)
                        && "autoSizeToWidth".equals(call.name)
                        && "(F)Lcom/fs/starfarer/ui/OO0O;".equals(call.desc)) {
                    result.add((LdcInsnNode) code.get(i));
                }
            }
        }
        return result;
    }
}
