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

        Map<String, String> inputHashes = workspace.inputHashes();
        JarRewriter rewriter = new JarRewriter(PatchRegistry.patches());
        List<PatchResult> patchResults = new ArrayList<>();
        for (String jarName : JarWorkspace.allJars()) {
            System.out.println("Applying ASM patches to " + jarName);
            patchResults.addAll(rewriter.rewrite(
                    jarName,
                    workspace.stagingInput(jarName),
                    workspace.patchedJar(jarName)
            ));
        }

        // 字符串解耦仅针对含翻译文本的 jar；fs.common_obf.jar 只注入不解耦，
        // patched 产物直接作为最终输出。
        DecouplerRunner decoupler = new DecouplerRunner(workspace);
        for (String jarName : JarWorkspace.jars()) {
            System.out.println("Decoupling " + jarName);
            decoupler.run(jarName, workspace.patchedJar(jarName), workspace.decoupledJar(jarName));
        }

        // 注入运行时类到 obf jar（original 与 localization 一致）：
        // IME（输入法）与 DynFont（动态字体；被 fs.common_obf.jar 的 hook 调用，
        // 两 jar 同处游戏固定 classpath，跨 jar 可见）。
        Map<String, Integer> injectedCounts = new LinkedHashMap<>();
        Path obfJar = workspace.decoupledJar(JarWorkspace.OBF_JAR);
        injectedCounts.put("ime", new RuntimeClassInjector(
                "org/fossic/starsector/ime/", "ImeHooks.class").injectInto(obfJar));
        injectedCounts.put("dynfont", new RuntimeClassInjector(
                "org/fossic/starsector/dynfont/", "DynFontOverrides.class").injectInto(obfJar));
        System.out.println("Injected runtime classes into " + JarWorkspace.OBF_JAR
                + ": " + injectedCounts);

        workspace.writeOutputs();
        // 原生库（ssime.dll / ss_dyn_font.dll）的编译与分发由 build.py 负责，不在本管线内。

        Map<String, String> outputHashes = workspace.outputHashes();
        writeReport(workspace, inputHashes, outputHashes, patchResults, injectedCounts);
        System.out.println("Preprocessing complete. Report: " + workspace.preprocessReport());
    }

    private static void writeReport(JarWorkspace workspace, Map<String, String> inputHashes,
                                    Map<String, String> outputHashes, List<PatchResult> patchResults,
                                    Map<String, Integer> injectedCounts)
            throws IOException {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"generatedAt\": ").append(JsonUtil.quote(Instant.now().toString())).append(",\n");
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
