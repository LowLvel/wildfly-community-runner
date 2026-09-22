package io.github.wildflycommunityrunner.run;

import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.SettingsEditor;
import io.github.wildflycommunityrunner.model.ServerProfile;
import io.github.wildflycommunityrunner.settings.WildFlyApplicationSettings;
import org.jetbrains.annotations.NotNull;
import javax.swing.*;
import java.awt.BorderLayout;

final class WildFlySettingsEditor extends SettingsEditor<WildFlyRunConfiguration> {
    private final JComboBox<ServerProfile> servers = new JComboBox<>();
    private final JPanel panel = new JPanel(new BorderLayout(8, 8));

    WildFlySettingsEditor(boolean attach) {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        JLabel label = new JLabel("Server profile:");
        label.setLabelFor(servers);
        row.add(label, BorderLayout.WEST);
        row.add(servers, BorderLayout.CENTER);
        panel.add(row, BorderLayout.NORTH);
        panel.add(new JLabel("<html>Manage profiles in the WildFly tool window.<br>"
                + (attach ? "Debug connects to the profile's debug port. Stop disconnects the debugger."
                : "Run starts WildFly. Debug starts it with JDWP and attaches the Java debugger.<br>"
                    + "An existing managed server is reused. Stopping a reused session only disconnects.<br>"
                    + "Build and deployment actions remain available in the WildFly tool window.")
                + "</html>"), BorderLayout.CENTER);
    }

    @Override protected void resetEditorFrom(@NotNull WildFlyRunConfiguration configuration) {
        servers.removeAllItems();
        ServerProfile selected = null;
        for (ServerProfile server : WildFlyApplicationSettings.getInstance().servers()) {
            ServerProfile copy = new ServerProfile(server);
            servers.addItem(copy);
            if (configuration.getServerId().equals(copy.id)) selected = copy;
        }
        servers.setSelectedItem(selected);
    }

    @Override protected void applyEditorTo(@NotNull WildFlyRunConfiguration configuration) throws ConfigurationException {
        ServerProfile selected = (ServerProfile) servers.getSelectedItem();
        if (selected == null) throw new ConfigurationException("Select a WildFly server profile.");
        configuration.setServerId(selected.id);
    }

    @Override protected @NotNull JComponent createEditor() { return panel; }
}
