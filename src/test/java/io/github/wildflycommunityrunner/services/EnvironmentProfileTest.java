package io.github.wildflycommunityrunner.services;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.nio.file.Path;
import java.util.Map;
import static io.github.wildflycommunityrunner.TestFiles.write;
import static org.junit.Assert.*;

public class EnvironmentProfileTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private Path home(String name) throws Exception {
        Path path = temporary.newFolder(name).toPath();
        write(path, "jboss-modules.jar", "fixture");
        write(path, "bin/standalone.sh", "");
        write(path, "bin/standalone.bat", "");
        write(path, "standalone/configuration/standalone.xml", "<server/>");
        return path;
    }

    @Test public void prefersValidWildflyHomeAndHandlesQuotedPaths() throws Exception {
        Path preferred = home("WildFly home");
        Path fallback = home("JBoss home");
        var profile = ProjectSetupService.environmentProfile(Map.of("WILDFLY_HOME", "\"" + preferred + "\"", "JBOSS_HOME", fallback.toString()));
        assertNotNull(profile);
        assertEquals(preferred.toString(), profile.home);
        assertEquals("standalone.xml", profile.configuration);
        assertEquals("", profile.javaHome);
    }

    @Test public void fallsBackToJbossHomeAndIgnoresAbsentInvalidOrUnrelatedVariables() throws Exception {
        Path fallback = home("server");
        var profile = ProjectSetupService.environmentProfile(Map.of("WILDFLY_HOME", "invalid\0path", "JBOSS_HOME", fallback.toString()));
        assertEquals(fallback.toString(), profile.home);
        assertNull(ProjectSetupService.environmentProfile(Map.of("JAVA_HOME", fallback.toString())));
        assertNull(ProjectSetupService.environmentProfile(Map.of("WILDFLY_HOME", temporary.getRoot().toString())));
        assertNull(ProjectSetupService.environmentProfile(Map.of()));
    }
}
