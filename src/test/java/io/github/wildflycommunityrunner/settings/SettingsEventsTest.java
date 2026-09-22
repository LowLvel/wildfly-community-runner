package io.github.wildflycommunityrunner.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.github.wildflycommunityrunner.model.ServiceProfile;

public class SettingsEventsTest extends BasePlatformTestCase {
    public void testRememberingAndForgettingSourcesDoNotBroadcastServerChanges() {
        int[] changes = new int[2];
        var connection = ApplicationManager.getApplication().getMessageBus().connect(getTestRootDisposable());
        connection.subscribe(WildFlyApplicationSettings.CHANGED, new WildFlyApplicationSettings.Listener() {
            @Override public void serversChanged() { changes[0]++; }
            @Override public void sourcesChanged() { changes[1]++; }
        });
        var settings = new WildFlyApplicationSettings();
        var source = new ServiceProfile(); source.buildFilePath = "pom.xml";
        settings.rememberService(source);
        settings.forgetServices(java.util.Set.of(source.id));
        assertEquals(0, changes[0]);
        assertEquals(2, changes[1]);
        settings.update(state -> state.servers.add(new io.github.wildflycommunityrunner.model.ServerProfile()));
        assertEquals(1, changes[0]);
        assertEquals(2, changes[1]);
    }

    public void testOldXmlMigratesToVersionedSnapshotAndRoundTripsWithoutObsoleteFields() throws Exception {
        var xml = com.intellij.openapi.util.JDOMUtil.load("<state><option name=\"mavenWorkingDirectory\" value=\"legacy\"/>"
                + "<option name=\"mavenGoals\" value=\"verify\"/></state>");
        var settings = new WildFlyProjectSettings();
        settings.loadState(com.intellij.util.xmlb.XmlSerializer.deserialize(xml, WildFlyProjectSettings.StateData.class));
        var persisted = com.intellij.util.xmlb.XmlSerializer.serialize(settings.getState());
        var restored = new WildFlyProjectSettings();
        restored.loadState(com.intellij.util.xmlb.XmlSerializer.deserialize(persisted, WildFlyProjectSettings.StateData.class));
        assertEquals(1, restored.services().size());
        assertEquals("verify", restored.services().getFirst().buildTasks);
        assertEquals(settings.services().getFirst().id, restored.services().getFirst().id);
        assertEquals(SettingsMigration.VERSION, restored.getState().schemaVersion);
        assertEquals("", restored.getState().mavenWorkingDirectory);
    }
}
