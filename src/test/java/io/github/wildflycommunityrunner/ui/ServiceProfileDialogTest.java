package io.github.wildflycommunityrunner.ui;

import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.github.wildflycommunityrunner.model.BuildSystem;
import io.github.wildflycommunityrunner.model.ServiceProfile;
import io.github.wildflycommunityrunner.services.BuildProjectDiscoveryService.BuildProjectChoice;
import javax.swing.*;
import java.awt.*;
import java.util.List;

public class ServiceProfileDialogTest extends BasePlatformTestCase {
    public void testSelectingGradleProjectDoesNotRecurseOrKeepMavenDefaults() {
        var choice = new BuildProjectChoice("web", "web", BuildSystem.GRADLE, "web/build.gradle.kts", "war");
        var dialog = new ServiceProfileDialog(getProject(), new ServiceProfile(), List.of(choice));
        try {
            JComboBox<?> systems = findCombo(dialog.getContentPane(), BuildSystem.class);
            assertNotNull(systems);
            systems.setSelectedItem(BuildSystem.GRADLE);
            JComboBox<?> projects = findCombo(dialog.getContentPane(), BuildProjectChoice.class);
            assertNotNull(projects);
            projects.setSelectedItem(choice);
            var result = dialog.getProfile();
            assertEquals(BuildSystem.GRADLE, result.buildSystemEnum());
            assertEquals("clean build", result.buildTasks);
            assertEquals("-x test", result.buildArguments);
            assertEquals(choice.buildFilePath(), result.buildFilePath);
            assertEquals("web", result.name);
        } finally { dialog.close(DialogWrapper.CANCEL_EXIT_CODE); }
    }

    private static JComboBox<?> findCombo(Container parent, Class<?> itemType) {
        for (Component component : parent.getComponents()) {
            if (component instanceof JComboBox<?> combo && combo.getItemCount() > 0 && itemType.isInstance(combo.getItemAt(0))) return combo;
            if (component instanceof Container child) {
                JComboBox<?> found = findCombo(child, itemType);
                if (found != null) return found;
            }
        }
        return null;
    }
}
