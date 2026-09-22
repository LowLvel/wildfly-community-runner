package io.github.wildflycommunityrunner.run;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.ExecutionResult;
import com.intellij.execution.Executor;
import com.intellij.execution.configurations.CommandLineState;
import com.intellij.execution.configurations.RemoteConnection;
import com.intellij.execution.configurations.RemoteConnectionCreator;
import com.intellij.execution.configurations.RemoteState;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.ProgramRunner;
import com.intellij.openapi.application.ApplicationManager;
import io.github.wildflycommunityrunner.model.ServerProfile;
import io.github.wildflycommunityrunner.services.WildFlyProcessService;
import io.github.wildflycommunityrunner.services.WildFlyServerDetector;
import org.jetbrains.annotations.NotNull;

/** Uses the standard Java runner through public execution interfaces. */
final class WildFlyRunState extends CommandLineState implements RemoteConnectionCreator, RemoteState {
    private final ServerProfile profile;
    private final boolean debug;
    private final java.util.List<io.github.wildflycommunityrunner.model.ServiceProfile> services;

    WildFlyRunState(ExecutionEnvironment environment, ServerProfile profile, boolean debug) {
        this(environment, profile, debug, java.util.List.of());
    }

    WildFlyRunState(ExecutionEnvironment environment, ServerProfile profile, boolean debug,
                   java.util.List<io.github.wildflycommunityrunner.model.ServiceProfile> services) {
        super(environment);
        this.profile = new ServerProfile(profile);
        this.debug = debug;
        this.services = services.stream().map(io.github.wildflycommunityrunner.model.ServiceProfile::new).toList();
    }

    @Override protected @NotNull ProcessHandler startProcess() {
        var handler = new WildFlySessionProcessHandler(() -> {
            if (getEnvironment().getProject().isDisposed()) throw new ExecutionException("Project closed before WildFly started.");
            var result = WildFlyProcessService.getInstance().startForExecution(profile, debug);
            return new WildFlySessionProcessHandler.Launch(result.handler(), result.ownsProcess());
        }, command -> ApplicationManager.getApplication().executeOnPooledThread(command),
                (cancelled, output) -> ApplicationLaunch.deploy(getEnvironment().getProject(), profile, services, cancelled, output));
        getEnvironment().getProject().getService(WildFlySessionService.class).register(handler);
        return handler;
    }

    @Override public @NotNull ExecutionResult execute(@NotNull Executor executor, @NotNull ProgramRunner<?> runner) throws ExecutionException {
        ExecutionResult result = super.execute(executor, runner);
        // Bind the console before launching, then perform filesystem/network/process work off EDT.
        ((WildFlySessionProcessHandler) result.getProcessHandler()).begin();
        return result;
    }

    @Override public RemoteConnection createRemoteConnection(ExecutionEnvironment environment) { return getRemoteConnection(); }
    @Override public RemoteConnection getRemoteConnection() { return new RemoteConnection(true, WildFlyServerDetector.connectionHost(profile.host), Integer.toString(profile.debugPort), false); }
    @Override public boolean isPollConnection() { return true; }
}
