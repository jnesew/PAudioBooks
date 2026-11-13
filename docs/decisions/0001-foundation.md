# 0001: Native foundation and dependency boundary

This decision records the native foundation and required dependency review.
The [current architecture](../ARCHITECTURE.md) and later decisions define runtime
behavior; this document is not an acceptance report.

## Context and choice

Use one `:app` module. The user requested
GitHub provenance for its identity: the origin is `jnesew/PAudioBooks`, so the
application ID and namespace are `io.github.jnesew.paudiobooks`, with working
display name **PAudioBooks**. No domain ownership or publication is implied.

Use JDK 17, AGP 9.4.0, Gradle 9.6.0, AGP's built-in Kotlin 2.2.10 and the matching
Compose compiler plugin, Compose BOM 2026.09.00, Activity 1.13.0, Lifecycle 2.11.0,
Room 2.8.5 with KSP 2.3.12, DataStore 1.3.0 and coroutines 1.10.2. The selected
Compose artifacts require compile SDK 37. Compile and target SDK are 37; minSdk
remains 26. Android build tools are 36.0.0 as selected
by this AGP. These are compatible stable pins, not a claim that every pin is the
newest upstream version. Review upgrades deliberately.

Sources: [AGP 9.4](https://developer.android.com/build/releases/agp-9-4-0-release-notes),
[built-in Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin),
[Compose BOM](https://developer.android.com/develop/ui/compose/bom/bom-mapping),
[Activity](https://developer.android.com/jetpack/androidx/releases/activity),
[Lifecycle](https://developer.android.com/jetpack/androidx/releases/lifecycle),
[Room](https://developer.android.com/jetpack/androidx/releases/room),
[DataStore](https://developer.android.com/jetpack/androidx/releases/datastore),
[Android 17 release](https://developer.android.com/blog/posts/android-17-is-here).

Framework UI/preferences/SQLite were considered; Compose, DataStore and Room remain
the intentional choices in the architecture. Room provides library persistence and
non-destructive migrations, with schemas exported under `app/schemas/`. Later decisions extend the runtime stack.

## Resolved runtime review

The complete reviewed module set, including BOM constraints and multiplatform
selectors, is checked in at
[`tools/verification/runtime-modules.txt`](../../tools/verification/runtime-modules.txt).
The release dependency report resolves Kotlin stdlib to **2.2.20**, coroutines to
**1.10.2**, and kotlinx serialization core to **1.7.3**. AndroidX also brings these
support libraries; they are accepted as required transitives, not new app features:

| Upstream / artifact | Purpose, license and transitive boundary | Maintenance / alternative |
| --- | --- | --- |
| [Okio](https://github.com/square/okio), 3.17.0 | DataStore local I/O; Apache-2.0; JVM variant depends on Kotlin stdlib; includes unused socket helpers, no permission contribution | Maintained by Square; avoid direct usage. Replacing DataStore or excluding required I/O is disproportionate |
| [atomicfu](https://github.com/Kotlin/kotlinx-atomicfu), 0.28.0 | DataStore atomic operations; Apache-2.0; JVM variant/stdlib | Kotlin upstream; do not substitute atomics inside upstream code |
| [Kotlin serialization](https://github.com/Kotlin/kotlinx.serialization), core 1.7.3 | Compose runtime saved/retained state; Apache-2.0; stdlib dependency | Kotlin upstream; no extra JSON or network module selected |
| [JetBrains annotations](https://github.com/JetBrains/java-annotations), 23.0.0 | JVM nullability/contracts; Apache-2.0; no additional runtime graph | JetBrains upstream; retain required annotation artifacts |
| [JSpecify](https://github.com/jspecify/jspecify), 1.0.0 | AndroidX nullness annotations; Apache-2.0; no additional runtime graph | JSpecify upstream; retain required annotation artifacts |
| [Guava listenablefuture](https://github.com/google/guava), 1.0 | AndroidX concurrent future interface; Apache-2.0; no full Guava or GMS runtime | Google upstream; framework-only replacement would mean replacing AndroidX internals |

These JVM libraries declare no Android components or permissions. No HTTP client,
GMS/Firebase, analytics, ads, or native codec runtime appears in the resolved tree.
AndroidX profile installation, local tracing and emoji initialization are local
library behavior, not remote telemetry. Runtime permissions/components are governed
by [the security contract](../SECURITY_AND_PERMISSIONS.md) and enforced by the
release checker; inspect all merged contributions when dependencies change.

Stable pins live in the version catalog; Gradle strictly verifies SHA-256 metadata
and artifact checksums and the wrapper distribution has its own pinned checksum.
Initial artifact hashes were generated from resolved official repositories. They
detect later changes but do **not** independently authenticate the first download;
independent provenance review remains a release gate. Do not regenerate hashes
automatically in CI or bypass mismatches. The module-set check requires explicit
review on additions/removals; it does not replace code/license/security review.

## Backup and transfer

Set allowBackup and fullBackupContent false. Extraction rules exclude every
documented storage domain for cloud, Android device transfer and iOS cross-platform
transfer. The [official backup guide](https://developer.android.com/identity/data/autobackup)
describes the cross-platform section from API 36.1 and requires matching parameters
for an actual counterpart app. This app has no counterpart and provides exclusions
only, with no fabricated bundle/team identifiers.

Use public manifest/extraction rules; never copy private AOSP classes into the
app. Actual backup/transfer behavior requires representative device verification.

## Verification

Use [Building](../BUILDING.md) and [Testing](../TESTING.md) for the wrapper, manifest,
runtime dependency, and schema checks. Preserve strict verification metadata and
review transitive changes. A successful build or static dependency review does
not establish parser, playback, device compatibility, or release readiness.
