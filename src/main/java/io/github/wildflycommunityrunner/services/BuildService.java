package io.github.wildflycommunityrunner.services;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.application.ApplicationManager;
import io.github.wildflycommunityrunner.model.BuildSystem;
import io.github.wildflycommunityrunner.model.ServiceProfile;

import java.nio.file.Path;
import java.util.function.Consumer;

public final class BuildService {
    private BuildService() {}

    public static BuildOperation build(Project project, ServiceProfile service, Consumer<String> output) {
        ServiceProfile snapshot = new ServiceProfile(service);
        var operation = new BuildOperation(command -> ApplicationManager.getApplication().executeOnPooledThread(command));
        operation.completion().thenAccept(result -> output.accept(snapshot.name + ": " + result.detail()));
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                if (project.isDisposed()) { operation.cancel(); return; }
                snapshot.migrateLegacyFields();
                if (snapshot.buildRootPath != null && !snapshot.buildRootPath.isBlank()) snapshot.buildFilePath = snapshot.buildRootPath;
                if (snapshot.buildJavaHome != null && !snapshot.buildJavaHome.isBlank()) {
                    String executable = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
                    if (!java.nio.file.Files.isRegularFile(Path.of(snapshot.buildJavaHome).resolve("bin").resolve(executable)))
                        throw new IllegalArgumentException("Build JAVA_HOME does not contain a Java executable: " + snapshot.buildJavaHome);
                }
                io.github.wildflycommunityrunner.security.SensitiveProperties.requireJvmField(snapshot.buildTasks, "build tasks/goals");
                io.github.wildflycommunityrunner.security.SensitiveProperties.requireJvmField(snapshot.buildArguments, "build arguments");
                if (snapshot.buildSystemEnum() == BuildSystem.GRADLE) GradleBuildService.build(project, snapshot, operation, output);
                else MavenBuildService.build(project, snapshot, operation, output);
            } catch (com.intellij.openapi.progress.ProcessCanceledException cancelled) {
                operation.failed(cancelled);
                throw cancelled;
            } catch (Exception error) { operation.failed(error); }
        });
        return operation;
    }

    public static Path resolveBuildFile(Project project, ServiceProfile service) {
        service.migrateLegacyFields();
        return service.buildSystemEnum() == BuildSystem.GRADLE
                ? GradleBuildService.resolveBuildFile(project, service)
                : MavenBuildService.resolvePom(project, service);
    }

    public static Path resolveModuleDir(Project project, ServiceProfile service) {
        return resolveBuildFile(project, service).getParent();
    }
}
