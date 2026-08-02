package org.fossic.starsector.preprocessing.patches;

import org.fossic.starsector.preprocessing.JarPatch;
import org.fossic.starsector.preprocessing.JarWorkspace;
import org.fossic.starsector.preprocessing.PatchContext;
import org.fossic.starsector.preprocessing.PatchException;
import org.fossic.starsector.preprocessing.PatchResult;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.List;
import java.util.Set;

/**
 * 把 {@code ResourceLoaderState.init} 切成互不重叠的主线程阶段。
 */
public final class ResourceLoaderStartupProfilePatch implements JarPatch {
    private static final String TARGET_CLASS =
            "com/fs/starfarer/loading/ResourceLoaderState.class";
    private static final String RESOURCE_LOADER_DESC =
            "(Lcom/fs/starfarer/loading/ResourceLoaderState;)V";

    @Override
    public String id() {
        return "startup-profile-resource-loader";
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
        MethodNode init = StartupProfilePatchSupport.requireMethod(
                classNode, "init", "(Ljava/util/Map;)V");

        init.instructions.insert(StartupProfilePatchSupport.phasePair(
                "start", "resource_loader.total",
                "start", "resource_loader.early_queue_and_ui"));

        MethodInsnNode settingsLoad = onlyCall(
                StartupProfilePatchSupport.methodCalls(
                        init, "com/fs/starfarer/settings/StarfarerSettings", RESOURCE_LOADER_DESC),
                "StarfarerSettings(ResourceLoaderState)");
        init.instructions.insertBefore(settingsLoad, StartupProfilePatchSupport.phasePair(
                "end", "resource_loader.early_queue_and_ui",
                "start", "resource_loader.settings_and_registry"));
        init.instructions.insert(settingsLoad, StartupProfilePatchSupport.phasePair(
                "end", "resource_loader.settings_and_registry",
                "start", "resource_loader.script_setup"));

        MethodInsnNode specStore = onlyCall(
                StartupProfilePatchSupport.methodCalls(
                        init, "com/fs/starfarer/loading/SpecStore", RESOURCE_LOADER_DESC),
                "SpecStore(ResourceLoaderState)");
        init.instructions.insertBefore(specStore, StartupProfilePatchSupport.phasePair(
                "end", "resource_loader.script_setup",
                "start", "resource_loader.spec_store"));
        init.instructions.insert(specStore, StartupProfilePatchSupport.phasePair(
                "end", "resource_loader.spec_store",
                "start", "resource_loader.queue_and_preload_setup"));

        MethodInsnNode newFixedThreadPool = StartupProfilePatchSupport.requireCall(
                init,
                "java/util/concurrent/Executors",
                "newFixedThreadPool",
                "(I)Ljava/util/concurrent/ExecutorService;");
        AbstractInsnNode executorStore = StartupProfilePatchSupport.nextExecutable(newFixedThreadPool);
        if (!(executorStore instanceof VarInsnNode) || executorStore.getOpcode() != Opcodes.ASTORE) {
            throw new PatchException("newFixedThreadPool 后预期为 ASTORE，实际 opcode="
                    + executorStore.getOpcode());
        }
        init.instructions.insert(executorStore, StartupProfilePatchSupport.phasePair(
                "end", "resource_loader.queue_and_preload_setup",
                "start", "resource_loader.resource_items"));

        MethodInsnNode shutdown = StartupProfilePatchSupport.requireCall(
                init, "java/util/concurrent/ExecutorService", "shutdown", "()V");
        init.instructions.insertBefore(shutdown, StartupProfilePatchSupport.phasePair(
                "end", "resource_loader.resource_items",
                "start", "resource_loader.async_resource_wait"));

        List<MethodInsnNode> scriptStoreVoidCalls = StartupProfilePatchSupport.methodCalls(
                init, "com/fs/starfarer/loading/scripts/ScriptStore", "()V");
        if (scriptStoreVoidCalls.size() != 4) {
            throw new PatchException("ScriptStore ()V 调用数异常: "
                    + scriptStoreVoidCalls.size() + "，预期 4");
        }
        MethodInsnNode scriptJoin = scriptStoreVoidCalls.get(scriptStoreVoidCalls.size() - 1);
        init.instructions.insertBefore(scriptJoin, StartupProfilePatchSupport.phasePair(
                "end", "resource_loader.async_resource_wait",
                "start", "resource_loader.script_join"));
        init.instructions.insert(scriptJoin, StartupProfilePatchSupport.phasePair(
                "end", "resource_loader.script_join",
                "start", "resource_loader.before_mod_callbacks"));

        MethodInsnNode enabledPlugins = StartupProfilePatchSupport.requireCall(
                init,
                "com/fs/starfarer/launcher/ModManager",
                "getEnabledModPlugins",
                "()Ljava/util/List;");
        init.instructions.insertBefore(enabledPlugins, StartupProfilePatchSupport.phasePair(
                "end", "resource_loader.before_mod_callbacks",
                "start", "resource_loader.mod_on_application_load"));

        MethodInsnNode postModAnchor = onlyCall(
                StartupProfilePatchSupport.methodCalls(
                        init, "com/fs/starfarer/combat/entities/ship/A/I", "()V"),
                "first post-mod initialization call");
        init.instructions.insertBefore(postModAnchor, StartupProfilePatchSupport.phasePair(
                "end", "resource_loader.mod_on_application_load",
                "start", "resource_loader.post_mod_initialization"));

        int returns = StartupProfilePatchSupport.countOpcode(init, Opcodes.RETURN);
        if (returns != 1) {
            throw new PatchException("ResourceLoaderState.init RETURN 数异常: "
                    + returns + "，预期 1");
        }
        for (AbstractInsnNode instruction : init.instructions.toArray()) {
            if (instruction.getOpcode() == Opcodes.RETURN) {
                init.instructions.insertBefore(instruction, StartupProfilePatchSupport.phasePair(
                        "end", "resource_loader.post_mod_initialization",
                        "end", "resource_loader.total"));
            }
        }
        init.maxStack += 1;

        int verified = StartupProfilePatchSupport.countProfilerCalls(classNode);
        return PatchResult.of(id(), context.classPath(), 24, 24, verified,
                "12 disjoint ResourceLoaderState boundaries, including specs, resources, script join and mod callbacks");
    }

    private static MethodInsnNode onlyCall(List<MethodInsnNode> calls, String label) {
        if (calls.size() != 1) {
            throw new PatchException(label + " 调用数异常: " + calls.size() + "，预期 1");
        }
        return calls.get(0);
    }
}
