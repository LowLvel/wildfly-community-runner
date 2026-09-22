package io.github.wildflycommunityrunner.util;

import io.github.wildflycommunityrunner.model.ServiceProfile;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

public final class DeploymentNames {
    private DeploymentNames() {}
    public static String name(ServiceProfile service) {
        String type = List.of("war", "ear", "jar").contains(service.packaging) ? service.packaging : "war";
        return ArtifactLocator.effectiveDeploymentName(service, Path.of("application." + type));
    }
    public static void requireUnique(List<ServiceProfile> services) {
        var names = new HashMap<String, ServiceProfile>();
        for (var service : services) {
            String name = name(service);
            var previous = names.putIfAbsent(name.toLowerCase(Locale.ROOT), service);
            if (previous != null && !previous.id.equals(service.id))
                throw new IllegalArgumentException("Deployment name '" + name + "' is used by both " + previous.name
                        + " and " + service.name + ". Choose distinct deployment names before deploying.");
        }
    }
}
