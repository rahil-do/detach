# Privacy

**Short version:** Detach can't use the internet, only looks at Instagram, YouTube and its own Settings pages, and stores almost nothing.

## What Detach can access

Detach uses Android's **Accessibility** API, the same one screen readers use. Android lets an Accessibility service read what's on screen and perform taps. Detach narrows that down as far as Android allows:

| Limit | Where | Effect |
|---|---|---|
| `packageNames` | `app/src/main/res/xml/accessibility_config.xml` | Android only sends Detach events from **Instagram**, **YouTube**, the **Settings** app, supported **uninstall prompts** (Google, AOSP, Samsung, Xiaomi), and Android **System UI**. System UI events are used only to hide an overlay while notification shade / Quick Settings is visible; Detach does not inspect or store System UI content. |
| Package check before every read | `DetachService.evaluate()`, `Inspector.dump()` | Before reading a screen, Detach checks which app is in front. If it isn't one of the above, Detach reads **only that app's package name** (e.g. `com.phonepe.app`) to know it should step aside, and reads nothing else from it. |
| No `flagRetrieveInteractiveWindows` | `accessibility_config.xml` | Detach can't list or inspect other apps' windows. |
| Covers clipped to the app's window | `DetachService.evaluate()` | A cover can never reach outside Instagram's own window, e.g. over another app in split-screen. |

### Why the Settings app?
Only to show the **"Detach is protected"** panel when one of *Detach's own* pages opens there (its Accessibility page, App info, uninstall prompt). On every other Settings page Detach does nothing.

## What Detach does with what it sees

On Instagram/YouTube/Settings screens it looks at element IDs, labels and positions to decide one of these:
- draw an opaque cover over part of the screen,
- press **Back**,
- tap Instagram's **Messages** tab, or YouTube's **Home** tab,
- show the "protected" panel.

That's everything it can do. It doesn't type, scroll, like, follow, send or read your messages.

## What Detach stores

All of this is in the app's private storage on the phone (`/data/data/io.github.detach/shared_prefs/`):

| File | Contents |
|---|---|
| `settings.xml` | The on/off switches |
| `lock.xml` | A random salt and a **PBKDF2-HMAC-SHA256** hash of your password (120,000 iterations). Never the password itself. |
| `pause.xml` | While a "pause for payments" is running: the time it ends. Otherwise empty. |
| `health.xml` | Timestamps of when each part of Instagram/YouTube was last recognised, daily counters, and the last action (e.g. "Instagram Reels tab (15:00:38)") |

Detach stores **no** screen content, messages, usernames, history or screenshots. Android cloud backup is disabled (`allowBackup="false"`).

## What Detach sends

**Nothing.** The app doesn't request `android.permission.INTERNET`, so Android blocks every network connection it might try to make. You can check this yourself:

```sh
aapt2 dump permissions Detach-<version>.apk
# package: io.github.detach
# uses-permission: name='android.permission.POST_NOTIFICATIONS'
# uses-permission: name='android.permission.USE_BIOMETRIC'
# uses-permission: name='android.permission.RECEIVE_BOOT_COMPLETED'
# uses-permission: name='android.permission.WRITE_SECURE_SETTINGS'
# uses-permission: name='android.permission.PACKAGE_USAGE_STATS'
# uses-permission: name='android.permission.FOREGROUND_SERVICE'
# uses-permission: name='android.permission.FOREGROUND_SERVICE_SPECIAL_USE'
```

## Permissions

| Permission | Used for |
|---|---|
| `POST_NOTIFICATIONS` | Local notifications only: "Detach can't recognise Instagram/YouTube", and the silent "Detach is on / paused until…" status notification. |
| `USE_BIOMETRIC` | Showing Android's own fingerprint prompt as an alternative to the Detach password. Detach only learns "confirmed" or "not confirmed". It never sees or stores fingerprint data, and uses no keys or crypto tied to it. |
| `RECEIVE_BOOT_COMPLETED` | If the phone restarts during a "pause for payments", Detach is woken once after boot to end that pause. It does nothing else at boot, and nothing at all if no pause was running. |
| `WRITE_SECURE_SETTINGS` | **Not granted by installing.** Only you can grant it, from a computer ([INSTALL.md](INSTALL.md#optional-let-detach-turn-itself-back-on-after-a-pause)). Used for one thing: after a pause, Detach adds **its own entry** back to Android's list of enabled Accessibility services (`enabled_accessibility_services`, and the `accessibility_enabled` flag that goes with it). Other apps' entries in that list are kept exactly as they were. Detach reads and writes no other system setting. |
| `PACKAGE_USAGE_STATS` (Usage access) | **Not granted by installing.** Only you can grant it (Settings → Special app access → Usage access, or `adb shell appops set io.github.detach GET_USAGE_STATS allow`). It lets an app see which apps were opened and when, never their content. Detach reads it only during a pause, once a second, to check whether Instagram or YouTube came to the front, and stores nothing from it. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | The small watcher that does that check. It runs only during a pause (1 to 60 minutes, as set) and shows the "paused until…" notification. |

The Accessibility service itself is not a permission Detach holds: you switch it on in Settings, and its limits are described above.

## Developer inspector

For updating the detection rules, Detach can print the current Instagram/YouTube/Settings screen layout (with text cut to 40 characters) to the phone's **local** system log. Only a computer connected over USB with debugging enabled can trigger this, because the trigger requires `android.permission.DUMP`, which normal apps can't hold. It's off unless triggered and never writes files.

## Third parties

None. Detach has no analytics, ads, crash reporting or third-party libraries.
