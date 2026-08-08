package org.fossic.starsector.preprocessing.patches;

import org.fossic.starsector.preprocessing.AsmUtil;
import org.fossic.starsector.preprocessing.JarPatch;
import org.fossic.starsector.preprocessing.JarWorkspace;
import org.fossic.starsector.preprocessing.PatchContext;
import org.fossic.starsector.preprocessing.PatchException;
import org.fossic.starsector.preprocessing.PatchResult;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class WindowDecorationPhysicalResolutionPatch implements JarPatch {
    private static final String TARGET_CLASS = "com/fs/starfarer/combat/CombatMain.class";
    private static final String TARGET_CLASS_NAME = "com/fs/starfarer/combat/CombatMain";
    private static final String TARGET_METHOD_NAME = "main";
    private static final String TARGET_METHOD_DESC = "([Ljava/lang/String;)V";

    private static final String TOOLKIT = "java/awt/Toolkit";
    private static final String TOOLKIT_GETTER_DESC = "()Ljava/awt/Toolkit;";
    private static final String SCREEN_SIZE_DESC = "()Ljava/awt/Dimension;";
    private static final String DIMENSION = "java/awt/Dimension";
    private static final String DISPLAY = "org/lwjgl/opengl/Display";
    private static final String DISPLAY_MODE = "org/lwjgl/opengl/DisplayMode";
    private static final String DISPLAY_MODE_DESC = "()Lorg/lwjgl/opengl/DisplayMode;";

    @Override
    public String id() {
        return "window-decoration-physical-resolution";
    }

    @Override
    public String targetJar() {
        return JarWorkspace.OBF_JAR;
    }

    @Override
    public Set<String> targetClasses() {
        return Set.of(TARGET_CLASS);
    }

    @Override
    public PatchResult applyAndVerify(ClassNode classNode, PatchContext context) {
        if (!TARGET_CLASS_NAME.equals(classNode.name)) {
            throw new PatchException(id() + " received unexpected class " + classNode.name);
        }

        MethodNode method = findTargetMethod(classNode, context);
        List<DimensionRead> widthReads = findLogicalDimensionReads(method, "width");
        List<DimensionRead> heightReads = findLogicalDimensionReads(method, "height");
        if (widthReads.size() != 1 || heightReads.size() != 1) {
            throw new PatchException(id() + " failed for " + context.classPath()
                    + ": expected one logical width read and one logical height read, found "
                    + widthReads.size() + " and " + heightReads.size());
        }

        replaceRead(method, widthReads.get(0), "getWidth");
        replaceRead(method, heightReads.get(0), "getHeight");

        int oldWidthReads = findLogicalDimensionReads(method, "width").size();
        int oldHeightReads = findLogicalDimensionReads(method, "height").size();
        int physicalWidthReads = countPhysicalDimensionReads(method, "getWidth");
        int physicalHeightReads = countPhysicalDimensionReads(method, "getHeight");
        if (oldWidthReads != 0 || oldHeightReads != 0
                || physicalWidthReads != 1 || physicalHeightReads != 1) {
            throw new PatchException(id() + " verification failed for " + context.classPath()
                    + ": oldWidth=" + oldWidthReads
                    + ", oldHeight=" + oldHeightReads
                    + ", physicalWidth=" + physicalWidthReads
                    + ", physicalHeight=" + physicalHeightReads);
        }

        return PatchResult.of(id(), context.classPath(), 2, 2, 2,
                "compare window size with LWJGL physical desktop dimensions");
    }

    private static MethodNode findTargetMethod(ClassNode classNode, PatchContext context) {
        List<MethodNode> matches = new ArrayList<>();
        for (MethodNode method : classNode.methods) {
            if (TARGET_METHOD_NAME.equals(method.name) && TARGET_METHOD_DESC.equals(method.desc)) {
                matches.add(method);
            }
        }
        if (matches.size() != 1) {
            throw new PatchException("window-decoration-physical-resolution failed for "
                    + context.classPath() + ": expected one target method, found " + matches.size());
        }
        return matches.get(0);
    }

    private static List<DimensionRead> findLogicalDimensionReads(MethodNode method, String fieldName) {
        List<DimensionRead> matches = new ArrayList<>();
        for (AbstractInsnNode node : AsmUtil.instructions(method)) {
            if (!(node instanceof MethodInsnNode toolkitCall)
                    || toolkitCall.getOpcode() != Opcodes.INVOKESTATIC
                    || !TOOLKIT.equals(toolkitCall.owner)
                    || !"getDefaultToolkit".equals(toolkitCall.name)
                    || !TOOLKIT_GETTER_DESC.equals(toolkitCall.desc)) {
                continue;
            }

            AbstractInsnNode next = nextExecutable(node);
            AbstractInsnNode afterNext = nextExecutable(next);
            if (next instanceof MethodInsnNode screenSizeCall
                    && screenSizeCall.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && TOOLKIT.equals(screenSizeCall.owner)
                    && "getScreenSize".equals(screenSizeCall.name)
                    && SCREEN_SIZE_DESC.equals(screenSizeCall.desc)
                    && afterNext instanceof FieldInsnNode fieldRead
                    && fieldRead.getOpcode() == Opcodes.GETFIELD
                    && DIMENSION.equals(fieldRead.owner)
                    && fieldName.equals(fieldRead.name)
                    && "I".equals(fieldRead.desc)) {
                matches.add(new DimensionRead(toolkitCall, screenSizeCall, fieldRead));
            }
        }
        return matches;
    }

    private static void replaceRead(MethodNode method, DimensionRead read, String getterName) {
        read.toolkitCall.owner = DISPLAY;
        read.toolkitCall.name = "getDesktopDisplayMode";
        read.toolkitCall.desc = DISPLAY_MODE_DESC;
        read.screenSizeCall.owner = DISPLAY_MODE;
        read.screenSizeCall.name = getterName;
        read.screenSizeCall.desc = "()I";
        method.instructions.remove(read.fieldRead);
    }

    private static int countPhysicalDimensionReads(MethodNode method, String getterName) {
        int count = 0;
        for (AbstractInsnNode node : AsmUtil.instructions(method)) {
            if (!(node instanceof MethodInsnNode displayCall)
                    || displayCall.getOpcode() != Opcodes.INVOKESTATIC
                    || !DISPLAY.equals(displayCall.owner)
                    || !"getDesktopDisplayMode".equals(displayCall.name)
                    || !DISPLAY_MODE_DESC.equals(displayCall.desc)) {
                continue;
            }
            AbstractInsnNode next = nextExecutable(node);
            if (next instanceof MethodInsnNode getter
                    && getter.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && DISPLAY_MODE.equals(getter.owner)
                    && getterName.equals(getter.name)
                    && "()I".equals(getter.desc)) {
                count++;
            }
        }
        return count;
    }

    private static AbstractInsnNode nextExecutable(AbstractInsnNode node) {
        AbstractInsnNode current = node == null ? null : node.getNext();
        while (current != null && current.getOpcode() < 0) {
            current = current.getNext();
        }
        return current;
    }

    private record DimensionRead(
            MethodInsnNode toolkitCall,
            MethodInsnNode screenSizeCall,
            FieldInsnNode fieldRead
    ) {
    }
}
