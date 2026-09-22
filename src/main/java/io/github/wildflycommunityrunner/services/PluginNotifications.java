package io.github.wildflycommunityrunner.services;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindowManager;
import io.github.wildflycommunityrunner.util.IdeUi;
import java.util.function.Consumer;

/** One notification per failed operation; subprocess output and refreshes do not produce balloons. */
public final class PluginNotifications {
    public static final String GROUP = "WildFly Community Runner";
    private PluginNotifications() {}

    public static void failure(Project project, String operation, Throwable error, Consumer<String> output) {
        if (error instanceof ProcessCanceledException cancelled) throw cancelled;
        if (error instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            return;
        }
        failure(project, operation, message(error), output);
    }

    public static void failure(Project project, String operation, String details, Consumer<String> output) {
        if (project.isDisposed()) return;
        if (output != null) output.accept(operation + ": " + details);
        Notification notification = createFailure(operation, details);
        notification.addAction(NotificationAction.createSimple("Open WildFly", () -> IdeUi.later(project, () -> {
            var window = ToolWindowManager.getInstance(project).getToolWindow("WildFly");
            if (window != null) window.show(null);
        })));
        notification.notify(project);
    }

    static Notification createFailure(String operation, String details) {
        return NotificationGroupManager.getInstance().getNotificationGroup(GROUP)
                .createNotification(escape(operation), escape(details), NotificationType.ERROR);
    }

    public static String message(Throwable error) {
        String message = io.github.wildflycommunityrunner.security.SensitiveProperties.redactProperties(error.getMessage());
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private static String escape(String value) {
        String text = value == null ? "Unknown error" : io.github.wildflycommunityrunner.security.SensitiveProperties.redactProperties(value);
        if (text.length() > 2000) text = text.substring(0, 2000) + "…";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;").replace("\n", "<br>");
    }
}
