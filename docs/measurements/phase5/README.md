# Phase 5 call measurements (2026-11-02)

One CSV per phone per call, written by `CallMetrics.kt` (debug builds): one row per second, numbers from WebRTC's stats plus Android's thermal status and the battery. No names, ids or content. Summarise with:

```
python tools/call_report.py docs/measurements/phase5
```

| File suffix | Call |
|---|---|
| `182203` | Test A: video, Wi-Fi, both phones on Maximum, 3.7 min (realme sends 1440p, Xiaomi sends 1080p). Recorded with the first recorder version, before the live-stream fix. |
| `184954` | Voice call, 1.7 min (no video). |
| `185259` | Heat step-down test: video, realme on Maximum (1440p), step-down triggered by hand at ~6 s. |
| `185643` | Test C: video, both phones on Automatic, 0.5 min. |

Phones: Xiaomi 17 Pro (`25098PN5AC`, Snapdragon 8 Elite Gen 5, HyperOS CN), realme 14x 5G (`RMX3943`, Dimensity 6300, ColorOS). Same Wi-Fi, LiveKit Cloud. The Xiaomi's battery rose during test A (likely charging), so its battery column doesn't show drain.
