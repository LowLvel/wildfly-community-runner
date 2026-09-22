package io.github.wildflycommunityrunner.services;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.remote.RemoteConfiguration;
import com.intellij.execution.remote.RemoteConfigurationType;
import com.intellij.execution.runners.ExecutionEnvironmentBuilder;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import io.github.wildflycommunityrunner.util.IdeUi;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;

public final class DebugAttachService {
    private DebugAttachService() {}

    public static void attachWhenAvailable(Project project, String host, int port, Consumer<String> output) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            output.accept("Waiting for debugger at " + host + ":" + port + "...");
            Instant deadline = Instant.now().plus(Duration.ofSeconds(45));
            while (!project.isDisposed() && Instant.now().isBefore(deadline)) {
                if (isOpen(host, port)) {
                    IdeUi.later(project, () -> attach(project, host, port, output));
                    return;
                }
                try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
            if (!project.isDisposed()) PluginNotifications.failure(project, "Debugger attach failed",
                    "Port " + port + " did not become available within 45 seconds. Start WildFly with debugging enabled and check the profile's debug port.", output);
        });
    }

    public static void attach(Project project, String host, int port, Consumer<String> output) {
        if (project.isDisposed()) return;
        try {
            RemoteConfigurationType type = RemoteConfigurationType.getInstance();
            RemoteConfiguration config = new RemoteConfiguration(project, type);
            config.setName("WildFly " + host + ":" + port);
            config.USE_SOCKET_TRANSPORT = true;
            config.SERVER_MODE = false;
            config.HOST = host;
            config.PORT = Integer.toString(port);
            config.AUTO_RESTART = false;

            ExecutionEnvironmentBuilder
                    .create(DefaultDebugExecutor.getDebugExecutorInstance(), config)
                    .buildAndExecute();
            output.accept("Debugger attach requested: " + host + ":" + port);
        } catch (ExecutionException e) {
            PluginNotifications.failure(project, "Debugger attach failed", e, output);
        }
    }

    private static boolean isOpen(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 300);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }
}
