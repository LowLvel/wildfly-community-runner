package io.github.wildflycommunityrunner.util;

import com.intellij.openapi.project.Project;
import io.github.wildflycommunityrunner.model.BuildSystem;
import io.github.wildflycommunityrunner.model.ServiceProfile;
import io.github.wildflycommunityrunner.services.BuildService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

public final class ArtifactLocator {
    private static final Set<String> SUPPORTED = Set.of("war", "ear", "jar");

    private ArtifactLocator() {}

    public static Path resolve(Project project, ServiceProfile service) throws IOException {
        return resolveInModule(BuildService.resolveModuleDir(project, service), service);
    }

    public static Path resolveInModule(Path moduleDir, ServiceProfile service) throws IOException {

        if (service.artifactPath != null && !service.artifactPath.isBlank()) {
            Path configured = Path.of(service.artifactPath);
            if (!configured.isAbsolute()) configured = moduleDir.resolve(configured);
            configured = configured.normalize();
            validateArtifact(configured);
            return configured;
        }

        List<Path> outputDirs = service.buildSystemEnum() == BuildSystem.GRADLE
                ? List.of(moduleDir.resolve("build").resolve("libs"), moduleDir.resolve("build"))
                : List.of(moduleDir.resolve("target"));

        String expected = normalizePackaging(service.packaging);
        for (Path outputDir : outputDirs) {
            Path artifact = newestDeployable(outputDir, expected);
            if (artifact != null) return artifact;
        }

        String expectedLocation = service.buildSystemEnum() == BuildSystem.GRADLE
                ? moduleDir.resolve("build/libs").toString()
                : moduleDir.resolve("target").toString();
        throw new IOException("No deployable artifact found for " + service.name + " under " + expectedLocation
                + ". Build the service first or set Artifact override.");
    }

    public static String effectiveDeploymentName(ServiceProfile service, Path artifact) {
        if (service.deploymentName != null && !service.deploymentName.isBlank()) return service.deploymentName.trim();
        String extension = extension(artifact.getFileName().toString());
        String safeName = service.name == null || service.name.isBlank() ? "service" : service.name.replaceAll("[^A-Za-z0-9._-]", "-");
        return safeName + "." + (extension.isBlank() ? "war" : extension);
    }

    private static Path newestDeployable(Path dir, String expectedPackaging) throws IOException {
        if (!Files.isDirectory(dir)) return null;
        try (Stream<Path> paths = Files.list(dir)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(p -> isDeployable(p, expectedPackaging))
                    .filter(p -> !p.getFileName().toString().startsWith("original-"))
                    .filter(p -> !p.getFileName().toString().endsWith("-plain.jar"))
                    .max(Comparator.comparingLong(ArtifactLocator::lastModified))
                    .orElse(null);
        }
    }

    private static void validateArtifact(Path path) throws IOException {
        if (!Files.isRegularFile(path)) throw new IOException("Artifact not found: " + path);
        if (!SUPPORTED.contains(extension(path.getFileName().toString()))) {
            throw new IOException("Supported deployment artifacts are .war, .ear and .jar: " + path);
        }
    }

    private static boolean isDeployable(Path p, String expectedPackaging) {
        String ext = extension(p.getFileName().toString());
        if (!SUPPORTED.contains(ext)) return false;
        return expectedPackaging.isBlank() || "auto".equals(expectedPackaging) || !SUPPORTED.contains(expectedPackaging) || expectedPackaging.equals(ext);
    }

    private static String normalizePackaging(String packaging) {
        return packaging == null ? "" : packaging.trim().toLowerCase(Locale.ROOT);
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static long lastModified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }
}
