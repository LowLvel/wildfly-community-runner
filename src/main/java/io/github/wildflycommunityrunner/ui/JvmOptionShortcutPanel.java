package io.github.wildflycommunityrunner.ui;

import javax.swing.*;
import java.awt.*;
import java.io.File;

@SuppressWarnings("serial")
final class JvmOptionShortcutPanel extends JPanel {
    private record Preset(String label, String key, int selectionMode, boolean useParentDirectory) {
        @Override public String toString() { return label; }
    }

    private static final Preset ORACLE_TNS_FILE = new Preset(
            "Oracle TNS (pick tnsnames.ora)", "oracle.net.tns_admin", JFileChooser.FILES_ONLY, true);
    private static final Preset ORACLE_TNS_DIR = new Preset(
            "Oracle TNS Admin folder", "oracle.net.tns_admin", JFileChooser.DIRECTORIES_ONLY, false);
    private static final Preset TRUST_STORE = new Preset(
            "Java trustStore", "javax.net.ssl.trustStore", JFileChooser.FILES_ONLY, false);
    private static final Preset KEY_STORE = new Preset(
            "Java keyStore", "javax.net.ssl.keyStore", JFileChooser.FILES_ONLY, false);
    private static final Preset TEMP_DIR = new Preset(
            "JVM temp directory", "java.io.tmpdir", JFileChooser.DIRECTORIES_ONLY, false);
    private static final Preset CUSTOM = new Preset(
            "Custom (-D / -X / --)", "", -1, false);

    private final JComboBox<Preset> preset = new JComboBox<>(new Preset[]{
            ORACLE_TNS_FILE, ORACLE_TNS_DIR, TRUST_STORE, KEY_STORE, TEMP_DIR, CUSTOM
    });
    private final JTextField value = new JTextField();
    private final JButton browse = new JButton("…");
    private final JButton add = new JButton("Add");
    private final JTextField target;

    JvmOptionShortcutPanel(JTextField target) {
        super(new BorderLayout(4, 0));
        this.target = target;
        setToolTipText("Adds a common JVM option to the editable JVM-options field below.");
        add(preset, BorderLayout.WEST);
        add(value, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        browse.setToolTipText("Choose file/folder");
        buttons.add(browse);
        buttons.add(add);
        add(buttons, BorderLayout.EAST);

        preset.addActionListener(e -> refreshPreset());
        browse.addActionListener(e -> choosePath());
        add.addActionListener(e -> appendOption());
        value.addActionListener(e -> appendOption());
        refreshPreset();
    }

    private void refreshPreset() {
        Preset p = (Preset) preset.getSelectedItem();
        boolean custom = p == CUSTOM;
        browse.setVisible(!custom);
        value.setToolTipText(custom ? "Example: -Dmy.property=value or -Xmx2g" : "Value for -D" + p.key());
        value.setText("");
    }

    private void choosePath() {
        Preset p = (Preset) preset.getSelectedItem();
        if (p == null || p == CUSTOM) return;
        JFileChooser chooser = new JFileChooser(value.getText().isBlank() ? null : new File(value.getText()));
        chooser.setFileSelectionMode(p.selectionMode());
        if (p == ORACLE_TNS_FILE) chooser.setDialogTitle("Choose tnsnames.ora (its folder will be used as oracle.net.tns_admin)");
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File selected = chooser.getSelectedFile();
            if (p.useParentDirectory() && selected.getParentFile() != null) selected = selected.getParentFile();
            value.setText(selected.getAbsolutePath());
        }
    }

    private void appendOption() {
        Preset p = (Preset) preset.getSelectedItem();
        if (p == null || value.getText().isBlank()) return;
        String option;
        if (p == CUSTOM) {
            option = value.getText().trim();
            if (!option.startsWith("-D") && !option.startsWith("-X") && !option.startsWith("--")) option = "-D" + option;
        } else {
            String v = value.getText().trim();
            if (v.contains(" ")) v = "\"" + v.replace("\"", "\\\"") + "\"";
            option = "-D" + p.key() + "=" + v;
        }
        String existing = target.getText().trim();
        target.setText(existing.isBlank() ? option : existing + " " + option);
        value.setText("");
    }
}
