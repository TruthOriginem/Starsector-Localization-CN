package org.fossic.starsector.optimization;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringTokenizer;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipException;

/** 为 Janino 生成物计算与绝对安装路径无关的完整环境指纹。 */
public final class JaninoCacheFingerprint {
    private static final String SCHEMA = "starsector-janino-cache-v2";
    private static final int MAXIMUM_GRAPH_NODES = 100_000;
    private static final int MAXIMUM_GRAPH_EDGES = 100_000;

    private JaninoCacheFingerprint() {
    }

    public static String forInputs(
            List<Path> coreClasspath,
            List<LoaderFrame> loaderFrames,
            List<String> environment,
            String characterEncoding,
            boolean debugSource,
            boolean debugLines,
            boolean debugVars) throws IOException {
        Objects.requireNonNull(coreClasspath, "coreClasspath");
        Objects.requireNonNull(loaderFrames, "loaderFrames");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(characterEncoding, "characterEncoding");

        MessageDigest digest = sha256();
        update(digest, "schema", SCHEMA);
        updateFiles(digest, "core", coreClasspath);
        update(digest, "loader-frame-count",
                Integer.toString(loaderFrames.size()));
        for (int index = 0; index < loaderFrames.size(); index++) {
            LoaderFrame frame = Objects.requireNonNull(
                    loaderFrames.get(index), "loader frame");
            update(digest, "loader-class", frame.className());
            updateFiles(digest, "loader-" + index + "-url", frame.urls());
        }
        update(digest, "environment-count", Integer.toString(environment.size()));
        for (String value : environment) {
            update(digest, "environment",
                    Objects.requireNonNull(value, "environment value"));
        }
        update(digest, "encoding", characterEncoding);
        update(digest, "debug-source", Boolean.toString(debugSource));
        update(digest, "debug-lines", Boolean.toString(debugLines));
        update(digest, "debug-vars", Boolean.toString(debugVars));
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * 从游戏运行时 classpath 和传给 ScriptStore 的 parent loader 链计算指纹。
     * 任一目录、非 file URL 或不可读输入都会使调用失败，缓存随后按 fail-closed 禁用。
     */
    public static String forRuntime(
            ClassLoader parent,
            String characterEncoding,
            boolean debugSource,
            boolean debugLines,
            boolean debugVars) throws IOException {
        List<Path> core = new ArrayList<>();
        String classPath = System.getProperty("java.class.path", "");
        if (classPath.isEmpty()) {
            throw new IOException("java.class.path is empty");
        }
        for (String entry : classPath.split(
                java.util.regex.Pattern.quote(
                        System.getProperty("path.separator")), -1)) {
            if (entry.isEmpty()) {
                throw new IOException("Empty runtime classpath entry");
            }
            core.add(Path.of(entry));
        }

        List<LoaderFrame> frames = new ArrayList<>();
        for (ClassLoader current = parent;
                current != null;
                current = current.getParent()) {
            List<Path> urlsForLoader = new ArrayList<>();
            if (current instanceof URLClassLoader urls) {
                for (URL url : urls.getURLs()) {
                    urlsForLoader.add(filePath(url));
                }
            }
            frames.add(new LoaderFrame(
                    current.getClass().getName(), urlsForLoader));
        }
        List<String> environment = List.of(
                "bootstrap",
                "java.version=" + System.getProperty("java.version", ""),
                "java.vendor=" + System.getProperty("java.vendor", ""),
                "java.vm.name=" + System.getProperty("java.vm.name", ""),
                "os.arch=" + System.getProperty("os.arch", ""),
                propertySetting("jdk.util.jar.enableMultiRelease"),
                propertySetting("jdk.util.jar.version"));
        return forInputs(
                core, frames, environment, characterEncoding,
                debugSource, debugLines, debugVars);
    }

    /** 测试/受控环境使用的稳定种子指纹，仍包含全部编译选项。 */
    static String forSeed(
            String environmentSeed,
            String characterEncoding,
            boolean debugSource,
            boolean debugLines,
            boolean debugVars) {
        MessageDigest digest = sha256();
        update(digest, "schema", SCHEMA);
        update(digest, "environment-seed", Objects.requireNonNull(
                environmentSeed, "environmentSeed"));
        update(digest, "encoding", Objects.requireNonNull(
                characterEncoding, "characterEncoding"));
        update(digest, "debug-source", Boolean.toString(debugSource));
        update(digest, "debug-lines", Boolean.toString(debugLines));
        update(digest, "debug-vars", Boolean.toString(debugVars));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void updateFiles(
            MessageDigest digest, String kind, List<Path> paths)
            throws IOException {
        update(digest, kind + "-count", Integer.toString(paths.size()));
        ClasspathGraphHasher graph = new ClasspathGraphHasher(digest, kind);
        for (Path path : paths) {
            Objects.requireNonNull(path, kind + " classpath entry");
            if (!Files.isRegularFile(path)) {
                throw new IOException(
                        "Non-file " + kind + " classpath entry: " + path);
            }
            update(digest, kind + "-root", "begin");
            graph.updateNode(path);
            update(digest, kind + "-root", "end");
        }
    }

    private static String propertySetting(String name) {
        String value = System.getProperty(name);
        return value == null
                ? name + ":unset"
                : name + ":set:" + value;
    }

    private static void updateLong(
            MessageDigest digest, String tag, long value) {
        update(digest, tag, Long.toString(value));
    }

    private static Path filePath(URL url) throws IOException {
        if (!"file".equalsIgnoreCase(url.getProtocol())) {
            throw new IOException("Non-file classloader URL: " + url);
        }
        try {
            URI uri = url.toURI();
            return Path.of(uri);
        } catch (URISyntaxException | IllegalArgumentException exception) {
            throw new IOException("Invalid classloader URL: " + url, exception);
        }
    }

    /**
     * 对一个有序 classpath 列表计算与安装绝对路径无关的 manifest 依赖图。
     *
     * <p>节点编号只由遍历顺序决定，绝对路径仅用作本次计算中的去重 key，不进入摘要。
     * 因而整棵 mod 目录树移动后仍能命中；引用节点同时让循环 manifest 安全终止。缺失的
     * manifest 依赖会成为显式节点，文件随后出现时自然改变指纹。显式 classpath root、
     * 目录和非 file URL 则全部 fail closed。
     */
    private static final class ClasspathGraphHasher {
        private final MessageDigest digest;
        private final String kind;
        private final byte[] buffer = new byte[128 * 1024];
        private final Map<Path, Integer> nodeIds = new HashMap<>();
        private int edgeCount;

        private ClasspathGraphHasher(MessageDigest digest, String kind) {
            this.digest = digest;
            this.kind = kind;
        }

        private void updateNode(Path path) throws IOException {
            Path identity = path.toAbsolutePath().normalize();
            Integer existing = nodeIds.get(identity);
            if (existing != null) {
                update(digest, kind + "-node-reference",
                        Integer.toString(existing));
                return;
            }
            if (nodeIds.size() >= MAXIMUM_GRAPH_NODES) {
                throw new IOException(
                        "Manifest Class-Path graph has too many nodes");
            }
            int nodeId = nodeIds.size();
            nodeIds.put(identity, nodeId);
            update(digest, kind + "-node", "begin");
            update(digest, kind + "-node-id", Integer.toString(nodeId));

            if (!Files.exists(identity)) {
                update(digest, kind + "-node-state", "missing");
                update(digest, kind + "-node", "end");
                return;
            }
            if (!Files.isRegularFile(identity)) {
                throw new IOException(
                        "Non-file manifest Class-Path entry: " + identity);
            }
            update(digest, kind + "-node-state", "regular-file");
            updateFileContents(identity);

            List<Path> dependencies = manifestDependencies(identity);
            update(digest, kind + "-dependency-count",
                    Integer.toString(dependencies.size()));
            for (Path dependency : dependencies) {
                if (edgeCount >= MAXIMUM_GRAPH_EDGES) {
                    throw new IOException(
                            "Manifest Class-Path graph has too many edges");
                }
                edgeCount++;
                update(digest, kind + "-dependency", "begin");
                updateNode(dependency);
                update(digest, kind + "-dependency", "end");
            }
            update(digest, kind + "-node", "end");
        }

        private void updateFileContents(Path path) throws IOException {
            long expectedSize = Files.size(path);
            updateLong(digest, kind + "-node-size", expectedSize);
            long actualSize = 0L;
            try (InputStream input = Files.newInputStream(path)) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                    try {
                        actualSize = Math.addExact(actualSize, read);
                    } catch (ArithmeticException overflow) {
                        throw new IOException(
                                "Classpath entry is too large: " + path,
                                overflow);
                    }
                }
            }
            if (actualSize != expectedSize || Files.size(path) != expectedSize) {
                throw new IOException(
                        "Classpath entry changed while hashing: " + path);
            }
        }

        private static List<Path> manifestDependencies(Path owner)
                throws IOException {
            Manifest manifest;
            try (JarFile jar = new JarFile(owner.toFile(), false)) {
                manifest = jar.getManifest();
            } catch (ZipException notAnArchive) {
                return List.of();
            }
            if (manifest == null) {
                return List.of();
            }
            String classPath = manifest.getMainAttributes().getValue(
                    Attributes.Name.CLASS_PATH);
            if (classPath == null || classPath.isBlank()) {
                return List.of();
            }

            URL base = owner.toUri().toURL();
            ArrayList<Path> dependencies = new ArrayList<>();
            StringTokenizer entries = new StringTokenizer(classPath);
            while (entries.hasMoreTokens()) {
                URL dependency;
                try {
                    dependency = new URL(base, entries.nextToken());
                } catch (IllegalArgumentException exception) {
                    throw new IOException(
                            "Invalid manifest Class-Path in " + owner,
                            exception);
                }
                dependencies.add(filePath(dependency));
            }
            return List.copyOf(dependencies);
        }
    }

    private static void update(
            MessageDigest digest, String tag, String value) {
        byte[] tagBytes = tag.getBytes(StandardCharsets.UTF_8);
        byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES)
                .putInt(tagBytes.length).array());
        digest.update(tagBytes);
        digest.update(ByteBuffer.allocate(Integer.BYTES)
                .putInt(valueBytes.length).array());
        digest.update(valueBytes);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /** parent-first classloader 链中的一层及只属于该层的 URL 顺序。 */
    public record LoaderFrame(String className, List<Path> urls) {
        public LoaderFrame {
            Objects.requireNonNull(className, "className");
            urls = List.copyOf(Objects.requireNonNull(urls, "urls"));
        }
    }
}
