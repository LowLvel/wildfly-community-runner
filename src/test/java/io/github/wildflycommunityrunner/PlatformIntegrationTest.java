package io.github.wildflycommunityrunner;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.github.wildflycommunityrunner.services.WildFlyProcessService;
import io.github.wildflycommunityrunner.settings.WildFlyApplicationSettings;
import io.github.wildflycommunityrunner.settings.WildFlyProjectSettings;
import java.util.concurrent.atomic.AtomicBoolean;

public class PlatformIntegrationTest extends BasePlatformTestCase {
    public void testApplicationAndProjectServicesAreRegisteredInTheirIntendedScope() {
        var app = ApplicationManager.getApplication();
        assertSame(app.getService(WildFlyApplicationSettings.class), WildFlyApplicationSettings.getInstance());
        assertSame(app.getService(WildFlyProcessService.class), WildFlyProcessService.getInstance(getProject()));
        assertSame(getProject().getService(WildFlyProjectSettings.class), WildFlyProjectSettings.getInstance(getProject()));
    }

    public void testApplicationQueueProvidesWriteIntentForPlatformCallbacks() {
        var app = ApplicationManager.getApplication();
        var called = new AtomicBoolean();
        app.invokeLater(() -> {
            assertTrue(app.isDispatchThread());
            assertTrue("Platform callbacks need write intent on IDEA 2025.1+", app.isWriteIntentLockAcquired());
            called.set(true);
        }, ModalityState.nonModal());
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        assertTrue(called.get());
    }
}
