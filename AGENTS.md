# Workspace instructions

These instructions apply to the entire repository. Read this file and the relevant
documents in `docs/` before changing the project. Direct user instructions take
precedence. Keep documentation consistent with actual implementation.

## Working workflow

Work as one agent in the current checkout. Read Git status/diffs before editing;
preserve user changes, notes, library data and unfinished work. Make focused
changes, verify them, and create reviewable local commits. Do not push, publish,
or sign for release without explicit user authorization.

Keep checkpoints, acceptance tracking, machine paths, test inventories, dated
reports, user notes and design iterations in gitignored `docs/local/`. When
present, use `docs/local/STATUS.md` for current work and
`docs/local/ACCEPTANCE.md` for open gates; consult only relevant reports under
`docs/local/compatibility/`. These optional local files record bounded evidence,
not reusable setup or instructions to resume old rounds. A fresh checkout need
not contain them. Update existing local tracking instead of adding overlapping
handoffs. Keep durable requirements and generalized procedures in tracked docs.

## Product contract

Build a native, offline audiobook player for stock Android. It must run as a normal
unprivileged app without root, custom firmware, Google Play services, an account,
or connectivity. Covers, chapters, local library organization, reliable resume,
and background playback are core functionality.

Do not introduce sync, streaming, remote metadata/artwork lookup, advertisements,
analytics, telemetry, remote crash reporting, update checks, or account systems.
Do not add permission prompts or services in anticipation of hypothetical features.

The workspace contains plans, compatibility tools, and an in-progress native app.
Import, metadata, playback and listening-tool code require acceptance against the
implementation plan. Use the current evidence documents to distinguish implemented
code from validated journeys; never invent build/test results or describe a debug
artifact as a release-ready application.

## Read first

- `docs/DESIGN_PLAN.md`: expected user experience and release scope.
- `docs/ARCHITECTURE.md`: selected stack and data/playback boundaries.
- `docs/SECURITY_AND_PERMISSIONS.md`: permission budget and trust boundaries.
- `docs/IMPLEMENTATION_PLAN.md`: implementation order and release gates.
- `docs/BUILDING.md`, `docs/TESTING.md`, `docs/SIGNING.md`: reusable developer procedures.
- `docs/RELEASING.md`: version bumps, release cycles, Android codes, notes, and tags.
- Local status/acceptance files above, when available; never assume past results
  validate changed source or invent missing evidence.

## Platform and dependencies

1. Prefer a suitable public Android framework API.
2. Use official AndroidX/Jetpack when it provides a maintained compatibility layer
   or substantial functionality. Media3 is the selected playback/chapter stack;
   Compose, Room, and DataStore are intentional choices.
3. Keep Kotlin standard libraries and coroutines to the functionality needed.
4. Add another runtime dependency only when a concrete requirement remains unmet.
   Record its purpose, upstream, license, permissions, transitive dependencies,
   maintenance, and alternatives in an architecture decision under `docs/decisions/`.
   This review is part of implementation; it does not by itself require user approval.

AndroidX is packaged with the app: OS updates do not update these dependencies.
Track stable upstream releases and ship reviewed updates. Do not copy private AOSP
classes, use hidden APIs, or add FFmpeg/native codec packs as a shortcut.

Use stable, pinned versions, a Gradle version catalog, dependency verification,
and a checked-in wrapper for the existing app. No dynamic versions, changing
artifacts, arbitrary repositories, or bundled credentials. Prefer `google()` and
`mavenCentral()`; restrict plugin resolution to the official sources needed.

## Security and permissions

- No `INTERNET`, broad media/storage access, Bluetooth, microphone, location,
  contacts, phone state, battery exemption, boot, or exact-alarm permission.
- The initial platform permission allowlist is `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, and `WAKE_LOCK`. AndroidX Core's app-scoped
  signature permission for non-exported receivers is also permitted when generated;
  verify its exact name and protection level. Inspect all merged contributions.
- Do not request `POST_NOTIFICATIONS` for the media-session notification. If a new
  feature needs another permission, document its user benefit and platform basis
  and update the plan and verification together.
- Import only through user-selected system-picker grants. Read sources without
  modifying or deleting them. Never turn a `content://` URI into a guessed path.
- Playback accepts internal library IDs resolved to approved local sources. Reject
  arbitrary URIs, remote artwork, network schemes, and unknown media IDs.
- Disable app backup/transfer through the applicable manifest and extraction rules.
  Manual export is user initiated; never export raw database files or URI grants.
- Treat files, metadata, artwork, document providers, and incoming media commands as
  untrusted. Bound work, close resources, validate input, and fail with recovery UI.
- Export only components needed for launcher and system media integration. Restrict
  controllers and commands using authenticated UID/trust information, not a claimed
  package string. Keep library mutations private to this app.
- Keep user titles, paths, content URIs, bookmarks, and listening history out of
  production logs. Never commit keystores, secrets, local SDK paths, or user books.

## Implementation conventions

- Keep one `:app` module with package boundaries.
- Use Kotlin and Compose/Material 3; use public APIs and version guards above minSdk.
  The selected minimum is API 26; compile/target SDK is 37. Verify platform changes.
- Keep one player and session in the playback service. UI controls it through a
  MediaController; an Activity or composable must not own a second player.
- Keep filesystem/provider/database work off the main thread. Serialize playback
  state changes and progress writes; make cancellation and resource ownership explicit.
- Preserve progress and bookmarks across metadata edits, rescans, and migrations.
  Do not use destructive Room migrations or automatically merge books by title.
- Use stable asset IDs and 64-bit media positions; do not confuse media time with
  wall-clock time or chapter indices with audio-file indices.
- Put visible strings in resources. Support TalkBack, large fonts, RTL, dark mode,
  system insets, and 48 dp minimum touch targets. Accessibility must not depend on art.
- Support Light, Dark, and System appearance settings, with System as the default.
  Persist the selection and change theme without interrupting playback or navigation.
- New imports link local audio and use one bulk review/approval. No private-audio
  import option or preservation/conversion feature for existing test copies is required.
- Make deletion scope clear. Removing a book never deletes its original audio.
  Avoid unrelated refactors or speculative abstractions.

## UI text and visual references

- Use short, clear labels and natural language. When a term is sufficient, use it
  alone. Avoid patterns such as "Term; term clarification", "Term — explanation",
  or a redundant subtitle that only restates the label. Prefer "Chapters" to
  "Chapters; browse the chapters in your book".
- Add explanation when it changes a decision, prevents data loss, explains an
  unfamiliar action, or helps recovery. Do not remove useful time values, state,
  units, or accessible names just to shorten text.
- Put occasional help in the relevant sheet or error state rather than repeating
  it beside self-explanatory controls. Accessibility labels should identify the
  action and context without unnecessary verbosity.
- Keep all generated UI boards, candidates, prompts, iterations and captures under
  gitignored `docs/local/design/`. Keep current written UI specifications under
  tracked `docs/design/`. Record styling decisions there before implementation;
  exploratory candidates are alternatives, not cumulative requirements.
- User-facing documentation screenshots belong in tracked `docs/screenshots/`,
  with only synthetic content and no private paths or notifications. Keep their
  run-specific evidence local. Document reusable demo assets/provenance separately
  from design exploration; screenshots do not become visual requirements by default.
- Paper List is the approved visual baseline. Read
  `docs/design/PROTOTYPE_TARGETS.md` for current presentation and interaction rules.
  Keep it current as the UI evolves. Archived images predate later modifications
  and are historical references, not current implementation targets.
- Follow the written rules for layout, spacing, type hierarchy, and both themes.
  Build real native text, controls, and accessibility semantics; never ship a
  flattened mockup as the interface.

## Verification and delivery

- Before implementation, check current official Android guidance (the `android docs`
  CLI is available here). Record important platform assumptions and source links.
- Run checks appropriate to the change. For documentation changes, check links,
  whitespace, and consistency; do not generate app tests to test prose.
- Run the real wrapper's build, lint, and relevant tests. Document
  exact commands in `docs/BUILDING.md` and `docs/TESTING.md`; do not assume
  commands work before verifying them.
- For playback/storage changes, cover chapter boundaries, multi-file order, process
  death, permission denial, missing/corrupt media, and progress persistence.
- Run one emulator at a time; reuse a suitable persistent AVD. Record image identity,
  offline state and actual APK/source hashes. Finish/reap instrumentation, stop the
  owned emulator gracefully and verify exit before launching another. Avoid heavy
  builds overlapping emulator suites; stop idle build daemons. Guest RAM does not
  bound host emulator RSS. Never wipe/uninstall/clear data to reset a preserved library.
- Install only a completed successful current-source build. Never install a stale
  artifact after a failed build. Do not overlap native CLI UI inspection and
  instrumentation on one device; reboot without resetting data if automation
  registration needs recovery.
- Exercise offline journeys on a GMS-free AOSP emulator and stock physical hardware.
  A GMS emulator alone does not prove the app has no Google services dependency.
- For dependency or manifest changes, inspect the merged release manifest and
  resolved runtime dependencies. Maintain the permission allowlist release check.
- Use synthetic or redistributable media fixtures with provenance. Do not commit
  commercial audiobooks or large binary fixtures.
- State what changed, checks actually run, and any remaining limitations. Do not
  publish, sign for release, or claim release readiness as part of a planning task.
