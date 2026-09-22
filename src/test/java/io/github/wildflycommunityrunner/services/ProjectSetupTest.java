package io.github.wildflycommunityrunner.services;

import com.intellij.ide.trustedProjects.TrustedProjects;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.github.wildflycommunityrunner.model.BuildSystem;
import io.github.wildflycommunityrunner.model.ServerProfile;
import io.github.wildflycommunityrunner.model.ServiceProfile;
import io.github.wildflycommunityrunner.settings.WildFlyApplicationSettings;
import io.github.wildflycommunityrunner.settings.WildFlyProjectSettings;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.rules.TemporaryFolder;
import static io.github.wildflycommunityrunner.TestFiles.write;

public class ProjectSetupTest extends BasePlatformTestCase {
    private WildFlyApplicationSettings.StateData previousApp;
    private WildFlyProjectSettings.StateData previousProject;
    private ProjectSetupService setup;

    @Override protected void setUp() throws Exception {
        super.setUp();
        previousApp = app().getState();
        previousProject = settings().getState();
        app().loadState(new WildFlyApplicationSettings.StateData());
        settings().loadState(new WildFlyProjectSettings.StateData());
        setup = new ProjectSetupService(getProject());
    }

    @Override protected void tearDown() throws Exception {
        try {
            Disposer.dispose(setup);
            ArtifactAutoDeployService.getInstance(getProject()).configure(List.of(), null, ignored -> {});
            app().loadState(previousApp);
            settings().loadState(previousProject);
        } finally { super.tearDown(); }
    }

    private WildFlyApplicationSettings app() { return WildFlyApplicationSettings.getInstance(); }
    private WildFlyProjectSettings settings() { return WildFlyProjectSettings.getInstance(getProject()); }
    private BuildProjectDiscoveryService.BuildProjectChoice choice() {
        return new BuildProjectDiscoveryService.BuildProjectChoice("orders", "apps/orders", BuildSystem.GRADLE, "apps/orders/build.gradle.kts", "war");
    }

    public void testInitialSetupAddsDefaultsAndDoesNotRecreateServicesAfterUserRemoval() {
        var server = new ServerProfile();
        server.home = Path.of("wildfly").toAbsolutePath().toString();
        setup.applyInitialState(server, List.of(choice()));
        assertEquals(1, app().servers().size());
        assertEquals(server.id, settings().getState().selectedServerId);
        assertEquals(1, settings().services().size());
        var service = settings().services().getFirst();
        assertEquals("clean build", service.buildTasks);
        assertEquals("-x test", service.buildArguments);
        assertEquals("orders.war", service.deploymentName);
        assertEquals(service.id, app().findKnownServiceByBuildFile(service.buildFilePath).id);
        assertFalse(WildFlyProcessService.getInstance().isRunning(server));
        settings().services().clear();
        setup.applyInitialState(server, List.of(choice()));
        assertTrue(settings().services().isEmpty());
        assertEquals(1, app().servers().size());
    }

    public void testUserChangesDuringDiscoveryWinOverInitialDefaults() {
        var selected = new ServerProfile();
        var another = new ServerProfile();
        app().servers().add(selected);
        app().servers().add(another);
        settings().getState().selectedServerId = selected.id;
        var custom = new ServiceProfile();
        custom.buildTasks = "verify";
        settings().services().add(custom);
        setup.applyInitialState(new ServerProfile(), List.of(choice()));
        assertEquals(2, app().servers().size());
        assertEquals(selected.id, settings().getState().selectedServerId);
        assertSame(custom, settings().services().getFirst());
        assertEquals("verify", custom.buildTasks);
    }

    public void testMissingSelectionUsesLastGlobalServerAndLegacyServiceIsPreserved() {
        var first = new ServerProfile();
        var last = new ServerProfile();
        app().servers().add(first);
        app().servers().add(last);
        app().setLastServerId(last.id);
        settings().getState().selectedServerId = "removed-profile";
        settings().getState().mavenWorkingDirectory = "legacy";
        settings().getState().mavenGoals = "verify";
        settings().migrateLegacyService();
        setup.applyInitialState(null, List.of(choice()));
        assertEquals(last.id, settings().getState().selectedServerId);
        assertEquals(1, settings().services().size());
        assertEquals("verify", settings().services().getFirst().buildTasks);
        assertEquals(Path.of("legacy", "pom.xml").toString(), settings().services().getFirst().buildFilePath);
    }

    public void testWatchersInitializeWithoutToolWindowAndSafeModeDisablesThem() throws Exception {
        var temporary = new TemporaryFolder();
        temporary.create();
        boolean previousTrust = TrustedProjects.isProjectTrusted(getProject());
        try {
            TrustedProjects.setProjectTrusted(getProject(), true);
            Path buildFile = write(temporary.getRoot().toPath(), "api/pom.xml", "<project/>");
            var service = new ServiceProfile();
            service.buildFilePath = buildFile.toString();
            settings().services().add(service);
            var server = new ServerProfile();
            server.home = temporary.getRoot().toString();
            app().servers().add(server);
            settings().getState().selectedServerId = server.id;
            var watcher = ArtifactAutoDeployService.getInstance(getProject());
            setup.configureWatcher(null);
            awaitWatching(watcher, service.id, true);
            assertFalse(WildFlyProcessService.getInstance().isRunning(server));
            TrustedProjects.setProjectTrusted(getProject(), false);
            setup.configureWatcher(null);
            awaitWatching(watcher, service.id, false);
        } finally {
            TrustedProjects.setProjectTrusted(getProject(), previousTrust);
            ArtifactAutoDeployService.getInstance(getProject()).configure(List.of(), null, ignored -> {});
            temporary.delete();
        }
    }

    private static void awaitWatching(ArtifactAutoDeployService watcher, String id, boolean expected) throws Exception {
        boolean matched = ApplicationManager.getApplication().executeOnPooledThread(() -> {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (watcher.isWatching(id) != expected && System.nanoTime() < deadline) Thread.sleep(10);
            return watcher.isWatching(id) == expected;
        }).get(10, TimeUnit.SECONDS);
        assertTrue("Artifact watcher configuration did not match trust state", matched);
    }
}
