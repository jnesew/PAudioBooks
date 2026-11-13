"""Synthetic metadata variants only; never used to parse or modify user media."""
import struct


def synchsafe(value):
    return bytes((value >> shift) & 127 for shift in (21, 14, 7, 0))


def id3_fixture(source, output, version, fallback=False, description="Synthetic description\nSecond line"):
    audio = source.read_bytes()
    size = sum(audio[6+i] << (7*(3-i)) for i in range(4))
    audio = audio[10+size:]
    def frame(kind, values):
        text = "\0".join(values)
        payload = (b"\x03" + text.encode("utf-8")) if version == 4 else (b"\x01" + b"\0\0".join(value.encode("utf-16") for value in values))
        length = synchsafe(len(payload)) if version == 4 else struct.pack(">I", len(payload))
        return kind.encode("ascii") + length + b"\0\0" + payload
    fields = [("TIT2", ["Chapter One"]), ("TALB", ["Shared Book"]),
              ("TPE1", ["Doe, Jane", "Ánne"] if version == 4 else ["Doe, Jane"]),
              ("TPE2", ["Album fallback"]), ("TCOM", ["Composer reader"]),
              ("TPUB", ["Synthetic Press"]), ("TCON", ["Fiction"]),
              ("TDRC" if version == 4 else "TYER", ["2020-02" if version == 4 else "2020"]),
              ("TXXX", ["DESCRIPTION", description]),
              ("MVNM", ["Movement Series"]), ("MVIN", ["7"])]
    if fallback:
        fields = [(k,v) for k,v in fields if k != "TPE1"]
    if not fallback:
        fields += [("TXXX", ["NARRATOR", "Reader One", "Reader Two"] if version == 4 else ["NARRATOR", "Reader One"]),
                   ("TIPL" if version == 4 else "IPLS", ["translator", "Translator One"]),
                   ("TXXX", ["SERIES", "Explicit Series"]), ("TXXX", ["SERIES-PART", "2.5"])]
    if version == 4:
        fields += [("TDRL", ["2021-06-10"])]
    body = b"".join(frame(k,v) for k,v in fields)
    output.write_bytes(b"ID3" + bytes((version,0,0)) + synchsafe(len(body)) + body + audio)


def flac_fixture(source, output):
    data = source.read_bytes()
    assert data[:4] == b"fLaC"
    entries = ["ALBUM=Shared Book", "ARTIST=Doe, Jane", "artist=Ánne", "ARTIST=Doe, Jane",
               "PERFORMER=Reader One", "performer=Reader Two", "COMPOSER=Composer reader",
               "TRANSLATOR=Translator One", "SERIES=Explicit Series", "SERIES-PART=2.5",
               "GENRE=Fiction", "ORGANIZATION=Synthetic Press", "DATE=2020-02",
               "RELEASEDATE=2021-06-10", "DESCRIPTION=Synthetic description\nSecond line"]
    vendor = b"PAudioBooks synthetic fixture"
    comment = struct.pack("<I",len(vendor)) + vendor + struct.pack("<I",len(entries))
    for entry in entries:
        encoded = entry.encode("utf-8"); comment += struct.pack("<I",len(encoded)) + encoded
    blocks=[]; pos=4
    while True:
        header=data[pos]; size=int.from_bytes(data[pos+1:pos+4],"big")
        blocks.append((header & 127,comment if header & 127 == 4 else data[pos+4:pos+4+size]))
        pos += 4+size
        if header & 128: break
    output.write_bytes(b"fLaC" + b"".join(bytes((kind | (128 if i == len(blocks)-1 else 0),)) + len(body).to_bytes(3,"big") + body for i,(kind,body) in enumerate(blocks)) + data[pos:])


def mp4_fixture(source, output, movement=False):
    from generate import boxes
    def atom(kind,payload): return struct.pack(">I",len(payload)+8)+kind+payload
    def text(kind,value): return atom(kind,atom(b"data",b"\0\0\0\x01\0\0\0\0"+value.encode("utf-8")))
    def free(key,value):
        return atom(b"----",atom(b"mean",b"\0"*4+b"com.apple.iTunes")+atom(b"name",b"\0"*4+key.encode())+atom(b"data",b"\0\0\0\x01\0\0\0\0"+value.encode("utf-8")))
    ilst = text(b"\xa9nam","Chapter One")+text(b"\xa9alb","Shared Book")+text(b"\xa9ART","Artist fallback")+text(b"aART","Album fallback")
    ilst += text(b"\xa9wrt","Composer reader")+text(b"\xa9gen","Fiction")+text(b"\xa9day","2020-02")+text(b"\xa9mvn","Movement Series")
    ilst += atom(b"\xa9mvi",atom(b"data",b"\0\0\0\x15\0\0\0\0\x07"))
    for key,value in [("AUTHOR","Doe, Jane"),("TRANSLATOR","Translator One"),("PUBLISHER","Synthetic Press"),("RELEASEDATE","2021-06-10"),("DESCRIPTION","Synthetic description\nSecond line")]:
        ilst += free(key,value)
    if not movement:
        for key,value in [("NARRATOR","Reader One"),("SERIES","Explicit Series"),("SERIES-PART","2.5")]: ilst += free(key,value)
    replacement=atom(b"udta",atom(b"meta",b"\0"*4+atom(b"hdlr",b"\0"*8+b"mdir"+b"\0"*12)+atom(b"ilst",ilst)))
    data=source.read_bytes(); result=b""
    for pos,size,kind in boxes(data,0,len(data)):
        if kind == b"moov":
            assert pos+size == len(data), "Synthetic moov must follow sample bytes"
            body=b"".join(data[p:p+n] for p,n,k in boxes(data,pos+8,pos+size) if k != b"udta")
            result += atom(kind,body+replacement)
        else: result += data[pos:pos+size]
    output.write_bytes(result)


def relocate_mdta_fixture(source, output):
    """Explicit moov/meta variant; retain the original FFmpeg udta/meta limitation fixture."""
    from generate import boxes
    def atom(kind,payload): return struct.pack(">I",len(payload)+8)+kind+payload
    data=source.read_bytes(); result=b""
    for p,n,k in boxes(data,0,len(data)):
        if k != b"moov": result += data[p:p+n]; continue
        assert p+n == len(data)
        body=b""; metadata=None
        for pos,size,kind in boxes(data,p+8,p+n):
            if kind == b"udta":
                for m,l,t in boxes(data,pos+8,pos+size):
                    if t == b"meta": metadata=data[m:m+l]
            else: body += data[pos:pos+size]
        assert metadata is not None
        result += atom(b"moov",body+metadata)
    output.write_bytes(result)
