# Automatic payment-pause resume

## Modes and permissions

The default payment pause fully disables Detach's accessibility service and schedules one timer for the selected 1–60 minutes. No Usage Access monitor runs by default. The timer cannot know whether the bank is still foreground; it may re-enable protection while the payment app is still open. Choose enough time for the transaction, or resume manually when finished.

Automatic enablement requires the owner's optional `WRITE_SECURE_SETTINGS` grant. Installing Detach does not grant it. Without that grant the pause remains pending and the due notification/app explains the manual Accessibility Settings fallback; an automatic attempt does not authorize a Settings reconnect or claim that protection is running. An explicit Resume action can authorize one manual Settings reconnect for three minutes.

For manual recovery, open Detach, tap **Resume**, then use Android Settings → Accessibility → Downloaded/Installed apps → Detach → enable the service within three minutes. Names vary by manufacturer. Merely opening Accessibility Settings, opening Detach or tapping the ongoing monitor notification does not authorize a reconnect. If a request expires, return to Detach and explicitly tap Resume again.

Owners who want true automatic background resume can grant the optional development permission themselves from an authorized computer:

```sh
adb shell pm grant io.github.detach android.permission.WRITE_SECURE_SETTINGS
```

This is a powerful Android development permission, not a normal runtime permission prompt. Detach uses it only to restore its own accessibility-service entry while preserving other entries. This implementation does not run the command, install anything, or change grants on the owner's device. If a grant is absent/revoked or a secure-settings write fails, use manual recovery rather than assuming protection has resumed.

“Resume when Instagram or YouTube opens” is a separate, default-off consent setting. It additionally requires Usage Access and the secure-settings grant. Enable it from Detach's consent UI, then enable Usage Access for Detach in Android Settings. If the device has no package-specific Usage Access screen, open Settings → Special app access → Usage access and select Detach. No monitor is started merely by opening the app, tapping its notification, rebooting, updating the app, or granting a permission.

## Detection, battery and privacy limits

The optional monitor runs only during a valid pending payment pause. A dedicated `HandlerThread` queries recent Usage Events approximately every **two seconds**, only with an interactive screen, no keyguard lock and unlocked user storage. Queries cover at most the most recent **ten seconds**, never before this pause began; after the first query they normally cover only the time since the last check. A single event buffer is reused. No wake lock, network request, third-party runtime dependency, content/class-name inspection or usage-history persistence is used. The app still requests neither `INTERNET` nor `QUERY_ALL_PACKAGES`.

This is near-immediate **best effort**, not instantaneous detection or a hard real-time upper bound: ordinary detection is within approximately one polling interval **after Android makes the event available**, plus binder/scheduler delay. Usage Events are not a topmost-window or payment-completion API; OEM behavior, delayed/missing events, multi-window and process termination can delay or prevent detection. There are no device measurements proving a battery/RAM improvement or a particular battery cost.

Only package names, foreground/background event types and timestamps are processed. Recent foreground and unresolved selected-payment package hints are held in memory, not written as a history. A fresh unresolved payment hint takes priority over a social hint. Hints expire after **30 seconds**; screen lock/off or a query gap longer than ten seconds discards cached state. The timer-side payment hint is additionally accepted only if a query completed in the last four seconds. Thus an old bank hint cannot defer a timer indefinitely. Once a hint expires, foreground is **unknown**, not “banking finished”; the timer reverts to the default's unknown-foreground limitation and may resume over an open bank. Do not rely on this monitor to establish transaction completion or to enforce payment-app compatibility indefinitely.

The foreground service has a private ongoing notification, an explicit immutable tap into Detach, no binder interface and no exported component. The public lockscreen version has no package information. Both notification versions request `localOnly`, an advisory bridge/companion-device hint, not an absolute guarantee against forwarding by privileged/system notification listeners. Disabling the feature, revoking Usage Access, ending a pause or completing a resume stops it. Screen-off/lock removes polling rather than scheduling repeated checks. An AppOps listener observes Usage Access revocation even while screen-off. Android's foreground-service task manager can also stop it. The service is non-sticky and is never restarted after boot or process death; if killed, the timer/manual fallback remains.

## Authority and fail-closed behavior

Starting a pause must successfully commit its durable pending marker **before** the parent accessibility service disables itself. `Pause.begin(...)` returns false on failure; callers must leave protection running in that case.

Automatic authority is bound to a unique pending pause, the original wall and elapsed start times, duration and boot count. Wall/elapsed ages must agree within five seconds. Timer authority is valid only at/after both original deadlines and for at most one additional hour; it is not renewed by a late delivery, app lifecycle callback, notification tap, boot or update. Boot/update explicitly invalidate it. A stale, malformed or mismatched marker remains paused with manual fallback. An automatic attempt is single-use. A successful secure-settings write retains the pending marker until the accessibility service consumes a separate wall/elapsed/boot-bound, single-use reconnect authorization and reports that it started. The legacy `ACTION_RESUME` broadcast remains ignored.

Rendering reminders must not cancel an authorized due alarm that Android has not delivered yet. App opening/UI countdown refresh may restore the original future alarm but cannot invent a new deadline or automatic token. A fresh known-payment hint at delivery may defer the single outstanding alarm by 30 seconds, preserving timer fallback if the monitor is then killed. This does not change the original pause deadline or extend authority; invalid/stale authority still requires manual fallback.

A timer resume records only the original selected bank as short-lived control state, so the same bank does not immediately trigger another pause on reconnect. The suppression expires after three minutes, after leaving that package, or on boot mismatch; a different bank is never exempt. Call `Pause.shouldAutoPausePayment(...)` for startup and foreground transitions, including non-payment transitions, so a later visit is treated normally. No package learned from Usage Events is persisted by the monitor.

## Android 12–15 foreground-service constraints

The monitor is a narrowly declared `specialUse` foreground service with `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_SPECIAL_USE`, plus its manifest subtype explanation. It does not impersonate a media, location, health or data-sync service. Android 14 requires the declared type/permission. `specialUse` has no runtime prerequisites of its own, but does not bypass ordinary startup restrictions, and distribution through Google Play may require review of this use case.

On Android 12+, background FGS starts need a platform exemption; a visible user action is supported, and the background-start documentation also refers to apps permitted to start activities from the background. A system-bound accessibility service can qualify under the documented system-bound-service allowance, but a later disabled service or generic background callback must not be assumed to qualify. Detach attempts startup only as part of beginning the pause, before accessibility is disabled, catches denial and falls back to timer/manual behavior. Acceptance of a start request is not proof that the monitor is running. Android 15's visible-overlay requirement for the `SYSTEM_ALERT_WINDOW` exemption is not used; Detach does not request that permission. Boot/update never launch this monitor. The `shortService` three-minute lifetime is inappropriate for a configurable pause up to an hour.

Android 12+ inexact alarms can be delivered late; Doze, OEM power management and process restrictions can add delay. Detach requests no new exact-alarm exemption: it uses exact delivery only where the platform already permits it, otherwise an inexact alarm. If delivery falls outside the original automatic-authority window, reconnect is refused and manual fallback remains. Restoring the reminder after a reboot does not restore authority to re-enable accessibility.

## Primary Android references

Reviewed November 13, 2026:

- [UsageStatsManager and Usage Access](https://developer.android.com/reference/android/app/usage/UsageStatsManager)
- [UsageEvents.Event package/event metadata](https://developer.android.com/reference/android/app/usage/UsageEvents.Event)
- [Foreground-service background-start restrictions and Android 15 overlay changes](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- [Foreground-service types, specialUse and shortService](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Background activity-start allowances for system-bound services](https://developer.android.com/guide/components/activities/background-starts)
- [Alarm scheduling and inexact delivery](https://developer.android.com/develop/background-work/services/alarms/schedule)
