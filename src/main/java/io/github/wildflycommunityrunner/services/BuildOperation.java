package io.github.wildflycommunityrunner.services;

import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessListener;
import com.intellij.openapi.progress.ProcessCanceledException;
import org.jetbrains.annotations.NotNull;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Owns one build, including a process arriving after cancellation was requested. */
public final class BuildOperation {
    public enum Outcome { SUCCESS, FAILED, CANCELLED }
    public record Result(Outcome outcome, String detail) {}
    private final CompletableFuture<Result> completion = new CompletableFuture<>();
    private final Executor processExecutor;
    private final List<Runnable> cleanup = new ArrayList<>();
    private ProcessHandler process;
    private boolean launching;
    private boolean cancelled;
    private boolean finished;
    private boolean stopRequested;
    private String failure;
    private Runnable cancelLaunch = () -> {};
    private final ProcessListener listener = new ProcessListener() {
        @Override public void processTerminated(@NotNull ProcessEvent event) { exited(event.getExitCode()); }
    };

    public BuildOperation(Executor processExecutor) { this.processExecutor = processExecutor; }
    public CompletableFuture<Result> completion() { return completion; }

    /** Must be the last gate before creating a process or scheduling a native runner. */
    public synchronized boolean beginLaunch() {
        if (finished || cancelled || launching) return false;
        launching = true;
        return true;
    }

    public void onCancelLaunch(Runnable action) {
        boolean run;
        synchronized (this) { cancelLaunch = action; run = cancelled; }
        if (run) action.run();
    }

    public void onFinished(Runnable action) {
        boolean run;
        synchronized (this) { run = finished; if (!run) cleanup.add(action); }
        if (run) action.run();
    }

    /** Register before startNotify when possible; the exit-code check also handles a fast runner. */
    public void bind(ProcessHandler handler) {
        synchronized (this) {
            if (process == handler) return;
            if (process != null) throw new IllegalStateException("A build cannot own two processes");
            process = handler;
            if (!finished) handler.addProcessListener(listener);
        }
        Integer exit = handler.getExitCode();
        if (exit != null) exited(exit);
        stopIfRequested();
    }

    public void cancel() {
        Runnable hook;
        boolean queued;
        synchronized (this) {
            if (finished || cancelled) return;
            cancelled = true;
            hook = cancelLaunch;
            queued = !launching;
        }
        hook.run();
        if (queued) finish(new Result(Outcome.CANCELLED, "Build cancelled"));
        stopIfRequested();
    }

    public void failed(Throwable error) {
        boolean hasProcess;
        boolean cancellation = error instanceof ProcessCanceledException || error instanceof InterruptedException;
        synchronized (this) {
            if (finished) return;
            if (cancellation) cancelled = true;
            else failure = PluginNotifications.message(error);
            hasProcess = process != null;
        }
        if (error instanceof InterruptedException) Thread.currentThread().interrupt();
        if (hasProcess) stopIfRequested();
        else finish(result(1));
    }

    private void stopIfRequested() {
        ProcessHandler owned;
        synchronized (this) {
            if ((!cancelled && failure == null) || process == null || stopRequested || process.isProcessTerminated()) return;
            stopRequested = true;
            owned = process;
        }
        processExecutor.execute(owned::destroyProcess);
    }

    private void exited(int code) { finish(result(code)); }
    private synchronized Result result(int code) {
        // processWillTerminate also fires on natural exit. IntelliJ marks a user Stop explicitly.
        if (cancelled || process != null && Boolean.TRUE.equals(process.getUserData(ProcessHandler.TERMINATION_REQUESTED))) {
            return new Result(Outcome.CANCELLED, "Build cancelled");
        }
        if (failure != null) return new Result(Outcome.FAILED, failure);
        return new Result(code == 0 ? Outcome.SUCCESS : Outcome.FAILED,
                code == 0 ? "Build succeeded" : "Build exited with code " + code);
    }

    private void finish(Result result) {
        List<Runnable> actions;
        synchronized (this) {
            if (finished) return;
            finished = true;
            if (process != null) process.removeProcessListener(listener);
            actions = List.copyOf(cleanup);
            cleanup.clear();
            cancelLaunch = () -> {};
        }
        try { for (Runnable action : actions) action.run(); }
        finally { completion.complete(result); }
    }

    /** Project disposal must not wait for an IDE launch queue that is itself being disposed. */
    public void dispose() {
        cancel();
        boolean pendingLaunch;
        synchronized (this) { pendingLaunch = process == null; }
        if (pendingLaunch) finish(new Result(Outcome.CANCELLED, "Project closed"));
    }
}
