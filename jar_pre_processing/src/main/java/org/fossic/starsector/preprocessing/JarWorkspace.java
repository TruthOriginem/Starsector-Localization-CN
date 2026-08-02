package org.fossic.starsector.preprocessing;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

public final class JarWorkspace {
    public static final String API_JAR = "starfarer.api.jar";
    public static final String OBF_JAR = "starfarer_obf.jar";
    /**
     * 仅 ASM 注入、不做字符串解耦/翻译的 jar：本分支的动态字体 hook 目标。
     *
     * <p>各变体汉化包都要分发它（其余分支产出的是原版副本），否则玩家从本分支
     * 换回其它变体时这个 jar 不会被覆盖，残留的 hook 找不到已被换走的运行时类，
     * 游戏启动即 NoClassDefFoundError。
     */
    public static final String COMMON_OBF_JAR = "fs.common_obf.jar";
    /** 仅 ASM 注入、不做字符串解耦/翻译的声音引擎 jar。 */
    public static final String SOUND_OBF_JAR = "fs.sound_obf.jar";

    private final Path projectDir;
    private final Path repoDir;
    private final Path gameDataDir;
    private final Path originalDir;
    private final Path localizationDir;
    private final Path workDir;
    private final Path vendorDecoupler;

    public JarWorkspace(Path projectDir) {
        this.projectDir = projectDir.toAbsolutePath().normalize();
        this.repoDir = this.projectDir.getParent();
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
        for (String jarName : allJars()) {
            Path input = inputJar(jarName);
            if (!Files.exists(input)) {
                throw new PatchException("Missing input jar: " + input);
            }
            Files.copy(input, stagingInput(jarName), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** jar 的最终产物：解耦集合取 decoupled，仅 ASM 注入集合取 patched。 */
    private Path finalJar(String jarName) {
        for (String decoupled : jars()) {
            if (decoupled.equals(jarName)) {
                return decoupledJar(jarName);
            }
        }
        return patchedJar(jarName);
    }

    public void writeOutputs() throws IOException {
        for (String jarName : allJars()) {
            byte[] bytes = Files.readAllBytes(finalJar(jarName));
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
        for (String jarName : allJars()) {
            hashes.put(jarName, sha256(inputJar(jarName)));
        }
        return hashes;
    }

    public Map<String, String> outputHashes() throws IOException {
        Map<String, String> hashes = new LinkedHashMap<>();
        for (String jarName : allJars()) {
            hashes.put("original/" + jarName, sha256(originalDir.resolve(jarName)));
            hashes.put("localization/" + jarName, sha256(localizationDir.resolve(jarName)));
        }
        return hashes;
    }

    /** 需要字符串解耦（含翻译流程）的 jar。 */
    public static String[] jars() {
        return new String[]{API_JAR, OBF_JAR};
    }

    /** 全部处理的 jar（解耦集合 + 仅 ASM 注入的引擎 jar）。 */
    public static String[] allJars() {
        return new String[]{
                API_JAR, OBF_JAR, COMMON_OBF_JAR, SOUND_OBF_JAR};
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
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(
                    dir,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException missing) {
            return;
        }

        // Windows junction 在 NIO 中可同时表现为 directory 与 other，
        // Files.walk() 即使未显式 FOLLOW_LINKS 也可能进入其目标。任何链接或
        // reparse point 都只删除入口本身；仅普通目录允许逐层打开。
        if (!attributes.isDirectory()
                || attributes.isOther()
                || attributes.isSymbolicLink()) {
            Files.deleteIfExists(dir);
            return;
        }
        try (DirectoryStream<Path> children =
                     Files.newDirectoryStream(dir)) {
            for (Path child : children) {
                deleteDirectory(child);
            }
        }
        Files.deleteIfExists(dir);
    }
}
