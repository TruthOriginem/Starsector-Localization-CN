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
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class IntelPutFirstTagIdPatch implements JarPatch {
    private static final String TARGET_CLASS =
            "com/fs/starfarer/campaign/comms/v2/EventsPanel.class";
    private static final String TARGET_CLASS_NAME =
            "com/fs/starfarer/campaign/comms/v2/EventsPanel";
    private static final String TARGET_METHOD_NAME = "o00000";
    private static final String TARGET_METHOD_DESC =
            "(Lcom/fs/starfarer/campaign/command/M;Z)V";

    private static final String TAG_SPEC = "com/fs/starfarer/loading/C";
    private static final String NAME_GETTER = "Object";
    private static final String ID_GETTER = "Õ00000";
    private static final String STRING_GETTER_DESC = "()Ljava/lang/String;";
    private static final String COUNTING_MAP = "com/fs/starfarer/api/util/CountingMap";
    private static final String COUNTING_MAP_ADD_DESC = "(Ljava/lang/Object;I)V";

    @Override
    public String id() {
        return "intel-put-first-tag-id";
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
            throw new PatchException(id() + " received unexpected class " + classNode.name);
        }

        MethodNode method = findTargetMethod(classNode, context);
        List<MethodInsnNode> matches = findCountingKeyGetterCalls(method, NAME_GETTER);
        int nameGetterCallsBefore = countTagSpecCalls(method, NAME_GETTER);
        if (matches.size() != 1 || nameGetterCallsBefore != 3) {
            throw new PatchException(id() + " failed for " + context.classPath()
                    + ": expected one putFirst name-key sequence and three name getter calls, found "
                    + matches.size() + " and " + nameGetterCallsBefore);
        }

        matches.get(0).name = ID_GETTER;

        int oldSequences = findCountingKeyGetterCalls(method, NAME_GETTER).size();
        int patchedSequences = findCountingKeyGetterCalls(method, ID_GETTER).size();
        int remainingDisplayNameCalls = countTagSpecCalls(method, NAME_GETTER);
        if (oldSequences != 0 || patchedSequences != 1 || remainingDisplayNameCalls != 2) {
            throw new PatchException(id() + " verification failed for " + context.classPath()
                    + ": oldSequences=" + oldSequences
                    + ", patchedSequences=" + patchedSequences
                    + ", displayNameCalls=" + remainingDisplayNameCalls);
        }

        return PatchResult.of(id(), context.classPath(), 1, 1, 1,
                "use intel tag id instead of display name when seeding putFirst tags");
    }

    private static MethodNode findTargetMethod(ClassNode classNode, PatchContext context) {
        List<MethodNode> matches = new ArrayList<>();
        for (MethodNode method : classNode.methods) {
            if (TARGET_METHOD_NAME.equals(method.name) && TARGET_METHOD_DESC.equals(method.desc)) {
                matches.add(method);
            }
        }
        if (matches.size() != 1) {
            throw new PatchException("intel-put-first-tag-id failed for " + context.classPath()
                    + ": expected one target method, found " + matches.size());
        }
        return matches.get(0);
    }

    private static List<MethodInsnNode> findCountingKeyGetterCalls(MethodNode method, String getterName) {
        List<MethodInsnNode> matches = new ArrayList<>();
        for (AbstractInsnNode node : AsmUtil.instructions(method)) {
            if (!(node instanceof MethodInsnNode getter)
                    || getter.getOpcode() != Opcodes.INVOKEVIRTUAL
                    || !TAG_SPEC.equals(getter.owner)
                    || !getterName.equals(getter.name)
                    || !STRING_GETTER_DESC.equals(getter.desc)) {
                continue;
            }

            AbstractInsnNode previous = previousExecutable(node);
            AbstractInsnNode next = nextExecutable(node);
            AbstractInsnNode afterNext = nextExecutable(next);
            if (previous instanceof TypeInsnNode cast
                    && cast.getOpcode() == Opcodes.CHECKCAST
                    && TAG_SPEC.equals(cast.desc)
                    && next != null
                    && next.getOpcode() == Opcodes.ICONST_0
                    && afterNext instanceof MethodInsnNode add
                    && add.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && COUNTING_MAP.equals(add.owner)
                    && "add".equals(add.name)
                    && COUNTING_MAP_ADD_DESC.equals(add.desc)) {
                matches.add(getter);
            }
        }
        return matches;
    }

    private static int countTagSpecCalls(MethodNode method, String methodName) {
        int count = 0;
        for (AbstractInsnNode node : AsmUtil.instructions(method)) {
            if (node instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && TAG_SPEC.equals(call.owner)
                    && methodName.equals(call.name)
                    && STRING_GETTER_DESC.equals(call.desc)) {
                count++;
            }
        }
        return count;
    }

    private static AbstractInsnNode previousExecutable(AbstractInsnNode node) {
        AbstractInsnNode current = node == null ? null : node.getPrevious();
        while (current != null && current.getOpcode() < 0) {
            current = current.getPrevious();
        }
        return current;
    }

    private static AbstractInsnNode nextExecutable(AbstractInsnNode node) {
        AbstractInsnNode current = node == null ? null : node.getNext();
        while (current != null && current.getOpcode() < 0) {
            current = current.getNext();
        }
        return current;
    }
}
