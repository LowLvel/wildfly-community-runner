package io.github.wildflycommunityrunner.services;

import io.github.wildflycommunityrunner.model.ServerProfile;
import io.github.wildflycommunityrunner.model.ServiceProfile;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Sequential build/deploy orchestration, independent of any Swing component. */
public final class BuildBatch {
    public enum Mode { AUTO, FORCE_DEPLOY, BUILD_ONLY }
    public record Status(String text, int completed, int total, boolean active) {}
    public record Result(BuildOperation.Outcome outcome, String detail, boolean failureReported) {}
    public interface Backend {
        BuildOperation build(ServiceProfile service);
        CompletableFuture<Boolean> deploy(ServiceProfile service, ServerProfile server);
        Runnable suppress(ServiceProfile service);
        default String buildKey(ServiceProfile service) {
            if (service.buildRootPath == null || service.buildRootPath.isBlank()) return "service:" + service.id;
            return service.buildSystem + "\n" + service.buildRootPath + "\n" + service.buildTasks + "\n"
                    + service.buildArguments + "\n" + service.buildJvmOptions + "\n" + service.buildJavaHome;
        }
    }
    private final List<ServiceProfile> services;
    private final ServerProfile server;
    private final Mode mode;
    private final boolean external;
    private final Backend backend;
    private final Executor executor;
    private final Consumer<Status> progress;
    private final CompletableFuture<Result> completion = new CompletableFuture<>();
    private BuildOperation operation;
    private final java.util.List<Runnable> releases = new java.util.ArrayList<>();
    private final java.util.Set<String> completedBuilds = new java.util.HashSet<>();
    private boolean suppressed;
    private int index;
    private boolean started;
    private boolean cancelled;
    private boolean deploying;
    private Status status;

    public BuildBatch(List<ServiceProfile> services, ServerProfile server, Mode mode, boolean external,
                      Backend backend, Executor executor, Consumer<Status> progress) {
        this.services = services.stream().map(ServiceProfile::new).toList();
        this.server = server == null ? null : new ServerProfile(server);
        this.mode = mode;
        this.external = external;
        this.backend = backend;
        this.executor = executor;
        this.progress = progress;
        status = new Status("Build queued", 0, services.size(), true);
    }

    public CompletableFuture<Result> completion() { return completion; }
    public synchronized Status status() { return status; }
    public synchronized void start() {
        if (started) return;
        started = true;
        executor.execute(this::next);
    }

    private synchronized void next() {
        if (completion.isDone()) return;
        if (cancelled) { finish(BuildOperation.Outcome.CANCELLED, "Build cancelled", false); return; }
        if (index == services.size()) { finish(BuildOperation.Outcome.SUCCESS, "Build operation completed", false); return; }
        ServiceProfile service = services.get(index);
        update("Building " + (index + 1) + "/" + services.size() + ": " + service.name, true);
        try {
            if (mode != Mode.AUTO && !suppressed) {
                suppressed = true;
                for (ServiceProfile source : services) releases.add(backend.suppress(source));
            }
            if (completedBuilds.contains(backend.buildKey(service))) { deployOrAdvance(service); return; }
            operation = backend.build(service);
            BuildOperation expected = operation;
            operation.completion().whenComplete((result, error) -> executor.execute(() -> built(expected, result, error)));
        } catch (Exception error) { failed(error); }
    }

    private synchronized void built(BuildOperation expected, BuildOperation.Result result, Throwable error) {
        if (completion.isDone() || operation != expected) return;
        operation = null;
        if (cancelled || result != null && result.outcome() == BuildOperation.Outcome.CANCELLED) {
            finish(BuildOperation.Outcome.CANCELLED, "Build cancelled", false);
        } else if (error != null) {
            failed(error);
        } else if (result.outcome() != BuildOperation.Outcome.SUCCESS) {
            finish(BuildOperation.Outcome.FAILED, services.get(index).name + ": " + result.detail(), false);
        } else {
            ServiceProfile service = services.get(index);
            completedBuilds.add(backend.buildKey(service));
            deployOrAdvance(service);
        }
    }

    private void deployOrAdvance(ServiceProfile service) {
            boolean deploy = server != null && (mode == Mode.FORCE_DEPLOY || mode == Mode.AUTO && external && service.deployAfterBuild);
            if (!deploy) { advance(); return; }
            deploying = true;
            update("Deploying " + (index + 1) + "/" + services.size() + ": " + service.name, true);
            try { backend.deploy(service, server).whenComplete((ok, failure) -> executor.execute(() -> deployed(ok, failure))); }
            catch (Exception failure) { failed(failure); }
    }

    private synchronized void deployed(Boolean ok, Throwable error) {
        if (completion.isDone()) return;
        deploying = false;
        if (cancelled) finish(BuildOperation.Outcome.CANCELLED, "Build cancelled after current deployment finished", false);
        else if (error != null) failed(error);
        else if (!Boolean.TRUE.equals(ok)) finish(BuildOperation.Outcome.FAILED, "Deployment failed: " + services.get(index).name, true);
        else advance();
    }

    private void advance() {
        index++;
        executor.execute(this::next);
    }

    public synchronized void cancel() {
        if (completion.isDone() || cancelled) return;
        cancelled = true;
        update(deploying ? "Cancelling after current deployment finishes…" : "Cancelling build…", true);
        if (operation != null) operation.cancel();
        else if (!deploying) finish(BuildOperation.Outcome.CANCELLED, "Build cancelled", false);
    }

    public synchronized void dispose() {
        cancel();
        if (operation != null) operation.dispose();
        // Other projects may watch this source. Hold its global lease until actual completion.
        if (!deploying && (operation == null || operation.completion().isDone())) {
            finish(BuildOperation.Outcome.CANCELLED, "Project closed", false);
        }
    }

    private void failed(Throwable error) {
        if (error instanceof com.intellij.openapi.progress.ProcessCanceledException || error instanceof InterruptedException) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            finish(BuildOperation.Outcome.CANCELLED, "Build cancelled", false);
        } else finish(BuildOperation.Outcome.FAILED, PluginNotifications.message(error), false);
    }
    private void release() {
        for (Runnable lease : releases) lease.run();
        releases.clear();
    }
    private void finish(BuildOperation.Outcome outcome, String detail, boolean reported) {
        if (completion.isDone()) return;
        release();
        update(detail, false);
        completion.complete(new Result(outcome, detail, reported));
    }
    private void update(String text, boolean active) {
        status = new Status(text, index, services.size(), active);
        progress.accept(status);
    }
}
