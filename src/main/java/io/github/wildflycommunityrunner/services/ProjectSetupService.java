package io.github.wildflycommunityrunner.services;

import com.intellij.ide.trustedProjects.TrustedProjects;
import com.intellij.ide.trustedProjects.TrustedProjectsListener;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.util.messages.Topic;
import io.github.wildflycommunityrunner.model.BuildSystem;
import io.github.wildflycommunityrunner.model.ServerProfile;
import io.github.wildflycommunityrunner.model.ServiceProfile;
import io.github.wildflycommunityrunner.settings.WildFlyApplicationSettings;
import io.github.wildflycommunityrunner.settings.WildFlyProjectSettings;
import io.github.wildflycommunityrunner.util.IdeUi;
import io.github.wildflycommunityrunner.util.WildFlyPaths;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Initializes saved artifact watches independently of tool-window creation. Never builds or starts a server. */
@Service(Service.Level.PROJECT)
public final class ProjectSetupService implements Disposable {
    public interface Listener { void initialized(); }
    public static final Topic<Listener> CHANGED = Topic.create("WildFly project initialized", Listener.class);
    private final Project project;
    private final Object watchLock = new Object();
    private final AtomicInteger watchGeneration = new AtomicInteger();
    private volatile boolean disposed;
    private boolean started;
    private volatile Future<?> initialization;

    public ProjectSetupService(Project project) { this.project = project; }

    public void start() {
        IdeUi.later(project, () -> disposed, () -> {
            if (started) return;
            started = true;
            // The modern trust listener is public but marked experimental in the 2025.1 baseline.
            ApplicationManager.getApplication().getMessageBus().connect(this).subscribe(TrustedProjectsListener.TOPIC,
                    new TrustedProjectsListener() {
                        @Override public void onProjectTrusted(Project changed) {
                            if (changed == project) IdeUi.later(project, () -> disposed, () -> initializeSafely());
                        }
                        @Override public void onProjectUntrusted(Project changed) {
                            if (changed == project) IdeUi.later(project, () -> disposed, () -> configureWatcher(null));
                        }
                    });
            initializeSafely();
        });
    }

    private void initializeSafely() {
        try { initialize(); }
        catch (Exception error) { PluginNotifications.failure(project, "WildFly setup failed", error, null); }
    }

    private void initialize() {
        if (disposed || project.isDisposed() || !TrustedProjects.isProjectTrusted(project)) return;
        if (initialization != null && !initialization.isDone()) return;
        var settings = WildFlyProjectSettings.getInstance(project);
        settings.migrateLegacyService();
        var state = settings.getState();
        boolean discover = !state.onboardingCompleted && state.services.isEmpty();
        boolean findHome = WildFlyApplicationSettings.getInstance().servers().isEmpty();
        initialization = ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                ServerProfile candidate = findHome ? environmentProfile(System.getenv()) : null;
                var choices = discover ? BuildProjectDiscoveryService.discover(project) : List.<BuildProjectDiscoveryService.BuildProjectChoice>of();
                IdeUi.later(project, () -> disposed, () -> {
                    if (!TrustedProjects.isProjectTrusted(project)) return;
                    try {
                        applyInitialState(candidate, choices);
                        configureWatcher(null);
                        project.getMessageBus().syncPublisher(CHANGED).initialized();
                    } catch (Exception error) { PluginNotifications.failure(project, "WildFly setup failed", error, null); }
                });
            } catch (ProcessCanceledException cancelled) {
                throw cancelled;
            } catch (Exception error) {
                PluginNotifications.failure(project, "WildFly setup failed", error, null);
            }
        });
    }

    /** Background filesystem validation, bounded to the two explicit environment values. */
    static ServerProfile environmentProfile(Map<String, String> environment) {
        for (String variable : List.of("WILDFLY_HOME", "JBOSS_HOME")) {
            String home = environment.get(variable);
            if (home == null || home.isBlank()) continue;
            try {
                var profile = new ServerProfile();
                profile.home = Path.of(WildFlyPaths.unquote(home.trim())).toAbsolutePath().normalize().toString();
                if (WildFlyPaths.validate(profile) == null) return profile;
            } catch (IllegalArgumentException ignored) { /* An invalid environment path is only a failed suggestion. */ }
        }
        return null;
    }

    /** Runs on the IntelliJ application queue; user edits made during discovery take precedence. */
    void applyInitialState(ServerProfile candidate, List<BuildProjectDiscoveryService.BuildProjectChoice> choices) {
        var app = WildFlyApplicationSettings.getInstance();
        var state = WildFlyProjectSettings.getInstance(project).getState();
        if (candidate != null && app.servers().isEmpty()) app.servers().add(new ServerProfile(candidate));
        if (!state.onboardingCompleted && state.services.isEmpty()) {
            for (var choice : choices) {
                ServiceProfile service = discoveredService(choice);
                state.services.add(service);
                app.rememberService(service);
            }
        }
        state.onboardingCompleted = true;
        for (ServiceProfile service : state.services) app.rememberService(service);
        if (app.servers().stream().noneMatch(s -> s.id.equals(state.selectedServerId))) {
            ServerProfile selection = app.servers().stream().filter(s -> s.id.equals(app.lastServerId())).findFirst()
                    .orElse(app.servers().isEmpty() ? null : app.servers().getFirst());
            state.selectedServerId = selection == null ? "" : selection.id;
        }
    }

    public static ServiceProfile discoveredService(BuildProjectDiscoveryService.BuildProjectChoice choice) {
        var service = new ServiceProfile();
        service.name = choice.name();
        service.buildSystem = choice.system().name();
        service.buildFilePath = choice.buildFilePath();
        service.packaging = List.of("war", "ear", "jar").contains(choice.packaging()) ? choice.packaging() : "auto";
        service.buildTasks = service.defaultTasks();
        service.buildArguments = choice.system() == BuildSystem.MAVEN ? "-DskipTests" : "-x test";
        service.deploymentName = service.name.replaceAll("[^A-Za-z0-9._-]", "-") + "."
                + ("auto".equals(service.packaging) ? "war" : service.packaging);
        return service;
    }

    /** Capture settings on the application queue; serialize filesystem registration in the background. */
    public void configureWatcher(Consumer<String> output) {
        if (disposed || project.isDisposed()) return;
        var state = WildFlyProjectSettings.getInstance(project).getState();
        boolean trusted = TrustedProjects.isProjectTrusted(project);
        List<ServiceProfile> services = trusted ? state.services.stream().map(ServiceProfile::new).toList() : List.of();
        ServerProfile server = WildFlyApplicationSettings.getInstance().servers().stream()
                .filter(s -> s.id.equals(state.selectedServerId)).findFirst().map(ServerProfile::new).orElse(null);
        int generation = watchGeneration.incrementAndGet();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            synchronized (watchLock) {
                if (disposed || project.isDisposed() || generation != watchGeneration.get()) return;
                try { ArtifactAutoDeployService.getInstance(project).configure(services, server, output); }
                catch (Exception error) { PluginNotifications.failure(project, "Auto Redeploy setup failed", error, output); }
            }
        });
    }

    @Override public void dispose() {
        disposed = true;
        watchGeneration.incrementAndGet();
        if (initialization != null) initialization.cancel(true);
    }
}
