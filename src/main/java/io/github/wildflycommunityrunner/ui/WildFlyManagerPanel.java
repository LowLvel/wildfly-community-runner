package io.github.wildflycommunityrunner.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.progress.ProcessCanceledException;
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
import io.github.wildflycommunityrunner.services.BuildBatch;
import io.github.wildflycommunityrunner.services.BuildLifecycleService;
import io.github.wildflycommunityrunner.services.BuildProjectDiscoveryService;
import io.github.wildflycommunityrunner.services.BuildProjectDiscoveryService.BuildProjectChoice;
import io.github.wildflycommunityrunner.services.BuildService;
import io.github.wildflycommunityrunner.services.DebugAttachService;
import io.github.wildflycommunityrunner.services.DeploymentScannerService;
import io.github.wildflycommunityrunner.services.WildFlyProcessService;
import io.github.wildflycommunityrunner.settings.WildFlyApplicationSettings;
import io.github.wildflycommunityrunner.settings.WildFlyProjectSettings;
import io.github.wildflycommunityrunner.util.ArtifactLocator;
import io.github.wildflycommunityrunner.util.IdeUi;
import io.github.wildflycommunityrunner.services.PluginNotifications;
import io.github.wildflycommunityrunner.services.ProjectSetupService;
import io.github.wildflycommunityrunner.services.WildFlyServerDetector;
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
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.HashMap;
import java.util.function.Consumer;
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
public final class WildFlyManagerPanel extends JPanel implements Disposable {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DEPLOY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private enum SelectionSource { PROJECT, EXTERNAL, NONE }

    private record ExternalDeployment(String deploymentName, ServiceProfile source) {}
    private record DeploymentStatusView(String state, Instant deployedAt) {}

    private final Project project;
    private final JComboBox<ServerProfile> serverCombo = new JComboBox<>();
    private final JLabel serverStateLabel = new JLabel(" ");
    private final JPanel onboardingHint = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));

    private final ServiceTableModel serviceTableModel = new ServiceTableModel();
    private final JTable serviceTable = new JTable(serviceTableModel);
    private final ExternalTableModel externalTableModel = new ExternalTableModel();
    private final JTable externalTable = new JTable(externalTableModel);
    private final JPanel externalSection = new JPanel(new BorderLayout(4, 4));
    private final JLabel buildProgress = new JLabel("No build running");
    private final JButton cancelBuild = new JButton("Cancel Build");
    private final JLabel selectionLabel = new JLabel("No service selected");

    private final JTextArea output = new JTextArea();
    private final Timer refreshTimer;
    private List<BuildProjectChoice> buildChoices = List.of();
    private List<ExternalDeployment> externalDeployments = List.of();
    private boolean loading;
    private volatile boolean disposed;
    private final Consumer<String> activityOutput;
    private final StringBuilder pendingOutput = new StringBuilder();
    private boolean outputScheduled;
    private static final int MAX_ACTIVITY_CHARS = 200_000;
    private Map<String, DeploymentStatusView> deploymentStatuses = Map.of();
    private String statusServerId = "";
    private boolean refreshingTable;
    private boolean changingSelection;
    private volatile boolean serverStateRefreshRunning;
    private volatile boolean externalRefreshRunning;

    public WildFlyManagerPanel(Project project) {
        super(new BorderLayout());
        this.project = project;
        WeakReference<WildFlyManagerPanel> weakPanel = new WeakReference<>(this);
        activityOutput = message -> {
            WildFlyManagerPanel panel = weakPanel.get();
            if (panel != null) panel.append(message);
        };
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        buildUi();
        loadSettings();
        project.getMessageBus().connect(this).subscribe(ProjectSetupService.CHANGED,
                () -> onUi(this::refreshAfterSetup));
        project.getMessageBus().connect(this).subscribe(BuildLifecycleService.CHANGED,
                () -> onUi(() -> { refreshBuildProgress(); refreshExternalDeployments(); }));
        refreshBuildProgress();
        refreshTimer = new Timer(2000, e -> {
            if (disposed || project.isDisposed()) {
                ((Timer) e.getSource()).stop();
            } else {
                onUi(() -> { refreshExternalDeployments(); refreshServerState(); });
            }
        });
        refreshTimer.start();
    }

    @Override
    public void addNotify() {
        super.addNotify();
        if (!disposed && !project.isDisposed() && !refreshTimer.isRunning()) refreshTimer.start();
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
        JPanel footer = new JPanel(new BorderLayout(4, 4));
        footer.add(buildSelectionActionBar(), BorderLayout.NORTH);
        JPanel progressRow = new JPanel(new BorderLayout(4, 0));
        progressRow.add(buildProgress, BorderLayout.CENTER);
        progressRow.add(cancelBuild, BorderLayout.EAST);
        cancelBuild.addActionListener(e -> onUi(() -> project.getService(BuildLifecycleService.class).cancel()));
        footer.add(progressRow, BorderLayout.SOUTH);
        servicesTab.add(footer, BorderLayout.SOUTH);

        JPanel logsTab = new JPanel(new BorderLayout(4, 4));
        logsTab.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        JPanel logToolbar = new JPanel(new BorderLayout(4, 0));
        JLabel logTitle = new JLabel("WildFly / build activity");
        JPanel logActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 0));
        JButton openServerLog = new JButton("server.log");
        JButton clear = new JButton("Clear");
        openServerLog.addActionListener(e -> onUi(() -> openLog()));
        clear.addActionListener(e -> onUi(() -> output.setText("")));
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
        onboardingHint.add(new JLabel("Choose your local WildFly installation to get started."));
        JButton setup = new JButton("Add server…");
        setup.addActionListener(e -> onUi(this::addServer));
        onboardingHint.add(setup);
        panel.add(onboardingHint, BorderLayout.SOUTH);

        serverCombo.addActionListener(e -> {
            if (loading) return;
            onUi(() -> {
                deploymentStatuses = Map.of();
                statusServerId = "";
                saveSelectedServer();
                syncAutoDeployWatcher();
                serviceTable.repaint();
                refreshExternalDeployments();
                refreshServerState();
            });
        });
        start.addActionListener(e -> onUi(() -> startServer(false)));
        debug.addActionListener(e -> onUi(() -> startDebug()));
        stop.addActionListener(e -> onUi(() -> stopServer()));
        attach.addActionListener(e -> onUi(() -> attachDebugger()));
        add.addActionListener(e -> onUi(() -> addServer()));
        edit.addActionListener(e -> onUi(() -> editServer()));
        remove.addActionListener(e -> onUi(() -> removeServer()));
        config.addActionListener(e -> onUi(() -> editStandaloneConfig()));
        home.addActionListener(e -> onUi(() -> openWildFlyHome()));
        deployments.addActionListener(e -> onUi(() -> openDeploymentsFolder()));
        log.addActionListener(e -> onUi(() -> openLog()));
        return panel;
    }

    private JComponent buildServicesWorkspace() {
        configureProjectServiceTable();
        configureExternalTable();

        JComponent projectTablePanel = ToolbarDecorator.createDecorator(serviceTable)
                .setAddAction(button -> onUi(this::addCustomService))
                .setAddActionName("Add Service")
                .setEditAction(button -> onUi(this::openSelectedServiceDetails))
                .setEditActionName("Edit Service")
                .setRemoveAction(button -> onUi(this::removeSelectedService))
                .setRemoveActionName("Remove Service")
                .disableUpDownActions()
                .addExtraAction(new DumbAwareAction("Discover Projects", "Find Maven and Gradle projects recursively", AllIcons.Actions.Refresh) {
                    @Override public void actionPerformed(AnActionEvent e) { onUi(WildFlyManagerPanel.this::discoverBuildProjects); }
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
                    onUi(WildFlyManagerPanel.this::openSelectedServiceDetails);
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
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { onUi(WildFlyManagerPanel.this::openSelectedServiceDetails); }
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
                    onUi(WildFlyManagerPanel.this::openSelectedInBrowser);
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

        build.addActionListener(e -> onUi(() -> buildSelected(BuildBatch.Mode.AUTO)));
        redeploy.addActionListener(e -> onUi(() -> redeploySelected()));
        browser.addActionListener(e -> onUi(() -> openSelectedInBrowser()));
        buildDeploy.addActionListener(e -> onUi(() -> buildSelected(BuildBatch.Mode.FORCE_DEPLOY)));
        buildOnly.addActionListener(e -> onUi(() -> buildSelected(BuildBatch.Mode.BUILD_ONLY)));
        undeploy.addActionListener(e -> onUi(() -> undeploySelected()));
        autoOn.addActionListener(e -> onUi(() -> setSelectedAutoDeploy(true)));
        autoOff.addActionListener(e -> onUi(() -> setSelectedAutoDeploy(false)));
        edit.addActionListener(e -> onUi(() -> openSelectedServiceDetails()));
        associate.addActionListener(e -> onUi(() -> associateExternalSource()));
        addToProject.addActionListener(e -> onUi(() -> addExternalToProject()));
        openModule.addActionListener(e -> onUi(() -> openSelectedModuleFolder()));
        openArtifact.addActionListener(e -> onUi(() -> openSelectedArtifactFolder()));
        remove.addActionListener(e -> onUi(() -> removeSelectedService()));
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
        build.addActionListener(e -> onUi(() -> buildSelected(BuildBatch.Mode.AUTO)));
        buildDeploy.addActionListener(e -> onUi(() -> buildSelected(BuildBatch.Mode.FORCE_DEPLOY)));
        buildOnly.addActionListener(e -> onUi(() -> buildSelected(BuildBatch.Mode.BUILD_ONLY)));
        redeploy.addActionListener(e -> onUi(() -> redeploySelected()));
        undeploy.addActionListener(e -> onUi(() -> undeploySelected()));
        browser.addActionListener(e -> onUi(() -> openSelectedInBrowser()));
        autoOn.addActionListener(e -> onUi(() -> setSelectedAutoDeploy(true)));
        autoOff.addActionListener(e -> onUi(() -> setSelectedAutoDeploy(false)));
        edit.addActionListener(e -> onUi(() -> openSelectedServiceDetails()));
        module.addActionListener(e -> onUi(() -> openSelectedModuleFolder()));
        artifact.addActionListener(e -> onUi(() -> openSelectedArtifactFolder()));
        remove.addActionListener(e -> onUi(() -> removeSelectedService()));
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
        build.addActionListener(e -> onUi(() -> buildSelected(BuildBatch.Mode.AUTO)));
        buildDeploy.addActionListener(e -> onUi(() -> buildSelected(BuildBatch.Mode.FORCE_DEPLOY)));
        redeploy.addActionListener(e -> onUi(() -> redeploySelected()));
        undeploy.addActionListener(e -> onUi(() -> undeploySelected()));
        browser.addActionListener(e -> onUi(() -> openSelectedInBrowser()));
        associate.addActionListener(e -> onUi(() -> associateExternalSource()));
        addToProject.addActionListener(e -> onUi(() -> addExternalToProject()));
        module.addActionListener(e -> onUi(() -> openSelectedModuleFolder()));
        artifact.addActionListener(e -> onUi(() -> openSelectedArtifactFolder()));
        return menu;
    }

    private void loadSettings() {
        loading = true;
        refreshServers();
        refreshBuildChoices(false);
        WildFlyProjectSettings.StateData state = projectState();
        WildFlyProjectSettings.getInstance(project).migrateLegacyService();
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

    private void refreshServers() {
        String selectedId = selectedServer() == null ? null : selectedServer().id;
        DefaultComboBoxModel<ServerProfile> model = new DefaultComboBoxModel<>();
        for (ServerProfile server : WildFlyApplicationSettings.getInstance().servers()) model.addElement(server);
        serverCombo.setModel(model);
        onboardingHint.setVisible(model.getSize() == 0);
        if (selectedId != null) selectServerById(selectedId);
    }

    private void refreshAfterSetup() {
        loading = true;
        refreshServers();
        selectServerById(projectState().selectedServerId);
        refreshServiceTablePreservingSelection();
        loading = false;
        syncAutoDeployWatcher();
        refreshBuildChoices(true);
        refreshExternalDeployments();
        refreshServerState();
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
            onUi(() -> {
                ServerProfile current = selectedServer();
                if (current != null && process.isRunning(current)) return;
                if (selectServerById(id)) { saveSelectedServer(); refreshServerState(); refreshExternalDeployments(); }
            });
        });
    }

    private void refreshBuildChoices(boolean recursive) {
        background("Project discovery failed", () -> {
            List<BuildProjectChoice> choices = recursive ? BuildProjectDiscoveryService.discover(project)
                    : BuildProjectDiscoveryService.discoverImportedOnly(project);
            onUi(() -> buildChoices = choices);
        });
    }

    private void discoverBuildProjects() {
        append("Scanning workspace for Maven/Gradle projects…");
        background("Project discovery failed", () -> {
            List<BuildProjectChoice> discovered = BuildProjectDiscoveryService.discover(project);
            onUi(() -> {
                buildChoices = discovered;
                WildFlyProjectSettings.StateData state = projectState();
                int added = 0;
                for (BuildProjectChoice choice : discovered) {
                    if (state.services.stream().anyMatch(s -> samePath(s.buildFilePath, choice.buildFilePath()))) continue;
                    ServiceProfile service = ProjectSetupService.discoveredService(choice);
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
        projectState().onboardingCompleted = true;
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
        if (disposed || project.isDisposed()) return;
        ServerProfile selected = selectedServer();
        if (selected == null) {
            deploymentStatuses = Map.of();
            statusServerId = "";
            externalDeployments = List.of();
            externalTableModel.fireTableDataChanged();
            externalSection.setVisible(false);
            return;
        }
        if (externalRefreshRunning) return;
        externalRefreshRunning = true;
        ServerProfile server = new ServerProfile(selected);
        Set<String> localNames = new LinkedHashSet<>();
        for (ServiceProfile service : projectState().services) localNames.add(deploymentNameForStatus(service));
        Set<String> lowerLocalNames = new LinkedHashSet<>();
        localNames.forEach(name -> lowerLocalNames.add(name.toLowerCase(Locale.ROOT)));
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                if (disposed || project.isDisposed()) return;
                WildFlyApplicationSettings app = WildFlyApplicationSettings.getInstance();
                List<ExternalDeployment> rows = new ArrayList<>();
                Map<String, DeploymentStatusView> statuses = new HashMap<>();
                Set<String> allNames = new LinkedHashSet<>(localNames);
                allNames.addAll(DeploymentScannerService.listDeployments(server));
                for (String name : allNames) {
                    String status = DeploymentScannerService.status(server, name);
                    statuses.put(name, new DeploymentStatusView(status, DeploymentScannerService.lastDeployedAt(server, name)));
                    if (!"NOT DEPLOYED".equals(status) && !lowerLocalNames.contains(name.toLowerCase(Locale.ROOT))) {
                        rows.add(new ExternalDeployment(name, app.findKnownServiceByDeploymentName(name)));
                    }
                }
                rows.sort(Comparator.comparing(ExternalDeployment::deploymentName, String.CASE_INSENSITIVE_ORDER));
                onUi(() -> {
                    ServerProfile current = selectedServer();
                    if (current == null || !sameServerLocation(current, server)) return;
                    Set<String> selectedNames = new LinkedHashSet<>();
                    for (ExternalDeployment row : selectedExternalDeployments()) selectedNames.add(row.deploymentName());
                    deploymentStatuses = Map.copyOf(statuses);
                    statusServerId = server.id;
                    externalDeployments = List.copyOf(rows);
                    changingSelection = true;
                    try {
                        externalTableModel.fireTableDataChanged();
                        externalTable.clearSelection();
                        for (int i = 0; i < externalDeployments.size(); i++) {
                            if (selectedNames.contains(externalDeployments.get(i).deploymentName())) {
                                int view = externalTable.convertRowIndexToView(i);
                                if (view >= 0) externalTable.addRowSelectionInterval(view, view);
                            }
                        }
                    } finally { changingSelection = false; }
                    externalSection.setVisible(!externalDeployments.isEmpty());
                    refreshSelectionLabel();
                    revalidate();
                    repaint();
                });
            } catch (ProcessCanceledException cancelled) {
                throw cancelled;
            } catch (Exception error) {
                append("Deployment status refresh failed: " + PluginNotifications.message(error));
            } finally { externalRefreshRunning = false; }
        });
    }

    private static boolean sameServerLocation(ServerProfile a, ServerProfile b) {
        return Objects.equals(a.id, b.id) && Objects.equals(a.home, b.home)
                && Objects.equals(a.configuration, b.configuration) && Objects.equals(a.host, b.host)
                && a.httpPort == b.httpPort && a.debugPort == b.debugPort;
    }

    private void buildSelected(BuildBatch.Mode mode) {
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
        ServerProfile server = mode == BuildBatch.Mode.FORCE_DEPLOY ? requireServer()
                : selectedServer() == null ? null : new ServerProfile(selectedServer());
        if (mode == BuildBatch.Mode.FORCE_DEPLOY && server == null) return;
        project.getService(BuildLifecycleService.class).start(services, server, mode, externalSelection, activityOutput);
    }

    private void refreshBuildProgress() {
        var status = project.getService(BuildLifecycleService.class).status();
        buildProgress.setText(status.text());
        buildProgress.setToolTipText(status.text());
        cancelBuild.setEnabled(status.active());
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
            DeploymentScannerService.redeployExisting(project, server, external.deploymentName(), activityOutput,
                    ok -> onUi(() -> { refreshExternalDeployments(); if (ok) redeployExternalAt(deployments, server, index + 1); }));
        }
    }

    private void deployService(ServiceProfile service, ServerProfile server, Runnable completion) {
        ServerProfile serverSnapshot = new ServerProfile(server);
        ServiceProfile source = new ServiceProfile(service);
        background("Deployment failed", () -> {
            Path artifact = ArtifactLocator.resolve(project, source);
            String name = ArtifactLocator.effectiveDeploymentName(source, artifact);
            source.deploymentName = name;
            rememberService(source);
            append("Deploying " + source.name + " as " + name);
            DeploymentScannerService.deploy(project, serverSnapshot, artifact, name, activityOutput, ok -> onUi(() -> {
                refreshExternalDeployments();
                if (ok && completion != null) completion.run();
            }));
        });
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
        if (index >= names.size()) { onUi(this::refreshExternalDeployments); return; }
        DeploymentScannerService.undeploy(project, server, names.get(index), activityOutput,
                ok -> onUi(() -> { refreshExternalDeployments(); if (ok) undeployNamesAt(names, server, index + 1); }));
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
        WildFlyApplicationSettings.getInstance().getState().environmentSetupCompleted = true;
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
        WildFlyApplicationSettings.getInstance().getState().environmentSetupCompleted = true;
        WildFlyApplicationSettings.getInstance().servers().removeIf(p -> p.id.equals(current.id));
        refreshServers();
        saveSelectedServer();
        syncAutoDeployWatcher();
    }

    private void startServer(boolean debug) {
        ServerProfile profile = requireServer();
        if (profile == null) return;
        background("WildFly start failed", () -> WildFlyProcessService.getInstance().start(profile, debug, activityOutput));
    }

    private void startDebug() {
        ServerProfile profile = requireServer();
        if (profile == null) return;
        background("WildFly debug failed", () -> {
            WildFlyProcessService process = WildFlyProcessService.getInstance();
            WildFlyProcessService.ServerState state = process.state(profile);
            if (state == WildFlyProcessService.ServerState.MANAGED) {
                process.terminateAndWait(profile, activityOutput);
                process.start(profile, true, activityOutput);
            } else if (state == WildFlyProcessService.ServerState.STOPPED
                    || state == WildFlyProcessService.ServerState.PORT_BUSY
                    || state == WildFlyProcessService.ServerState.OTHER_CONFIGURATION
                    || state == WildFlyProcessService.ServerState.STOPPING) {
                process.start(profile, true, activityOutput);
            } else if (state == WildFlyProcessService.ServerState.DETECTED) {
                append("WildFly is already running outside this managed process. Trying to attach to debug port " + profile.debugPort + " without restarting it.");
            }
            int port = process.isDebugRunning(profile) ? process.managedDebugPort(profile) : profile.debugPort;
            DebugAttachService.attachWhenAvailable(project, debuggerHost(profile), port, activityOutput);
        });
    }

    private void attachDebugger() {
        ServerProfile profile = requireServer();
        if (profile != null) DebugAttachService.attachWhenAvailable(project, debuggerHost(profile), profile.debugPort, activityOutput);
    }

    private void stopServer() {
        ServerProfile selected = requireServer();
        if (selected == null) return;
        ServerProfile profile = new ServerProfile(selected);
        WildFlyProcessService process = WildFlyProcessService.getInstance();
        if (process.isRunning(profile)) {
            background("WildFly stop failed", () -> process.terminate(profile, activityOutput));
            return;
        }
        background("WildFly detection failed", () -> {
            boolean detected = process.isDetectedRunning(profile);
            boolean canForce = detected && process.canForceStopDetected(profile);
            onUi(() -> {
                if (canForce) {
                    int answer = JOptionPane.showConfirmDialog(this,
                            "This WildFly was started outside the current IDE session. One local WildFly process matches this home, server base and configuration. Force stop it?",
                            "Stop Detected WildFly", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                    if (answer == JOptionPane.YES_OPTION) {
                        background("WildFly stop failed", () -> process.forceStopDetected(profile, activityOutput));
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
        return WildFlyServerDetector.connectionHost(profile.host);
    }

    private void editStandaloneConfig() {
        ServerProfile profile = requireServer();
        if (profile != null) openInEditor(WildFlyPaths.configurationFile(profile));
    }

    private void openLog() {
        ServerProfile profile = requireServer();
        if (profile == null) return;
        Path log = WildFlyPaths.logFile(profile);
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
        ServiceProfile snapshot = new ServiceProfile(service);
        background("Cannot open module folder", () -> openFolder(BuildService.resolveModuleDir(project, snapshot)));
    }

    private void openSelectedArtifactFolder() {
        ServiceProfile service = singleSelectedSourceProfile();
        if (service == null) { append("Select exactly one service with an associated source project."); return; }
        ServiceProfile snapshot = new ServiceProfile(service);
        background("Cannot find built artifact", () -> openFolder(ArtifactLocator.resolve(project, snapshot).getParent()));
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
        background("Cannot open file", () -> {
            VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
            if (file == null) throw new java.io.IOException("File not found: " + path);
            onUi(() -> FileEditorManager.getInstance(project).openFile(file, true));
        });
    }

    private void openFolder(Path path) {
        background("Cannot open folder", () -> {
            if (!Files.isDirectory(path)) throw new java.io.IOException("Folder not found: " + path);
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(path.toFile());
            else throw new java.io.IOException("Desktop folder opening is not supported: " + path);
        });
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
            try {
                WildFlyProcessService.ServerState state = process.state(snapshot);
                onUi(() -> {
                    ServerProfile current = selectedServer();
                    if (current != null && sameServerLocation(current, snapshot)) applyServerState(current, state);
                });
            } catch (ProcessCanceledException cancelled) { throw cancelled; }
            catch (Exception error) { append("Server status refresh failed: " + PluginNotifications.message(error)); }
            finally { serverStateRefreshRunning = false; }
        });
    }

    private void applyServerState(ServerProfile server, WildFlyProcessService.ServerState state) {
        switch (state) {
            case MANAGED_DEBUG -> {
                serverStateLabel.setText("Managed debug :" + WildFlyProcessService.getInstance().managedDebugPort(server));
                serverStateLabel.setForeground(JBUI.CurrentTheme.ProgressBar.PASSED);
            }
            case MANAGED -> {
                serverStateLabel.setText("Managed running");
                serverStateLabel.setForeground(JBUI.CurrentTheme.ProgressBar.PASSED);
            }
            case DETECTED -> {
                serverStateLabel.setText("Detected local WildFly");
                serverStateLabel.setForeground(JBUI.CurrentTheme.ProgressBar.WARNING);
            }
            case STOPPED -> {
                serverStateLabel.setText("Stopped");
                serverStateLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
            }
            case STOPPING -> {
                serverStateLabel.setText("Stopping…");
                serverStateLabel.setForeground(JBUI.CurrentTheme.ProgressBar.WARNING);
            }
            case PORT_BUSY -> {
                serverStateLabel.setText("Port " + server.httpPort + " occupied · server unverified");
                serverStateLabel.setForeground(JBUI.CurrentTheme.ProgressBar.WARNING);
            }
            case OTHER_CONFIGURATION -> {
                serverStateLabel.setText("Another configuration is using this server base");
                serverStateLabel.setForeground(JBUI.CurrentTheme.ProgressBar.WARNING);
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
        if (loading || disposed || project.isDisposed()) return;
        project.getService(ProjectSetupService.class).configureWatcher(activityOutput);
    }

    private ServerProfile selectedServer() { return (ServerProfile) serverCombo.getSelectedItem(); }

    private ServerProfile requireServer() {
        ServerProfile server = selectedServer();
        if (server == null) append("Add/select a WildFly server first.");
        return server == null ? null : new ServerProfile(server);
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
        if (disposed || project.isDisposed() || message == null || message.isBlank()) return;
        synchronized (pendingOutput) {
            pendingOutput.append("[").append(LocalTime.now().format(TIME)).append("] ")
                    .append(message.length() > MAX_ACTIVITY_CHARS ? message.substring(message.length() - MAX_ACTIVITY_CHARS) : message)
                    .append(System.lineSeparator());
            if (pendingOutput.length() > MAX_ACTIVITY_CHARS) pendingOutput.delete(0, pendingOutput.length() - MAX_ACTIVITY_CHARS);
            if (outputScheduled) return;
            outputScheduled = true;
        }
        // This callback only edits a Swing text buffer; no platform/model APIs are called here.
        SwingUtilities.invokeLater(() -> {
            String batch;
            synchronized (pendingOutput) {
                batch = pendingOutput.toString();
                pendingOutput.setLength(0);
                outputScheduled = false;
            }
            if (disposed || project.isDisposed()) return;
            output.append(batch);
            int excess = output.getDocument().getLength() - MAX_ACTIVITY_CHARS;
            if (excess > 0) output.replaceRange("", 0, excess);
            output.setCaretPosition(output.getDocument().getLength());
        });
    }

    private void onUi(Runnable action) {
        IdeUi.later(project, () -> disposed, () -> {
            try { action.run(); }
            catch (Exception error) { PluginNotifications.failure(project, "WildFly action failed", error, activityOutput); }
        });
    }

    @FunctionalInterface private interface BackgroundAction { void run() throws Exception; }

    private void background(String operation, BackgroundAction action) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            if (disposed || project.isDisposed()) return;
            try { action.run(); }
            catch (Exception error) { PluginNotifications.failure(project, operation, error, activityOutput); }
        });
    }

    @Override public void dispose() {
        disposed = true;
        refreshTimer.stop();
        synchronized (pendingOutput) { pendingOutput.setLength(0); }
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
        if (server == null || !Objects.equals(server.id, statusServerId)) return new DeploymentStatusView("NOT DEPLOYED", null);
        return deploymentStatuses.getOrDefault(deploymentName, new DeploymentStatusView("NOT DEPLOYED", null));
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
