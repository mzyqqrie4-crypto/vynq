#!/usr/bin/env python3
"""Summarise vynq call measurements (Phase 5).

Debug builds write one CSV row per second of each call to
/sdcard/Android/data/app.vynq.debug/files/call-metrics/ (see CallMetrics.kt).
Pull them with:

    adb -s <phone> pull /sdcard/Android/data/app.vynq.debug/files/call-metrics <dir>

then:

    python tools/call_report.py <dir-or-csv> [...] [--label NAME=file.csv ...]

Prints one Markdown table row per call. Numbers come straight from WebRTC's
stats; nothing is estimated. The first 10 s are left out of the averages
(bandwidth ramp-up) but counted for freezes.
"""
import csv
import glob
import os
import statistics
import sys

WARMUP_S = 10.0


def num(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def mode(values):
    values = [v for v in values if v]
    return statistics.mode(values) if values else "—"


def avg(values):
    values = [v for v in values if v is not None]
    return statistics.fmean(values) if values else None


def pct(values, p):
    values = sorted(v for v in values if v is not None)
    if not values:
        return None
    return values[min(len(values) - 1, int(len(values) * p))]


def fmt(v, digits=0):
    return "—" if v is None else f"{v:.{digits}f}"


def summarise(path):
    with open(path, encoding="utf-8") as f:
        meta_line = f.readline().strip()
        rows = list(csv.DictReader(f))
    meta = dict(p.split("=", 1) for p in meta_line.lstrip("# ").split(" ") if "=" in p)
    if not rows or (num(rows[-1]["t_s"]) or 0) < 20:
        return None   # empty or a call that never really started
    steady = [r for r in rows if (num(r["t_s"]) or 0) >= WARMUP_S] or rows
    duration = num(rows[-1]["t_s"]) or 0
    freezes = max((num(r["freezes"]) or 0) for r in rows)
    freeze_s = max((num(r["freeze_s"]) or 0) for r in rows)
    temps = [num(r["battery_c"]) for r in rows if num(r["battery_c"]) is not None]
    batt = [num(r["battery_pct"]) for r in rows if num(r["battery_pct"]) is not None]
    return {
        "file": os.path.basename(path),
        "model": meta.get("model", "?"),
        "quality": meta.get("quality", "?"),
        "min": duration / 60,
        "recv_res": mode(f'{r["recv_w"]}×{r["recv_h"]}' if r["recv_w"] else "" for r in steady),
        "recv_fps": avg(num(r["recv_fps"]) for r in steady),
        "recv_fps_p10": pct([num(r["recv_fps"]) for r in steady], 0.10),
        "recv_kbps": avg(num(r["recv_kbps"]) for r in steady),
        "freezes": freezes,
        "freeze_s": freeze_s,
        "loss": avg(num(r["loss_pct"]) for r in steady),
        "rtt": pct([num(r["rtt_ms"]) for r in steady], 0.5),
        "send_res": mode(f'{r["send_w"]}×{r["send_h"]}' if r["send_w"] else "" for r in steady),
        "send_fps": avg(num(r["send_fps"]) for r in steady),
        "send_kbps": avg(num(r["send_kbps"]) for r in steady),
        "codec": f'{mode(r["send_codec"] for r in steady)}/{mode(r["recv_codec"] for r in steady)}',
        "thermal": max((int(num(r["thermal"]) or 0) for r in rows), default=0),
        "temp": f"{fmt(temps[0], 1)}→{fmt(max(temps), 1)}" if temps else "—",
        "battery": (batt[0] - batt[-1]) if batt else None,
    }


HEADER = ("| Call | Phone | Quality | Min | Recv res | Recv fps (avg / p10) | Recv kbps | Freezes (n / s) | Loss % | RTT ms "
          "| Send res | Send fps | Send kbps | Codec send/recv | Max thermal | Battery °C | Battery used % |")


def main(argv):
    sys.stdout.reconfigure(encoding="utf-8")
    paths, labels = [], {}
    args = iter(argv)
    for a in args:
        if a == "--label":
            name, file = next(args).split("=", 1)
            labels[os.path.basename(file)] = name
        elif os.path.isdir(a):
            paths += sorted(glob.glob(os.path.join(a, "**", "*.csv"), recursive=True))
        else:
            paths.append(a)
    if not paths:
        print(__doc__)
        return 1
    print(HEADER)
    print("|" + "---|" * (HEADER.count("|") - 1))
    for p in paths:
        s = summarise(p)
        if not s:
            continue
        print(f'| {labels.get(s["file"], s["file"])} | {s["model"]} | {s["quality"]} | {fmt(s["min"], 1)} | {s["recv_res"]} '
              f'| {fmt(s["recv_fps"])} / {fmt(s["recv_fps_p10"])} | {fmt(s["recv_kbps"])} | {int(s["freezes"])} / {fmt(s["freeze_s"], 1)} '
              f'| {fmt(s["loss"], 1)} | {fmt(s["rtt"])} | {s["send_res"]} | {fmt(s["send_fps"])} | {fmt(s["send_kbps"])} '
              f'| {s["codec"]} | {s["thermal"]} | {s["temp"]} | {fmt(s["battery"])} |')
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
