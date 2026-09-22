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

`MarketplaceScreenshotsTest` renders the actual production panels with a labelled
sample workspace in the IntelliJ test application's default theme. Its 1280×800
PNG output is retained as the `marketplace-screenshots` CI artifact. These are
component screenshots, not a fabricated full IDE window or proof of a live server.
The sample log and scanner markers are fixture data; real-server behavior is
covered separately by `WildFlyRuntimeTest`.

Review images visually before copying them into `docs/images/` or uploading them
to Marketplace Media. Keep the same aspect ratio, avoid personal paths and real
credentials, and use the original image files. For full-IDE screenshots, install
the candidate in a clean IDE profile and follow the same sample workflow.

Suggested captions: **Services and external deployments** and **Read server.log
without leaving the tool window**. See the official
[listing guidance](https://plugins.jetbrains.com/docs/marketplace/best-practices-for-listing.html)
and [release procedure](RELEASING.md).
