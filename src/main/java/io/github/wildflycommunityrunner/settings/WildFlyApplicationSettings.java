package io.github.wildflycommunityrunner.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import io.github.wildflycommunityrunner.model.ServiceProfile;
import io.github.wildflycommunityrunner.model.ServerProfile;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import com.intellij.util.messages.Topic;

@Service(Service.Level.APP)
@State(name = "WildFlyCommunityRunner", storages = @Storage("wildflyCommunityRunner.xml"))
public final class WildFlyApplicationSettings implements PersistentStateComponent<WildFlyApplicationSettings.StateData> {
    public interface Listener {
        default void serversChanged() {}
        default void sourcesChanged() {}
    }
    public static final Topic<Listener> CHANGED = Topic.create("WildFly global settings", Listener.class);

    public static final class StateData {
        // Zero must remain the deserialization default for archives predating versioned settings.
        public int schemaVersion;
        public List<ServerProfile> servers = new ArrayList<>();
        public List<ServiceProfile> knownServices = new ArrayList<>();
        public String lastServerId = "";
        public boolean environmentSetupCompleted;
    }

    private StateData state = migrate(new StateData());

    public static WildFlyApplicationSettings getInstance() {
        return ApplicationManager.getApplication().getService(WildFlyApplicationSettings.class);
    }

    @Override public synchronized @NotNull StateData getState() { return copy(state); }

    @Override public void loadState(@NotNull StateData value) {
        synchronized (this) { state = migrate(copy(value)); }
        publish(true, true);
    }

    /** The callback edits an isolated copy; no caller or serializer retains mutable live state. */
    public void update(Consumer<StateData> edit) {
        synchronized (this) {
            StateData draft = copy(state);
            edit.accept(draft);
            state = copy(draft);
        }
        publish(true, false);
    }

    public synchronized List<ServerProfile> servers() { return copy(state).servers; }
    public synchronized List<ServiceProfile> knownServices() { return copy(state).knownServices; }
    public synchronized String lastServerId() { return SettingsMigration.text(state.lastServerId); }
    public synchronized void setLastServerId(String id) { state.lastServerId = SettingsMigration.text(id); }

    public void rememberService(ServiceProfile service) {
        if (service == null || service.buildFilePath == null || service.buildFilePath.isBlank()) return;
        synchronized (this) {
            ServiceProfile copy = new ServiceProfile(service);
            copy.migrateLegacyFields();
            copy.tracked = false;
            int existing = -1;
            for (int i = 0; i < state.knownServices.size(); i++) {
                if (sameBuildFile(state.knownServices.get(i).buildFilePath, copy.buildFilePath)) { existing = i; break; }
            }
            if (existing >= 0) {
                copy.id = state.knownServices.get(existing).id;
                state.knownServices.set(existing, copy);
            } else {
                if (state.knownServices.stream().anyMatch(item -> Objects.equals(item.id, copy.id)))
                    copy.id = java.util.UUID.randomUUID().toString();
                state.knownServices.add(copy);
            }
        }
        publish(false, true);
    }

    public void forgetServices(Set<String> ids) {
        boolean changed;
        synchronized (this) { changed = state.knownServices.removeIf(service -> ids.contains(service.id)); }
        if (changed) publish(false, true);
    }

    /** Relinking replaces the old association atomically, including a duplicate destination path. */
    public boolean replaceKnownService(String id, ServiceProfile replacement) {
        synchronized (this) {
            if (state.knownServices.stream().noneMatch(service -> service.id.equals(id))) return false;
            ServiceProfile copy = new ServiceProfile(replacement);
            copy.migrateLegacyFields();
            copy.id = id;
            copy.tracked = false;
            state.knownServices.removeIf(service -> service.id.equals(id) || sameBuildFile(service.buildFilePath, copy.buildFilePath));
            state.knownServices.add(copy);
        }
        publish(false, true);
        return true;
    }

    private static StateData copy(StateData source) {
        StateData result = new StateData();
        result.schemaVersion = source.schemaVersion;
        result.lastServerId = SettingsMigration.text(source.lastServerId);
        result.environmentSetupCompleted = source.environmentSetupCompleted;
        if (source.servers != null) for (ServerProfile server : source.servers)
            if (server != null) result.servers.add(new ServerProfile(server));
        if (source.knownServices != null) for (ServiceProfile service : source.knownServices)
            if (service != null) result.knownServices.add(new ServiceProfile(service));
        return result;
    }

    private static StateData migrate(StateData state) {
        SettingsMigration.servers(state.servers);
        SettingsMigration.services(state.knownServices);
        // Last saved association wins for old archives containing the same source more than once.
        List<ServiceProfile> unique = new ArrayList<>();
        for (ServiceProfile service : state.knownServices) {
            unique.removeIf(previous -> sameBuildFile(previous.buildFilePath, service.buildFilePath));
            service.tracked = false;
            unique.add(service);
        }
        state.knownServices = unique;
        state.schemaVersion = Math.max(state.schemaVersion, SettingsMigration.VERSION);
        return state;
    }

    private static void publish(boolean servers, boolean sources) {
        var app = ApplicationManager.getApplication();
        if (app == null || app.isDisposed()) return;
        Listener listener = app.getMessageBus().syncPublisher(CHANGED);
        if (servers) listener.serversChanged();
        if (sources) listener.sourcesChanged();
    }

    public synchronized ServiceProfile findKnownServiceByBuildFile(String buildFilePath) {
        if (buildFilePath == null || buildFilePath.isBlank()) return null;
        for (ServiceProfile service : state.knownServices) {
            if (sameBuildFile(service.buildFilePath, buildFilePath)) return new ServiceProfile(service);
        }
        return null;
    }

    public synchronized ServiceProfile findKnownServiceByDeploymentName(String deploymentName) {
        if (deploymentName == null || deploymentName.isBlank()) return null;
        for (ServiceProfile service : state.knownServices) {
            String known = service.deploymentName;
            if (known != null && !known.isBlank() && deploymentName.equalsIgnoreCase(known.trim())) {
                return new ServiceProfile(service);
            }
            String ext = service.packaging == null || service.packaging.isBlank() || "auto".equalsIgnoreCase(service.packaging)
                    ? "war" : service.packaging.trim().toLowerCase(java.util.Locale.ROOT);
            String safeName = service.name == null || service.name.isBlank() ? "service" : service.name.replaceAll("[^A-Za-z0-9._-]", "-");
            if (deploymentName.equalsIgnoreCase(safeName + "." + ext)) return new ServiceProfile(service);
        }
        return null;
    }

    private static boolean sameBuildFile(String a, String b) {
        if (a == null || b == null || a.isBlank() || b.isBlank()) return false;
        try { return Path.of(a).toAbsolutePath().normalize().equals(Path.of(b).toAbsolutePath().normalize()); }
        catch (Exception e) { return Objects.equals(a, b); }
    }
}
