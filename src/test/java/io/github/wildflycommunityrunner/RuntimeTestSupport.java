package io.github.wildflycommunityrunner;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.testFramework.PlatformTestUtil;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;

/** Pump the platform queue while real processes run; never block their EDT launch callbacks. */
public final class RuntimeTestSupport {
    private RuntimeTestSupport() {}
    public static void await(BooleanSupplier done, Duration timeout, String detail) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!done.getAsBoolean() && System.nanoTime() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            Thread.sleep(20);
        }
        if (!done.getAsBoolean()) throw new AssertionError(detail);
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
    }
    public static <T> T background(Callable<T> action) throws Exception {
        Future<T> future = ApplicationManager.getApplication().executeOnPooledThread(action);
        await(future::isDone, Duration.ofMinutes(3), "Runtime fixture background operation timed out");
        return future.get();
    }
}
