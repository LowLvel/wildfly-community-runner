package io.github.wildflycommunityrunner.services;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.KillableProcessHandler;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessOutputType;
import com.intellij.execution.process.ProcessListener;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.progress.ProcessCanceledException;
import io.github.wildflycommunityrunner.util.IdeUi;
import io.github.wildflycommunityrunner.util.ProjectTrust;
import com.intellij.openapi.util.Key;
import com.intellij.util.execution.ParametersListUtil;
import io.github.wildflycommunityrunner.model.ServiceProfile;
import io.github.wildflycommunityrunner.security.JvmSecrets;
import io.github.wildflycommunityrunner.security.SecretRedactor;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class GradleBuildService {
    private GradleBuildService() {}

    static void build(Project project, ServiceProfile service, BuildOperation operation, Consumer<String> output) {
        IdeUi.later(project, () -> operation.completion().isDone(), () -> {
            try {
                if (!ProjectTrust.isTrusted(project)) throw new IllegalStateException("Trust this project before running a build.");
                FileDocumentManager.getInstance().saveAllDocuments();
                ApplicationManager.getApplication().executeOnPooledThread(() -> doBuild(project, service, operation, output));
            } catch (ProcessCanceledException cancelled) {
                operation.failed(cancelled);
                throw cancelled;
            } catch (Exception error) { operation.failed(error); }
        });
    }

    private static void doBuild(Project project, ServiceProfile service, BuildOperation operation, Consumer<String> output) {
        try {
            Path buildFile = resolveBuildFile(project, service);
            Path moduleDir = buildFile.getParent();
            Path wrapper = findWrapper(moduleDir);
            boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");

            List<String> command = new ArrayList<>();
            if (windows) {
                command.add("cmd.exe");
                command.add("/d");
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
            var secrets = JvmSecrets.getInstance();
            var options = secrets.prepare(service.buildJvmOptions);
            operation.onFinished(() -> secrets.release(options));
            var launcherOptions = options.containsSecrets() ? secrets.prepareGradleLauncher(options) : null;
            operation.onFinished(() -> secrets.release(launcherOptions));
            if (launcherOptions == null && !options.options().isBlank()) command.add("-Dorg.gradle.jvmargs=" + options.options());
            if (options.containsSecrets()) command.add("--no-daemon");

            int taskStart = windows ? 4 : 1;
            output.accept("Building " + service.name + " — Gradle " + String.join(" ", command.subList(taskStart, command.size())));

            GeneralCommandLine commandLine = new GeneralCommandLine(command)
                    .withWorkingDirectory(moduleDir)
                    .withCharset(StandardCharsets.UTF_8);
            if (launcherOptions != null) {
                String inherited = System.getenv("JAVA_OPTS");
                commandLine.withEnvironment("JAVA_OPTS", ((inherited == null ? "" : inherited) + " " + launcherOptions.options()).trim());
            }
            if (!ProjectTrust.isTrusted(project)) throw new IllegalStateException("Trust this project before running a build.");
            if (!operation.beginLaunch()) return;
            KillableProcessHandler handler = new KillableProcessHandler(commandLine);
            handler.setShouldKillProcessSoftly(false);
            operation.bind(handler);
            java.util.Map<Key<?>, SecretRedactor.Lines> streams = new java.util.concurrent.ConcurrentHashMap<>();
            ProcessListener outputListener = new ProcessListener() {
                @Override
                @SuppressWarnings("rawtypes")
                public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
                    streams.computeIfAbsent(outputType, key -> options.redactor().new Lines(text -> {
                        if (!text.isBlank()) output.accept(ProcessOutputType.isStderr(key) ? "[gradle stderr] " + text.stripTrailing() : text.stripTrailing());
                    })).accept(event.getText());
                }

            };
            handler.addProcessListener(outputListener);
            operation.onFinished(() -> {
                streams.values().forEach(SecretRedactor.Lines::finish);
                handler.removeProcessListener(outputListener);
            });
            handler.startNotify();
        } catch (ProcessCanceledException cancelled) {
            operation.failed(cancelled);
            throw cancelled;
        } catch (Exception error) { operation.failed(error); }
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
