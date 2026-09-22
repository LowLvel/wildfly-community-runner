package io.github.wildflycommunityrunner.settings;

import io.github.wildflycommunityrunner.model.ServerProfile;
import io.github.wildflycommunityrunner.model.ServiceProfile;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Pure, idempotent normalization: loading settings never probes the filesystem or credentials store. */
final class SettingsMigration {
    static final int VERSION = 1;
    private SettingsMigration() {}
    static String text(String value) { return value == null ? "" : value; }
    static String fallback(String value, String fallback) { return text(value).isBlank() ? fallback : value; }

    static void services(List<ServiceProfile> services) {
        Set<String> ids = new HashSet<>();
        services.removeIf(java.util.Objects::isNull);
        for (ServiceProfile service : services) {
            service.id = unique(service.id, ids);
            service.migrateLegacyFields();
            service.name = fallback(service.name, "Service");
            service.buildFilePath = text(service.buildFilePath);
            service.buildArguments = text(service.buildArguments);
            service.buildJvmOptions = text(service.buildJvmOptions);
            service.packaging = fallback(service.packaging, "war");
            service.artifactPath = text(service.artifactPath);
            service.deploymentName = text(service.deploymentName);
            service.contextPath = text(service.contextPath);
        }
    }

    static void servers(List<ServerProfile> servers) {
        Set<String> ids = new HashSet<>();
        servers.removeIf(java.util.Objects::isNull);
        for (ServerProfile server : servers) {
            server.id = unique(server.id, ids);
            server.name = fallback(server.name, "WildFly");
            server.home = text(server.home);
            server.configuration = fallback(server.configuration, "standalone.xml");
            server.javaHome = text(server.javaHome);
            server.host = fallback(server.host, "localhost");
            if (server.httpPort < 1 || server.httpPort > 65535) server.httpPort = 8080;
            if (server.debugPort < 1 || server.debugPort > 65535) server.debugPort = 8787;
            server.startupArguments = text(server.startupArguments);
            server.jvmOptions = text(server.jvmOptions);
        }
    }

    private static String unique(String id, Set<String> ids) {
        if (text(id).isBlank() || !ids.add(id)) {
            do { id = UUID.randomUUID().toString(); } while (!ids.add(id));
        }
        return id;
    }
}
