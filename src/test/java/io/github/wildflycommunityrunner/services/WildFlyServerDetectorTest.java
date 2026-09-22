package io.github.wildflycommunityrunner.services;

import io.github.wildflycommunityrunner.model.ServerProfile;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class WildFlyServerDetectorTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private ServerProfile profile() throws Exception {
        var profile = new ServerProfile();
        profile.home = temp.newFolder("wildfly home").getAbsolutePath();
        return profile;
    }

    private static List<String> arguments(ServerProfile profile) {
        return new ArrayList<>(List.of("-Xmx1g", "-jar", Path.of(profile.home).resolve("jboss-modules.jar").toString(),
                "-mp", Path.of(profile.home).resolve("modules").toString(), "org.jboss.as.standalone",
                "-Djboss.home.dir=" + profile.home, "-c", profile.configuration));
    }

    @Test public void exactHomeAndStandaloneEntryAreRequiredEvenWithSimilarDirectoryNames() throws Exception {
        var profile = profile();
        assertTrue(WildFlyServerDetector.matches(profile, "java", arguments(profile)));
        var other = new ServerProfile(profile);
        other.home += "-other";
        var arguments = arguments(other);
        arguments.add("-Dsome.unrelated.property=" + profile.home);
        assertFalse(WildFlyServerDetector.matches(profile, "java", arguments));
        assertFalse(WildFlyServerDetector.matches(profile, "not-java", arguments(profile)));
    }

    @Test public void distinguishesConfigurationsSharingTheSameServerBase() throws Exception {
        var profile = profile();
        var other = new ServerProfile(profile);
        other.configuration = "standalone-full.xml";
        assertFalse(WildFlyServerDetector.matches(profile, "java", arguments(other)));
        assertTrue(WildFlyServerDetector.matches(profile, "java", arguments(other), false));
        profile.startupArguments = "--server-config=standalone-full.xml";
        assertTrue(WildFlyServerDetector.matches(profile, "java", arguments(other)));
    }

    @Test public void honorsExplicitServerBaseAndConfigurationDirectory() throws Exception {
        var profile = profile();
        Path base = temp.newFolder("server instance").toPath();
        Path config = temp.newFolder("server config").toPath();
        profile.jvmOptions = "-Djboss.server.base.dir=\"" + base + "\" -Djboss.server.config.dir=\"" + config + "\"";
        var arguments = arguments(profile);
        assertFalse(WildFlyServerDetector.matches(profile, "java", arguments));
        arguments.add("-Djboss.server.base.dir=" + base);
        arguments.add("-Djboss.server.config.dir=" + config);
        assertTrue(WildFlyServerDetector.matches(profile, "java", arguments));
    }

    @Test public void supportsModulesMainAndReadOnlyConfigurationSyntax() throws Exception {
        var profile = profile();
        profile.configuration = "standalone-full.xml";
        var arguments = List.of("-classpath", "modules.jar", "org.jboss.modules.Main", "-mp", "modules",
                "org.jboss.as.standalone", "-Djboss.home.dir=" + profile.home, "--read-only-server-config=standalone-full.xml");
        assertTrue(WildFlyServerDetector.matches(profile, "java", arguments));
    }

    @Test public void recognizesTheDistributionSerialFilterButNotArbitraryArgumentFiles() throws Exception {
        var profile = profile(); var arguments = arguments(profile);
        arguments.addFirst("@" + Path.of(profile.home, "bin", "jdk.serialFilter"));
        assertTrue(WildFlyServerDetector.matches(profile, "java", arguments));
        arguments.set(0, "@" + Path.of(profile.home, "other.args"));
        assertFalse(WildFlyServerDetector.matches(profile, "java", arguments));
    }

    @Test public void absentMalformedOrUnrelatedProcessArgumentsNeverMatch() throws Exception {
        var profile = profile();
        assertFalse(WildFlyServerDetector.matches(profile, "java", List.of()));
        assertFalse(WildFlyServerDetector.matches(profile, "java", List.of("-jar")));
        assertFalse(WildFlyServerDetector.matches(profile, "java", List.of("-jar", Path.of(profile.home).getRoot().toString())));
        assertFalse(WildFlyServerDetector.matches(profile, "java", List.of("SomeOtherApplication", "org.jboss.as.standalone", "-Djboss.home.dir=" + profile.home)));
        var arguments = arguments(profile);
        arguments.set(arguments.indexOf("org.jboss.as.standalone"), "org.jboss.as.process-controller");
        assertFalse(WildFlyServerDetector.matches(profile, "java", arguments));
        arguments = arguments(profile);
        arguments.set(2, temp.getRoot().toPath().resolve("jboss-modules.jar").toString());
        assertFalse(WildFlyServerDetector.matches(profile, "java", arguments));
    }

    @Test public void normalizesWildcardAndIpv6HostsAndNeverOffersRemoteProcessStop() throws Exception {
        assertEquals("localhost", WildFlyServerDetector.connectionHost("[::]"));
        assertEquals("::1", WildFlyServerDetector.connectionHost("[::1]"));
        assertEquals("localhost", WildFlyServerDetector.connectionHost("0.0.0.0"));
        var profile = profile();
        profile.host = "203.0.113.1";
        assertTrue(WildFlyServerDetector.matchingProcesses(profile).isEmpty());
    }
}
