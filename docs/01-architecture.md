# 01 — System architecture & technology decisions

Status: **Accepted for Phase 1–4** unless marked *open*. Each decision lists why, the alternatives, and the risk.

## 1. Repository layout (monorepo)

```
vynq/
├─ android/                 Kotlin + Jetpack Compose app (Gradle)
│  ├─ app/                  application module: navigation, features, DI wiring
│  └─ core/designsystem/    tokens, theme, components, brand vectors (no business logic)
├─ ios/                     SwiftUI app (Phase 7, Xcode project generated on macOS)
├─ backend/supabase/        SQL migrations, RLS policies, edge functions (Phase 2)
├─ protocol/                shared contracts: message envelope schema, signalling events, API (Phase 2–3)
├─ brand/                   master SVGs, generator, exports, guidelines
├─ experiments/fusion/      standalone multi-network experiment (Phase 6) — never linked into the app
├─ docs/                    architecture, threat model, roadmap, ADRs
└─ .github/workflows/       CI (Android build/test; iOS on macOS runners later)
```

Why a monorepo: one source of truth for protocol contracts across Android, iOS and backend; atomic changes when the wire format moves. Platform code stays fully native — nothing is forced through a shared runtime.

Android will be split further (`core:data`, `core:crypto`, `feature:chat`, `feature:call`, …) as those layers get real code. Splitting empty modules now would only add build time.

## 2. Client platforms

| Decision | Choice | Rationale |
|---|---|---|
| Android | Kotlin 2.4, Jetpack Compose (BOM 2026.06.01), AGP 8.13, minSdk 28, target/compile 36 | Native camera/telecom/network APIs, best performance. minSdk 28 covers almost all active devices and drops a lot of legacy code paths. |
| Android UI | Custom `VynqTheme` design system on top of Material 3 foundations | Material supplies accessibility, ripple and text-field plumbing. Colour, type, shape and motion come from vynq tokens, not Material defaults. |
| iOS | Swift 6, SwiftUI, AVFoundation, CallKit/PushKit, Keychain + Secure Enclave | Genuinely native, as required. Shares only contracts and docs with Android. |
| Cross-platform frameworks (Flutter/RN/KMP UI) | **Rejected** | The call screen, camera pipeline and CallKit/ConnectionService work is native anyway, and the brief explicitly asks for a native-first feel. KMP for *non-UI* logic (protocol models) stays an option once real duplication shows up. |

## 3. Real-time media: LiveKit

**Decision:** use the LiveKit open-source SFU (Apache-2.0) with the official `client-sdk-android` and `client-sdk-swift`, all built on libwebrtc. Start on LiveKit Cloud's free tier for development, then self-host (single region VM + built-in TURN) before inviting real users.

Why:
- Maintained native SDKs on both platforms, with simulcast, dynacast and adaptive stream, congestion control, ICE restart / reconnection, built-in TURN (UDP/TCP/TLS 443), and a stats API for the diagnostics panel.
- **Frame-level E2EE** in both SDKs (insertable streams / frame cryptor). The SFU forwards encrypted frames it cannot decode, so true E2EE media stays possible with an SFU.
- Multi-party comes nearly free later.
- Apache-2.0 with self-hosting means no vendor lock-in.

Alternatives considered:
- **Raw libwebrtc P2P + own signalling + coturn.** Lowest possible latency for 1:1 when a direct path exists, but we would have to build reconnection, adaptive layers and stats plumbing ourselves on two platforms. That is a lot of risk for the defining feature. Kept as a **Phase 5 experiment** (direct P2P for 1:1 with SFU fallback) if measurements show the SFU hop costs too much.
- Agora / Twilio / Daily: proprietary, metered, and media is server-visible unless you add their E2EE. Rejected.

Risks: one extra network hop for 1:1 calls (mitigation: pick the region nearest the users, measure in Phase 5). E2EE disables server-side features such as recording and transcoding, which we don't want anyway.

## 4. Backend: Supabase (Postgres)

**Decision:** Supabase for auth (email OTP / magic link, no phone numbers), Postgres with Row-Level Security, Realtime for signalling and presence fan-out, Storage for **already-encrypted** attachment blobs, and Edge Functions for LiveKit token minting, push fan-out and username checks.

Why: managed, inexpensive at small scale, and it is plain Postgres, so migrating means `pg_dump` plus re-hosting the functions, not a rewrite. RLS gives defence in depth even if a client is compromised.

Portability rule: clients talk to the backend only through `protocol/` contracts and a thin repository layer (`AuthRepository`, `DirectoryRepository`, `MailboxRepository`, `SignallingChannel`). No Supabase types leak into UI or domain code.

## 5. Messaging E2EE: Signal Protocol via libsignal — *decided 2026-11-01*

**Decision (owner, 2026-11-01):** use libsignal and accept AGPL-3.0. Consequences we now carry:
- The vynq client apps are licensed **AGPL-3.0-or-later**. A `LICENSE` file goes into the repository when it is created, before any build is shared with anyone.
- Anyone who receives the app must be able to get its complete corresponding source. Plan: a "Source code" link in Settings → About pointing at the source for that exact version.
- No other dependency may be AGPL-incompatible. Dependency licences are checked whenever one is added (current ones are all Apache-2.0).
- Backend code (Supabase SQL and Edge Functions) does not link libsignal and can be licensed separately. It must never need message plaintext.
- Shipping on the App Store alongside AGPL has a known tension (Apple's terms add restrictions). Signal ships there as copyright holder. vynq is not, so this gets reviewed before Phase 7 distribution.

**Chosen:** `libsignal` (Signal's official Rust core with maintained Java/Android and Swift bindings). It provides X3DH/PQXDH key agreement, the Double Ratchet (forward secrecy and post-compromise security), sealed sender primitives, and per-device identity keys. It is the most scrutinised messaging protocol available.

⚠️ **Licence:** libsignal is **AGPL-3.0**. Distributing the app means offering its source to its users under AGPL. For a private app for a trusted circle this is usually acceptable. For a closed-source commercial product it is not.

Not chosen (kept as the fallback if the licence ever becomes a problem): **vodozemac** (Matrix Olm / Megolm, Apache-2.0, Rust, independently audited by Least Authority in 2022). Same Double Ratchet family, but we would have to maintain uniffi bindings for Kotlin and Swift ourselves. This is more engineering and a bigger risk surface.

Rejected: anything home-made, and "TLS + database encryption" presented as E2EE.

Design outline (Phase 3):
- Each **device** has its own identity key pair, signed pre-key and one-time pre-keys. Private keys are wrapped with an Android Keystore / iOS Keychain key (Secure Enclave or StrongBox where available).
- The server stores only public pre-key bundles and an **opaque encrypted mailbox** of envelopes `{to_device, ciphertext, server_ts}`. Envelopes are deleted on acknowledgement and expire after a TTL.
- Fan-out: a message is encrypted once per recipient device (and per sender's other devices).
- Safety numbers / QR codes for contact and device verification. A key change produces a visible warning.
- Attachments: random 256-bit key per file with AES-256-GCM (chunked), uploaded to Storage. The key and digest travel inside the E2EE message.

## 6. Call security model (honest labelling)

| Layer | Guarantee |
|---|---|
| Transport | DTLS-SRTP between each client and the SFU, TLS for signalling. **The SFU can see media without the E2EE layer.** |
| Media E2EE | LiveKit frame encryption, with a per-call key generated by the caller and delivered to the callee **through the Signal-encrypted message channel**, never through the SFU or the database. Key rotation on participant change. |
| Metadata the server sees | Who called whom, when, how long, IP addresses (SFU/TURN), approximate bitrate. Documented in the threat model. |

The UI will show "End-to-end encrypted" for a call **only** when the frame cryptor reports an active key on both sides. Otherwise it shows "Encrypted in transit".

*Built in Phase 4:* the invite carries a fresh 32-byte key over Signal; the badge needs every track (local and remote, audio and video) to report OK and none to have failed. Video format per direction: the best **hardware** encoder the sender has that the receiver can decode, AV1 → H.265 → H.264 (each phone sends its decodable list in the invite/answer). Resolution comes from Settings → Call quality and is never lowered mid-call; 1440p only on Maximum when the receiver's screen is wider than 1080 px and the sender's camera and encoder do 1440p30.

## 7. Push & background — *revised 2026-11-01*

Message delivery does **not** depend on any push provider (Phase 3, `android/.../notify/`):
- While the app is visible, or while the **background connection** foreground service runs (type `specialUse`, on by default, user can turn it off or hide its notification), the device listens on Supabase Realtime for new rows in its own mailbox. The Realtime event carries nothing the device couldn't fetch anyway; the device then fetches, decrypts and posts the notification itself.
- A WorkManager job (~15 min, network required) fetches mail and restarts the service if the OS killed it. `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` restart it too.
- Settings → Notifications is a checklist: notification permission and battery exemption (read live), plus deep links to vendor screens apps can't read (Xiaomi Autostart / battery saver / lock-screen pop-ups; OPPO/realme/OnePlus, vivo, Huawei/Honor auto-launch). We open those screens; we never try to bypass the restrictions.
- Why: many phones sold in China have no Google services, and phones that do (including the test Xiaomi, a CN ROM) throttle apps that aren't on vendor allow-lists. This is the approach Signal uses without Google services. Cost: a persistent connection (heartbeat every 25 s). Battery impact not yet measured.

**Calls (decided 2026-11-01, owner: background connection):** call invites arrive over the same background connection, so a call rings while it is alive (verified on both test phones with the screen locked). A phone whose vynq process the OS has killed misses the call and shows it as missed later. Revisit with FCM data-only (Firebase project + licence review next to AGPL code), vendor push (MiPush, HeyTap: proprietary SDKs, same licence question) or UnifiedPush if killed-state ringing becomes a requirement.

- Calls: a foreground service of type `phoneCall|camera|microphone` plus a call-style notification with a full-screen intent, and vynq's own ringtone (HyperOS may refuse the full-screen intent). `androidx.core:core-telecom` (ConnectionService) is **not built yet**, so the OS call log, car kits and "busy on another call" don't see vynq calls.
- iOS: APNs with a Notification Service Extension that decrypts locally, plus PushKit VoIP → CallKit for calls (required: every VoIP push must report a call).

## 8. Local data

- Android: Room on SQLCipher (or Room + per-column encryption with Tink AEAD) for messages. The database key is wrapped by a Keystore key. DataStore holds non-sensitive preferences.
- iOS: SQLite through GRDB with SQLCipher, keys in the Keychain (`afterFirstUnlockThisDeviceOnly`).
- No plaintext message content in logs, analytics or crash reports. No third-party tracking SDKs.

## 9. Fusion Mode

A separate experiment, see `06-fusion-mode-feasibility.md`. It cannot affect call reliability: it lives in `experiments/fusion` until measured results justify shipping it behind a flag.

## 10. Pinned versions (Android baseline)

See `android/gradle/libs.versions.toml`. All versions are pinned there. Upgrades go through a single PR with a build and test run.
