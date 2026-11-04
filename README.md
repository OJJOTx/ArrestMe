# Arrest Me — V0.3: Android Arrest Engine & Cloud Integration

Native Android focus & app-blocking application (**Arrest Me**) combining a deterministic, 100% offline **Self Arrest Engine** with optional **Fastify + MySQL Cloud Integration** ([`OJJOTx/ArrestMe-backend`](https://github.com/OJJOTx/ArrestMe-backend)).

---

## Key Capabilities

- **Offline-First Arrest Engine**:
  - `WindowManager` (`TYPE_APPLICATION_OVERLAY`) + `AccessibilityService` + `UsageStatsManager` + `ForegroundService` (`specialUse`) + reboot recovery.
  - Supports **Block Selected**, **Strict Allowlist**, and **Whitelist Mode**, edge-to-edge status-bar protection, and **Disable Emergency Exit** strict timers.
  - Operates **100% offline** — local session execution never depends on authentication, internet connectivity, or backend availability.
- **Configurable Backend Environments**:
  - **Official Server (`BuildConfig.API_BASE_URL`)**: Injected at build time via GitHub Actions repository variable `ARRESTME_API_BASE_URL`.
  - **Custom Self-Hosted HTTPS Backend**: Configurable at runtime in the **Account** tab and persisted in Jetpack DataStore.
  - **Local-Only Mode (Offline)**: Default when no server URL is configured; produces zero network traffic.
  - **Isolated Per-Server Sessions**: Switching servers switches to an isolated credential namespace and isolated Retrofit/OkHttp client; tokens from Server A are never sent to Server B.
- **Modular Kotlin Cloud Networking (`Retrofit 2` + `OkHttp 4` + `kotlinx.serialization`)**:
  - Enforces HTTPS for all remote endpoints (`network_security_config.xml` + `BackendUrlValidator`) and automatically normalizes trailing slashes or `/api/v1/` suffixes.
  - Single-flight `Mutex`-guarded rotating refresh token handling (`TokenRefreshCoordinator` + `TokenRefreshAuthenticator`) preventing `REFRESH_TOKEN_REUSED` replay revocations.
  - Hardware-backed **Android Keystore (`AES/GCM/NoPadding`)** encryption at rest for access and refresh tokens (`AndroidKeystoreSecretCipher`).
  - Connectivity pre-checks (`OfflinePrecheckInterceptor`), safe retries exclusively for idempotent requests (`IdempotentRetryInterceptor`), and structured Fastify error mapping (`ApiErrorMapper`).
- **Privacy-Preserving Device Registration**:
  - Registers Android installations via `POST /api/v1/devices` using a generated app-scoped random UUID (`installationId`) — **zero hardware identifiers** (no IMEI, serial, MAC, or `ANDROID_ID`).
  - Synchronizes non-invasive build metadata (`PATCH /api/v1/devices/:id`) and deregisters the device on logout (`DELETE /api/v1/devices/:id`).
- **Zero Client Secrets**:
  - Database passwords, JWT signing keys, maintenance keys, and service-account secrets are never stored in Android source code or APKs.

---

## Backend Environment Configuration

### 1. Official Builds via GitHub Actions

GitHub Actions executes unit tests (`testDebugUnitTest`) and builds the debug APK (`assembleDebug`) on every push and workflow dispatch (`.github/workflows/android-build.yml`).

To configure the official backend URL for GitHub Actions builds:
1. Open **Settings** → **Secrets and variables** → **Actions** → **Variables** tab on GitHub.
2. Create or update repository variable **`ARRESTME_API_BASE_URL`** (e.g. `https://api.yourdomain.com` — trailing `/api/v1/` is also accepted and normalized automatically).
3. The workflow passes `${{ vars.ARRESTME_API_BASE_URL }}` as `ORG_GRADLE_PROJECT_ARRESTME_API_BASE_URL` into `BuildConfig.API_BASE_URL`.
4. If `ARRESTME_API_BASE_URL` is unset, the APK defaults cleanly to **Local-Only (Offline)** mode.

### 2. Local Gradle Build Configuration

Provide `ARRESTME_API_BASE_URL` via Gradle property or environment variable (never commit production credentials):

```bash
# Via Gradle property flag
./gradlew testDebugUnitTest assembleDebug -PARRESTME_API_BASE_URL="https://api.yourdomain.com"

# Or via environment variable
export ORG_GRADLE_PROJECT_ARRESTME_API_BASE_URL="https://api.yourdomain.com"
./gradlew testDebugUnitTest assembleDebug
```

### 3. In-App Account & Server Management

Open **Arrest Me** and tap the **Account** tab:
- **Account Overview**: View username, display name, email, active server, authentication status, registered device (`ACTIVE` / cloud ID / installation UUID), and last synchronization timestamp.
- **Sign In / Register**: Create an account or sign in (`POST /api/v1/auth/register`, `POST /api/v1/auth/login`), automatically registering the current Android installation (`POST /api/v1/devices`).
- **Sync & Edit Profile**: Refresh profile & device heartbeat (`GET /api/v1/users/me`, `PATCH /api/v1/devices/:id`) or update display name (`PATCH /api/v1/users/me`).
- **Logout**: Deregisters the device (`DELETE /api/v1/devices/:id`), revokes the refresh token family (`POST /api/v1/auth/logout`), and wipes encrypted local credentials.
- **Server Configuration**: Switch between Official Server, Custom Self-Hosted HTTPS Backend, and Local-Only Mode, and test `/api/v1/health` round-trip latency.

---

## Architecture Overview

```text
app/src/main/java/com/arrestme/benchmark/
├── core/
│   ├── backend/        # BackendUrlValidator, SecretCipher (Android Keystore AES-GCM), BackendPreferencesDataSource,
│   │                   # BackendConfigRepository, BackendAuthRepository, DeviceMetadataProvider, BackendApiClient
│   ├── session/        # SessionController & SessionModels (100% offline, deterministic state machine)
│   ├── blocking/       # BlockingEngine, BlockingRulesEvaluator, SystemSafetyAllowlist (unchanged, zero cloud coupling)
│   ├── accessibility/  # ArrestAccessibilityService (debounced foreground detection, zero content capture)
│   ├── overlay/        # OverlayManager (TYPE_APPLICATION_OVERLAY, status bar guard, orientation-safe)
│   ├── persistence/    # PersistenceManager, ArrestForegroundService, BootRecoveryReceiver, SessionRecoveryWorker
│   ├── permissions/    # PermissionChecker (live OS verification + OEM auto-start probing)
│   ├── analytics/      # UsageMonitor (real-time metrics + UsageStatsManager audit)
│   └── monitoring/     # AntiCheatingMonitor (severity-graded diagnostic event log)
├── data/
│   ├── local/          # Room Database, Entities, DAOs, SessionSnapshotStore
│   ├── remote/         # ArrestCloudApi (Retrofit), CloudDtos (kotlinx.serialization), ArrestApiClientFactory,
│   │                   # AuthHeaderInterceptor, TokenRefreshCoordinator, TokenRefreshAuthenticator,
│   │                   # IdempotentRetryInterceptor, OfflinePrecheckInterceptor, ApiErrorMapper
│   └── repository/     # ArrestRepository + JSON Benchmark Exporter
└── ui/
    ├── home/           # Engine readiness, cloud account summary & active session dashboard
    ├── permissions/    # Live verified permission onboarding & Android Settings launchers
    ├── apps/           # Blocked & Study Allowlist package selector
    ├── session/        # Self Arrest session controller UI
    ├── benchmark/      # Latency, block counts, UsageStats provenance & JSON export
    ├── diagnostics/    # Event log & state transition viewer
    └── account/        # Cloud authentication (Login/Register), profile, registered device & server configuration
```

---

## Automated Testing

Run the full unit and MockWebServer integration test suite:

```bash
./gradlew testDebugUnitTest
```

Covered scenarios (`app/src/test/`):
- URL validation, HTTPS enforcement on remote domains, RFC1918 local HTTP allowances, and `/api/v1/` path normalization (`BackendUrlValidatorTest`).
- Default, custom self-hosted, and local-only server mode persistence (`BackendConfigRepositoryTest`).
- At-rest AES-256-GCM token encryption and strict per-server session isolation across server and offline switches (`BackendAuthRepositoryTest`).
- End-to-end Retrofit + OkHttp + MockWebServer flows: registration, login, invalid credentials (`401`), proactive & reactive `401` single-flight token rotation, refresh expiration handling, device registration & logout deregistration ordering, transient `503` idempotent retries vs non-idempotent POST safety, and abrupt socket disconnects (`CloudIntegrationTest`).
- Offline pre-checks and complete local Arrest Engine session independence (`OfflineModeTest`).
- Optional live Hostinger verification (`LiveHostingerIntegrationTest`, enabled via `-DARRESTME_LIVE_TEST_URL=https://...`).

---

## Installing the APK on a Physical Android Device

1. Download **`ArrestMe-v0.3.0-debug.apk`** from [GitHub Releases](https://github.com/OJJOTx/ArrestMe/releases) or the **`arrest-me-v0.3-debug-apk`** workflow artifact on [GitHub Actions](https://github.com/OJJOTx/ArrestMe/actions).
2. Install on your Android 8.0+ (API 26–35) device.
3. In the **Perms** tab, enable **Overlay (`SYSTEM_ALERT_WINDOW`)**, **Allow restricted settings** (Android 13+ sideloaded APKs via App Info ⋮ menu), **Arrest Me Foreground Monitor** Accessibility Service, and **Usage Access**.
4. Optionally open **Account** to sign in or configure your server, or start a **Self Arrest** session immediately in **Session** completely offline.
