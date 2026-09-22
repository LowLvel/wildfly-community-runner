package io.github.wildflycommunityrunner.run;

import com.intellij.execution.process.ProcessListener;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import org.jetbrains.annotations.NotNull;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Detach project sessions on project close/plugin unload without stopping application-wide servers. */
@Service(Service.Level.PROJECT)
public final class WildFlySessionService implements Disposable {
    private final Set<ProcessHandler> sessions = ConcurrentHashMap.newKeySet();
    private volatile boolean disposed;

    void register(ProcessHandler handler) {
        sessions.add(handler);
        handler.addProcessListener(new ProcessListener() {
            @Override public void processTerminated(@NotNull ProcessEvent event) {
                sessions.remove(handler);
                handler.removeProcessListener(this);
            }
        });
        if (disposed) handler.detachProcess();
        if (handler.isProcessTerminated()) sessions.remove(handler);
    }

    @Override public void dispose() {
        disposed = true;
        for (ProcessHandler handler : sessions) handler.detachProcess();
        sessions.clear();
    }
}
