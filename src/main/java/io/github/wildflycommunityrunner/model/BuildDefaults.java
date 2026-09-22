package io.github.wildflycommunityrunner.model;

/** Switch only recognizable defaults; custom tasks, arguments and JVM options stay intact. */
public final class BuildDefaults {
    private BuildDefaults() {}

    public static void changeSystem(ServiceProfile profile, BuildSystem system) {
        BuildSystem previous = profile.buildSystemEnum();
        if (previous == system) return;
        String previousTasks = previous == BuildSystem.MAVEN ? "clean package" : "clean build";
        String previousArguments = previous == BuildSystem.MAVEN ? "-DskipTests" : "-x test";
        if (profile.buildTasks == null || profile.buildTasks.isBlank() || previousTasks.equals(profile.buildTasks.trim())) {
            profile.buildTasks = system == BuildSystem.MAVEN ? "clean package" : "clean build";
        }
        if (previousArguments.equals(profile.buildArguments == null ? "" : profile.buildArguments.trim())) {
            profile.buildArguments = system == BuildSystem.MAVEN ? "-DskipTests" : "-x test";
        }
        profile.buildSystem = system.name();
    }
}
