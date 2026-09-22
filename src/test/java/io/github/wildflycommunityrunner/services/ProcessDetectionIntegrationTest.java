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
            Path java = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
            process = new ProcessBuilder(java.toString(), "-jar", jar.toString(), "-mp", home.resolve("modules").toString(),
                    "org.jboss.as.standalone", "-Djboss.home.dir=" + home, "-c", "standalone.xml").redirectErrorStream(true).start();
            Process child = process;
            String ready = ApplicationManager.getApplication().executeOnPooledThread(
                    () -> new java.io.BufferedReader(new java.io.InputStreamReader(child.getInputStream())).readLine()).get(10, TimeUnit.SECONDS);
            assertEquals("ready", ready);
            var profile = new ServerProfile();
            profile.home = home.toString();
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                assertTrue(WildFlyServerDetector.matchingProcesses(profile).stream().anyMatch(p -> p.pid() == child.pid()));
                assertTrue(WildFlyServerDetector.verifies(profile, child.toHandle()));
                profile.configuration = "standalone-full.xml";
                assertFalse(WildFlyServerDetector.verifies(profile, child.toHandle()));
                assertTrue(WildFlyServerDetector.matchingProcesses(profile).isEmpty());
                assertTrue(WildFlyServerDetector.matchingServerBase(profile).stream().anyMatch(p -> p.pid() == child.pid()));
            }).get(20, TimeUnit.SECONDS);
        } finally {
            if (process != null) { process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS); }
            temporary.delete();
        }
    }
}
