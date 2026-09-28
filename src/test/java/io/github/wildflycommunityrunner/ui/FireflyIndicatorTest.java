package io.github.wildflycommunityrunner.ui;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class FireflyIndicatorTest extends BasePlatformTestCase {
    public void testMotionStopsWhenHiddenReducedOrDisposedAndRestartsWhenVisible() {
        var visible = new AtomicBoolean(false);
        var indicator = new FireflyIndicator(visible::get, System::nanoTime);
        try {
            indicator.setMode(FireflyIndicator.Mode.WORKING);
            assertFalse(indicator.isAnimating());
            visible.set(true);
            indicator.setMode(FireflyIndicator.Mode.WORKING);
            assertTrue(indicator.isAnimating());
            indicator.setReducedMotion(true);
            assertFalse(indicator.isAnimating());
            assertTrue(indicator.getAccessibleContext().getAccessibleName().contains("building"));
            indicator.setReducedMotion(false);
            assertTrue(indicator.isAnimating());
            visible.set(false);
            indicator.tick();
            assertFalse(indicator.isAnimating());
            indicator.dispose();
            visible.set(true);
            indicator.setMode(FireflyIndicator.Mode.STARTING);
            assertFalse(indicator.isAnimating());
        } finally { indicator.dispose(); }
    }
    public void testSuccessCelebrationExpiresAndIdleDoesNotAnimate() {
        var time = new AtomicLong(1_000_000_000L);
        var indicator = new FireflyIndicator(() -> true, time::get);
        try {
            indicator.setMode(FireflyIndicator.Mode.READY);
            assertFalse(indicator.isAnimating());
            indicator.celebrate();
            assertTrue(indicator.isAnimating());
            time.addAndGet(3_000_000_000L);
            indicator.tick();
            assertFalse(indicator.isAnimating());
            indicator.setReducedMotion(true);
            indicator.celebrate();
            assertFalse(indicator.isAnimating());
            indicator.setMode(FireflyIndicator.Mode.ATTENTION);
            assertFalse(indicator.isAnimating());
        } finally { indicator.dispose(); }
    }
    public void testWindowRemovalStopsAnimationImmediately() {
        var indicator = new FireflyIndicator(() -> true, System::nanoTime);
        try {
            indicator.setMode(FireflyIndicator.Mode.WORKING);
            assertTrue(indicator.isAnimating());
            indicator.removeNotify();
            assertFalse(indicator.isAnimating());
        } finally { indicator.dispose(); }
    }
}
