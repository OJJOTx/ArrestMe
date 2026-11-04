# Arrest Me — Phase V0.1: Android Arrest Engine Benchmark

Native Android benchmark application evaluating the reliability, latency, persistence, and platform boundaries of the **Arrest Me** app-blocking engine using standard non-root Android permissions.

---

## Scope of Phase V0.1

- **Language & Stack**: Kotlin, Jetpack Compose + Material 3, Room SQLite (KSP), WorkManager, Android `WindowManager` (`TYPE_APPLICATION_OVERLAY`), `AccessibilityService`, `UsageStatsManager`, `ApplicationExitInfo`.
- **Offline & Local-Only**: Zero backend dependencies, zero `INTERNET` permission. All blocking rules, session state machines, usage reconciliations, and JSON exports run 100% on-device.
- **CI/CD**: 100% cloud compilation via GitHub Actions (`.github/workflows/android-build.yml`) — no local Android Studio, SDK, or emulator required.

---

## Modular Architecture

```text
app/src/main/java/com/arrestme/benchmark/
├── core/
│   ├── session/        # SessionController (IDLE/PREPARING/ACTIVE/PAUSED/COMPLETED/EMERGENCY_STOPPED/INTERRUPTED)
│   ├── blocking/       # BlockingEngine, BlockingRulesEvaluator, SystemSafetyAllowlist
│   ├── accessibility/  # ArrestAccessibilityService (debounced TYPE_WINDOW_STATE_CHANGED, zero content capture)
│   ├── overlay/        # OverlayManager (TYPE_APPLICATION_OVERLAY, sub-frame latency timer, orientation-safe)
│   ├── persistence/    # PersistenceManager, ArrestForegroundService (Android 14/15 specialUse), BootRecoveryReceiver, SessionRecoveryWorker
│   ├── permissions/    # PermissionChecker (live OS verification + OEM auto-start intent probing)
│   ├── analytics/      # UsageMonitor (real-time metrics + UsageStatsManager discrepancy audit)
│   └── monitoring/     # AntiCheatingMonitor (structured severity-graded diagnostic event history)
├── data/
│   ├── local/          # Room Database, Entities, DAOs, atomic SessionSnapshotStore
│   └── repository/     # ArrestRepository + JSON Benchmark Exporter
└── ui/
    ├── home/           # Engine readiness & session summary
    ├── permissions/    # Live verified permission onboarding & Settings launchers
    ├── apps/           # Manual Blocked & Study Allowlist package selector
    ├── session/        # Self Arrest session controller UI
    ├── benchmark/      # Latency, block counts, UsageStats provenance, JSON export
    └── diagnostics/    # Event log & state transition viewer
```

---

## Downloading & Installing the Debug APK on a Physical Android Device

> **Note**: Successful compilation in GitHub Actions only verifies build integrity. Blocking latency, Accessibility callbacks, overlay behavior, and OEM persistence must be validated on a physical Android phone.

### Step 1: Download the APK from GitHub Actions
1. Open the repository on GitHub: https://github.com/OJJOTx/ArrestMe/actions
2. Click the latest green **Android Debug APK Build** workflow run.
3. Scroll to **Artifacts** and download **`arrest-me-v0.1-debug-apk`**.
4. Unzip `arrest-me-v0.1-debug-apk.zip` to get **`app-debug.apk`**.
5. Transfer `app-debug.apk` to your Android phone (via USB, Google Drive, LocalSend, or `adb install app-debug.apk`) and install it (tap *Install anyway* if Google Play Protect prompts on an unsigned/debug test build).

### Step 2: Enable Permissions on Physical Device (Important for Android 13 / 14 / 15)
Open **Arrest Me** and go to the **Perms** tab:

1. **Overlay Permission (`SYSTEM_ALERT_WINDOW`)**:
   - Tap **Open Overlay Settings** → select **Arrest Me** → enable **Allow display over other apps**.
2. **Allow Restricted Settings (Android 13+ Sideloaded APKs Only)**:
   - On Android 13, 14, and 15, sideloaded APKs show *"Restricted setting"* if you try to enable Accessibility right away.
   - In the **Perms** tab, tap **Open App Info (For Restricted Settings)** → tap the **⋮ (3-dot menu)** in the top-right corner → tap **Allow restricted settings** and confirm with your screen lock.
3. **Accessibility Foreground Monitor**:
   - Tap **Open Accessibility Settings** → **Installed apps** (or *Downloaded apps*) → **Arrest Me Foreground Monitor** → toggle **ON** and confirm.
   - Return to Arrest Me and verify status reads **`Enabled (Active)`**.
4. **Usage Access (`PACKAGE_USAGE_STATS`)**:
   - Tap **Open Usage Access Settings** → **Arrest Me** → toggle **Permit usage access** ON.
5. **Notifications & Battery Optimization**:
   - Tap **Enable Notifications** → Allow.
   - Tap **Open Battery Optimization Settings** (or App Info → Battery) → set Arrest Me to **Unrestricted**.
   - *(Xiaomi / OPPO / Realme / Vivo / Huawei / OnePlus)*: Tap the **OEM Auto-Start** button on the Perms screen and allow background startup.

### Step 3: Execute the 20-Scenario Physical Device Benchmark
- Select blocked & allowed study apps in the **Apps** tab.
- Start a **Self Arrest** session in the **Session** tab (default: `15m`, `"Finish studying chapter 3"`).
- Follow [docs/TESTING_CHECKLIST.md](docs/TESTING_CHECKLIST.md) and review platform constraints in [docs/ANDROID_LIMITATIONS.md](docs/ANDROID_LIMITATIONS.md).
- Export structured telemetry from the **Results** tab via **Save JSON** or **Share JSON**.
