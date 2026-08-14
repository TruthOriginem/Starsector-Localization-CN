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
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Prevents the campaign terrain status bar from leaving a separator after the last visible name.
 *
 * <p>The original method decides whether an entry is last using the raw map size, then skips
 * terrain plugins whose display name is null. Mod terrain names may also be empty or change at
 * runtime. This patch retains the existing layout code but makes the separator conditional on a
 * previously rendered name, so separators are emitted only between visible entries.</p>
 */
public final class TerrainStatusBarSeparatorPatch implements JarPatch {
    private static final String TARGET_CLASS =
            "com/fs/starfarer/ui/newui/public.class";
    private static final String TARGET_CLASS_NAME =
            "com/fs/starfarer/ui/newui/public";
    private static final String TARGET_METHOD_NAME = "recreate";
    private static final String TARGET_METHOD_DESC = "()V";

    private static final String PLUGIN =
            "com/fs/starfarer/api/campaign/CampaignTerrainPlugin";
    private static final String NAME_GETTER = "getTerrainName";
    private static final String NAME_DESC = "()Ljava/lang/String;";
    private static final String STRING = "java/lang/String";
    private static final String TEXT_COMPONENT = "com/fs/starfarer/ui/d";
    private static final String POSITION = "com/fs/starfarer/ui/OO0O";
    private static final String RIGHT_OF_MID = "rightOfMid";
    private static final String RIGHT_OF_MID_DESC =
            "(Lcom/fs/starfarer/api/ui/UIComponentAPI;F)Lcom/fs/starfarer/ui/OO0O;";

    private static final int PREVIOUS_COMPONENT_LOCAL = 2;
    private static final int GAP_LOCAL = 3;
    private static final int NAME_CACHE_LOCAL = 8;

    @Override
    public String id() {
        return "terrain-status-bar-visible-separator";
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
        int labelsBefore = countLabels(method);
        int originalMaxLocals = method.maxLocals;

        NameFlow nameFlow = findNameFlow(method, context);
        SeparatorFlow separatorFlow = findSeparatorFlow(method, nameFlow.loopContinue(), context);

        cacheAndValidateName(method, nameFlow);
        int scratchLocal = moveSeparatorBeforeCurrentName(method, separatorFlow);

        verifyPatchedMethod(
                method,
                nameFlow,
                separatorFlow,
                scratchLocal,
                originalMaxLocals,
                labelsBefore,
                context
        );

        return PatchResult.of(
                id(),
                context.classPath(),
                1,
                1,
                1,
                "cache each terrain name once and render separators only between nonblank names"
        );
    }

    private MethodNode findTargetMethod(ClassNode classNode, PatchContext context) {
        List<MethodNode> matches = classNode.methods.stream()
                .filter(method -> TARGET_METHOD_NAME.equals(method.name)
                        && TARGET_METHOD_DESC.equals(method.desc))
                .toList();
        if (matches.size() != 1) {
            throw failure(context, "expected one recreate()V method, found " + matches.size());
        }
        return matches.get(0);
    }

    private NameFlow findNameFlow(MethodNode method, PatchContext context) {
        List<MethodInsnNode> getters = methodCalls(method, PLUGIN, NAME_GETTER, NAME_DESC);
        if (getters.size() != 3) {
            throw failure(context, "expected three terrain-name getter calls, found " + getters.size());
        }

        MethodInsnNode firstGetter = getters.get(0);
        VarInsnNode pluginLoad = requireVar(
                previousExecutable(firstGetter), Opcodes.ALOAD, "terrain plugin load", context);
        JumpInsnNode visibleJump = requireJump(
                nextExecutable(firstGetter), Opcodes.IFNONNULL, "terrain-name null check", context);
        JumpInsnNode nullFallback = requireJump(
                nextExecutable(visibleJump), Opcodes.GOTO, "null-name fallback", context);
        FrameNode visibleFrame = frameImmediatelyAfter(visibleJump.label, context);
        if (visibleFrame.type != Opcodes.F_APPEND
                || visibleFrame.local == null
                || visibleFrame.local.size() != 2
                || !Opcodes.INTEGER.equals(visibleFrame.local.get(0))
                || !PLUGIN.equals(visibleFrame.local.get(1))
                || (visibleFrame.stack != null && !visibleFrame.stack.isEmpty())) {
            throw failure(context, "unexpected visible-name stack-map frame");
        }

        for (int i = 1; i < getters.size(); i++) {
            VarInsnNode load = requireVar(
                    previousExecutable(getters.get(i)),
                    Opcodes.ALOAD,
                    "repeated terrain plugin load",
                    context
            );
            if (load.var != pluginLoad.var) {
                throw failure(context, "terrain-name getters use inconsistent plugin locals");
            }
        }

        return new NameFlow(
                getters,
                pluginLoad.var,
                visibleJump,
                nullFallback,
                visibleFrame,
                visibleJump.label,
                nullFallback.label
        );
    }

    private SeparatorFlow findSeparatorFlow(
            MethodNode method,
            LabelNode loopContinue,
            PatchContext context
    ) {
        List<LdcInsnNode> commaConstants = AsmUtil.instructions(method).stream()
                .filter(node -> node instanceof LdcInsnNode ldc && ",".equals(ldc.cst))
                .map(LdcInsnNode.class::cast)
                .toList();
        if (commaConstants.size() != 1) {
            throw failure(context, "expected one terrain separator literal, found "
                    + commaConstants.size());
        }
        LdcInsnNode comma = commaConstants.get(0);

        TypeInsnNode allocation = requireType(
                previousExecutable(previousExecutable(comma)),
                Opcodes.NEW,
                TEXT_COMPONENT,
                "separator allocation",
                context
        );
        if (nextExecutable(allocation).getOpcode() != Opcodes.DUP) {
            throw failure(context, "separator allocation is not followed by DUP");
        }

        MethodInsnNode constructor = firstCallAfter(
                comma,
                TEXT_COMPONENT,
                "<init>",
                null,
                Opcodes.INVOKESPECIAL,
                context
        );
        VarInsnNode separatorStore = requireVar(
                nextExecutable(constructor), Opcodes.ASTORE, "separator store", context);

        MethodInsnNode commaPositioning = firstCallAfter(
                separatorStore,
                POSITION,
                RIGHT_OF_MID,
                RIGHT_OF_MID_DESC,
                Opcodes.INVOKEVIRTUAL,
                context
        );
        AbstractInsnNode commaGap = previousExecutable(commaPositioning);
        if (commaGap == null || commaGap.getOpcode() != Opcodes.FCONST_2) {
            throw failure(context, "separator positioning does not use the expected 2px gap");
        }
        VarInsnNode commaAnchor = requireVar(
                previousExecutable(commaGap), Opcodes.ALOAD, "separator anchor", context);
        AbstractInsnNode commaPositioningPop = nextExecutable(commaPositioning);
        if (commaPositioningPop == null || commaPositioningPop.getOpcode() != Opcodes.POP) {
            throw failure(context, "separator positioning result is not discarded");
        }
        VarInsnNode finalSource = requireVar(
                nextExecutable(commaPositioningPop), Opcodes.ALOAD, "final previous source", context);
        VarInsnNode finalStore = requireVar(
                nextExecutable(finalSource), Opcodes.ASTORE, "final previous store", context);
        if (finalSource.var != separatorStore.var || finalStore.var != PREVIOUS_COMPONENT_LOCAL) {
            throw failure(context, "unexpected previous-component update after separator");
        }

        List<JumpInsnNode> gates = new ArrayList<>();
        for (AbstractInsnNode node = comma.getPrevious(); node != null; node = node.getPrevious()) {
            if (node instanceof JumpInsnNode jump
                    && jump.getOpcode() == Opcodes.IFNE
                    && jump.label == loopContinue
                    && previousExecutable(jump) instanceof VarInsnNode load
                    && load.getOpcode() == Opcodes.ILOAD
                    && load.var == NAME_CACHE_LOCAL) {
                gates.add(jump);
            }
        }
        if (gates.size() != 1) {
            throw failure(context, "expected one original last-item separator gate, found "
                    + gates.size());
        }
        JumpInsnNode gate = gates.get(0);
        VarInsnNode gateLoad = (VarInsnNode) previousExecutable(gate);

        return new SeparatorFlow(
                comma,
                gateLoad,
                gate,
                separatorStore.var,
                commaAnchor.var,
                commaAnchor,
                commaPositioning,
                commaPositioningPop,
                finalSource,
                finalStore
        );
    }

    private void cacheAndValidateName(MethodNode method, NameFlow flow) {
        InsnList cache = new InsnList();
        cache.add(new VarInsnNode(Opcodes.ASTORE, NAME_CACHE_LOCAL));
        cache.add(new VarInsnNode(Opcodes.ALOAD, NAME_CACHE_LOCAL));
        method.instructions.insert(flow.getters().get(0), cache);

        flow.visibleJump().setOpcode(Opcodes.IFNULL);
        flow.visibleJump().label = flow.loopContinue();

        InsnList blankCheck = new InsnList();
        blankCheck.add(new VarInsnNode(Opcodes.ALOAD, NAME_CACHE_LOCAL));
        blankCheck.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, STRING, "isBlank", "()Z", false));
        blankCheck.add(new JumpInsnNode(Opcodes.IFEQ, flow.visibleLabel()));
        method.instructions.insert(flow.visibleJump(), blankCheck);

        flow.visibleFrame().local.set(0, STRING);

        for (int i = 1; i < flow.getters().size(); i++) {
            MethodInsnNode getter = flow.getters().get(i);
            VarInsnNode load = (VarInsnNode) previousExecutable(getter);
            load.var = NAME_CACHE_LOCAL;
            method.instructions.remove(getter);
        }
    }

    private int moveSeparatorBeforeCurrentName(MethodNode method, SeparatorFlow flow) {
        int scratchLocal = method.maxLocals;
        method.maxLocals = scratchLocal + 1;

        InsnList rememberComponents = new InsnList();
        rememberComponents.add(new VarInsnNode(Opcodes.ALOAD, PREVIOUS_COMPONENT_LOCAL));
        rememberComponents.add(new VarInsnNode(Opcodes.ASTORE, scratchLocal));
        rememberComponents.add(new VarInsnNode(Opcodes.ALOAD, flow.currentComponentLocal()));
        rememberComponents.add(new VarInsnNode(Opcodes.ASTORE, PREVIOUS_COMPONENT_LOCAL));
        method.instructions.insertBefore(flow.gateLoad(), rememberComponents);

        VarInsnNode patchedGateLoad = new VarInsnNode(Opcodes.ALOAD, scratchLocal);
        method.instructions.set(flow.gateLoad(), patchedGateLoad);
        flow.gate().setOpcode(Opcodes.IFNULL);

        flow.commaAnchor().var = scratchLocal;

        InsnList repositionCurrent = new InsnList();
        repositionCurrent.add(new VarInsnNode(Opcodes.ALOAD, flow.currentComponentLocal()));
        repositionCurrent.add(new VarInsnNode(Opcodes.ALOAD, flow.separatorLocal()));
        repositionCurrent.add(new VarInsnNode(Opcodes.FLOAD, GAP_LOCAL));
        repositionCurrent.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                POSITION,
                RIGHT_OF_MID,
                RIGHT_OF_MID_DESC,
                false
        ));
        repositionCurrent.add(new InsnNode(Opcodes.POP));
        method.instructions.insert(flow.commaPositioningPop(), repositionCurrent);

        flow.finalSource().var = flow.currentComponentLocal();
        return scratchLocal;
    }

    private void verifyPatchedMethod(
            MethodNode method,
            NameFlow nameFlow,
            SeparatorFlow separatorFlow,
            int scratchLocal,
            int originalMaxLocals,
            int labelsBefore,
            PatchContext context
    ) {
        if (methodCalls(method, PLUGIN, NAME_GETTER, NAME_DESC).size() != 1
                || methodCalls(method, STRING, "isBlank", "()Z").size() != 1) {
            throw failure(context, "terrain name is not cached and validated exactly once");
        }
        if (nameFlow.visibleFrame().local == null
                || !STRING.equals(nameFlow.visibleFrame().local.get(0))) {
            throw failure(context, "visible-name frame was not updated for the cached String");
        }
        if (method.maxLocals != originalMaxLocals + 1 || scratchLocal != originalMaxLocals) {
            throw failure(context, "unexpected scratch-local allocation");
        }
        if (countLabels(method) != labelsBefore) {
            throw failure(context, "patch unexpectedly changed the method's control-flow labels");
        }
        if (separatorFlow.gate().getOpcode() != Opcodes.IFNULL
                || separatorFlow.gate().label != nameFlow.loopContinue()
                || !(previousExecutable(separatorFlow.gate()) instanceof VarInsnNode load)
                || load.getOpcode() != Opcodes.ALOAD
                || load.var != scratchLocal) {
            throw failure(context, "separator is not gated by the previous visible component");
        }
        if (separatorFlow.commaAnchor().var != scratchLocal) {
            throw failure(context, "separator is not anchored after the previous visible name");
        }
        if (separatorFlow.finalSource().var != separatorFlow.currentComponentLocal()) {
            throw failure(context, "current terrain label was not retained as the previous component");
        }
        if (countVarJumpPairs(method, Opcodes.ILOAD, NAME_CACHE_LOCAL, Opcodes.IFNE) != 0) {
            throw failure(context, "old raw-map last-item separator gate remains");
        }
        if (countCallsAfter(
                method,
                separatorFlow.comma(),
                POSITION,
                RIGHT_OF_MID,
                RIGHT_OF_MID_DESC) != 2) {
            throw failure(context, "expected separator placement and current-label repositioning");
        }
    }

    private static List<MethodInsnNode> methodCalls(
            MethodNode method,
            String owner,
            String name,
            String desc
    ) {
        return AsmUtil.instructions(method).stream()
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .filter(call -> owner.equals(call.owner)
                        && name.equals(call.name)
                        && desc.equals(call.desc))
                .toList();
    }

    private static MethodInsnNode firstCallAfter(
            AbstractInsnNode start,
            String owner,
            String name,
            String desc,
            int opcode,
            PatchContext context
    ) {
        for (AbstractInsnNode node = start.getNext(); node != null; node = node.getNext()) {
            if (node instanceof MethodInsnNode call
                    && call.getOpcode() == opcode
                    && owner.equals(call.owner)
                    && name.equals(call.name)
                    && (desc == null || desc.equals(call.desc))) {
                return call;
            }
        }
        throw failure(context, "required call not found after separator anchor: " + owner + "." + name);
    }

    private static int countCallsAfter(
            MethodNode method,
            AbstractInsnNode start,
            String owner,
            String name,
            String desc
    ) {
        int count = 0;
        boolean afterStart = false;
        for (AbstractInsnNode node : AsmUtil.instructions(method)) {
            if (node == start) {
                afterStart = true;
            } else if (afterStart
                    && node instanceof MethodInsnNode call
                    && owner.equals(call.owner)
                    && name.equals(call.name)
                    && desc.equals(call.desc)) {
                count++;
            }
        }
        return count;
    }

    private static int countVarJumpPairs(
            MethodNode method,
            int varOpcode,
            int varIndex,
            int jumpOpcode
    ) {
        int count = 0;
        for (AbstractInsnNode node : AsmUtil.instructions(method)) {
            if (node instanceof VarInsnNode var
                    && var.getOpcode() == varOpcode
                    && var.var == varIndex
                    && nextExecutable(var) instanceof JumpInsnNode jump
                    && jump.getOpcode() == jumpOpcode) {
                count++;
            }
        }
        return count;
    }

    private static int countLabels(MethodNode method) {
        return (int) AsmUtil.instructions(method).stream()
                .filter(LabelNode.class::isInstance)
                .count();
    }

    private static FrameNode frameImmediatelyAfter(LabelNode label, PatchContext context) {
        for (AbstractInsnNode node = label.getNext(); node != null; node = node.getNext()) {
            if (node instanceof FrameNode frame) {
                return frame;
            }
            if (node.getOpcode() >= 0) {
                break;
            }
        }
        throw failure(context, "visible-name label has no immediate stack-map frame");
    }

    private static VarInsnNode requireVar(
            AbstractInsnNode node,
            int opcode,
            String description,
            PatchContext context
    ) {
        if (node instanceof VarInsnNode var && var.getOpcode() == opcode) {
            return var;
        }
        throw failure(context, "unexpected " + description);
    }

    private static JumpInsnNode requireJump(
            AbstractInsnNode node,
            int opcode,
            String description,
            PatchContext context
    ) {
        if (node instanceof JumpInsnNode jump && jump.getOpcode() == opcode) {
            return jump;
        }
        throw failure(context, "unexpected " + description);
    }

    private static TypeInsnNode requireType(
            AbstractInsnNode node,
            int opcode,
            String desc,
            String description,
            PatchContext context
    ) {
        if (node instanceof TypeInsnNode type
                && type.getOpcode() == opcode
                && desc.equals(type.desc)) {
            return type;
        }
        throw failure(context, "unexpected " + description);
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

    private static PatchException failure(PatchContext context, String detail) {
        return new PatchException("terrain-status-bar-visible-separator failed for "
                + context.classPath() + ": " + detail);
    }

    private record NameFlow(
            List<MethodInsnNode> getters,
            int pluginLocal,
            JumpInsnNode visibleJump,
            JumpInsnNode nullFallback,
            FrameNode visibleFrame,
            LabelNode visibleLabel,
            LabelNode loopContinue
    ) {
    }

    private record SeparatorFlow(
            LdcInsnNode comma,
            VarInsnNode gateLoad,
            JumpInsnNode gate,
            int separatorLocal,
            int currentComponentLocal,
            VarInsnNode commaAnchor,
            MethodInsnNode commaPositioning,
            AbstractInsnNode commaPositioningPop,
            VarInsnNode finalSource,
            VarInsnNode finalStore
    ) {
    }
}
