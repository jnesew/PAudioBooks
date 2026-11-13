# 0006: Bound persisted read grants before taking new access

Extends [linked storage](0005-linked-local-library.md); no new dependency or permission.

## Problem and evidence

Android can prune older persisted URI grants when its per-app limit is exceeded.
The supported range cannot be handled by assuming one number solely from SDK.
[Legacy AOSP URI grant management](https://android.googlesource.com/platform/prebuilts/fullsdk/sources/android-30/+/refs/heads/main/com/android/server/uri/UriGrantsManagerService.java)
has a 128-entry limit; [current AOSP](https://android.googlesource.com/platform/frameworks/base/+/master/services/core/java/com/android/server/uri/UriGrantsManagerService.java)
uses 512. These are implementation observations, not a new public API contract.
The public [ContentResolver API](https://developer.android.com/reference/android/content/ContentResolver)
provides the app's actual persisted permissions but does not expose its capacity.
No private class/API is copied or called.

Capacity and eviction probes belong only on a dedicated synthetic setup.
Never reproduce grant eviction in a preserved or user library. Keep experiment
identities, paths, and results in local evidence.

## Decision

Use public persisted-permission snapshots under the existing central grant mutex.
Count distinct URIs in every persisted mode, including unknown/unowned/write-only
entries, together with the requested URIs. Reject a new read acquisition before
any ownership/reservation record or platform take when it would exceed:

- **96** entries for ordinary additions;
- **128** for deliberate reconnect/reauthorization, leaving 32 entries of recovery
  headroom relative to the addition budget while old sources/descriptors remain.

These are conservative application budgets, not claims that Android has exactly
96/128 slots. A shared parent tree serves many separate books with one grant;
this does not cap the number of books at 96. Existing read grants are reused
without another platform take, including legacy libraries above the budget.
Unknown grants are counted but never swept. Metadata verification and playback
need no new grant acquisition and keep working at the addition budget.

Only private reconnect/reauthorization operations request recovery headroom.
Selections exceeding it fail without displacing existing access. Recovery UI
suggests fewer locations or a shared folder under decision 0007; it does not ask for
broad storage access or another runtime permission. Caller journals are cleaned
through the existing interrupted-import path, preserving old books and notes.

A valid pre-existing read remains unowned and is not released on book removal.
If that read has been revoked and the app deliberately acquires a replacement,
record the new read as app-owned durably before taking it. Releasing READ later
must leave any unrelated WRITE mode alone. This closes an ownership leak without
claiming unknown permissions that remain valid.

## Acceptance and limits

Exercise actual public permissions with a small injected test budget: no mutation
on refusal, shared-grant reuse at capacity, reserved recovery headroom, refusal at
the recovery limit, and cleanup retaining unrelated permissions. Revoke/reacquire
an initially unowned read, then confirm only the newly owned read is released.
Exercise the real capacity error and location-recovery UI on a dedicated synthetic setup.
Run current builds/lints/JVM and storage/native regressions on the API floor and
current AOSP, with final source/APK identities.

Budget protection does not prevent user revocation, provider removal or every OEM
policy. Existing recovery behavior and hardware/provider gates remain required.
It does not silently consolidate grants, convert audio, merge books or remove
notes. More elaborate grant consolidation would require its own reviewed behavior
for active playback; it is not introduced here. New imports remain linked-only
under [decision 0007](0007-linked-only-bulk-import.md).
Validate hardware/provider limits separately from synthetic component tests.
