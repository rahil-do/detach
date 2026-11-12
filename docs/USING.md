# Using Detach

Everything happens on one screen in the Detach app.

## Protection status

| Badge | Meaning |
|---|---|
| **Protection is on** | Detach's Accessibility service is enabled. |
| **Ready when you are** | It's off. Follow the setup guidance ([INSTALL.md](INSTALL.md#set-up-both-options)). |
| **Waiting for Android** | Accessibility is enabled but the service is not connected. Protection is not confirmed; check Accessibility if it persists. |
| **Protection is paused** | A [payment pause](#pause-for-payments) is pending. The timer can resume with the optional USB grant; without it, tap **Resume protection** and complete Android Settings. |
| *Allow notifications…* | Optional protection status, payment reminders and compatibility warnings. |

## Instagram controls

The **main switch** turns all Instagram blocking on or off. **Customize Instagram** shows the individual controls:

| Switch | What it does | What still works |
|---|---|---|
| **Hide home feed** | Covers the posts under the Stories row. Tapping **Back to stories** on the cover scrolls back up. | Stories row when allowed, **+** (create), notifications |
| **Block Reels tab** | Opening the Reels tab sends you to your DMs. A reel opened anywhere except a chat (or your own profile) is closed. | A reel **sent to you in a DM** plays. Swipe to the next reel and you go back to the chat |
| **Hide Explore & reels in search** | Covers the Explore grid and any grid of reels/posts in search results. | The search box, account results, the **Accounts** filter |
| **Hide posts on others' profiles** | Covers the posts/reels grid on other people's profiles. | Their name, bio, highlights, Follow and Message buttons |
| **Allow Stories** | On by default. Off covers the tray and closes detected Story viewers. | Other Instagram choices remain independent |
| **Allow DMs** | On by default. Off closes detected inbox/chat screens. | Other Instagram choices remain independent |
| **Allow reels sent in DMs** | On by default. Off closes detected shared reels even when Reels-tab blocking is off. | Ordinary allowed DMs remain usable |

Your own profile and its posts, settings, account switching and creating posts remain usable. Stories, DMs and shared reels follow their independent controls. With DMs disabled, a blocked Reels tab returns Home or goes Back instead of opening the inbox.

## YouTube controls

**Block Shorts**: the Shorts tab sends you back to Home, and a Short opened anywhere else (Home shelf, search, channel, shared link) is closed. Normal videos are untouched.

## Pause for payments

Some payment apps refuse to open while an accessibility service is enabled. A payment pause fully disables Detach's Accessibility service, removes overlays and stops its protection worker. Timer-only mode keeps no app-monitoring service running. Separately enabling app detection permits a limited local foreground monitor during the pause.

- **Automatic pause:** enable auto-pause and choose your payment/banking apps. Google Pay India, PhonePe, Paytm and BHIM are selected initially. Only the selected app's foreground package name is checked; its screen is not inspected. Other banks must be selected explicitly. A selected app may need to be reopened after the first pause.
- **Manual pause:** use the app's pause control or the notification's payment-pause action. Manual pauses retain the fingerprint/password confirmation.
- **While paused:** all Instagram/YouTube blocking and Settings/uninstall protection are off. The app and silent status notification make that state explicit.
- **Timer:** choose 1–60 minutes, default 5. With the optional USB grant, a valid same-boot timer requests automatic resume once. Without the grant, it is a reminder. Android may deliver inexact alarms late. Reboot/update invalidate automatic authority; app/notification opens do not renew it.
- **Resume on Instagram/YouTube:** separately opt in under Payment preferences after reading the disclosure and granting Usage Access plus the optional USB permission. Recent foreground package events are checked about every two seconds only while paused, screen-on and unlocked. No screen content or usage history is stored. Detection is not instantaneous or guaranteed; Android can refuse the monitor start. See [mode limits](automatic-payment-resume.md).
- **Resume after paying:** open Detach and explicitly resume. With the optional [USB grant](INSTALL.md#optional-let-detach-turn-itself-back-on-after-a-pause), this restores the service directly; otherwise turn **Use Detach** back on in Accessibility settings. No grant is required to pause.
- **Status notification:** the active status opens Detach and offers a separate pause action. Hiding the active notification does not hide the paused-state reminder.

**Deliberate safety tradeoff:** automatic payment pauses do not ask for a password. Anyone can open a selected payment app to pause protection, then use Instagram/YouTube or disable/uninstall Detach. This is not tamper-proof banking detection. Timer-only mode cannot know whether banking has finished and may re-enable Accessibility while a bank is open; select enough time or do not grant automatic-resume permission. Optional detection can defer for fresh selected-bank evidence, not certify every banking flow. Configure selected apps and modes accordingly.

## Take a breather

Choose **Start a breather**, enter 1–60 minutes and select the features to make temporarily available. Starting or extending requires a fresh Detach password or enrolled biometric; there is no credential-free wait or phone-PIN fallback. Configure a password first if biometrics are unavailable.

Feed, Reels, Explore, profile posts, Stories, DMs, shared reels and YouTube Shorts are independent. If DMs are disabled, select DMs too to reach a shared reel; the shared-reel option still allows only one item, not endless swiping. A Reels breather allows the full player. Permanent switches and protected Settings remain unchanged. **End breather now** restores usual restrictions early; expiry reevaluates the active screen without needing another social-app event. A payment pause takes priority and the breather clock does not stop during it. Reboot, app process restart, invalid state or clock inconsistency ends a breather safely.

## Password lock

- **Set password**: at least 4 characters. It's shown as `*` while you type.
- With a password set, **every switch** asks for it before changing (including each Instagram option). After you enter it once, the switches stay unlocked until you leave the Detach screen.
- **Fingerprint:** if the phone has a fingerprint enrolled (Android 10 or newer), Android's fingerprint prompt appears instead, with a **Use password** button. Either one unlocks. The phone's PIN or pattern is never accepted.
- **Change** asks for your **current** password, then the new one twice. No fingerprint here.
- **Remove** always asks for the password. No fingerprint here either.
- After **5 wrong tries**, you have to wait 30 seconds.
- **Forgot it?** There's no recovery, by design. Reinstalling Detach resets everything (see [uninstalling](INSTALL.md#uninstalling)), or from a computer: `adb uninstall io.github.detach`.

Only a salted hash of the password is stored, never the password itself.

## "Detach is protected"

This panel covers:
- Detach's page in **Accessibility settings** (the *Use Detach* switch and the *Detach shortcut*)
- Detach's **App info** page (*Force stop*, *Uninstall*, *Archive*, storage)
- The **uninstall prompt** for Detach

Buttons:
- **Go back**: leaves the page.
- **Unlock**: shown if a fingerprint is enrolled. Unlocks with your fingerprint, or with your Detach password if you set one (**Use password**).
- **Enter password**: shown if you set a password and the phone has no fingerprint.
- **Wait 30 seconds**: shown if there's neither a password nor a fingerprint. A countdown, then *Continue*. Leaving the window resets the countdown.

After unlocking, Detach's system pages stay open for **2 minutes**. Other Settings pages (Wi-Fi, Display…) are never covered.

## Diagnostics

**Diagnostics ▾** at the bottom shows when Detach last recognised each part of Instagram and YouTube, and the last thing it blocked, for example:

```
Instagram tabs (by id):        just now
Instagram stories row:         2 min ago
YouTube Shorts player:         1 h ago
Last action: Instagram Reels tab (15:00:38)
```

If, after an Instagram or YouTube update, a line stays on "not yet" or an old time even though you're using that part of the app, detection for it has probably broken. Detach also sends a notification if it hasn't recognised Instagram's or YouTube's tab bar at all during a day of normal use.

## FAQ

**Does it slow my phone down or drain the battery?**
Protection evaluations are bounded and limited to supported foreground screens; overlays use a small metadata-only watchdog while visible. Timer-only payment pauses stop that work. The optional local monitor checks recent package events about every two seconds during a pause only while unlocked, with no wake lock; it stops after resume and has a bounded lifetime. Actual battery, RAM and latency depend on Android/OEM behavior and have not been benchmarked for this release. No zero-battery-cost claim is made.

**Will it break my UPI or banking apps?**
Detach never acts on, covers or reads other apps; see [PRIVACY.md](PRIVACY.md). But some UPI apps (Google Pay, BHIM…) refuse to open while *any* Accessibility app installed from outside the Play Store is switched on. For those, use [Pause for payments](#pause-for-payments). Some also refuse to run while **Developer options** are on; turn those off after installing.

**Can Instagram see that I'm using Detach?**
Detach doesn't modify Instagram or talk to its servers. It works like a screen reader on your phone. To Instagram it looks like you tapping normally (switching to the DMs tab, pressing Back).

**Does it work with multiple Instagram accounts?**
Yes. It applies to whichever account is active.

**Why does a reel from a DM close when I swipe?**
That's the point: the reel you were sent plays, but the endless feed of suggested reels after it doesn't.

**Can I still post Reels?**
Yes. Creating content isn't blocked.
