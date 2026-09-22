package io.github.wildflycommunityrunner.settings;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.project.Project;
import io.github.wildflycommunityrunner.model.ServiceProfile;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.nio.file.Path;

@Service(Service.Level.PROJECT)
@State(name = "WildFlyCommunityRunnerProject", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class WildFlyProjectSettings implements PersistentStateComponent<WildFlyProjectSettings.StateData> {
    public static final class StateData {
        public String selectedServerId = "";
        public String selectedServiceId = "";
        public List<ServiceProfile> services = new ArrayList<>();
        public boolean onboardingCompleted;

        // Kept only to make upgrades from the first MVP non-destructive.
        public String artifactPath = "";
        public String mavenGoals = "";
        public String mavenWorkingDirectory = "";
    }

    private StateData state = new StateData();

    public static WildFlyProjectSettings getInstance(Project project) {
        return project.getService(WildFlyProjectSettings.class);
    }

    @Override
    public @NotNull StateData getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull StateData state) {
        this.state = state;
        if (this.state.services == null) this.state.services = new ArrayList<>();
        for (ServiceProfile service : this.state.services) service.migrateLegacyFields();
    }

    public List<ServiceProfile> services() {
        return state.services;
    }

    /** Also used before startup discovery, so legacy single-service settings are not replaced by onboarding. */
    public void migrateLegacyService() {
        if (!state.services.isEmpty()) return;
        if ((state.mavenWorkingDirectory == null || state.mavenWorkingDirectory.isBlank())
                && (state.artifactPath == null || state.artifactPath.isBlank())) return;
        var legacy = new ServiceProfile();
        legacy.name = "Legacy service";
        if (state.mavenWorkingDirectory != null && !state.mavenWorkingDirectory.isBlank()) {
            legacy.buildFilePath = Path.of(state.mavenWorkingDirectory).resolve("pom.xml").toString();
        }
        legacy.artifactPath = state.artifactPath == null ? "" : state.artifactPath;
        legacy.buildTasks = state.mavenGoals == null || state.mavenGoals.isBlank() ? "clean package" : state.mavenGoals;
        state.services.add(legacy);
    }
}
