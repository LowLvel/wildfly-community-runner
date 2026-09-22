package io.github.wildflycommunityrunner.settings;

import io.github.wildflycommunityrunner.model.ServiceProfile;
import io.github.wildflycommunityrunner.model.ServerProfile;
import org.junit.Test;
import java.nio.file.Path;
import static org.junit.Assert.*;

public class SettingsRegressionTest {
    @Test public void migratesLegacyMavenConfigurationWithoutLosingValues() {
        var service = new ServiceProfile();
        service.pomPath = "apps/api/pom.xml";
        service.mavenGoals = "verify";
        service.mavenArguments = "-Pdevelopment";
        service.mavenJvmOptions = "-Xmx1g";
        service.migrateLegacyFields();
        service.migrateLegacyFields();
        assertEquals("apps/api/pom.xml", service.buildFilePath);
        assertEquals("verify", service.buildTasks);
        assertEquals("-Pdevelopment", service.buildArguments);
        assertEquals("-Xmx1g", service.buildJvmOptions);
    }

    @Test public void preservesExplicitGenericBuildConfigurationDuringMigration() {
        var service = new ServiceProfile();
        service.buildSystem = "GRADLE";
        service.buildFilePath = "build.gradle.kts";
        service.buildTasks = "assemble";
        service.buildArguments = "--offline";
        service.buildJvmOptions = "-Xmx2g";
        service.pomPath = "old/pom.xml";
        service.mavenGoals = "verify";
        service.mavenArguments = "-Pold";
        service.mavenJvmOptions = "-Xmx1g";
        service.migrateLegacyFields();
        assertEquals("GRADLE", service.buildSystem);
        assertEquals("build.gradle.kts", service.buildFilePath);
        assertEquals("assemble", service.buildTasks);
        assertEquals("--offline", service.buildArguments);
        assertEquals("-Xmx2g", service.buildJvmOptions);
    }

    @Test public void globalRegistryDeduplicatesNormalizedPathsAndDoesNotShareProfileEdits() {
        var settings = new WildFlyApplicationSettings();
        var service = new ServiceProfile();
        service.buildFilePath = Path.of("workspace", "api", "pom.xml").toAbsolutePath().toString();
        service.name = "Orders";
        settings.rememberService(service);
        service.name = "Edited outside registry";
        assertEquals("Orders", settings.findKnownServiceByBuildFile(service.buildFilePath).name);
        var replacement = new ServiceProfile(service);
        replacement.buildFilePath = Path.of("workspace", "api", "..", "api", "pom.xml").toAbsolutePath().toString();
        settings.rememberService(replacement);
        assertEquals(1, settings.knownServices().size());
        var retrieved = settings.findKnownServiceByBuildFile(service.buildFilePath);
        retrieved.name = "Detached edit";
        assertEquals("Edited outside registry", settings.findKnownServiceByBuildFile(service.buildFilePath).name);
    }

    @Test public void resolvesRememberedExternalSourcesByCustomAndDefaultDeploymentName() {
        var settings = new WildFlyApplicationSettings();
        var service = new ServiceProfile();
        service.buildFilePath = "services/api/pom.xml";
        service.name = "Orders API";
        settings.rememberService(service);
        assertEquals(service.id, settings.findKnownServiceByDeploymentName("Orders-API.war").id);
        service.deploymentName = " custom.ear ";
        settings.rememberService(service);
        assertEquals(service.id, settings.findKnownServiceByDeploymentName("CUSTOM.EAR").id);
        assertNull(settings.findKnownServiceByDeploymentName("unknown.jar"));
    }

    @Test public void loadsOlderStatesWithMissingListsAndDefaultServerPorts() {
        var settings = new WildFlyApplicationSettings();
        var state = new WildFlyApplicationSettings.StateData();
        state.knownServices = null;
        var server = new ServerProfile();
        server.host = "";
        server.httpPort = 0;
        server.debugPort = -1;
        state.servers.add(server);
        settings.loadState(state);
        assertTrue(settings.knownServices().isEmpty());
        assertEquals("localhost", settings.servers().getFirst().host);
        assertEquals(8080, settings.servers().getFirst().httpPort);
        assertEquals(8787, settings.servers().getFirst().debugPort);
        var projectSettings = new WildFlyProjectSettings();
        var projectState = new WildFlyProjectSettings.StateData();
        projectState.services = null;
        projectSettings.loadState(projectState);
        assertTrue(projectSettings.services().isEmpty());
    }
}
