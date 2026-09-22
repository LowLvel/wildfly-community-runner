package io.github.wildflycommunityrunner.run;

import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.ConfigurationTypeUtil;
import com.intellij.execution.configurations.RemoteConnectionCreator;
import com.intellij.execution.configurations.RemoteState;
import com.intellij.execution.configurations.RuntimeConfigurationError;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.runners.ExecutionEnvironmentBuilder;
import com.intellij.execution.runners.ProgramRunner;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.github.wildflycommunityrunner.model.ServerProfile;
import io.github.wildflycommunityrunner.settings.WildFlyApplicationSettings;
import io.github.wildflycommunityrunner.settings.WildFlyProjectSettings;
import org.jdom.Element;
import org.jdom.output.XMLOutputter;
import static org.junit.Assert.assertThrows;

public class NativeRunConfigurationTest extends BasePlatformTestCase {
    public void testApplicationSelectionRoundTripResolvesPortablePathsAndRejectsMissingServices() throws Exception {
        var settings = WildFlyProjectSettings.getInstance(getProject());
        var before = settings.getState();
        try {
            var service = io.github.wildflycommunityrunner.model.ServiceProfile.create();
            service.name = "orders"; service.buildFilePath = "orders/pom.xml";
            settings.update(state -> state.services.add(service));
            var configuration = configuration(false);
            configuration.setServicePaths(java.util.List.of("orders/pom.xml"));
            var element = new Element("configuration"); configuration.writeExternal(element);
            var restored = configuration(false); restored.readExternal(element);
            assertEquals(java.util.List.of("orders/pom.xml"), restored.getServicePaths());
            assertEquals(service.id, restored.resolveServices().getFirst().id);
            settings.update(state -> state.services.clear());
            assertThrows(RuntimeConfigurationError.class, restored::checkConfiguration);
        } finally { settings.loadState(before); }
    }
    private WildFlyApplicationSettings.StateData previous;
    private String previousSelection;
    private ServerProfile server;

    @Override protected void setUp() throws Exception {
        super.setUp();
        var settings = WildFlyApplicationSettings.getInstance();
        previous = settings.getState();
        previousSelection = WildFlyProjectSettings.getInstance(getProject()).getState().selectedServerId;
        settings.loadState(new WildFlyApplicationSettings.StateData());
        server = new ServerProfile();
        server.home = "/not-opened-during-editing";
        server.debugPort = 9876;
        server.jvmOptions = "-Dpassword=SENSITIVE_TEST_VALUE";
        settings.update(state -> state.servers.add(server));
        settings.setLastServerId(server.id);
        WildFlyProjectSettings.getInstance(getProject()).update(state -> state.selectedServerId = "");
    }

    @Override protected void tearDown() throws Exception {
        try {
            WildFlyApplicationSettings.getInstance().loadState(previous);
            WildFlyProjectSettings.getInstance(getProject()).update(state -> state.selectedServerId = previousSelection);
        } finally { super.tearDown(); }
    }

    private WildFlyRunConfiguration configuration(boolean attach) {
        var type = ConfigurationTypeUtil.findConfigurationType(WildFlyConfigurationType.class);
        for (ConfigurationFactory factory : type.getConfigurationFactories()) {
            if (factory.getId().equals(attach ? "AttachDebugger" : "LocalServer")) {
                return (WildFlyRunConfiguration) factory.createTemplateConfiguration(getProject());
            }
        }
        throw new AssertionError("Missing configuration factory");
    }

    public void testStandardRunAndDebugRunnersRecognizeLocalConfiguration() throws Exception {
        var configuration = configuration(false);
        configuration.checkConfiguration();
        assertEquals(server.id, configuration.getServerId());
        assertNotNull(ProgramRunner.getRunner(DefaultRunExecutor.EXECUTOR_ID, configuration));
        assertNotNull(ProgramRunner.getRunner(DefaultDebugExecutor.EXECUTOR_ID, configuration));
        assertFalse(configuration.isBuildBeforeLaunchAddedByDefault());
        assertTrue(configuration.isExcludeCompileBeforeLaunchOption());
    }

    public void testAttachHasOnlyDebuggerAndUsesSelectedProfilePort() throws Exception {
        var configuration = configuration(true);
        assertNull(ProgramRunner.getRunner(DefaultRunExecutor.EXECUTOR_ID, configuration));
        assertNotNull(ProgramRunner.getRunner(DefaultDebugExecutor.EXECUTOR_ID, configuration));
        var executor = DefaultDebugExecutor.getDebugExecutorInstance();
        var environment = ExecutionEnvironmentBuilder.create(executor, configuration).build();
        var state = configuration.getState(executor, environment);
        assertInstanceOf(state, RemoteState.class);
        var connection = ((RemoteState) state).getRemoteConnection();
        assertEquals("9876", connection.getDebuggerAddress());
        assertEquals(server.host, connection.getDebuggerHostName());
        assertFalse(connection.isServerMode());
    }

    public void testLocalDebugConnectionSnapshotsProfileWithoutLaunchingServerDuringEditing() throws Exception {
        var configuration = configuration(false);
        var executor = DefaultDebugExecutor.getDebugExecutorInstance();
        var environment = ExecutionEnvironmentBuilder.create(executor, configuration).build();
        var state = configuration.getState(executor, environment);
        WildFlyApplicationSettings.getInstance().update(data -> data.servers.getFirst().debugPort = 1111);
        var creator = (RemoteConnectionCreator) state;
        assertEquals("9876", creator.createRemoteConnection(environment).getDebuggerAddress());
        assertTrue(creator.isPollConnection());
    }

    public void testXmlRoundTripAndCloneKeepOnlyProfileReference() {
        var configuration = configuration(false);
        Element element = new Element("configuration");
        configuration.writeExternal(element);
        String xml = new XMLOutputter().outputString(element);
        assertTrue(xml.contains(server.id));
        assertFalse(xml.contains("SENSITIVE_TEST_VALUE"));
        assertFalse(xml.contains(server.home));
        var restored = configuration(false);
        restored.setServerId("different");
        restored.readExternal(element);
        assertEquals(server.id, restored.getServerId());
        var clone = (WildFlyRunConfiguration) restored.clone();
        clone.setServerId("clone-selection");
        assertEquals(server.id, restored.getServerId());
    }

    public void testRemovedProfileAndInvalidPortAreActionableConfigurationErrors() {
        var configuration = configuration(false);
        WildFlyApplicationSettings.getInstance().update(state -> state.servers.getFirst().debugPort = 70000);
        assertThrows(RuntimeConfigurationError.class, configuration::checkConfiguration);
        WildFlyApplicationSettings.getInstance().update(state -> state.servers.clear());
        assertThrows(RuntimeConfigurationError.class, configuration::checkConfiguration);
    }
}
