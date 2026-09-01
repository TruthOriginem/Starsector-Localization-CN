package org.fossic.starsector.preprocessing.patches;

import java.util.ArrayList;
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
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

/** 在已启用 CU 去重的 ScriptStore loader 上增加逻辑源码索引。 */
public final class JaninoSourceIndexPatch implements JarPatch {
    private static final String TARGET_CLASS =
            "com/fs/starfarer/loading/scripts/ScriptStore.class";
    private static final String ORIGINAL =
            "org/fossic/starsector/optimization/"
                    + "DeduplicatingJavaSourceClassLoader";
    private static final String OPTIMIZED =
            "org/fossic/starsector/optimization/"
                    + "IndexedDeduplicatingJavaSourceClassLoader";
    private static final String CONSTRUCTOR =
            "(Ljava/lang/ClassLoader;"
                    + "Lorg/codehaus/janino/util/resource/ResourceFinder;"
                    + "Ljava/lang/String;)V";

    @Override
    public String id() {
        return "janino-source-index";
    }

    @Override
    public PatchGroup group() {
        return PatchGroup.JANINO_SOURCE_INDEX;
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
    public PatchResult applyAndVerify(
            ClassNode classNode, PatchContext context) {
        List<TypeInsnNode> allocations = new ArrayList<>();
        List<MethodInsnNode> constructors = new ArrayList<>();
        classNode.methods.forEach(method ->
                AsmUtil.instructions(method).forEach(instruction -> {
                    if (instruction instanceof TypeInsnNode type
                            && type.getOpcode() == Opcodes.NEW
                            && ORIGINAL.equals(type.desc)) {
                        allocations.add(type);
                    }
                    if (instruction instanceof MethodInsnNode call
                            && call.getOpcode() == Opcodes.INVOKESPECIAL
                            && ORIGINAL.equals(call.owner)
                            && "<init>".equals(call.name)
                            && CONSTRUCTOR.equals(call.desc)) {
                        constructors.add(call);
                    }
                }));

        int existingAllocations =
                JaninoCompilationUnitDedupPatch.countAllocations(
                        classNode, OPTIMIZED);
        int existingConstructors = AsmUtil.countMethodCall(
                classNode, OPTIMIZED, "<init>", CONSTRUCTOR);
        if (allocations.size() != 1
                || constructors.size() != 1
                || existingAllocations != 0
                || existingConstructors != 0) {
            throw new PatchException(
                    "Janino source index 前置结构异常: new="
                            + allocations.size()
                            + ", init=" + constructors.size()
                            + ", indexedNew=" + existingAllocations
                            + ", indexedInit=" + existingConstructors);
        }

        allocations.get(0).desc = OPTIMIZED;
        constructors.get(0).owner = OPTIMIZED;

        int originalAllocations =
                JaninoCompilationUnitDedupPatch.countAllocations(
                        classNode, ORIGINAL);
        int originalConstructors = AsmUtil.countMethodCall(
                classNode, ORIGINAL, "<init>", CONSTRUCTOR);
        int optimizedAllocations =
                JaninoCompilationUnitDedupPatch.countAllocations(
                        classNode, OPTIMIZED);
        int optimizedConstructors = AsmUtil.countMethodCall(
                classNode, OPTIMIZED, "<init>", CONSTRUCTOR);
        if (originalAllocations != 0
                || originalConstructors != 0
                || optimizedAllocations != 1
                || optimizedConstructors != 1) {
            throw new PatchException(
                    "Janino source index 替换验证失败: original="
                            + originalAllocations + "/" + originalConstructors
                            + ", indexed=" + optimizedAllocations
                            + "/" + optimizedConstructors);
        }

        return PatchResult.of(
                id(), context.classPath(), 1, 1, 1,
                "wrap the CU-deduplicating loader with a logical source index");
    }
}
