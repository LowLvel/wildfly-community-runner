package io.github.wildflycommunityrunner.services;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.util.messages.Topic;
import io.github.wildflycommunityrunner.security.SensitiveProperties;
import io.github.wildflycommunityrunner.util.IdeUi;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Project-owned failure context; never retains a tool window, profile or credentials. */
@Service(Service.Level.PROJECT)
public final class OperationFeedback {
    public enum Action {
        ACTIVITY("View activity"), SERVER_LOG("View server log"),
        SERVICE_SETTINGS("Edit service"), SERVER_SETTINGS("Edit server");
        public final String label;
        Action(String label) { this.label = label; }
    }
    public enum Kind { BUILD, DEPLOYMENT, SERVER, GENERAL }
    public record Failure(String title, String detail, String hint, Kind kind, String serviceId, String serverId) {
        public List<Action> actions() {
            if (kind == Kind.BUILD && !serviceId.isBlank()) return List.of(Action.ACTIVITY, Action.SERVICE_SETTINGS);
            if ((kind == Kind.DEPLOYMENT || kind == Kind.SERVER) && !serverId.isBlank())
                return List.of(Action.ACTIVITY, Action.SERVER_LOG, Action.SERVER_SETTINGS);
            return List.of(Action.ACTIVITY);
        }
    }
    public interface Listener { void changed(); }
    public interface Navigation { void open(Failure failure, Action action); }
    public static final Topic<Listener> CHANGED = Topic.create("WildFly operation feedback", Listener.class);
    public static final Topic<Navigation> NAVIGATE = Topic.create("WildFly error navigation", Navigation.class);
    private final Project project;
    private volatile Failure latest;

    public OperationFeedback(Project project) { this.project = project; }
    public Failure latest() { return latest; }
    public synchronized Failure report(String title, String detail, String serviceId, String serverId) {
        latest = describe(title, detail, serviceId, serverId);
        changed();
        return latest;
    }
    public synchronized void dismiss(Failure expected) {
        if (latest != expected) return;
        latest = null;
        changed();
    }
    private void changed() {
        IdeUi.later(project, () -> project.getMessageBus().syncPublisher(CHANGED).changed());
    }
    public void navigate(Failure failure, Action action) {
        IdeUi.later(project, () -> {
            var window = ToolWindowManager.getInstance(project).getToolWindow("WildFly");
            if (window != null) window.show(() -> IdeUi.later(project,
                    () -> project.getMessageBus().syncPublisher(NAVIGATE).open(failure, action)));
        });
    }
    static Failure describe(String title, String detail, String serviceId, String serverId) {
        String operation = Objects.toString(title, "WildFly operation failed").toLowerCase(Locale.ROOT);
        Kind kind = operation.contains("deploy") ? Kind.DEPLOYMENT : operation.contains("build") ? Kind.BUILD
                : operation.contains("start") || operation.contains("stop") || operation.contains("debug") ? Kind.SERVER : Kind.GENERAL;
        String hint = switch (kind) {
            case BUILD -> "Check the build output, goals and build JDK.";
            case DEPLOYMENT -> "Check server.log and the deployment scanner before retrying.";
            case SERVER -> "Check the server JDK, ports and server.log.";
            case GENERAL -> "Open activity for the failure details.";
        };
        return new Failure(clean(title), clean(detail), hint, kind,
                Objects.toString(serviceId, ""), Objects.toString(serverId, ""));
    }
    private static String clean(String value) {
        String text = SensitiveProperties.redactProperties(Objects.toString(value, "Unknown error"));
        return text.length() > 2000 ? text.substring(0, 2000) + "…" : text;
    }
}
