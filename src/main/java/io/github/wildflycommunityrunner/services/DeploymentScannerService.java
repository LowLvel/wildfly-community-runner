package io.github.wildflycommunityrunner.services;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import io.github.wildflycommunityrunner.model.ServerProfile;
import io.github.wildflycommunityrunner.util.WildFlyPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

public final class DeploymentScannerService {
    private static final List<String> MARKERS = List.of(
            ".dodeploy", ".isdeploying", ".deployed", ".failed", ".isundeploying", ".undeployed", ".pending", ".skipdeploy"
    );

    private DeploymentScannerService() {}

    public static void deploy(Project project,
                              ServerProfile profile,
                              Path artifact,
                              String deploymentName,
                              Consumer<String> output,
                              Consumer<Boolean> completion) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            boolean success = false;
            try {
                Path deployments = WildFlyPaths.deploymentsDir(profile);
                Files.createDirectories(deployments);
                String name = deploymentName == null || deploymentName.isBlank()
                        ? artifact.getFileName().toString()
                        : deploymentName.trim();
                Path target = deployments.resolve(name);
                Path deployed = deployments.resolve(name + ".deployed");
                long previousDeployedStamp = lastModified(deployed);

                out(output, "Hot redeploy: " + artifact + " -> " + target);
                Files.deleteIfExists(deployments.resolve(name + ".failed"));
                Files.deleteIfExists(deployments.resolve(name + ".undeployed"));
                replaceArtifactSafely(artifact, target);
                Files.writeString(deployments.resolve(name + ".dodeploy"), "", StandardCharsets.UTF_8);
                success = waitForDeployment(project, deployments, name, previousDeployedStamp, output);
            } catch (Exception e) {
                out(output, "ERROR: " + e.getMessage());
            } finally {
                if (completion != null) completion.accept(success);
            }
        });
    }

    public static void undeploy(Project project,
                                ServerProfile profile,
                                String deploymentName,
                                Consumer<String> output,
                                Consumer<Boolean> completion) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            boolean success = false;
            try {
                String name = safeDeploymentName(deploymentName);
                Path deployments = WildFlyPaths.deploymentsDir(profile);
                Path artifact = deployments.resolve(name);
                Path deployed = deployments.resolve(name + ".deployed");
                Path undeployed = deployments.resolve(name + ".undeployed");
                Path isUndeploying = deployments.resolve(name + ".isundeploying");

                boolean hadDeployedMarker = Files.exists(deployed);
                boolean hadAnyScannerState = hadDeployedMarker
                        || Files.exists(deployments.resolve(name + ".failed"))
                        || Files.exists(deployments.resolve(name + ".dodeploy"))
                        || Files.exists(deployments.resolve(name + ".isdeploying"))
                        || Files.exists(deployments.resolve(name + ".pending"))
                        || Files.exists(isUndeploying)
                        || Files.exists(undeployed);
                boolean hadArtifact = Files.exists(artifact);

                if (!hadAnyScannerState && !hadArtifact) {
                    out(output, name + " is already not deployed.");
                    success = true;
                } else {
                    out(output, "Undeploying " + name);

                    // Cancel any pending manual deployment request first.
                    Files.deleteIfExists(deployments.resolve(name + ".dodeploy"));

                    // WildFly's deployment-scanner command for an active deployment is removal
                    // of the .deployed marker. This works in manual scanner mode as well.
                    if (hadDeployedMarker) {
                        Files.deleteIfExists(undeployed);
                        Files.deleteIfExists(deployed);
                        waitForUndeployConfirmation(project, undeployed, isUndeploying, output, name);
                    }

                    // The deployments/ copy belongs to the plugin/scanner, not the source project.
                    // Remove it as part of the action so failed/pending/external deployments cannot
                    // remain as scanner candidates or be auto-deployed again later.
                    cleanupDeployment(profile, name);

                    boolean gone = !Files.exists(artifact) && !hasActiveMarker(deployments, name);
                    if (gone) {
                        out(output, "Undeployed and removed from deployments: " + name);
                        success = true;
                    } else {
                        out(output, "Undeploy cleanup is incomplete for " + name + ". Check the WildFly deployments directory.");
                    }
                }
            } catch (Exception e) {
                out(output, "ERROR: " + e.getMessage());
            } finally {
                if (completion != null) completion.accept(success);
            }
        });
    }

    private static void waitForUndeployConfirmation(Project project,
                                                     Path undeployed,
                                                     Path isUndeploying,
                                                     Consumer<String> output,
                                                     String deploymentName) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        boolean sawUndeploying = false;
        while (Instant.now().isBefore(deadline) && !project.isDisposed()) {
            if (Files.exists(undeployed)) {
                out(output, "WildFly confirmed undeploy: " + deploymentName);
                return;
            }
            if (Files.exists(isUndeploying)) sawUndeploying = true;
            if (sawUndeploying && !Files.exists(isUndeploying)) return;
            Thread.sleep(250);
        }
        out(output, "No .undeployed marker was observed before cleanup; removing scanner content anyway.");
    }

    private static boolean hasActiveMarker(Path deployments, String name) {
        return Files.exists(deployments.resolve(name + ".deployed"))
                || Files.exists(deployments.resolve(name + ".isdeploying"))
                || Files.exists(deployments.resolve(name + ".isundeploying"))
                || Files.exists(deployments.resolve(name + ".dodeploy"))
                || Files.exists(deployments.resolve(name + ".pending"))
                || Files.exists(deployments.resolve(name + ".failed"));
    }

    private static String safeDeploymentName(String deploymentName) {
        if (deploymentName == null || deploymentName.isBlank()) {
            throw new IllegalArgumentException("Deployment name is empty.");
        }
        String trimmed = deploymentName.trim();
        Path path = Path.of(trimmed);
        if (path.getNameCount() != 1 || !path.getFileName().toString().equals(trimmed)) {
            throw new IllegalArgumentException("Invalid deployment name: " + deploymentName);
        }
        return trimmed;
    }


    public static List<String> listDeployments(ServerProfile profile) {
        if (profile == null) return List.of();
        Path deployments;
        try {
            deployments = WildFlyPaths.deploymentsDir(profile);
        } catch (Exception e) {
            return List.of();
        }
        if (!Files.isDirectory(deployments)) return List.of();
        Set<String> names = new LinkedHashSet<>();
        try (var paths = Files.list(deployments)) {
            paths.forEach(path -> {
                String name = path.getFileName().toString();
                String lower = name.toLowerCase(Locale.ROOT);
                for (String marker : MARKERS) {
                    if (lower.endsWith(marker)) {
                        String base = name.substring(0, name.length() - marker.length());
                        if (isDeployableName(base)) names.add(base);
                        return;
                    }
                }
                if (isDeployableName(name)) names.add(name);
            });
        } catch (IOException ignored) {
            return List.of();
        }
        List<String> result = new ArrayList<>(names);
        result.sort(String.CASE_INSENSITIVE_ORDER);
        return result;
    }

    private static boolean isDeployableName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".war") || lower.endsWith(".ear") || lower.endsWith(".jar");
    }

    /** Current deployment state exposed to the UI. Keep this intentionally small and state-oriented. */
    public static String status(ServerProfile profile, String deploymentName) {
        if (profile == null || deploymentName == null || deploymentName.isBlank()) return "NOT DEPLOYED";
        Path deployments;
        try {
            deployments = WildFlyPaths.deploymentsDir(profile);
        } catch (Exception e) {
            return "NOT DEPLOYED";
        }
        if (Files.exists(deployments.resolve(deploymentName + ".failed"))) return "FAILED";
        if (Files.exists(deployments.resolve(deploymentName + ".deployed"))) return "DEPLOYED";
        if (Files.exists(deployments.resolve(deploymentName + ".isdeploying"))
                || Files.exists(deployments.resolve(deploymentName + ".pending"))
                || Files.exists(deployments.resolve(deploymentName + ".dodeploy"))) return "DEPLOYING";
        // .isundeploying/.undeployed are transitions/history toward the same current state: not deployed.
        return "NOT DEPLOYED";
    }

    /** Timestamp of the last successful deployment, based on WildFly's .deployed marker. */
    public static Instant lastDeployedAt(ServerProfile profile, String deploymentName) {
        if (profile == null || deploymentName == null || deploymentName.isBlank()) return null;
        try {
            Path marker = WildFlyPaths.deploymentsDir(profile).resolve(deploymentName + ".deployed");
            if (!Files.isRegularFile(marker)) return null;
            return Files.getLastModifiedTime(marker).toInstant();
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Re-trigger the deployment scanner for an artifact already present in standalone/deployments. */
    public static void redeployExisting(Project project,
                                        ServerProfile profile,
                                        String deploymentName,
                                        Consumer<String> output,
                                        Consumer<Boolean> completion) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            boolean success = false;
            try {
                Path deployments = WildFlyPaths.deploymentsDir(profile);
                Path artifact = deployments.resolve(deploymentName);
                if (!Files.isRegularFile(artifact)) throw new IOException("Deployment artifact not found: " + artifact);
                Path deployed = deployments.resolve(deploymentName + ".deployed");
                long previousDeployedStamp = lastModified(deployed);
                Files.deleteIfExists(deployments.resolve(deploymentName + ".failed"));
                Files.deleteIfExists(deployments.resolve(deploymentName + ".undeployed"));
                Files.writeString(deployments.resolve(deploymentName + ".dodeploy"), "", StandardCharsets.UTF_8);
                out(output, "Redeploy requested for existing deployment: " + deploymentName);
                success = waitForDeployment(project, deployments, deploymentName, previousDeployedStamp, output);
            } catch (Exception e) {
                out(output, "ERROR: " + e.getMessage());
            } finally {
                if (completion != null) completion.accept(success);
            }
        });
    }

    public static void cleanupDeployment(ServerProfile profile, String deploymentName) throws IOException {
        Path deployments = WildFlyPaths.deploymentsDir(profile);
        Files.deleteIfExists(deployments.resolve(deploymentName));
        for (String marker : MARKERS) Files.deleteIfExists(deployments.resolve(deploymentName + marker));
        Files.deleteIfExists(deployments.resolve(deploymentName + ".undeploy"));
    }

    private static void replaceArtifactSafely(Path source, Path target) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + ".uploading");
        Files.deleteIfExists(temp);
        try {
            Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static boolean waitForDeployment(Project project, Path deployments, String name, long previousDeployedStamp, Consumer<String> output) throws Exception {
        Path deployed = deployments.resolve(name + ".deployed");
        Path failed = deployments.resolve(name + ".failed");
        Path deploying = deployments.resolve(name + ".isdeploying");
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        boolean sawDeploying = false;

        while (Instant.now().isBefore(deadline) && !project.isDisposed()) {
            if (Files.exists(deploying)) sawDeploying = true;
            if (Files.exists(failed)) {
                String details = "";
                try { details = Files.readString(failed); } catch (IOException ignored) {}
                out(output, "DEPLOYMENT FAILED: " + name + (details.isBlank() ? "" : "\n" + details));
                return false;
            }
            if (Files.exists(deployed)) {
                long stamp = lastModified(deployed);
                if (previousDeployedStamp == 0L || stamp > previousDeployedStamp || (sawDeploying && !Files.exists(deploying))) {
                    out(output, "Deployment successful: " + name);
                    return true;
                }
            }
            Thread.sleep(300);
        }
        out(output, "Deployment requested; no fresh deployment confirmation was observed within 60 seconds.");
        return false;
    }

    private static long lastModified(Path path) {
        try { return Files.exists(path) ? Files.getLastModifiedTime(path).toMillis() : 0L; }
        catch (IOException e) { return 0L; }
    }

    private static void out(Consumer<String> output, String message) {
        output.accept(message);
    }
}
