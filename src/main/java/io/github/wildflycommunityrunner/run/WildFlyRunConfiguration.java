package io.github.wildflycommunityrunner.run;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.Executor;
import com.intellij.execution.configurations.*;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.remote.RemoteConfiguration;
import com.intellij.execution.remote.RemoteConfigurationType;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.search.GlobalSearchScope;
import io.github.wildflycommunityrunner.model.ServerProfile;
import io.github.wildflycommunityrunner.services.WildFlyServerDetector;
import io.github.wildflycommunityrunner.settings.WildFlyApplicationSettings;
import io.github.wildflycommunityrunner.settings.WildFlyProjectSettings;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;

public class WildFlyRunConfiguration extends RunConfigurationBase<RunConfigurationOptions> implements ModuleRunProfile {
    private String serverId = "";

    public WildFlyRunConfiguration(Project project, ConfigurationFactory factory, String name) {
        super(project, factory, name);
        var settings = WildFlyApplicationSettings.getInstance();
        String projectSelection = WildFlyProjectSettings.getInstance(project).getState().selectedServerId;
        serverId = projectSelection == null || projectSelection.isBlank() ? settings.lastServerId() : projectSelection;
        if (serverId.isBlank() && settings.servers().size() == 1) serverId = settings.servers().getFirst().id;
    }

    public String getServerId() { return serverId; }
    public void setServerId(String value) { serverId = value == null ? "" : value; }
    public boolean isAttachOnly() { return false; }

    public ServerProfile resolveServer() {
        return WildFlyApplicationSettings.getInstance().servers().stream()
                .filter(server -> server != null && serverId.equals(server.id))
                .findFirst().map(ServerProfile::new).orElse(null);
    }

    private String configurationError() {
        ServerProfile server = resolveServer();
        if (server == null) return "Choose a WildFly server profile. Profiles are managed in the WildFly tool window.";
        if (server.host == null || server.host.isBlank()) return "The server profile needs a host.";
        if (server.debugPort < 1 || server.debugPort > 65535) return "Debug port must be between 1 and 65535.";
        if (!isAttachOnly() && (server.httpPort < 1 || server.httpPort > 65535)) return "HTTP port must be between 1 and 65535.";
        if (!isAttachOnly() && (server.home == null || server.home.isBlank())) {
            return "Set WildFly Home in the selected server profile.";
        }
        return null;
    }

    @Override public void checkConfiguration() throws RuntimeConfigurationException {
        String error = configurationError();
        if (error != null) throw new RuntimeConfigurationError(error);
    }

    @Override public RunProfileState getState(@NotNull Executor executor, @NotNull ExecutionEnvironment environment) throws ExecutionException {
        String error = configurationError();
        if (error != null) throw new ExecutionException(error);
        ServerProfile profile = resolveServer();
        if (profile == null) throw new ExecutionException("The selected WildFly profile was removed.");
        boolean debug = DefaultDebugExecutor.EXECUTOR_ID.equals(executor.getId());
        if (isAttachOnly()) {
            if (!debug) throw new ExecutionException("Use Debug to attach to WildFly.");
            RemoteConfiguration remote = new RemoteConfiguration(getProject(), RemoteConfigurationType.getInstance());
            remote.setName(getName());
            remote.USE_SOCKET_TRANSPORT = true;
            remote.SERVER_MODE = false;
            remote.HOST = WildFlyServerDetector.connectionHost(profile.host);
            remote.PORT = Integer.toString(profile.debugPort);
            remote.AUTO_RESTART = false;
            return remote.getState(executor, environment);
        }
        return new WildFlyRunState(environment, profile, debug);
    }

    @Override public @NotNull SettingsEditor<WildFlyRunConfiguration> getConfigurationEditor() {
        return new WildFlySettingsEditor(isAttachOnly());
    }

    @Override public boolean isBuildBeforeLaunchAddedByDefault() { return false; }
    @Override public boolean isBuildProjectOnEmptyModuleList() { return false; }
    @Override public boolean isExcludeCompileBeforeLaunchOption() { return true; }
    @Override public @NotNull GlobalSearchScope getSearchScope() { return GlobalSearchScope.allScope(getProject()); }

    @Override public void readExternal(@NotNull Element element) {
        super.readExternal(element);
        setServerId(element.getAttributeValue("wildflyServerId"));
    }

    @Override public void writeExternal(@NotNull Element element) {
        super.writeExternal(element);
        element.setAttribute("wildflyServerId", serverId);
    }
}
