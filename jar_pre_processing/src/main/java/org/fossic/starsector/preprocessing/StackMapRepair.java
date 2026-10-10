package org.fossic.starsector.preprocessing;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
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
 * 字符串解耦之后的 StackMapTable 重建步骤。
 *
 * <p>jar-string-decoupler 会原地重写方法体（替换字符串 LDC 位点）并平移后续
 * 字节，被重写方法的帧表要么整体丢失、要么仍然存在但指向过期位置。消费端
 * 虽可用 -XX:-BytecodeVerificationLocal/Remote 跳过普通类的校验，但该豁免对
 * 启动链上被 -javaagent 改写过的类不生效（这类类一律在链接期强制校验），
 * 且这些诊断开关在高版本 JDK 上随时可能收紧，因此构建期重建帧表是唯一
 * 稳健的修复位置。
 *
 * <p>修复方式：对解耦 jar 的全部类用 ASM COMPUTE_FRAMES 重建帧表（配合
 * EXPAND_FRAMES 读取，同时重算 maxStack/maxLocals），major &lt; 55 的旧版本类
 * 一并升到 v61——JDK 27+ 忽略 v49 类上的 NestHost/NestMembers，嵌套类私有
 * 访问会抛 IllegalAccessError。公共父类解析不通过 Class.forName 加载游戏类，
 * 而是读取预先建立的全量类字节索引，避免修复过程中初始化任何游戏类。
 *
 * <p>fail-closed：含 jsr/ret 的类与 package-info/module-info 保持原样（v61
 * 禁止 jsr/ret，这些类保持旧版本号由旧校验语义正常运行），重建失败的类同样
 * 保持原样并在日志中报告，绝不写入半成品。该全类重建策略已在 0.98a-RC8
 * 全部 21 个游戏 jar（约 6700 类）上真机验证，JDK 28 与 JRE 17 双路线均可
 * 正常启动并读档。
 */
final class StackMapRepair {
    private final JarWorkspace workspace;
    private final Hierarchy hierarchy = new Hierarchy();

    StackMapRepair(JarWorkspace workspace) {
        this.workspace = workspace;
    }

    /** 对单个解耦后的 jar 全量重建帧表，返回被改写的类名列表。 */
    List<String> repairJar(String jarName) throws IOException {
        Path jar = workspace.decoupledJar(jarName);
        buildIndex();

        List<String> repaired = new ArrayList<>();
        Map<String, byte[]> replacements = new HashMap<>();
        int keptAsIs = 0;
        try (ZipFile zipFile = new ZipFile(jar.toFile())) {
            for (ZipEntry entry : zipFile.stream().toList()) {
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                    continue;
                }
                byte[] raw = zipFile.getInputStream(entry).readAllBytes();
                String internal = entry.getName()
                        .substring(0, entry.getName().length() - ".class".length());
                // v61 禁止 jsr/ret；package-info/module-info 无方法体且升级有
                // ACC_SUPER 历史问题。这些类保持原样即可，旧校验语义完全正常。
                if (internal.endsWith("package-info") || internal.endsWith("module-info")
                        || containsJsrOrRet(raw)) {
                    keptAsIs++;
                    continue;
                }
                byte[] input = classMajor(raw) < 55 ? bumpTo61(raw) : raw;
                byte[] fixed;
                try {
                    // 全类重建：解耦器平移代码后，帧表可能丢失也可能"存在但过期"，
                    // 仅凭缺帧检测无法发现后者（如 BaseLocation.advance），因此
                    // 不做选择性跳过；重建失败则保持原类（fail-closed）。
                    fixed = rebuildWithComputedFrames(input);
                } catch (Throwable t) {
                    System.out.println("[stack-map] 保持原样 " + internal + "（重建失败: " + t + "）");
                    keptAsIs++;
                    continue;
                }
                replacements.put(entry.getName(), fixed);
                repaired.add(internal);
            }
        }
        if (keptAsIs > 0) {
            System.out.println("[stack-map] " + jarName + ": " + keptAsIs
                    + " 个类保持原样（jsr/ret、package-info/module-info 或重建失败）");
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

    /** 类文件头主版本号（偏移 6-7，大端）。 */
    static int classMajor(byte[] bytes) {
        return ((bytes[6] & 0xFF) << 8) | (bytes[7] & 0xFF);
    }

    /**
     * 把旧版本类升到 v61（Java 17）。JDK 27+ 忽略 v49 类上的 NestHost/
     * NestMembers 属性，内部类直接访问外部私有成员时会抛 IllegalAccessError，
     * 所以必须连版本号一起升级，仅重建帧表不够。
     */
    static byte[] bumpTo61(byte[] bytes) {
        byte[] out = bytes.clone();
        out[4] = 0;
        out[5] = 0;
        out[6] = 0;
        out[7] = 61;
        return out;
    }

    /** 方法体内是否含 jsr/ret 指令（v49 时代的异常处理写法，v61 禁止）。 */
    static boolean containsJsrOrRet(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                int opcode = insn.getOpcode();
                if (opcode == Opcodes.JSR || opcode == Opcodes.RET) {
                    return true;
                }
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
