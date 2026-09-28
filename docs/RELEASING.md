# Releasing

The repository builds an unsigned candidate on pull requests. Successful pushes
to `main` automatically publish that candidate as a public GitHub prerelease.
`gradle.properties` is the source of the plugin version. Review
[validation](VALIDATION.md), the changelog, metadata, and screenshots before
merging a candidate into `main`.

## Public ZIP downloads

After **Build and verify** succeeds for a push to `main`, **Publish public ZIP**
downloads `plugin-candidate` from that exact run, checks its plugin ID and version,
and publishes the ZIP plus `SHA256SUMS` on the
[Releases page](https://github.com/LowLvel/wildfly-community-runner/releases).
Release assets can be downloaded without signing in to GitHub.

Each prerelease uses a `main-<full commit SHA>` tag pointing to the validated
commit. The workflow uploads assets to a draft before making it public; reruns
can resume an incomplete draft and leave an already published release unchanged.
Pull requests, failed builds, and manual validation runs do not publish releases.
The ZIP is unsigned, and the workflow needs only the repository's built-in
`GITHUB_TOKEN`. It does not rebuild the plugin or require Marketplace secrets.

## Repository setup

Configure branch protection to require **Validation gate**. Create a GitHub
environment named `marketplace`, restrict it to `main`, and add an appropriate
maintainer review rule. Configure these environment secrets:

| Secret | Purpose |
| --- | --- |
| `CERTIFICATE_CHAIN` | Maintainer's PEM certificate chain |
| `PRIVATE_KEY` | Matching PEM private key |
| `PRIVATE_KEY_PASSWORD` | Key password, when encrypted |
| `PUBLISH_TOKEN` | JetBrains Marketplace token, needed only for an update upload |

Generate and manage the maintainer's signing identity using the
[JetBrains signing instructions](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html).
Never commit private keys. Normal CI signs with a disposable one-day certificate
to exercise `signPlugin` and `verifyPluginSignature`; that signed test archive is
not a maintainer release and is not uploaded as the installable candidate.

## Prepare a signed candidate

Run **Prepare or publish Marketplace update** manually from `main`, leaving
**action = prepare**. The workflow:

1. Runs the same Linux/Windows/macOS tests, real runtime fixtures, structure checks,
   signature smoke test, and all six Plugin Verifier jobs.
2. Waits for the `marketplace` environment's configured protections.
3. Downloads the unsigned candidate from that exact validation run and checks its
   plugin ID and version against the checked-out source.
4. Signs those bytes and verifies the signature. No replacement build is signed.
   Verification and publishing both select the signed output explicitly, including
   when Gradle considers the signing task up to date.
5. Uploads unsigned/signed ZIPs, `SHA256SUMS`, and a commit/version manifest in
   `signed-marketplace-candidate`. It does not upload to Marketplace in prepare mode.

The release job disables Gradle caches and configuration caching while handling
signing material. The publishing token is provided only to the explicit upload
step. Release execution is serialized; a later request does not cancel an upload.

## First Marketplace submission

JetBrains requires the initial plugin upload through the
[Marketplace web interface](https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html).
Use the reviewed signed candidate and the material in [MARKETPLACE.md](MARKETPLACE.md).
The maintainer must supply the account/vendor identity, accept applicable terms,
select appropriate available tags, and complete JetBrains review. These account
actions and credentials are not supplied by this repository.

After a listing exists, **action = publish-update** runs the complete validation
and signing path, then explicitly invokes `publishPlugin` for the default channel.
Use a new version for each published update. The Marketplace workflow does not
merge a PR or change the listing's screenshots automatically. Public GitHub
prereleases are handled separately as described above.

For a rollback, keep the earlier known-good ZIP and release evidence. Test a fixed
version with a higher version number before uploading it; do not reuse a published
version or upload a different artifact under an old checksum.

## Final interactive check

Before the first submission, install the candidate from disk into a normal IDE
profile and check native debugger breakpoint/source navigation, the profile and
service dialogs, multi-selection, project close/reopen, and theme/HiDPI rendering.
Runtime CI covers native run state, JDWP attach/disconnect, HTTP and deployment
behavior; it does not automate a human clicking through the debugger UI. Retain
the exact tested IDE/WildFly/JDK versions with the release evidence.
