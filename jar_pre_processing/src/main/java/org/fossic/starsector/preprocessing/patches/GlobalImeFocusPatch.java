package org.fossic.starsector.preprocessing.patches;

import org.fossic.starsector.preprocessing.AsmUtil;
import org.fossic.starsector.preprocessing.JarPatch;
import org.fossic.starsector.preprocessing.PatchGroup;
import org.fossic.starsector.preprocessing.JarWorkspace;
import org.fossic.starsector.preprocessing.PatchContext;
import org.fossic.starsector.preprocessing.PatchException;
import org.fossic.starsector.preprocessing.PatchResult;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.Set;

/** 在游戏全局输入帧入口初始化并校验 IME 生命周期。 */
public final class GlobalImeFocusPatch implements JarPatch {
    private static final String TARGET_CLASS = "com/fs/starfarer/ui/W.class";
    private static final String FRAME_METHOD = "o00000";
    private static final String FRAME_DESC = "(Lcom/fs/starfarer/util/A/new;)V";
    private static final String FOCUS_GETTER = "Ó00000";
    private static final String FOCUS_DESC = "()Lcom/fs/starfarer/ui/supersuper;";
    private static final String HOOKS_OWNER = "org/fossic/starsector/ime/ImeHooks";
    private static final String FRAME_HOOK = "onGlobalInputFrame";
    private static final String FOCUS_HOOK = "onGlobalFocusChanged";
    private static final String HOOK_DESC = "(Ljava/lang/Object;)V";
    private static final String OWNER_DESC = "(Lcom/fs/starfarer/ui/supersuper;)V";
    private static final Target[] MUTATIONS = {
            new Target("o00000", "(Ljava/util/List;)V"),
            new Target("class", OWNER_DESC),
            new Target("Ø00000", OWNER_DESC),
            new Target("o00000", OWNER_DESC),
            new Target("Ô00000", OWNER_DESC),
            new Target("new", OWNER_DESC),
            new Target("new", "()V")
    };

    @Override
    public String id() {
        return "global-ime-focus-hook";
    }

    @Override
    public PatchGroup group() {
        return PatchGroup.IME;
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
        MethodNode frame = null;
        for (MethodNode method : classNode.methods) {
            if (FRAME_METHOD.equals(method.name) && FRAME_DESC.equals(method.desc)) {
                if (frame != null) {
                    throw new PatchException(id() + " found duplicate " + FRAME_METHOD + FRAME_DESC);
                }
                frame = method;
            }
        }
        if (frame == null) {
            throw new PatchException(id() + " requires exactly one " + FRAME_METHOD + FRAME_DESC);
        }

        MethodInsnNode getter = null;
        for (var instruction : frame.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && classNode.name.equals(call.owner)
                    && FOCUS_GETTER.equals(call.name)
                    && FOCUS_DESC.equals(call.desc)) {
                if (getter != null) {
                    throw new PatchException(id() + " found ambiguous focus getter in "
                            + FRAME_METHOD + FRAME_DESC);
                }
                getter = call;
            }
        }
        if (getter == null) {
            throw new PatchException(id() + " found no focus getter in "
                    + FRAME_METHOD + FRAME_DESC);
        }

        if (!(AsmUtil.nextReal(getter) instanceof VarInsnNode store)
                || store.getOpcode() != Opcodes.ASTORE) {
            throw new PatchException(id() + " expected focus getter result to be stored with ASTORE");
        }

        InsnList hook = new InsnList();
        hook.add(new VarInsnNode(Opcodes.ALOAD, store.var));
        hook.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                HOOKS_OWNER,
                FRAME_HOOK,
                HOOK_DESC,
                false));
        frame.instructions.insert(store, hook);
        frame.maxStack = Math.max(frame.maxStack, 1);

        int mutationHooks = 0;
        for (Target target : MUTATIONS) {
            MethodNode mutation = findExactlyOne(classNode, target);
            int returns = 0;
            for (AbstractInsnNode instruction = mutation.instructions.getFirst();
                 instruction != null; instruction = instruction.getNext()) {
                if (instruction.getOpcode() != Opcodes.RETURN) {
                    continue;
                }
                InsnList notification = new InsnList();
                notification.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        classNode.name,
                        FOCUS_GETTER,
                        FOCUS_DESC,
                        false));
                notification.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        HOOKS_OWNER,
                        FOCUS_HOOK,
                        HOOK_DESC,
                        false));
                mutation.instructions.insertBefore(instruction, notification);
                returns++;
            }
            if (returns != 1) {
                throw new PatchException(id() + " expected exactly one normal return in "
                        + target.name() + target.descriptor() + ", found " + returns);
            }
            mutation.maxStack = Math.max(mutation.maxStack, 1);
            mutationHooks += returns;
        }

        int verified = AsmUtil.countMethodCall(classNode, HOOKS_OWNER, FRAME_HOOK, HOOK_DESC)
                + AsmUtil.countMethodCall(classNode, HOOKS_OWNER, FOCUS_HOOK, HOOK_DESC);
        int expected = 1 + mutationHooks;
        return PatchResult.of(id(), context.classPath(), expected, expected, verified,
                "inject global IME frame boundary and focus-change notifications");
    }

    private MethodNode findExactlyOne(ClassNode classNode, Target target) {
        MethodNode found = null;
        for (MethodNode method : classNode.methods) {
            if (!target.name().equals(method.name) || !target.descriptor().equals(method.desc)) {
                continue;
            }
            if (found != null) {
                throw new PatchException(id() + " found duplicate "
                        + target.name() + target.descriptor());
            }
            found = method;
        }
        if (found == null) {
            throw new PatchException(id() + " requires exactly one "
                    + target.name() + target.descriptor());
        }
        return found;
    }

    private record Target(String name, String descriptor) {
    }
}
