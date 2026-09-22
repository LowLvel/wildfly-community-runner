package io.github.wildflycommunityrunner.services;

import com.intellij.execution.ExecutionListener;
import com.intellij.execution.ExecutionManager;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/** Observes only the environment created for this build, before the process can exit. */
final class BuildExecutionListener implements ExecutionListener {
    private final ExecutionEnvironment environment;
    private final BuildOperation operation;

    BuildExecutionListener(Project project, ExecutionEnvironment environment, BuildOperation operation) {
        this.environment = environment;
        this.operation = operation;
        var connection = project.getMessageBus().connect();
        connection.subscribe(ExecutionManager.EXECUTION_TOPIC, this);
        operation.onFinished(connection::disconnect);
        operation.onCancelLaunch(() -> environment.putUserData(ExecutionManager.EXECUTION_SKIP_RUN, true));
    }

    @Override public void processStarting(@NotNull String executorId, @NotNull ExecutionEnvironment env, @NotNull ProcessHandler handler) {
        if (env == environment) operation.bind(handler);
    }
    @Override public void processStarted(@NotNull String executorId, @NotNull ExecutionEnvironment env, @NotNull ProcessHandler handler) {
        if (env == environment) operation.bind(handler);
    }
    @Override public void processNotStarted(@NotNull String executorId, @NotNull ExecutionEnvironment env) {
        processNotStarted(executorId, env, null);
    }
    @Override public void processNotStarted(@NotNull String executorId, @NotNull ExecutionEnvironment env, Throwable cause) {
        if (env == environment) operation.failed(cause == null ? new IllegalStateException("Maven launch did not start") : cause);
    }
}
