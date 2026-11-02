# 06 — Fusion Mode: feasibility

**Status: Phase 6 measured (2026-11-02), partly. Decision: don't ship Fusion Mode now (see the end). The notes below were written before the experiment.**

## What the platforms actually allow

**Android**: feasible for path diversity.
- `ConnectivityManager.requestNetwork(TRANSPORT_CELLULAR + INTERNET)` keeps mobile data up while Wi-Fi is the default. The returned `Network` can `bindSocket()` or create a `DatagramSocket` bound to cellular. Requires `CHANGE_NETWORK_STATE`. Costs radio power and data.
- libwebrtc gathers ICE candidates on all active interfaces. With "continual gathering" plus ICE restart, a call can fail over from Wi-Fi to LTE without dropping. LiveKit already performs ICE restarts on network change. **This failover is the realistic first win and largely comes with the stack.**
- True per-packet scheduling across both paths is not exposed by libwebrtc. It would need a custom transport underneath WebRTC, i.e. a tunnel (below).

**iOS**: restricted.
- WebRTC on iOS follows the system default path. Apps cannot bind arbitrary UDP sockets to cellular while Wi-Fi is up. The exception is `Network.framework` with `requiredInterfaceType = .cellular`, which works for our own `NWConnection`s but not inside libwebrtc without patching it.
- Multipath TCP (`multipathServiceType` = handover/interactive) is supported for URLSession/NWConnection, but **`.aggregate` mode is restricted to development/testing**. It is also TCP, so a poor fit for real-time media.
- Wi-Fi Assist already switches to cellular at the OS level.

## Candidate techniques (to be measured)
1. **Smart single-path selection + fast failover** (both platforms): monitor RTT/loss per path, pre-warm the secondary, trigger an ICE restart early. *Most likely to ship.*
2. **Selective duplication** (Android only): duplicate audio packets (small, latency-critical) over a cellular-bound relay tunnel during Wi-Fi degradation, and de-duplicate at a relay. Needs our own UDP relay. Data cost is bounded because audio is ~32–64 kbps.
3. **Bonded throughput** via a multipath QUIC tunnel to a relay (e.g. a MASQUE-style proxy). High complexity, adds latency from the extra hop and reordering, and the iOS limits above make it Android-only. *Unlikely to beat option 1 for calls.*

## Experiment plan (Phase 6)
A standalone Android app in `experiments/fusion/` plus a small UDP echo/relay server. Scenarios: Wi-Fi only, LTE only, both, Wi-Fi drop, induced loss/jitter (via a `tc netem` relay), and changing quality. Metrics: RTT p50/p95, loss, throughput, recovery time, freeze count (in a WebRTC loopback), data use and battery (Battery Historian). Ship criterion: statistically meaningful improvement in recovery time or freezes, with bounded data cost.

## Phase 6 results (2026-11-02)

**Setup (owner decisions: no relay server; only the Xiaomi has mobile data).** `experiments/fusion` (Fusion Probe, a separate app) keeps mobile data up next to Wi-Fi with `requestNetwork`, binds one UDP socket to each network and sends a STUN Binding request every 100 ms on a shared clock to `stun.cloudflare.com:3478`, recording the round trip, "lost" (no reply in 1 s) or "down" (can't send). `tools/fusion_report.py` summarises a run and estimates, from the same simultaneous probes, **best-path selection** (each second use the path that did better the previous second) and **duplication** (a packet arrives if either copy does, the faster counts). These are timing estimates, not a real duplicated media stream, and they leave out a relay's extra hop. Raw data: `docs/measurements/phase6/`. Phone: Xiaomi 17 Pro on home Wi-Fi + LTE; probes cost about 1.2 KB/s per path.

**Run 1 — both paths, phone untouched, 10 min (6,000 probes per path)**

| Path | Loss % | RTT p50 / p95 / p99 ms | Late >150 ms % | Outages ≥300 ms (n / longest) |
|---|---|---|---|---|
| Mobile data (LTE) | 0.20 | 34 / 52 / 56 | 0.08 | 1 / 1.1 s |
| Wi-Fi | 1.58 | 18 / 51 / 109 | 0.23 | 5 / 0.5 s |
| Best path (est.) | 1.05 | 19 / 50 / 94 | 0.15 | 3 / 0.6 s |
| Duplicated on both (est.) | **0.00** | 18 / 38 / 49 | 0.00 | **0** |

**Run 2 — Wi-Fi switched off on the phone from 109.5 s to 174.4 s, 5 min**

| Path | Loss % | RTT p50 / p95 / p99 ms | Late >150 ms % | Outages ≥300 ms (n / longest) |
|---|---|---|---|---|
| Mobile data (LTE) | 8.53 | 33 / 242 / 532 | 6.36 | 8 / 8.8 s |
| Wi-Fi | 22.88 (off for 65 s) | 19 / 94 / 118 | 0.10 | 1 / 65.1 s |
| Best path (est.) | 8.46 | 24 / 232 / 368 | 5.56 | 6 / 5.7 s |
| Duplicated on both (est.) | 7.20 | 22 / 242 / 468 | 5.93 | 8 / 5.0 s |

Mobile data, although already up, went silent for 8.8 s starting 4 s *before* Android reported Wi-Fi lost, then ran at a 140–330 ms median with seven more dropouts while it carried all the phone's traffic; it was clean again within seconds of Wi-Fi returning.

**What this shows**
- Wi-Fi and mobile data fail at *different* moments in normal use: duplicating packets on both would have hidden every Wi-Fi blip in 10 minutes and halved the worst-case delay. Those short blips are what caused the freezes in the Phase 5 calls.
- Keeping mobile data warm does **not** make a full Wi-Fi drop seamless: the mobile path itself stalls and congests when Android moves the phone's traffic onto it. Neither best-path selection nor duplication covered that (7–8 % loss, multi-second outages).
- Best-path selection at one-second granularity barely helps: most Wi-Fi blips are shorter than a second.

**Not measured**
- Failover of a real vynq call when Wi-Fi drops (technique 1 as LiveKit does it today): the call test wasn't run. This is also Phase 4's open handover check.
- Any real duplicated or bonded media (techniques 2 and 3): they need a relay server, which the owner chose not to run.
- Induced loss/jitter (needs the relay), battery cost of keeping mobile data up (Android didn't allow resetting battery stats over adb), the realme (no mobile data), and iOS.

## Decision (2026-11-02)

**Don't ship Fusion Mode now.** The measurements don't justify the cost: a relay server to build and run, double data on the mobile plan, extra battery, Android only, and no help in the case users notice most (Wi-Fi dropping out completely).

**Worth a follow-up experiment, if the owner wants it:** audio-only duplication through a relay (technique 2). Audio is ~32–64 kbps, so the data cost is small, and the probe data says duplication removes the short Wi-Fi blips. It needs a small server; ship only if a real call measured with `CallMetrics` shows clearly fewer audio gaps at a bounded data cost.

**Meanwhile:** rely on LiveKit's built-in ICE restart for Wi-Fi→mobile handover, and measure it (Phase 4 open item).
