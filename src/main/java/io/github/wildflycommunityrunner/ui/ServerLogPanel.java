package io.github.wildflycommunityrunner.ui;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import io.github.wildflycommunityrunner.model.ServerProfile;
import io.github.wildflycommunityrunner.security.SensitiveProperties;
import io.github.wildflycommunityrunner.services.ServerLogTailer;
import io.github.wildflycommunityrunner.services.WildFlyProcessService;
import io.github.wildflycommunityrunner.util.IdeUi;
import io.github.wildflycommunityrunner.util.WildFlyPaths;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Swing state stays on EDT; a single worker owns every tail-reader state transition. */
@SuppressWarnings("serial")
public final class ServerLogPanel extends JPanel implements Disposable {
    private record Target(ServerProfile profile, Path path, long generation) {}
    private final Project project;
    private final WildFlyProcessService processes;
    private final JTextArea output = new JTextArea();
    private final JScrollPane scroll = new JScrollPane(output);
    private final JTextField filter = new JTextField(18);
    private final JTextField location = new JTextField();
    private final JLabel status = new JLabel("Select a WildFly server profile");
    private final JToggleButton pause = new JToggleButton("Pause");
    private final JCheckBox follow = new JCheckBox("Follow", true);
    private final JButton open = new JButton("Open in Editor");
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "WildFly server.log reader"); thread.setDaemon(true); return thread;
    });
    private final AtomicLong viewRevision = new AtomicLong();
    private final AtomicLong clearRevision = new AtomicLong();
    private final AtomicLong reloadRevision = new AtomicLong();
    private final AtomicBoolean forceRead = new AtomicBoolean();
    private volatile Target target;
    private volatile boolean active, paused, disposed;
    private long targetGeneration;
    private String latestText = "", latestStatus = "Select a WildFly server profile";
    // Accessed only by the worker.
    private ServerLogTailer tailer = new ServerLogTailer();
    private long loadedTarget = -1, loadedClear = -1, loadedReload = -1;

    public ServerLogPanel(Project project, Consumer<Path> openEditor) { this(project, openEditor, true); }
    ServerLogPanel(Project project, Consumer<Path> openEditor, boolean schedule) {
        super(new BorderLayout(4, 4));
        this.project = project; processes = WildFlyProcessService.getInstance();
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        output.setEditable(false);
        ((javax.swing.text.DefaultCaret) output.getCaret()).setUpdatePolicy(javax.swing.text.DefaultCaret.NEVER_UPDATE);
        output.setFont(new Font(Font.MONOSPACED, Font.PLAIN, output.getFont().getSize()));
        output.getAccessibleContext().setAccessibleName("WildFly server.log tail");
        location.setEditable(false);
        location.getAccessibleContext().setAccessibleName("Selected server log path");
        JPanel header = new JPanel(new BorderLayout(4, 4));
        header.add(location, BorderLayout.NORTH);
        JPanel controls = new JPanel(new GridLayout(0, 1, 0, 4));
        JPanel viewControls = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JPanel fileControls = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton clear = new JButton("Clear View"), reload = new JButton("Reload Tail");
        clear.setToolTipText("Clear displayed lines without changing server.log");
        reload.setToolTipText("Read the latest bounded tail again, including while paused");
        pause.setToolTipText("Freeze this view; resume to catch up with the latest log tail");
        follow.setToolTipText("Scroll to new log lines automatically");
        filter.setToolTipText("Show lines containing this text (case insensitive)");
        filter.getAccessibleContext().setAccessibleName("Filter server log text");
        viewControls.add(pause); viewControls.add(follow); viewControls.add(clear);
        fileControls.add(reload); fileControls.add(open);
        controls.add(viewControls); controls.add(fileControls);
        header.add(controls, BorderLayout.CENTER);
        JPanel search = new JPanel(new BorderLayout(4, 0));
        search.add(new JLabel("Filter text:"), BorderLayout.WEST); search.add(filter, BorderLayout.CENTER);
        header.add(search, BorderLayout.SOUTH);
        add(header, BorderLayout.NORTH); add(scroll, BorderLayout.CENTER); add(status, BorderLayout.SOUTH);
        pause.addActionListener(event -> setPaused(pause.isSelected()));
        follow.addActionListener(event -> render());
        clear.addActionListener(event -> clearView());
        reload.addActionListener(event -> reloadTail());
        open.addActionListener(event -> {
            Target selected = target;
            if (selected != null) IdeUi.later(project, () -> disposed, () -> openEditor.accept(selected.path()));
        });
        filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { render(); }
            @Override public void removeUpdate(DocumentEvent event) { render(); }
            @Override public void changedUpdate(DocumentEvent event) { render(); }
        });
        open.setEnabled(false);
        if (schedule) worker.scheduleWithFixedDelay(this::pollNow, 0, 750, TimeUnit.MILLISECONDS);
    }

    public void setProfile(ServerProfile profile) {
        if (disposed) return;
        try {
            Path path = profile == null ? null : WildFlyPaths.logFile(profile).toAbsolutePath().normalize();
            Target old = target;
            if (old != null && profile != null && old.profile().id.equals(profile.id) && old.path().equals(path)) {
                target = new Target(new ServerProfile(profile), path, old.generation());
                location.setText(profile.name + " — " + path); return;
            }
            viewRevision.incrementAndGet();
            target = profile == null ? null : new Target(new ServerProfile(profile), path, ++targetGeneration);
            latestText = ""; latestStatus = profile == null ? "Select a WildFly server profile" : "Waiting to read server.log";
            location.setText(profile == null ? "No server selected" : profile.name + " — " + path);
            location.setToolTipText(path == null ? null : path.toString());
            open.setEnabled(profile != null); render();
        } catch (RuntimeException failure) {
            viewRevision.incrementAndGet(); target = null; latestText = "";
            latestStatus = "Cannot resolve server.log: " + SensitiveProperties.redactProperties(failure.getMessage());
            location.setText("Invalid server profile"); open.setEnabled(false); render();
        }
    }

    public void setActive(boolean value) {
        if (disposed || active == value) return;
        active = value; viewRevision.incrementAndGet();
    }
    void setPaused(boolean value) {
        paused = value; pause.setSelected(value); viewRevision.incrementAndGet(); render();
    }
    void clearView() {
        clearRevision.incrementAndGet(); viewRevision.incrementAndGet(); latestText = ""; render();
    }
    void reloadTail() {
        reloadRevision.incrementAndGet(); viewRevision.incrementAndGet(); forceRead.set(true);
    }

    void pollNow() {
        Target selected = target;
        if (disposed || project.isDisposed() || !active || selected == null) return;
        boolean forced = forceRead.getAndSet(false);
        if (paused && !forced) return;
        long revision = viewRevision.get(), clear = clearRevision.get(), reload = reloadRevision.get();
        ServerLogTailer.Snapshot snapshot;
        try {
            if (loadedTarget != selected.generation()) {
                tailer = new ServerLogTailer(); loadedTarget = selected.generation(); loadedClear = clear; loadedReload = reload;
            }
            if (loadedReload != reload) { tailer.reload(); loadedReload = reload; }
            if (loadedClear != clear) { tailer.clearView(); loadedClear = clear; }
            snapshot = tailer.poll(selected.path(), text -> processes.redact(selected.profile(), text));
        } catch (com.intellij.openapi.progress.ProcessCanceledException cancelled) { throw cancelled; }
        catch (Exception failure) {
            if (disposed || Thread.currentThread().isInterrupted()) return;
            String detail = SensitiveProperties.redactProperties(failure.getMessage());
            if (detail.length() > 500) detail = detail.substring(0, 500);
            snapshot = new ServerLogTailer.Snapshot(null, "Cannot read server.log; retrying: " + detail);
        }
        ServerLogTailer.Snapshot result = snapshot;
        // Only Swing buffers are updated here. Platform editor actions use IdeUi above.
        SwingUtilities.invokeLater(() -> {
            if (disposed || project.isDisposed() || !active || target != selected || viewRevision.get() != revision || (paused && !forced)) return;
            if (result.text() != null) latestText = result.text();
            latestStatus = result.status(); render();
        });
    }

    private void render() {
        String visible = filtered(latestText, filter.getText());
        String old = output.getText();
        if (!Objects.equals(old, visible)) {
            Point position = scroll.getViewport().getViewPosition();
            int caret = output.getCaretPosition();
            if (visible.startsWith(old)) output.append(visible.substring(old.length()));
            else output.setText(visible);
            if (!follow.isSelected() || paused) {
                output.setCaretPosition(Math.min(caret, output.getDocument().getLength()));
                scroll.getViewport().setViewPosition(position);
            }
        }
        if (follow.isSelected() && !paused) output.setCaretPosition(output.getDocument().getLength());
        status.setText(paused ? "Paused · " + latestStatus.replace("Following ", "") : latestStatus);
    }

    static String filtered(String text, String query) {
        if (query == null || query.isEmpty()) return text;
        String needle = query.toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder();
        text.lines().filter(line -> line.toLowerCase(Locale.ROOT).contains(needle)).forEach(line -> result.append(line).append('\n'));
        return result.toString();
    }
    String displayedText() { return output.getText(); }
    String displayedStatus() { return status.getText(); }

    @Override public void dispose() {
        disposed = true; viewRevision.incrementAndGet(); worker.shutdownNow();
    }
}
