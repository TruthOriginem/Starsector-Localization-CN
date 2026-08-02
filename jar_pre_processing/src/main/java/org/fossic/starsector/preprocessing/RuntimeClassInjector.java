package org.fossic.starsector.preprocessing;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 把指定包前缀的运行时类（本模块或依赖 jar 的编译产物）注入游戏 jar。
 *
 * <p>运行时类与本工具同属一个 Maven 模块，由 {@code mvnw compile} 统一编译
 * （编译期依赖 pom 中 system scope 的游戏 API jar）。本类从自身 classpath
 * 读取编译产物并追加进 jar，不做运行时编译。目录与依赖 jar 两种 classpath
 * 形式均受支持。
 *
 * <p>这些类被 ASM patch 注入的字节码调用，必须与游戏类同处 classpath，因此
 * 打包进游戏 jar。注入在字符串解耦之后进行，故运行时类的（中文日志）字符串
 * 不会被解耦或误入 ParaTranz 翻译流程。当前运行时包包括：
 * {@code org.fossic.starsector.ime.*}（中文输入法）与
 * {@code org.fossic.starsector.dynfont.*}（动态字体），以及按构建选择注入的
 * {@code org.fossic.starsector.startup.*}（启动阶段计时）与
 * {@code org.fossic.starsector.optimization.*}（启动优化 helper）。第三方 PNG/Zstd
 * 实现仅以私有资源树形式携带，由隔离 classloader 定义，不暴露到 mod classpath。
 */
public final class RuntimeClassInjector {
    private final String classPrefix;
    private final String requiredClass;

    /**
     * @param classPrefix 包路径前缀（如 {@code org/fossic/starsector/ime/}）
     * @param requiredSimpleName 必须存在的关键类文件名（ASM 注入字节码的调用目标，
     *                           如 {@code ImeHooks.class}），防止空目录静默通过
     */
    public RuntimeClassInjector(String classPrefix, String requiredSimpleName) {
        this.classPrefix = classPrefix;
        this.requiredClass = classPrefix + requiredSimpleName;
    }

    /**
     * 从本工具 classpath 收集运行时 class 并注入指定 jar（就地重写）。
     *
     * @return 注入的 class 数量
     */
    public int injectInto(Path jar) throws IOException {
        return injectInto(jar, new String[0]);
    }

    /**
     * 从工具 classpath 注入运行时 class，并可同时携带许可证等普通资源。
     *
     * @return 注入的 class 数量（不含普通资源）
     */
    public int injectInto(Path jar, String... additionalResources)
            throws IOException {
        Map<String, byte[]> classes = collectRuntimeClasses();
        if (!classes.containsKey(requiredClass)) {
            throw new PatchException("运行时编译产物中缺少 " + requiredClass
                    + "（请先执行 mvnw compile）");
        }
        Map<String, byte[]> entries = new LinkedHashMap<>(classes);
        for (String resourceName : additionalResources) {
            if (resourceName == null || resourceName.isBlank()) {
                throw new PatchException("附加运行时资源名不能为空");
            }
            if (entries.containsKey(resourceName)) {
                throw new PatchException(
                        "附加运行时资源与 class 重名: " + resourceName);
            }
            try (InputStream input = RuntimeClassInjector.class
                    .getClassLoader().getResourceAsStream(resourceName)) {
                if (input == null) {
                    throw new PatchException(
                            "classpath 中找不到附加运行时资源 "
                                    + resourceName);
                }
                entries.put(resourceName, input.readAllBytes());
            }
        }
        addEntries(jar, entries);
        return classes.size();
    }

    /**
     * 保持 class 内部名不变，但把 class/resource 条目放入游戏 jar 的私有资源树。
     * 运行时由 {@code PrivateDependencyClassLoader} 读取，避免污染 mod 的 parent-first
     * classpath。许可证等 publicResources 仍保留顶层标准路径。
     */
    public int injectPrivatelyInto(
            Path jar,
            String privateRoot,
            String[] privateResources,
            String... publicResources) throws IOException {
        String root = validatePrivateRoot(privateRoot);
        Map<String, byte[]> classes = collectRuntimeClasses();
        if (!classes.containsKey(requiredClass)) {
            throw new PatchException("运行时编译产物中缺少 " + requiredClass
                    + "（请先执行 mvnw compile）");
        }
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
            entries.put(root + entry.getKey(), entry.getValue());
        }
        for (String resource : privateResources) {
            String name = validateResourceName(resource);
            String privateName = root + name;
            if (entries.putIfAbsent(
                    privateName, readResource(name)) != null) {
                throw new PatchException(
                        "私有运行时资源与 class 重名: " + name);
            }
        }
        for (String resource : publicResources) {
            String name = validateResourceName(resource);
            if (entries.put(name, readResource(name)) != null) {
                throw new PatchException("私有/公开运行时资源重名: " + name);
            }
        }
        addEntries(jar, entries);
        return classes.size();
    }

    private static String validatePrivateRoot(String root) {
        if (root == null) {
            throw new PatchException("私有运行时资源根不能为空");
        }
        String normalized = root.replace('\\', '/');
        if (!normalized.startsWith(
                    "META-INF/starsector-optimization/private/")
                || !normalized.endsWith("/")
                || normalized.contains("../")
                || normalized.contains("//")) {
            throw new PatchException("非法私有运行时资源根: " + root);
        }
        return normalized;
    }

    private static String validateResourceName(String resource) {
        if (resource == null || resource.isBlank()) {
            throw new PatchException("附加运行时资源名不能为空");
        }
        String normalized = resource.replace('\\', '/');
        if (normalized.startsWith("/")
                || normalized.contains("../")
                || normalized.contains("//")) {
            throw new PatchException("非法附加运行时资源名: " + resource);
        }
        return normalized;
    }

    private static byte[] readResource(String resourceName)
            throws IOException {
        try (InputStream input = RuntimeClassInjector.class
                .getClassLoader().getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new PatchException(
                        "classpath 中找不到附加运行时资源 "
                                + resourceName);
            }
            return input.readAllBytes();
        }
    }

    private Map<String, byte[]> collectRuntimeClasses() throws IOException {
        Enumeration<URL> packageUrls = RuntimeClassInjector.class
                .getClassLoader().getResources(classPrefix);
        Map<String, byte[]> classes = new LinkedHashMap<>();
        boolean found = false;
        while (packageUrls.hasMoreElements()) {
            found = true;
            URL packageUrl = packageUrls.nextElement();
            Map<String, byte[]> fromLocation;
            if ("file".equals(packageUrl.getProtocol())) {
                fromLocation = collectDirectoryClasses(packageUrl);
            } else if ("jar".equals(packageUrl.getProtocol())) {
                fromLocation = collectJarClasses(packageUrl);
            } else {
                throw new PatchException(
                        "不支持的运行时包 classpath 协议: " + packageUrl);
            }
            mergeClasses(classes, fromLocation, packageUrl);
        }
        if (!found) {
            throw new PatchException("classpath 中找不到运行时包 " + classPrefix
                    + "（请先执行 mvnw compile）");
        }
        return classes;
    }

    private static void mergeClasses(
            Map<String, byte[]> target,
            Map<String, byte[]> source,
            URL sourceUrl) {
        for (Map.Entry<String, byte[]> entry : source.entrySet()) {
            byte[] previous = target.putIfAbsent(
                    entry.getKey(), entry.getValue());
            if (previous != null
                    && !java.util.Arrays.equals(previous, entry.getValue())) {
                throw new PatchException(
                        "classpath 中存在内容不同的重复运行时类 "
                                + entry.getKey() + "，来源 " + sourceUrl);
            }
        }
    }

    private Map<String, byte[]> collectDirectoryClasses(URL packageUrl)
            throws IOException {
        Path packageDir;
        try {
            packageDir = Path.of(packageUrl.toURI());
        } catch (URISyntaxException e) {
            throw new PatchException("无法解析运行时包路径: " + packageUrl, e);
        }

        Map<String, byte[]> classes = new LinkedHashMap<>();
        try (var stream = Files.walk(packageDir)) {
            List<Path> classFiles = stream
                    .filter(p -> p.toString().endsWith(".class"))
                    .sorted()
                    .toList();
            for (Path classFile : classFiles) {
                String entryName = classPrefix
                        + packageDir.relativize(classFile).toString().replace('\\', '/');
                classes.put(entryName, Files.readAllBytes(classFile));
            }
        }
        return classes;
    }

    private Map<String, byte[]> collectJarClasses(URL packageUrl)
            throws IOException {
        JarURLConnection connection;
        try {
            connection = (JarURLConnection) packageUrl.openConnection();
        } catch (ClassCastException invalidConnection) {
            throw new PatchException(
                    "无法打开依赖 Jar 中的运行时包: " + packageUrl,
                    invalidConnection);
        }
        connection.setUseCaches(false);
        Map<String, byte[]> classes = new LinkedHashMap<>();
        try (var source = connection.getJarFile()) {
            List<? extends java.util.zip.ZipEntry> classEntries =
                    source.stream()
                            .filter(entry -> !entry.isDirectory())
                            .filter(entry -> entry.getName()
                                    .startsWith(classPrefix))
                            .filter(entry -> entry.getName()
                                    .endsWith(".class"))
                            .sorted(java.util.Comparator.comparing(
                                    java.util.zip.ZipEntry::getName))
                            .toList();
            for (var entry : classEntries) {
                try (InputStream input = source.getInputStream(entry)) {
                    classes.put(entry.getName(), input.readAllBytes());
                }
            }
        }
        return classes;
    }

    private static void addEntries(Path jar, Map<String, byte[]> classes) throws IOException {
        Path temp = jar.resolveSibling(jar.getFileName() + ".inject.tmp");
        try {
            try (ZipFile source = new ZipFile(jar.toFile());
                 ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(temp))) {
                for (ZipEntry entry : source.stream().toList()) {
                    if (classes.containsKey(entry.getName())) {
                        throw new PatchException("jar 中已存在同名运行时类: " + entry.getName());
                    }
                    ZipEntry copy = new ZipEntry(entry.getName());
                    copy.setTime(entry.getTime());
                    out.putNextEntry(copy);
                    try (InputStream input = source.getInputStream(entry)) {
                        out.write(input.readAllBytes());
                    }
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
            try {
                Files.move(
                        temp,
                        jar,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temp, jar, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
