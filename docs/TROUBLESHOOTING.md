# Troubleshooting

## Server setup and startup

Select an extracted WildFly **Home**, containing `jboss-modules.jar` and `bin/`.
The plugin supports local standalone mode and the deployment scanner under the
selected XML. Custom scanner paths and named paths are supported. Check the
profile's scanner name, enabled state, and any unresolved expressions. Domain
mode remains outside the plugin's scope.

If the profile reports an occupied port or another configuration, check the HTTP
port and the base/configuration paths before starting. A listening socket alone
does not prove the process is WildFly. The plugin only offers a detected-process
Stop when it can match a unique local JVM and recheck its identity. On Windows,
local CIM access must be available. Unrecognized launcher argument files leave an
external process unverified; the distribution's `bin/jdk.serialFilter` is recognized.

WildFly's own standalone scripts split custom `jboss.server.base.dir`,
`jboss.server.config.dir`, and `jboss.server.log.dir` overrides incorrectly when
their values contain spaces or shell metacharacters. The plugin rejects these
overrides before launch. Use paths without those characters for overrides. A
WildFly Home containing spaces can use its default standalone directories.
Oracle TNS and other quoted ordinary JVM properties use a Java argument file and
can contain spaces. The plugin does not modify WildFly's distribution scripts.

Set **Java Home** to a JDK supported by your WildFly version. The plugin is built
with Java 21; the selected server JDK is a separate setting. Secret/quoted JVM
options use Java argument files and require Java 9 or newer. Runtime CI exercises
WildFly 41.0.1.Final with Java 21, not every historical WildFly/JDK combination.

## Build and redeploy

Maven uses IntelliJ's Maven settings and bundled runner. If it cannot select Java,
check **Settings → Build Tools → Maven → Runner → JRE**. Sensitive JVM options
require the standard local runner. If the experimental `maven.use.scripts`
registry option is enabled, disable it before using this feature. Remote Maven
targets are outside the protected JVM-option workflow.

Gradle uses the nearest wrapper above the selected build file and otherwise uses
system Gradle. Check the wrapper executable/permissions and tasks in Service
Details. A build with secret properties uses a single-use daemon. Keep sensitive
`-D` values in the JVM options field, not the goals/tasks or command arguments.

If Auto Redeploy does not run, check that the project is trusted, **Auto** is
checked, WildFly is running, and the final WAR/EAR/JAR output was rebuilt. Editing
a source file alone is deliberately insufficient. The archive must settle and be
a readable ZIP. If an unchanged artifact previously failed, use **Redeploy** or
rebuild it; failures are not retried indefinitely. Activity and `server.log` explain
scanner failures. Do not delete the source archive to clear a failed deployment.

**Cancel Build** cancels the owned process and skips queued services. Once a
deployment has been submitted, cancellation waits for scanner confirmation before
releasing Auto Redeploy suppression. Closing the tool window keeps the batch
running; closing its project cancels it.

If multiple archives match, set **Artifact override**; timestamps are not used to
choose an application. For reactor builds, set **Root build file** on each selected
module with the same goals/options. Set **Build JAVA_HOME** if the build JDK must
differ from the server JDK or IDE defaults. New services use `package`/`build`,
keep tests enabled, and start with Auto Redeploy off.

A leftover `.deployed` marker is historical when the server is stopped. **Unknown**
means the selected process or scanner configuration is unverified. A deployment
timeout may still finish in WildFly; inspect `server.log` before retrying. Adjust
startup/deployment timeouts in the server profile when necessary. Expected HTTP
host/port must match WildFly socket bindings; editing them does not change the
server's bindings. Runtime-only management changes may differ from saved XML.

If a shared configuration cannot find an application, reload **Shared Project
Settings** from `.wildfly/services.xml` and check its project-relative build path.
Missing profiles require a corresponding local profile with a unique matching
name or an explicit selection. An imported definition never supplies credentials,
local JDKs, or an automatic redeploy opt-in.

## Debugging and shared processes

Use **Local Server → Debug** to start with JDWP. Use **Attach Debugger → Debug**
for an existing debug-enabled server. The profile's debug port must match the JVM.
Stopping an Attach session disconnects without terminating WildFly. Stopping a
Local Server session terminates only a process that session started; a reused
session disconnects and leaves the shared server available in other projects.

If a profile uses an existing managed server's home/base/configuration, it refers
to the same process even under a different profile name. To run another instance,
choose a separate base directory and non-conflicting socket bindings/ports.

## Credentials and output

If a saved reference is unavailable, unlock/configure **Settings → Appearance &
Behavior → System Settings → Passwords**, then re-enter and save the JVM property.
Environment references read the IDE's environment; set the variable before
starting the IDE. Copying profile XML does not copy the local password store.

Argument files use Java's native launcher encoding. A non-UTF-8 Windows locale
may not represent every character. Unsupported characters fail before launching;
use a UTF-8 system locale or the application's credential-file feature. Never
work around this by pasting a credential into public build arguments or logs.

The server log tab shows a bounded UTF-8 tail, with complete lines only. For older
content, very long lines, or another encoding, use **Open in Editor**. Pause and
Clear View do not change the file. Third-party application/build output may still
contain credentials; inspect attachments before reporting an issue.

## IDE errors

If an IDE reports **Access is allowed from write thread only**, include the full
IDE build number, action that triggered it, and a sanitized stack trace. Platform
calls from plugin Swing actions are dispatched through IntelliJ's application
queue; do not disable the IDE's threading checks as a workaround.

Use **Remembered Sources → Edit / Relink** after moving a project. **Forget Selected**
removes only the remembered association. Missing/unmounted paths are never deleted
automatically. After unloading/reloading the plugin, stop and relaunch a server
through it before requesting an in-process restart that needs a released private
argument file.
