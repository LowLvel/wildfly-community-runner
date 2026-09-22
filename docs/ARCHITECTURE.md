# Architecture and preservation boundaries

The 0.5.2 baseline is a Java 21 plugin compiled against IDEA Community 2025.1.7.
It depends on the public Java and bundled Maven plugins; Gradle builds use the
service's wrapper (searched in ancestor directories), then system Gradle.

## State and ownership

- `WildFlyApplicationSettings` persists global server profiles, the last profile,
  and the source registry used by external deployments across projects.
- `WildFlyProjectSettings` persists this project's service list and selections.
- `ProjectSetupService` owns first-use discovery and serializes saved watcher
  configuration independently of tool-window lifetime. `ProjectSetupActivity`
  enters through the public project-startup API; Safe Mode blocks automatic work.
- `WildFlyProcessService` owns the application-wide managed process registry and
  inspects externally running servers.
- `ArtifactAutoDeployService` is a disposable project service with one blocking
  WatchService thread and one scheduled worker. It watches final artifacts, not
  source files. Generation-scoped slots retain changes during in-flight work and
  expire queued work when settings change. Output-directory recovery rescans only
  deployable outputs. `ArtifactFingerprint` checks stable archive content.
- `DeploymentCoordinator` is application-wide: counted source suppression leases,
  shared automatic-deployment ownership, bounded successful-fingerprint caches,
  and per-target scanner locks coordinate multiple project windows. Other targets
  continue independently while a request waits for WildFly. Copy validation preserves the previous
  scanner artifact when the source changes mid-copy.

## Operations

`BuildService` routes to the Maven or Gradle implementation. Discovery combines
imported modules with a bounded recursive filesystem scan. `ArtifactLocator`
resolves a configured override or a final WAR/EAR/JAR in the output directory.
`DeploymentScannerService` copies artifacts via temporary files and coordinates
WildFly scanner markers. `DebugAttachService` uses the Java remote debugger.

External process detection requires the standalone entry point and matching home,
server base, and configuration. Background filesystem identity checks accommodate
canonical paths such as macOS `/var` and `/private/var`. Windows CIM command rows
also recognize the implicit `.exe` suffix used by the vendor's Java launcher;
PID and creation time are checked before a process can be stopped.

`BuildLifecycleService` owns one cancellable batch per project. `BuildBatch`
snapshots the selection, sequences build/deploy completion, and holds watcher
suppression through deployment. `BuildOperation` owns exactly one build process,
including late process creation after cancellation. Maven subscribes to public
execution events filtered by the exact environment before `startNotify`, with
callbacks and exit-code checks as fallbacks. Native progress and the tool window
both cancel the same operation. Closing a tool window does not own the batch.

The `run` package registers native Local Server and Attach Debugger configuration
factories. Configurations persist a global server-profile ID, keeping JVM options
out of shared run-configuration XML. Standard Java Run/Debug runners bind an
asynchronous session handler to the global process service. Stop owns only a
process started by that session; observing or detaching another project's server
does not kill it. The disposable project session service detaches listeners on
project close and plugin unload.

`WildFlyManagerPanel` presents server controls, a multi-selection project table,
a separate external-deployments table, and activity logs. It delegates build
batches to the project service and owns several asynchronous refreshes. Changes
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

### Settings and global registry lifetime

`WildFlyApplicationSettings` and `WildFlyProjectSettings` own their mutable state
behind synchronized snapshot/update boundaries. Persistence receives detached
copies; UI tables render a stable snapshot and retain selected IDs across refresh.
`SettingsMigration` performs only pure normalization, clears transferred obsolete
fields, repairs missing/duplicate IDs and invalid loaded ports, and records the
schema version. Loading settings does not access files or the credential store.

Global server and source change events are separate. `ProjectSetupService`
reconciles selected servers and reconfigures artifact watches when server profiles
change, even without a tool window. Remembering a source does not reset watches.
`RememberedSourcesDialog` checks availability in the background and provides
explicit forget/relink operations; unavailable paths remain recoverable.
`WildFlyProcessService.dispose` removes plugin listeners and detaches platform
process handlers, including launches completing during disposal, without killing
application-wide servers.

### Sensitive options

`JvmSecrets` accesses public `PasswordSafe` APIs only on background threads. A
`Protection` transaction creates fresh credential IDs and removes uncommitted
entries; existing shared references are never deleted by profile removal.
`SensitiveSettingsMigration` saves first and compares the original fields before
replacing them, so concurrent user edits win. `PendingSecrets` rolls back a dialog
save if it is cancelled or changed before its application-queue callback runs.

`PrivateJvmOptions` secures its directory and file before writing JVM properties,
preserves nonsensitive options, and escapes Java argument-file syntax. A reporting
native-charset encoder rejects values that the system launcher cannot represent.
The public `RunConfigurationExtension` refreshes Maven arguments for each execution;
`MavenSecretSessions` binds cleanup to the unique file path in the process command,
which also supports concurrent reruns without changing serialized settings. Pending
files from launches without a handler are released at project disposal. Gradle gets
`org.gradle.jvmargs` through a second client argument file passed in `GRADLE_OPTS`,
avoiding nested cmd.exe quoting, and uses a single-use daemon. Earlier CLI and
environment JVM overrides cannot replace the service profile's configured options. WildFly keeps nonsensitive
server identity properties visible to its launcher and the process detector.
Quoted ordinary WildFly properties join the private file in their original order,
including other occurrences of the same property key. `JDK_JAVA_OPTIONS` carries
the file reference directly to Java, avoiding distribution-script quote parsing.
Custom base/config/log overrides with spaces or shell metacharacters are rejected
before launch; the vendor scripts cannot reliably parse them. Detection recognizes
the distribution's fixed `bin/jdk.serialFilter` file and rejects arbitrary launcher
argument files whose entry point cannot be established.
Process cleanup holds the already-created credential service instead of looking
up services during container disposal. `SecretRedactor.Lines` buffers bounded
stdout/stderr lines so a secret split across process output chunks is not exposed.

## Server log viewer

`ServerLogTailer` owns a bounded byte cursor, UTF-8 decoder, partial-line buffer, and
redacted text tail on one worker. Each poll opens and closes its channel. File
identity, size, prefix and cursor-anchor checks recover from rotation and
truncate/regrow operations. Metadata is checked again before consuming a read.
The incomplete line survives Clear View so its redaction context cannot be lost.

`ServerLogPanel` owns the read worker for its tool-window lifetime. Reads occur only
while the tab is visible, with pause and explicit reload controls. Profile and view
revisions expire queued callbacks after selection changes, clearing, pausing, hiding,
or disposal. Swing rendering and filtering use cached strings; the editor action
crosses `IdeUi` before entering platform APIs. The manager's application-wide server
selection remains the source of the log path; no service-specific log model exists.
