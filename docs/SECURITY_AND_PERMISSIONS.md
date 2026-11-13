# Security and permission design

This document defines requirements and selected mitigations; it is not a security
audit or proof of device behavior. Security depends on the installed Android version
and its patches as well as application code and packaged dependency updates.

The release manifest must request the three normal playback
permissions below and declare/use the generated
`io.github.jnesew.paudiobooks.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` at signature
protection. Media3's network-state permission contribution is explicitly removed.
The launcher and typed playback service are exported; Media3's narrowly protected
Bluetooth validation activity supports the API 36/37 system probe and requires a
privileged permission from its caller. The app does not request Bluetooth access.
The inspection service is private and isolated. Backups and cloud/device/
cross-platform transfers are excluded; ProfileInstaller's exported receiver is
removed. The [release boundary check](../tools/verification/check_release.py)
enforces these constraints and the reviewed runtime module set. See the
[media runtime decision](decisions/0003-media-runtime.md) for rationale. Static
evidence does not establish actual OEM transfer, headset or controller behavior.

## Offline contract

No app networking, sync, account, analytics, ads, remote crash collection, remote
metadata/cover lookup, streaming, or automatic update checker. Omit `INTERNET` and
network-state permissions. Add no HTTP client, WebView, Cast stack, download
service, or remote font/image loader. Playback and artwork resolve only approved
local data. Required Media3 modules may contain unused network-capable classes;
enforce the boundary with permission omission and the local-only source resolver,
not a claim that every packaged class is incapable of networking.

First launch, import from local storage, search, listening, and resume must work in
airplane mode without ever having connected. A signed APK must also run without
Google Play services; installation/distribution must not imply a runtime dependency.

The system document picker can expose providers that have their own network access.
Set `Intent.EXTRA_LOCAL_ONLY` for import and export. Treat it as a provider contract,
not an app-enforced network sandbox. Root `FLAG_LOCAL_ONLY` can inform validation
when available; do not infer locality from an arbitrary URI string. Do not claim
that the app can control a separate provider's traffic. Validate linked reads and
playback with connectivity off; never silently copy audio to claim availability. See the
[local-only intent contract](https://developer.android.com/reference/kotlin/android/content/Intent#EXTRA_LOCAL_ONLY)
and [provider root flag](https://developer.android.com/reference/kotlin/android/provider/DocumentsContract.Root#FLAG_LOCAL_ONLY).

## Permission budget

The platform permission allowlist contains these three permissions,
including contributions merged from dependencies. They are normal permissions,
without a dangerous-permission prompt:

| Permission | Need | Lifetime/limits |
| --- | --- | --- |
| `android.permission.FOREGROUND_SERVICE` | Continue user-requested listening in a playback service | Media3 service lifecycle; no idle keep-alive |
| `android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Typed playback foreground service on modern Android | Service declares `foregroundServiceType="mediaPlayback"` |
| `android.permission.WAKE_LOCK` | Reliable screen-off local playback through Media3 | Local wake mode; owned/released by player, no Wi-Fi lock |

AndroidX Core may additionally merge
`${applicationId}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, declared and used with
`protectionLevel="signature"`. This app-scoped receiver protection does not grant
storage/network access or display a runtime prompt. Keep it when required for
compatibility, verify its exact name/protection level, and reject other unexpected
permissions. See the [AOSP-published AndroidX Core manifest](https://android.googlesource.com/platform/prebuilts/sdk/+/dbc89592d1bcb88ed9718f2b8d4f117ebb74547f/current/androidx/manifests/androidx.core_core/AndroidManifest.xml).

Foreground playback permissions follow the
[Media3 background-playback guide](https://developer.android.com/media/media3/session/background-playback).
Wake mode is governed by the
[ExoPlayer API](https://developer.android.com/reference/androidx/media3/exoplayer/ExoPlayer#setWakeMode(int)).
Foreground service status does not provide unrestricted background execution.
Start playback from a permitted user/media action and verify target-SDK restrictions.

| Omitted permission/category | How the feature works without it |
| --- | --- |
| `INTERNET`, `ACCESS_NETWORK_STATE` | No application network feature |
| `READ_MEDIA_AUDIO`, `READ_MEDIA_IMAGES`, legacy read/write storage, `MANAGE_EXTERNAL_STORAGE` | User-selected SAF read grants and private metadata/cover caches; image chosen through picker |
| `POST_NOTIFICATIONS` | Genuine media-session notifications are exempt; no unrelated notifications |
| Bluetooth/nearby-device permissions | Android routes audio and media buttons; no scanning, pairing, or device inventory |
| Microphone, camera, location, contacts, phone state | No corresponding product feature; interruptions handled by audio focus |
| Exact alarm, boot completed, battery-optimization exemption | Timer lives with playback; no boot auto-start or perpetual service |
| Query all packages, package installation, overlay, accessibility service | No app discovery, self-updater, overlay, or accessibility-service feature |

Do create the required playback notification/channel; permission exemption is not
permission to omit the notification. No prompt is needed for media notifications;
see the [notification permission exemptions](https://developer.android.com/develop/ui/views/notifications/notification-permission#exemptions).
Picker access is still a visible user grant, despite requiring no storage runtime
permission. Take only read flags actually returned; release unneeded persisted grants.

## Trust boundaries and mitigations

| Boundary / threat | Required control |
| --- | --- |
| Malformed audio, tags, chapter tables | Upstream parsers, worker limits, timeouts/cleanup, bounded metadata, corrupt-file fixtures |
| Huge or hostile covers | Validate encoded bytes and dimensions, sampled decode, thumbnail caps, placeholder fallback; no SVG/HTML renderer |
| Misbehaving document provider | Scoped grants, bounded traversal, cancellation/timeout where supported, temporary copies, recovery UI |
| File names / paths from input | Generate owned filenames; never concatenate a source name into a filesystem path; reject traversal and escaped canonical paths |
| External media controller | Authenticate UID/trust, limit commands, resolve known library IDs, reject external URIs/DRM configs and library mutation |
| Export/import JSON | Explicit picker action, schema and count/length limits, conflict preview, no raw paths/grants/deserialization into executable classes |
| Another ordinary app | App-private files/database; no exported data provider; temporary minimal artwork sharing only if needed |
| Logs and system surfaces | No private content in production logs; session shows only needed current-book metadata; offer generic lock-screen metadata mode |
| Packaged dependency | Minimal approved stack, pinned versions/checksums, transitive review, merged-manifest gate, release update review |
| Accidental data loss | Atomic file staging plus journal, transactional metadata, periodic checkpoints, schema migrations, explicit removal confirmation |

Default bounds for the implementation spike: 16 MiB encoded artwork; reject source
images above 16 megapixels; output at most 1024×1024 preserving aspect; 4,096
characters per display field/note; 10,000 chapters per book; 10,000 enumerated files
and depth 16 per import; 10 MiB per portability JSON file. Make these named constants
and test rejection/recovery. Limits are proposed, not proven against upstream APIs;
revise based on fixtures without permitting unbounded work. Stream large audio
independently of metadata limits, and cap concurrent extraction to one job initially.

Cancellation alone cannot stop every blocking/native parser. Validate extractor
allocation/cleanup in the spike, including timeout/resource-leak behavior. If safe
bounds cannot be enforced with the selected APIs, reject that metadata path or
design a narrowly scoped isolated inspection process before enabling it. A
post-allocation length check does not prevent an allocation attack.

Only the launcher and the media integration components that need discovery may
be exported. V1 has no share/open-intent importer or public catalog. The playback
service is intentionally discoverable; keep library editing, deletion, export,
and import unavailable through external session commands. Use explicit immutable
PendingIntents where applicable. Do not trust a caller-supplied package name.
Trusted media controllers can include user-authorized notification listeners:
do not treat the session as a private storage channel.

Android audio routing/focus avoids a need for Bluetooth and phone-state permissions.
Use speech attributes and the supported Media3 focus integration; check current
target-SDK requirements in the [audio focus guidance](https://developer.android.com/media/optimize/audio-focus).

## Linked-source access

[Decision 0005](decisions/0005-linked-local-library.md) adds only user-granted,
read-only local sources. Retain a returned persistable tree/document grant while
books, workers or playback require it; release only when the final owner is done.
Never guess content-URI paths, export grants, broaden storage/network permissions
or delete originals. Revocation, removal, changed files and incomplete scans keep
IDs/history/notes and expose recovery. Incomplete inventory is never proof of
deletion. Existing private copies retain their present protection and lifetime.
[Decision 0007](decisions/0007-linked-only-bulk-import.md) selects linked-only new
imports. Rescans and deferred metadata retain these boundaries without additional
permissions. Verify actual provider, lifecycle, and hardware behavior; component
results alone do not establish that acceptance.

## Local data and backup

New imports keep audio at its selected local document location. Keep metadata/progress in
private Room storage, and preferences in private DataStore storage. Rely on the
Android sandbox and device storage encryption; do not invent custom audio
encryption or key management. An unlocked compromised OS/root attacker is beyond
this app's protection. There are no account credentials or service tokens to store.

Set `android:allowBackup="false"`, disable legacy full backup, and provide explicit
cloud-backup and device-transfer exclusions for all applicable storage domains on
modern versions. Include any newer transfer modes supported by the selected SDK;
do not leave a transfer mode implicitly enabled. Some devices handle migration
differently, so verify rules and representative transfer behavior instead of
promising that a manifest flag controls every OEM tool. See
[Android backup and extraction rules](https://developer.android.com/identity/data/autobackup).

Manual transfer uses streaming schema validation with token, field, count, nesting
and byte budgets before allocating each next record. Provider calls have a bounded
worker, deadline, explicit cancellation and close ownership; a stuck call keeps its
admission guard until the real work/cleanup exits. Late results do not revive UI.
See [decision 0008](decisions/0008-security-updates.md) for these limits and dependency
security updates. Neither coroutine cancellation alone nor a byte cap bounds a
provider that stops responding.

Manual export is an explicit user exception for portability, not sync. Write a
versioned JSON document through the local-only picker; explain its unencrypted
notes/progress content. Exclude audio, raw paths, grants, diagnostics, and caches.
Uninstall/clear-data deletes the private library and progress; explain export
before these operations where the app can do so. Removing a copied book releases
only its own assets; reference-count shared assets if deduplication is implemented.

## Release evidence

- Merged release-manifest permissions equal the three-platform-permission allowlist
  plus the documented app-scoped signature permission when required. Inspect all
  exported components and backup/extraction rules.
- Resolved runtime tree contains no additional networking, analytics, ads, GMS, or
  native codec SDK introduced accidentally; review transitive components and the
  unused capabilities of required modules, not just direct dependency names.
- Fresh-install and resumed-use journeys pass offline on a GMS-free AOSP image
  and stock physical device. No network-scheme input triggers a provider/browser.
- An unauthorized test controller cannot play arbitrary files or change/delete/export
  the library. Legitimate lock-screen/headset controls still work.
- Malformed and oversized file fixtures, low space, process death, and backup-rule
  inspection pass. No user media is uploaded as part of diagnostics or testing.
