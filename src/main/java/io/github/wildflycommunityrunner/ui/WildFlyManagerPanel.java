package io.github.wildflycommunityrunner.ui;

import com.intellij.icons.AllIcons;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.util.ui.JBUI;
import io.github.wildflycommunityrunner.model.BuildSystem;
import io.github.wildflycommunityrunner.model.ServerProfile;
import io.github.wildflycommunityrunner.model.ServiceProfile;
import io.github.wildflycommunityrunner.services.ArtifactAutoDeployService;
import io.github.wildflycommunityrunner.services.BuildProjectDiscoveryService;
import io.github.wildflycommunityrunner.services.BuildProjectDiscoveryService.BuildProjectChoice;
import io.github.wildflycommunityrunner.services.BuildService;
import io.github.wildflycommunityrunner.services.DebugAttachService;
import io.github.wildflycommunityrunner.services.DeploymentScannerService;
import io.github.wildflycommunityrunner.services.WildFlyProcessService;
import io.github.wildflycommunityrunner.settings.WildFlyApplicationSettings;
import io.github.wildflycommunityrunner.settings.WildFlyProjectSettings;
import io.github.wildflycommunityrunner.util.ArtifactLocator;
import io.github.wildflycommunityrunner.util.ServicePresentation;
import io.github.wildflycommunityrunner.util.WildFlyPaths;

import javax.swing.*;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@SuppressWarnings("serial")
public final class WildFlyManagerPanel extends JPanel {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DEPLOY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private enum BuildDeployMode { AUTO, FORCE_DEPLOY, BUILD_ONLY }
    private enum SelectionSource { PROJECT, EXTERNAL, NONE }

    private record ExternalDeployment(String deploymentName, ServiceProfile source) {}
    private record DeploymentStatusView(String state, Instant deployedAt) {}

    private final Project project;
    private final JComboBox<ServerProfile> serverCombo = new JComboBox<>();
    private final JLabel serverStateLabel = new JLabel(" ");

    private final ServiceTableModel serviceTableModel = new ServiceTableModel();
    private final JTable serviceTable = new JTable(serviceTableModel);
    private final ExternalTableModel externalTableModel = new ExternalTableModel();
    private final JTable externalTable = new JTable(externalTableModel);
    private final JPanel externalSection = new JPanel(new BorderLayout(4, 4));
    private final JLabel selectionLabel = new JLabel("No service selected");

    private final JTextArea output = new JTextArea();
    private final Timer refreshTimer;
    private List<BuildProjectChoice> buildChoices = List.of();
    private List<ExternalDeployment> externalDeployments = List.of();
    private boolean loading;
    private boolean refreshingTable;
    private boolean changingSelection;
    private volatile boolean serverStateRefreshRunning;
    private volatile boolean externalRefreshRunning;

    public WildFlyManagerPanel(Project project) {
        super(new BorderLayout());
        this.project = project;
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        buildUi();
        loadSettings();
        refreshTimer = new Timer(2000, e -> {
            if (project.isDisposed()) {
                ((Timer) e.getSource()).stop();
            } else {
                serviceTable.repaint();
                refreshExternalDeployments();
                refreshServerState();
            }
        });
        refreshTimer.start();
    }

    @Override
    public void addNotify() {
        super.addNotify();
        if (!project.isDisposed() && !refreshTimer.isRunning()) refreshTimer.start();
    }

    @Override
    public void removeNotify() {
        refreshTimer.stop();
        super.removeNotify();
    }

    private void buildUi() {
        JPanel servicesTab = new JPanel(new BorderLayout(5, 5));
        servicesTab.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        servicesTab.add(buildServerPanel(), BorderLayout.NORTH);
        servicesTab.add(buildServicesWorkspace(), BorderLayout.CENTER);
        servicesTab.add(buildSelectionActionBar(), BorderLayout.SOUTH);

        JPanel logsTab = new JPanel(new BorderLayout(4, 4));
        logsTab.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        JPanel logToolbar = new JPanel(new BorderLayout(4, 0));
        JLabel logTitle = new JLabel("WildFly / build activity");
        JPanel logActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 0));
        JButton openServerLog = new JButton("server.log");
        JButton clear = new JButton("Clear");
        openServerLog.addActionListener(e -> openLog());
        clear.addActionListener(e -> output.setText(""));
        logActions.add(openServerLog);
        logActions.add(clear);
        logToolbar.add(logTitle, BorderLayout.WEST);
        logToolbar.add(logActions, BorderLayout.EAST);
        output.setEditable(false);
        output.setLineWrap(false);
        output.setFont(new Font(Font.MONOSPACED, Font.PLAIN, output.getFont().getSize()));
        logsTab.add(logToolbar, BorderLayout.NORTH);
        logsTab.add(new JScrollPane(output), BorderLayout.CENTER);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Services", servicesTab);
        tabs.addTab("Logs", logsTab);
        add(tabs, BorderLayout.CENTER);
    }

    private JComponent buildServerPanel() {
        JPanel panel = new JPanel(new BorderLayout(4, 3));
        panel.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));

        JPanel header = new JPanel(new BorderLayout(6, 0));
        JLabel title = new JLabel("Server");
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        serverStateLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        header.add(title, BorderLayout.WEST);
        header.add(serverStateLabel, BorderLayout.EAST);
        panel.add(header, BorderLayout.NORTH);

        JPanel row = new JPanel(new BorderLayout(4, 0));
        serverCombo.setToolTipText("WildFly server profile");
        row.add(serverCombo, BorderLayout.CENTER);

        JButton start = iconButton(AllIcons.Actions.Execute, "Start server");
        JButton debug = iconButton(AllIcons.Actions.StartDebugger, "Start server in debug mode");
        JButton stop = iconButton(AllIcons.Actions.Suspend, "Stop server");

        JPopupMenu menu = new JPopupMenu();
        JMenuItem attach = new JMenuItem("Attach Debugger");
        JMenuItem add = new JMenuItem("Add Server Profile…");
        JMenuItem edit = new JMenuItem("Edit Server Profile…");
        JMenuItem remove = new JMenuItem("Remove Server Profile…");
        JMenuItem config = new JMenuItem("Edit standalone config");
        JMenuItem home = new JMenuItem("Open WildFly Home");
        JMenuItem deployments = new JMenuItem("Open deployments folder");
        JMenuItem log = new JMenuItem("Open server.log");
        menu.add(attach);
        menu.addSeparator();
        menu.add(add); menu.add(edit); menu.add(remove);
        menu.addSeparator();
        menu.add(config); menu.add(home); menu.add(deployments); menu.add(log);
        JButton more = popupButton(AllIcons.General.ArrowDown, "More server actions", menu);

        JPanel toolbar = compactToolbar(start, debug, stop, more);
        row.add(toolbar, BorderLayout.EAST);
        panel.add(row, BorderLayout.CENTER);

        serverCombo.addActionListener(e -> {
            saveSelectedServer();
            syncAutoDeployWatcher();
            serviceTable.repaint();
            refreshExternalDeployments();
            refreshServerState();
        });
        start.addActionListener(e -> startServer(false));
        debug.addActionListener(e -> startDebug());
        stop.addActionListener(e -> stopServer());
        attach.addActionListener(e -> attachDebugger());
        add.addActionListener(e -> addServer());
        edit.addActionListener(e -> editServer());
        remove.addActionListener(e -> removeServer());
        config.addActionListener(e -> editStandaloneConfig());
        home.addActionListener(e -> openWildFlyHome());
        deployments.addActionListener(e -> openDeploymentsFolder());
        log.addActionListener(e -> openLog());
        return panel;
    }

    private JComponent buildServicesWorkspace() {
        configureProjectServiceTable();
        configureExternalTable();

        JComponent projectTablePanel = ToolbarDecorator.createDecorator(serviceTable)
                .setAddAction(button -> addCustomService())
                .setAddActionName("Add Service")
                .setEditAction(button -> openSelectedServiceDetails())
                .setEditActionName("Edit Service")
                .setRemoveAction(button -> removeSelectedService())
                .setRemoveActionName("Remove Service")
                .disableUpDownActions()
                .addExtraAction(new DumbAwareAction("Discover Projects", "Find Maven and Gradle projects recursively", AllIcons.Actions.Refresh) {
                    @Override public void actionPerformed(AnActionEvent e) { discoverBuildProjects(); }
                    @Override public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
                })
                .createPanel();

        JPanel workspace = new JPanel(new BorderLayout(4, 4));
        workspace.add(projectTablePanel, BorderLayout.CENTER);

        JPanel externalHeader = new JPanel(new BorderLayout(4, 0));
        JLabel externalTitle = new JLabel("External deployments");
        externalTitle.setFont(externalTitle.getFont().deriveFont(Font.BOLD));
        JLabel externalHint = new JLabel("Running on this WildFly, not in this IntelliJ project");
        externalHint.setForeground(UIManager.getColor("Label.disabledForeground"));
        externalHeader.add(externalTitle, BorderLayout.WEST);
        externalHeader.add(externalHint, BorderLayout.EAST);
        externalSection.setBorder(BorderFactory.createEmptyBorder(5, 0, 0, 0));
        externalSection.add(new JSeparator(), BorderLayout.NORTH);
        JPanel externalBody = new JPanel(new BorderLayout(4, 3));
        externalBody.setBorder(BorderFactory.createEmptyBorder(5, 0, 0, 0));
        externalBody.add(externalHeader, BorderLayout.NORTH);
        JScrollPane externalScroll = new JScrollPane(externalTable);
        externalScroll.setPreferredSize(new Dimension(100, JBUI.scale(125)));
        externalBody.add(externalScroll, BorderLayout.CENTER);
        externalSection.add(externalBody, BorderLayout.CENTER);
        externalSection.setVisible(false);
        workspace.add(externalSection, BorderLayout.SOUTH);
        return workspace;
    }

    private void configureProjectServiceTable() {
        serviceTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        serviceTable.setFillsViewportHeight(true);
        serviceTable.setRowHeight(Math.max(serviceTable.getRowHeight(), JBUI.scale(24)));
        serviceTable.getColumnModel().getColumn(0).setMaxWidth(JBUI.scale(52));
        serviceTable.getColumnModel().getColumn(0).setMinWidth(JBUI.scale(42));
        serviceTable.getColumnModel().getColumn(2).setPreferredWidth(JBUI.scale(70));
        serviceTable.getColumnModel().getColumn(2).setMaxWidth(JBUI.scale(92));
        serviceTable.getColumnModel().getColumn(3).setPreferredWidth(JBUI.scale(112));
        serviceTable.getColumnModel().getColumn(1).setCellRenderer(new ServiceNameRenderer());
        serviceTable.getColumnModel().getColumn(3).setCellRenderer(new StatusRenderer());
        serviceTable.getTableHeader().setToolTipText("Auto: redeploy when the built WAR/EAR/JAR changes, regardless of where the build was started.");

        TableRowSorter<ServiceTableModel> sorter = new TableRowSorter<>(serviceTableModel);
        sorter.setComparator(1, String.CASE_INSENSITIVE_ORDER);
        sorter.setSortKeys(List.of(new RowSorter.SortKey(1, SortOrder.ASCENDING)));
        serviceTable.setRowSorter(sorter);

        serviceTable.getSelectionModel().addListSelectionListener(this::projectSelectionChanged);
        serviceTable.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { handleProjectServiceMouse(e); }
            @Override public void mouseReleased(MouseEvent e) { handleProjectServiceMouse(e); }
            @Override public void mouseClicked(MouseEvent e) {
                int row = serviceTable.rowAtPoint(e.getPoint());
                if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2 && row >= 0) {
                    if (!serviceTable.isRowSelected(row)) serviceTable.setRowSelectionInterval(row, row);
                    openSelectedServiceDetails();
                }
            }
        });
        serviceTable.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "clear-service-selection");
        serviceTable.getActionMap().put("clear-service-selection", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { clearAllSelection(); }
        });
        serviceTable.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "edit-service");
        serviceTable.getActionMap().put("edit-service", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { openSelectedServiceDetails(); }
        });
    }

    private void configureExternalTable() {
        externalTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        externalTable.setFillsViewportHeight(true);
        externalTable.setRowHeight(Math.max(externalTable.getRowHeight(), JBUI.scale(23)));
        externalTable.getColumnModel().getColumn(1).setPreferredWidth(JBUI.scale(150));
        externalTable.getColumnModel().getColumn(2).setPreferredWidth(JBUI.scale(105));
        externalTable.getColumnModel().getColumn(2).setMaxWidth(JBUI.scale(120));
        externalTable.getColumnModel().getColumn(2).setCellRenderer(new StatusRenderer());
        TableRowSorter<ExternalTableModel> sorter = new TableRowSorter<>(externalTableModel);
        sorter.setComparator(0, String.CASE_INSENSITIVE_ORDER);
        sorter.setSortKeys(List.of(new RowSorter.SortKey(0, SortOrder.ASCENDING)));
        externalTable.setRowSorter(sorter);
        externalTable.getSelectionModel().addListSelectionListener(this::externalSelectionChanged);
        externalTable.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { handleExternalMouse(e); }
            @Override public void mouseReleased(MouseEvent e) { handleExternalMouse(e); }
            @Override public void mouseClicked(MouseEvent e) {
                int row = externalTable.rowAtPoint(e.getPoint());
                if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2 && row >= 0) {
                    if (!externalTable.isRowSelected(row)) externalTable.setRowSelectionInterval(row, row);
                    openSelectedInBrowser();
                }
            }
        });
    }

    private JComponent buildSelectionActionBar() {
        JPanel bar = new JPanel(new BorderLayout(6, 0));
        bar.setBorder(BorderFactory.createEmptyBorder(3, 2, 0, 0));
        selectionLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        bar.add(selectionLabel, BorderLayout.CENTER);

        JButton build = new JButton("Build", AllIcons.Actions.Compile);
        build.setToolTipText("Build selected services. Auto-enabled services redeploy when their built artifact changes.");
        JButton redeploy = iconButton(AllIcons.Actions.Restart, "Redeploy selected service(s)");
        JButton browser = iconButton(AllIcons.Actions.Forward, "Open selected service in browser");

        JPopupMenu moreMenu = new JPopupMenu();
        JMenuItem buildDeploy = new JMenuItem("Build and Deploy", AllIcons.Actions.Compile);
        JMenuItem buildOnly = new JMenuItem("Build without Deploy");
        JMenuItem undeploy = new JMenuItem("Undeploy", AllIcons.Actions.Cancel);
        JMenuItem autoOn = new JMenuItem("Enable Auto Redeploy");
        JMenuItem autoOff = new JMenuItem("Disable Auto Redeploy");
        JMenuItem edit = new JMenuItem("Edit Service…");
        JMenuItem associate = new JMenuItem("Associate Source…");
        JMenuItem addToProject = new JMenuItem("Add to this Project");
        JMenuItem openModule = new JMenuItem("Open module folder");
        JMenuItem openArtifact = new JMenuItem("Show built artifact");
        JMenuItem remove = new JMenuItem("Remove service profile…");
        moreMenu.add(buildDeploy); moreMenu.add(buildOnly); moreMenu.add(undeploy);
        moreMenu.addSeparator(); moreMenu.add(autoOn); moreMenu.add(autoOff);
        moreMenu.addSeparator(); moreMenu.add(edit); moreMenu.add(associate); moreMenu.add(addToProject);
        moreMenu.addSeparator(); moreMenu.add(openModule); moreMenu.add(openArtifact); moreMenu.add(remove);
        moreMenu.addPopupMenuListener(new PopupMenuListener() {
            @Override public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                SelectionSource source = selectionSource();
                boolean projectSelected = source == SelectionSource.PROJECT && !selectedServices().isEmpty();
                boolean externalSelected = source == SelectionSource.EXTERNAL && !selectedExternalDeployments().isEmpty();
                boolean singleProject = projectSelected && selectedServices().size() == 1;
                boolean singleExternal = externalSelected && selectedExternalDeployments().size() == 1;
                boolean allExternalKnown = externalSelected && selectedExternalDeployments().stream().allMatch(x -> x.source() != null);
                buildDeploy.setEnabled(projectSelected || allExternalKnown);
                buildOnly.setEnabled(projectSelected || allExternalKnown);
                undeploy.setEnabled(projectSelected || externalSelected);
                autoOn.setEnabled(projectSelected);
                autoOff.setEnabled(projectSelected);
                edit.setEnabled(singleProject);
                associate.setEnabled(singleExternal);
                addToProject.setEnabled(singleExternal && selectedExternalDeployments().get(0).source() != null);
                openModule.setEnabled(singleProject || (singleExternal && selectedExternalDeployments().get(0).source() != null));
                openArtifact.setEnabled(singleProject || (singleExternal && selectedExternalDeployments().get(0).source() != null));
                remove.setEnabled(projectSelected);
            }
            @Override public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {}
            @Override public void popupMenuCanceled(PopupMenuEvent e) {}
        });
        JButton more = popupButton(AllIcons.General.ArrowDown, "More service actions", moreMenu);

        JPanel actions = compactToolbar(build, redeploy, browser, more);
        bar.add(actions, BorderLayout.EAST);

        build.addActionListener(e -> buildSelected(BuildDeployMode.AUTO));
        redeploy.addActionListener(e -> redeploySelected());
        browser.addActionListener(e -> openSelectedInBrowser());
        buildDeploy.addActionListener(e -> buildSelected(BuildDeployMode.FORCE_DEPLOY));
        buildOnly.addActionListener(e -> buildSelected(BuildDeployMode.BUILD_ONLY));
        undeploy.addActionListener(e -> undeploySelected());
        autoOn.addActionListener(e -> setSelectedAutoDeploy(true));
        autoOff.addActionListener(e -> setSelectedAutoDeploy(false));
        edit.addActionListener(e -> openSelectedServiceDetails());
        associate.addActionListener(e -> associateExternalSource());
        addToProject.addActionListener(e -> addExternalToProject());
        openModule.addActionListener(e -> openSelectedModuleFolder());
        openArtifact.addActionListener(e -> openSelectedArtifactFolder());
        remove.addActionListener(e -> removeSelectedService());
        return bar;
    }

    private void projectSelectionChanged(ListSelectionEvent e) {
        if (e.getValueIsAdjusting() || changingSelection || refreshingTable) return;
        changingSelection = true;
        try {
            if (serviceTable.getSelectedRowCount() > 0) externalTable.clearSelection();
            List<ServiceProfile> selected = selectedServices();
            projectState().selectedServiceId = selected.size() == 1 ? selected.get(0).id : "";
        } finally {
            changingSelection = false;
        }
        refreshSelectionLabel();
    }

    private void externalSelectionChanged(ListSelectionEvent e) {
        if (e.getValueIsAdjusting() || changingSelection) return;
        changingSelection = true;
        try {
            if (externalTable.getSelectedRowCount() > 0) serviceTable.clearSelection();
            projectState().selectedServiceId = "";
        } finally {
            changingSelection = false;
        }
        refreshSelectionLabel();
    }

    private void handleProjectServiceMouse(MouseEvent e) {
        int row = serviceTable.rowAtPoint(e.getPoint());
        if (row < 0) {
            if (SwingUtilities.isLeftMouseButton(e) && e.getID() == MouseEvent.MOUSE_PRESSED) clearAllSelection();
            return;
        }
        if (e.isPopupTrigger()) {
            if (!serviceTable.isRowSelected(row)) serviceTable.setRowSelectionInterval(row, row);
            projectContextMenu().show(serviceTable, e.getX(), e.getY());
        }
    }

    private void handleExternalMouse(MouseEvent e) {
        int row = externalTable.rowAtPoint(e.getPoint());
        if (row < 0) return;
        if (e.isPopupTrigger()) {
            if (!externalTable.isRowSelected(row)) externalTable.setRowSelectionInterval(row, row);
            externalContextMenu().show(externalTable, e.getX(), e.getY());
        }
    }

    private JPopupMenu projectContextMenu() {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem build = new JMenuItem("Build", AllIcons.Actions.Compile);
        JMenuItem buildDeploy = new JMenuItem("Build and Deploy");
        JMenuItem buildOnly = new JMenuItem("Build without Deploy");
        JMenuItem redeploy = new JMenuItem("Redeploy", AllIcons.Actions.Restart);
        JMenuItem undeploy = new JMenuItem("Undeploy", AllIcons.Actions.Cancel);
        JMenuItem browser = new JMenuItem("Open in Browser", AllIcons.Actions.Forward);
        JMenuItem autoOn = new JMenuItem("Enable Auto Redeploy");
        JMenuItem autoOff = new JMenuItem("Disable Auto Redeploy");
        JMenuItem edit = new JMenuItem("Edit Service…", AllIcons.Actions.Edit);
        JMenuItem module = new JMenuItem("Open module folder");
        JMenuItem artifact = new JMenuItem("Show built artifact");
        JMenuItem remove = new JMenuItem("Remove service profile…");
        menu.add(build); menu.add(buildDeploy); menu.add(buildOnly); menu.add(redeploy); menu.add(undeploy); menu.add(browser);
        menu.addSeparator(); menu.add(autoOn); menu.add(autoOff); menu.add(edit);
        menu.addSeparator(); menu.add(module); menu.add(artifact); menu.add(remove);
        build.addActionListener(e -> buildSelected(BuildDeployMode.AUTO));
        buildDeploy.addActionListener(e -> buildSelected(BuildDeployMode.FORCE_DEPLOY));
        buildOnly.addActionListener(e -> buildSelected(BuildDeployMode.BUILD_ONLY));
        redeploy.addActionListener(e -> redeploySelected());
        undeploy.addActionListener(e -> undeploySelected());
        browser.addActionListener(e -> openSelectedInBrowser());
        autoOn.addActionListener(e -> setSelectedAutoDeploy(true));
        autoOff.addActionListener(e -> setSelectedAutoDeploy(false));
        edit.addActionListener(e -> openSelectedServiceDetails());
        module.addActionListener(e -> openSelectedModuleFolder());
        artifact.addActionListener(e -> openSelectedArtifactFolder());
        remove.addActionListener(e -> removeSelectedService());
        edit.setEnabled(selectedServices().size() == 1);
        browser.setEnabled(selectedServices().size() == 1);
        module.setEnabled(selectedServices().size() == 1);
        artifact.setEnabled(selectedServices().size() == 1);
        return menu;
    }

    private JPopupMenu externalContextMenu() {
        List<ExternalDeployment> selected = selectedExternalDeployments();
        boolean single = selected.size() == 1;
        boolean allKnown = !selected.isEmpty() && selected.stream().allMatch(x -> x.source() != null);
        JPopupMenu menu = new JPopupMenu();
        JMenuItem build = new JMenuItem("Build", AllIcons.Actions.Compile);
        JMenuItem buildDeploy = new JMenuItem("Build and Deploy");
        JMenuItem redeploy = new JMenuItem("Redeploy", AllIcons.Actions.Restart);
        JMenuItem undeploy = new JMenuItem("Undeploy", AllIcons.Actions.Cancel);
        JMenuItem browser = new JMenuItem("Open in Browser", AllIcons.Actions.Forward);
        JMenuItem associate = new JMenuItem(single && selected.get(0).source() != null ? "Edit Source Association…" : "Associate Source…", AllIcons.Actions.Edit);
        JMenuItem addToProject = new JMenuItem("Add to this Project");
        JMenuItem module = new JMenuItem("Open source module folder");
        JMenuItem artifact = new JMenuItem("Show built artifact");
        menu.add(build); menu.add(buildDeploy); menu.add(redeploy); menu.add(undeploy); menu.add(browser);
        menu.addSeparator(); menu.add(associate); menu.add(addToProject);
        menu.addSeparator(); menu.add(module); menu.add(artifact);
        build.setEnabled(allKnown);
        buildDeploy.setEnabled(allKnown);
        browser.setEnabled(single);
        associate.setEnabled(single);
        addToProject.setEnabled(single && selected.get(0).source() != null);
        module.setEnabled(single && selected.get(0).source() != null);
        artifact.setEnabled(single && selected.get(0).source() != null);
        build.addActionListener(e -> buildSelected(BuildDeployMode.AUTO));
        buildDeploy.addActionListener(e -> buildSelected(BuildDeployMode.FORCE_DEPLOY));
        redeploy.addActionListener(e -> redeploySelected());
        undeploy.addActionListener(e -> undeploySelected());
        browser.addActionListener(e -> openSelectedInBrowser());
        associate.addActionListener(e -> associateExternalSource());
        addToProject.addActionListener(e -> addExternalToProject());
        module.addActionListener(e -> openSelectedModuleFolder());
        artifact.addActionListener(e -> openSelectedArtifactFolder());
        return menu;
    }

    private void loadSettings() {
        loading = true;
        refreshServers();
        refreshBuildChoices(false);
        WildFlyProjectSettings.StateData state = projectState();
        migrateOldSettingsIfNeeded(state);
        for (ServiceProfile service : state.services) {
            service.migrateLegacyFields();
            rememberService(service);
        }
        serviceTableModel.fireTableDataChanged();
        selectInitialServer(state.selectedServerId);
        selectServiceById(state.selectedServiceId);
        loading = false;
        saveSelectedServer();
        syncAutoDeployWatcher();
        refreshExternalDeployments();
        refreshSelectionLabel();
        refreshServerState();
    }

    private void migrateOldSettingsIfNeeded(WildFlyProjectSettings.StateData state) {
        if (!state.services.isEmpty()) return;
        if ((state.mavenWorkingDirectory == null || state.mavenWorkingDirectory.isBlank())
                && (state.artifactPath == null || state.artifactPath.isBlank())) return;
        ServiceProfile legacy = new ServiceProfile();
        legacy.name = "Legacy service";
        legacy.buildSystem = BuildSystem.MAVEN.name();
        if (state.mavenWorkingDirectory != null && !state.mavenWorkingDirectory.isBlank()) {
            legacy.buildFilePath = Path.of(state.mavenWorkingDirectory).resolve("pom.xml").toString();
        }
        legacy.artifactPath = state.artifactPath == null ? "" : state.artifactPath;
        legacy.buildTasks = state.mavenGoals == null || state.mavenGoals.isBlank() ? "clean package" : state.mavenGoals;
        state.services.add(legacy);
    }

    private void refreshServers() {
        String selectedId = selectedServer() == null ? null : selectedServer().id;
        DefaultComboBoxModel<ServerProfile> model = new DefaultComboBoxModel<>();
        for (ServerProfile server : WildFlyApplicationSettings.getInstance().servers()) model.addElement(server);
        serverCombo.setModel(model);
        if (selectedId != null) selectServerById(selectedId);
    }

    private void selectInitialServer(String projectServerId) {
        boolean projectExplicit = projectServerId != null && !projectServerId.isBlank();
        if (selectServerById(projectServerId)) return;
        WildFlyProcessService process = WildFlyProcessService.getInstance();
        for (int i = 0; i < serverCombo.getItemCount(); i++) {
            ServerProfile server = serverCombo.getItemAt(i);
            if (process.isRunning(server)) {
                serverCombo.setSelectedIndex(i);
                return;
            }
        }
        WildFlyApplicationSettings app = WildFlyApplicationSettings.getInstance();
        selectServerById(app.lastServerId());
        if (serverCombo.getSelectedItem() == null && serverCombo.getItemCount() > 0) serverCombo.setSelectedIndex(0);
        if (!projectExplicit) selectDetectedServerAsync();
    }

    private void selectDetectedServerAsync() {
        List<ServerProfile> servers = new ArrayList<>();
        for (int i = 0; i < serverCombo.getItemCount(); i++) servers.add(new ServerProfile(serverCombo.getItemAt(i)));
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            WildFlyProcessService process = WildFlyProcessService.getInstance();
            ServerProfile detected = null;
            for (ServerProfile server : servers) {
                if (process.isDetectedRunning(server)) { detected = server; break; }
            }
            if (detected == null) return;
            String id = detected.id;
            ApplicationManager.getApplication().invokeLater(() -> {
                ServerProfile current = selectedServer();
                if (current != null && process.isRunning(current)) return;
                if (selectServerById(id)) { saveSelectedServer(); refreshServerState(); refreshExternalDeployments(); }
            });
        });
    }

    private void refreshBuildChoices(boolean recursive) {
        buildChoices = recursive ? BuildProjectDiscoveryService.discover(project) : BuildProjectDiscoveryService.discoverImportedOnly(project);
    }

    private void discoverBuildProjects() {
        append("Scanning workspace for Maven/Gradle projects…");
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            List<BuildProjectChoice> discovered = BuildProjectDiscoveryService.discover(project);
            ApplicationManager.getApplication().invokeLater(() -> {
                buildChoices = discovered;
                WildFlyProjectSettings.StateData state = projectState();
                int added = 0;
                for (BuildProjectChoice choice : discovered) {
                    if (state.services.stream().anyMatch(s -> samePath(s.buildFilePath, choice.buildFilePath()))) continue;
                    ServiceProfile service = new ServiceProfile();
                    service.name = choice.name();
                    service.buildSystem = choice.system().name();
                    service.buildFilePath = choice.buildFilePath();
                    service.packaging = normalizePackaging(choice.packaging());
                    service.buildTasks = service.defaultTasks();
                    service.buildArguments = choice.system() == BuildSystem.MAVEN ? "-DskipTests" : "-x test";
                    service.deployAfterBuild = true;
                    service.deploymentName = defaultDeploymentName(service);
                    state.services.add(service);
                    rememberService(service);
                    added++;
                }
                refreshServiceTablePreservingSelection();
                append(added == 0 ? "Project services are already in sync." : "Added " + added + " discovered service(s). Auto Redeploy is enabled by default and watches the built artifact directly.");
            });
        });
    }

    private void addCustomService() {
        ServiceProfile draft = new ServiceProfile();
        draft.name = "Custom service";
        if (buildChoices.isEmpty()) refreshBuildChoices(false);
        ServiceProfileDialog dialog = new ServiceProfileDialog(project, draft, buildChoices);
        if (!dialog.showAndGet()) return;
        ServiceProfile service = dialog.getProfile();
        projectState().services.add(service);
        rememberService(service);
        refreshServiceTablePreservingSelection();
        selectServiceById(service.id);
        append("Added service: " + service.name);
    }

    private void openSelectedServiceDetails() {
        List<ServiceProfile> selected = selectedServices();
        if (selected.size() != 1) {
            append(selected.isEmpty() ? "Select a service first." : "Select exactly one service to edit.");
            return;
        }
        ServiceProfile current = selected.get(0);
        if (buildChoices.isEmpty()) refreshBuildChoices(false);
        ServiceProfileDialog dialog = new ServiceProfileDialog(project, current, buildChoices);
        if (!dialog.showAndGet()) return;
        copyService(dialog.getProfile(), current);
        rememberService(current);
        refreshServiceTablePreservingSelection();
        refreshExternalDeployments();
        append("Updated service: " + current.name);
    }

    private void removeSelectedService() {
        List<ServiceProfile> selected = selectedServices();
        if (selected.isEmpty()) return;
        String message = selected.size() == 1 ? "Remove service profile '" + selected.get(0).name + "'?" : "Remove " + selected.size() + " selected service profiles?";
        int answer = JOptionPane.showConfirmDialog(this, message, "Remove Service", JOptionPane.YES_NO_OPTION);
        if (answer != JOptionPane.YES_OPTION) return;
        Set<String> ids = new LinkedHashSet<>();
        for (ServiceProfile service : selected) ids.add(service.id);
        projectState().services.removeIf(service -> ids.contains(service.id));
        projectState().selectedServiceId = "";
        refreshServiceTablePreservingSelection();
        clearAllSelection();
        refreshExternalDeployments();
    }

    private void setSelectedAutoDeploy(boolean enabled) {
        List<ServiceProfile> selected = selectedServices();
        if (selected.isEmpty()) { append("Select one or more project services first."); return; }
        for (ServiceProfile service : selected) {
            service.deployAfterBuild = enabled;
            rememberService(service);
        }
        refreshServiceTablePreservingSelection();
        append((enabled ? "Enabled" : "Disabled") + " Auto Redeploy for " + selected.size() + " service(s).");
    }

    private void associateExternalSource() {
        List<ExternalDeployment> selected = selectedExternalDeployments();
        if (selected.size() != 1) { append("Select exactly one external deployment first."); return; }
        ExternalDeployment external = selected.get(0);
        ServiceProfile draft = external.source() == null ? serviceDraftForExternal(external.deploymentName()) : new ServiceProfile(external.source());
        draft.deploymentName = external.deploymentName();
        if (buildChoices.isEmpty()) refreshBuildChoices(false);
        ServiceProfileDialog dialog = new ServiceProfileDialog(project, draft, buildChoices);
        if (!dialog.showAndGet()) return;
        ServiceProfile source = dialog.getProfile();
        source.deploymentName = external.deploymentName();
        rememberService(source);
        refreshExternalDeployments();
        append("Remembered source for external deployment " + external.deploymentName() + ": " + source.buildFilePath);
    }

    private void addExternalToProject() {
        List<ExternalDeployment> selected = selectedExternalDeployments();
        if (selected.size() != 1 || selected.get(0).source() == null) { append("Associate a source project first."); return; }
        ServiceProfile source = new ServiceProfile(selected.get(0).source());
        if (projectState().services.stream().anyMatch(s -> samePath(s.buildFilePath, source.buildFilePath))) {
            append("That source project is already configured in this IntelliJ project.");
            return;
        }
        source.id = UUID.randomUUID().toString();
        projectState().services.add(source);
        rememberService(source);
        refreshServiceTablePreservingSelection();
        selectServiceById(source.id);
        refreshExternalDeployments();
        append("Added external deployment source to this project: " + source.name);
    }

    private void refreshExternalDeployments() {
        ServerProfile selected = selectedServer();
        if (selected == null) {
            externalDeployments = List.of();
            externalTableModel.fireTableDataChanged();
            externalSection.setVisible(false);
            return;
        }
        if (externalRefreshRunning) return;
        externalRefreshRunning = true;
        ServerProfile server = new ServerProfile(selected);
        Set<String> localNames = new LinkedHashSet<>();
        for (ServiceProfile service : projectState().services) localNames.add(deploymentNameForStatus(service).toLowerCase(Locale.ROOT));
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            WildFlyApplicationSettings app = WildFlyApplicationSettings.getInstance();
            List<ExternalDeployment> rows = new ArrayList<>();
            for (String name : DeploymentScannerService.listDeployments(server)) {
                String status = DeploymentScannerService.status(server, name);
                if ("NOT DEPLOYED".equals(status)) continue;
                if (localNames.contains(name.toLowerCase(Locale.ROOT))) continue;
                rows.add(new ExternalDeployment(name, app.findKnownServiceByDeploymentName(name)));
            }
            rows.sort(Comparator.comparing(ExternalDeployment::deploymentName, String.CASE_INSENSITIVE_ORDER));
            ApplicationManager.getApplication().invokeLater(() -> {
                try {
                    ServerProfile current = selectedServer();
                    if (current == null || !Objects.equals(current.id, server.id)) return;
                    Set<String> selectedNames = new LinkedHashSet<>();
                    for (ExternalDeployment row : selectedExternalDeployments()) selectedNames.add(row.deploymentName());
                    externalDeployments = List.copyOf(rows);
                    externalTableModel.fireTableDataChanged();
                    externalSection.setVisible(!externalDeployments.isEmpty());
                    if (!selectedNames.isEmpty()) {
                        changingSelection = true;
                        try {
                            externalTable.clearSelection();
                            for (int i = 0; i < externalDeployments.size(); i++) {
                                if (selectedNames.contains(externalDeployments.get(i).deploymentName())) {
                                    int view = externalTable.convertRowIndexToView(i);
                                    if (view >= 0) externalTable.addRowSelectionInterval(view, view);
                                }
                            }
                        } finally { changingSelection = false; }
                    }
                    refreshSelectionLabel();
                    revalidate();
                    repaint();
                } finally {
                    externalRefreshRunning = false;
                }
            });
        });
    }

    private void buildSelected(BuildDeployMode mode) {
        boolean externalSelection = selectionSource() == SelectionSource.EXTERNAL;
        List<ServiceProfile> services;
        if (externalSelection) {
            List<ExternalDeployment> external = selectedExternalDeployments();
            if (external.isEmpty()) { append("Select one or more services first."); return; }
            if (external.stream().anyMatch(x -> x.source() == null)) { append("Associate a source project before building external deployments."); return; }
            services = external.stream().map(x -> new ServiceProfile(x.source())).toList();
        } else {
            services = selectedServices().stream().map(ServiceProfile::new).toList();
        }
        if (services.isEmpty()) { append("Select one or more services first."); return; }
        ServerProfile server = mode == BuildDeployMode.FORCE_DEPLOY ? requireServer() : selectedServer();
        if (mode == BuildDeployMode.FORCE_DEPLOY && server == null) return;
        append("Building " + services.size() + " service(s) sequentially.");
        buildAt(services, server, 0, mode, externalSelection);
    }

    private void buildAt(List<ServiceProfile> services, ServerProfile server, int index, BuildDeployMode mode, boolean externalSelection) {
        if (index >= services.size()) { append("Build operation completed."); refreshExternalDeployments(); return; }
        ServiceProfile service = services.get(index);
        rememberService(service);
        ArtifactAutoDeployService watcher = ArtifactAutoDeployService.getInstance(project);
        if (mode != BuildDeployMode.AUTO) watcher.suppress(service);
        BuildService.build(project, service,
                () -> {
                    if (mode != BuildDeployMode.AUTO) watcher.releaseSuppression(service);
                    if (mode == BuildDeployMode.FORCE_DEPLOY && server != null) {
                        deployService(service, server, () -> buildAt(services, server, index + 1, mode, externalSelection));
                    } else if (mode == BuildDeployMode.AUTO && externalSelection && service.deployAfterBuild && server != null) {
                        // External remembered sources are not watched by this project's Auto watcher.
                        deployService(service, server, () -> buildAt(services, server, index + 1, mode, true));
                    } else {
                        buildAt(services, server, index + 1, mode, externalSelection);
                    }
                },
                () -> {
                    if (mode != BuildDeployMode.AUTO) watcher.releaseSuppression(service);
                    append("Build operation stopped after failure in " + service.name);
                },
                this::append);
    }

    private void redeploySelected() {
        ServerProfile server = requireServer();
        if (server == null) return;
        if (selectionSource() == SelectionSource.EXTERNAL) {
            List<ExternalDeployment> selected = selectedExternalDeployments();
            if (selected.isEmpty()) { append("Select one or more deployments first."); return; }
            redeployExternalAt(selected, server, 0);
            return;
        }
        List<ServiceProfile> selected = selectedServices().stream().map(ServiceProfile::new).toList();
        if (selected.isEmpty()) { append("Select one or more services first."); return; }
        deployAt(selected, server, 0);
    }

    private void deployAt(List<ServiceProfile> services, ServerProfile server, int index) {
        if (index >= services.size()) { refreshExternalDeployments(); return; }
        deployService(services.get(index), server, () -> deployAt(services, server, index + 1));
    }

    private void redeployExternalAt(List<ExternalDeployment> deployments, ServerProfile server, int index) {
        if (index >= deployments.size()) { refreshExternalDeployments(); return; }
        ExternalDeployment external = deployments.get(index);
        if (external.source() != null) {
            deployService(new ServiceProfile(external.source()), server, () -> redeployExternalAt(deployments, server, index + 1));
        } else {
            DeploymentScannerService.redeployExisting(project, server, external.deploymentName(), this::append,
                    ok -> redeployExternalAt(deployments, server, index + 1));
        }
    }

    private void deployService(ServiceProfile service, ServerProfile server, Runnable completion) {
        try {
            Path artifact = ArtifactLocator.resolve(project, service);
            String name = ArtifactLocator.effectiveDeploymentName(service, artifact);
            service.deploymentName = name;
            rememberService(service);
            append("Deploying " + service.name + " as " + name);
            DeploymentScannerService.deploy(project, server, artifact, name, this::append, ok -> {
                ApplicationManager.getApplication().invokeLater(() -> {
                    serviceTable.repaint();
                    refreshExternalDeployments();
                });
                if (ok && completion != null) completion.run();
            });
        } catch (Exception e) {
            append("ERROR: " + e.getMessage());
        }
    }

    private void undeploySelected() {
        ServerProfile server = requireServer();
        if (server == null) return;
        List<String> names = new ArrayList<>();
        if (selectionSource() == SelectionSource.EXTERNAL) {
            for (ExternalDeployment external : selectedExternalDeployments()) names.add(external.deploymentName());
        } else {
            for (ServiceProfile service : selectedServices()) names.add(deploymentNameForStatus(service));
        }
        if (names.isEmpty()) { append("Select one or more services first."); return; }
        undeployNamesAt(names, server, 0);
    }

    private void undeployNamesAt(List<String> names, ServerProfile server, int index) {
        if (index >= names.size()) { ApplicationManager.getApplication().invokeLater(this::refreshExternalDeployments); return; }
        DeploymentScannerService.undeploy(project, server, names.get(index), this::append,
                ok -> undeployNamesAt(names, server, index + 1));
    }

    private void openSelectedInBrowser() {
        ServerProfile server = requireServer();
        if (server == null) return;
        String deploymentName;
        String contextPath = "";
        if (selectionSource() == SelectionSource.EXTERNAL) {
            List<ExternalDeployment> selected = selectedExternalDeployments();
            if (selected.size() != 1) { append("Select exactly one service to open in the browser."); return; }
            ExternalDeployment external = selected.get(0);
            deploymentName = external.deploymentName();
            if (external.source() != null) contextPath = Objects.toString(external.source().contextPath, "");
        } else {
            List<ServiceProfile> selected = selectedServices();
            if (selected.size() != 1) { append("Select exactly one service to open in the browser."); return; }
            ServiceProfile service = selected.get(0);
            deploymentName = deploymentNameForStatus(service);
            contextPath = Objects.toString(service.contextPath, "");
        }
        String url = browserUrl(server, deploymentName, contextPath);
        append("Opening " + url);
        BrowserUtil.browse(url);
    }

    private static String browserUrl(ServerProfile server, String deploymentName, String configuredContext) {
        String host = server.host == null || server.host.isBlank() ? "localhost" : server.host.trim();
        if (host.equals("0.0.0.0") || host.equals("::") || host.equals("::0")) host = "localhost";
        int port = server.httpPort > 0 ? server.httpPort : 8080;
        String context = configuredContext == null ? "" : configuredContext.trim();
        if (context.isBlank()) context = contextFromDeploymentName(deploymentName);
        if (context.equals("/") || context.equalsIgnoreCase("ROOT")) context = "";
        while (context.startsWith("/")) context = context.substring(1);
        while (context.endsWith("/") && !context.isEmpty()) context = context.substring(0, context.length() - 1);
        return "http://" + host + ":" + port + (context.isEmpty() ? "/" : "/" + context + "/");
    }

    private static String contextFromDeploymentName(String deploymentName) {
        if (deploymentName == null) return "";
        String name = deploymentName.trim();
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : List.of(".war", ".ear", ".jar")) {
            if (lower.endsWith(ext)) return name.substring(0, name.length() - ext.length());
        }
        return name;
    }

    private void addServer() {
        ServerProfileDialog dialog = new ServerProfileDialog(project, null);
        if (!dialog.showAndGet()) return;
        ServerProfile profile = dialog.getProfile();
        WildFlyApplicationSettings.getInstance().servers().add(profile);
        refreshServers();
        serverCombo.setSelectedItem(profile);
        saveSelectedServer();
        syncAutoDeployWatcher();
        append("Added server profile: " + profile.name);
    }

    private void editServer() {
        ServerProfile current = selectedServer();
        if (current == null) { append("Add a WildFly server first."); return; }
        ServerProfileDialog dialog = new ServerProfileDialog(project, current);
        if (!dialog.showAndGet()) return;
        ServerProfile edited = dialog.getProfile();
        List<ServerProfile> servers = WildFlyApplicationSettings.getInstance().servers();
        for (int i = 0; i < servers.size(); i++) if (servers.get(i).id.equals(current.id)) { servers.set(i, edited); break; }
        refreshServers();
        selectServerById(edited.id);
        saveSelectedServer();
        syncAutoDeployWatcher();
        append("Updated server profile: " + edited.name);
    }

    private void removeServer() {
        ServerProfile current = selectedServer();
        if (current == null) return;
        int answer = JOptionPane.showConfirmDialog(this, "Remove server profile '" + current.name + "'?", "Remove WildFly Server", JOptionPane.YES_NO_OPTION);
        if (answer != JOptionPane.YES_OPTION) return;
        WildFlyApplicationSettings.getInstance().servers().removeIf(p -> p.id.equals(current.id));
        refreshServers();
        saveSelectedServer();
        syncAutoDeployWatcher();
    }

    private void startServer(boolean debug) {
        ServerProfile profile = requireServer();
        if (profile == null) return;
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try { WildFlyProcessService.getInstance().start(profile, debug, this::append); }
            catch (Exception e) { append("ERROR: " + e.getMessage()); }
        });
    }

    private void startDebug() {
        ServerProfile profile = requireServer();
        if (profile == null) return;
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                WildFlyProcessService process = WildFlyProcessService.getInstance();
                WildFlyProcessService.ServerState state = process.state(profile);
                if (state == WildFlyProcessService.ServerState.MANAGED) {
                    process.terminateAndWait(profile, this::append);
                    process.start(profile, true, this::append);
                } else if (state == WildFlyProcessService.ServerState.STOPPED) {
                    process.start(profile, true, this::append);
                } else if (state == WildFlyProcessService.ServerState.DETECTED) {
                    append("WildFly is already running outside this managed process. Trying to attach to debug port " + profile.debugPort + " without restarting it.");
                }
                DebugAttachService.attachWhenAvailable(project, debuggerHost(profile), profile.debugPort, this::append);
            } catch (Exception e) {
                append("ERROR: " + e.getMessage());
            }
        });
    }

    private void attachDebugger() {
        ServerProfile profile = requireServer();
        if (profile != null) DebugAttachService.attachWhenAvailable(project, debuggerHost(profile), profile.debugPort, this::append);
    }

    private void stopServer() {
        ServerProfile selected = requireServer();
        if (selected == null) return;
        ServerProfile profile = new ServerProfile(selected);
        WildFlyProcessService process = WildFlyProcessService.getInstance();
        if (process.isRunning(profile)) {
            process.terminate(profile, this::append);
            return;
        }
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            boolean detected = process.isDetectedRunning(profile);
            boolean canForce = detected && process.canForceStopDetected(profile);
            ApplicationManager.getApplication().invokeLater(() -> {
                if (canForce) {
                    int answer = JOptionPane.showConfirmDialog(this,
                            "This WildFly is running locally but was not started by the current IDE session. A unique WildFly process matching this server home was found. Force stop it?",
                            "Stop Detected WildFly", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                    if (answer == JOptionPane.YES_OPTION) {
                        ApplicationManager.getApplication().executeOnPooledThread(() -> process.forceStopDetected(profile, this::append));
                    }
                } else if (detected) {
                    append("WildFly is running at " + process.endpoint(profile) + " but no unique local process could be identified safely, so it was not killed.");
                } else {
                    append("No running WildFly process was detected for " + profile.name + ".");
                }
            });
        });
    }

    private static String debuggerHost(ServerProfile profile) {
        String host = profile.host == null || profile.host.isBlank() ? "localhost" : profile.host.trim();
        return host.equals("0.0.0.0") || host.equals("::") || host.equals("::0") ? "localhost" : host;
    }

    private void editStandaloneConfig() {
        ServerProfile profile = requireServer();
        if (profile != null) openInEditor(WildFlyPaths.configurationFile(profile));
    }

    private void openLog() {
        ServerProfile profile = requireServer();
        if (profile == null) return;
        Path log = WildFlyPaths.logFile(profile);
        if (!Files.isRegularFile(log)) { append("server.log not found yet: " + log); return; }
        openInEditor(log);
    }

    private void openWildFlyHome() {
        ServerProfile profile = requireServer();
        if (profile != null) openFolder(WildFlyPaths.home(profile));
    }

    private void openDeploymentsFolder() {
        ServerProfile profile = requireServer();
        if (profile != null) openFolder(WildFlyPaths.deploymentsDir(profile));
    }

    private void openSelectedModuleFolder() {
        ServiceProfile service = singleSelectedSourceProfile();
        if (service == null) { append("Select exactly one service with an associated source project."); return; }
        try { openFolder(BuildService.resolveModuleDir(project, service)); }
        catch (Exception e) { append("ERROR: " + e.getMessage()); }
    }

    private void openSelectedArtifactFolder() {
        ServiceProfile service = singleSelectedSourceProfile();
        if (service == null) { append("Select exactly one service with an associated source project."); return; }
        try { openFolder(ArtifactLocator.resolve(project, service).getParent()); }
        catch (Exception e) { append("ERROR: " + e.getMessage()); }
    }

    private ServiceProfile singleSelectedSourceProfile() {
        if (selectionSource() == SelectionSource.PROJECT) {
            List<ServiceProfile> selected = selectedServices();
            return selected.size() == 1 ? selected.get(0) : null;
        }
        List<ExternalDeployment> selected = selectedExternalDeployments();
        return selected.size() == 1 && selected.get(0).source() != null ? selected.get(0).source() : null;
    }

    private void openInEditor(Path path) {
        ApplicationManager.getApplication().invokeLater(() -> {
            VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
            if (file == null) { append("File not found: " + path); return; }
            FileEditorManager.getInstance(project).openFile(file, true);
        });
    }

    private void openFolder(Path path) {
        try {
            if (!Files.isDirectory(path)) { append("Folder not found: " + path); return; }
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(path.toFile());
            else append("Desktop folder opening is not supported on this system: " + path);
        } catch (Exception e) { append("ERROR opening folder: " + e.getMessage()); }
    }

    private void refreshServerState() {
        ServerProfile selected = selectedServer();
        if (selected == null) {
            serverStateLabel.setText("No server");
            serverStateLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
            return;
        }
        WildFlyProcessService process = WildFlyProcessService.getInstance();
        if (process.isRunning(selected)) {
            applyServerState(selected, process.isDebugRunning(selected)
                    ? WildFlyProcessService.ServerState.MANAGED_DEBUG
                    : WildFlyProcessService.ServerState.MANAGED);
            return;
        }
        if (serverStateRefreshRunning) return;
        serverStateRefreshRunning = true;
        ServerProfile snapshot = new ServerProfile(selected);
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            WildFlyProcessService.ServerState state = process.state(snapshot);
            ApplicationManager.getApplication().invokeLater(() -> {
                try {
                    ServerProfile current = selectedServer();
                    if (current != null && Objects.equals(current.id, snapshot.id)) applyServerState(current, state);
                } finally {
                    serverStateRefreshRunning = false;
                }
            });
        });
    }

    private void applyServerState(ServerProfile server, WildFlyProcessService.ServerState state) {
        switch (state) {
            case MANAGED_DEBUG -> {
                serverStateLabel.setText("Managed debug :" + server.debugPort);
                serverStateLabel.setForeground(JBUI.CurrentTheme.ProgressBar.PASSED);
            }
            case MANAGED -> {
                serverStateLabel.setText("Managed running");
                serverStateLabel.setForeground(JBUI.CurrentTheme.ProgressBar.PASSED);
            }
            case DETECTED -> {
                serverStateLabel.setText("Detected running · " + debuggerHost(server) + ":" + server.httpPort);
                serverStateLabel.setForeground(JBUI.CurrentTheme.ProgressBar.WARNING);
            }
            case STOPPED -> {
                serverStateLabel.setText("Stopped");
                serverStateLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
            }
        }
    }

    private void refreshSelectionLabel() {
        if (selectionSource() == SelectionSource.EXTERNAL) {
            int count = externalTable.getSelectedRowCount();
            selectionLabel.setText(count == 1 ? "1 external deployment selected" : count + " external deployments selected");
        } else {
            int selected = serviceTable.getSelectedRowCount();
            int total = projectState().services.size();
            selectionLabel.setText(selected == 0 ? total + " project service" + (total == 1 ? "" : "s") : selected + " selected · " + total + " project service" + (total == 1 ? "" : "s"));
        }
    }

    private SelectionSource selectionSource() {
        if (externalTable.getSelectedRowCount() > 0) return SelectionSource.EXTERNAL;
        if (serviceTable.getSelectedRowCount() > 0) return SelectionSource.PROJECT;
        return SelectionSource.NONE;
    }

    private void clearAllSelection() {
        changingSelection = true;
        try {
            serviceTable.clearSelection();
            externalTable.clearSelection();
            projectState().selectedServiceId = "";
        } finally { changingSelection = false; }
        refreshSelectionLabel();
    }

    private List<ServiceProfile> selectedServices() {
        int[] rows = serviceTable.getSelectedRows();
        List<ServiceProfile> selected = new ArrayList<>(rows.length);
        for (int viewRow : rows) {
            int modelRow = serviceTable.convertRowIndexToModel(viewRow);
            if (modelRow >= 0 && modelRow < projectState().services.size()) selected.add(projectState().services.get(modelRow));
        }
        return selected;
    }

    private List<ExternalDeployment> selectedExternalDeployments() {
        int[] rows = externalTable.getSelectedRows();
        List<ExternalDeployment> selected = new ArrayList<>(rows.length);
        for (int viewRow : rows) {
            int modelRow = externalTable.convertRowIndexToModel(viewRow);
            if (modelRow >= 0 && modelRow < externalDeployments.size()) selected.add(externalDeployments.get(modelRow));
        }
        return selected;
    }

    private void refreshServiceTablePreservingSelection() {
        Set<String> ids = new LinkedHashSet<>();
        for (ServiceProfile service : selectedServices()) ids.add(service.id);
        refreshingTable = true;
        try {
            serviceTableModel.fireTableDataChanged();
            serviceTable.clearSelection();
            for (int modelRow = 0; modelRow < projectState().services.size(); modelRow++) {
                if (ids.contains(projectState().services.get(modelRow).id)) {
                    int viewRow = serviceTable.convertRowIndexToView(modelRow);
                    if (viewRow >= 0) serviceTable.addRowSelectionInterval(viewRow, viewRow);
                }
            }
        } finally { refreshingTable = false; }
        syncAutoDeployWatcher();
        refreshSelectionLabel();
    }

    private void selectServiceById(String id) {
        if (id == null || id.isBlank()) return;
        for (int modelRow = 0; modelRow < projectState().services.size(); modelRow++) {
            if (id.equals(projectState().services.get(modelRow).id)) {
                int viewRow = serviceTable.convertRowIndexToView(modelRow);
                if (viewRow >= 0) serviceTable.setRowSelectionInterval(viewRow, viewRow);
                return;
            }
        }
    }

    private boolean selectServerById(String id) {
        if (id == null || id.isBlank()) return false;
        for (int i = 0; i < serverCombo.getItemCount(); i++) {
            if (id.equals(serverCombo.getItemAt(i).id)) {
                serverCombo.setSelectedIndex(i);
                return true;
            }
        }
        return false;
    }

    private void saveSelectedServer() {
        if (loading) return;
        ServerProfile server = selectedServer();
        String id = server == null ? "" : server.id;
        projectState().selectedServerId = id;
        WildFlyApplicationSettings.getInstance().setLastServerId(id);
    }

    private void syncAutoDeployWatcher() {
        if (loading || project.isDisposed()) return;
        ArtifactAutoDeployService.getInstance(project).configure(projectState().services, selectedServer(), this::append);
    }

    private ServerProfile selectedServer() { return (ServerProfile) serverCombo.getSelectedItem(); }

    private ServerProfile requireServer() {
        ServerProfile server = selectedServer();
        if (server == null) append("Add/select a WildFly server first.");
        return server;
    }

    private WildFlyProjectSettings.StateData projectState() { return WildFlyProjectSettings.getInstance(project).getState(); }

    private void rememberService(ServiceProfile service) {
        WildFlyApplicationSettings.getInstance().rememberService(service);
    }

    private static void copyService(ServiceProfile source, ServiceProfile target) {
        target.name = source.name;
        target.buildSystem = source.buildSystem;
        target.buildFilePath = source.buildFilePath;
        target.buildTasks = source.buildTasks;
        target.buildArguments = source.buildArguments;
        target.buildJvmOptions = source.buildJvmOptions;
        target.packaging = source.packaging;
        target.artifactPath = source.artifactPath;
        target.deploymentName = source.deploymentName;
        target.contextPath = source.contextPath;
        target.deployAfterBuild = source.deployAfterBuild;
    }

    private static ServiceProfile serviceDraftForExternal(String deploymentName) {
        ServiceProfile service = new ServiceProfile();
        service.name = contextFromDeploymentName(deploymentName);
        if (service.name.isBlank()) service.name = deploymentName;
        service.deploymentName = deploymentName;
        service.contextPath = contextFromDeploymentName(deploymentName);
        String lower = deploymentName.toLowerCase(Locale.ROOT);
        service.packaging = lower.endsWith(".ear") ? "ear" : lower.endsWith(".jar") ? "jar" : "war";
        return service;
    }

    private String deploymentNameForStatus(ServiceProfile service) {
        return service.deploymentName == null || service.deploymentName.isBlank() ? defaultDeploymentName(service) : service.deploymentName.trim();
    }

    private static String defaultDeploymentName(ServiceProfile service) {
        String ext = normalizePackaging(service.packaging);
        if ("auto".equals(ext)) ext = "war";
        String base = service.name == null || service.name.isBlank() ? "service" : service.name.replaceAll("[^A-Za-z0-9._-]", "-");
        return base + "." + ext;
    }

    private static String normalizePackaging(String packaging) {
        if (packaging == null) return "auto";
        String value = packaging.trim().toLowerCase(Locale.ROOT);
        return List.of("war", "ear", "jar").contains(value) ? value : "auto";
    }

    private static boolean samePath(String a, String b) {
        if (a == null || b == null || a.isBlank() || b.isBlank()) return false;
        try { return Path.of(a).toAbsolutePath().normalize().equals(Path.of(b).toAbsolutePath().normalize()); }
        catch (Exception e) { return Objects.equals(a, b); }
    }

    private void append(String message) {
        if (message == null || message.isBlank()) return;
        SwingUtilities.invokeLater(() -> {
            output.append("[" + LocalTime.now().format(TIME) + "] " + message + System.lineSeparator());
            output.setCaretPosition(output.getDocument().getLength());
        });
    }

    private static JButton iconButton(Icon icon, String tooltip) {
        JButton button = new JButton(icon);
        button.setToolTipText(tooltip);
        button.setFocusable(false);
        button.setMargin(new Insets(2, 5, 2, 5));
        return button;
    }

    private static JButton popupButton(Icon icon, String tooltip, JPopupMenu menu) {
        JButton button = iconButton(icon, tooltip);
        button.addActionListener(e -> menu.show(button, 0, button.getHeight()));
        return button;
    }

    private DeploymentStatusView deploymentStatusView(String deploymentName) {
        ServerProfile server = selectedServer();
        return new DeploymentStatusView(
                DeploymentScannerService.status(server, deploymentName),
                DeploymentScannerService.lastDeployedAt(server, deploymentName)
        );
    }

    private static JPanel compactToolbar(JComponent... components) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 0));
        for (JComponent component : components) panel.add(component);
        return panel;
    }

    private final class ServiceTableModel extends AbstractTableModel {
        private final String[] columns = {"Auto", "Service / module", "Build", "Status"};
        @Override public int getRowCount() { return projectState().services.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int column) { return columns[column]; }
        @Override public Class<?> getColumnClass(int column) { return column == 0 ? Boolean.class : column == 3 ? DeploymentStatusView.class : String.class; }
        @Override public boolean isCellEditable(int row, int column) { return column == 0; }
        @Override public Object getValueAt(int row, int column) {
            ServiceProfile service = projectState().services.get(row);
            service.migrateLegacyFields();
            return switch (column) {
                case 0 -> service.deployAfterBuild;
                case 1 -> ServicePresentation.displayName(project, service);
                case 2 -> service.buildSystemEnum().toString();
                case 3 -> deploymentStatusView(deploymentNameForStatus(service));
                default -> "";
            };
        }
        @Override public void setValueAt(Object value, int row, int column) {
            if (column == 0 && value instanceof Boolean enabled) {
                ServiceProfile service = projectState().services.get(row);
                service.deployAfterBuild = enabled;
                rememberService(service);
                fireTableCellUpdated(row, column);
                syncAutoDeployWatcher();
            }
        }
    }

    private final class ExternalTableModel extends AbstractTableModel {
        private final String[] columns = {"Deployment", "Source", "Status"};
        @Override public int getRowCount() { return externalDeployments.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int column) { return columns[column]; }
        @Override public Object getValueAt(int row, int column) {
            ExternalDeployment external = externalDeployments.get(row);
            return switch (column) {
                case 0 -> external.deploymentName();
                case 1 -> external.source() == null ? "Source not associated" : sourceLabel(external.source());
                case 2 -> deploymentStatusView(external.deploymentName());
                default -> "";
            };
        }
        private String sourceLabel(ServiceProfile source) {
            String file = Objects.toString(source.buildFilePath, "");
            if (file.isBlank()) return source.buildSystemEnum().toString();
            try {
                Path parent = Path.of(file).toAbsolutePath().normalize().getParent();
                return source.buildSystemEnum() + " · " + (parent == null ? file : parent.toString());
            } catch (Exception e) {
                return source.buildSystemEnum() + " · " + file;
            }
        }
    }

    private final class ServiceNameRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            JLabel label = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            String full = value == null ? "" : value.toString();
            int width = table.getColumnModel().getColumn(column).getWidth() - JBUI.scale(12);
            label.setText(ellipsize(full, label.getFontMetrics(label.getFont()), Math.max(width, JBUI.scale(40))));
            int modelRow = table.convertRowIndexToModel(row);
            if (modelRow >= 0 && modelRow < projectState().services.size()) {
                ServiceProfile service = projectState().services.get(modelRow);
                String path = ServicePresentation.buildPathTooltip(service);
                label.setToolTipText(path == null || path.isBlank() ? full : "<html>" + full + "<br>" + path + "</html>");
            }
            return label;
        }
    }

    private static String ellipsize(String text, FontMetrics metrics, int maxWidth) {
        if (metrics.stringWidth(text) <= maxWidth) return text;
        String suffix = "…";
        int suffixWidth = metrics.stringWidth(suffix);
        int low = 0;
        int high = text.length();
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (metrics.stringWidth(text.substring(0, mid)) + suffixWidth <= maxWidth) low = mid;
            else high = mid - 1;
        }
        return text.substring(0, low) + suffix;
    }

    private static final class StatusRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            JLabel label = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            DeploymentStatusView view = value instanceof DeploymentStatusView status
                    ? status
                    : new DeploymentStatusView(value == null ? "NOT DEPLOYED" : value.toString(), null);
            String state = view.state();
            label.setText(switch (state) {
                case "DEPLOYED" -> "● Deployed";
                case "FAILED" -> "● Failed";
                case "DEPLOYING" -> "● Deploying";
                default -> "○ Not deployed";
            });
            if (view.deployedAt() != null) {
                String when = DEPLOY_TIME.format(view.deployedAt().atZone(ZoneId.systemDefault()));
                String prefix = "DEPLOYED".equals(state) ? "Deployed" : "Last successful deployment";
                label.setToolTipText(prefix + ": " + when);
            } else {
                label.setToolTipText(label.getText().substring(2));
            }
            if (!isSelected) {
                label.setForeground(switch (state) {
                    case "DEPLOYED" -> JBUI.CurrentTheme.ProgressBar.PASSED;
                    case "FAILED" -> JBUI.CurrentTheme.ProgressBar.FAILED;
                    case "DEPLOYING" -> JBUI.CurrentTheme.ProgressBar.WARNING;
                    default -> table.getForeground();
                });
            }
            return label;
        }
    }
}
