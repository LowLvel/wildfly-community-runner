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

CI retries only the known [JetBrains layout-index race](https://github.com/JetBrains/intellij-platform-gradle-plugin/issues/2192)
when its exact error signatures appear before any Gradle task executes, at most
three attempts. Only generated layout-index JSON is cleared. Compiler, test, and
verifier failures are never retried by this helper. Its retry policy has dedicated
Python tests. The first test-stage run hit this upstream race; an unchanged Linux
retry and both other operating systems executed all 19 regression tests successfully.

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
2. Automated behavior and platform integration tests — passed [35719197292](https://github.com/LowLvel/wildfly-community-runner/actions/runs/35719197292): 19 plugin tests and five CI-helper tests on three operating systems, plus all six verifier targets.
3. Native Run/Debug configurations — passed [35721351298](https://github.com/LowLvel/wildfly-community-runner/actions/runs/35721351298): 32 plugin tests on three operating systems and all six verifier targets. A report-upload failure passed on retry; the compatibility check itself passed on both attempts.
4. Notifications, threading boundaries, and error handling — passed [35725003648](https://github.com/LowLvel/wildfly-community-runner/actions/runs/35725003648): 48 plugin tests and five CI-helper tests on all three operating systems, plus all six verifier targets.
5. Server detection — passed [35729937907](https://github.com/LowLvel/wildfly-community-runner/actions/runs/35729937907): 67 plugin tests on all three operating systems and all six verifier targets. Includes exact process identity, managed-instance aliases, occupied-port rejection, Windows CIM/quoting, and an actual child-JVM detection fixture. The fixture emulates launcher arguments; it does not start a WildFly server.
6. Onboarding — passed [35734948228](https://github.com/LowLvel/wildfly-community-runner/actions/runs/35734948228): 77 plugin tests on three operating systems and all six verifier targets. Covers environment home validation, first-use discovery, preserved user edits and legacy settings, Safe Mode, and saved watcher initialization without a tool window. The initial 2025.1 job passed on retry after a Maven Central HTTP 429 download failure.
7. Build lifecycle and cancellation — passed [35737989577](https://github.com/LowLvel/wildfly-community-runner/actions/runs/35737989577): 91 plugin tests on three operating systems and all six verifier targets. Covers project-owned batches, native progress, cancellation before/during launch, exact Maven environment ownership, fast exits, and suppression through deployment.
8. Artifact watcher hardening — passed [35743561787](https://github.com/LowLvel/wildfly-community-runner/actions/runs/35743561787): 105 plugin tests on all three operating systems and all six verifier targets. Covers content fingerprints, stable ZIP output, changes during in-flight deployment, directory recreation, source filtering, expired generations, cross-project suppression and deduplication, and serialized scanner targets. Filesystem tests emulate scanner acknowledgements with a fake managed process; they are not a WildFly runtime test.
9. Settings migration and stale registry cleanup (in progress): versioned XML migration, detached settings snapshots, concurrent updates, remembered-source relink/forget, cross-project change notifications, and listener cleanup on unload without terminating shared servers.
10. Sensitive JVM properties.
11. Server log viewer.
12. Marketplace metadata, icons, screenshots, documentation, and release workflow.

Each major stage requires compilation, tests, and applicable verifier results
before the following stage begins. Preserve the architecture boundaries in
[ARCHITECTURE.md](ARCHITECTURE.md).

## Assessed compatibility warnings

The 2026.x verifier reports two uses of `ReadAction.compute` in project discovery.
They protect short imported-project/model snapshots; filesystem scanning takes
place outside the read action on background threads. This public API supports the
2025.1 baseline. The native execution code no longer uses `ProcessAdapter` or the
deprecated `ConfigurationException.getMessage()` method.

Onboarding uses the public `ProjectActivity` entry point. `ProjectTrust` uses the
public `com.intellij.ide.impl.TrustedProjects.isTrusted` compatibility facade:
the replacement `Project` overload did not exist in the initial 2025.1 release.
The facade is experimental on the baseline and deprecated on later releases;
it is not an internal API. Its trust-change listener is also public experimental.
The verifier matrix covers both on every declared IDE release line.
