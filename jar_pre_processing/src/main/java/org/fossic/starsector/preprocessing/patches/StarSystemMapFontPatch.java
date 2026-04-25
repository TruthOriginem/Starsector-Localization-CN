package org.fossic.starsector.preprocessing.patches;

import org.fossic.starsector.preprocessing.AsmUtil;
import org.fossic.starsector.preprocessing.JarPatch;
import org.fossic.starsector.preprocessing.JarWorkspace;
import org.fossic.starsector.preprocessing.PatchContext;
import org.fossic.starsector.preprocessing.PatchResult;
import org.objectweb.asm.tree.ClassNode;

import java.util.Set;

public final class StarSystemMapFontPatch implements JarPatch {
    private static final String TARGET_CLASS = "com/fs/starfarer/coreui/A/ooOO.class";
    // Big font: victor14 -> victor16. Must be done BEFORE the small-font replacement,
    // otherwise the newly-placed victor14 would be re-replaced in the second pass.
    private static final String BIG_FROM  = "graphics/fonts/victor14.fnt";
    private static final String BIG_TO    = "graphics/fonts/victor16.fnt";
    // Small font: victor10 -> victor14
    private static final String SMALL_FROM = "graphics/fonts/victor10.fnt";
    private static final String SMALL_TO   = "graphics/fonts/victor14.fnt";

    @Override
    public String id() {
        return "star-system-map-font";
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
        int applied = AsmUtil.replaceStringConstant(classNode, BIG_FROM, BIG_TO)
                    + AsmUtil.replaceStringConstant(classNode, SMALL_FROM, SMALL_TO);
        // After both replacements: victor10(0), victor14(1), victor16(1).
        // BIG_FROM and SMALL_TO are the same literal, so verify the final state.
        int verified = AsmUtil.countStringConstant(classNode, BIG_TO)   == 1
                    && AsmUtil.countStringConstant(classNode, SMALL_TO)  == 1
                    && AsmUtil.countStringConstant(classNode, SMALL_FROM) == 0 ? 2 : 0;
        return PatchResult.of(id(), context.classPath(), 2, applied, verified,
                BIG_FROM + " -> " + BIG_TO + ", " + SMALL_FROM + " -> " + SMALL_TO);
    }
}
