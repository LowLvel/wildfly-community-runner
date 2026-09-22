package io.github.wildflycommunityrunner.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import io.github.wildflycommunityrunner.model.BuildSystem;
import io.github.wildflycommunityrunner.model.ServiceProfile;
import io.github.wildflycommunityrunner.services.BuildProjectDiscoveryService.BuildProjectChoice;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Compact modal editor for a single deployable service. */
public final class ServiceProfileDialog extends DialogWrapper {
    private final ServiceProfile working;
    private final List<BuildProjectChoice> choices;

    private final JTextField nameField = new JTextField(34);
    private final JComboBox<BuildSystem> buildSystem = new JComboBox<>(BuildSystem.values());
    private final JComboBox<BuildProjectChoice> importedProject = new JComboBox<>();
    private final JTextField buildFile = new JTextField(34);
    private final JComboBox<String> packaging = new JComboBox<>(new String[]{"auto", "war", "ear", "jar"});
    private final JTextField deploymentName = new JTextField(34);
    private final JTextField contextPath = new JTextField(34);
    private final JTextField artifact = new JTextField(34);
    private final JCheckBox deployAfterBuild = new JCheckBox("Auto Redeploy when built artifact changes");

    private final JTextField tasks = new JTextField(34);
    private final JTextField arguments = new JTextField(34);
    private final JTextField jvmOptions = new JTextField(34);

    public ServiceProfileDialog(Project project, ServiceProfile service, List<BuildProjectChoice> choices) {
        super(project, true);
        this.working = new ServiceProfile(service);
        this.working.migrateLegacyFields();
        this.choices = choices == null ? List.of() : choices;
        setTitle("Service — " + (working.name == null || working.name.isBlank() ? "Unnamed" : working.name));
        loadFields();
        init();
    }

    public ServiceProfile getProfile() {
        applyFields();
        return working;
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Service", buildGeneralPanel());
        tabs.addTab("Build & JVM", buildBuildPanel());
        tabs.setPreferredSize(new Dimension(720, 390));
        return tabs;
    }

    private JComponent buildGeneralPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        GridBagConstraints c = constraints();
        addRow(panel, c, "Name", nameField, null);
        addRow(panel, c, "Build system", buildSystem, null);
        addRow(panel, c, "Imported project", importedProject, null);
        addRow(panel, c, "Build file", buildFile, browseBuildFileButton());
        addRow(panel, c, "Artifact type", packaging, null);
        addRow(panel, c, "Deployment name", deploymentName, null);
        addRow(panel, c, "Browser context path", contextPath, null);
        addRow(panel, c, "Artifact override", artifact, browseArtifactButton());

        c.gridx = 1; c.weightx = 1; c.gridwidth = 2;
        panel.add(deployAfterBuild, c); c.gridy++;

        JLabel hint = new JLabel("Leave Artifact override empty to auto-detect target/ or build/libs/.");
        panel.add(hint, c);

        buildSystem.addActionListener(e -> refreshImportedProjects());
        importedProject.addActionListener(e -> applyImportedProject());
        return panel;
    }

    private JComponent buildBuildPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        GridBagConstraints c = constraints();
        addRow(panel, c, "Tasks / goals", tasks, null);
        addRow(panel, c, "Build arguments", arguments, null);
        addRow(panel, c, "Quick JVM option", new JvmOptionShortcutPanel(jvmOptions), null);
        addRow(panel, c, "Build JVM options", jvmOptions, null);
        c.gridx = 1; c.weightx = 1; c.gridwidth = 2;
        panel.add(new JLabel("Examples: Maven clean package; Gradle clean build. Oracle TNS can be added with the picker above."), c);
        return panel;
    }

    private static GridBagConstraints constraints() {
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridx = 0;
        c.gridy = 0;
        return c;
    }

    private void loadFields() {
        nameField.setText(Objects.toString(working.name, ""));
        buildSystem.setSelectedItem(working.buildSystemEnum());
        buildFile.setText(Objects.toString(working.buildFilePath, ""));
        packaging.setSelectedItem(normalizePackaging(working.packaging));
        deploymentName.setText(Objects.toString(working.deploymentName, ""));
        contextPath.setText(Objects.toString(working.contextPath, ""));
        artifact.setText(Objects.toString(working.artifactPath, ""));
        deployAfterBuild.setSelected(working.deployAfterBuild);
        tasks.setText(working.buildTasks == null || working.buildTasks.isBlank() ? working.defaultTasks() : working.buildTasks);
        arguments.setText(Objects.toString(working.buildArguments, ""));
        jvmOptions.setText(Objects.toString(working.buildJvmOptions, ""));
        refreshImportedProjects();
        selectImportedProject(working.buildFilePath);
    }

    private void refreshImportedProjects() {
        BuildSystem selected = (BuildSystem) buildSystem.getSelectedItem();
        Object old = importedProject.getSelectedItem();
        DefaultComboBoxModel<BuildProjectChoice> model = new DefaultComboBoxModel<>();
        for (BuildProjectChoice choice : choices) {
            if (selected == null || choice.system() == selected) model.addElement(choice);
        }
        importedProject.setModel(model);
        importedProject.setSelectedItem(null);
        if (old instanceof BuildProjectChoice oldChoice) selectImportedProject(oldChoice.buildFilePath());
        else selectImportedProject(buildFile.getText());
    }

    private void selectImportedProject(String path) {
        if (path == null || path.isBlank()) return;
        for (int i = 0; i < importedProject.getItemCount(); i++) {
            BuildProjectChoice choice = importedProject.getItemAt(i);
            if (samePath(path, choice.buildFilePath())) {
                importedProject.setSelectedIndex(i);
                return;
            }
        }
    }

    private void applyImportedProject() {
        BuildProjectChoice choice = (BuildProjectChoice) importedProject.getSelectedItem();
        if (choice == null) return;
        buildSystem.setSelectedItem(choice.system());
        buildFile.setText(choice.buildFilePath());
        if (nameField.getText().isBlank() || "Service".equals(nameField.getText()) || "Custom service".equals(nameField.getText())) {
            nameField.setText(choice.name());
        }
        packaging.setSelectedItem(normalizePackaging(choice.packaging()));
        if (deploymentName.getText().isBlank()) deploymentName.setText(safeDeploymentName(choice.name(), choice.packaging()));
    }

    private JButton browseBuildFileButton() {
        JButton button = new JButton("Browse…");
        button.addActionListener(e -> {
            JFileChooser chooser = chooserFor(buildFile.getText());
            chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
            chooser.setDialogTitle("Select pom.xml, build.gradle, or build.gradle.kts");
            if (chooser.showOpenDialog(getContentPane()) == JFileChooser.APPROVE_OPTION) {
                File file = chooser.getSelectedFile();
                buildFile.setText(file.getAbsolutePath());
                String n = file.getName();
                if ("pom.xml".equalsIgnoreCase(n)) buildSystem.setSelectedItem(BuildSystem.MAVEN);
                else if ("build.gradle".equals(n) || "build.gradle.kts".equals(n)) buildSystem.setSelectedItem(BuildSystem.GRADLE);
            }
        });
        return button;
    }

    private JButton browseArtifactButton() {
        JButton button = new JButton("Browse…");
        button.addActionListener(e -> {
            JFileChooser chooser = chooserFor(artifact.getText());
            chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
            chooser.setDialogTitle("Select WAR, EAR, or JAR");
            if (chooser.showOpenDialog(getContentPane()) == JFileChooser.APPROVE_OPTION) {
                artifact.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });
        return button;
    }

    private static JFileChooser chooserFor(String current) {
        if (current == null || current.isBlank()) return new JFileChooser();
        try {
            File file = Path.of(current).toFile();
            return new JFileChooser(file.isDirectory() ? file : file.getParentFile());
        } catch (Exception ignored) {
            return new JFileChooser();
        }
    }

    private static void addRow(JPanel panel, GridBagConstraints c, String label, JComponent field, @Nullable JComponent extra) {
        c.gridwidth = 1; c.gridx = 0; c.weightx = 0;
        panel.add(new JLabel(label + ":"), c);
        c.gridx = 1; c.weightx = 1;
        panel.add(field, c);
        if (extra != null) {
            c.gridx = 2; c.weightx = 0;
            panel.add(extra, c);
        }
        c.gridy++;
    }

    private void applyFields() {
        BuildSystem system = (BuildSystem) buildSystem.getSelectedItem();
        if (system != null) working.buildSystem = system.name();
        working.name = nameField.getText().trim();
        working.buildFilePath = buildFile.getText().trim();
        working.packaging = Objects.toString(packaging.getSelectedItem(), "auto");
        working.deploymentName = deploymentName.getText().trim();
        working.contextPath = contextPath.getText().trim();
        working.artifactPath = artifact.getText().trim();
        working.deployAfterBuild = deployAfterBuild.isSelected();
        working.buildTasks = tasks.getText().trim();
        working.buildArguments = arguments.getText().trim();
        working.buildJvmOptions = jvmOptions.getText().trim();
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        applyFields();
        if (working.name == null || working.name.isBlank()) return new ValidationInfo("Service name is required.");
        if (working.buildFilePath == null || working.buildFilePath.isBlank()) return new ValidationInfo("Select a Maven or Gradle build file.");
        String lower = working.buildFilePath.toLowerCase(Locale.ROOT);
        if (working.buildSystemEnum() == BuildSystem.MAVEN && !lower.endsWith("pom.xml")) return new ValidationInfo("Maven services must point to pom.xml.");
        if (working.buildSystemEnum() == BuildSystem.GRADLE && !(lower.endsWith("build.gradle") || lower.endsWith("build.gradle.kts"))) return new ValidationInfo("Gradle services must point to build.gradle or build.gradle.kts.");
        return null;
    }

    private static boolean samePath(String a, String b) {
        if (a == null || b == null || a.isBlank() || b.isBlank()) return false;
        try { return Path.of(a).toAbsolutePath().normalize().equals(Path.of(b).toAbsolutePath().normalize()); }
        catch (Exception e) { return Objects.equals(a, b); }
    }

    private static String normalizePackaging(String value) {
        if (value == null) return "auto";
        String v = value.trim().toLowerCase(Locale.ROOT);
        return List.of("war", "ear", "jar").contains(v) ? v : "auto";
    }

    private static String safeDeploymentName(String name, String packaging) {
        String ext = normalizePackaging(packaging);
        if ("auto".equals(ext)) ext = "war";
        String base = name == null || name.isBlank() ? "service" : name.replaceAll("[^A-Za-z0-9._-]", "-");
        return base + "." + ext;
    }
}
