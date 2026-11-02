"""
Reverse-engineering inspector for GSM Drive Test: 5G LTE RF (v1.8.0 XAPK).
Extracts manifest metadata, Room SQLite schema, Kotlin data classes, enums,
default thresholds, and export templates from com.drivetest.app.apk.
"""
import io
import json
import struct
import zipfile
from pathlib import Path

XAPK_PATH = Path(r"d:\APP\Drive-Test-5G-LTE-RF\GSM+Drive+Test_+5G+LTE+RF_1.8.0.xapk")


def read_uleb128(b: bytes, off: int) -> tuple[int, int]:
    res, shift = 0, 0
    while True:
        byte = b[off]
        off += 1
        res |= (byte & 0x7F) << shift
        if (byte & 0x80) == 0:
            break
        shift += 7
    return res, off


def analyze_xapk(xapk_path: Path) -> dict:
    with zipfile.ZipFile(xapk_path, "r") as z:
        manifest = json.loads(z.read("manifest.json").decode("utf-8"))
        apk_bytes = z.read("com.drivetest.app.apk")

    with zipfile.ZipFile(io.BytesIO(apk_bytes), "r") as apk:
        dex = apk.read("classes.dex")

    string_ids_size, string_ids_off = struct.unpack("<II", dex[56:64])
    type_ids_size, type_ids_off = struct.unpack("<II", dex[64:72])

    strings: list[str] = []
    for i in range(string_ids_size):
        s_off = struct.unpack("<I", dex[string_ids_off + i * 4 : string_ids_off + (i + 1) * 4])[0]
        _, pos = read_uleb128(dex, s_off)
        end = dex.find(b"\x00", pos)
        strings.append(dex[pos:end].decode("utf-8", errors="replace"))

    types = [
        strings[struct.unpack("<I", dex[type_ids_off + i * 4 : type_ids_off + (i + 1) * 4])[0]]
        for i in range(type_ids_size)
    ]

    sql_tables = [
        s for s in strings
        if s.startswith("CREATE TABLE IF NOT EXISTS `")
        and any(t in s for t in ("campaigns", "measurements", "session_events", "speed_tests"))
    ]

    export_strings = [
        s for s in strings
        if any(k in s.lower() for k in ("<kml", "<placemark", "geojson", "featurecollection", "timestamp,latitude", "campaign_"))
        and len(s) < 300
    ]

    return {
        "manifest": manifest,
        "dex_string_count": len(strings),
        "dex_type_count": len(types),
        "sql_tables": sql_tables,
        "export_strings": export_strings,
    }


if __name__ == "__main__":
    info = analyze_xapk(XAPK_PATH)
    print(json.dumps(info, indent=2, ensure_ascii=False))

