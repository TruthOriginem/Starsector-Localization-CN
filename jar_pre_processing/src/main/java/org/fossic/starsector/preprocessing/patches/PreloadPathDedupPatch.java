package org.fossic.starsector.preprocessing.patches;

import java.util.List;
import java.util.Set;
import org.fossic.starsector.preprocessing.AsmUtil;
import org.fossic.starsector.preprocessing.JarPatch;
import org.fossic.starsector.preprocessing.JarWorkspace;
import org.fossic.starsector.preprocessing.PatchContext;
import org.fossic.starsector.preprocessing.PatchException;
import org.fossic.starsector.preprocessing.PatchResult;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** 把已桥接的图片入队调用切换为精确路径唯一入队。 */
public final class PreloadPathDedupPatch implements JarPatch {
    private static final String TARGET = "com/fs/graphics/L.class";
    private static final String HELPER =
            "org/fossic/starsector/optimization/PreloadResultCoordinator";
    private static final String ENQUEUE_DESC =
            "(Ljava/util/List;Ljava/lang/String;)V";

    @Override
    public String id() {
        return "preload-image-path-dedup";
    }

    @Override
    public String targetJar() {
        return JarWorkspace.COMMON_OBF_JAR;
    }

    @Override
    public Set<String> targetClasses() {
        return Set.of(TARGET);
    }

    @Override
    public PatchResult applyAndVerify(
            ClassNode classNode, PatchContext context) {
        List<MethodInsnNode> imageCalls = helperCalls(
                classNode, "queueImage");
        int uniqueCalls = helperCalls(
                classNode, "queueImageUnique").size();
        int soundCalls = helperCalls(
                classNode, "queueSound").size();
        if (imageCalls.size() != 1
                || uniqueCalls != 0
                || soundCalls != 1) {
            throw new PatchException(
                    "预读路径去重 bridge 结构异常: image="
                            + imageCalls.size() + ", unique="
                            + uniqueCalls + ", sound=" + soundCalls);
        }

        imageCalls.get(0).name = "queueImageUnique";

        int verified = helperCalls(
                classNode, "queueImageUnique").size();
        if (verified != 1
                || !helperCalls(classNode, "queueImage").isEmpty()
                || helperCalls(classNode, "queueSound").size() != 1) {
            throw new PatchException(
                    "预读路径去重 bridge 验证失败: " + verified);
        }
        return PatchResult.of(
                id(), context.classPath(), 1, 1, verified,
                "deduplicate exact image paths within one preload cycle");
    }

    private static List<MethodInsnNode> helperCalls(
            ClassNode classNode, String name) {
        return classNode.methods.stream()
                .flatMap(method -> AsmUtil.instructions(method).stream())
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .filter(call -> HELPER.equals(call.owner))
                .filter(call -> name.equals(call.name))
                .filter(call -> ENQUEUE_DESC.equals(call.desc))
                .toList();
    }
}
