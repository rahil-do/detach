# Security

Detach runs as an Accessibility service, a powerful permission. This document explains how it's kept narrow, what was audited, and what's still out of scope.

## Threat model

| We protect against | How |
|---|---|
| Detach leaking data | No `INTERNET` permission; no files written outside private storage; no logs except the adb-only inspector |
| Another app abusing Detach (making it draw overlays, tap, read screens, or pause) | No exported components except the launcher activity; no intents, extras or deep links accepted; the inspector receiver requires `android.permission.DUMP` (adb/system only); the pause receiver and `PauseActivity` are not exported and are only reached by Android itself (boot, app update) and by Detach's own alarm and notifications, through immutable PendingIntents with explicit targets |
| Detach acting on the wrong app (e.g. a UPI app) | `packageNames` filter; package check before every read and **right before** every action; covers clipped to the target app's window; covers removed within ~250 ms of the app leaving the foreground |
| Hostile content inside Instagram/YouTube (crafted usernames, captions, links) | Screen text is only compared against fixed strings; no parsing, no code paths driven by it; worst case is Detach blocking *less*; actions are limited to Back / Instagram's Messages tab / YouTube's Home tab |
| Someone with the phone switching Detach off on impulse | Optional password (salted PBKDF2) on all switches; "Detach is protected" panel on Detach's own system pages; fingerprint accepted as an alternative (Android's prompt, never the phone's PIN/pattern); pausing needs the same confirmation |

**Out of scope:** someone with a computer and USB debugging (`adb` can disable or uninstall any app), safe mode, factory reset, root. Also out of scope: a malicious Instagram/YouTube app, since Detach trusts the package name Android reports.

## Notes on 0.2.0 (pause for payments, fingerprint)

These features were added after the audit below and have **not** been independently reviewed.

- **The pause is a deliberate gap.** For the length of the pause (1 to 60 minutes, default 5) the Accessibility service is off: nothing is blocked and the "Detach is protected" panel can't appear, so Detach can be disabled or uninstalled in that window. Pauses can be repeated. Starting one needs fingerprint or password, **unless the phone has neither**, in which case it needs nothing (and skips the 30-second wait the panel would ask for). Set a password if that matters to you.
- **Ending a pause needs no confirmation**, since it only turns protection back on. The worst another party could do by triggering it is end a pause early, and they can't: the receiver isn't exported.
- **`WRITE_SECURE_SETTINGS` is powerful.** It allows changing any secure system setting, and Android only grants it over adb. Detach uses it in one function (`Pause.enableService`) to append its own component to `enabled_accessibility_services` and set `accessibility_enabled` to 1; other entries are written back unchanged. It never enables anything else. If you'd rather not grant it, pausing still works and you turn Detach back on by hand.
- **Usage access is sensitive.** It reveals which apps are opened and when. It is optional, only the owner can grant it, and Detach queries it only inside `PauseWatchService`, which runs only during a pause, is not exported, and keeps nothing.
- **Resume paths:** an alarm (exact only where Android allows it without an extra permission), an in-process timer, a boot / app-updated receiver, and a check when the app is opened. All go through one idempotent function. If none can run (e.g. Detach was force-stopped during a pause), Detach stays off until the app is opened.
- **Fingerprint:** Android's `BiometricPrompt`, accepting any biometric Android rates Class 2 ("weak") or better, without device-credential fallback. No keystore key is bound to it: like the password check, it's a speed bump, not cryptographic protection. With no password set, a fingerprint alone replaces the 30-second wait, so anyone whose fingerprint is enrolled on the phone can get past the panel. A fingerprint lockout or timeout does not fall back to the 30-second wait: with no password set, the only answer is to try again later.
- **Changing or removing the password** still needs the typed password.
- **Battery:** nothing runs outside a pause. During a pause a small foreground service checks once a second whether the pause is over and, with Usage access, whether Instagram or YouTube was opened; it holds no wakelock and stops with the pause.

## Audit (v0.1.0)

An independent code review on 2026-11-10 covered four areas. No critical findings.

### 1. Battery and CPU
| Finding | Severity | Status |
|---|---|---|
| Re-checks the screen up to ~14×/s while videos play under a cover | Medium | **Fixed**: content changes are throttled to 160 ms (~6×/s), scrolls to 80 ms, new screens immediately; `notificationTimeout` raised to 120 ms |
| Last Instagram screen snapshot kept in memory after leaving Instagram | Low | **Fixed**: dropped when another app comes to the front |
| Password hashing (PBKDF2) on the UI thread | Low | **Fixed**: runs on a background thread |

Checked fine: no wakelocks, no alarms or jobs; the watchdog runs only while a cover/panel is visible; zero work while Instagram/YouTube/Settings aren't in front. Measured: 2–11 ms per check on a mid-range phone.

### 2. Hijacking and data exfiltration
| Finding | Severity | Status |
|---|---|---|
| Covers sized to the full screen, could overlap another app in split-screen | Medium | **Fixed**: clipped to the target app's window; the protection panel matches the Settings window |
| Brief window (≤ ~0.5 s) where a cover could sit over the next app; "instant" cover drawn before checking the foreground | Low | **Fixed**: foreground checked first; watchdog every 250 ms |
| Back/tap could land in an app opened a few ms earlier | Low | **Fixed**: foreground re-checked immediately before every action |
| Debug build is debuggable (adb `run-as` can reset the password) | Low | **By design for development**: share only release builds |
| Protection matches pages showing the app label "Detach" | Info | Accepted (another app named "Detach" would also be protected) |

Checked fine: no `INTERNET`; `allowBackup=false`; `UnlockActivity` and the service aren't exported; `MainActivity` reads no input; the inspector receiver requires `DUMP`; the `instagram://direct-inbox` intent is pinned to Instagram's package; no PendingIntents, providers or file writes; overlays are created only by the service's own decisions.

### 3. Remote code execution
**None found.** No reflection, dynamic code loading, WebView/JavaScript, deserialization, native code or URL/file parsing. The one regex has no backtracking risk. Crafted on-screen text can at most:
- make Detach think it's in a chat or on your own profile, so it blocks **less**;
- trigger a harmless Instagram tab switch.

| Finding | Severity | Status |
|---|---|---|
| A YouTube element labelled "Home" outside the tab bar could be tapped | Low | **Fixed**: only taps inside YouTube's real tab bar; otherwise uses Back |

### 4. Shareability
| Finding | Severity | Status |
|---|---|---|
| No release signing / only debug APK | High | **Fixed**: release keystore, minified, non-debuggable release build |
| Settings protection and YouTube tab matching English-only | Medium | **Partly fixed**: layout-based fallback for the protection; YouTube player detection is ID-based (any language); the Shorts-tab shortcut is still English |
| Uninstall prompt only covered on AOSP/Google | Medium-low | **Fixed**: added Samsung and Xiaomi installers |
| "Allow restricted settings" step in the wrong order | Low | **Fixed** |
| Notification permission only offered during setup | Low | **Fixed**: offered whenever missing |
| Settings shortcuts could crash on unusual phones | Low | **Fixed**: guarded |
| Unlock window may not open if a phone blocks background starts | Low | **Fixed**: falls back to a message |

## Verifying a release yourself

```sh
# permissions: POST_NOTIFICATIONS, USE_BIOMETRIC, RECEIVE_BOOT_COMPLETED, WRITE_SECURE_SETTINGS, PACKAGE_USAGE_STATS, FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE. Never INTERNET
aapt2 dump permissions Detach-<version>.apk
# not debuggable: should print nothing
aapt2 dump badging Detach-<version>.apk | grep debuggable
# signer: compare with the fingerprint published with the release
apksigner verify --print-certs Detach-<version>.apk
```

Release signing certificate (SHA-256):
```
dd6a82f45e22d93904bfff00bd4ff94a0d1ae4d8862bff9304b7e7a461495889
```
The certificate was created before the rename, so its name still reads `CN=Unreel`; the fingerprint is what matters.

If a file claiming to be Detach has a different fingerprint, it wasn't built by this project. Don't install it.

## Reporting

Found a vulnerability, or Instagram/YouTube changed and Detach stopped working? Open an issue on the project's GitHub page. For security issues, please describe the problem without posting a working exploit publicly, and the maintainer will follow up.


### Review of 0.2.0 (2026-11-10)

A second independent read of the pause and fingerprint code found no critical or high issues. The medium and low findings (watcher windows that could miss an app launch, late pause end without Usage access, two fail-open paths in the fingerprint flow, an unconfirmed pause with no password or fingerprint, a silent "still off" notice) are fixed in 0.2.1. Accepted and not fixed: moving the clock back during a pause can lengthen it by up to an hour; a force-stop during a pause leaves Detach off until the app is opened.