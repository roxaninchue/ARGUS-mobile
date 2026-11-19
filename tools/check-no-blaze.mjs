#!/usr/bin/env node
// Rename guard (docs/RENAME-ARGUS-PLAN.md, Phase 0). Counts "blaze" (any case) per file, after cutting out the permanent
// allowlist (tools/rename/keep-blaze.txt), and compares with the ratchet tools/rename/baseline.json.
//   node tools/check-no-blaze.mjs                    fails (exit 1) if a file gained a "blaze" or a new file has one
//   node tools/check-no-blaze.mjs --update-baseline  rewrites the baseline (run after a rename phase shrinks it)
//   node tools/check-no-blaze.mjs --strict           fails on ANY remaining non-allowlisted "blaze" (the end state)
// Counts only ever go down: a phase that removes names runs --update-baseline in the same commit.
import { readFileSync, writeFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import { join, relative, sep } from 'node:path';

const SKIP_DIRS = new Set(['.git', '.gradle', '.kotlin', 'build', 'node_modules', 'jniLibs', '.idea']);
// History and the rename tooling itself may say blaze.
export const SKIP = [/^CHANGELOG\.md$/, /^docs\/RENAME-ARGUS(-PLAN)?\.md$/, /^docs\/rename\//, /^tools\/rename\//,
  /^tools\/check-no-blaze(\.test)?\.mjs$/, /(^|\/)CHANGELOG\.md$/];
const BIN = /\.(png|jpe?g|gif|webp|ico|jar|so|apk|aab|keystore|p12|zip|gz|tar|ttf|otf|woff2?|mp3|mp4|ogg|wav|onnx|bin|pdf|db)$/i;

export function loadAllow(root) {
  const f = join(root, 'tools/rename/keep-blaze.txt');
  if (!existsSync(f)) return [];
  return readFileSync(f, 'utf8').split('\n').map(l => l.trim()).filter(l => l && !l.startsWith('#'))
    .map(l => new RegExp(l.split(/\t|  +# /)[0].trim(), 'g'));
}

export function countLine(line, allow) {
  let s = line;
  for (const r of allow) s = s.replace(r, ' ');
  return (s.match(/blaze/gi) || []).length;
}

function walk(dir, out) {
  for (const n of readdirSync(dir)) {
    if (SKIP_DIRS.has(n)) continue;
    const p = join(dir, n);
    let s; try { s = statSync(p); } catch { continue; }
    if (s.isDirectory()) walk(p, out); else if (!BIN.test(n) && s.size < 5_000_000) out.push(p);
  }
  return out;
}

export function scan(root, allow = loadAllow(root)) {
  const counts = {};
  for (const abs of walk(root, [])) {
    const f = relative(root, abs).split(sep).join('/');
    if (SKIP.some(r => r.test(f))) continue;
    const buf = readFileSync(abs);
    if (buf.includes(0)) continue; // binary
    let n = 0;
    for (const l of buf.toString('utf8').split('\n')) n += countLine(l, allow);
    // A file NAME with blaze counts once too (BlazeApp.kt, widget_blaze.xml, blaze-docs/…).
    if (/blaze/i.test(f)) n += 1;
    if (n) counts[f] = n;
  }
  return counts;
}

export function compare(base, now) {
  const grew = [];
  for (const [f, n] of Object.entries(now)) if (n > (base[f] || 0)) grew.push(`${f}: ${base[f] || 0} → ${n}`);
  const total = o => Object.values(o).reduce((a, b) => a + b, 0);
  return { grew, before: total(base), after: total(now), files: Object.keys(now).length };
}

const isMain = process.argv[1] && import.meta.url.endsWith(process.argv[1].split(sep).join('/').split('/').pop());
if (isMain) {
  const root = process.cwd();
  const bf = join(root, 'tools/rename/baseline.json');
  const now = scan(root);
  if (process.argv.includes('--update-baseline')) {
    const sorted = Object.fromEntries(Object.entries(now).sort(([a], [b]) => a.localeCompare(b)));
    writeFileSync(bf, JSON.stringify(sorted, null, 0).replace(/,"/g, ',\n"') + '\n');
    console.log(`baseline written: ${Object.keys(now).length} files, ${Object.values(now).reduce((a, b) => a + b, 0)} occurrences`);
    process.exit(0);
  }
  if (process.argv.includes('--strict')) {
    const left = Object.entries(now);
    left.slice(0, 50).forEach(([f, n]) => console.log(`${f}: ${n}`));
    console.log(`${left.length} files still say blaze outside the allowlist`);
    process.exit(left.length ? 1 : 0);
  }
  const base = existsSync(bf) ? JSON.parse(readFileSync(bf, 'utf8')) : {};
  const r = compare(base, now);
  console.log(`blaze outside the allowlist: ${r.after} occurrences in ${r.files} files (baseline ${r.before})`);
  if (r.after < r.before) console.log('Counts went down: run `node tools/check-no-blaze.mjs --update-baseline` and commit it.');
  if (r.grew.length) {
    console.log('New "blaze" (use Argus / BrandName.NAME, or add a permanent reason to tools/rename/keep-blaze.txt):');
    r.grew.slice(0, 100).forEach(l => console.log('  ' + l));
    process.exit(1);
  }
}
