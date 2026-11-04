# Technical Architecture — Arrest Me V0.1 Benchmark

## Overview

Arrest Me V0.1 is modularized into three strict layers under `com.arrestme.benchmark` so the core engine can later integrate partner-controlled sessions (`PARTNER_ARREST`) without refactoring foreground monitoring, overlay rendering, or local persistence:

1. **`core/` (Arrest Engine)**:
   - `session/`: `SessionController`, `SessionModels` (`IDLE`, `PREPARING`, `ACTIVE`, `PAUSED`, `COMPLETED`, `EMERGENCY_STOPPED`, `INTERRUPTED`).
   - `blocking/`: `BlockingEngine`, `BlockingRulesEvaluator`, `SystemSafetyAllowlist`.
   - `accessibility/`: `ArrestAccessibilityService` (`TYPE_WINDOW_STATE_CHANGED`, `canRetrieveWindowContent="false"`, burst debouncer, IME/self-overlay filter).
   - `overlay/`: `OverlayManager` (`WindowManager` + `TYPE_APPLICATION_OVERLAY`, API 30+ window context, frame-commit latency timer, orientation-aware scroll container).
   - `persistence/`: `PersistenceManager`, `ArrestForegroundService` (Android 14/15 `specialUse`), `BootRecoveryReceiver`, `SessionRecoveryWorker` (`WorkManager`), `ApplicationExitInfo` Force-Stop inspector.
   - `permissions/`: `PermissionChecker` (live OS verification + OEM auto-start intent probing).
   - `analytics/`: `UsageMonitor` (real-time metrics + `UsageStatsManager.queryEvents()` discrepancy reconciliation + `DataConfidence` provenance).
   - `monitoring/`: `AntiCheatingMonitor` (severity-graded structured diagnostic event recorder).
2. **`data/` (Offline Local Persistence)**:
   - `local/`: Room SQLite database (`ArrestDatabase`) + synchronous `SessionSnapshotStore` mirror.
   - `repository/`: `ArrestRepository` + formatted JSON benchmark exporter.
3. **`ui/` (Jetpack Compose + Material 3)**:
   - `home/`, `permissions/`, `apps/`, `session/`, `benchmark/`, `diagnostics/`.

## Key Invariants

- **Timestamp-Derived Remaining Time**: In-memory coroutines only drive UI refresh ticks and periodic expiry checks; remaining session time is always computed from persisted `endTimestampMs - System.currentTimeMillis()`.
- **Self-Overlay Event Isolation**: `ArrestAccessibilityService` ignores `com.arrestme.benchmark` window-state events unless the window class is `MainActivity`, preventing focusable `TYPE_APPLICATION_OVERLAY` windows from triggering self-dismissal.
- **IME & Transient System Window Stability**: Soft keyboards (`InputMethodManager.enabledInputMethodList` / `SoftInputWindow`) and `com.android.systemui` transient windows return `IgnoreTransientWindow`, preventing both false triggers when typing in allowed Study Apps and accidental dismissal when a blocked app raises an IME behind the overlay.
- **Emergency & System Control Safety**: `SystemSafetyAllowlist` unconditionally permits Dialer, Telecom, Emergency, Settings, Permission Controller, Home Launcher, and Package Uninstaller packages.
