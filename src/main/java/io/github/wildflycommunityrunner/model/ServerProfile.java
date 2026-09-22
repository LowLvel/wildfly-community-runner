package io.github.wildflycommunityrunner.model;

import java.util.Objects;
import java.util.UUID;

public class ServerProfile {
    public String id = UUID.randomUUID().toString();
    public String name = "WildFly";
    public String home = "";
    public String configuration = "standalone.xml";
    public String javaHome = "";
    public String host = "localhost";
    public int httpPort = 8080;
    public int debugPort = 8787;
    public String startupArguments = "";
    public String jvmOptions = "";
    public String scannerName = "default";
    public int deploymentTimeoutSeconds = 120;
    public int startupTimeoutSeconds = 120;

    public ServerProfile() {}

    public ServerProfile(ServerProfile other) {
        this.id = other.id;
        this.name = other.name;
        this.home = other.home;
        this.configuration = other.configuration;
        this.javaHome = other.javaHome;
        this.host = other.host;
        this.httpPort = other.httpPort;
        this.debugPort = other.debugPort;
        this.startupArguments = other.startupArguments;
        this.jvmOptions = other.jvmOptions;
        this.scannerName = other.scannerName;
        this.deploymentTimeoutSeconds = other.deploymentTimeoutSeconds;
        this.startupTimeoutSeconds = other.startupTimeoutSeconds;
    }

    @Override
    public String toString() {
        return name + (home == null || home.isBlank() ? "" : "  —  " + home);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ServerProfile that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
