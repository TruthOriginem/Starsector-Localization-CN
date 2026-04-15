package com.truthoriginem.starsector.preprocessing.patches;

import com.truthoriginem.starsector.preprocessing.AsmUtil;
import com.truthoriginem.starsector.preprocessing.JarPatch;
import com.truthoriginem.starsector.preprocessing.JarWorkspace;
import com.truthoriginem.starsector.preprocessing.PatchContext;
import com.truthoriginem.starsector.preprocessing.PatchResult;
import org.objectweb.asm.tree.ClassNode;

import java.util.Set;

public final class CombatDeploymentFontPatch implements JarPatch {
    private static final String TARGET_CLASS = "com/fs/starfarer/class/new/return.class";
    private static final String FROM = "graphics/fonts/victor21.fnt";
    private static final String TO = "graphics/fonts/victor14.fnt";

    @Override
    public String id() {
        return "combat-deployment-font";
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
        int applied = AsmUtil.replaceStringConstant(classNode, FROM, TO);
        int verified = AsmUtil.countStringConstant(classNode, TO) >= 2
                && AsmUtil.countStringConstant(classNode, FROM) == 0 ? 2 : 0;
        return PatchResult.of(id(), context.classPath(), 2, applied, verified,
                FROM + " -> " + TO);
    }
}
