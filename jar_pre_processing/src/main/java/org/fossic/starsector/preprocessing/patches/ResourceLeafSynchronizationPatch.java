package org.fossic.starsector.preprocessing.patches;

import org.fossic.starsector.preprocessing.JarPatch;
import org.fossic.starsector.preprocessing.JarWorkspace;
import org.fossic.starsector.preprocessing.PatchContext;
import org.fossic.starsector.preprocessing.PatchException;
import org.fossic.starsector.preprocessing.PatchResult;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 允许纯资源 leaf 查询并发执行，不再争用 {@code com.fs.util.C} 的全局 monitor。
 *
 * <p>只处理完全由 path 与不可变 source descriptor 决定结果的四个方法：打开流、时间戳、
 * 取得 File、存在性查询。资源根变更方法、指定下一来源/跳过 mod 的一次性状态，以及消费
 * 这些状态的高层入口仍保持 synchronized。
 */
public final class ResourceLeafSynchronizationPatch implements JarPatch {
    private static final String TARGET_CLASS = "com/fs/util/C.class";
    private static final Set<String> TARGET_DESCRIPTORS = Set.of(
            "(Ljava/lang/String;Lcom/fs/util/C$Oo;)Ljava/io/InputStream;",
            "(Ljava/lang/String;Lcom/fs/util/C$Oo;)J",
            "(Ljava/lang/String;Lcom/fs/util/C$Oo;)Ljava/io/File;",
            "(Ljava/lang/String;Lcom/fs/util/C$Oo;)Z"
    );

    @Override
    public String id() {
        return "resource-leaf-remove-global-monitor";
    }

    @Override
    public String targetJar() {
        return JarWorkspace.COMMON_OBF_JAR;
    }

    @Override
    public Set<String> targetClasses() {
        return Set.of(TARGET_CLASS);
    }

    @Override
    public PatchResult applyAndVerify(ClassNode classNode, PatchContext context) {
        Set<String> found = new LinkedHashSet<>();
        int applied = 0;
        for (MethodNode method : classNode.methods) {
            if (!TARGET_DESCRIPTORS.contains(method.desc)
                    || (method.access & Opcodes.ACC_STATIC) != 0) {
                continue;
            }
            if (!found.add(method.desc)) {
                throw new PatchException(
                        "资源 leaf descriptor 重复: " + method.desc);
            }
            if ((method.access & Opcodes.ACC_SYNCHRONIZED) == 0) {
                throw new PatchException(
                        "资源 leaf 原方法不再是 synchronized: "
                                + method.name + method.desc);
            }
            if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                throw new PatchException(
                        "资源 leaf 意外成为 abstract/native: "
                                + method.name + method.desc);
            }
            method.access &= ~Opcodes.ACC_SYNCHRONIZED;
            applied++;
        }

        Set<String> missing = new LinkedHashSet<>(TARGET_DESCRIPTORS);
        missing.removeAll(found);
        if (!missing.isEmpty()) {
            throw new PatchException("缺少资源 leaf descriptor: " + missing);
        }

        int verified = 0;
        for (MethodNode method : classNode.methods) {
            if (TARGET_DESCRIPTORS.contains(method.desc)
                    && (method.access & Opcodes.ACC_STATIC) == 0
                    && (method.access & Opcodes.ACC_SYNCHRONIZED) == 0) {
                verified++;
            }
        }
        return PatchResult.of(id(), context.classPath(), TARGET_DESCRIPTORS.size(),
                applied, verified,
                "remove ACC_SYNCHRONIZED from four pure resource leaf methods");
    }
}
