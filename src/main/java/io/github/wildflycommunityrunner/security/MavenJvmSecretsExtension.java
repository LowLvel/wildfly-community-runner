package io.github.wildflycommunityrunner.security;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.RunConfigurationExtension;
import com.intellij.execution.configurations.JavaParameters;
import com.intellij.execution.configurations.RunConfigurationBase;
import com.intellij.execution.configurations.RunnerSettings;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.util.execution.ParametersListUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.idea.maven.execution.MavenRunConfiguration;

/** Resolves references only into a launch's JavaParameters, never into saved settings. */
public final class MavenJvmSecretsExtension extends RunConfigurationExtension {
    @Override public boolean isApplicableFor(@NotNull RunConfigurationBase<?> configuration) {
        if (!(configuration instanceof MavenRunConfiguration maven) || maven.getRunnerSettings() == null) return false;
        String options = maven.getRunnerSettings().getVmOptions();
        return options != null && (options.contains("${secret:") || options.contains("${env:"));
    }

    @Override public <T extends RunConfigurationBase<?>> void updateJavaParameters(@NotNull T configuration,
            @NotNull JavaParameters parameters, @Nullable RunnerSettings runnerSettings) throws ExecutionException {
        if (!isApplicableFor(configuration)) return;
        updateParameters(parameters, configuration.getProject().getService(MavenSecretSessions.class));
    }

    static void updateParameters(JavaParameters parameters, MavenSecretSessions sessions) throws ExecutionException {
        try {
            String prepared = sessions.prepare(ParametersListUtil.join(parameters.getVMParametersList().getList()));
            parameters.getVMParametersList().clearAll();
            parameters.getVMParametersList().addParametersString(prepared);
        } catch (ProcessCanceledException cancelled) { throw cancelled; }
        catch (Exception failure) { throw new ExecutionException(SensitiveProperties.redactProperties(failure.getMessage())); }
    }

    @Override protected void attachToProcess(@NotNull RunConfigurationBase<?> configuration,
            @NotNull ProcessHandler handler, @Nullable RunnerSettings runnerSettings) {
        if (!configuration.getProject().isDisposed() && handler instanceof OSProcessHandler process) {
            configuration.getProject().getService(MavenSecretSessions.class).attach(handler, process.getCommandLine());
        }
    }
}
