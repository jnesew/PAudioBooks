#!/usr/bin/env bash
set -euo pipefail
# Generate only synthetic media and provision a selected already-running emulator.
# Use ANDROID_SERIAL to select a disposable GMS-free emulator.
: "${ANDROID_SERIAL:?Set ANDROID_SERIAL to a disposable emulator}"
fixture_dir=$(mktemp -d)
trap 'rm -rf "$fixture_dir"' EXIT
python3 tools/fixtures/generate.py "$fixture_dir"
python3 tools/fixtures/check.py "$fixture_dir"
adb shell run-as io.github.jnesew.paudiobooks mkdir -p files/metadata-fixtures
for name in metadata-html.mp3 metadata-mdta-moov.m4b metadata-mdta.m4b metadata-v23.mp3 metadata-v24.mp3 metadata-fallback.mp3 metadata.flac metadata.m4b metadata-movement.m4b metadata-vorbis.ogg metadata-opus.ogg credits.mp3 credits.flac chapters.mp3 chapters-vbr.mp3 chapters-quicktime.m4b chapters-nero.m4b chapters-both.m4b chapters-conflict.m4b chapters-invalid.mp3 no-chapters.wav no-chapters.flac no-chapters.ogg no-chapters-opus.ogg no-chapters.m4a unsupported-msadpcm.wav cover.mp3 cover-large.mp3 truncated.m4b unsupported.xyz; do
    adb push "$fixture_dir/$name" "/data/local/tmp/paudiobooks-$name" > /dev/null
    adb shell run-as io.github.jnesew.paudiobooks cp "/data/local/tmp/paudiobooks-$name" "files/metadata-fixtures/$name"
    adb shell rm "/data/local/tmp/paudiobooks-$name"
done
adb shell am instrument -w -e class io.github.jnesew.paudiobooks.metadata.MetadataInspectorTest io.github.jnesew.paudiobooks.test/androidx.test.runner.AndroidJUnitRunner | tee "$fixture_dir/result.txt"
if ! grep -Eq '^OK \([0-9]+ tests?\)' "$fixture_dir/result.txt"; then
    echo "Metadata instrumentation did not pass" >&2
    exit 1
fi
