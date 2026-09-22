package io.github.wildflycommunityrunner.security;

import com.intellij.util.execution.ParametersListUtil;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
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
        byte[] encoded = encode(content.toString(), launcherCharset());
        Path directory = Files.createTempDirectory(temporaryRoot, "wildfly-jvm-");
        Path file = directory.resolve("options.args");
        try {
            restrict(directory, true);
            Files.createFile(file);
            restrict(file, false);
            Files.write(file, encoded);
            List<String> arguments = new ArrayList<>(visible);
            arguments.add("@" + file);
            return new PrivateJvmOptions(ParametersListUtil.join(arguments), directory, file, new SecretRedactor(values));
        } catch (Exception error) {
            Files.deleteIfExists(file); Files.deleteIfExists(directory);
            throw new IOException("Cannot create a private JVM argument file. Check temporary-directory permissions.");
        }
    }

    // Java's native launcher uses the OS encoding, including the Windows ANSI code page.
    static Charset launcherCharset() {
        return Charset.forName(System.getProperty("native.encoding", Charset.defaultCharset().name()));
    }

    static byte[] encode(String content, Charset charset) throws IOException {
        try {
            ByteBuffer bytes = charset.newEncoder().encode(CharBuffer.wrap(content));
            byte[] encoded = new byte[bytes.remaining()]; bytes.get(encoded); return encoded;
        } catch (CharacterCodingException error) {
            throw new IOException("A JVM option contains characters unavailable in the system launcher encoding ("
                    + charset.name() + "). Use a UTF-8 system locale or a credential file supported by the application.");
        }
    }

    static PrivateJvmOptions gradleLauncher(PrivateJvmOptions daemon, Path temporaryRoot) throws IOException {
        // Avoid nested quotes passing through cmd.exe: the Gradle client reads the
        // org.gradle.jvmargs property from its own Java argument file.
        return create("", List.of(), List.of("-Dorg.gradle.jvmargs=" + daemon.options()), List.of(), temporaryRoot);
    }

    private PrivateJvmOptions(String options, Path directory, Path file, SecretRedactor redactor) {
        this.options = options; this.directory = directory; this.file = file; this.redactor = redactor;
    }
    public String options() { return options; }
    public String visibleOptions() {
        return file == null ? options : ParametersListUtil.join(ParametersListUtil.parse(options).stream()
                .filter(argument -> !argument.equals("@" + file)).toList());
    }
    public String argumentFileReference() { return file == null ? "" : ParametersListUtil.join(List.of("@" + file)); }
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
