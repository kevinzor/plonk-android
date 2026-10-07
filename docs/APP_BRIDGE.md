# The page and the app

How the game page (https://play.plonk.land) and the Android app talk to each other. The web client lives in a separate repo, so this file is the contract both sides build against.

**Short version**
- The page knows it is in the app from the user agent (`PlonkApp/<version>`) and from `window.PlonkNative`.
- The page sends JSON strings to `PlonkNative`. The app answers with JSON strings of the same type `t`.
- The app sends news the page didn't ask for (a link was tapped, the network is back) as a cancelable `plonknative` DOM event.
- Links reach the page as query params (`?open=`, `?ref=`, `?kol=`) on a cold start, and as the `open` event when the game is already running.
- Every feature is optional. A page that does nothing still works in the app exactly like on the website.

## Is this the app?

```js
const IN_APP = /\bPlonkApp\/[\d.]+/.test(navigator.userAgent);
const bridge = window.PlonkNative; // undefined on the website, in iframes of other origins, and on very old WebViews
```

The user agent ends in `Solana Mobile Web Shell PlonkApp/0.2.0`.
- `Solana Mobile Web Shell` lets wallet libraries treat the WebView as a Mobile Wallet Adapter host.
- `PlonkApp/<version>` is for the game.

`window.PlonkNative` exists only when the page runs in the app on `https://play.plonk.land`. It is injected before any page script runs. Messages are accepted only from the **main frame** of that origin; an iframe (an ad, a widget) can't reach the app even if it sees the object.

Check which messages this build supports before using one. Older app builds ignore unknown types.

```js
// -> { t: 'caps', v: 1, types: ['awake', 'bundle', 'caps', 'exit', 'getWallet', 'haptic', 'info',
//      'notify', 'notifyPermission', 'notifySettings', 'notifyState', 'share', 'wallets'] }
```

## Envelope

```js
// page -> app
PlonkNative.postMessage(JSON.stringify({ t: 'share', id: 7, text: 'Join me in Plonk' }));

// app -> page
PlonkNative.addEventListener('message', (e) => {
  const m = JSON.parse(e.data); // { t: 'share', id: 7, ok: true, app: 'org.telegram.messenger' }
});
```

- Every message is a JSON **string** with a type `t`.
- A reply has the same `t`, and echoes `id` when the request had one, so the page can match answers to requests.
- A handler that throws replies `{ t, error: 'failed' }`. Other errors are listed per type below.
- A reply is bound to the document that sent the request. If the page navigates or reloads first, the reply is dropped.
- `v` in `caps` is the envelope version (1). It changes only if this envelope changes, not when a type is added.

A small helper the game can drop in:

```js
const Plonk = (() => {
  const bridge = window.PlonkNative;
  const waiting = new Map();
  let nextId = 1;
  let caps;
  bridge?.addEventListener('message', (e) => {
    let m;
    try { m = JSON.parse(e.data); } catch { return; }
    const done = waiting.get(m.id);
    if (done) { waiting.delete(m.id); done(m); }
  });
  const send = (t, fields = {}) => bridge?.postMessage(JSON.stringify({ ...fields, t }));
  // Resolves with the reply, or null outside the app. Share and permission replies wait for the
  // player, so only give a timeout to types that answer at once.
  const ask = (t, fields = {}, timeoutMs = 0) => {
    if (!bridge) return Promise.resolve(null);
    const id = nextId++;
    return new Promise((resolve) => {
      waiting.set(id, resolve);
      if (timeoutMs) setTimeout(() => waiting.delete(id) && resolve(null), timeoutMs);
      send(t, { ...fields, id });
    });
  };
  const has = async (t) => {
    caps ??= ask('caps', {}, 3000).then((r) => new Set(r?.types ?? []));
    return (await caps).has(t);
  };
  return { inApp: !!bridge, send, ask, has };
})();
```

The examples below use this helper.

## Messages

| `t` | Request fields | Reply |
|---|---|---|
| `caps` | | `{ v, types }` |
| `info` | | `{ version, code, sdk, model, seeker }` |
| `haptic` | `k`: `tick`, `tap` (default), `hit`, `heavy`, `success`, `error` | none |
| `awake` | `on`: `true` (default) keeps the screen on, `false` lets it sleep | none |
| `exit` | | none (the app closes) |
| `notify` | `title`, `body`, `kind?`, `tag?`, `ttl?` | `{ shown: true }` or `{ shown: false, reason }` |
| `notifyPermission` | | `{ granted, enabled, permission }` |
| `notifyState` | | `{ enabled, permission, kinds }` |
| `notifySettings` | `kind?` | `{ opened }` |
| `share` | `text`, `url?`, `title?` | `{ ok: true, app? \| via? }` or `{ error }` |
| `wallets` | `icons?` | `{ apps, store }` |
| `getWallet` | `which`: `phantom` or `solflare` | `{ store }` or `{ error }` |
| `bundle` | | `{ mode, bundleBuild, liveBuild, bundled, served, servedBytes, network }` |

### `info`

`{ t: 'info', version: '0.2.0', code: 3, sdk: 35, model: 'Seeker', seeker: true }`

`seeker` is a hint from the device name, for cosmetic UI only. Anything worth money (perks, rewards) must come from the server's own on-chain check, such as a Genesis Token lookup.

### `haptic`

The app uses the phone's built-in effects where it has them, so a tap feels like the rest of the system. Calls closer than 60 ms apart are dropped, so combat can send one per hit.

```js
Plonk.send('haptic', { k: 'hit' });     // a landed hit
Plonk.send('haptic', { k: 'success' }); // level up, rare drop
Plonk.send('haptic', { k: 'error' });   // can't afford, inventory full
```

### `awake`

The screen stays on while the game is showing. The page can let it sleep (`on: false`) on long menus or when the player is AFK, and turn it back on later. While the app's own error screen is up, the phone sleeps as usual whatever the page chose.

### `exit`

Closes the app for good, so the next open is a cold load. Normally the page doesn't need it: Android back at the world root already sends the app to the background (see [Back button](#back-button)).

### Game alerts: `notify`, `notifyPermission`, `notifyState`, `notifySettings`

**`notify`** `{ title, body, kind?, tag?, ttl? }`
- `kind`: `boss`, `payout`, `invite` (`party` and `trade` also mean invite) or `whisper`. Anything else is `other`. Each kind is its own channel under "Game alerts" in system settings.
- `tag`: alerts with the same tag replace each other. Use one tag per boss or per sender.
- `ttl`: seconds the alert stays, 0 to 24 h. By default a boss alert goes after 20 min and an invite after 5 min; the rest stay until tapped.
- `title` is cut to 80 characters and `body` to 600. A missing title becomes "Plonk". With neither: `{ error: 'bad_request' }`.
- Replies `{ shown: true }`, or `{ shown: false, reason }`. `reason` is one of:
  - `foreground`: the game is on screen, so the game shows it itself;
  - `permission`: no notification permission;
  - `disabled`: notifications are off for the app;
  - `muted`: that kind's channel is off.
- Payout and whisper text is hidden on a locked screen when the player hides sensitive content.
- Tapping an alert resumes the running game. Opening the game clears the shade.

The page can call `notify` whenever it would show an in-game toast; the app decides whether the game is on screen.

> **Limit.** The alert comes from the running page. Once the player leaves, Android 14+ freezes the app within seconds and the socket stalls, so alerts cover the short window after switching away (a trip to the wallet, a quick reply), not hours later. Real "while you were away" alerts need a server feed or push; see [Pending work](#pending-work).

**`notifyPermission`** shows Android 13's prompt if permission isn't granted yet. Send it only from a player action. Below Android 13, `granted` is always true.

**`notifyState`** never prompts.
- `enabled` means permission is granted and the app's notifications are on.
- `permission` is `granted`, `default` (can still ask) or `denied` (Android won't show the prompt again). These are the same words as the web's `Notification.permission`, so the toggle code can be shared with the browser build.
- `kinds` has `boss`, `payout`, `invite`, `whisper` and `other`; each is false when the player turned that channel (or the whole group) off.

**`notifySettings`** `{ kind? }` opens Android's notification settings for the app, or for one kind's channel. It replies `{ opened }`.

```js
// "Alert me when I'm away" toggle
async function enableAlerts() {
  if (!(await Plonk.has('notify'))) return;
  const r = await Plonk.ask('notifyPermission');
  if (r.permission === 'denied') showButton('Open settings', () => Plonk.ask('notifySettings'));
  setToggle(r.enabled);
}
document.addEventListener('visibilitychange', async () => {
  if (document.visibilityState === 'visible' && Plonk.inApp) applyAlertState(await Plonk.ask('notifyState', {}, 3000));
});

// Where the game already shows a toast:
socket.on('bossSpawn', (b) => {
  toast(`${b.name} has spawned`);
  Plonk.send('notify', { kind: 'boss', tag: `boss:${b.id}`, title: `${b.name} has spawned`, body: `Near ${b.zone}` });
});
```

### `share`

Opens the Android share sheet, with the player's recent chats and a preview title.
- `text`: up to 4000 characters. `url`: http or https only, up to 2000. `title`: the sheet's headline and the email subject, up to 200.
- The url is added to the text on its own line unless the text already contains it.
- The app replies exactly once:
  - `{ ok: true, app: 'org.telegram.messenger' }`: the package the player picked;
  - `{ ok: true, via: 'copy' | 'edit' }`: the sheet's own Copy or Edit action (Android 15+);
  - `{ error: 'cancelled' }`: the sheet was closed, or a newer share replaced this one;
  - `{ error: 'bad_request' }` (no text and no url), `{ error: 'bad_url' }`, `{ error: 'unavailable' }`.

```js
async function shareInvite(code) {
  const url = `https://plonk.land/?ref=${code}`;
  if (!(await Plonk.has('share'))) return copyToClipboard(url);
  const r = await Plonk.ask('share', { text: 'Come fight with me in Plonk', url, title: 'Plonk invite' });
  if (r.error === 'cancelled') return; // the player changed their mind
  if (r.error) copyToClipboard(url);
}
```

Don't hand out rewards because of a share reply. The client can fake it.

### Wallets: `wallets`, `getWallet`

In the app, "Connect wallet" works through Mobile Wallet Adapter as on mobile Chrome: the MWA library opens a `solana-wallet:` link, and the app hands it to the phone's wallet (Seed Vault on a Seeker, Phantom, Solflare). A WebView never fires `blur` when another app opens, so the app sends a synthetic `blur` after the wallet opens; the MWA JS client relies on it.

With no wallet installed, that link goes nowhere and the button seems broken. Ask first:

- **`wallets`** `{ icons? }` replies `{ apps: [{ label, pkg, id?, icon? }], store }`.
  - `apps` are the installed apps that answer MWA links, sorted by label.
  - `id` is `phantom` or `solflare` for the wallets `getWallet` knows.
  - With `icons: true`, each app has a 96 px PNG data URL in `icon`.
  - `store` is where `getWallet` would go: `dappstore`, `play`, or `null`.
- **`getWallet`** `{ which: 'phantom' | 'solflare' }` opens that wallet's listing in the Solana dApp Store, else Google Play, else the Play page in the browser. It replies `{ store: 'dappstore' | 'play' | 'web' }`, or `{ error: 'unknown_wallet' }` / `{ error: 'unavailable' }`.

```js
async function connectWallet() {
  const r = (await Plonk.has('wallets')) ? await Plonk.ask('wallets', { icons: true }, 5000) : null;
  if (r && r.apps.length === 0) {
    const where = r.store === 'dappstore' ? 'Get it on the Solana dApp Store' : 'Get it on Google Play';
    return showGetWallet(where, (which) => Plonk.ask('getWallet', { which }));
  }
  return mwaConnect(); // the normal MWA flow
}
// After the player installs one and comes back:
document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible') refreshWalletButton(); });
```

### `bundle`

The app ships the game's static files inside the APK and serves unchanged ones from there (see [BUNDLE.md](BUNDLE.md)). `bundle` reports how the current page was loaded:

`{ mode: 'live' | 'offline' | 'network', bundleBuild, liveBuild, bundled, served, servedBytes, network }`

- `live`: unchanged files came from the APK.
- `offline`: the phone had no network, and the whole page is the APK's snapshot. The game can show a small "offline copy" note on its reconnecting screen.
- `network`: everything came from the network, as on the website.

The counts cover the current document only.

## Events from the app

News the page didn't ask for comes as a cancelable `plonknative` event on `window`. The bridge can only answer messages, and this news can arrive before the page has said anything. Call `preventDefault()` to say "handled". Otherwise the app does the job itself, so nothing is lost on a page that doesn't listen.

```js
window.addEventListener('plonknative', (e) => {
  const m = e.detail;
  if (m.t === 'open' && handleLink(m)) e.preventDefault();
  if (m.t === 'online' && reloadSoonOnMyOwnTerms()) e.preventDefault();
});
```

Register the listener early, during boot. An event that arrives before it is there is treated as unhandled.

| `t` | Detail | If not handled |
|---|---|---|
| `open` | `{ target?: 'bag' \| 'market' \| 'map', ref?, kol?, url }` | the app loads `url` (a full page load) |
| `online` | `{ mode: 'offline' }` | the app reloads the page |

**`open`**: a link was tapped while the game was already showing that page; see [Links](#links). Open `target`, record `ref` / `kol`, then call `preventDefault()`. Only call it if the link really was handled; otherwise the player would lose the link.

**`online`**: the page was running from the APK's offline snapshot and the network is back (validated). The snapshot can be older than the server, so the app moves the player onto the live game. A page that would rather reload at a good moment (after a fight) can call `preventDefault()`, but it must then reload soon: old client code should not keep talking to a newer server.

## Links

| Link | Opens |
|---|---|
| `https://play.plonk.land/<path>?...` | that page, with its query (App Link) |
| `https://plonk.land/?ref=CODE&kol=CODE` | the game, with the referral |
| `plonk://play`, `plonk://bag`, `plonk://market`, `plonk://map` | the game, or that window; `?ref=` / `?kol=` allowed |
| Launcher shortcuts Bag, Market, World map | `plonk://bag`, `plonk://market`, `plonk://map` |

The app rebuilds every link on `https://play.plonk.land` before loading it:
- `src=app` from the start URL is always kept.
- `open` must be `bag`, `market` or `map`.
- `ref` and `kol` must match `[A-Za-z0-9_.@-]{1,64}`. Anything else is dropped.
- Unknown paths fall back to the start page. A path the server answers with 4xx also falls back to the start page, keeping `ref` / `kol`.

**Cold start.** The link is the first page loaded, for example:

`https://play.plonk.land/?src=app&open=market&ref=ABC`

The page reads `open`, `ref` and `kol` from `location.search`. It can strip them afterwards with `history.replaceState`.

**Game already running.**
- If the link only adds `open` / `ref` / `kol` to the page already showing, the app sends the `open` event instead of reloading. A fight or a trade isn't lost.
- If no listener handles it, the app loads the link's `url`.
- A link with a different path or other params is a normal page load.
- Reopening Plonk from Recents never replays an old link.

## Back button

Android back asks the page to close its top window first:

```js
// true if a window (or anything else on top) was closed, false at the world root
window.plonkBack = () => escCloseTop();
```

- If `window.plonkBack` is missing, the app sends a synthetic `Escape` keydown on `window`, and counts it as "closed" if the DOM changed.
- At the world root, the first back shows "Press back again to leave Plonk". A second back within 2 s is the system's own back: the predictive back-to-home animation plays, and Android keeps the game warm in the background, so coming back resumes the session.

## What the app does on its own

None of this needs page code.

- **Loading and errors.** The splash hands over to a branded loader. If the game can't load, a branded screen says why and retries by itself:
  - offline: it waits for the network;
  - 5xx or 429: a countdown;
  - 502-504: "Plonk is updating".
  The page's own error HTML is never shown for a load the app started.
- **Renderer crashes.** If Android kills the WebView renderer, the app rebuilds it and reloads (when the player comes back, if it happened in the background). Repeated crashes show "Plonk stopped unexpectedly" with a growing countdown instead of a loop.
- **Popups.** `window.open` and `target=_blank` open the system browser, and only from a tap. A script can't open one by itself.
- **Navigation.** Only `https://play.plonk.land` (default port) stays in the app. `http://play.plonk.land` is upgraded to https. Every other site opens in the browser. `intent:` links are reduced to safe, browsable intents.
- **Bundled files** carry `X-Plonk-Bundle: <build>` (visible in devtools) and `X-Content-Type-Options: nosniff`. If the server adds a CSP or other security headers, the app must learn to send them too; see [BUNDLE.md](BUNDLE.md#how-it-works).

## Pending work

**Done (live since 2026-10-07).** The game page uses the bridge through `public/js/app-bridge.js` in the game repo: `window.plonkBack`, cold-start `?open=` / `?kol=` and the `plonknative` `open` event, the "Alert me when I'm away" toggle with `notify` on boss spawns, payouts and sales, invites and whispers, `share` on the invite button, `wallets` / `getWallet` before the MWA connect, and `haptic`. The server serves `GET /app/manifest` and `/.well-known/assetlinks.json` on `play.plonk.land` with the release cert. Every hook is a no-op outside the app.

Still open. None of it is needed for the app to work.

**Game page (web repo)**
1. Optional: `awake: false` in long menus or AFK. Show an "offline copy" note when `bundle` says `mode: 'offline'`. (The page deliberately leaves the `online` event unhandled, so the app reloads onto the live game.)
2. If the game adds a new static folder or file type, add it to `BundlePaths.kt` and `tools/fetch-bundle.mjs`.
3. Don't send `{ t: 'status' }` yet. `LoadController.serverUpdating()` exists, but no handler is wired to it.

**Game server and landing site**
1. Serve `/.well-known/assetlinks.json` on `plonk.land` too (the landing site), so referral links on that host verify as App Links.
2. During deploys and restarts, let the main document return 502/503/504 rather than a 200 maintenance page, so the app shows "Plonk is updating" and retries.
3. For alerts while the player is away: an authenticated feed such as `GET /app/alerts?since=<cursor>` that the app can poll with WorkManager (every 15 min at most, plus once right after the player leaves), or push. The app side is not built yet.
4. If security headers are ever added (CSP, `frame-ancestors`...), also list them in `/app/manifest` so the app can replay them, and update the app.
5. Optional: have the game socket send the client build, and ask old builds to reload. This guards stale tabs on the website and the app's offline snapshot alike.
