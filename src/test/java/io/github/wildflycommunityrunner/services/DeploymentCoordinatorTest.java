package io.github.wildflycommunityrunner.services;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.junit.rules.TemporaryFolder;
import java.nio.file.Files;
import java.util.concurrent.*;
import static io.github.wildflycommunityrunner.TestFiles.*;

public class DeploymentCoordinatorTest extends BasePlatformTestCase {
    private TemporaryFolder temp;
    private DeploymentCoordinator coordinator;
    @Override protected void setUp() throws Exception { super.setUp(); temp = new TemporaryFolder(); temp.create(); coordinator = new DeploymentCoordinator(); }
    @Override protected void tearDown() throws Exception {
        try { coordinator.dispose(); temp.delete(); } finally { super.tearDown(); }
    }
    public void testConcurrentWatchersShareFlightAndIgnoreAlreadyDeployedFingerprint() throws Exception {
        var source = archive(temp.getRoot().toPath(), "source/api.war", "one");
        var target = archive(temp.getRoot().toPath(), "deployments/api.war", "one");
        var fingerprint = ArtifactFingerprint.read(source);
        var first = coordinator.claim(target, fingerprint);
        var second = coordinator.claim(target, fingerprint);
        assertNotNull(first.lease()); assertNotNull(second.busy());
        assertFalse(second.busy().isDone());
        write(target.getParent(), "api.war.deployed", "");
        first.lease().complete(true);
        assertTrue(second.busy().isDone());
        assertTrue(coordinator.claim(target, fingerprint).unchanged());
        Files.delete(target.resolveSibling("api.war.deployed"));
        var missingMarker = coordinator.claim(target, fingerprint);
        assertNotNull(missingMarker.lease()); missingMarker.lease().complete(false);
    }
    public void testSuppressionIsReferenceCountedAndSettlesBuiltArtifactAcrossProjects() throws Exception {
        var file = archive(temp.getRoot().toPath(), "api.war", "one");
        var source = file.resolveSibling("pom.xml");
        Runnable first = coordinator.suppress(source, () -> ArtifactFingerprint.read(file));
        Runnable second = coordinator.suppress(source, () -> ArtifactFingerprint.read(file));
        first.run(); first.run();
        Thread.sleep(2300);
        assertTrue(coordinator.suppressed(source, null));
        second.run();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (coordinator.suppressed(source, null) && System.nanoTime() < deadline) Thread.sleep(20);
        assertFalse(coordinator.suppressed(source, null));
        assertTrue(coordinator.suppressed(source, ArtifactFingerprint.read(file)));
        archive(temp.getRoot().toPath(), "api.war", "two");
        assertFalse(coordinator.suppressed(source, ArtifactFingerprint.read(file)));
    }
    public void testScannerRequestsForSameTargetSerializeWithoutLockingDifferentTargets() throws Exception {
        var pool = Executors.newFixedThreadPool(3);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var target = temp.getRoot().toPath().resolve("deployments/api.war");
        try {
            var first = pool.submit(() -> coordinator.withTarget(getProject(), target, () -> { entered.countDown(); return release.await(5, TimeUnit.SECONDS); }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var second = pool.submit(() -> coordinator.withTarget(getProject(), target, () -> "second"));
            var other = pool.submit(() -> coordinator.withTarget(getProject(), target.resolveSibling("other.war"), () -> "other"));
            assertEquals("other", other.get(5, TimeUnit.SECONDS));
            assertFalse(second.isDone());
            release.countDown();
            assertTrue(first.get(5, TimeUnit.SECONDS));
            assertEquals("second", second.get(5, TimeUnit.SECONDS));
        } finally { release.countDown(); pool.shutdownNow(); pool.awaitTermination(5, TimeUnit.SECONDS); }
    }
    public void testDisposalReleasesWaitingWatchersAndLateSuppressionCloseIsSafe() throws Exception {
        var file = archive(temp.getRoot().toPath(), "api.war", "one");
        var fingerprint = ArtifactFingerprint.read(file);
        var active = coordinator.claim(file, fingerprint);
        var waiting = coordinator.claim(file, fingerprint);
        var release = coordinator.suppress(file, () -> fingerprint);
        coordinator.dispose();
        assertTrue(waiting.busy().isDone());
        release.run(); active.lease().complete(true);
        assertFalse(coordinator.suppressed(file, null));
    }
}
