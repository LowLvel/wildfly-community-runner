package io.github.wildflycommunityrunner.services;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.application.ApplicationManager;
import io.github.wildflycommunityrunner.model.BuildSystem;
import io.github.wildflycommunityrunner.model.ServiceProfile;

import java.nio.file.Path;
import java.util.function.Consumer;

public final class BuildService {
    private BuildService() {}

    public static void build(Project project,
                             ServiceProfile service,
                             Runnable onSuccess,
                             Runnable onFailure,
                             Consumer<String> output) {
        ServiceProfile snapshot = new ServiceProfile(service);
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            if (project.isDisposed()) return;
            snapshot.migrateLegacyFields();
            if (snapshot.buildSystemEnum() == BuildSystem.GRADLE) {
                GradleBuildService.build(project, snapshot, onSuccess, onFailure, output);
            } else {
                MavenBuildService.build(project, snapshot, onSuccess, onFailure, output);
            }
        });
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
