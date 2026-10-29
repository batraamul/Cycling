# Architecture — how the Cycling app works

This document is written to be *learned from*: it explains what each part does, why it was built that way,
and what alternatives were considered. Read it alongside the source.

## 1. Technology choice

| Option | Why (not) for this app |
|---|---|
| **Native Kotlin + Jetpack Compose** ✅ | Background sensors, foreground services, boot receivers and battery rules are Android-OS features. Native code uses them directly, with the smallest APK and no bridge layer. |
| Flutter / React Native | Fine for UI, but continuous background step counting and GPS services would still need native Kotlin plugins — two languages to debug for the hardest part. |
| PWA | Browsers cannot count steps or record GPS in the background with the screen off. Not viable. |
| iOS (Swift) | Not needed — the target is an Android phone. |

**Verified fact:** the app has *no* third-party runtime libraries beyond AndroidX (Compose, Room, Navigation,
Lifecycle) and Kotlin coroutines. No Google Play Services, no map SDK, no chart library.

## 2. The big picture

```
 ┌──────────── UI (Compose) ────────────┐
 │ Today · Ride · Calibration · Analysis │   reads Flows, sends commands
 └───────────────┬───────────────────────┘
                 │  AppViewModel → TrackingController / AppRepository
 ┌───────────────▼───────────────────────┐
 │ TrackingEngine  (the Mode Controller) │  ONE instance per process, one mutex
 │  WALKING: StepSensor  ──┐             │
 │  CYCLING: LocationFeed ─┘ never both  │
 └──────┬─────────────────────┬──────────┘
        │ batched writes      │ status Flow
 ┌──────▼──────┐       ┌──────▼─────────────────────┐
 │ Room (SQLite)│      │ TrackingService (foreground)│ notification, keeps process alive
 └─────────────┘       └────────────────────────────┘
```

### Why a foreground service?
Android freezes or kills background apps to save battery. A **foreground service** shows a persistent notification
and tells the OS "the user is relying on this right now". Since Android 14 every foreground service must declare a
**type**: this app uses `health` (step counting) and adds `location` only during a ride.

**Verified (Android docs):** Android 15 forbids starting *camera, dataSync, mediaPlayback, mediaProjection,
phoneCall, microphone* services from `BOOT_COMPLETED`; `health` is not on that list, so walking tracking can
restart after a reboot. A `location` service needs the app in the foreground, so a ride interrupted by a reboot
is marked **interrupted** and the user is asked to resume or finish it.

## 3. Execution flow

**App launch:** `CyclingApp.onCreate` builds the `AppContainer` (database, engine, controller) → `MainActivity`
draws `CyclingAppUi` → on every resume `vm.ensureTracking()` sends `ACTION_RESTORE` to the service →
`TrackingService.onStartCommand` calls `startForeground(...)` → `engine.restore()` reads the saved mode from the
`tracker_state` table and starts the step sensor (walking) or the ride (cycling).

**A step happens:** the hardware step counter reports the *cumulative* steps since boot →
`StepCounterMath.update()` subtracts the previous reading (the *baseline*) → new steps are added to memory →
every 50 steps or 60 s, `TrackingDao.commitSteps()` writes **the new total and the new baseline in one SQL
transaction**. That single transaction is what makes steps impossible to lose or double-count after a crash.

**Start Cycling pressed:** save walking total → stop step sensor → start GPS (if that fails, walking restarts and
nothing changes) → in ONE transaction: create the ride row + set mode `CYCLING` + *invalidate the step baseline*.
Invalidating the baseline means the first step reading after the ride only sets a new baseline, so steps the
sensor registered while pedalling are never added.

**A GPS fix arrives:** `DistanceAccumulator.add()` filters it (accuracy, impossible-speed jumps, jitter anchor,
Doppler-stationary gate) → accepted points are batched (15 points / 30 s) and written with the ride totals.

**Finish Cycling:** stop GPS → final elapsed time, calories → one transaction: ride `COMPLETED` + mode `WALKING`
→ step sensor restarts with an invalid baseline.

## 4. Key components (files)

| File | Role |
|---|---|
| `core/TrackingStateMachine.kt` | Pure rules: which transitions are legal; `countsSteps` and `recordsGps` are mutually exclusive by construction. |
| `core/StepCounterMath.kt` | Baseline bookkeeping for the cumulative hardware counter, reboot detection (`BOOT_COUNT`). |
| `core/AccelerometerStepDetector.kt` | Fallback step detection: magnitude → gravity removal → smoothing → peak detection → rhythm gate (4 regular steps before counting, then retroactive). |
| `core/DistanceAccumulator.kt` | GPS distance filter; raw distance only. |
| `core/Calibration.kt` | Step-length statistics, `d × N / 1000`, GPS calibration evaluation `k = Dr / Dm` with GOOD/WARNING/REJECTED rules. |
| `tracking/TrackingEngine.kt` | The Mode Controller. All operations under one `Mutex` on a single-threaded coroutine scope. No Android `Service` dependency → testable. |
| `tracking/TrackingService.kt` | Thin Android shell: foreground type, notification, start/stop. |
| `tracking/AndroidSources.kt` | Real sensors: hardware counter → wake-up accelerometer → accelerometer + timed wake lock; GPS via `LocationManager`. |
| `data/Daos.kt` | SQL, including the atomic transactions and day-aggregated analytics queries. |
| `core/ActivityType.kt` | Cycling, Running, Jogging, Treadmill, Exercise — each declares `usesGps` / `countsSteps`; the engine reads these flags instead of special-casing names. |
| `data/BackupCodec.kt` | Streaming JSON backup format (JsonWriter/JsonReader: memory stays small however long the history). |
| `data/BackupManager.kt` | Export, preview and **merge-restore** in one transaction (never deletes; idempotent). |
| `backup/DriveBackup.kt` | Storage Access Framework file in Google Drive, persisted write permission, WorkManager daily job. |
| `ui/theme/*` | 10 `ThemeSpec`s, procedural vector artwork and background textures (no bitmaps). |
| `ui/components/Components.kt` | Shared cards, tiles, `ActionButton` / `OutlineActionButton` (≥ 56 dp, contrast-checked labels). |

## 4a. Activity sessions (v1.2.0)
The mode `SESSION` (called `CYCLING` before v1.2.0; the old stored value is still understood) means "an activity
is being recorded". The session's `ActivityType` decides the sensors:

| Type | GPS | Step sensor → session | Distance |
|---|---|---|---|
| Cycling | ✓ | – | GPS × cycling calibration factor |
| Running / Jogging | ✓ | ✓ | GPS (no factor: it was measured on a bike) |
| Treadmill | – | ✓ | steps × step length (running length if cadence ≥ 140 and calibrated) |
| Exercise | – | – | none; time + MET 5.0 calories |

Daily walking is paused during every session, exactly as for cycling. Session steps are stored on the session row
and saved in the same transaction as the step-counter baseline (`TrackingDao.commitSession`) — the same
no-duplicates rule as daily walking. Treadmill/exercise need no location type for the foreground service, so they
continue after a reboot instead of becoming "interrupted".

## 4b. Backup to Google Drive without the INTERNET permission
The app writes the backup through the Storage Access Framework: the user creates `Cycling-backup.json` in a Drive
folder using the system file picker; the app keeps a persistable URI permission and overwrites that file daily
(WorkManager, battery-not-low) and a few minutes after each finished activity. The **Drive app** uploads it. So
there is no Google sign-in, API key or Cloud project in this app, and no network code. Safety rules: an automatic
backup is skipped while the database is empty (a fresh install must not overwrite the good copy before restore);
restore is a merge in one transaction (higher step count per day wins; sessions matched by start time; nothing
deleted); a damaged or newer-format file is refused with nothing changed.

## 5. Calibration design
- **Step length:** the measured value is stored and used as-is. The height formula (0.415 × height) is used only
  until the user saves a measurement and never silently replaces it. Single-step (median of repeats), course
  (length ÷ steps) and manual modes are kept distinct; the method is recorded. Optional running step length is
  used when cadence ≥ 140 steps/min.
- **GPS:** `k = 1.000 / Dm`. Rejected if fewer than 30 fixes, mean accuracy worse than ±20 m, or |k−1| > 20 %.
  Warning between ±10 m / 8 %. Applied only after explicit confirmation; the active factor is the mean of all
  applied calibrations. Every ride stores its **raw** distance and the factor used, so corrections are transparent
  and *Reset to default* never alters history. Routes are never distorted.

## 6. Battery decisions
- Walking never uses GPS. Hardware step counter with 30 s hardware batching.
- Database writes batched (steps ≈ once a minute; route points every 15 fixes).
- Wake lock only in the last-resort accelerometer mode, with a 10-minute timeout renewed while data flows.
- No animation loops: artwork is static vector drawing; the ride clock ticks only while frames are drawn.
- Notification refresh limited to once per 2 s; ride time uses the system chronometer (no updates needed).

## 7. Dependency versions (and why not the newest)
The newest AndroidX releases (Compose UI 1.12, Navigation 2.10, Activity 1.13) require **compileSdk 37 and
Android Gradle Plugin 9.1+**. This project deliberately stays on AGP 8.13.2 / compileSdk 36 with Compose BOM
2026.05.00 and Navigation 2.9.7 — mature, compatible versions. Lint's "newer version available" notices are
therefore disabled. Upgrading to AGP 9 is a planned, separate step.

## 8. Known limitations (honest list)
- Hardware step counters deliver steps in batches; steps taken while the phone sleeps across midnight are
  attributed to the day they are delivered.
- If OxygenOS force-stops the app (e.g. swipe-away without battery exemption), tracking stops until the app is
  opened again; counter-based steps taken meanwhile are recovered from the cumulative counter when tracking
  resumes (as long as the phone was not rebooted in between and walking mode was active).
- A ride interrupted by a reboot loses the distance during the gap.
- Calorie values are estimates (Compendium of Physical Activities METs for cycling; 0.5 kcal/kg/km walking).
- The accelerometer fallback algorithm is verified on synthetic signals only; it must be validated on the phone.
- Treadmill distance depends on the step length: calibrate a running step length for treadmill running.
- Jogging and Running record identically (GPS + steps); they are separate labels for your history.
- Automatic Drive backups depend on the Drive app keeping write access to the chosen file; if it is lost, Settings
  shows "Last backup failed" and asks you to choose the file again. Not yet verified on a phone.
- The backup file is not encrypted; it is protected by your Google account.
