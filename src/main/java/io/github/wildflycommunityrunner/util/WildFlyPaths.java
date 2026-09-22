package io.github.wildflycommunityrunner.util;

import io.github.wildflycommunityrunner.model.ServerProfile;

import java.nio.file.Files;
import java.nio.file.Path;

public final class WildFlyPaths {
    private WildFlyPaths() {}

    public static Path home(ServerProfile profile) {
        return Path.of(profile.home).toAbsolutePath().normalize();
    }

    public static Path standaloneDir(ServerProfile profile) {
        return home(profile).resolve("standalone");
    }

    public static Path deploymentsDir(ServerProfile profile) {
        return standaloneDir(profile).resolve("deployments");
    }

    public static Path logFile(ServerProfile profile) {
        return standaloneDir(profile).resolve("log").resolve("server.log");
    }

    public static Path configurationFile(ServerProfile profile) {
        return standaloneDir(profile).resolve("configuration").resolve(profile.configuration);
    }

    public static Path startupScript(ServerProfile profile) {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        return home(profile).resolve("bin").resolve(windows ? "standalone.bat" : "standalone.sh");
    }

    public static String validate(ServerProfile profile) {
        if (profile.name == null || profile.name.isBlank()) return "Server name is required.";
        if (profile.home == null || profile.home.isBlank()) return "WildFly Home is required.";
        Path home = home(profile);
        if (!Files.isDirectory(home)) return "WildFly Home does not exist: " + home;
        if (!Files.isRegularFile(startupScript(profile))) return "Cannot find WildFly startup script under: " + home.resolve("bin");
        if (!Files.isDirectory(standaloneDir(profile))) return "Cannot find standalone directory under: " + home;
        if (profile.configuration == null || profile.configuration.isBlank()) return "Configuration file is required.";
        if (!Files.isRegularFile(configurationFile(profile))) return "Cannot find configuration: " + configurationFile(profile);
        return null;
    }
}
