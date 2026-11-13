# PAudioBooks 0.1.0

2026-11-13 · Initial release · Android 8.0 and newer

PAudioBooks is a native offline player for your own audiobooks. Choose local files
or folders, review the books, and start listening. No account, internet connection
or Google Play services is required.

## Included

- Local file and folder import with bulk review, editable grouping/order and
  cancellation. Audio stays in its original location; removing a book never
  deletes the original files.
- List and cover-grid library views, search, sorting, listening-state filters,
  and genre/author filters derived from local book metadata.
- Background playback, Android media notification and lock-screen controls,
  remembered listening position, chapters, and chapter/book seeking.
- Configurable skips, 0.50×–3.00× speed, optional smart rewind, recent-seek undo,
  and sleep timers including end of chapter.
- Bookmarks with editable notes, embedded artwork and book information, and
  local title, contributor, series and cover overrides.
- System, Light and Dark themes, plus an option to hide book details on the
  lock screen.
- Manual progress, bookmark and metadata-override export/import with a preview
  before replacing selected local records.

## Privacy and your library

The app has no internet permission, advertising, analytics, remote artwork lookup,
sync or update checker. Android's picker grants access only to locations you choose;
document providers are separate apps with their own behavior. Obtainium can manage
release downloads separately from the offline player.

Keep selected audio available, including any removable card. App backup and device
transfer are disabled. Manual exports contain no audio or source-access grants,
but do contain unencrypted titles, listening records and notes. Store them privately.
On a new installation, add the corresponding audio and verify its identity before
restoring an export. Export before uninstalling or clearing app data.

## Compatibility and limitations

- Requires Android 8.0 / API 26 or newer.
- Intended formats are MP3, DRM-free AAC M4B/M4A, FLAC, Ogg Vorbis/Opus and WAV PCM.
  Actual decoding, metadata, chapter extraction and seek precision depend on the
  file, Android version and device codecs. There is no DRM removal, transcoding,
  archive import, streaming or remote repair.
- Metadata preparation can take time for large books or slow document providers.
  Missing tags remain unknown; there is no online metadata lookup.
- Sleep timers do not survive playback-service recreation. Saved listening
  position restores paused after the app process stops.
- This is an early release. Broader OEM/removable-storage, headset routing,
  acoustic accuracy, battery/performance and complete assistive-technology
  acceptance remain ongoing; compatibility is not established for every device.

## Install

Use the signed production APK attached to the
[GitHub release](https://github.com/jnesew/PAudioBooks/releases), and compare its
SHA-256 with the accompanying `SHA256SUMS`. The application ID is
`io.github.jnesew.paudiobooks`; this cycle uses version `0.1.0`, Android code `1`
and tag `v0.1.0`.

Development builds can use a different signing key and cannot then be updated in
place by the production APK. Preserve your original audio and a progress export
before changing installations; do not clear a library just to bypass an update
error.

See the [usage guide](https://github.com/jnesew/PAudioBooks/blob/v0.1.0/docs/USAGE.md),
[MIT license](https://github.com/jnesew/PAudioBooks/blob/v0.1.0/LICENSE) and
[security-reporting policy](https://github.com/jnesew/PAudioBooks/security/policy).
Build-tool dependency caveats are documented in
[decision 0008](https://github.com/jnesew/PAudioBooks/blob/v0.1.0/docs/decisions/0008-security-updates.md).
