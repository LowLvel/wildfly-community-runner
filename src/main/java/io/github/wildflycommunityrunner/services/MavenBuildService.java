package io.github.wildflycommunityrunner.services;

import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ProgramRunner;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.progress.ProcessCanceledException;
import io.github.wildflycommunityrunner.util.IdeUi;
import io.github.wildflycommunityrunner.util.ProjectTrust;
import com.intellij.util.execution.ParametersListUtil;
import io.github.wildflycommunityrunner.model.ServiceProfile;
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

    static void build(Project project, ServiceProfile service, BuildOperation operation, Consumer<String> output) {
        Path pom = resolvePom(project, service);
        String tasks = service.buildTasks == null || service.buildTasks.isBlank() ? "clean package" : service.buildTasks;
        List<String> goals = new ArrayList<>(ParametersListUtil.parse(tasks));
        if (service.buildArguments != null && !service.buildArguments.isBlank()) goals.addAll(ParametersListUtil.parse(service.buildArguments));
        MavenRunnerParameters parameters = new MavenRunnerParameters();
        parameters.setWorkingDirPath(pom.getParent().toString());
        parameters.setPomFileName(pom.getFileName().toString());
        parameters.setGoals(goals);
        parameters.setResolveToWorkspace(true);
        IdeUi.later(project, () -> operation.completion().isDone(), () -> {
            try {
                if (!ProjectTrust.isTrusted(project)) throw new IllegalStateException("Trust this project before running a build.");
                FileDocumentManager.getInstance().saveAllDocuments();
                MavenRunnerSettings settings = MavenRunner.getInstance(project).getSettings().clone();
                String inherited = settings.getVmOptions() == null ? "" : settings.getVmOptions().trim();
                String options = service.buildJvmOptions == null ? "" : service.buildJvmOptions.trim();
                settings.setVmOptions((inherited + " " + options).trim());
                var configuration = MavenRunConfigurationType.createRunnerAndConfigurationSettings(
                        null, settings, parameters, project, "WildFly build: " + service.name, false);
                var callback = new ProgramRunner.Callback() {
                    @Override public void processStarted(com.intellij.execution.ui.RunContentDescriptor descriptor) {
                        ProcessHandler handler = descriptor.getProcessHandler();
                        if (handler == null) operation.failed(new IllegalStateException("Maven process did not start"));
                        else operation.bind(handler);
                    }
                    @Override public void processNotStarted(Throwable error) {
                        operation.failed(error == null ? new IllegalStateException("Maven launch did not start") : error);
                    }
                };
                var environment = com.intellij.execution.runners.ExecutionEnvironmentBuilder.create(
                        com.intellij.execution.executors.DefaultRunExecutor.getRunExecutorInstance(), configuration).build(callback);
                new BuildExecutionListener(project, environment, operation);
                if (!operation.beginLaunch()) return;
                output.accept("Building " + service.name + " — Maven " + String.join(" ", goals));
                environment.getRunner().execute(environment);
            } catch (ProcessCanceledException cancelled) {
                operation.failed(cancelled);
                throw cancelled;
            } catch (Exception error) { operation.failed(error); }
        });
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
