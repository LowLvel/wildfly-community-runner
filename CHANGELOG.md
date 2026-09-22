# Changelog

## Unreleased

- Selected applications in native Local Server Run/Debug: start/reuse, wait for
  the expected HTTP endpoint, build, and deploy with cancellation and ownership.
- Explicit Build and Deploy / Build Only actions; new services use incremental
  build goals, tests enabled, and Auto Redeploy off. Existing settings survive.
- Explicit shared root builds, per-service build JDKs, deterministic artifact
  selection, and duplicate deployment-name validation.
- Optional versioned `.wildfly/services.xml` for portable project definitions;
  JDK paths, JVM options, Auto choices, and server installations stay local.
- Server-scoped source associations; scanner configuration/path validation,
  configurable timeouts, stopped/unknown status, full browser URLs, and IPv6.
- Focused application discovery and collapsed advanced JVM-option helpers.

## 0.6.0 — release candidate

This release retains the 0.5.2 Maven/Gradle and multi-service workflows while adding
native server configurations, safer lifecycle handling, and Marketplace tooling.

- Native WildFly **Local Server** Run/Debug and **Attach Debugger** configurations.
  Sessions distinguish the process they started from a shared process they reused.
- Background first-use discovery in trusted projects and optional server setup
  from `WILDFLY_HOME` / `JBOSS_HOME`. Setup never starts a server or a build.
- Exact standalone process identity, global profile awareness, occupied-port
  diagnostics, and bounded Windows process-metadata detection.
- Sequential build batches with native progress, cancellation, fast-exit handling,
  and Auto Redeploy suppression through the current deployment.
- Final-artifact fingerprinting, stable ZIP checks, watcher recovery after output
  directory replacement, and coordination across open projects.
- Versioned settings migration, detached persistence snapshots, remembered-source
  relink/forget actions, and listener cleanup when the plugin unloads.
- Sensitive JVM properties stored as local password-store references or environment
  references; owner-restricted argument files and bounded output redaction.
- A server-level `server.log` tab with UTF-8 tails, rotation/truncation recovery,
  Pause, Follow, literal filtering, Clear View, Reload Tail, and Open in Editor.
- IntelliJ application-queue dispatch around platform calls from Swing callbacks;
  filesystem, credential, discovery, and process work stays in background tasks.
- Three-OS regression CI, six-version Plugin Verifier matrix, real WildFly and
  native Maven smoke tests, original icons, rendered UI images, and a manual
  signing/release workflow. Historical ZIPs are removed from the source repository.

Upgrade notes: password references are local to the IDE's credential store. Native
run configurations reference global profiles by ID and require a local profile
selection when shared with another machine. Custom base/config/log directory
overrides must avoid spaces and shell metacharacters because the distribution
scripts parse them incorrectly; the default standalone directories under a
WildFly Home containing spaces are supported. See [troubleshooting](docs/TROUBLESHOOTING.md).

## 0.5.2 — imported development baseline

User-supplied source baseline with Maven/Gradle service discovery, multiple
selection, global sources and server awareness, artifact Auto Redeploy, external
deployments, deployment status/timestamps, browser context paths, profiles,
configurable debug ports, and JVM-option helpers. No per-service logging is added.
