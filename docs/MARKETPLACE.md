# Marketplace listing material

The description and change notes are embedded in `META-INF/plugin.xml`. The
distribution includes original 40×40 light/dark SVG logos and 16×16 tool-window
icons. They do not use the WildFly or JetBrains brand logos.

| Field | Value |
| --- | --- |
| Name | WildFly Community Runner |
| Plugin ID | `io.github.wildflycommunityrunner` |
| Vendor text | Open Source Contributors |
| Homepage / source | https://github.com/LowLvel/wildfly-community-runner |
| Issue tracker | https://github.com/LowLvel/wildfly-community-runner/issues |
| Documentation | https://github.com/LowLvel/wildfly-community-runner/blob/main/README.md |
| License | Apache-2.0; link the repository's `LICENSE` |
| Compatibility | IntelliJ IDEA 2025.1–2026.2, requiring platform, Java, and Maven modules |
| Scope | Local standalone WildFly; independent community project |

Do not claim Red Hat/JetBrains endorsement or coverage of every historical
WildFly/JDK combination. Use the exact evidence in [VALIDATION.md](VALIDATION.md).
Account contact details and ownership are supplied by the maintainer in Marketplace.

## Getting started text

1. Install the plugin and open a trusted Maven or Gradle project.
2. Open the **WildFly** tool window. Nested services are discovered on first setup.
3. Choose **Add server…** and select your local WildFly installation. If
   `WILDFLY_HOME` or `JBOSS_HOME` is already valid, review the suggested profile.
4. Start the server, select one or more services, and choose **Build and Deploy**.
5. Enable **Auto** to redeploy when a final WAR/EAR/JAR is rebuilt. Use native
   **Run → Edit Configurations → WildFly** for Run, Debug, or Attach configurations.

## Images

The existing files under `docs/images/` and the `marketplace-screenshots` CI
artifact are component test renders with synthetic data. They are not captures
of a running IDE and must not be used as product or Marketplace screenshots.

Real IDE screenshots are still needed. Install the plugin in a normal IDE,
open a sample project, and connect to a local WildFly server. Capture the actual
services and log views, excluding private paths, credentials, and application data.
Keep the original aspect ratio and record the IDE and plugin versions.

Suggested captions: **Services and external deployments** and **Read server.log
without leaving the tool window**. See the official
[listing guidance](https://plugins.jetbrains.com/docs/marketplace/best-practices-for-listing.html)
and [release procedure](RELEASING.md).
