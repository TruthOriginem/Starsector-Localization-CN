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

        Map<String, String> inputHashes = workspace.inputHashes();
        JarRewriter rewriter = new JarRewriter(PatchRegistry.patches());
        List<PatchResult> patchResults = new ArrayList<>();
        for (String jarName : JarWorkspace.jars()) {
            System.out.println("Applying ASM patches to " + jarName);
            patchResults.addAll(rewriter.rewrite(
                    jarName,
                    workspace.stagingInput(jarName),
                    workspace.patchedJar(jarName)
            ));
        }

        DecouplerRunner decoupler = new DecouplerRunner(workspace);
        for (String jarName : JarWorkspace.jars()) {
            System.out.println("Decoupling " + jarName);
            decoupler.run(jarName, workspace.patchedJar(jarName), workspace.decoupledJar(jarName));
        }

        // 注入中文输入法运行时类到 obf jar（original 与 localization 一致）。
        ImeRuntimeInjector imeInjector = new ImeRuntimeInjector();
        int injectedClasses = imeInjector.injectInto(workspace.decoupledJar(JarWorkspace.OBF_JAR));
        System.out.println("Injected " + injectedClasses + " IME runtime classes into " + JarWorkspace.OBF_JAR);

        workspace.writeOutputs();

        // 分发预编译的 IME 原生库到汉化结果（仅 localization）。
        workspace.distributeImeNativeLibrary();
        System.out.println("Distributed ssime.dll to localization/native/windows");
        String ssimeDllHash = JarWorkspace.sha256(
                workspace.localizationNativeWindowsDir().resolve("ssime.dll"));
        Map<String, String> outputHashes = workspace.outputHashes();
        writeReport(workspace, inputHashes, outputHashes, patchResults, injectedClasses, ssimeDllHash);
        System.out.println("Preprocessing complete. Report: " + workspace.preprocessReport());
    }

    private static void writeReport(JarWorkspace workspace, Map<String, String> inputHashes,
                                    Map<String, String> outputHashes, List<PatchResult> patchResults,
                                    int imeInjectedClasses, String ssimeDllHash)
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
        json.append("  \"imeRuntime\": {\"injectedClasses\": ").append(imeInjectedClasses)
                .append(", \"ssimeDllSha256\": ").append(JsonUtil.quote(ssimeDllHash)).append("},\n");
        json.append("  \"outputHashes\": ").append(JsonUtil.stringMap(outputHashes)).append("\n");
        json.append("}\n");
        Files.writeString(workspace.preprocessReport(), json.toString(), StandardCharsets.UTF_8);
    }
}
