# Security

Detach runs as an Accessibility service, a powerful permission. This document explains how it's kept narrow, what was audited, and what's still out of scope.

## Threat model

| We protect against | How |
|---|---|
| Detach leaking data | No network permission, analytics, content persistence or external-storage writes; the content-bearing inspector exists only in debug builds and requires `DUMP` |
| Another app abusing Detach (making it draw overlays, tap, read screens, or pause) | Only the launcher activity is exported; its diagnostics action reveals local health counters but cannot authorize Resume. No external extras or deep links control protection. The release build has no inspector receiver. The pause receiver, unlock and pause activities are not exported; reminder/notification/tile PendingIntents are immutable with explicit targets. The Quick Settings tile service is exported only behind `BIND_QUICK_SETTINGS_TILE` (System UI) and can only open the authenticated pause confirmation; its "Pause and open" starts only a selected app's plain launcher intent, after the confirmed pause. No `upi://` or other intent handler exists. Accessibility events from a selected app (which any app can send, also from the background) pause only while Android reports no active window; while another app is in front they only trigger package-only re-checks of the active window, so a selected app in the background cannot switch protection off or undo an explicit Resume. The remaining gap is that brief no-active-window moment |
| Detach acting on the wrong app (e.g. a UPI app) | Separate event-routing and content-read allowlists; package metadata checked before content reads, foreground rechecked before actions and overlay updates, foreign/password subtrees skipped, covers clipped to app and safe screen bounds. A 120 ms metadata-only watchdog runs only while an overlay is visible; Android scheduling can add latency |
| Hostile content inside Instagram/YouTube (crafted usernames, captions, links) | Screen text is only compared against fixed strings; no parsing, no code paths driven by it; worst case is Detach blocking *less*; actions are limited to Back / Instagram's Messages tab / YouTube's Home tab |
| Someone with the phone switching Detach off on impulse | Optional salted-PBKDF2 password on switches and protected system pages, with biometric confirmation. Manual payment pause uses confirmation, but selected-app auto-pause intentionally bypasses it. Nothing is protected while paused; this is not tamper-proof enforcement |

**Out of scope:** someone with a computer and USB debugging (`adb` can disable or uninstall any app), safe mode, factory reset, root. Also out of scope: a malicious Instagram/YouTube app, since Detach trusts the package name Android reports.

## Current Payment / Privacy Behavior (0.5.0)

The historical audit below describes v0.1.0, not certification of the current app. The current changes receive implementation review, a separate full-app source audit and parent integration review. These are not independent security certification or physical-device banking certification; see [current audit scope](AUDIT-0.5.0.md).

- **Payment-app compatibility.** A sideloaded accessibility service with `isAccessibilityTool="false"` cannot be hidden from Google Pay's or other apps' accessibility checks, and Detach will not claim to be an accessibility tool. Detach therefore aims to be off before a payment app starts: users can select the apps they pay from, or pause first via the authenticated tile/notification/app control. For opening a UPI app directly, "Pause and open" waits until Android no longer lists Detach before launching it. A selected app's event pauses only while no active window is reported; otherwise the selected app's own window must take focus (re-checked for about a second), which is late for apps that check at launch. Pausing commits the durable marker before `disableSelf()` and defers alarm/notification work until after it. A `upi://pay` relay was rejected (explicit-package launches bypass it, it would handle UPI URIs and amounts, adds an exported handler, and makes Detach the visible caller). Other apps' accessibility services are only listed, never changed.
- **The pause is a deliberate gap.** Selected-app launches (UPI, bank or checkout apps the owner selected, detected from the active window, or from an event while no active window is reported) automatically disable accessibility and remove overlays before content inspection. This bypasses password/anti-distraction enforcement: Instagram/YouTube, Settings changes and uninstalling are unrestricted until protection resumes. Manual pause uses fingerprint/password where available; without either, the Pause button confirms it. Do not treat this as parental control or tamper-proof banking detection.
- **Automatic timer:** 1–60 minutes, default 5; the optional secure-settings grant is required. A same-boot, clock-bounded pause capability permits one automatic attempt. Without that grant, the timer only reminds. Timer-only mode cannot know whether banking has finished. Reboot/update invalidate automatic authority; app/notification opens and legacy resume broadcasts cannot create or renew it. Android may delay delivery. A due timer does not resume while the screen is off or locked (30-second retries, bounded to 20 minutes before the automatic authority ends). If a resume reconnects while a selected app is the active window, the service pauses again immediately (plus package-only re-checks for about a second after binding); there is no post-resume exemption. This cannot loop tightly because each reconnect needs fresh timer, social-observation or explicit-Resume authority, none of which a selected app's foreground provides.
- **Explicit Resume** needs no password because it restores protection. It writes a single-use authorization before attempting secure enable, including when the user must finish in Android Accessibility settings. Authorization expires after three minutes, is bounded by wall and monotonic clocks, and is bound to the boot count. Pending-pause reconnects must consume it before initializing overlays/workers. Boot/update broadcasts and a new pause revoke it. This closes the normal process-death gap between recording a pause and disabling the service; durable storage failure or a compromised OS remains outside the guarantee.
- **`WRITE_SECURE_SETTINGS` is powerful.** It allows changing any secure system setting, and Android only grants it over adb. Detach uses it in one function (`Pause.enableService`) to append its own component to `enabled_accessibility_services` and set `accessibility_enabled` to 1; other entries are written back unchanged. It never enables anything else. If you'd rather not grant it, pausing still works and you turn Detach back on by hand.
- **Optional package-only observer:** off by default, requiring explicit consent, Usage Access and the secure-settings grant. A private non-sticky `specialUse` foreground service checks recent package events about every two seconds only while paused and unlocked. It uses one worker, no wake lock, no content reads, no persistent usage history and no network; it stops on resume, opt-out, revoked access or bounded authority expiry. Android can deny its start. A selected app observed in front defers the timer until Android reports it left (at most ten minutes without a new event, and only while observation is continuous); the app that started the pause gets that hold only if the pause came from its active window, otherwise a 30-second hint. After an unlock the timer waits one poll for a returning payment app. Then unknown-foreground timer semantics apply. Full details: [automatic payment resume](docs/automatic-payment-resume.md). Unknown banks, embedded payments and multi-window ambiguity remain limitations.
- **Breather:** fresh biometric/password confirmation unblocks all Instagram/YouTube restrictions for 1–60 minutes. No permanent setting or Settings protection is changed. Single-use confirmation is invalidated when its host stops. Sessions validate process/boot identity and wall/elapsed clocks; expiry, early end, storage error and restart fail closed.
- **Relock boundary:** protected chooser/consent dialogs close on host stop; stale Save/Enable callbacks and late authentication results cannot reuse the prior settings visit.
- **Fingerprint:** Android's `BiometricPrompt`, accepting any biometric Android rates Class 2 ("weak") or better, without device-credential fallback. No keystore key is bound to it: like the password check, it's a speed bump, not cryptographic protection. With no password set, a fingerprint alone replaces the 30-second wait, so anyone whose fingerprint is enrolled on the phone can get past the panel. A fingerprint lockout or timeout does not fall back to the 30-second wait: with no password set, the only answer is to try again later.
- **Changing or removing the password** still needs the typed password.
- **Battery:** accessibility evaluations run while protection is enabled; a package-only watchdog exists only while an overlay is visible. Pauses stop the protection worker and overlays. Timer-only mode has no app monitor; optional monitoring is bounded, unlocked-only and wake-lock-free. Battery/RAM/detection latency are not benchmarked for this release.

## Historical Audit (v0.1.0)

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
# permissions: POST_NOTIFICATIONS, USE_BIOMETRIC, RECEIVE_BOOT_COMPLETED, WRITE_SECURE_SETTINGS only
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
