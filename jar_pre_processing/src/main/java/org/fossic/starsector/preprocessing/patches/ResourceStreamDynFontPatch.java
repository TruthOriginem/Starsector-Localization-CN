package org.fossic.starsector.preprocessing.patches;

import org.fossic.starsector.preprocessing.AsmUtil;
import org.fossic.starsector.preprocessing.JarPatch;
import org.fossic.starsector.preprocessing.JarWorkspace;
import org.fossic.starsector.preprocessing.PatchContext;
import org.fossic.starsector.preprocessing.PatchException;
import org.fossic.starsector.preprocessing.PatchResult;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.List;
import java.util.Set;

/**
 * 动态字体：向游戏资源加载器注入字体资源流拦截钩子。
 *
 * <p>目标类 {@code com.fs.util.C}（ResourceLoader，位于 {@code fs.common_obf.jar}）的
 * {@code openStream(String)} 方法（0.98a-RC8 混淆名 {@code Ô00000}，为规避非 ASCII
 * 混淆名的编码问题，按<b>方法签名</b> {@code (Ljava/lang/String;)Ljava/io/InputStream;}
 * 匹配——已实测该签名在类中唯一，patch 强校验唯一性）。方法体开头插入：
 *
 * <pre>
 * InputStream r = DynFontOverrides.openStream(path);
 * if (r != null) return r;
 * // 原逻辑继续
 * </pre>
 *
 * <p>命中我们生成的字体产物（11 套 .fnt 与图集 PNG）时直接供流，未命中零改动。
 * 被调用的 {@code org.fossic.starsector.dynfont.*} 运行时类注入在
 * {@code starfarer_obf.jar}（与本 jar 同属游戏固定 classpath，跨 jar 可见）。
 *
 * <p>注入净栈变化 0、峰值栈需求 2。管线使用 {@code ClassWriter(0)}，因此 patch
 * 会显式为新增分支目标写入 StackMapTable 帧，使产物在启用 JVM verifier 时同样合法。
 */
public final class ResourceStreamDynFontPatch implements JarPatch {
    private static final String TARGET_CLASS = "com/fs/util/C.class";
    private static final String TARGET_DESC = "(Ljava/lang/String;)Ljava/io/InputStream;";
    private static final String HOOK_OWNER = "org/fossic/starsector/dynfont/DynFontOverrides";
    private static final String HOOK_METHOD = "openStream";

    @Override
    public String id() {
        return "resource-stream-dynfont-hook";
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
        List<MethodNode> matches = classNode.methods.stream()
                .filter(m -> TARGET_DESC.equals(m.desc) && (m.access & Opcodes.ACC_STATIC) == 0)
                .toList();
        if (matches.size() != 1) {
            throw new PatchException("openStream(String) 签名匹配数异常: " + matches.size()
                    + "（预期 1，混淆布局可能已变化，需重新核对 " + TARGET_CLASS + "）");
        }
        MethodNode method = matches.get(0);

        LabelNode fallThrough = new LabelNode();
        InsnList prelude = new InsnList();
        prelude.add(new VarInsnNode(Opcodes.ALOAD, 1));  // path 参数（实例方法 slot 1）
        prelude.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK_OWNER, HOOK_METHOD,
                TARGET_DESC, false));
        prelude.add(new InsnNode(Opcodes.DUP));
        prelude.add(new JumpInsnNode(Opcodes.IFNULL, fallThrough));
        prelude.add(new InsnNode(Opcodes.ARETURN));
        prelude.add(fallThrough);
        prelude.add(new FrameNode(
                Opcodes.F_FULL,
                2,
                new Object[] {"com/fs/util/C", "java/lang/String"},
                1,
                new Object[] {"java/io/InputStream"}));
        prelude.add(new InsnNode(Opcodes.POP));
        method.instructions.insert(prelude);
        method.maxStack = Math.max(method.maxStack, 2);

        int verified = AsmUtil.countMethodCall(classNode, HOOK_OWNER, HOOK_METHOD, TARGET_DESC);
        return PatchResult.of(id(), context.classPath(), 1, 1, verified,
                "inject DynFontOverrides.openStream at start of ResourceLoader.openStream(String)");
    }
}
