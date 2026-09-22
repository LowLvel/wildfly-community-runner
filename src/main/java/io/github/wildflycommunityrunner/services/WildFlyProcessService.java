package io.github.wildflycommunityrunner.services;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.KillableProcessHandler;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessOutputType;
import com.intellij.execution.process.ProcessListener;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.util.execution.ParametersListUtil;
import io.github.wildflycommunityrunner.model.ServerProfile;
import io.github.wildflycommunityrunner.util.WildFlyPaths;
import org.jetbrains.annotations.NotNull;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Service(Service.Level.APP)
public final class WildFlyProcessService {
    private final Map<String, KillableProcessHandler> handlers = new ConcurrentHashMap<>();
    private final Map<String, Boolean> debugModes = new ConcurrentHashMap<>();

    public static WildFlyProcessService getInstance() {
        return ApplicationManager.getApplication().getService(WildFlyProcessService.class);
    }


    public enum ServerState { STOPPED, DETECTED, MANAGED, MANAGED_DEBUG }

    public ServerState state(ServerProfile profile) {
        if (profile == null) return ServerState.STOPPED;
        if (isRunning(profile)) return isDebugRunning(profile) ? ServerState.MANAGED_DEBUG : ServerState.MANAGED;
        return isDetectedRunning(profile) ? ServerState.DETECTED : ServerState.STOPPED;
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
        process.descendants().forEach(child -> { try { child.destroy(); } catch (Exception ignored) {} });
        process.destroy();
        long deadline = System.nanoTime() + Duration.ofSeconds(4).toNanos();
        while (process.isAlive() && System.nanoTime() < deadline) {
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        if (process.isAlive()) {
            process.descendants().forEach(child -> { try { child.destroyForcibly(); } catch (Exception ignored) {} });
            process.destroyForcibly();
        }
    }

    private Optional<ProcessHandle> findUniqueLocalWildFlyProcess(ServerProfile profile) {
        if (profile == null || profile.home == null || profile.home.isBlank()) return Optional.empty();
        String host = profile.host == null ? "" : profile.host.trim().toLowerCase(Locale.ROOT);
        if (!(host.isBlank() || host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1") || host.equals("0.0.0.0"))) {
            return Optional.empty();
        }
        String home;
        try { home = Path.of(profile.home).toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT); }
        catch (Exception e) { return Optional.empty(); }
        List<ProcessHandle> matches = ProcessHandle.allProcesses()
                .filter(ProcessHandle::isAlive)
                .filter(ph -> ph.pid() != ProcessHandle.current().pid())
                .filter(ph -> {
                    String cmd = ph.info().commandLine().orElse("").toLowerCase(Locale.ROOT);
                    if (cmd.isBlank()) return false;
                    boolean wildFly = cmd.contains("org.jboss.modules.main") || cmd.contains("jboss-modules") || cmd.contains("org.jboss.as.standalone");
                    return wildFly && cmd.contains(home);
                })
                .limit(2)
                .toList();
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }

    /** Compatibility helper for callers that already have a Project. The process registry is application-wide. */
    public static WildFlyProcessService getInstance(Project ignored) {
        return getInstance();
    }

    public synchronized boolean isRunning(ServerProfile profile) {
        KillableProcessHandler handler = handlers.get(profile.id);
        return handler != null && !handler.isProcessTerminated() && !handler.isProcessTerminating();
    }

    public synchronized boolean isDebugRunning(ServerProfile profile) {
        return isRunning(profile) && Boolean.TRUE.equals(debugModes.get(profile.id));
    }

    /** True when the configured WildFly HTTP endpoint is accepting TCP connections, even if another project/process started it. */
    public boolean isDetectedRunning(ServerProfile profile) {
        if (profile == null) return false;
        String host = profile.host == null || profile.host.isBlank() ? "localhost" : profile.host.trim();
        int port = profile.httpPort > 0 ? profile.httpPort : 8080;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 250);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    public synchronized void start(ServerProfile profile, boolean debug, Consumer<String> output) throws Exception {
        if (isRunning(profile)) {
            output.accept("Server is already running and managed by WildFly Community Runner: " + profile.name);
            return;
        }
        if (isDetectedRunning(profile)) {
            output.accept("A server is already listening at " + endpoint(profile) + ". Reusing the active server instead of starting another instance.");
            return;
        }

        createProcess(profile, debug, output).startNotify();
    }

    public record LaunchResult(KillableProcessHandler handler, boolean ownsProcess) {}

    /** Called on a pooled thread by a native Run/Debug session. The owner starts notifications after binding its console. */
    public synchronized LaunchResult startForExecution(ServerProfile profile, boolean debug) throws Exception {
        KillableProcessHandler current = handlers.get(profile.id);
        if (current != null && current.isProcessTerminating()) {
            throw new IllegalStateException("WildFly is still stopping. Wait for it to exit before starting another session.");
        }
        if (isRunning(profile)) {
            if (debug && !isDebugRunning(profile)) {
                throw new IllegalStateException("This WildFly is already running without debugging. Stop it before starting Debug, or use Attach if JDWP was enabled separately.");
            }
            return new LaunchResult(handlers.get(profile.id), false);
        }
        if (isDetectedRunning(profile)) {
            throw new IllegalStateException("A server is already running at " + endpoint(profile)
                    + ". Use the WildFly Attach Debugger configuration to attach without restarting it.");
        }
        return new LaunchResult(createProcess(profile, debug, ignored -> {}), true);
    }

    private KillableProcessHandler createProcess(ServerProfile profile, boolean debug, Consumer<String> output) throws Exception {

        String validationError = WildFlyPaths.validate(profile);
        if (validationError != null) throw new IllegalArgumentException(validationError);

        Path script = WildFlyPaths.startupScript(profile);
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        List<String> serverArgs = new ArrayList<>();
        serverArgs.add("-c");
        serverArgs.add(profile.configuration);
        if (debug) {
            serverArgs.add("--debug");
            serverArgs.add(Integer.toString(profile.debugPort));
        }
        if (profile.startupArguments != null && !profile.startupArguments.isBlank()) {
            serverArgs.addAll(ParametersListUtil.parse(profile.startupArguments));
        }

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
        if (profile.jvmOptions != null && !profile.jvmOptions.isBlank()) {
            String inherited = System.getenv("JAVA_OPTS");
            String combined = ((inherited == null ? "" : inherited.trim()) + " " + profile.jvmOptions.trim()).trim();
            commandLine.withEnvironment("JAVA_OPTS", combined);
        }

        output.accept("Starting " + profile.name + (debug ? " in DEBUG mode on port " + profile.debugPort : "")
                + " using " + profile.configuration);
        KillableProcessHandler handler = new KillableProcessHandler(commandLine);
        handler.setShouldKillProcessSoftly(false);
        handlers.put(profile.id, handler);
        debugModes.put(profile.id, debug);
        handler.addProcessListener(new ProcessListener() {
            @Override
            @SuppressWarnings("rawtypes")
            public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
                String text = event.getText().stripTrailing();
                if (text.isBlank()) return;
                output.accept(ProcessOutputType.isStderr(outputType) ? "[stderr] " + text : text);
            }

            @Override
            public void processTerminated(@NotNull ProcessEvent event) {
                if (handlers.remove(profile.id, handler)) debugModes.remove(profile.id);
                output.accept(profile.name + " terminated with exit code " + event.getExitCode());
            }
        });
        return handler;
    }

    public void terminateAndWait(ServerProfile profile, Consumer<String> output) throws InterruptedException {
        KillableProcessHandler handler;
        synchronized (this) {
            handler = handlers.get(profile.id);
            if (handler == null || handler.isProcessTerminated()) return;
            output.accept("Restarting " + profile.name + " for debug...");
            handler.killProcess();
        }
        long deadline = System.currentTimeMillis() + 10_000L;
        while (!handler.isProcessTerminated() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100L);
        }
        if (!handler.isProcessTerminated()) throw new IllegalStateException("WildFly did not terminate within 10 seconds.");
    }

    public synchronized void terminate(ServerProfile profile, Consumer<String> output) {
        KillableProcessHandler handler = handlers.get(profile.id);
        if (handler == null || handler.isProcessTerminated()) {
            if (isDetectedRunning(profile)) {
                output.accept("WildFly is running at " + endpoint(profile) + " but was not started by this IDE process, so it will not be force-killed automatically.");
            } else {
                output.accept("No running WildFly process is managed for " + profile.name + ".");
            }
            return;
        }
        output.accept("Terminating " + profile.name + "...");
        handler.killProcess();
    }

    public String endpoint(ServerProfile profile) {
        String host = profile.host == null || profile.host.isBlank() ? "localhost" : profile.host.trim();
        int port = profile.httpPort > 0 ? profile.httpPort : 8080;
        return host + ":" + port;
    }
}
