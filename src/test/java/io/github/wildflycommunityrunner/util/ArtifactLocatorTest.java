package io.github.wildflycommunityrunner.util;

import io.github.wildflycommunityrunner.model.ServiceProfile;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import static io.github.wildflycommunityrunner.TestFiles.*;
import static org.junit.Assert.*;

public class ArtifactLocatorTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private ServiceProfile service(String system, String buildFile, String packaging) throws Exception {
        var service = new ServiceProfile();
        service.buildSystem = system;
        service.buildFilePath = write(temp.getRoot().toPath(), buildFile, "").toString();
        service.packaging = packaging;
        return service;
    }

    @Test public void refusesAmbiguousMavenOutputEvenWhenOneArchiveIsNewer() throws Exception {
        Path root = temp.getRoot().toPath();
        var service = service("MAVEN", "services/api/pom.xml", "war");
        Path old = write(root, "services/api/target/old.war", "old");
        Path current = write(root, "services/api/target/api.war", "current");
        Files.setLastModifiedTime(old, FileTime.fromMillis(1000));
        Files.setLastModifiedTime(current, FileTime.fromMillis(2000));
        write(root, "services/api/target/wrong.jar", "newer");
        write(root, "target/unrelated.war", "root output");
        assertTrue(assertThrows(IOException.class, () -> ArtifactLocator.resolve(projectAt(root), service)).getMessage().contains("Multiple deployment artifacts"));
        service.artifactPath = "target/api.war";
        assertEquals(current, ArtifactLocator.resolve(projectAt(root), service));
    }

    @Test public void gradlePrefersLibrariesAndIgnoresOriginalAndPlainJars() throws Exception {
        Path root = temp.getRoot().toPath();
        var service = service("GRADLE", "nested/build.gradle.kts", "jar");
        Path artifact = write(root, "nested/build/libs/app.jar", "final");
        Files.setLastModifiedTime(artifact, FileTime.fromMillis(1000));
        write(root, "nested/build/libs/original-app.jar", "original");
        write(root, "nested/build/libs/app-plain.jar", "plain");
        write(root, "nested/build/libs/app-sources.jar", "sources");
        write(root, "nested/build/libs/app-javadoc.jar", "javadoc");
        write(root, "nested/build/libs/app-tests.jar", "tests");
        write(root, "nested/build/libs/app-test-fixtures.jar", "fixtures");
        write(root, "nested/build/other.jar", "fallback");
        assertEquals(artifact, ArtifactLocator.resolve(projectAt(root), service));
    }

    @Test public void supportsGradleEarFallbackAndModuleRelativeOverride() throws Exception {
        Path root = temp.getRoot().toPath();
        var service = service("GRADLE", "nested/build.gradle", "ear");
        Path fallback = write(root, "nested/build/app.ear", "ear");
        assertEquals(fallback, ArtifactLocator.resolve(projectAt(root), service));
        Path custom = write(root, "nested/distribution/custom.WAR", "custom");
        service.artifactPath = "distribution/custom.WAR";
        assertEquals(custom, ArtifactLocator.resolve(projectAt(root), service));
        service.artifactPath = custom.toString();
        assertEquals(custom, ArtifactLocator.resolve(projectAt(root), service));
    }

    @Test public void reportsMissingOrUnsupportedArtifactWithoutSelectingSourceFiles() throws Exception {
        Path root = temp.getRoot().toPath();
        var service = service("MAVEN", "pom.xml", "war");
        write(root, "src/main/java/Example.java", "class Example {}");
        assertThrows(IOException.class, () -> ArtifactLocator.resolve(projectAt(root), service));
        service.artifactPath = "src/main/java/Example.java";
        assertThrows(IOException.class, () -> ArtifactLocator.resolve(projectAt(root), service));
        service.artifactPath = "missing.war";
        assertThrows(IOException.class, () -> ArtifactLocator.resolve(projectAt(root), service));
    }

    @Test public void deploymentNameKeepsExplicitOverrideAndUsesStableServiceNameByDefault() {
        var service = new ServiceProfile();
        service.name = "Orders API";
        assertEquals("Orders-API.war", ArtifactLocator.effectiveDeploymentName(service, Path.of("orders-1.2.war")));
        service.deploymentName = " custom.ear ";
        assertEquals("custom.ear", ArtifactLocator.effectiveDeploymentName(service, Path.of("orders-1.2.war")));
    }
}
