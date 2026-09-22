# Security and sensitive data

This plugin runs local build tools and WildFly with the permissions of the IDE
user. Only trust projects whose build scripts you intend to execute. The plugin
does not contain analytics or a telemetry endpoint. It connects to configured
server/debug endpoints and may invoke build tools that download dependencies.

Sensitive JVM-property names are protected through the IntelliJ Passwords store.
Settings retain `${secret:…}` references; `${env:VARIABLE}` can be used when an
application's sensitive property has a different name. Values resolve only for a
launch and are written to owner-restricted temporary Java argument files. These
files and in-memory redactors live for the execution, with cleanup on exit or
plugin unload. Failed Maven launches without a process handler release their
pending file at project close. Password-store preferences determine persistence.

The plugin redacts known values and sensitive `-D` assignments in its own activity,
WildFly native console, and live server log viewer. It does not rewrite server.log
on disk or control an application's, Maven's, or another plugin's logging. Treat
logs and screenshots as potentially sensitive. A local administrator or another
process with equivalent privileges is outside this protection boundary.

Do not publish real credentials, private JVM options, or exploit details in a
public issue. Use GitHub's **Report a vulnerability** option if the repository has
private reporting enabled. If it is unavailable, open a minimal issue requesting
a private contact method without including sensitive details. No response-time
or managed-service commitment is implied.

See [troubleshooting](docs/TROUBLESHOOTING.md) for locked stores, unavailable
environment variables, launcher encodings, and the supported local Maven runner.
