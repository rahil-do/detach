# PR #2 Integration Review

Reviewed against the original PR head `9379a5aecb619c1ea5dad49a772baa0601e03c49` and the integrated 0.4.0 implementation on November 13, 2026. Four implementation agents handled overlays, payment/privacy, notifications and UI; the parent independently reviewed their source and tests, then made the integration fixes below. This is a source/regression review, not independent security certification.

## Original PR Findings Resolved

| Finding | Resolution and regression coverage |
|---|---|
| The notification-shade latch cleared only on an Instagram event, leaving YouTube and protected Settings unhandled after the shade closed. | Foreground package metadata replaces the latch; service tests exercise recovery for both apps. |
| Disabling Reels-tab blocking bypassed the independent shared-reel restriction. | Shared-reel permission is evaluated separately; rule tests cover the toggle combinations and changes during a viewer session. |
| The armed Story fallback could mistake a large DM/profile surface for a Story. | DM/profile screens disarm the fallback; Story and reel contexts reset on observed app switches. |
| Inspector traversal used the event allowlist, allowing Settings/SystemUI content and release logging. | Debug-only social-content gating, per-node package/password checks, no release receiver, and R8 removal of inspector actions/logging. |
| Cached or queued covers/actions could outlive the screen or app that produced them. | Revision-checked main-thread updates, foreground/action rechecks, safe-window clipping and opaque in-place cover reuse. |

## Parent Integration Findings Resolved

- Pending-pause startup must reject unapproved Android reconnects before initializing overlays, a worker or an inspector. Explicit Resume now grants one three-minute, wall/elapsed/boot-bound durable authorization. Selected payment foreground still refuses initialization after authorization.
- The UI now explicitly discloses the auto-pause password bypass, unrestricted pause window and embedded/unknown-bank limitations.
- Release inspector registration and evaluation/action logging are disabled at compile time, not merely filtered at the dump entry point.
- Legacy guard/feed panels now share the new palette, typography and accessible controls. Protection surfaces remain immediately opaque, never fade through protected content, and the guard can scroll in small windows.
- A listed but disconnected accessibility service no longer appears as confirmed active protection. Connection refresh runs only while the activity is visible and is removed on pause.
- Backup/extraction rules explicitly exclude all storage domains from cloud backup and Android device transfer. Install scripts no longer grant secure-settings access silently.
- Notifications use explicit immutable PendingIntents, private generic lockscreen versions, silent status updates, consolidated health incidents and immediate recovery clearing. Diagnostics navigation is wired for cold and existing activities.

## Local Validation

```sh
ANDROID_HOME=/home/rahil/Android/Sdk ./gradlew \
  testDebugUnitTest testReleaseUnitTest assembleRelease lintRelease \
  --offline --console=plain
```

- Debug: **213 tests passed**, zero failures/errors/skips.
- Release: **213 tests passed**, zero failures/errors/skips, including release inspector silence.
- Release assembly and lint passed: **0 errors, 20 warnings**. Remaining warnings concern synchronous preference commits, localization, resource/API guidance and inset/resource compatibility; this is not a warning-free build.
- Compiled APK inspection confirms the four documented permissions only, no debuggable flag, backup exclusions, no providers and only the launcher activity exported.
- Release DEX inspection finds no inspector action strings, tag or dump marker after R8.
- The production signing certificate matches both `SECURITY.md` and the previously installed app. The verified 0.4.0/versionCode 7 release installs in place without wiping data or granting new permissions.

## Limits / Device Checks

- The signed release installs and launches successfully on the connected phone. The phone was locked during the attempted visual smoke test; actual layout/motion inspection requires the owner to unlock it. No banking/payment app was opened or recorded.
- Bank detection is package-based and applies only to selected apps when Android exposes usable foreground metadata. Unknown/unselected banks, embedded payment flows and OEM restrictions require manual pause or separate consented testing.
- Auto-pause intentionally bypasses password/anti-distraction enforcement. While paused, Instagram/YouTube and disabling/uninstalling Detach are unrestricted. There is no background watcher, and the reminder never restarts protection.
- Android may retain an idle cached process. Detach disables its accessibility service and removes overlays/callbacks; it does not promise process termination.
- The reconnect guard assumes durable preference writes and an uncompromised Android lifecycle. Storage failure, root, adb access, safe mode and OS compromise are not tamper-proofed.
- Event delivery, the 120 ms metadata-only overlay watchdog and social-app layout heuristics cannot guarantee zero visible frames or every OEM transition. Shared-reel provenance across unobserved app switches remains heuristic.
