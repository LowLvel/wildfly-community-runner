package io.github.wildflycommunityrunner.services;

import io.github.wildflycommunityrunner.util.ProjectTrust;
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
            io.github.wildflycommunityrunner.security.SensitiveSettingsMigration.schedule(project);
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
            ApplicationManager.getApplication().getMessageBus().connect(this).subscribe(WildFlyApplicationSettings.CHANGED,
                    new WildFlyApplicationSettings.Listener() {
                        @Override public void serversChanged() {
                            IdeUi.later(project, () -> disposed, () -> { reconcileServerSelection(); configureWatcher(null); });
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
        if (disposed || project.isDisposed() || !ProjectTrust.isTrusted(project)) return;
        if (initialization != null && !initialization.isDone()) return;
        var settings = WildFlyProjectSettings.getInstance(project);
        settings.migrateLegacyService();
        var state = settings.getState();
        boolean discover = !state.onboardingCompleted && state.services.isEmpty();
        var applicationSettings = WildFlyApplicationSettings.getInstance();
        boolean findHome = !applicationSettings.getState().environmentSetupCompleted && applicationSettings.servers().isEmpty();
        initialization = ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                ServerProfile candidate = findHome ? environmentProfile(System.getenv()) : null;
                var shared = readSharedServices();
                var choices = discover ? BuildProjectDiscoveryService.discover(project) : List.<BuildProjectDiscoveryService.BuildProjectChoice>of();
                IdeUi.later(project, () -> disposed, () -> {
                    if (!ProjectTrust.isTrusted(project)) return;
                    try {
                        applyInitialState(candidate, choices);
                        if (!shared.isEmpty()) settings.update(data -> io.github.wildflycommunityrunner.settings.ProjectDeploymentFile
                                .merge(Path.of(project.getBasePath()), data, shared));
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

    private List<ServiceProfile> readSharedServices() {
        try {
            return project.getBasePath() == null ? List.of()
                    : io.github.wildflycommunityrunner.settings.ProjectDeploymentFile.read(Path.of(project.getBasePath()));
        } catch (Exception error) {
            PluginNotifications.failure(project, "Shared WildFly settings were not loaded", error, null);
            return List.of(); // Invalid shared definitions must not disable existing local services and watches.
        }
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
        var settings = WildFlyProjectSettings.getInstance(project);
        app.update(state -> {
            if (candidate != null && !state.environmentSetupCompleted && state.servers.isEmpty())
                state.servers.add(new ServerProfile(candidate));
            if (!state.servers.isEmpty()) state.environmentSetupCompleted = true;
        });
        settings.update(state -> {
            if (!state.onboardingCompleted && state.services.isEmpty())
                for (var choice : choices) if (suggestedApplication(choice)) state.services.add(discoveredService(choice));
            state.onboardingCompleted = true;
        });
        for (ServiceProfile service : settings.services()) app.rememberService(BuildService.sourceSnapshot(project, service));
        reconcileServerSelection();
    }

    private void reconcileServerSelection() {
        var app = WildFlyApplicationSettings.getInstance();
        var servers = app.servers();
        String last = app.lastServerId();
        WildFlyProjectSettings.getInstance(project).update(state -> {
            if (servers.stream().noneMatch(s -> s.id.equals(state.selectedServerId))) {
                ServerProfile selection = servers.stream().filter(s -> s.id.equals(last)).findFirst()
                        .orElse(servers.isEmpty() ? null : servers.getFirst());
                state.selectedServerId = selection == null ? "" : selection.id;
            }
        });
    }

    public static ServiceProfile discoveredService(BuildProjectDiscoveryService.BuildProjectChoice choice) {
        var service = ServiceProfile.create();
        service.name = choice.name();
        service.buildSystem = choice.system().name();
        service.buildFilePath = choice.buildFilePath();
        service.packaging = "ejb".equalsIgnoreCase(choice.packaging()) ? "jar"
                : List.of("war", "ear", "jar").contains(choice.packaging()) ? choice.packaging() : "auto";
        service.buildTasks = service.defaultTasks();
        service.deploymentName = service.name.replaceAll("[^A-Za-z0-9._-]", "-") + "."
                + ("auto".equals(service.packaging) ? "war" : service.packaging);
        return service;
    }

    public static boolean suggestedApplication(BuildProjectDiscoveryService.BuildProjectChoice choice) {
        return List.of("war", "ear", "ejb").contains(choice.packaging().toLowerCase(java.util.Locale.ROOT));
    }

    /** Capture settings on the application queue; serialize filesystem registration in the background. */
    public void configureWatcher(Consumer<String> output) {
        if (disposed || project.isDisposed()) return;
        var state = WildFlyProjectSettings.getInstance(project).getState();
        boolean trusted = ProjectTrust.isTrusted(project);
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
