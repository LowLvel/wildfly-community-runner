package io.github.wildflycommunityrunner.services;

import io.github.wildflycommunityrunner.model.BuildSystem;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import static io.github.wildflycommunityrunner.TestFiles.write;
import static org.junit.Assert.*;

public class BuildProjectDiscoveryTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private List<BuildProjectDiscoveryService.BuildProjectChoice> discover(Path root) {
        var choices = new LinkedHashMap<String, BuildProjectDiscoveryService.BuildProjectChoice>();
        BuildProjectDiscoveryService.scanRecursively(root, root, choices);
        // Overlapping imported roots must not create duplicate services.
        BuildProjectDiscoveryService.scanRecursively(root, root, choices);
        return List.copyOf(choices.values());
    }

    @Test public void discoversNestedMavenAndBothGradleDialectsWithoutAggregatorOrGeneratedProjects() throws Exception {
        Path root = temp.getRoot().toPath();
        write(root, "pom.xml", "<project><artifactId>parent</artifactId><packaging>pom</packaging></project>");
        write(root, "apps/api/pom.xml", "<project><parent><artifactId>parent</artifactId></parent><artifactId>api</artifactId><packaging>war</packaging></project>");
        write(root, "apps/batch/build.gradle.kts", "plugins { id(\"ear\") }");
        write(root, "apps/library/build.gradle", "plugins { id 'java' }");
        for (String ignored : List.of("target", "build", "node_modules", ".git", ".gradle")) {
            write(root, ignored + "/hidden/pom.xml", "<project><artifactId>hidden</artifactId></project>");
        }
        var choices = discover(root);
        assertEquals(3, choices.size());
        var api = choices.stream().filter(c -> c.name().equals("api")).findFirst().orElseThrow();
        assertEquals(BuildSystem.MAVEN, api.system());
        assertEquals("war", api.packaging());
        assertEquals("apps › api", api.displayPath());
        assertTrue(choices.stream().anyMatch(c -> c.name().equals("batch") && c.packaging().equals("ear") && c.system() == BuildSystem.GRADLE));
        assertTrue(choices.stream().anyMatch(c -> c.name().equals("library") && c.packaging().equals("jar")));
    }

    @Test public void ignoresParentCoordinatesAndDefaultsMavenPackagingToJar() throws Exception {
        Path root = temp.getRoot().toPath();
        write(root, "child/pom.xml", "<project><parent><artifactId>wrong-name</artifactId></parent><artifactId>actual-name</artifactId></project>");
        var choice = discover(root).getFirst();
        assertEquals("actual-name", choice.name());
        assertEquals("jar", choice.packaging());
    }

    @Test public void doesNotExpandExternalEntitiesAndContinuesPastMalformedPom() throws Exception {
        Path root = temp.getRoot().toPath();
        Path secret = write(root, "secret.txt", "MUST-NOT-BE-READ");
        write(root, "unsafe/pom.xml", "<!DOCTYPE project [<!ENTITY xxe SYSTEM '" + secret.toUri() + "'>]><project><artifactId>&xxe;</artifactId></project>");
        write(root, "broken/pom.xml", "not xml");
        write(root, "valid/build.gradle", "plugins { id 'war' }");
        var choices = discover(root);
        assertEquals(3, choices.size());
        assertFalse(choices.stream().anyMatch(c -> c.name().contains("MUST-NOT-BE-READ")));
        assertTrue(choices.stream().anyMatch(c -> c.name().equals("valid") && c.packaging().equals("war")));
    }

    @Test public void boundsTraversalDepthAndHandlesMissingRoot() throws Exception {
        Path root = temp.getRoot().toPath();
        write(root, "a/b/c/d/e/f/g/h/i/j/k/pom.xml", "<project/>");
        assertTrue(discover(root).isEmpty());
        assertTrue(discover(root.resolve("missing")).isEmpty());
    }
}
