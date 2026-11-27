"""Convert Firebase RTDB fleet trail JSON exports into CSV and KMZ files."""

from __future__ import annotations

import argparse
import csv
import datetime as dt
import html
import json
import zipfile
from pathlib import Path
from typing import Any

TZ_BANGKOK = dt.timezone(dt.timedelta(hours=7))

# Matches KmlExporter.kt (AABBGGRR for KML, #RRGGBB for HTML balloons)
KML_COLORS = ("ff81b910", "ff5ec522", "ff08b3ea", "ff1673f9", "ff4444ef")
HTML_COLORS = ("#10b981", "#22c55e", "#eab308", "#f97316", "#ef4444")
LINE_WIDTH = 6


def tier_index(rsrp: int) -> int:
    if rsrp >= -85:
        return 1
    if rsrp >= -95:
        return 2
    if rsrp >= -105:
        return 3
    if rsrp >= -115:
        return 4
    return 5


def load_trail_points(json_path: Path, session_label: str) -> list[dict[str, Any]]:
    with json_path.open("r", encoding="utf-8") as fh:
        raw = json.load(fh)

    items = raw.items() if isinstance(raw, dict) else enumerate(raw)
    points: list[dict[str, Any]] = []
    for key, val in items:
        if not isinstance(val, dict):
            continue
        lat = val.get("lat")
        lon = val.get("lon")
        ts = val.get("ts")
        if lat is None or lon is None or ts is None:
            continue
        points.append(
            {
                "id": str(key),
                "session": session_label,
                "ts": int(ts),
                "lat": float(lat),
                "lon": float(lon),
                "speed": float(val.get("speed") or 0),
                "rat": str(val.get("rat") or "UNKNOWN"),
                "pci": int(val.get("pci")) if val.get("pci") is not None else 0,
                "rsrp": int(val.get("rsrp")) if val.get("rsrp") is not None else -140,
            }
        )

    points.sort(key=lambda p: (p["ts"], p["id"]))
    return points


def format_times(ts_ms: int) -> tuple[str, str]:
    utc_dt = dt.datetime.fromtimestamp(ts_ms / 1000.0, tz=dt.timezone.utc)
    local_dt = utc_dt.astimezone(TZ_BANGKOK)
    return utc_dt.strftime("%Y-%m-%dT%H:%M:%SZ"), local_dt.strftime("%Y-%m-%d %H:%M:%S")


def write_csv(points: list[dict[str, Any]], out_path: Path) -> None:
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with out_path.open("w", encoding="utf-8-sig", newline="") as fh:
        writer = csv.writer(fh)
        writer.writerow(
            [
                "Session",
                "FirebaseKey",
                "Timestamp_ms",
                "DateTime_TH",
                "DateTime_UTC",
                "Latitude",
                "Longitude",
                "Speed_kmh",
                "RAT",
                "PCI",
                "RSRP_dBm",
                "RSRP_Tier",
            ]
        )
        for p in points:
            utc_str, local_str = format_times(p["ts"])
            writer.writerow(
                [
                    p["session"],
                    p["id"],
                    p["ts"],
                    local_str,
                    utc_str,
                    f"{p['lat']:.7f}",
                    f"{p['lon']:.7f}",
                    f"{p['speed']:.1f}",
                    p["rat"],
                    p["pci"],
                    p["rsrp"],
                    tier_index(p["rsrp"]),
                ]
            )


def kml_header(doc_name: str, description: str) -> str:
    parts = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        '<kml xmlns="http://www.opengis.net/kml/2.2">',
        "<Document>",
        f"    <name>{html.escape(doc_name)}</name>",
        f"    <description>{html.escape(description)}</description>",
    ]
    for idx, color in enumerate(KML_COLORS, start=1):
        parts.append(
            f'    <Style id="line_tier{idx}"><LineStyle><color>{color}</color><width>{LINE_WIDTH}</width></LineStyle></Style>'
        )
    for idx, color in enumerate(KML_COLORS, start=1):
        parts.append(
            f"""    <Style id="point_tier{idx}">
        <IconStyle>
            <color>{color}</color>
            <scale>0.6</scale>
            <Icon><href>http://maps.google.com/mapfiles/kml/shapes/placemark_circle.png</href></Icon>
        </IconStyle>
    </Style>"""
        )
    parts.append(
        """    <Style id="marker_start">
        <IconStyle>
            <scale>1.1</scale>
            <Icon><href>http://maps.google.com/mapfiles/kml/paddle/grn-circle.png</href></Icon>
        </IconStyle>
    </Style>
    <Style id="marker_end">
        <IconStyle>
            <scale>1.1</scale>
            <Icon><href>http://maps.google.com/mapfiles/kml/paddle/red-circle.png</href></Icon>
        </IconStyle>
    </Style>"""
    )
    return "\n".join(parts)


def render_session_folder(points: list[dict[str, Any]], folder_name: str) -> str:
    if not points:
        return ""
    chunks: list[str] = [f"    <Folder>\n        <name>{html.escape(folder_name)}</name>"]

    first, last = points[0], points[-1]
    _, first_local = format_times(first["ts"])
    _, last_local = format_times(last["ts"])
    chunks.append(
        f"""        <Placemark>
            <name>START ({html.escape(first_local)})</name>
            <styleUrl>#marker_start</styleUrl>
            <Point><coordinates>{first['lon']},{first['lat']},0</coordinates></Point>
        </Placemark>
        <Placemark>
            <name>END ({html.escape(last_local)})</name>
            <styleUrl>#marker_end</styleUrl>
            <Point><coordinates>{last['lon']},{last['lat']},0</coordinates></Point>
        </Placemark>"""
    )

    chunks.append("        <Folder>\n            <name>Route Track</name>")
    for i in range(len(points) - 1):
        p1, p2 = points[i], points[i + 1]
        # Avoid drawing straight jump lines across huge time gaps (> 5 minutes)
        if p2["ts"] - p1["ts"] > 300_000:
            continue
        tier = tier_index(p1["rsrp"])
        chunks.append(
            f"""            <Placemark>
                <name>Segment #{i} ({p1['rsrp']} dBm)</name>
                <styleUrl>#line_tier{tier}</styleUrl>
                <LineString>
                    <tessellate>1</tessellate>
                    <coordinates>{p1['lon']},{p1['lat']},0 {p2['lon']},{p2['lat']},0</coordinates>
                </LineString>
            </Placemark>"""
        )
    chunks.append("        </Folder>")

    chunks.append("        <Folder>\n            <name>Survey Points</name>")
    for p in points:
        utc_str, local_str = format_times(p["ts"])
        tier = tier_index(p["rsrp"])
        color_hex = HTML_COLORS[tier - 1]
        rat_esc = html.escape(p["rat"])
        sess_esc = html.escape(p["session"])
        chunks.append(
            f"""            <Placemark>
                <name>{rat_esc} PCI:{p['pci']} ({p['rsrp']}dBm)</name>
                <styleUrl>#point_tier{tier}</styleUrl>
                <description><![CDATA[
                    <div style="font-family:Arial,sans-serif; min-width:220px; color:#0f172a;">
                        <div style="background-color:#1e293b; color:white; padding:8px 12px;">
                            <b>{sess_esc} — {rat_esc}</b><br/>
                            <span style="font-size:11px; color:#94a3b8;">{local_str} (+07:00)</span>
                        </div>
                        <table border="1" cellpadding="5" cellspacing="0" style="border-collapse:collapse; width:100%; font-size:12px;">
                            <tr><td><b>RSRP</b></td><td><b style="color:{color_hex};">{p['rsrp']} dBm</b></td></tr>
                            <tr><td><b>PCI</b></td><td>{p['pci']}</td></tr>
                            <tr><td><b>Speed</b></td><td>{p['speed']:.1f} km/h</td></tr>
                        </table>
                    </div>
                ]]></description>
                <TimeStamp><when>{utc_str}</when></TimeStamp>
                <Point><coordinates>{p['lon']},{p['lat']},0</coordinates></Point>
                <ExtendedData>
                    <Data name="Session"><value>{sess_esc}</value></Data>
                    <Data name="RAT"><value>{rat_esc}</value></Data>
                    <Data name="PCI"><value>{p['pci']}</value></Data>
                    <Data name="RSRP_dBm"><value>{p['rsrp']}</value></Data>
                    <Data name="Speed_kmh"><value>{p['speed']:.1f}</value></Data>
                </ExtendedData>
            </Placemark>"""
        )
    chunks.append("        </Folder>")
    chunks.append("    </Folder>")
    return "\n".join(chunks)


def write_kmz(
    sessions: list[tuple[str, list[dict[str, Any]]]],
    out_path: Path,
    doc_name: str,
    description: str,
) -> None:
    out_path.parent.mkdir(parents=True, exist_ok=True)
    kml_parts = [kml_header(doc_name, description)]
    for label, pts in sessions:
        kml_parts.append(render_session_folder(pts, label))
    kml_parts.append("</Document>\n</kml>")
    kml_bytes = "\n".join(kml_parts).encode("utf-8")

    with zipfile.ZipFile(out_path, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("doc.kml", kml_bytes)


def main() -> None:
    parser = argparse.ArgumentParser(description="Convert Firebase trail JSONs to CSV and KMZ.")
    parser.add_argument("--out-dir", default="exports", help="Output directory")
    parser.add_argument("--date", default="2026-11-26", help="Date prefix for output filenames (YYYY-MM-DD)")
    parser.add_argument(
        "--session",
        action="append",
        nargs=3,
        metavar=("JSON_PATH", "SLUG", "LABEL"),
        help="Session spec: <json_path> <slug> <label>. Repeat for multiple sessions.",
    )
    args = parser.parse_args()

    out_dir = Path(args.out_dir)
    specs = args.session or [
        ("1795655977938.json", "morning_1795655977938", "Morning 26/11/69"),
        ("1795667466126.json", "afternoon_1795667466126", "Afternoon 26/11/69_2"),
    ]

    loaded: list[tuple[str, str, list[dict[str, Any]]]] = []
    all_pts: list[dict[str, Any]] = []
    for json_path_str, slug, label in specs:
        pts = load_trail_points(Path(json_path_str), label)
        loaded.append((slug, label, pts))
        all_pts.extend(pts)

    all_pts.sort(key=lambda p: (p["ts"], p["id"]))

    jobs: list[tuple[str, str, list[tuple[str, list[dict[str, Any]]]], list[dict[str, Any]]]] = [
        (f"{args.date}_{slug}", f"Drive Test {args.date} ({label})", [(label, pts)], pts)
        for slug, label, pts in loaded
    ]
    if len(loaded) > 1:
        jobs.append(
            (
                f"{args.date}_all_combined",
                f"Drive Test {args.date} (Combined)",
                [(label, pts) for _, label, pts in loaded],
                all_pts,
            )
        )

    for stem, title, sessions, flat_pts in jobs:
        csv_path = out_dir / f"{stem}.csv"
        kmz_path = out_dir / f"{stem}.kmz"
        write_csv(flat_pts, csv_path)
        write_kmz(sessions, kmz_path, title, f"{len(flat_pts)} points from Firebase RTDB")
        print(
            f"{stem}: {len(flat_pts)} pts -> {csv_path} ({csv_path.stat().st_size:,} B), {kmz_path} ({kmz_path.stat().st_size:,} B)"
        )


if __name__ == "__main__":
    main()
