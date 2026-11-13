#!/usr/bin/env python3
"""Generate only the fictional, silent demo audiobooks used in documentation.

Requires FFmpeg/ffprobe. Never reads or edits a listener's audio or library.
"""

import argparse
import hashlib
import json
from pathlib import Path
import struct
import subprocess


ROOT = Path(__file__).resolve().parents[2]
CHAPTERS = {
    "glass-harbor": ["The Tide Road", "A Light in the Harbor", "The Mapmaker", "Into the Sound", "The Lantern", "The Crossing", "Low Tide", "The Far Shore", "Homeward", "A New Chart", "Morning Glass", "The Returning Tide"],
    "quiet-stars": ["The Listening Station", "An Old Signal", "Departure", "The Dust Sea", "Between Orbits", "A Quiet Constellation", "First Contact", "The Long Way Home", "A New Frequency"],
    "last-orchard": ["The Letter", "Homecoming", "The Locked Gate", "Windfall", "Old Roots", "A Missing Page", "The Conservator", "Harvest", "Under the Trees", "The Last Secret", "A Place to Begin", "Spring Again"],
    "moss-trail": ["First Light", "The Fern Path", "After the Rain", "Small Streams", "A Clearing", "The Long View", "Evening Notes", "Home Before Dark"],
}


def run(*args):
    subprocess.run(args, check=True, stdout=subprocess.DEVNULL)


def atom(kind, data):
    return struct.pack(">I", len(data) + 8) + kind + data


def boxes(data, start, end):
    """Walk only ordinary 32-bit boxes emitted by this generator's FFmpeg run."""
    while start < end:
        size = int.from_bytes(data[start:start + 4], "big")
        if size < 8 or start + size > end:
            raise ValueError("Unexpected generated MP4 box")
        yield start, size, data[start + 4:start + 8]
        start += size


def add_book_tags(path, book, cover):
    """Replace our own trailing moov/udta metadata, without moving audio offsets."""
    def text(key, value):
        return atom(key, atom(b"data", b"\0\0\0\x01\0\0\0\0" + value.encode()))

    def free(key, value):
        return atom(b"----", atom(b"mean", b"\0" * 4 + b"com.apple.iTunes") +
                    atom(b"name", b"\0" * 4 + key.encode()) +
                    atom(b"data", b"\0\0\0\x01\0\0\0\0" + value.encode()))

    tags = text(b"\xa9nam", book["title"]) + text(b"\xa9alb", book["title"])
    tags += text(b"\xa9ART", book["author"]) + text(b"\xa9gen", book["genre"])
    tags += text(b"\xa9day", book["date"])
    for key, value in [("AUTHOR", book["author"]), ("NARRATOR", book["narrator"]),
                       ("PUBLISHER", book["publisher"]), ("RELEASEDATE", book["date"]),
                       ("DESCRIPTION", book["description"])]:
        tags += free(key, value)
    tags += atom(b"covr", atom(b"data", b"\0\0\0\x0d\0\0\0\0" + cover.read_bytes()))
    metadata = atom(b"meta", b"\0" * 4 + atom(b"hdlr", b"\0" * 8 + b"mdir" + b"\0" * 12) + atom(b"ilst", tags))
    data = path.read_bytes()
    output = bytearray()
    for offset, size, kind in boxes(data, 0, len(data)):
        if kind == b"moov":
            if offset + size != len(data):
                raise ValueError("Expected generated moov after audio")
            body = b"".join(data[p:p + n] for p, n, k in boxes(data, offset + 8, offset + size) if k != b"udta")
            output += atom(b"moov", body + atom(b"udta", metadata))
        else:
            output += data[offset:offset + size]
    path.write_bytes(output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path, help="New or empty directory; audio is not committed")
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    if any(output.iterdir()):
        parser.error("Output directory must be empty")
    books = json.loads((ROOT / "docs/demo/books.json").read_text())
    manifest = []
    for book in books:
        folder = output / book["title"]
        folder.mkdir()
        duration_ms = round(book["hours"] * 3_600_000)
        titles = CHAPTERS[book["id"]]
        metadata = folder / "chapters.ffmeta"
        lines = [";FFMETADATA1"]
        for index, title in enumerate(titles):
            lines += ["[CHAPTER]", "TIMEBASE=1/1000", f"START={duration_ms * index // len(titles)}",
                      f"END={duration_ms * (index + 1) // len(titles)}", f"title={title}"]
        metadata.write_text("\n".join(lines) + "\n")
        audio = folder / (book["title"] + ".m4b")
        run("ffmpeg", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i",
            "anullsrc=r=8000:cl=mono", "-i", str(metadata), "-map", "0:a", "-map_metadata", "1",
            "-map_chapters", "1", "-t", str(book["hours"] * 3600), "-c:a", "aac", "-b:a", "8k", str(audio))
        add_book_tags(audio, book, ROOT / "docs/demo/covers" / (book["id"] + ".jpg"))
        actual = json.loads(subprocess.check_output([
            "ffprobe", "-v", "error", "-show_format", "-show_chapters", "-of", "json", str(audio)
        ], text=True))
        if abs(float(actual["format"]["duration"]) * 1000 - duration_ms) > 200:
            raise ValueError("Unexpected generated duration")
        if len(actual["chapters"]) != len(titles):
            raise ValueError("Unexpected generated chapters")
        manifest.append({"id": book["id"], "path": str(audio.relative_to(output)),
                         "sha256": hashlib.sha256(audio.read_bytes()).hexdigest(),
                         "duration_ms": duration_ms, "chapters": len(titles),
                         "metadata": actual["format"].get("tags", {})})
        metadata.unlink()
        print(f"Generated {book['title']}: {len(titles)} chapters, {audio.stat().st_size:,} bytes")
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")


if __name__ == "__main__":
    main()
