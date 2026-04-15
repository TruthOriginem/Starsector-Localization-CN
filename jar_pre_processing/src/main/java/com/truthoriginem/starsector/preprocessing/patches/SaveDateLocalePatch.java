package com.truthoriginem.starsector.preprocessing.patches;

import com.truthoriginem.starsector.preprocessing.AsmUtil;
import com.truthoriginem.starsector.preprocessing.JarPatch;
import com.truthoriginem.starsector.preprocessing.JarWorkspace;
import com.truthoriginem.starsector.preprocessing.PatchContext;
import com.truthoriginem.starsector.preprocessing.PatchResult;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Set;

public final class SaveDateLocalePatch implements JarPatch {
    private static final String TARGET_CLASS = "com/fs/starfarer/campaign/save/LoadGameDialog$o.class";
    private static final String FROM_FORMAT = "EEE, MMM d, yyyy, hh:mm a";
    private static final String TO_FORMAT = "yyyy年M月d日 HH:mm:ss";

    @Override
    public String id() {
        return "save-date-locale";
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
        int applied = AsmUtil.replaceStringConstant(classNode, FROM_FORMAT, TO_FORMAT);
        for (MethodNode method : classNode.methods) {
            for (AbstractInsnNode node : AsmUtil.instructions(method)) {
                if (node instanceof FieldInsnNode field
                        && "java/util/Locale".equals(field.owner)
                        && "ENGLISH".equals(field.name)
                        && "Ljava/util/Locale;".equals(field.desc)) {
                    field.name = "CHINESE";
                    applied++;
                }
            }
        }
        int verified = 0;
        if (AsmUtil.countStringConstant(classNode, TO_FORMAT) == 1
                && AsmUtil.countStringConstant(classNode, FROM_FORMAT) == 0) {
            verified++;
        }
        if (countLocaleField(classNode, "CHINESE") == 1 && countLocaleField(classNode, "ENGLISH") == 0) {
            verified++;
        }
        return PatchResult.of(id(), context.classPath(), 2, applied, verified,
                "save date format and Locale.CHINESE");
    }

    private static int countLocaleField(ClassNode classNode, String name) {
        int count = 0;
        for (MethodNode method : classNode.methods) {
            for (AbstractInsnNode node : AsmUtil.instructions(method)) {
                if (node instanceof FieldInsnNode field
                        && "java/util/Locale".equals(field.owner)
                        && name.equals(field.name)
                        && "Ljava/util/Locale;".equals(field.desc)) {
                    count++;
                }
            }
        }
        return count;
    }
}
