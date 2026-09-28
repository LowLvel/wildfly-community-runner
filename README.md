# WildFly Community Runner

Build, deploy, and debug local WildFly applications from IntelliJ IDEA Community.
Supports Maven/Gradle, WAR/EAR/JAR deployments, automatic redeploy, and live server logs.
Local standalone mode only.

## Install

Requires **IntelliJ IDEA 2025.1–2026.2** with Java and Maven plugins enabled,
a local WildFly installation, and a JDK supported by your WildFly version.

1. [Download the plugin ZIP](https://github.com/LowLvel/wildfly-community-runner/releases)
   from the newest **Main build**. No GitHub login required.
2. In IntelliJ, open **Settings → Plugins → gear menu → Install Plugin from Disk**
   and select the ZIP without extracting it.

## Quick start

1. Open a trusted Maven or Gradle project and the **WildFly** tool window.
2. Choose **Add server…**, then select your WildFly installation and JDK.
3. Start the server, select your services, and click **Build and Deploy**.

Enable **Auto** to redeploy whenever a built archive changes. For Run/Debug or
debugger attachment, use **Run → Edit Configurations → WildFly**.

## Current limitations

Domain mode, remote deployment management, and per-service logs are not supported.
Auto Redeploy watches built archives; it does not rebuild on source edits.

## Documentation

[User guide](docs/USAGE.md) · [Troubleshooting](docs/TROUBLESHOOTING.md) ·
[Changelog](CHANGELOG.md) · [Build & contribute](CONTRIBUTING.md) ·
[Validation](docs/VALIDATION.md) · [Releasing](docs/RELEASING.md) · [Security](SECURITY.md)

[Apache-2.0](LICENSE). Independent community project; not affiliated with Red Hat or JetBrains.
