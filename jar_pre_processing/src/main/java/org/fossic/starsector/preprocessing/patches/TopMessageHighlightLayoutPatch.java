package org.fossic.starsector.preprocessing.patches;

import org.fossic.starsector.preprocessing.AsmUtil;
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
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.List;
import java.util.Set;

/** Reflows top-of-screen messages before resolving their highlight range. */
public final class TopMessageHighlightLayoutPatch implements JarPatch {
    private static final String TARGET_CLASS = "com/fs/starfarer/campaign/ui/O00O$o.class";
    private static final String TARGET_CLASS_NAME = "com/fs/starfarer/campaign/ui/O00O$o";
    private static final String TARGET_METHOD = "o00000";
    private static final String TARGET_METHOD_DESC = "()V";
    private static final String LABEL = "com/fs/starfarer/ui/d";
    private static final String POSITION = "com/fs/starfarer/ui/OO0O";
    private static final String RENDERER = "com/fs/graphics/A/oo" + "O".repeat(254);
    private static final String HIGHLIGHT_TEXT_FIELD = "Ò00000";
    private static final String HIGHLIGHT_METHOD = "Ø00000";

    @Override
    public String id() {
        return "top-message-highlight-after-layout";
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
        MethodNode method = requireTargetMethod(classNode, context);
        requireOriginalShape(method, context);

        MethodInsnNode setColor = calls(method, LABEL, "setColor", "(Ljava/awt/Color;)V")
                .get(0);
        InsnList layout = new InsnList();
        layout.add(new VarInsnNode(Opcodes.ALOAD, 1));
        layout.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, LABEL, "autoSize", "()L" + POSITION + ";", false));
        layout.add(new InsnNode(Opcodes.POP));
        method.instructions.insert(setColor, layout);
        method.maxStack = Math.max(method.maxStack, 1);

        int verified = calls(method, LABEL, "autoSize", "()L" + POSITION + ";").size();
        return PatchResult.of(id(), context.classPath(), 1, 1, verified,
                "auto-size the final CJK text before resolving its highlight substring");
    }

    private static MethodNode requireTargetMethod(ClassNode classNode, PatchContext context) {
        List<MethodNode> methods = classNode.methods.stream()
                .filter(method -> TARGET_METHOD.equals(method.name)
                        && TARGET_METHOD_DESC.equals(method.desc))
                .toList();
        if (methods.size() != 1) {
            throw failure(context, "expected one message creation method, found " + methods.size());
        }
        return methods.get(0);
    }

    private static void requireOriginalShape(MethodNode method, PatchContext context) {
        requireCallCount(method, LABEL, "createSmallInsigniaLabel",
                "(Ljava/lang/String;Lcom/fs/starfarer/api/ui/Alignment;)L" + LABEL + ";",
                1, context);
        requireCallCount(method, LABEL, "setColor", "(Ljava/awt/Color;)V", 1, context);
        requireCallCount(method, LABEL, "autoSize", "()L" + POSITION + ";", 0, context);
        requireCallCount(method, RENDERER, HIGHLIGHT_METHOD,
                "(Ljava/lang/String;)V", 1, context);

        MethodInsnNode setColor = calls(method, LABEL, "setColor", "(Ljava/awt/Color;)V")
                .get(0);
        MethodInsnNode highlight = calls(method, RENDERER, HIGHLIGHT_METHOD,
                "(Ljava/lang/String;)V").get(0);
        if (indexOf(method, setColor) >= indexOf(method, highlight)) {
            throw failure(context, "highlight call no longer follows label color setup");
        }

        AbstractInsnNode target = previousExecutable(highlight);
        AbstractInsnNode renderer = previousExecutable(target);
        if (!(target instanceof FieldInsnNode field)
                || field.getOpcode() != Opcodes.GETFIELD
                || !TARGET_CLASS_NAME.equals(field.owner)
                || !HIGHLIGHT_TEXT_FIELD.equals(field.name)
                || !"Ljava/lang/String;".equals(field.desc)
                || !(renderer instanceof VarInsnNode load)
                || load.getOpcode() != Opcodes.ALOAD
                || load.var != 0) {
            throw failure(context, "highlight target load has drifted");
        }
    }

    private static void requireCallCount(MethodNode method, String owner, String name,
                                         String desc, int expected, PatchContext context) {
        int actual = calls(method, owner, name, desc).size();
        if (actual != expected) {
            throw failure(context, "expected " + expected + " call(s) to " + owner + "."
                    + name + desc + ", found " + actual);
        }
    }

    private static List<MethodInsnNode> calls(
            MethodNode method, String owner, String name, String desc
    ) {
        return AsmUtil.instructions(method).stream()
                .filter(node -> node instanceof MethodInsnNode call
                        && owner.equals(call.owner)
                        && name.equals(call.name)
                        && desc.equals(call.desc))
                .map(MethodInsnNode.class::cast)
                .toList();
    }

    private static int indexOf(MethodNode method, AbstractInsnNode target) {
        return AsmUtil.instructions(method).indexOf(target);
    }

    private static AbstractInsnNode previousExecutable(AbstractInsnNode node) {
        AbstractInsnNode current = node == null ? null : node.getPrevious();
        while (current != null && current.getOpcode() < 0) current = current.getPrevious();
        return current;
    }

    private static PatchException failure(PatchContext context, String detail) {
        return new PatchException("top-message-highlight-after-layout failed for "
                + context.classPath() + ": " + detail);
    }
}
