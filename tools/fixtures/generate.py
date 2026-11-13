#!/usr/bin/env python3
"""Generate short, synthetic media for the platform compatibility spike.

The output directory must be empty. No source recordings or network access are used.
"""

import argparse
import json
import shutil
import struct
import subprocess
import tempfile
import zlib
from pathlib import Path
from metadata import id3_fixture, flac_fixture, mp4_fixture, relocate_mdta_fixture


def run(*args: str) -> None:
    subprocess.run(args, check=True, stdout=subprocess.DEVNULL)


def png(path: Path, width: int, height: int) -> None:
    """Write a synthetic solid-color RGB PNG, streaming rows into the compressor."""
    def chunk(kind: bytes, payload: bytes) -> bytes:
        return (struct.pack(">I", len(payload)) + kind + payload +
                struct.pack(">I", zlib.crc32(kind + payload)))

    compressor = zlib.compressobj(level=6)
    row = b"\0" + b"\x48\x71\x5a" * width
    parts = [compressor.compress(row) for _ in range(height)]
    parts.append(compressor.flush())
    path.write_bytes(b"\x89PNG\r\n\x1a\n" +
                     chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)) +
                     chunk(b"IDAT", b"".join(parts)) + chunk(b"IEND", b""))


def boxes(data: bytes | bytearray, start: int, end: int):
    """Walk the ordinary 32-bit boxes produced by our short FFmpeg fixture."""
    pos = start
    while pos < end:
        if end - pos < 8:
            raise ValueError("incomplete MP4 box")
        size = int.from_bytes(data[pos:pos + 4], "big")
        if size < 8 or pos + size > end:
            raise ValueError("unexpected MP4 box size")
        yield pos, size, bytes(data[pos + 4:pos + 8])
        pos += size


def mp4_variants(source: Path, output: Path) -> None:
    """Make structurally controlled Nero-only and conflicting chapter fixtures.

    These changes only touch our own short, known FFmpeg output. Replacing boxes
    with equal-sized free boxes preserves all sample offsets; this is not a parser
    or editor for arbitrary user files.
    """
    data = bytearray(source.read_bytes())
    moovs = [(p, n) for p, n, t in boxes(data, 0, len(data)) if t == b"moov"]
    if len(moovs) != 1:
        raise ValueError("expected one moov")
    moov, moov_size = moovs[0]
    children = list(boxes(data, moov + 8, moov + moov_size))
    tracks = [(p, n) for p, n, t in children if t == b"trak"]
    chapter_track_ids = []
    track_ids = {}
    references = []
    for track, size in tracks:
        inner = list(boxes(data, track + 8, track + size))
        headers = [(p, n) for p, n, t in inner if t == b"tkhd"]
        if len(headers) != 1:
            raise ValueError("expected one track header")
        header, header_size = headers[0]
        version = data[header + 8]
        id_offset = header + (20 if version == 0 else 28 if version == 1 else header_size)
        if id_offset + 4 > header + header_size:
            raise ValueError("unexpected track header")
        track_ids[int.from_bytes(data[id_offset:id_offset + 4], "big")] = track
        for ref, ref_size, kind in inner:
            if kind != b"tref":
                continue
            for pos, box_size, box_type in boxes(data, ref + 8, ref + ref_size):
                if box_type == b"chap":
                    if box_size != 12:
                        raise ValueError("expected one chapter track reference")
                    chapter_track_ids.append(int.from_bytes(data[pos + 8:pos + 12], "big"))
                    references.append(ref)
    if len(chapter_track_ids) != 1 or chapter_track_ids[0] not in track_ids:
        raise ValueError("expected one referenced QuickTime chapter track")
    udtas = [(p, n) for p, n, t in children if t == b"udta"]
    if len(udtas) != 1:
        raise ValueError("expected one udta")
    udta, udta_size = udtas[0]
    chpls = [(p, n) for p, n, t in boxes(data, udta + 8, udta + udta_size) if t == b"chpl"]
    if len(chpls) != 1:
        raise ValueError("expected one Nero chapter atom")
    chpl, chpl_size = chpls[0]
    nero = bytearray(data)
    for pos in references + [track_ids[chapter_track_ids[0]]]:
        nero[pos + 4:pos + 8] = b"free"
    (output / "chapters-nero.m4b").write_bytes(nero)

    # Change only the Nero titles, without moving any box or sample offsets.
    # The QuickTime titles remain First/Second/Third to test precedence.
    conflict = bytearray(data)
    pos = chpl + 8
    if conflict[pos:pos + 4] != b"\x01\x00\x00\x00" or conflict[pos + 8] != 3:
        raise ValueError("unexpected Nero chapter header")
    pos += 9
    for original, replacement in ((b"First", b"Nero1"), (b"Second", b"Nero02"), (b"Third", b"Nero3")):
        if pos + 9 > chpl + chpl_size:
            raise ValueError("truncated Nero chapter")
        length = conflict[pos + 8]
        pos += 9
        if length != len(original) or conflict[pos:pos + length] != original:
            raise ValueError("unexpected Nero chapter title")
        conflict[pos:pos + length] = replacement
        pos += length
    if pos != chpl + chpl_size:
        raise ValueError("unexpected trailing Nero data")
    (output / "chapters-conflict.m4b").write_bytes(conflict)


def corrupt_id3_chapter(source: Path, destination: Path) -> None:
    """Make the second chapter end before it starts in our generated ID3v2.3 tag."""
    data = bytearray(source.read_bytes())
    if data[:4] != b"ID3\x03" or any(value & 0x80 for value in data[6:10]):
        raise ValueError("expected ID3v2.3 with synchsafe tag size")
    tag_size = sum(data[6 + index] << (7 * (3 - index)) for index in range(4))
    pos, end = 10, 10 + tag_size
    chapter_count = 0
    while pos + 10 <= end and data[pos:pos + 4] != b"\0\0\0\0":
        kind = data[pos:pos + 4]
        size = int.from_bytes(data[pos + 4:pos + 8], "big")
        payload, next_pos = pos + 10, pos + 10 + size
        if next_pos > end or next_pos > len(data):
            raise ValueError("invalid ID3 frame size")
        if kind == b"CHAP":
            chapter_count += 1
            name_end = data.find(b"\0", payload, next_pos)
            if name_end < 0 or name_end + 9 > next_pos:
                raise ValueError("invalid CHAP frame")
            if chapter_count == 2:
                start = int.from_bytes(data[name_end + 1:name_end + 5], "big")
                if start != 4000:
                    raise ValueError("unexpected second chapter start")
                data[name_end + 5:name_end + 9] = (1000).to_bytes(4, "big")
        pos = next_pos
    if chapter_count != 3:
        raise ValueError("expected three CHAP frames")
    destination.write_bytes(data)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path, help="new or empty output directory")
    args = parser.parse_args()
    for command in ("ffmpeg", "ffprobe"):
        if not shutil.which(command):
            parser.error(f"{command} must be installed")
    output = args.output.resolve()
    if output.exists() and (not output.is_dir() or any(output.iterdir())):
        parser.error("output must be a new or empty directory")
    output.mkdir(parents=True, exist_ok=True)

    # The audio is generated mathematically, with no copyrighted recording.
    # ffmetadata uses milliseconds so chapter boundaries are explicit.
    metadata = """;FFMETADATA1
title=Synthetic Book
artist=Fixture Author
[CHAPTER]
TIMEBASE=1/1000
START=0
END=4000
title=First
[CHAPTER]
TIMEBASE=1/1000
START=4000
END=8000
title=Second
[CHAPTER]
TIMEBASE=1/1000
START=8000
END=12000
title=Third
"""
    with tempfile.TemporaryDirectory(prefix="audiobook-fixtures-") as temp:
        meta = Path(temp) / "chapters.ffmeta"
        meta.write_text(metadata, encoding="utf-8")
        common = ("ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
                  "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100:duration=12")
        for name, codec, extra in (
            ("chapters.mp3", "libmp3lame", ("-b:a", "96k", "-id3v2_version", "3")),
            ("chapters-vbr.mp3", "libmp3lame", ("-q:a", "4", "-id3v2_version", "3")),
            ("chapters-both.m4b", "aac", ("-b:a", "96k")),
            ("chapters-quicktime.m4b", "aac", ("-b:a", "96k", "-movflags", "+disable_chpl")),
        ):
            run(*common, "-i", str(meta), "-map", "0:a", "-map_metadata", "1",
                "-map_chapters", "1", "-c:a", codec, *extra, str(output / name))
        for name, codec, extra in (
            ("no-chapters.wav", "pcm_s16le", ()),
            ("no-chapters.flac", "flac", ()),
            ("no-chapters.ogg", "libvorbis", ()),
            ("no-chapters-opus.ogg", "libopus", ()),
            ("no-chapters.m4a", "aac", ("-b:a", "96k")),
            ("unsupported-msadpcm.wav", "adpcm_ms", ()),
        ):
            run(*common, "-c:a", codec, *extra, str(output / name))
        for name, codec, extra in (("credits.mp3", "libmp3lame", ("-id3v2_version", "3")), ("credits.flac", "flac", ())):
            run(*common, "-map_metadata", "-1", "-c:a", codec, *extra,
                "-metadata", "artist=Synthetic Author", "-metadata", "NARRATOR=Synthetic Narrator",
                "-metadata", "TRANSLATOR=Synthetic Translator", "-metadata", "SERIES=Synthetic Series",
                "-metadata", "SERIES-PART=2.5", str(output / name))
        for name, side in (("cover.mp3", 32), ("cover-large.mp3", 4096)):
            cover = Path(temp) / f"cover-{side}.png"
            png(cover, side, side)
            run(*common, "-i", str(cover), "-map", "0:a", "-map", "1:v", "-c:a", "libmp3lame",
                "-b:a", "96k", "-c:v", "copy", "-id3v2_version", "3",
                "-metadata:s:v", "title=Cover", "-metadata:s:v", "comment=Cover (front)",
                str(output / name))

        for name,codec in (("metadata-vorbis.ogg","libvorbis"),("metadata-opus.ogg","libopus")):
            run(*common,"-c:a",codec,"-metadata","album=Shared Book","-metadata","artist=Doe, Jane",
                "-metadata","PERFORMER=Reader One","-metadata","PUBLISHER=Synthetic Press",
                "-metadata","GENRE=Fiction","-metadata","DATE=2020-02",
                "-metadata","DESCRIPTION=Synthetic description\nSecond line",str(output/name))
        run(*common,"-c:a","aac","-movflags","use_metadata_tags",
            "-metadata","album=Shared Book","-metadata","artist=Doe, Jane",
            "-metadata","narrator=Reader One","-metadata","translator=Translator One",
            "-metadata","series=Explicit Series","-metadata","series-part=2.5",
            "-metadata","publisher=Synthetic Press","-metadata","genre=Fiction",
            "-metadata","date=2020-02","-metadata","releasedate=2021-06-10",
            "-metadata","description=Synthetic description\nSecond line",str(output/"metadata-mdta.m4b"))
    relocate_mdta_fixture(output/"metadata-mdta.m4b",output/"metadata-mdta-moov.m4b")
    id3_fixture(output/"chapters.mp3",output/"metadata-v23.mp3",3)
    id3_fixture(output/"chapters.mp3",output/"metadata-v24.mp3",4)
    id3_fixture(output/"chapters.mp3",output/"metadata-html.mp3",4,description="<p>Synthetic <b>description</b><br>Second line</p>")
    id3_fixture(output/"chapters.mp3",output/"metadata-fallback.mp3",4,True)
    flac_fixture(output/"no-chapters.flac",output/"metadata.flac")
    mp4_fixture(output/"no-chapters.m4a",output/"metadata.m4b")
    mp4_fixture(output/"no-chapters.m4a",output/"metadata-movement.m4b",True)
    mp4_variants(output / "chapters-both.m4b", output)
    corrupt_id3_chapter(output / "chapters.mp3", output / "chapters-invalid.mp3")
    # A truncated MP4 is deliberately invalid; do not use it to establish chapter support.
    data = (output / "chapters-both.m4b").read_bytes()
    (output / "truncated.m4b").write_bytes(data[: min(256, len(data))])
    (output / "unsupported.xyz").write_bytes(b"NOT_AN_AUDIO_FILE_UNSUPPORTED_DATA\0" * 32)
    version = subprocess.check_output(["ffmpeg", "-version"], text=True).splitlines()[0]
    manifest = {
        "metadata_files": ["metadata-html.mp3", "metadata-mdta-moov.m4b", "metadata-mdta.m4b", "metadata-v23.mp3", "metadata-v24.mp3", "metadata-fallback.mp3", "metadata.flac", "metadata.m4b", "metadata-movement.m4b", "metadata-vorbis.ogg", "metadata-opus.ogg"],
        "generator": "tools/fixtures/generate.py",
        "ffmpeg_version": version,
        "source": "Generated 440 Hz sine wave; no third-party recording",
        "duration_seconds": 12,
        "chapter_timebase": "milliseconds",
        "chapters": [
            {"title": "First", "start": 0, "end": 4000},
            {"title": "Second", "start": 4000, "end": 8000},
            {"title": "Third", "start": 8000, "end": 12000},
        ],
        "chapter_files": ["chapters.mp3", "chapters-vbr.mp3", "chapters-both.m4b", "chapters-quicktime.m4b", "chapters-nero.m4b"],
        "conflicting_chapter_file": "chapters-conflict.m4b",
        "conflicting_nero_titles": ["Nero1", "Nero02", "Nero3"],
        "no_chapter_files": ["no-chapters.wav", "no-chapters.flac", "no-chapters.ogg", "no-chapters-opus.ogg", "no-chapters.m4a"],
        "unsupported_codec_files": ["unsupported-msadpcm.wav"],
        "invalid_files": ["truncated.m4b", "unsupported.xyz"],
        "invalid_chapter_file": "chapters-invalid.mp3",
        "artwork_files": {"cover.mp3": [32, 32], "cover-large.mp3": [4096, 4096]},
    }
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"Generated fixtures in {output}")


if __name__ == "__main__":
    main()
