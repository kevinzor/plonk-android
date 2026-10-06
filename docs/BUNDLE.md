# Bundled game files

The APK carries a copy of the game's static files: the page, its JavaScript, CSS, 3D models, icons, sounds and fonts. When the app opens, it serves each file from the APK if it is still identical to what the live server has, and fetches only what changed. A cold start therefore doesn't download megabytes of code and art, and it still works on weak signal. A deploy reaches the app on the next page load without an app update.

## How it works

The WebView still loads `https://play.plonk.land/?src=app`, the real origin. Cookies, local storage, the wallet adapter and the `PlonkNative` origin lock all behave the same as on the website. The app answers some requests itself through `WebViewClient.shouldInterceptRequest`, and through the `ServiceWorkerClient` for requests the game's service worker makes. The page cannot tell the difference, apart from an `X-Plonk-Bundle` response header that shows up in devtools.

```
page load ── GET /  ──► GameBundle picks a plan for this load
                          ├─ no network ............ offline: serve the whole bundle
                          ├─ GET /app/manifest ok .. live:    serve files whose hash matches
                          └─ 404 / slow / error .... network: serve nothing, like the website
                             (also: Wi-Fi that isn't validated yet, unless a manifest
                              from the last 15 s is at hand)
   then GET /js/main.js, /models/x.glb, ... ──► served by that plan, or passed to the network
```

The plan is chosen once per document, when the main frame requests it, so the shell and every file it loads follow the same rules. The three plans:

| Plan | When | What comes from the APK |
|---|---|---|
| `live` | The server's manifest answered (fetched at launch, reused for 15 s, refreshed with `If-None-Match` on each later page load). | Every bundled file whose sha256 equals the server's. Changed, new and unlisted files come from the network. |
| `offline` | The phone has no network at all. | The whole bundle, as one consistent snapshot. The game opens and shows its own reconnecting screen. When the network is back, the app reloads it onto the live game (see Limits). |
| `network` | Online, but no manifest: the endpoint is missing (404), slow (over 2.5 s), or broken. | Nothing. Every request goes to the network, exactly like the website. |

There is no plan that guesses. Without a fresh answer from the server, the app never mixes old bundled code with a newer page from the network. The last good manifest is cached on disk only so that the next check can be a 304.

**Only a new document changes the plan.**
- A main-frame navigation to any page other than the shell (an App Link to `/wiki/x`, say) runs under `network`.
- A document the service worker answers from its own cache never reaches the app, so the app can't tell which build it is. It also runs under `network` (the app notices it in `onPageStarted`).
- A `GET /` that is not a navigation (a page `fetch('/')`, a service-worker precache) goes to the network and leaves the running page's plan alone. A version poll therefore always sees the real server.
- A failed manifest fetch is remembered for 10 s, so Retry during an outage doesn't wait 2.5 s each time.

**What is never touched:** any request that is not a `GET`, other hosts, websockets, and every path outside the allow-list. The allow-list is `/` and `/index.html`, plus files under `/js/ /vendor/ /css/ /icons/ /models/ /sounds/ /fx/ /fonts/ /images/ /cards/`, plus images at the root, and only with a known file type. This keeps `/status`, `/auth`, `/api`, skins, `sw.js`, `plonk.apk` and anything generated on the network, whatever a manifest says. The rules live in `BundlePaths.kt` and are mirrored in `tools/fetch-bundle.mjs`.

**Speed:** a request costs a few map lookups. Hashes are compared once per manifest, when the plan is built, and the phone never hashes a file: the build tool records the hash of every byte it packs.

**Responses** use the server's headers for static files: the right `Content-Type` (`text/javascript`, `model/gltf-binary`, `image/webp`, `font/woff2`, `application/wasm` and so on), the exact `Content-Length`, and `Cache-Control: no-cache`. A single `Range: bytes=` request gets a `206`. Anything the app can't answer exactly (multiple ranges, a file missing from the APK) goes to the network.

**Debugging:** debug builds log the plan and a hit/miss summary per page load (`adb logcat -s Plonk`). Any build answers `{ t: 'bundle' }` on the bridge:

```js
PlonkNative.postMessage(JSON.stringify({ t: 'bundle' }));
// -> { t: 'bundle', mode: 'live', bundleBuild, liveBuild, bundled: 1040, served: 812, servedBytes, network: 14 }
```

## Building with a bundle

The bundle is generated at build time and is not committed (`app/src/main/assets/www/` and `app/src/main/assets/bundled-manifest.json` are gitignored). An APK built without a bundle works normally and streams everything.

```bash
FETCH_BUNDLE=1 scripts/build.sh release                              # from the live server's /app/manifest
FETCH_BUNDLE=1 BUNDLE_FROM=../Plonk/public scripts/build.sh release   # from a local checkout of the game
node tools/fetch-bundle.mjs --status                                  # what the current bundle holds
node tools/fetch-bundle.mjs --clean                                   # remove it
```

`tools/fetch-bundle.mjs` (Node 20, no dependencies) reads the manifest, downloads every servable file six at a time, and writes `bundled-manifest.json` with the size and sha256 of the bytes it actually wrote. A file that changed mid-download is packed as downloaded, and the app's hash check takes care of it. A rebuild reuses unchanged files from the previous bundle. The new bundle is built in `app/build/bundle-staging` and swapped in only when complete, so a failed run keeps the old one.

## Server contract: `GET /app/manifest`

The game server must provide this endpoint. Until it does, the app runs in `network` mode, which is the same as having no bundle.

```http
GET /app/manifest
If-None-Match: "3f9c0a1b2d4e"                  (optional)

200 OK
Content-Type: application/json; charset=utf-8
Cache-Control: no-cache
ETag: "3f9c0a1b2d4e"

{
  "v": 1,
  "build": "3f9c0a1b2d4e",
  "files": {
    "/index.html":     [273114, "9b1c…64 hex…"],
    "/js/main.js":     [894281, "e3b0…64 hex…"],
    "/models/orc.glb": [412880, "5d41…64 hex…"]
  }
}
```

- **`v`** is `1`. The app ignores any other version.
- **`files`** keys are URL paths: a leading `/`, decoded (a space is a space, not `%20`), and exactly as `express.static` serves them. `/index.html` is the file served for `/`.
- **Values** are `[size in bytes, lowercase hex sha256]` of the exact bytes `express.static` sends, before gzip.
- **`build`** is any short string that changes when any file changes, such as a hash of the `files` map. It doubles as the ETag. Answer `304` when `If-None-Match` matches.
- **It must never be stale.** If the manifest still shows an old hash after a deploy, the app could serve old bundled code with a newer page. Check file stats on every request, and rehash only the files whose size or mtime changed.
- **It must never block the event loop.** Hash with streams (`fs.createReadStream` into `crypto.createHash`), keep at most about four hashes running at once, and hash only on the first request or when a stat changes. While the first full hash is still running, answer `503`, and the app falls back to `network` for that load.
- **Exclude** what the app never serves, and what is big or private: `plonk.apk`, `sw.js`, `.well-known/`, `*-preview.html` and the probe pages, `wiki/`, `landing-img/`, and any dotfile. Listing extra files is harmless, because the app's allow-list ignores them, but it makes the response bigger.
- About 1,200 entries is about 110 KB of JSON, or about 55 KB gzipped. The app fetches it once per launch, and after that usually gets a 304.

A reference implementation for the Express server (`src/http/app-manifest.js`):

```js
import { createHash } from 'node:crypto';
import { createReadStream } from 'node:fs';
import fs from 'node:fs/promises';
import path from 'node:path';

const EXCLUDE = /^\/(plonk\.apk|sw\.js|\.well-known\/|wiki\/|landing-img\/)|-preview\.html$|probe\.html$|\/\./;

export function appManifest(publicDir) {
  const cache = new Map(); // relPath -> { key: `${size}:${mtimeMs}`, sha }
  let ready = false;
  let running = null;

  async function list(dir = '') {
    const out = [];
    for (const e of await fs.readdir(path.join(publicDir, dir), { withFileTypes: true })) {
      const rel = path.posix.join(dir, e.name);
      if (EXCLUDE.test('/' + rel + (e.isDirectory() ? '/' : ''))) continue;
      if (e.isDirectory()) out.push(...(await list(rel)));
      else if (e.isFile()) out.push(rel);
    }
    return out;
  }

  const hash = (file) =>
    new Promise((resolve, reject) => {
      const h = createHash('sha256');
      createReadStream(file).on('data', (c) => h.update(c)).on('end', () => resolve(h.digest('hex'))).on('error', reject);
    });

  async function build() {
    const files = {};
    const todo = [];
    for (const rel of await list()) {
      const st = await fs.stat(path.join(publicDir, rel));
      const key = `${st.size}:${st.mtimeMs}`;
      const hit = cache.get(rel);
      if (hit?.key === key) files['/' + rel] = [st.size, hit.sha];
      else todo.push({ rel, key, size: st.size });
    }
    for (let i = 0; i < todo.length; i += 4) {
      await Promise.all(todo.slice(i, i + 4).map(async (t) => {
        const sha = await hash(path.join(publicDir, t.rel));
        cache.set(t.rel, { key: t.key, sha });
        files['/' + t.rel] = [t.size, sha];
      }));
    }
    const sorted = Object.fromEntries(Object.entries(files).sort(([a], [b]) => (a < b ? -1 : 1)));
    const build = createHash('sha256').update(JSON.stringify(sorted)).digest('hex').slice(0, 12);
    ready = true;
    return { v: 1, build, files: sorted };
  }

  // One build at a time; concurrent requests share it. Each request re-stats, so it is never stale.
  const current = () => (running ??= build().finally(() => { running = null; }));
  current().catch(() => {}); // warm up at boot

  return async (req, res) => {
    if (!ready) { current().catch(() => {}); return res.status(503).set('Retry-After', '5').end(); }
    try {
      const m = await current();
      res.set({ 'Cache-Control': 'no-cache', ETag: `"${m.build}"` });
      if (req.headers['if-none-match'] === `"${m.build}"`) return res.status(304).end();
      res.json(m);
    } catch {
      res.status(503).end();
    }
  };
}

// src/index.js, before express.static:
// app.get('/app/manifest', appManifest(PUBLIC));
```

## Limits

- The bundle adds its size to the APK: about 40 MB of files for the current game, mostly models and icons.
- On a phone that has no network at all, the bundle can be older than the live game. The offline snapshot is only for opening the app. When the network comes back (validated), the app tells the page with a cancelable `plonknative` event `{ t: 'online', mode: 'offline' }`. If no listener calls `preventDefault()`, the app reloads the page, so the `live` plan takes over and old bundled code never keeps talking to a newer server. See [APP_BRIDGE.md](APP_BRIDGE.md).
- Requests from other hosts (wallet sites, Jupiter, CDNs) are never bundled.
