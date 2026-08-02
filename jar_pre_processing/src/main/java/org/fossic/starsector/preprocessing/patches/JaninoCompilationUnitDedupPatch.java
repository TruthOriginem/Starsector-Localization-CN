package org.fossic.starsector.preprocessing.patches;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.fossic.starsector.preprocessing.AsmUtil;
import org.fossic.starsector.preprocessing.JarPatch;
import org.fossic.starsector.preprocessing.JarWorkspace;
import org.fossic.starsector.preprocessing.PatchContext;
import org.fossic.starsector.preprocessing.PatchException;
import org.fossic.starsector.preprocessing.PatchResult;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

/** 让 ScriptStore 的默认 Janino loader 使用官方 CU 去重兼容子类。 */
public final class JaninoCompilationUnitDedupPatch implements JarPatch {
    private static final String TARGET_CLASS =
            "com/fs/starfarer/loading/scripts/ScriptStore.class";
    private static final String ORIGINAL =
            "org/codehaus/janino/JavaSourceClassLoader";
    private static final String OPTIMIZED =
            "org/fossic/starsector/optimization/"
                    + "DeduplicatingJavaSourceClassLoader";
    private static final String CONSTRUCTOR =
            "(Ljava/lang/ClassLoader;"
                    + "Lorg/codehaus/janino/util/resource/ResourceFinder;"
                    + "Ljava/lang/String;)V";

    @Override
    public String id() {
        return "janino-compilation-unit-dedup";
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

        int existingAllocations = countAllocations(classNode, OPTIMIZED);
        int existingConstructors = AsmUtil.countMethodCall(
                classNode, OPTIMIZED, "<init>", CONSTRUCTOR);
        if (allocations.size() != 1
                || constructors.size() != 1
                || existingAllocations != 0
                || existingConstructors != 0) {
            throw new PatchException(
                    "Janino loader 工厂结构异常: new=" + allocations.size()
                            + ", init=" + constructors.size()
                            + ", optimizedNew=" + existingAllocations
                            + ", optimizedInit=" + existingConstructors);
        }

        allocations.get(0).desc = OPTIMIZED;
        constructors.get(0).owner = OPTIMIZED;

        int originalAllocations = countAllocations(classNode, ORIGINAL);
        int originalConstructors = AsmUtil.countMethodCall(
                classNode, ORIGINAL, "<init>", CONSTRUCTOR);
        int optimizedAllocations = countAllocations(classNode, OPTIMIZED);
        int optimizedConstructors = AsmUtil.countMethodCall(
                classNode, OPTIMIZED, "<init>", CONSTRUCTOR);
        if (originalAllocations != 0
                || originalConstructors != 0
                || optimizedAllocations != 1
                || optimizedConstructors != 1) {
            throw new PatchException(
                    "Janino loader 替换验证失败: original="
                            + originalAllocations + "/" + originalConstructors
                            + ", optimized=" + optimizedAllocations
                            + "/" + optimizedConstructors);
        }

        return PatchResult.of(
                id(), context.classPath(), 1, 1, 1,
                "replace the default Janino loader with CU-deduplicating subclass");
    }

    static int countAllocations(ClassNode classNode, String owner) {
        int count = 0;
        for (var method : classNode.methods) {
            for (var instruction : AsmUtil.instructions(method)) {
                if (instruction instanceof TypeInsnNode type
                        && type.getOpcode() == Opcodes.NEW
                        && owner.equals(type.desc)) {
                    count++;
                }
            }
        }
        return count;
    }
}
