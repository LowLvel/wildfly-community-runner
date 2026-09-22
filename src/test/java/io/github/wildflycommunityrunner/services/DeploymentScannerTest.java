package io.github.wildflycommunityrunner.services;

import io.github.wildflycommunityrunner.model.ServerProfile;
import io.github.wildflycommunityrunner.util.WildFlyPaths;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.io.IOException;
import org.junit.Assume;
import static io.github.wildflycommunityrunner.TestFiles.write;
import static org.junit.Assert.*;

public class DeploymentScannerTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private ServerProfile server() {
        var server = new ServerProfile();
        server.home = temp.getRoot().toString();
        return server;
    }

    @Test public void followsScannerStatesAndRetainsSuccessfulTimestamp() throws Exception {
        var server = server();
        Path dir = WildFlyPaths.deploymentsDir(server);
        assertEquals("NOT DEPLOYED", DeploymentScannerService.status(server, "api.war"));
        assertNull(DeploymentScannerService.lastDeployedAt(server, "api.war"));
        Path requested = write(dir, "api.war.dodeploy", "");
        assertEquals("DEPLOYING", DeploymentScannerService.status(server, "api.war"));
        Files.delete(requested);
        Path deploying = write(dir, "api.war.isdeploying", "");
        assertEquals("DEPLOYING", DeploymentScannerService.status(server, "api.war"));
        Files.delete(deploying);
        Path deployed = write(dir, "api.war.deployed", "");
        Instant timestamp = Instant.parse("2025-01-02T03:04:05Z");
        Files.setLastModifiedTime(deployed, FileTime.from(timestamp));
        assertEquals("DEPLOYED", DeploymentScannerService.status(server, "api.war"));
        assertEquals(timestamp, DeploymentScannerService.lastDeployedAt(server, "api.war"));
        write(dir, "api.war.failed", "failure details");
        assertEquals("FAILED", DeploymentScannerService.status(server, "api.war"));
    }

    @Test public void listsExternalArtifactsAndMarkerOnlyDeploymentsOnce() throws Exception {
        var server = server();
        Path dir = WildFlyPaths.deploymentsDir(server);
        write(dir, "external.ear", "ear");
        write(dir, "external.ear.deployed", "");
        write(dir, "api.war.failed", "failure");
        write(dir, "notes.txt", "ignore");
        write(dir, "upload.jar.uploading", "ignore");
        assertEquals(List.of("api.war", "external.ear"), DeploymentScannerService.listDeployments(server));
    }

    @Test public void cleanupRemovesScannerCopyAndMarkersButPreservesSourceAndOtherDeployments() throws Exception {
        var server = server();
        Path dir = WildFlyPaths.deploymentsDir(server);
        Path source = write(temp.getRoot().toPath(), "project/target/api.war", "source artifact");
        write(dir, "api.war", "copy");
        for (String suffix : List.of(".deployed", ".failed", ".dodeploy", ".pending", ".undeployed")) {
            write(dir, "api.war" + suffix, "");
        }
        Path other = write(dir, "other.jar", "other");
        DeploymentScannerService.cleanupDeployment(server, "api.war");
        assertEquals("source artifact", Files.readString(source));
        assertTrue(Files.exists(other));
        assertEquals(List.of("other.jar"), DeploymentScannerService.listDeployments(server));
        assertEquals("NOT DEPLOYED", DeploymentScannerService.status(server, "api.war"));
    }

    @Test public void pendingRedeployTakesPrecedenceOverLastSuccessfulMarker() throws Exception {
        var server = server();
        Path dir = WildFlyPaths.deploymentsDir(server);
        write(dir, "api.war.deployed", "");
        write(dir, "api.war.dodeploy", "");
        assertEquals("DEPLOYING", DeploymentScannerService.status(server, "api.war"));
        assertNotNull(DeploymentScannerService.lastDeployedAt(server, "api.war"));
    }

    @Test public void cleanupRejectsTraversalAndPortableInvalidNamesBeforeDeletingAnything() throws Exception {
        var server = server();
        Path sentinel = write(WildFlyPaths.standaloneDir(server), "outside.war", "keep");
        for (String name : List.of(".", "..", "../outside.war", "..\\outside.war", "/outside.war", "C:\\outside.war", "api:stream.war", "NUL.war", "api.war.")) {
            assertThrows(name, IllegalArgumentException.class, () -> DeploymentScannerService.cleanupDeployment(server, name));
            assertEquals("NOT DEPLOYED", DeploymentScannerService.status(server, name));
        }
        assertEquals("keep", Files.readString(sentinel));
    }

    @Test public void failedCopyKeepsPreviousArtifactAndRemovesOnlyItsTemporaryFile() throws Exception {
        Path dir = temp.newFolder("deployments").toPath();
        Path target = write(dir, "api.war", "previous artifact");
        Path unrelated = write(dir, "api.war.uploading", "unrelated upload");
        assertThrows(IOException.class, () -> DeploymentScannerService.replaceArtifactSafely(dir.resolve("missing.war"), target));
        assertEquals("previous artifact", Files.readString(target));
        assertEquals("unrelated upload", Files.readString(unrelated));
        try (var files = Files.list(dir)) { assertEquals(2, files.count()); }
    }

    @Test public void successfulCopyPreservesSourceAndAtomicallyReplacesScannerCopy() throws Exception {
        Path dir = temp.newFolder("deployments").toPath();
        Path source = write(temp.getRoot().toPath(), "project/api.war", "new artifact");
        Path target = write(dir, "api.war", "old artifact");
        DeploymentScannerService.replaceArtifactSafely(source, target);
        assertEquals("new artifact", Files.readString(source));
        assertEquals("new artifact", Files.readString(target));
        try (var files = Files.list(dir)) { assertEquals(1, files.count()); }
        DeploymentScannerService.createRequestMarker(dir.resolve("api.war.dodeploy"));
        assertEquals(0, Files.size(dir.resolve("api.war.dodeploy")));
    }

    @Test public void markerCannotTruncateFileThroughSymbolicLink() throws Exception {
        Path dir = temp.newFolder("deployments").toPath();
        Path outside = write(temp.getRoot().toPath(), "outside.txt", "keep");
        Path marker = dir.resolve("api.war.dodeploy");
        try { Files.createSymbolicLink(marker, outside); }
        catch (UnsupportedOperationException | IOException unavailable) {
            Assume.assumeNoException("Creating symlinks requires OS support/privileges", unavailable);
        }
        assertThrows(IOException.class, () -> DeploymentScannerService.createRequestMarker(marker));
        assertEquals("keep", Files.readString(outside));
    }
}
