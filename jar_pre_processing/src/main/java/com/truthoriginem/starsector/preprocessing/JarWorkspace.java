package com.truthoriginem.starsector.preprocessing;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

public final class JarWorkspace {
    public static final String API_JAR = "starfarer.api.jar";
    public static final String OBF_JAR = "starfarer_obf.jar";

    private final Path projectDir;
    private final Path gameDataDir;
    private final Path originalDir;
    private final Path localizationDir;
    private final Path workDir;
    private final Path vendorDecoupler;

    public JarWorkspace(Path projectDir) {
        this.projectDir = projectDir.toAbsolutePath().normalize();
        Path repoDir = this.projectDir.getParent();
        this.gameDataDir = repoDir.resolve("game data");
        this.originalDir = repoDir.resolve("original");
        this.localizationDir = repoDir.resolve("localization");
        this.workDir = this.projectDir.resolve("target").resolve("preprocess-work");
        this.vendorDecoupler = this.projectDir.resolve("vendor").resolve("jar-string-decoupler-1.0.0-all.jar");
    }

    public Path workDir() {
        return workDir;
    }

    public Path vendorDecoupler() {
        return vendorDecoupler;
    }

    public Path inputJar(String jarName) {
        return gameDataDir.resolve(jarName);
    }

    public Path stagingInput(String jarName) {
        return workDir.resolve("input").resolve(jarName);
    }

    public Path decoupledJar(String jarName) {
        return workDir.resolve("decoupled").resolve(jarName);
    }

    public Path patchedJar(String jarName) {
        return workDir.resolve("patched").resolve(jarName);
    }

    public Path decouplerReport(String jarName) {
        return workDir.resolve("reports").resolve(jarName + ".decoupler.json");
    }

    public Path preprocessReport() {
        return workDir.resolve("preprocess-report.json");
    }

    public void prepare() throws IOException {
        if (!Files.exists(vendorDecoupler)) {
            throw new PatchException("Missing vendored decoupler jar: " + vendorDecoupler);
        }
        deleteDirectory(workDir);
        Files.createDirectories(workDir.resolve("input"));
        Files.createDirectories(workDir.resolve("decoupled"));
        Files.createDirectories(workDir.resolve("patched"));
        Files.createDirectories(workDir.resolve("reports"));
        for (String jarName : jars()) {
            Path input = inputJar(jarName);
            if (!Files.exists(input)) {
                throw new PatchException("Missing input jar: " + input);
            }
            Files.copy(input, stagingInput(jarName), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public void writeOutputs() throws IOException {
        for (String jarName : jars()) {
            byte[] bytes = Files.readAllBytes(decoupledJar(jarName));
            Path originalTarget = originalDir.resolve(jarName);
            Path localizationTarget = localizationDir.resolve(jarName);
            atomicWrite(originalTarget, bytes);
            atomicWrite(localizationTarget, bytes);
            String originalHash = sha256(originalTarget);
            String localizationHash = sha256(localizationTarget);
            if (!originalHash.equals(localizationHash)) {
                throw new PatchException("Output hash mismatch for " + jarName + ": original="
                        + originalHash + ", localization=" + localizationHash);
            }
        }
    }

    public Map<String, String> inputHashes() throws IOException {
        Map<String, String> hashes = new LinkedHashMap<>();
        for (String jarName : jars()) {
            hashes.put(jarName, sha256(inputJar(jarName)));
        }
        return hashes;
    }

    public Map<String, String> outputHashes() throws IOException {
        Map<String, String> hashes = new LinkedHashMap<>();
        for (String jarName : jars()) {
            hashes.put("original/" + jarName, sha256(originalDir.resolve(jarName)));
            hashes.put("localization/" + jarName, sha256(localizationDir.resolve(jarName)));
        }
        return hashes;
    }

    public static String[] jars() {
        return new String[]{API_JAR, OBF_JAR};
    }

    public static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new PatchException("SHA-256 is not available", e);
        }
    }

    private static void atomicWrite(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(target.getParent());
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(temp, bytes);
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
