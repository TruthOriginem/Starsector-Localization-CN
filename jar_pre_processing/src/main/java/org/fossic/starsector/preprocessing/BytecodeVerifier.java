package org.fossic.starsector.preprocessing;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipFile;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

/** Read-only checks of serialized output. No class loading, rewriting or frame computation.
 * BasicVerifier checks primitive/reference categories, not reference-class assignability.
 * Frame checks cover structure and required targets, not the JVM's complete type verifier.
 */
public final class BytecodeVerifier {
    private BytecodeVerifier() {}

    public static int verifyJar(String jarName, Path jar, List<JarPatch> patches) throws IOException {
        int count = 0;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Set<String> entries = new HashSet<>();
            for (var entry : zip.stream().toList()) {
                if (!entries.add(entry.getName())) throw new PatchException("Duplicate entry: " + jarName + ":" + entry.getName());
                if (!entry.getName().endsWith(".class")) continue;
                List<String> related = patches.stream()
                        .filter(p -> jarName.equals(p.targetJar()) && p.targetClasses().contains(entry.getName()))
                        .map(JarPatch::id).toList();
                try {
                    verify(zip.getInputStream(entry).readAllBytes());
                } catch (RuntimeException failure) {
                    throw new PatchException("Pre-translation ASM check: " + jarName + ":" + entry.getName()
                            + "; patches=" + related + "; " + failure.getMessage(), failure);
                }
                count++;
            }
        }
        return count;
    }

    public static void verify(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        for (MethodNode method : node.methods) {
            if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
            try {
                verifyMethod(node, method);
            } catch (AnalyzerException failure) {
                throw new PatchException(node.name + "." + method.name + method.desc
                        + " instruction=" + method.instructions.indexOf(failure.node) + ": " + failure.getMessage(), failure);
            } catch (RuntimeException failure) {
                throw new PatchException(node.name + "." + method.name + method.desc + ": " + failure.getMessage(), failure);
            }
        }
    }

    private static void verifyMethod(ClassNode owner, MethodNode method) throws AnalyzerException {
        Map<AbstractInsnNode, Integer> positions = new IdentityHashMap<>();
        Map<Integer, AbstractInsnNode> executable = new HashMap<>();
        Set<Integer> framed = new HashSet<>();
        int position = 0;
        for (AbstractInsnNode insn : method.instructions) {
            positions.put(insn, position);
            if (insn instanceof FrameNode && !framed.add(position)) fail("duplicate frame at instruction " + position);
            if (insn.getOpcode() >= 0) executable.put(position++, insn);
        }
        final int end = position;
        if (end == 0) fail("concrete method has no code");
        if (framed.contains(end)) fail("frame after end of code");
        int arguments = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        for (Type type : Type.getArgumentTypes(method.desc)) arguments += type.getSize();
        if (method.maxLocals < arguments) fail("max_locals does not cover arguments");

        List<Object> locals = FrameSupport.initialLocals(owner.name, method);
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof JumpInsnNode jump) target(owner, jump.label, positions, framed, end);
            if (insn instanceof TableSwitchInsnNode table) {
                target(owner, table.dflt, positions, framed, end);
                for (LabelNode label : table.labels) target(owner, label, positions, framed, end);
            }
            if (insn instanceof LookupSwitchInsnNode lookup) {
                target(owner, lookup.dflt, positions, framed, end);
                for (LabelNode label : lookup.labels) target(owner, label, positions, framed, end);
            }
            if (insn instanceof VarInsnNode variable) {
                int opcode = variable.getOpcode();
                int size = opcode == Opcodes.LLOAD || opcode == Opcodes.DLOAD
                        || opcode == Opcodes.LSTORE || opcode == Opcodes.DSTORE ? 2 : 1;
                checkLocal(variable.var, size, method.maxLocals);
            }
            if (insn instanceof IincInsnNode increment) checkLocal(increment.var, 1, method.maxLocals);
            if (insn instanceof FrameNode frame) {
                List<Object> stack = frame.stack == null ? List.of() : frame.stack;
                switch (frame.type) {
                    case Opcodes.F_NEW, Opcodes.F_FULL -> locals = new ArrayList<>(frame.local == null ? List.of() : frame.local);
                    case Opcodes.F_APPEND -> locals.addAll(frame.local);
                    case Opcodes.F_CHOP -> {
                        int removed = frame.local.size();
                        if (removed > locals.size()) fail("frame CHOP exceeds locals");
                        locals.subList(locals.size() - removed, locals.size()).clear();
                    }
                    case Opcodes.F_SAME, Opcodes.F_SAME1 -> { }
                    default -> fail("unknown frame type " + frame.type);
                }
                if (FrameSupport.slots(locals) > method.maxLocals) fail("frame exceeds max_locals");
                if (FrameSupport.slots(stack) > method.maxStack) fail("frame exceeds max_stack");
                checkUninitialized(locals, positions, executable);
                checkUninitialized(stack, positions, executable);
            }
        }
        for (TryCatchBlockNode block : method.tryCatchBlocks) {
            int start = labelPosition(block.start, positions), stop = labelPosition(block.end, positions);
            if (start >= stop || stop > end) fail("invalid exception range");
            target(owner, block.handler, positions, framed, end);
        }
        for (Frame<BasicValue> frame : new Analyzer<>(new BasicVerifier()).analyze(owner.name, method)) {
            if (frame == null) continue;
            int required = 0;
            for (int i = 0; i < frame.getStackSize(); i++) required += frame.getStack(i).getSize();
            if (required > method.maxStack) fail("max_stack=" + method.maxStack + ", required=" + required);
        }
    }

    private static void target(ClassNode owner, LabelNode label, Map<AbstractInsnNode, Integer> positions,
                               Set<Integer> framed, int end) {
        int position = labelPosition(label, positions);
        if (position >= end) fail("branch/handler target outside code");
        // Version 50 allows the legacy verifier fallback; <=49 predates mandatory frames.
        if ((owner.version & 0xffff) >= Opcodes.V1_7 && position != 0 && !framed.contains(position)) {
            fail("missing target frame at instruction " + position);
        }
    }

    private static int labelPosition(LabelNode label, Map<AbstractInsnNode, Integer> positions) {
        Integer position = positions.get(label);
        if (position == null) fail("label outside method");
        return position;
    }

    private static void checkLocal(int index, int size, int limit) {
        if (index < 0 || index + size > limit) fail("local " + index + " size=" + size + " exceeds max_locals=" + limit);
    }

    private static void checkUninitialized(List<Object> values, Map<AbstractInsnNode, Integer> positions,
                                           Map<Integer, AbstractInsnNode> executable) {
        for (Object value : values) {
            if (value instanceof LabelNode label) {
                AbstractInsnNode instruction = executable.get(labelPosition(label, positions));
                if (instruction == null || instruction.getOpcode() != Opcodes.NEW) fail("uninitialized frame value does not reference NEW");
            }
        }
    }

    private static void fail(String message) { throw new PatchException(message); }
}
