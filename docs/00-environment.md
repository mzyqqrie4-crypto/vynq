# 00 — Development environment (inspected 2026-10-31)

Host: Windows 11 Pro 10.0.26300, x64, 16 GB RAM, ~36 GB free on C:.

| Tool | Status | Notes |
|---|---|---|
| JDK | ✅ Temurin 17.0.20 | `JAVA_HOME` **not set** — Gradle finds `java` on PATH. Set it to avoid surprises. |
| Android SDK | ✅ `%LOCALAPPDATA%\Android\Sdk` | platform `android-36`, build-tools 35.0.0 / 36.0.0, cmdline-tools latest, licence accepted. `ANDROID_HOME` not set. |
| Android Studio | ❌ not installed | Not required to build; recommended for layout inspector, profilers, emulator. |
| Emulator / system images | ❌ none | Install via Android Studio or `sdkmanager "emulator" "system-images;android-36;google_apis;x86_64"`. Real Xiaomi devices preferred for camera/call work anyway. |
| adb | ✅ platform-tools (winget) | No devices attached at inspection time. |
| Gradle | ✅ 8.14.6 distribution cached | Used to generate the project's Gradle wrapper. Not on PATH (not needed). |
| AGP / Kotlin / Compose | ✅ cached | AGP 8.13.2, Kotlin 2.4.10, Compose BOM 2026.06.01 — chosen as the pinned baseline because they are known-good on this machine. |
| Git | ❌ **missing** | Required. `winget install Git.Git` |
| GitHub CLI | ❌ missing | Recommended. `winget install GitHub.cli` |
| Node.js | ✅ 24.15 | Used for brand asset rendering (resvg). |
| Python | ✅ 3.13.5 (+ Pillow 12.3) | Used for the brand geometry generator. |
| Docker | ❌ missing | Needed later for local Supabase (`supabase start`) and a local LiveKit server. Docker Desktop or WSL2 + Docker Engine. |
| Supabase CLI | ❌ missing | Phase 2. `npm i -g supabase` or scoop. |
| macOS / Xcode | ❌ n/a on Windows | iOS builds need macOS: a Mac, or GitHub Actions `macos-*` runners, plus an Apple Developer account for signing/TestFlight. |

## Recommended one-time setup

```powershell
winget install Git.Git
winget install GitHub.cli
[Environment]::SetEnvironmentVariable("JAVA_HOME", "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot", "User")
[Environment]::SetEnvironmentVariable("ANDROID_HOME", "$env:LOCALAPPDATA\Android\Sdk", "User")
```

## Build on Windows

```powershell
cd android
.\gradlew.bat assembleDebug          # → app/build/outputs/apk/debug/app-debug.apk
adb install -r app\build\outputs\apk\debug\app-debug.apk
```
