package io.github.wildflycommunityrunner.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import io.github.wildflycommunityrunner.model.ServiceProfile;
import io.github.wildflycommunityrunner.services.BuildProjectDiscoveryService.BuildProjectChoice;
import io.github.wildflycommunityrunner.settings.WildFlyApplicationSettings;
import io.github.wildflycommunityrunner.util.IdeUi;
import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;

/** Global source associations are removable independently of project profiles and deployed archives. */
final class RememberedSourcesDialog extends DialogWrapper {
    private final Project project;
    private final List<BuildProjectChoice> choices;
    private List<ServiceProfile> sources = List.of();
    private Map<String, String> availability = Map.of();
    private int generation;
    private final SourceTableModel model = new SourceTableModel();
    private final JTable table = new JTable(model);
    private JPanel editor;

    RememberedSourcesDialog(Project project, List<BuildProjectChoice> choices) {
        super(project, true);
        this.project = project;
        this.choices = List.copyOf(choices);
        setTitle("Remembered Sources — All Projects");
        setOKButtonText("Close");
        init();
        ApplicationManager.getApplication().getMessageBus().connect(getDisposable())
                .subscribe(WildFlyApplicationSettings.CHANGED, new WildFlyApplicationSettings.Listener() {
                    @Override public void sourcesChanged() {
                        // This callback only updates Swing snapshots; it must also run while this modal dialog is open.
                        SwingUtilities.invokeLater(() -> { if (!isDisposed() && !project.isDisposed()) refresh(); });
                    }
                });
        refresh();
    }

    @Override protected Action[] createActions() { return new Action[]{getOKAction()}; }

    @Override protected JComponent createCenterPanel() {
        if (editor != null) return editor;
        editor = new JPanel(new BorderLayout(6, 6));
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setAutoCreateRowSorter(true);
        table.setFillsViewportHeight(true);
        editor.add(new JScrollPane(table), BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton relink = new JButton("Edit / Relink…");
        JButton forget = new JButton("Forget Selected");
        JButton refresh = new JButton("Refresh");
        relink.addActionListener(event -> IdeUi.later(project, this::isDisposed, this::relink));
        forget.addActionListener(event -> IdeUi.later(project, this::isDisposed, () -> {
            Set<String> ids = new LinkedHashSet<>();
            for (ServiceProfile service : selected()) ids.add(service.id);
            WildFlyApplicationSettings.getInstance().forgetServices(ids);
        }));
        refresh.addActionListener(event -> IdeUi.later(project, this::isDisposed, this::refresh));
        actions.add(relink); actions.add(forget); actions.add(refresh);
        JPanel footer = new JPanel(new BorderLayout());
        footer.add(actions, BorderLayout.NORTH);
        footer.add(new JLabel("Forgetting keeps files, deployments and project profiles. Building or discovery can remember a source again."), BorderLayout.SOUTH);
        editor.add(footer, BorderLayout.SOUTH);
        editor.setPreferredSize(new Dimension(880, 350));
        return editor;
    }

    private List<ServiceProfile> selected() {
        List<ServiceProfile> selected = new ArrayList<>();
        for (int row : table.getSelectedRows()) selected.add(sources.get(table.convertRowIndexToModel(row)));
        return selected;
    }

    private void relink() {
        List<ServiceProfile> selected = selected();
        if (selected.size() != 1) { setErrorText("Select exactly one remembered source to edit or relink."); return; }
        ServiceProfile source = selected.getFirst();
        ServiceProfileDialog dialog = new ServiceProfileDialog(project, source, choices);
        if (dialog.showAndGet()) {
            boolean saved = WildFlyApplicationSettings.getInstance().replaceKnownService(source.id, dialog.getProfile());
            if (!saved) setErrorText("This association was forgotten in another window. Refresh the list.");
        }
    }

    private void refresh() {
        Set<String> selected = new LinkedHashSet<>();
        for (ServiceProfile source : selected()) selected.add(source.id);
        sources = WildFlyApplicationSettings.getInstance().knownServices();
        availability = Map.of();
        model.fireTableDataChanged();
        for (int row = 0; row < sources.size(); row++) if (selected.contains(sources.get(row).id)) {
            int view = table.convertRowIndexToView(row);
            table.addRowSelectionInterval(view, view);
        }
        List<ServiceProfile> snapshot = List.copyOf(sources);
        int request = ++generation;
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            Map<String, String> statuses = new HashMap<>();
            for (ServiceProfile source : snapshot) statuses.put(source.id, availability(source.buildFilePath));
            SwingUtilities.invokeLater(() -> {
                if (isDisposed() || project.isDisposed() || generation != request) return;
                availability = Map.copyOf(statuses);
                // Updating only cells preserves both row sorting and selection.
                for (int row = 0; row < sources.size(); row++) model.fireTableCellUpdated(row, 2);
            });
        });
    }

    static String availability(String path) {
        if (path == null || path.isBlank()) return "No path — relink";
        try {
            Path file = Path.of(path);
            if (!file.isAbsolute()) return "Relative path — project dependent";
            return Files.isRegularFile(file) && Files.isReadable(file) ? "Available" : "Unavailable — check drive or relink";
        } catch (IllegalArgumentException | SecurityException invalid) { return "Unavailable — relink"; }
    }

    private final class SourceTableModel extends AbstractTableModel {
        private final String[] columns = {"Source", "Build file", "Availability"};
        @Override public int getRowCount() { return sources.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int column) { return columns[column]; }
        @Override public Object getValueAt(int row, int column) {
            ServiceProfile source = sources.get(row);
            return switch (column) {
                case 0 -> source.name;
                case 1 -> source.buildFilePath;
                default -> availability.getOrDefault(source.id, "Checking…");
            };
        }
    }
}
