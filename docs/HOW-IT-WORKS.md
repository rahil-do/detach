# How Detach works

Detach is a native Kotlin app with no runtime dependencies beyond the Android SDK. It doesn't modify Instagram or YouTube. It watches their screens through the Accessibility API and reacts in one of four ways: **cover** part of the screen, press **Back**, tap a **tab**, or show the **protection panel**.

## The loop

```
 Android ──event──▶ DetachService.onAccessibilityEvent
                        │  (social / Settings / installer / System UI / selected payments)
                        ▼
                  package routing → payment: pause · System UI: hide, no content reads
                        ▼
                  requestEval()  ── coalesced, frame-scale scheduling for target surfaces
                        ▼
                  evaluate()   [worker thread]
                        │ rootInActiveWindow → package check (anything else: hide all, stop)
                        │ Snap(root): one pass over the visible accessibility tree
                        ▼
      ┌─────────────────┼────────────────────┐
 InstagramRules     YouTubeRules           Guard
      │                 │                    │
      └──── Decision(cover?, action?) ───────┘
                        ▼
   cover  → FeedCover overlay (clipped to the app's window)
   action → cover immediately, confirm twice → foreground re-checked → performed
            (Back · click Messages tab · click Home tab) → loop guard (max 8 per 6 s)
   guard  → GuardOverlay full-window panel → UnlockActivity
```

A lightweight **foreground watchdog** runs only while an overlay is visible. It checks package metadata rather than repeatedly snapshotting an unchanged screen. Covers update in place without window animations; versioned UI work is rejected after a package/state change, and bounded loading-state covers avoid exposing content between incomplete trees. Geometry is clipped to the app window and safe screen bounds. Accessibility events cannot guarantee zero visible frames on every device; app updates and real-device transitions still need regression testing.

## Source tour (`app/src/main/java/io/github/detach/`)

| File | Role |
|---|---|
| `DetachService.kt` | The Accessibility service: events, throttling, evaluation, confirmation, actions, overlays, watchdog, loop guard |
| `Snap.kt` | Flattens the active window's tree into a list with IDs, labels, bounds and an "on screen" flag. Skips off-screen subtrees, since Instagram keeps whole tabs laid out to the side |
| `InstagramRules.kt` | Instagram decisions: tabs, Reels viewer state (opened from a chat? swiped?), home feed, search, profiles |
| `YouTubeRules.kt` | Shorts player / Shorts tab |
| `Guard.kt` | Recognises Detach's own system pages; the "Detach is protected" overlay |
| `UnlockActivity.kt` | Fingerprint / password / 30-second wait window opened from the protection panel |
| `Auth.kt` | "Fingerprint or password": Android's `BiometricPrompt` with the Detach password as the only fallback; the shared password dialog |
| `Pause.kt` | Durable pause lifecycle, bounded automatic timer capability, single-use reconnect authorization and one immutable alarm |
| `PauseForegroundMonitor.kt` | Default-off, consented Usage Access package monitor: one bounded worker, unlocked-only polling, no content/history/network |
| `PaymentAppPicker.kt` | Searchable local app labels/icons with bounded asynchronous loading and stop-invalidated Save |
| `Breather.kt`, `BreatherControls.kt`, `BreatherIntro.kt` | Authenticated general temporary permissions, number-wheel picker, boot/process/clock-bound expiry and native controls |
| `StatusNotifications.kt` | Silent, local active/paused/reminder notifications with explicit actions |
| `PauseActivity.kt` | Confirmation window opened from the "Detach is on" notification |
| `FeedCover.kt` | The opaque cover overlay (`TYPE_ACCESSIBILITY_OVERLAY`, touch-swallowing) |
| `CoverState.kt` | Per-package, clipped cover frames and bounded loading-state handoff |
| `Ui.kt` | Shared native visual language and motion helpers |
| `Decision.kt` | `Decision` and `Action` types |
| `Settings.kt` | The switches (SharedPreferences) |
| `Lock.kt` | Password: salted PBKDF2-HMAC-SHA256, constant-time compare, 5-try lockout |
| `PasswordField.kt` | `*`-masked password input; background hashing |
| `Health.kt` | "Last recognised" timestamps, daily counters, the layout-changed notification |
| `Inspector.kt` | adb-only tree dump / event trace for updating rules |
| `MainActivity.kt` | The app's single screen: setup, pause, switches, password, privacy, diagnostics |
| `Targets.kt` | The package lists |
| `PauseTileService.kt` | "Pause for payment" Quick Settings tile; opens the authenticated pause confirmation (`PauseActivity`, which also offers "Pause and open <selected app>") |
| `OtherAccessibilityApps.kt` | Labels of other enabled non-system accessibility apps, for the Payments warning |

## Pause for payments

```
 Selected app (UPI, bank or an app you pay from) is the active window → package name only
   (an event alone pauses only while no active window is reported; otherwise ~1 s re-checks)
 OR manual pause (app, notification or Quick Settings tile) → Auth.confirm
   (optional "Pause and open": launch the selected app only after Detach leaves
    enabled_accessibility_services, polled for at most 2 s)
        ▼
 Persist pause successfully · remove overlays · cancel protection worker · disableSelf()
        │  then: replace alarms, paused notification
        │  one immutable timer; optional consented package monitor
        ▼
 Valid due timer (not while off/locked) OR opt-in observed social app → one automatic enable attempt
        │  with Usage Access: one package-only query first; defer while a selected app is in front
        │  requires secure-settings grant; otherwise reminder/manual Settings
        │  boot/update revoke auto authority; app opens never renew it
        ▼
 Explicit Resume button → short-lived durable user authorization
        │  optional WRITE_SECURE_SETTINGS: restore own service entry
        │  otherwise: complete the Accessibility-settings step
        ▼
 Service connection verifies pause authorization before initialization
        │  unauthorized reconnect: disableSelf, preserve pending pause
        ▼
 Authorized start → selected app still in front? pause again (re-checked for ~1 s)
        ▼
 Otherwise → clear pause/authorization, show active status
```

Automatic pauses bypass password and anti-distraction enforcement by design. During a pause, Instagram/YouTube and Settings/uninstall are unrestricted. Timer-only mode cannot know whether banking has finished. Optional monitoring queries package events, never bank nodes, and defers while a selected app is observed in front. Payment apps can check for accessibility services as they launch, so the reliable setups are selecting the app you pay from or using "Pause and open"; see [why](automatic-payment-resume.md#why-payment-apps-refuse-and-when-detach-must-already-be-off). A pending pause survives process death between persistence and `disableSelf()`; reconnect without one-use authorization is refused before creating overlays/workers. Storage failure prevents disabling without a durable pause. See [automatic resume policy](automatic-payment-resume.md).

Breather permissions are a read-only feature snapshot supplied to the rules, not rewritten permanent switches. A running service schedules reevaluation at expiry and on early-end/settings refresh. DMs navigation rechecks effective temporary permission before each action. Settings/Guard and payment routing remain outside those allowances; a payment pause wins. Sessions fail closed on reboot, process restart, invalid data or clock inconsistency.

## How each screen is recognised

Each rule uses **at least two independent signals**, so a single renamed ID in an app update doesn't break it.

### Instagram (`com.instagram.android`)

| Thing | Primary signal | Fallback(s) |
|---|---|---|
| Bottom tabs + which is selected | IDs `feed_tab`, `clips_tab`, `direct_tab`, `search_tab`, `profile_tab` + `isSelected` | Clickable items in the bottom 25 % labelled Home / Reels / Message / Search / Profile |
| Reels viewer | ID `clips_viewer_view_pager` wider than half the screen | Full-screen element described "Reel by …" |
| Reels **tab** (vs. a reel opened elsewhere) | Reels tab selected | "Reels" + "Friends" header at the top |
| Which reel is showing | Description of `clips_media_component` / `clips_video_container` | `clips_author_username`; any "Reel by …" node |
| Swiped to another reel | Reel identity changed | `TYPE_VIEW_SCROLLED` from the viewer pager |
| In a DM chat | IDs containing `thread_composer` / `direct_thread` / `message_composer` | "Message…" text box; Audio/Video call buttons at the top |
| Stories row | ID `reels_tray_container` | Description "reels tray container"; a row of "…'s story" bubbles |
| Home feed | `title_logo` / "Instagram Home Feed" / stories row / `row_feed_profile_header` | |
| Your own profile | "Edit profile" / "Your profile…" / `create_highlight_button_view` | |
| A profile | `row_profile_header_imageview` / `profile_header_*` | posts + followers + following counters |
| Grid thumbnails | IDs `grid_card_layout_container`, `image_button`, `preview_container`, `media_previews_rv`… | "… at row N, column M", "Reel/Photo by …" |
| Search screen | Search tab selected | IDs `row_search_*`, `*search_edit_text*` (the results page hides the tab bar) |

**Reel opened from a chat:** Detach remembers the last non-reel screen. If it was a DM thread, the Messages tab or your own profile, the first reel is allowed and its identity recorded. Any change of identity, or a swipe more than 1.2 s after opening, presses Back.

### YouTube (`com.google.android.youtube`)

| Thing | Primary | Fallback |
|---|---|---|
| Shorts player | IDs `reel_recycler`, `reel_player_page_container` taller than half the screen | Any large `shorts_*` / `reel_player*` element |
| Shorts tab | "Shorts" selected inside `pivot_bar` | Bottom-bar items by label |
| Action | Tap **Home** in `pivot_bar` | Back |

### Settings / installer (protection)

A page is protected if it shows "Detach" **and** one of *Use Detach*, *Detach shortcut*, *Uninstall*, *Force stop*, *Archive*, *Disable*, *Clear storage/data/cache*. As a language-independent fallback, it's also protected if it's titled "Detach" in the top 30 % and has an on/off switch or a row of action buttons under the title. For the uninstall prompt, a mention of "Detach" is enough.

## Safety mechanisms

- **Double decision:** an action must be decided twice, at least 250 ms apart, so half-loaded screens don't trigger wrong moves.
- **Foreground re-check** right before acting.
- **Cooldown** of 700 ms after each action; **loop guard**: more than 8 actions in 6 s pauses Detach for 15 s instead of trapping you.
- **Clipped covers:** never outside the app's own window.
- **Instant cover on return:** when Instagram comes back to the front and was last on the home feed, the cover is drawn at its last position before the first full check, so the feed never flashes.

## Tests

`app/src/test/` holds unit tests that run on a computer. They build fake accessibility trees with the ids, labels and positions the real apps report, feed them to the rules, and check the decision. See [MAINTAINING.md](MAINTAINING.md#tests).
