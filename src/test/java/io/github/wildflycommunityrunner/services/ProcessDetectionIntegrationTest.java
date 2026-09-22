package io.github.wildflycommunityrunner.services;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.github.wildflycommunityrunner.model.ServerProfile;
import org.junit.rules.TemporaryFolder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

public class ProcessDetectionIntegrationTest extends BasePlatformTestCase {
    public void testDetectsRealChildJvmAndRevalidatesItsIdentity() throws Exception {
        var temporary = new TemporaryFolder();
        temporary.create();
        Process process = null;
        try {
            Path home = temporary.newFolder("WildFly home with spaces").toPath().toAbsolutePath();
            Path jar = home.resolve("jboss-modules.jar");
            Manifest manifest = new Manifest();
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, ProcessProbe.class.getName());
            String classPath = ProcessProbe.class.getName().replace('.', '/') + ".class";
            try (var output = new JarOutputStream(Files.newOutputStream(jar), manifest);
                 var bytecode = ProcessProbe.class.getClassLoader().getResourceAsStream(classPath)) {
                assertNotNull(bytecode);
                output.putNextEntry(new JarEntry(classPath));
                bytecode.transferTo(output);
                output.closeEntry();
            }
            boolean windows = System.getProperty("os.name", "").startsWith("Windows");
            Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
            process = new ProcessBuilder(javaExecutable.toString(), "-jar", jar.toString(), "-mp", home.resolve("modules").toString(),
                    "org.jboss.as.standalone", "-Djboss.home.dir=" + home, "-c", "standalone.xml").redirectErrorStream(true).start();
            Process child = process;
            String ready = ApplicationManager.getApplication().executeOnPooledThread(
                    () -> new java.io.BufferedReader(new java.io.InputStreamReader(child.getInputStream())).readLine()).get(10, TimeUnit.SECONDS);
            assertEquals("ready", ready);
            var profile = new ServerProfile();
            profile.home = home.toString();
            String failure = ApplicationManager.getApplication().executeOnPooledThread(() -> {
                // Periodic status uses a one-second cache. A process born after that snapshot appears on the next refresh.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                boolean detected;
                do {
                    detected = WildFlyServerDetector.matchingProcesses(profile).stream().anyMatch(p -> p.pid() == child.pid());
                    if (!detected) Thread.sleep(200);
                } while (!detected && System.nanoTime() < deadline);
                if (!detected) {
                    var row = windows ? WindowsProcessQuery.read(true).stream().filter(c -> c.pid() == child.pid()).findFirst() : java.util.Optional.<WindowsProcessQuery.Command>empty();
                    return "Child process not detected: alive=" + child.isAlive() + ", nativeArgs=" + child.info().arguments().isPresent()
                            + ", CIM row=" + row.isPresent() + ", nativeStart=" + child.info().startInstant()
                            + ", CIM start=" + row.map(WindowsProcessQuery.Command::startedMillis)
                            + ", argumentsMatch=" + row.map(c -> WildFlyServerDetector.matches(profile, c.executable(), c.arguments()));
                }
                if (!WildFlyServerDetector.verifies(profile, child.toHandle())) return "Fresh process identity did not match";
                profile.configuration = "standalone-full.xml";
                if (WildFlyServerDetector.verifies(profile, child.toHandle())) return "Wrong configuration matched";
                if (!WildFlyServerDetector.matchingProcesses(profile).isEmpty()) return "Wrong configuration appeared as running";
                if (WildFlyServerDetector.matchingServerBase(profile).stream().noneMatch(p -> p.pid() == child.pid())) return "Server base conflict not detected";
                return null;
            }).get(40, TimeUnit.SECONDS);
            assertNull(failure, failure);
        } finally {
            if (process != null) { process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS); }
            temporary.delete();
        }
    }
}
