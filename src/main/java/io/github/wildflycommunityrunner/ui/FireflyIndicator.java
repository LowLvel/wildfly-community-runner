package io.github.wildflycommunityrunner.ui;

import com.intellij.openapi.Disposable;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import javax.swing.*;
import java.awt.*;
import java.awt.event.HierarchyEvent;
import java.awt.geom.Ellipse2D;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** A small vector mascot. Its timer only runs during visible, enabled motion. */
@SuppressWarnings("serial")
public final class FireflyIndicator extends JComponent implements Disposable {
    public enum Mode { RESTING, STARTING, WORKING, READY, ATTENTION }
    private final Timer animation = new Timer(80, event -> tick());
    private final BooleanSupplier showing;
    private final LongSupplier clock;
    private Mode mode = Mode.RESTING;
    private boolean reducedMotion;
    private boolean disposed;
    private boolean removed;
    private long celebrationUntil;
    private int frame;

    public FireflyIndicator() {
        this(null, System::nanoTime);
    }
    FireflyIndicator(BooleanSupplier showing, LongSupplier clock) {
        this.showing = showing == null ? this::isShowing : showing;
        this.clock = clock;
        setPreferredSize(JBUI.size(28, 24));
        setMinimumSize(JBUI.size(28, 24));
        setOpaque(false);
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) updateTimer();
        });
        setMode(Mode.RESTING);
    }
    public Mode mode() { return mode; }
    public void setMode(Mode value) {
        if (disposed) return;
        if (mode != value) { mode = value; frame = 0; celebrationUntil = 0; }
        String state = switch (mode) {
            case RESTING -> "resting";
            case STARTING -> "starting server";
            case WORKING -> "building or deploying";
            case READY -> "server running";
            case ATTENTION -> "needs attention";
        };
        setToolTipText("Firefly: " + state);
        getAccessibleContext().setAccessibleName("Firefly: " + state);
        updateTimer();
        repaint();
    }
    @Override public javax.accessibility.AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) accessibleContext = new AccessibleJComponent() {};
        return accessibleContext;
    }
    public void setReducedMotion(boolean value) {
        reducedMotion = value;
        frame = 0;
        if (value) celebrationUntil = 0;
        updateTimer();
        repaint();
    }
    public void celebrate() {
        if (disposed || removed || reducedMotion || !showing.getAsBoolean()
                || mode == Mode.ATTENTION || mode == Mode.WORKING || mode == Mode.STARTING) return;
        celebrationUntil = clock.getAsLong() + 2_400_000_000L;
        updateTimer();
    }
    private void updateTimer() {
        boolean moving = mode == Mode.STARTING || mode == Mode.WORKING || clock.getAsLong() < celebrationUntil;
        if (!disposed && !removed && !reducedMotion && showing.getAsBoolean() && moving) animation.start();
        else animation.stop();
    }
    void tick() {
        updateTimer();
        frame++;
        repaint();
    }
    boolean isAnimating() { return animation.isRunning(); }
    @Override public void addNotify() {
        super.addNotify();
        removed = false;
        updateTimer();
    }
    @Override public void removeNotify() {
        removed = true;
        animation.stop();
        celebrationUntil = 0;
        super.removeNotify();
    }
    @Override public void dispose() { disposed = true; animation.stop(); }
    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            double scale = JBUI.scale(1f);
            g.translate(getWidth() / 2.0, getHeight() / 2.0);
            g.scale(scale, scale);
            double phase = reducedMotion ? 0 : frame * 0.32;
            boolean flying = mode == Mode.WORKING || mode == Mode.STARTING;
            if (flying) g.translate(0, Math.sin(phase) * 1.5);
            Color light = mode == Mode.ATTENTION ? JBUI.CurrentTheme.ProgressBar.WARNING
                    : new JBColor(new Color(0x9B7800), new Color(0xF5D76E));
            boolean celebrating = !reducedMotion && clock.getAsLong() < celebrationUntil;
            if (mode != Mode.RESTING || celebrating) {
                float alpha = (float) (0.10 + (flying || celebrating ? 0.06 * (1 + Math.sin(phase)) : 0));
                g.setComposite(AlphaComposite.SrcOver.derive(alpha));
                g.setColor(light);
                g.fill(new Ellipse2D.Double(-9, -5, 18, 18));
                g.setComposite(AlphaComposite.SrcOver);
            }
            g.setColor(new JBColor(new Color(0xB7CDD4), new Color(0x7999A5)));
            double wing = flying && !reducedMotion ? Math.sin(phase * 2) * 1.3 : 0;
            g.fill(new Ellipse2D.Double(-9, -6 - wing, 8, 5));
            g.fill(new Ellipse2D.Double(1, -6 + wing, 8, 5));
            g.setColor(new JBColor(new Color(0x39464B), new Color(0xB9C8CB)));
            g.setStroke(new BasicStroke(1.2f));
            g.drawLine(-2, -7, -4, -10); g.drawLine(2, -7, 4, -10);
            g.fillOval(-3, -8, 6, 6);
            g.fillOval(-4, -3, 8, 11);
            g.setColor(mode == Mode.RESTING && !celebrating ? new JBColor(new Color(0x999B8B), new Color(0x777A6B)) : light);
            g.fillOval(-3, 1, 6, 6);
        } finally { g.dispose(); }
    }
}
