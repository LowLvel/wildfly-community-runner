# Architecture and preservation boundaries

The 0.5.2 baseline is a Java 21 plugin compiled against IDEA Community 2025.1.7.
It depends on the public Java and bundled Maven plugins; Gradle builds use the
service's wrapper (searched in ancestor directories), then system Gradle.

## State and ownership

- `WildFlyApplicationSettings` persists global server profiles, the last profile,
  and the source registry used by external deployments across projects.
- `WildFlyProjectSettings` persists this project's service list and selections.
- `WildFlyProcessService` owns the application-wide managed process registry and
  inspects externally running servers.
- `ArtifactAutoDeployService` is a disposable project service with one blocking
  WatchService thread and one scheduled worker. It watches final artifacts, not
  source files. Explicit build modes suppress automatic deployment.

## Operations

`BuildService` routes to the Maven or Gradle implementation. Discovery combines
imported modules with a bounded recursive filesystem scan. `ArtifactLocator`
resolves a configured override or a final WAR/EAR/JAR in the output directory.
`DeploymentScannerService` copies artifacts via temporary files and coordinates
WildFly scanner markers. `DebugAttachService` uses the Java remote debugger.

The `run` package registers native Local Server and Attach Debugger configuration
factories. Configurations persist a global server-profile ID, keeping JVM options
out of shared run-configuration XML. Standard Java Run/Debug runners bind an
asynchronous session handler to the global process service. Stop owns only a
process started by that session; observing or detaching another project's server
does not kill it. The disposable project session service detaches listeners on
project close and plugin unload.

`WildFlyManagerPanel` presents server controls, a multi-selection project table,
a separate external-deployments table, and activity logs. It currently also
orchestrates build/deploy sequences and several asynchronous refreshes. Changes
should extract focused responsibilities when needed, preserving these flows.

## Threading boundary

Filesystem scans, process inspection, socket connections, artifact copies, and
log reads belong on background threads. Project-model reads require a read
action. Document saving, run-configuration creation, editor opening, and other
platform model operations must enter through the IntelliJ application queue,
with disposal checks and the appropriate modality. A Swing callback being on
the EDT does not establish write-intent access in IDEA 2025.1+.

`IdeUi` is the queue boundary for raw Swing entry points and worker completions.
Modal validation captures its originating modality before background work.
The disposable manager panel renders deployment snapshots loaded in the
background; renderers do not query disk. A weak activity sink and bounded text
buffer keep application-wide processes from retaining closed tool windows.
`PluginNotifications` reports operation failures in the IDE notification group;
cancellation and interruption retain their control-flow semantics.

Reference: [IntelliJ threading model](https://plugins.jetbrains.com/docs/intellij/threading-model.html).

## Behavior to retain

Maven and Gradle; nested discovery; multi-selection; artifact-only Auto Redeploy;
separate external deployments; global source/server awareness; Start, Debug,
Attach, Stop; the four deployment states and deployment-time hover; browser
context overrides; configuration/home/deployment/server-log shortcuts; multiple
profiles; configurable debug ports; Oracle TNS and JVM helpers. There is no
per-service log feature.
