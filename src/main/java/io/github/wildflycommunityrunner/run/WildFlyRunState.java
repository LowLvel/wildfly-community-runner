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
import org.jetbrains.annotations.NotNull;

/** Uses the standard Java runner through public execution interfaces. */
final class WildFlyRunState extends CommandLineState implements RemoteConnectionCreator, RemoteState {
    private final ServerProfile profile;
    private final boolean debug;

    WildFlyRunState(ExecutionEnvironment environment, ServerProfile profile, boolean debug) {
        super(environment);
        this.profile = new ServerProfile(profile);
        this.debug = debug;
    }

    @Override protected @NotNull ProcessHandler startProcess() {
        return new WildFlySessionProcessHandler(() -> {
            if (getEnvironment().getProject().isDisposed()) throw new ExecutionException("Project closed before WildFly started.");
            var result = WildFlyProcessService.getInstance().startForExecution(profile, debug);
            return new WildFlySessionProcessHandler.Launch(result.handler(), result.ownsProcess());
        }, command -> ApplicationManager.getApplication().executeOnPooledThread(command));
    }

    @Override public @NotNull ExecutionResult execute(@NotNull Executor executor, @NotNull ProgramRunner<?> runner) throws ExecutionException {
        ExecutionResult result = super.execute(executor, runner);
        // Bind the console before launching, then perform filesystem/network/process work off EDT.
        ((WildFlySessionProcessHandler) result.getProcessHandler()).begin();
        return result;
    }

    @Override public RemoteConnection createRemoteConnection(ExecutionEnvironment environment) { return getRemoteConnection(); }
    @Override public RemoteConnection getRemoteConnection() { return new RemoteConnection(true, profile.host, Integer.toString(profile.debugPort), false); }
    @Override public boolean isPollConnection() { return true; }
}
