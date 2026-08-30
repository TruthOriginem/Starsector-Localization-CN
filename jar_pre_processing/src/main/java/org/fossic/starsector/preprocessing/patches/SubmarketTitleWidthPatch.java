package org.fossic.starsector.preprocessing.patches;

import org.fossic.starsector.preprocessing.JarPatch;
import org.fossic.starsector.preprocessing.JarWorkspace;
import org.fossic.starsector.preprocessing.PatchContext;
import org.fossic.starsector.preprocessing.PatchException;
import org.fossic.starsector.preprocessing.PatchResult;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.List;
import java.util.Set;

/** Give submarket card titles the full text area already reserved beside the icon. */
public final class SubmarketTitleWidthPatch implements JarPatch {
    static final String TARGET_CLASS = "com/fs/starfarer/campaign/ui/ooOO.class";
    private static final String TARGET_CLASS_NAME =
            "com/fs/starfarer/campaign/ui/ooOO";
    private static final String LABEL_CLASS = "com/fs/starfarer/ui/d";
    private static final String LABEL_DESC = "L" + LABEL_CLASS + ";";
    private static final String POSITION_DESC = "Lcom/fs/starfarer/ui/OO0O;";
    private static final String AUTO_SIZE_DESC = "()" + POSITION_DESC;
    private static final String AUTO_SIZE_TO_WIDTH_DESC = "(F)" + POSITION_DESC;

    // Icon: inTL(4, 4), width=(height-8)*1.6; title: rightOfMid(icon, 10).
    // Keep another 4 px before the card's right border: 4 + 10 + 4 = 18.
    private static final float ICON_VERTICAL_INSET = 8.0f;
    private static final float ICON_ASPECT = 1.6f;
    private static final float HORIZONTAL_INSETS = 18.0f;

    @Override
    public String id() {
        return "submarket-title-width";
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
        if (!TARGET_CLASS_NAME.equals(classNode.name)) {
            throw failure(context, "unexpected class " + classNode.name);
        }

        MethodNode create = requireCreate(classNode, context);
        if (!containsString(create, "graphics/fonts/orbitron12condensed.fnt")) {
            throw failure(context, "submarket title font anchor is missing");
        }

        List<MethodInsnNode> autoSizes = calls(
                create, LABEL_CLASS, "autoSize", AUTO_SIZE_DESC);
        if (autoSizes.size() != 1) {
            throw failure(context, "expected one title autoSize call, found "
                    + autoSizes.size());
        }
        MethodInsnNode autoSize = autoSizes.get(0);
        AbstractInsnNode receiver = previousExecutable(autoSize);
        if (!(receiver instanceof FieldInsnNode field)
                || field.getOpcode() != Opcodes.GETFIELD
                || !TARGET_CLASS_NAME.equals(field.owner)
                || !LABEL_DESC.equals(field.desc)) {
            throw failure(context, "autoSize receiver is not the title label field");
        }

        InsnList width = new InsnList();
        width.add(new VarInsnNode(Opcodes.ALOAD, 0));
        width.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                TARGET_CLASS_NAME,
                "getWidth",
                "()F",
                false));
        width.add(new VarInsnNode(Opcodes.ALOAD, 0));
        width.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                TARGET_CLASS_NAME,
                "getHeight",
                "()F",
                false));
        width.add(new LdcInsnNode(ICON_VERTICAL_INSET));
        width.add(new InsnNode(Opcodes.FSUB));
        width.add(new LdcInsnNode(ICON_ASPECT));
        width.add(new InsnNode(Opcodes.FMUL));
        width.add(new InsnNode(Opcodes.FSUB));
        width.add(new LdcInsnNode(HORIZONTAL_INSETS));
        width.add(new InsnNode(Opcodes.FSUB));
        create.instructions.insertBefore(autoSize, width);
        autoSize.name = "autoSizeToWidth";
        autoSize.desc = AUTO_SIZE_TO_WIDTH_DESC;
        create.maxStack = Math.max(create.maxStack, 4);

        int oldCalls = calls(create, LABEL_CLASS, "autoSize", AUTO_SIZE_DESC).size();
        int newCalls = calls(
                create, LABEL_CLASS, "autoSizeToWidth", AUTO_SIZE_TO_WIDTH_DESC).size();
        int verified = oldCalls == 0 && newCalls == 1 ? 1 : 0;
        return PatchResult.of(id(), context.classPath(), 1, 1, verified,
                "submarket title uses the full card area beside its icon");
    }

    private static MethodNode requireCreate(ClassNode classNode, PatchContext context) {
        List<MethodNode> matches = classNode.methods.stream()
                .filter(method -> "create".equals(method.name)
                        && "()V".equals(method.desc))
                .toList();
        if (matches.size() != 1) {
            throw failure(context, "expected one create()V, found " + matches.size());
        }
        return matches.get(0);
    }

    private static boolean containsString(MethodNode method, String value) {
        for (AbstractInsnNode node : method.instructions) {
            if (node instanceof LdcInsnNode ldc && value.equals(ldc.cst)) {
                return true;
            }
        }
        return false;
    }

    private static List<MethodInsnNode> calls(
            MethodNode method, String owner, String name, String desc) {
        return java.util.stream.StreamSupport.stream(
                        method.instructions.spliterator(), false)
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .filter(call -> owner.equals(call.owner)
                        && name.equals(call.name)
                        && desc.equals(call.desc))
                .toList();
    }

    private static AbstractInsnNode previousExecutable(AbstractInsnNode node) {
        AbstractInsnNode current = node.getPrevious();
        while (current != null && current.getOpcode() < 0) {
            current = current.getPrevious();
        }
        return current;
    }

    private static PatchException failure(PatchContext context, String detail) {
        return new PatchException(idStatic() + " failed for "
                + context.classPath() + ": " + detail);
    }

    private static String idStatic() {
        return "submarket-title-width";
    }
}
