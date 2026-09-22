package io.github.wildflycommunityrunner.services;

import com.intellij.execution.process.ProcessHandler;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.github.wildflycommunityrunner.model.ServerProfile;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.UUID;

public class ManagedServerRegistryTest extends BasePlatformTestCase {
    private static final class FakeProcess extends ProcessHandler {
        int stops;
        int detaches;
        @Override protected void destroyProcessImpl() { stops++; notifyProcessTerminated(0); }
        @Override protected void detachProcessImpl() { detaches++; notifyProcessDetached(); }
        @Override public boolean detachIsDefault() { return false; }
        @Override public OutputStream getProcessInput() { return null; }
        void exit() { notifyProcessTerminated(0); }
    }

    private ServerProfile profile() {
        var profile = new ServerProfile();
        profile.home = Path.of(System.getProperty("java.io.tmpdir"), "wildfly-registry-" + UUID.randomUUID()).toAbsolutePath().toString();
        return profile;
    }

    public void testEquivalentProfilesReuseOneProcessWithoutTakingStopOwnership() throws Exception {
        var registry = new WildFlyProcessService();
        var profile = profile();
        var process = new FakeProcess();
        registry.registerManaged(profile, process, true);
        process.startNotify();
        var alias = new ServerProfile(profile);
        alias.id = UUID.randomUUID().toString();
        alias.host = "127.0.0.1";
        assertTrue(registry.isRunning(alias));
        assertTrue(registry.isDebugRunning(alias));
        var session = registry.startForExecution(alias, true);
        assertSame(process, session.handler());
        assertFalse(session.ownsProcess());
        process.exit();
        assertFalse(registry.isRunning(profile));
        assertFalse(registry.isRunning(alias));
    }

    public void testEditedProfileCannotAccidentallyStopItsPreviousInstance() {
        var registry = new WildFlyProcessService();
        var profile = profile();
        var process = new FakeProcess();
        registry.registerManaged(profile, process, false);
        process.startNotify();
        var edited = new ServerProfile(profile);
        edited.configuration = "standalone-full.xml";
        assertFalse(registry.isRunning(edited));
        edited.configuration = profile.configuration;
        edited.host = "203.0.113.1";
        assertFalse(registry.isRunning(edited));
        assertTrue(registry.isRunning(profile));
        process.exit();
    }

    public void testOldExitCannotRemoveReplacementAndDebugPortMismatchIsExplicit() throws Exception {
        var registry = new WildFlyProcessService();
        var profile = profile();
        var old = new FakeProcess();
        registry.registerManaged(profile, old, false);
        old.startNotify();
        var replacement = new FakeProcess();
        registry.registerManaged(profile, replacement, true);
        replacement.startNotify();
        old.exit();
        assertTrue(registry.isDebugRunning(profile));
        var edited = new ServerProfile(profile);
        edited.debugPort++;
        assertEquals(profile.debugPort, registry.managedDebugPort(edited));
        var error = org.junit.Assert.assertThrows(IllegalStateException.class, () -> registry.startForExecution(edited, true));
        assertTrue(error.getMessage().contains(Integer.toString(profile.debugPort)));
        replacement.exit();
    }

    public void testArbitraryTcpListenerIsUnverifiedAndCannotBeForceStopped() throws Exception {
        var registry = new WildFlyProcessService();
        var profile = profile();
        profile.host = "127.0.0.1";
        try (var listener = new ServerSocket(0, 10, InetAddress.getByName(profile.host))) {
            profile.httpPort = listener.getLocalPort();
            assertEquals(WildFlyProcessService.ServerState.PORT_BUSY, registry.state(profile));
            assertFalse(registry.isDetectedRunning(profile));
            assertFalse(registry.canForceStopDetected(profile));
        }
    }

    public void testDisposeDetachesSharedServersAndReleasesListenersWithoutStoppingThem() {
        var registry = new WildFlyProcessService();
        var profile = profile();
        var process = new FakeProcess();
        registry.registerManaged(profile, process, false);
        process.startNotify();
        assertEquals(1, registry.listenerCount());
        registry.dispose();
        assertEquals(0, registry.listenerCount());
        assertEquals(0, process.stops);
        assertEquals(1, process.detaches);
        assertFalse(registry.isRunning(profile));
        registry.dispose();
        assertEquals(1, process.detaches);
    }

    public void testLaunchCompletingAfterDisposalDetachesAndDoesNotRecreateRegistry() {
        var registry = new WildFlyProcessService();
        registry.dispose();
        var profile = profile();
        var process = new FakeProcess();
        registry.registerManaged(profile, process, false);
        process.startNotify();
        assertEquals(0, process.stops);
        assertEquals(1, process.detaches);
        assertEquals(0, registry.listenerCount());
        assertFalse(registry.isRunning(profile));
    }
}
