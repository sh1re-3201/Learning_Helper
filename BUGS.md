# BUGS.md: Post-Accessibility Refactor & Architecture Audit Findings

> **Audit Date:** 2026-11-11  
> **Auditors:** 5-Member Architecture Council + 2 Independent Inquisitors (Fact-Checker & Red-Team Blind Spot Inquisitor)  
> **Status:** AUDITED, VERIFIED, CATALOGED — READY FOR FIXING PHASE

---

## 1. Executive Summary & Verification Matrix

Following the migration from `AccessibilityService` to `UsageStatsManager`, a deep audit across all subsystems identified critical vulnerabilities, lifecycle desynchronizations, and hardware/platform traps.

- **Total Council Claims Audited:** 32
  - **Confirmed True:** 30
  - **Platform Nuance:** 1 (PiP / Floating overlays inherent to `UsageStatsManager`)
  - **False Positive:** 1 (Finding 13 rejected: `AGENTS.md` intentionally dismisses passive notifications during WORK even for allowed apps)
- **Blind Spots Discovered by Inquisitor 2:** 13 additional critical/high/medium flaws missed by the council (including an exact timestamp equality bug blocking skips, permanent DND trap on app kill, and crash loops).
- **Total Unique Confirmed Bugs:** 43 issues structured into 5 severity tiers.

---

## 2. Master Bug Index

| Bug ID | Subsystem | Severity | File Location | Line(s) | Description |
|---|---|---|---|---|---|
| **BUG-01** | Platform / Lifecycle | **CRITICAL (P0)** | `app/src/main/AndroidManifest.xml` | 99 | `BootReceiver` is `exported="false"`; OS drops all boot & time change broadcasts |
| **BUG-02** | Focus / Pre-Session | **CRITICAL (P0)** | `data/FocusRepository.kt` | 374 | Exact equality (`active.startAt == scheduledStartMillis`) always rejects pre-session skip in grace period |
| **BUG-03** | Audio / DND | **CRITICAL (P0)** | `ui/DeskClockScreen.kt` | 93–102 | Killing Desk Clock or LMK process death leaves device permanently trapped in "Alarms Only" DND & muted notifications |
| **BUG-04** | Storage / Crash | **CRITICAL (P0)** | `data/FocusRepository.kt` | 157–193 | Corrupted `workMin < 5` in SharedPreferences crashes `FocusSession.init` in `Application.onCreate` (unrecoverable bootloop) |
| **BUG-05** | Anti-Tamper / Policy | **CRITICAL (P0)** | `ui/AllowlistScreen.kt` | 207–228 | Android Settings can be disabled in Allowlist, blocking Settings in WORK mode and preventing app uninstallation |
| **BUG-06** | Clock / Tampering | **CRITICAL (P1)** | `data/FocusRepository.kt` | 205–217 | Forward clock shift + reboot bypasses active focus session via unvalidated `wallDelta` in `reconcileReboot` |
| **BUG-07** | Anti-Bypass / Window | **CRITICAL (P1)** | `focus/ForegroundAppMonitor.kt` | 280–316 | Multi-window, Split-screen & Samsung Pop-up View bypass: blocked apps run unrestricted if allowed app tapped last |
| **BUG-08** | Knox / Sandboxing | **CRITICAL (P1)** | `focus/ForegroundAppMonitor.kt` | 285–332 | Samsung Secure Folder & Dual Messenger sandbox bypass: `UsageStatsManager` in User 0 blind to User 150/95 |
| **BUG-09** | Anti-Tamper / Window | **CRITICAL (P1)** | `focus/FocusService.kt` | 69–75, 97 | `ForegroundAppMonitor` is never started during `[T-5m, T-0)`: Device Care, Routines, and App Stores unblocked in pre-session |
| **BUG-10** | Anti-Tamper / Security | **HIGH (P1)** | `focus/ForegroundAppMonitor.kt` | 140–142 | Revoking Usage Access in system Settings causes monitor to silently return, unlocking all apps device-wide |
| **BUG-11** | Focus / State | **HIGH (P1)** | `BlockActivity.kt` | 140–154 | `BlockActivity` does not evaluate `FocusPolicy.shouldBlock` dynamically; normal apps remain trapped during BREAK |
| **BUG-12** | Call Guard / VoIP | **CRITICAL (P1)** | `focus/ForegroundAppMonitor.kt` | 185–200 | Mid-session VoIP calls (Teams, Zoom, Meet) blocked within 400ms because grandfathering only checked on tick 1 |
| **BUG-13** | Call Guard / Audio | **CRITICAL (P1)** | `ui/DeskClockScreen.kt` | 93–99 | `DeskClockScreen` activates `INTERRUPTION_FILTER_ALARMS`, completely muting incoming call ringtones |
| **BUG-14** | Call Guard / State | **HIGH (P1)** | `focus/FocusNotificationListenerService.kt` | 112–126 | `findActiveCallPackage()` does not check `sbn.isOngoing`; stale missed call notifications hijack call detection |
| **BUG-15** | Audio / Alarms | **HIGH (P1)** | `focus/AlarmScheduler.kt` | 32–36 | `AlarmPlayer` omits `AudioManager.MODE_RINGTONE`; alarm sound blares over incoming phone call ringtones |
| **BUG-16** | Notification Service | **HIGH (P1)** | `focus/FocusNotificationListenerService.kt` | 22–26 | Notification listener unbind on memory pressure kills notification blocker permanently with zero rebind attempts |
| **BUG-17** | Monotonic / Alarms | **HIGH (P1)** | `focus/AlarmSchedulerManager.kt` | 33–46 | Phase boundary alarms use `RTC_WAKEUP` with `phaseEndAt > now`; forward clock shifts cancel the boundary alarm |
| **BUG-18** | Battery / Screen-Off | **HIGH (P1)** | `focus/ForegroundAppMonitor.kt` | 35 | Service recreate with screen off runs continuous 400ms polling loop (0% idle CPU invariant violation) |
| **BUG-19** | Battery / Screen-Off | **HIGH (P1)** | `focus/FocusService.kt` | 42–46, 144 | `scheduleCheck` clamps delay to 60s and ignores `ACTION_SCREEN_OFF`, waking CPU every 60s while screen is off |
| **BUG-20** | Scheduling / Lookahead | **HIGH (P1)** | `focus/ScheduleCalculator.kt` | 108–119 | Weekly recurrence search capped at 8 days (`0L..8L`), permanently dropping alarms if a single recurrence is skipped |
| **BUG-21** | Android 14 / BAL | **HIGH (P1)** | `focus/AlarmScheduler.kt` | 178–184 | Android 14 Background Activity Launch (BAL) violation at T-2m: `startActivity` fails over Keyguard without full-screen intent |
| **BUG-22** | WakeLock / Sleep | **MEDIUM (P1)** | `focus/AlarmScheduler.kt` | 187–199 | Missing WakeLock in `ACTION_START`: device can re-enter Doze before `FocusService` starts |
| **BUG-23** | Scheduling / Midnight | **HIGH (P1)** | `focus/ScheduleCalculator.kt` | 107–135 | Overnight cross-midnight schedules (23:30–01:30) ignored if phone boots after 00:00 |
| **BUG-24** | Storage / Reinstall | **HIGH (P2)** | `data/FocusRepository.kt` | 150–154, 545 | Reinstallation wipes `/Documents/LearningHelper/backup.json`: permissions granted post-install overwrite with empty state |
| **BUG-25** | Storage / JSON | **HIGH (P2)** | `data/FocusRepository.kt` | 650–689, 721 | Single corrupt entry in schedules or history JSON wipes all entries due to whole-loop `runCatching` |
| **BUG-26** | Validation / Timer | **HIGH (P2)** | `ui/EmergencyExitScreen.kt` | 48–69 | Emergency Exit 9x open/exit exploit: satisfies 3-minute writing timer with 0 keystrokes |
| **BUG-27** | Validation / Draft | **HIGH (P2)** | `ui/EmergencyExitScreen.kt` | 51–69 | Emergency exit draft text stops saving once `elapsedSeconds >= 180s`, losing subsequent essay text on Back |
| **BUG-28** | Validation / Anti-Spam | **HIGH (P2)** | `ui/EmergencyExitScreen.kt` | 170 | Skip reasons dilute past emergency exit essays in 20-essay check, enabling identical essay reuse |
| **BUG-29** | Storage / Quota | **MEDIUM (P2)** | `data/FocusRepository.kt` | 613–617 | External backup merge bypasses 5-app optional quota, merging sets without clamping |
| **BUG-30** | Concurrency / Memory | **MEDIUM (P1)** | `focus/ForegroundAppMonitor.kt` | 38–48 | Non-volatile fields (`lastKnownForegroundPackage`, `lastAllowedPackage`) accessed across threads without sync |
| **BUG-31** | Monotonic / State | **HIGH (P1)** | `data/FocusRepository.kt` | 209, 283 | Monotonic elapsed time is never checkpointed to disk during active session, losing progress on reboot |
| **BUG-32** | History / State | **MEDIUM (P1)** | `data/FocusRepository.kt` | 265–274 | Expired sessions overwritten without being logged to history when `startSession()` is called |
| **BUG-33** | Alarms / Orphaned | **MEDIUM (P1)** | `MainActivity.kt` | 62–65 | Emergency exit does not cancel `ACTION_PHASE_BOUNDARY` in `AlarmManager`, leaving orphaned alarms |
| **BUG-34** | Service / Notification | **MEDIUM (P1)** | `focus/FocusService.kt` | 69–75 | Missing completion notification in `FocusService` when `session == null`; return value of `clearExpiredSession` ignored |
| **BUG-35** | Storage / Leak | **LOW (P2)** | `data/FocusRepository.kt` | 501 | `_firedMilestones` and `_skippedOccurrences` grow unboundedly over months with zero pruning |
| **BUG-36** | UI / Recomposition | **MEDIUM (P2)** | `ui/HomeScreen.kt` | 46, 53–65 | `HomeScreen` root-level 1-second ticker triggers full layout recomposition churn |
| **BUG-37** | UI / Recomposition | **LOW (P2)** | `ui/DeskClockScreen.kt` | 230, 241 | `DeskClockScreen` mutates Compose state inside `AndroidView.factory` |
| **BUG-38** | Audio / Ducking | **MEDIUM (P2)** | `focus/AlarmScheduler.kt` | 28–69 | `AlarmPlayer` never requests AudioFocus; mixes with Spotify/podcasts and traps audio in Bluetooth earbuds |
| **BUG-39** | Samsung / Edge Panel | **HIGH (P1)** | `focus/AppCatalog.kt` | 16–22 | Samsung Edge Panel (`com.samsung.android.app.cocktailbarservice`) allows launching floating Pop-ups during WORK |
| **BUG-40** | Samsung / Hardware Key | **MEDIUM (P2)** | `PreSessionActivity.kt` | 96–102 | Hardware volume keys cannot silence T-5m alarm sound while screen is off |
| **BUG-41** | Samsung / DeX | **MEDIUM (P2)** | `focus/ForegroundAppMonitor.kt` | 265–271 | Samsung DeX multi-display failure: `BlockActivity` launches only on phone display, leaving DeX desktop unblocked |
| **BUG-42** | Pre-Session / Grace | **HIGH (P1)** | `data/FocusRepository.kt` | 368 | `skipOccurrence` hardcodes `now > scheduledStartMillis + 60s`, rejecting skip if pre-session activity launched late |
| **BUG-43** | Scheduling / Overlap | **MEDIUM (P2)** | `ui/ScheduleScreen.kt` | 413–442 | Zero schedule overlap validation causes silent interval and setting overrides |

---

## 3. Detailed Bug Profiles & Root Causes

### BUG-01: `BootReceiver` Exported Flag
- **File:** [`AndroidManifest.xml:99`](file:///D:/Projects/LearningHelper/app/src/main/AndroidManifest.xml#L99)
- **Root Cause:** Declared with `android:exported="false"`. Android 12+ drops implicit system broadcasts (`BOOT_COMPLETED`, `TIME_SET`) sent to unexported receivers.
- **Fix:** Set `android:exported="true"`.

### BUG-02: Exact Equality Check Breaking Pre-Session Skip Grace Period
- **File:** [`FocusRepository.kt:374`](file:///D:/Projects/LearningHelper/app/src/main/java/com/example/learninghelper/data/FocusRepository.kt#L374)
- **Root Cause:** `if (active.startAt == scheduledStartMillis)` fails because `startAt` includes exact milliseconds when `AlarmReceiver` fired (e.g. `10:00:00.042`), whereas `scheduledStartMillis` is aligned to the minute (`10:00:00.000`).
- **Fix:** Compare with `kotlin.math.abs(active.startAt - scheduledStartMillis) <= 90_000L`.

### BUG-03: Permanent System-Wide DND "Alarms Only" Lockout on App Termination
- **File:** [`DeskClockScreen.kt:93-102`](file:///D:/Projects/LearningHelper/app/src/main/java/com/example/learninghelper/ui/DeskClockScreen.kt#L93-L102)
- **Root Cause:** DND filter reset and notification un-mute live solely in Compose `DisposableEffect.onDispose`. Swiping app from Recents or LMK kill bypasses `onDispose`.
- **Fix:** Add DND reset failsafe in `LearningHelperApp.onCreate()`, `MainActivity.onDestroy()`, and `BootReceiver`.

### BUG-04: Fatal Bootloop from Unhandled `FocusSession` Preconditions
- **File:** [`FocusRepository.kt:157-193`](file:///D:/Projects/LearningHelper/app/src/main/java/com/example/learninghelper/data/FocusRepository.kt#L157-L193)
- **Root Cause:** `FocusSession.init` throws `IllegalArgumentException` if `workMinutes < 5` or `endAt <= startAt`. `restoreSession()` called during `Application.onCreate` has no try-catch.
- **Fix:** Wrap `restoreSession()` in `runCatching` and defensively clamp inputs.

### BUG-05: Android Settings Can Be Disabled in Allowlist
- **File:** [`AllowlistScreen.kt:207-228`](file:///D:/Projects/LearningHelper/app/src/main/java/com/example/learninghelper/ui/AllowlistScreen.kt#L207-L228), [`AppCatalog.kt:39-61`](file:///D:/Projects/LearningHelper/app/src/main/java/com/example/learninghelper/focus/AppCatalog.kt#L39-L61)
- **Root Cause:** `com.android.settings` is present in `DEFAULT_ALLOWLIST` with an active "Disable" button in UI, and omitted from `AppCatalog.STATIC_ALWAYS_ALLOWED`.
- **Fix:** Add `com.android.settings` to `STATIC_ALWAYS_ALLOWED` and lock its toggle in the GUI.

### BUG-06: Clock Tampering Forward + Reboot Session Bypass
- **File:** [`FocusRepository.kt:205-217`](file:///D:/Projects/LearningHelper/app/src/main/java/com/example/learninghelper/data/FocusRepository.kt#L205-L217)
- **Root Cause:** `reconcileReboot()` trusts `curWall - lastSavedWall` blindly and commits it directly to `KEY_ACCUMULATED_ELAPSED`.
- **Fix:** Cap reboot wall delta to `MAX_REBOOT_SLACK_MS` (5m) and periodically flush monotonic elapsed time during the session.

### BUG-07: Split-Screen & Samsung Pop-up View Bypass
- **File:** [`ForegroundAppMonitor.kt:280-316`](file:///D:/Projects/LearningHelper/app/src/main/java/com/example/learninghelper/focus/ForegroundAppMonitor.kt#L280-L316)
- **Root Cause:** Iterates `UsageEvents` and only retains `latestPkg` with the single newest timestamp.
- **Fix:** Maintain a set of currently resumed packages (resumed without subsequent pause/stop) and evaluate `shouldBlock()` against all visible packages.

### BUG-08: Samsung Secure Folder & Dual Messenger Sandbox Bypass
- **File:** [`ForegroundAppMonitor.kt:285-332`](file:///D:/Projects/LearningHelper/app/src/main/java/com/example/learninghelper/focus/ForegroundAppMonitor.kt#L285-L332), [`AppCatalog.kt:16-22`](file:///D:/Projects/LearningHelper/app/src/main/java/com/example/learninghelper/focus/AppCatalog.kt#L16-L22)
- **Root Cause:** Knox container apps run in separate user profiles (User 150/95) not visible to User 0 `UsageStatsManager`.
- **Fix:** Add `com.samsung.knox.securefolder` and `com.samsung.android.da.daagent` to `ALWAYS_BLOCKED_DURING_SESSION`.

### BUG-09: Pre-Session Blocker Ineffective During `[T-5m, T-0)`
- **File:** [`FocusService.kt:69-75`](file:///D:/Projects/LearningHelper/app/src/main/java/com/example/learninghelper/focus/FocusService.kt#L69-L75), [`AlarmScheduler.kt:150-186`](file:///D:/Projects/LearningHelper/app/src/main/java/com/example/learninghelper/focus/AlarmScheduler.kt#L150-L186)
- **Root Cause:** `ForegroundAppMonitor.start()` is only called in `FocusService.onStartCommand()`, which stops itself if `activeSession() == null`.
- **Fix:** Call `ForegroundAppMonitor.start(context)` on `ACTION_PRE_5M` and keep monitor active during `inPreSession`.

### BUG-12: Mid-Session VoIP Calls Blocked
- **File:** [`ForegroundAppMonitor.kt:185-200`](file:///D:/Projects/LearningHelper/app/src/main/java/com/example/learninghelper/focus/ForegroundAppMonitor.kt#L185-L200)
- **Root Cause:** `workPhaseGrandfatherChecked` flag is set to `true` on tick 1 of `WORK`. Incoming VoIP calls arriving later are never grandfathered and get blocked within 400ms.
- **Fix:** Dynamically query active call state (`MODE_IN_COMMUNICATION` / notification listener) on every check tick.

### BUG-13: Desk Clock Mutes Incoming Phone Call Ringtones
- **File:** [`DeskClockScreen.kt:93-99`](file:///D:/Projects/LearningHelper/app/src/main/java/com/example/learninghelper/ui/DeskClockScreen.kt#L93-L99)
- **Root Cause:** Sets `INTERRUPTION_FILTER_ALARMS`, which suppresses incoming call rings across Android OS.
- **Fix:** Use `INTERRUPTION_FILTER_PRIORITY` (allowing calls) or avoid DND interruption filter adjustments.

### BUG-17: Monotonic Clock Desync Cancels Boundary Alarms
- **File:** [`AlarmSchedulerManager.kt:33-46`](file:///D:/Projects/LearningHelper/app/src/main/java/com/example/learninghelper/focus/AlarmSchedulerManager.kt#L33-L46)
- **Root Cause:** Schedules boundary alarms using `AlarmManager.RTC_WAKEUP` comparing against wall clock `phaseEndAt > now`. Forward clock changes cancel the alarm.
- **Fix:** Switch to `AlarmManager.ELAPSED_REALTIME_WAKEUP` using `SystemClock.elapsedRealtime() + remainingMs`.

### BUG-18: Screen-Off 400ms Polling Loop on LMK Service Recreate
- **File:** [`ForegroundAppMonitor.kt:35`](file:///D:/Projects/LearningHelper/app/src/main/java/com/example/learninghelper/focus/ForegroundAppMonitor.kt#L35)
- **Root Cause:** `isScreenOn` defaults to `true`. If service restarts while device is asleep, 400ms polling starts immediately.
- **Fix:** Query `PowerManager.isInteractive` inside `start()`.

### BUG-20: Weekly Recurrence Search Capped at 8 Days
- **File:** [`ScheduleCalculator.kt:108-119`](file:///D:/Projects/LearningHelper/app/src/main/java/com/example/learninghelper/focus/ScheduleCalculator.kt#L108-L119)
- **Root Cause:** `dayOffset in 0L..8L` stops searching at 8 days. Skipping one occurrence pushes next occurrence to day 13, returning null and dropping alarms.
- **Fix:** Expand scan loop to `0L..21L` (3 weeks).

### BUG-24: Reinstallation Wipes External Backup File
- **File:** [`FocusRepository.kt:150-154, 539-556`](file:///D:/Projects/LearningHelper/app/src/main/java/com/example/learninghelper/data/FocusRepository.kt#L150-L154)
- **Root Cause:** `syncWithExternalBackup()` is only called in `init()` if permission is granted. On reinstall, permission is granted later, and subsequent edits overwrite `/Documents/LearningHelper/backup.json` with empty local state.
- **Fix:** Trigger `syncWithExternalBackup()` when All Files Access permission is granted.

### BUG-26: Emergency Exit 9x Timer Exploit
- **File:** [`EmergencyExitScreen.kt:48-69`](file:///D:/Projects/LearningHelper/app/src/main/java/com/example/learninghelper/ui/EmergencyExitScreen.kt#L48-L69)
- **Root Cause:** Initial active typing elapsed grants the first 20 seconds without typing. Opening/closing the screen 9 times satisfies 3 minutes with 0 keystrokes.
- **Fix:** Only accumulate writing time after verified text input.

---

## 4. Remediation Grouping & Execution Plan

- **Phase 1 (P0 Platform Invariants & Anti-Trap):**
  - Fix BUG-01 (`BootReceiver` export)
  - Fix BUG-02 (Pre-session skip timestamp equality)
  - Fix BUG-03 (DND & mute cleanup failsafe)
  - Fix BUG-04 (`restoreSession` defensive crash prevention)
  - Fix BUG-05 (Android Settings unblockable & STATIC_ALWAYS_ALLOWED)
- **Phase 2 (P1 Anti-Bypass, Clock & Calls):**
  - Fix BUG-06 (Reboot clock tampering hardening)
  - Fix BUG-07 (Multi-window / split-screen tracking)
  - Fix BUG-08 (Samsung Secure Folder package blocking)
  - Fix BUG-09 (Pre-session monitor lifecycle)
  - Fix BUG-10 (Usage Access revocation defense)
  - Fix BUG-11 (`BlockActivity` dynamic unblock in break)
  - Fix BUG-12 & BUG-14 (Mid-session VoIP call protection)
  - Fix BUG-13 & BUG-15 (DeskClock & incoming call audio)
  - Fix BUG-16 (NotificationListener rebind)
  - Fix BUG-17 & BUG-22 (Monotonic boundary alarms & WakeLock)
  - Fix BUG-18 & BUG-19 (Screen-off 0% CPU enforcement)
  - Fix BUG-20 & BUG-23 (Weekly lookahead 21d & overnight recovery)
  - Fix BUG-21 (Android 14 BAL full-screen intent)
  - Fix BUG-30 & BUG-31 (Volatile state & monotonic progress checkpoint)
- **Phase 3 (P2 Storage, Validation & Performance):**
  - Fix BUG-24 (External backup sync on permission grant)
  - Fix BUG-25 (Per-item JSON parsing isolation)
  - Fix BUG-26 & BUG-27 (Emergency exit timer exploit & draft persistence)
  - Fix BUG-28 (Emergency exit anti-plagiarism filter)
  - Fix BUG-29 (External backup 5-app quota clamping)
  - Fix BUG-32 & BUG-34 (History logging & completion notifications)
  - Fix BUG-33 (Emergency exit alarm cancellation)
  - Fix BUG-35 (Milestone pruning)
  - Fix BUG-36 & BUG-37 (Compose recomposition leaf scoping)
  - Fix BUG-38 (AudioFocus transient ducking)
  - Fix BUG-39 (Edge Panel package blocking)
  - Fix BUG-42 (Grace period clock buffer)
  - Fix BUG-43 (Schedule overlap validation)
