package io.github.wildflycommunityrunner.services;

import io.github.wildflycommunityrunner.security.SecretRedactor;
import io.github.wildflycommunityrunner.security.SensitiveProperties;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import static org.junit.Assert.*;

public class ServerLogTailerTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private Path path() { return temporary.getRoot().toPath().resolve("server.log"); }
    private static void append(Path path, String value) throws Exception { Files.writeString(path, value, StandardOpenOption.APPEND); }
    private static ServerLogTailer.Snapshot poll(ServerLogTailer tailer, Path path) throws Exception {
        return tailer.poll(path, SensitiveProperties::redactProperties);
    }

    @Test public void missingLogAppearsAndAppendsWithoutKeepingAFileHandleOpen() throws Exception {
        var tailer = new ServerLogTailer(); Path file = path();
        assertTrue(poll(tailer, file).status().contains("Waiting"));
        Files.writeString(file, "first\n"); assertEquals("first\n", poll(tailer, file).text());
        append(file, "second\n"); assertEquals("first\nsecond\n", poll(tailer, file).text());
        Files.move(file, file.resolveSibling("server.log.1"));
        assertTrue(poll(tailer, file).status().contains("Waiting"));
        Files.writeString(file, "new file\n");
        String text = poll(tailer, file).text();
        assertTrue(text.contains("rotated or truncated")); assertTrue(text.endsWith("new file\n"));
    }

    @Test public void utf8CharactersAndCrLfCanBeSplitAcrossAnyByteBoundary() throws Exception {
        var tailer = new ServerLogTailer(); Path file = Files.createFile(path());
        byte[] data = "日本語 ü emoji 😀\r\nnext\n".getBytes(StandardCharsets.UTF_8);
        for (byte value : data) {
            Files.write(file, new byte[]{value}, StandardOpenOption.APPEND);
            assertFalse(poll(tailer, file).text().contains("�"));
        }
        assertEquals("日本語 ü emoji 😀\nnext\n", poll(tailer, file).text());
    }

    @Test public void initialLargeFilesAreBoundedAndStartAtACompleteLine() throws Exception {
        var tailer = new ServerLogTailer(); Path file = path();
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 100_000; i++) content.append("line-").append(i).append(" 日本語\n");
        Files.writeString(file, content);
        String text = poll(tailer, file).text();
        assertTrue(text.length() <= ServerLogTailer.MAX_TEXT);
        assertFalse(text.contains("line-0 ")); assertTrue(text.endsWith("line-99999 日本語\n"));
        assertFalse(text.contains("�"));
    }

    @Test public void truncationAndRegrowthWithTheSamePrefixResetTheCursor() throws Exception {
        var tailer = new ServerLogTailer(); Path file = path();
        String prefix = "identical header ".repeat(6) + "\n";
        Files.writeString(file, prefix + "old line\n".repeat(20)); poll(tailer, file);
        Files.writeString(file, prefix + "new longer line\n".repeat(40));
        String text = poll(tailer, file).text();
        assertTrue(text.contains("rotated or truncated")); assertTrue(text.endsWith("new longer line\n"));
        Files.writeString(file, "short\n"); assertTrue(poll(tailer, file).text().endsWith("short\n"));
    }

    @Test public void clearOnlyChangesTheViewAndReloadReadsTheFileAgain() throws Exception {
        var tailer = new ServerLogTailer(); Path file = path();
        Files.writeString(file, "original\n"); poll(tailer, file);
        tailer.clearView(); assertEquals("", poll(tailer, file).text());
        assertEquals("original\n", Files.readString(file));
        append(file, "later\n"); assertEquals("later\n", poll(tailer, file).text());
        tailer.reload(); assertEquals("original\nlater\n", poll(tailer, file).text());
    }

    @Test public void partialSecretsAreNeverDisplayedAndClearRetainsTheirRedactionContext() throws Exception {
        var tailer = new ServerLogTailer(); Path file = path();
        Files.writeString(file, "-Dpassword=first"); assertEquals("", poll(tailer, file).text());
        tailer.clearView(); append(file, "second\n");
        assertEquals("-Dpassword=[redacted]\n", poll(tailer, file).text());
        var redactor = new SecretRedactor(List.of("value-split-across-chunks"));
        append(file, "plain value-split-"); assertFalse(tailer.poll(file, redactor::redact).text().contains("value-split"));
        append(file, "across-chunks\n");
        assertTrue(tailer.poll(file, redactor::redact).text().endsWith("plain [redacted]\n"));
    }

    @Test public void oversizedLinesAreOmittedWithoutExposingTheirSuffix() throws Exception {
        var tailer = new ServerLogTailer(); Path file = path();
        Files.writeString(file, "-Dpassword=" + "s".repeat(70_000));
        assertEquals("", poll(tailer, file).text());
        append(file, "sensitive-tail\nafter\n");
        assertEquals("[Oversized log line omitted]\nafter\n", poll(tailer, file).text());
    }

    @Test public void aLargeAppendCatchesUpWithoutReadingUnboundedHistory() throws Exception {
        var tailer = new ServerLogTailer(); Path file = path();
        Files.writeString(file, "first\n"); poll(tailer, file);
        append(file, "intermediate line\n".repeat(60_000) + "last\n");
        String text = poll(tailer, file).text();
        assertTrue(text.length() <= ServerLogTailer.MAX_TEXT); assertTrue(text.endsWith("last\n"));
    }

    @Test public void changingFallbackCreationTimesDoNotTurnEveryAppendIntoARotation() {
        assertTrue(ServerLogTailer.sameFile(attributes("same file", 1), attributes("same file", 2)));
        assertTrue(ServerLogTailer.sameFile(attributes(null, 1), attributes(null, 2)));
        assertFalse(ServerLogTailer.sameFile(attributes("first file", 1), attributes("second file", 1)));
    }

    private static java.nio.file.attribute.BasicFileAttributes attributes(Object key, long time) {
        return new java.nio.file.attribute.BasicFileAttributes() {
            @Override public java.nio.file.attribute.FileTime lastModifiedTime() { return java.nio.file.attribute.FileTime.fromMillis(time); }
            @Override public java.nio.file.attribute.FileTime lastAccessTime() { return lastModifiedTime(); }
            @Override public java.nio.file.attribute.FileTime creationTime() { return lastModifiedTime(); }
            @Override public boolean isRegularFile() { return true; }
            @Override public boolean isDirectory() { return false; }
            @Override public boolean isSymbolicLink() { return false; }
            @Override public boolean isOther() { return false; }
            @Override public long size() { return time; }
            @Override public Object fileKey() { return key; }
        };
    }

    @Test public void malformedUtf8DoesNotStopFollowingLaterLines() throws Exception {
        var tailer = new ServerLogTailer(); Path file = path();
        Files.write(file, new byte[]{(byte) 0xff, '\n'});
        assertEquals("�\n", poll(tailer, file).text());
        append(file, "valid\n"); assertTrue(poll(tailer, file).text().endsWith("valid\n"));
    }
}
