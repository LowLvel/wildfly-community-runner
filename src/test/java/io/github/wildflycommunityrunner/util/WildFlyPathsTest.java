package io.github.wildflycommunityrunner.util;

import io.github.wildflycommunityrunner.model.ServerProfile;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.nio.file.Path;
import java.util.UUID;
import static io.github.wildflycommunityrunner.TestFiles.write;
import static org.junit.Assert.*;

public class WildFlyPathsTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private ServerProfile profile() {
        var profile = new ServerProfile();
        profile.home = temp.getRoot().getAbsolutePath();
        return profile;
    }

    @Test public void filesystemShortcutsFollowServerBaseConfigurationAndLogOverrides() throws Exception {
        var profile = profile();
        Path base = temp.newFolder("instance one").toPath();
        Path config = temp.newFolder("config").toPath();
        Path logs = temp.newFolder("logs").toPath();
        profile.jvmOptions = "-Djboss.server.base.dir=\"" + base + "\" -Djboss.server.config.dir=\"" + config + "\"";
        profile.startupArguments = "-Djboss.server.log.dir=\"" + logs + "\" --server-config=custom.xml";
        assertEquals(base.resolve("deployments"), WildFlyPaths.deploymentsDir(profile));
        assertEquals(config.resolve("custom.xml"), WildFlyPaths.configurationFile(profile));
        assertEquals(logs.resolve("server.log"), WildFlyPaths.logFile(profile));
    }

    @Test public void identityUsesNormalizedInstancePathsInsteadOfProfileIdsOrDisplayNames() {
        var profile = profile();
        var alias = new ServerProfile(profile);
        alias.id = UUID.randomUUID().toString();
        alias.name = "Different display name";
        alias.home = Path.of(profile.home, "subfolder", "..").toString();
        assertEquals(WildFlyPaths.identity(profile), WildFlyPaths.identity(alias));
        alias.configuration = "standalone-full.xml";
        assertNotEquals(WildFlyPaths.identity(profile), WildFlyPaths.identity(alias));
    }

    @Test public void validationRequiresWildFlyModulesAndConfiguredJavaExecutable() throws Exception {
        var profile = profile();
        Path home = Path.of(profile.home);
        write(home, "bin/standalone.sh", "");
        write(home, "bin/standalone.bat", "");
        write(home, "standalone/configuration/standalone.xml", "<server/>");
        assertTrue(WildFlyPaths.validate(profile).contains("jboss-modules.jar"));
        write(home, "jboss-modules.jar", "fixture");
        assertNull(WildFlyPaths.validate(profile));
        profile.javaHome = home.resolve("missing-java").toString();
        assertTrue(WildFlyPaths.validate(profile).contains("JAVA_HOME"));
    }

    @Test public void duplicateOrMissingConfigurationArgumentsAreRejectedLikeWildFly() {
        assertThrows(IllegalArgumentException.class, () -> WildFlyPaths.configurationArgument(java.util.List.of("-c"), "standalone.xml"));
        assertThrows(IllegalArgumentException.class, () -> WildFlyPaths.configurationArgument(java.util.List.of("-c", "a.xml", "--server-config=b.xml"), "standalone.xml"));
        assertEquals("readonly.xml", WildFlyPaths.configurationArgument(java.util.List.of("--read-only-server-config=readonly.xml"), "standalone.xml"));
    }
}
