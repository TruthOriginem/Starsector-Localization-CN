package org.fossic.starsector.preprocessing.patches;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** ClassWriter(0) round-trip plus JVM linking for patched game-data classes. */
final class GameDataPatchVerifier {
    private static final List<Path> GAME_JARS = List.of(
            Path.of("..", "game data", "starfarer_obf.jar"),
            Path.of("..", "game data", "fs.common_obf.jar"),
            Path.of("..", "game data", "fs.sound_obf.jar"),
            Path.of("..", "game data", "starfarer.api.jar"));

    private GameDataPatchVerifier() {
    }

    static ClassNode roundTrip(ClassNode source) {
        ClassNode result = new ClassNode();
        new ClassReader(write(source)).accept(result, 0);
        return result;
    }

    static void verifyWithJvm(ClassNode source)
            throws ClassNotFoundException {
        verifyWithJvm(List.of(source));
    }

    static void verifyWithJvm(List<ClassNode> sources)
            throws ClassNotFoundException {
        LinkedHashMap<String, byte[]> definitions = new LinkedHashMap<>();
        for (ClassNode source : sources) {
            definitions.put(source.name.replace('/', '.'), write(source));
        }
        GameDataClassLoader loader = new GameDataClassLoader(definitions);
        for (String className : definitions.keySet()) {
            loader.loadAndResolve(className);
        }
    }

    private static byte[] write(ClassNode source) {
        ClassWriter writer = new ClassWriter(0);
        source.accept(writer);
        return writer.toByteArray();
    }

    private static final class GameDataClassLoader extends ClassLoader {
        private final Map<String, byte[]> patchedDefinitions;

        private GameDataClassLoader(Map<String, byte[]> patchedDefinitions) {
            super(GameDataPatchVerifier.class.getClassLoader());
            this.patchedDefinitions = Map.copyOf(patchedDefinitions);
        }

        private Class<?> loadAndResolve(String name)
                throws ClassNotFoundException {
            return loadClass(name, true);
        }

        @Override
        protected Class<?> findClass(String name)
                throws ClassNotFoundException {
            byte[] definition = patchedDefinitions.get(name);
            if (definition == null) {
                definition = readGameClass(name);
            }
            if (definition == null) {
                throw new ClassNotFoundException(name);
            }
            // The shipped obfuscator emits member names containing dots
            // (for example, "float.new").  Starsector's launcher accepts
            // those files, while a stock test JVM rejects the pre-existing
            // names before reaching our patched instructions.  Rename only
            // those illegal member identifiers, consistently in declarations
            // and references, so stock-JVM linking can exercise the frames and
            // descriptors produced by the Patch.
            definition = sanitizeObfuscatedMemberNames(definition);
            return defineClass(name, definition, 0, definition.length);
        }

        private static byte[] readGameClass(String name) {
            String entryName = name.replace('.', '/') + ".class";
            for (Path jar : GAME_JARS) {
                try (ZipFile input = new ZipFile(jar.toFile())) {
                    ZipEntry entry = input.getEntry(entryName);
                    if (entry != null) {
                        return input.getInputStream(entry).readAllBytes();
                    }
                } catch (IOException failure) {
                    throw new IllegalStateException(
                            "Unable to read game class " + name
                                    + " from " + jar,
                            failure);
                }
            }
            return null;
        }

        private static byte[] sanitizeObfuscatedMemberNames(
                byte[] definition) {
            ClassNode node = new ClassNode();
            new ClassReader(definition).accept(node, 0);
            node.fields.forEach(field ->
                    field.name = legalMemberName(field.name, false));
            node.methods.forEach(method -> {
                method.name = legalMemberName(method.name, true);
                method.instructions.forEach(instruction -> {
                    if (instruction instanceof FieldInsnNode field) {
                        field.name = legalMemberName(field.name, false);
                    } else if (instruction instanceof MethodInsnNode call) {
                        call.name = legalMemberName(call.name, true);
                    }
                });
            });
            if (node.outerMethod != null) {
                node.outerMethod = legalMemberName(node.outerMethod, true);
            }
            ClassWriter writer = new ClassWriter(0);
            node.accept(writer);
            return writer.toByteArray();
        }

        private static String legalMemberName(
                String name, boolean method) {
            if (method && ("<init>".equals(name)
                    || "<clinit>".equals(name))) {
                return name;
            }
            StringBuilder result = null;
            for (int index = 0; index < name.length(); index++) {
                char character = name.charAt(index);
                boolean illegal = character == '.'
                        || character == ';'
                        || character == '['
                        || character == '/'
                        || (method && (character == '<'
                                || character == '>'));
                if (illegal) {
                    if (result == null) {
                        result = new StringBuilder(name.length() + 8);
                        result.append(name, 0, index);
                    }
                    result.append('$');
                    String hex = Integer.toHexString(character);
                    result.append("0".repeat(4 - hex.length())).append(hex);
                } else if (result != null) {
                    result.append(character);
                }
            }
            return result == null ? name : result.toString();
        }
    }
}
