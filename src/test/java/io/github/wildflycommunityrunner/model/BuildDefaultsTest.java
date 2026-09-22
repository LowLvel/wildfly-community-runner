package io.github.wildflycommunityrunner.model;

import org.junit.Test;
import static org.junit.Assert.*;

public class BuildDefaultsTest {
    @Test public void newServicesUseIncrementalBuildsAndRequireAutoRedeployOptIn() {
        var service = ServiceProfile.create();
        assertFalse(service.deployAfterBuild);
        assertEquals("package", service.buildTasks);
        assertEquals("", service.buildArguments);
        BuildDefaults.changeSystem(service, BuildSystem.GRADLE);
        assertEquals("build", service.buildTasks);
        assertEquals("", service.buildArguments);
        // Serializer defaults remain intact for old XML that omitted them.
        assertTrue(new ServiceProfile().deployAfterBuild);
        assertEquals("clean package", new ServiceProfile().buildTasks);
    }
    @Test public void switchingBuildSystemUpdatesOnlyDefaults() {
        var service = new ServiceProfile();
        BuildDefaults.changeSystem(service, BuildSystem.GRADLE);
        assertEquals("clean build", service.buildTasks);
        assertEquals("-x test", service.buildArguments);
        BuildDefaults.changeSystem(service, BuildSystem.MAVEN);
        assertEquals("clean package", service.buildTasks);
        assertEquals("-DskipTests", service.buildArguments);
    }

    @Test public void switchingPreservesCustomTasksArgumentsJvmOptionsAndEnabledTests() {
        var service = new ServiceProfile();
        service.buildTasks = "assemble";
        service.buildArguments = "--offline";
        service.buildJvmOptions = "-Xmx2g";
        BuildDefaults.changeSystem(service, BuildSystem.GRADLE);
        assertEquals("assemble", service.buildTasks);
        assertEquals("--offline", service.buildArguments);
        assertEquals("-Xmx2g", service.buildJvmOptions);
        service.buildArguments = "";
        BuildDefaults.changeSystem(service, BuildSystem.MAVEN);
        assertEquals("", service.buildArguments);
    }
}
