package io.github.wildflycommunityrunner.services;

import com.intellij.execution.process.ProcessHandler;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicInteger;

public class BuildOperationTest extends BasePlatformTestCase {
    static final class Process extends ProcessHandler {
        int stops;
        @Override protected void destroyProcessImpl() { stops++; }
        @Override protected void detachProcessImpl() { notifyProcessDetached(); }
        @Override public boolean detachIsDefault() { return false; }
        @Override public OutputStream getProcessInput() { return null; }
        void exit(int code) { notifyProcessTerminated(code); }
    }

    public void testQueuedCancellationPreventsLatePreparedLaunch() {
        var operation = new BuildOperation(Runnable::run);
        operation.cancel();
        assertFalse(operation.beginLaunch());
        assertEquals(BuildOperation.Outcome.CANCELLED, operation.completion().join().outcome());
    }

    public void testCancellationDuringLaunchWaitsForLateOwnedProcessToExit() {
        var stops = new ArrayDeque<Runnable>();
        var operation = new BuildOperation(stops::add);
        var process = new Process();
        assertTrue(operation.beginLaunch());
        operation.cancel();
        assertFalse(operation.completion().isDone());
        operation.bind(process);
        operation.bind(process);
        process.startNotify();
        stops.remove().run();
        assertEquals(1, process.stops);
        assertFalse(operation.completion().isDone());
        process.exit(0);
        assertEquals(BuildOperation.Outcome.CANCELLED, operation.completion().join().outcome());
        assertTrue(stops.isEmpty());
    }

    public void testFastExitAndLateFailureCompleteAndCleanUpOnce() {
        var process = new Process();
        process.startNotify();
        process.exit(7);
        var operation = new BuildOperation(Runnable::run);
        var cleanups = new AtomicInteger();
        var completions = new AtomicInteger();
        operation.onFinished(cleanups::incrementAndGet);
        operation.completion().thenRun(completions::incrementAndGet);
        assertTrue(operation.beginLaunch());
        operation.bind(process);
        operation.failed(new IllegalStateException("late callback"));
        operation.bind(process);
        operation.cancel();
        assertEquals(BuildOperation.Outcome.FAILED, operation.completion().join().outcome());
        assertTrue(operation.completion().join().detail().contains("7"));
        assertEquals(1, cleanups.get());
        assertEquals(1, completions.get());
    }

    public void testNativeConsoleStopIsCancellationEvenWithZeroExit() {
        var process = new Process();
        var operation = new BuildOperation(Runnable::run);
        operation.beginLaunch();
        operation.bind(process);
        process.startNotify();
        process.putUserData(ProcessHandler.TERMINATION_REQUESTED, true);
        process.destroyProcess();
        process.exit(0);
        assertEquals(BuildOperation.Outcome.CANCELLED, operation.completion().join().outcome());
    }

    public void testLaunchFailureAndPlatformCancellationBothFinishWithoutAProcess() {
        var failure = new BuildOperation(Runnable::run);
        failure.beginLaunch();
        failure.failed(new IllegalStateException("Missing Java"));
        assertEquals(BuildOperation.Outcome.FAILED, failure.completion().join().outcome());
        assertEquals("Missing Java", failure.completion().join().detail());
        var cancelled = new BuildOperation(Runnable::run);
        cancelled.beginLaunch();
        cancelled.failed(new ProcessCanceledException());
        assertEquals(BuildOperation.Outcome.CANCELLED, cancelled.completion().join().outcome());
    }

    public void testProjectDisposalStillStopsAProcessArrivingAfterQueueDisposal() {
        var operation = new BuildOperation(Runnable::run);
        var process = new Process();
        process.startNotify();
        operation.beginLaunch();
        operation.dispose();
        assertTrue(operation.completion().isDone());
        operation.bind(process);
        assertEquals(1, process.stops);
        process.exit(0);
    }
}
