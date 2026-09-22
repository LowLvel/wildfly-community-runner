package io.github.wildflycommunityrunner.ui;

import com.intellij.openapi.util.Disposer;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.github.wildflycommunityrunner.model.ServerProfile;
import io.github.wildflycommunityrunner.model.ServiceProfile;
import io.github.wildflycommunityrunner.settings.WildFlyApplicationSettings;
import io.github.wildflycommunityrunner.settings.WildFlyProjectSettings;
import io.github.wildflycommunityrunner.util.IdeUi;
import io.github.wildflycommunityrunner.util.WildFlyPaths;
import javax.swing.*;
import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

public class ManagerPanelTest extends BasePlatformTestCase {
    public void testRawSwingCanCreatePanelAndRenderingUsesCachedStatusAndTimestamp() throws Exception {
        var app = WildFlyApplicationSettings.getInstance();
        var projectSettings = WildFlyProjectSettings.getInstance(getProject());
        var previousApp = app.getState();
        var previousProject = projectSettings.getState();
        var created = new AtomicReference<WildFlyManagerPanel>();
        try {
            app.loadState(new WildFlyApplicationSettings.StateData());
            projectSettings.loadState(new WildFlyProjectSettings.StateData());
            var server = new ServerProfile();
            server.home = myFixture.getTempDirFixture().getTempDirPath();
            app.servers().add(server);
            projectSettings.getState().selectedServerId = server.id;
            var service = new ServiceProfile();
            service.name = "api";
            service.deploymentName = "api.war";
            service.deployAfterBuild = false;
            projectSettings.services().add(service);
            Path dir = WildFlyPaths.deploymentsDir(server);
            Files.createDirectories(dir);
            Path marker = Files.writeString(dir.resolve("api.war.deployed"), "");
            SwingUtilities.invokeLater(() -> IdeUi.later(getProject(), () -> created.set(new WildFlyManagerPanel(getProject()))));
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            assertNotNull(created.get());
            JTable table = findServiceTable(created.get());
            assertNotNull(table);
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
            while (!status(table).getText().contains("Deployed") && System.nanoTime() < deadline) {
                Thread.sleep(10);
                PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            }
            assertEquals("● Deployed", status(table).getText());
            assertTrue(status(table).getToolTipText().startsWith("Deployed: "));
            Files.delete(marker);
            // Painting reads the last immutable snapshot, not the filesystem on EDT.
            assertEquals("● Deployed", status(table).getText());
        } finally {
            if (created.get() != null) Disposer.dispose(created.get());
            app.loadState(previousApp);
            projectSettings.loadState(previousProject);
        }
    }

    private static JTable findServiceTable(Container parent) {
        for (Component component : parent.getComponents()) {
            if (component instanceof JTable table && table.getColumnCount() == 4) return table;
            if (component instanceof Container child) {
                JTable table = findServiceTable(child);
                if (table != null) return table;
            }
        }
        return null;
    }

    private static JLabel status(JTable table) {
        return (JLabel) table.prepareRenderer(table.getCellRenderer(0, 3), 0, 3);
    }
}
