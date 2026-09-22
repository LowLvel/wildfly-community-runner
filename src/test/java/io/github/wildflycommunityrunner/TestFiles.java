package io.github.wildflycommunityrunner;

import com.intellij.openapi.project.Project;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;

public final class TestFiles {
    private TestFiles() {}

    public static Path write(Path root, String relative, String contents) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, contents);
    }

    public static Path archive(Path root, String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), "archive-", ".writing");
        try {
            byte[] data = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var crc = new java.util.zip.CRC32(); crc.update(data);
            try (var output = new java.util.zip.ZipOutputStream(Files.newOutputStream(temp))) {
                var entry = new java.util.zip.ZipEntry("index.txt");
                entry.setTime(0); entry.setMethod(java.util.zip.ZipEntry.STORED);
                entry.setSize(data.length); entry.setCrc(crc.getValue());
                output.putNextEntry(entry); output.write(data); output.closeEntry();
            }
            return Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }

    public static String archiveContent(Path file) throws IOException {
        try (var zip = new java.util.zip.ZipFile(file.toFile()); var input = zip.getInputStream(zip.getEntry("index.txt"))) {
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /** Fail on unexpected IDE access: these scenarios should only need the project directory. */
    public static Project projectAt(Path base) {
        return (Project) Proxy.newProxyInstance(Project.class.getClassLoader(), new Class<?>[]{Project.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getBasePath" -> base.toString();
                    case "isDisposed" -> false;
                    case "toString" -> "Filesystem test project";
                    default -> throw new AssertionError("Unexpected Project API: " + method.getName());
                });
    }
}
