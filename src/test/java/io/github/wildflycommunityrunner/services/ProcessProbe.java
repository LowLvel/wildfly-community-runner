package io.github.wildflycommunityrunner.services;

/** Independent test JVM for metadata detection; deliberately contains no WildFly or IntelliJ classes. */
public final class ProcessProbe {
    public static void main(String[] args) throws InterruptedException {
        System.out.println("ready");
        System.out.flush();
        Thread.sleep(30_000);
    }
}
