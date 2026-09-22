package io.github.wildflycommunityrunner.services;

import io.github.wildflycommunityrunner.model.ServerProfile;
import io.github.wildflycommunityrunner.util.WildFlyPaths;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Conservative local identity detection. An open TCP port is never proof of WildFly. */
public final class WildFlyServerDetector {
    private WildFlyServerDetector() {}

    public static List<ProcessHandle> matchingProcesses(ServerProfile profile) {
        return matchingProcesses(profile, true);
    }

    public static List<ProcessHandle> matchingServerBase(ServerProfile profile) {
        return matchingProcesses(profile, false);
    }

    /** Launch preflight must not reuse a snapshot taken before another process was started. */
    static List<ProcessHandle> matchingProcessesFresh(ServerProfile profile) {
        if (isWindows() && profile != null && isLocalHost(profile.host)) WindowsProcessQuery.read(true);
        return matchingProcesses(profile);
    }

    private static List<ProcessHandle> matchingProcesses(ServerProfile profile, boolean exactConfiguration) {
        if (profile == null || !isLocalHost(profile.host)) return List.of();
        List<ProcessHandle> matches = new ArrayList<>();
        try (var processes = ProcessHandle.allProcesses()) {
            processes.filter(ProcessHandle::isAlive).filter(p -> p.pid() != ProcessHandle.current().pid()).forEach(process -> {
                try {
                    var info = process.info();
                    if (matches(profile, info.command().orElse(""), List.of(info.arguments().orElse(new String[0])), exactConfiguration)) matches.add(process);
                } catch (SecurityException ignored) { /* Inaccessible identity never authorizes Stop. */ }
            });
        } catch (SecurityException ignored) { return List.of(); }
        if (isWindows()) {
            for (var command : WindowsProcessQuery.read(false)) {
                if (!matches(profile, command.executable(), command.arguments(), exactConfiguration)) continue;
                ProcessHandle.of(command.pid()).filter(ProcessHandle::isAlive).filter(p -> sameProcess(p, command))
                        .filter(p -> matches.stream().noneMatch(existing -> existing.pid() == p.pid())).ifPresent(matches::add);
            }
        }
        return List.copyOf(matches);
    }

    static boolean verifies(ServerProfile profile, ProcessHandle process) {
        if (!process.isAlive()) return false;
        var info = process.info();
        if (info.arguments().isPresent()) return matches(profile, info.command().orElse(""), List.of(info.arguments().get()));
        return isWindows() && WindowsProcessQuery.read(true).stream().anyMatch(command -> sameProcess(process, command)
                && matches(profile, command.executable(), command.arguments()));
    }

    private static boolean sameProcess(ProcessHandle process, WindowsProcessQuery.Command command) {
        return process.pid() == command.pid() && process.info().startInstant()
                .map(start -> start.toEpochMilli() == command.startedMillis()).orElse(false);
    }

    private static boolean isWindows() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"); }

    static boolean matches(ServerProfile profile, String executable, List<String> arguments) {
        return matches(profile, executable, arguments, true);
    }

    static boolean matches(ServerProfile profile, String executable, List<String> arguments, boolean exactConfiguration) {
        if (profile == null) return false;
        try {
            Path command = Path.of(executable);
            if (command.getFileName() == null || !Set.of("java", "java.exe", "javaw.exe")
                    .contains(command.getFileName().toString().toLowerCase(Locale.ROOT))) return false;
            int entry = standaloneEntry(arguments, WildFlyPaths.home(profile).resolve("bin/jdk.serialFilter"));
            if (entry < 0) return false;
            var properties = WildFlyPaths.properties(arguments);
            String homeValue = properties.get("jboss.home.dir");
            if (homeValue == null) return false;
            Path home = absolute(homeValue);
            Path base = properties.containsKey("jboss.server.base.dir")
                    ? absolute(properties.get("jboss.server.base.dir")) : home.resolve("standalone");
            Path configDir = properties.containsKey("jboss.server.config.dir")
                    ? absolute(properties.get("jboss.server.config.dir")) : base.resolve("configuration");
            String config = WildFlyPaths.configurationArgument(arguments.subList(entry + 1, arguments.size()), "standalone.xml");
            var actual = new WildFlyPaths.Identity(home, base, configDir.resolve(config).normalize());
            var expected = WildFlyPaths.identity(profile);
            if (!samePath(actual.base(), expected.base()) || (exactConfiguration && (!samePath(actual.home(), expected.home())
                    || !samePath(actual.configuration(), expected.configuration())))) return false;
            int jar = arguments.indexOf("-jar");
            return jar < 0 || samePath(absolute(WildFlyPaths.unquote(arguments.get(jar + 1))), home.resolve("jboss-modules.jar"));
        } catch (IllegalArgumentException | IndexOutOfBoundsException ignored) { return false; }
    }

    private static int standaloneEntry(List<String> arguments, Path serialFilter) {
        boolean modulesLauncher = false;
        for (int i = 0; i < arguments.size(); i++) {
            String argument = arguments.get(i);
            if (!modulesLauncher) {
                if (argument.startsWith("@") && samePath(absolute(WildFlyPaths.unquote(argument.substring(1))), serialFilter)) {
                    // Recent WildFly distributions supply their JDK serial filter through
                    // this fixed argument file. Arbitrary @files remain unverified.
                    continue;
                } else if (argument.equals("-jar")) {
                    if (++i >= arguments.size()) return -1;
                    Path fileName = Path.of(WildFlyPaths.unquote(arguments.get(i))).getFileName();
                    if (fileName == null || !fileName.toString().equals("jboss-modules.jar")) return -1;
                    modulesLauncher = true;
                } else if (Set.of("-cp", "-classpath", "--class-path", "-p", "--module-path", "--add-opens", "--add-exports", "--add-modules").contains(argument)) {
                    i++;
                } else if (argument.equals("org.jboss.modules.Main")) {
                    modulesLauncher = true;
                } else if (!argument.startsWith("-")) {
                    return -1;
                }
            } else {
                if (Set.of("-mp", "-modulepath", "-cp", "-classpath", "-dep", "-dependencies", "-javaagent").contains(argument)) i++;
                else if (argument.equals("org.jboss.as.standalone")) return i;
                else if (!argument.startsWith("-")) return -1;
            }
        }
        return -1;
    }

    private static Path absolute(String text) {
        Path path = Path.of(text).normalize();
        if (!path.isAbsolute()) throw new IllegalArgumentException("External process path is not absolute");
        return path;
    }

    private static boolean samePath(Path first, Path second) {
        if (first.equals(second)) return true;
        // Detection already runs in the background. Vendor launchers canonicalize
        // aliases such as macOS /var -> /private/var and Windows short directory names.
        try { return Files.isSameFile(first, second); }
        catch (java.io.IOException | SecurityException inaccessible) { return false; }
    }

    public static String connectionHost(String configured) {
        String host = configured == null || configured.isBlank() ? "localhost" : configured.trim();
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        return Set.of("0.0.0.0", "::", "::0").contains(host) ? "localhost" : host;
    }

    public static boolean isLocalHost(String configured) {
        String host = connectionHost(configured);
        if (Set.of("localhost", "127.0.0.1", "::1").contains(host.toLowerCase(Locale.ROOT))) return true;
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (address.isLoopbackAddress() || NetworkInterface.getByInetAddress(address) != null) return true;
            }
        } catch (Exception ignored) { /* DNS/network restrictions leave the endpoint unverified. */ }
        return false;
    }

    public static boolean isPortOpen(ServerProfile profile) {
        if (profile == null || profile.httpPort < 1 || profile.httpPort > 65535) return false;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(connectionHost(profile.host), profile.httpPort), 250);
            return true;
        } catch (Exception ignored) { return false; }
    }
}
