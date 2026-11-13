# Disposable Inspector spike

This is an experimental test harness, not the audiobook application. It copies
four synthetic assets into its own cache, retrieves duration and track metadata
with both the default Media3 Inspector source factory and one that retains MP4
sample tables, and displays the result. A separate button runs a short, muted
ExoPlayer position and speed measurement over the synthetic formats. It is
deliberately narrow: it does not automatically verify chapter correctness,
decoded seek accuracy, pitch, background playback, cancellation, or release security.
Do not use it with personal media or distribute its debug build.

To prepare it, use Python 3 and FFmpeg/ffprobe to generate and check synthetic
fixtures with `python3 tools/fixtures/generate.py EMPTY_OUTPUT_DIRECTORY` and
`python3 tools/fixtures/check.py EMPTY_OUTPUT_DIRECTORY` from the repository root.
Copy this directory to a disposable location, create `app/src/main/assets` there, and copy
all generated `.mp3`, `.m4b`, `.wav`, `.flac`, and `.ogg` files from the
generated fixture directory into it. Build the copy
with Gradle 9.6.0, JDK 17, Android SDK platform 36 and build tools 36.0.0 using
`:app:assembleDebug`. The spike has no wrapper or dependency verification; those
are required for the actual app scaffold. Do not treat this build as an app build.

On a test device, open the debug build and compare the reported metadata with the
fixture manifest. Record device, OS, Media3 version and observed values. A
successful build alone says nothing about extraction on Android. Avoid granting
the spike access to user files; its inputs are bundled synthetic assets only.
