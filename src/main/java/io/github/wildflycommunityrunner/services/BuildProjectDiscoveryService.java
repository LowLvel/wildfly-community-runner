package io.github.wildflycommunityrunner.services;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import io.github.wildflycommunityrunner.model.BuildSystem;
import org.jetbrains.idea.maven.project.MavenProject;
import org.jetbrains.idea.maven.project.MavenProjectsManager;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class BuildProjectDiscoveryService {
    private static final int MAX_SCAN_DEPTH = 10;
    private static final Set<String> SKIPPED_DIRECTORIES = Set.of(
            ".git", ".idea", ".gradle", ".mvn", "target", "build", "out", "dist",
            "node_modules", ".next", ".cache", "coverage", "generated", "classes"
    );

    public record BuildProjectChoice(String name,
                                     String displayPath,
                                     BuildSystem system,
                                     String buildFilePath,
                                     String packaging) {
        @Override
        public String toString() {
            return displayPath + "  —  " + system;
        }
    }

    private BuildProjectDiscoveryService() {}

    public static List<BuildProjectChoice> discoverImportedOnly(Project project) {
        Map<String, BuildProjectChoice> byPath = new LinkedHashMap<>();
        Path projectBase = projectBase(project);
        List<Path> roots = ReadAction.compute(() -> discoverImported(project, projectBase, byPath));
        for (Path root : roots) addDirectBuildFiles(root, projectBase, byPath);
        return sorted(byPath);
    }

    public static List<BuildProjectChoice> discover(Project project) {
        Map<String, BuildProjectChoice> byPath = new LinkedHashMap<>();
        Path projectBase = projectBase(project);

        // Project-model access must be protected by a read action on IDEA 2025.1+.
        List<Path> roots = ReadAction.compute(() -> discoverImported(project, projectBase, byPath));
        for (Path root : roots) addDirectBuildFiles(root, projectBase, byPath);

        // Also scan the project tree so nested projects that have not yet been imported by
        // IntelliJ are still discoverable. Keep it bounded and skip output/vendor folders.
        LinkedHashSet<Path> scanRoots = new LinkedHashSet<>();
        if (projectBase != null) {
            scanRoots.add(projectBase);
            for (Path root : roots) {
                try {
                    if (!root.toAbsolutePath().normalize().startsWith(projectBase)) scanRoots.add(root);
                } catch (Exception ignored) {}
            }
        } else {
            scanRoots.addAll(roots);
        }
        for (Path root : scanRoots) scanRecursively(root, projectBase, byPath);
        return sorted(byPath);
    }

    private static List<BuildProjectChoice> sorted(Map<String, BuildProjectChoice> byPath) {
        return byPath.values().stream()
                .sorted(Comparator.comparing(BuildProjectChoice::displayPath, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(c -> c.system().name()))
                .toList();
    }

    private static List<Path> discoverImported(Project project,
                                               Path projectBase,
                                               Map<String, BuildProjectChoice> out) {
        List<Path> roots = new ArrayList<>();
        if (project.isDisposed()) return roots;

        MavenProjectsManager manager = MavenProjectsManager.getInstance(project);
        for (MavenProject mp : manager.getProjects()) {
            String artifactId = mp.getMavenId().getArtifactId();
            Path buildFile = Path.of(mp.getFile().getPath());
            String name = artifactId == null || artifactId.isBlank()
                    ? moduleFolderName(buildFile)
                    : artifactId;
            String packaging = mp.getPackaging() == null ? "" : mp.getPackaging();
            if (!"pom".equalsIgnoreCase(packaging)) {
                addChoice(out, projectBase, name, BuildSystem.MAVEN, buildFile, packaging);
            }
            Path parent = buildFile.getParent();
            if (parent != null) roots.add(parent);
        }

        for (Module module : ModuleManager.getInstance(project).getModules()) {
            for (VirtualFile root : ModuleRootManager.getInstance(module).getContentRoots()) {
                try {
                    Path moduleRoot = Path.of(root.getPath());
                    roots.add(moduleRoot);
                } catch (Exception ignored) {
                    // A malformed/non-local VFS root should not make discovery fail.
                }
            }
        }
        return roots;
    }

    // Package boundary also permits filesystem regression tests without starting an IDE.
    static void scanRecursively(Path root,
                                        Path projectBase,
                                        Map<String, BuildProjectChoice> out) {
        if (root == null || !Files.isDirectory(root)) return;
        try {
            Files.walkFileTree(root, Set.of(), MAX_SCAN_DEPTH, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    ProgressManager.checkCanceled();
                    if (!dir.equals(root)) {
                        Path fileName = dir.getFileName();
                        String name = fileName == null ? "" : fileName.toString().toLowerCase(Locale.ROOT);
                        if (SKIPPED_DIRECTORIES.contains(name)) return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString();
                    if ("pom.xml".equalsIgnoreCase(name)) {
                        addScannedMaven(file, projectBase, out);
                    } else if ("build.gradle".equals(name) || "build.gradle.kts".equals(name)) {
                        addScannedGradle(file, projectBase, out);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (ProcessCanceledException cancelled) {
            throw cancelled;
        } catch (Exception ignored) {
            // Discovery is best-effort. Imported projects remain available even if part of the
            // filesystem tree is unreadable (common on corporate workspaces).
        }
    }

    private static void addDirectBuildFiles(Path root,
                                            Path projectBase,
                                            Map<String, BuildProjectChoice> out) {
        Path pom = root.resolve("pom.xml");
        if (Files.isRegularFile(pom)) addScannedMaven(pom, projectBase, out);
        Path kotlin = root.resolve("build.gradle.kts");
        if (Files.isRegularFile(kotlin)) addScannedGradle(kotlin, projectBase, out);
        Path groovy = root.resolve("build.gradle");
        if (Files.isRegularFile(groovy)) addScannedGradle(groovy, projectBase, out);
    }

    private static void addScannedMaven(Path pom,
                                        Path projectBase,
                                        Map<String, BuildProjectChoice> out) {
        String key = normalized(pom.toString());
        if (out.containsKey(key)) return;
        String packaging = directPomValue(pom, "packaging");
        if (packaging == null || packaging.isBlank()) packaging = "jar";
        if ("pom".equalsIgnoreCase(packaging)) return;
        String artifactId = directPomValue(pom, "artifactId");
        String name = artifactId == null || artifactId.isBlank() ? moduleFolderName(pom) : artifactId.trim();
        addChoice(out, projectBase, name, BuildSystem.MAVEN, pom, packaging);
    }

    private static void addScannedGradle(Path buildFile,
                                         Path projectBase,
                                         Map<String, BuildProjectChoice> out) {
        String key = normalized(buildFile.toString());
        if (out.containsKey(key)) return;
        String name = moduleFolderName(buildFile);
        addChoice(out, projectBase, name, BuildSystem.GRADLE, buildFile, inferGradlePackaging(buildFile));
    }

    private static void addChoice(Map<String, BuildProjectChoice> out,
                                  Path projectBase,
                                  String name,
                                  BuildSystem system,
                                  Path buildFile,
                                  String packaging) {
        String key = normalized(buildFile.toString());
        out.putIfAbsent(key, new BuildProjectChoice(
                name,
                displayPath(projectBase, buildFile, name),
                system,
                buildFile.toString(),
                packaging == null ? "" : packaging
        ));
    }

    private static String displayPath(Path projectBase, Path buildFile, String projectName) {
        Path moduleDir = buildFile.getParent();
        if (moduleDir == null) return projectName;
        try {
            Path normalizedModule = moduleDir.toAbsolutePath().normalize();
            if (projectBase != null) {
                Path normalizedBase = projectBase.toAbsolutePath().normalize();
                if (normalizedModule.startsWith(normalizedBase)) {
                    Path relative = normalizedBase.relativize(normalizedModule);
                    if (relative.getNameCount() == 0) return projectName;
                    String folderPath = joinHierarchy(relative);
                    String leaf = relative.getFileName().toString();
                    return leaf.equalsIgnoreCase(projectName)
                            ? folderPath
                            : folderPath + "  —  " + projectName;
                }
            }
        } catch (Exception ignored) {}
        String folder = moduleDir.getFileName() == null ? "" : moduleDir.getFileName().toString();
        return folder.equalsIgnoreCase(projectName) || folder.isBlank()
                ? projectName
                : folder + "  —  " + projectName;
    }

    private static String joinHierarchy(Path relative) {
        List<String> parts = new ArrayList<>();
        for (Path part : relative) parts.add(part.toString());
        return String.join(" › ", parts);
    }

    private static String moduleFolderName(Path buildFile) {
        Path parent = buildFile.getParent();
        if (parent == null || parent.getFileName() == null) return buildFile.getFileName().toString();
        return parent.getFileName().toString();
    }

    private static String directPomValue(Path pom, String tag) {
        try (InputStream in = Files.newInputStream(pom)) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            Node root = factory.newDocumentBuilder().parse(in).getDocumentElement();
            NodeList children = root.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                if (child.getNodeType() == Node.ELEMENT_NODE && tag.equals(child.getNodeName())) {
                    return child.getTextContent() == null ? null : child.getTextContent().trim();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String inferGradlePackaging(Path buildFile) {
        try {
            String compact = Files.readString(buildFile).toLowerCase(Locale.ROOT);
            if (compact.contains("id(\"ear\")") || compact.contains("id 'ear'")
                    || compact.contains("plugin: 'ear'") || compact.contains("apply plugin: \"ear\"")) return "ear";
            if (compact.contains("id(\"war\")") || compact.contains("id 'war'")
                    || compact.contains("plugin: 'war'") || compact.contains("apply plugin: \"war\"")) return "war";
        } catch (Exception ignored) {}
        return "jar";
    }

    private static Path projectBase(Project project) {
        String base = project.getBasePath();
        if (base == null || base.isBlank()) return null;
        try { return Path.of(base).toAbsolutePath().normalize(); }
        catch (Exception e) { return null; }
    }

    private static String normalized(String path) {
        try { return Path.of(path).toAbsolutePath().normalize().toString(); }
        catch (Exception e) { return path; }
    }
}
