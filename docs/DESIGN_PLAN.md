# Product and interaction design

This document defines the product contract and expected user behavior. The app
is in development; requirements and UI specifications do not establish
validated device compatibility or release readiness.

## Goal and boundaries

Make a personal audiobook collection comfortable to listen to entirely offline.
The important path is: select local files, confirm a book, press Play, and resume
later at the right place, including with the screen locked.

Run on stock Android as an ordinary installable app. No special firmware, root,
Google Play services, account, network, or initial online activation is required.
The selected support floor is Android 8.0 / API 26; older Android is outside the
initial scope. This is a compatibility decision, not a claim that old operating
systems still receive security patches.

There is no sync, streaming, store, subscription, remote cover/tag lookup, analytics,
advertising, casting, or DRM circumvention. The first release supports phones and
adaptive tablet layouts. Dedicated Android Auto, Wear OS, and TV experiences are
later work; ordinary system media controls and headset buttons are first-release work.

## First release

| Capability | Required behavior |
| --- | --- |
| Import | Select files/folders through the system picker; review one/many distinct books; link local sources, with progress/cancel |
| Library | Cover grid/list, local search, sort, and All / In progress / Unplayed / Finished filters |
| Book details | Cover, title, author, narrator when present, duration, progress, file details, chapter list, metadata overrides |
| Listening | Play/pause, book/chapter seek, configurable skip, chapter jump, speed, remembered position |
| Background | Notification, lock-screen and headset controls, audio focus, unplug pause, screen-off playback |
| Personal tools | Named bookmarks, recent seek history/undo, sleep timer, mark finished/unplayed |
| Maintenance | Storage usage, remove books, local metadata edits, explicit progress/bookmark export/import |
| Accessibility | TalkBack, large text, sufficient contrast, RTL, keyboard/switch access, portrait/landscape |

## Navigation and screen design

Use Material 3 with a calm neutral background and one restrained accent. Covers
provide visual identity; controls remain readable without a cover. Respect system
light/dark mode and offer explicit overrides. Dynamic colors may be an opt-in
palette where available; retain the selected design's palette as the consistent
fallback. Ship resources locally, including any fonts and icons.

Use two top-level destinations, **Library** and **Settings**, with a persistent mini
player when a book is loaded. Book details and the full player are nested screens.
Search and Add books are actions within Library. Tablets use a navigation rail
and, when space permits, library/detail panes; compact screens use bottom navigation.

### Appearance

Settings → Appearance → Theme has three choices: **System**, **Light**, **Dark**.
Default to System and persist the selection locally. System follows Android theme
changes; Light and Dark stay selected until changed. Apply theme changes immediately
without restarting playback, resetting progress, losing a sheet's state, or changing
navigation. Provide both themes throughout the app, including dialogs, import,
errors, and empty states.

Use separate semantic light/dark color tokens for surfaces, text, controls, outlines,
and progress. Dark mode uses comfortable dark surfaces with readable contrast,
not simple color inversion. Preserve original cover colors. Match system bar/icon
appearance to the active theme; status must remain understandable without color.

UI copy uses direct labels such as "Chapters", "Sleep timer", and "Theme".
Avoid label-plus-clarification patterns when the label is already clear. Explain
only meaningful consequences or unfamiliar choices, especially copying/removal.

Current presentation and interaction rules are in
[the UI specification](design/PROTOTYPE_TARGETS.md). Keep it consistent with
approved UI changes and verify the native implementation against the written
rules, including accessibility and platform behavior. Image boards are historical
references retained only in gitignored local documentation.

Paper List is approved as the prototype target: relaxed warm ivory/forest palette,
sage accents in dark mode, readable sans-serif type, and library rows with cover
thumbnails, thin progress bars, and remaining listening time. Continue iterating
during prototyping without losing that visual character.

### Library

- Start with a Continue listening card for the last book, then the filtered library.
  Card shows title, current chapter, progress, and estimated listening time left.
- Toolbar offers Search, Add books, and view/sort options. Default sort is recently
  listened; alternatives are title, author, and date added, with stable tie ordering.
- Default rows show cover, title, author, progress, remaining listening time, and
  an accessible status label. Optional grid view shares the same information and
  remembered filter/sort; the approved prototype target is the list.
- Search is local, case-insensitive, and handles diacritics. Search title, author,
  narrator, and series if known; never query a remote service.
- Genres and Authors use the selected compact Filters sheet: multi-select labels
  from local imported genre metadata and visible author credits, live matching,
  fixed tabs, option search and independently scrolling lists. Genre choices are
  created automatically from current books; missing tags appear as No genre.
  Split comma-delimited genres while preserving periods/ampersands. Split clear
  comma-separated full author names; preserve ambiguous credits such as Doe, Jane.
  Selections persist, intersect existing search/status and affect both list/grid.
  Active state/count on Filters replaces a library tag row. All dismissal methods
  keep changes; Clear all lives inside the sheet. There is no Apply footer.
- Empty state: “Add your first audiobook” and “Choose files” / “Choose a folder”.
  No permission wall or onboarding carousel. Unavailable/corrupt items retain
  progress and offer an actionable repair or reimport path.

### Import

1. Open the local system picker with read access only. Folder selection stays
   scoped to the chosen location, without a device-wide scan.
2. Review grouping when selecting multiple folders: separate books by default,
   with explicit combine/disc handling and select all/none. Never silently discard
   root files or merge books by title.
3. Read bounded metadata directly from selected audio, then show one bulk review
   with title, cover, file count, known size, selection and optional Edit actions.
   Edits cover title and file order. Prefer consistent disc/track tags, then natural
   filename order; preserve deliberate changes. Missing details remain explicit.
4. One Add N books action approves the selection. Process books independently;
   cancellation keeps completed additions and releases only unfinished ownership.
   Results show added/skipped/failed/not added. Retry only unfinished entries.
5. Return to Library/results without autoplay or opening the final book. Rotation
   retains selections and edits. Interrupted jobs can be deferred with Later and
   reopened from Add books for explicit cleanup.

New imports link local audio. Explain once: “Books play from this location. Keep
the files available.” No private-audio mode, storage radio buttons or copy fallback.
Private caches, covers and listening records remain. Existing private test copies
need no migration/preservation feature in this unreleased app. Never delete the
installed library as a test reset. See [decision 0007](decisions/0007-linked-only-bulk-import.md).

Picker filters accommodate mislabeled M4B with a deliberate other-files path.
Validate content, not filenames/MIME claims. Restricted directory roots and
nonseekable providers require actionable local-folder recovery, not broader access.
See [SAF guidance](https://developer.android.com/training/data-storage/shared/documents-files).

### Book details

Compact cover/title/author, optional series/number and role-labeled narrator/
translator credits, a single state-based time/status line and current progress.
Show total duration before starting, listening time left while in progress, and
Finished after completion; keep full duration in Book information.
A contextual Play/Resume/Open player action precedes openable Chapters/Bookmarks
rows with counts/current chapter; do not expand their entire lists by default.
Book information shows available description, genre, publisher, an explicit
validated release date and full duration. Missing fields and ambiguous generic
dates are omitted. A bottom Audio files row opens storage mode, availability,
formats, file count/size and maintenance actions. Its Back/outside/system Back
returns to Book information; only one window is displayed. Keep both windows
scrollable and retain their selection across Activity recreation.
Editing, cover changes, completion/reset and removal live in the overflow menu;
recovery is prominent when needed. Missing metadata is omitted or clearly labeled;
do not infer the narrator from the author tag.

Allow title, author, narrator, translator, series/number, and cover overrides stored in the library,
without rewriting source media. A cover can be selected through the picker.
Offer Reset override. Rescans never overwrite a user's edits.

Remove book explicitly removes its imported audio, local progress, and bookmarks
after a clear confirmation. Show the affected size and offer export first.
Original source files remain untouched. No “delete original” action in v1.

### Now playing

Large cover, title/author, current chapter and its position in the book. Keep
primary controls within comfortable reach: **back 10 s**, **play/pause**,
**forward 30 s**. Skip intervals are configurable. Previous/next chapter is a
separate labeled action so it cannot be confused with skip seconds.

Default scrubber represents the current chapter, with an explicit Chapter / Book
toggle. It changes only the seek bar's range and displayed elapsed/remaining media
times: current chapter versus entire audiobook. Switching scope does not seek,
switch tracks, change playback speed, or limit playback to one chapter. Chapter
playback continues into the next chapter normally. Keep the selected scope during
the current listening session; default to Chapter for a newly loaded book. If the
full duration is unknown, disable Book scope rather than inventing a total.

Show elapsed time on the left and remaining time with a minus sign on the right
(`6:42` / `-17:28` for a 24:10 chapter). These are media times; speed-adjusted
listening time is a separate concept used by the Library's time-left values.
A chapter sheet highlights the active chapter with progress and keeps transport
controls available. Tapping a row seeks to that chapter's start, preserves the
existing play/pause state, and closes the sheet. Opening/closing the sheet alone
does not affect playback. Bookmarks store a media position and optional note,
not a fragile chapter number.

Use real transport icons and a top-left collapse chevron. Below transport, show
Sleep, Speed, Bookmark, Chapters in that order, with contextual accessible
names, tooltips and active-state cues. Speed shows its value as the trigger; the
other utilities use icons. Chapters and speed use sheets; note/timer input uses
IME-safe dialogs. Outside taps close ordinary transient views without committing
an action; session bookmark drafts remain available until Save or explicit Cancel. Mini player shows cover, title, author, play/pause, and skip
forward; tapping its body opens the full player. Seek gestures have an accessible
equivalent and show a temporary Undo action backed by recent seek history.

Use 48 dp minimum touch targets, a larger central transport control, scalable
text, safe system insets, and high contrast. Announce chapter and playback state
changes meaningfully without announcing every second of progress. Give the
scrubber adjustable semantics with spoken time values.

### Settings

Sections: Playback, Appearance/accessibility, Storage/library, Export/import, About.
Show the app version, licenses, permission explanations, and a plain statement that
there is no app networking or sync. Offer a lock-screen privacy mode using generic
session title/artwork while keeping transport controls. Keep technical diagnostics
outside the ordinary listening flow. No login, device registration, or notification
permission prompt.

## Listening rules

- Speed: 0.5×–3.0×, pitch preserved, 0.05× steps; remember per book with a default
  for new books. Verify the range on supported devices. No silence skipping or
  loudness processing in v1; these complicate time estimates and audio behavior.
- Save progress after at most five media seconds of advancement or five elapsed
  seconds, whichever comes first, and on pause, seek, track change, completion,
  and explicit stop. Serialize writes. This keeps faster playback from increasing
  the target recovery loss beyond five media seconds on healthy storage. Normal
  pause must save its committed position. Restore paused, without unsolicited audio.
- Display media time for position and chapters. Label speed-adjusted remaining
  listening time separately. Unknown durations display “Unknown” rather than zero.
- Optional smart rewind: default off; when enabled, rewind five seconds after a
  pause of at least five minutes, bounded by the book start. Never apply it to an
  explicit seek or bookmark/chapter jump.
- Sleep timer: 15/30/45/60 minutes, custom minutes, or end of chapter. Countdown
  uses elapsed real time, not audio speed or clock time. End-of-chapter uses media
  position. Pause and persist at expiry; timer is cleared after process/service
  recreation. No reboot restoration, exact alarm, or shake-to-extend permission.
- Use speech audio attributes and managed audio focus. Pause for focus loss or
  output disconnect; do not resume just because headphones reconnect. A temporary
  focus interruption may resume only when the platform/player permits and the
  listener has not paused or changed books meanwhile.
- Finish only at confirmed end-of-book playback or explicit Mark finished. Merely
  seeking near the end must not mark it finished. Let listeners undo completion.
- Swiping the Activity away while playing keeps playback; explicit Stop and system
  force-stop end it. After reboot, the app restores position paused on next open.

## Formats, chapters, and covers

| Input | v1 commitment |
| --- | --- |
| MP3 | Single/multiple files, CBR/VBR seek fixtures, ID3 metadata/artwork; CHAP/CTOC chapters when present and valid |
| M4B / M4A / MP4 audio | DRM-free AAC-LC baseline, embedded cover, QuickTime and Nero chapters |
| FLAC | Playback with platform decoder; tags/artwork; file boundaries as fallback chapters |
| Ogg Vorbis / Ogg Opus | Playback on the selected OS floor; tags/artwork when exposed by the stack |
| WAV PCM | Playback; filename/file-boundary fallback where useful metadata is absent |
| Other codecs/containers | Best effort only after explicit fixture validation; do not promise based on extension alone |

Container support and audio decoding are separate. Media3 supplies extraction;
the installed platform supplies the selected decoders. Unsupported encodings get
a useful error. No FFmpeg extension, DRM support, transcoding, archive importer,
playlist URL, or internet recovery path is planned. See
[Media3 supported formats](https://developer.android.com/media/media3/exoplayer/supported-formats).

Media3 1.11 introduced QuickTime/Nero MP4 chapter extraction; the researched stable
baseline is **1.11.1**. Use upstream extraction before considering a custom parser.
Verify both chapter schemes and ID3 chapters against fixtures before accepting the
release scope. See the [Media3 release notes](https://developer.android.com/jetpack/androidx/releases/media3)
and [ID3 chapter API](https://developer.android.com/reference/androidx/media3/extractor/metadata/id3/ChapterFrame).

Chapter precedence: valid user-defined ordering/overrides, valid embedded chapters,
then one chapter per audio file. A single file without chapters remains playable
with a clear “No embedded chapters” state. Never invent chapter titles from a
duration. Normalize chapters across files; ignore hidden or invalid entries, bound
counts, and preserve useful valid entries. Prefer upstream handling when a file
contains both QuickTime and Nero tables.

Cover precedence: user-selected override, embedded front cover, adjacent
`cover.jpg` / `cover.png` / `folder.jpg` from an explicitly granted folder, then a
local title/initials placeholder. Selecting one audio file does not grant sibling
cover access. Never fetch an artwork URL found in metadata. Keep aspect ratio,
bound image decoding, and cache only replaceable thumbnails. Fit complete artwork
inside square library/mini-player layout slots; round the actual image bounds
rather than cropping text to fill a decorative frame.

Metadata precedence: user override, consistent embedded book tags, import folder
name, filename. Retain provenance. Narrator/series fields are optional because
tagging is inconsistent. Do not store or render arbitrary embedded HTML.

Metadata separates album/track titles, retains multiple contributors, and uses
per-file agreement with documented role fallbacks. Genre, publisher, tagged date,
explicit release date and plain-text description are stored in schema 6. The
read-only [Book information target](design/PROTOTYPE_TARGETS.md#book-information-and-audio-files)
keeps generic tagged DATE hidden and introduces no additional metadata editor.
See the [mapping policy](ARCHITECTURE.md#embedded-audiobook-metadata).

## Manual portability

Provide versioned JSON export/import of progress, bookmarks, and metadata overrides
through an explicit system save/open action. Exclude audio, raw paths, URI grants,
and caches. Explain that it is an unencrypted file and may contain private notes.
Map to existing books by verified asset fingerprints or explicit user mapping;
preview conflicts and never merge by title alone. No scheduled export or sync.

## Later candidates

CUE or sidecar chapters, collections,
silence skipping, equalizer, home-screen widget, and dedicated vehicle/wearable UI.
Each needs its own scope and tests. Linked local folders/SD-card libraries require
revocation/ejection/changed-source recovery and measured acceptance.
Account/network/sync features remain outside the product contract.
