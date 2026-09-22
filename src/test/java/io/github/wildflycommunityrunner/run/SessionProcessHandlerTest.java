package io.github.wildflycommunityrunner.run;

import com.intellij.execution.process.ProcessAdapter;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.openapi.util.Key;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jetbrains.annotations.NotNull;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class SessionProcessHandlerTest extends BasePlatformTestCase {
    private static final class FakeProcess extends ProcessHandler {
        int stopCount;
        @Override protected void destroyProcessImpl() { stopCount++; notifyProcessTerminated(0); }
        @Override protected void detachProcessImpl() { notifyProcessDetached(); }
        @Override public boolean detachIsDefault() { return false; }
        @Override public OutputStream getProcessInput() { return null; }
        void exit(int code) { notifyProcessTerminated(code); }
    }

    public void testStopOwnedSessionTerminatesItsProcess() {
        var process = new FakeProcess();
        var session = new WildFlySessionProcessHandler(() -> new WildFlySessionProcessHandler.Launch(process, true), Runnable::run);
        session.begin();
        session.destroyProcess();
        assertEquals(1, process.stopCount);
        assertTrue(session.isProcessTerminated());
    }

    public void testStopReusedSessionLeavesGlobalProcessAliveAndRemovesOutputListener() {
        var process = new FakeProcess();
        process.startNotify();
        var text = new StringBuilder();
        var session = new WildFlySessionProcessHandler(() -> new WildFlySessionProcessHandler.Launch(process, false), Runnable::run);
        session.addProcessListener(new ProcessAdapter() {
            @Override public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) { text.append(event.getText()); }
        });
        session.begin();
        process.notifyTextAvailable("before", ProcessOutputTypes.STDOUT);
        session.destroyProcess();
        process.notifyTextAvailable("after", ProcessOutputTypes.STDOUT);
        assertEquals("before", text.toString());
        assertEquals(0, process.stopCount);
        assertFalse(process.isProcessTerminated());
        process.exit(0);
    }

    public void testCancelBeforeBackgroundLaunchDoesNotStartProcess() {
        var pending = new ArrayDeque<Runnable>();
        var calls = new AtomicInteger();
        var session = new WildFlySessionProcessHandler(() -> {
            calls.incrementAndGet();
            return new WildFlySessionProcessHandler.Launch(new FakeProcess(), true);
        }, pending::add);
        session.begin();
        session.destroyProcess();
        pending.remove().run();
        assertEquals(0, calls.get());
        assertTrue(session.isProcessTerminated());
    }

    public void testProcessCreatedDuringCancellationIsStoppedWhenLaunchReturns() {
        var process = new FakeProcess();
        var reference = new AtomicReference<WildFlySessionProcessHandler>();
        var session = new WildFlySessionProcessHandler(() -> {
            reference.get().destroyProcess();
            return new WildFlySessionProcessHandler.Launch(process, true);
        }, Runnable::run);
        reference.set(session);
        session.begin();
        assertEquals(1, process.stopCount);
        assertTrue(process.isProcessTerminated());
    }

    public void testFastExitIsReportedOnceEvenBeforeListenerIsRegistered() {
        var process = new FakeProcess();
        process.startNotify();
        process.exit(7);
        var terminations = new AtomicInteger();
        var session = new WildFlySessionProcessHandler(() -> new WildFlySessionProcessHandler.Launch(process, false), Runnable::run);
        session.addProcessListener(new ProcessAdapter() {
            @Override public void processTerminated(@NotNull ProcessEvent event) { terminations.incrementAndGet(); }
        });
        session.begin();
        assertEquals(Integer.valueOf(7), session.getExitCode());
        assertEquals(1, terminations.get());
    }

    public void testDetachOwnedSessionPreservesServerForOtherProjects() {
        var process = new FakeProcess();
        var session = new WildFlySessionProcessHandler(() -> new WildFlySessionProcessHandler.Launch(process, true), Runnable::run);
        session.begin();
        session.detachProcess();
        assertTrue(session.isProcessTerminated());
        assertFalse(process.isProcessTerminated());
        assertEquals(0, process.stopCount);
        process.exit(0);
    }

    public void testLaunchFailureFinishesSessionWithNonzeroExit() {
        var session = new WildFlySessionProcessHandler(() -> { throw new IllegalArgumentException("Missing startup script"); }, Runnable::run);
        session.begin();
        assertEquals(Integer.valueOf(1), session.getExitCode());
    }

    public void testProjectSessionDisposalCancelsPendingLaunchAndDetachesActiveServer() {
        var service = new WildFlySessionService();
        var pending = new ArrayDeque<Runnable>();
        var launches = new AtomicInteger();
        var queued = new WildFlySessionProcessHandler(() -> {
            launches.incrementAndGet();
            return new WildFlySessionProcessHandler.Launch(new FakeProcess(), true);
        }, pending::add);
        service.register(queued);
        queued.begin();
        var process = new FakeProcess();
        var active = new WildFlySessionProcessHandler(() -> new WildFlySessionProcessHandler.Launch(process, true), Runnable::run);
        service.register(active);
        active.begin();
        service.dispose();
        pending.remove().run();
        assertEquals(0, launches.get());
        assertTrue(queued.isProcessTerminated());
        assertTrue(active.isProcessTerminated());
        assertFalse(process.isProcessTerminated());
        process.exit(0);
    }
}
