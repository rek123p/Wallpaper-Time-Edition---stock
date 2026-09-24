# Wallpaper: Time edition

Android live wallpaper: a bouncing (DVD-logo style) digital clock, with the
date and weekday shown on a fixed line near the bottom. Written natively in
Kotlin (Canvas 2D), package `com.rek123p.walltimeedition`.

## Features

- Bouncing `HH:MM` clock (DVD-style), fixed width so the bounce always has
  real room to move regardless of screen size.
- Date + weekday shown on a separate, fixed line near the bottom - the clock
  is free to fly over/under it, it's drawn on top.
- Settings screen (tap the app icon, or the gear on the system's wallpaper
  picker): language (System default / English / Polski) and time format
  (24h / 12h), applied live to the running wallpaper.
- 4 hidden corner signatures, revealed for a few seconds whenever the clock
  passes over that corner and leaves again.
- Adaptive launcher icon (API 26+) with legacy PNG fallbacks for older
  devices (minSdk 24).

## Repo structure

- **`pre-built/`** - the full custom source (Kotlin + resources) plus build
  instructions, meant to be dropped into a fresh Android Studio project and
  built yourself.
- **`after-built/`** - install instructions for the compiled `.apk`, for
  installing directly on a device without opening Android Studio. The APK
  itself is added here after building from `pre-built/` (see its README).

## Requirements

- minSdk 24 (Android 7.0) / compileSdk & targetSdk 37
- Android Studio (recent) with a Kotlin-capable Gradle setup

## License

PolyForm Noncommercial 1.0.0 - free to use, modify, and redistribute for any noncommercial purpose. Commercial use (including selling it, or a modified version of it) is not permitted.
