# Changelog

## 0.5.0 (unreleased)

- Improve system light/dark compatibility, including readable disabled controls, distinct switch tracks/thumbs, button press/focus feedback and native dialog themes.
- Replace package-ID payment selection with a searchable local app-name/logo picker; retain unavailable saved selections internally.
- Restore a bounded same-boot automatic payment-resume timer with the optional USB grant. Without that grant, resume still requires Android Settings.
- Add separately consented, default-off Usage Access detection for Instagram/YouTube during pauses: package-only checks about every two seconds while unlocked, no wake lock or stored history, and no monitor in timer-only mode.
- Add authenticated 1–60 minute breathers that unblock all Instagram/YouTube restrictions, chosen on a native number wheel with a start animation, without changing permanent settings or weakening Settings protection. Expiry, early end, process restart and invalid state restore the usual restrictions.
- Payment apps that check accessibility at launch (Google Pay naming "Detach"): pause **before** they start. Any app can now be selected for auto-pause, including the apps you pay from (investing, broker, shopping), so Detach is already off when they open a UPI app. New authenticated **Pause for payment** Quick Settings tile (System UI-only binding; opens the existing confirmation). For opening a UPI or bank app directly, the confirmation (tile or notification) offers **Pause and open <app>** for selected apps: after the confirmed pause it waits (up to two seconds) until `io.github.detach` is gone from `enabled_accessibility_services`, then starts the app's plain launcher intent, and never opens it while Detach is still listed. Opening a UPI app straight from the home screen remains a late, best-effort detection.
- Reactive fallback: an event from a selected app pauses at once only while Android reports no active window yet; whenever another app (including the home screen or Detach itself) is the active window, Detach only re-checks the active package for about a second, so a selected app in the background cannot switch protection off. One active-window read per event, and alarms/notifications are deferred until after `disableSelf()`.
- Fewer resumes over a payment app: removed the three-minute post-resume exemption for the original app and added package-only startup re-checks after reconnecting. A due timer no longer resumes while the screen is off or locked (it retries every 30 seconds and gives up waiting 20 minutes before its automatic authority ends). With app detection, a selected app seen in front (or whose own window started the pause) is held until it leaves, bounded to ten minutes; a pause started by an event alone keeps the old 30-second hint; and after an unlock a due timer waits one poll for the payment app to reappear. Timer-only mode still cannot see the front app, and the app now says so.
- Guidance: Payments section, paused notification and docs explain the recommended setup and that Google Pay may block until the phone restarts once (Detach stays paused through it; the paused status no longer promises an automatic resume after a restart). A brief warning names other enabled non-system accessibility apps (read-only; other services are never changed). Not done on purpose: `isAccessibilityTool=true` and a `upi://pay` relay.
- Keep pending-pause reconnects fail-closed, immutable private alarm targets, no runtime networking and no new runtime dependencies. Boot/update and app/notification opens cannot create automatic authority.
- Timer-only mode cannot know whether banking has finished; Android may delay alarms or monitoring. Selected-bank auto-pause remains an intentional password-bypass tradeoff, not tamper-proof protection.

## 0.4.0 (2026-11-13)

- Redesigned native screens with restrained light/dark styling, accessible controls, preserved disclosure/scroll state and reduced-motion-aware transitions.
- Faster opaque covers, in-place overlay updates, bounded loading-state retention and stale-action/foreground checks reduce content flashes without fading protected content into view.
- Fixed notification-shade recovery, independent shared-reel controls, Story/DM misclassification, split-screen geometry and false YouTube Shorts-player detection.
- Local, selected-package banking auto-pause disables protection completely. The duration is a reminder only: explicit Resume is required, with no background watcher or Usage access.
- A selected banking app intentionally bypasses password protection. While paused, nothing is blocked, including disabling/uninstalling Detach. Unknown banks and embedded payment flows are not automatically detected.
- Added single-use, three-minute, boot/monotonic-bound resume authorization to reject unapproved reconnects during a pending pause. Alarms, reboot, updates and app/notification opens cannot authorize resume.
- Polished silent/private status notifications and deduplicated compatibility alerts; release builds have no inspector receiver. Debug inspection rejects payment/SystemUI/Settings, foreign-package and password content.
- USB installation no longer silently grants secure-settings access. No runtime networking, analytics, broad package permission, or new runtime dependencies.

## 0.3.0 (2026-11-10)

- Renamed the app to **Detach** (it was called Unreel until 0.2.3). The package id changed to `io.github.detach`, so it installs as a new app: uninstall the old one, install this one, and set it up again (switches, password, and the optional adb grants).
- No change in behaviour.

## 0.2.3 (2026-11-10)

- Added an automated test suite (48 tests, run on the computer, no phone needed): the blocking rules for Instagram and YouTube, hostile on-screen text, the protection screen, the password, the pause, and what the installed app exposes. `scripts/release.sh` now runs it before every release.
- Hardening found while writing the tests: a tall feed post described as "Reel by …" can no longer be mistaken for the reel viewer on Home.

## 0.2.2 (2026-11-10)

- Fixed: on a fresh start of Instagram the home feed was not covered until a tab had been switched once. Instagram doesn't mark the Home tab as selected at launch; Home is now also recognised by its header.

## 0.2.1 (2026-11-10)

Fixes from the review of 0.2.0.
- The pause watcher's checks now overlap, so an Instagram or YouTube launch can't slip between two of them.
- A pause ends on time even without Usage access (the watcher keeps running for the length of the pause).
- A fingerprint lockout no longer falls through to the 30-second wait when no password is set; it says to try again later.
- If the fingerprint becomes unavailable while the unlock window is open, the 30-second wait applies instead of unlocking.
- The pause notification always asks for a tap on "Pause" when there is neither a password nor a fingerprint.
- The "Detach is still off" notice (only without the adb grant) now alerts.

## 0.2.0 (2026-11-10)

### Added
- **Pause for payments:** turns Detach off for 5 minutes so UPI apps that refuse to run alongside a sideloaded Accessibility app will open, then turns it back on. Started from a button in the app or from the new ongoing "Detach is on" notification (can be hidden); "Resume now" ends it early.
- Detach turns itself back on after a pause if `WRITE_SECURE_SETTINGS` has been granted once over adb (the USB install scripts try this). Without it, a notification asks you to switch it back on.
- A pause ends immediately if Instagram or YouTube is opened, when Usage access is granted (optional). New permissions for this: `PACKAGE_USAGE_STATS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`.
- Pause length can be set from 1 to 60 minutes in the app (default 5).
- **Fingerprint** as an alternative to the password for the switches, the "Detach is protected" screen and pausing (Android 10+). With no password set, the protected screen asks for a fingerprint instead of the 30-second wait. Changing or removing the password still needs the password.

### Changed
- New permissions: `USE_BIOMETRIC`, `RECEIVE_BOOT_COMPLETED`, `WRITE_SECURE_SETTINGS` (see docs/PRIVACY.md). Still no internet permission.

### Known limitations
- While paused, nothing is blocked and the "Detach is protected" screen is off.
- Pause, auto-resume and fingerprint weren't yet verified on a device at release time.

## 0.1.0 (2026-11-10)

First release.

### Instagram
- Home feed covered under the Stories row ("Back to stories" button), with an instant cover when Instagram returns to the front.
- Reels tab redirects to DMs.
- Reels sent in DMs play; swiping to the next (suggested) reel goes back to the chat.
- Search: account results visible; Explore grid and reel/post grids in results covered.
- Other people's profiles: header visible, posts grid covered.
- Own profile, settings, account switching, posting untouched.

### YouTube
- Shorts blocked (Shorts tab → Home; Shorts opened elsewhere closed).

### App
- Per-app main switches and per-feature Instagram switches.
- Optional password (salted PBKDF2, `*`-masked input) for every switch; Change requires the current password; Remove always asks.
- "Detach is protected" panel on Detach's Accessibility page, App info and uninstall prompt (password or 30-second wait, then a 2-minute window).
- Guided first-run setup, including Android 13+ "Allow restricted settings".
- Diagnostics and a notification if an app update breaks detection.

### Security
- No internet permission; Accessibility limited to Instagram, YouTube, Settings and package installers.
- Independent audit: all medium and low findings fixed (see SECURITY.md).

### Known limitations
- The "reel from a DM" flow wasn't yet verified on a device at release time.
- Some fallback detection uses English labels.
