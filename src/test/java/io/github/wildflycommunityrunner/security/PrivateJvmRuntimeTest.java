package io.github.wildflycommunityrunner.security;

import com.intellij.util.execution.ParametersListUtil;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import static org.junit.Assert.*;

public class PrivateJvmRuntimeTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static boolean windows() { return System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win"); }

    @Test public void realJvmReadsPrivateArgumentsWithSpacesUnicodeQuotesAndBackslashes() throws Exception {
        Path root = temporary.newFolder("private path with spaces").toPath();
        String value = "synthetic " + (PrivateJvmOptions.launcherCharset().newEncoder().canEncode("ü日本語") ? "ü日本語" : (PrivateJvmOptions.launcherCharset().newEncoder().canEncode("ü") ? "ü" : "ASCII")) + " \"quotes\" C:\\folder\\tail # @ $ =\nsecond line";
        var options = PrivateJvmOptions.create("", List.of("-Dordinary=visible"), List.of("-Dwildfly.test.password=" + value), List.of(value), root);
        Path file = options.file();
        try (options) {
            Path jar = probeJar(root);
            var command = new ArrayList<String>();
            command.add(Path.of(System.getProperty("java.home"), "bin", windows() ? "java.exe" : "java").toString());
            command.addAll(ParametersListUtil.parse(options.options()));
            command.add("-jar"); command.add(jar.toString());
            assertFalse(command.toString().contains(value));
            run(command, root, value, 30, null);
        }
        assertFalse(Files.exists(file));
    }

    @Test public void realGradleBuildReceivesJvmSecretWithoutPlaintextCommandArguments() throws Exception {
        Path root = temporary.newFolder("Gradle project with spaces").toPath();
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name = 'secret-probe'\n");
        Files.writeString(root.resolve("build.gradle"), "tasks.register('verifySecret') { doLast { "
                + "if (System.getProperty('wildfly.test.password') != System.getenv('EXPECTED_TEST_SECRET')) "
                + "throw new GradleException('The private JVM property was not delivered') } }\n");
        String home = System.getProperty("wildfly.test.gradleHome");
        assertNotNull("Run this integration test with the repository Gradle wrapper", home);
        String value = "synthetic-Gradle value with spaces";
        try (var options = PrivateJvmOptions.create("", List.of(), List.of("-Dwildfly.test.password=" + value), List.of(value), root);
             var launcher = PrivateJvmOptions.gradleLauncher(options, root)) {
            var command = new ArrayList<String>();
            if (windows()) { command.add("cmd.exe"); command.add("/d"); command.add("/c"); }
            command.add(Path.of(home, "bin", windows() ? "gradle.bat" : "gradle").toString());
            command.addAll(List.of("--offline", "--no-daemon", "--console=plain", "verifySecret"));
            assertFalse(command.toString().contains(value));
            run(command, root, value, 120, launcher.options());
        }
    }

    @Test public void unrepresentableCharactersFailWithoutSubstitutionOrSecretInTheError() throws Exception {
        String value = "synthetic日本語";
        var error = assertThrows(java.io.IOException.class,
                () -> PrivateJvmOptions.encode(value, java.nio.charset.StandardCharsets.US_ASCII));
        assertFalse(error.getMessage().contains(value));
        assertTrue(error.getMessage().contains("UTF-8"));
        assertArrayEquals(value.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                PrivateJvmOptions.encode(value, java.nio.charset.StandardCharsets.UTF_8));
    }

    private void run(List<String> command, Path directory, String expected, long timeout, String launcherOptions) throws Exception {
        Path log = directory.resolve("process-output.txt");
        var builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("EXPECTED_TEST_SECRET", expected);
        builder.environment().put("GRADLE_USER_HOME", directory.resolve("gradle-user-home").toString());
        if (launcherOptions != null) builder.environment().put("JAVA_OPTS", launcherOptions);
        Process process = builder.start();
        try {
            assertTrue("Child JVM timed out", process.waitFor(timeout, TimeUnit.SECONDS));
            String output = Files.readString(log);
            assertFalse("The child process exposed its synthetic test secret", output.contains(expected));
            assertEquals("Child process failed: " + new SecretRedactor(List.of(expected)).redact(output), 0, process.exitValue());
        } finally {
            if (process.isAlive()) {
                try (var descendants = process.descendants()) { descendants.forEach(ProcessHandle::destroyForcibly); }
                process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS);
            }
        }
    }

    private static Path probeJar(Path directory) throws Exception {
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, SecretProbe.class.getName());
        Path jar = directory.resolve("probe.jar");
        String resource = SecretProbe.class.getName().replace('.', '/') + ".class";
        try (var output = new JarOutputStream(Files.newOutputStream(jar), manifest);
             var input = SecretProbe.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input); output.putNextEntry(new JarEntry(resource)); input.transferTo(output); output.closeEntry();
        }
        return jar;
    }
}
