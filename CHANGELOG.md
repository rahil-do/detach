# Changelog

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
