package io.github.wildflycommunityrunner.model;

import java.util.Objects;
import java.util.UUID;

public class ServiceProfile {
    public String id = UUID.randomUUID().toString();
    /** Legacy v0.4.x bulk-inclusion flag. Retained only for non-destructive settings migration. */
    public boolean tracked = true;
    public String name = "Service";

    // Generic build configuration.
    public String buildSystem = BuildSystem.MAVEN.name();
    public String buildFilePath = "";
    public String buildTasks = "clean package";
    public String buildArguments = "-DskipTests";
    public String buildJvmOptions = "";

    public String packaging = "war";
    public String artifactPath = "";
    public String deploymentName = "";
    public String contextPath = "";
    /** Watch the final WAR/EAR/JAR and redeploy whenever the built artifact changes. */
    public boolean deployAfterBuild = true;

    // Legacy fields retained for non-destructive upgrades from v0.3.x.
    public String pomPath = "";
    public String mavenGoals = "";
    public String mavenArguments = "";
    public String mavenJvmOptions = "";

    public ServiceProfile() {}

    public ServiceProfile(ServiceProfile other) {
        this.id = other.id;
        this.tracked = other.tracked;
        this.name = other.name;
        this.buildSystem = other.buildSystem;
        this.buildFilePath = other.buildFilePath;
        this.buildTasks = other.buildTasks;
        this.buildArguments = other.buildArguments;
        this.buildJvmOptions = other.buildJvmOptions;
        this.packaging = other.packaging;
        this.artifactPath = other.artifactPath;
        this.deploymentName = other.deploymentName;
        this.contextPath = other.contextPath;
        this.deployAfterBuild = other.deployAfterBuild;
        this.pomPath = other.pomPath;
        this.mavenGoals = other.mavenGoals;
        this.mavenArguments = other.mavenArguments;
        this.mavenJvmOptions = other.mavenJvmOptions;
    }

    public BuildSystem buildSystemEnum() {
        return BuildSystem.from(buildSystem);
    }

    public void migrateLegacyFields() {
        if ((buildFilePath == null || buildFilePath.isBlank()) && pomPath != null && !pomPath.isBlank()) {
            buildFilePath = pomPath;
            buildSystem = BuildSystem.MAVEN.name();
        }
        if (mavenGoals != null && !mavenGoals.isBlank()
                && (buildTasks == null || buildTasks.isBlank() || "clean package".equals(buildTasks))) {
            buildTasks = mavenGoals;
        } else if (buildTasks == null || buildTasks.isBlank()) {
            buildTasks = defaultTasks();
        }
        if (mavenArguments != null && !mavenArguments.isBlank()
                && (buildArguments == null || buildArguments.isBlank() || "-DskipTests".equals(buildArguments))) {
            buildArguments = mavenArguments;
        }
        if ((buildJvmOptions == null || buildJvmOptions.isBlank()) && mavenJvmOptions != null && !mavenJvmOptions.isBlank()) {
            buildJvmOptions = mavenJvmOptions;
        }
        if (buildSystem == null || buildSystem.isBlank()) buildSystem = BuildSystem.MAVEN.name();
    }

    public String defaultTasks() {
        return buildSystemEnum() == BuildSystem.GRADLE ? "clean build" : "clean package";
    }

    @Override
    public String toString() {
        return name;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ServiceProfile that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
