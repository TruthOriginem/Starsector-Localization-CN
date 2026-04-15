package com.truthoriginem.starsector.preprocessing.patches;

import com.truthoriginem.starsector.preprocessing.AsmUtil;
import com.truthoriginem.starsector.preprocessing.JarPatch;
import com.truthoriginem.starsector.preprocessing.JarWorkspace;
import com.truthoriginem.starsector.preprocessing.PatchContext;
import com.truthoriginem.starsector.preprocessing.PatchException;
import com.truthoriginem.starsector.preprocessing.PatchResult;
import org.objectweb.asm.tree.ClassNode;

import java.util.Set;

public final class FactionHostilityNoManualPatch implements JarPatch {
    private static final String TARGET_CLASS = "com/fs/starfarer/api/impl/campaign/intel/FactionHostilityIntel.class";

    @Override
    public String id() {
        return "faction-hostility-no-manual";
    }

    @Override
    public String targetJar() {
        return JarWorkspace.API_JAR;
    }

    @Override
    public Set<String> targetClasses() {
        return Set.of(TARGET_CLASS);
    }

    @Override
    public PatchResult applyAndVerify(ClassNode classNode, PatchContext context) {
        int hostilities = AsmUtil.countStringConstant(classNode, "Hostilities");
        int manualChinese = AsmUtil.countStringConstant(classNode, "敌对活动");
        if (hostilities <= 0 || manualChinese != 0) {
            throw new PatchException(id() + " failed for " + context.classPath()
                    + ": Hostilities=" + hostilities + ", 敌对活动=" + manualChinese);
        }
        return PatchResult.of(id(), context.classPath(), 0, 0, 0,
                "guard only: Hostilities remains for decoupler/ParaTranz, no manual Chinese replacement");
    }
}
