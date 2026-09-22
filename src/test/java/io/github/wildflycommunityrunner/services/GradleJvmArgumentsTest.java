package io.github.wildflycommunityrunner.services;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.assertEquals;

public class GradleJvmArgumentsTest {
    @Test public void profileOptionsSupersedeEverySupportedSystemPropertySyntax() {
        assertEquals(List.of("clean", "build"), GradleJvmArguments.withoutJvmOverrides(List.of(
                "clean", "-Dorg.gradle.jvmargs=-Xmx1g", "-D", "org.gradle.jvmargs=-Xmx2g",
                "--system-prop=org.gradle.jvmargs=-Xmx3g", "--system-prop", "org.gradle.jvmargs=-Xmx4g",
                "-Dorg.gradle.jvmargs", "build")));
    }
    @Test public void unrelatedPropertiesAndProjectArgumentsAreUnchanged() {
        List<String> values = List.of("test", "-Dordinary=value", "--system-prop", "another=value",
                "-Dorg.gradle.jvmargs.extra=value", "-Porg.gradle.jvmargs=project-value", "--offline");
        assertEquals(values, GradleJvmArguments.withoutJvmOverrides(values));
    }
}
