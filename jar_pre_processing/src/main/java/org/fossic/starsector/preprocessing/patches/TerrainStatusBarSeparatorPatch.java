package org.fossic.starsector.preprocessing.patches;

import org.fossic.starsector.preprocessing.AsmUtil;
import org.fossic.starsector.preprocessing.JarPatch;
import org.fossic.starsector.preprocessing.JarWorkspace;
import org.fossic.starsector.preprocessing.PatchContext;
import org.fossic.starsector.preprocessing.PatchException;
import org.fossic.starsector.preprocessing.PatchGroup;
import org.fossic.starsector.preprocessing.PatchResult;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.List;
import java.util.Set;

/**
 * Replaces the terrain status bar's visible ASCII comma with spacing.
 *
 * <p>The original method may leave its separator visible when a later terrain label is absent or
 * clipped. Rewriting that method's control flow proved unsafe for the game's verification-disabled
 * Zulu 17 C1 compiler. This patch therefore changes only the separator constant: multiple visible
 * terrain names retain spacing, while a trailing separator becomes invisible. Bytecode structure,
 * locals, jumps, and stack-map frames remain byte-for-byte equivalent.</p>
 */
public final class TerrainStatusBarSeparatorPatch implements JarPatch {
    private static final String TARGET_CLASS = "com/fs/starfarer/ui/newui/public.class";
    private static final String TARGET_CLASS_NAME = "com/fs/starfarer/ui/newui/public";
    private static final String TARGET_METHOD_NAME = "recreate";
    private static final String TARGET_METHOD_DESC = "()V";
    private static final String TEXT_COMPONENT = "com/fs/starfarer/ui/d";
    private static final String PLUGIN =
            "com/fs/starfarer/api/campaign/CampaignTerrainPlugin";
    private static final String NAME_DESC = "()Ljava/lang/String;";

    @Override
    public String id() {
        return "terrain-status-bar-visible-separator";
    }

    @Override
    public PatchGroup group() {
        return PatchGroup.LOCALIZATION;
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

        List<LdcInsnNode> separators = AsmUtil.instructions(method).stream()
                .filter(node -> node instanceof LdcInsnNode ldc && ",".equals(ldc.cst))
                .map(LdcInsnNode.class::cast)
                .toList();
        if (separators.size() != 1) {
            throw failure(context, "expected one terrain separator literal, found "
                    + separators.size());
        }
        LdcInsnNode separator = separators.get(0);
        requireSeparatorConstruction(separator, context);

        separator.cst = " ";
        int verified = " ".equals(separator.cst) ? 1 : 0;
        return PatchResult.of(id(), context.classPath(), 1, 1, verified,
                "replace the status-bar comma with invisible trailing spacing; no code-shape changes");
    }

    private static MethodNode requireTargetMethod(ClassNode classNode, PatchContext context) {
        List<MethodNode> methods = classNode.methods.stream()
                .filter(method -> TARGET_METHOD_NAME.equals(method.name)
                        && TARGET_METHOD_DESC.equals(method.desc))
                .toList();
        if (methods.size() != 1) {
            throw failure(context, "expected one recreate()V method, found " + methods.size());
        }
        return methods.get(0);
    }

    private static void requireOriginalShape(MethodNode method, PatchContext context) {
        long getters = AsmUtil.instructions(method).stream()
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .filter(call -> call.getOpcode() == Opcodes.INVOKEINTERFACE
                        && PLUGIN.equals(call.owner)
                        && "getTerrainName".equals(call.name)
                        && NAME_DESC.equals(call.desc))
                .count();
        if (getters != 3) {
            throw failure(context, "expected three terrain-name reads, found " + getters);
        }
    }

    private static void requireSeparatorConstruction(
            LdcInsnNode separator,
            PatchContext context
    ) {
        AbstractInsnNode dup = previousExecutable(separator);
        AbstractInsnNode allocation = previousExecutable(dup);
        if (dup == null || dup.getOpcode() != Opcodes.DUP
                || !(allocation instanceof TypeInsnNode type)
                || type.getOpcode() != Opcodes.NEW
                || !TEXT_COMPONENT.equals(type.desc)) {
            throw failure(context, "terrain separator construction has drifted");
        }
    }

    private static AbstractInsnNode previousExecutable(AbstractInsnNode node) {
        AbstractInsnNode current = node == null ? null : node.getPrevious();
        while (current != null && current.getOpcode() < 0) current = current.getPrevious();
        return current;
    }

    private static PatchException failure(PatchContext context, String detail) {
        return new PatchException("terrain-status-bar-visible-separator failed for "
                + context.classPath() + ": " + detail);
    }
}
