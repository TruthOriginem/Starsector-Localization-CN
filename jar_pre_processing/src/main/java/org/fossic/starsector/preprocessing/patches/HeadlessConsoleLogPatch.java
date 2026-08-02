package org.fossic.starsector.preprocessing.patches;

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
import org.objectweb.asm.tree.MethodNode;

/** 在 {@code CombatMain.main} 入口桥接一次 GUI 控制台日志配置。 */
public final class HeadlessConsoleLogPatch implements JarPatch {
    private static final String TARGET_CLASS =
            "com/fs/starfarer/combat/CombatMain.class";
    private static final String HELPER =
            "org/fossic/starsector/optimization/StartupLogConfigurator";
    private static final String METHOD = "configure";
    private static final String DESCRIPTOR = "()V";

    @Override
    public String id() {
        return "headless-console-log-detach";
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
        List<MethodNode> targets = classNode.methods.stream()
                .filter(method -> "main".equals(method.name))
                .filter(method -> "([Ljava/lang/String;)V"
                        .equals(method.desc))
                .filter(method -> (method.access & Opcodes.ACC_PUBLIC) != 0)
                .filter(method -> (method.access & Opcodes.ACC_STATIC) != 0)
                .toList();
        int existing = AsmUtil.countMethodCall(
                classNode, HELPER, METHOD, DESCRIPTOR);
        if (targets.size() != 1 || existing != 0) {
            throw new PatchException(
                    "GUI console log 入口结构异常: main="
                            + targets.size() + ", helper=" + existing);
        }

        targets.get(0).instructions.insert(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                HELPER,
                METHOD,
                DESCRIPTOR,
                false));
        int verified = AsmUtil.countMethodCall(
                classNode, HELPER, METHOD, DESCRIPTOR);
        if (verified != 1) {
            throw new PatchException(
                    "GUI console log bridge 验证失败: " + verified);
        }
        return PatchResult.of(
                id(), context.classPath(), 1, 1, 1,
                "detach named console appender only for GUI launches");
    }
}
