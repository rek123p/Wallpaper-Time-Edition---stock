# after-built

The ready-to-install `.apk` (built from `pre-built/`, see its README), for
installing straight onto a device without opening Android Studio.

## Install

1. Transfer the `.apk` to your Android device (USB, cloud drive, whatever's
   easiest).
2. Tap it to install. Android will likely ask you to allow installs from
   whatever app you opened it with ("Install unknown apps") - this is
   expected for an app not published on the Play Store, not a sign
   something's wrong.
3. Open the app once, then either:
   - tap **Set wallpaper** to jump straight into the system's live wallpaper
     preview, or
   - go to your device's wallpaper picker yourself -> **Live wallpapers** ->
     **Wallpaper: Time edition**.
4. Settings (language, 24h/12h) are reachable from the app icon itself, or
   from the gear/settings icon in the system's wallpaper picker.

## Requirements

Android 7.0 (API 24) or newer.

## Note

This isn't distributed through Google Play, so Play Protect may warn about
an unrecognized/unknown developer during install - expected for a sideloaded
personal project, not a red flag.
