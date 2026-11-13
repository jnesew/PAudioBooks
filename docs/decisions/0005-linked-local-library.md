# 0005: Linked local library sources

The optional private-copy import choice is superseded by
[decision 0007](0007-linked-only-bulk-import.md). New imports link local audio.
The lasting source boundaries are retained in [Architecture](../ARCHITECTURE.md#linked-storage).

Linking avoids permanent duplication of a potentially large library, but source
availability, provider latency, seeking, and grant lifetime remain dependencies.
Stable book/track IDs and listening history stay separate from source location.
Use read-only user-selected SAF grants, bounded off-main provider work, exact
read-only descriptor ranges, isolated inspection, and one service-owned player.
Never guess paths, copy audio silently, delete originals, or infer equality from
a title, size, timestamp, or sampled hash. Reconnect at a different location only
after full identity verification. Incomplete inventory cannot erase history.

No runtime dependency, network feature, or additional permission is required.
Measure source reads, writes, cache bounds, cold resume, and lifecycle behavior
on realistic providers; linking is not an instant-import promise.

[SAF guidance](https://developer.android.com/training/data-storage/shared/documents-files)
notes that persisted grants do not guarantee access after document movement or
deletion. [ContentDataSource](https://developer.android.com/reference/androidx/media3/datasource/ContentDataSource)
provides public URI reads, without proving reliable seeking for every provider.
