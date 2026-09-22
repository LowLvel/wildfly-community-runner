package io.github.wildflycommunityrunner.services;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import io.github.wildflycommunityrunner.model.BuildSystem;
import io.github.wildflycommunityrunner.model.ServerProfile;
import io.github.wildflycommunityrunner.model.ServiceProfile;
import io.github.wildflycommunityrunner.util.ArtifactLocator;
import io.github.wildflycommunityrunner.util.ProjectTrust;
import io.github.wildflycommunityrunner.util.WildFlyPaths;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Watches final archives, with generation-scoped work and one dirty bit retained during deployment. */
@Service(Service.Level.PROJECT)
public final class ArtifactAutoDeployService implements Disposable {
    private static final long DEBOUNCE_MS = 750;
    private static final class Slot {
        final ServiceProfile service;
        final ServerProfile server;
        final Path module;
        final Path source;
        final long generation;
        ScheduledFuture<?> pending;
        long ticket;
        boolean active;
        boolean dirty;
        int retries;
        ArtifactFingerprint failed;
        volatile String lastCheck = "Waiting for output event";
        Slot(ServiceProfile service, ServerProfile server, Path module, Path source, long generation) {
            this.service = service; this.server = server; this.module = module; this.source = source; this.generation = generation;
        }
    }
    private record WatchTarget(Slot slot, Path desired, Path watched, String exact) {}
    private final Project project;
    private final ExecutorService watcher = Executors.newSingleThreadExecutor(daemonFactory("WildFly artifact watch"));
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(daemonFactory("WildFly auto redeploy"));
    private final Map<String, Slot> slots = new HashMap<>();
    private volatile List<ServiceProfile> configuredServices = List.of();
    private volatile Consumer<String> output = ignored -> {};
    private volatile WatchService watchService;
    private volatile long generation;
    private volatile boolean disposed;
    private ScheduledFuture<?> restart;
    private int registrationFailures;
    private volatile String lastEvent = "none";

    public ArtifactAutoDeployService(Project project) { this.project = project; }
    public static ArtifactAutoDeployService getInstance(Project project) { return project.getService(ArtifactAutoDeployService.class); }

    /** Called in the background by ProjectSetupService, including when the tool window was never opened. */
    public synchronized void configure(List<ServiceProfile> services, ServerProfile server, Consumer<String> output) {
        if (disposed || project.isDisposed()) return;
        generation++;
        closeWatcher();
        if (restart != null) restart.cancel(false);
        for (Slot slot : slots.values()) if (slot.pending != null) slot.pending.cancel(false);
        slots.clear();
        if (output != null) this.output = output;
        configuredServices = services == null ? List.of() : services.stream().filter(Objects::nonNull)
                .filter(service -> service.deployAfterBuild).map(ServiceProfile::new).toList();
        for (ServiceProfile service : configuredServices) {
            try {
                var sameTarget = services.stream().filter(other -> io.github.wildflycommunityrunner.util.DeploymentNames.name(other)
                        .equalsIgnoreCase(io.github.wildflycommunityrunner.util.DeploymentNames.name(service))).toList();
                io.github.wildflycommunityrunner.util.DeploymentNames.requireUnique(sameTarget);
                Path source = BuildService.resolveBuildFile(project, service).toRealPath();
                slots.put(service.id, new Slot(service, server == null ? null : new ServerProfile(server),
                        source.getParent(), source, generation));
            } catch (Exception error) { out("Auto Redeploy skipped " + service.name + ": " + PluginNotifications.message(error)); }
        }
        register(false);
    }

    /** A lease suppresses this source in every project until its build and deployment have finished. */
    public Runnable suppress(ServiceProfile service) {
        try {
            var snapshot = new ServiceProfile(service);
            Path source = BuildService.resolveBuildFile(project, snapshot).toRealPath();
            return DeploymentCoordinator.getInstance().suppress(source,
                    () -> ArtifactFingerprint.read(ArtifactLocator.resolveInModule(source.getParent(), snapshot)));
        } catch (IOException error) { throw new IllegalArgumentException("Cannot watch the selected build file", error); }
    }

    public boolean isWatching(String serviceId) {
        return serviceId != null && configuredServices.stream().anyMatch(service -> serviceId.equals(service.id));
    }

    private synchronized void register(boolean rescan) {
        closeWatcher();
        if (disposed || project.isDisposed() || slots.isEmpty()) return;
        WatchService ws = null;
        try {
            ws = FileSystems.getDefault().newWatchService();
            Map<Path, List<WatchTarget>> grouped = new HashMap<>();
            for (Slot slot : slots.values()) {
                for (WatchTarget target : targets(slot)) grouped.computeIfAbsent(target.watched(), ignored -> new ArrayList<>()).add(target);
            }
            Map<Path, List<WatchTarget>> registrations = new HashMap<>();
            for (var entry : grouped.entrySet()) {
                WatchKey key = entry.getKey().register(ws, StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
                // Some IDE filesystem providers return a new key wrapper from every take().
                // Associate events by the public watched path, retaining all aliases of one registration.
                registrations.computeIfAbsent(watchablePath(key), ignored -> new ArrayList<>()).addAll(entry.getValue());
            }
            watchService = ws;
            registrationFailures = 0;
            WatchService owned = ws;
            long current = generation;
            watcher.execute(() -> watchLoop(owned, registrations, current));
            if (rescan) for (Slot slot : slots.values()) schedule(slot, DEBOUNCE_MS, true);
        } catch (IOException error) {
            if (ws != null) try { ws.close(); } catch (IOException ignored) {}
            if (registrationFailures++ == 0) {
                out("Auto Redeploy is retrying output-directory registration: " + PluginNotifications.message(error));
            }
            scheduleRestart(generation, Math.min(30_000L, 1000L * registrationFailures));
        }
    }

    private List<WatchTarget> targets(Slot slot) throws IOException {
        var service = slot.service;
        if (service.artifactPath != null && !service.artifactPath.isBlank()) {
            Path artifact = Path.of(service.artifactPath);
            if (!artifact.isAbsolute()) artifact = slot.module.resolve(artifact);
            artifact = artifact.toAbsolutePath().normalize();
            return List.of(target(slot, artifact.getParent(), artifact.getFileName().toString()));
        }
        if (service.buildSystemEnum() == BuildSystem.GRADLE) {
            return List.of(target(slot, slot.module.resolve("build/libs"), null), target(slot, slot.module.resolve("build"), null));
        }
        return List.of(target(slot, slot.module.resolve("target"), null));
    }
    private static WatchTarget target(Slot slot, Path desired, String exact) throws IOException {
        if (desired == null) throw new IOException("Cannot determine artifact directory");
        Path watched = desired;
        while (watched != null && !Files.isDirectory(watched)) watched = watched.getParent();
        if (watched == null) throw new IOException("Output directory has no accessible parent");
        return new WatchTarget(slot, desired, watched, exact);
    }

    private void watchLoop(WatchService ws, Map<Path, List<WatchTarget>> registrations, long expected) {
        try {
            while (!disposed && !project.isDisposed() && expected == generation && watchService == ws) {
                WatchKey key = ws.take();
                Path watchedPath = watchablePath(key);
                boolean refresh = false;
                for (WatchEvent<?> event : key.pollEvents()) {
                    Path relative = event.context() instanceof Path path ? path : null;
                    lastEvent = event.kind().name() + " " + event.context() + " ("
                            + (event.context() == null ? "null" : event.context().getClass().getName()) + ") on " + key.watchable();
                    for (WatchTarget target : registrations.getOrDefault(watchedPath, List.of())) {
                        if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                            schedule(target.slot(), DEBOUNCE_MS, true); refresh = true;
                        } else if (!target.watched().equals(target.desired())) {
                            if (towardOutput(target.watched(), target.desired(), relative)) {
                                refresh = true;
                                schedule(target.slot(), DEBOUNCE_MS, true);
                            }
                        } else if (relevant(target.slot().service, target.exact(), relative)) {
                            schedule(target.slot(), DEBOUNCE_MS, true);
                        }
                    }
                }
                if (!key.reset()) { registrations.remove(watchedPath); refresh = true; }
                if (refresh) scheduleRestart(expected);
                if (registrations.isEmpty()) return;
            }
        } catch (ClosedWatchServiceException ignored) { /* Reconfiguration or disposal. */ }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    static boolean towardOutput(Path watched, Path desired, Path relative) {
        Path route = watched.relativize(desired);
        return relative != null && route.getNameCount() > 0
                && watched.resolve(relative.getName(0).toString()).equals(watched.resolve(route.getName(0).toString()));
    }
    static Path watchablePath(WatchKey key) {
        if (!(key.watchable() instanceof Path path)) throw new IllegalArgumentException("Watch registration has no filesystem path");
        // Rebase through the default provider; key.watchable() can expose its unwrapped delegate path.
        return Path.of(path.toString()).toAbsolutePath().normalize();
    }
    static boolean relevant(ServiceProfile service, String exact, Path relative) {
        if (relative == null) return false;
        String name = relative.getFileName().toString();
        if (exact != null) return exact.equals(name);
        if (ArtifactLocator.auxiliaryArchive(name)) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        String packaging = Objects.toString(service.packaging, "auto").toLowerCase(Locale.ROOT);
        return switch (packaging) {
            case "war", "ear", "jar" -> lower.endsWith("." + packaging);
            default -> lower.endsWith(".war") || lower.endsWith(".ear") || lower.endsWith(".jar");
        };
    }

    private synchronized void scheduleRestart(long expected) {
        scheduleRestart(expected, 200);
    }
    private synchronized void scheduleRestart(long expected, long delay) {
        if (disposed || expected != generation) return;
        if (restart != null) restart.cancel(false);
        restart = scheduler.schedule(() -> {
            synchronized (ArtifactAutoDeployService.this) { if (!disposed && expected == generation) register(true); }
        }, delay, TimeUnit.MILLISECONDS);
    }
    private synchronized boolean current(Slot slot) {
        return !disposed && !project.isDisposed() && slot.generation == generation && slots.get(slot.service.id) == slot;
    }
    private synchronized void schedule(Slot slot, long delay, boolean event) {
        if (!current(slot)) return;
        slot.dirty = true;
        if (event) slot.retries = 0;
        if (slot.active) return;
        if (slot.pending != null) slot.pending.cancel(false);
        long ticket = ++slot.ticket;
        slot.pending = scheduler.schedule(() -> check(slot, ticket), delay, TimeUnit.MILLISECONDS);
    }

    private void check(Slot slot, long ticket) {
        synchronized (this) {
            if (!current(slot) || ticket != slot.ticket) return;
            slot.pending = null; slot.active = true; slot.dirty = false;
        }
        boolean async = false;
        boolean retry = false;
        DeploymentCoordinator.AutoLease lease = null;
        try {
            var coordinator = DeploymentCoordinator.getInstance();
            slot.lastCheck = "Checking server and trust";
            if (slot.server == null) { slot.lastCheck = "No server"; return; }
            if (!ProjectTrust.isTrusted(project)) { slot.lastCheck = "Untrusted project"; return; }
            if (coordinator.suppressed(slot.source, null)) { slot.lastCheck = "Suppressed source"; return; }
            if (!WildFlyProcessService.getInstance().isDetectedRunning(slot.server)) { slot.lastCheck = "Server not running"; return; }
            slot.lastCheck = "Waiting for stable archive";
            Path artifact = ArtifactLocator.resolveInModule(slot.module, slot.service);
            ArtifactFingerprint fingerprint = ArtifactFingerprint.stable(artifact, () -> !current(slot));
            if (fingerprint == null) { retry = true; return; }
            if (!current(slot) || !ProjectTrust.isTrusted(project) || coordinator.suppressed(slot.source, fingerprint)
                    || fingerprint.equals(slot.failed)) return;
            String name = DeploymentScannerService.safeDeploymentName(ArtifactLocator.effectiveDeploymentName(slot.service, artifact));
            var associated = io.github.wildflycommunityrunner.settings.WildFlyApplicationSettings.getInstance().findDeploymentSource(slot.server, name);
            if (associated != null && !BuildService.resolveBuildFile(project, associated).toRealPath().equals(slot.source)) {
                slot.lastCheck = "Deployment belongs to another source. Use Associate Source before enabling Auto Redeploy.";
                out("Auto Redeploy skipped " + slot.service.name + ": " + slot.lastCheck);
                return;
            }
            var claim = coordinator.claim(WildFlyPaths.deploymentsDir(slot.server).resolve(name), fingerprint);
            if (claim.unchanged()) return;
            if (claim.busy() != null) {
                async = true;
                claim.busy().whenComplete((unused, error) -> finished(slot, true));
                return;
            }
            lease = claim.lease();
            DeploymentCoordinator.AutoLease owned = lease;
            synchronized (this) {
                if (!current(slot) || !ProjectTrust.isTrusted(project) || coordinator.suppressed(slot.source, fingerprint)) return;
                out("Artifact changed; auto redeploying " + slot.service.name + " (" + artifact.getFileName() + ")");
                DeploymentScannerService.deploy(project, slot.server, artifact, name, this::out, ok -> {
                    try {
                        if (ok) io.github.wildflycommunityrunner.settings.WildFlyApplicationSettings.getInstance()
                                .rememberDeployment(slot.server, name, BuildService.sourceSnapshot(project, slot.service));
                        synchronized (ArtifactAutoDeployService.this) { if (current(slot)) slot.failed = ok ? null : fingerprint; }
                        owned.complete(ok);
                    } finally { finished(slot, false); }
                }, fingerprint, () -> current(slot) && ProjectTrust.isTrusted(project)
                        && !coordinator.suppressed(slot.source, fingerprint));
                async = true;
            }
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch (IOException error) {
            slot.lastCheck = PluginNotifications.message(error);
            retry = true;
            if (slot.retries >= 9) out("Auto Redeploy skipped " + slot.service.name + ": " + PluginNotifications.message(error));
        } catch (Exception error) { out("Auto Redeploy skipped " + slot.service.name + ": " + PluginNotifications.message(error)); }
        finally {
            if (!async) {
                if (lease != null) lease.complete(false);
                finished(slot, retry);
            }
        }
    }

    private synchronized void finished(Slot slot, boolean retry) {
        if (!current(slot)) return;
        slot.active = false;
        if (slot.dirty) schedule(slot, DEBOUNCE_MS, false);
        else if (retry && slot.retries++ < 10) schedule(slot, 1000, false);
    }
    private void out(String message) { if (!disposed) output.accept(message); }
    synchronized String diagnosticState() {
        return "disposed=" + disposed + ", generation=" + generation + ", watch=" + (watchService != null)
                + ", workers stopped=" + watcher.isShutdown() + "/" + scheduler.isShutdown() + ", last event=" + lastEvent + ", slots=" + slots.values().stream()
                .map(slot -> slot.source + " [ticket=" + slot.ticket + ", active=" + slot.active + ", " + slot.lastCheck + "]").toList();
    }
    private static ThreadFactory daemonFactory(String name) {
        return runnable -> { var thread = new Thread(runnable, name); thread.setDaemon(true); return thread; };
    }
    private void closeWatcher() {
        WatchService ws = watchService; watchService = null;
        if (ws != null) try { ws.close(); } catch (IOException ignored) {}
    }
    @Override public synchronized void dispose() {
        disposed = true; generation++;
        closeWatcher();
        if (restart != null) restart.cancel(false);
        for (Slot slot : slots.values()) if (slot.pending != null) slot.pending.cancel(false);
        slots.clear(); configuredServices = List.of();
        scheduler.shutdownNow(); watcher.shutdownNow();
    }
}
