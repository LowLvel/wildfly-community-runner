package io.github.wildflycommunityrunner.util;

import io.github.wildflycommunityrunner.model.ServerProfile;
import com.intellij.util.execution.ParametersListUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;

public final class WildFlyPaths {
    private WildFlyPaths() {}

    /** A profile ID is a UI reference; processes belong to a concrete server instance. No disk I/O here. */
    public record Identity(Path home, Path base, Path configuration) {}

    public static Identity identity(ServerProfile profile) {
        return new Identity(home(profile), standaloneDir(profile), configurationFile(profile));
    }

    public static Path home(ServerProfile profile) {
        if (profile.home == null || profile.home.isBlank()) throw new IllegalArgumentException("WildFly Home is required.");
        return Path.of(profile.home).toAbsolutePath().normalize();
    }

    public static Path standaloneDir(ServerProfile profile) {
        return propertyPath(profile, "jboss.server.base.dir", home(profile).resolve("standalone"));
    }

    public static Path deploymentsDir(ServerProfile profile) {
        return io.github.wildflycommunityrunner.services.ScannerConfiguration.directory(profile);
    }

    public static Path logFile(ServerProfile profile) {
        return propertyPath(profile, "jboss.server.log.dir", standaloneDir(profile).resolve("log")).resolve("server.log");
    }

    public static Path configurationFile(ServerProfile profile) {
        return configurationDir(profile).resolve(configurationArgument(startupArguments(profile), profile.configuration)).normalize();
    }

    public static Path configurationDir(ServerProfile profile) {
        return propertyPath(profile, "jboss.server.config.dir", standaloneDir(profile).resolve("configuration"));
    }

    private static Path propertyPath(ServerProfile profile, String property, Path fallback) {
        String value = properties(profileArguments(profile)).get(property);
        if (value == null) return fallback;
        if (value.isBlank()) throw new IllegalArgumentException(property + " must not be empty.");
        Path path = Path.of(value);
        // Managed launchers use bin/ as their working directory.
        return (path.isAbsolute() ? path : home(profile).resolve("bin").resolve(path)).normalize();
    }

    public static List<String> startupArguments(ServerProfile profile) {
        return ParametersListUtil.parse(Objects.toString(profile.startupArguments, ""));
    }

    public static List<String> profileArguments(ServerProfile profile) {
        List<String> arguments = new ArrayList<>(ParametersListUtil.parse(Objects.toString(profile.jvmOptions, "")));
        arguments.addAll(startupArguments(profile));
        return arguments;
    }

    public static Map<String, String> properties(List<String> arguments) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String argument : arguments) {
            int equals = argument.indexOf('=');
            if (argument.startsWith("-D") && equals > 2) result.put(argument.substring(2, equals), unquote(argument.substring(equals + 1)));
        }
        return result;
    }

    public static String configurationArgument(List<String> arguments, String fallback) {
        String result = fallback == null || fallback.isBlank() ? "standalone.xml" : fallback;
        boolean specified = false;
        for (int i = 0; i < arguments.size(); i++) {
            String argument = arguments.get(i);
            for (String flag : List.of("-c", "--server-config", "--read-only-server-config")) {
                if (argument.equals(flag) || argument.startsWith(flag + "=")) {
                    if (specified) throw new IllegalArgumentException("Specify only one server configuration argument.");
                    if (argument.equals(flag)) {
                        if (i + 1 >= arguments.size()) throw new IllegalArgumentException("Missing value for " + flag);
                        result = unquote(arguments.get(++i));
                    } else result = unquote(argument.substring(flag.length() + 1));
                    if (result.isBlank() || result.startsWith("-")) throw new IllegalArgumentException("Invalid server configuration argument.");
                    specified = true;
                    break;
                }
            }
        }
        return result;
    }

    public static boolean hasConfigurationArgument(List<String> arguments) {
        return arguments.stream().anyMatch(argument -> List.of("-c", "--server-config", "--read-only-server-config")
                .stream().anyMatch(flag -> argument.equals(flag) || argument.startsWith(flag + "=")));
    }

    public static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) return value.substring(1, value.length() - 1);
        return value;
    }

    public static Path startupScript(ServerProfile profile) {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        return home(profile).resolve("bin").resolve(windows ? "standalone.bat" : "standalone.sh");
    }

    public static String validate(ServerProfile profile) {
        if (profile.name == null || profile.name.isBlank()) return "Server name is required.";
        if (profile.home == null || profile.home.isBlank()) return "WildFly Home is required.";
        Path home = home(profile);
        for (var option : properties(profileArguments(profile)).entrySet()) {
            if (List.of("jboss.server.base.dir", "jboss.server.config.dir", "jboss.server.log.dir").contains(option.getKey())
                    && option.getValue().chars().anyMatch(c -> Character.isWhitespace(c) || "\"'&|<>^%$`!".indexOf(c) >= 0)) {
                return "WildFly's standalone scripts cannot reliably parse " + option.getKey()
                        + " containing spaces or shell metacharacters. Use a directory override without those characters."
                        + " WildFly Home itself may contain spaces when using its default standalone directories.";
            }
        }
        if (!Files.isDirectory(home)) return "WildFly Home does not exist: " + home;
        if (!Files.isRegularFile(home.resolve("jboss-modules.jar"))) return "Cannot find jboss-modules.jar under: " + home;
        if (!Files.isRegularFile(startupScript(profile))) return "Cannot find WildFly startup script under: " + home.resolve("bin");
        if (!Files.isDirectory(standaloneDir(profile))) return "Cannot find server base directory: " + standaloneDir(profile);
        if (profile.configuration == null || profile.configuration.isBlank()) return "Configuration file is required.";
        if (!Files.isRegularFile(configurationFile(profile))) return "Cannot find configuration: " + configurationFile(profile);
        String configuredHome = properties(profileArguments(profile)).get("jboss.home.dir");
        if (configuredHome != null && !Path.of(configuredHome).toAbsolutePath().normalize().equals(home)) {
            return "Set the WildFly Home field instead of overriding jboss.home.dir in JVM/startup options.";
        }
        if (profile.javaHome != null && !profile.javaHome.isBlank()) {
            boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
            Path java = Path.of(profile.javaHome).resolve("bin").resolve(windows ? "java.exe" : "java");
            if (!Files.isRegularFile(java)) return "JAVA_HOME does not contain a Java executable: " + java;
        }
        return null;
    }
}
