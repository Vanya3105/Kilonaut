# Kilonaut (formerly GymTracker) — notes for Claude

The app is called **Kilonaut** (renamed from GymTracker on 2026-11-07). Use Kilonaut in anything people see. Internal names keep `gymtracker` on purpose and must not be renamed: the `applicationId` `com.vanyaevtushenko.gymtracker` (a new one would be a different app: data and Google sign-in lost), the Kotlin package, `gymtracker.db`, prefs names, the watch link paths (`/gymtracker/...`, `gymtracker://open`), theme names. Backups are written as `kilonaut-backup-*`; `gymtracker-backup-*` files are still recognised (`AutoBackup.OLD_FILE_PREFIXES`).

**At the start of every session, read `ROADMAP.md` and continue from its "Next up" section.**
When the user says "continue", that is what they mean.

## Versions
Until the first public release (1.0.0, on Google Play) every release is a beta: `versionName = "1.0.0-beta.N"` in `app/` and `wear/`, published on GitHub as a pre-release. `versionCode` only ever goes up. Releases before this (2.x, 3.x) count as older than any 1.x (`AppUpdate.isNewer`). Add the release's highlights to `WhatsNew.notesFor` under its version name.

## Workflow
- One branch per roadmap phase (`phase-N-name`); never commit to `master` directly.
- After each step: build + tests must pass, then commit and update `ROADMAP.md` (tick boxes, rewrite "Next up") in the same commit.
- Don't merge a phase into `master` until the user has tested it on a phone.
- Leave unrelated `.idea/` changes out of commits.

## Working notes (read after a crash or on a new PC)
- The real working copy is `C:\Users\evtus\AndroidStudioProjects\GymTrackerApp`. An old clone at `...\GymTracker` is stale: don't work there.
- Start of a session: `git fetch`, full `git status` (don't truncate it) and `git stash list`. An interrupted session can leave uncommitted work; ask before discarding anything. The stash "Crashed session: Phase 5 plate calculator" is superseded and kept only for reference.
- Build fails right after a crash with "invalid stream header" or "Configuration cache state could not be cached": a Gradle cache was corrupted. Delete `app/build/intermediates/incremental/*-mergeJavaRes`, `app/build/intermediates/merged_java_res` and `.gradle/configuration-cache`, then rebuild. Source files are not involved.
- The owner's phone (Galaxy S26 Ultra, `SM-S948B`) is often plugged in: always pass `adb -s emulator-5554`. In Git Bash set `MSYS_NO_PATHCONV=1`, or paths like `/sdcard/...` get rewritten.
- Health Connect's own screens block screenshots; read them with `uiautomator dump`.
- Owner's devices: Galaxy S26 Ultra + Galaxy Watch (Wear OS). Heart rate reaches the app through Samsung Health → Health Connect.
- The owner tests each step on the phone and then asks to commit and push: push the phase branch after each tested step.
- Release signing: the upload key is `C:\Users\evtus\keys\gymtracker-upload.jks`; `keystore.properties` (git-ignored, copy next to the key) points to it. Never commit either, never print the passwords.

## Commands (Git Bash, from repo root)
- Unit tests: `./gradlew testDebugUnitTest`
- Debug APK: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
- Release APK: `./gradlew assembleRelease` (signed with `keystore.properties` if present, else the debug key)

## Code map
- Kotlin package `com.example.gymtracker` (the `applicationId` is `com.vanyaevtushenko.gymtracker`).
- `Storage.kt`: `LocalStorage` (the only storage API; in-memory reads, ordered writes on one storage thread), `StoreCodec` (JSON/backup format).
- `Database.kt`: Room schema (`GymDatabase`, `GymDao`) and `RoomStore` (model <-> Room). Schemas are exported to `app/schemas/` (committed). Any schema change: bump the version, add a `Migration`, and extend `DatabaseMigrationTest`.
- `Engine.kt`: records/1RM, suggestions, rest alarms, notifications, dates.
- `Models.kt`: data classes. `ExerciseDatabase.kt`: built-in exercise library.
- Screens: `HomeScreen`, `ActiveWorkoutScreen`, `HistoryScreen`, `ProgressScreen`, `SettingsScreen`, `OnboardingScreen`.
- Design system: `Theme.kt` (`P` palette, `AppType`; Inter from `res/font/inter.ttf`, `InterDisplay` for headings), `Components.kt`, `Celebration.kt` (confetti, count-up tiles). Full-screen pages over the tabs need `StatusBarScrim()` on top.
- Widgets + lock screen + share image: `WidgetsAndStoryShare.kt`, `WidgetConfigActivity.kt`.
- Health Connect: `HealthSync.kt` (writes are called from `LocalStorage`), `HealthPrivacyActivity.kt`.
- Watch: `WatchSync.kt` (phone side: publishes `watchStateOf`, `WatchListenerService` applies button presses); `:wear` module (the Wear OS app); `:watchlink` module (the protocol both share). Build the watch app with `./gradlew :wear:assembleDebug`. `./gradlew :wear:testDebugUnitTest` renders every watch screen to `wear/build/screenshots/*.png` (no emulator needed).

## Conventions
- Compose + Material 3, custom design system; no DI framework, no ViewModels so far.
- Weights stored in kg; never hardcode "kg" in UI text. Use `formatWeightKg`, `formatSet`, `formatVolume`, `stepWeight`, `scaleWeight` and `WeightStepper` from `Units.kt`/`Components.kt`. Old store files and backups must keep loading (see `StoreCodecTest`, `StorageMigrationTest`).
- Storage tests run on Robolectric (`RoomStoreTest`, `StorageMigrationTest`). On-device checks: use the emulator (`emulator-5554`), never the user's phone.
- Comments are sparse and explain *why*.
