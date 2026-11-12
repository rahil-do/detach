# Privacy

**Short version:** Detach has no network permission or remote endpoints. Screen-content detection is limited to Instagram, YouTube and Settings/uninstall protection. Payment auto-pause uses package names only and stops Accessibility. Optional, separately consented Usage Access monitoring checks recent app-package events during pauses, never banking-screen content; timer-only mode has no app monitor.

## What Detach can access

Detach uses Android's **Accessibility** API, the same one screen readers use. Android lets an Accessibility service read what's on screen and perform taps. Detach narrows that down as far as Android allows:

| Limit | Where | Effect |
|---|---|---|
| Event package filter | `accessibility_config.xml`, `Settings.eventPackages()` | Events are limited to the social apps, Settings/uninstall packages, System UI, and selected payment packages. Changing the payment selection updates the filter. No wildcard or unrestricted event subscription is used. |
| Separate content allowlist | `Targets.READABLE`, `DetachService.evaluate()`, `Inspector` | Payment/System UI events are routed using package metadata before accessing their source or screen contents. Receiving an event never grants a package membership in the content allowlist. |
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

It does not type, scroll, like, follow or send messages. Accessibility labels can include message previews or other social-app text while a supported screen is inspected; that transient text is used for local rules, not stored or uploaded. Password subtrees and foreign-package children are excluded.

## What Detach stores

All of this is in the app's private storage on the phone (`/data/data/io.github.detach/shared_prefs/`):

| File | Contents |
|---|---|
| `settings.xml` | On/off switches, pause duration, default-off monitor preference and selected payment packages. The chooser loads app labels/icons locally and shows no package IDs; it does not upload an installed-app list. |
| `lock.xml` | A random salt and a **PBKDF2-HMAC-SHA256** hash of your password (120,000 iterations). Never the password itself. |
| `pause.xml` | Pause timestamps, a same-boot clock-bounded automatic capability, one-use reconnect authorization and brief original-bank suppression metadata. These are lifecycle metadata, not usage history or screen content. |
| `breather.xml` | Temporarily allowed feature names, duration and process/boot/wall/elapsed identity. Invalid state, expiry, early end or process restart restores the usual restrictions. |
| `health.xml` | Timestamps of when each part of Instagram/YouTube was last recognised, daily counters, and the last action (e.g. "Instagram Reels tab (15:00:38)") |

Detach stores **no** screen content, messages, usernames, history or screenshots. Android cloud backup is disabled (`allowBackup="false"`, `fullBackupContent="false"`), and explicit extraction rules exclude every storage domain from cloud backup and Android device-to-device transfer.

## What Detach sends

**Nothing.** The app doesn't request `android.permission.INTERNET`, so Android blocks every network connection it might try to make. You can check this yourself:

```sh
aapt2 dump permissions Detach-<version>.apk
# package: io.github.detach
# uses-permission: name='android.permission.POST_NOTIFICATIONS'
# uses-permission: name='android.permission.USE_BIOMETRIC'
# uses-permission: name='android.permission.RECEIVE_BOOT_COMPLETED'
# uses-permission: name='android.permission.WRITE_SECURE_SETTINGS'
```

## Permissions

| Permission | Used for |
|---|---|
| `POST_NOTIFICATIONS` | Local notifications only: "Detach can't recognise Instagram/YouTube", and the silent "Detach is on / paused until…" status notification. |
| `USE_BIOMETRIC` | Showing Android's own fingerprint prompt as an alternative to the Detach password. Detach only learns "confirmed" or "not confirmed". It never sees or stores fingerprint data, and uses no keys or crypto tied to it. |
| `RECEIVE_BOOT_COMPLETED` | Restores the reminder after reboot if a pause is pending; never automatically restarts the accessibility service. |
| `WRITE_SECURE_SETTINGS` | **Optional USB grant, never granted by installing.** A Resume tap or a valid automatic timer/social observation can request adding only Detach's own Accessibility entry and enabling the associated flag. Existing services are preserved. Boot/update and app/notification taps cannot create automatic authority. |
| `PACKAGE_USAGE_STATS` | **Optional Usage Access plus explicit in-app opt-in.** While paused and unlocked, query a narrow recent event window for foreground package changes; no content is read and no usage history is persisted. An old OS grant alone does not enable the feature. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | Keep the optional package monitor visible as a private local notification only during a bounded payment pause. Timer-only mode starts no foreground service. |

No broad package-query or network permission is requested. Pauses use one explicit immutable alarm PendingIntent. The receiver is not exported, rejects legacy resume actions, and old tokens are revoked. The opt-in monitor uses a bounded recent event window, one worker and no wake lock, stops polling while off/locked and stops entirely after resume/opt-out/revocation/authority expiry. Android can retain a cached process; a zero-RAM or zero-battery claim is not made. See [exact monitor policy](automatic-payment-resume.md).

Automatic pausing intentionally bypasses password/anti-distraction enforcement when a selected payment app opens. Nothing is blocked or protected during that pause, including Instagram/YouTube and disabling/uninstalling Detach. This is a payment-safety tradeoff, not tamper-proof banking detection. Timer-only mode can resume while a bank is still open; optional package evidence is best-effort, not universal banking detection.

An explicit Resume records a durable, single-use authorization valid for three minutes, including when Android Settings must complete the enable step. Its age is bounded by monotonic elapsed time and bound to Android's boot count, not just the adjustable wall clock; boot/update broadcasts revoke it. A pending-pause service reconnect must consume that authorization before initializing; missing, expired, future-dated or already-consumed authorization is rejected. This prevents a reconnect from silently clearing a pause after process death between recording it and disabling the service. A new pause revokes the authorization, and a completed resume clears it with both pause timestamps. The boot count is system lifecycle metadata, not app-usage or payment-screen data.

The Accessibility service itself is not a permission Detach holds: you switch it on in Settings, and its limits are described above.

## Developer inspector

In debug builds only, the developer inspector can print social-app detection information to the local system log. Its gate rejects payment, System UI, Settings and unknown packages before traversal, validates package membership per node, and redacts password values. Its broadcast trigger requires `android.permission.DUMP`. Release builds do not register the inspector receiver or emit inspector output. Debug logs can still include non-password social-app labels; do not enable diagnostic tracing when handling private social content.

## Third parties

None. Detach has no analytics, ads, crash reporting or third-party libraries.
