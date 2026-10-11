package org.fossic.starsector.preprocessing;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

/** Supported prologues only: straight-line fragments, unchanged locals, no constructors. */
public final class EntryHooks {
    private EntryHooks() {}

    public static void returnIfTrue(String owner, MethodNode method, InsnList condition, InsnList taken) {
        requireMethod(method);
        int peak = Math.max(fragmentPeak(owner, method, condition, true), fragmentPeak(owner, method, taken, false));
        boolean entryFrame = hasEntryFrame(owner, method);
        LabelNode resume = new LabelNode();
        InsnList code = new InsnList();
        code.add(condition);
        code.add(new JumpInsnNode(Opcodes.IFEQ, resume));
        code.add(taken);
        code.add(resume);
        if (!entryFrame) code.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        method.instructions.insert(code);
        method.maxStack = Math.max(method.maxStack, peak);
    }

    public static void returnIfNonNull(String owner, MethodNode method, InsnList lookup) {
        requireMethod(method);
        Type result = Type.getReturnType(method.desc);
        if (result.getSort() != Type.OBJECT && result.getSort() != Type.ARRAY) {
            throw new PatchException("Non-null entry hook requires reference return type");
        }
        // Validate the lookup as an independent return body, without touching caller instructions.
        InsnList probe = copy(lookup);
        probe.add(new InsnNode(Opcodes.ARETURN));
        int peak = Math.max(2, fragmentPeak(owner, method, probe, false));
        LabelNode resume = new LabelNode();
        InsnList code = new InsnList();
        code.add(lookup);
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new JumpInsnNode(Opcodes.IFNULL, resume));
        code.add(new InsnNode(Opcodes.ARETURN));
        code.add(resume);
        code.add(new FrameSupport.State(FrameSupport.initialLocals(owner, method),
                java.util.List.of(FrameSupport.frameType(result))).fullFrame());
        code.add(new InsnNode(Opcodes.POP));
        method.instructions.insert(code);
        method.maxStack = Math.max(method.maxStack, peak);
    }

    private static void requireMethod(MethodNode method) {
        if (method.name.startsWith("<") || (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
                || method.instructions.size() == 0) throw new PatchException("Unsupported entry hook method " + method.name);
    }

    private static boolean hasEntryFrame(String owner, MethodNode method) {
        for (AbstractInsnNode node : method.instructions) {
            if (node.getOpcode() >= 0) break;
            if (node instanceof FrameNode frame) {
                FrameSupport.State state = FrameSupport.expandAt(owner, method, frame);
                if (!state.locals().equals(FrameSupport.initialLocals(owner, method)) || !state.stack().isEmpty()) {
                    throw new PatchException("Unexpected method entry frame");
                }
                return true;
            }
        }
        return false;
    }

    private static InsnList copy(InsnList code) {
        InsnList result = new InsnList();
        for (AbstractInsnNode insn : code) {
            int op = insn.getOpcode();
            if (op < 0 || insn instanceof JumpInsnNode || insn instanceof TableSwitchInsnNode
                    || insn instanceof LookupSwitchInsnNode || insn instanceof IincInsnNode
                    || op == Opcodes.NEW || op == Opcodes.RET || op == Opcodes.ATHROW
                    || op == Opcodes.MONITORENTER || op == Opcodes.MONITOREXIT
                    || (op >= Opcodes.ISTORE && op <= Opcodes.ASTORE)
                    || (insn instanceof MethodInsnNode call && call.name.equals("<init>"))) {
                throw new PatchException("Entry hook fragment must be linear and preserve locals");
            }
            result.add(insn.clone(null));
        }
        return result;
    }

    private static int fragmentPeak(String owner, MethodNode method, InsnList code, boolean condition) {
        MethodNode probe = new MethodNode(method.access, "entryProbe",
                condition ? method.desc.substring(0, method.desc.indexOf(')') + 1) + "Z" : method.desc, null, null);
        probe.instructions = copy(code);
        if (condition) probe.instructions.add(new InsnNode(Opcodes.IRETURN));
        int returnOp = Type.getReturnType(probe.desc).getOpcode(Opcodes.IRETURN);
        for (AbstractInsnNode node : probe.instructions) {
            if (node.getOpcode() >= Opcodes.IRETURN && node.getOpcode() <= Opcodes.RETURN
                    && (node != probe.instructions.getLast() || node.getOpcode() != returnOp)) {
                throw new PatchException("Entry hook must end with exactly one matching return");
            }
        }
        if (probe.instructions.getLast() == null || probe.instructions.getLast().getOpcode() != returnOp) {
            throw new PatchException("Entry hook is missing return");
        }
        probe.maxLocals = method.maxLocals;
        // Conservative analysis workspace only; the emitted limit comes from measured slot demand.
        probe.maxStack = probe.instructions.size() * 2 + 2;
        int peak = 0;
        try {
            Frame<BasicValue>[] frames = new Analyzer<>(new BasicVerifier()).analyze(owner, probe);
            int returnValues = returnOp == Opcodes.RETURN ? 0 : 1;
            if (frames[frames.length - 1].getStackSize() != returnValues) {
                throw new PatchException("Entry hook leaves extra operand stack values");
            }
            for (Frame<BasicValue> frame : frames) {
                if (frame == null) continue;
                int slots = 0;
                for (int i = 0; i < frame.getStackSize(); i++) slots += frame.getStack(i).getSize();
                peak = Math.max(peak, slots);
            }
        } catch (AnalyzerException error) {
            throw new PatchException("Invalid entry hook fragment: " + error.getMessage(), error);
        }
        return peak;
    }
}
