package io.github.wildflycommunityrunner.run;

import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.RunConfigurationWithSuppressedDefaultRunAction;
import com.intellij.openapi.project.Project;

/** Attaching never owns or stops the server process. IntelliJ's remote runner owns the debugger session. */
public final class WildFlyAttachConfiguration extends WildFlyRunConfiguration implements RunConfigurationWithSuppressedDefaultRunAction {
    public WildFlyAttachConfiguration(Project project, ConfigurationFactory factory, String name) {
        super(project, factory, name);
    }

    @Override public boolean isAttachOnly() { return true; }
}
