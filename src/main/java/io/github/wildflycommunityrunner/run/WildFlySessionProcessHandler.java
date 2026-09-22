package io.github.wildflycommunityrunner.run;

import com.intellij.execution.process.ProcessAdapter;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.openapi.util.Key;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.io.OutputStream;
import java.util.concurrent.Executor;

/** A session owns only the process it started; observing another project's server never grants stop ownership. */
final class WildFlySessionProcessHandler extends ProcessHandler {
    record Launch(ProcessHandler handler, boolean ownsProcess) {}
    @FunctionalInterface interface Launcher { Launch launch() throws Exception; }
    private enum Request { NONE, STOP, DETACH }

    private final Launcher launcher;
    private final Executor executor;
    private Request request = Request.NONE;
    private Launch launch;
    private final ProcessAdapter listener = new ProcessAdapter() {
        @Override public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
            if (!isProcessTerminated()) notifyTextAvailable(event.getText(), outputType);
        }
        @Override public void processTerminated(@NotNull ProcessEvent event) { finish(event.getExitCode()); }
    };

    WildFlySessionProcessHandler(Launcher launcher, Executor executor) {
        this.launcher = launcher;
        this.executor = executor;
    }

    void begin() {
        startNotify();
        executor.execute(() -> {
            synchronized (this) { if (request != Request.NONE) return; }
            try {
                Launch result = launcher.launch();
                Request pending;
                synchronized (this) {
                    launch = result;
                    pending = request;
                    if (pending == Request.NONE) result.handler().addProcessListener(listener);
                }
                if (result.ownsProcess()) result.handler().startNotify();
                if (pending == Request.STOP && result.ownsProcess()) result.handler().destroyProcess();
                if (pending == Request.NONE) {
                    Integer exit = result.handler().getExitCode();
                    if (exit != null) finish(exit);
                }
            } catch (Exception e) {
                if (!isProcessTerminated()) {
                    notifyTextAvailable("Cannot start WildFly: " + e.getMessage() + "\n", ProcessOutputTypes.STDERR);
                    finish(1);
                }
            }
        });
    }

    private synchronized void finish(int code) {
        if (launch != null) launch.handler().removeProcessListener(listener);
        notifyProcessTerminated(code);
    }

    @Override protected void destroyProcessImpl() {
        Launch current;
        synchronized (this) {
            request = Request.STOP;
            current = launch;
            if (current != null && !current.ownsProcess()) current.handler().removeProcessListener(listener);
        }
        if (current != null && current.ownsProcess()) {
            // Keep the session terminating until the process actually exits, so Rerun cannot race Stop.
            executor.execute(current.handler()::destroyProcess);
        } else if (current != null) {
            notifyProcessDetached();
        } else {
            notifyProcessTerminated(130);
        }
    }

    @Override protected synchronized void detachProcessImpl() {
        request = Request.DETACH;
        if (launch != null) launch.handler().removeProcessListener(listener);
        notifyProcessDetached();
    }

    @Override public boolean detachIsDefault() { return true; }
    @Override public synchronized @Nullable OutputStream getProcessInput() {
        return launch == null ? null : launch.handler().getProcessInput();
    }
}
