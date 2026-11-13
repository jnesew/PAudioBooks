# Implementation plan and acceptance gates

This document defines requirements and milestone exit criteria. It does not
instruct maintainers to repeat completed work or establish current acceptance.
Build commands are in [Building](BUILDING.md), verification procedures in
[Testing](TESTING.md), and release-key handling in [Signing](SIGNING.md).

The [product design](DESIGN_PLAN.md), [UI specification](design/PROTOTYPE_TARGETS.md),
and [source boundaries](ARCHITECTURE.md#import-and-storage-lifecycle) refine the
milestones below. [Decision 0007](decisions/0007-linked-only-bulk-import.md) selects
linked-only new imports and one bulk approval. Catalog metadata is read-only in
Book information, with storage and maintenance in Audio files. Provider/cost,
metadata parity, and physical-device behavior remain required release criteria.
Signing and distribution require separate authorization.

## Visual design

Paper List is selected. Use the [UI specification](design/PROTOTYPE_TARGETS.md) for
native hierarchy, spacing, themes and interaction rules. Record styling decisions
in the specification before implementation. Keep image boards and explorations
local; archived boards predate later modifications. Generate candidates only for
an actual new design decision; alternatives are not cumulative requirements.
Preserve real text, controls, accessibility and platform behavior.

## 0. Validate the platform choices

- Preserve the selected API 26 minimum and application identity. Revisit platform
  support only with measured compatibility evidence and reviewed upgrade effects.
- Record stable compile/target SDK, AGP, Gradle, JDK, Kotlin, Compose, Room, and
  Media3 versions; verify current platform behavior with official Android docs.
- Use a disposable local spike for Media3 Inspector chapter/artwork extraction,
  seek accuracy, speed/pitch, speech focus, and screen-off playback.
- Verify QuickTime and Nero M4B chapters separately and together, ID3 CHAP/CTOC,
  absent/malformed metadata, large artwork, cancellation, and resource cleanup.
  Media3 1.11.1 is pinned; fixture results must remain tied to their tested inputs.
- Establish synthetic/redistributable fixtures with provenance and expected tags,
  chapters, ordering, and tolerances. Stream-generate very large fixtures when needed.

Exit: a short recorded compatibility result, dependency/toolchain choices, and any
scoped design corrections. No claim of chapter support solely from release notes.

## 1. Scaffold the smallest native application

- Create one `:app` module with Kotlin, Compose/Material 3, stable wrapper, version
  catalog, strict dependency verification, and official repositories.
- Add launcher, theme, Library/Settings shells, local string resources, application
  container, and empty-state Add books actions. Include backup/extraction rules.
- Add the System / Light / Dark setting and persist it in DataStore. Verify that
  changing themes keeps playback and navigation state intact.
- Maintain only the three reviewed playback permissions and AndroidX's audited
  app-scoped signature permission. Keep the merged-manifest boundary check.
- Add Room schemas/migration infrastructure, DataStore settings, and CI for build,
  lint, unit tests, manifest/dependency review. Keep SDK paths and signing local.
- Maintain setup/build requirements in Building and verification commands in Testing.

Exit: a debug APK installs and opens offline on the API floor and current stable
Android AOSP emulator, without Play services or a runtime permission prompt.

## 2. Import and library data

- Implement picker contracts, local-only intent settings, read-grant lifecycle,
  folder/file grouping preview, natural/tag ordering, and user reorder.
- Use read-only linked sources and one editable bulk review/approval, with
  size/free-space feedback, bounded inspection, explicit identity verification,
  durable journals/grant reservations, cancellation and safe recovery/release.
- Preserve unfinished work when recovery is deferred; keep cleanup explicit and
  reopenable. Reviewed rescans append selected additions without rewriting history.
- Integrate bounded metadata/chapters/artwork, with provenance and fallbacks.
- Deliver library grid/list/search/filter/sort and book details; preserve overrides.
- Implement explicit book removal with clear data-loss scope and no source writes.

Exit: MP3 folder and M4B imports show correct cover/chapter/order data offline;
missing or revoked source access offers recovery without erasing history. Denial, malformed
input, unknown sizes, interrupted copy, and full storage cause recoverable states.

## 3. Playback and durable progress

- Add the service-owned player/session, local-source resolver and transport controls.
- Add only the documented foreground-service/media-playback/wake-lock permissions.
  Configure the typed service, managed local wake mode, media notification, speech
  focus, output-disconnect pause, and external-controller command restrictions.
- Implement playlist/timeline mapping, chapter navigation, configurable skip, speed,
  progress checkpoints, completion, and paused restoration.
- Deliver now-playing and mini player; wire every entry point through one session.

Exit: first play through screen lock, headset actions, Activity dismissal, pause,
process kill, reopening, and book switching preserves expected position. No duplicate
players, permission dialog, remote source, or unsolicited playback on restore.

## 4. Listening tools and accessible polish

- Bookmarks/notes, seek history/undo, smart rewind option, and sleep timer modes.
- Manual progress/bookmark/override export/import with preview and fingerprint mapping.
- Metadata/cover overrides, storage accounting, missing-file recovery, and helpful errors.
- Adaptive tablet layout, themes, lock-screen privacy option, RTL, TalkBack, large
  fonts, keyboard/switch access, and safe handling of system insets.

Exit: timer, bookmarking, export round-trip, and accessibility journeys pass offline.
Unknown duration, missing art, and long titles have usable layouts and semantics.

## 5. Harden and prepare a release candidate

- Run the matrix below and the security document's release-evidence checks.
- Inspect release manifest, extraction rules, resolved dependencies, licenses, and
  minified release behavior. Verify no debug logs or test-only exported components.
- Verify updates preserve the installed personal library; no destructive reset.
  Historical schema/transfer compatibility tests are not a pre-release gate. Measure
  startup/library responsiveness, memory, battery, and progress writes.
- Record known format/device limitations, APK size, reproduction steps, and test
  versions. Add a concise privacy statement matching the built app.
- Prepare a reviewable candidate. Release signing, store publication, and distribution
  are separate authorized actions; they are not part of this planning bootstrap.

Exit: reproducible build and recorded acceptance results, with unresolved release
blockers explicit. An emulator-only run is insufficient evidence for release.

## Release criteria

These are requirements, not completed checkboxes or a record of test results.
Track current evidence and open gates locally; changing sources or dependencies
requires revalidating affected criteria.

| Gate | Required behavior |
| --- | --- |
| E01 | OS/offline: first launch/import/listen/restart without activation, runtime permission wall or GMS |
| E02 | formats: real extraction, decoding, seek and recovery across CBR/VBR MP3, AAC M4B/M4A chapter variants, FLAC, Vorbis, Opus and PCM |
| E03 | inspection: bounded hostile tables/artwork, cancellation, isolation/watchdog and safe retry |
| E04 | import: picker → explicit grouping/order/review → add → library → play/pause/reopen, originals unchanged |
| E05 | storage recovery: denial, revoked/missing/changed source, cancellation, low space and interrupted work preserve committed data |
| E06 | library/data: ordering/grouping, duplicate review, search/filter/sort, override preservation, reference-safe removal and repair |
| E07 | durable playback: restore paused IDs/speed/position after live kill, force-stop and reboot; ordered writes |
| E08 | shutdown/idle: playback survives Activity dismissal, paused recreation retains media, true ten-minute expiry releases resources |
| E09 | timelines/controls: multi-file/disc order, unknown/differing/>24h duration, exact boundaries, undo and skip |
| E10 | background/audio/tools: screen-off/Doze, monotonic timers, focus/noisy handling, privacy, speed and no reconnect autoplay |
| E11 | editing/transfer: bounded explicit preview/conflicts/fingerprint mapping, stable notes/overrides and safe delayed saves |
| E12 | visual/accessibility: Paper List hierarchy, both themes, 200% text, RTL, keyboard/switch/TalkBack, 48dp and insets |
| E13 | security: exact permissions/exports/backup rules, local-only IDs, controller trust, privacy and reviewed dependencies/licenses |
| E14 | reproducibility: verified wrapper builds/lints/tests and recorded final hashes |
| E15 | minified: unsigned production build and separate nondebuggable validation journeys |
| E16 | retention/performance: preserve the installed personal library, measured startup/library/resume/memory/write cost and no idle polling |
| E17 | delivery: documentation, privacy/licenses, verified commands, limits, exact provenance and local review agree |

## Verification matrix

| Area | Required examples / expected outcome |
| --- | --- |
| OS | API 26 floor; API 30 storage restrictions; API 33 notification behavior; API 34 typed FGS; API 35+ audio-focus restrictions; current stable target; supplement with real stock hardware |
| Device | GMS-free AOSP emulator, stock phone, tablet/large-window configuration; optional physical SD-card source for import |
| Offline | Clean install/first launch/import/listen/restart in airplane mode; no activation, remote artwork, or connectivity recovery dependency |
| Formats | CBR/VBR MP3; AAC-LC M4B/M4A QuickTime/Nero/both/no chapters; ID3 CHAP/CTOC; FLAC; Ogg Vorbis/Opus; WAV PCM; unsupported codec |
| Timelines | Multiple discs/files, 2/10 filename order, differing durations, >24-hour synthetic book, unknown duration, final chapter boundary, seek exactly at a track boundary |
| Storage | Picker cancel/denial, revoked temporary grant, unknown size, mislabeled MIME, ejected source during copy, low space, corrupt owned asset, interrupted journal reconciliation |
| Lifecycle | Rotation, Activity recreation/dismissal, screen off, Doze, process kill, user force-stop, reboot, restart paused, concurrent seek/book switch checkpoints |
| Audio | Wired/Bluetooth transport via OS, unplug/disconnect pause, focus interruption, speed/pitch, book transitions, timer while screen off and at non-1× speed |
| Privacy | Three platform permissions plus audited app-scoped signature protection; backups/transfers excluded; logs redacted; external URI/controller abuse rejected; no hidden sync |
| Accessibility | TalkBack focus/order/actions, 200% font size, RTL, contrast in Light and Dark, 48 dp targets, keyboard/switch actions, landscape, small display and tablet |
| Appearance | System default follows OS changes; forced Light/Dark persists across restart; theme changes preserve navigation, progress, and playback; artwork colors unchanged |
| Data | Current-schema persistence, preserved override/progress after refresh, duplicate candidates, removal reference safety, current-format export/import conflict preview and limits |

Test timeline/order/parser normalization/checkpoint sequencing as pure unit logic;
use instrumented tests for Room, SAF, service lifecycle, media controls, and UI
journeys. Use synthetic fake controllers/providers for abuse and cancellation tests.
Do not replace lifecycle and real-device listening checks with mocked player tests.

Proposed tolerances to confirm in milestone 0: normal AAC/FLAC/PCM seek/resume within
one second; indexed MP3 within two seconds; no more than five media seconds of
uncheckpointed advancement lost on abrupt process death with healthy storage.
Check the cadence at all supported speeds, and measure decoder seek error separately.
Surface approximate seeking for inputs without usable indexes. Never promise
sample-accurate navigation for every container. A normal pause must persist the
actual position successfully.

Proposed performance budgets on a documented midrange reference device: cached
1,000-book library becomes interactive within two seconds, warm local resume starts
within one second, and thumbnail decoding does not stall the UI. Imports show byte
progress and remain cancelable; import speed depends on the provider and storage.
Measure memory/battery and set numerical budgets from the spike; do not claim them
without measurements. There should be no idle polling service or background scan.

## Decisions to revisit only when needed

| Decision | Baseline | Revisit trigger |
| --- | --- | --- |
| OS floor | API 26 | Measured audience need or upstream support change |
| Storage | Linked-only new imports and bulk approval | Current metadata parity, provider/cost and physical acceptance |
| External browsing | MediaSessionService only | Dedicated vehicle UI or system resumption carousel becomes in scope |
| Import execution | Explicit foreground task with recovery | Evidence that uninterrupted background import is necessary |
| Extra parser/dependency | None beyond selected stack | A required fixture cannot be supported safely upstream |
| App identity/distribution | PAudioBooks; `io.github.jnesew.paudiobooks`; channel unset | Before distribution tooling |

For changes with lasting tradeoffs, create `docs/decisions/NNNN-topic.md` describing
context, choice, alternatives, consequences, and evidence. No extra decision file
or user approval is needed for routine implementation within these constraints.
