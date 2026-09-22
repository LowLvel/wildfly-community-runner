package io.github.wildflycommunityrunner.run;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.progress.ProcessCanceledException;
import io.github.wildflycommunityrunner.model.ServerProfile;
import io.github.wildflycommunityrunner.model.ServiceProfile;
import io.github.wildflycommunityrunner.services.*;
import io.github.wildflycommunityrunner.util.DeploymentNames;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Runs on the session worker. The session's stop/detach request owns cancellation of this batch only. */
final class ApplicationLaunch {
    static void deploy(Project project, ServerProfile profile, List<ServiceProfile> services,
                       BooleanSupplier cancelled, Consumer<String> output) throws Exception {
        if (services.isEmpty()) return;
        DeploymentNames.requireUnique(services);
        ScannerConfiguration.requireEnabled(profile);
        output.accept("Waiting for the selected WildFly HTTP endpoint…");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(profile.startupTimeoutSeconds);
        while (!WildFlyServerDetector.isPortOpen(profile)) {
            if (cancelled.getAsBoolean() || project.isDisposed()) throw new ProcessCanceledException();
            if (!WildFlyProcessService.getInstance().isDetectedRunning(profile)) throw new IllegalStateException("WildFly exited before its HTTP endpoint became available. Check server.log.");
            if (System.nanoTime() >= deadline) throw new IllegalStateException("WildFly HTTP endpoint did not become available. Check Expected HTTP host/port and the server's socket bindings.");
            Thread.sleep(100);
        }
        if (cancelled.getAsBoolean() || project.isDisposed()) throw new ProcessCanceledException();
        var batch = project.getService(BuildLifecycleService.class).startBatch(services, profile, BuildBatch.Mode.FORCE_DEPLOY, false, output);
        if (batch == null) throw new IllegalStateException("Cannot start application build. Finish or cancel the current project build first.");
        for (;;) {
            if (cancelled.getAsBoolean() || project.isDisposed()) batch.cancel();
            try {
                var result = batch.completion().get(100, TimeUnit.MILLISECONDS);
                if (result.outcome() == BuildOperation.Outcome.CANCELLED) throw new ProcessCanceledException();
                if (result.outcome() != BuildOperation.Outcome.SUCCESS) throw new IllegalStateException(result.detail());
                return;
            } catch (TimeoutException pending) { /* Keep stop/detach responsive while the batch owns its leases. */ }
            catch (InterruptedException interrupted) { batch.cancel(); Thread.currentThread().interrupt(); throw interrupted; }
        }
    }
}
