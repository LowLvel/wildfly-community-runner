package io.github.wildflycommunityrunner.settings;

import io.github.wildflycommunityrunner.model.ServiceProfile;
import io.github.wildflycommunityrunner.model.ServerProfile;
import org.junit.Test;
import java.nio.file.Path;
import static org.junit.Assert.*;

public class SettingsRegressionTest {
    @Test public void deploymentSourcesRequireExplicitServerScopedAssociationAndFollowRelink() {
        var settings = new WildFlyApplicationSettings();
        var serverA = new io.github.wildflycommunityrunner.model.ServerProfile(); serverA.home = java.nio.file.Path.of("server-a").toAbsolutePath().toString();
        var serverB = new io.github.wildflycommunityrunner.model.ServerProfile(); serverB.home = java.nio.file.Path.of("server-b").toAbsolutePath().toString();
        var a = ServiceProfile.create(); a.name = "orders"; a.buildFilePath = "orders/pom.xml"; a.deploymentName = "api.war";
        var b = ServiceProfile.create(); b.name = "billing"; b.buildFilePath = "billing/pom.xml"; b.deploymentName = "api.war";
        settings.rememberService(a); settings.rememberService(b);
        assertNull(settings.findDeploymentSource(serverA, "api.war"));
        settings.rememberDeployment(serverA, "api.war", a); settings.rememberDeployment(serverB, "api.war", b);
        assertEquals("orders", settings.findDeploymentSource(serverA, "api.war").name);
        assertEquals("billing", settings.findDeploymentSource(serverB, "api.war").name);
        a.buildFilePath = "moved/pom.xml"; settings.replaceKnownService(a.id, a);
        assertEquals("moved/pom.xml", settings.findDeploymentSource(serverA, "api.war").buildFilePath);
        settings.forgetServices(java.util.Set.of(a.id));
        assertNull(settings.findDeploymentSource(serverA, "api.war"));
        assertNotNull(settings.findDeploymentSource(serverB, "api.war"));
    }
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

    @Test public void migrationClearsObsoleteFieldsAndPreservesExplicitEmptyArguments() {
        var service = new ServiceProfile();
        service.buildFilePath = "pom.xml";
        service.buildTasks = "clean package";
        service.buildArguments = "";
        service.pomPath = "old/pom.xml";
        service.mavenGoals = "verify";
        service.mavenArguments = "-Pold";
        service.mavenJvmOptions = "-Xmx2g";
        service.migrateLegacyFields();
        assertEquals("clean package", service.buildTasks);
        assertEquals("", service.buildArguments);
        assertEquals("", service.buildJvmOptions);
        assertEquals("", service.pomPath);
        assertEquals("", service.mavenGoals);
        assertEquals("", service.mavenArguments);
        assertEquals("", service.mavenJvmOptions);
    }

    @Test public void legacyProjectServiceMigratesOnceAndCannotReappearAfterRemoval() {
        var settings = new WildFlyProjectSettings();
        var legacy = new WildFlyProjectSettings.StateData();
        legacy.mavenWorkingDirectory = "old-project";
        legacy.mavenGoals = "verify";
        legacy.artifactPath = "old-project/target/custom.ear";
        settings.loadState(legacy);
        assertEquals(1, settings.services().size());
        assertEquals("verify", settings.services().getFirst().buildTasks);
        assertEquals("old-project/target/custom.ear", settings.services().getFirst().artifactPath);
        assertEquals("", settings.getState().mavenWorkingDirectory);
        assertEquals("", settings.getState().artifactPath);
        assertEquals(SettingsMigration.VERSION, settings.getState().schemaVersion);
        settings.update(state -> state.services.clear());
        settings.migrateLegacyService();
        settings.loadState(settings.getState());
        assertTrue(settings.services().isEmpty());
        assertEquals("old-project", legacy.mavenWorkingDirectory); // caller's archive was not mutated
    }

    @Test public void loadingRepairsMissingAndDuplicateIdsWithoutSharingInputOrOutput() {
        var settings = new WildFlyApplicationSettings();
        var state = new WildFlyApplicationSettings.StateData();
        var server = new ServerProfile();
        server.id = "duplicate";
        server.debugPort = 70000;
        state.servers.add(server);
        state.servers.add(new ServerProfile(server));
        state.servers.add(null);
        var service = new ServiceProfile();
        service.id = null;
        service.buildFilePath = "one/pom.xml";
        state.knownServices.add(service);
        state.knownServices.add(null);
        settings.loadState(state);
        assertEquals(2, settings.servers().size());
        assertNotEquals(settings.servers().get(0).id, settings.servers().get(1).id);
        assertEquals(8787, settings.servers().getFirst().debugPort);
        assertFalse(settings.knownServices().getFirst().id.isBlank());
        server.name = "outside change";
        settings.getState().servers.clear();
        settings.servers().getFirst().name = "snapshot change";
        assertEquals("WildFly", settings.servers().getFirst().name);
        var retained = new java.util.concurrent.atomic.AtomicReference<WildFlyApplicationSettings.StateData>();
        settings.update(draft -> { draft.servers.getFirst().name = "saved"; retained.set(draft); });
        retained.get().servers.clear();
        assertEquals("saved", settings.servers().getFirst().name);
    }

    @Test public void relinkingReplacesOldPathAndForgettingLeavesSourceFilesUntouched() throws Exception {
        var settings = new WildFlyApplicationSettings();
        Path folder = java.nio.file.Files.createTempDirectory("remembered-source-");
        Path old = java.nio.file.Files.writeString(folder.resolve("pom.xml"), "old");
        Path target = java.nio.file.Files.writeString(folder.resolve("build.gradle"), "new");
        try {
            var first = new ServiceProfile(); first.buildFilePath = old.toString();
            var second = new ServiceProfile(); second.buildFilePath = target.toString();
            settings.rememberService(first); settings.rememberService(second);
            var edited = new ServiceProfile(first); edited.buildFilePath = target.toString();
            assertTrue(settings.replaceKnownService(first.id, edited));
            assertNull(settings.findKnownServiceByBuildFile(old.toString()));
            assertEquals(1, settings.knownServices().size());
            assertEquals(first.id, settings.findKnownServiceByBuildFile(target.toString()).id);
            settings.forgetServices(java.util.Set.of(first.id));
            assertFalse(settings.replaceKnownService(first.id, edited));
            assertTrue(settings.knownServices().isEmpty());
            assertEquals("old", java.nio.file.Files.readString(old));
            assertEquals("new", java.nio.file.Files.readString(target));
        } finally {
            java.nio.file.Files.deleteIfExists(old); java.nio.file.Files.deleteIfExists(target);
            java.nio.file.Files.deleteIfExists(folder);
        }
    }

    @Test public void concurrentUpdatesAndSerializationSnapshotsDoNotLoseServices() throws Exception {
        var settings = new WildFlyProjectSettings();
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var tasks = new java.util.ArrayList<java.util.concurrent.Callable<Void>>();
            for (int worker = 0; worker < 4; worker++) tasks.add(() -> {
                for (int i = 0; i < 50; i++) {
                    settings.update(state -> state.services.add(new ServiceProfile()));
                    settings.getState().services.clear();
                }
                return null;
            });
            for (var task : workers.invokeAll(tasks)) task.get();
        }
        assertEquals(200, settings.services().size());
    }
}
