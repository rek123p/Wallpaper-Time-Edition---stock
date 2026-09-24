# pre-built

This is the complete `app/` module - the whole Android Studio module, not
just a handful of files - ready to drop into a project shell and build.

## 1. Create the project shell

Android Studio -> New Project -> **No Activity**

- Language: Kotlin
- Package name: `com.<user>.<app_name>`
- Minimum SDK: API 24 (Android 7.0)
- Build configuration language: Kotlin DSL (`build.gradle.kts`)

This gives you the surrounding project (root `build.gradle.kts`,
`settings.gradle.kts`, `gradle/libs.versions.toml`, the Gradle wrapper) that
this repo doesn't track, since it's the same boilerplate Android Studio
generates for any new project.

## 2. Swap in this module

Delete the `app/` folder Android Studio just generated for you, and put
this repo's `pre-built/app/` in its place (same location, same name: `app/`
directly under the project root).

In the freshly generated `app/build.gradle.kts` (the one you're replacing),
check `compileSdk` / `targetSdk` were **37** - if this module's own
`build.gradle.kts` already sets them, you don't need to do anything; only
worth a glance if the build complains about SDK versions after the swap.

## 3. Sync and build

Let Gradle sync. Then either **Build > Build Bundle(s) / APK(s) > Build
APK(s)** in Android Studio, or from a terminal in the project root:

```
./gradlew assembleDebug
```

The output APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

## 4. Test

Install on a device or emulator, open the app once (either sets the
wallpaper directly via the **Set wallpaper** button, or falls back to the
system's live wallpaper chooser), and confirm from there:

- the clock bounces and the date/weekday line stays fixed at the bottom
- Settings (language / time format) apply live, without resetting the
  wallpaper
- the 4 corner signatures reveal correctly after the clock passes over and
  leaves each corner
