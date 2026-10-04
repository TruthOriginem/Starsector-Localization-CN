package org.fossic.starsector.preprocessing.patches;

import org.fossic.starsector.preprocessing.*;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.*;

/** Changes only searches, match ends, and CJK boundary acceptance in three renderer methods. */
public final class RendererCjkHighlightPatch implements JarPatch {
    private static final String OWNER = RendererDynFontPatch.RENDERER_CLASS;
    private static final String HOOK = "org/fossic/starsector/dynfont/CjkHighlightMatcher";
    private static final String TEXT = "while.super";

    @Override
    public String id() {
        return "renderer-cjk-highlight";
    }

    @Override
    public PatchGroup group() {
        return PatchGroup.DYNFONT;
    }

    @Override
    public String targetJar() {
        return JarWorkspace.COMMON_OBF_JAR;
    }

    @Override
    public Set<String> targetClasses() {
        return Set.of(OWNER + ".class");
    }

    @Override
    public PatchResult applyAndVerify(ClassNode n, PatchContext context) {
        require(n.name.equals(OWNER), "wrong class");
        for (MethodNode m : n.methods)
            for (AbstractInsnNode i : m.instructions)
                require(
                        !(i instanceof MethodInsnNode c && c.owner.equals(HOOK)),
                        "already patched");
        MethodNode bulk = method(n, "o00000", "(Z[Ljava/lang/String;)V");
        MethodNode first = method(n, "Ø00000", "(Ljava/lang/String;)V");
        MethodNode last = method(n, "Ô00000", "(Ljava/lang/String;)V");
        patchBulk(bulk);
        patchSingle(first, false);
        patchSingle(last, true);
        int calls = 0;
        for (MethodNode m : n.methods)
            for (AbstractInsnNode i : m.instructions)
                if (i instanceof MethodInsnNode c && c.owner.equals(HOOK)) calls++;
        require(calls == 5, "expected five runtime calls");
        return PatchResult.of(
                id(),
                context.classPath(),
                8,
                8,
                8,
                "three searches, three actual ends, two CJK boundary allowances; original rendering"
                    + " retained");
    }

    private static void patchBulk(MethodNode m) {
        MethodInsnNode search =
                uniqueCall(m, "java/lang/String", "indexOf", "(Ljava/lang/String;I)I");
        int from = var(prev(search), Opcodes.ILOAD);
        int target = var(prev(prev(search)), Opcodes.ALOAD);
        var(prev(prev(prev(search))), Opcodes.ALOAD);
        int start = var(next(search), Opcodes.ISTORE);
        AbstractInsnNode endLoad = findEnd(m, start, target);
        AbstractInsnNode add = next(next(next(endLoad)));
        int end = var(next(add), Opcodes.ISTORE);
        List<MethodInsnNode> whitespace = calls(m, "java/lang/Character", "isWhitespace", "(C)Z");
        require(whitespace.size() == 2, "expected left and right whitespace checks");
        for (MethodInsnNode c : whitespace) {
            var(prev(c), Opcodes.ILOAD);
            require(
                    next(c) instanceof JumpInsnNode j && j.getOpcode() == Opcodes.IFNE,
                    "whitespace acceptance branch changed");
        }
        require(
                calls(m, "java/lang/Character", "isDigit", "(C)Z").size() == 1,
                "numeric suffix guard changed");
        require(from != start && start != end && target != from, "unexpected local alias");
        int packed = allocateRange(m);
        InsnList code = display();
        code.add(call("findBulk", "(Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;)J"));
        unpackStart(code, packed);
        m.instructions.insertBefore(search, code);
        m.instructions.remove(search);
        replaceEnd(m, endLoad, add, packed);
        for (int index = 0; index < whitespace.size(); index++) {
            MethodInsnNode c = whitespace.get(index);
            InsnList allow = display();
            allow.add(new VarInsnNode(Opcodes.ILOAD, index == 0 ? start : end));
            allow.add(call("cjkBoundary", "(Ljava/lang/String;I)Z"));
            allow.add(new JumpInsnNode(Opcodes.IFNE, ((JumpInsnNode) next(c)).label));
            m.instructions.insertBefore(prev(c), allow);
        }
    }

    private static void patchSingle(MethodNode m, boolean last) {
        List<MethodInsnNode> exact =
                calls(
                        m,
                        "java/lang/String",
                        last ? "lastIndexOf" : "indexOf",
                        "(Ljava/lang/String;)I");
        require(exact.size() == (last ? 1 : 2), "single search shape changed");
        MethodInsnNode search = exact.get(0);
        require(var(prev(search), Opcodes.ALOAD) == 1, "target parameter changed");
        require(
                prev(prev(search)) instanceof FieldInsnNode f
                        && f.owner.equals(OWNER)
                        && f.name.equals(TEXT),
                "search is not on final text");
        int start = var(next(search), Opcodes.ISTORE);
        AbstractInsnNode endLoad = findEnd(m, start, 1);
        AbstractInsnNode add = next(next(next(endLoad)));
        require(
                next(add).getOpcode() == Opcodes.ICONST_1
                        && next(next(add)).getOpcode() == Opcodes.ISUB,
                "single range end must be inclusive");
        int packed = allocateRange(m);
        InsnList select = display();
        select.add(new VarInsnNode(Opcodes.ALOAD, 1));
        select.add(new InsnNode(last ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
        select.add(call("selectSingle", "(ILjava/lang/String;Ljava/lang/String;Z)J"));
        unpackStart(select, packed);
        m.instructions.insert(search, select);
        replaceEnd(m, endLoad, add, packed);
    }

    private static AbstractInsnNode findEnd(MethodNode m, int start, int target) {
        List<AbstractInsnNode> found = new ArrayList<>();
        for (AbstractInsnNode i : m.instructions) {
            if (!(i instanceof VarInsnNode v && v.getOpcode() == Opcodes.ILOAD && v.var == start))
                continue;
            AbstractInsnNode a = next(i),
                    b = a == null ? null : next(a),
                    c = b == null ? null : next(b);
            if (a instanceof VarInsnNode t
                    && t.getOpcode() == Opcodes.ALOAD
                    && t.var == target
                    && b instanceof MethodInsnNode len
                    && len.owner.equals("java/lang/String")
                    && len.name.equals("length")
                    && len.desc.equals("()I")
                    && c != null
                    && c.getOpcode() == Opcodes.IADD) found.add(i);
        }
        require(found.size() == 1, "expected exactly one target-length end calculation");
        return found.get(0);
    }

    private static void replaceEnd(
            MethodNode m, AbstractInsnNode first, AbstractInsnNode last, int packed) {
        InsnList load = new InsnList();
        load.add(new VarInsnNode(Opcodes.LLOAD, packed));
        load.add(new InsnNode(Opcodes.L2I));
        m.instructions.insertBefore(first, load);
        for (AbstractInsnNode i = first; ; ) {
            AbstractInsnNode next = i.getNext();
            m.instructions.remove(i);
            if (i == last) break;
            i = next;
        }
    }

    private static int allocateRange(MethodNode m) {
        int slot = m.maxLocals;
        m.maxLocals += 2;
        return slot;
    }

    private static void unpackStart(InsnList code, int slot) {
        code.add(new InsnNode(Opcodes.DUP2));
        code.add(new VarInsnNode(Opcodes.LSTORE, slot));
        code.add(new IntInsnNode(Opcodes.BIPUSH, 32));
        code.add(new InsnNode(Opcodes.LSHR));
        code.add(new InsnNode(Opcodes.L2I));
    }

    private static InsnList display() {
        InsnList c = new InsnList();
        c.add(new VarInsnNode(Opcodes.ALOAD, 0));
        c.add(new FieldInsnNode(Opcodes.GETFIELD, OWNER, TEXT, "Ljava/lang/String;"));
        return c;
    }

    private static MethodInsnNode call(String name, String desc) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, name, desc, false);
    }

    private static AbstractInsnNode next(AbstractInsnNode n) {
        return AsmUtil.nextReal(n);
    }

    private static AbstractInsnNode prev(AbstractInsnNode n) {
        return AsmUtil.previousReal(n);
    }

    private static int var(AbstractInsnNode n, int op) {
        require(n instanceof VarInsnNode && n.getOpcode() == op, "unexpected local load/store");
        return ((VarInsnNode) n).var;
    }

    private static MethodNode method(ClassNode n, String name, String desc) {
        List<MethodNode> found =
                n.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).toList();
        require(found.size() == 1, "missing/duplicate method " + name + desc);
        return found.get(0);
    }

    private static List<MethodInsnNode> calls(
            MethodNode m, String owner, String name, String desc) {
        List<MethodInsnNode> found = new ArrayList<>();
        for (AbstractInsnNode i : m.instructions)
            if (i instanceof MethodInsnNode c
                    && c.owner.equals(owner)
                    && c.name.equals(name)
                    && c.desc.equals(desc)) found.add(c);
        return found;
    }

    private static MethodInsnNode uniqueCall(MethodNode m, String owner, String name, String desc) {
        List<MethodInsnNode> found = calls(m, owner, name, desc);
        require(found.size() == 1, "missing/duplicate call " + name);
        return found.get(0);
    }

    private static void require(boolean condition, String detail) {
        if (!condition) throw new PatchException("renderer-cjk-highlight: " + detail);
    }
}
