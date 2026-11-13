# 0003: Local media runtime and system integration

Selected for implementation. Device acceptance remains separate.

## Selected runtime

Add Media3 ExoPlayer, Session and Inspector **1.11.1**, all pinned together in the
version catalog. These implement the already selected playback/metadata stack.
Add Lifecycle ViewModel Compose **2.11.0** for operation ownership across Activity
recreation. No network, streaming, casting or native codec module is selected.

The current release dependency report adds Media3 common/container/database/
datasource/decoder/extractor and the three selected modules; AndroidX ExifInterface
**1.3.6**, Lifecycle Service **2.11.0**, legacy AndroidX Media **1.7.0**, and
ViewModel Compose variants. These are Apache-2.0 AndroidX support libraries. The
resolved report is generated with the wrapper command in [Testing](../TESTING.md); the complete
module set is checked in under `tools/verification/runtime-modules.txt`.

Media3 also requires [Guava](https://github.com/google/guava) **33.3.1-android**
and `failureaccess` **1.0.2**, Apache-2.0, maintained upstream by Google. They
supply futures and collections used by Media3, including asynchronous controller
and Inspector APIs. They are JVM libraries without Android manifest components or
permission contributions; their selected transitive modules appear in the runtime
report and checksum metadata. Replacing these upstream APIs or excluding required
classes is not a maintained alternative. The app does not use unrelated Guava
functionality. These are not Google Play services.

All modules use the existing official repositories and strict checksum
verification. The initial checksum provenance limitation in decision 0001 still
applies; a passing check does not independently authenticate initial downloads.

## Permissions and exported components

The implemented playback service requests exactly `FOREGROUND_SERVICE`,
`FOREGROUND_SERVICE_MEDIA_PLAYBACK`, and `WAKE_LOCK`. Media3 owns the local wake
mode; no separate Wi-Fi or manual wake lock is added. AndroidX Core's generated
app-scoped signature receiver permission remains. Media3's transitive
`ACCESS_NETWORK_STATE` contribution is explicitly removed by the manifest merger.
No `INTERNET` or dangerous permission is requested.

Only the launcher, the typed `PlaybackService`, and Media3's protected
`BluetoothValidationActivity` are exported. The latter is a narrowly scoped
system integration exception: the [upstream implementation](https://github.com/androidx/media/blob/1.11.1/libraries/session/src/main/java/androidx/media3/session/BluetoothValidationActivity.java)
exists to satisfy the Bluetooth AVRCP probe on API 36 and 37. It immediately
finishes if instantiated. Its upstream manifest restricts callers with
`android.permission.BLUETOOTH_PRIVILEGED`, excludes it from recents, and uses a
no-display theme. The application does **not** request that permission. Removing
the probe could break required system media integration. Actual headset behavior
still needs verification.

The release boundary check now accepts the three playback permissions only with
the correctly typed service, rejects network-state permission, enforces privileged
protection on the probe activity, and requires the metadata service to remain
non-exported and isolated. ProfileInstaller's exported receiver stays removed.
Backup and all documented extraction-mode exclusions remain enforced.

## Local source and metadata boundaries

Controllers supply known book/chapter IDs to app-UID-only custom commands.
Untrusted controllers are rejected; trusted system controllers receive transport
commands but cannot supply media items or change the library. Items expose opaque
asset URIs, resolved only through an approved local file map and file data source.
No default remote data-source fallback is used.

Metadata inspection runs in a separate isolated process as recorded in decision
0002. The playback session uses an explicit metadata wrapper because ExoPlayer
can merge embedded fields into item metadata; permission omission alone cannot
stop a separate system artwork loader from dereferencing a supplied URI. The
wrapper exposes app-owned bounded metadata and removes extracted artwork, tracks,
timed metadata and unrelated playlist metadata. Privacy mode uses generic metadata.
Verification of this boundary must include both current-item and merged session
metadata, plus malicious embedded input; narrow synthetic tests are not a complete
external-controller audit.

## Verification

Follow [Testing](../TESTING.md) for release-boundary checks and native journeys.
Controller abuse, malicious embedded metadata, focus/noisy behavior, and legitimate
system media integration require scoped device evidence, including physical routing.
