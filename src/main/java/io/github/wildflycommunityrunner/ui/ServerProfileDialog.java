package io.github.wildflycommunityrunner.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import io.github.wildflycommunityrunner.model.ServerProfile;
import io.github.wildflycommunityrunner.util.WildFlyPaths;
import io.github.wildflycommunityrunner.util.IdeUi;
import io.github.wildflycommunityrunner.services.PluginNotifications;
import io.github.wildflycommunityrunner.security.JvmSecrets;
import io.github.wildflycommunityrunner.security.SensitiveProperties;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

public final class ServerProfileDialog extends DialogWrapper {
    private final JTextField nameField = new JTextField(34);
    private final JTextField homeField = new JTextField(34);
    private final JComboBox<String> configCombo = new JComboBox<>();
    private final JTextField javaHomeField = new JTextField(34);
    private final JTextField hostField = new JTextField(34);
    private final JSpinner httpPort = new JSpinner(new SpinnerNumberModel(8080, 1, 65535, 1));
    private final JSpinner debugPort = new JSpinner(new SpinnerNumberModel(8787, 1, 65535, 1));
    private final JTextField startupArgumentsField = new JTextField(34);
    private final JTextField jvmOptionsField = new JTextField(34);
    private final JTextField scannerName = new JTextField(24);
    private final JSpinner deployTimeout = new JSpinner(new SpinnerNumberModel(120, 1, 3600, 1));
    private final JSpinner startupTimeout = new JSpinner(new SpinnerNumberModel(120, 1, 3600, 1));
    private final ServerProfile profile;
    private final Project project;
    private boolean validating;
    private final PendingSecrets pendingSecrets = new PendingSecrets();
    private int configurationGeneration;

    public ServerProfileDialog(Project project, @Nullable ServerProfile existing) {
        super(project, true);
        this.project = project;
        this.profile = existing == null ? new ServerProfile() : new ServerProfile(existing);
        setTitle(existing == null ? "Add WildFly Server" : "Edit WildFly Server");
        nameField.setText(profile.name);
        homeField.setText(profile.home);
        configCombo.setEditable(true);
        configCombo.setSelectedItem(profile.configuration);
        javaHomeField.setText(profile.javaHome);
        hostField.setText(profile.host == null || profile.host.isBlank() ? "localhost" : profile.host);
        httpPort.setValue(profile.httpPort <= 0 ? 8080 : profile.httpPort);
        debugPort.setValue(profile.debugPort <= 0 ? 8787 : profile.debugPort);
        startupArgumentsField.setText(profile.startupArguments == null ? "" : profile.startupArguments);
        jvmOptionsField.setText(profile.jvmOptions == null ? "" : profile.jvmOptions);
        scannerName.setText(profile.scannerName);
        deployTimeout.setValue(profile.deploymentTimeoutSeconds);
        startupTimeout.setValue(profile.startupTimeoutSeconds);
        init();
        com.intellij.openapi.util.Disposer.register(getDisposable(), pendingSecrets);
        reloadConfigurations(profile.configuration);
    }

    public ServerProfile getProfile() {
        readFieldsIntoProfile();
        return profile;
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridx = 0;
        c.gridy = 0;

        addRow(panel, c, "Name", nameField, null);
        addRow(panel, c, "WildFly Home", homeField, browseHomeButton());
        addRow(panel, c, "Configuration", configCombo, null);
        addRow(panel, c, "JAVA_HOME", javaHomeField, browseButton(javaHomeField, JFileChooser.DIRECTORIES_ONLY));
        addRow(panel, c, "Expected HTTP host", hostField, null);
        addRow(panel, c, "Expected HTTP port", httpPort, null);
        addRow(panel, c, "Debug port", debugPort, null);
        addRow(panel, c, "Deployment scanner name", scannerName, null);
        addRow(panel, c, "Deployment timeout (seconds)", deployTimeout, null);
        addRow(panel, c, "Startup timeout (seconds)", startupTimeout, null);
        addRow(panel, c, "Startup args", startupArgumentsField, null);
        hostField.setToolTipText("Expected application endpoint; does not change WildFly socket bindings.");
        httpPort.setToolTipText("Expected application port; configure socket bindings in WildFly XML or startup arguments.");
        JCheckBox presets = new JCheckBox("Show JVM option presets");
        JvmOptionShortcutPanel shortcuts = new JvmOptionShortcutPanel(jvmOptionsField);
        shortcuts.setVisible(false);
        presets.addActionListener(event -> { shortcuts.setVisible(presets.isSelected()); panel.revalidate(); });
        addRow(panel, c, "Advanced", presets, null);
        addRow(panel, c, "", shortcuts, null);
        addRow(panel, c, "WildFly JVM options", jvmOptionsField, null);

        JLabel hint = new JLabel("Sensitive -D properties use IDE Passwords. Custom keys can use -Dkey=${env:VARIABLE}.");
        c.gridx = 1;
        c.gridy++;
        c.gridwidth = 2;
        c.weightx = 1;
        panel.add(hint, c);
        return panel;
    }

    private JButton browseHomeButton() {
        return browseButton(homeField, JFileChooser.DIRECTORIES_ONLY);
    }

    private void reloadConfigurations(@Nullable String preferred) {
        Object current = configCombo.isEditable() ? configCombo.getEditor().getItem() : configCombo.getSelectedItem();
        String wanted = preferred != null ? preferred : current == null ? "standalone.xml" : current.toString();
        String home = homeField.getText();
        ServerProfile location = new ServerProfile(profile);
        location.home = home;
        location.jvmOptions = jvmOptionsField.getText();
        location.startupArguments = startupArgumentsField.getText();
        int generation = ++configurationGeneration;
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            java.util.List<String> names = new java.util.ArrayList<>();
            try {
                Path dir = WildFlyPaths.configurationDir(location);
                if (Files.isDirectory(dir)) {
                    try (Stream<Path> files = Files.list(dir)) {
                        files.filter(Files::isRegularFile)
                                .map(p -> p.getFileName().toString())
                                .filter(n -> n.endsWith(".xml"))
                                .sorted()
                                .forEach(names::add);
                    }
                }
            } catch (Exception ignored) {}
            // Only Swing controls are updated here, including when the dialog has just opened.
            SwingUtilities.invokeLater(() -> {
                if (isDisposed() || project.isDisposed()) return;
                if (generation != configurationGeneration || !home.equals(homeField.getText())) return;
                // Keep a configuration typed while the directory was being listed.
                Object selected = configCombo.getEditor().getItem();
                String keep = selected == null || selected.toString().isBlank() ? wanted : selected.toString();
                configCombo.setModel(new DefaultComboBoxModel<>(names.toArray(String[]::new)));
                configCombo.setSelectedItem(keep == null || keep.isBlank() ? "standalone.xml" : keep);
            });
        });
    }

    private static void addRow(JPanel panel, GridBagConstraints c, String label, JComponent field, @Nullable JComponent extra) {
        c.gridwidth = 1;
        c.gridx = 0;
        c.weightx = 0;
        panel.add(new JLabel(label + ":"), c);
        c.gridx = 1;
        c.weightx = 1;
        panel.add(field, c);
        if (extra != null) {
            c.gridx = 2;
            c.weightx = 0;
            panel.add(extra, c);
        }
        c.gridy++;
    }

    private JButton browseButton(JTextField field, int selectionMode) {
        JButton button = new JButton("Browse…");
        button.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser(field.getText().isBlank() ? null : new File(field.getText()));
            chooser.setFileSelectionMode(selectionMode);
            if (chooser.showOpenDialog(getContentPane()) == JFileChooser.APPROVE_OPTION) {
                field.setText(chooser.getSelectedFile().getAbsolutePath());
                if (field == homeField) reloadConfigurations(null);
            }
        });
        return button;
    }

    private void readFieldsIntoProfile() {
        profile.name = nameField.getText().trim();
        profile.home = homeField.getText().trim();
        Object config = configCombo.getEditor().getItem();
        profile.configuration = config == null ? "standalone.xml" : config.toString().trim();
        profile.javaHome = javaHomeField.getText().trim();
        profile.host = hostField.getText().trim().isBlank() ? "localhost" : hostField.getText().trim();
        profile.httpPort = (Integer) httpPort.getValue();
        profile.debugPort = (Integer) debugPort.getValue();
        profile.startupArguments = startupArgumentsField.getText().trim();
        profile.jvmOptions = jvmOptionsField.getText().trim();
        profile.scannerName = scannerName.getText().isBlank() ? "default" : scannerName.getText().trim();
        profile.deploymentTimeoutSeconds = (Integer) deployTimeout.getValue();
        profile.startupTimeoutSeconds = (Integer) startupTimeout.getValue();
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        readFieldsIntoProfile();
        try { SensitiveProperties.requireJvmField(profile.startupArguments, "WildFly startup arguments"); }
        catch (IllegalArgumentException error) { return new ValidationInfo(error.getMessage(), startupArgumentsField); }
        if (profile.httpPort < 1 || profile.httpPort > 65535) return new ValidationInfo("HTTP port must be between 1 and 65535.");
        if (profile.debugPort < 1 || profile.debugPort > 65535) return new ValidationInfo("Debug port must be between 1 and 65535.");
        if (profile.name.isBlank()) return new ValidationInfo("Server name is required.", nameField);
        if (profile.home.isBlank()) return new ValidationInfo("WildFly Home is required.", homeField);
        if (profile.configuration.isBlank()) return new ValidationInfo("Configuration is required.", configCombo);
        return null;
    }

    @Override protected void doOKAction() {
        if (validating) return;
        ValidationInfo validation = doValidate();
        if (validation != null) { setErrorText(validation.message); return; }
        ServerProfile snapshot = new ServerProfile(profile);
        String fields = validationFields();
        ModalityState modality = ModalityState.defaultModalityState();
        validating = true;
        setOKActionEnabled(false);
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            JvmSecrets.Protection protection = null;
            try {
                String failure = WildFlyPaths.validate(snapshot);
                if (failure != null) throw new IllegalArgumentException(failure);
                protection = JvmSecrets.getInstance().protection();
                String protectedOptions = protection.protect(snapshot.jvmOptions);
                if (!pendingSecrets.offer(protection)) return;
                IdeUi.later(project, modality, this::isDisposed, () -> {
                    validating = false;
                    setOKActionEnabled(true);
                    if (!fields.equals(validationFields())) {
                        pendingSecrets.finish(false);
                        setErrorText("Settings changed during validation. Press OK again.");
                        return;
                    }
                    jvmOptionsField.setText(protectedOptions);
                    // doValidate/getProfile read the tokenized field, so plaintext cannot be restored afterward.
                    readFieldsIntoProfile();
                    pendingSecrets.finish(true);
                    super.doOKAction();
                });
            } catch (Exception failure) {
                if (protection != null) protection.close();
                String message = PluginNotifications.message(failure);
                IdeUi.later(project, modality, this::isDisposed, () -> {
                    validating = false; setOKActionEnabled(true); setErrorText(message);
                });
            }
        });
    }

    private String validationFields() {
        return nameField.getText() + "\n" + homeField.getText() + "\n" + configCombo.getEditor().getItem()
                + "\n" + javaHomeField.getText() + "\n" + hostField.getText() + "\n" + httpPort.getValue() + "\n" + debugPort.getValue()
                + "\n" + startupArgumentsField.getText() + "\n" + jvmOptionsField.getText()
                + "\n" + scannerName.getText() + "\n" + deployTimeout.getValue() + "\n" + startupTimeout.getValue();
    }
}
