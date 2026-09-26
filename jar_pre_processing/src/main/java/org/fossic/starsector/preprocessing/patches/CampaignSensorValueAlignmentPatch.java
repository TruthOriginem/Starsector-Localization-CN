package org.fossic.starsector.preprocessing.patches;

import org.fossic.starsector.preprocessing.*;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Keep sensor values inside their fixed HUD panels when dynamic digits are wider. */
public final class CampaignSensorValueAlignmentPatch implements JarPatch {
    static final String TARGET = "com/fs/starfarer/coreui/Objectnew";
    private static final String VALUE_PANEL = TARGET + "$Oo";
    private static final String POSITION = "com/fs/starfarer/ui/OO0O";
    private static final String ALIGNMENT = "com/fs/starfarer/api/ui/Alignment";

    @Override public String id() { return "campaign-sensor-value-alignment"; }
    @Override public PatchGroup group() { return PatchGroup.DYNFONT; }
    @Override public String targetJar() { return JarWorkspace.OBF_JAR; }
    @Override public Set<String> targetClasses() { return Set.of(TARGET + ".class"); }

    @Override
    public PatchResult applyAndVerify(ClassNode node, PatchContext context) {
        MethodNode method = node.methods.stream()
                .filter(m -> m.name.equals("OOO000") && m.desc.equals("()V"))
                .findFirst().orElseThrow(() -> failure("missing sensor setup method"));
        var code = AsmUtil.instructions(method);
        for (String icon : List.of("icon_logistics_sensorStrength", "icon_logistics_sensorProfile")) {
            if (code.stream().filter(i -> AsmUtil.isStringLdc(i, icon)).count() != 1) {
                throw failure("missing or duplicate icon anchor: " + icon);
            }
        }
        List<MethodInsnNode> setters = new ArrayList<>();
        Set<String> fields = new HashSet<>();
        for (int i = 4; i < code.size(); i++) {
            if (!(code.get(i) instanceof MethodInsnNode call)
                    || !call.owner.equals(SingleLineLabel.LABEL) || !call.name.equals("setAlignment")
                    || !call.desc.equals("(L" + ALIGNMENT + ";)V")) continue;
            if (!(code.get(i - 1) instanceof FieldInsnNode alignment)
                    || alignment.getOpcode() != Opcodes.GETSTATIC
                    || !alignment.owner.equals(ALIGNMENT) || !alignment.name.equals("RMID")
                    || !(code.get(i - 2) instanceof MethodInsnNode getter)
                    || !getter.owner.equals(VALUE_PANEL) || !getter.name.equals("getValue")
                    || !getter.desc.equals("()L" + SingleLineLabel.LABEL + ";")
                    || !(code.get(i - 3) instanceof FieldInsnNode field)
                    || field.getOpcode() != Opcodes.GETFIELD || !field.owner.equals(TARGET)
                    || !field.desc.equals("L" + VALUE_PANEL + ";")
                    || !(code.get(i - 4) instanceof VarInsnNode load)
                    || load.getOpcode() != Opcodes.ALOAD || load.var != 0) {
                throw failure("unexpected sensor alignment receiver");
            }
            fields.add(field.name);
            setters.add(call);
        }
        if (setters.size() != 2 || fields.size() != 2 || countAnchors(method) != 0) {
            throw failure("expected exactly two unpatched sensor labels");
        }
        // Validate both sites before mutating. Preserve the existing label for our calls.
        for (MethodInsnNode setter : setters) {
            AbstractInsnNode alignment = setter.getPrevious();
            while (alignment.getOpcode() < 0) alignment = alignment.getPrevious();
            method.instructions.insertBefore(alignment, new InsnNode(Opcodes.DUP));
            InsnList fix = new InsnList();
            fix.add(new InsnNode(Opcodes.DUP));
            fix.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, SingleLineLabel.LABEL,
                    "getRenderer", "()L" + SingleLineLabel.RENDERER + ";", false));
            fix.add(new InsnNode(Opcodes.ICONST_0));
            fix.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, SingleLineLabel.RENDERER,
                    "return", "(Z)V", false));
            fix.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, SingleLineLabel.LABEL,
                    "getPosition", "()L" + POSITION + ";", false));
            fix.add(new LdcInsnNode(3f));
            fix.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, POSITION,
                    "inRMid", "(F)L" + POSITION + ";", false));
            fix.add(new InsnNode(Opcodes.POP));
            method.instructions.insert(setter, fix);
        }
        method.maxStack += 1;
        return PatchResult.of(id(), context.classPath(), 2, 2, countAnchors(method),
                "sensor values anchored to parent right edge with 3px logical inset; wrapping disabled");
    }

    private static int countAnchors(MethodNode method) {
        int count = 0;
        for (var insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(POSITION)
                    && call.name.equals("inRMid") && call.desc.equals("(F)L" + POSITION + ";")) count++;
        }
        return count;
    }

    private static PatchException failure(String detail) {
        return new PatchException("campaign-sensor-value-alignment: " + detail);
    }
}
