package io.github.wildflycommunityrunner.services;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipFile;

/** Bounded-memory fingerprint of a completed archive; never treats the last unstable sample as stable. */
record ArtifactFingerprint(Path path, String sha256) {
    record Stamp(long size, FileTime modified, Object key) {}
    static Stamp stamp(Path path) throws IOException {
        var attrs = Files.readAttributes(path, BasicFileAttributes.class);
        return new Stamp(attrs.size(), attrs.lastModifiedTime(), attrs.fileKey());
    }
    static ArtifactFingerprint read(Path artifact) throws IOException {
        Path path = artifact.toRealPath();
        Stamp before = stamp(path);
        try (var archive = new ZipFile(path.toFile())) { archive.size(); }
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Artifact read cancelled");
                digest.update(buffer, 0, count);
            }
        }
        if (!Objects.equals(before, stamp(path))) throw new IOException("Artifact is still being written");
        return new ArtifactFingerprint(path, HexFormat.of().formatHex(digest.digest()));
    }
    static ArtifactFingerprint stable(Path path, BooleanSupplier cancelled) throws IOException, InterruptedException {
        Stamp previous = stamp(path);
        int stable = 0;
        for (int i = 0; i < 12; i++) {
            if (cancelled.getAsBoolean()) return null;
            Thread.sleep(250);
            Stamp current = stamp(path);
            stable = current.equals(previous) ? stable + 1 : 0;
            previous = current;
            if (stable >= 3) {
                ArtifactFingerprint result = read(path);
                return !cancelled.getAsBoolean() && current.equals(stamp(path)) ? result : null;
            }
        }
        return null;
    }
}
