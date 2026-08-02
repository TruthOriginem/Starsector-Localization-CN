package org.fossic.starsector.preprocessing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class JarPreProcessorMain {
    private JarPreProcessorMain() {
    }

    public static void main(String[] args) throws Exception {
        Path projectDir = Path.of("").toAbsolutePath().normalize();
        JarWorkspace workspace = new JarWorkspace(projectDir);
        workspace.prepare();

        PatchSelection patchSelection = PatchSelection.fromSystemProperties();
        System.out.println("Enabled patch groups: "
                + patchSelection.enabledGroupIds());
        Map<String, String> inputHashes = workspace.inputHashes();
        JarRewriter rewriter = new JarRewriter(
                PatchRegistry.patches(patchSelection));
        List<PatchResult> patchResults = new ArrayList<>();
        for (String jarName : JarWorkspace.allJars()) {
            System.out.println("Applying ASM patches to " + jarName);
            patchResults.addAll(rewriter.rewrite(
                    jarName,
                    workspace.stagingInput(jarName),
                    workspace.patchedJar(jarName)
            ));
        }

        // 字符串解耦仅针对含翻译文本的 jar；其余引擎 jar 只做 ASM 注入，
        // patched 产物直接作为最终输出。
        DecouplerRunner decoupler = new DecouplerRunner(workspace);
        for (String jarName : JarWorkspace.jars()) {
            System.out.println("Decoupling " + jarName);
            decoupler.run(jarName, workspace.patchedJar(jarName), workspace.decoupledJar(jarName));
        }

        // 注入运行时类到 obf jar（original 与 localization 一致）：
        // IME（输入法）、DynFont（动态字体；被 fs.common_obf.jar 的 hook 调用）
        // StartupProfiler（仅 profiling 组显式启用时）与启动优化 helper。
        // 各组类均与目标 hook 所在 jar 同处游戏固定 classpath，支持跨 jar 调用。
        Path obfJar = workspace.decoupledJar(JarWorkspace.OBF_JAR);
        Map<String, Integer> injectedCounts = injectRuntimeClasses(
                obfJar, patchSelection);

        System.out.println("Injected runtime classes into " + JarWorkspace.OBF_JAR
                + ": " + injectedCounts);

        workspace.writeOutputs();
        // 原生库（ssime.dll / ss_dyn_font.dll）的编译与分发由 build.py 负责，不在本管线内。

        Map<String, String> outputHashes = workspace.outputHashes();
        writeReport(
                workspace,
                patchSelection,
                inputHashes,
                outputHashes,
                patchResults,
                injectedCounts);
        System.out.println("Preprocessing complete. Report: " + workspace.preprocessReport());
    }

    static Map<String, Integer> injectRuntimeClasses(
            Path obfJar, PatchSelection patchSelection) throws IOException {
        Map<String, Integer> injectedCounts = new LinkedHashMap<>();
        injectedCounts.put("ime", new RuntimeClassInjector(
                "org/fossic/starsector/ime/", "ImeHooks.class").injectInto(obfJar));
        injectedCounts.put("dynfont", new RuntimeClassInjector(
                "org/fossic/starsector/dynfont/", "DynFontOverrides.class").injectInto(obfJar));
        injectStartupProfilerRuntime(
                obfJar, patchSelection, injectedCounts);
        injectedCounts.put("optimization", new RuntimeClassInjector(
                "org/fossic/starsector/optimization/", "FastTextReader.class").injectInto(obfJar));
        if (patchSelection.enabled(PatchGroup.FAST_PNG)) {
            int provider = new RuntimeClassInjector(
                    "org/fossic/starsector/privateimpl/png/",
                    "TwlPngProvider.class")
                    .injectPrivatelyInto(
                            obfJar,
                            "META-INF/starsector-optimization/private/png/",
                            new String[0]);
            int dependency = new RuntimeClassInjector(
                    "de/matthiasmann/twl/utils/", "PNGDecoder.class")
                    .injectPrivatelyInto(
                            obfJar,
                            "META-INF/starsector-optimization/private/png/",
                            new String[0],
                            "META-INF/LICENSE-pngdecoder.txt");
            injectedCounts.put("pngDecoder", provider + dependency);
        }
        if (patchSelection.enabled(PatchGroup.TEXTURE_CACHE)
                || patchSelection.enabled(PatchGroup.PCM_CACHE)) {
            int provider = new RuntimeClassInjector(
                    "org/fossic/starsector/privateimpl/zstd/",
                    "ZstdProvider.class")
                    .injectPrivatelyInto(
                            obfJar,
                            "META-INF/starsector-optimization/private/zstd/",
                            new String[0]);
            int dependency = new RuntimeClassInjector(
                    "com/github/luben/zstd/", "Zstd.class")
                    .injectPrivatelyInto(
                            obfJar,
                            "META-INF/starsector-optimization/private/zstd/",
                            new String[]{
                                "win/amd64/libzstd-jni-1.5.7-4.dll"
                            },
                            "META-INF/LICENSE-zstd-jni.txt");
            injectedCounts.put("zstd", provider + dependency);
        }
        return injectedCounts;
    }

    static void injectStartupProfilerRuntime(
            Path obfJar,
            PatchSelection patchSelection,
            Map<String, Integer> injectedCounts) throws IOException {
        if (!patchSelection.enabled(PatchGroup.PROFILING)) {
            return;
        }
        injectedCounts.put("startupProfiler", new RuntimeClassInjector(
                "org/fossic/starsector/startup/", "StartupProfiler.class")
                .injectInto(obfJar));
    }

    private static void writeReport(
            JarWorkspace workspace,
            PatchSelection patchSelection,
            Map<String, String> inputHashes,
            Map<String, String> outputHashes,
            List<PatchResult> patchResults,
            Map<String, Integer> injectedCounts)
            throws IOException {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"generatedAt\": ").append(JsonUtil.quote(Instant.now().toString())).append(",\n");
        json.append("  \"patchSelection\": {\n");
        json.append("    \"requestedOptimizations\": ")
                .append(JsonUtil.quote(
                        patchSelection.requestedOptimizationSpec()))
                .append(",\n");
        json.append("    \"requestedProfiling\": ")
                .append(patchSelection.requestedProfiling())
                .append(",\n");
        json.append("    \"disabledGroups\": ")
                .append(JsonUtil.stringArray(
                        patchSelection.disabledGroupIds()))
                .append(",\n");
        json.append("    \"enabledOptimizations\": ")
                .append(JsonUtil.stringArray(
                        patchSelection.enabledOptimizationIds()))
                .append(",\n");
        json.append("    \"enabledGroups\": ")
                .append(JsonUtil.stringArray(
                        patchSelection.enabledGroupIds()))
                .append("\n");
        json.append("  },\n");
        json.append("  \"inputHashes\": ").append(JsonUtil.stringMap(inputHashes)).append(",\n");
        json.append("  \"decouplerReports\": {\n");
        for (int i = 0; i < JarWorkspace.jars().length; i++) {
            String jarName = JarWorkspace.jars()[i];
            json.append("    ").append(JsonUtil.quote(jarName)).append(": ")
                    .append(JsonUtil.quote(workspace.decouplerReport(jarName).toString()));
            json.append(i + 1 == JarWorkspace.jars().length ? "\n" : ",\n");
        }
        json.append("  },\n");
        json.append("  \"patches\": [\n");
        for (int i = 0; i < patchResults.size(); i++) {
            json.append(patchResults.get(i).toJson(4));
            json.append(i + 1 == patchResults.size() ? "\n" : ",\n");
        }
        json.append("  ],\n");
        json.append("  \"runtimeInjection\": {");
        int i = 0;
        for (Map.Entry<String, Integer> entry : injectedCounts.entrySet()) {
            if (i++ > 0) {
                json.append(", ");
            }
            json.append(JsonUtil.quote(entry.getKey())).append(": ").append(entry.getValue());
        }
        json.append("},\n");
        json.append("  \"outputHashes\": ").append(JsonUtil.stringMap(outputHashes)).append("\n");
        json.append("}\n");
        Files.writeString(workspace.preprocessReport(), json.toString(), StandardCharsets.UTF_8);
    }
}
