package io.github.wildflycommunityrunner.services;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.UnaryOperator;

/** Single-worker, bounded UTF-8 tail. No file handles survive a poll. */
public final class ServerLogTailer {
    public static final int MAX_TEXT = 200_000;
    static final int MAX_READ = 256 * 1024;
    private static final int MAX_LINE = 65_536;
    public record Snapshot(String text, String status) {}
    private final StringBuilder text = new StringBuilder();
    private final StringBuilder line = new StringBuilder();
    private final java.nio.charset.CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
    private byte[] carry = new byte[0], prefix = new byte[0], anchor = new byte[0];
    private BasicFileAttributes identity;
    private long offset;
    private boolean missing, skipPartial, dropping, afterCr;

    public Snapshot poll(Path path, UnaryOperator<String> redact) throws IOException {
        try {
            BasicFileAttributes before = Files.readAttributes(path, BasicFileAttributes.class);
            if (!before.isRegularFile()) return snapshot("server.log is not a regular file");
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
                byte[] currentPrefix = read(channel, 0, (int) Math.min(before.size(), 64));
                boolean reset = identity == null || missing || !sameFile(identity, before) || before.size() < offset
                        || !startsWith(currentPrefix, prefix)
                        || !Arrays.equals(anchor, read(channel, Math.max(0, offset - anchor.length), anchor.length));
                boolean behind = before.size() - (reset ? 0 : offset) > MAX_READ;
                long start = reset ? 0 : offset;
                if (behind) start = before.size() - MAX_READ;
                byte[] bytes = read(channel, start, (int) Math.min(MAX_READ, before.size() - start));
                long next = start + bytes.length;
                byte[] nextAnchor = read(channel, Math.max(0, next - 64), (int) Math.min(next, 64));
                BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class);
                if (!sameFile(before, after) || after.size() < next
                        || (after.size() == before.size() && !after.lastModifiedTime().equals(before.lastModifiedTime()))) return snapshot("Log changed while reading; retrying");

                if (reset || behind) {
                    if (identity != null && reset) append("[server.log rotated or truncated]\n");
                    resetDecoder();
                    skipPartial = start > 0;
                    if (skipPartial) append("[Showing the latest log tail; older content omitted]\n");
                }
                identity = after; missing = false; prefix = currentPrefix; anchor = nextAnchor; offset = next;
                consume(bytes, redact);
                return snapshot("Following server.log · UTF-8 · up to 200,000 characters");
            }
        } catch (NoSuchFileException absent) {
            missing = true;
            return snapshot("Waiting for server.log to be created");
        }
    }

    /** Clears displayed complete lines only; preserving a partial line prevents redaction gaps. */
    public void clearView() { text.setLength(0); }

    public void reload() {
        text.setLength(0); identity = null; offset = 0; missing = false;
        prefix = new byte[0]; anchor = new byte[0]; resetDecoder(); skipPartial = false;
    }

    private Snapshot snapshot(String status) { return new Snapshot(text.toString(), status); }

    private static boolean sameFile(BasicFileAttributes left, BasicFileAttributes right) {
        return Objects.equals(left.fileKey(), right.fileKey()) && left.creationTime().equals(right.creationTime());
    }
    private static boolean startsWith(byte[] value, byte[] beginning) {
        if (value.length < beginning.length) return false;
        for (int i = 0; i < beginning.length; i++) if (value[i] != beginning[i]) return false;
        return true;
    }
    private static byte[] read(FileChannel channel, long start, int count) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(count);
        while (buffer.hasRemaining()) {
            int size = channel.read(buffer, start + buffer.position());
            if (size <= 0) break;
        }
        return Arrays.copyOf(buffer.array(), buffer.position());
    }
    private void resetDecoder() {
        decoder.reset(); carry = new byte[0]; line.setLength(0); dropping = false; afterCr = false;
    }
    private void consume(byte[] bytes, UnaryOperator<String> redact) {
        int start = 0;
        if (skipPartial) {
            while (start < bytes.length && bytes[start] != '\n') start++;
            if (start == bytes.length) return;
            start++; skipPartial = false;
        }
        ByteBuffer input = ByteBuffer.allocate(carry.length + bytes.length - start);
        input.put(carry).put(bytes, start, bytes.length - start).flip();
        CharBuffer characters = CharBuffer.allocate(input.remaining() + 1);
        decoder.decode(input, characters, false);
        carry = new byte[input.remaining()]; input.get(carry);
        characters.flip();
        while (characters.hasRemaining()) {
            char value = characters.get();
            if (value == '\n' && afterCr) { afterCr = false; continue; }
            afterCr = value == '\r';
            if (value == '\n' || value == '\r') {
                append(dropping ? "[Oversized log line omitted]\n" : redact.apply(line.toString()) + "\n");
                line.setLength(0); dropping = false;
            } else if (!dropping) {
                line.append(value);
                if (line.length() > MAX_LINE) { line.setLength(0); dropping = true; }
            }
        }
    }
    private void append(String value) {
        text.append(value);
        if (text.length() > MAX_TEXT) {
            int boundary = text.indexOf("\n", text.length() - MAX_TEXT);
            text.delete(0, boundary < 0 ? text.length() : boundary + 1);
        }
    }
}
