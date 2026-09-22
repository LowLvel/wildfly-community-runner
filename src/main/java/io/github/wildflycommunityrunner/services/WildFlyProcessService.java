package io.github.wildflycommunityrunner.services;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.KillableProcessHandler;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessOutputType;
import com.intellij.execution.process.ProcessListener;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import io.github.wildflycommunityrunner.model.ServerProfile;
import io.github.wildflycommunityrunner.security.JvmSecrets;
import io.github.wildflycommunityrunner.security.PrivateJvmOptions;
import io.github.wildflycommunityrunner.security.SecretRedactor;
import io.github.wildflycommunityrunner.security.SensitiveProperties;
import io.github.wildflycommunityrunner.util.WildFlyPaths;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Service(Service.Level.APP)
public final class WildFlyProcessService implements com.intellij.openapi.Disposable {
    private record ManagedProcess(ProcessHandler handler, boolean debug, int debugPort) {}
    private record ManagedKey(WildFlyPaths.Identity identity, String host) {}
    private final Map<ManagedKey, ManagedProcess> processes = new ConcurrentHashMap<>();
    // Only background launch paths acquire this lock. UI state queries never wait for process/socket I/O.
    private final Object launchLock = new Object();
    private final Object listenerLock = new Object();
    private final Map<ProcessHandler, List<ProcessListener>> listeners = new java.util.IdentityHashMap<>();
    private volatile boolean disposed;
    private record PrivateOptions(JvmSecrets owner, PrivateJvmOptions options) {
        void release() { owner.release(options); }
    }
    private final Map<ProcessHandler, PrivateOptions> privateOptions = new java.util.IdentityHashMap<>();

    public static WildFlyProcessService getInstance() {
        return ApplicationManager.getApplication().getService(WildFlyProcessService.class);
    }


    public enum ServerState { STOPPED, DETECTED, MANAGED, MANAGED_DEBUG, STOPPING, PORT_BUSY, OTHER_CONFIGURATION }

    public ServerState state(ServerProfile profile) {
        if (profile == null) return ServerState.STOPPED;
        ManagedProcess managed = managed(profile);
        if (managed != null && managed.handler().isProcessTerminating()) return ServerState.STOPPING;
        if (managed != null && !managed.handler().isProcessTerminated()) return managed.debug() ? ServerState.MANAGED_DEBUG : ServerState.MANAGED;
        if (!WildFlyServerDetector.matchingProcesses(profile).isEmpty()) return ServerState.DETECTED;
        if (!WildFlyServerDetector.matchingServerBase(profile).isEmpty()) return ServerState.OTHER_CONFIGURATION;
        return WildFlyServerDetector.isPortOpen(profile) ? ServerState.PORT_BUSY : ServerState.STOPPED;
    }

    public boolean canForceStopDetected(ServerProfile profile) {
        return findUniqueLocalWildFlyProcess(profile).isPresent();
    }

    /**
     * Best-effort safe stop for a local WildFly that was not started by this IDE session.
     * It is only offered when exactly one local Java process can be matched to the configured WildFly home.
     */
    public void forceStopDetected(ServerProfile profile, Consumer<String> output) {
        if (profile == null) return;
        Optional<ProcessHandle> match = findUniqueLocalWildFlyProcess(profile);
        if (match.isEmpty()) {
            output.accept("Cannot safely identify a unique local WildFly process for " + profile.name + ".");
            return;
        }
        ProcessHandle process = match.get();
        output.accept("Stopping detected WildFly process " + process.pid() + " for " + profile.name + "...");
        if (!WildFlyServerDetector.verifies(profile, process)) {
            throw new IllegalStateException("WildFly process identity changed before Stop; no process was terminated.");
        }
        if (Thread.currentThread().isInterrupted()) return;
        try (var children = process.descendants()) {
            children.forEach(child -> { try { child.destroy(); } catch (SecurityException ignored) {} });
        }
        process.destroy();
        long deadline = System.nanoTime() + Duration.ofSeconds(4).toNanos();
        while (process.isAlive() && System.nanoTime() < deadline) {
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
        if (process.isAlive()) {
            try (var children = process.descendants()) {
                children.forEach(child -> { try { child.destroyForcibly(); } catch (SecurityException ignored) {} });
            }
            process.destroyForcibly();
        }
    }

    private Optional<ProcessHandle> findUniqueLocalWildFlyProcess(ServerProfile profile) {
        List<ProcessHandle> matches = WildFlyServerDetector.matchingProcesses(profile);
        return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
    }

    /** Compatibility helper for callers that already have a Project. The process registry is application-wide. */
    public static WildFlyProcessService getInstance(Project ignored) {
        return getInstance();
    }

    private static ManagedKey managedKey(ServerProfile profile) {
        String host = WildFlyServerDetector.connectionHost(profile.host).toLowerCase(java.util.Locale.ROOT);
        if (host.equals("127.0.0.1") || host.equals("::1")) host = "localhost";
        return new ManagedKey(WildFlyPaths.identity(profile), host);
    }

    private ManagedProcess managed(ServerProfile profile) {
        if (profile == null) return null;
        try { return processes.get(managedKey(profile)); }
        catch (IllegalArgumentException error) { return null; }
    }

    /** Redacts a complete log line using only the currently managed execution's values. */
    public String redact(ServerProfile profile, String text) {
        ManagedProcess current = managed(profile);
        SecretRedactor redactor = current == null ? null : current.handler().getUserData(SecretRedactor.PROCESS);
        return redactor == null ? SensitiveProperties.redactProperties(text) : redactor.redact(text);
    }

    public boolean isRunning(ServerProfile profile) {
        ManagedProcess process = managed(profile);
        return process != null && !process.handler().isProcessTerminated() && !process.handler().isProcessTerminating();
    }

    public boolean isDebugRunning(ServerProfile profile) {
        ManagedProcess process = managed(profile);
        return process != null && process.debug() && !process.handler().isProcessTerminated() && !process.handler().isProcessTerminating();
    }

    public int managedDebugPort(ServerProfile profile) {
        ManagedProcess process = managed(profile);
        return process == null ? profile.debugPort : process.debugPort();
    }

    /** A managed or identity-verified local WildFly process, including one still starting its HTTP listener. */
    public boolean isDetectedRunning(ServerProfile profile) {
        return isRunning(profile) || !WildFlyServerDetector.matchingProcesses(profile).isEmpty();
    }

    public void start(ServerProfile profile, boolean debug, Consumer<String> output) throws Exception {
        ServerProfile snapshot = new ServerProfile(profile);
        synchronized (launchLock) {
            if (disposed) throw new IllegalStateException("WildFly integration has been disposed.");
            if (isRunning(snapshot)) {
                output.accept("Server is already managed by WildFly Community Runner: " + snapshot.name);
                return;
            }
            ensureCanStart(snapshot);
            if (isDetectedRunning(snapshot)) {
                output.accept("This WildFly instance is already running locally. Use Attach Debugger to debug it.");
                return;
            }
            createProcess(snapshot, debug, output).startNotify();
        }
    }

    public record LaunchResult(ProcessHandler handler, boolean ownsProcess) {}

    /** Called on a pooled thread by a native Run/Debug session. The owner starts notifications after binding its console. */
    public LaunchResult startForExecution(ServerProfile profile, boolean debug) throws Exception {
        ServerProfile snapshot = new ServerProfile(profile);
        synchronized (launchLock) {
            if (disposed) throw new IllegalStateException("WildFly integration has been disposed.");
            ManagedProcess current = managed(snapshot);
            if (current != null && current.handler().isProcessTerminating()) {
                throw new IllegalStateException("WildFly is still stopping. Wait for it to exit before starting another session.");
            }
            if (isRunning(snapshot)) {
                if (debug && !isDebugRunning(snapshot)) {
                    throw new IllegalStateException("This WildFly is already running without debugging. Stop it before starting Debug, or use Attach if JDWP was enabled separately.");
                }
                if (debug && current.debugPort() != snapshot.debugPort) {
                    throw new IllegalStateException("This instance is already debugging on port " + current.debugPort()
                            + ". Update the profile's debug port or stop the instance before restarting Debug.");
                }
                return new LaunchResult(current.handler(), false);
            }
            ensureCanStart(snapshot);
            if (isDetectedRunning(snapshot)) {
                throw new IllegalStateException("This WildFly instance is already running locally. Use WildFly Attach Debugger to attach without restarting it.");
            }
            return new LaunchResult(createProcess(snapshot, debug, ignored -> {}), true);
        }
    }

    private void ensureCanStart(ServerProfile profile) {
        String error = WildFlyPaths.validate(profile);
        if (error != null) throw new IllegalArgumentException(error);
        if (!WildFlyServerDetector.isLocalHost(profile.host)) {
            throw new IllegalArgumentException("Local WildFly launch requires a local HTTP host. Use Attach Debugger for a remote JVM.");
        }
        WildFlyPaths.Identity identity = WildFlyPaths.identity(profile);
        ManagedProcess current = processes.get(managedKey(profile));
        if (current != null && current.handler().isProcessTerminating()) {
            throw new IllegalStateException("WildFly is still stopping. Wait for it to exit before starting again.");
        }
        boolean anotherConfiguration = processes.entrySet().stream().anyMatch(entry ->
                entry.getKey().identity().base().equals(identity.base()) && !entry.getKey().identity().equals(identity)
                        && !entry.getValue().handler().isProcessTerminated());
        boolean externallyRunning = !WildFlyServerDetector.matchingProcessesFresh(profile).isEmpty();
        if (anotherConfiguration || (!externallyRunning
                && !WildFlyServerDetector.matchingServerBase(profile).isEmpty())) {
            throw new IllegalStateException("Another WildFly configuration is using this server base directory. Stop it first or configure a separate jboss.server.base.dir.");
        }
        if (!externallyRunning && !isRunning(profile) && WildFlyServerDetector.isPortOpen(profile)) {
            throw new IllegalStateException("Port " + endpoint(profile) + " is occupied, but its process could not be verified as this WildFly instance. Check the port and server profile before starting.");
        }
    }

    private KillableProcessHandler createProcess(ServerProfile profile, boolean debug, Consumer<String> output) throws Exception {
        if (disposed) throw new IllegalStateException("WildFly integration has been disposed.");
        String validationError = WildFlyPaths.validate(profile);
        if (validationError != null) throw new IllegalArgumentException(validationError);

        SensitiveProperties.requireJvmField(profile.startupArguments, "WildFly startup arguments");
        Path script = WildFlyPaths.startupScript(profile);
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        List<String> startupArguments = WildFlyPaths.startupArguments(profile);
        List<String> serverArgs = new ArrayList<>();
        if (!WildFlyPaths.hasConfigurationArgument(startupArguments)) {
            serverArgs.add("-c");
            serverArgs.add(profile.configuration);
        }
        if (debug) {
            serverArgs.add("--debug");
            serverArgs.add(Integer.toString(profile.debugPort));
        }
        serverArgs.addAll(startupArguments);

        List<String> command = new ArrayList<>();
        if (windows) {
            command.add("cmd.exe");
            command.add("/c");
        }
        command.add(script.toString());
        command.addAll(serverArgs);
        GeneralCommandLine commandLine = new GeneralCommandLine(command)
                .withWorkingDirectory(script.getParent())
                .withCharset(StandardCharsets.UTF_8);
        if (profile.javaHome != null && !profile.javaHome.isBlank()) {
            commandLine.withEnvironment("JAVA_HOME", profile.javaHome.trim());
        }
        String inherited = System.getenv("JAVA_OPTS");
        String combined = ((inherited == null ? "" : inherited.trim()) + " "
                + (profile.jvmOptions == null ? "" : profile.jvmOptions.trim())).trim();
        JvmSecrets secretManager = JvmSecrets.getInstance();
        PrivateJvmOptions options = secretManager.prepare(combined);
        KillableProcessHandler handler;
        try {
            if (!options.options().isBlank()) commandLine.withEnvironment("JAVA_OPTS", options.options());
            output.accept("Starting " + profile.name + (debug ? " in DEBUG mode on port " + profile.debugPort : "")
                    + " using " + profile.configuration);
            if (disposed) throw new IllegalStateException("WildFly integration has been disposed.");
            handler = new KillableProcessHandler(commandLine);
            handler.setShouldKillProcessSoftly(false);
            handler.putUserData(SecretRedactor.PROCESS, options.redactor());
            synchronized (listenerLock) {
                if (!disposed) privateOptions.put(handler, new PrivateOptions(secretManager, options));
                else { handler.putUserData(SecretRedactor.PROCESS, null); secretManager.release(options); }
            }
            registerManaged(profile, handler, debug);
        } catch (Exception failure) {
            secretManager.release(options);
            throw failure;
        }
        Map<Key<?>, SecretRedactor.Lines> streams = new ConcurrentHashMap<>();
        addListener(handler, new ProcessListener() {
            @Override
            @SuppressWarnings("rawtypes")
            public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
                streams.computeIfAbsent(outputType, key -> options.redactor().new Lines(text -> {
                    if (!text.isBlank()) output.accept(ProcessOutputType.isStderr(key) ? "[stderr] " + text.stripTrailing() : text.stripTrailing());
                })).accept(event.getText());
            }
            @Override public void processTerminated(@NotNull ProcessEvent event) {
                streams.values().forEach(SecretRedactor.Lines::finish);
                output.accept(profile.name + " terminated with exit code " + event.getExitCode());
            }
        });
        return handler;
    }

    void registerManaged(ServerProfile profile, ProcessHandler handler, boolean debug) {
        ManagedKey identity = managedKey(profile);
        ManagedProcess managed = new ManagedProcess(handler, debug, profile.debugPort);
        boolean detach;
        synchronized (listenerLock) {
            detach = disposed;
            if (!detach) {
                processes.put(identity, managed);
                addListener(handler, new ProcessListener() {
                    @Override public void processTerminated(@NotNull ProcessEvent event) {
                        processes.remove(identity, managed);
                        removeListeners(handler);
                    }
                });
            }
        }
        // ProcessHandler queues detach until startNotify for a concurrently completing launch.
        if (detach) handler.detachProcess();
    }

    private void addListener(ProcessHandler handler, ProcessListener listener) {
        synchronized (listenerLock) {
            if (disposed || handler.isProcessTerminated()) return;
            listeners.computeIfAbsent(handler, ignored -> new ArrayList<>()).add(listener);
            handler.addProcessListener(listener);
        }
    }

    private void removeListeners(ProcessHandler handler) {
        synchronized (listenerLock) {
            List<ProcessListener> removed = listeners.remove(handler);
            if (removed != null) removed.forEach(handler::removeProcessListener);
            handler.putUserData(SecretRedactor.PROCESS, null);
            PrivateOptions options = privateOptions.remove(handler);
            if (options != null) options.release();
        }
    }

    @Override public void dispose() {
        List<ProcessHandler> detach;
        synchronized (listenerLock) {
            disposed = true;
            detach = new ArrayList<>(listeners.keySet());
            listeners.forEach((handler, owned) -> owned.forEach(handler::removeProcessListener));
            listeners.clear();
            privateOptions.forEach((handler, options) -> {
                handler.putUserData(SecretRedactor.PROCESS, null);
                options.release();
            });
            privateOptions.clear();
            processes.clear();
        }
        // Unloading the plugin must release its listeners without terminating shared WildFly servers.
        detach.forEach(handler -> { if (!handler.isProcessTerminated()) handler.detachProcess(); });
    }

    int listenerCount() { synchronized (listenerLock) { return listeners.values().stream().mapToInt(List::size).sum(); } }

    public void terminateAndWait(ServerProfile profile, Consumer<String> output) throws InterruptedException {
        ManagedProcess managed = managed(profile);
        if (managed == null || managed.handler().isProcessTerminated()) return;
        ProcessHandler handler = managed.handler();
        output.accept("Restarting " + profile.name + " for debug...");
        handler.destroyProcess();
        long deadline = System.currentTimeMillis() + 10_000L;
        while (!handler.isProcessTerminated() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100L);
        }
        if (!handler.isProcessTerminated()) throw new IllegalStateException("WildFly did not terminate within 10 seconds.");
    }

    public void terminate(ServerProfile profile, Consumer<String> output) {
        ManagedProcess managed = managed(profile);
        ProcessHandler handler = managed == null ? null : managed.handler();
        if (handler == null || handler.isProcessTerminated()) {
            if (isDetectedRunning(profile)) {
                output.accept("WildFly is running at " + endpoint(profile) + " but was not started by this IDE process, so it will not be force-killed automatically.");
            } else {
                output.accept("No running WildFly process is managed for " + profile.name + ".");
            }
            return;
        }
        output.accept("Terminating " + profile.name + "...");
        handler.destroyProcess();
    }

    public String endpoint(ServerProfile profile) {
        String host = WildFlyServerDetector.connectionHost(profile.host);
        if (host.contains(":")) host = "[" + host + "]";
        int port = profile.httpPort > 0 ? profile.httpPort : 8080;
        return host + ":" + port;
    }
}
