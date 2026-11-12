#!/usr/bin/env node
// P0-IL-03: proves the do-not-port register (docs/overhaul/do-not-port.md) holds. No CI: run by hand,
//   node tools/check-do-not-port.mjs            (from the repo root)
// Each rule names a register row. A rule fails when its pattern matches a file's text (text) or a file's path (path)
// under the scanned roots. The register and this file must name the same rows. Exits 1 on any failure.
import { readFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import { join, relative, sep } from 'node:path';

const ROOT = process.argv[2] || process.cwd();
const ROOTS = ['app/src/main', 'tools', 'evals'];
// Credits and history may name what isn't ported; this file names every pattern.
const SKIP = [/^app\/src\/main\/assets\/legal\//, /^evals\/reports\//, /^tools\/check-do-not-port(\.test)?\.mjs$/];
const TEXT = /\.(kt|kts|java|js|mjs|ts|json|xml|md|html|css|sh|py|txt|toml|yaml|yml|properties)$/i;

export const RULES = [
  { id: 'BH-75', text: /composio/i, why: 'Composio Google sign-in (and Composio anywhere in the app, D5)' },
  { id: 'BH-76', text: /ALLOWED_EMAILS/, why: 'server allow-list' },
  { id: 'BH-77', text: /\/auth\/(dev|owner|link)\b|DEV_LOGIN|sign-in-link\.sh/, why: 'dev login, owner key, sign-in links' },
  { id: 'BH-78', text: /\b\w*_session\b.*[Cc]ookie|[Cc]ookie.*\b\w*_session\b|httpOnly/, why: 'cookie sessions' },
  { id: 'BH-86', text: /grok-?bot/i, why: 'Grokbot branding' },
  { id: 'BH-ART', path: /(shapes\.gen|BotAvatar|grok-?bot|artwork\/shapes)/i, text: /gen-shapes|shapes\.gen/, why: 'Bot HQ / Grok Bot avatar artwork' },
  { id: 'BH-DEAD-1', text: /createSubagent|subagents\.ts/, why: 'Bot HQ subagents.ts as-is' },
  { id: 'BH-DEAD-2', text: /memories\/consolidate|\/compact\b(?!ion)/, why: 'Bot HQ consolidate / compact stub routes' },
  { id: 'BH-DEAD-3', text: /\b(llm_items|pending_turns|memory_jobs|compactions)\b/, why: 'Bot HQ unused tables' },
  { id: 'AT-01', text: /addJavascriptInterface\([^)]*"Native"\)|window\.Native\b/, why: 'A-team WebView shell + Native bridge' },
  { id: 'AT-57', text: /\b(gmail_list|cal_list)\b|\.argus\/env/, why: 'A-team removed Connections / gmail shell helpers' },
  { id: 'AT-67', path: /(^|\/)build\.sh$/, text: /\baapt2 link\b|\bd8 --release\b|\bapksigner sign\b/, why: 'A-team no-Gradle build' },
  { id: 'AT-CACTUS', path: /(cactus\.(wasm|js)|needle-worker\.js)$/i, text: /cactus\.wasm|needle-worker/, why: 'A-team cactus.wasm WebView runtime' },
];

function walk(dir, out) {
  if (!existsSync(dir)) return out;
  for (const n of readdirSync(dir)) {
    const p = join(dir, n);
    const s = statSync(p);
    if (s.isDirectory()) { if (n !== 'node_modules' && n !== 'build') walk(p, out); } else out.push(p);
  }
  return out;
}

export function scan(root = ROOT) {
  const hits = [];
  const files = ROOTS.flatMap(r => walk(join(root, r), [])).map(f => relative(root, f).split(sep).join('/')).filter(f => !SKIP.some(s => s.test(f)));
  for (const f of files) {
    for (const r of RULES) {
      if (r.path && r.path.test(f)) hits.push(`${r.id} (${r.why}): file ${f}`);
      if (r.text && TEXT.test(f)) {
        const lines = readFileSync(join(root, f), 'utf8').split('\n');
        lines.forEach((l, i) => { if (r.text.test(l)) hits.push(`${r.id} (${r.why}): ${f}:${i + 1}: ${l.trim().slice(0, 120)}`); });
      }
    }
  }
  return { files: files.length, hits };
}

/** Row ids in the register table that carry a code check ("check: yes"). */
export function registerIds(root = ROOT) {
  const md = readFileSync(join(root, 'docs/overhaul/do-not-port.md'), 'utf8');
  return [...md.matchAll(/^\| (BH-[A-Z0-9-]+|AT-[A-Z0-9-]+) \|.*\| yes \|\s*$/gm)].map(m => m[1]);
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const { files, hits } = scan();
  const reg = new Set(registerIds()), ids = new Set(RULES.map(r => r.id));
  const drift = [...reg].filter(x => !ids.has(x)).map(x => `register row ${x} has no rule here`)
    .concat([...ids].filter(x => !reg.has(x)).map(x => `rule ${x} is not a checked row in do-not-port.md`));
  for (const l of drift.concat(hits)) console.log('FAIL ' + l);
  console.log(`${RULES.length} rules, ${files} files scanned, ${hits.length} hits, ${drift.length} register mismatches`);
  process.exit(hits.length || drift.length ? 1 : 0);
}
