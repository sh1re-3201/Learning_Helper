# LearningHelper — "Thesis Lock-In" 🎓🔒

[![Platform: Android](https://img.shields.io/badge/Platform-Android%2013%2B%20(API%2033%2B)-3DDC84.svg?style=flat&logo=android)](https://www.android.com/)
[![Target SDK: 37](https://img.shields.io/badge/Target%20SDK-37-3DDC84.svg?style=flat)](https://developer.android.com/)
[![Language: Kotlin](https://img.shields.io/badge/Language-Kotlin-7F52FF.svg?style=flat&logo=kotlin)](https://kotlinlang.org/)
[![UI: Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose%20%2B%20Material%203-4285F4.svg?style=flat&logo=jetpackcompose)](https://developer.android.com/jetpack/compose)
[![100% Offline](https://img.shields.io/badge/Network-100%25%20Offline%20(Zero%20Internet)-critical.svg?style=flat)](#zero-network--100-offline)
[![Tests: JVM Pure Logic](https://img.shields.io/badge/Tests-JVM%20Unit%20Tests%20Passing-brightgreen.svg?style=flat)](#automated-testing)

An uncompromising, battery-frugal personal Android focus locker built to enforce a strict thesis-writing routine on Samsung One UI devices.

Installed directly via sideloading / Android Studio, **LearningHelper** eliminates distractions by locking out non-whitelisted apps, suppressing notifications, and imposing a mathematically rigorous, anti-spam Roman-alphabet essay requirement to abort early.

---

## 🌟 Key Features

### 1. 🔒 Strict Focus Lock & App Blocker
- **Instant Full-Screen Interception (`BlockActivity`):** Distracting applications are immediately blocked during active focus blocks with a motivational full-screen overlay.
- **Allowed Apps Quota (5-App Optional Pool):** Choose up to 5 custom applications (e.g., PDF readers, reference tools, note-taking apps) outside focus blocks.
- **Always Unblocked Essentials:** Incoming/outgoing phone calls (`com.samsung.android.dialer`, Contacts, active dialer), Samsung Clock (for dismissable system alarms), core System UI (Quick Settings panel), keyboards, and default messaging apps (WhatsApp) consume **0 quota**.
- **Uninstallation Always Permitted:** System Package Installers and Android Settings (`com.android.settings`) remain accessible at all times per user device ownership design.

### 2. 📝 High-Friction Emergency Exit (`EssayValidator`)
- **No Quick "Give Up" Button:** The only in-app escape during a focus block is completing an **Emergency Exit**.
- **Active Writing Timer:** Enforces a minimum active writing duration (default **3 minutes**, configurable 1–15m outside sessions). Drafts and elapsed active seconds persist across screen rotations, calls, and app switches.
- **Anti-Spam & Linguistic Anti-Cheating Pipeline:**
  - **True-Span Paste Blocker:** Inspects inserted text spans to reject multi-word pastes, clipboard chip injections, and rapid burst spam without erasing existing input.
  - **Word Count & Density:** Requires **≥ 100 Roman words** with an average length ≥ 3.4 characters and strict single-letter word thresholds.
  - **Shannon Character Entropy:** Enforces unigram entropy ($H(X_1) \ge 3.2\text{ bits}$) and conditional bigram entropy ($H(X_2 \mid X_1)$) to block keyboard mashing and repetitive babble in any Latin-script language (English, Indonesian, etc.).
  - **Vector Cosine Similarity:** Employs character-trigram TF vectors to reject internal sentence repetitions and sublinear-TF content-word vectors against past saved excuses in local storage.

### 3. ⏰ Weekly & Calendar Scheduling with Pre-Session Alarms
- **Flexible Routines:** Set up recurring weekly sessions (Mon–Sun) or specific one-off calendar dates with custom work/break intervals.
- **Audible Pre-Session Warnings:**
  - **T - 5 minutes:** Fires the user-selected Samsung/system alarm ringtone (`AudioAttributes.USAGE_ALARM`, auto-silenced at 45s) and issues an actionable notification.
  - **T - 2 minutes:** Re-sounds the alarm and superimposes `PreSessionActivity` over the screen (`SYSTEM_ALERT_WINDOW` + `USE_FULL_SCREEN_INTENT`) with a live countdown, Volume-key / button mute, **Proceed**, and **Skip**.
- **Pre-Session Skip Requirement:** Skipping an upcoming session before `T-0` requires submitting a **≥ 20-word validated reason** (with a one-time +60s grace extension if actively typing when the clock strikes 0:00).

### 4. ☕ Scheduled Breaks & Per-Session Rest Token
- **Normal Phone Access during Breaks:** Scheduled intervals temporarily unblock user apps while keeping anti-tampering packages (Device Care, Play Store) locked.
- **Floating HUD Countdown Pill:** Displays a non-interactive, touch-pass-through countdown pill (`FLAG_NOT_TOUCHABLE`) using a native `Chronometer`. At 10 seconds remaining, the pill pulses amber with a haptic vibration cue before re-locking.
- **1-per-Session Rest Token:** Grants one unscheduled **30-minute break** per session without shifting the final session end time. Can be resumed early at any point.
- **Audible Break Arrival Alert:** Sounds a 15-second alarm with a distinctive 3-pulse vibration pattern when entering break time.

### 5. 🕒 AMOLED Landscape Desk Clock (`DeskClockScreen`)
- **True-Black AMOLED Display (`#000000`):** Over 96% of display pixels are turned off (0 mA on Samsung Dynamic AMOLED panels) with 2% hardware screen brightness.
- **OLED Burn-In Protection:** Digital clock (`HH:mm`) shifts coordinates each minute and only recomposes on minute boundaries.
- **Corner Phase Countdown:** The countdown to the next break or session end is isolated in a leaf composable at the bottom-right corner.
- **Animated Phase Shifts:** Smoothly animates the screen background over 3 seconds from `#000000` to deep royal blue (`#273c86`) when break time arrives, and fades back to black when work resumes.
- **Notification Panel Direct Launch:** Accessible directly via a dedicated action button on the persistent Focus notification.

### 6. 🔕 Distraction Notification Blocker & Quick Settings Freedom
- **Unrestricted Quick Settings:** The top notification tray (`com.android.systemui`) is never blocked—Wi-Fi, Bluetooth, sound toggles, and flashlight remain accessible.
- **Notification Sweeping (`FocusNotificationListenerService`):** During `WORK` blocks, distraction notifications from non-allowlisted apps are dismissed.
- **Emergency VoIP & Call Continuity:** Incoming/ongoing telephony and VoIP calls (WhatsApp, Zoom, Teams, Meet) are always preserved. Calls active when entering work mode are grandfathered until concluded.

---

## 🛡️ Hard Architectural Invariants

### Zero Network / 100% Offline
- **No Internet Permission:** `<uses-permission android:name="android.permission.INTERNET" />` is strictly absent from `AndroidManifest.xml`.
- **Zero Remote Dependencies:** All data, settings, history, and validation logic run 100% on-device with zero telemetry or cloud dependencies.

### Monotonic Clock Anchor (Anti-Tampering)
- Active session timing and debounces are anchored to `SystemClock.elapsedRealtime()` and cross-checked against `Settings.Global.BOOT_COUNT`.
- Manually modifying the system date or time in Android Settings cannot bypass or expire an active focus session.

### Pure Domain Logic (100% Unit-Testable)
- Core domain logic (`FocusPolicy`, `EssayValidator`, `ScheduleCalculator`) has **zero imports** from `android.*` or `org.json.*`.
- Everything compiles and executes directly in fast JVM unit tests (`testDebugUnitTest`) without Android mocks.

---

## 🏗️ Project Architecture

```
app/src/main/
├── AndroidManifest.xml
├── java/com/example/learninghelper/
│   ├── LearningHelperApp.kt          # Application initialization & notification channels
│   ├── MainActivity.kt               # Jetpack Compose navigation host & singleTask routing
│   ├── BlockActivity.kt              # Full-screen lock overlay shown over blocked apps
│   ├── PreSessionActivity.kt         # Superimposed T-2m countdown (Mute / Proceed / 20w Skip)
│   ├── data/
│   │   └── FocusRepository.kt        # Local offline storage + monotonic clock anchor + JSON history
│   ├── focus/
│   │   ├── FocusPolicy.kt            # Pure Kotlin: session phases, tail rules, block policies
│   │   ├── EssayValidator.kt         # Pure Kotlin: Roman-alphabet anti-spam & entropy pipeline
│   │   ├── ScheduleCalculator.kt     # Pure Kotlin: next milestone calculation (T-5m, T-2m, T-0)
│   │   ├── AlarmScheduler.kt         # AlarmReceiver & auto-stop AlarmPlayer
│   │   ├── AlarmSchedulerManager.kt  # Exact alarm chaining via AlarmManager
│   │   ├── AppCatalog.kt             # Cached app metadata, allowlists, and blocklists
│   │   ├── BlockerAccessibilityService.kt # Foreground & multi-window detection
│   │   ├── FocusNotificationListenerService.kt # Distraction notification suppressor
│   │   ├── FocusService.kt           # Foreground service + Chronometer break overlay
│   │   ├── FocusNotifications.kt     # Notification channels & ongoing countdown builders
│   │   ├── BootReceiver.kt           # Session restoration & monotonic clock reconciliation on boot
│   │   └── Permissions.kt            # Accessibility, overlay, notification, and battery helpers
│   └── ui/
│       ├── HomeScreen.kt             # Dashboard, interval selectors, active countdown, setup cards
│       ├── AllowlistScreen.kt        # Allowed apps selector with 5-app quota & Essentials quick-filter
│       ├── ScheduleScreen.kt         # Recurring weekly days + Calendar DatePicker
│       ├── EmergencyExitScreen.kt    # 100-word essay input with persistent writing timer
│       ├── DeskClockScreen.kt        # AOD Landscape AMOLED desk clock with animated break cues
│       ├── HistoryScreen.kt          # Offline stats, daily streaks, skip reasons & exit essays
│       ├── SettingsScreen.kt         # Min writing time & Samsung alarm ringtone selector
│       └── theme/                    # Material 3 theme and typography
└── src/test/java/com/example/learninghelper/
    └── FocusLogicTest.kt             # Comprehensive JVM unit test suite
```

---

## 🚀 Building & Running

### Prerequisites
- **Android Studio** (Koala / Ladybug or newer)
- **Android SDK:** Compile & Target SDK `37`, Minimum SDK `33` (Android 13+)
- **Target Device:** Samsung Galaxy phone running One UI (Android 13–16)

### Windows PowerShell Build Instructions
`java` may not be in your default system `PATH`. Set `JAVA_HOME` to Android Studio's bundled JetBrains Runtime (JBR):

```powershell
# Set JAVA_HOME to Android Studio JBR
$env:JAVA_HOME = "C:\Users\$env:USERNAME\AppData\Local\Programs\Android Studio\jbr"

# Run JVM Unit Tests
.\gradlew.bat testDebugUnitTest --console=plain

# Assemble Debug APK
.\gradlew.bat assembleDebug --console=plain
```

### Installation
Deploy directly to your connected device:
```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 🧪 Automated Testing

Pure logic modules are tested with JVM unit tests that execute in seconds:
- **`FocusPolicyTest`:** Phase transitions (`WORK` ↔ `BREAK`), tail-break clipping rules, quota enforcement, and block decision policies.
- **`EssayValidatorTest`:** Latin-script normalization, paste interception, Shannon unigram/bigram entropy validation, character-trigram sentence similarity, and historical content-word cosine comparison.
- **`ScheduleCalculatorTest`:** Weekly recurring intervals, calendar date calculations, occurrence collision handling, and milestone alarm sequencing (`T-5m`, `T-2m`, `T-0`).

---

## 📄 License
Personal open-source study project. Intended for sideloading and personal productivity enhancement.
