# Plonk for Android

**Plonk** is a live 3D MMORPG on Solana ([play.plonk.land](https://play.plonk.land)). Players fight, gather, fish, trade and run a market. Tokens are never held in a game balance: every $PLONK or SKR purchase is paid from the player's own wallet and checked on-chain by the server before anything is granted.

This repo is the **native Android app** (`land.plonk.app`) for Seeker phones and the Solana dApp Store. It is a Kotlin WebView app around the live game, with native wallet access through **Mobile Wallet Adapter** and a set of phone features the website can't have.

> **For judges:** the game server and web client live in a separate, private repo. Access is shared with the judges through Align. This repo is everything that runs natively on the phone.

## At a glance

**Built natively in this app**

- **Mobile Wallet Adapter + Seed Vault.** Wallet links open the phone's wallet app (Seed Vault Wallet on a Seeker, or Phantom / Solflare). Sign-in and on-chain purchases go through it. With no wallet installed, the game offers the dApp Store listing instead of a dead button.
- **Bundled game files with live sync.** The APK ships the game's code, models, icons and sounds (1,182 files). Each time the game loads, the app compares them with the server's manifest and only downloads what changed, so a game update reaches the app without an app update. With no network at all, the game still opens from the APK.
- **Game alerts.** Boss spawns, payouts and market sales, invites and whispers become Android notifications while the game is in the background, each kind its own channel.
- **Share sheet** for invite links, **launcher shortcuts** (Bag, Market, World map), **deep links** (`https://play.plonk.land/...`, `plonk://`) and **haptics** on hits, kills, loot, level-ups and purchases.
- **Branded loading, offline and crash recovery.** Never a browser error page: a Plonk screen says what went wrong and reconnects by itself when the network returns. If Android kills the WebView renderer (low memory while the wallet is open), the app rebuilds it instead of crashing.
- **A game-safe shell.** Full screen, edge to edge, no pull-to-refresh, no reload on rotation or keyboard, and Android back closes the top game window first.

**In the game, live now** (private game repo; in the app it all runs through MWA)

- **Seeker Genesis perks.** A wallet holding a Seeker Genesis Token counts as a 1,000,000 $PLONK holder for every holder perk: premium zones, raids and bosses, the gold nameplate, the sparkle trail, custom skins. The server checks the token on-chain (Token-2022 group membership); the app's own `seeker` device hint is never trusted for this. Holders also get the "Making Moves" emote.
- **Pay with SKR.** Mounts and house tiers 4 to 8 can be paid in SKR at the same live dollar value as $PLONK. The server builds the transfer, the player's wallet signs it, and the server verifies it on-chain before the item is granted.
- **Gold to SKR.** Gold cashed out at the desk is paid in $PLONK as before, then one tap builds a Jupiter swap to SKR that the player's own wallet signs. The page checks the quote and the transaction's shape before the wallet opens. The house never trades.

Wallet sign-in and market purchases through MWA have been tested on a Seeker.

## Install

Requires Android 9 or newer and a Solana wallet app (Seekers ship with Seed Vault Wallet).

1. Get the signed APK, `Plonk-1.0.1.apk`, from the [v1.0.1 release](https://github.com/kevinzor/plonk-android/releases/tag/v1.0.1) (also linked in the submission).
2. Install it with `adb install Plonk-1.0.1.apk`, or open the file on the phone and allow installs from that source.
3. Open Plonk, tap Connect, and approve in the wallet.

The release signing certificate is
`89:4B:A1:18:65:81:5E:7A:24:27:E7:04:3D:8C:00:07:DD:46:80:D7:74:E7:0F:9D:AC:A7:09:56:17:EE:20:82` (SHA-256). Check it with `apksigner verify --print-certs Plonk-1.0.1.apk`.

To build it yourself, see [Build](#build).

## Architecture

```
 Seeker / Android phone
 +---------------------------------------------------------------+
 |  Plonk app (this repo)                                        |
 |                                                               |
 |  MainActivity: full screen, back, insets, renderer recovery   |
 |   |                                                           |
 |   +- WebView  https://play.plonk.land/?src=app                |
 |   |   |                                                       |
 |   |   +- PlonkWebViewClient --- solana-wallet: links ---------+--> Seed Vault / Phantom / Solflare
 |   |   |                                                       |    (Mobile Wallet Adapter)
 |   |   +- GameBundle --------- serves APK files whose sha256   |
 |   |   |                       still matches /app/manifest     |
 |   |   +- window.PlonkNative - bridge handlers: haptic, notify,|
 |   |                           share, wallets, info, bundle... |
 |   +- LoadController / StatusScreen: loading, offline, errors  |
 |   +- GameLinks / ShortcutActivity: deep links and shortcuts   |
 +----------------------------+----------------------------------+
                              | https + websocket
                              v
 play.plonk.land  (private game repo)
   web client: three.js world, app-bridge.js (uses PlonkNative when present)
   game server: Express + Colyseus rooms
     GET /app/manifest              sha256 of every static file (bundle sync)
     /.well-known/assetlinks.json   App Link verification for this app's cert
     on-chain checks                $PLONK and SKR payments, Seeker Genesis Token
                              |
                              v
 Solana mainnet  (RPC; Jupiter for the gold -> SKR swap, signed by the player)
```

## What the app does

Everything below is built in this repo, and the live game page uses all of it through `public/js/app-bridge.js` in the game repo. Outside the app those hooks do nothing, so the website is unchanged. What is still open on either side is listed in [docs/APP_BRIDGE.md](docs/APP_BRIDGE.md#pending-work).

| | |
|---|---|
| **Mobile Wallet Adapter** | `solana-wallet:` links open the phone's wallet app (Seed Vault, Phantom, Solflare). The page then gets a synthetic `blur`, so the MWA JS client knows the wallet opened (a WebView never fires one). Wallet sign-in and on-chain market purchases are tested on a Seeker. If no wallet app is installed, the page lists wallets (`wallets`) and sends the player to the dApp Store listing (`getWallet`) instead of a dead button. |
| **Game files in the APK** | The game's code, models, icons and sounds ship inside the app (`FETCH_BUNDLE=1` at build time). Each file is served from the APK while its sha256 still matches the live server's `/app/manifest`, and only changed files are downloaded. The plan is made per document, so old and new code never mix. With no network at all, the app opens the game from the APK, and once the network is back it moves the player onto the live game. See [docs/BUNDLE.md](docs/BUNDLE.md). |
| **Full-screen game** | Immersive mode, edge to edge. Game content is padded away from the camera cutout, and it shrinks above the keyboard so chat stays visible. |
| **Game-safe WebView** | No pull-to-refresh, so a downward drag can never reload a fight. Rotation, folds, keyboards, theme, bold text and SIM or roaming changes never recreate the activity. Page zoom is off (the game has its own pinch zoom). |
| **Back button** | Closes the top game window first (`window.plonkBack()`, or a synthetic Escape that counts only if it changed something). At the world root, a second press within 2 s is the system's own back: predictive back-to-home, and the game stays warm in the background. |
| **Loading and error screens** | The splash hands over to the PLONK logo with a slim gold load bar, never a blank or browser page. If the game can't load, a branded screen says why (offline, no answer, server error or restarting) and gets the player back in by itself: at once when the network returns (`ConnectivityManager` callback), otherwise on a countdown. A 4xx on a link falls back to the start page. Nothing retries in the background, and the screen may sleep while the error shows. |
| **Crash recovery** | If Android kills the WebView renderer (e.g. low memory while a wallet app is in front), the app rebuilds the WebView instead of crashing; in the background it waits until the player returns. A renderer that keeps crashing gets "Plonk stopped unexpectedly" and a growing countdown, not a loop. |
| **Native bridge** | `window.PlonkNative` (`WebViewCompat.addWebMessageListener`), limited to the `https://play.plonk.land` main frame. Each feature is a small handler, and the page asks which ones this build has (`caps`). Full contract: [docs/APP_BRIDGE.md](docs/APP_BRIDGE.md). |
| **Haptics** | `haptic` plays the phone's own click, tick and heavy-click effects. The game sends it on hits taken, kills, loot, level-ups, purchases and errors; the app rate-limits it for combat. |
| **Game alerts** | An "Alert me when I'm away" toggle in the game's settings turns boss spawns, payouts and market sales, party and trade invites and whispers into Android notifications while the game is off screen (on screen, the game shows them itself). Each kind is its own channel under "Game alerts", so players can mute one kind in system settings. The Android 13 prompt appears only when the player turns the toggle on. Tapping an alert resumes the running game, and opening the game clears the shade. **Limit:** alerts come from the running page, so they cover the first seconds after switching away (a trip to the wallet, a quick reply), not hours later: Android 14+ freezes a background app. Alerts while away need a server feed or push (pending). |
| **Share sheet** | `share` opens the Android share sheet (direct-share targets, preview title) for the invite button, and tells the page which app the player picked or that they cancelled. |
| **Deep links** | App Links for `https://play.plonk.land` (the server serves `assetlinks.json` with the release cert, so Android can verify them), referral links `https://plonk.land/?ref=...`, and `plonk://`. They open inside the game with `?ref=` / `?kol=` / `?open=` kept and checked. If the game is already running, the link goes to the page as an event instead of a reload, so a fight or trade isn't lost. `plonk.land` does not serve `assetlinks.json` yet, so on Android 12+ its links open in the browser unless the player allows them in the app's settings. See [Links into the game](#links-into-the-game). |
| **Launcher shortcuts** | Long-press the icon for **Bag**, **Market** and **World map**. They open `?open=bag` / `market` / `map`, or send the running game the `open` event, and the game opens that window once the player is in the world. |
| **Links and files** | Only https `play.plonk.land` stays in the app; `http://` game links are upgraded to https, other sites open in the system browser, and popups need a tap. `intent:` links are reduced to implicit, browsable targets. File pickers (skin upload) use the system picker. |


The WebView user agent ends in `Solana Mobile Web Shell PlonkApp/<version>`. The first marker lets wallet libraries treat the app as a supported MWA host. The second lets the game turn on app-only features.

## Project layout

```
app/src/main/java/land/plonk/app/
  MainActivity.kt        full-screen game activity: insets, back, renderer recovery, file chooser
  PlonkWebViewClient.kt  navigation policy (MWA intents, intent: sanitizing, https game origin only)
  PlonkChromeClient.kt   progress, debug console, popups (tap only) to the system browser, file chooser
  GameLinks.kt           deep links: strict parsing onto the game origin, in-place delivery to a running game
  ShortcutActivity.kt    invisible trampoline for launcher shortcuts (keeps a running game alive)
  LoadController.kt      loader and error screen state, auto-retry (backoff, network back, app resumed)
  NetworkWatcher.kt      default-network callback while the error screen or the offline snapshot is up
  OfflineCopyWatch.kt    moves a page opened from the offline snapshot onto the live game when online
  PageEvents.kt          app-to-page 'plonknative' events (links, network back)
  ui/
    StatusScreen.kt      the branded loading / can't-load screen
    PlonkLogoView.kt     the splash logo, vignetted into the page colour
    LoadingBar.kt        slim eased load bar with a moving highlight
  bundle/
    GameBundle.kt        serves unchanged game files from the APK; plans each page load (live/offline/network)
    LiveManifest.kt      GET /app/manifest with an ETag cache on disk
    BundleIndex.kt       what this APK carries (assets/bundled-manifest.json)
    BundlePaths.kt       which paths may ever be bundled, and their content types
    BundleResponse.kt    the HTTP response for one bundled file (headers, byte ranges)
  bridge/
    NativeBridge.kt      window.PlonkNative: origin lock, routing by message type, built-in caps
    BridgeHandler.kt     the interface one bridge feature implements
    Reply.kt             answers to the page (any thread, dropped once the page or WebView is gone)
    BridgeHost.kt        activity access for handlers: intents, permission prompts, result launchers
    HapticsHandler.kt    haptic
    CoreHandlers.kt      exit, awake, info
    NotifyHandler.kt     notify, notifyPermission, notifyState, notifySettings
    GameAlerts.kt        alert kinds and their notification channels; posting, replacing by tag, clearing
    ShareHandler.kt      share (Android share sheet)
    WalletHandler.kt     wallets, getWallet: installed MWA wallets and store links
    BundleHandler.kt     bundle
app/src/main/res/        icons, splash, theme, launcher shortcuts, network security config (cleartext only for MWA's loopback)
app/src/main/assets/     the game bundle, generated at build time and not committed
tools/fetch-bundle.mjs   fills the game bundle from the live server (or a local checkout)
scripts/build.sh         memory-capped build (see below)
docs/APP_BRIDGE.md       the page <-> app contract: every message, event and link, and pending work
docs/BUNDLE.md           how the game bundle works, and the server's /app/manifest contract
```

## Native bridge

The full contract, with examples for the game page, is in [docs/APP_BRIDGE.md](docs/APP_BRIDGE.md). In short: the page talks to the app with JSON strings. Every message has a type `t`. Replies carry the same `t`, plus the request's `id` if it sent one.

```js
PlonkNative.onmessage = (e) => { const m = JSON.parse(e.data); /* m.t, m.id, ... */ };
PlonkNative.postMessage(JSON.stringify({ t: 'caps' }));
// -> { t: 'caps', v: 1, types: ['awake', 'bundle', 'caps', 'exit', 'getWallet', 'haptic', 'info', 'notify', 'notifyPermission', 'notifySettings', 'notifyState', 'share', 'wallets'] }
```

| `t` | Request | Reply |
|---|---|---|
| `caps` | | `{ v, types }`: every type this build supports |
| `haptic` | `k`: tick, tap, hit, heavy, success or error. Rate-limited to about 16 per second. | none |
| `awake` | `on`: keep the screen on (default `true`) | none |
| `exit` | close the app | none |
| `info` | | `{ version, code, sdk, model, seeker }` |
| `notify` | `title`, `body`, `tag?`, `kind?` (boss, payout, invite, party, trade, whisper; else other), `ttl?` seconds. Shown only while the game is off screen. Alerts with the same `tag` replace each other. | `{ shown, reason? }`, where reason is foreground, permission, disabled or muted. With neither title nor body: `{ error: 'bad_request' }` |
| `notifyPermission` | Shows Android 13's notification prompt if needed. Send it from a player action, such as an "Alert me" toggle. | `{ granted, enabled, permission }` |
| `notifyState` | | `{ enabled, permission, kinds: { boss, payout, invite, whisper, other } }`. permission is granted, default or denied, as on the web. |
| `notifySettings` | `kind?`: open the system settings for these alerts, or for one kind | `{ opened }` |
| `share` | `text`, `url?`, `title?`. The url is appended to the text unless the text already contains it. | Once: `{ ok: true, app? }` with the chosen app's package, or on Android 15+ `{ ok: true, via: 'copy' \| 'edit' }`. `error`: `cancelled` (sheet closed, or replaced by a newer share), `bad_request`, `bad_url`, `unavailable`. |
| `wallets` | `icons`: also send each app's icon as a PNG data URL | `{ apps: [{ label, pkg, id?, icon? }], store }`: installed apps that answer Mobile Wallet Adapter links, and the store `getWallet` would use (`dappstore`, `play` or null) |
| `getWallet` | `which`: phantom or solflare | `{ store }`: opens the wallet's listing in the Solana dApp Store, else Google Play, else the Play page in the browser. `error: 'unknown_wallet'` or `'unavailable'` |
| `bundle` | | `{ mode, bundleBuild, liveBuild, bundled, served, servedBytes, network }`: how this page load used the APK's game files. mode is `live`, `offline` or `network` |

A handler that fails replies `{ t, error: 'failed' }`. Unknown types are ignored, so the page should check `caps` before using a newer feature.

The app also sends the page news it didn't ask for, as a cancelable `plonknative` event on `window`: `{ t: 'open', ... }` for a link tapped while the game runs, and `{ t: 'online', mode: 'offline' }` when a page opened from the offline snapshot can go live. If no listener calls `preventDefault()`, the app loads the link or reloads the page itself.

**Adding a feature.** Write a `BridgeHandler` that names its `types`, then add one line to `bridgeHandlers()` in `MainActivity`. A handler that needs a permission prompt or another app uses `BridgeHost`. A handler that needs a result launcher registers it in its constructor. The app refuses to start if two handlers claim the same type.

## Links into the game

| Link | Opens |
|---|---|
| `https://play.plonk.land/...` | that page; path and query kept (App Link) |
| `https://plonk.land/?ref=CODE&kol=CODE` | the game, with the referral |
| `plonk://play`, `plonk://bag`, `plonk://market`, `plonk://map` | the game, or that window; `?ref=` / `?kol=` allowed |
| Launcher shortcuts Bag / Market / World map | `plonk://bag`, `plonk://market`, `plonk://map` |

The shortcuts start `ShortcutActivity` rather than the game. Android launches static shortcuts with `FLAG_ACTIVITY_CLEAR_TASK`, which would destroy a running game. The trampoline lives in its own task and hands the link to the game.

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

**App Link verification.** `autoVerify` succeeds for a host once it serves `/.well-known/assetlinks.json` with the release signing cert. `play.plonk.land` serves it today; `plonk.land` (the landing site) does not yet. The file:

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
FETCH_BUNDLE=1 scripts/build.sh release   # first pack the live game's files into the APK
```

`FETCH_BUNDLE=1` runs `tools/fetch-bundle.mjs` (Node 20+) before Gradle. It needs the server's `/app/manifest`, or set `BUNDLE_FROM=<game>/public` to pack from a local checkout. Without the flag, the existing bundle is kept. An APK with no bundle works normally and streams every file. See [docs/BUNDLE.md](docs/BUNDLE.md).

The 1.0.1 release packs the live game's files as of manifest build `95f33281176d`: 1,187 files, 39.1 MB before compression.

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

The launcher shortcut glyphs are from Material Icons (Apache-2.0).

The template's license is in [`LICENSE-webshell-template`](LICENSE-webshell-template).
