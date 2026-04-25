package org.fossic.starsector.preprocessing;

import org.objectweb.asm.tree.ClassNode;

import java.util.Set;

public interface JarPatch {
    String id();

    String targetJar();

    Set<String> targetClasses();

    PatchResult applyAndVerify(ClassNode classNode, PatchContext context);
}
