#!/usr/bin/env python3
"""Check generated fixture metadata with ffprobe, independently of Android."""

import json
import subprocess
import sys
from pathlib import Path
from generate import boxes


def probe(path: Path) -> dict:
    result = subprocess.run(
        ["ffprobe", "-v", "error", "-show_format", "-show_chapters", "-of", "json", str(path)],
        capture_output=True, text=True, check=True,
    )
    return json.loads(result.stdout)


def check_mp4_layout(directory: Path, manifest: dict) -> None:
    """Check the structures deliberately written by this fixture generator."""
    for name, track_count, has_reference, nero_titles in (
        ("chapters-both.m4b", 2, True, ["First", "Second", "Third"]),
        ("chapters-quicktime.m4b", 2, True, None),
        ("chapters-nero.m4b", 1, False, ["First", "Second", "Third"]),
        (manifest["conflicting_chapter_file"], 2, True, manifest["conflicting_nero_titles"]),
    ):
        data = (directory / name).read_bytes()
        moovs = [(p, n) for p, n, t in boxes(data, 0, len(data)) if t == b"moov"]
        if len(moovs) != 1:
            raise ValueError(f"{name}: expected one moov")
        pos, size = moovs[0]
        children = list(boxes(data, pos + 8, pos + size))
        tracks = [(p, n) for p, n, t in children if t == b"trak"]
        refs = [ref for track, length in tracks
                for ref in boxes(data, track + 8, track + length) if ref[2] == b"tref"]
        if len(tracks) != track_count or bool(refs) != has_reference:
            raise ValueError(f"{name}: unexpected chapter track/reference structure")
        chpls = []
        for udta, length, kind in children:
            if kind == b"udta":
                chpls.extend((p, n) for p, n, t in boxes(data, udta + 8, udta + length) if t == b"chpl")
        if len(chpls) != (0 if nero_titles is None else 1):
            raise ValueError(f"{name}: unexpected Nero chapter atom count")
        if nero_titles is None:
            continue
        chpl, length = chpls[0]
        cursor, end = chpl + 8, chpl + length
        if data[cursor:cursor + 4] != b"\x01\x00\x00\x00" or data[cursor + 8] != 3:
            raise ValueError(f"{name}: unexpected Nero chapter header")
        cursor += 9
        for index, title in enumerate(nero_titles):
            if cursor + 9 > end:
                raise ValueError(f"{name}: truncated Nero chapter")
            start = int.from_bytes(data[cursor:cursor + 8], "big")
            title_length = data[cursor + 8]
            cursor += 9
            if (start != index * 40_000_000 or cursor + title_length > end or
                    data[cursor:cursor + title_length].decode("utf-8") != title):
                raise ValueError(f"{name}: unexpected Nero chapter")
            cursor += title_length
        if cursor != end:
            raise ValueError(f"{name}: unexpected Nero chapter trailing data")


def main() -> None:
    if len(sys.argv) != 2:
        sys.exit("Usage: python3 tools/fixtures/check.py OUTPUT_DIRECTORY")
    directory = Path(sys.argv[1])
    manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
    check_mp4_layout(directory, manifest)
    for name in manifest["metadata_files"]:
        result = probe(directory / name)
        streams = subprocess.run(["ffprobe","-v","error","-show_streams","-of","json",str(directory/name)],capture_output=True,text=True,check=True)
        tags = {k.upper():v for k,v in result["format"].get("tags",{}).items()}
        for stream in json.loads(streams.stdout)["streams"]:
            tags.update({k.upper():v for k,v in stream.get("tags",{}).items()})
        if tags.get("ALBUM") != "Shared Book" or tags.get("GENRE") != "Fiction":
            raise ValueError(f"{name}: missing album or genre {tags}")
        if not all(word in tags.get("DESCRIPTION", tags.get("COMMENT", "")) for word in ("Synthetic", "description")):
            raise ValueError(f"{name}: missing description {tags}")
    expected = manifest["chapters"]
    for name in manifest["chapter_files"]:
        result = probe(directory / name)
        actual = result["chapters"]
        if len(actual) != len(expected):
            raise ValueError(f"{name}: expected {len(expected)} chapters, got {len(actual)}")
        for chapter, wanted in zip(actual, expected):
            if (abs(float(chapter["start_time"]) * 1000 - wanted["start"]) > 1 or
                    abs(float(chapter["end_time"]) * 1000 - wanted["end"]) > 1 or
                    chapter.get("tags", {}).get("title") != wanted["title"]):
                raise ValueError(f"{name}: unexpected chapter {chapter}")
        tags = result["format"].get("tags", {})
        if tags.get("title") != "Synthetic Book" or tags.get("artist") != "Fixture Author":
            raise ValueError(f"{name}: unexpected metadata {tags}")
    for name in manifest["no_chapter_files"]:
        result = probe(directory / name)
        if result.get("chapters"):
            raise ValueError(f"{name}: unexpected chapters")
        if abs(float(result["format"]["duration"]) - 12) > 0.05:
            raise ValueError(f"{name}: unexpected duration")
    for name in manifest["invalid_files"]:
        result = subprocess.run(
            ["ffprobe", "-v", "error", str(directory / name)], capture_output=True,
        )
        if result.returncode == 0:
            raise ValueError(f"{name}: expected ffprobe to reject the truncated file")
    for name, dimensions in manifest["artwork_files"].items():
        result = subprocess.run(
            ["ffprobe", "-v", "error", "-show_streams", "-of", "json", str(directory / name)],
            capture_output=True, text=True, check=True,
        )
        streams = json.loads(result.stdout)["streams"]
        images = [stream for stream in streams if stream.get("disposition", {}).get("attached_pic") == 1]
        if len(images) != 1 or [images[0].get("width"), images[0].get("height")] != dimensions:
            raise ValueError(f"{name}: unexpected embedded artwork")
    conflict = probe(directory / manifest["conflicting_chapter_file"])
    if [chapter.get("tags", {}).get("title") for chapter in conflict["chapters"]] != ["First", "Second", "Third"]:
        raise ValueError("conflicting fixture: ffprobe did not report the QuickTime titles")
    malformed = (directory / manifest["invalid_chapter_file"]).read_bytes()
    marker = b"ch1\0" + (4000).to_bytes(4, "big") + (1000).to_bytes(4, "big")
    if malformed.count(marker) != 1:
        raise ValueError("invalid chapter fixture: expected one reversed second chapter")
    valid_chapters = probe(directory / manifest["invalid_chapter_file"])["chapters"]
    if [chapter.get("tags", {}).get("title") for chapter in valid_chapters] != ["First", "Third"]:
        raise ValueError("invalid chapter fixture: ffprobe did not discard the reversed chapter")
    print("Fixture metadata checks passed with ffprobe; this check does not test Android/Media3.")


if __name__ == "__main__":
    main()
