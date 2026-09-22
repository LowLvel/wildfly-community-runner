package io.github.wildflycommunityrunner.services;

import com.intellij.notification.NotificationType;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public class OperationFailureTest extends BasePlatformTestCase {
    public void testRegisteredNotificationEscapesUntrustedFailureContent() {
        var notification = PluginNotifications.createFailure("Deploy failed", "<a href='bad'>artifact</a> & details");
        assertEquals(NotificationType.ERROR, notification.getType());
        assertFalse(notification.getContent().contains("<a "));
        assertTrue(notification.getContent().contains("&lt;a"));
        assertTrue(notification.getContent().contains("&amp; details"));
        notification.expire();
    }

    public void testFailureWithoutMessageStillHasUsefulDescription() {
        assertEquals("IllegalArgumentException", PluginNotifications.message(new IllegalArgumentException()));
    }

    public void testCancellationCompletesOnceWithoutTurningIntoFailureNotification() {
        var completions = new ArrayList<Boolean>();
        var output = new ArrayList<String>();
        try {
            DeploymentScannerService.complete(getProject(), "Deploy failed", output::add, completions::add,
                    () -> { throw new ProcessCanceledException(); });
            fail("Cancellation must propagate");
        } catch (ProcessCanceledException expected) {
            assertEquals(java.util.List.of(false), completions);
            assertTrue(output.isEmpty());
        }
    }

    public void testInterruptedOperationCompletesOnceAndPreservesInterrupt() {
        var completions = new ArrayList<Boolean>();
        var output = new ArrayList<String>();
        try {
            DeploymentScannerService.complete(getProject(), "Deploy failed", output::add, completions::add,
                    () -> { throw new InterruptedException(); });
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(java.util.List.of(false), completions);
            assertTrue(output.isEmpty());
        } finally { Thread.interrupted(); }
    }

    public void testSuccessfulOperationCompletesExactlyOnce() {
        var calls = new AtomicInteger();
        DeploymentScannerService.complete(getProject(), "Deploy failed", ignored -> fail(), success -> {
            assertTrue(success);
            calls.incrementAndGet();
        }, () -> true);
        assertEquals(1, calls.get());
    }
}
