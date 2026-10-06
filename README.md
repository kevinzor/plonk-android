# Plonk for Android

Plonk is a live 3D MMORPG on Solana ([play.plonk.land](https://play.plonk.land)). Players fight, gather, fish and trade. Every $PLONK purchase is paid from the player's own wallet and checked on-chain. This repo is the **native Android app** for the Solana dApp Store and Seeker phones.

The game server and web client live in a separate (private) repo. This app gives the live game a phone-first home with native wallet access through **Mobile Wallet Adapter**.

## What the app does

| | |
|---|---|
| **Mobile Wallet Adapter** | `solana-wallet:` links open the phone's wallet app (Seed Vault, Phantom, Solflare). The page then gets a synthetic `blur` so the MWA JS client knows the wallet opened (a WebView never fires one). Wallet sign-in and on-chain market purchases are tested on a Seeker. |
| **Full-screen game** | Immersive mode, edge to edge. Game content is padded away from the camera cutout, and it shrinks above the keyboard so chat stays visible. |
| **Game-safe WebView** | There is no pull-to-refresh, so a downward drag can never reload a fight. Rotation, folds, keyboards and theme changes never recreate the activity. Page zoom is off (the game has its own pinch zoom). |
| **Back button** | Closes the top game window first (`window.plonkBack()`, falling back to a synthetic Escape). At the world root, a second press within 2 s leaves the app. |
| **Crash recovery** | If Android kills the WebView renderer (e.g. low memory while a wallet app is in front), the app rebuilds the WebView and reloads instead of crashing. |
| **Native bridge** | `window.PlonkNative` (`WebViewCompat.addWebMessageListener`), limited to the `https://play.plonk.land` main frame. Each feature is a small handler, and the page can ask which ones this build has (`caps`). See [Native bridge](#native-bridge) below. |
| **Share sheet** | `share` opens the Android share sheet (direct-share targets, preview title) for referral invites and brag text, and tells the page which app the player picked or that they cancelled. |
| **Deep links** | Verified App Links for `https://play.plonk.land`, referral links `https://plonk.land/?ref=...`, and `plonk://`. They open inside the game with `?ref=` / `?kol=` kept. If the game is already running, the link goes to the page as an event instead of reloading it, so a fight or trade isn't lost. See [Links into the game](#links-into-the-game). |
| **Links and files** | Other sites open in the system browser. `intent:` links are sanitized to implicit, browsable targets. File pickers (skin upload) use the system picker. |

The WebView user agent ends in `Solana Mobile Web Shell PlonkApp/<version>`. The first marker lets wallet libraries treat the app as a supported MWA host. The second lets the game turn on app-only features.

## Project layout

```
app/src/main/java/land/plonk/app/
  MainActivity.kt        full-screen game activity: insets, back, renderer recovery, file chooser
  PlonkWebViewClient.kt  navigation policy (MWA intents, intent: sanitizing, in-scope host)
  PlonkChromeClient.kt   progress, debug console, popups to the system browser, file chooser
  GameLinks.kt           deep links: strict parsing onto the game origin, in-place delivery to a running game
  bridge/
    NativeBridge.kt      window.PlonkNative: origin lock, routing by message type, built-in caps
    BridgeHandler.kt     the interface one bridge feature implements
    Reply.kt             answers to the page (any thread, dropped once the page or WebView is gone)
    BridgeHost.kt        activity access for handlers: intents, permission prompts, result launchers
    HapticsHandler.kt    haptic
    CoreHandlers.kt      exit, awake, info
    ShareHandler.kt      share (Android share sheet)
app/src/main/res/        icons, splash, theme, network security config (cleartext only for MWA's loopback)
scripts/build.sh         memory-capped build (see below)
```

## Native bridge

The page talks to the app with JSON strings. Every message has a type `t`. Replies carry the same `t`, plus the request's `id` if it sent one.

```js
PlonkNative.onmessage = (e) => { const m = JSON.parse(e.data); /* m.t, m.id, ... */ };
PlonkNative.postMessage(JSON.stringify({ t: 'caps' }));
// -> { t: 'caps', v: 1, types: ['awake', 'caps', 'exit', 'haptic', 'info', 'share'] }
```

| `t` | Request | Reply |
|---|---|---|
| `caps` | | `{ v, types }`: every type this build supports |
| `haptic` | `k`: tick, tap, hit, heavy, success or error. Rate-limited to about 16 per second. | none |
| `awake` | `on`: keep the screen on (default `true`) | none |
| `exit` | close the app | none |
| `info` | | `{ version, code, sdk, model, seeker }` |
| `share` | `text`, `url?`, `title?`. The url is appended to the text unless the text already contains it. | Once: `{ ok: true, app? }` with the chosen app's package, or on Android 15+ `{ ok: true, via: 'copy' \| 'edit' }`. `error`: `cancelled` (sheet closed, or replaced by a newer share), `bad_request`, `bad_url`, `unavailable`. |

A handler that fails replies `{ t, error: 'failed' }`. Unknown types are ignored, so the page should check `caps` before using a newer feature.

**Adding a feature.** Write a `BridgeHandler` that names its `types`, then add one line to `bridgeHandlers()` in `MainActivity`. A handler that needs a permission prompt or another app uses `BridgeHost`. A handler that needs a result launcher registers it in its constructor. The app refuses to start if two handlers claim the same type.

## Links into the game

| Link | Opens |
|---|---|
| `https://play.plonk.land/...` | that page; path and query kept (App Link) |
| `https://plonk.land/?ref=CODE&kol=CODE` | the game, with the referral |
| `plonk://play`, `plonk://bag`, `plonk://market`, `plonk://map` | the game, or that window; `?ref=` / `?kol=` allowed |

Every link is rebuilt as a URL on the game origin, and the start URL's `src=app` is always kept. Hosts must match exactly, over https, with no user info and no port other than 443. `open` must be `bag`, `market` or `map`. `ref` and `kol` must be short codes (`[A-Za-z0-9_.@-]`, at most 64 characters). Anything invalid is dropped. Links from Recents are not replayed.

**Cold start:** the link is the first page loaded, so the page reads `?open=`, `?ref=` and `?kol=` from `location.search`.

**Game already running:** if the link only adds `open` / `ref` / `kol` to the page already showing, the app does not reload. It dispatches a cancelable `plonknative` event on `window`:

```js
window.addEventListener('plonknative', (e) => {
  const m = e.detail; // { t: 'open', target?: 'bag' | 'market' | 'map', ref?, kol?, url }
  if (m.t === 'open' && handleLink(m)) e.preventDefault(); // handled: don't reload
});
```

If no listener calls `preventDefault()` (an older page, or a page still loading), the app loads `url` instead, so the link is never lost. A plain link to the page already showing just brings the app to the front. Any other link (a different path, other params) loads normally.

**App Link verification.** `autoVerify` only succeeds once both hosts serve `/.well-known/assetlinks.json` with the release signing cert:

```json
[{
  "relation": ["delegate_permission/common.handle_all_urls"],
  "target": {
    "namespace": "android_app",
    "package_name": "land.plonk.app",
    "sha256_cert_fingerprints": ["89:4B:A1:18:65:81:5E:7A:24:27:E7:04:3D:8C:00:07:DD:46:80:D7:74:E7:0F:9D:AC:A7:09:56:17:EE:20:82"]
  }
}]
```

Check on a phone with `adb shell pm get-app-links land.plonk.app`. A debug build has a different cert, so allow it by hand: `adb shell pm set-app-links-user-selection --user cur --package land.plonk.app true play.plonk.land plonk.land`.

## Build

Requirements: JDK 21 and the Android SDK (platform 37, build-tools 37.0.0).

```bash
scripts/build.sh debug     # app/build/outputs/apk/debug/app-debug.apk
scripts/build.sh release   # signed if a signing env file exists
```

Our build box also runs the live game server, so `scripts/build.sh` runs Gradle inside a `systemd-run` scope:
- hard 2.6 GB memory cap
- low CPU weight
- temp files on disk

If the build runs out of room, the build is killed, never the game.

**Release signing.** The keystore and passwords are never in this repo. `scripts/build.sh release` reads `/root/plonk-android-keys/signing.env`; override the path with `PLONK_SIGNING_ENV`. The file sets:

```
SOLANA_MOBILE_KEYSTORE_PATH=...
SOLANA_MOBILE_KEYSTORE_ALIAS=...
SOLANA_MOBILE_KEYSTORE_PASSWORD=...
SOLANA_MOBILE_KEY_PASSWORD=...
```

Following Solana dApp Store rules, the release key is used only for the dApp Store, never for Google Play.

App id, start URL and version are Gradle properties in `gradle.properties`:
- `SOLANA_MOBILE_APPLICATION_ID`
- `SOLANA_MOBILE_URL`
- `SOLANA_MOBILE_VERSION_CODE`
- `SOLANA_MOBILE_VERSION_NAME`

## Credits

The app is forked from the **Solana Mobile webshell template** (`solana-mobile` CLI, Apache-2.0). Changes from the template:
- Compose removed
- pull-to-refresh and page zoom removed
- broader `configChanges`
- back handling, renderer-crash recovery, immersive insets, the native bridge, haptics, file chooser, and Plonk branding

The template's license is in [`LICENSE-webshell-template`](LICENSE-webshell-template).
