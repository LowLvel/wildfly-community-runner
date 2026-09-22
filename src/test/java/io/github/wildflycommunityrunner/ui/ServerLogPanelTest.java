package io.github.wildflycommunityrunner.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.github.wildflycommunityrunner.model.ServerProfile;
import io.github.wildflycommunityrunner.util.WildFlyPaths;
import org.junit.rules.TemporaryFolder;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;

public class ServerLogPanelTest extends BasePlatformTestCase {
    private TemporaryFolder temporary;
    private ServerLogPanel panel;
    @Override protected void setUp() throws Exception {
        super.setUp(); temporary = new TemporaryFolder(); temporary.create();
        panel = new ServerLogPanel(getProject(), ignored -> {}, false); panel.setActive(true);
    }
    @Override protected void tearDown() throws Exception {
        try { panel.dispose(); temporary.delete(); } finally { super.tearDown(); }
    }
    private ServerProfile server(String name, String log) throws Exception {
        ServerProfile profile = new ServerProfile(); profile.name = name;
        profile.home = temporary.getRoot().toPath().resolve(name).toString();
        var file = WildFlyPaths.logFile(profile); Files.createDirectories(file.getParent()); Files.writeString(file, log);
        return profile;
    }
    private void read() throws Exception {
        ApplicationManager.getApplication().executeOnPooledThread(panel::pollNow).get(10, TimeUnit.SECONDS);
    }
    private void deliver() { PlatformTestUtil.dispatchAllEventsInIdeEventQueue(); }

    public void testQueuedReadCannotReplaceANewlySelectedServer() throws Exception {
        ServerProfile first = server("first", "first server\n"), second = server("second", "second server\n");
        panel.setProfile(first); read(); panel.setProfile(second); deliver();
        assertEquals("", panel.displayedText());
        read(); deliver(); assertEquals("second server\n", panel.displayedText());
    }

    public void testPauseClearReloadAndVisibilityDoNotChangeTheLogFile() throws Exception {
        var profile = server("server", "first\n"); panel.setProfile(profile); read(); deliver();
        panel.setPaused(true);
        Files.writeString(WildFlyPaths.logFile(profile), "second\n", StandardOpenOption.APPEND);
        read(); deliver(); assertEquals("first\n", panel.displayedText());
        panel.setPaused(false); read(); deliver(); assertEquals("first\nsecond\n", panel.displayedText());
        panel.clearView(); read(); deliver(); assertEquals("", panel.displayedText());
        assertEquals("first\nsecond\n", Files.readString(WildFlyPaths.logFile(profile)));
        panel.setPaused(true); panel.reloadTail(); read(); deliver(); assertEquals("first\nsecond\n", panel.displayedText());
        assertTrue(panel.displayedStatus().startsWith("Paused"));
        panel.setPaused(false); panel.setActive(false);
        Files.writeString(WildFlyPaths.logFile(profile), "hidden\n", StandardOpenOption.APPEND);
        read(); deliver(); assertFalse(panel.displayedText().contains("hidden"));
        panel.setActive(true); read(); deliver(); assertTrue(panel.displayedText().endsWith("hidden\n"));
    }

    public void testPausingExpiresQueuedUpdatesButResumeKeepsTheirReadContent() throws Exception {
        var profile = server("server", "first\n"); panel.setProfile(profile); read(); deliver();
        Files.writeString(WildFlyPaths.logFile(profile), "second\n", StandardOpenOption.APPEND);
        read(); panel.setPaused(true); deliver();
        assertEquals("first\n", panel.displayedText());
        panel.setPaused(false); read(); deliver();
        assertEquals("first\nsecond\n", panel.displayedText());
    }

    public void testDisposedViewsRejectQueuedReadResults() throws Exception {
        panel.setProfile(server("server", "late result\n")); read(); panel.dispose(); deliver();
        assertEquals("", panel.displayedText());
    }

    public void testFilteringIsLiteralCaseInsensitiveAndDoesNotRewriteRetainedText() {
        String text = "INFO ready\nERROR [component] failed\nWARN something else\n";
        assertEquals("ERROR [component] failed\n", ServerLogPanel.filtered(text, "[COMPONENT]"));
        assertEquals(text, ServerLogPanel.filtered(text, ""));
        assertEquals("", ServerLogPanel.filtered(text, "missing"));
    }
}
