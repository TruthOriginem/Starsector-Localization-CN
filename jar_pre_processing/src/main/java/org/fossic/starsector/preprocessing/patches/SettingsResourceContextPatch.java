package org.fossic.starsector.preprocessing.patches;

import java.util.Set;
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
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;

/** Keep the SettingsAPI one-shot skip flag in the caller and clear it on every exit. */
public final class SettingsResourceContextPatch implements JarPatch {
    private static final String OWNER = "com/fs/starfarer/settings/StarfarerSettings$1";
    private static final String HELPER = "org/fossic/starsector/optimization/ResourceReadContext";

    @Override
    public String id() { return "settings-resource-context"; }
    @Override
    public PatchGroup group() { return PatchGroup.RESOURCE_CONTEXT; }
    @Override
    public String targetJar() { return JarWorkspace.OBF_JAR; }
    @Override
    public Set<String> targetClasses() { return Set.of(OWNER + ".class"); }

    @Override
    public PatchResult applyAndVerify(ClassNode node, PatchContext context) {
        if (AsmUtil.countMethodCall(node, HELPER, "skipMods", "(Z)V") != 0) {
            throw new PatchException("SettingsAPI 资源线程上下文已存在，拒绝重复 patch");
        }
        int writes = 0, exits = 0;
        for (MethodNode method : node.methods) {
            if (!("loadCSV".equals(method.name)
                    && "(Ljava/lang/String;Z)Lorg/json/JSONArray;".equals(method.desc))
                    && !("loadJSON".equals(method.name)
                    && "(Ljava/lang/String;Z)Lorg/json/JSONObject;".equals(method.desc))) {
                continue;
            }
            int methodWrites = 0, returns = 0;
            for (AbstractInsnNode insn : AsmUtil.instructions(method)) {
                if (insn instanceof FieldInsnNode field
                        && field.getOpcode() == Opcodes.PUTSTATIC
                        && "com/fs/util/C".equals(field.owner)
                        && "super".equals(field.name) && "Z".equals(field.desc)) {
                    method.instructions.set(field, new MethodInsnNode(Opcodes.INVOKESTATIC,
                            HELPER, "skipMods", "(Z)V", false));
                    methodWrites++;
                }
                if (insn.getOpcode() == Opcodes.ARETURN) {
                    method.instructions.insertBefore(insn, clear());
                    returns++;
                }
            }
            if (methodWrites != 1 || returns != 1 || !method.tryCatchBlocks.isEmpty()) {
                throw new PatchException("SettingsAPI 资源上下文结构变化: " + method.name
                        + ", writes=" + methodWrites + ", returns=" + returns);
            }
            LabelNode start = new LabelNode(), end = new LabelNode(), handler = new LabelNode();
            method.instructions.insert(start);
            method.instructions.add(end);
            method.instructions.add(handler);
            method.instructions.add(new FrameNode(Opcodes.F_FULL, 3,
                    new Object[] {OWNER, "java/lang/String", Opcodes.INTEGER},
                    1, new Object[] {"java/lang/Throwable"}));
            method.instructions.add(clear());
            method.instructions.add(new InsnNode(Opcodes.ATHROW));
            method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, null));
            writes += methodWrites;
            exits += 2;
        }
        int verified = AsmUtil.countMethodCall(node, HELPER, "skipMods", "(Z)V")
                + AsmUtil.countMethodCall(node, HELPER, "clearSkipMods", "()V");
        if (writes != 2 || exits != 4) {
            throw new PatchException("SettingsAPI skip-mods 入口数异常: " + writes);
        }
        return PatchResult.of(id(), context.classPath(), 6, writes + exits, verified,
                "thread-confined skip-mods with normal and exceptional cleanup");
    }

    private static MethodInsnNode clear() {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "clearSkipMods", "()V", false);
    }
}
