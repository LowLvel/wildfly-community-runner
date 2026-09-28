package io.github.wildflycommunityrunner.ui;

import com.intellij.openapi.util.Disposer;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.github.wildflycommunityrunner.model.ServiceProfile;
import io.github.wildflycommunityrunner.services.OperationFeedback;
import io.github.wildflycommunityrunner.settings.WildFlyApplicationSettings;
import io.github.wildflycommunityrunner.settings.WildFlyProjectSettings;
import io.github.wildflycommunityrunner.util.IdeUi;
import javax.swing.*;
import java.awt.*;
import java.util.concurrent.atomic.AtomicReference;

public class FailurePresentationTest extends BasePlatformTestCase {
    public void testFailurePreservesSelectionAndMissingSourceDoesNotEditSelectedService() {
        var app = WildFlyApplicationSettings.getInstance();
        var settings = WildFlyProjectSettings.getInstance(getProject());
        var previousApp = app.getState();
        var previousProject = settings.getState();
        var created = new AtomicReference<WildFlyManagerPanel>();
        try {
            app.loadState(new WildFlyApplicationSettings.StateData());
            settings.loadState(new WildFlyProjectSettings.StateData());
            var service = new ServiceProfile();
            service.name = "Keep selected";
            service.deployAfterBuild = false;
            settings.update(state -> {
                state.services.add(service);
                state.showFirefly = false;
                state.reduceFireflyMotion = true;
            });
            settings.loadState(settings.getState());
            assertFalse(settings.getState().showFirefly);
            assertTrue(settings.getState().reduceFireflyMotion);
            IdeUi.later(getProject(), () -> created.set(new WildFlyManagerPanel(getProject())));
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            JTable table = find(created.get(), JTable.class);
            JTabbedPane tabs = find(created.get(), JTabbedPane.class);
            assertNotNull(table);
            assertFalse(find(created.get(), FireflyIndicator.class).isVisible());
            settings.update(state -> state.showFirefly = true);
            getProject().getMessageBus().syncPublisher(io.github.wildflycommunityrunner.services.ProjectSetupService.CHANGED).initialized();
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            assertTrue(find(created.get(), FireflyIndicator.class).isVisible());
            table.setRowSelectionInterval(0, 0);
            tabs.setSelectedIndex(0);
            var feedback = getProject().getService(OperationFeedback.class);
            var failure = feedback.report("Build failed", "Missing JDK", "deleted-service", "");
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            assertEquals(0, tabs.getSelectedIndex());
            assertEquals(1, table.getSelectedRowCount());
            assertTrue(find(created.get(), FailureBanner.class).isVisible());
            getProject().getMessageBus().syncPublisher(OperationFeedback.NAVIGATE)
                    .open(failure, OperationFeedback.Action.SERVICE_SETTINGS);
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            assertEquals(1, tabs.getSelectedIndex());
            assertEquals(1, table.getSelectedRowCount());
            feedback.dismiss(failure);
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            assertFalse(find(created.get(), FailureBanner.class).isVisible());
        } finally {
            if (created.get() != null) Disposer.dispose(created.get());
            app.loadState(previousApp);
            settings.loadState(previousProject);
        }
    }

    public void testBannerKeepsDetailsLiteralAndUsesOriginalContext() {
        var opened = new AtomicReference<OperationFeedback.Failure>();
        var banner = new FailureBanner((failure, action) -> opened.set(failure), ignored -> {});
        var failure = new OperationFeedback.Failure("Build failed", "<html>literal error</html>",
                "Check JDK", OperationFeedback.Kind.BUILD, "source-id", "server-id");
        banner.showFailure(failure);
        assertTrue(find(banner, JTextArea.class).getText().contains("<html>literal error</html>"));
        button(banner, "Edit service").doClick();
        assertSame(failure, opened.get());
        banner.showFailure(null);
        assertFalse(banner.isVisible());
    }

    private static JButton button(Container parent, String text) {
        for (Component component : parent.getComponents()) {
            if (component instanceof JButton value && text.equals(value.getText())) return value;
            if (component instanceof Container child) {
                JButton value = button(child, text);
                if (value != null) return value;
            }
        }
        return null;
    }
    private static <T> T find(Container parent, Class<T> type) {
        for (Component component : parent.getComponents()) {
            if (type.isInstance(component)) return type.cast(component);
            if (component instanceof Container child) {
                T value = find(child, type);
                if (value != null) return value;
            }
        }
        return null;
    }
}
