# Decision: isolate untrusted media inspection

Acceptance depends on device,
resource, cancellation and hostile-fixture tests; this is not a release gate pass.

Media3 Inspector is the chosen metadata API, but full MP4 sample tables are needed
for the QuickTime chapter fixtures. The [Media3 1.11.1 MP4 extractor source](https://github.com/androidx/media/blob/1.11.1/libraries/extractor/src/main/java/androidx/media3/extractor/mp4/Mp4Extractor.java)
allocates a parsed leaf atom using its declared size, with an integer-sized upper
bound rather than the application's artwork/metadata limits. Thus output checks
alone cannot cap all upstream allocations. The [ID3 decoder](https://github.com/androidx/media/blob/1.11.1/libraries/extractor/src/main/java/androidx/media3/extractor/metadata/id3/Id3Decoder.java)
also copies frame data before the application can check artwork size. Omitting
sample tables would avoid this chapter path but loses the QuickTime-only fixture.
A separate hand-written container parser would increase maintenance and attack
surface; post-allocation checks in the app process do not meet the trust boundary.

A private non-exported `isolatedProcess` service receives a read-only descriptor to
an approved local source, including its exact descriptor offset/range. A local-only
data source reads that descriptor;
no provider URI or guessed path reaches the parser. Android documents that an
[isolated service has no permissions of its own](https://developer.android.com/guide/topics/manifest/service-element).
A single job is allowed at a time; a watchdog terminates the isolated process on a
deadline, and cancellation requests process termination. The service releases its
retriever and descriptor, sends only bounded values, then exits. Unexpected process
death is reported as a recoverable inspection failure. Isolation limits damage to
the app process but does not prove that system-wide memory pressure is harmless.

Returned text, chapter count and IPC text are bounded. Image bounds are checked
before sampled decoding, output is sanitized to a small JPEG, and oversized or
invalid artwork falls back to a placeholder. Chapter normalization discards hidden,
reversed, out-of-range and duplicate starts; file boundaries are the fallback.
No remote artwork is loaded. There is no network permission. Provenance identifies
embedded metadata; user overrides remain separate. Unknown values remain unknown.

The [Inspector guidance](https://developer.android.com/media/media3/inspector/retrieve-metadata)
requires closing the retriever. The implementation uses an owned scope and closes
it; device tests must still verify timeout, cancellation, process death and repeated
runs, including when the parser is blocked. A resource-pressure test and physical
hardware validation remain required before enabling this path in release imports.
