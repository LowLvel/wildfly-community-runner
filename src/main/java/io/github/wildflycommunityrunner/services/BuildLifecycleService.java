package io.github.wildflycommunityrunner.services;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.util.messages.Topic;
import io.github.wildflycommunityrunner.model.ServerProfile;
import io.github.wildflycommunityrunner.model.ServiceProfile;
import io.github.wildflycommunityrunner.settings.WildFlyApplicationSettings;
import io.github.wildflycommunityrunner.util.ArtifactLocator;
import io.github.wildflycommunityrunner.util.IdeUi;
import io.github.wildflycommunityrunner.util.ProjectTrust;
import org.jetbrains.annotations.NotNull;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/** One user-visible build batch per project, surviving tool-window closure. */
@Service(Service.Level.PROJECT)
public final class BuildLifecycleService implements Disposable {
    public interface Listener { void changed(); }
    public static final Topic<Listener> CHANGED = Topic.create("WildFly build progress", Listener.class);
    private final Project project;
    private volatile BuildBatch active;
    private volatile BuildBatch.Status status = new BuildBatch.Status("No build running", 0, 0, false);
    private volatile boolean disposed;

    public BuildLifecycleService(Project project) { this.project = project; }
    public BuildBatch.Status status() { return status; }

    public synchronized boolean start(List<ServiceProfile> services, ServerProfile server, BuildBatch.Mode mode,
                                      boolean external, Consumer<String> output) {
        return startBatch(services, server, mode, external, output) != null;
    }

    public synchronized BuildBatch startBatch(List<ServiceProfile> services, ServerProfile server, BuildBatch.Mode mode,
                                              boolean external, Consumer<String> output) {
        if (disposed || project.isDisposed() || services.isEmpty()) return null;
        try {
            if (mode == BuildBatch.Mode.FORCE_DEPLOY) io.github.wildflycommunityrunner.util.DeploymentNames.requireUnique(services);
        } catch (IllegalArgumentException invalid) {
            PluginNotifications.failure(project, "Build not started", invalid, output);
            return null;
        }
        if (active != null && !active.completion().isDone()) {
            output.accept("A build is already running. Cancel it or wait for completion before starting another.");
            return null;
        }
        if (!ProjectTrust.isTrusted(project)) {
            PluginNotifications.failure(project, "Build not started", "Trust this project before running a build.", output);
            return null;
        }
        if (mode == BuildBatch.Mode.FORCE_DEPLOY && server == null) {
            PluginNotifications.failure(project, "Build not started", "Select a WildFly server for Build and Deploy.", output);
            return null;
        }
        var watcher = ArtifactAutoDeployService.getInstance(project);
        var backend = new BuildBatch.Backend() {
            @Override public BuildOperation build(ServiceProfile service) {
                WildFlyApplicationSettings.getInstance().rememberService(BuildService.sourceSnapshot(project, service));
                return BuildService.build(project, service, output);
            }
            @Override public CompletableFuture<Boolean> deploy(ServiceProfile service, ServerProfile target) {
                var result = new CompletableFuture<Boolean>();
                ApplicationManager.getApplication().executeOnPooledThread(() -> {
                    try {
                        if (disposed || project.isDisposed()) { result.complete(false); return; }
                        var artifact = ArtifactLocator.resolve(project, service);
                        var source = BuildService.sourceSnapshot(project, service);
                        source.deploymentName = ArtifactLocator.effectiveDeploymentName(source, artifact);
                        WildFlyApplicationSettings.getInstance().rememberService(source);
                        DeploymentScannerService.deploy(project, target, artifact, source.deploymentName, output, ok -> {
                            if (ok) WildFlyApplicationSettings.getInstance().rememberDeployment(target, source.deploymentName, source);
                            result.complete(ok);
                        });
                    } catch (Exception error) { result.completeExceptionally(error); }
                });
                return result;
            }
            @Override public Runnable suppress(ServiceProfile service) {
                return watcher.suppress(service);
            }
        };
        var batch = new BuildBatch(services, server, mode, external, backend,
                command -> ApplicationManager.getApplication().executeOnPooledThread(command), this::changed);
        active = batch;
        changed(batch.status());
        batch.completion().thenAccept(result -> {
            synchronized (BuildLifecycleService.this) { if (active == batch) active = null; }
            if (result.outcome() == BuildOperation.Outcome.FAILED && !result.failureReported()) {
                PluginNotifications.failure(project, "Build operation failed", result.detail(), output);
            } else output.accept(result.detail());
        });
        IdeUi.later(project, () -> disposed || batch.completion().isDone(), () -> new Task.Backgroundable(project, "WildFly build", true) {
            @Override public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(false);
                while (!batch.completion().isDone()) {
                    if (indicator.isCanceled()) batch.cancel();
                    var current = batch.status();
                    indicator.setText(current.text());
                    indicator.setFraction(current.total() == 0 ? 0 : (double) current.completed() / current.total());
                    try { batch.completion().get(200, TimeUnit.MILLISECONDS); }
                    catch (TimeoutException ignored) { /* Keep cancellation and native progress responsive. */ }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); batch.cancel(); return; }
                    catch (ExecutionException error) { return; }
                }
            }
            @Override public void onCancel() { batch.cancel(); }
        }.queue());
        batch.start();
        return batch;
    }

    public void cancel() { BuildBatch batch = active; if (batch != null) batch.cancel(); }
    private void changed(BuildBatch.Status value) {
        status = value;
        IdeUi.later(project, () -> disposed, () -> project.getMessageBus().syncPublisher(CHANGED).changed());
    }
    @Override public void dispose() {
        disposed = true;
        BuildBatch batch = active;
        if (batch != null) batch.dispose();
        active = null;
    }
}
