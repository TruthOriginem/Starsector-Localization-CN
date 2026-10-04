package org.fossic.starsector.preprocessing.patches;

import static org.junit.jupiter.api.Assertions.*;

import org.fossic.starsector.preprocessing.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipFile;

/**
 * Executes the real matcher bytecode, with only invalid obfuscated names and GL invalidation
 * removed.
 */
final class RendererCjkHighlightPatchTest {
    static final String OWNER = RendererDynFontPatch.RENDERER_CLASS;

    @Test
    void wrapsAndChineseBoundaries() throws Exception {
        for (String s : List.of("附加核心易损状态", "附加核心易\n损状态", "核心\n易\n损", "A核心易损B")) {
            Fixture f = new Fixture(true, s);
            f.all(true, "核心易损");
            assertEquals(4, f.markedVisible(), s);
        }
        for (String s : List.of("，EMP。", "“EMP”", "【EMP】", "…EMP—", "中EMP文", "𠀀EMP𠀁")) {
            Fixture f = new Fixture(true, s);
            f.all(true, "EMP");
            assertEquals(3, f.markedVisible(), s);
        }
        // Allowing one boundary must not bypass the independent check on the other side.
        for (String s : List.of("，EMPword", "wordEMP，", "中EMPword", "wordEMP中")) {
            Fixture f = new Fixture(true, s);
            f.all(true, "EMP");
            assertEquals(0, f.markedVisible(), s);
        }
    }

    @Test
    void firstAndLastChooseActualOccurrenceAndEnd() throws Exception {
        for (boolean last : new boolean[] {false, true}) {
            Fixture f = new Fixture(true, "核心易\n损 核心易损 核心\n易损");
            f.single(last, "核心易损");
            assertArrayEquals(last ? new int[] {11, 15} : new int[] {0, 4}, f.range());
        }
    }

    @Test
    void bulkKeepsOrderedColorsAndActualEnd() throws Exception {
        Fixture f = new Fixture(true, "核心易\n损核心易损50%");
        f.all(true, "核心易损", "核心易损", "50%");
        assertArrayEquals(new int[] {0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2}, f.colors());
        assertEquals(11, f.markedVisible());
    }

    @Test
    void doesNotInventMatchesAcrossParagraphsOrSpaces() throws Exception {
        for (String s : List.of("核心 易损", "核心\n\n易损", "核心\n \n易损", "核心\t易损", "核心\r\n易损")) {
            Fixture f = new Fixture(true, s);
            f.all(false, "核心易损");
            assertEquals(0, f.markedVisible(), s);
        }
    }

    @Test
    void legacyLatinBehaviorAndColorOrderingRemainIdentical() throws Exception {
        for (String s :
                List.of(
                        "foobar",
                        "150",
                        "1.5",
                        "1,500",
                        "ＡEMPＢ",
                        " core\nfragility core fragility ",
                        " EMP EMP EMP ",
                        " 50% +20% [x] ")) {
            for (boolean boundary : new boolean[] {false, true}) {
                Fixture old = new Fixture(false, s), fixed = new Fixture(true, s);
                String[] targets = {
                    "", null, "bar", "50", "1", "EMP", "EMP", "core fragility", "+20%", "[x]"
                };
                old.all(boundary, targets);
                fixed.all(boundary, targets);
                assertArrayEquals(old.mask(), fixed.mask(), s);
                assertArrayEquals(old.colors(), fixed.colors(), s);
            }
        }
    }

    @Test
    void numericGuardsStillExecuteAfterChineseBoundaryAllowance() throws Exception {
        for (String s : List.of("核心.5", "核心,500")) {
            Fixture f = new Fixture(true, s);
            f.all(true, "核心");
            assertEquals(0, f.markedVisible());
        }
        Fixture f = new Fixture(true, "核心易\n损 核心易损 核心易\n损");
        f.all(true, "核心易损", "核心易损", "核心易损");
        assertEquals(12, f.markedVisible());
    }

    @Test
    void originalFirstLastAndExplicitNewlinesRemainUnchangedForLatin() throws Exception {
        for (String text :
                List.of(
                        "core fragility core fragility",
                        "core\nfragility core\nfragility",
                        "[x] +20%",
                        "core  fragility")) {
            for (String target : List.of("core fragility", "core\nfragility", "", "absent")) {
                for (boolean last : new boolean[] {true, false}) {
                    Fixture a = new Fixture(false, text), b = new Fixture(true, text);
                    a.single(last, target);
                    b.single(last, target);
                    assertArrayEquals(a.range(), b.range());
                }
            }
        }
    }

    @Test
    void rejectsDuplicateApplicationAndDrift() throws Exception {
        ClassNode n = original();
        RendererCjkHighlightPatch p = new RendererCjkHighlightPatch();
        PatchContext ctx = new PatchContext("fs.common_obf.jar", OWNER + ".class");
        p.applyAndVerify(n, ctx).requireSuccess();
        assertThrows(PatchException.class, () -> p.applyAndVerify(n, ctx));
        ClassNode missing = original();
        missing.methods.removeIf(
                m -> m.name.equals("Ø00000") && m.desc.equals("(Ljava/lang/String;)V"));
        assertThrows(PatchException.class, () -> p.applyAndVerify(missing, ctx));
        ClassNode changed = original();
        for (MethodNode m : changed.methods)
            if (m.desc.equals("(Z[Ljava/lang/String;)V"))
                for (AbstractInsnNode i : m.instructions)
                    if (i instanceof MethodInsnNode c && c.name.equals("isWhitespace"))
                        c.name = "isSpaceChar";
        assertThrows(PatchException.class, () -> p.applyAndVerify(changed, ctx));
    }

    @Test
    void modifiesExactlyThreeMethods() throws Exception {
        ClassNode before = original(), after = original();
        new RendererCjkHighlightPatch()
                .applyAndVerify(after, new PatchContext("fs.common_obf.jar", OWNER + ".class"));
        Set<String> changed = new HashSet<>();
        for (int i = 0; i < before.methods.size(); i++)
            if (!Arrays.equals(
                    methodBytes(before.methods.get(i)), methodBytes(after.methods.get(i))))
                changed.add(before.methods.get(i).name + before.methods.get(i).desc);
        assertEquals(
                Set.of(
                        "o00000(Z[Ljava/lang/String;)V",
                        "Ø00000(Ljava/lang/String;)V",
                        "Ô00000(Ljava/lang/String;)V"),
                changed);
        assertEquals(before.fields.size(), after.fields.size());
    }

    private static byte[] methodBytes(MethodNode m) {
        ClassWriter w = new ClassWriter(0);
        w.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/Compare", null, "java/lang/Object", null);
        m.accept(w);
        return w.toByteArray();
    }

    static ClassNode original() throws Exception {
        try (ZipFile z = new ZipFile(Path.of("..", "game data", "fs.common_obf.jar").toFile())) {
            ClassNode n = new ClassNode();
            new ClassReader(z.getInputStream(z.getEntry(OWNER + ".class"))).accept(n, 0);
            return n;
        }
    }

    static final class Fixture {
        final Class<?> type;
        final Object value;
        final String text;

        Fixture(boolean patched, String text) throws Exception {
            this.text = text;
            ClassNode n = original();
            if (patched)
                new RendererCjkHighlightPatch()
                        .applyAndVerify(n, new PatchContext("fs.common_obf.jar", OWNER + ".class"))
                        .requireSuccess();
            Set<String> methods =
                    Set.of(
                            "o00000(Z[Ljava/lang/String;)V",
                            "Ø00000(Ljava/lang/String;)V",
                            "Ô00000(Ljava/lang/String;)V",
                            "Object(II)V");
            n.methods.removeIf(m -> !methods.contains(m.name + m.desc));
            n.fields.clear();
            n.interfaces.clear();
            n.innerClasses.clear();
            n.outerClass = null;
            n.name = "test/HighlightFixture";
            n.superName = "java/lang/Object";
            n.access = Opcodes.ACC_PUBLIC;
            Map<String, String> names =
                    Map.of(
                            "while.super",
                            "text",
                            "null.new",
                            "mask",
                            "Oo0000",
                            "colors",
                            "interface.new",
                            "start",
                            "interface.super",
                            "end");
            Map<String, String> fields = new HashMap<>();
            for (MethodNode m : n.methods) {
                m.localVariables = null;
                m.tryCatchBlocks.clear();
                for (AbstractInsnNode i : m.instructions.toArray()) {
                    if (i instanceof FrameNode) m.instructions.remove(i);
                    if (i instanceof FieldInsnNode f && f.owner.equals(OWNER)) {
                        f.owner = n.name;
                        f.name = names.getOrDefault(f.name, "unused");
                        fields.put(f.name, f.desc);
                    }
                    if (i instanceof MethodInsnNode call) {
                        if (call.owner.equals(OWNER)) call.owner = n.name;
                        if (call.owner.equals("com/fs/graphics/util/GLListManager")) {
                            // Consume its token without entering any OpenGL code.
                            m.instructions.set(call, new InsnNode(Opcodes.POP));
                        }
                    }
                }
            }
            for (var e : fields.entrySet()) {
                String desc = e.getKey().equals("unused") ? "Ljava/lang/Object;" : e.getValue();
                n.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, e.getKey(), desc, null, null));
            }
            for (MethodNode m : n.methods)
                for (AbstractInsnNode i : m.instructions)
                    if (i instanceof FieldInsnNode f && f.name.equals("unused"))
                        f.desc = "Ljava/lang/Object;";
            MethodNode ctor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            ctor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            ctor.instructions.add(
                    new MethodInsnNode(
                            Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
            ctor.instructions.add(new InsnNode(Opcodes.RETURN));
            n.methods.add(ctor);
            ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            n.accept(w);
            byte[] bytes = w.toByteArray();
            type =
                    new ClassLoader(getClass().getClassLoader()) {
                        Class<?> define() {
                            return defineClass("test.HighlightFixture", bytes, 0, bytes.length);
                        }
                    }.define();
            value = type.getConstructor().newInstance();
            type.getField("text").set(value, text);
            type.getField("start").setInt(value, -1);
            type.getField("end").setInt(value, -1);
        }

        void all(boolean boundary, String... targets) throws Exception {
            type.getMethod("o00000", boolean.class, String[].class)
                    .invoke(value, boundary, targets);
        }

        void single(boolean last, String target) throws Exception {
            type.getMethod(last ? "Ô00000" : "Ø00000", String.class).invoke(value, target);
        }

        boolean[] mask() throws Exception {
            return (boolean[]) type.getField("mask").get(value);
        }

        int[] colors() throws Exception {
            return (int[]) type.getField("colors").get(value);
        }

        int[] range() throws Exception {
            return new int[] {
                type.getField("start").getInt(value), type.getField("end").getInt(value)
            };
        }

        int markedVisible() throws Exception {
            int count = 0;
            boolean[] m = mask();
            for (int i = 0; i < m.length; i++) if (m[i] && text.charAt(i) != '\n') count++;
            return count;
        }
    }
}
