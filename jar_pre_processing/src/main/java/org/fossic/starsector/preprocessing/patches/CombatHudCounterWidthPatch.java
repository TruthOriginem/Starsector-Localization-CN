package org.fossic.starsector.preprocessing.patches;

import java.util.List;
import java.util.Set;
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
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Widen combat HUD ammo/use counters while preserving their outer layout. */
public final class CombatHudCounterWidthPatch implements JarPatch {
    private static final String WEAPON_CLASS =
            "com/fs/starfarer/renderers/A/G";
    private static final String SYSTEM_CLASS =
            "com/fs/starfarer/class/new/B";
    private static final String WEAPON_DESC =
            "(Lcom/fs/starfarer/combat/systems/o00O;Ljava/awt/Color;"
                    + "Lcom/fs/graphics/A/F;)V";
    private static final String SYSTEM_DESC =
            "(Ljava/awt/Color;Lcom/fs/graphics/A/F;)V";
    private static final String SHORTEN_DESC =
            "(Ljava/lang/String;FLcom/fs/graphics/A/F;)Ljava/lang/String;";
    private static final String SET_SIZE_DESC =
            "(FF)Lcom/fs/starfarer/ui/OO0O;";
    private static final float ORIGINAL_COUNTER_WIDTH = 40.0f;

    @Override
    public String id() {
        return "combat-hud-counter-width";
    }

    @Override
    public PatchGroup group() {
        return PatchGroup.DYNFONT;
    }

    @Override
    public String targetJar() {
        return JarWorkspace.OBF_JAR;
    }

    @Override
    public Set<String> targetClasses() {
        return Set.of(WEAPON_CLASS + ".class", SYSTEM_CLASS + ".class");
    }

    @Override
    public PatchResult applyAndVerify(ClassNode classNode, PatchContext context) {
        Layout layout = layoutFor(classNode, context);
        MethodNode constructor = requireConstructor(
                classNode, layout.constructorDesc(), context);

        VarInsnNode shortenWidth = requireShortenWidth(constructor, context);
        VarInsnNode labelWidth = requireLabelWidth(
                constructor, shortenWidth.var, context);
        LdcInsnNode counterWidth = requireCounterWidth(constructor, context);
        requireZeroCounterOffset(counterWidth, context);

        insertSubtract(constructor, shortenWidth, layout.nameWidthReduction());
        insertSubtract(constructor, labelWidth, layout.nameWidthReduction());
        counterWidth.cst = layout.counterWidth();

        int verified = 0;
        if (countWidthReductions(constructor, shortenWidth.var,
                layout.nameWidthReduction()) == 2) {
            verified += 2;
        }
        if (findRightAlignedWidths(constructor, layout.counterWidth()).size() == 1
                && findRightAlignedWidths(
                        constructor, ORIGINAL_COUNTER_WIDTH).isEmpty()) {
            verified++;
        }
        requireZeroCounterOffset(
                findRightAlignedWidths(constructor, layout.counterWidth()).stream()
                        .findFirst()
                        .orElseThrow(() -> failure(context,
                                "patched counter width was not found")),
                context);

        return PatchResult.of(id(), context.classPath(), 3, 3, verified,
                layout.description());
    }

    private static Layout layoutFor(ClassNode classNode, PatchContext context) {
        return switch (classNode.name) {
            case WEAPON_CLASS -> new Layout(
                    WEAPON_DESC, 52.0f, 12.0f,
                    "weapon ammo width 40 -> 52; name width -12");
            case SYSTEM_CLASS -> new Layout(
                    SYSTEM_DESC, 45.0f, 5.0f,
                    "system use count width 40 -> 45; name width -5");
            default -> throw failure(context,
                    "unexpected target class " + classNode.name);
        };
    }

    private static MethodNode requireConstructor(
            ClassNode classNode, String desc, PatchContext context) {
        List<MethodNode> matches = classNode.methods.stream()
                .filter(method -> "<init>".equals(method.name)
                        && desc.equals(method.desc))
                .toList();
        if (matches.size() != 1) {
            throw failure(context, "expected one constructor " + desc
                    + ", found " + matches.size());
        }
        return matches.get(0);
    }

    private static VarInsnNode requireShortenWidth(
            MethodNode constructor, PatchContext context) {
        List<MethodInsnNode> calls = AsmUtil.instructions(constructor).stream()
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .filter(call -> "com/fs/starfarer/renderers/A/G".equals(call.owner)
                        && "shortenName".equals(call.name)
                        && SHORTEN_DESC.equals(call.desc))
                .toList();
        if (calls.size() != 1) {
            throw failure(context, "expected one shortenName call, found "
                    + calls.size());
        }
        AbstractInsnNode font = previousExecutable(calls.get(0));
        AbstractInsnNode width = previousExecutable(font);
        if (!(font instanceof VarInsnNode fontLoad)
                || fontLoad.getOpcode() != Opcodes.ALOAD
                || !(width instanceof VarInsnNode widthLoad)
                || widthLoad.getOpcode() != Opcodes.FLOAD) {
            throw failure(context, "shortenName width stack shape drifted");
        }
        return widthLoad;
    }

    private static VarInsnNode requireLabelWidth(
            MethodNode constructor, int widthVar, PatchContext context) {
        List<VarInsnNode> matches = AsmUtil.instructions(constructor).stream()
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .filter(call -> "inLMid".equals(call.name))
                .map(CombatHudCounterWidthPatch::previousSetSize)
                .filter(call -> call != null && SET_SIZE_DESC.equals(call.desc))
                .map(CombatHudCounterWidthPatch::widthLoadBeforeSetSize)
                .filter(load -> load != null && load.var == widthVar)
                .toList();
        if (matches.size() != 1) {
            throw failure(context, "expected one left label width load, found "
                    + matches.size());
        }
        return matches.get(0);
    }

    private static MethodInsnNode previousSetSize(MethodInsnNode inLMid) {
        for (AbstractInsnNode node = previousExecutable(inLMid);
             node != null;
             node = previousExecutable(node)) {
            if (node instanceof MethodInsnNode call) {
                return "setSize".equals(call.name) ? call : null;
            }
        }
        return null;
    }

    private static VarInsnNode widthLoadBeforeSetSize(MethodInsnNode setSize) {
        AbstractInsnNode heightField = previousExecutable(setSize);
        AbstractInsnNode heightOwner = previousExecutable(heightField);
        AbstractInsnNode width = previousExecutable(heightOwner);
        return width instanceof VarInsnNode load
                && load.getOpcode() == Opcodes.FLOAD ? load : null;
    }

    private static LdcInsnNode requireCounterWidth(
            MethodNode constructor, PatchContext context) {
        List<LdcInsnNode> matches = findRightAlignedWidths(
                constructor, ORIGINAL_COUNTER_WIDTH);
        if (matches.size() != 1) {
            throw failure(context, "expected one right-aligned 40px counter, found "
                    + matches.size());
        }
        return matches.get(0);
    }

    private static List<LdcInsnNode> findRightAlignedWidths(
            MethodNode method, float width) {
        return AsmUtil.instructions(method).stream()
                .filter(node -> AsmUtil.isFloatLdc(node, width))
                .map(LdcInsnNode.class::cast)
                .filter(CombatHudCounterWidthPatch::isRightAlignedWidth)
                .toList();
    }

    private static boolean isRightAlignedWidth(LdcInsnNode width) {
        boolean sawSetSize = false;
        int executable = 0;
        for (AbstractInsnNode node = width.getNext();
             node != null && executable < 12;
             node = node.getNext()) {
            if (node.getOpcode() < 0) continue;
            executable++;
            if (node instanceof MethodInsnNode call
                    && "setSize".equals(call.name)
                    && SET_SIZE_DESC.equals(call.desc)) {
                sawSetSize = true;
            } else if (node instanceof MethodInsnNode call
                    && "rightOfMid".equals(call.name)) {
                return sawSetSize;
            }
        }
        return false;
    }

    private static void requireZeroCounterOffset(
            LdcInsnNode width, PatchContext context) {
        MethodInsnNode rightOfMid = null;
        int executable = 0;
        for (AbstractInsnNode node = width.getNext();
             node != null && executable < 12;
             node = node.getNext()) {
            if (node.getOpcode() < 0) continue;
            executable++;
            if (node instanceof MethodInsnNode call
                    && "rightOfMid".equals(call.name)) {
                rightOfMid = call;
                break;
            }
        }
        AbstractInsnNode offset = previousExecutable(rightOfMid);
        if (rightOfMid == null
                || offset == null
                || offset.getOpcode() != Opcodes.FCONST_0) {
            throw failure(context, "counter right edge offset is no longer zero");
        }
    }

    private static void insertSubtract(
            MethodNode method, VarInsnNode widthLoad, float delta) {
        InsnList instructions = new InsnList();
        instructions.add(new LdcInsnNode(delta));
        instructions.add(new InsnNode(Opcodes.FSUB));
        method.instructions.insert(widthLoad, instructions);
    }

    private static int countWidthReductions(
            MethodNode method, int widthVar, float delta) {
        int count = 0;
        for (AbstractInsnNode node : method.instructions) {
            if (!(node instanceof VarInsnNode load)
                    || load.getOpcode() != Opcodes.FLOAD
                    || load.var != widthVar) continue;
            AbstractInsnNode value = nextExecutable(load);
            AbstractInsnNode subtract = nextExecutable(value);
            if (AsmUtil.isFloatLdc(value, delta)
                    && subtract != null
                    && subtract.getOpcode() == Opcodes.FSUB) {
                count++;
            }
        }
        return count;
    }

    private static AbstractInsnNode nextExecutable(AbstractInsnNode node) {
        for (AbstractInsnNode next = node.getNext(); next != null;
             next = next.getNext()) {
            if (next.getOpcode() >= 0) return next;
        }
        return null;
    }

    private static AbstractInsnNode previousExecutable(AbstractInsnNode node) {
        if (node == null) return null;
        for (AbstractInsnNode previous = node.getPrevious(); previous != null;
             previous = previous.getPrevious()) {
            if (previous.getOpcode() >= 0) return previous;
        }
        return null;
    }

    private static PatchException failure(PatchContext context, String detail) {
        return new PatchException("combat-hud-counter-width failed for "
                + context.classPath() + ": " + detail);
    }

    private record Layout(
            String constructorDesc,
            float counterWidth,
            float nameWidthReduction,
            String description) {
    }
}
