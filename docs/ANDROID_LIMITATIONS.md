# Known Android Platform Limitations (Arrest Me V0.1)

Arrest Me V0.1 is a **standard, non-root, non-Device-Owner Android application** (`minSdk = 26`, `targetSdk = 35`). It operates strictly within standard public Android APIs and user-granted permissions. It implements **no fake blocking**, **no covert behavior**, and **no attempts to subvert user control over system settings**.

---

## 1. Force-Stop Restriction (`ApplicationExitInfo.REASON_USER_REQUESTED`)

- **Platform Rule**: When a user navigates to *Android Settings → Apps → Arrest Me → Force Stop*, the Android OS immediately terminates the process, unbinds `ArrestAccessibilityService`, stops `ArrestForegroundService`, cancels pending alarms, and places the package in the `stopped` state (suppressing implicit/boot broadcasts including `BOOT_COMPLETED`).
- **What Arrest Me Can & Cannot Do**:
  - **Cannot**: Prevent Force-Stop, block the Android Settings screen, or self-restart from a force-stopped state.
  - **Can (Implemented)**: Persist `startTimestampMs` and `endTimestampMs` to disk prior to termination. When the user manually reopens Arrest Me, `PersistenceManager` queries `ActivityManager.getHistoricalProcessExitReasons()` (Android 11+ / API 30+), identifies `REASON_USER_REQUESTED`, logs a `CRITICAL` `FORCE_STOP_DETECTED` event in Diagnostic Logs, increments `serviceInterruptions`, reconciles `UsageStatsManager` to count any blocked apps opened while stopped, and restores or completes the session accurately based on persisted timestamps.

---

## 2. User Revocation of Overlay or Accessibility Permissions

- **Platform Rule**: Android guarantees users full authority to disable `SYSTEM_ALERT_WINDOW` (Display over other apps) or turn off `ArrestAccessibilityService` at any time.
- **What Arrest Me Can & Cannot Do**:
  - **Cannot**: Block `com.android.settings` or `com.android.permissioncontroller`, or re-enable revoked permissions programmatically.
  - **Can (Implemented)**: Immediately detect revocation via `onUnbind()` and periodic live permission verification, clean up overlay window state without crashing or leaking views, transition the active session to `INTERRUPTED`, increment `permissionFailures`, and log `OVERLAY_PERMISSION_LOST` / `ACCESSIBILITY_PERMISSION_LOST` in the Anti-Cheating Monitor.

---

## 3. Android 13+ (API 33+) "Restricted Settings" on Sideloaded APKs

- **Platform Rule**: Starting in Android 13, APKs installed manually via file manager or browser (rather than an app store session installer) have Accessibility Services locked behind **Restricted settings**.
- **User Action Required**: Open *Settings → Apps → Arrest Me → ⋮ (top-right menu) → Allow restricted settings*, authenticate with device PIN/biometric, and then enable **Arrest Me Foreground Monitor** in Accessibility Settings. The Permissions Dashboard displays a dedicated helper card and shortcut button for this flow.

---

## 4. Protected System Windows Above `TYPE_APPLICATION_OVERLAY`

- **Platform Rule**: `WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY` sits below critical Android system layers in z-order.
- **Surfaces That Render Above the Overlay**:
  - Notification Shade & Quick Settings panel
  - Android Lock Screen & Keyguard
  - Emergency Dialer / Incoming Call System UI
  - System Permission Controller & Package Uninstaller dialogs
  - System Reboot / Power Menu
- **Impact**: Arrest Me never assumes its overlay covers system windows. If a user pulls down notifications over a blocked app, the overlay remains attached underneath so the blocked app itself remains covered when the shade closes.

---

## 5. Android 14 & 15 Foreground Service & Reboot Restrictions

- **Platform Rule**:
  - Android 14 (`API 34`) requires explicit `foregroundServiceType="specialUse"` and `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` declaration for focus-session foreground services.
  - Android 15 (`API 35`) prohibits `BOOT_COMPLETED` broadcast receivers from directly launching `specialUse` foreground services (`ForegroundServiceStartNotAllowedException`).
- **How Arrest Me Handles Reboot Recovery**:
  1. When `ArrestAccessibilityService` was enabled before reboot, the Android OS itself re-binds `ArrestAccessibilityService` automatically after boot, restoring real-time foreground monitoring and reconciling the persisted session via `SessionController`.
  2. `BootRecoveryReceiver` runs session timestamp reconciliation, schedules `SessionRecoveryWorker` via `WorkManager`, posts a recovery status notification, and wraps `startForegroundService()` in safe exception handling so Android 15 never crashes the app on boot.
  3. On Direct Boot (before first unlock after reboot), `directBootAware="false"` means recovery runs once the user unlocks credential-encrypted storage (`BOOT_COMPLETED`).

---

## 6. Aggressive OEM Background Process Killers & Auto-Start Managers

- **Platform Rule**: OEM Android skins (Xiaomi HyperOS/MIUI, OPPO/Realme ColorOS, Vivo FunTouch, Huawei EMUI, OnePlus OxygenOS) implement proprietary background killers and custom Auto-Start toggles outside AOSP APIs.
- **Limitation**: Android provides **no public API** to verify whether a proprietary OEM Auto-Start switch is turned on.
- **How Arrest Me Handles This**: `PermissionChecker` probes `PackageManager.resolveActivity()` for known OEM startup manager activities. If present, it marks Auto-Start as `Available (OEM Screen Detected — Unverified by OS)` and provides a direct launch button without falsely claiming the setting is enabled.

---

## 7. Multi-Window (Split Screen / Freeform / Floating Windows)

- **Platform Rule**: In split-screen or OEM floating-window modes, two apps can be visible simultaneously while `TYPE_WINDOW_STATE_CHANGED` fires for whichever pane gains window focus.
- **Impact**: When the blocked app pane receives focus, the full-screen `TYPE_APPLICATION_OVERLAY` covers the display. If an allowed app pane holds focus while a blocked app sits unfocused in a secondary split pane, detection occurs as soon as focus enters the blocked pane.

---

## 8. `UsageStatsManager` Granularity & Delays

- **Platform Rule**: `UsageStatsManager.queryEvents()` requires explicit user grant (`PACKAGE_USAGE_STATS`) and records activity resume/pause transitions. If Usage Access is not granted, OS-level historical reconciliation is unavailable.
- **How Arrest Me Handles This**: Every session tags its analytics with explicit provenance (`CONFIRMED_REALTIME_AND_USAGESTATS`, `CONFIRMED_REALTIME_ONLY`, `CONFIRMED_USAGESTATS_ONLY`, `ESTIMATED`, or `UNAVAILABLE_PERMISSION_MISSING`).

---

## 9. Uninstallation & Safe Mode

- Without Device Owner (`DevicePolicyManager`) privileges, a standard Android user can always uninstall Arrest Me or reboot the device into Android Safe Mode (which disables all third-party apps). V0.1 intentionally does not attempt to block Settings or Package Uninstaller.
