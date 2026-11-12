#!/usr/bin/env node
// P0-QA-01: builds docs/overhaul/specs.md, the spec index. Every A-team Playwright/Java PASS line (app/tests/*), every
// A-team evals/test case and every Bot HQ e2e step (tests/e2e.mjs, tests/voice-call.mjs) becomes one row with the
// ticket that turns it into a JUnit / Robolectric / Compose test. Log-only scripts (no PASS lines) give one row per
// labelled console.log. Mapping: per-file rules, then keyword rules on the spec text, then the file's default; the
// "by" column says which. Deterministic (sorted, no timestamps).
//
//   node tools/gen-specs.mjs <a-team checkout> <bot-hq checkout> > docs/overhaul/specs.md
// a-team = gitlab blazeinchue/a-team (88676660); bot-hq = github nickvasilescu/bot-hq (only tests/ is read).
import fs from 'node:fs'; import path from 'node:path';
function callsOf(src, fn) {
  const out = []; const re = new RegExp('(^|[^A-Za-z0-9_.$])' + fn + '\\(', 'g'); let m;
  while ((m = re.exec(src))) {
    let i = m.index + m[0].length, depth = 1, args = [], cur = '', q = null;
    for (; i < src.length && depth > 0; i++) {
      const c = src[i];
      if (q) { cur += c; if (c === '\\') { cur += src[++i]; continue; } if (c === q) q = null; continue; }
      if (c === '"' || c === "'" || c === '`') { q = c; cur += c; continue; }
      if (c === '/' && /(^|[(,=:[!&|?{};>])\s*$/.test(cur.slice(-20) || '(') && src[i + 1] !== '/' && src[i + 1] !== '*') {   // a regex literal
        let cls = false; cur += c; i++;
        for (; i < src.length; i++) { const d = src[i]; cur += d; if (d === '\\') { cur += src[++i]; continue; } if (d === '[') cls = true; else if (d === ']') cls = false; else if (d === '/' && !cls) break; }
        continue;
      }
      if ('([{'.includes(c)) depth++; if (')]}'.includes(c)) depth--;
      if (depth === 0) break;
      if (c === ',' && depth === 1) { args.push(cur.trim()); cur = ''; continue; }
      cur += c;
    }
    args.push(cur.trim());
    out.push({ line: src.slice(0, m.index).split('\n').length, args });
  }
  return out;
}
const lit = s => { if (!s) return ''; s = s.replace(/(^|[(,=:[!&|?{};>]\s*)\/(?:\\.|\[(?:\\.|[^\]])*\]|[^/\\\n])+\/[a-z]*/g, '$1'); const parts = s.match(/'(?:\\.|[^'])*'|"(?:\\.|[^"])*"|`(?:\\.|[^`])*`/g); return parts ? parts.map(p => p.slice(1, -1).replace(/\$\{[^}]*\}/g, '…')).join(' ').replace(/\s+/g, ' ').trim() : '(check named at run time: ' + s.replace(/\s+/g, ' ').slice(0, 60) + ')'; };
const FILE_FIRST = [
  [/^evals\/test\/graders/, 'P0-QA-01'],
  [/^evals\/test\/v212/, 'P0-API-01'],
  [/^app\/tests\/models-workspace/, 'P0-API-01'],
  [/^app\/tests\/PtyArgvTest/, 'P7-CP-03'],
  [/^app\/tests\/review/, 'P7-CP-06'],
  [/^app\/tests\/icons/, 'P0-SH-02'],
];
const RULES = [
  [/codespace|lease|cloud tool|provider picker|quota|orgo|hard cap|orphan/i, 'P7-CP-08'],
  [/\bbell\b|inbox/i, 'P2-SF-03'],
  [/\bundo\b/i, 'P3-AG-05'],
  [/image|multimodal|jpeg|png|vision/i, 'P7-CP-04'],
  [/refus|must-refuse|harmful|precheck|look-alike|over-refus|self-harm|weapon|malware/i, 'P2-QA-01'],
  [/inject|taint/i, 'P2-QA-01'],
  [/\bmodels?\b|chain|prefer|fallback|live list|\bwire\b|metrics|onFail|plan refus|model_not_in_plan|blaze-auto/i, 'P0-API-01'],
  [/\bsudo|destructive|fork ?bomb|rm -rf|checkRun|completeness|unbacked|claim|fence|loop guard|repeat|fake|no-op|step cap|done_job|guard/i, 'P2-SF-07'],
  [/approv|allow|permission|confirm|ask first|never without/i, 'P2-SF-02'],
  [/\bstop\b|abort|cancel/i, 'P2-SF-06'],
  [/notif|\bping|badge/i, 'P2-SF-08'],
  [/\bsoul\b/i, 'P3-AG-03'],
  [/profile|stated|inferred|what .*knows/i, 'P3-SH-02'],
  [/memor|remember|recall|forget|\bfacts?\b/i, 'P3-AG-05'],
  [/\bmcp\b/i, 'P4-CN-06'],
  [/\bskills?\b/i, 'P4-CN-05'],
  [/oauth|connect apps|connector|catalog|gmail|calendar|plugin|brand logo/i, 'P4-CN-02'],
  [/voice|audio|caption|greeting|\bcall\b|mic\b|speak/i, 'P8-VC-01'],
  [/group|@mention|mention|\broom\b|members reply/i, 'P5-TM-02'],
  [/delegat|send_to_bot|hand ?off|handoff|manager asks|report back/i, 'P5-TM-01'],
  [/computer|viewer|take control|hand back|live screen|screens?\b/i, 'P7-CP-09'],
  [/browser|web ?view|site/i, 'P7-CP-07'],
  [/library/i, 'P7-CP-05'],
  [/write_file|upload|attachment|\bfiles?\b|download/i, 'P7-CP-04'],
  [/ubuntu|proot|distro|workspace (setup|ready|install)|install/i, 'P7-CP-01'],
  [/terminal|\btabs?\b|extra.?keys|tmux|\bpty\b|shell|\bterm\b|\$ |command/i, 'P7-CP-03'],
  [/review|secret scan|shell=True|eval\(/i, 'P7-CP-06'],
  [/routine|cron|check-?in|schedul|remind|due\b|overdue|recurr/i, 'P6-PR-01'],
  [/export|import|backup|move to argus/i, 'P1-MG-01'],
  [/sync|push|pull|server agents|rev\b|tomb/i, 'P8-API-02'],
  [/sign.?in|sign.?out|sign.?up|\bgate\b|mfa|password|forgot|\bcode\b.*(bad|wrong)|emailed/i, 'P1-SH-06'],
  [/vault|api key|\bkeys?\b|token/i, 'P4-CN-01'],
  [/icon|glyph|svg|emoji/i, 'P0-SH-02'],
  [/\borb\b|colou?r|theme|dark|light mode|glass|font|serif|contrast|\blook\b|360|tap target|overflow|clip/i, 'P1-QA-01'],
  [/graph|nodes?\b|canvas/i, 'P5-SH-02'],
  [/setup|onboard|interview|first run|welcome/i, 'P5-SH-01'],
  [/settings/i, 'P1-SH-06'],
  [/search|palette/i, 'P1-SH-01'],
  [/\bjobs?\b|\btasks?\b|queue|add_job|waits on|blocked/i, 'P1-TM-05'],
  [/new bot|create bot|template|delete|rename|\bbots?\b|\bagents?\b|role/i, 'P1-TM-04'],
  [/manager/i, 'P1-TM-06'],
  [/markdown|stream|bubble|thread|reply|replies|chat|composer|send/i, 'P1-SH-03'],
  [/home|chips?|pins?\b|rows?\b|header|needs? you|sidebar/i, 'P1-TM-03'],
];
const FILE_DEFAULT = [
  [/^evals\/test\//, 'P2-SF-07'],
  [/chat\.test|v2\d+b?c?d?\.test\.js|v27|v29\.test\.js/, 'P1-SH-03'],
  [/jobs\.test/, 'P1-TM-05'], [/polish/, 'P1-QA-01'], [/setup\.test/, 'P5-SH-01'], [/terminal\.test/, 'P7-CP-03'],
  [/vault/, 'P4-CN-01'], [/signin/, 'P1-SH-06'], [/sync\.test/, 'P8-API-02'], [/ui\.test/, 'P1-TM-03'],
  [/icons\.test/, 'P0-SH-02'], [/WorkspaceTest/, 'P7-CP-01'], [/e2e\.mjs/, 'P1-SH-01'], [/voice-call/, 'P8-VC-01'],
];
function ticketFor(it) {
  for (const [re, t] of FILE_FIRST) if (re.test(it.file)) return [t, 'file'];
  for (const [re, t] of RULES) if (re.test(it.name)) return [t, 'text'];
  for (const [re, t] of FILE_DEFAULT) if (re.test(it.file)) return [t, 'default'];
  return [null, 'none'];
}
function collect(atDir, bhqDir) {
  const items = [];
for (const f of fs.readdirSync(path.join(atDir, 'app/tests')).filter(f => f.endsWith('.js')).sort()) {
  const src = fs.readFileSync(path.join(atDir, 'app/tests', f), 'utf8');
  for (const c of callsOf(src, 'ok')) if (c.args.length >= 2) items.push({ src: 'AT-PW', file: 'app/tests/' + f, line: c.line, name: lit(c.args[1]) });
  for (const c of callsOf(src, 'check')) if (c.args.length >= 2) items.push({ src: 'AT-PW', file: 'app/tests/' + f, line: c.line, name: /^['"`]/.test(c.args[0]) ? lit(c.args[0]) : lit(c.args[1]) });
}
// log-only Playwright scripts (no PASS lines): each labelled console.log is one check.
for (const f of fs.readdirSync(path.join(atDir, 'app/tests')).filter(f => f.endsWith('.js')).sort()) {
  if (items.some(i => i.file === 'app/tests/' + f)) continue;
  const src = fs.readFileSync(path.join(atDir, 'app/tests', f), 'utf8');
  for (const c of callsOf(src, 'console.log')) { const n = lit(c.args[0]).replace(/[:|]+$/, '').trim(); if (n && !/^(errs|ERRORS|FAILS)$/i.test(n)) items.push({ src: 'AT-PW', file: 'app/tests/' + f, line: c.line, name: n + ' (logged check)' }); }
}
for (const f of ['PtyArgvTest.java', 'WorkspaceTest.java']) {
  const src = fs.readFileSync(path.join(atDir, 'app/tests', f), 'utf8');
  for (const c of callsOf(src, 'ok')) if (c.args.length >= 2 && !/String what/.test(c.args[1])) items.push({ src: 'AT-JV', file: 'app/tests/' + f, line: c.line, name: lit(c.args[1]) });
  for (const c of callsOf(src, 'check')) if (c.args.length >= 2) items.push({ src: 'AT-JV', file: 'app/tests/' + f, line: c.line, name: lit(c.args[c.args.length-1]) });
  if (!items.some(i => i.file === 'app/tests/' + f)) for (const c of callsOf(src, 'System.out.println')) { const n = lit(c.args[0]).replace(/[:|]+$/, '').trim(); if (n) items.push({ src: 'AT-JV', file: 'app/tests/' + f, line: c.line, name: n + ' (logged check)' }); }
}
for (const f of fs.readdirSync(path.join(atDir, 'evals/test')).filter(f => f.endsWith('.mjs')).sort()) {
  const src = fs.readFileSync(path.join(atDir, 'evals/test', f), 'utf8');
  for (const c of callsOf(src, 'test')) items.push({ src: 'AT-EV', file: 'evals/test/' + f, line: c.line, name: lit(c.args[0]) });
}
for (const f of ['e2e.mjs', 'voice-call.mjs']) {
  const src = fs.readFileSync(path.join(bhqDir, f), 'utf8');
  for (const c of callsOf(src, 'step')) if (c.args.length >= 2) items.push({ src: 'BH-E2E', file: 'tests/' + f, line: c.line, name: lit(c.args[0]) });
  if (f === 'voice-call.mjs') for (const c of callsOf(src, 'log')) { const n = lit(c.args[0]).replace(/[:|]+$/, '').trim(); if (n && !/^console\.|^pageerror/.test(n)) items.push({ src: 'BH-E2E', file: 'tests/' + f, line: c.line, name: n + ' (logged check)' }); }
}
  return items;
}

const [atDir, bhqDir] = process.argv.slice(2);
if (!atDir || !bhqDir) { console.error('usage: node tools/gen-specs.mjs <a-team dir> <bot-hq dir>'); process.exit(2); }
const items = collect(atDir, bhqDir).map(i => { const [t, by] = ticketFor(i); return { ...i, ticket: t, by }; });
const missing = items.filter(i => !i.ticket);
if (missing.length) { console.error('no ticket for:', missing.map(i => i.file + ':' + i.line).join(', ')); process.exit(1); }
const esc = s => String(s).replace(/\|/g, '\\|').replace(/</g, '&lt;').slice(0, 160);
const SRC = { 'AT-PW': 'A-team Playwright', 'AT-JV': 'A-team Java', 'AT-EV': 'A-team evals/test', 'BH-E2E': 'Bot HQ e2e' };
const out = [];
out.push('# Spec index (P0-QA-01)', '',
  '> Generated by `node tools/gen-specs.mjs <a-team> <bot-hq>`; do not edit rows by hand, change the rules in the script.',
  '> Every behaviour the old apps test is listed once with the ticket that ports it as a JUnit / Robolectric / Compose test',
  '> (06 §8 principle: tests are behaviour specs). Mapping is a first pass (`by`: file rule, text keyword or file default);',
  '> when a ticket starts, its owner reads its rows here, moves any that belong elsewhere by fixing the rules, and lists',
  '> the test that covers each row in the ticket\'s commit.', '',
  `Sources: a-team \`app/tests/*\` + \`evals/test/*\`, bot-hq \`tests/e2e.mjs\` + \`tests/voice-call.mjs\`. **${items.length} specs, every one with a ticket.**`, '');
const per = {}; for (const i of items) per[i.ticket] = (per[i.ticket] || 0) + 1;
out.push('## Specs per ticket', '', '| Ticket | Specs |', '|---|---|');
for (const t of Object.keys(per).sort()) out.push(`| ${t} | ${per[t]} |`);
const files = [...new Set(items.map(i => i.file))];
for (const f of files) {
  const rows = items.filter(i => i.file === f);
  out.push('', `## ${SRC[rows[0].src]} · \`${f}\` (${rows.length})`, '', '| # | Line | Spec | Ticket | By |', '|---|---|---|---|---|');
  rows.forEach((i, n) => out.push(`| ${n + 1} | ${i.line} | ${esc(i.name) || '(unnamed check)'} | ${i.ticket} | ${i.by} |`));
}
process.stdout.write(out.join('\n') + '\n');
