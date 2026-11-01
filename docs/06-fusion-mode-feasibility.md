# 06 — Fusion Mode: initial feasibility notes (pre-experiment)

**Status: research only. No claim of benefit until Phase 6 measurements exist.**

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
