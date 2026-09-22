package io.github.wildflycommunityrunner.util;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import java.util.function.BooleanSupplier;

/** Enter the platform's write-intent context even when the caller is a raw Swing callback. */
public final class IdeUi {
    private IdeUi() {}

    public static void later(Project project, Runnable action) {
        later(project, () -> false, action);
    }

    public static void later(Project project, BooleanSupplier expired, Runnable action) {
        later(project, ModalityState.defaultModalityState(), expired, action);
    }

    public static void later(Project project, ModalityState modality, BooleanSupplier expired, Runnable action) {
        var app = ApplicationManager.getApplication();
        if (app.isDisposed() || project.isDisposed() || expired.getAsBoolean()) return;
        app.invokeLater(() -> {
            if (!project.isDisposed() && !expired.getAsBoolean()) action.run();
        }, modality, ignored -> project.isDisposed() || expired.getAsBoolean());
    }
}
