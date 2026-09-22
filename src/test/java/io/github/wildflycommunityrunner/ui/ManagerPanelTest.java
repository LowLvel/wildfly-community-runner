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
import org.junit.rules.TemporaryFolder;

public class ManagerPanelTest extends BasePlatformTestCase {
    public void testSnapshotRefreshKeepsSelectedIdsAndAutoCheckboxWritesThrough() {
        var app = WildFlyApplicationSettings.getInstance();
        var settings = WildFlyProjectSettings.getInstance(getProject());
        var previousApp = app.getState();
        var previousProject = settings.getState();
        var created = new AtomicReference<WildFlyManagerPanel>();
        try {
            app.loadState(new WildFlyApplicationSettings.StateData());
            settings.loadState(new WildFlyProjectSettings.StateData());
            var first = new ServiceProfile(); first.name = "First"; first.deployAfterBuild = false;
            var second = new ServiceProfile(); second.name = "Second"; second.deployAfterBuild = false;
            settings.update(state -> { state.services.add(first); state.services.add(second); });
            IdeUi.later(getProject(), () -> created.set(new WildFlyManagerPanel(getProject())));
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            JTable table = findServiceTable(created.get());
            assertNotNull(table);
            table.setRowSelectionInterval(0, 1);
            table.getModel().setValueAt(true, 1, 0);
            assertTrue(settings.services().stream().filter(service -> service.id.equals(second.id)).findFirst().orElseThrow().deployAfterBuild);
            var replacement = new ServiceProfile(); replacement.name = "Replacement"; replacement.deployAfterBuild = false;
            settings.update(state -> { state.services.removeIf(service -> service.id.equals(first.id)); state.services.add(replacement); });
            getProject().getMessageBus().syncPublisher(io.github.wildflycommunityrunner.services.ProjectSetupService.CHANGED).initialized();
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            assertEquals(2, table.getRowCount());
            assertEquals(1, table.getSelectedRowCount());
            assertTrue(table.getValueAt(table.getSelectedRow(), 1).toString().contains("Second"));
        } finally {
            if (created.get() != null) Disposer.dispose(created.get());
            app.loadState(previousApp);
            settings.loadState(previousProject);
        }
    }

    public void testRawSwingCanCreatePanelAndRenderingUsesCachedStatusAndTimestamp() throws Exception {
        var temporary = new TemporaryFolder();
        temporary.create();
        var app = WildFlyApplicationSettings.getInstance();
        var projectSettings = WildFlyProjectSettings.getInstance(getProject());
        var previousApp = app.getState();
        var previousProject = projectSettings.getState();
        var created = new AtomicReference<WildFlyManagerPanel>();
        try {
            app.loadState(new WildFlyApplicationSettings.StateData());
            projectSettings.loadState(new WildFlyProjectSettings.StateData());
            var server = new ServerProfile();
            server.home = temporary.getRoot().getAbsolutePath();
            app.update(state -> state.servers.add(server));
            projectSettings.update(state -> state.selectedServerId = server.id);
            var service = new ServiceProfile();
            service.name = "api";
            service.deploymentName = "api.war";
            service.deployAfterBuild = false;
            projectSettings.update(state -> state.services.add(service));
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
            temporary.delete();
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
