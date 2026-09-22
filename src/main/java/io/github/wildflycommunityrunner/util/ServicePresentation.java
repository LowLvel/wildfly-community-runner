package io.github.wildflycommunityrunner.util;

import com.intellij.openapi.project.Project;
import io.github.wildflycommunityrunner.model.ServiceProfile;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ServicePresentation {
    private ServicePresentation() {}

    public static String displayName(Project project, ServiceProfile service) {
        if (service == null) return "";
        String name = service.name == null || service.name.isBlank() ? "Service" : service.name.trim();
        if (service.buildFilePath == null || service.buildFilePath.isBlank()) return name;
        try {
            Path buildFile = Path.of(service.buildFilePath).toAbsolutePath().normalize();
            Path moduleDir = buildFile.getParent();
            String base = project.getBasePath();
            if (moduleDir == null || base == null || base.isBlank()) return name;
            Path projectBase = Path.of(base).toAbsolutePath().normalize();
            if (!moduleDir.startsWith(projectBase)) return name;
            Path relative = projectBase.relativize(moduleDir);
            if (relative.getNameCount() == 0) return name;
            String hierarchy = joinHierarchy(relative);
            String leaf = relative.getFileName().toString();
            return leaf.equalsIgnoreCase(name) ? hierarchy : hierarchy + "  —  " + name;
        } catch (Exception ignored) {
            return name;
        }
    }

    public static String buildPathTooltip(ServiceProfile service) {
        if (service == null || service.buildFilePath == null || service.buildFilePath.isBlank()) return null;
        return service.buildFilePath;
    }

    private static String joinHierarchy(Path relative) {
        List<String> parts = new ArrayList<>();
        for (Path part : relative) parts.add(part.toString());
        return String.join(" › ", parts);
    }
}
