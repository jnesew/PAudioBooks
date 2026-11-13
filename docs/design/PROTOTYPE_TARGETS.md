# Paper List UI specification

Paper List is the approved palette and layout baseline. Use real native text,
controls, scrolling, and accessibility semantics. Derive displayed values from
actual metadata and playback. This written specification defines the current
presentation and interaction rules.

Image boards are archived locally under gitignored `docs/local/design/`. They
predate later UI modifications and are historical references, not current targets.

Preserve warm ivory/charcoal/forest in Light
and forest-charcoal/ivory/sage in Dark, using semantic color roles and measured
contrast. Keep relaxed spacing, quiet dividers, readable sans-serif text, 48dp
minimum touch targets, and safe system/IME insets. Adapt to large fonts, RTL,
landscape, and tablets rather than copying fixed canvas dimensions.

## Library and covers

Library and Settings are the top-level destinations. While a book is loaded, its
mini player replaces Continue listening; the cover/title body opens the player,
with separate play/pause and timed skip-forward targets. Full player hides the
mini player and destination bar. Merely opening a screen never starts audio.

Use square slots in rows, grid, Continue, and mini player. Fit the complete image
without stretching or cropping text. Round the image's actual bounds; unused slot
space remains the page surface. Full-player/details artwork keeps its aspect
ratio within bounded dimensions; cap player art at 300dp and, in windows at least
600dp wide, at 35% of window height. Use local square placeholders for missing art.

One Filters button carries active state and selected-value count. Its sheet has
fixed Genres / Authors tabs, search, independently scrolling choices, a result
count below the title, and Clear all inside. Apply selections live; all dismissal
methods keep them. No library tag row or Apply footer. Choices derive from local
metadata. Split genres on commas, retaining periods/ampersands; split only clear
full-name author lists and retain ambiguous credits such as Doe, Jane. Persist
selections; combine them with search/status for both grid and list.

## Time, transport, and chapters

Library and details show one primary time/status line: total Duration before
starting, speed-adjusted time left in progress, Finished after completion, and an
honest unknown state when duration is unavailable. Keep full media duration in
Book information. Player and chapter readouts retain exact media times.

Chapter / Book changes seek-bar range only. Default to Chapter on loading a book;
retain the scope within the session. Disable Book if total duration is unknown.
Switching scope never seeks, changes speed, or stops at a chapter boundary.
Transport uses previous chapter, back 10 s, play/pause, forward 30 s, next chapter;
timed skip values are configurable. Keep Undo near seek scope. Utilities are Sleep,
Speed, Bookmark, Chapters, with speed's current value as its trigger.

Chapter rows are whole-row jump targets with ordinal, title, and full duration.
Only the current row adds Current, progress, and elapsed/remaining media time.
Unknown duration is Unknown. The player sheet opens at the current row, shows
current/total in its header, and keeps Close and transport available. Selecting
a chapter seeks, saves, and closes on success; selecting the current row restarts
it. Preserve loaded play/pause state; selecting a different book loads it paused.
Opening, browsing, or closing alone does not switch books or affect sound.

## Transient views, notes, speed, and timers

Outside tap, Back, visible Close/Done, and sheet drag follow consistent exits;
consume outside taps so they never activate controls beneath. Whole-dialog
scrolling keeps headings, fields, and actions reachable at 200% text with the IME.
Use native pane/heading semantics. Destructive confirmations commit only through
the explicit action; running jobs retain their real cancellation/ownership rules.

Bookmark rows jump by position, with Edit note and Remove in separate overflow.
Closing a note editor never saves or seeks. Retain session drafts, including a new
bookmark's captured position or an edited bookmark's identity. Cancel discards;
Save commits. During a pending save guard dismissal and show Saving; failure keeps
the draft for retry. Preserve draft and operation identity across recreation.
Notes allow up to 4,096 characters without silent truncation; clearing text retains
the bookmark, track, media position, and creation time.

Speed uses one prominent value, a 0.50×–3.00× stepped slider, precise 0.05× −/+
buttons, and 1.00×/1.25×/1.50×/2.00× quick values. Apply slider changes on gesture
completion; buttons/presets apply on tap. Disable endpoint buttons appropriately.
Keep the sheet open for adjustment and use Done; applied changes remain on close.
The service-confirmed value is authoritative and changing speed never starts or
pauses playback. Settings uses the same controls for Default speed, affecting
books without a remembered speed while preserving loaded/remembered book speeds.

Sleep offers wrapping presets, End of chapter, custom Minutes/Start for 1–1,440
whole minutes, and Stop timer only while active. Closing changes no timer. Show
range errors and keep all actions reachable above the keyboard. The timer belongs
to the service; do not invent a countdown from picker state.

## Book information and Audio files

Book information shows plain description, then available genre, publisher, valid
explicit release date, and full duration. Omit missing fields and ambiguous generic
DATE. Localize valid year/month/day precision without inventing missing parts.
Keep the existing Paper dialog, 560dp maximum width, 24dp content inset, natural
wrapping, and merged short label/value accessibility groups.

A bottom Audio files row opens storage mode/consequence, availability, formats,
file count/size, and maintenance. Read metadata appears when pending; Verify audio
for linked books and Rescan folder for folder books remain here. Only one native
Dialog page is visible; page changes scroll to the heading and survive recreation.
Back/outside/system Back from Audio files returns to Book information; those exits
from Book information close it. Opening/dismissal changes no listening state.
Recovery stays prominent on the book page. Removal retains explicit scope and
confirmation. Starting read/rescan work closes the information windows; cancellation
returns to Audio files. Existing ownership, byte limits, and confirmations remain.

## Import, source recovery, and settings

Use explicit grouping where ambiguous, then bounded preparation and one bulk
review with cover/title/count/known-size/selection and optional title/order Edit.
Done returns from editing to review. Select all/none and Add N books cover the
whole review. Explain once that books play from this location and files must stay
available. No private-copy option, repeated approval, or autoplay. Results distinguish
added/skipped/failed/not added; retry unfinished entries. Duplicate documents start
unchecked. Preserve relative disc paths when filenames repeat. Keep grouping and
review scrollable, with clear cancellation and recreation retention.

Separate Finding books, Reading book details, and Adding books states. Report real
throttled source reads and the current file; hide bytes until a positive read and
never label metadata reads as copied audio. Only committed additions justify an
already-added-books message. Interrupted work offers Later and explicit Clean up,
is reopenable from Add books, and must not block existing books.

Folder rescans review only the book subtree. Retain old order/history and append
explicitly reviewed additions. Show missing/changed sources for recovery without
deleting records. Explicit Read metadata has progress/cancel and an honest pending
count, distinct from full identity verification. Explain any loaded-target pause;
unrelated playback continues. See [Architecture](../ARCHITECTURE.md) for ownership
and identity checks, including repair of existing private assets.

Settings defaults to System theme, 1.00× default speed, 10 s / 30 s skips, smart
rewind off, and lock-screen details on. Theme changes preserve playback/navigation.
Storage omits zero private-audio usage; actual retained audio is Audio stored in app.
Progress transfer is explicit with preview/conflicts and privacy explanation.
About/privacy/licenses open on demand; technical diagnostics stay outside listening.
