package io.github.wildflycommunityrunner.security;

import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessListener;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.util.execution.ParametersListUtil;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Per-execution files; configuration identity cannot distinguish concurrent reruns. */
@Service(Service.Level.PROJECT)
public final class MavenSecretSessions implements Disposable {
    private final Supplier<JvmSecrets> owner;
    private final Map<String, Lease> pending = new HashMap<>();
    private final Map<ProcessHandler, Lease> running = new HashMap<>();
    private boolean disposed;

    public MavenSecretSessions() { this(JvmSecrets::getInstance); }
    MavenSecretSessions(Supplier<JvmSecrets> owner) { this.owner = owner; }

    private static final class Lease {
        final JvmSecrets owner;
        final PrivateJvmOptions options;
        ProcessListener listener;
        Lease(JvmSecrets owner, PrivateJvmOptions options) { this.owner = owner; this.options = options; }
        void release() { owner.release(options); }
    }

    String prepare(String options) throws IOException {
        JvmSecrets secrets = owner.get();
        PrivateJvmOptions prepared = secrets.prepare(options);
        synchronized (this) {
            if (disposed) { secrets.release(prepared); throw new IllegalStateException("The Maven project has closed."); }
            if (prepared.containsSecrets()) pending.put("@" + prepared.file(), new Lease(secrets, prepared));
            else secrets.release(prepared);
        }
        return prepared.options();
    }

    synchronized void attach(ProcessHandler handler, String commandLine) {
        if (disposed || commandLine == null || running.containsKey(handler)) return;
        for (String argument : ParametersListUtil.parse(commandLine)) {
            Lease lease = pending.remove(argument);
            if (lease == null) continue;
            lease.listener = new ProcessListener() {
                @Override public void processTerminated(ProcessEvent event) { finish(handler); }
                @Override public void processWillTerminate(ProcessEvent event, boolean willBeDestroyed) {
                    // Detached processes no longer belong to this project. The Java
                    // launcher has already read the arguments before a user can detach.
                    if (!willBeDestroyed) finish(handler);
                }
            };
            running.put(handler, lease);
            handler.addProcessListener(lease.listener);
            if (handler.isProcessTerminated()) finish(handler);
            return;
        }
    }

    private synchronized void finish(ProcessHandler handler) {
        Lease lease = running.remove(handler);
        if (lease == null) return;
        handler.removeProcessListener(lease.listener);
        lease.release();
    }

    @Override public synchronized void dispose() {
        disposed = true;
        running.forEach((handler, lease) -> { handler.removeProcessListener(lease.listener); lease.release(); });
        running.clear();
        // Includes launches cancelled or failed before the platform supplied a handler.
        pending.values().forEach(Lease::release);
        pending.clear();
    }
}
