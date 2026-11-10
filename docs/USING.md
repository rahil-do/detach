# Using Detach

Everything happens on one screen in the Detach app.

## The status badge

| Badge | Meaning |
|---|---|
| **● Active** | Detach's Accessibility service is on and working. |
| **Set up Detach** card | It's off. Follow the steps ([INSTALL.md](INSTALL.md#set-up-both-options)). |
| **⏸ Paused until 14:35** | A [pause for payments](#pause-for-payments) is running. **Resume now** ends it. |
| 🔔 *Allow notifications…* | Optional. Tap it so Detach can warn you if an app update breaks it. |

## Instagram card

The **main switch** turns all Instagram blocking on or off. **Customize ▾** shows the individual parts:

| Switch | What it does | What still works |
|---|---|---|
| **Hide home feed** | Covers the posts under the Stories row. Tapping **↑ Back to stories** on the cover scrolls back up. | Stories row, **+** (create), ♡ (notifications) |
| **Block Reels tab** | Opening the Reels tab sends you to your DMs. A reel opened anywhere except a chat (or your own profile) is closed. | A reel **sent to you in a DM** plays. Swipe to the next reel and you go back to the chat |
| **Hide Explore & reels in search** | Covers the Explore grid and any grid of reels/posts in search results. | The search box, account results, the **Accounts** filter |
| **Hide posts on others' profiles** | Covers the posts/reels grid on other people's profiles. | Their name, bio, highlights, Follow and Message buttons |

Always available: DMs, your own profile and its posts, settings, switching between your accounts (long-press the profile tab, or tap your username at the top of your profile), posting posts, stories and reels.

## YouTube card

**Block Shorts**: the Shorts tab sends you back to Home, and a Short opened anywhere else (Home shelf, search, channel, shared link) is closed. Normal videos are untouched.

## Pause for payments

Some payment apps (Google Pay, BHIM and other UPI apps) won't open while a sideloaded Accessibility app is on. A pause turns Detach off for a few minutes (**5** unless you change it), then back on.

- **Start a pause:** tap **Pause for 5 minutes** in the app (the button shows the length you set), or tap the ongoing notification **"Detach is on. Tap to pause for payments"**. Detach asks for your fingerprint or password first. If the phone has neither, the notification opens a small window and you tap **Pause** to confirm.
- **While paused:** the notification says **"Detach paused until 14:35"**, and the app shows the same with a **Resume now** button. Nothing is blocked, and the "Detach is protected" screen is off.
- **End it early:** tap the notification or **Resume now**. No fingerprint or password needed.
- **Turning back on by itself** needs a [one-time command from a computer](INSTALL.md#optional-let-detach-turn-itself-back-on-after-a-pause). Without it the pause still works, but when it's over a notification says **"Detach is still off"**; tap it and switch **Use Detach** back on in Accessibility. The app tells you which of the two applies.
- **Show pause notification** (in the same card) hides the "Detach is on" notification. The button in the app still works, and the "paused" notification is always shown.

The notification is silent and needs notifications to be allowed for Detach. If the payment app still refuses, close it fully and open it again after pausing.

**Pause length.** The default is 5 minutes. Change it with the − and + buttons on the Pause card, one minute at a time, from 1 to 60. If a password is set, changing it asks for your fingerprint or password.

**Ending early when Instagram or YouTube opens.** With **Usage access** allowed for Detach (the app shows a hint, or Settings → Special app access → Usage access), the pause ends within about a second of Instagram or YouTube being opened, so a pause can't be used to scroll. If you pause while one of them is already open, the pause ends as soon as you return to it. Without Usage access the pause simply runs its full time.

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
No. Detach does nothing unless Instagram, YouTube or its own Settings pages are on screen. While Instagram is open, each check takes a few milliseconds and runs at most about 6 times a second. There are no wakelocks or background jobs. In testing, Android reported 0% battery use for Detach.

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
