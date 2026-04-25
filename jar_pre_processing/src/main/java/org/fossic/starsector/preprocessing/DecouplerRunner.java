package org.fossic.starsector.preprocessing;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class DecouplerRunner {
    private final JarWorkspace workspace;

    public DecouplerRunner(JarWorkspace workspace) {
        this.workspace = workspace;
    }

    public void run(String jarName, Path input, Path output) throws IOException, InterruptedException {
        Path report = workspace.decouplerReport(jarName);
        List<String> command = new ArrayList<>();
        command.add("java");
        command.add("-Dfile.encoding=UTF-8");
        command.add("-jar");
        command.add(workspace.vendorDecoupler().toString());
        command.add("--input");
        command.add(input.toString());
        command.add("--output");
        command.add(output.toString());
        command.add("--mode");
        command.add("ldc-sites");
        command.add("--verify");
        command.add("true");
        command.add("--fail-on-constant-pool-overflow");
        command.add("true");
        command.add("--fail-on-unsupported-attribute");
        command.add("true");
        command.add("--reproducible");
        command.add("true");
        command.add("--report");
        command.add(report.toString());

        Process process = new ProcessBuilder(command)
                .directory(workspace.workDir().toFile())
                .inheritIO()
                .start();
        int exit = process.waitFor();
        if (exit != 0) {
            throw new PatchException("jar-string-decoupler failed for " + jarName + " with exit code " + exit);
        }
    }
}
