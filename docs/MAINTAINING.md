# Maintaining Detach

## Setup

- **JDK 17** (e.g. `jdk17-openjdk`)
- **Android SDK** with `platforms;android-35` and `build-tools;35.0.0`. Android Studio works, or just the command-line tools:
  ```sh
  sdkmanager --sdk_root=$HOME/Android/Sdk "platforms;android-35" "build-tools;35.0.0"
  ```
- `local.properties` (git-ignored): `sdk.dir=/home/you/Android/Sdk`
- `adb` for testing on a phone

## Build

```sh
./gradlew assembleDebug     # app/build/outputs/apk/debug/app-debug.apk   (debuggable, for testing)
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk (minified, signed)
./gradlew lintDebug         # must report 0 errors
./gradlew testDebugUnitTest  # the test suite; needs no phone
```

Install and enable on a test phone:
```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
# enable without losing other accessibility services:
cur=$(adb shell settings get secure enabled_accessibility_services)
adb shell settings put secure enabled_accessibility_services "$cur:io.github.detach/io.github.detach.DetachService"
```
⚠️ Don't use `adb shell am force-stop io.github.detach`: Android then switches Detach's Accessibility off.

## Tests

`app/src/test/` holds unit tests that run on the computer with Robolectric (a test-only library; the app itself still has no third-party code). They build fake screens with the same ids, labels and positions the real apps report (`Screen.kt`) and check what the rules decide.

| File | Covers |
|---|---|
| `InstagramRulesTest` | Feed cover, fresh-start Home, Reels tab, reels from chats, search, profiles, hostile on-screen text |
| `YouTubeRulesTest` | Shorts tab and player, a fake "Home" element that must never be tapped |
| `GuardTest` | Which Settings and uninstall pages are protected, and which are not |
| `LockTest` | Password check, lockout after 5 wrong tries, nothing stored in plain text |
| `PauseTest` | Re-enabling keeps other accessibility services intact; forged or stray resume signals can't switch Detach on |
| `ManifestTest` | No network permission, only the launcher screen exported, backup off, the accessibility package list matches the code |

When a real-world bug is fixed, add a test that reproduces the screen that caused it (dump it with the inspector, see below). The tests check the rules, not the phone: overlays, fingerprint prompts and the pause itself still need the smoke test on a device.

## Signing

Release builds are signed with the key described in `keystore.properties` (git-ignored, never commit it):

```properties
storeFile=/home/you/Android/keys/detach-release.jks
storePassword=…
keyAlias=detach
keyPassword=…
```

Without that file, `assembleRelease` falls back to the debug key. **Don't share debug-signed builds.**

**Back up the `.jks` file and `keystore.properties` somewhere safe** (password manager, encrypted drive). Every future update must be signed with the same key. If the key is lost, everyone has to uninstall Detach (losing their settings and password) before installing a build signed with a new key.

To create a new key (only for a fork, or if the old one is gone):
```sh
keytool -genkeypair -keystore detach-release.jks -storetype PKCS12 -alias detach \
  -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Detach"
```
Then update the fingerprint in `SECURITY.md`.

## Releasing

1. Bump `versionCode` (+1) and `versionName` in `app/build.gradle.kts`.
2. Add an entry to `CHANGELOG.md`.
3. `./scripts/release.sh`. It builds, checks permissions/debuggable/signature, and writes `dist/Detach-<version>.apk` plus a `.sha256` file.
4. Smoke-test the release APK on a phone (checklist below).
5. Share `dist/Detach-<version>.apk`. On GitHub, attach it to a Release together with the SHA-256.

### Smoke-test checklist
- [ ] Setup card shows on a fresh install; **● Active** after enabling
- [ ] Instagram Home: stories visible, feed covered, "Back to stories" works
- [ ] Reels tab → DMs
- [ ] Reel from a DM plays; swiping to the next one goes back to the chat
- [ ] Search: accounts visible; Explore grid and results grid covered
- [ ] Another person's profile: header visible, grid covered; own profile not covered
- [ ] Account switcher, "+" create, settings work
- [ ] YouTube: Shorts tab → Home; a Short from Home closes; normal video plays
- [ ] Detach's Accessibility page, App info and uninstall prompt → "Detach is protected"; Go back works; password/30 s unlock works; Wi-Fi settings not covered
- [ ] Password: set, switch asks for it, Change asks for current, Remove asks, `*` masking
- [ ] Fresh start of Instagram (force-close it first): the feed is covered without switching tabs
- [ ] Pause: notification → fingerprint or password → "paused until…"; a UPI app opens; opening Instagram ends the pause within a couple of seconds; otherwise it ends on time; "Resume now" works
- [ ] Pause length: − and + change it, and ask for fingerprint or password when a password is set
- [ ] Fingerprint unlocks the switches and the "Detach is protected" screen; Change/Remove password still ask for the typed password
- [ ] Leave Instagram while the cover is up → cover gone (`adb shell dumpsys window windows | grep -c "Detach feed cover"` → 0)

## When Instagram or YouTube changes its layout

Symptoms: the feed shows again, Reels aren't redirected, Diagnostics lines stop updating, or you get the "can't recognise" notification.

1. Connect the phone with USB debugging and install a **debug** build.
2. Open the screen that broke, then dump what Detach sees:
   ```sh
   adb logcat -c
   adb shell am broadcast -a io.github.detach.DUMP -p io.github.detach
   adb logcat -d -s DetachInspect:I -v raw > dump.txt
   ```
   Each line is `Class #view_id [left,top,right,bottom] FLAGS t="text" d="description"`.
   Flags: `C` clickable, `S` selected, `R` scrollable, `K` checkable, `X` checked, `h` not visible.
3. For a live trace of events, decisions and actions:
   ```sh
   adb shell am broadcast -a io.github.detach.TRACE --ez on true -p io.github.detach
   adb logcat -s DetachInspect:I
   ```
   You'll see lines like `EVAL com.instagram.android nodes=44 3ms cover=Rect(…) action=null` and `ACTION Back: swiped past a shared reel`.
4. Update the IDs/labels in `InstagramRules.kt` / `YouTubeRules.kt` / `Guard.kt`. **Add** the new signal alongside the old one rather than replacing it, since different users run different app versions. The tables in [HOW-IT-WORKS.md](HOW-IT-WORKS.md) list every signal.
5. Rebuild, test, release.

**Privacy when debugging:** dumps and traces contain on-screen text from Instagram (usernames, DM previews), cut to 40 characters. They stay on your computer. Don't paste them into public issues without removing personal content.

## Adding another app (e.g. Facebook, Snapchat)

1. Add the package to `Targets.PACKAGES` **and** to `packageNames` in `res/xml/accessibility_config.xml`. They must match.
2. Write a `XxxRules` class returning `Decision`s, and route to it in `DetachService.evaluate()`.
3. Add a switch in `Settings.kt` and a card in `MainActivity.kt`.
4. Update `PRIVACY.md` (the list of apps Detach looks at).

## Project layout

```
app/src/main/
  AndroidManifest.xml            no INTERNET permission; service + activities
  java/io/github/detach/         all code (see HOW-IT-WORKS.md)
  res/xml/accessibility_config.xml   package filter, events, flags
  res/drawable/                  icons (vector)
docs/                            user + developer docs
scripts/install-usb.sh|.bat      install over USB (for friends)
scripts/release.sh               build + verify + copy to dist/
```
