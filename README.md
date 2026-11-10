# Detach

**Use Instagram and YouTube without the endless scrolling.**

Detach is a small Android app that works with the **official** Instagram and YouTube apps. It hides the parts designed to keep you scrolling and keeps the parts you actually need: messages, stories from friends, posting, and normal videos.

- No root, no modified Instagram, no login.
- No internet permission. It can't send anything anywhere.
- Free and open source (MIT).

---

## What it does

### Instagram

| Part of Instagram | With Detach |
|---|---|
| Stories row (top of Home) | ✅ Works normally |
| Home feed (posts under the stories) | 🚫 Covered |
| Reels tab | 🚫 Sends you to your DMs |
| Reels someone **sends you in a DM** | ✅ Plays. Swipe to the next (suggested) reel and you go back to the chat |
| Search | ✅ Find accounts. 🚫 The Explore grid and reel/post grids in results are covered |
| Other people's profiles | ✅ Name, bio, Follow/Message buttons. 🚫 Their posts/reels grid is covered |
| DMs, notifications | ✅ Normal |
| Your own profile, settings, switching between accounts | ✅ Normal |
| Posting posts, stories, reels | ✅ Normal |

### YouTube

| Part of YouTube | With Detach |
|---|---|
| Shorts (Shorts tab, Shorts on Home, in search, on channels) | 🚫 Blocked: you're sent back |
| Normal videos, Home, Subscriptions, You | ✅ Normal |

Every item can be switched on or off in the app. Instagram's blocks can be switched individually.

### Password lock and "Detach is protected"

- An **optional password** protects every switch in the app. It's needed to turn Instagram or YouTube blocking on or off, and for each Instagram option. If the phone has a **fingerprint** enrolled, that unlocks the switches too.
- If someone opens Detach's page in **Accessibility settings**, its **App info** page (Force stop, Uninstall, Clear storage) or its **uninstall prompt**, a full-screen **"Detach is protected"** panel covers it. Getting past it needs your fingerprint or the password, or a 30-second wait if the phone has neither. After that, there are 2 minutes to make the change.

This slows down impulsive decisions. It isn't meant to be unbreakable: see [Limitations](#limitations).

### Pause for payments

Some payment apps (Google Pay, BHIM and other UPI apps in India) refuse to open while a sideloaded Accessibility app like Detach is switched on. **Pause** (a button in the app, or a tap on the "Detach is on" notification) turns Detach off after a fingerprint or password check, and back on when the time is up. The length is yours to set, from 1 to 60 minutes (default 5). With Usage access allowed, the pause also ends the moment Instagram or YouTube is opened, so it can't be used to scroll. Turning back on by itself needs a [one-time command from a computer](docs/INSTALL.md#optional-let-detach-turn-itself-back-on-after-a-pause); without it, a notification asks you to switch Detach back on by hand.

---

## Install

**→ Step-by-step guide: [docs/INSTALL.md](docs/INSTALL.md)**

Short version:
1. Get `Detach-<version>.apk` and install it.
   - In India and a few other countries, Google Play Protect blocks installing apps that use Accessibility when they come from WhatsApp, a browser or a file manager. If that happens, use the [USB install](docs/INSTALL.md#option-b-install-from-a-computer-over-usb) instead.
2. Open Detach and follow **Set up Detach**: turn on **Use Detach** in Accessibility. On Android 13 or newer you may first need **Allow restricted settings**.

Requires Android 9 or newer.

---

## Privacy in one paragraph

Detach has **no internet permission**, so Android won't let it connect to anything. It only looks at **Instagram, YouTube and Detach's own Settings pages**. When any other app is in front (UPI, banking, messages…), it only notes that "another app is in front" and steps aside; it never reads that app's screen. It stores your switches, a salted hash of your password (never the password itself), and a few timestamps for the health check. That's all. Details: [docs/PRIVACY.md](docs/PRIVACY.md).

## Security

An independent review checked battery use, hijacking and data leaks, and remote code execution. Its findings and fixes, the threat model, and how to report a problem are in **[SECURITY.md](SECURITY.md)**.

## Limitations

- **Instagram and YouTube updates can break detection.** Detach recognises each screen in at least two ways (internal view IDs, plus labels or layout), so one changed detail usually doesn't break it. If an update does break it, the app's **Diagnostics** section shows it, and you get a notification. See [docs/MAINTAINING.md](docs/MAINTAINING.md) for how to fix the rules.
- **Language:** most detection uses Instagram's and YouTube's internal view IDs, which don't change with the phone's language, and the Settings protection has a layout-based fallback. Some backups, though, match English labels: the "Edit profile" check that recognises *your own* profile, and YouTube's Shorts *tab* shortcut. On a phone set to another language those backups do nothing. It's tested in English only.
- **While paused, Detach is off.** During a "pause for payments", nothing is blocked and the "Detach is protected" screen isn't shown, so Detach can be switched off or uninstalled in those minutes.
- **The protection can be bypassed** by booting into safe mode, by a computer with USB debugging, or by a factory reset. It's a speed bump for willpower, not parental-control software.
- **Phones:** tested on one phone so far. The Settings protection covers standard Android Settings (Pixel, Motorola and others) and the Google, Samsung and Xiaomi uninstall prompts.

## Documentation

| Doc | For |
|---|---|
| [docs/INSTALL.md](docs/INSTALL.md) | Installing and setting up, including the Play Protect block and the USB install |
| [docs/USING.md](docs/USING.md) | Every switch, the password, the protection screen, Diagnostics, uninstalling, FAQ |
| [docs/PRIVACY.md](docs/PRIVACY.md) | Exactly what Detach can see, store and do |
| [SECURITY.md](SECURITY.md) | Threat model, audit results, reporting issues |
| [docs/HOW-IT-WORKS.md](docs/HOW-IT-WORKS.md) | Architecture and source tour |
| [docs/MAINTAINING.md](docs/MAINTAINING.md) | Building, signing, releasing, updating the detection rules |
| [CHANGELOG.md](CHANGELOG.md) | Version history |

## Build from source

```sh
git clone <this repo> && cd detach
echo "sdk.dir=$HOME/Android/Sdk" > local.properties   # path to your Android SDK
./gradlew assembleDebug                                 # → app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest                             # the test suite; no phone needed
```

Needs JDK 17 and Android SDK platform 35. The tests cover the blocking rules, hostile on-screen text, the password, the pause and what the installed app exposes; see [docs/MAINTAINING.md](docs/MAINTAINING.md#tests). For release builds and signing, see [docs/MAINTAINING.md](docs/MAINTAINING.md).

## License

MIT. See [LICENSE](LICENSE).

Detach is not affiliated with Instagram, Meta, YouTube or Google. Their names and logos belong to their owners and are used only to label the matching settings.
