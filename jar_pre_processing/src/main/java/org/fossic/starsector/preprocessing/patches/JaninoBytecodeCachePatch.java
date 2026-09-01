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
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/** 把源码索引 loader 升级为整代 bytecode 缓存，并在 worker 成功 join 后发布。 */
public final class JaninoBytecodeCachePatch implements JarPatch {
    private static final String TARGET_CLASS =
            "com/fs/starfarer/loading/scripts/ScriptStore.class";
    private static final String ORIGINAL =
            "org/fossic/starsector/optimization/"
                    + "IndexedDeduplicatingJavaSourceClassLoader";
    private static final String OPTIMIZED =
            "org/fossic/starsector/optimization/"
                    + "CachingIndexedDeduplicatingJavaSourceClassLoader";
    private static final String CONSTRUCTOR =
            "(Ljava/lang/ClassLoader;"
                    + "Lorg/codehaus/janino/util/resource/ResourceFinder;"
                    + "Ljava/lang/String;)V";
    private static final String LOADER_DESCRIPTOR =
            "Lorg/codehaus/janino/JavaSourceClassLoader;";
    private static final String FAILURE_DESCRIPTOR =
            "Ljava/lang/Exception;";
    private static final String HOOK_OWNER =
            "org/fossic/starsector/optimization/JaninoBytecodeCacheHooks";
    private static final String HOOK_DESCRIPTOR =
            "(Lorg/codehaus/janino/JavaSourceClassLoader;"
                    + "Ljava/lang/Throwable;)V";

    @Override
    public String id() {
        return "janino-bytecode-cache";
    }

    @Override
    public PatchGroup group() {
        return PatchGroup.JANINO_BYTECODE_CACHE;
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
        List<JoinSite> joins = new ArrayList<>();
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
                    if (instruction instanceof MethodInsnNode call
                            && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                            && "java/lang/Thread".equals(call.owner)
                            && "join".equals(call.name)
                            && "()V".equals(call.desc)) {
                        joins.add(new JoinSite(method, call));
                    }
                }));

        List<FieldNode> loaderFields = staticFields(
                classNode, LOADER_DESCRIPTOR);
        List<FieldNode> failureFields = staticFields(
                classNode, FAILURE_DESCRIPTOR);
        int existingOptimized =
                JaninoCompilationUnitDedupPatch.countAllocations(
                        classNode, OPTIMIZED);
        int existingHooks = AsmUtil.countMethodCall(
                classNode, HOOK_OWNER, "finish", HOOK_DESCRIPTOR);
        if (allocations.size() != 1
                || constructors.size() != 1
                || joins.size() != 1
                || loaderFields.size() != 1
                || failureFields.size() != 1
                || existingOptimized != 0
                || existingHooks != 0) {
            throw new PatchException(
                    "Janino bytecode cache 前置结构异常: new="
                            + allocations.size()
                            + ", init=" + constructors.size()
                            + ", join=" + joins.size()
                            + ", loaderField=" + loaderFields.size()
                            + ", failureField=" + failureFields.size()
                            + ", cachedNew=" + existingOptimized
                            + ", hooks=" + existingHooks);
        }

        allocations.get(0).desc = OPTIMIZED;
        constructors.get(0).owner = OPTIMIZED;

        JoinSite site = joins.get(0);
        InsnList hook = new InsnList();
        hook.add(new FieldInsnNode(
                Opcodes.GETSTATIC,
                classNode.name,
                loaderFields.get(0).name,
                LOADER_DESCRIPTOR));
        hook.add(new FieldInsnNode(
                Opcodes.GETSTATIC,
                classNode.name,
                failureFields.get(0).name,
                FAILURE_DESCRIPTOR));
        hook.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                HOOK_OWNER,
                "finish",
                HOOK_DESCRIPTOR,
                false));
        site.method().instructions.insert(site.join(), hook);
        site.method().maxStack = Math.max(site.method().maxStack, 2);

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
        int hooks = AsmUtil.countMethodCall(
                classNode, HOOK_OWNER, "finish", HOOK_DESCRIPTOR);
        if (originalAllocations != 0
                || originalConstructors != 0
                || optimizedAllocations != 1
                || optimizedConstructors != 1
                || hooks != 1) {
            throw new PatchException(
                    "Janino bytecode cache 替换验证失败: original="
                            + originalAllocations + "/" + originalConstructors
                            + ", caching=" + optimizedAllocations + "/"
                            + optimizedConstructors + ", hooks=" + hooks);
        }

        return PatchResult.of(
                id(), context.classPath(), 2, 2, 2,
                "load a validated whole-generation Janino bytecode pack and "
                        + "publish it only after the script worker joins");
    }

    private static List<FieldNode> staticFields(
            ClassNode classNode, String descriptor) {
        return classNode.fields.stream()
                .filter(field -> (field.access & Opcodes.ACC_STATIC) != 0)
                .filter(field -> descriptor.equals(field.desc))
                .toList();
    }

    private record JoinSite(MethodNode method, MethodInsnNode join) {
    }
}
