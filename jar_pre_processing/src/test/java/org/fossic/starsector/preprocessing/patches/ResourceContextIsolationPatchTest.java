package org.fossic.starsector.preprocessing.patches;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Future;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipFile;
import org.fossic.starsector.optimization.ResourceReadContext;
import org.fossic.starsector.optimization.SpeculativeResourceContext;
import org.fossic.starsector.preprocessing.AsmUtil;
import org.fossic.starsector.preprocessing.JarPatch;
import org.fossic.starsector.preprocessing.JarWorkspace;
import org.fossic.starsector.preprocessing.PatchContext;
import org.fossic.starsector.preprocessing.PatchRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

public final class ResourceContextIsolationPatchTest {
    @TempDir Path temporary;
    private static final AtomicInteger IO_CHECKS =
            new AtomicInteger();

    public static void assertUnlockedAtFileOpen(Object resource) {
        assertFalse(Thread.holdsLock(resource), "file I/O must remain outside the resource monitor");
        IO_CHECKS.incrementAndGet();
    }

    @Test
    void shortLockPathDoesNotHoldMonitorDuringActualFileOpen() throws Exception {
        try (Fixture fixture = new Fixture(true)) {
            int before = IO_CHECKS.get();
            assertEquals("mission", fixture.read("mission.txt"));
            assertTrue(IO_CHECKS.get() > before);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void disabledFixReproducesFalseMissingAndImmediateRetrySuccess(boolean shortLocks) throws Exception {
        try (Fixture fixture = new Fixture(shortLocks, false)) {
            var executor = Executors.newSingleThreadExecutor();
            CountDownLatch selected = new CountDownLatch(1), consumed = new CountDownLatch(1);
            try {
                Future<?> owner = executor.submit(() -> {
                    fixture.select("modB");
                    selected.countDown();
                    assertTrue(consumed.await(5, TimeUnit.SECONDS));
                    assertEquals("A", fixture.read("shared.txt")); // Selection stolen.
                    return null;
                });
                assertTrue(selected.await(5, TimeUnit.SECONDS));
                try {
                    assertThrows(java.lang.reflect.InvocationTargetException.class,
                            () -> fixture.read("mission.txt"));
                    assertEquals("mission", fixture.read("mission.txt"));
                } finally {
                    consumed.countDown();
                }
                owner.get(5, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentSelectionsSurviveRepeatedInterleaving(boolean shortLocks) throws Exception {
        try (Fixture fixture = new Fixture(shortLocks)) {
            var executor = Executors.newFixedThreadPool(8);
            var barrier = new CyclicBarrier(8);
            var results = new ArrayList<Future<?>>();
            try {
                for (int thread = 0; thread < 8; thread++) {
                    final int kind = thread % 4;
                    results.add(executor.submit(() -> {
                        for (int iteration = 0; iteration < 1000; iteration++) {
                            if (kind == 0) fixture.select("modB");
                            if (kind == 1) fixture.skipMods();
                            if (kind == 2) fixture.select("modA");
                            barrier.await(10, TimeUnit.SECONDS);
                            assertEquals(kind == 0 ? "B" : kind == 1 ? "base" : "A",
                                    fixture.read("shared.txt"));
                            assertEquals("mission", fixture.read("mission.txt"));
                            barrier.await(10, TimeUnit.SECONDS);
                        }
                        return null;
                    }));
                }
                for (Future<?> result : results) result.get(90, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void loaderInstancesAndChildThreadsDoNotShareSelectors(boolean shortLocks) throws Exception {
        try (Fixture fixture = new Fixture(shortLocks)) {
            Object second = fixture.newResource();
            fixture.select("modB");
            assertEquals("A", fixture.readFrom(second, "shared.txt"));
            var executor = Executors.newSingleThreadExecutor();
            try {
                // Created after selection; an InheritableThreadLocal would fail this check.
                assertEquals("A", executor.submit(() -> fixture.read("shared.txt"))
                        .get(5, TimeUnit.SECONDS));
                assertEquals("B", fixture.read("shared.txt"));
                executor.submit(() -> {
                    fixture.select("modB");
                    assertThrows(java.lang.reflect.InvocationTargetException.class,
                            () -> fixture.read("mission.txt"));
                    fixture.skipMods();
                    assertThrows(java.lang.reflect.InvocationTargetException.class,
                            () -> fixture.read("mission.txt"));
                    return null;
                }).get(5, TimeUnit.SECONDS);
                assertEquals("A", executor.submit(() -> fixture.read("shared.txt"))
                        .get(5, TimeUnit.SECONDS));
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            }
            fixture.resource.getClass().getField("super").setBoolean(null, true);
            assertEquals("base", fixture.read("shared.txt"));
            assertEquals("A", fixture.read("shared.txt"));
        }
    }

    @Test
    void speculativeReadDoesNotConsumeTheCallingThreadsContext() throws Exception {
        try (Fixture fixture = new Fixture(true)) {
            fixture.select("modB");
            SpeculativeResourceContext.enter();
            try {
                assertEquals("A", fixture.read("shared.txt"));
            } finally {
                SpeculativeResourceContext.exit();
            }
            assertEquals("B", fixture.read("shared.txt"));
            fixture.skipMods();
            SpeculativeResourceContext.enter();
            try {
                assertEquals("A", fixture.read("shared.txt"));
            } finally {
                SpeculativeResourceContext.exit();
            }
            assertEquals("base", fixture.read("shared.txt"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void skipModsCannotBeStolenByAnotherThread(boolean shortLocks) throws Exception {
        try (Fixture fixture = new Fixture(shortLocks)) {
            var executor = Executors.newSingleThreadExecutor();
            CountDownLatch selected = new CountDownLatch(1);
            CountDownLatch consumed = new CountDownLatch(1);
            try {
                var owner = executor.submit(() -> {
                    fixture.skipMods();
                    selected.countDown();
                    assertTrue(consumed.await(5, TimeUnit.SECONDS));
                    assertEquals("base", fixture.read("shared.txt"));
                    assertEquals("A", fixture.read("shared.txt"));
                    return null;
                });
                assertTrue(selected.await(5, TimeUnit.SECONDS));
                try {
                    assertEquals("A", fixture.read("shared.txt"));
                } finally {
                    consumed.countDown();
                }
                owner.get(5, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unrelatedReaderCannotStealModSelection(boolean shortLocks) throws Exception {
        try (Fixture fixture = new Fixture(shortLocks)) {
            var executor = Executors.newSingleThreadExecutor();
            CountDownLatch selected = new CountDownLatch(1);
            CountDownLatch consumed = new CountDownLatch(1);
            try {
                var owner = executor.submit(() -> {
                    fixture.select("modB");
                    selected.countDown();
                    assertTrue(consumed.await(5, TimeUnit.SECONDS));
                    assertEquals("B", fixture.read("shared.txt"));
                    assertEquals("A", fixture.read("shared.txt"));
                    return null;
                });
                assertTrue(selected.await(5, TimeUnit.SECONDS));
                try {
                    assertEquals("mission", fixture.read("mission.txt"));
                    assertEquals("A", fixture.read("shared.txt"));
                } finally {
                    consumed.countDown();
                }
                owner.get(5, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void explicitClearAndFailureConsumeOnlyCurrentThreadsSelection(boolean shortLocks)
            throws Exception {
        try (Fixture fixture = new Fixture(shortLocks)) {
            fixture.select("modB");
            fixture.select(null);
            assertEquals("A", fixture.read("shared.txt"));
            fixture.select("modA");
            fixture.select("modB");
            assertEquals("B", fixture.read("shared.txt"));
            fixture.select("modB");
            assertThrows(java.lang.reflect.InvocationTargetException.class,
                    () -> fixture.read("mission.txt"));
            assertEquals("mission", fixture.read("mission.txt"));
        }
    }

    private final class Fixture implements AutoCloseable {
        private final Loader loader;
        private final Object resource;
        private final Method select;
        private final Method open;

        Fixture(boolean shortLocks) throws Exception {
            this(shortLocks, true);
        }

        Fixture(boolean shortLocks, boolean isolated) throws Exception {
            Path jar = Path.of("..", "game data", "fs.common_obf.jar");
            ClassNode node = new ClassNode();
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                new ClassReader(zip.getInputStream(zip.getEntry("com/fs/util/C.class")))
                        .accept(node, 0);
            }
            PatchContext context = new PatchContext(JarWorkspace.COMMON_OBF_JAR,
                    "com/fs/util/C.class");
            if (shortLocks) {
                new ResourceLeafSynchronizationPatch().applyAndVerify(node, context)
                        .requireSuccess();
                new ResourceLookupSynchronizationPatch().applyAndVerify(node, context)
                        .requireSuccess();
            }
            for (JarPatch patch : PatchRegistry.patches()) {
                if (isolated && patch.id().equals("resource-context-isolation")) {
                    patch.applyAndVerify(node, context).requireSuccess();
                }
            }
            if (shortLocks) {
                for (var method : node.methods) {
                    for (var insn : AsmUtil.instructions(method)) {
                        if (insn instanceof TypeInsnNode typeInsn
                                && typeInsn.getOpcode() == Opcodes.NEW
                                && typeInsn.desc.equals("java/io/FileInputStream")) {
                            var check = new InsnList();
                            check.add(new VarInsnNode(Opcodes.ALOAD, 0));
                            check.add(new MethodInsnNode(
                                    Opcodes.INVOKESTATIC,
                                    Type.getInternalName(ResourceContextIsolationPatchTest.class),
                                    "assertUnlockedAtFileOpen", "(Ljava/lang/Object;)V", false));
                            method.instructions.insertBefore(insn, check);
                            method.maxStack++;
                        }
                    }
                }
            }
            ClassWriter writer = new ClassWriter(0);
            node.accept(writer);
            loader = new Loader(jar.toUri().toURL());
            Class<?> type = loader.define(writer.toByteArray());
            resource = newResource(type);
            select = type.getMethod("\u00d600000", String.class);
            open = Arrays.stream(type.getDeclaredMethods())
                    .filter(m -> m.getReturnType() == InputStream.class)
                    .filter(m -> Arrays.equals(m.getParameterTypes(),
                            new Class<?>[] {String.class, boolean.class}))
                    .findFirst().orElseThrow();
        }

        Object newResource() throws Exception { return newResource(resource.getClass()); }

        Object newResource(Class<?> type) throws Exception {
            Object instance = type.getConstructor().newInstance();
            Path a = temporary.resolve("modA"), b = temporary.resolve("modB");
            Files.createDirectories(a);
            Files.createDirectories(b);
            Files.writeString(a.resolve("mission.txt"), "mission");
            Files.writeString(a.resolve("shared.txt"), "A");
            Files.writeString(b.resolve("shared.txt"), "B");
            Method add = type.getMethod("o00000", String.class, boolean.class, Object.class);
            Path base = temporary.resolve("base");
            Files.createDirectories(base);
            Files.writeString(base.resolve("shared.txt"), "base");
            add.invoke(instance, base.toString(), false, null);
            add.invoke(instance, b.toString(), true, null);
            add.invoke(instance, a.toString(), true, null);
            return instance;
        }

        void select(String name) throws Exception { select.invoke(resource, name); }

        void skipMods() throws Exception {
            ResourceReadContext.skipMods(true);
        }

        String read(String name) throws Exception {
            return readFrom(resource, name);
        }

        String readFrom(Object instance, String name) throws Exception {
            try (InputStream in = (InputStream) open.invoke(instance, name, true)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }

        public void close() throws Exception { loader.close(); }
    }

    private static final class Loader extends URLClassLoader {
        Loader(URL url) { super(new URL[] {url}, ResourceContextIsolationPatchTest.class.getClassLoader()); }
        Class<?> define(byte[] data) { return defineClass(null, data, 0, data.length); }
    }
}
