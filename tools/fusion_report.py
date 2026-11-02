#!/usr/bin/env python3
"""Summarise Fusion Probe runs (Phase 6, docs/06-fusion-mode-feasibility.md).

Each run folder (pulled from /sdcard/Android/data/app.vynq.fusion/files/fusion/)
holds probes.csv (one row per probe: seq,path,send_ms,rtt_ms,status) and
events.csv. Probes leave both paths at the same moment (same seq), so we can
also estimate, from the same data:

  * best-path selection: each second, use the path that did better in the
    previous second (technique 1, without the cost of switching);
  * duplication: send every packet on both paths; a packet arrives if either
    copy does, with the faster copy's delay (technique 2, before any relay
    overhead).

These are estimates from probe timing, not a real duplicated media stream.

    python tools/fusion_report.py <run-folder> [...]
"""
import csv
import os
import statistics
import sys
from collections import defaultdict

OUTAGE_MS = 300   # consecutive missing probes covering at least this long = an outage
LATE_MS = 150     # a reply slower than this is too late for real-time media


def load(folder):
    probes = defaultdict(dict)   # path -> seq -> (send_ms, rtt or None)
    with open(os.path.join(folder, "probes.csv"), encoding="utf-8") as f:
        for r in csv.DictReader(f):
            rtt = float(r["rtt_ms"]) if r["status"] == "ok" and r["rtt_ms"] else None
            probes[r["path"]][int(r["seq"])] = (int(r["send_ms"]), rtt)
    events = []
    ev = os.path.join(folder, "events.csv")
    if os.path.exists(ev):
        with open(ev, encoding="utf-8") as f:
            events = [(int(r["t_ms"]), r["event"]) for r in csv.DictReader(f)]
    return probes, events


def pct(values, p):
    values = sorted(values)
    return values[min(len(values) - 1, int(len(values) * p))] if values else None


def outages(series, interval_ms=100):
    """series: list of (seq, rtt or None) sorted by seq -> list of outage lengths in ms."""
    out, run = [], 0
    for _, rtt in series:
        if rtt is None:
            run += 1
        else:
            if run * interval_ms >= OUTAGE_MS:
                out.append(run * interval_ms)
            run = 0
    if run * interval_ms >= OUTAGE_MS:
        out.append(run * interval_ms)
    return out


def stats(name, series):
    rtts = [r for _, r in series if r is not None]
    n = len(series)
    lost = n - len(rtts)
    o = outages(series)
    late = sum(1 for r in rtts if r > LATE_MS)
    return {
        "name": name, "n": n,
        "loss": 100.0 * lost / n if n else None,
        "p50": pct(rtts, 0.50), "p95": pct(rtts, 0.95), "p99": pct(rtts, 0.99),
        "late": 100.0 * late / n if n else None,
        "outages": len(o), "longest": max(o) if o else 0,
    }


def fmt(v, d=0):
    return "—" if v is None else f"{v:.{d}f}"


def summarise(folder):
    probes, events = load(folder)
    rows = []
    for path in sorted(probes):
        rows.append(stats(path, sorted((s, v[1]) for s, v in probes[path].items())))
    if {"wifi", "cell"} <= set(probes):
        common = sorted(set(probes["wifi"]) & set(probes["cell"]))
        w = {s: probes["wifi"][s][1] for s in common}
        c = {s: probes["cell"][s][1] for s in common}
        # Duplication: either copy arrives, the faster one counts.
        dup = [(s, min(x for x in (w[s], c[s]) if x is not None) if (w[s] is not None or c[s] is not None) else None) for s in common]
        # Best path: per 10-probe second, use the path with fewer losses (then lower median) last second.
        best, choice = [], "wifi"
        by_sec = defaultdict(list)
        for s in common:
            by_sec[s // 10].append(s)
        for sec in sorted(by_sec):
            for s in by_sec[sec]:
                best.append((s, w[s] if choice == "wifi" else c[s]))

            def score(d):
                vals = [d[s] for s in by_sec[sec]]
                got = [v for v in vals if v is not None]
                return (len(vals) - len(got), statistics.median(got) if got else 1e9)
            choice = "wifi" if score(w) <= score(c) else "cell"
        rows.append(stats("best path (est.)", best))
        rows.append(stats("both, duplicated (est.)", dup))
    return rows, events


def main(argv):
    sys.stdout.reconfigure(encoding="utf-8")
    if not argv:
        print(__doc__)
        return 1
    for folder in argv:
        rows, events = summarise(folder)
        print(f"\n### {os.path.basename(os.path.normpath(folder))}\n")
        print(f"| Path | Probes | Loss % | RTT p50 / p95 / p99 ms | Late >{LATE_MS} ms % | Outages ≥{OUTAGE_MS} ms (n / longest ms) |")
        print("|---|---|---|---|---|---|")
        for r in rows:
            print(f'| {r["name"]} | {r["n"]} | {fmt(r["loss"], 2)} | {fmt(r["p50"])} / {fmt(r["p95"])} / {fmt(r["p99"])} '
                  f'| {fmt(r["late"], 2)} | {r["outages"]} / {r["longest"]} |')
        interesting = [e for e in events if "lost" in e[1] or "available" in e[1]]
        if interesting:
            print("\nNetwork events: " + "; ".join(f"{t / 1000:.1f} s {e}" for t, e in interesting))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
