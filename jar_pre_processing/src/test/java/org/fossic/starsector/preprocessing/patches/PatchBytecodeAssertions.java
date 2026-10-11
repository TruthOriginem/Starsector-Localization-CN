package org.fossic.starsector.preprocessing.patches;

import org.fossic.starsector.preprocessing.BytecodeVerifier;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

/** Checks production serialization before a behavioral fixture can recompute frames or limits. */
final class PatchBytecodeAssertions {
    private PatchBytecodeAssertions() {}

    static ClassNode verify(ClassNode source) {
        ClassWriter writer = new ClassWriter(0);
        source.accept(writer);
        BytecodeVerifier.verify(writer.toByteArray());
        ClassNode node = new ClassNode();
        new ClassReader(writer.toByteArray()).accept(node, 0);
        return node;
    }
}
