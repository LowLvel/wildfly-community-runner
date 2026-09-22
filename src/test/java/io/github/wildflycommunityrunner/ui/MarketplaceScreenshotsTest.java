package io.github.wildflycommunityrunner.ui;

import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.IconLoader;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.actionSystem.impl.PresentationFactory;
import com.intellij.ui.icons.IconPathProvider;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.github.wildflycommunityrunner.model.ServerProfile;
import io.github.wildflycommunityrunner.model.ServiceProfile;
import io.github.wildflycommunityrunner.settings.WildFlyApplicationSettings;
import io.github.wildflycommunityrunner.settings.WildFlyProjectSettings;
import io.github.wildflycommunityrunner.util.IdeUi;
import io.github.wildflycommunityrunner.util.WildFlyPaths;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.*;
import static io.github.wildflycommunityrunner.RuntimeTestSupport.*;

/** Paints actual production components in the platform test application's default theme. */
public class MarketplaceScreenshotsTest extends BasePlatformTestCase {
    public void testRenderServicesAndServerLogPanelsForDocumentation() throws Exception {
        var app = WildFlyApplicationSettings.getInstance();
        var project = WildFlyProjectSettings.getInstance(getProject());
        var oldApp = app.getState(); var oldProject = project.getState();
        var created = new AtomicReference<WildFlyManagerPanel>();
        Path fixture = Path.of(System.getProperty("java.io.tmpdir"), "wildfly-marketplace-sample");
        assertFalse("Screenshot fixture must start clean", Files.exists(fixture));
        var server = new ServerProfile(); server.name = "Development"; server.home = fixture.resolve("wildfly").toString();
        try {
            IconLoader.activate();
            Path configuration = WildFlyPaths.configurationFile(server);
            Files.createDirectories(configuration.getParent());
            Files.writeString(configuration, "<server><deployment-scanner path='deployments' relative-to='jboss.server.base.dir'/></server>");
            Path deployments = WildFlyPaths.deploymentsDir(server); Files.createDirectories(deployments);
            Files.writeString(deployments.resolve("billing-api.war.deployed"), "sample scanner marker");
            Files.writeString(deployments.resolve("orders-api.war.deployed"), "sample scanner marker");
            Files.writeString(deployments.resolve("admin-console.war.deployed"), "sample external deployment");
            app.loadState(new WildFlyApplicationSettings.StateData());
            project.loadState(new WildFlyProjectSettings.StateData());
            app.update(state -> state.servers.add(server));
            var services = new ArrayList<ServiceProfile>();
            services.add(service("billing-api", "backend/billing/api", "MAVEN", true));
            services.add(service("orders-api", "backend/orders/api", "GRADLE", true));
            services.add(service("reporting", "backend/reporting", "MAVEN", false));
            project.update(state -> { state.selectedServerId = server.id; state.services.addAll(services); });
            IdeUi.later(getProject(), () -> created.set(new WildFlyManagerPanel(getProject())));
            await(() -> created.get() != null, Duration.ofSeconds(10), "Manager panel did not initialize");
            WildFlyManagerPanel panel = created.get();
            List<JTable> tables = components(panel, JTable.class);
            JTable table = tables.stream().filter(value -> value.getColumnCount() == 4).findFirst().orElseThrow();
            await(() -> tables.stream().anyMatch(value -> value != table && value.getRowCount() == 1),
                    Duration.ofSeconds(15), "Sample external deployment did not appear");
            table.setRowSelectionInterval(0, 1);
            capture(panel, "services.png", "Services and external deployments");

            Path log = WildFlyPaths.logFile(server); Files.createDirectories(log.getParent());
            Files.writeString(log, """
                    10:12:04 INFO  [org.jboss.as] WildFly started in standalone mode
                    10:12:05 INFO  [org.jboss.as.server] Deployed billing-api.war
                    10:12:06 INFO  [org.jboss.as.server] Deployed orders-api.war
                    10:14:28 INFO  [sample.billing] Request completed in 42 ms
                    10:14:29 WARN  [sample.orders] Downstream request retry scheduled
                    10:14:30 INFO  [sample.orders] Downstream connection recovered
                    10:15:02 INFO  [org.jboss.as.server] Redeployed billing-api.war
                    10:15:03 INFO  [sample.billing] Health check passed
                    """);
            JTabbedPane tabs = components(panel, JTabbedPane.class).getFirst();
            ServerLogPanel viewer = components(panel, ServerLogPanel.class).getFirst();
            tabs.setSelectedComponent(viewer); viewer.setActive(true);
            background(() -> { viewer.pollNow(); return null; });
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            assertTrue(viewer.displayedText().contains("Health check passed"));
            viewer.setPaused(true);
            capture(panel, "server-log.png", "Read server.log without leaving the tool window");
        } finally {
            if (created.get() != null) Disposer.dispose(created.get());
            IconLoader.deactivate();
            app.loadState(oldApp); project.loadState(oldProject);
            if (Files.exists(fixture)) try (var files = Files.walk(fixture)) {
                for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
    private ServiceProfile service(String name, String module, String system, boolean auto) {
        var service = new ServiceProfile(); service.name = name; service.buildSystem = system;
        service.buildFilePath = Path.of(getProject().getBasePath(), module, system.equals("MAVEN") ? "pom.xml" : "build.gradle").toString();
        service.deploymentName = name + ".war"; service.deployAfterBuild = auto;
        return service;
    }
    private static void capture(JComponent panel, String file, String subtitle) throws Exception {
        // Rasterize at a larger scale with the same aspect ratio for every image.
        var canvas = new JPanel(new BorderLayout(0, 12));
        canvas.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));
        var heading = new JPanel(new GridLayout(0, 1, 0, 4));
        var title = new JLabel("WildFly Community Runner"); title.setFont(title.getFont().deriveFont(Font.BOLD, 18));
        heading.add(title); heading.add(new JLabel(subtitle));
        canvas.add(heading, BorderLayout.NORTH); canvas.add(panel, BorderLayout.CENTER);
        canvas.add(new JLabel("Sample workspace · actual plugin panels rendered in the IntelliJ test application"), BorderLayout.SOUTH);
        // A detached headless table has not received addNotify's usual header setup.
        for (JScrollPane scroll : components(panel, JScrollPane.class)) {
            if (scroll.getViewport().getView() instanceof JTable table) scroll.setColumnHeaderView(table.getTableHeader());
        }
        canvas.setSize(1000, 625);
        var image = new BufferedImage(1280, 800, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        var originalActionIcons = new java.util.IdentityHashMap<Presentation, Icon>();
        canvas.addNotify();
        try {
            layout(canvas); layout(canvas);
            var updates = components(panel, ActionToolbar.class).stream().map(ActionToolbar::updateActionsAsync).toList();
            await(() -> updates.stream().allMatch(java.util.concurrent.Future::isDone), Duration.ofSeconds(10), "Screenshot toolbar did not update");
            for (ActionToolbar toolbar : components(panel, ActionToolbar.class)) {
                for (var action : toolbar.getActions()) {
                    Presentation presentation = action.getTemplatePresentation();
                    originalActionIcons.putIfAbsent(presentation, presentation.getIcon());
                    presentation.setIcon(renderedIcon(presentation.getIcon()));
                    PresentationFactory.updatePresentation(action);
                }
            }
            var renderedUpdates = components(panel, ActionToolbar.class).stream().map(ActionToolbar::updateActionsAsync).toList();
            await(() -> renderedUpdates.stream().allMatch(java.util.concurrent.Future::isDone), Duration.ofSeconds(10), "Screenshot icons did not update");
            // Platform tests create placeholder AllIcons before this fixture starts.
            // Resolve those exact original resources through the public loader for capture.
            for (AbstractButton button : components(panel, AbstractButton.class)) {
                button.setIcon(renderedIcon(button.getIcon()));
            }
            layout(canvas); layout(canvas);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            // paint preserves selection; printAll deliberately removes table selection.
            graphics.scale(1.28, 1.28); canvas.paint(graphics);
        } finally {
            originalActionIcons.forEach(Presentation::setIcon);
            graphics.dispose(); canvas.removeNotify(); canvas.remove(panel);
        }
        Path directory = Path.of(System.getProperty("wildfly.test.screenshots")); Files.createDirectories(directory);
        assertTrue(ImageIO.write(image, "png", directory.resolve(file).toFile()));
        assertTrue(Files.size(directory.resolve(file)) > 10000);
    }
    private static Icon renderedIcon(Icon original) {
        if (original instanceof IconPathProvider icon && icon.getOriginalPath() != null) {
            String path = icon.getOriginalPath();
            return IconLoader.getIcon(path.startsWith("/") ? path : "/" + path, AllIcons.class);
        }
        return original;
    }
    private static void layout(Container parent) { parent.doLayout(); for (Component child : parent.getComponents()) if (child instanceof Container nested) layout(nested); }
    private static <T> List<T> components(Container root, Class<T> type) {
        var found = new ArrayList<T>();
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) found.add(type.cast(child));
            if (child instanceof Container nested) found.addAll(components(nested, type));
        }
        return found;
    }
}
