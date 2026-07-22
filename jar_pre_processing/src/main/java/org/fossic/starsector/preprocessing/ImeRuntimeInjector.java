package org.fossic.starsector.preprocessing;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 把中文输入法运行时类（{@code org.fossic.starsector.ime.*}）注入 {@code starfarer_obf.jar}。
 *
 * <p>运行时类与本工具同属一个 Maven 模块，由 {@code mvnw compile} 统一编译
 * （编译期依赖 pom 中 system scope 的游戏 API jar）。本类从自身 classpath
 * （{@code target/classes}）读取编译产物并追加进 jar，不做运行时编译。
 *
 * <p>这些类被 {@link org.fossic.starsector.preprocessing.patches.TextFieldImeHookPatch}
 * 注入的字节码调用，必须与游戏类位于同一 classpath，因此打包进 obf jar。
 * 注入在字符串解耦之后进行，故运行时类的（中文日志）字符串不会被解耦或
 * 误入 ParaTranz 翻译流程。
 */
public final class ImeRuntimeInjector {
    private static final String CLASS_PREFIX = "org/fossic/starsector/ime/";
    /** 注入结果必须包含的关键类（ASM 注入字节码的调用目标），防止空目录静默通过。 */
    private static final String REQUIRED_CLASS = CLASS_PREFIX + "ImeHooks.class";

    /**
     * 从本工具 classpath 收集运行时 class 并注入指定 jar（就地重写）。
     *
     * @return 注入的 class 数量
     */
    public int injectInto(Path obfJar) throws IOException {
        Map<String, byte[]> classes = collectRuntimeClasses();
        if (!classes.containsKey(REQUIRED_CLASS)) {
            throw new PatchException("IME 运行时编译产物中缺少 " + REQUIRED_CLASS
                    + "（请先执行 mvnw compile）");
        }
        addEntries(obfJar, classes);
        return classes.size();
    }

    private static Map<String, byte[]> collectRuntimeClasses() throws IOException {
        URL packageUrl = ImeRuntimeInjector.class.getClassLoader().getResource(CLASS_PREFIX);
        if (packageUrl == null) {
            throw new PatchException("classpath 中找不到 IME 运行时包 " + CLASS_PREFIX
                    + "（请先执行 mvnw compile）");
        }
        if (!"file".equals(packageUrl.getProtocol())) {
            throw new PatchException("IME 运行时包不在目录形式的 classpath 中: " + packageUrl);
        }
        Path packageDir;
        try {
            packageDir = Path.of(packageUrl.toURI());
        } catch (URISyntaxException e) {
            throw new PatchException("无法解析 IME 运行时包路径: " + packageUrl, e);
        }

        Map<String, byte[]> classes = new LinkedHashMap<>();
        try (var stream = Files.walk(packageDir)) {
            List<Path> classFiles = stream
                    .filter(p -> p.toString().endsWith(".class"))
                    .sorted()
                    .toList();
            for (Path classFile : classFiles) {
                String entryName = CLASS_PREFIX
                        + packageDir.relativize(classFile).toString().replace('\\', '/');
                classes.put(entryName, Files.readAllBytes(classFile));
            }
        }
        return classes;
    }

    private static void addEntries(Path jar, Map<String, byte[]> classes) throws IOException {
        Path temp = jar.resolveSibling(jar.getFileName() + ".ime.tmp");
        try {
            try (ZipFile source = new ZipFile(jar.toFile());
                 ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(temp))) {
                for (ZipEntry entry : source.stream().toList()) {
                    if (classes.containsKey(entry.getName())) {
                        throw new PatchException("jar 中已存在同名 IME 运行时类: " + entry.getName());
                    }
                    ZipEntry copy = new ZipEntry(entry.getName());
                    copy.setTime(entry.getTime());
                    out.putNextEntry(copy);
                    out.write(source.getInputStream(entry).readAllBytes());
                    out.closeEntry();
                }
                for (Map.Entry<String, byte[]> injected : classes.entrySet()) {
                    ZipEntry entry = new ZipEntry(injected.getKey());
                    entry.setTime(0L);
                    out.putNextEntry(entry);
                    out.write(injected.getValue());
                    out.closeEntry();
                }
            }
            Files.move(temp, jar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
