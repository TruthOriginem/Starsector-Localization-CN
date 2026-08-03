package org.fossic.starsector.preprocessing;

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
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class JarWorkspace {
    public static final String API_JAR = "starfarer.api.jar";
    public static final String OBF_JAR = "starfarer_obf.jar";
    /**
     * 仅 ASM 注入、不做字符串解耦/翻译的 jar：本分支的动态字体 hook 目标。
     *
     * <p>主分支会在这里修复高亮颜色数组的空值崩溃，动态字体分支还会注入额外
     * hook。各变体汉化包都分发此文件，以便覆盖安装时不会残留其它变体的 hook
     * 或运行时类依赖。
     */
    public static final String COMMON_OBF_JAR = "fs.common_obf.jar";
    /**
     * 仅 ASM 注入、不做字符串解耦/翻译的声音引擎 jar。
     *
     * <p>启动优化分支会修改此文件；其它分支仍需分发原版副本，确保从启动优化版
     * 覆盖安装回来时不会残留声音加载 hook。
     */
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
            Path generated = finalJar(jarName);
            Path originalTarget = originalDir.resolve(jarName);
            Path localizationTarget = localizationDir.resolve(jarName);

            if (jarContentsEqual(generated, originalTarget)) {
                if (localizationIsCompatible(originalTarget, localizationTarget)) {
                    System.out.println("Preserving " + jarName
                            + ": generated entries match the existing original");
                    continue;
                }

                byte[] originalBytes = Files.readAllBytes(originalTarget);
                atomicWrite(localizationTarget, originalBytes);
                requireEqualHashes(jarName, originalTarget, localizationTarget);
                System.out.println("Resetting localization/" + jarName
                        + ": existing localization is missing or incompatible");
                continue;
            }

            byte[] bytes = Files.readAllBytes(generated);
            atomicWrite(originalTarget, bytes);
            atomicWrite(localizationTarget, bytes);
            requireEqualHashes(jarName, originalTarget, localizationTarget);
            System.out.println("Resetting " + jarName
                    + ": generated entry contents changed");
        }
    }

    private static boolean jarContentsEqual(Path generated, Path existing)
            throws IOException {
        if (!Files.isRegularFile(existing)) {
            return false;
        }

        try (ZipFile generatedZip = new ZipFile(generated.toFile())) {
            try (ZipFile existingZip = new ZipFile(existing.toFile())) {
                Map<String, ZipEntry> generatedEntries = uniqueEntries(generatedZip);
                Map<String, ZipEntry> existingEntries = uniqueEntries(existingZip);
                if (generatedEntries == null || existingEntries == null
                        || !generatedEntries.keySet().equals(existingEntries.keySet())) {
                    return false;
                }

                for (Map.Entry<String, ZipEntry> item : generatedEntries.entrySet()) {
                    ZipEntry generatedEntry = item.getValue();
                    ZipEntry existingEntry = existingEntries.get(item.getKey());
                    if (generatedEntry.isDirectory() != existingEntry.isDirectory()
                            || !entryContentsEqual(
                                    generatedZip, generatedEntry,
                                    existingZip, existingEntry)) {
                        return false;
                    }
                }
                return true;
            } catch (IOException invalidExisting) {
                return false;
            }
        }
    }

    private static boolean localizationIsCompatible(Path original, Path localization)
            throws IOException {
        if (!Files.isRegularFile(localization)) {
            return false;
        }

        try (ZipFile originalZip = new ZipFile(original.toFile())) {
            try (ZipFile localizationZip = new ZipFile(localization.toFile())) {
                Map<String, ZipEntry> originalEntries = uniqueEntries(originalZip);
                Map<String, ZipEntry> localizationEntries = uniqueEntries(localizationZip);
                if (originalEntries == null || localizationEntries == null
                        || !originalEntries.keySet().equals(localizationEntries.keySet())) {
                    return false;
                }

                for (Map.Entry<String, ZipEntry> item : originalEntries.entrySet()) {
                    ZipEntry originalEntry = item.getValue();
                    ZipEntry localizationEntry = localizationEntries.get(item.getKey());
                    if (originalEntry.isDirectory() != localizationEntry.isDirectory()) {
                        return false;
                    }
                    if (item.getKey().endsWith(".class")) {
                        // Read the entry to force CRC/integrity validation; translated class
                        // bytes are intentionally allowed to differ from the original.
                        try (InputStream input =
                                     localizationZip.getInputStream(localizationEntry)) {
                            input.readAllBytes();
                        }
                    } else if (!entryContentsEqual(
                            originalZip, originalEntry,
                            localizationZip, localizationEntry)) {
                        return false;
                    }
                }
                return true;
            } catch (IOException invalidLocalization) {
                return false;
            }
        }
    }

    private static Map<String, ZipEntry> uniqueEntries(ZipFile zip) {
        Map<String, ZipEntry> entries = new LinkedHashMap<>();
        for (ZipEntry entry : zip.stream().toList()) {
            if (entries.put(entry.getName(), entry) != null) {
                return null;
            }
        }
        return entries;
    }

    private static boolean entryContentsEqual(
            ZipFile leftZip, ZipEntry left,
            ZipFile rightZip, ZipEntry right) throws IOException {
        if (left.getSize() != right.getSize()) {
            return false;
        }
        try (InputStream leftInput = leftZip.getInputStream(left);
             InputStream rightInput = rightZip.getInputStream(right)) {
            byte[] leftBuffer = new byte[8192];
            byte[] rightBuffer = new byte[8192];
            while (true) {
                int leftRead = leftInput.readNBytes(leftBuffer, 0, leftBuffer.length);
                int rightRead = rightInput.readNBytes(rightBuffer, 0, rightBuffer.length);
                if (leftRead != rightRead) {
                    return false;
                }
                if (leftRead == 0) {
                    return true;
                }
                for (int index = 0; index < leftRead; index++) {
                    if (leftBuffer[index] != rightBuffer[index]) {
                        return false;
                    }
                }
            }
        }
    }

    private static void requireEqualHashes(
            String jarName, Path originalTarget, Path localizationTarget)
            throws IOException {
        String originalHash = sha256(originalTarget);
        String localizationHash = sha256(localizationTarget);
        if (!originalHash.equals(localizationHash)) {
            throw new PatchException("Output hash mismatch for " + jarName + ": original="
                    + originalHash + ", localization=" + localizationHash);
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
