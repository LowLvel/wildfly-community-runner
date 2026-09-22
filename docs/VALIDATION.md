# Validation

Use JDK 21 and the checked-in Gradle wrapper:

```sh
./gradlew verifyPluginProjectConfiguration test buildPlugin verifyPluginStructure
./gradlew verifyPlugin
```

On Windows use `gradlew.bat`. A full verifier run downloads large IDE archives;
to inspect one target:

```sh
./gradlew verifyPlugin -PverifierIde=IC-2025.1
```

`gradle.properties` owns the pinned IDE matrix used by both Gradle and CI. The
compiler baseline remains IDEA Community 2025.1.7. Verification includes the
earliest declared release and a maintained patch of each subsequent release
line through 2026.2. Unified IDEA releases use the IU distribution from 2025.3.
The configured upper bound is a candidate compatibility range, not evidence
that every runtime flow has been tested.

CI compiles, runs tests, checks plugin structure, and builds on Linux, Windows,
and macOS. Independent Plugin Verifier jobs retain reports even on failure.
`Validation gate` succeeds only when every required job passes and is the
intended required status check for branch protection. Cancelling a matrix job
cannot result in a successful gate. No job publishes to Marketplace.

Verifier errors for incompatible binaries, internal APIs, override-only APIs,
non-extendable APIs, missing dependencies, and invalid plugins block the build.
Warnings and deprecations remain in the reports for review. They must be assessed
before releasing; binary verification cannot prove runtime threading safety.

The imported 0.5.2 archive had no automated test sources. The regression suite now
exercises nested Maven/Gradle discovery, XML entity rejection, final artifact
selection and overrides, scanner status/timestamps/cleanup, legacy migration,
global source registry copies, and IntelliJ service registration/write intent.
Filesystem tests use temporary directories; platform tests boot an IntelliJ test
application. CI fails if no regression tests execute and retains JUnit XML/HTML.
These tests do not replace testing against a running WildFly server.

## Baseline evidence

- Source: user-supplied `wildfly-community-runner-0.5.2-source.zip`.
- GitHub originally contained historical source archives, most recently 0.5.0.
- Windows baseline attempted with Gradle 9.0.0 and JDK 21. SDK download succeeded,
  but Java canonical-path access failed with `AccessDeniedException` before
  compilation. This also reproduced with a newly installed JDK 21.0.12.1 and a
  minimal `Path.toRealPath()` check. It is not a compiler or test result.
- Baseline GitHub Actions run [35714805434](https://github.com/LowLvel/wildfly-community-runner/actions/runs/35714805434)
  passed project configuration validation but failed compilation: both profile
  dialogs declared private `applyFields()` helpers that collide with the protected
  platform method. The helpers are renamed without changing behavior.

## Ordered readiness work

1. Plugin Verifier and multiple-version CI — passed [35715308965](https://github.com/LowLvel/wildfly-community-runner/actions/runs/35715308965): three operating systems and six IDE targets.
2. Automated behavior and platform integration tests (validation in progress).
3. Native Run/Debug configurations.
4. Notifications and error handling.
5. Server detection.
6. Onboarding.
7. Build lifecycle and cancellation.
8. Artifact watcher hardening.
9. Settings migration and stale registry cleanup.
10. Sensitive JVM properties.
11. Server log viewer.
12. Marketplace metadata, icons, screenshots, documentation, and release workflow.

Each major stage requires compilation, tests, and applicable verifier results
before the following stage begins. Preserve the architecture boundaries in
[ARCHITECTURE.md](ARCHITECTURE.md).
