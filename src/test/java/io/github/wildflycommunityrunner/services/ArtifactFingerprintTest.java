package io.github.wildflycommunityrunner.services;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import static io.github.wildflycommunityrunner.TestFiles.*;
import static org.junit.Assert.*;

public class ArtifactFingerprintTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Test public void detectsChangedContentWithSameLengthAndTimestamp() throws Exception {
        var path = archive(temp.getRoot().toPath(), "api.war", "first");
        var before = ArtifactFingerprint.read(path);
        var modified = Files.getLastModifiedTime(path);
        long size = Files.size(path);
        archive(temp.getRoot().toPath(), "api.war", "other");
        Files.setLastModifiedTime(path, modified);
        assertEquals(size, Files.size(path));
        assertNotEquals(before, ArtifactFingerprint.read(path));
    }
    @Test public void rejectsTruncatedArchivesAndHonorsCancellationBeforeWaiting() throws Exception {
        var path = write(temp.getRoot().toPath(), "partial.war", "PK incomplete");
        assertThrows(IOException.class, () -> ArtifactFingerprint.read(path));
        assertNull(ArtifactFingerprint.stable(path, () -> true));
    }
    @Test public void neverReturnsLastUnstableSampleAsDeployable() throws Exception {
        var path = archive(temp.getRoot().toPath(), "api.war", "first");
        var changes = new AtomicInteger();
        var result = ArtifactFingerprint.stable(path, () -> {
            try { Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.fromMillis(10000L * changes.incrementAndGet())); }
            catch (IOException error) { throw new java.io.UncheckedIOException(error); }
            return false;
        });
        assertNull(result);
    }
    @Test public void changedArchiveCannotReplacePreviouslyDeployedCopy() throws Exception {
        var source = archive(temp.getRoot().toPath(), "source/api.war", "first");
        var expected = ArtifactFingerprint.read(source);
        var target = archive(temp.getRoot().toPath(), "deployments/api.war", "deployed");
        archive(temp.getRoot().toPath(), "source/api.war", "newer");
        assertThrows(DeploymentScannerService.ArtifactChangedException.class,
                () -> DeploymentScannerService.replaceArtifactSafely(source, target, expected));
        assertEquals("deployed", archiveContent(target));
        assertEquals("newer", archiveContent(source));
    }
}
