package io.github.wildflycommunityrunner.settings;

import io.github.wildflycommunityrunner.model.ServiceProfile;
import io.github.wildflycommunityrunner.security.SensitiveProperties;
import io.github.wildflycommunityrunner.util.BrowserUrls;
import io.github.wildflycommunityrunner.util.DeploymentNames;
import io.github.wildflycommunityrunner.util.SafeXml;
import java.nio.file.*;
import java.util.*;
import java.io.IOException;
import org.w3c.dom.Element;

/** Portable, opt-in project definitions. Credentials, JDKs and automatic behavior stay local. */
public final class ProjectDeploymentFile {
    public static final String LOCATION = ".wildfly/services.xml";
    private ProjectDeploymentFile() {}
    public static List<ServiceProfile> read(Path project) throws Exception {
        Path file = project.resolve(LOCATION);
        if (!Files.isRegularFile(file)) return List.of();
        Element root = SafeXml.read(file).getDocumentElement();
        if (!"wildfly-services".equals(root.getTagName()) || !"1".equals(root.getAttribute("version")))
            throw new IOException("Unsupported WildFly project file. Expected wildfly-services version 1.");
        List<ServiceProfile> result = new ArrayList<>();
        var nodes = root.getElementsByTagName("service");
        if (nodes.getLength() > 500) throw new IOException("Too many WildFly services in project file.");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element node = (Element) nodes.item(i);
            var service = ServiceProfile.create();
            service.name = node.getAttribute("name");
            service.buildSystem = node.getAttribute("buildSystem");
            if (!List.of("MAVEN", "GRADLE").contains(service.buildSystem)) throw new IOException("Unknown build system.");
            service.buildFilePath = relative(project, node.getAttribute("buildFile"));
            service.buildRootPath = optionalRelative(project, node.getAttribute("buildRoot"));
            service.packaging = node.getAttribute("packaging");
            if (!List.of("war", "ear", "jar", "auto").contains(service.packaging)) throw new IOException("Unsupported artifact type.");
            service.buildTasks = node.hasAttribute("tasks") ? node.getAttribute("tasks") : service.defaultTasks();
            service.buildArguments = node.getAttribute("arguments");
            service.deploymentName = node.getAttribute("deploymentName");
            service.artifactPath = node.getAttribute("artifact");
            if (!service.artifactPath.isBlank()) {
                Path module = project.resolve(service.buildFilePath).getParent();
                Path artifact = module.resolve(service.artifactPath).normalize();
                relative(project, artifact.toString());
            }
            service.contextPath = node.getAttribute("contextPath");
            service.browserUrl = node.getAttribute("browserUrl");
            validate(service);
            result.add(service);
        }
        DeploymentNames.requireUnique(result);
        requireDistinctBuildFiles(project, result);
        return result;
    }
    public static void write(Path project, List<ServiceProfile> services) throws Exception {
        DeploymentNames.requireUnique(services);
        requireDistinctBuildFiles(project, services);
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<wildfly-services version=\"1\">\n");
        for (var service : services) {
            validate(service);
            xml.append("  <service");
            attr(xml, "name", service.name); attr(xml, "buildSystem", service.buildSystem);
            String buildFile = relative(project, service.buildFilePath);
            attr(xml, "buildFile", buildFile); attr(xml, "buildRoot", optionalRelative(project, service.buildRootPath));
            attr(xml, "packaging", service.packaging); attr(xml, "tasks", service.buildTasks); attr(xml, "arguments", service.buildArguments);
            attr(xml, "deploymentName", service.deploymentName);
            String artifact = service.artifactPath;
            if (artifact != null && !artifact.isBlank()) {
                Path module = project.resolve(buildFile).getParent();
                Path absolute = module.resolve(artifact).normalize();
                relative(project, absolute.toString());
                artifact = module.relativize(absolute).toString().replace('\\', '/');
            }
            attr(xml, "artifact", artifact); attr(xml, "contextPath", service.contextPath); attr(xml, "browserUrl", service.browserUrl);
            xml.append("/>\n");
        }
        xml.append("</wildfly-services>\n");
        Path destination = project.resolve(LOCATION);
        Files.createDirectories(destination.getParent());
        Path temporary = Files.createTempFile(destination.getParent(), ".services-", ".xml");
        try {
            Files.writeString(temporary, xml);
            try { Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
    public static void merge(Path project, WildFlyProjectSettings.StateData state, List<ServiceProfile> imported) {
        for (var definition : imported) {
            ServiceProfile previous = state.services.stream().filter(service -> samePath(project, service.buildFilePath, definition.buildFilePath)).findFirst().orElse(null);
            ServiceProfile service = new ServiceProfile(definition);
            if (previous != null) {
                service.id = previous.id; service.buildJvmOptions = previous.buildJvmOptions;
                service.buildJavaHome = previous.buildJavaHome; service.deployAfterBuild = previous.deployAfterBuild;
                state.services.set(state.services.indexOf(previous), service);
            } else state.services.add(service);
        }
    }
    private static boolean samePath(Path root, String a, String b) {
        return root.resolve(a).toAbsolutePath().normalize().equals(root.resolve(b).toAbsolutePath().normalize());
    }
    private static void requireDistinctBuildFiles(Path root, List<ServiceProfile> services) {
        Set<String> files = new HashSet<>();
        for (var service : services) {
            if (!files.add(relative(root, service.buildFilePath)))
                throw new IllegalArgumentException("Shared services must have distinct build files: " + service.buildFilePath);
        }
    }
    private static void validate(ServiceProfile service) {
        if (service.name == null || service.name.isBlank()) throw new IllegalArgumentException("Shared service name is required.");
        SensitiveProperties.requireJvmField(service.buildTasks, "shared build tasks");
        SensitiveProperties.requireJvmField(service.buildArguments, "shared build arguments");
        BrowserUrls.validateOverride(service.browserUrl);
        io.github.wildflycommunityrunner.services.DeploymentScannerService.safeDeploymentName(DeploymentNames.name(service));
    }
    private static String optionalRelative(Path root, String path) {
        return path == null || path.isBlank() ? "" : relative(root, path);
    }
    private static String relative(Path root, String path) {
        if (path == null || path.isBlank()) throw new IllegalArgumentException("Shared build file is required.");
        Path base = root.toAbsolutePath().normalize();
        Path file = base.resolve(path).normalize();
        if (!file.startsWith(base)) throw new IllegalArgumentException("Shared paths must stay inside the project: " + path);
        return base.relativize(file).toString().replace('\\', '/');
    }
    private static void attr(StringBuilder xml, String key, String value) {
        value = Objects.toString(value, "").replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\r", "&#13;").replace("\n", "&#10;").replace("\t", "&#9;");
        xml.append(' ').append(key).append("=\"").append(value).append('"');
    }
}
