package io.github.wildflycommunityrunner.services;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import io.github.wildflycommunityrunner.model.ServerProfile;
import io.github.wildflycommunityrunner.util.WildFlyPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.LinkOption;
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
        deploy(project, profile, artifact, deploymentName, output, completion, null);
    }

    static void deploy(Project project, ServerProfile profile, Path artifact, String deploymentName,
                       Consumer<String> output, Consumer<Boolean> completion, ArtifactFingerprint expected) {
        ServerProfile snapshot = new ServerProfile(profile);
        submitTarget(project, snapshot, deploymentName == null || deploymentName.isBlank() ? artifact.getFileName().toString() : deploymentName,
                "Deployment failed", output, completion, () -> {
            boolean success = false;
            Path deployments = WildFlyPaths.deploymentsDir(snapshot);
            Files.createDirectories(deployments);
            String name = safeDeploymentName(deploymentName == null || deploymentName.isBlank()
                    ? artifact.getFileName().toString() : deploymentName);
            Path target = deployments.resolve(name);
            Path deployed = deployments.resolve(name + ".deployed");
            long previousDeployedStamp = lastModified(deployed);

            out(output, "Hot redeploy: " + artifact + " -> " + target);
            Files.deleteIfExists(deployments.resolve(name + ".failed"));
            Files.deleteIfExists(deployments.resolve(name + ".undeployed"));
            replaceArtifactSafely(artifact, target, expected);
            createRequestMarker(deployments.resolve(name + ".dodeploy"));
            success = waitForDeployment(project, deployments, name, previousDeployedStamp, output);
            return success;
        });
    }

    public static void undeploy(Project project,
                                ServerProfile profile,
                                String deploymentName,
                                Consumer<String> output,
                                Consumer<Boolean> completion) {
        ServerProfile snapshot = new ServerProfile(profile);
        submitTarget(project, snapshot, deploymentName, "Undeploy failed", output, completion, () -> {
            boolean success = false;
            String name = safeDeploymentName(deploymentName);
            Path deployments = WildFlyPaths.deploymentsDir(snapshot);
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
                cleanupDeployment(snapshot, name);

                boolean gone = !Files.exists(artifact) && !hasActiveMarker(deployments, name);
                if (gone) {
                    out(output, "Undeployed and removed from deployments: " + name);
                    success = true;
                } else {
                    throw new IOException("Undeploy cleanup is incomplete for " + name + ". Check the WildFly deployments directory.");
                }
            }
            return success;
        });
    }

    private static void waitForUndeployConfirmation(Project project,
                                                     Path undeployed,
                                                     Path isUndeploying,
                                                     Consumer<String> output,
                                                     String deploymentName) throws InterruptedException, IOException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        boolean sawUndeploying = false;
        while (Instant.now().isBefore(deadline) && !project.isDisposed()) {
            ProgressManager.checkCanceled();
            if (Files.exists(undeployed)) {
                out(output, "WildFly confirmed undeploy: " + deploymentName);
                return;
            }
            if (Files.exists(isUndeploying)) sawUndeploying = true;
            if (sawUndeploying && !Files.exists(isUndeploying)) return;
            Thread.sleep(250);
        }
        if (project.isDisposed()) throw new ProcessCanceledException();
        throw new IOException("WildFly did not confirm undeploy of " + deploymentName + " within 30 seconds. Scanner content was kept; check server.log and retry.");
    }

    private static boolean hasActiveMarker(Path deployments, String name) {
        return Files.exists(deployments.resolve(name + ".deployed"))
                || Files.exists(deployments.resolve(name + ".isdeploying"))
                || Files.exists(deployments.resolve(name + ".isundeploying"))
                || Files.exists(deployments.resolve(name + ".dodeploy"))
                || Files.exists(deployments.resolve(name + ".pending"))
                || Files.exists(deployments.resolve(name + ".failed"));
    }

    public static String safeDeploymentName(String deploymentName) {
        if (deploymentName == null || deploymentName.isBlank()) {
            throw new IllegalArgumentException("Deployment name is empty.");
        }
        String trimmed = deploymentName.trim();
        Path path = Path.of(trimmed);
        String base = trimmed.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
        if (trimmed.equals(".") || trimmed.equals("..") || trimmed.endsWith(".")
                || trimmed.chars().anyMatch(c -> c < 32 || "<>:\"/\\|?*".indexOf(c) >= 0)
                || base.matches("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]")
                || path.isAbsolute() || path.getNameCount() != 1 || !path.getFileName().toString().equals(trimmed)) {
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
            deploymentName = safeDeploymentName(deploymentName);
            deployments = WildFlyPaths.deploymentsDir(profile);
        } catch (Exception e) {
            return "NOT DEPLOYED";
        }
        if (Files.exists(deployments.resolve(deploymentName + ".failed"))) return "FAILED";
        if (Files.exists(deployments.resolve(deploymentName + ".isdeploying"))
                || Files.exists(deployments.resolve(deploymentName + ".pending"))
                || Files.exists(deployments.resolve(deploymentName + ".dodeploy"))) return "DEPLOYING";
        if (Files.exists(deployments.resolve(deploymentName + ".isundeploying"))) return "NOT DEPLOYED";
        if (Files.exists(deployments.resolve(deploymentName + ".deployed"))) return "DEPLOYED";
        // .isundeploying/.undeployed are transitions/history toward the same current state: not deployed.
        return "NOT DEPLOYED";
    }

    /** Timestamp of the last successful deployment, based on WildFly's .deployed marker. */
    public static Instant lastDeployedAt(ServerProfile profile, String deploymentName) {
        if (profile == null || deploymentName == null || deploymentName.isBlank()) return null;
        try {
            Path marker = WildFlyPaths.deploymentsDir(profile).resolve(safeDeploymentName(deploymentName) + ".deployed");
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
        ServerProfile snapshot = new ServerProfile(profile);
        submitTarget(project, snapshot, deploymentName, "Redeploy failed", output, completion, () -> {
            boolean success = false;
            String name = safeDeploymentName(deploymentName);
            Path deployments = WildFlyPaths.deploymentsDir(snapshot);
            Path artifact = deployments.resolve(name);
            if (!Files.isRegularFile(artifact)) throw new IOException("Deployment artifact not found: " + artifact);
            Path deployed = deployments.resolve(name + ".deployed");
            long previousDeployedStamp = lastModified(deployed);
            Files.deleteIfExists(deployments.resolve(name + ".failed"));
            Files.deleteIfExists(deployments.resolve(name + ".undeployed"));
            createRequestMarker(deployments.resolve(name + ".dodeploy"));
            out(output, "Redeploy requested for existing deployment: " + deploymentName);
            success = waitForDeployment(project, deployments, name, previousDeployedStamp, output);
            return success;
        });
    }

    public static void cleanupDeployment(ServerProfile profile, String deploymentName) throws IOException {
        deploymentName = safeDeploymentName(deploymentName);
        Path deployments = WildFlyPaths.deploymentsDir(profile);
        Files.deleteIfExists(deployments.resolve(deploymentName));
        for (String marker : MARKERS) Files.deleteIfExists(deployments.resolve(deploymentName + marker));
        Files.deleteIfExists(deployments.resolve(deploymentName + ".undeploy"));
    }

    static void replaceArtifactSafely(Path source, Path target) throws IOException {
        replaceArtifactSafely(source, target, null);
    }

    static final class ArtifactChangedException extends IOException {
        ArtifactChangedException() { super("Artifact changed during deployment preparation"); }
    }

    static void replaceArtifactSafely(Path source, Path target, ArtifactFingerprint expected) throws IOException {
        Path temp = Files.createTempFile(target.getParent(), ".wildfly-upload-", ".uploading");
        try {
            try (var input = Files.newInputStream(source);
                 var output = Files.newOutputStream(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    ProgressManager.checkCanceled();
                    if (Thread.currentThread().isInterrupted()) throw new ProcessCanceledException();
                    output.write(buffer, 0, count);
                }
            }
            if (expected != null) {
                try {
                    if (!expected.sha256().equals(ArtifactFingerprint.read(temp).sha256())) throw new ArtifactChangedException();
                } catch (java.util.zip.ZipException invalid) { throw new ArtifactChangedException(); }
            }
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
            ProgressManager.checkCanceled();
            if (Files.exists(deploying)) sawDeploying = true;
            if (Files.exists(failed)) {
                String details = "";
                try (var input = Files.newInputStream(failed, LinkOption.NOFOLLOW_LINKS)) {
                    details = new String(input.readNBytes(8192), StandardCharsets.UTF_8);
                } catch (IOException ignored) {}
                throw new IOException(name + " failed to deploy. Check server.log." + (details.isBlank() ? "" : "\n" + details));
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
        if (project.isDisposed()) throw new ProcessCanceledException();
        throw new IOException("No fresh deployment confirmation for " + name + " within 60 seconds. Check that WildFly and its deployment scanner are running, then inspect server.log.");
    }

    private static long lastModified(Path path) {
        try { return Files.exists(path) ? Files.getLastModifiedTime(path).toMillis() : 0L; }
        catch (IOException e) { return 0L; }
    }

    static void createRequestMarker(Path marker) throws IOException {
        Files.writeString(marker, "", StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    }

    @FunctionalInterface interface ScannerOperation { boolean run() throws Exception; }

    private static void submitTarget(Project project, ServerProfile profile, String deploymentName, String operation,
                                     Consumer<String> output, Consumer<Boolean> completion, ScannerOperation action) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> complete(project, operation, output, completion, () -> {
            Path target = WildFlyPaths.deploymentsDir(profile).resolve(safeDeploymentName(deploymentName));
            return DeploymentCoordinator.getInstance().withTarget(project, target, action::run);
        }));
    }

    // Single completion boundary for success, failure, cancellation, and project shutdown.
    static void complete(Project project, String operation, Consumer<String> output,
                         Consumer<Boolean> completion, ScannerOperation action) {
        boolean success = false;
        try {
            if (!project.isDisposed()) success = action.run();
        } catch (ArtifactChangedException changed) {
            out(output, "Auto Redeploy is waiting for the newer artifact to finish writing.");
        } catch (Exception error) {
            PluginNotifications.failure(project, operation, error, output);
        } finally {
            if (completion != null) completion.accept(success);
        }
    }

    private static void out(Consumer<String> output, String message) {
        if (output != null) output.accept(message);
    }
}
