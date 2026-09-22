package io.github.wildflycommunityrunner.security;

/** Child JVM fixture: verifies exact values without printing them. */
public final class SecretProbe {
    public static void main(String[] arguments) {
        if (!java.util.Objects.equals(System.getenv("EXPECTED_TEST_SECRET"), System.getProperty("wildfly.test.password"))) System.exit(2);
        if (!"visible".equals(System.getProperty("ordinary"))) System.exit(3);
        System.out.println("Secret argument reached JVM correctly");
    }
}
