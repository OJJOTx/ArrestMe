# Physical Device Testing Checklist — Arrest Me V0.1 Benchmark

> **Important**: Compiling the APK on GitHub Actions verifies build integrity only — **runtime blocking behavior must be verified on a physical Android device** using the 20 benchmark scenarios below.

## Device Metadata (Fill During Benchmark Run)

- **Device Manufacturer / Model**: `________________________`
- **Android Version / OEM Skin**: `________________________`
- **APK Version / Git Commit**: `0.1.0-benchmark-debug`
- **Tester & Date**: `________________________`

### Status Legend
- `PASS` — Behavior matches specification reliably.
- `PARTIAL` — Core persistence/logging works, with expected OS-imposed constraint noted.
- `FAIL` — Defect in Arrest Me implementation.
- `UNSUPPORTED` — Restricted by standard non-root Android OS architecture (must be explained).

---

## Pre-Test Setup

1. Install `app-debug.apk` on the physical Android device.
2. Open **Arrest Me → Perms** tab:
   - Grant **Overlay Permission** (`Display over other apps`).
   - *(Android 13/14/15 only)* Open **App Info → ⋮ → Allow restricted settings**.
   - Enable **Arrest Me Foreground Monitor** in **Accessibility Settings**.
   - Grant **Usage Access** and **Notifications**.
   - Set **Battery Optimization** to **Unrestricted**.
3. Open **Apps** tab:
   - Mark **2 test apps** (e.g., Chrome, YouTube, or Calculator) as **Block**.
   - Mark **1 study app** (e.g., Google Docs, Notes, or Files) as **Study Allow**.

---

## 20 Benchmark Test Scenarios

| # | Scenario | Steps | Expected Behavior | Result (`PASS` / `FAIL` / `PARTIAL` / `UNSUPPORTED`) | Notes / Explanation if Not `PASS` |
| :- | :--- | :--- | :--- | :--- | :--- |
| **1** | **Start an Arrest session** | Go to **Session** tab, keep default Self Arrest (`15m`, `"Finish studying chapter 3"`), tap **Start Self Arrest**. | Session transitions `IDLE → PREPARING → ACTIVE`; countdown starts from persisted `endTimestampMs`; ongoing notification appears; duplicate start is rejected. | `[ ]` | |
| **2** | **Open a blocked application** | Press Home and launch Blocked App #1. | `ArrestAccessibilityService` detects blocked package and notifies `BlockingEngine`; `BLOCKED_APP_ATTEMPT` logged. | `[ ]` | |
| **3** | **Verify the overlay appears** | Observe screen over Blocked App #1. | Full-screen blocking overlay appears showing session goal, live `MM:SS` countdown, blocked app reason, Dialer/Study App buttons, and Emergency Exit. Latency recorded in `ms`. | `[ ]` | |
| **4** | **Press Home** | Swipe/press Home (or tap *Go to Phone Home Screen* on overlay). | Home Launcher is recognized as allowed; blocking overlay is cleanly removed without lingering over the launcher. | `[ ]` | |
| **5** | **Open another blocked application** | From Home screen, launch Blocked App #2. | Foreground transition to Blocked App #2 is detected immediately. | `[ ]` | |
| **6** | **Check whether blocking is restored** | Observe screen over Blocked App #2. | Overlay re-activates cleanly with updated reason naming Blocked App #2; no duplicate window leak. | `[ ]` | |
| **7** | **Open an allowed application** | Tap *Open Study App* on overlay (or open the Study App / Phone Dialer from Home). | Overlay removes itself (if showing) and launches the allowed app. | `[ ]` | |
| **8** | **Verify the allowed app remains usable** | Interact with the allowed Study App, including opening the soft keyboard to type. | Allowed app remains 100% usable; opening the IME keyboard does not trigger false overlay flashes. | `[ ]` | |
| **9** | **Lock and unlock the phone** | Lock screen with power button, wait 10s, unlock phone while session is `ACTIVE`, then open Blocked App #1. | Session countdown continues accurately via wall-clock timestamps while locked; blocking remains active immediately upon unlock. | `[ ]` | |
| **10** | **Switch applications rapidly** | Rapidly switch across 5+ apps via Recents / gesture bar within 6 seconds, ending on a blocked app. | Debouncer avoids UI freeze or duplicate overlays; `RAPID_APP_SWITCHING` warning is logged; final foreground blocked app is covered by overlay. | `[ ]` | |
| **11** | **Revoke overlay permission** | Open Android Settings → Special app access → Display over other apps → Arrest Me → Toggle **OFF** during active session. | App does not crash; active overlay cleans up; `OVERLAY_PERMISSION_LOST` (`CRITICAL`) is logged; session transitions to `INTERRUPTED`; `permissionFailures` increments. | `[ ]` | *Platform note*: Standard Android cannot block user revocation; expected engine behavior is clean detection + `INTERRUPTED` state. |
| **12** | **Revoke accessibility permission** | Re-enable Overlay, resume session, then open Settings → Accessibility → Arrest Me Foreground Monitor → Toggle **OFF**. | `onUnbind()` fires; `ACCESSIBILITY_PERMISSION_LOST` (`CRITICAL`) is logged; session transitions to `INTERRUPTED`; Permissions Dashboard immediately reflects `Disabled`. | `[ ]` | *Platform note*: Standard Android cannot prevent disabling Accessibility; expected engine behavior is detection + `INTERRUPTED` state. |
| **13** | **Restart the phone** | Re-enable Accessibility & Overlay, ensure an `ACTIVE` 15m session has >5m left, and reboot the phone. Unlock device after boot. | OS re-binds `ArrestAccessibilityService` & `BOOT_COMPLETED` fires; persisted session restores with accurate remaining wall-clock time; opening a blocked app triggers overlay. | `[ ]` | *Note*: On some aggressive OEM ROMs (MIUI/ColorOS) without Auto-Start enabled, manual app open may be required (`PARTIAL`). |
| **14** | **Force-stop the application** | During an `ACTIVE` session, go to Android Settings → Apps → Arrest Me → Tap **Force stop**, then open a blocked app. | **Expected Android Platform Limitation (`UNSUPPORTED` / `PARTIAL`)**: While force-stopped, Android halts all app processes, Accessibility bindings, and overlays — blocked app will open unblocked until Arrest Me is reopened. | `[ ]` | Standard non-root Android OS stops all services on Force-Stop. Verified recovery & audit occur in Scenario #15–16. |
| **15** | **Reopen the application** | Launch Arrest Me manually after Scenario #14 Force-Stop. | `PersistenceManager` inspects `ApplicationExitInfo`, logs `FORCE_STOP_DETECTED` (`CRITICAL`), increments `serviceInterruptions`, and reconciles `UsageStatsManager` missed blocks. | `[ ]` | |
| **16** | **Verify persisted session information** | Check **Session** and **Results** screens after reopening in Scenario #15. | Original `sessionId`, `goal`, `startTimestampMs`, `endTimestampMs`, and exact remaining duration (or `COMPLETED` if expired while force-stopped) are intact. | `[ ]` | |
| **17** | **Wait for the session to expire** | Start a `1m` preset (or wait out remaining `15m` session) until countdown reaches `00:00` while viewing a blocked app's overlay. | Overlay automatically removes itself at `00:00`; foreground service stops. | `[ ]` | |
| **18** | **Verify automatic session completion** | Open Arrest Me **Session** & **Results** screens after expiry. | Session state is `COMPLETED`; `ACTIVE → COMPLETED` transition is logged with timestamp; `UsageStatsManager` reconciliation runs automatically. | `[ ]` | |
| **19** | **Test emergency exit** | Start a new session, open a blocked app (or stay on Session screen), trigger **Emergency Exit** and confirm. | Overlay (if active) immediately dismisses; session transitions to `EMERGENCY_STOPPED`; ForegroundService stops; `EMERGENCY_EXIT` logged. | `[ ]` | |
| **20** | **Review benchmark logs & JSON export** | Open **Results** and **Logs** tabs; tap **Save JSON** / **Share JSON**. | All attempts, successful/failed blocks, latency (`ms`), interruptions, permission losses, transitions, and `UsageStats` provenance render accurately and export to valid formatted JSON. | `[ ]` | |
