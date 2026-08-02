package org.fossic.starsector.optimization;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** 从游戏 jar 的私有资源树定义保持原内部名的第三方 class。 */
public final class PrivateDependencyClassLoader extends ClassLoader {
    private static final String PRIVATE_BASE =
            "META-INF/starsector-optimization/private/";

    private final String resourceRoot;
    private final List<OwnedPackage> ownedPackages;
    private final Map<String, ResourceSource> ownedSources;
    private final List<ResourceSource> developmentClosure;
    private final ResourceSource definingSource;
    private final boolean development;

    private PrivateDependencyClassLoader(
            String resourceRoot, List<OwnedPackage> ownedPackages) {
        super(PrivateDependencyClassLoader.class.getClassLoader());
        this.resourceRoot = validateRoot(resourceRoot);
        this.ownedPackages = List.copyOf(ownedPackages);
        if (this.ownedPackages.isEmpty()) {
            throw new IllegalArgumentException(
                    "私有依赖至少需要一个 owned package");
        }
        this.definingSource = ResourceSource.forClass(
                PrivateDependencyClassLoader.class);
        this.development = definingSource.directory();

        Map<String, ResourceSource> packageSources = new LinkedHashMap<>();
        LinkedHashSet<ResourceSource> closure = new LinkedHashSet<>();
        closure.add(definingSource);
        for (OwnedPackage ownedPackage : this.ownedPackages) {
            ResourceSource source = development
                    ? developmentSource(ownedPackage)
                    : definingSource;
            ResourceSource previous = packageSources.putIfAbsent(
                    ownedPackage.prefix(), source);
            if (previous != null && !previous.equals(source)) {
                throw new LinkageError(
                        "私有依赖 package 绑定到多个代码源: "
                                + ownedPackage.prefix());
            }
            closure.add(source);
        }
        this.ownedSources = Map.copyOf(packageSources);
        this.developmentClosure = List.copyOf(closure);
    }

    public static <T> T loadProvider(
            String resourceRoot,
            String providerClass,
            Class<T> service,
            OwnedPackage... ownedPackages) {
        Objects.requireNonNull(providerClass, "providerClass");
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(ownedPackages, "ownedPackages");
        PrivateDependencyClassLoader loader =
                new PrivateDependencyClassLoader(
                        resourceRoot, List.of(ownedPackages));
        if (!loader.owned(providerClass)) {
            throw new IllegalArgumentException(
                    "provider 不属于私有依赖 package: " + providerClass);
        }
        try {
            Class<?> implementation = Class.forName(
                    providerClass, true, loader);
            return service.cast(
                    implementation.getDeclaredConstructor().newInstance());
        } catch (InvocationTargetException invocation) {
            Throwable cause = invocation.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(
                    "私有依赖 provider 初始化失败: " + providerClass,
                    cause);
        } catch (ReflectiveOperationException | ClassCastException failure) {
            throw new LinkageError(
                    "私有依赖 provider 不兼容: " + providerClass,
                    failure);
        }
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve)
            throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                if (owned(name)) {
                    // owned 类严格 child-first；缺失时绝不落到 mod/core 的同 FQCN。
                    loaded = findClass(name);
                } else {
                    loaded = super.loadClass(name, false);
                }
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        ResourceSource source = ownedSource(name);
        if (source == null) {
            throw new ClassNotFoundException(name);
        }
        String logical = name.replace('.', '/') + ".class";
        String storedName = development
                ? logical : resourceRoot + logical;
        try (InputStream input = source.open(storedName)) {
            if (input == null) {
                throw new ClassNotFoundException(
                        name + "（绑定代码源中的私有资源缺失）");
            }
            byte[] bytecode = input.readAllBytes();
            return defineClass(name, bytecode, 0, bytecode.length);
        } catch (IOException failure) {
            throw new ClassNotFoundException(name, failure);
        }
    }

    @Override
    public URL getResource(String name) {
        String logical = normalizeResourceName(name);
        if (logical == null) {
            return null;
        }
        try {
            if (!development) {
                return definingSource.find(resourceRoot + logical);
            }
            ResourceSource owned = ownedSourceForResource(logical);
            if (owned != null) {
                return owned.find(logical);
            }
            for (ResourceSource source : developmentClosure) {
                URL resource = source.find(logical);
                if (resource != null) {
                    return resource;
                }
            }
            return null;
        } catch (IOException failure) {
            throw new LinkageError(
                    "读取绑定代码源中的私有资源失败: " + logical,
                    failure);
        }
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        URL resource = getResource(name);
        return resource == null
                ? Collections.emptyEnumeration()
                : Collections.enumeration(List.of(resource));
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        String logical = normalizeResourceName(name);
        if (logical == null) {
            return null;
        }
        try {
            if (!development) {
                return definingSource.open(resourceRoot + logical);
            }
            ResourceSource owned = ownedSourceForResource(logical);
            if (owned != null) {
                return owned.open(logical);
            }
            for (ResourceSource source : developmentClosure) {
                InputStream input = source.open(logical);
                if (input != null) {
                    return input;
                }
            }
            return null;
        } catch (IOException failure) {
            throw new LinkageError(
                    "读取绑定代码源中的私有资源失败: " + logical,
                    failure);
        }
    }

    private ResourceSource developmentSource(OwnedPackage ownedPackage) {
        Class<?> anchor;
        try {
            anchor = Class.forName(
                    ownedPackage.developmentAnchorClass(),
                    false,
                    getParent());
        } catch (ClassNotFoundException failure) {
            throw new LinkageError(
                    "开发环境缺少私有依赖代码源锚点: "
                            + ownedPackage.developmentAnchorClass(),
                    failure);
        }
        return ResourceSource.forClass(anchor);
    }

    private boolean owned(String binaryName) {
        return ownedSource(binaryName) != null;
    }

    private ResourceSource ownedSource(String binaryName) {
        OwnedPackage best = null;
        for (OwnedPackage ownedPackage : ownedPackages) {
            if (binaryName.startsWith(ownedPackage.prefix())
                    && (best == null
                    || ownedPackage.prefix().length()
                            > best.prefix().length())) {
                best = ownedPackage;
            }
        }
        return best == null ? null : ownedSources.get(best.prefix());
    }

    private ResourceSource ownedSourceForResource(String logicalName) {
        ResourceSource bestSource = null;
        int bestLength = -1;
        for (OwnedPackage ownedPackage : ownedPackages) {
            String prefix = ownedPackage.prefix().replace('.', '/');
            if (logicalName.startsWith(prefix)
                    && prefix.length() > bestLength) {
                bestSource = ownedSources.get(ownedPackage.prefix());
                bestLength = prefix.length();
            }
        }
        return bestSource;
    }

    private static String validateRoot(String root) {
        Objects.requireNonNull(root, "resourceRoot");
        String normalized = root.replace('\\', '/');
        if (!normalized.startsWith(PRIVATE_BASE)
                || !normalized.endsWith("/")
                || normalized.contains("../")
                || normalized.contains("//")) {
            throw new IllegalArgumentException(
                    "非法私有依赖资源根: " + root);
        }
        return normalized;
    }

    private static String normalizeResourceName(String name) {
        if (name == null) {
            return null;
        }
        String normalized = name.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isEmpty()
                || normalized.contains("../")
                || normalized.contains("//")) {
            return null;
        }
        return normalized;
    }

    /** 显式声明一个 owned package 在开发 classpath 中允许使用的代码源锚点。 */
    public record OwnedPackage(
            String prefix, String developmentAnchorClass) {
        public OwnedPackage {
            Objects.requireNonNull(prefix, "prefix");
            Objects.requireNonNull(
                    developmentAnchorClass, "developmentAnchorClass");
            if (prefix.isBlank()
                    || !prefix.endsWith(".")
                    || prefix.indexOf('/') >= 0
                    || !developmentAnchorClass.startsWith(prefix)) {
                throw new IllegalArgumentException(
                        "非法 owned package/代码源锚点: "
                                + prefix + " / "
                                + developmentAnchorClass);
            }
        }
    }

    private record ResourceSource(Path location, boolean directory) {
        private static ResourceSource forClass(Class<?> anchor) {
            try {
                CodeSource codeSource = anchor.getProtectionDomain()
                        .getCodeSource();
                URL location = codeSource == null
                        ? null : codeSource.getLocation();
                if (location == null
                        || !"file".equalsIgnoreCase(
                                location.getProtocol())) {
                    throw new LinkageError(
                            "无法绑定私有依赖代码源: " + anchor.getName());
                }
                Path path = Path.of(location.toURI()).toRealPath();
                boolean directory = Files.isDirectory(
                        path, LinkOption.NOFOLLOW_LINKS);
                if (!directory && !Files.isRegularFile(
                        path, LinkOption.NOFOLLOW_LINKS)) {
                    throw new LinkageError(
                            "私有依赖代码源不是目录或 jar: " + path);
                }
                return new ResourceSource(path, directory);
            } catch (IOException | URISyntaxException
                    | RuntimeException failure) {
                throw new LinkageError(
                        "无法绑定私有依赖代码源: " + anchor.getName(),
                        failure);
            }
        }

        private URL find(String logicalName) throws IOException {
            if (directory) {
                Path resource = resolveDirectoryResource(logicalName);
                return resource == null ? null : resource.toUri().toURL();
            }
            try (ZipFile archive = new ZipFile(location.toFile())) {
                ZipEntry entry = archive.getEntry(logicalName);
                if (entry == null || entry.isDirectory()) {
                    return null;
                }
            }
            try {
                return new URL(
                        "jar:" + location.toUri().toASCIIString()
                                + "!/" + logicalName);
            } catch (MalformedURLException failure) {
                throw new IOException(
                        "无法创建私有依赖资源 URL: " + logicalName,
                        failure);
            }
        }

        private InputStream open(String logicalName) throws IOException {
            if (directory) {
                Path resource = resolveDirectoryResource(logicalName);
                return resource == null
                        ? null : Files.newInputStream(resource);
            }
            ZipFile archive = new ZipFile(location.toFile());
            ZipEntry entry = archive.getEntry(logicalName);
            if (entry == null || entry.isDirectory()) {
                archive.close();
                return null;
            }
            InputStream input = archive.getInputStream(entry);
            return new FilterInputStream(input) {
                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        archive.close();
                    }
                }
            };
        }

        private Path resolveDirectoryResource(String logicalName) {
            Path resource = location.resolve(logicalName).normalize();
            if (!resource.startsWith(location)
                    || !Files.isRegularFile(
                            resource, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(resource)) {
                return null;
            }
            return resource;
        }
    }
}
