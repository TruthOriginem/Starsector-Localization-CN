package org.fossic.starsector.preprocessing.patches;

import org.fossic.starsector.preprocessing.AsmUtil;
import org.fossic.starsector.preprocessing.JarPatch;
import org.fossic.starsector.preprocessing.JarWorkspace;
import org.fossic.starsector.preprocessing.PatchContext;
import org.fossic.starsector.preprocessing.PatchResult;
import org.objectweb.asm.tree.ClassNode;

import java.util.Set;

public final class CombatDeploymentFontPatch implements JarPatch {
    private static final String TARGET_CLASS = "com/fs/starfarer/class/new/return.class";
    private static final String FROM = "graphics/fonts/victor21.fnt";
    private static final String TO = "graphics/fonts/victor16.fnt";

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
        // return.class has 2 occurrences of victor21.fnt; victor16.fnt has no prior occurrences
        int verified = AsmUtil.countStringConstant(classNode, TO) >= 2
                && AsmUtil.countStringConstant(classNode, FROM) == 0 ? 2 : 0;
        return PatchResult.of(id(), context.classPath(), 2, applied, verified,
                FROM + " -> " + TO);
    }
}
