package io.github.wildflycommunityrunner.services;

import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessListener;
import com.intellij.execution.runners.ProgramRunner;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.util.execution.ParametersListUtil;
import io.github.wildflycommunityrunner.model.ServiceProfile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.idea.maven.execution.MavenRunConfigurationType;
import org.jetbrains.idea.maven.execution.MavenRunner;
import org.jetbrains.idea.maven.execution.MavenRunnerParameters;
import org.jetbrains.idea.maven.execution.MavenRunnerSettings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class MavenBuildService {
    private MavenBuildService() {}

    public static void build(Project project,
                             ServiceProfile service,
                             Runnable onSuccess,
                             Runnable onFailure,
                             Consumer<String> output) {
        try {
            Path pom = resolvePom(project, service);
            String workDir = pom.getParent().toString();
            String taskText = service.buildTasks == null || service.buildTasks.isBlank() ? "clean package" : service.buildTasks;
            List<String> goals = new ArrayList<>(ParametersListUtil.parse(taskText));
            if (service.buildArguments != null && !service.buildArguments.isBlank()) {
                goals.addAll(ParametersListUtil.parse(service.buildArguments));
            }

            MavenRunnerParameters parameters = new MavenRunnerParameters();
            parameters.setWorkingDirPath(workDir);
            parameters.setPomFileName(pom.getFileName().toString());
            parameters.setGoals(goals);
            parameters.setResolveToWorkspace(true);

            output.accept("Building " + service.name + " — Maven " + String.join(" ", goals));

            // IntelliJ 2025.1 no longer gives raw Swing callbacks an implicit write-intent
            // lock. Saving documents and creating/running the Maven configuration can touch
            // platform model state, so both operations must begin from Application.invokeLater().
            ApplicationManager.getApplication().invokeLater(() -> {
                try {
                    FileDocumentManager.getInstance().saveAllDocuments();
                    MavenRunner runner = MavenRunner.getInstance(project);
                    MavenRunnerSettings settings = runner.getSettings().clone();
                    String inheritedVmOptions = settings.getVmOptions() == null ? "" : settings.getVmOptions().trim();
                    String serviceVmOptions = service.buildJvmOptions == null ? "" : service.buildJvmOptions.trim();
                    settings.setVmOptions((inheritedVmOptions + " " + serviceVmOptions).trim());

                    ProgramRunner.Callback callback = descriptor -> {
                        ProcessHandler handler = descriptor.getProcessHandler();
                        if (handler == null) {
                            output.accept("ERROR: Maven process did not start for " + service.name);
                            if (onFailure != null) onFailure.run();
                            return;
                        }
                        handler.addProcessListener(new ProcessListener() {
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
                    };

                    MavenRunConfigurationType.runConfiguration(project, parameters, null, settings, callback, false);
                } catch (Exception e) {
                    output.accept("ERROR launching Maven: " + e.getMessage());
                    if (onFailure != null) onFailure.run();
                }
            });
        } catch (Exception e) {
            output.accept("ERROR: " + e.getMessage());
            if (onFailure != null) onFailure.run();
        }
    }

    public static Path resolvePom(Project project, ServiceProfile service) {
        String base = project.getBasePath();
        Path projectBase = Path.of(base == null ? "." : base).toAbsolutePath().normalize();
        String configured = service.buildFilePath;
        if ((configured == null || configured.isBlank()) && service.pomPath != null && !service.pomPath.isBlank()) {
            configured = service.pomPath;
        }
        if (configured == null || configured.isBlank()) {
            Path pom = projectBase.resolve("pom.xml");
            if (!Files.isRegularFile(pom)) throw new IllegalArgumentException("No Maven project selected for " + service.name);
            return pom;
        }
        Path pom = Path.of(configured);
        if (!pom.isAbsolute()) pom = projectBase.resolve(pom);
        pom = pom.normalize();
        if (Files.isDirectory(pom)) pom = pom.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) throw new IllegalArgumentException("pom.xml not found: " + pom);
        return pom;
    }
}
