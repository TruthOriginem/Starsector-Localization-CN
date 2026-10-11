package org.fossic.starsector.preprocessing;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;

final class BytecodeVerifierTest {
    @TempDir Path temp;

    private static byte[] branch(int version, boolean frame, int stack, boolean wide) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(version, Opcodes.ACC_PUBLIC, "test/Bytecode", null, "java/lang/Object", null);
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "test", "()V", null, null);
        method.visitCode();
        Label end = new Label();
        method.visitInsn(Opcodes.ICONST_0);
        method.visitJumpInsn(Opcodes.IFEQ, end);
        method.visitInsn(Opcodes.RETURN);
        method.visitLabel(end);
        if (frame) method.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        if (wide) {
            method.visitInsn(Opcodes.LCONST_0);
            method.visitInsn(Opcodes.POP2);
        }
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(stack, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    @Test void acceptsFramesAndLegacyVersions() {
        assertDoesNotThrow(() -> BytecodeVerifier.verify(branch(Opcodes.V17, true, 1, false)));
        assertDoesNotThrow(() -> BytecodeVerifier.verify(branch(Opcodes.V1_5, false, 1, false)));
        assertDoesNotThrow(() -> BytecodeVerifier.verify(branch(Opcodes.V1_6, false, 1, false)));
    }

    @Test void rejectsMissingTargetAndBothFormsOfStackUnderallocation() {
        assertTrue(assertThrows(PatchException.class,
                () -> BytecodeVerifier.verify(branch(Opcodes.V17, false, 1, false))).getMessage().contains("missing target frame"));
        assertThrows(PatchException.class, () -> BytecodeVerifier.verify(branch(Opcodes.V17, true, 0, false)));
        assertThrows(PatchException.class, () -> BytecodeVerifier.verify(branch(Opcodes.V17, true, 1, true)));
        assertDoesNotThrow(() -> BytecodeVerifier.verify(branch(Opcodes.V17, true, 2, true)));
    }

    @Test void checksTargetsEvenWhenOtherFramesExist() {
        var node = new org.objectweb.asm.tree.ClassNode();
        new ClassReader(branch(Opcodes.V17, true, 1, false)).accept(node, 0);
        var method = node.methods.get(0);
        var original = method.instructions.getFirst();
        var continuation = new org.objectweb.asm.tree.LabelNode();
        var prefix = new org.objectweb.asm.tree.InsnList();
        prefix.add(new org.objectweb.asm.tree.InsnNode(Opcodes.ICONST_0));
        prefix.add(new org.objectweb.asm.tree.JumpInsnNode(Opcodes.IFEQ, continuation));
        prefix.add(new org.objectweb.asm.tree.InsnNode(Opcodes.RETURN));
        prefix.add(continuation);
        method.instructions.insertBefore(original, prefix);
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        assertThrows(PatchException.class, () -> BytecodeVerifier.verify(writer.toByteArray()));
    }

    @Test void checksSwitchHandlersAndUninitializedReferences() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/Bad", null, "java/lang/Object", null);
        var m = writer.visitMethod(Opcodes.ACC_STATIC, "test", "()V", null, null);
        m.visitCode();
        Label start = new Label(), end = new Label(), handler = new Label();
        m.visitLabel(start);
        m.visitInsn(Opcodes.ICONST_0);
        m.visitLookupSwitchInsn(end, new int[]{1}, new Label[]{end});
        m.visitLabel(end);
        m.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        m.visitInsn(Opcodes.RETURN);
        m.visitLabel(handler);
        m.visitFrame(Opcodes.F_FULL, 0, null, 1, new Object[]{start});
        m.visitInsn(Opcodes.ATHROW);
        m.visitTryCatchBlock(start, end, handler, null);
        m.visitMaxs(1, 0);
        m.visitEnd();
        writer.visitEnd();
        assertTrue(assertThrows(PatchException.class, () -> BytecodeVerifier.verify(writer.toByteArray()))
                .getMessage().contains("does not reference NEW"));
    }

    @Test void usesImplicitEntryFrameAndDoesNotRejectObfuscatedNames() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/Names", null, "java/lang/Object", null);
        var m = writer.visitMethod(Opcodes.ACC_STATIC, "int.super", "()V", null, null);
        m.visitCode();
        Label start = new Label();
        m.visitLabel(start);
        m.visitInsn(Opcodes.ICONST_0);
        m.visitJumpInsn(Opcodes.IFNE, start);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(1, 0);
        m.visitEnd();
        writer.visitEnd();
        assertDoesNotThrow(() -> BytecodeVerifier.verify(writer.toByteArray()));
    }

    @Test void finalJarFailurePreventsEveryOutputWrite() throws Exception {
        JarWorkspace workspace = new JarWorkspace(temp.resolve("jar_pre_processing"));
        for (String jar : JarWorkspace.allJars()) {
            Path path = workspace.finalJar(jar);
            Files.createDirectories(path.getParent());
            try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
                zip.putNextEntry(new ZipEntry("test/Bytecode.class"));
                zip.write(branch(Opcodes.V17, !jar.equals(JarWorkspace.SOUND_OBF_JAR), 1, false));
                zip.closeEntry();
            }
            for (String output : List.of("original", "localization")) {
                Files.createDirectories(temp.resolve(output));
                Files.write(temp.resolve(output).resolve(jar), new byte[]{42});
            }
        }
        PatchException failure = assertThrows(PatchException.class,
                () -> JarPreProcessorMain.verifyAndWriteOutputs(workspace, List.of()));
        assertTrue(failure.getMessage().contains(JarWorkspace.SOUND_OBF_JAR));
        for (String jar : JarWorkspace.allJars())
            for (String output : List.of("original", "localization"))
                assertArrayEquals(new byte[]{42}, Files.readAllBytes(temp.resolve(output).resolve(jar)));
    }
}
