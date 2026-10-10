package org.fossic.starsector.preprocessing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

class DecouplerRegressionTest {
    @TempDir Path temp;

    @Test
    void verifierRejectsInvalidUninitializedEvenWithoutRewritesAndPreservesOutput() throws Exception {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(V17, ACC_PUBLIC, "FrameDemo", null, "java/lang/Object", null);
        MethodVisitor m = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "bad", "()V", null, null);
        m.visitCode();
        Label invalid = new Label(), target = new Label();
        m.visitLabel(invalid); m.visitInsn(NOP);
        m.visitInsn(ACONST_NULL); m.visitJumpInsn(GOTO, target);
        m.visitLabel(target); m.visitFrame(F_FULL, 0, new Object[0], 1, new Object[]{invalid});
        m.visitInsn(POP); m.visitInsn(RETURN); m.visitMaxs(1, 0); m.visitEnd(); writer.visitEnd();
        byte[] bytes = writer.toByteArray();
        Path source = temp.resolve("bad.jar"), output = temp.resolve("existing.jar");
        writeJar(source, bytes); Files.write(output, new byte[]{4, 5, 6});
        assertThrows(PatchException.class, () -> runDecoupler(source, output));
        assertArrayEquals(new byte[]{4, 5, 6}, Files.readAllBytes(output));
    }

    @Test
    void preservesUninitializedLocalsAndStackAcrossLdcAndSwitchBoundaries() throws Exception {
        for (int poolSize : new int[]{100, 253, 254, 400}) {
            for (int branch : new int[]{0, 1, 2}) {
                byte[] input = fixture(poolSize, branch);
                assertRuns(input);
                Path source = temp.resolve("input.jar"), output = temp.resolve("output.jar");
                writeJar(source, input);
                runDecoupler(source, output);
                try (ZipFile zip = new ZipFile(output.toFile())) {
                    byte[] actual = zip.getInputStream(zip.getEntry("FrameDemo.class")).readAllBytes();
                    assertArrayEquals(new byte[]{1, 2, 3}, zip.getInputStream(zip.getEntry("resource.bin")).readAllBytes());
                    assertEquals(new ClassReader(input).readShort(6), new ClassReader(actual).readShort(6));
                    // Existing constant-pool indices (including independent translation occurrences) stay stable.
                    int poolEnd = new ClassReader(input).header;
                    assertArrayEquals(Arrays.copyOfRange(input, 10, poolEnd), Arrays.copyOfRange(actual, 10, poolEnd));
                    ClassReader reader = new ClassReader(actual);
                    int independentStrings = 0;
                    for (int index = 1; index < reader.getItemCount(); index++) {
                        if (reader.readByte(reader.getItem(index) - 1) == 8
                                && "shared".equals(reader.readConst(index, new char[reader.getMaxStringLength()]))) {
                            independentStrings++;
                        }
                    }
                    assertEquals(5, independentStrings); // Original plus four independent LDC sites.
                    assertRuns(actual);
                }
            }
        }
    }

    private void runDecoupler(Path source, Path output) throws Exception {
        JarWorkspace actual = new JarWorkspace(Path.of(""));
        JarWorkspace isolated = new JarWorkspace(temp.resolve("project"));
        Files.createDirectories(isolated.vendorDecoupler().getParent());
        Files.copy(actual.vendorDecoupler(), isolated.vendorDecoupler(), StandardCopyOption.REPLACE_EXISTING);
        Files.createDirectories(isolated.workDir().resolve("reports"));
        new DecouplerRunner(isolated).run("fixture.jar", source, output);
    }

    static void writeJar(Path path, byte[] bytes) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("FrameDemo.class")); zip.write(bytes); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("resource.bin")); zip.write(new byte[]{1, 2, 3}); zip.closeEntry();
        }
    }

    static void assertRuns(byte[] bytes) throws Exception {
        Class<?> type = new ClassLoader() {
            Class<?> define() { return defineClass(null, bytes, 0, bytes.length); }
        }.define();
        for (int selector : new int[]{0, 1, 7}) {
            assertEquals("shared", type.getMethod("greet", int.class).invoke(null, selector).toString());
        }
    }

    static byte[] fixture(int poolSize, int branch) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        writer.visit(V17, ACC_PUBLIC, "FrameDemo", null, "java/lang/Object", null);
        writer.newConst("shared");
        MethodVisitor m = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "greet", "(I)Ljava/lang/StringBuilder;", null, null);
        m.visitCode();
        for (int i = 0; i < 2; i++) { m.visitLdcInsn("shared"); m.visitInsn(POP); }
        m.visitTypeInsn(NEW, "java/lang/StringBuilder"); m.visitInsn(DUP);
        m.visitVarInsn(ASTORE, 1); m.visitInsn(DUP);
        m.visitVarInsn(ILOAD, 0);
        Label first = new Label(), second = new Label(), end = new Label();
        if (branch == 0) m.visitJumpInsn(IFEQ, first);
        else if (branch == 1) m.visitTableSwitchInsn(0, 1, second, first, second);
        else m.visitLookupSwitchInsn(second, new int[]{0, 7}, new Label[]{first, second});
        m.visitLabel(second); m.visitLdcInsn("shared"); m.visitJumpInsn(GOTO, end);
        m.visitLabel(first); m.visitLdcInsn("shared"); m.visitLabel(end);
        m.visitMethodInsn(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false);
        m.visitInsn(ARETURN); m.visitMaxs(0, 0); m.visitEnd(); writer.visitEnd();
        int count = new ClassReader(writer.toByteArray()).getItemCount();
        for (int i = count; i < poolSize; i++) writer.newUTF8("padding-" + i);
        assertEquals(poolSize, new ClassReader(writer.toByteArray()).getItemCount());
        return writer.toByteArray();
    }
}
