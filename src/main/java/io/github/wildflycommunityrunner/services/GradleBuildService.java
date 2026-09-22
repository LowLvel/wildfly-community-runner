package io.github.wildflycommunityrunner.services;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.KillableProcessHandler;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessOutputType;
import com.intellij.execution.process.ProcessListener;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.util.execution.ParametersListUtil;
import io.github.wildflycommunityrunner.model.ServiceProfile;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class GradleBuildService {
    private GradleBuildService() {}

    public static void build(Project project,
                             ServiceProfile service,
                             Runnable onSuccess,
                             Runnable onFailure,
                             Consumer<String> output) {
        // Save from an IntelliJ-dispatched EDT callback so document/model writes have the
        // required write-intent context, then do the expensive Gradle process work in BGT.
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                FileDocumentManager.getInstance().saveAllDocuments();
            } catch (Exception e) {
                output.accept("ERROR saving documents before Gradle build: " + e.getMessage());
                if (onFailure != null) onFailure.run();
                return;
            }
            ApplicationManager.getApplication().executeOnPooledThread(() -> doBuild(project, service, onSuccess, onFailure, output));
        });
    }

    private static void doBuild(Project project,
                                ServiceProfile service,
                                Runnable onSuccess,
                                Runnable onFailure,
                                Consumer<String> output) {
        try {
            Path buildFile = resolveBuildFile(project, service);
            Path moduleDir = buildFile.getParent();
            Path wrapper = findWrapper(moduleDir);
            boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");

            List<String> command = new ArrayList<>();
            if (windows) {
                command.add("cmd.exe");
                command.add("/c");
                command.add(wrapper != null ? wrapper.toString() : "gradle");
            } else {
                command.add(wrapper != null ? wrapper.toString() : "gradle");
            }
            if (wrapper == null) {
                output.accept("No Gradle wrapper found above " + moduleDir + "; falling back to system Gradle.");
            }

            String tasks = service.buildTasks == null || service.buildTasks.isBlank() ? "clean build" : service.buildTasks;
            command.addAll(ParametersListUtil.parse(tasks));
            if (service.buildArguments != null && !service.buildArguments.isBlank()) {
                command.addAll(ParametersListUtil.parse(service.buildArguments));
            }
            if (service.buildJvmOptions != null && !service.buildJvmOptions.isBlank()) {
                command.add("-Dorg.gradle.jvmargs=" + service.buildJvmOptions.trim());
            }

            int taskStart = windows ? 3 : 1;
            output.accept("Building " + service.name + " — Gradle " + String.join(" ", command.subList(taskStart, command.size())));

            GeneralCommandLine commandLine = new GeneralCommandLine(command)
                    .withWorkingDirectory(moduleDir)
                    .withCharset(StandardCharsets.UTF_8);
            KillableProcessHandler handler = new KillableProcessHandler(commandLine);
            handler.setShouldKillProcessSoftly(false);
            handler.addProcessListener(new ProcessListener() {
                @Override
                @SuppressWarnings("rawtypes")
                public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
                    String text = event.getText().stripTrailing();
                    if (text.isBlank()) return;
                    output.accept(ProcessOutputType.isStderr(outputType) ? "[gradle stderr] " + text : text);
                }

                @Override
                public void processTerminated(@NotNull ProcessEvent event) {
                    if (event.getExitCode() == 0) {
                        output.accept("Build succeeded: " + service.name);
                        if (onSuccess != null) onSuccess.run();
                    } else {
                        output.accept("BUILD FAILED: " + service.name + " (exit " + event.getExitCode() + ")");
                        if (onFailure != null) onFailure.run();
                    }
                }
            });
            handler.startNotify();
        } catch (Exception e) {
            output.accept("ERROR: " + e.getMessage());
            if (onFailure != null) onFailure.run();
        }
    }

    public static Path resolveBuildFile(Project project, ServiceProfile service) {
        String base = project.getBasePath();
        Path projectBase = Path.of(base == null ? "." : base).toAbsolutePath().normalize();
        if (service.buildFilePath == null || service.buildFilePath.isBlank()) {
            Path kotlin = projectBase.resolve("build.gradle.kts");
            Path groovy = projectBase.resolve("build.gradle");
            if (Files.isRegularFile(kotlin)) return kotlin;
            if (Files.isRegularFile(groovy)) return groovy;
            throw new IllegalArgumentException("No Gradle project selected for " + service.name);
        }
        Path file = Path.of(service.buildFilePath);
        if (!file.isAbsolute()) file = projectBase.resolve(file);
        file = file.normalize();
        if (Files.isDirectory(file)) {
            Path kotlin = file.resolve("build.gradle.kts");
            Path groovy = file.resolve("build.gradle");
            if (Files.isRegularFile(kotlin)) return kotlin;
            if (Files.isRegularFile(groovy)) return groovy;
        }
        if (!Files.isRegularFile(file)) throw new IllegalArgumentException("Gradle build file not found: " + file);
        return file;
    }

    private static Path findWrapper(Path start) {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String name = windows ? "gradlew.bat" : "gradlew";
        Path current = start.toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(name);
            if (Files.isRegularFile(candidate)) return candidate;
            current = current.getParent();
        }
        return null;
    }
}
