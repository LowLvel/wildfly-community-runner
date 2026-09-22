package io.github.wildflycommunityrunner.services;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.github.wildflycommunityrunner.model.ServerProfile;
import io.github.wildflycommunityrunner.model.ServiceProfile;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class BuildBatchTest extends BasePlatformTestCase {
    private static final class Harness implements BuildBatch.Backend {
        final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        final List<BuildOperation> builds = new ArrayList<>();
        final List<BuildOperationTest.Process> processes = new ArrayList<>();
        final List<CompletableFuture<Boolean>> deploys = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        int leases;
        int releases;
        @Override public BuildOperation build(ServiceProfile service) {
            names.add(service.name);
            var operation = new BuildOperation(Runnable::run);
            var process = new BuildOperationTest.Process();
            operation.beginLaunch();
            operation.bind(process);
            process.startNotify();
            builds.add(operation);
            processes.add(process);
            return operation;
        }
        @Override public CompletableFuture<Boolean> deploy(ServiceProfile service, ServerProfile server) {
            var future = new CompletableFuture<Boolean>();
            deploys.add(future);
            return future;
        }
        @Override public Runnable suppress(ServiceProfile service) {
            leases++;
            return () -> { leases--; releases++; };
        }
        void drain() { while (!queue.isEmpty()) queue.remove().run(); }
        void exit(int code) { processes.getLast().exit(code); drain(); }
        BuildBatch batch(BuildBatch.Mode mode, boolean external) {
            var a = new ServiceProfile(); a.name = "orders";
            var b = new ServiceProfile(); b.name = "billing";
            var batch = new BuildBatch(List.of(a, b), new ServerProfile(), mode, external, this, queue::add, ignored -> {});
            a.name = "edited after clicking Build";
            batch.start();
            return batch;
        }
    }

    public void testExplicitDeployCompletesBeforeNextBuildAndHoldsSuppression() {
        var h = new Harness();
        var batch = h.batch(BuildBatch.Mode.FORCE_DEPLOY, false);
        h.drain();
        assertEquals(List.of("orders"), h.names);
        h.exit(0);
        assertEquals(1, h.deploys.size());
        assertEquals(1, h.leases);
        assertEquals(1, h.builds.size());
        h.deploys.getFirst().complete(true); h.drain();
        assertEquals(List.of("orders", "billing"), h.names);
        h.exit(0);
        h.deploys.getLast().complete(true); h.drain();
        assertEquals(BuildOperation.Outcome.SUCCESS, batch.completion().join().outcome());
        assertEquals(0, h.leases);
        assertEquals(2, h.releases);
    }

    public void testCancelDuringBuildWaitsForProcessExitAndSkipsQueue() {
        var h = new Harness();
        var batch = h.batch(BuildBatch.Mode.BUILD_ONLY, false); h.drain();
        batch.cancel();
        assertEquals(1, h.processes.getFirst().stops);
        assertEquals(1, h.leases);
        assertFalse(batch.completion().isDone());
        h.exit(0);
        assertEquals(BuildOperation.Outcome.CANCELLED, batch.completion().join().outcome());
        assertEquals(1, h.builds.size());
        assertEquals(1, h.releases);
        assertTrue(h.deploys.isEmpty());
    }

    public void testCancelDuringDeploymentKeepsLeaseUntilScannerResult() {
        var h = new Harness();
        var batch = h.batch(BuildBatch.Mode.FORCE_DEPLOY, false); h.drain(); h.exit(0);
        batch.cancel();
        assertTrue(batch.status().text().contains("current deployment"));
        assertEquals(1, h.leases);
        assertFalse(batch.completion().isDone());
        h.deploys.getFirst().complete(true); h.drain();
        assertEquals(BuildOperation.Outcome.CANCELLED, batch.completion().join().outcome());
        assertEquals(1, h.builds.size());
        assertEquals(1, h.releases);
    }

    public void testFailedBuildStopsQueueAndReleasesOnce() {
        var h = new Harness();
        var batch = h.batch(BuildBatch.Mode.FORCE_DEPLOY, false); h.drain(); h.exit(2);
        batch.cancel(); batch.dispose(); h.drain();
        assertEquals(BuildOperation.Outcome.FAILED, batch.completion().join().outcome());
        assertEquals(1, h.builds.size());
        assertEquals(1, h.releases);
        assertTrue(h.deploys.isEmpty());
    }

    public void testAutoModeUsesWatcherForProjectSourcesAndDeploysExternalSources() {
        var project = new Harness();
        var first = project.batch(BuildBatch.Mode.AUTO, false); project.drain(); project.exit(0); project.exit(0);
        assertEquals(BuildOperation.Outcome.SUCCESS, first.completion().join().outcome());
        assertTrue(project.deploys.isEmpty());
        assertEquals(0, project.releases);
        var external = new Harness();
        var second = external.batch(BuildBatch.Mode.AUTO, true); external.drain(); external.exit(0);
        assertEquals(1, external.deploys.size());
        external.deploys.getFirst().complete(false); external.drain();
        assertTrue(second.completion().join().failureReported());
        assertEquals(1, external.builds.size());
    }

    public void testQueuedCancellationAndDisposalDoNotLaunchOrReleaseTwice() {
        var h = new Harness();
        var batch = h.batch(BuildBatch.Mode.FORCE_DEPLOY, false);
        batch.cancel(); h.drain();
        assertTrue(h.builds.isEmpty());
        assertEquals(0, h.releases);
        var active = h.batch(BuildBatch.Mode.FORCE_DEPLOY, false); h.drain(); h.exit(0);
        active.dispose();
        assertEquals(0, h.releases);
        h.deploys.getFirst().complete(true); h.drain();
        assertEquals(1, h.releases);
        assertEquals(BuildOperation.Outcome.CANCELLED, active.completion().join().outcome());
    }
}
