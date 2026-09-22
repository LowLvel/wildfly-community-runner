package io.github.wildflycommunityrunner.services;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/** Application-wide ownership of scanner targets and explicit-build suppression. */
@Service(Service.Level.APP)
public final class DeploymentCoordinator implements Disposable {
    private static final class LockEntry { final ReentrantLock lock = new ReentrantLock(); int users; }
    private static final class Suppression { int users; long generation; ArtifactFingerprint ignored; }
    private record Success(ArtifactFingerprint fingerprint, ArtifactFingerprint.Stamp artifact, ArtifactFingerprint.Stamp marker) {}
    record Claim(AutoLease lease, CompletableFuture<Void> busy, boolean unchanged) {}
    private final Map<Path, LockEntry> locks = new HashMap<>();
    private final Map<Path, Suppression> suppressions = new LinkedHashMap<>();
    private final Map<Path, CompletableFuture<Void>> flights = new HashMap<>();
    private final Map<Path, Success> successes = new LinkedHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        var thread = new Thread(runnable, "WildFly deployment coordination"); thread.setDaemon(true); return thread;
    });
    private boolean disposed;

    public static DeploymentCoordinator getInstance() { return ApplicationManager.getApplication().getService(DeploymentCoordinator.class); }

    /** Resolve the existing parent, retaining the filename because scanner copies are atomically replaced. */
    static Path targetKey(Path target) throws IOException {
        Path absolute = target.toAbsolutePath().normalize();
        Path existing = absolute.getParent();
        while (existing != null && !Files.exists(existing)) existing = existing.getParent();
        return existing == null ? absolute : existing.toRealPath().resolve(existing.relativize(absolute)).normalize();
    }

    <T> T withTarget(Project project, Path target, Callable<T> action) throws Exception {
        Path key = targetKey(target);
        LockEntry entry;
        synchronized (this) {
            if (disposed) throw new ProcessCanceledException();
            entry = locks.computeIfAbsent(key, ignored -> new LockEntry()); entry.users++;
        }
        boolean acquired = false;
        try {
            while (!(acquired = entry.lock.tryLock(100, TimeUnit.MILLISECONDS))) {
                ProgressManager.checkCanceled();
                if (project.isDisposed()) throw new ProcessCanceledException();
            }
            if (project.isDisposed()) throw new ProcessCanceledException();
            synchronized (this) { if (disposed) throw new ProcessCanceledException(); successes.remove(key); }
            return action.call();
        } finally {
            if (acquired) entry.lock.unlock();
            synchronized (this) { if (--entry.users == 0) locks.remove(key, entry); }
        }
    }

    Claim claim(Path target, ArtifactFingerprint fingerprint) throws IOException {
        Path key = targetKey(target);
        Success success;
        synchronized (this) {
            if (disposed) return new Claim(null, null, true);
            var busy = flights.get(key);
            if (busy != null) return new Claim(null, busy, false);
            success = successes.get(key);
        }
        boolean unchanged = success != null && success.fingerprint().equals(fingerprint) && stillDeployed(key, success);
        synchronized (this) {
            if (disposed) return new Claim(null, null, true);
            var busy = flights.get(key);
            if (busy != null) return new Claim(null, busy, false);
            if (unchanged && successes.get(key) == success) return new Claim(null, null, true);
            var completion = new CompletableFuture<Void>();
            flights.put(key, completion);
            return new Claim(new AutoLease(key, fingerprint, completion), null, false);
        }
    }

    private static boolean stillDeployed(Path target, Success success) {
        try {
            for (String suffix : List.of(".failed", ".dodeploy", ".isdeploying", ".pending", ".isundeploying", ".undeployed")) {
                if (Files.exists(marker(target, suffix))) return false;
            }
            return success.artifact().equals(ArtifactFingerprint.stamp(target))
                    && success.marker().equals(ArtifactFingerprint.stamp(marker(target, ".deployed")));
        } catch (IOException error) { return false; }
    }
    private static Path marker(Path target, String suffix) { return target.resolveSibling(target.getFileName() + suffix); }

    final class AutoLease {
        private final Path target;
        private final ArtifactFingerprint fingerprint;
        private final CompletableFuture<Void> completion;
        private final AtomicBoolean closed = new AtomicBoolean();
        private AutoLease(Path target, ArtifactFingerprint fingerprint, CompletableFuture<Void> completion) {
            this.target = target; this.fingerprint = fingerprint; this.completion = completion;
        }
        void complete(boolean success) {
            if (!closed.compareAndSet(false, true)) return;
            Success observed = null;
            if (success) {
                try { observed = new Success(fingerprint, ArtifactFingerprint.stamp(target), ArtifactFingerprint.stamp(marker(target, ".deployed"))); }
                catch (IOException ignored) { /* A concurrent manual operation may have replaced the markers. */ }
            }
            synchronized (DeploymentCoordinator.this) {
                if (!disposed) {
                    if (observed != null) successes.put(target, observed);
                    else successes.remove(target);
                    while (successes.size() > 256) successes.remove(successes.keySet().iterator().next());
                }
                flights.remove(target, completion);
            }
            completion.complete(null);
        }
    }

    synchronized Runnable suppress(Path source, Callable<ArtifactFingerprint> settledArtifact) {
        if (disposed) return () -> {};
        Suppression state = suppressions.computeIfAbsent(source, ignored -> new Suppression());
        state.users++; state.generation++;
        AtomicBoolean closed = new AtomicBoolean();
        return () -> {
            if (!closed.compareAndSet(false, true)) return;
            synchronized (DeploymentCoordinator.this) {
                if (disposed) return;
                scheduler.schedule(() -> release(source, state, settledArtifact), 2, TimeUnit.SECONDS);
            }
        };
    }

    private void release(Path source, Suppression state, Callable<ArtifactFingerprint> settledArtifact) {
        long generation;
        synchronized (this) { if (disposed) return; generation = state.generation; }
        ArtifactFingerprint fingerprint = null;
        try { fingerprint = settledArtifact.call(); }
        catch (Exception ignored) { /* A cancelled/failed build may leave no valid archive. */ }
        synchronized (this) {
            if (disposed) return;
            if (--state.users == 0 && generation == state.generation) state.ignored = fingerprint;
            if (suppressions.size() > 256) {
                var entries = suppressions.entrySet().iterator();
                while (entries.hasNext() && suppressions.size() > 256) if (entries.next().getValue().users == 0) entries.remove();
            }
        }
    }

    synchronized boolean suppressed(Path source, ArtifactFingerprint fingerprint) {
        Suppression state = suppressions.get(source);
        return state != null && (state.users > 0 || fingerprint != null && fingerprint.equals(state.ignored));
    }

    @Override public void dispose() {
        List<CompletableFuture<Void>> pending;
        synchronized (this) {
            disposed = true;
            scheduler.shutdownNow();
            pending = List.copyOf(flights.values());
            flights.clear(); successes.clear(); suppressions.clear();
        }
        for (var completion : pending) completion.complete(null);
    }
}
