package io.github.wildflycommunityrunner.util;

import io.github.wildflycommunityrunner.model.ServerProfile;
import io.github.wildflycommunityrunner.services.WildFlyServerDetector;
import java.net.URI;
import java.util.Locale;

public final class BrowserUrls {
    private BrowserUrls() {}
    public static void validateOverride(String value) {
        if (value == null || value.isBlank()) return;
        try {
            URI uri = URI.create(value.trim());
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getPort() > 65535 || uri.getPort() == 0)
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Browser URL must be a full HTTP or HTTPS URL without embedded credentials.");
        }
    }
    public static String resolve(ServerProfile server, String deploymentName, String contextPath, String override) {
        validateOverride(override);
        if (override != null && !override.isBlank()) return override.trim();
        String context = contextPath == null ? "" : contextPath.trim();
        if (context.isBlank()) {
            if (deploymentName == null || !deploymentName.toLowerCase(Locale.ROOT).endsWith(".war"))
                throw new IllegalArgumentException("Set Browser URL or Browser context path for this EAR/JAR application.");
            context = deploymentName.substring(0, deploymentName.length() - 4);
        }
        if (context.equalsIgnoreCase("ROOT")) context = "";
        context = context.replaceAll("^/+|/+$", "");
        String host = WildFlyServerDetector.connectionHost(server.host);
        try {
            return new URI("http", null, host, server.httpPort, context.isBlank() ? "/" : "/" + context + "/", null, null).toASCIIString();
        } catch (java.net.URISyntaxException invalid) { throw new IllegalArgumentException("Invalid browser host or context path."); }
    }
}
