package io.github.wildflycommunityrunner.run;

import com.intellij.execution.process.ProcessListener;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.progress.ProcessCanceledException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.io.OutputStream;
import java.util.concurrent.Executor;
import io.github.wildflycommunityrunner.security.SecretRedactor;
import io.github.wildflycommunityrunner.security.SensitiveProperties;

/** A session owns only the process it started; observing another project's server never grants stop ownership. */
final class WildFlySessionProcessHandler extends ProcessHandler {
    record Launch(ProcessHandler handler, boolean ownsProcess) {}
    @FunctionalInterface interface Launcher { Launch launch() throws Exception; }
    @FunctionalInterface interface AfterLaunch {
        void run(java.util.function.BooleanSupplier cancelled, java.util.function.Consumer<String> output) throws Exception;
    }
    private enum Request { NONE, STOP, DETACH }

    private final Launcher launcher;
    private final Executor executor;
    private final AfterLaunch afterLaunch;
    private Request request = Request.NONE;
    private Launch launch;
    private SecretRedactor redactor = new SecretRedactor(java.util.List.of());
    private final java.util.Map<Key<?>, SecretRedactor.Lines> streams = new java.util.concurrent.ConcurrentHashMap<>();
    private final ProcessListener listener = new ProcessListener() {
        @Override public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
            if (!isProcessTerminated()) streams.computeIfAbsent(outputType,
                    key -> redactor.new Lines(text -> notifyTextAvailable(text, key))).accept(event.getText());
        }
        @Override public void processTerminated(@NotNull ProcessEvent event) { finish(event.getExitCode()); }
    };

    WildFlySessionProcessHandler(Launcher launcher, Executor executor) {
        this(launcher, executor, null);
    }

    WildFlySessionProcessHandler(Launcher launcher, Executor executor, AfterLaunch afterLaunch) {
        this.launcher = launcher;
        this.executor = executor;
        this.afterLaunch = afterLaunch;
    }

    private synchronized boolean cancelled() { return request != Request.NONE || isProcessTerminated(); }

    void begin() {
        startNotify();
        executor.execute(() -> {
            synchronized (this) { if (request != Request.NONE) return; }
            try {
                Launch result = launcher.launch();
                Request pending;
                synchronized (this) {
                    launch = result;
                    SecretRedactor processRedactor = result.handler().getUserData(SecretRedactor.PROCESS);
                    if (processRedactor != null) redactor = processRedactor;
                    pending = request;
                    if (pending == Request.NONE) result.handler().addProcessListener(listener);
                }
                if (result.ownsProcess()) result.handler().startNotify();
                if (pending == Request.STOP && result.ownsProcess()) result.handler().destroyProcess();
                if (pending == Request.NONE) {
                    Integer exit = result.handler().getExitCode();
                    if (exit != null) finish(exit);
                    else if (afterLaunch != null) afterLaunch.run(this::cancelled,
                            text -> notifyTextAvailable(redactor.redact(text) + "\n", ProcessOutputTypes.STDOUT));
                }
            } catch (ProcessCanceledException cancelled) {
                finish(130);
                throw cancelled;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                finish(130);
            } catch (Exception e) {
                Launch current;
                synchronized (this) { current = launch; }
                if (current != null && current.ownsProcess()) current.handler().destroyProcess();
                if (!isProcessTerminated()) {
                    notifyTextAvailable("Cannot start WildFly: " + SensitiveProperties.redactProperties(e.getMessage()) + "\n", ProcessOutputTypes.STDERR);
                    finish(1);
                }
            }
        });
    }

    private synchronized void finish(int code) {
        if (launch != null) launch.handler().removeProcessListener(listener);
        streams.values().forEach(SecretRedactor.Lines::finish);
        streams.clear();
        redactor = new SecretRedactor(java.util.List.of());
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
            streams.values().forEach(SecretRedactor.Lines::finish);
            streams.clear();
            redactor = new SecretRedactor(java.util.List.of());
            notifyProcessDetached();
        } else {
            notifyProcessTerminated(130);
        }
    }

    @Override protected synchronized void detachProcessImpl() {
        request = Request.DETACH;
        streams.values().forEach(SecretRedactor.Lines::finish);
        streams.clear();
        redactor = new SecretRedactor(java.util.List.of());
        if (launch != null) launch.handler().removeProcessListener(listener);
        notifyProcessDetached();
    }

    @Override public boolean detachIsDefault() { return true; }
    @Override public synchronized @Nullable OutputStream getProcessInput() {
        return launch == null ? null : launch.handler().getProcessInput();
    }
}
