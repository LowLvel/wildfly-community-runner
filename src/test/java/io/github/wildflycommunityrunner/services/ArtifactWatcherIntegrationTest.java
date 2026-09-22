package io.github.wildflycommunityrunner.services;

import com.intellij.ide.trustedProjects.TrustedProjects;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.github.wildflycommunityrunner.model.ServerProfile;
import io.github.wildflycommunityrunner.model.ServiceProfile;
import io.github.wildflycommunityrunner.util.WildFlyPaths;
import org.junit.rules.TemporaryFolder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import static io.github.wildflycommunityrunner.TestFiles.*;

/** Real filesystem WatchService and scanner markers, with a fake managed server (not a WildFly runtime). */
public class ArtifactWatcherIntegrationTest extends BasePlatformTestCase {
    private TemporaryFolder temp;
    private ArtifactAutoDeployService watcher;
    private BuildOperationTest.Process process;
    private ServiceProfile service;
    private ServerProfile server;
    private Path root;
    private Path target;
    private boolean previousTrust;
    private final List<String> output = new CopyOnWriteArrayList<>();

    @Override protected void setUp() throws Exception {
        super.setUp();
        temp = new TemporaryFolder(); temp.create(); root = temp.getRoot().toPath();
        previousTrust = TrustedProjects.isProjectTrusted(getProject());
        TrustedProjects.setProjectTrusted(getProject(), true);
        service = new ServiceProfile();
        service.buildFilePath = write(root, "project/pom.xml", "<project/>").toString();
        service.deploymentName = "api.war";
        server = new ServerProfile(); server.home = root.resolve("wildfly").toString();
        target = WildFlyPaths.deploymentsDir(server).resolve("api.war");
        process = new BuildOperationTest.Process(); process.startNotify();
        WildFlyProcessService.getInstance().registerManaged(server, process, false);
        watcher = ArtifactAutoDeployService.getInstance(getProject());
        configure(List.of(service));
    }
    @Override protected void tearDown() throws Exception {
        try {
            configure(List.of());
            process.exit(0);
            TrustedProjects.setProjectTrusted(getProject(), previousTrust);
            temp.delete();
        } finally { super.tearDown(); }
    }
    private void configure(List<ServiceProfile> services) throws Exception {
        ApplicationManager.getApplication().executeOnPooledThread(() -> watcher.configure(services, server, output::add)).get(10, TimeUnit.SECONDS);
    }
    private void await(BooleanSupplier condition) throws Exception {
        boolean success = ApplicationManager.getApplication().executeOnPooledThread(() -> {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(20);
            return condition.getAsBoolean();
        }).get(20, TimeUnit.SECONDS);
        assertTrue(String.join("\n", output) + "\n" + watcher.diagnosticState(), success);
    }
    private Path request() { return target.resolveSibling("api.war.dodeploy"); }
    private void confirm(int count) throws Exception {
        Files.delete(request());
        write(target.getParent(), "api.war.deployed", "confirmation " + count);
        await(() -> output.stream().filter(line -> line.startsWith("Deployment successful:")).count() >= count);
    }

    public void testSourceEditsAreIgnoredAndDeletedOutputDirectoryIsRecreated() throws Exception {
        write(root, "project/src/App.java", "source edit");
        Thread.sleep(1000);
        assertFalse(Files.exists(request()));
        var artifact = archive(root, "project/target/api.war", "first");
        await(() -> Files.exists(request()));
        assertEquals("first", archiveContent(target)); confirm(1);
        Files.delete(artifact); Files.delete(artifact.getParent());
        archive(root, "project/target/api.war", "next");
        await(() -> Files.exists(request()));
        assertEquals("next", archiveContent(target)); confirm(2);
    }

    public void testNewArtifactDuringInflightDeploymentIsNotDropped() throws Exception {
        archive(root, "project/target/api.war", "first");
        await(() -> Files.exists(request()));
        archive(root, "project/target/api.war", "second");
        Thread.sleep(1000); // The first scanner request deliberately remains unacknowledged.
        assertEquals("first", archiveContent(target)); confirm(1);
        await(() -> Files.exists(request()));
        assertEquals("second", archiveContent(target)); confirm(2);
    }

    public void testQueuedEventsExpireOnReconfigurationWithoutDeployingExistingArchives() throws Exception {
        configure(List.of());
        archive(root, "project/target/api.war", "existing");
        configure(List.of(service));
        Thread.sleep(1000);
        assertFalse("Opening a project must not deploy its existing archive", Files.exists(request()));
        archive(root, "project/target/api.war", "queued");
        configure(List.of());
        Thread.sleep(1500);
        assertFalse(Files.exists(request()));
        assertFalse(watcher.isWatching(service.id));
    }

    public void testArtifactFiltersAndAncestorRecoveryNeverMatchSourceChanges() {
        assertFalse(ArtifactAutoDeployService.relevant(service, null, Path.of("App.java")));
        assertFalse(ArtifactAutoDeployService.relevant(service, null, Path.of("api.war.uploading")));
        assertTrue(ArtifactAutoDeployService.relevant(service, null, Path.of("api.war")));
        assertFalse(ArtifactAutoDeployService.relevant(service, "exact.ear", Path.of("other.ear")));
        Path module = root.resolve("project");
        assertFalse(ArtifactAutoDeployService.towardOutput(module, module.resolve("build/libs"), Path.of("src")));
        assertTrue(ArtifactAutoDeployService.towardOutput(module, module.resolve("build/libs"), Path.of("build")));
    }

    public void testReconfigurationExpiresAutomaticRequestWaitingBehindManualDeployment() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var blocked = ApplicationManager.getApplication().executeOnPooledThread(() ->
                DeploymentCoordinator.getInstance().withTarget(getProject(), target, () -> {
                    entered.countDown(); return release.await(30, TimeUnit.SECONDS);
                }));
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            archive(root, "project/target/api.war", "queued");
            await(() -> output.stream().anyMatch(line -> line.startsWith("Artifact changed;")));
            configure(List.of());
            release.countDown(); blocked.get(5, TimeUnit.SECONDS);
            Thread.sleep(1000);
            assertFalse(Files.exists(request()));
            assertFalse(Files.exists(target));
        } finally { release.countDown(); blocked.get(5, TimeUnit.SECONDS); }
    }
}
