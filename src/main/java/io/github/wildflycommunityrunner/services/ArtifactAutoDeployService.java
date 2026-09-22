package io.github.wildflycommunityrunner.services;

import com.intellij.openapi.Disposable;
import com.intellij.ide.trustedProjects.TrustedProjects;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import io.github.wildflycommunityrunner.model.BuildSystem;
import io.github.wildflycommunityrunner.model.ServerProfile;
import io.github.wildflycommunityrunner.model.ServiceProfile;
import io.github.wildflycommunityrunner.util.ArtifactLocator;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Event-driven watcher for build artifacts. Auto redeploy watches target/ or build/libs/ (or the
 * configured artifact override) using the OS-backed WatchService. It intentionally watches the
 * final deployable artifact rather than source files, so builds started from IntelliJ's Maven/
 * Gradle tabs, a terminal, or this plugin all behave the same way.
 */
@Service(Service.Level.PROJECT)
public final class ArtifactAutoDeployService implements Disposable {
    private static final long DEBOUNCE_MS = 750;
    private static final long STABILITY_SAMPLE_MS = 250;
    private static final int STABILITY_SAMPLES = 6;

    private record WatchTarget(ServiceProfile service, Path desiredDirectory, Path watchedDirectory, String exactFileName) {}
    private record ArtifactStamp(long size, long modified) {}

    private final Project project;
    private final ExecutorService watchExecutor = Executors.newSingleThreadExecutor(daemonFactory("WildFly artifact watch"));
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(daemonFactory("WildFly auto redeploy"));
    private final Map<String, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();
    private final Map<String, ArtifactStamp> lastSuccessfulStamp = new ConcurrentHashMap<>();
    private final Set<String> suppressed = ConcurrentHashMap.newKeySet();
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    private volatile List<ServiceProfile> configuredServices = List.of();
    private volatile ServerProfile configuredServer;
    private volatile Consumer<String> output = ignored -> {};
    private volatile WatchService watchService;
    private volatile int generation;
    private volatile ScheduledFuture<?> restartFuture;
    private volatile boolean disposed;

    public ArtifactAutoDeployService(Project project) {
        this.project = project;
    }

    public static ArtifactAutoDeployService getInstance(Project project) {
        return project.getService(ArtifactAutoDeployService.class);
    }

    /** Reconcile watched services/server after UI or project settings change. */
    public synchronized void configure(List<ServiceProfile> services, ServerProfile server, Consumer<String> output) {
        if (disposed || project.isDisposed()) return;
        List<ServiceProfile> copies = new ArrayList<>();
        if (services != null) {
            for (ServiceProfile service : services) {
                if (service != null && service.deployAfterBuild) copies.add(new ServiceProfile(service));
            }
        }
        configuredServices = List.copyOf(copies);
        configuredServer = server == null ? null : new ServerProfile(server);
        if (output != null) this.output = output;
        restartWatcherLocked();
    }

    /** Suppress auto redeploy while an explicit Build-and-Deploy / Build-without-Deploy is running. */
    public void suppress(Collection<ServiceProfile> services) {
        if (services == null) return;
        for (ServiceProfile service : services) if (service != null) suppressed.add(service.id);
    }

    public void suppress(ServiceProfile service) {
        if (service != null) suppressed.add(service.id);
    }

    /** Keep suppression briefly after the build callback so late filesystem events are absorbed. */
    public void releaseSuppression(ServiceProfile service) {
        if (service == null) return;
        scheduler.schedule(() -> suppressed.remove(service.id), 2, TimeUnit.SECONDS);
    }

    public boolean isWatching(String serviceId) {
        if (serviceId == null) return false;
        return configuredServices.stream().anyMatch(s -> serviceId.equals(s.id));
    }

    private synchronized void restartWatcherLocked() {
        generation++;
        closeWatchService();
        if (disposed || project.isDisposed() || configuredServices.isEmpty()) return;

        final int localGeneration = generation;
        try {
            WatchService ws = FileSystems.getDefault().newWatchService();
            Map<Path, List<WatchTarget>> grouped = new HashMap<>();
            for (ServiceProfile service : configuredServices) {
                try {
                    WatchTarget target = createTarget(service);
                    grouped.computeIfAbsent(target.watchedDirectory(), ignored -> new ArrayList<>()).add(target);
                } catch (Exception e) {
                    out("Auto redeploy watcher skipped " + service.name + ": " + e.getMessage());
                }
            }
            if (grouped.isEmpty()) {
                ws.close();
                return;
            }

            Map<WatchKey, List<WatchTarget>> registrations = new HashMap<>();
            for (Map.Entry<Path, List<WatchTarget>> entry : grouped.entrySet()) {
                WatchKey key = entry.getKey().register(ws,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE);
                registrations.put(key, List.copyOf(entry.getValue()));
            }
            watchService = ws;
            watchExecutor.execute(() -> watchLoop(ws, registrations, localGeneration));
        } catch (IOException e) {
            out("Auto redeploy watcher could not start: " + e.getMessage());
        }
    }

    private WatchTarget createTarget(ServiceProfile service) throws IOException {
        Path moduleDir = BuildService.resolveModuleDir(project, service).toAbsolutePath().normalize();
        Path desired;
        String exact = null;
        if (service.artifactPath != null && !service.artifactPath.isBlank()) {
            Path artifact = Path.of(service.artifactPath);
            if (!artifact.isAbsolute()) artifact = moduleDir.resolve(artifact);
            artifact = artifact.toAbsolutePath().normalize();
            desired = artifact.getParent();
            exact = artifact.getFileName().toString();
        } else if (service.buildSystemEnum() == BuildSystem.GRADLE) {
            desired = moduleDir.resolve("build").resolve("libs");
        } else {
            desired = moduleDir.resolve("target");
        }
        if (desired == null) throw new IOException("Cannot determine artifact output directory");
        desired = desired.toAbsolutePath().normalize();
        Path watched = nearestExistingDirectory(desired, moduleDir);
        return new WatchTarget(new ServiceProfile(service), desired, watched, exact);
    }

    private static Path nearestExistingDirectory(Path desired, Path moduleDir) throws IOException {
        Path current = desired;
        while (current != null && !Files.isDirectory(current)) {
            if (current.equals(moduleDir)) break;
            current = current.getParent();
        }
        if (current == null || !Files.isDirectory(current)) {
            if (Files.isDirectory(moduleDir)) return moduleDir;
            throw new IOException("Module directory does not exist: " + moduleDir);
        }
        return current;
    }

    private void watchLoop(WatchService ws, Map<WatchKey, List<WatchTarget>> registrations, int localGeneration) {
        try {
            while (!project.isDisposed() && localGeneration == generation) {
                WatchKey key = ws.take();
                List<WatchTarget> targets = registrations.getOrDefault(key, List.of());
                boolean registrationNeedsRefresh = false;
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                        for (WatchTarget target : targets) scheduleCheck(target.service());
                        continue;
                    }
                    Object context = event.context();
                    Path relative = context instanceof Path p ? p : null;
                    for (WatchTarget target : targets) {
                        if (!target.watchedDirectory().equals(target.desiredDirectory())) {
                            Path towardDesired = target.watchedDirectory().relativize(target.desiredDirectory());
                            boolean relevantParentChange = relative == null
                                    || (towardDesired.getNameCount() > 0 && relative.getName(0).equals(towardDesired.getName(0)));
                            if (relevantParentChange) registrationNeedsRefresh = true;
                            // If creation happened faster than re-registration, a delayed resolve still catches the artifact.
                            if (Files.isDirectory(target.desiredDirectory())) scheduleCheck(target.service());
                        } else if (relevant(target, relative)) {
                            scheduleCheck(target.service());
                        }
                    }
                }
                if (!key.reset()) registrations.remove(key);
                if (registrationNeedsRefresh) scheduleRestart();
            }
        } catch (ClosedWatchServiceException ignored) {
            // Normal when settings change or the project closes.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean relevant(WatchTarget target, Path relative) {
        if (relative == null) return true;
        String name = relative.getFileName().toString();
        if (target.exactFileName() != null) return target.exactFileName().equals(name);
        String lower = name.toLowerCase();
        String packaging = target.service().packaging == null ? "auto" : target.service().packaging.toLowerCase();
        if ("war".equals(packaging)) return lower.endsWith(".war");
        if ("ear".equals(packaging)) return lower.endsWith(".ear");
        if ("jar".equals(packaging)) return lower.endsWith(".jar");
        return lower.endsWith(".war") || lower.endsWith(".ear") || lower.endsWith(".jar");
    }

    private void scheduleRestart() {
        synchronized (this) {
            if (restartFuture != null) restartFuture.cancel(false);
            restartFuture = scheduler.schedule(() -> {
                synchronized (ArtifactAutoDeployService.this) {
                    restartWatcherLocked();
                }
            }, 200, TimeUnit.MILLISECONDS);
        }
    }

    private void scheduleCheck(ServiceProfile service) {
        if (service == null || suppressed.contains(service.id)) return;
        ScheduledFuture<?> old = pending.remove(service.id);
        if (old != null) old.cancel(false);
        ScheduledFuture<?> future = scheduler.schedule(() -> checkAndDeploy(service.id), DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        pending.put(service.id, future);
    }

    private void checkAndDeploy(String serviceId) {
        pending.remove(serviceId);
        if (project.isDisposed() || !TrustedProjects.isProjectTrusted(project) || suppressed.contains(serviceId) || inFlight.contains(serviceId)) return;
        ServiceProfile service = currentService(serviceId);
        ServerProfile server = configuredServer == null ? null : new ServerProfile(configuredServer);
        if (service == null || !service.deployAfterBuild || server == null) return;

        if (!WildFlyProcessService.getInstance().isDetectedRunning(server)) {
            out("Auto redeploy skipped for " + service.name + ": WildFly is not running.");
            return;
        }

        try {
            Path artifact = ArtifactLocator.resolve(project, service).toAbsolutePath().normalize();
            ArtifactStamp stamp = awaitStable(artifact);
            if (stamp == null || Objects.equals(stamp, lastSuccessfulStamp.get(service.id))) return;
            if (!inFlight.add(service.id)) return;

            String deploymentName = ArtifactLocator.effectiveDeploymentName(service, artifact);
            out("Artifact changed; auto redeploying " + service.name + " (" + artifact.getFileName() + ")");
            DeploymentScannerService.deploy(project, server, artifact, deploymentName, this::out, ok -> {
                if (ok) lastSuccessfulStamp.put(service.id, stamp);
                inFlight.remove(service.id);
            });
        } catch (Exception e) {
            out("Auto redeploy skipped for " + service.name + ": " + e.getMessage());
        }
    }

    private ServiceProfile currentService(String id) {
        for (ServiceProfile service : configuredServices) {
            if (Objects.equals(service.id, id)) return new ServiceProfile(service);
        }
        return null;
    }

    private static ArtifactStamp awaitStable(Path artifact) throws Exception {
        if (!Files.isRegularFile(artifact)) return null;
        ArtifactStamp previous = stamp(artifact);
        int stable = 0;
        for (int i = 0; i < STABILITY_SAMPLES; i++) {
            Thread.sleep(STABILITY_SAMPLE_MS);
            if (!Files.isRegularFile(artifact)) return null;
            ArtifactStamp current = stamp(artifact);
            if (current.equals(previous)) {
                stable++;
                if (stable >= 2) return current;
            } else {
                stable = 0;
                previous = current;
            }
        }
        return previous;
    }

    private static ArtifactStamp stamp(Path artifact) throws IOException {
        return new ArtifactStamp(Files.size(artifact), Files.getLastModifiedTime(artifact).toMillis());
    }

    private void out(String message) {
        Consumer<String> consumer = output;
        if (consumer != null && message != null && !message.isBlank()) consumer.accept(message);
    }

    private static ThreadFactory daemonFactory(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    private synchronized void closeWatchService() {
        WatchService ws = watchService;
        watchService = null;
        if (ws != null) {
            try { ws.close(); } catch (IOException ignored) {}
        }
    }

    @Override
    public synchronized void dispose() {
        disposed = true;
        generation++;
        closeWatchService();
        if (restartFuture != null) restartFuture.cancel(false);
        for (ScheduledFuture<?> future : pending.values()) future.cancel(false);
        pending.clear();
        scheduler.shutdownNow();
        watchExecutor.shutdownNow();
    }
}
