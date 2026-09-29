package io.github.wildflycommunityrunner.ui;

import com.intellij.icons.AllIcons;
import com.intellij.util.ui.JBUI;
import io.github.wildflycommunityrunner.services.OperationFeedback;
import javax.swing.*;
import java.awt.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** One bounded, dismissible failure, without changing the user's active tab or selection. */
@SuppressWarnings("serial")
final class FailureBanner extends JPanel {
    private final JLabel title = new JLabel();
    private final JTextArea detail = new JTextArea();
    private final JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0));
    private final JButton close = new JButton(AllIcons.Actions.Close);
    private OperationFeedback.Failure displayed;
    private final BiConsumer<OperationFeedback.Failure, OperationFeedback.Action> open;
    private final Consumer<OperationFeedback.Failure> dismiss;

    FailureBanner(BiConsumer<OperationFeedback.Failure, OperationFeedback.Action> open,
                  Consumer<OperationFeedback.Failure> dismiss) {
        super(new BorderLayout(0, JBUI.scale(4)));
        this.open = open;
        this.dismiss = dismiss;
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBUI.CurrentTheme.ProgressBar.WARNING), JBUI.Borders.empty(6)));
        title.putClientProperty("html.disable", Boolean.TRUE);
        title.setIcon(AllIcons.General.Error);
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        detail.setEditable(false);
        detail.setOpaque(false);
        detail.setFont(UIManager.getFont("Label.font"));
        detail.setLineWrap(true);
        detail.setWrapStyleWord(true);
        detail.setRows(3);
        JPanel header = new JPanel(new BorderLayout(JBUI.scale(4), 0));
        close.setToolTipText("Dismiss failure");
        close.getAccessibleContext().setAccessibleName("Dismiss failure");
        close.setMargin(JBUI.insets(1));
        close.addActionListener(event -> { if (displayed != null) dismiss.accept(displayed); });
        header.add(title, BorderLayout.CENTER);
        header.add(close, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);
        add(detail, BorderLayout.CENTER);
        add(actions, BorderLayout.SOUTH);
        setVisible(false);
    }
    void showFailure(OperationFeedback.Failure failure) {
        actions.removeAll();
        displayed = failure;
        setVisible(failure != null);
        if (failure == null) { revalidate(); return; }
        title.setText(failure.title());
        String message = failure.detail().replace('\n', ' ').replace('\r', ' ');
        detail.setText((message.length() > 200 ? message.substring(0, 200) + "…" : message) + "\n" + failure.hint());
        detail.setCaretPosition(0);
        var choices = failure.actions();
        for (var action : choices.size() > 2 ? choices.subList(0, 1) : choices) {
            JButton button = new JButton(action.label);
            button.setMargin(JBUI.insets(2, 6));
            button.addActionListener(event -> open.accept(failure, action));
            actions.add(button);
        }
        if (choices.size() > 2) {
            JButton more = new JButton("Server actions…");
            more.setMargin(JBUI.insets(2, 6));
            JPopupMenu menu = new JPopupMenu();
            for (var action : choices.subList(1, choices.size())) {
                JMenuItem item = new JMenuItem(action.label);
                item.addActionListener(event -> open.accept(failure, action));
                menu.add(item);
            }
            more.addActionListener(event -> menu.show(more, 0, more.getHeight()));
            actions.add(more);
        }
        revalidate();
        repaint();
    }
}
