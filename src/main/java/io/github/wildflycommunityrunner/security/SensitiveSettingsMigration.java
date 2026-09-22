package io.github.wildflycommunityrunner.security;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import io.github.wildflycommunityrunner.model.ServiceProfile;
import io.github.wildflycommunityrunner.services.PluginNotifications;
import io.github.wildflycommunityrunner.services.ProjectSetupService;
import io.github.wildflycommunityrunner.settings.WildFlyApplicationSettings;
import io.github.wildflycommunityrunner.settings.WildFlyProjectSettings;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Saves credentials before replacing old plaintext fields; concurrent edits always win. */
public final class SensitiveSettingsMigration {
    private static final AtomicBoolean GLOBAL_RUNNING = new AtomicBoolean();
    private SensitiveSettingsMigration() {}

    public static void schedule(Project project) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                if (project.isDisposed()) return;
                var secrets = JvmSecrets.getInstance();
                var app = WildFlyApplicationSettings.getInstance();
                if (GLOBAL_RUNNING.compareAndSet(false, true)) try {
                    for (var original : app.servers()) {
                        try (var protection = secrets.protection()) {
                            String jvm = protection.protect(original.jvmOptions);
                            String startup = protection.protect(original.startupArguments);
                            if (jvm.equals(original.jvmOptions) && startup.equals(original.startupArguments)) continue;
                            AtomicBoolean applied = new AtomicBoolean();
                            app.update(state -> state.servers.stream().filter(server -> server.id.equals(original.id)
                                    && Objects.equals(server.jvmOptions, original.jvmOptions)
                                    && Objects.equals(server.startupArguments, original.startupArguments)).forEach(server -> {
                                server.jvmOptions = jvm; server.startupArguments = startup; applied.set(true);
                            }));
                            if (applied.get()) protection.commit();
                        }
                    }
                    for (ServiceProfile original : app.knownServices()) migrateService(secrets, original,
                            edit -> app.updateSources(state -> state.knownServices.forEach(edit)));
                } finally { GLOBAL_RUNNING.set(false); }
                var settings = WildFlyProjectSettings.getInstance(project);
                for (ServiceProfile original : settings.services()) {
                    if (project.isDisposed()) return;
                    migrateService(secrets, original, edit -> settings.update(state -> state.services.forEach(edit)));
                }
                if (!project.isDisposed()) project.getMessageBus().syncPublisher(ProjectSetupService.CHANGED).initialized();
            } catch (Exception error) { PluginNotifications.failure(project, "JVM secret migration needs attention", error, null); }
        });
    }

    static void migrateService(JvmSecrets secrets, ServiceProfile original, Consumer<Consumer<ServiceProfile>> update) {
        try (var protection = secrets.protection()) {
            String jvm = protection.protect(original.buildJvmOptions);
            String arguments = protection.protect(original.buildArguments);
            String tasks = protection.protect(original.buildTasks);
            if (jvm.equals(original.buildJvmOptions) && arguments.equals(original.buildArguments) && tasks.equals(original.buildTasks)) return;
            AtomicBoolean applied = new AtomicBoolean();
            update.accept(service -> {
                if (service.id.equals(original.id) && Objects.equals(service.buildJvmOptions, original.buildJvmOptions)
                        && Objects.equals(service.buildArguments, original.buildArguments) && Objects.equals(service.buildTasks, original.buildTasks)) {
                    service.buildJvmOptions = jvm; service.buildArguments = arguments; service.buildTasks = tasks; applied.set(true);
                }
            });
            if (applied.get()) protection.commit();
        }
    }
}
