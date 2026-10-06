#!/usr/bin/env node
// Fetch the live game's static files into the APK (app/src/main/assets/www/) and record the
// exact sha256 of every byte packed, in app/src/main/assets/bundled-manifest.json.
//
//   node tools/fetch-bundle.mjs                       # from https://play.plonk.land/app/manifest
//   node tools/fetch-bundle.mjs --origin <url>        # another server (staging, local)
//   node tools/fetch-bundle.mjs --from-dir <public>   # from a checkout of the game's public/ dir
//   node tools/fetch-bundle.mjs --status              # print what the current bundle holds
//   node tools/fetch-bundle.mjs --clean               # remove the bundle (app streams everything)
//
// The app only ever serves a bundled file when its hash still equals the live server's, so a
// stale bundle is never wrong, just less useful. Hashes recorded here are of the bytes actually
// written, even if the server changed a file mid-download. The new bundle is built in
// app/build/bundle-staging and swapped in only when complete, so a failed run keeps the old one.
// Design and server contract: docs/BUNDLE.md. Node 20+, no dependencies.

import { createHash } from 'node:crypto';
import { createReadStream } from 'node:fs';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const ASSETS = path.join(ROOT, 'app/src/main/assets');
const WWW = path.join(ASSETS, 'www');
const MANIFEST = path.join(ASSETS, 'bundled-manifest.json');
const STAGING = path.join(ROOT, 'app/build/bundle-staging');
const DEFAULT_ORIGIN = 'https://play.plonk.land';
const PARALLEL = 6;

// Same rules as BundlePaths.kt: change both together. Only these paths can ever be served from
// the APK, so nothing else is worth packing.
const DIRS = ['/js/', '/vendor/', '/css/', '/icons/', '/models/', '/sounds/', '/fx/', '/fonts/', '/images/', '/cards/'];
const ROOT_IMAGES = new Set(['png', 'webp', 'ico', 'svg', 'jpg', 'jpeg', 'gif', 'avif']);
const TYPES = new Set([
  'html', 'js', 'mjs', 'css', 'json', 'txt', 'wasm', 'glb', 'gltf', 'bin', 'ktx2', 'png', 'webp', 'avif', 'jpg',
  'jpeg', 'gif', 'svg', 'ico', 'mp3', 'ogg', 'opus', 'wav', 'm4a', 'woff2', 'woff', 'ttf', 'otf',
]);
const SHELL = '/index.html';

function servable(key) {
  if (key === SHELL) return true;
  if (typeof key !== 'string' || !key.startsWith('/')) return false;
  if (key.includes('/.') || key.includes('..') || key.includes('//') || key.includes('\\')) return false;
  const name = key.slice(key.lastIndexOf('/') + 1);
  const dot = name.lastIndexOf('.');
  if (dot <= 0 || dot === name.length - 1) return false;
  const ext = name.slice(dot + 1).toLowerCase();
  if (!TYPES.has(ext)) return false;
  if (DIRS.some((d) => key.startsWith(d))) return true;
  return key.lastIndexOf('/') === 0 && ROOT_IMAGES.has(ext);
}

// aapt2 silently drops some asset names (its default ignore pattern). Packing them would only
// make the app look for files that aren't there, so skip them up front.
function aaptKeeps(key) {
  const parts = key.split('/').filter(Boolean);
  const name = parts.at(-1);
  if (parts.some((p) => p.startsWith('.'))) return false;
  if (parts.slice(0, -1).some((p) => p.startsWith('_'))) return false;
  if (name.endsWith('~') || /^(thumbs\.db|picasa\.ini|cvs)$/i.test(name) || name.endsWith('.scc')) return false;
  return true;
}

function parseArgs(argv) {
  const opts = { origin: DEFAULT_ORIGIN, fromDir: null, status: false, clean: false };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--origin') opts.origin = argv[++i];
    else if (a === '--from-dir') opts.fromDir = argv[++i];
    else if (a === '--status') opts.status = true;
    else if (a === '--clean') opts.clean = true;
    else if (a === '-h' || a === '--help') {
      console.log('Usage: node tools/fetch-bundle.mjs [--origin <url> | --from-dir <public dir> | --status | --clean]');
      process.exit(0);
    } else throw new Error(`Unknown option ${a}`);
  }
  opts.origin = opts.origin.replace(/\/+$/, '');
  if (!/^https?:\/\//.test(opts.origin)) throw new Error(`--origin must be an http(s) URL, got ${opts.origin}`);
  return opts;
}

const mb = (n) => `${(n / 1048576).toFixed(1)} MB`;
const sha256 = (buf) => createHash('sha256').update(buf).digest('hex');

async function readJson(file) {
  try {
    return JSON.parse(await fs.readFile(file, 'utf8'));
  } catch {
    return null;
  }
}

async function hashFile(file) {
  const h = createHash('sha256');
  let size = 0;
  for await (const chunk of createReadStream(file)) {
    h.update(chunk);
    size += chunk.length;
  }
  return [size, h.digest('hex')];
}

// The file list to pack: { build, files: { key: [size, sha] } } from the live server...
async function liveManifest(origin) {
  const url = `${origin}/app/manifest`;
  const res = await fetch(url, { headers: { accept: 'application/json', 'user-agent': 'plonk-fetch-bundle' } });
  if (res.status === 404) {
    throw new Error(
      `${url} answered 404: this server has no bundle manifest yet (see docs/BUNDLE.md).\n` +
        'Build from a checkout instead (--from-dir <game>/public), or build without FETCH_BUNDLE.',
    );
  }
  if (!res.ok) throw new Error(`${url} answered HTTP ${res.status}`);
  const json = await res.json();
  if (json?.v !== 1 || typeof json.files !== 'object') throw new Error(`${url} is not a v1 bundle manifest`);
  return { build: json.build ?? null, files: json.files, source: url };
}

// ...or built from a local copy of the game's public/ directory (same shape, same hashing).
async function dirManifest(dir) {
  const root = path.resolve(dir);
  const files = {};
  async function walk(rel) {
    for (const ent of await fs.readdir(path.join(root, rel), { withFileTypes: true })) {
      if (ent.name.startsWith('.')) continue;
      const relPath = path.posix.join(rel, ent.name);
      if (ent.isDirectory()) await walk(relPath);
      else if (ent.isFile() && servable('/' + relPath)) files['/' + relPath] = await hashFile(path.join(root, relPath));
    }
  }
  await walk('');
  const build = sha256(JSON.stringify(Object.entries(files).sort())).slice(0, 12);
  return { build: `dir-${build}`, files, source: root };
}

// Download (or copy) one file into staging, reusing the previous bundle's copy when unchanged.
async function stageFile(key, expect, ctx) {
  const dest = path.join(STAGING, 'www', key);
  if (!dest.startsWith(path.join(STAGING, 'www') + path.sep)) throw new Error(`Refusing path ${key}`);
  await fs.mkdir(path.dirname(dest), { recursive: true });

  const prev = ctx.previous?.files?.[key];
  if (prev && prev[1] === expect[1]) {
    try {
      const [size, sha] = await hashFile(path.join(WWW, key));
      if (sha === expect[1]) {
        await fs.copyFile(path.join(WWW, key), dest);
        ctx.reused++;
        return [size, sha];
      }
    } catch {
      // fall through to a fresh copy
    }
  }

  let bytes;
  if (ctx.fromDir) {
    bytes = await fs.readFile(path.join(ctx.fromDir, key));
  } else {
    bytes = await download(`${ctx.origin}${encodeURI(key)}`);
  }
  await fs.writeFile(dest, bytes);
  const sha = sha256(bytes);
  if (sha !== expect[1]) ctx.changed.push(key);
  ctx.fetched++;
  return [bytes.length, sha];
}

async function download(url, tries = 3) {
  for (let attempt = 1; ; attempt++) {
    try {
      const res = await fetch(url, { headers: { 'user-agent': 'plonk-fetch-bundle' } });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      return Buffer.from(await res.arrayBuffer());
    } catch (e) {
      if (attempt >= tries) throw new Error(`${url}: ${e.message}`);
      await new Promise((r) => setTimeout(r, 500 * attempt));
    }
  }
}

async function build(opts) {
  const manifest = opts.fromDir ? await dirManifest(opts.fromDir) : await liveManifest(opts.origin);
  const keys = Object.keys(manifest.files)
    .filter((k) => servable(k) && aaptKeeps(k) && Array.isArray(manifest.files[k]))
    .sort();
  const skipped = Object.keys(manifest.files).length - keys.length;
  if (keys.length === 0) throw new Error(`${manifest.source} lists no files the app can serve`);

  await fs.rm(STAGING, { recursive: true, force: true });
  await fs.mkdir(path.join(STAGING, 'www'), { recursive: true });
  const ctx = {
    origin: opts.origin,
    fromDir: opts.fromDir && path.resolve(opts.fromDir),
    previous: await readJson(MANIFEST),
    reused: 0,
    fetched: 0,
    changed: [],
  };

  const out = {};
  let next = 0;
  let done = 0;
  async function worker() {
    while (next < keys.length) {
      const key = keys[next++];
      out[key] = await stageFile(key, manifest.files[key], ctx);
      if (++done % 100 === 0) process.stdout.write(`  ${done}/${keys.length}\n`);
    }
  }
  await Promise.all(Array.from({ length: PARALLEL }, worker));

  const files = Object.fromEntries(keys.map((k) => [k, out[k]]));
  const total = keys.reduce((n, k) => n + files[k][0], 0);
  await fs.writeFile(path.join(STAGING, 'bundled-manifest.json'), manifestJson(manifest, files));

  // Swap in the finished bundle.
  await fs.mkdir(ASSETS, { recursive: true });
  await fs.rm(WWW, { recursive: true, force: true });
  await fs.rename(path.join(STAGING, 'www'), WWW);
  await fs.rename(path.join(STAGING, 'bundled-manifest.json'), MANIFEST);
  await fs.rm(STAGING, { recursive: true, force: true });

  console.log(`bundle: ${keys.length} files, ${mb(total)}, build ${manifest.build ?? '?'} from ${manifest.source}`);
  console.log(`        ${ctx.fetched} fetched, ${ctx.reused} reused from the previous bundle, ${skipped} not servable`);
  if (ctx.changed.length) {
    console.log(`        ${ctx.changed.length} changed while fetching (packed as downloaded; the app checks hashes):`);
    for (const k of ctx.changed.slice(0, 10)) console.log(`          ${k}`);
  }
}

// Same shape as the server's manifest, one file per line so diffs and greps stay readable.
function manifestJson(manifest, files) {
  const head = { v: 1, build: manifest.build, source: manifest.source, createdAt: new Date().toISOString() };
  const lines = Object.keys(files).map((k) => `  ${JSON.stringify(k)}: ${JSON.stringify(files[k])}`);
  return `${JSON.stringify(head).slice(0, -1)},\n"files": {\n${lines.join(',\n')}\n}}\n`;
}

async function status() {
  const m = await readJson(MANIFEST);
  if (!m?.files) {
    console.log('bundle: empty (the app streams every file from the server)');
    return;
  }
  const keys = Object.keys(m.files);
  const total = keys.reduce((n, k) => n + (m.files[k]?.[0] ?? 0), 0);
  console.log(`bundle: ${keys.length} files, ${mb(total)}, build ${m.build ?? '?'} (${m.createdAt ?? 'unknown date'})`);
}

async function clean() {
  await fs.rm(WWW, { recursive: true, force: true });
  await fs.rm(MANIFEST, { force: true });
  console.log('bundle: removed');
}

try {
  const opts = parseArgs(process.argv.slice(2));
  if (opts.status) await status();
  else if (opts.clean) await clean();
  else await build(opts);
} catch (e) {
  console.error(`fetch-bundle: ${e.message}`);
  process.exit(1);
}
