# Installing Detach

Detach isn't on the Play Store, so it's installed from an APK file. There are two ways:

- **Option A: open the APK on the phone.** Easiest, but Google Play Protect may block it (see below).
- **Option B: install from a computer over USB.** Always works, and skips Android's extra "restricted settings" step.

Requires **Android 9 or newer**.

---

## Option A: open the APK on the phone

1. Copy `Detach-<version>.apk` to the phone (WhatsApp, Telegram, Google Drive, a USB cable…) and tap it.
2. If Android asks, allow that app (Files, Chrome, WhatsApp…) to **install unknown apps**.
3. Tap **Install**.

### If Play Protect says "App blocked" or "Unsafe app blocked"

In **India** (and some other countries), Google's *enhanced fraud protection* automatically blocks apps that use **Accessibility** when they're installed from a browser, messaging app or file manager. Fake bank and loan apps abuse that same permission. The block is about the **type of permission**, not about Detach specifically, so a scan result won't change it.

What to do:
- **Use Option B** (recommended).
- If Play Protect offers **"Send app for security check"**, it's fine to send it. The APK contains no personal data or secrets. Just don't expect it to lift this block.

Some people temporarily turn off Play Protect to install. We don't recommend it unless you understand the trade-off, because Play Protect also guards against real scam apps.

---

## Option B: install from a computer over USB

This uses Android's developer tool `adb`. Installs through `adb` aren't blocked by Play Protect, and they don't need "Allow restricted settings".

### 1. On the phone: turn on USB debugging
1. **Settings → About phone** → tap **Build number** 7 times, until it says you're a developer.
2. **Settings → System → Developer options** → turn on **USB debugging**.

### 2. On the computer: get adb
- **Windows / macOS / Linux:** download Google's [SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools) and unzip it.
- Or: Arch `sudo pacman -S android-tools` · Debian/Ubuntu `sudo apt install adb` · macOS `brew install android-platform-tools`.

### 3. Connect and install
1. Plug the phone in with a USB cable and tap **Allow** on the "Allow USB debugging?" popup.
2. Run the script from this repository's `scripts/` folder, with the APK path:

   **Linux / macOS**
   ```sh
   ./scripts/install-usb.sh Detach-<version>.apk
   ```
   **Windows** (put `adb.exe` next to the script, or add it to PATH)
   ```bat
   scripts\install-usb.bat Detach-<version>.apk
   ```

   Or by hand: `adb install -r Detach-<version>.apk`

3. **Optional, recommended:** let Detach turn itself back on after a "pause for payments". The scripts try this for you; if you installed by hand, run it right after `adb install`:

   ```sh
   adb shell pm grant io.github.detach android.permission.WRITE_SECURE_SETTINGS
   ```
   See [below](#optional-let-detach-turn-itself-back-on-after-a-pause) for what it does.

4. **Optional:** after setup, turn **USB debugging off** again. Some UPI and banking apps refuse to run while Developer options are on. Detach keeps working without it.

---

## Set up (both options)

Open **Detach**. Until it's switched on, it shows a **Set up Detach** card:

1. **Turn on Detach.** Tap **Open Accessibility** → find **Detach** (sometimes under *Downloaded apps* or *Installed apps*) → switch on **Use Detach** → **Allow**.
   Android shows a strong warning that the app can "view and control the screen". That's standard for every Accessibility app. Detach only looks at Instagram, YouTube and its own Settings pages; see [PRIVACY.md](PRIVACY.md).
2. **If Android says "Restricted setting"** (Android 13+, Option A only): tap **Open App info** in Detach → tap **⋮** (top right) → **Allow restricted settings** → confirm. Then do step 1 again.
   On some phones this menu item only appears *after* you've seen the "Restricted setting" message once.
3. **Allow notifications (optional).** Detach only uses notifications to tell you if an Instagram or YouTube update stops it from working.

When the badge at the top says **● Active**, you're done. Open Instagram: the stories row is there and the feed under it is covered.

### Optional: let Detach turn itself back on after a pause

[Pause for payments](USING.md#pause-for-payments) turns Detach off for a few minutes (1 to 60, default 5). Android lets an app switch its own Accessibility service *off*, but not back *on*: that needs the `WRITE_SECURE_SETTINGS` permission, which can only be granted from a computer. Do it once (with USB debugging on, see [Option B](#option-b-install-from-a-computer-over-usb)); it survives updates and reboots, and is lost only if Detach is uninstalled:

```sh
adb shell pm grant io.github.detach android.permission.WRITE_SECURE_SETTINGS
```

On some phones (Xiaomi, Oppo, Realme…) this fails with a security error until you also turn on **USB debugging (Security settings)** / **Disable permission monitoring** in Developer options.

Without it, pausing still works; when the pause is over, a notification asks you to switch **Use Detach** back on yourself. To take the permission away again: `adb shell pm revoke io.github.detach android.permission.WRITE_SECURE_SETTINGS`.

Detach uses this permission for one thing: adding its own entry back to Android's list of enabled Accessibility services. See [PRIVACY.md](PRIVACY.md#permissions).

### Optional: end a pause when Instagram or YouTube is opened

While paused, Detach's Accessibility service is off, so it can't see which app is open. If you allow **Usage access**, it can still notice Instagram or YouTube coming to the front and end the pause within a second or two. Usage access shows which apps are opened and when, never what is on screen, and Detach reads it only during a pause.

- On the phone: open Detach and tap the hint on the Pause card, or go to Settings → Apps → Special app access → Usage access → Detach → allow.
- Or from a computer: `adb shell appops set io.github.detach GET_USAGE_STATS allow`

This only has an effect together with the permission above. Without Usage access the pause runs its full time.

### Recommended next steps
- **Set a password** in Detach (Password lock → Set password). Without one, the "Detach is protected" screen only asks for your fingerprint, or for a 30-second wait if the phone has none.
- **Exclude Detach from battery optimisation** if your phone is known for killing background apps (Xiaomi, Oppo, Vivo, Realme, OnePlus, Samsung): Settings → Apps → Detach → Battery → **Unrestricted**. Accessibility apps are normally left alone, but these phones sometimes stop them anyway. On phones with near-stock Android this usually isn't needed.

---

## Updating

Install the new APK over the old one with Option A or B. Your switches and password stay, as long as the new APK is signed with the same key (official releases always are).

If Android says **"App not installed: package conflicts"**, the new APK was signed with a different key. You'd have to uninstall Detach first, which resets its switches and password.

## Uninstalling

Because of the protection screen, uninstalling Detach takes one extra step:
1. Settings → Apps → **Detach** → the **"Detach is protected"** screen appears.
2. Tap **Unlock** / **Enter password** (or **Wait 30 seconds** if there's no password or fingerprint), then unlock.
3. You now have 2 minutes: tap **Uninstall**.

Forgot the password? From a computer: `adb uninstall io.github.detach`.

## Troubleshooting

| Problem | Fix |
|---|---|
| "App blocked" by Play Protect | Use [Option B](#option-b-install-from-a-computer-over-usb). |
| "Use Detach" is greyed out / "Restricted setting" | Setup step 2: App info → ⋮ → Allow restricted settings. |
| Detach's badge says **Off** after a while | Your phone stopped it. Turn it back on in Accessibility, and set battery to **Unrestricted** (see above). Using **Force stop** on Detach also turns its Accessibility off. |
| The feed or Reels show again after an Instagram update | Open Detach → **Diagnostics**. If items say "not yet" or show old times, the layout changed. Report it (see [SECURITY.md](../SECURITY.md#reporting)) or update the rules ([MAINTAINING.md](MAINTAINING.md)). |
| A UPI app refuses to open | Some refuse while any sideloaded Accessibility app is on: use [Pause for payments](USING.md#pause-for-payments). Others refuse while **Developer options / USB debugging** is on: turn Developer options off. |
| Detach stays off after a pause | The one-time permission isn't granted (the app says so under *Pause for payments*). Tap the "Detach is still off" notification and switch it on, or [grant the permission](#optional-let-detach-turn-itself-back-on-after-a-pause). |
