package org.fossic.starsector.preprocessing;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public final class JarRewriter {
    private final List<JarPatch> patches;

    public JarRewriter(List<JarPatch> patches) {
        this.patches = patches;
    }

    public List<PatchResult> rewrite(String jarName, Path input, Path output) throws IOException {
        Map<String, List<JarPatch>> patchesByClass = new LinkedHashMap<>();
        for (JarPatch patch : patches) {
            if (!jarName.equals(patch.targetJar())) {
                continue;
            }
            for (String classPath : patch.targetClasses()) {
                patchesByClass.computeIfAbsent(classPath, ignored -> new ArrayList<>()).add(patch);
            }
        }
        Set<String> seenTargets = new HashSet<>();
        List<PatchResult> results = new ArrayList<>();
        Files.createDirectories(output.getParent());
        try (ZipFile zipFile = new ZipFile(input.toFile());
             ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(output))) {
            for (ZipEntry entry : zipFile.stream().toList()) {
                byte[] bytes = zipFile.getInputStream(entry).readAllBytes();
                ZipEntry newEntry = new ZipEntry(entry.getName());
                newEntry.setTime(entry.getTime());
                out.putNextEntry(newEntry);
                List<JarPatch> classPatches = patchesByClass.get(entry.getName());
                if (classPatches != null) {
                    seenTargets.add(entry.getName());
                    bytes = patchClass(jarName, entry.getName(), bytes, classPatches, results);
                } else if (entry.getName().endsWith(".class")) {
                    verifyReadable(entry.getName(), bytes);
                }
                out.write(bytes);
                out.closeEntry();
            }
        }
        for (String target : patchesByClass.keySet()) {
            if (!seenTargets.contains(target)) {
                throw new PatchException("Target class not found in " + jarName + ": " + target);
            }
        }
        verifyJarReadable(output);
        return results;
    }

    private static byte[] patchClass(String jarName, String classPath, byte[] bytes, List<JarPatch> classPatches,
                                     List<PatchResult> results) {
        ClassNode classNode = new ClassNode();
        new ClassReader(bytes).accept(classNode, 0);
        for (JarPatch patch : classPatches) {
            PatchResult result = patch.applyAndVerify(classNode, new PatchContext(jarName, classPath));
            result.requireSuccess();
            results.add(result);
        }
        ClassWriter writer = new ClassWriter(0);
        classNode.accept(writer);
        byte[] patchedBytes = writer.toByteArray();
        verifyReadable(classPath, patchedBytes);
        return patchedBytes;
    }

    private static void verifyJarReadable(Path jar) throws IOException {
        try (ZipFile zipFile = new ZipFile(jar.toFile())) {
            for (ZipEntry entry : zipFile.stream().toList()) {
                if (entry.getName().endsWith(".class")) {
                    verifyReadable(entry.getName(), zipFile.getInputStream(entry).readAllBytes());
                }
            }
        }
    }

    private static void verifyReadable(String classPath, byte[] bytes) {
        try {
            new ClassReader(bytes).accept(new ClassNode(), ClassReader.SKIP_DEBUG);
        } catch (RuntimeException e) {
            throw new PatchException("ASM failed to read class " + classPath, e);
        }
    }
}
