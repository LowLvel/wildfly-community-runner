package io.github.wildflycommunityrunner.model;

public enum BuildSystem {
    MAVEN("Maven"),
    GRADLE("Gradle");

    private final String label;

    BuildSystem(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }

    public static BuildSystem from(String value) {
        if (value == null) return MAVEN;
        try {
            return BuildSystem.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return MAVEN;
        }
    }
}
