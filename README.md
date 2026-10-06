# Plonk for Android

Plonk is a live 3D MMORPG on Solana ([play.plonk.land](https://play.plonk.land)). Players fight, gather, fish and trade. Every $PLONK purchase is paid from the player's own wallet and checked on-chain. This repo is the **native Android app** for the Solana dApp Store and Seeker phones.

The game server and web client live in a separate (private) repo. This app gives the live game a phone-first home with native wallet access through **Mobile Wallet Adapter**.

## What the app does

| | |
|---|---|
| **Mobile Wallet Adapter** | `solana-wallet:` links open the phone's wallet app (Seed Vault, Phantom, Solflare). The page then gets a synthetic `blur` so the MWA JS client knows the wallet opened (a WebView never fires one). Wallet sign-in and on-chain market purchases are tested on a Seeker. If no wallet app is installed, the page can list wallets (`wallets`) and send the player to the dApp Store listing (`getWallet`) instead of a dead button. |
| **Full-screen game** | Immersive mode, edge to edge. Game content is padded away from the camera cutout, and it shrinks above the keyboard so chat stays visible. |
| **Game-safe WebView** | There is no pull-to-refresh, so a downward drag can never reload a fight. Rotation, folds, keyboards and theme changes never recreate the activity. Page zoom is off (the game has its own pinch zoom). |
| **Back button** | Closes the top game window first (`window.plonkBack()`, falling back to a synthetic Escape). At the world root, a second press within 2 s leaves the app. |
| **Loading and offline screens** | While the game loads, the splash hands over to the PLONK logo with a slim gold load bar, never a browser page. If the game can't load, a branded screen says why (offline, no answer, or the server restarting) and gets the player back in by itself: it reloads as soon as the phone is back online (`ConnectivityManager` network callback), and otherwise retries on a short countdown. Nothing retries while the app is in the background. |
| **Crash recovery** | If Android kills the WebView renderer (e.g. low memory while a wallet app is in front), the app rebuilds the WebView and reloads instead of crashing. |
| **Native bridge** | `window.PlonkNative` (`WebViewCompat.addWebMessageListener`), limited to the `https://play.plonk.land` main frame. Each feature is a small handler, and the page can ask which ones this build has (`caps`). See [Native bridge](#native-bridge) below. |
| **Links and files** | Other sites open in the system browser. `intent:` links are sanitized to implicit, browsable targets. File pickers (skin upload) use the system picker. |

The WebView user agent ends in `Solana Mobile Web Shell PlonkApp/<version>`. The first marker lets wallet libraries treat the app as a supported MWA host. The second lets the game turn on app-only features.

## Project layout

```
app/src/main/java/land/plonk/app/
  MainActivity.kt        full-screen game activity: insets, back, renderer recovery, file chooser
  PlonkWebViewClient.kt  navigation policy (MWA intents, intent: sanitizing, in-scope host)
  PlonkChromeClient.kt   progress, debug console, popups to the system browser, file chooser
  LoadController.kt      loader and error screen state, auto-retry (backoff, network back, app resumed)
  NetworkWatcher.kt      default-network callback while the error screen is up
  ui/
    StatusScreen.kt      the branded loading / can't-load screen
    PlonkLogoView.kt     the splash logo, vignetted into the page colour
    LoadingBar.kt        slim eased load bar with a moving highlight
  bridge/
    NativeBridge.kt      window.PlonkNative: origin lock, routing by message type, built-in caps
    BridgeHandler.kt     the interface one bridge feature implements
    Reply.kt             answers to the page (any thread, dropped once the page or WebView is gone)
    BridgeHost.kt        activity access for handlers: intents, permission prompts, result launchers
    HapticsHandler.kt    haptic
    CoreHandlers.kt      exit, awake, info
    WalletHandler.kt     wallets, getWallet: installed MWA wallets and store links
app/src/main/res/        icons, splash, theme, network security config (cleartext only for MWA's loopback)
scripts/build.sh         memory-capped build (see below)
```

## Native bridge

The page talks to the app with JSON strings. Every message has a type `t`. Replies carry the same `t`, plus the request's `id` if it sent one.

```js
PlonkNative.onmessage = (e) => { const m = JSON.parse(e.data); /* m.t, m.id, ... */ };
PlonkNative.postMessage(JSON.stringify({ t: 'caps' }));
// -> { t: 'caps', v: 1, types: ['awake', 'caps', 'exit', 'haptic', 'info'] }
```

| `t` | Request | Reply |
|---|---|---|
| `caps` | | `{ v, types }`: every type this build supports |
| `haptic` | `k`: tick, tap, hit, heavy, success or error. Rate-limited to about 16 per second. | none |
| `awake` | `on`: keep the screen on (default `true`) | none |
| `exit` | close the app | none |
| `info` | | `{ version, code, sdk, model, seeker }` |
| `wallets` | `icons`: also send each app's icon as a PNG data URL | `{ apps: [{ label, pkg, id?, icon? }], store }`: installed apps that answer Mobile Wallet Adapter links, and the store `getWallet` would use (`dappstore`, `play` or null) |
| `getWallet` | `which`: phantom or solflare | `{ store }`: opens the wallet's listing in the Solana dApp Store, else Google Play, else the Play page in the browser. `error: 'unknown_wallet'` or `'unavailable'` |

A handler that fails replies `{ t, error: 'failed' }`. Unknown types are ignored, so the page should check `caps` before using a newer feature.

**Adding a feature.** Write a `BridgeHandler` that names its `types`, then add one line to `bridgeHandlers()` in `MainActivity`. A handler that needs a permission prompt or another app uses `BridgeHost`. A handler that needs a result launcher registers it in its constructor. The app refuses to start if two handlers claim the same type.

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
