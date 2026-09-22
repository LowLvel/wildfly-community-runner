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
    private String serverAlias = "";
    private java.util.List<String> servicePaths = java.util.List.of();

    public WildFlyRunConfiguration(Project project, ConfigurationFactory factory, String name) {
        super(project, factory, name);
        var settings = WildFlyApplicationSettings.getInstance();
        String projectSelection = WildFlyProjectSettings.getInstance(project).getState().selectedServerId;
        serverId = projectSelection == null || projectSelection.isBlank() ? settings.lastServerId() : projectSelection;
        var servers = settings.servers();
        if (serverId.isBlank() && servers.size() == 1) serverId = servers.getFirst().id;
    }

    public String getServerId() { return serverId; }
    public void setServerId(String value) {
        serverId = value == null ? "" : value;
        ServerProfile server = resolveServer();
        if (server != null) serverAlias = server.name;
    }
    public java.util.List<String> getServicePaths() { return java.util.List.copyOf(servicePaths); }
    public void setServicePaths(java.util.List<String> paths) { servicePaths = java.util.List.copyOf(paths); }
    public String servicePath(io.github.wildflycommunityrunner.model.ServiceProfile service) {
        java.nio.file.Path file = java.nio.file.Path.of(service.buildFilePath).normalize();
        String base = getProject().getBasePath();
        if (base != null && file.isAbsolute() && file.startsWith(java.nio.file.Path.of(base).toAbsolutePath().normalize()))
            file = java.nio.file.Path.of(base).toAbsolutePath().normalize().relativize(file);
        return file.toString().replace('\\', '/');
    }
    public java.util.List<io.github.wildflycommunityrunner.model.ServiceProfile> resolveServices() {
        var all = WildFlyProjectSettings.getInstance(getProject()).services();
        var selected = new java.util.ArrayList<io.github.wildflycommunityrunner.model.ServiceProfile>();
        for (String path : servicePaths) {
            var matches = all.stream().filter(service -> path.equals(servicePath(service))).toList();
            if (matches.size() != 1) throw new IllegalArgumentException("Application selection is missing or ambiguous: " + path + ". Re-select applications in this configuration.");
            selected.add(matches.getFirst());
        }
        io.github.wildflycommunityrunner.util.DeploymentNames.requireUnique(selected);
        return selected;
    }
    public boolean isAttachOnly() { return false; }

    public ServerProfile resolveServer() {
        var servers = WildFlyApplicationSettings.getInstance().servers();
        ServerProfile exact = servers.stream()
                .filter(server -> server != null && serverId.equals(server.id))
                .findFirst().map(ServerProfile::new).orElse(null);
        if (exact != null || serverAlias.isBlank()) return exact;
        var aliases = servers.stream().filter(server -> serverAlias.equals(server.name)).toList();
        return aliases.size() == 1 ? new ServerProfile(aliases.getFirst()) : null;
    }

    private String configurationError() {
        ServerProfile server = resolveServer();
        if (server == null) return "Choose a WildFly server profile. Profiles are managed in the WildFly tool window.";
        if (!isAttachOnly()) {
            try { resolveServices(); }
            catch (IllegalArgumentException error) { return error.getMessage(); }
        }
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
        return new WildFlyRunState(environment, profile, debug, resolveServices());
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
        serverAlias = java.util.Objects.toString(element.getAttributeValue("wildflyServerAlias"), "");
        servicePaths = element.getChildren("wildflyApplication").stream().map(child -> child.getAttributeValue("buildFile"))
                .filter(java.util.Objects::nonNull).toList();
    }

    @Override public void writeExternal(@NotNull Element element) {
        super.writeExternal(element);
        element.setAttribute("wildflyServerId", serverId);
        ServerProfile server = resolveServer();
        element.setAttribute("wildflyServerAlias", server == null ? serverAlias : server.name);
        element.removeChildren("wildflyApplication");
        for (String path : servicePaths) element.addContent(new Element("wildflyApplication").setAttribute("buildFile", path));
    }
}
