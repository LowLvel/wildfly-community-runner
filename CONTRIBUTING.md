# Contributing

Use JDK 21 and the checked-in Gradle wrapper. Java 21 bytecode supports the IDEA
2025.1 baseline; changing your installed SDK does not upgrade the IDE's runtime.

```sh
./gradlew verifyPluginProjectConfiguration test buildPlugin verifyPluginStructure
./gradlew verifyPlugin
python3 -m unittest discover -s scripts -p 'test_*.py'
```

Use `gradlew.bat` on Windows. `./gradlew runIde` opens an isolated development IDE.
The regular tests run IntelliJ platform fixtures and actual Java/Gradle/Maven
processes. No personal server or credential is needed. The full WildFly test is
enabled only when `WILDFLY_TEST_HOME` is set; see [validation](docs/VALIDATION.md).

Read [architecture](docs/ARCHITECTURE.md) before changing ownership or threading.
Keep fixes focused and preserve nested Maven/Gradle discovery, multi-selection,
the global registry, and separate external deployments. Auto Redeploy must observe
final archives rather than source files. There is no per-service log feature.

Use public IntelliJ Platform APIs. Raw Swing callbacks must enter `IdeUi` before
calling platform APIs that need EDT/write intent. Keep blocking filesystem,
process, network, and password-store operations off EDT. Queued callbacks need
disposal and generation checks where their owner can change.

Include tests for the behavior being changed, particularly failure, cancellation,
and cross-project ownership. Run the build and relevant tests after each major
change. All six compatibility jobs and **Validation gate** must pass before a
release. Check deprecation/experimental warnings rather than suppressing them
indiscriminately. Avoid internal APIs even when they appear to work on one IDE.

Pull requests should describe the user-visible trigger, resulting behavior, and
validation evidence. Do not commit generated plugin/source ZIPs, build caches,
credentials, private keys, or private application logs. Screenshots use the sample
workspace renderer and are reviewed before inclusion in documentation.

For ordinary bugs, include the IDE build, plugin/JDK/WildFly versions, operating
system, and a minimal reproduction in a GitHub issue. Remove credentials and
private paths from attachments. For sensitive reports, follow [SECURITY.md](SECURITY.md).
