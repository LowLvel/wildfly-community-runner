package io.github.wildflycommunityrunner.services;

import java.util.ArrayList;
import java.util.List;

/** Profile JVM options have always taken precedence over earlier Gradle CLI overrides. */
final class GradleJvmArguments {
    private GradleJvmArguments() {}

    static List<String> withoutJvmOverrides(List<String> arguments) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < arguments.size(); i++) {
            String value = arguments.get(i);
            if ((value.equals("-D") || value.equals("--system-prop")) && i + 1 < arguments.size()
                    && isJvmProperty(arguments.get(i + 1))) { i++; continue; }
            if (value.startsWith("-D") && isJvmProperty(value.substring(2))) continue;
            if (value.startsWith("--system-prop=") && isJvmProperty(value.substring("--system-prop=".length()))) continue;
            result.add(value);
        }
        return result;
    }

    private static boolean isJvmProperty(String value) {
        return value.equals("org.gradle.jvmargs") || value.startsWith("org.gradle.jvmargs=");
    }
}
