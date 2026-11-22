#!/usr/bin/env node
// 0.55.0 lean harness: builds the tool / skill catalog the gateway serves at GET /v1/catalog (gateway 0.9.38) from this
// app's own sources, so the gateway's per-plan catalog_policy and the phone's blaze.db catalog name the same tools.
//   Tools:  every function the app declares (fn("name", "desc" …), put("name", "x")…put("description", "…")), with the
//           first sentence of its description and its ToolRouter group when the group lists it by name.
//   Skills: every bundled skill (app/src/main/assets/skills/*/SKILL.md front matter: name, description).
// Deterministic (sorted, no timestamps).
//   node tools/gen-catalog.mjs > ../Blaze-agent/gateway/src/catalog-data.json
import fs from 'node:fs'; import path from 'node:path';

const root = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');
const src = path.join(root, 'app/src/main/java');
const skillsDir = path.join(root, 'app/src/main/assets/skills');

function walk(d, out = []) {
  for (const n of fs.readdirSync(d)) { const p = path.join(d, n); const s = fs.statSync(p); if (s.isDirectory()) walk(p, out); else if (n.endsWith('.kt')) out.push(p); }
  return out;
}
const unq = s => s.replace(/\\"/g, '"').replace(/\\n/g, ' ').replace(/\$\{BrandName\.NAME\}|\$\{[^}]*\}/g, 'Argus').replace(/\s+/g, ' ').trim();
function summary(d) {
  const t = unq(d);
  const m = t.match(/^(.{20,200}?[.!?])(\s|$)/);
  return (m ? m[1] : t.slice(0, 160)).trim();
}
const NAME = '[a-z][a-z0-9_]{1,60}';
const STR = '"((?:[^"\\\\]|\\\\.)*)"';
const tools = new Map();
const add = (name, desc) => {
  if (!name || /^(type|object|string|function|array|integer|number|boolean)$/.test(name)) return;
  const prev = tools.get(name);
  const s = desc ? summary(desc) : '';
  if (!prev || (!prev.summary && s)) tools.set(name, { name, summary: s });
};
const groupOf = new Map();
const groupPrefix = [];
for (const f of walk(src)) {
  const t = fs.readFileSync(f, 'utf8');
  // fn("name", "description …   (the helper every tool file uses)
  for (const m of t.matchAll(new RegExp(`\\b(?:fn|def|tool|function|f|spec)\\(\\s*"(${NAME})"\\s*,\\s*${STR}`, 'g'))) {
    if (m[2].length >= 12) add(m[1], m[2]);
  }
  // put("name", "x") … put("description", "…")
  for (const m of t.matchAll(new RegExp(`put\\("name",\\s*"(${NAME})"\\)([\\s\\S]{0,400})`, 'g'))) {
    if (!/"type",\s*"function"|put\("function"|put\("description"/.test(t.slice(Math.max(0, m.index - 300), m.index + 500))) continue;
    const d = m[2].match(new RegExp(`put\\("description",\\s*${STR}`));
    add(m[1], d ? d[1] : '');
  }
  // put("name", CONST) with `const val CONST = "x"` in the same file
  const consts = new Map([...t.matchAll(/(?:const )?val ([A-Z_][A-Z0-9_]*)\s*=\s*"([a-z][a-z0-9_]{1,60})"/g)].map(m => [m[1], m[2]]));
  for (const m of t.matchAll(new RegExp(`put\\("name",\\s*([A-Za-z_.]*?)([A-Z_][A-Z0-9_]*)\\)([\\s\\S]{0,400})`, 'g'))) {
    const n = consts.get(m[2]); if (!n) continue;
    const d = m[3].match(new RegExp(`put\\("description",\\s*${STR}`));
    add(n, d ? d[1] : '');
  }
  // tool JSON written as a string: {"name":"x","description":"…"}
  for (const m of t.matchAll(/\\?"name\\?"\s*:\s*\\?"([a-z][a-z0-9_]{1,60})\\?"\s*,\s*\\?"description\\?"\s*:\s*\\?"((?:[^"\\]|\\.){12,}?)\\?"/g)) add(m[1], m[2]);
  // ToolGuide: "name" to "one-line guide"
  if (f.endsWith('agent/ToolGuide.kt')) for (const m of t.matchAll(new RegExp(`"(${NAME})" to ${STR}`, 'g'))) add(m[1], m[2]);
  if (f.endsWith('agent/ToolRouter.kt')) {
    // one chunk per Group(…): its id, then the names its lambda lists (`it in setOf(…)` / `it == "x"`), never the next group's
    for (const chunk of t.split(/\bGroup\(/).slice(1)) {
      const id = chunk.match(/^"([a-z]+)"/)?.[1]; if (!id) continue;
      for (const sw of (chunk.match(/\{ *it\.startsWith[^}]*\}/)?.[0] ?? '').matchAll(/startsWith\("([a-z_]+)"\)/g)) groupPrefix.push([sw[1], id]);
      const lam = chunk.match(/\{ *it (?:in setOf\(([^)]*)\)|== "([a-z_0-9]+)")/);
      if (!lam) continue;
      const names = lam[1] ? [...lam[1].matchAll(/"([a-z_0-9]+)"/g)].map(x => x[1]) : [lam[2]];
      for (const n of names) if (!groupOf.has(n)) groupOf.set(n, id);
    }
  }
}
const PREFIX = [[/^browser_/, 'browser'], [/^(file_|run_shell|run_code|terminal_|git$)/, 'terminal'], [/^(memory_|remember|recall_)/, 'memory'],
  [/^(agent_|agents_|add_job)/, 'team'], [/^(calendar_|create_routine|add_calendar)/, 'schedule'], [/^(set_alarm|set_timer|toggle_setting|set_volume|media_control)$/, 'device']];
const out = { tools: [], skills: [] };
for (const t of [...tools.values()].sort((a, b) => a.name < b.name ? -1 : 1)) {
  const g = groupOf.get(t.name) ?? groupPrefix.find(([p]) => t.name.startsWith(p))?.[1] ?? PREFIX.find(([re]) => re.test(t.name))?.[1] ?? '';
  out.tools.push({ name: t.name, group: g, summary: t.summary });
}
for (const d of fs.readdirSync(skillsDir).sort()) {
  const f = path.join(skillsDir, d, 'SKILL.md');
  if (!fs.existsSync(f)) continue;
  const fm = fs.readFileSync(f, 'utf8').match(/^---\n([\s\S]*?)\n---/);
  if (!fm) continue;
  const name = fm[1].match(/^name:\s*(.+)$/m)?.[1]?.trim() ?? d;
  const desc = fm[1].match(/^description:\s*(.+)$/m)?.[1]?.trim() ?? '';
  out.skills.push({ name, summary: summary(desc) });
}
process.stdout.write(JSON.stringify(out, null, 1) + '\n');
