package io.github.wildflycommunityrunner.security;

import com.intellij.util.execution.ParametersListUtil;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.*;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/** Restricted files are created before writing values. Call close on a background thread. */
public final class PrivateJvmOptions implements AutoCloseable {
    private final String options;
    private final Path directory;
    private final Path file;
    private final SecretRedactor redactor;
    private boolean closed;

    static PrivateJvmOptions create(String original, List<String> visible, List<String> protectedArgs, List<String> values) throws IOException {
        return create(original, visible, protectedArgs, values, Path.of(System.getProperty("java.io.tmpdir")));
    }

    static PrivateJvmOptions create(String original, List<String> visible, List<String> protectedArgs, List<String> values, Path temporaryRoot) throws IOException {
        if (protectedArgs.isEmpty()) return new PrivateJvmOptions(original, null, null, new SecretRedactor(values));
        StringBuilder content = new StringBuilder();
        for (String argument : protectedArgs) content.append(quote(argument)).append('\n');
        Path directory = Files.createTempDirectory(temporaryRoot, "wildfly-jvm-");
        Path file = directory.resolve("options.args");
        try {
            restrict(directory, true);
            Files.createFile(file);
            restrict(file, false);
            Files.writeString(file, content, StandardCharsets.UTF_8);
            List<String> arguments = new ArrayList<>(visible);
            arguments.add("@" + file);
            return new PrivateJvmOptions(ParametersListUtil.join(arguments), directory, file, new SecretRedactor(values));
        } catch (Exception error) {
            Files.deleteIfExists(file); Files.deleteIfExists(directory);
            throw new IOException("Cannot create a private JVM argument file. Check temporary-directory permissions.");
        }
    }

    private PrivateJvmOptions(String options, Path directory, Path file, SecretRedactor redactor) {
        this.options = options; this.directory = directory; this.file = file; this.redactor = redactor;
    }
    public String options() { return options; }
    public boolean containsSecrets() { return file != null; }
    public SecretRedactor redactor() { return redactor; }
    Path file() { return file; }

    private static void restrict(Path path, boolean directory) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class);
        if (posix != null) {
            posix.setPermissions(PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
        if (acl == null) throw new IOException("The temporary filesystem does not support private permissions.");
        AclEntry entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(acl.getOwner())
                .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build();
        acl.setAcl(List.of(entry));
    }

    static String quote(String argument) {
        if (argument.indexOf(0) >= 0) throw new IllegalArgumentException("JVM properties cannot contain a NUL character.");
        return "\"" + argument.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t").replace("\f", "\\f") + "\"";
    }

    @Override public synchronized void close() {
        if (closed) return;
        try {
            if (file != null) Files.deleteIfExists(file);
            if (directory != null) Files.deleteIfExists(directory);
            closed = true;
        } catch (IOException failure) {
            // Retry at application disposal; never log option contents.
        }
    }
    synchronized boolean isClosed() { return closed; }
}
