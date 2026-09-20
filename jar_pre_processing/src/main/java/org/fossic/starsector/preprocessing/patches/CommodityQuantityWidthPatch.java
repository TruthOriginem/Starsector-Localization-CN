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
    static final String PANEL_TARGET = "com/fs/starfarer/campaign/ui/marketinfo/ooOo";

    @Override public String id() { return "commodity-quantity-width"; }
    @Override public PatchGroup group() { return PatchGroup.DYNFONT; }
    @Override public String targetJar() { return JarWorkspace.OBF_JAR; }
    @Override public Set<String> targetClasses() { return Set.of(TARGET + ".class", PANEL_TARGET + ".class"); }

    @Override
    public PatchResult applyAndVerify(ClassNode node, PatchContext context) {
        if (PANEL_TARGET.equals(node.name)) return patchPanel(node, context);
        List<LdcInsnNode> widths = findWidths(node, 24f);
        if (!TARGET.equals(node.name) || widths.size() != 2) {
            throw new PatchException(id() + ": expected both producer/consumer width anchors, found " + widths.size());
        }
        for (MethodNode method : node.methods) {
            var code = AsmUtil.instructions(method);
            for (LdcInsnNode width : widths) {
                int index = code.indexOf(width);
                if (index >= 0) disableWrapping(method, width, ((VarInsnNode) code.get(index + 2)).var);
            }
        }
        widths.forEach(width -> width.cst = 26f);
        return PatchResult.of(id(), context.classPath(), 2, widths.size(), findWidths(node, 26f).size(),
                "producer/consumer quantity width 24 -> 26; retain fixed icon alignment");
    }

    private PatchResult patchPanel(ClassNode node, PatchContext context) {
        var methods = node.methods.stream().filter(m -> m.name.equals("sizeChanged")
                && m.desc.equals("(FF)V")).toList();
        if (methods.size() != 1) throw new PatchException(id() + ": missing commodity panel layout");
        MethodNode method = methods.get(0);
        var code = AsmUtil.instructions(method);
        List<LdcInsnNode> widths = new ArrayList<>();
        List<Integer> slots = new ArrayList<>();
        List<MethodInsnNode> availableWidths = new ArrayList<>();
        int iconLayouts = 0;
        VarInsnNode labelLoad = null;
        for (int i = 0; i < code.size(); i++) {
            if ((AsmUtil.isFloatLdc(code.get(i), 32f) || AsmUtil.isFloatLdc(code.get(i), 24f))
                    && i + 1 < code.size() && code.get(i + 1) instanceof VarInsnNode store
                    && store.getOpcode() == Opcodes.FSTORE) {
                widths.add((LdcInsnNode) code.get(i));
                slots.add(store.var);
            }
            if (code.get(i) instanceof MethodInsnNode call) {
                if (call.owner.equals("com/fs/starfarer/ui/d") && call.name.equals("setSize")
                        && call.desc.equals("(FF)Lcom/fs/starfarer/ui/OO0O;") && i >= 4
                        && code.get(i - 4) instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD
                        && code.get(i - 3) instanceof VarInsnNode widthLoad && widthLoad.getOpcode() == Opcodes.FLOAD
                        && !slots.isEmpty() && widthLoad.var == slots.get(0)) {
                    if (labelLoad != null) throw new PatchException(id() + ": duplicate quantity label");
                    labelLoad = load;
                }
                if (call.owner.equals(PANEL_TARGET) && call.name.equals("getWidth") && call.desc.equals("()F")) {
                    availableWidths.add(call);
                }
                if (call.owner.equals("com/fs/starfarer/campaign/ui/marketinfo/ooO0")
                        && call.name.equals("autoSizeWithAdjust") && call.desc.equals("(FFFF)V")) iconLayouts++;
            }
        }
        // Both modes assign the same width local. The other 24f is a row-height
        // comparison, not a width; leave that font-selection threshold untouched.
        if (widths.size() != 2 || !widths.get(0).cst.equals(32f) || !widths.get(1).cst.equals(24f)
                || !slots.get(0).equals(slots.get(1)) || availableWidths.size() != 1 || iconLayouts != 1 || labelLoad == null) {
            throw new PatchException(id() + ": unexpected commodity panel width/layout anchors");
        }
        disableWrapping(method, labelLoad, labelLoad.var);
        widths.get(0).cst = 34f;
        widths.get(1).cst = 26f;
        InsnList reserve = new InsnList();
        reserve.add(new LdcInsnNode(2f));
        reserve.add(new InsnNode(Opcodes.FSUB));
        method.instructions.insert(availableWidths.get(0), reserve);
        method.maxStack++;
        return PatchResult.of(id(), context.classPath(), 3, 3, 3,
                "commodity panel widths 32/24 -> 34/26; icon area reduced by 2");
    }

    private static void disableWrapping(MethodNode method, AbstractInsnNode before, int labelSlot) {
        String renderer = "com/fs/graphics/A/oo" + "O".repeat(254);
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, labelSlot));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "com/fs/starfarer/ui/d", "getRenderer",
                "()L" + renderer + ";", false));
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, renderer, "return", "(Z)V", false));
        method.instructions.insertBefore(before, code);
        method.maxStack += 2;
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
