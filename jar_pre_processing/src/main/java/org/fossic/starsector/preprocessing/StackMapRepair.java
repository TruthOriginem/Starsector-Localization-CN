package org.fossic.starsector.preprocessing;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 字符串解耦之后的 StackMapTable 修复步骤。
 *
 * <p>jar-string-decoupler 会原地重写方法体（替换字符串 LDC 位点），被重写的方法
 * 会丢失或留下过期的 StackMapTable。Java 8 时代的 JVM 关闭字节码校验时这无关紧要，
 * 但 Java 27+ 对所有类强制校验（包括运行时由 -javaagent 改写过的类），任何带分支
 * 却没有有效帧表的方法都会在首次执行时抛出 VerifyError / ClassFormatError，
 * 直接导致游戏无法在 JDK 27/28 启动链上运行。
 *
 * <p>修复方式：用 ASM 的 COMPUTE_FRAMES 重建帧表（配合 EXPAND_FRAMES 读取）。
 * 公共父类解析不通过 Class.forName 加载游戏类，而是读取预先建立的全量类字节
 * 索引，避免修复过程中初始化任何游戏类。无法解析层级关系的类保持原样并在
 * 日志中报告，不会写入半成品。
 */
final class StackMapRepair {
    private final JarWorkspace workspace;
    private final Hierarchy hierarchy = new Hierarchy();

    StackMapRepair(JarWorkspace workspace) {
        this.workspace = workspace;
    }

    /** 对单个解耦后的 jar 原地修复帧表，返回被修复的类名列表。 */
    List<String> repairJar(String jarName) throws IOException {
        Path jar = workspace.decoupledJar(jarName);
        buildIndex();

        List<String> repaired = new ArrayList<>();
        Map<String, byte[]> replacements = new HashMap<>();
        try (ZipFile zipFile = new ZipFile(jar.toFile())) {
            for (ZipEntry entry : zipFile.stream().toList()) {
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                    continue;
                }
                byte[] raw = zipFile.getInputStream(entry).readAllBytes();
                if (!needsRepair(raw)) {
                    continue;
                }
                String internal = entry.getName()
                        .substring(0, entry.getName().length() - ".class".length());
                byte[] fixed;
                try {
                    fixed = rebuildWithComputedFrames(raw);
                } catch (Throwable t) {
                    System.out.println("[stack-map] 跳过 " + internal + "（重建失败: " + t + "）");
                    continue;
                }
                replacements.put(entry.getName(), fixed);
                repaired.add(internal);
            }
        }
        if (repaired.isEmpty()) {
            return repaired;
        }
        rewriteJar(jar, replacements);
        for (String internal : repaired) {
            System.out.println("[stack-map] 已重建帧表: " + internal.replace('/', '.'));
        }
        return repaired;
    }

    private void buildIndex() throws IOException {
        for (String jarName : JarWorkspace.jars()) {
            try (ZipFile zipFile = new ZipFile(workspace.decoupledJar(jarName).toFile())) {
                for (ZipEntry entry : zipFile.stream().toList()) {
                    if (!entry.isDirectory() && entry.getName().endsWith(".class")) {
                        String internal = entry.getName()
                                .substring(0, entry.getName().length() - ".class".length());
                        hierarchy.index(internal, zipFile.getInputStream(entry).readAllBytes());
                    }
                }
            }
        }
    }

    /** 只要存在"带分支却缺 FrameNode"的方法就判定需要修复。 */
    static boolean needsRepair(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        for (MethodNode method : node.methods) {
            if (method.instructions.size() == 0) {
                continue;
            }
            boolean hasFrame = false;
            boolean branched = false;
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof FrameNode) {
                    hasFrame = true;
                }
                int opcode = insn.getOpcode();
                if ((opcode >= Opcodes.IFEQ && opcode <= Opcodes.LOOKUPSWITCH)
                        || opcode == Opcodes.GOTO
                        || opcode == Opcodes.JSR
                        || opcode == Opcodes.RET
                        || opcode == Opcodes.IFNULL
                        || opcode == Opcodes.IFNONNULL
                        || opcode == 0xC8   // GOTO_W
                        || opcode == 0xC9) { // JSR_W
                    branched = true;
                }
            }
            if (branched && !hasFrame) {
                return true;
            }
        }
        return false;
    }

    /** EXPAND_FRAMES 读取 + COMPUTE_FRAMES 重写；层级解析走类字节索引。 */
    static byte[] rebuildWithComputedFrames(byte[] bytes, Hierarchy hierarchy) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                return hierarchy.commonSuperClass(type1, type2);
            }
        };
        reader.accept(writer, ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    private byte[] rebuildWithComputedFrames(byte[] bytes) {
        return rebuildWithComputedFrames(bytes, hierarchy);
    }

    private void rewriteJar(Path jar, Map<String, byte[]> replacements) throws IOException {
        Path tmp = jar.resolveSibling(jar.getFileName() + ".stackmap-fixing");
        try (ZipFile zipFile = new ZipFile(jar.toFile());
             ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(tmp))) {
            for (ZipEntry entry : zipFile.stream().toList()) {
                byte[] data = replacements.containsKey(entry.getName())
                        ? replacements.get(entry.getName())
                        : zipFile.getInputStream(entry).readAllBytes();
                ZipEntry newEntry = new ZipEntry(entry.getName());
                newEntry.setTime(entry.getTime());
                out.putNextEntry(newEntry);
                out.write(data);
                out.closeEntry();
            }
        }
        Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * 基于"类字节索引"的类型层级：解析公共父类时只读取索引里的原始字节，
     * 不加载、不初始化任何游戏类；JDK 自身类型走反射（不触发初始化）。
     */
    static final class Hierarchy {
        private final Map<String, byte[]> classes = new HashMap<>();
        private final Map<String, Set<String>> closures = new HashMap<>();

        void index(String internalName, byte[] bytes) {
            classes.put(internalName, bytes);
        }

        Set<String> closure(String type) {
            Set<String> cached = closures.get(type);
            if (cached != null) {
                return cached;
            }
            Set<String> result = new HashSet<>();
            Deque<String> work = new ArrayDeque<>();
            work.push(type);
            while (!work.isEmpty()) {
                String current = work.poll();
                if (current == null || !result.add(current)) {
                    continue;
                }
                if (current.startsWith("[")) {
                    result.add("java/lang/Object");
                    continue;
                }
                if (isJdkType(current)) {
                    try {
                        Class<?> loaded = Class.forName(
                                current.replace('/', '.'), false, Hierarchy.class.getClassLoader());
                        Class<?> parent = loaded.getSuperclass();
                        if (parent != null) {
                            work.push(parent.getName().replace('/', '.'));
                        }
                        for (Class<?> iface : loaded.getInterfaces()) {
                            work.push(iface.getName().replace('/', '.'));
                        }
                    } catch (ClassNotFoundException ignored) {
                        // 解析不到的超类型不影响其余候选的合并
                    }
                    continue;
                }
                byte[] bytes = classes.get(current);
                if (bytes == null) {
                    throw new IllegalArgumentException("Could not resolve class " + current);
                }
                ClassReader reader = new ClassReader(bytes);
                if (reader.getSuperName() != null) {
                    work.push(reader.getSuperName());
                }
                for (String iface : reader.getInterfaces()) {
                    work.push(iface);
                }
            }
            closures.put(type, result);
            return result;
        }

        String commonSuperClass(String type1, String type2) {
            if (type1.equals(type2)) {
                return type1;
            }
            Set<String> closure1 = closure(type1);
            List<String> common = new ArrayList<>();
            for (String candidate : closure(type2)) {
                if (closure1.contains(candidate)) {
                    common.add(candidate);
                }
            }
            if (common.isEmpty()) {
                return "java/lang/Object";
            }
            String best = "java/lang/Object";
            int bestSize = closure(best).size();
            for (String candidate : common) {
                int size = closure(candidate).size();
                if (size > bestSize) {
                    best = candidate;
                    bestSize = size;
                }
            }
            return best;
        }

        private static boolean isJdkType(String internalName) {
            return internalName.startsWith("java/")
                    || internalName.startsWith("javax/")
                    || internalName.startsWith("sun/")
                    || internalName.startsWith("jdk/")
                    || internalName.startsWith("com/sun/")
                    || internalName.startsWith("org/w3c/")
                    || internalName.startsWith("org/xml/")
                    || internalName.startsWith("org/ietf/");
        }
    }
}
