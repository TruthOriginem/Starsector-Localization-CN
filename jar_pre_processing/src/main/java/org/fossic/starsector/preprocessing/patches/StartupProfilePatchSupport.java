package org.fossic.starsector.preprocessing.patches;

import org.fossic.starsector.preprocessing.PatchException;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Arrays;
import java.util.List;

final class StartupProfilePatchSupport {
    static final String PROFILER_OWNER = "org/fossic/starsector/startup/StartupProfiler";
    static final String STRING_HOOK_DESC = "(Ljava/lang/String;)V";

    private StartupProfilePatchSupport() {
    }

    static MethodNode requireMethod(ClassNode classNode, String name, String desc) {
        List<MethodNode> matches = classNode.methods.stream()
                .filter(method -> name.equals(method.name) && desc.equals(method.desc))
                .toList();
        if (matches.size() != 1) {
            throw new PatchException("方法匹配数异常: " + classNode.name + "." + name + desc
                    + "，实际 " + matches.size() + "，预期 1");
        }
        return matches.get(0);
    }

    static InsnList initializeCall() {
        InsnList result = new InsnList();
        result.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                PROFILER_OWNER,
                "initialize",
                "()V",
                false
        ));
        return result;
    }

    static InsnList phaseCall(String method, String phase) {
        InsnList result = new InsnList();
        result.add(new LdcInsnNode(phase));
        result.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                PROFILER_OWNER,
                method,
                STRING_HOOK_DESC,
                false
        ));
        return result;
    }

    static InsnList phasePair(String firstMethod, String firstPhase,
                              String secondMethod, String secondPhase) {
        InsnList result = new InsnList();
        result.add(phaseCall(firstMethod, firstPhase));
        result.add(phaseCall(secondMethod, secondPhase));
        return result;
    }

    static List<MethodInsnNode> methodCalls(MethodNode method, String owner, String desc) {
        return Arrays.stream(method.instructions.toArray())
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .filter(call -> owner.equals(call.owner) && desc.equals(call.desc))
                .toList();
    }

    static MethodInsnNode requireCall(MethodNode method, String owner, String name, String desc) {
        List<MethodInsnNode> matches = Arrays.stream(method.instructions.toArray())
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .filter(call -> owner.equals(call.owner)
                        && name.equals(call.name)
                        && desc.equals(call.desc))
                .toList();
        if (matches.size() != 1) {
            throw new PatchException("调用匹配数异常: " + owner + "." + name + desc
                    + "，实际 " + matches.size() + "，预期 1");
        }
        return matches.get(0);
    }

    static AbstractInsnNode nextExecutable(AbstractInsnNode instruction) {
        AbstractInsnNode current = instruction.getNext();
        while (current != null && current.getOpcode() < 0) {
            current = current.getNext();
        }
        if (current == null) {
            throw new PatchException("目标指令后没有可执行指令");
        }
        return current;
    }

    static int countProfilerCalls(ClassNode classNode) {
        int count = 0;
        for (MethodNode method : classNode.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call
                        && PROFILER_OWNER.equals(call.owner)) {
                    count++;
                }
            }
        }
        return count;
    }

    static int countOpcode(MethodNode method, int opcode) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() == opcode) {
                count++;
            }
        }
        return count;
    }
}
