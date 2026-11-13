# 0008: Transfer bounds and dependency security updates

## Transfer boundary

Progress transfer uses the public Android `JsonReader` and strict UTF-8 decoding.
The 10 MiB byte cap applies to the entire input, including trailing data. Books,
tracks and bookmarks are counted before retaining each next record. The reader
also bounds nesting (32), total lexical tokens (250,000), encoded string tokens
(24,578 characters), unquoted tokens (128). Schema objects allow 64 unique field names of
at most 128 characters. Display fields remain 512 characters and notes 4,096.
Unknown values are skipped without constructing object graphs, within the same
lexical budgets. Duplicate known-object keys, invalid types/integers and trailing
content are rejected. Versions 1/2 and explicit transactional application remain.
Export validates against these same aggregate bounds before writing a destination.
No raw database, paths, grants or media are transferred.

A process-owned bounded transfer worker handles provider open/read/write/flush/
close off the main thread, with a 30-second deadline and explicit UI cancellation.
There is one admitted operation until its actual worker and cancellation/close
cleanup return. Cancellation and close have separate single workers so a blocked
provider cancellation callback cannot prevent closing streams. Uncooperative
providers retain admission and retries fail instead of adding threads. Late-opened
resources remain owned and close once. JSON and database work do not run while
holding provider streams. Export preparation completes before opening/truncating
the destination; cancellation/failure after writing can leave an incomplete file
and is reported as such. The existing crash-recovery journal remains.

Public API basis: [JsonReader](https://developer.android.com/reference/android/util/JsonReader),
[ContentResolver.openAssetFileDescriptor](https://developer.android.com/reference/android/content/ContentResolver#openAssetFileDescriptor(android.net.Uri,java.lang.String,android.os.CancellationSignal))
and [CancellationSignal](https://developer.android.com/reference/android/os/CancellationSignal).
A coroutine timeout by itself cannot stop blocking provider code.

## Reviewed dependency updates

The existing plugins and runtime stack remain. Upgrade the wrapper to Gradle
9.9.1, including its generated scripts/JAR and official distribution checksum.
This addresses the [Gradle 9.9.1 security release](https://docs.gradle.org/9.9.1/release-notes.html),
including catalog-code generation and daemon registry/authentication issues in
the old 9.6.0 distribution. Do not replace libraries inside a Gradle distribution;
use the supported patched distribution. Kotlin 2.4.21 is the stable compiler patch
aligned with that distribution's embedded Kotlin DSL version. Stable pins in the version catalog
update required transitive dependencies in the plugin, lint, processor and app/test
scopes; no new runtime feature or permission is added. Settings-stage buildscript rules must
apply before plugin dependencies resolve; ordinary configuration rules cover lint,
KSP and app/test classpaths. These are targeted module replacements, not a blanket
version override by group. Do not remove them until the selected upstream graph
provides reviewed replacements.

| Component | Selected patch | Purpose / upstream / license | Advisory / alternative |
| --- | --- | --- | --- |
| Kotlin and Compose compiler plugin | 2.4.21 | Existing built-in Kotlin compiler/build integration; JetBrains, Apache-2.0 | [CVE-2026-53914](https://github.com/advisories/GHSA-r937-wjx7-w2jp); stable release containing the cache-deserialization fix, instead of a prerelease or disabling integrity checks |
| Guava | 33.7.2-android for app, 33.7.2-jre for tooling | Existing Media3 futures/collections and build-tool support; Google, Apache-2.0; requires failureaccess 1.0.3 and annotation/empty-future support | [CVE-2026-102554](https://github.com/google/guava/security/advisories/GHSA-xxph-c9ww-hj94); preserve required APIs rather than excluding Guava |
| Commons Lang | 3.18.0 | Existing AGP/lint string utilities; Apache, Apache-2.0; build only | [CVE-2025-48924](https://github.com/advisories/GHSA-j288-q9x7-2f5v); required upstream utility, no APK contribution |
| HttpClient | 4.5.14 | Existing AGP/lint HTTP helper; Apache, Apache-2.0; build only; keeps 4.x API and required httpcore/commons transitives | [CVE-2020-13956](https://github.com/advisories/GHSA-7r82-7xv7-xcpj); avoid incompatible migration to 5.x |
| jose4j | 0.9.6 | Existing AGP token/crypto tooling; Bitbucket b_c/jose4j, Apache-2.0; build only | [CVE-2024-29371](https://github.com/advisories/GHSA-3677-xxcr-wjqv); upstream decompression bound |
| Bouncy Castle bcprov/bcpkix/bcutil | 1.85 | Existing AGP/lint signing/certificate tooling; Legion of the Bouncy Castle, MIT; aligned build-only artifacts | [CVE-2026-5588](https://github.com/bcgit/bc-java/wiki/CVE-2026-5588), [CVE-2026-8763](https://github.com/bcgit/bc-java/wiki/CVE-2026-8763), [CVE-2026-13506](https://github.com/bcgit/bc-java/wiki/CVE-2026-13506); preserve signing APIs and align their shared internals |
| JDOM | 2.0.6.1 | Existing AGP XML tooling; hunterhacker/jdom, JDOM license; build only | [CVE-2021-33813](https://github.com/hunterhacker/jdom/releases/tag/JDOM-2.0.6.1); patched compatible XML parser defaults |

Upstream release references: [Kotlin](https://github.com/JetBrains/kotlin/releases/tag/v2.4.21),
[Guava](https://github.com/google/guava/releases/tag/v33.7.2),
[Commons Lang](https://commons.apache.org/proper/commons-lang/changes.html),
[HttpClient](https://hc.apache.org/httpcomponents-client-4.5.x/current/httpclient/dependency-info.html),
[jose4j](https://bitbucket.org/b_c/jose4j/wiki/Home),
[Bouncy Castle](https://www.bouncycastle.org/download/bouncy-castle-java/).
All are maintained upstream projects. Removing or replacing the Android toolchain
is disproportionate to these compatible security updates. Build dependencies can
network as part of a build; their existence does not give networking to the app.

## Integrity and ongoing review

Resolve only from the existing official repositories. Review added metadata and
artifact hashes against their official repository publications and available
checksums; never replace an existing hash merely to make CI pass. Parent POMs and
Gradle module metadata are verified too. A missing checksum can be exposed by a
fresh cache even when a warm cache passes; fresh-cache validation complements the
normal build. A transitive prerelease BOM used as build metadata is not an app
runtime feature or permission.

Inventory all resolved buildscript and resolvable app configurations, including
lint, KSP and tests, for advisory review. Version-matched advisory results are not
proof of reachability; document scopes and reassess after input changes. Keep
exact package queries, sources, artifact hashes, results and device limits in local
evidence, not this reusable decision. Runtime module/license inventories and the
merged manifest must agree with actual resolution. No source publication or APK
release is authorized by this decision.

## Remaining Gradle vendor advisory

The official Gradle 9.9.1 distribution still contains Guava 33.4.6-jre, which
matches CVE-2026-102554. Project dependency substitution cannot replace classes in
Gradle's own parent classloader. No supported newer stable distribution was found
during this review. The bundled-library advisory query is retained with local
evidence, including one Gradle-specific report component without Maven metadata. Keep the official distribution intact and track its upstream
update; do not hand-patch its jars or misrepresent the full toolchain as an empty
advisory scan. The project/APK and plugin/lint/processor Guava selections above
are patched independently. The build has no configured remote build cache and
must not deserialize untrusted cache objects; keep Gradle user-home/cache access
restricted. This is a bounded reachability assessment, not proof that all vendor
code is unaffected. Distribution/OS/JDK review remains separate from the resolved
project dependency check.

## CI Action dependency boundary

The workflow pins the official stable, MIT-licensed `actions/checkout` 7.0.1 commit and disables
credential persistence because later build steps need no authenticated Git access.
It uses Node 24 on the hosted Ubuntu runner; workflow permissions remain read-only
and triggers remain `push`/`pull_request`. The upgrade replaces affected Octokit,
busboy, UUID and older Undici selections in the previous 4.2.2 bundle. See the
[official release](https://github.com/actions/checkout/releases/tag/v7.0.1),
[runtime definition](https://github.com/actions/checkout/blob/3d3c42e5aac5ba805825da76410c181273ba90b1/action.yml)
and [lockfile](https://github.com/actions/checkout/blob/3d3c42e5aac5ba805825da76410c181273ba90b1/package-lock.json).

That latest stable Action still bundles Undici 6.27.0 with matched advisories,
including conditional [TLS callback validation](https://github.com/advisories/GHSA-2m5v-pch6-74hx)
and [excessive content-encoding denial of service](https://github.com/advisories/GHSA-xjj6-f33q-929c).
Do not edit its precompiled distribution or assume an npm override in this Android
repository patches a downloaded Action. Track a supported upstream release. The
workflow supplies no custom TLS callbacks, WebSocket/EventSource inputs or retry
interceptor configuration; network responses and runner dependencies still require
trust in GitHub/upstream infrastructure. This is a vendor residual, not a claim
that every matching bundled advisory is unreachable. Keep the complete dated
package/advisory list locally and verify the hosted run independently.
