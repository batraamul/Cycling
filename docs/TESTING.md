# Testing

## 1. Automated tests (run on every push by GitHub Actions)

```bash
./gradlew testDebugUnitTest
```

| Suite | Tests | What it proves |
|---|---|---|
| `core/AccelerometerStepDetectorTest` | 5 | Fallback detector counts synthetic walking within 5 %, ignores a still phone and isolated bumps |
| `core/StepCounterMathTest` | 6 | Baseline logic: no double counting on replay, reboot detection, glitch rejection |
| `core/DistanceAccumulatorTest` | 6 | 1 km ride measured within 1.5 %; parked bike accumulates < 10 m; jumps/poor fixes rejected; pause gaps not bridged |
| `core/CalibrationTest` | 11 | `N × d / 1000`, median of repeats, course mode, GPS quality rules, factor combination, calories |
| `core/TrackingStateMachineTest` | 7 | Walking and cycling are mutually exclusive; pause does not resume walking |
| `integration/TrackingEngineIntegrationTest` | 12 | Real Room database + fake sensors: the mode controller end to end (see below) |

Integration tests map to the acceptance workflow:

| Acceptance step | Integration test |
|---|---|
| 5 Walking active by default | `walkingIsOnByDefaultAndStepsPersist` |
| 8–13 Start/Finish Cycling, no double counting | `cyclingSuspendsWalkingAndFinishResumesWithoutDoubleCounting` |
| 14 Restart persistence | `processDeathDuringWalkingNeitherLosesNorDuplicatesSteps` |
| Reboot mid-ride | `rebootMidRideMarksRideInterruptedAndNeverCountsItAsWalking` |
| Cycling cannot start → walking preserved | `startCyclingWithGpsOffKeepsWalkingIntact` |
| Pause/Resume | `pausingStopsTheClockAndDoesNotCountSteps` |
| Discard | `discardDeletesOnlyThatRide` |
| Midnight | `stepsBeforeMidnightStayOnThatDay` |
| Accelerometer fallback | `accelerometerFallbackCountsDetectedSteps` |
| Permission revoked | `missingActivityPermissionIsReportedNotIgnored` |
| 4 GPS calibration apply/decline/reset | `gpsCalibrationIsAppliedOnlyWhenConfirmedAndCanBeReset` |
| 15 Weekly analytics | `weeklyAnalyticsSeparateWalkingAndCycling` |

**Limits:** these tests use fake sensors. They prove the *logic*; they cannot prove that OxygenOS keeps the
service alive or that the phone's sensors behave as assumed. That is what the on-phone test below is for.

## 2. On-phone acceptance test (OnePlus 11R) — record results here

| # | Step | How to check | Result |
|---|---|---|---|
| 1 | Install the release APK | App opens | ☐ |
| 2 | Onboarding + permissions | Activity ✓, Notifications ✓, Location ✓ (While using) | ☐ |
| 3 | Calibrate one step | Calibration Centre shows value, method, date | ☐ |
| 4 | Calibrate on known 1 km | Result screen shows raw km, accuracy, k; apply only after ticking confirm | ☐ |
| 5 | Home shows **Walking Tracking Active**; notification visible | | ☐ |
| 6 | Lock phone, walk a known distance (e.g. 500 m, count steps) | | ☐ |
| 7 | Reopen: steps ≈ counted steps; distance ≈ steps × step length | Note error % | ☐ |
| 8 | Press **Start Cycling** | | ☐ |
| 9 | Status **Cycling Active — Walking Paused**; step total stops increasing | | ☐ |
| 10 | Ride a measured route | Distance vs known | ☐ |
| 11 | **Finish Cycling** | Ride detail opens | ☐ |
| 12 | Ride in Rides list; status back to **Walking Tracking Active** | | ☐ |
| 13 | Steps did not jump after the ride | Compare step count before/after | ☐ |
| 14 | Force-close & reopen app; reboot phone | Totals unchanged; tracking resumes after reboot | ☐ |
| 15 | Analysis → this week shows the ride and steps | | ☐ |

Also check: **Settings › Sensors on this phone** — record whether a *Step counter* is listed. If it says
"not present", select *Automatic* anyway (the app falls back to the accelerometer) and repeat steps 6–7 carefully.
You can force the fallback with *Accelerometer only* to compare both methods on the same walk.

## 3. Battery-usage test plan

Run each scenario from a full charge, same brightness, mobile data as usual. Use
*Settings › Battery › Battery usage* for the per-app figure.

| Scenario | Duration | Expectation | Measure |
|---|---|---|---|
| A. Walking mode, hardware counter, normal day | 24 h | Very low (typically ≤ 1–2 % of battery) | App % in Battery usage |
| B. Walking mode, forced accelerometer | 8 h | Noticeably higher than A (CPU wakes for batches) | App %; compare to A |
| C. Cycling ride, screen off, phone in pocket | 1 h | GPS-dominated; similar to other GPS trackers | Battery drop % |
| D. Cycling ride, screen on (Ride screen) | 30 min | Higher than C (display) | Battery drop % |
| E. Idle overnight in walking mode | 8 h | Near zero app usage | App % |

Developer view (USB debugging): `adb shell dumpsys batterystats --charged com.batraamul.cycling`
and `adb shell dumpsys power | grep -i wake_lock` (no wake lock expected with the hardware counter).
