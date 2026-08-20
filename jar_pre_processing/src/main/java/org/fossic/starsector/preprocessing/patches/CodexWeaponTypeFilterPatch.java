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

/**
 * Prevents special-type mod weapons from falling through every Codex weapon-type filter.
 *
 * <p>The original predicate explicitly rejects only BALLISTIC, MISSILE, and ENERGY base types.
 * A mod weapon whose {@code type} is directly HYBRID, COMPOSITE, SYNERGY, or UNIVERSAL therefore
 * reaches the terminal {@code true}, even when neither its type nor mount type was selected. The
 * existing mount-type checks remain untouched; this patch replaces only that permissive terminal
 * value with a branch-free guard for the four recognized special types.</p>
 */
public final class CodexWeaponTypeFilterPatch implements JarPatch {
    private static final String TARGET_CLASS =
            "com/fs/starfarer/api/impl/codex/CodexDataV2$21.class";
    private static final String TARGET_CLASS_NAME =
            "com/fs/starfarer/api/impl/codex/CodexDataV2$21";
    private static final String OUTER =
            "com/fs/starfarer/api/impl/codex/CodexDataV2";
    private static final String TARGET_METHOD_NAME = "matchesTags";
    private static final String TARGET_METHOD_DESC = "(Ljava/util/Set;)Z";
    private static final String SPEC_FIELD = "val$spec";
    private static final String SPEC = "com/fs/starfarer/api/loading/WeaponSpecAPI";
    private static final String SPEC_DESC = "L" + SPEC + ";";
    private static final String WEAPON_TYPE =
            "com/fs/starfarer/api/combat/WeaponAPI$WeaponType";
    private static final String WEAPON_TYPE_DESC = "L" + WEAPON_TYPE + ";";
    private static final String GET_TYPE_DESC = "()" + WEAPON_TYPE_DESC;
    private static final List<String> SPECIAL_TYPES =
            List.of("HYBRID", "COMPOSITE", "SYNERGY", "UNIVERSAL");

    @Override
    public String id() {
        return "codex-special-weapon-type-filter";
    }

    @Override
    public String targetJar() {
        return JarWorkspace.API_JAR;
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
        requireSpecField(classNode, context);
        MethodNode method = requireTargetMethod(classNode, context);
        requireOriginalShape(method, context);

        AbstractInsnNode lastReturn = lastExecutable(method);
        AbstractInsnNode fallback = previousExecutable(lastReturn);
        if (lastReturn == null || lastReturn.getOpcode() != Opcodes.IRETURN
                || fallback == null || fallback.getOpcode() != Opcodes.ICONST_1) {
            throw failure(context, "terminal permissive fallback has drifted");
        }

        method.instructions.insertBefore(fallback, buildSpecialTypeGuard());
        method.instructions.remove(fallback);
        method.maxStack = Math.max(method.maxStack, 4);

        verifyPatchedShape(method, context);
        return PatchResult.of(id(), context.classPath(), 1, 1, 1,
                "special weapon types now require their own selected type or mount tag");
    }

    private static InsnList buildSpecialTypeGuard() {
        InsnList code = new InsnList();

        // selectedSpecialType = OR(type == special && tags.contains(specialTag))
        code.add(new InsnNode(Opcodes.ICONST_0));
        for (String type : SPECIAL_TYPES) {
            addTypeEquals(code, type);
            code.add(new VarInsnNode(Opcodes.ALOAD, 1));
            code.add(new FieldInsnNode(
                    Opcodes.GETSTATIC, OUTER, type, "Ljava/lang/String;"));
            code.add(new MethodInsnNode(
                    Opcodes.INVOKEINTERFACE,
                    "java/util/Set",
                    "contains",
                    "(Ljava/lang/Object;)Z",
                    true
            ));
            code.add(new InsnNode(Opcodes.IAND));
            code.add(new InsnNode(Opcodes.IOR));
        }

        // Preserve the original permissive result for ordinary and unknown base types, while a
        // recognized special type must have matched one of the four tags above.
        code.add(new InsnNode(Opcodes.ICONST_0));
        for (String type : SPECIAL_TYPES) {
            addTypeEquals(code, type);
            code.add(new InsnNode(Opcodes.IOR));
        }
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new InsnNode(Opcodes.IXOR));
        code.add(new InsnNode(Opcodes.IOR));
        return code;
    }

    private static void addTypeEquals(InsnList code, String type) {
        code.add(new FieldInsnNode(Opcodes.GETSTATIC, WEAPON_TYPE, type, WEAPON_TYPE_DESC));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, TARGET_CLASS_NAME, SPEC_FIELD, SPEC_DESC));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE, SPEC, "getType", GET_TYPE_DESC, true));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/Object",
                "equals",
                "(Ljava/lang/Object;)Z",
                false
        ));
    }

    private static void requireSpecField(ClassNode classNode, PatchContext context) {
        long fields = classNode.fields.stream()
                .filter(field -> SPEC_FIELD.equals(field.name) && SPEC_DESC.equals(field.desc))
                .count();
        if (fields != 1) {
            throw failure(context, "expected one captured WeaponSpecAPI field, found " + fields);
        }
    }

    private static MethodNode requireTargetMethod(ClassNode classNode, PatchContext context) {
        List<MethodNode> methods = classNode.methods.stream()
                .filter(method -> TARGET_METHOD_NAME.equals(method.name)
                        && TARGET_METHOD_DESC.equals(method.desc))
                .toList();
        if (methods.size() != 1) {
            throw failure(context, "expected one matchesTags(Set) method, found " + methods.size());
        }
        return methods.get(0);
    }

    private static void requireOriginalShape(MethodNode method, PatchContext context) {
        long mountTypeCalls = countInterfaceCalls(method, "getMountType", GET_TYPE_DESC);
        long baseTypeCalls = countInterfaceCalls(method, "getType", GET_TYPE_DESC);
        if (mountTypeCalls != 4 || baseTypeCalls != 3) {
            throw failure(context, "weapon type checks have drifted: mount=" + mountTypeCalls
                    + ", base=" + baseTypeCalls);
        }
        for (String type : SPECIAL_TYPES) {
            int enumReads = countFieldReads(method, WEAPON_TYPE, type);
            int tagReads = countFieldReads(method, OUTER, type);
            if (enumReads != 1 || tagReads != 1) {
                throw failure(context, type + " checks have drifted: enum=" + enumReads
                        + ", tag=" + tagReads);
            }
        }
    }

    private static void verifyPatchedShape(MethodNode method, PatchContext context) {
        if (countObjectEqualsCalls(method) != 8) {
            throw failure(context, "expected eight branch-free special-type comparisons");
        }
        for (String type : SPECIAL_TYPES) {
            int enumReads = countFieldReads(method, WEAPON_TYPE, type);
            int tagReads = countFieldReads(method, OUTER, type);
            if (enumReads != 3 || tagReads != 2) {
                throw failure(context, type + " verification failed: enum=" + enumReads
                        + ", tag=" + tagReads);
            }
        }
        AbstractInsnNode lastReturn = lastExecutable(method);
        if (lastReturn == null || lastReturn.getOpcode() != Opcodes.IRETURN
                || previousExecutable(lastReturn).getOpcode() != Opcodes.IOR) {
            throw failure(context, "terminal guard verification failed");
        }
    }

    private static long countInterfaceCalls(MethodNode method, String name, String desc) {
        return AsmUtil.instructions(method).stream()
                .filter(node -> node instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKEINTERFACE
                        && SPEC.equals(call.owner)
                        && name.equals(call.name)
                        && desc.equals(call.desc))
                .count();
    }

    private static int countObjectEqualsCalls(MethodNode method) {
        return (int) AsmUtil.instructions(method).stream()
                .filter(node -> node instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && "java/lang/Object".equals(call.owner)
                        && "equals".equals(call.name)
                        && "(Ljava/lang/Object;)Z".equals(call.desc))
                .count();
    }

    private static int countFieldReads(MethodNode method, String owner, String name) {
        return (int) AsmUtil.instructions(method).stream()
                .filter(node -> node instanceof FieldInsnNode field
                        && field.getOpcode() == Opcodes.GETSTATIC
                        && owner.equals(field.owner)
                        && name.equals(field.name))
                .count();
    }

    private static AbstractInsnNode lastExecutable(MethodNode method) {
        AbstractInsnNode current = method.instructions.getLast();
        while (current != null && current.getOpcode() < 0) current = current.getPrevious();
        return current;
    }

    private static AbstractInsnNode previousExecutable(AbstractInsnNode node) {
        AbstractInsnNode current = node == null ? null : node.getPrevious();
        while (current != null && current.getOpcode() < 0) current = current.getPrevious();
        return current;
    }

    private static PatchException failure(PatchContext context, String detail) {
        return new PatchException("codex-special-weapon-type-filter failed for "
                + context.classPath() + ": " + detail);
    }
}
