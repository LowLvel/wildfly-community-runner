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
import java.util.function.Consumer;

@Service(Service.Level.PROJECT)
@State(name = "WildFlyCommunityRunnerProject", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class WildFlyProjectSettings implements PersistentStateComponent<WildFlyProjectSettings.StateData> {
    public static final class StateData {
        public int schemaVersion;
        public String selectedServerId = "";
        public String selectedServiceId = "";
        public List<ServiceProfile> services = new ArrayList<>();
        public boolean onboardingCompleted;

        // Kept only to make upgrades from the first MVP non-destructive.
        public String artifactPath = "";
        public String mavenGoals = "";
        public String mavenWorkingDirectory = "";
    }

    private StateData state = migrate(new StateData());

    public static WildFlyProjectSettings getInstance(Project project) {
        return project.getService(WildFlyProjectSettings.class);
    }

    @Override public synchronized @NotNull StateData getState() { return copy(state); }
    @Override public synchronized void loadState(@NotNull StateData value) { state = migrate(copy(value)); }

    public synchronized void update(Consumer<StateData> edit) {
        StateData draft = copy(state);
        edit.accept(draft);
        state = copy(draft);
    }

    public synchronized List<ServiceProfile> services() { return copy(state).services; }

    /** Safe to repeat before discovery; obsolete values are cleared even if services already exist. */
    public synchronized void migrateLegacyService() { state = migrate(copy(state)); }

    private static StateData copy(StateData source) {
        StateData result = new StateData();
        result.schemaVersion = source.schemaVersion;
        result.selectedServerId = SettingsMigration.text(source.selectedServerId);
        result.selectedServiceId = SettingsMigration.text(source.selectedServiceId);
        result.onboardingCompleted = source.onboardingCompleted;
        result.artifactPath = SettingsMigration.text(source.artifactPath);
        result.mavenGoals = SettingsMigration.text(source.mavenGoals);
        result.mavenWorkingDirectory = SettingsMigration.text(source.mavenWorkingDirectory);
        if (source.services != null) for (ServiceProfile service : source.services)
            if (service != null) result.services.add(new ServiceProfile(service));
        return result;
    }

    private static StateData migrate(StateData state) {
        if (state.services.isEmpty() && (!state.mavenWorkingDirectory.isBlank() || !state.artifactPath.isBlank())) {
            ServiceProfile legacy = new ServiceProfile();
            legacy.name = "Legacy service";
            if (!state.mavenWorkingDirectory.isBlank()) {
                try { legacy.buildFilePath = Path.of(state.mavenWorkingDirectory).resolve("pom.xml").toString(); }
                catch (IllegalArgumentException invalid) {
                    // Preserve an invalid old path for explicit repair instead of aborting settings loading.
                    legacy.buildFilePath = state.mavenWorkingDirectory + java.io.File.separator + "pom.xml";
                }
            }
            legacy.artifactPath = state.artifactPath;
            legacy.buildTasks = SettingsMigration.fallback(state.mavenGoals, "clean package");
            state.services.add(legacy);
        }
        state.artifactPath = state.mavenGoals = state.mavenWorkingDirectory = "";
        SettingsMigration.services(state.services);
        state.schemaVersion = Math.max(state.schemaVersion, SettingsMigration.VERSION);
        return state;
    }
}
