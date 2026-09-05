package org.fossic.starsector.preprocessing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
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
        System.out.println("Requested optimizations: "
                + patchSelection.requestedOptimizationSpec()
                + "; profiling: " + patchSelection.requestedProfiling()
                + "; disabled patch groups: "
                + patchSelection.requestedDisabledGroupIds());
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

        // 字符串解耦仅针对含翻译文本的 jar；fs.common_obf.jar 和
        // fs.sound_obf.jar 只过 patch 阶段，patched 结果直接作为最终输出。
        DecouplerRunner decoupler = new DecouplerRunner(workspace);
        for (String jarName : JarWorkspace.jars()) {
            System.out.println("Decoupling " + jarName);
            decoupler.run(jarName, workspace.patchedJar(jarName), workspace.decoupledJar(jarName));
        }

        workspace.writeOutputs();
        Map<String, String> outputHashes = workspace.outputHashes();
        writeReport(
                workspace,
                patchSelection,
                inputHashes,
                outputHashes,
                patchResults);
        System.out.println("Preprocessing complete. Report: " + workspace.preprocessReport());
    }

    private static void writeReport(
            JarWorkspace workspace,
            PatchSelection patchSelection,
            Map<String, String> inputHashes,
            Map<String, String> outputHashes,
            List<PatchResult> patchResults)
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
        json.append("    \"requestedDisabledGroups\": ")
                .append(JsonUtil.stringArray(
                        patchSelection.requestedDisabledGroupIds()))
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
        json.append("  \"outputHashes\": ").append(JsonUtil.stringMap(outputHashes)).append("\n");
        json.append("}\n");
        Files.writeString(workspace.preprocessReport(), json.toString(), StandardCharsets.UTF_8);
    }
}
