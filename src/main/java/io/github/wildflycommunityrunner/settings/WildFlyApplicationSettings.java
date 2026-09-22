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

@Service(Service.Level.APP)
@State(name = "WildFlyCommunityRunner", storages = @Storage("wildflyCommunityRunner.xml"))
public final class WildFlyApplicationSettings implements PersistentStateComponent<WildFlyApplicationSettings.StateData> {
    public static final class StateData {
        public List<ServerProfile> servers = new ArrayList<>();
        public List<ServiceProfile> knownServices = new ArrayList<>();
        public String lastServerId = "";
    }

    private StateData state = new StateData();

    public static WildFlyApplicationSettings getInstance() {
        return ApplicationManager.getApplication().getService(WildFlyApplicationSettings.class);
    }

    @Override
    public @NotNull StateData getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull StateData state) {
        this.state = state;
        if (this.state.servers == null) this.state.servers = new ArrayList<>();
        if (this.state.knownServices == null) this.state.knownServices = new ArrayList<>();
        for (ServiceProfile service : this.state.knownServices) service.migrateLegacyFields();
        for (ServerProfile server : this.state.servers) {
            if (server.host == null || server.host.isBlank()) server.host = "localhost";
            if (server.httpPort <= 0) server.httpPort = 8080;
            if (server.debugPort <= 0) server.debugPort = 8787;
        }
    }

    public List<ServerProfile> servers() {
        return state.servers;
    }

    public List<ServiceProfile> knownServices() {
        return state.knownServices;
    }

    public String lastServerId() {
        return state.lastServerId == null ? "" : state.lastServerId;
    }

    public void setLastServerId(String id) {
        state.lastServerId = id == null ? "" : id;
    }

    public synchronized void rememberService(ServiceProfile service) {
        if (service == null || service.buildFilePath == null || service.buildFilePath.isBlank()) return;
        ServiceProfile copy = new ServiceProfile(service);
        copy.tracked = false; // legacy flag is intentionally not carried into global registry semantics
        for (int i = 0; i < state.knownServices.size(); i++) {
            ServiceProfile existing = state.knownServices.get(i);
            if (sameBuildFile(existing.buildFilePath, copy.buildFilePath)) {
                state.knownServices.set(i, copy);
                return;
            }
        }
        state.knownServices.add(copy);
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
                    ? "war" : service.packaging.trim().toLowerCase();
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
