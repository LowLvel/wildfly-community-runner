package io.github.wildflycommunityrunner.services;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;

/** Java implementation of the public coroutine entry point; the disposable service owns queued work. */
public final class ProjectSetupActivity implements ProjectActivity {
    @Override public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        if (!ApplicationManager.getApplication().isUnitTestMode() && !project.isDefault()) {
            project.getService(ProjectSetupService.class).start();
        }
        return Unit.INSTANCE;
    }
}
