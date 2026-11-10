# How Detach works

Detach is about 2,000 lines of Kotlin with no dependencies beyond the Android SDK. It doesn't modify Instagram or YouTube. It watches their screens through the Accessibility API and reacts in one of four ways: **cover** part of the screen, press **Back**, tap a **tab**, or show the **protection panel**.

## The loop

```
 Android ──event──▶ DetachService.onAccessibilityEvent
                        │  (only from Instagram / YouTube / Settings / installer)
                        ▼
                  requestEval()  ── throttle: new screen → now · scroll → 80 ms · other → 160 ms
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
   action → must be decided twice, ≥250 ms apart → foreground re-checked → performed
            (Back · click Messages tab · click Home tab) → loop guard (max 8 per 6 s)
   guard  → GuardOverlay full-window panel → UnlockActivity
```

A **watchdog** (every 250 ms) runs only while a cover or panel is visible, so they disappear as soon as the app leaves the screen. This is needed because Android doesn't send Detach events from other apps.

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
| `Pause.kt` | Pause for payments: the stored end time, the resume alarm and receiver, re-enabling the service (`WRITE_SECURE_SETTINGS`), the status notification |
| `PauseWatch.kt` | `PauseWatchService`: a foreground service that lives only during a pause, polls `UsageStatsManager` once a second and ends the pause when Instagram or YouTube comes to the front |
| `PauseActivity.kt` | Confirmation window opened from the "Detach is on" notification |
| `FeedCover.kt` | The opaque cover overlay (`TYPE_ACCESSIBILITY_OVERLAY`, touch-swallowing) |
| `Decision.kt` | `Decision` and `Action` types |
| `Settings.kt` | The switches (SharedPreferences) |
| `Lock.kt` | Password: salted PBKDF2-HMAC-SHA256, constant-time compare, 5-try lockout |
| `PasswordField.kt` | `*`-masked password input; background hashing |
| `Health.kt` | "Last recognised" timestamps, daily counters, the layout-changed notification |
| `Inspector.kt` | adb-only tree dump / event trace for updating rules |
| `MainActivity.kt` | The app's single screen: setup, pause, switches, password, privacy, diagnostics |
| `Targets.kt` | The package lists |

## Pause for payments

```
 MainActivity button / "Detach is on" notification → PauseActivity
        │  Auth.confirm (fingerprint or password; nothing if neither exists)
        ▼
 DetachService.pauseForPayments()      same process, via the service's static reference
        │  Pause.begin: store end time · set alarm + in-process timer · "paused until…" notification
        │  start PauseWatchService (checks once a second: time up? Instagram/YouTube opened?)
        │  remove overlays · disableSelf()
        ▼
 When the set time is up (1–60 min), or Instagram/YouTube is opened, or "Resume now"
 (watcher, alarm → PauseReceiver, timer, boot, or opening the app; whichever comes first)
        │  Pause.resume: add Detach's own entry to enabled_accessibility_services
        │  (without WRITE_SECURE_SETTINGS: "Detach is still off" notification → Accessibility settings)
        ▼
 DetachService.onServiceConnected → Pause.onServiceStarted: clear the pause, "Detach is on" notification
```

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
