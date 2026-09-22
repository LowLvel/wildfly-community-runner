package io.github.wildflycommunityrunner.run;

import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.ConfigurationTypeBase;
import com.intellij.execution.configurations.RunConfiguration;
import io.github.wildflycommunityrunner.ui.WildFlyIcons;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

public final class WildFlyConfigurationType extends ConfigurationTypeBase {
    public WildFlyConfigurationType() {
        super("WildFlyCommunityRunner", "WildFly", "Run or debug a local WildFly server", WildFlyIcons.SERVER);
        addFactory(new Factory(this, false));
        addFactory(new Factory(this, true));
    }

    private static final class Factory extends ConfigurationFactory {
        private final boolean attach;

        private Factory(WildFlyConfigurationType type, boolean attach) {
            super(type);
            this.attach = attach;
        }

        @Override public @NotNull String getId() { return attach ? "AttachDebugger" : "LocalServer"; }
        @Override public @NotNull String getName() { return attach ? "Attach Debugger" : "Local Server"; }
        @Override public boolean isEditableInDumbMode() { return true; }

        @Override public @NotNull RunConfiguration createTemplateConfiguration(@NotNull Project project) {
            return attach ? new WildFlyAttachConfiguration(project, this, "WildFly Attach")
                    : new WildFlyRunConfiguration(project, this, "WildFly");
        }
    }
}
