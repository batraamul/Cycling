# Cycling — all-day walking tracker + GPS cycling log for Android

A lightweight native Android app (Kotlin + Jetpack Compose) that:

- **counts your walking steps all day in the background** (screen off, other apps open, after reboot),
- **records cycling rides with GPS** when you press **Start Cycling**,
- keeps the two **mutually exclusive**, so the same movement is never counted twice,
- lets you **calibrate** your step length and GPS distance on a known 1 km course,
- stores everything **only on your phone**, and
- comes in **eight visual themes** with vector artwork (release APK ≈ 3 MB).

Target device: OnePlus 11R 5G (OxygenOS). Works on any Android 10+ phone.

---

## Install on your phone (no Android Studio needed)

1. On the phone, open the repository's **Releases** page: `https://github.com/batraamul/Cycling/releases`
2. Download the latest `Cycling-vX.Y.Z.apk`.
3. Open the downloaded file. Android will ask to allow installs from your browser/Files app
   (**Settings › Install unknown apps**) — allow it for that app only.
4. Tap **Install**, then **Open**.
5. Complete onboarding: height/weight → permissions → battery settings → **Start tracking**.

**Updating to a newer version:** download the newer `Cycling-vX.Y.Z.apk` and open it — Android shows **Update**
instead of Install. Your data is kept *as long as both APKs are signed with the same release key* (all official
releases are) and the version number is higher. **Never uninstall to "update"** — uninstalling deletes your history.
The app has no internet access, so it cannot update itself; check the Releases page for new versions.

**Debug builds** (from the Actions tab) have the package name `com.batraamul.cycling.debug` and install *alongside*
the release app as a separate test app with its own data.

### Install from a computer with ADB (optional)
```bash
adb devices                                   # phone must show as "device" (enable USB debugging first)
adb install -r Cycling-v1.0.0.apk             # -r = replace/update, keeps data
```

---

## Using the app

| Screen | What it does |
|---|---|
| **Today** | Steps vs goal, walking/cycling km, calories (estimate), tracking status, **Start Cycling** |
| **Ride** | Live distance, time, speed, GPS accuracy, route; Pause/Resume, Finish Cycling, Discard |
| **Calibration Centre** | Step length (single-step, course, manual; walking & running) and 1 km GPS calibration |
| **Analysis** | Weekly bar charts and totals, all-time totals |
| **Rides** | Ride history (paged) and ride details with route and raw vs corrected distance |
| **Settings** | 8 themes, walking on/off, step-sensor choice, profile, permissions, battery guide |

### Tracking modes
- **Default: Walking ON.** Status shows *Walking Tracking Active*. No GPS is used for walking.
- **Start Cycling** → walking total is saved, the step sensor is stopped, GPS starts.
  Status: *Cycling Active — Walking Paused*.
- **Pause** keeps the ride open and does **not** restart step counting.
- **Finish Cycling** → ride saved → walking resumes automatically.
- **Discard** (with confirmation) deletes only that ride.

Steps you take while a ride is active (e.g. pushing the bike) are intentionally **not** counted.

### Pause step counting (motorbike, car, bus)
Engine vibration and road bumps can be miscounted as steps. On the Today screen tap **Pause step counting** and
choose 30 min, 1 h, 2 h or *until I resume* — or use **Pause steps 1 h** in the notification. Timed pauses resume
automatically; **Resume** is available on the Today screen and in the notification. Use **Start Cycling** for a
bicycle ride instead (it pauses steps *and* records the ride).

### OnePlus / OxygenOS battery settings
OxygenOS may stop background apps. In onboarding (or Settings) tap **Allow unrestricted battery**, and in
*App settings › Battery usage* enable **Allow background activity**. Optionally lock the app in Recent apps.

---

## Build it yourself

Requirements: JDK 17+, Android SDK (platform 36, build-tools 36). Android Studio is optional.

```bash
git clone https://github.com/batraamul/Cycling.git && cd Cycling
echo "sdk.dir=$HOME/Android/Sdk" > local.properties        # path to your Android SDK
./gradlew testDebugUnitTest      # 55 unit, integration and migration tests
./gradlew lintDebug              # static checks
./gradlew assembleDebug          # → app/build/outputs/apk/debug/app-debug.apk
```

Signed release (needs your keystore — see [docs/SIGNING.md](docs/SIGNING.md)):
```bash
./gradlew assembleRelease        # → app/build/outputs/apk/release/app-release.apk
```

### CI/CD
- **Android CI** (`.github/workflows/android-ci.yml`): every push/PR runs tests, lint and builds the debug APK (artifact).
- **Release** (`.github/workflows/release.yml`): pushing a tag `vX.Y.Z` builds a signed APK and publishes a GitHub Release.
  Needs the four signing secrets described in [docs/SIGNING.md](docs/SIGNING.md).

### Project layout
```
app/src/main/java/com/batraamul/cycling/
  core/       pure Kotlin logic (step detector, GPS filter, state machine, calibration maths) — unit-tested
  data/       Room database: entities, DAOs, repository
  tracking/   TrackingEngine (mode controller), foreground service, sensors, boot receiver
  ui/         Compose screens, 8 themes, shared components
app/src/test/ unit tests (core/) and Robolectric integration tests (integration/)
docs/         architecture, testing plan, signing, release notes
```
See **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)** for how it all works and **[docs/TESTING.md](docs/TESTING.md)**
for the on-phone acceptance test and battery test plan.

## Privacy
No accounts, no network access, no analytics. The app does not even request the INTERNET permission.
Data is excluded from cloud backup.
