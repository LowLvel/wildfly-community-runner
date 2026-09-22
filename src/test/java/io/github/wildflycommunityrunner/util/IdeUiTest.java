package io.github.wildflycommunityrunner.util;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import javax.swing.SwingUtilities;
import java.util.concurrent.atomic.AtomicBoolean;

public class IdeUiTest extends BasePlatformTestCase {
    public void testRawSwingCallbackEntersWriteIntentBeforePlatformAction() {
        var called = new AtomicBoolean();
        SwingUtilities.invokeLater(() -> IdeUi.later(getProject(), () -> {
            var app = ApplicationManager.getApplication();
            assertTrue(app.isDispatchThread());
            assertTrue("Swing events must enter the platform application queue", app.isWriteIntentLockAcquired());
            called.set(true);
        }));
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        assertTrue(called.get());
    }

    public void testDisposalAfterSchedulingExpiresQueuedAction() {
        var disposed = new AtomicBoolean();
        var called = new AtomicBoolean();
        IdeUi.later(getProject(), disposed::get, () -> called.set(true));
        disposed.set(true);
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        assertFalse(called.get());
    }

    public void testAlreadyDisposedOwnerNeverSchedulesWork() {
        IdeUi.later(getProject(), () -> true, () -> fail("Disposed UI must not run queued platform work"));
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
    }
}
