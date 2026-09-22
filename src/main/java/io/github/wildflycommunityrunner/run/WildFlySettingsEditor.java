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
    private final DefaultListModel<io.github.wildflycommunityrunner.model.ServiceProfile> applications = new DefaultListModel<>();
    private final JList<io.github.wildflycommunityrunner.model.ServiceProfile> selection = new JList<>(applications);
    private final boolean attach;

    WildFlySettingsEditor(boolean attach) {
        this.attach = attach;
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
                    + "Select applications to build and deploy after startup. No selection runs the server only.")
                + "</html>"), BorderLayout.SOUTH);
        if (!attach) {
            selection.setVisibleRowCount(7);
            selection.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
            panel.add(new JScrollPane(selection), BorderLayout.CENTER);
        }
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
        if (selected == null) servers.setSelectedItem(configuration.resolveServer());
        applications.clear();
        if (!attach) {
            var services = io.github.wildflycommunityrunner.settings.WildFlyProjectSettings.getInstance(configuration.getProject()).services();
            for (var service : services) applications.addElement(service);
            for (int i = 0; i < applications.size(); i++)
                if (configuration.getServicePaths().contains(configuration.servicePath(applications.get(i)))) selection.addSelectionInterval(i, i);
        }
    }

    @Override protected void applyEditorTo(@NotNull WildFlyRunConfiguration configuration) throws ConfigurationException {
        ServerProfile selected = (ServerProfile) servers.getSelectedItem();
        if (selected == null) throw new ConfigurationException("Select a WildFly server profile.");
        configuration.setServerId(selected.id);
        if (!attach) configuration.setServicePaths(selection.getSelectedValuesList().stream().map(configuration::servicePath).toList());
    }

    @Override protected @NotNull JComponent createEditor() { return panel; }
}
