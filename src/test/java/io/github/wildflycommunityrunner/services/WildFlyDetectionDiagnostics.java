package io.github.wildflycommunityrunner.services;

import io.github.wildflycommunityrunner.model.ServerProfile;
import io.github.wildflycommunityrunner.util.WildFlyPaths;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;

/** Bounded diagnostics for the real distribution fixture; never logs unrelated processes. */
public final class WildFlyDetectionDiagnostics {
    private WildFlyDetectionDiagnostics() {}

    public static String awaitMatch(ServerProfile server) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        do {
            if (WildFlyServerDetector.matchingProcesses(server).size() == 1) return null;
            Thread.sleep(200);
        } while (System.nanoTime() < deadline);
        StringBuilder details = new StringBuilder("WildFly process identity did not match ")
                .append(WildFlyPaths.identity(server));
        try (var descendants = ProcessHandle.current().descendants()) {
            for (var child : descendants.toList()) {
                var info = child.info();
                details.append("\nChild ").append(child.pid()).append(" start=").append(info.startInstant())
                        .append(" executable=").append(info.command()).append(" arguments=")
                        .append(Arrays.toString(info.arguments().orElse(new String[0])));
                if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
                    WindowsProcessQuery.read(true).stream().filter(row -> row.pid() == child.pid()).forEach(row ->
                            details.append("\nCIM ").append(row).append(" argumentsMatch=")
                                    .append(WildFlyServerDetector.matches(server, row.executable(), row.arguments())));
                }
            }
        }
        return WildFlyProcessService.getInstance().redact(server, details.toString());
    }
}
