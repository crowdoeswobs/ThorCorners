<h1 align="center">Thor Corners</h1>

<p align="center">
  True-black rounded screen corners for <b>both</b> screens of the <b>AYN Thor</b>.<br>
  No ads, no tracking, no internet access, and touches always pass through.
</p>

---

## What it does

The AYN Thor's two AMOLED panels have hard square corners. Thor Corners draws small black
rounded "fillets" in each corner, so the screens look curved like a flagship phone's.

- **Both screens, set separately.** The top and bottom screens each get their own on/off switch and corner radius.
- **True black.** The corners are pure `#000000`, so on AMOLED those pixels are simply off. They're drawn above the status bar and navigation bar, so nothing tints them grey.
- **Never blocks your touches.** Taps and swipes in the corners go straight to the game or app underneath.
- **Per-app rules.** Hide the corners on the top screen, bottom screen, or both while a chosen app is open (for example, a game that already letterboxes).
- **Turn off on low battery.** Pick a level from 5% to 50%. The corners come back automatically while charging.
- **Start on boot**, switchable.
- **Changes apply live.** Sliders update the corners instantly while you drag them.
- **Gamepad friendly.** Everything is a standard Android control, so it works with the Thor's D-pad.
- **Tiny.** About 2.5 MB, with no third-party libraries at all.

<p align="center">
  <img src="docs/settings.png" alt="Thor Corners settings screen" width="720">
</p>

## Coded from scratch, inspired by Curvo

Thor Corners was inspired by [Curvo: Round Screen Corners](https://play.google.com/store/apps/details?id=com.mobileservicezonex.cornering).
I studied how that kind of app works, then wrote Thor Corners **from scratch** for the Thor.
It contains **no code, assets or resources from Curvo**. It's a fresh Kotlin project that uses plain
Android Views and no libraries.

### What it improves on, compared to Curvo

| | Curvo 1.1 | Thor Corners |
|---|---|---|
| **Second screen** | Draws only on the default (top) display | Draws on **every** display. On the Thor, top and bottom are configured separately |
| **Touches in the corners** | Its overlay can swallow taps and act like a hitbox | Touches **always pass through** (see below) |
| **Corner colour** | Overlay sits *under* the status and navigation bars, so corners can look grey there | Drawn *above* the system bars, so the corners stay **true black** |
| **Ads and tracking** | Ad networks, analytics, A/B testing and cross-promotion SDKs | **None**. The app doesn't even request internet access |
| **Size** | ~57 MB | ~2.5 MB |
| **Paywall** | Some features need Pro | Everything is free |

Curvo has extra features Thor Corners doesn't (edge lighting, notch masking, a dynamic-island pill,
themes). Thor Corners deliberately focuses on doing corners well on the Thor.

## How it works (and why it uses Accessibility)

Most corner apps use the **"Display over other apps"** permission. That causes two problems:

1. Since Android 12, the system **drops any touch** that lands on an opaque overlay from a regular app,
   even if the overlay is marked "not touchable". That's why corner overlays can feel like dead zones.
2. Those overlays sit **below** the status bar and navigation bar, so corners can't be fully black there.

Thor Corners instead draws its corners as **accessibility overlays**. Android trusts these windows,
so they can be fully opaque, sit above the system bars, and still let every touch through.

The accessibility service is used for drawing **only**:

- It cannot read window content (`canRetrieveWindowContent="false"`).
- It ignores every accessibility event it receives.
- The app has no internet permission, so nothing could leave the device even if it wanted to.

Each screen gets its own window context
(`createWindowContext(display, TYPE_ACCESSIBILITY_OVERLAY, null)`), with four small windows, one per
corner, each exactly as big as the radius. A `DisplayListener` keeps them in sync when a screen
turns on or off or rotates.

**Per-app rules** use Android's *Usage access* to see which apps are currently open. Android doesn't
report which screen an app is on, so a rule applies while that app is open on either screen.

## Install

1. Download `ThorCorners-x.y.apk` from [Releases](../../releases) and install it on your Thor.
2. Open **Thor Corners** and tap **Open Accessibility settings**. Then turn on **Thor Corners**.
   - On Android 13, if you see *"Restricted setting"*, go to **Settings → Apps → Thor Corners → ⋮ → Allow restricted settings**, then try again.
3. Optional, for per-app rules: open **Per-app rules** and tap **Grant usage access**.

The defaults are: top screen on at 15 dp, bottom screen off at 20 dp, and start on boot on.

## Build from source

Requirements: JDK 17+ and the Android SDK (platform 34 and build-tools 34). You can use Android Studio,
or just the command-line tools.

```powershell
# create local.properties pointing at your SDK, e.g.
#   sdk.dir=C:/Users/you/AppData/Local/Android/Sdk
./gradlew assembleDebug
```

To build, install on a Thor connected over wireless ADB, launch the app and follow its logs:

```powershell
adb connect <thor-ip>:<port>
.\deploy.ps1
```

## Project layout

```
app/src/main/java/dev/thorcorners/
├── CornerService.kt         accessibility service: draws corners on every display
├── CornerView.kt            one corner: a square minus a circle, filled black
├── ForegroundAppWatcher.kt  which apps are open (usage access), for per-app rules
├── Prefs.kt                 all settings and their defaults
├── MainActivity.kt          settings screen (built in code, no layout XML)
└── AppRulesActivity.kt      per-app rules list with search
```

## Requirements

- AYN Thor (Android 13). It should also work on other Android 11+ devices, including ones with a
  single screen, but it's only tested on the Thor.

## Disclaimer

Thor Corners is an independent hobby project. It isn't affiliated with AYN, or with Curvo and its
developer. "AYN" and "Thor" are trademarks of their respective owners.
