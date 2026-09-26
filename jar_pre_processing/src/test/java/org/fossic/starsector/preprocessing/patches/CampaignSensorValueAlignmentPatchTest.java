package org.fossic.starsector.preprocessing.patches;

import org.fossic.starsector.preprocessing.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

final class CampaignSensorValueAlignmentPatchTest {
    @Test
    void realGamePositionKeepsRightInsetAfterValueResizesAndPanelMoves() throws Exception {
        URL[] jars;
        try (var paths = Files.list(Path.of("..", "game data"))) {
            jars = paths.filter(p -> p.toString().endsWith(".jar"))
                    .map(p -> {
                        try { return p.toUri().toURL(); }
                        catch (Exception e) { throw new RuntimeException(e); }
                    }).toArray(URL[]::new);
        }
        // The game permits obfuscated member names containing dots; stock test JVMs do not.
        // Rename those members consistently while retaining the original method bodies.
        try (var loader = new URLClassLoader(jars, ClassLoader.getPlatformClassLoader()) {
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                URL resource = findResource(name.replace('.', '/') + ".class");
                if (resource == null) throw new ClassNotFoundException(name);
                try (var in = resource.openStream()) {
                    ClassWriter writer = new ClassWriter(0);
                    new ClassReader(in).accept(new ClassRemapper(writer, new Remapper() {
                        @Override public String mapFieldName(String owner, String name, String descriptor) {
                            return name.replace('.', '$');
                        }
                        @Override public String mapMethodName(String owner, String name, String descriptor) {
                            return name.replace('.', '$');
                        }
                    }), 0);
                    byte[] bytes = writer.toByteArray();
                    return defineClass(name, bytes, 0, bytes.length);
                } catch (java.io.IOException e) { throw new ClassNotFoundException(name, e); }
            }
        }) {
            Class<?> position = loader.loadClass("com.fs.starfarer.ui.OO0O");
            Object panel = position.getConstructor(float.class, float.class).newInstance(65.25f, 24f);
            Object value = position.getConstructor(float.class, float.class, float.class,
                    float.class, position).newInstance(0f, 0f, 35f, 15f, panel);
            position.getMethod("add", position).invoke(panel, value);
            position.getMethod("inRMid", float.class).invoke(value, 3f);
            for (float x : new float[]{0f, 100f}) {
                position.getMethod("setLocation", float.class, float.class).invoke(panel, x, 50f);
                for (float width : new float[]{35f, 41.707f, 25.024f, 37.38f, 55f}) {
                    position.getMethod("setSize", float.class, float.class).invoke(value, width, 15f);
                    float left = (float) position.getMethod("getX").invoke(value);
                    // Original UI rounds the left coordinate to a logical pixel.
                    assertEquals(x + 65.25f - 3f, left + width, 0.51f);
                    assertTrue(left + width < x + 65.25f);
                }
            }
        }
    }

    @Test
    void anchorsOnlyTheTwoSensorValuesWithoutChangingPanelGeometry() throws Exception {
        ClassNode node = read();
        byte[] original = bytes(node);
        apply(node);
        int anchored = 0;
        for (MethodNode method : node.methods) {
            for (var insn : AsmUtil.instructions(method)) {
                if (!(insn instanceof MethodInsnNode setter) || !setter.name.equals("setAlignment")
                        || !method.name.equals("OOO000")) continue;
                var before = setter.getPrevious().getPrevious();
                assertEquals(Opcodes.DUP, before.getOpcode());
                var next = setter.getNext();
                assertEquals(Opcodes.DUP, next.getOpcode());
                assertEquals("getRenderer", ((MethodInsnNode) next.getNext()).name);
                assertEquals(Opcodes.ICONST_0, next.getNext().getNext().getOpcode());
                assertEquals("return", ((MethodInsnNode) next.getNext().getNext().getNext()).name);
                var position = next.getNext().getNext().getNext().getNext();
                assertEquals("getPosition", ((MethodInsnNode) position).name);
                assertEquals(3f, ((LdcInsnNode) position.getNext()).cst);
                assertEquals("inRMid", ((MethodInsnNode) position.getNext().getNext()).name);
                assertEquals(Opcodes.POP, position.getNext().getNext().getNext().getOpcode());
                method.instructions.remove(before);
                for (int i = 0; i < 8; i++) {
                    var following = next.getNext();
                    method.instructions.remove(next);
                    next = following;
                }
                anchored++;
            }
            if (method.name.equals("OOO000")) method.maxStack--;
        }
        assertEquals(2, anchored);
        // Removing just the two injected blocks must recover the complete original class.
        assertArrayEquals(original, bytes(node));
    }

    @Test
    void rejectsChangedAlignmentBeforePartiallyPatching() throws Exception {
        ClassNode node = read();
        MethodNode method = node.methods.stream().filter(m -> m.name.equals("OOO000"))
                .findFirst().orElseThrow();
        for (var insn : method.instructions) {
            if (insn instanceof FieldInsnNode field && field.name.equals("RMID")) {
                field.name = "LMID";
                break;
            }
        }
        byte[] before = bytes(node);
        assertThrows(PatchException.class, () -> apply(node));
        assertArrayEquals(before, bytes(node));
    }

    @Test
    void rejectsDuplicateApplication() throws Exception {
        ClassNode node = read();
        apply(node);
        assertThrows(PatchException.class, () -> apply(node));
    }

    private static ClassNode read() throws Exception {
        return CampaignDateWidthPatchTest.readClass("starfarer_obf.jar",
                CampaignSensorValueAlignmentPatch.TARGET);
    }

    private static byte[] bytes(ClassNode node) {
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void apply(ClassNode node) {
        new CampaignSensorValueAlignmentPatch().applyAndVerify(node,
                new PatchContext("starfarer_obf.jar", node.name + ".class")).requireSuccess();
    }
}
