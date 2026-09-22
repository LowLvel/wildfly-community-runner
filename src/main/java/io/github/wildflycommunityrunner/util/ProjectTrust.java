package io.github.wildflycommunityrunner.util;

import com.intellij.openapi.project.Project;

/** Public compatibility facade shared by the initial 251 release and later platforms. */
public final class ProjectTrust {
    private ProjectTrust() {}

    @SuppressWarnings("deprecation")
    public static boolean isTrusted(Project project) {
        // The replacement Project overload did not exist in the original IDEA 2025.1.
        // Despite its package name this facade is public, not @ApiStatus.Internal.
        return !project.isDisposed() && com.intellij.ide.impl.TrustedProjects.isTrusted(project);
    }
}
