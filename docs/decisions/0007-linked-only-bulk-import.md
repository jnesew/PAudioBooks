# 0007: Linked audio with one bulk review

New imports use linked audio and one bulk review. This supersedes the optional
private-copy choice in [decision 0005](0005-linked-local-library.md). The app is
unreleased; existing private test copies need no conversion feature. Never reset
installed data as an implementation shortcut. Listening history, notes, and edits
remain protected during normal use.

New imports retain read-only system-picker grants and play selected local audio.
There is no private-audio choice or retry-as-copy action. Metadata, sanitized
covers, journals, progress and preferences still require app-private storage.
Existing low-level file-copy utilities used by fixture tests, cover handling and
old-entry repair are not a supported second import mode; their wholesale removal
is separate from the user-facing simplification. No migration project is required.

Review grouping explicitly, then prepare bounded metadata for all selected books.
One bulk review shows titles, covers, counts, known sizes and checkboxes, with
optional title/order editing. One Add N books action commits independent books.
No repeated confirmations or autoplay. Failed entries do not block healthy books;
retry unfinished entries without repeating successes. Already-linked document
identities start unchecked and can be selected explicitly. No byte equivalence
is inferred between distinct locations. Cancellation closes all review jobs;
shared grants stay owned while other jobs/books/descriptors still require them.

The import review and explicit Read metadata use 64MiB/file and 64MiB/book
inspection limits, the isolated watchdog, and exact descriptor ranges. Cheap
component probes retain 2MiB/file and 16MiB/book limits. Inspection never copies
audio or performs full hashing. Beyond the limit details stay pending with an
explicit recovery action. File size and duration are not import cutoffs; large
sample tables may consume the metadata allowance independently of playback.
Retained bulk metadata is capped at 8Mi characters and 100,000 chapters across at
most 1,000 selected audio files; one sanitized cover per book is bounded to 64KiB.
Provider faults remain bounded. Measure provider cost and parity using encoded
fixtures and real device/provider journeys before accepting release behavior.

Official [SAF guidance](https://developer.android.com/training/data-storage/shared/documents-files)
requires returned persisted read grants, never guessed paths; moving/deleting documents can invalidate access. Select local
files, handle missing/nonseekable/revoked sources with user-directed recovery,
and never silently create permanent audio copies. No new permission, dependency,
network feature or service is introduced.

Report actual descriptor reads during inspection, starting with the first positive
read and throttled to at most five updates per second, with the final
count delivered on completion. File size and duration are not import cutoffs.
Before approval, omit the already-added-books message; show it only after
an actual commit succeeds. Settings omits zero private-audio
usage and labels any remaining test audio as audio stored in the app.
[Inspector guidance](https://developer.android.com/media/media3/inspector/retrieve-metadata)
describes asynchronous retrieval, a supplied MediaSourceFactory, and explicit
retriever closure. The worker watchdog, allocation
isolation, source range validation and all output bounds remain in place.
