package io.github.wildflycommunityrunner.services;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
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

    public static void failure(Project project, String operation, Throwable error, Consumer<String> output,
                               String serviceId, String serverId) {
        if (error instanceof ProcessCanceledException cancelled) throw cancelled;
        if (error instanceof InterruptedException) { Thread.currentThread().interrupt(); return; }
        failure(project, operation, message(error), output, serviceId, serverId);
    }

    public static void failure(Project project, String operation, String details, Consumer<String> output) {
        failure(project, operation, details, output, "", "");
    }

    public static void failure(Project project, String operation, String details, Consumer<String> output,
                               String serviceId, String serverId) {
        if (project.isDisposed()) return;
        var feedback = project.getService(OperationFeedback.class);
        var failure = feedback.report(operation, details, serviceId, serverId);
        if (output != null) output.accept(failure.title() + ": " + failure.detail());
        Notification notification = createFailure(failure.title(), failure.detail() + "\n" + failure.hint());
        for (var action : failure.actions()) notification.addAction(NotificationAction.createSimple(action.label,
                () -> feedback.navigate(failure, action)));
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
