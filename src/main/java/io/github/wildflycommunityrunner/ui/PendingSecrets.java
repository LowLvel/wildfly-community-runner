package io.github.wildflycommunityrunner.ui;

import com.intellij.openapi.Disposable;
import io.github.wildflycommunityrunner.security.JvmSecrets;

/** Rolls back credentials if a dialog closes or its fields change during asynchronous saving. */
final class PendingSecrets implements Disposable {
    private JvmSecrets.Protection pending;
    private boolean disposed;
    synchronized boolean offer(JvmSecrets.Protection value) {
        if (disposed) { value.rollbackAsync(); return false; }
        pending = value;
        return true;
    }
    synchronized void finish(boolean commit) {
        var value = pending; pending = null;
        if (value == null) return;
        if (commit && !disposed) value.commit();
        else value.rollbackAsync();
    }
    @Override public synchronized void dispose() { disposed = true; finish(false); }
}
