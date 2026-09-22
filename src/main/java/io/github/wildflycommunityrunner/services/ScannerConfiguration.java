package io.github.wildflycommunityrunner.services;

import io.github.wildflycommunityrunner.model.ServerProfile;
import io.github.wildflycommunityrunner.util.SafeXml;
import io.github.wildflycommunityrunner.util.WildFlyPaths;
import org.w3c.dom.Element;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Reads the selected local XML on workers; never changes server configuration. */
public final class ScannerConfiguration {
    public record Scanner(Path directory, boolean enabled) {}
    private ScannerConfiguration() {}
    public static Scanner read(ServerProfile profile) {
        try {
            var document = SafeXml.read(WildFlyPaths.configurationFile(profile));
            var nodes = document.getElementsByTagNameNS("*", "deployment-scanner");
            Element selected = null;
            String name = profile.scannerName == null || profile.scannerName.isBlank() ? "default" : profile.scannerName;
            for (int i = 0; i < nodes.getLength(); i++) {
                var candidate = (Element) nodes.item(i);
                if (name.equals(candidate.getAttribute("name")) || name.equals("default") && candidate.getAttribute("name").isBlank()) {
                    if (selected != null) throw new IllegalArgumentException("More than one deployment scanner matches " + name);
                    selected = candidate;
                }
            }
            if (selected == null) throw new IllegalArgumentException("Deployment scanner '" + name + "' is absent from the selected configuration.");
            Map<String, String> properties = new HashMap<>(WildFlyPaths.properties(WildFlyPaths.profileArguments(profile)));
            properties.putIfAbsent("jboss.home.dir", WildFlyPaths.home(profile).toString());
            properties.putIfAbsent("jboss.server.base.dir", WildFlyPaths.standaloneDir(profile).toString());
            properties.putIfAbsent("jboss.server.config.dir", WildFlyPaths.configurationDir(profile).toString());
            String enabled = resolve(selected.getAttribute("scan-enabled"), properties);
            if (!enabled.isBlank() && !enabled.equalsIgnoreCase("true") && !enabled.equalsIgnoreCase("false"))
                throw new IllegalArgumentException("Cannot determine whether the deployment scanner is enabled.");
            Map<String, Element> paths = new HashMap<>();
            var pathNodes = document.getElementsByTagNameNS("*", "path");
            for (int i = 0; i < pathNodes.getLength(); i++) {
                var node = (Element) pathNodes.item(i);
                if (node.getParentNode() instanceof Element parent && "paths".equals(parent.getLocalName())) paths.put(node.getAttribute("name"), node);
            }
            String value = resolve(selected.getAttribute("path"), properties);
            if (value.isBlank()) throw new IllegalArgumentException("The deployment scanner has no path.");
            Path directory = Path.of(value);
            if (!directory.isAbsolute()) directory = base(selected.getAttribute("relative-to"), paths, properties, profile, 0).resolve(directory);
            return new Scanner(directory.toAbsolutePath().normalize(), !"false".equalsIgnoreCase(enabled));
        } catch (IllegalArgumentException error) { throw error; }
        catch (Exception error) { throw new IllegalArgumentException("Cannot read deployment scanner configuration: " + error.getMessage(), error); }
    }
    public static Path directory(ServerProfile profile) {
        // Status and path previews remain useful before an installation has been configured.
        if (!Files.isRegularFile(WildFlyPaths.configurationFile(profile))) return WildFlyPaths.standaloneDir(profile).resolve("deployments");
        return read(profile).directory();
    }
    public static void requireEnabled(ServerProfile profile) {
        if (!read(profile).enabled()) throw new IllegalArgumentException("Deployment scanner is disabled. Enable it in WildFly before deploying; no archive was copied.");
    }
    private static Path base(String name, Map<String, Element> paths, Map<String, String> properties, ServerProfile profile, int depth) {
        if (depth > 12) throw new IllegalArgumentException("Circular or excessively nested scanner path.");
        if (name.isBlank()) return WildFlyPaths.home(profile).resolve("bin");
        if (properties.containsKey(name)) return Path.of(resolve(properties.get(name), properties));
        Element path = paths.get(name);
        if (path == null) throw new IllegalArgumentException("Cannot resolve scanner path '" + name + "'.");
        Path value = Path.of(resolve(path.getAttribute("path"), properties));
        return value.isAbsolute() ? value : base(path.getAttribute("relative-to"), paths, properties, profile, depth + 1).resolve(value);
    }
    private static String resolve(String value, Map<String, String> properties) {
        for (int count = 0; count < 12 && value.contains("${"); count++) {
            var matcher = Pattern.compile("\\$\\{([^}:]+)(?::([^}]*))?}").matcher(value);
            StringBuffer result = new StringBuffer();
            while (matcher.find()) {
                String key = matcher.group(1);
                String replacement = properties.get(key);
                if (replacement == null && key.startsWith("env.")) replacement = System.getenv(key.substring(4));
                if (replacement == null) replacement = matcher.group(2);
                if (replacement == null) throw new IllegalArgumentException("Unresolved scanner expression: " + key);
                matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(replacement));
            }
            value = matcher.appendTail(result).toString();
        }
        if (value.contains("${")) throw new IllegalArgumentException("Unresolved or circular scanner expression.");
        return value;
    }
}
