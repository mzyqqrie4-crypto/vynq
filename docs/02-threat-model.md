# 02 — Security threat model (v0, Phase 0)

Scope: vynq clients (Android, iOS), Supabase backend, LiveKit SFU/TURN, push providers (FCM, APNs).

## Assets
1. Message plaintext and attachments.
2. Call media (audio and video).
3. Device private keys (identity, pre-keys, ratchet state).
4. Account credentials and sessions.
5. Social graph and metadata: who talks to whom, and when.
6. Email addresses (private identifiers).

## Adversaries
- **A1** Network attacker (public Wi-Fi, ISP, MITM).
- **A2** Curious or compromised server operator (Supabase, LiveKit host, us).
- **A3** Thief or finder of an unlocked or locked device.
- **A4** Malicious contact or stranger (spam, malicious files, impersonation).
- **A5** Account-takeover attacker (phished email, SIM swap is N/A since there are no phone numbers).
- **A6** Push providers (Google, Apple).

## Threats → mitigations

| # | Threat | Mitigation | Residual risk |
|---|---|---|---|
| T1 | Message interception (A1, A2) | Signal Protocol E2EE per device. The server stores ciphertext only. TLS 1.2+ everywhere. | None for content. Metadata remains, see T9. |
| T2 | Server key substitution / MITM on key exchange (A2) | Safety numbers and QR verification. Identity-key change warnings. Verified state blocks silent key changes. | Users who never verify are trusting the server on first use (TOFU). |
| T3 | Call media visible to the SFU (A2) | Frame-level E2EE with the key delivered over the Signal channel. UI states the truth (see architecture §6). | IP addresses and timing are visible to the SFU/TURN. |
| T4 | Account takeover (A5) | Email OTP with short TTL and rate limits. New device needs approval from an existing device (*not built yet*; Phase 3 instead warns every contact when a new device appears and clears verification). Session list with remote revocation. | Losing control of the email account plus all devices means account recovery without message history (by design). |
| T5 | Lost or stolen device (A3) | Keys in Keystore/Keychain, hardware-backed where available. Encrypted local DB. Optional app lock (biometric). Remote device revocation. | An unlocked device in an attacker's hands exposes what is on screen. |
| T6 | Session hijacking | Short-lived JWTs, refresh token rotation, tokens in encrypted storage, revocation on sign-out or device removal. | — |
| T7 | Replay of messages or signalling | Double Ratchet message keys are single-use. Signalling events carry nonce + timestamp + call ID and are E2EE. LiveKit tokens are short-lived and room-scoped. | — |
| T8 | Push leakage (A6) | Data-only pushes with no content or sender name. Content is decrypted on device. iOS shows generic text unless the Notification Service Extension decrypts locally. | Push timing reveals activity to Google/Apple. |
| T9 | Metadata exposure (A2) | Collect the minimum: no contact upload, no phone numbers, mailbox TTL, delete on ack, no analytics. Sealed-sender style mailbox (Phase 8 hardening). | The server sees account IDs, timing and IPs. vynq is **not anonymous**. |
| T10 | Insecure local storage | SQLCipher / Tink. No plaintext logs. Backups excluded (`allowBackup=false`, data extraction rules). | Rooted or jailbroken devices weaken guarantees. |
| T11 | Malicious attachments (A4) | Attachments open only in sandboxed decoders. Size and type limits. Images re-decoded with platform decoders. No auto-download from unverified contacts. | Platform decoder 0-days. |
| T12 | Unwanted contact requests / spam (A4) | Contacts are invite-only: requests need acceptance before any message. Rate limits per account. Block list. | — |
| T13 | Username enumeration / impersonation | Exact-match lookup only (no prefix search), rate limited, returns the same response timing. Confusable-character normalisation (NFKC + skeleton). Reserved names. | Exact usernames can still be tested slowly. |
| T14 | Secrets leaked via repo | No secrets in git. `local.properties`/`.env` ignored. CI secrets in GitHub encrypted secrets. LiveKit API secret only lives in an Edge Function. | — |
| T15 | Backend authorization bugs | RLS on every table, deny by default. Policy tests in CI (Phase 2). | — |

## Explicit non-goals / what vynq cannot protect against
- A compromised OS, malware with accessibility or screen-capture rights, or a rooted device controlled by an attacker.
- A contact who screenshots or forwards content.
- Traffic analysis by a global observer. vynq does not hide *that* you communicate.
- Legal compulsion of server operators to hand over **metadata** (they hold no content).

This document will be revised at the end of Phases 3, 4 and 8.
