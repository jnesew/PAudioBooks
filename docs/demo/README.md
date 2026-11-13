# Fictional demo library

The documentation screenshots use four fictional books. Titles, people, publisher,
descriptions, dates, chapter names, and listening examples were created for this
project. They do not describe commercial audiobooks or actual narrations.

The four covers were generated with OpenAI's built-in image tool, then exported
as 768 × 768 JPEGs for embedding. The source PNGs and exact prompts are retained
in ignored local design records. These images illustrate demo books only; they
are not app UI targets or bundled runtime artwork.

| Book | Author | Genre |
| --- | --- | --- |
| The Glass Harbor | Mira Vale | Fantasy, Adventure |
| A Map of Quiet Stars | Niko Arden | Science fiction, Adventure |
| The Last Orchard | Elin Marsh | Mystery, Fiction |
| Along the Moss Trail | Rowan Hale | Nature, Travel |

[books.json](books.json) contains their metadata. [The generator](../../tools/demo/generate.py)
creates valid AAC M4B files with silence, embedded JPEG covers, book/credit/catalog
tags, and named chapters. Long durations are deliberate synthetic examples. The
files are imported through the app's ordinary local-folder picker and review;
screenshots show the real app, not composited UI.

With Python 3 and FFmpeg/ffprobe installed, run from the repository root:

```sh
python3 tools/demo/generate.py test-media/generated/documentation-demo
```

Use a new or empty output directory. The generator refuses to overwrite existing
content and checks durations/chapters with ffprobe. Its output includes a manifest
with hashes and observed tags. Generated audio is gitignored; do not commit it.
Copy the generated book folders into a dedicated test device's local storage,
then choose their parent folder in PAudioBooks. Do not reset a preserved library
to reproduce screenshots.

User-facing screenshots belong in `docs/screenshots/`. Machine paths, exact build/
device hashes, UI dumps, and capture-run records stay in ignored `docs/local/`.
Capture at 1080 × 2400 with the Pixel 8a profile, an offline GMS-free image, normal
text size, and both app themes. This display profile does not establish physical
Pixel hardware acceptance.

The captures retain the native screen contents; Android's status-bar demo mode
normalizes the clock and battery. See the official
[Pixel specifications](https://support.google.com/pixelphone/answer/7158570) and
[Android screenshot guidance](https://developer.android.com/studio/debug/am-screenshot).
