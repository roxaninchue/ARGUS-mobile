// The agent loop under test. It mirrors app/www/chat.js (v2.6 as merged to main, b560e49d; v2.9 native tools): same system prompt tool section, same fenced
// tool blocks, same "Tool results (from the app, not the user):" feedback, same 6-step cap, sudo stripped like the
// app does. With guard=true it adds app/www/guard.js on top (normalize/validate, hints, loop stop, completeness gate,
// refusal pre/post checks, hardened prompt) so the same suite measures the lift from the system around the model.
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
export const GUARD = require('../vendor/guard.js');

export const MAX_STEPS = 6;

export function systemPrompt({ guard = false, native = false, today = '2026-11-09' } = {}) {
  let s = `You are Nova, engineering, an AI agent on the A team at ARGUS. Portfolio:\n(none)\nYour jobs:\n- Ship the release notes [this week]\n` +
    `You report to Ada. The team: Ada (lead), Kai (design).\nToday is ${today}. Answer briefly and concretely, like a colleague texting. Markdown is fine.`;
  s += (native ? `\n\nTOOLS. Call the tools as functions when you can. If function calling isn't available, put a fenced block on its own lines instead.` : `\n\nTOOLS. To act, put a fenced block on its own lines.`) + ` The app runs it, shows it as a card, sends you the result, and you continue the same reply. After a run or recall block, stop and wait for the result; never invent output.` +
    `\n\`\`\`run\nls -la ~\n\`\`\`\nRuns a bash script (multi-line is fine) in your workspace.` +
    `\n\`\`\`remember team\nZamtel's buyer is Mwila; prefers email.\n\`\`\`\nSaves a note. "remember me" is private to you; "remember team" is shared with every bot.` +
    `\n\`\`\`recall\nzamtel buyer\n\`\`\`\nSearches memory.\n\`\`\`forget\nthe note's text\n\`\`\`` +
      `\n\`\`\`add_job\nSend the Q4 deck\ndue: friday\nfor: Kai\npriority: high\n\`\`\`\nAdds a job and starts it for that bot (due, for, priority and waits lines are optional; "for" delegates it to another bot, "waits: <job title>" holds it until that job is done).\n\`\`\`done_job\nSend the Q4 deck\n\`\`\`\nMarks a job done.\n\`\`\`update_agent\nname: Manager\nrole: Team lead\n\`\`\`\nChanges your own name, role, email or portfolio (add "for: <name>" to change a bot that reports to you).` +
      `\nUse these only to act. A run block is only for real shell commands: never use one (or a comment inside one) to stand in for another action, and never claim you did something no tool did. To rename yourself or change your role, use update_agent. Code meant for the user goes in normal fences like \`\`\`python. Never put passwords, tokens or keys in memory.` +
    (native ? `\nThe write_file tool (function only, path + content) writes a whole text file and creates its folders. It takes up to ~60 KB of content per call; write a bigger file in parts (write_file the first part, then append the rest with run: cat >> file <<'EOF').` : '') +
    `\nExample:\nUser: what's in my home folder?\nYou: Let me look.\n\`\`\`run\nls ~\n\`\`\`\n(the app sends the output)\nYou: You have 3 files: a.txt, b.txt and notes.md.`;
  s += `\n\nENVIRONMENT. Your workspace is Ubuntu 24.04 running under proot inside the ARGUS app on the user's Android phone. You are root, so never use sudo. apt-get install, pip, git, curl, python3 and gcc work. Files the user attaches are in /root/uploads.` +
    ` If a command fails with an app or host-level error (for example "CANNOT LINK EXECUTABLE", a proot error, or "workspace not ready"), the workspace itself has an app problem: tell the user that and suggest updating the ARGUS app. Don't invent fixes (no sudo, no copying libraries into /data or /system).` +
    ` In this session the network is off.`;
  if (guard) s += '\n\n' + GUARD.PROMPT;
  return s;
}

/** In-memory app state for non-shell tools. */
export function newState() { return { memory: [], jobs: [{ title: 'Ship the release notes', done: false, for: 'Nova' }], agent: { name: 'Nova', role: 'engineering' }, log: [] }; }

function appTool(state, b) {
  const body = b.body.trim();
  switch (b.name) {
    case 'remember': if (!body) return '[remember] not saved: empty'; state.memory.push({ text: body, scope: /team/i.test(b.arg) ? 'team' : 'me' }); return `[remember] saved to ${/team/i.test(b.arg) ? 'team' : 'your private'} memory`;
    case 'recall': { const w = body.toLowerCase().split(/\W+/).filter(x => x.length > 2); const hits = state.memory.filter(m => w.some(x => m.text.toLowerCase().includes(x))); return '[recall] ' + (hits.length ? '\n' + hits.map(h => '- ' + h.text).join('\n') : 'no memories match'); }
    case 'forget': { const i = state.memory.findIndex(m => m.text.toLowerCase().includes(body.toLowerCase().slice(0, 30))); if (i < 0) return '[forget] nothing matched'; const [m] = state.memory.splice(i, 1); return '[forget] removed: ' + m.text; }
    case 'add_job': { const [title, ...rest] = body.split('\n'); const j = { title: title.trim(), done: false }; for (const l of rest) { const m = /^\s*(due|for|priority)\s*:\s*(.+)$/i.exec(l); if (m) j[m[1].toLowerCase()] = m[2].trim(); } if (!j.title) return '[add_job] missing title'; state.jobs.push(j); return `[add_job] added "${j.title}"`; }
    case 'done_job': { const j = state.jobs.find(x => !x.done && x.title.toLowerCase().includes(body.toLowerCase())) || state.jobs.find(x => !x.done && body.toLowerCase().includes(x.title.toLowerCase())); if (!j) return `[done_job] no job matches "${body}"`; j.done = true; return `[done_job] marked "${j.title}" done`; }
    case 'update_agent': { let n = 0; for (const l of body.split('\n')) { const m = /^\s*(name|role|email|portfolio|model)\s*:\s*(.+)$/i.exec(l); if (m) { state.agent[m[1].toLowerCase()] = m[2].trim(); n++; } } return n ? `[update_agent] updated ${n} field(s)` : '[update_agent] nothing to update'; }
    case 'note_team': state.memory.push({ text: body, scope: 'team' }); return '[note_team] saved';
  }
  return `[${b.name}] unknown tool`;
}
const stripSudo = s => String(s).replace(/(^|\n|[;&|]\s*|&&\s*|\|\|\s*)([ \t]*)sudo\s+(?:-[EHn]+\s+)*/g, '$1$2');
const cut = (s, n) => { s = String(s == null ? '' : s); return s.length > n ? s.slice(0, n) + `…[+${s.length - n}]` : s; };
const tail = (s, n) => { s = String(s == null ? '' : s); return s.length > n ? '…' + s.slice(-n) : s; };

/**
 * Run one task through the agent loop.
 * model: async (messages, {step, tools}) => replyText | {content, tool_calls, model}.   sandbox: Sandbox or null (no shell).
 * native: send OpenAI tools and execute tool_calls (fences stay the fallback, as in the app).
 * Returns a transcript {steps:[{reply, text, blocks:[{name,arg,body,res,code,blocked}], trace}], final, state, guardEvents}.
 */
export async function runAgent({ model, sandbox, user, guard = false, native = false, cmdTimeout = 30, maxSteps = MAX_STEPS }) {
  const state = newState(), messages = [{ role: 'system', content: systemPrompt({ guard, native }) }, { role: 'user', content: user }];
  const tr = { user, steps: [], final: '', state, guardEvents: [], refusedByGuard: false, native, maxSteps };
  if (guard) {
    const pre = GUARD.precheck(user);
    if (pre.action === 'refuse') { tr.refusedByGuard = true; tr.final = pre.reply; tr.steps.push({ reply: pre.reply, blocks: [], byGuard: true, trace: { step: 1, byGuard: 'precheck' } }); return tr; }
  }
  const tools = native ? GUARD.toolSchemas({ ws: !!sandbox }) : undefined;
  const sg = new GUARD.StepGuard({ maxSteps: 12, maxRepeats: 2 }), gate = new GUARD.CompletenessGate();
  let anyTool = false, claimAsked = false, fenceAsked = false;
  // v2.11: a step the app spends on its own nudge (gate, claim or fence check) does not use up the job's budget: each
  // nudge adds one step, at most BONUS_MAX per turn. Before this, a gate nudge on step 7 of 8 decided the result.
  const BONUS_MAX = 3; let budget = maxSteps, bonus = 0;
  const nudge = () => { if (bonus < BONUS_MAX) { bonus++; budget++; return true; } return false; };
  for (let step = 0; step < budget; step++) {
    const t0 = Date.now();
    const out = await model(messages, { step, tools });
    const o = typeof out === 'string' ? { content: out } : (out || {});
    let reply = o.content || '', calls = native ? (o.tool_calls || []) : [];
    const trace = { step: step + 1, model: o.model || model.last?.model || null, fellThrough: (o.fellThrough || model.last?.fellThrough || []).map(f => `${f.model}: ${f.error}`.slice(0, 160)), finish: o.finish || null, gatewayFallback: o.gatewayFallback ?? model.last?.gatewayFallback ?? 0, ms: Date.now() - t0, reply: cut(reply, 1500), tool_calls: calls.map(c => ({ id: c.id, name: c.function?.name, args: cut(c.function?.arguments, 1500) })), events: [] };
    if (guard) {
      const post = GUARD.postcheck(user, reply);
      if (post.action === 'replace') { tr.guardEvents.push({ step, post: post.category }); trace.events.push('postcheck: replaced'); reply = post.reply; calls = []; }
    }
    let rawReply = null;
    // v2.10: mirror chat.js: the app stops the stream when a run/recall/write_file fence closes, so text after it never
    // reaches the user or the history. Keep the raw reply in the trace.
    { const v0 = GUARD.validate(reply); const cutAt = GUARD.cutAfterFirstResultBlock(v0.text); if (cutAt != null && cutAt < v0.text.trim().length) { rawReply = reply; trace.rawReply = cut(reply, 1500); trace.events.push('cut after first result block (as the app does)'); reply = v0.text.slice(0, cutAt); } }
    let text = reply, fixes = [];
    if (guard) { const v = GUARD.validate(reply); text = v.text; fixes = v.fixes; if (fixes.length) { tr.guardEvents.push({ step, fixes }); trace.events.push('fixes: ' + fixes.join('; ')); } }
    // fenced blocks first (they stream first in the app), then native tool_calls
    const fence = GUARD.parse(text).filter(b => b.kind === 'tool');
    const nat = calls.map(c => Object.assign(GUARD.fromToolCall(c), { native: true, callId: c.id || 'call_' + step + '_' + Math.random().toString(36).slice(2, 8) }));
    const blocks = fence.concat(nat);
    const rec = { reply, rawReply, text, blocks: [], native: nat.length > 0, trace }; tr.steps.push(rec);
    messages.push(nat.length ? { role: 'assistant', content: reply || null, tool_calls: calls.map((c, i) => ({ id: nat[i].callId, type: 'function', function: { name: c.function?.name, arguments: typeof c.function?.arguments === 'string' ? c.function.arguments : JSON.stringify(c.function?.arguments || {}) } })) } : { role: 'assistant', content: reply });
    const results = [], toolMsgs = []; let stop = false;
    for (const b of blocks) {
      const r = { name: b.name, arg: b.arg, body: b.body, native: !!b.native };
      const done = res => { r.res = res; rec.blocks.push(r); (b.native ? toolMsgs.push({ role: 'tool', tool_call_id: b.callId, content: res }) : results.push(res)); };
      if (b.error) { r.code = 2; done(`[${b.name}] not run: ${b.error}`); continue; }
      if (b.name === 'run') {
        let script = stripSudo(b.body);
        if (guard) {
          const del = GUARD.deletesEdited(b.body, gate.edited);
          if (del.length) { r.blocked = [GUARD.DELETE_MSG(del)]; tr.guardEvents.push({ step, blocked: r.blocked }); done(`[run] not run: ${r.blocked[0]}`); continue; }
          const c = GUARD.checkRun(b.body);
          if (!c.ok) { r.blocked = c.errors; tr.guardEvents.push({ step, blocked: c.errors }); done(`[run] not run: ${c.errors.join('; ')}`); continue; }
          script = c.script;
          const s0 = sg.before(script); if (!s0.ok) { done('[run] not run: ' + s0.reason); stop = true; break; }
        }
        let res;
        if (!sandbox) res = { code: 127, out: 'There is no shell on this device.' };
        else res = sandbox.run(script, { timeout: cmdTimeout });
        r.code = res.code; r.out = res.out; r.timedOut = res.timedOut; anyTool = true;
        if (guard) { const lp = sg.after(script, res); gate.onRun(script, res); if (lp.stop) stop = true; done(GUARD.feedback(res, lp.hint)); }
        else { gate.onRun(script, res); done(`[run] exit ${res.code}${res.timedOut ? ' (timed out)' : ''}\n` + (res.out.length > 6000 ? '…' + res.out.slice(-6000) : res.out || '(no output)')); }
      } else if (b.name === 'write_file') {
        const w = GUARD.checkWrite(b.arg, b.body); r.arg = w.path || b.arg;
        if (!w.ok) { r.code = 1; done(`[write_file] not written: ${w.error}`); continue; }
        if (!sandbox) { r.code = 127; done('[write_file] not written: there is no workspace on this device'); continue; }
        const res = sandbox.run(GUARD.writeScript(w.path, b.body), { timeout: 30 });
        r.code = res.code; r.out = res.out; anyTool = true;
        if (res.code === 0) { gate.onWrite(w.path); done(`[write_file] wrote ${w.path} (${w.bytes} bytes)`); }
        else done(`[write_file] not written: ${res.out.trim().slice(-300)}`);
      } else if (b.name === 'attach' || b.name === 'save') { anyTool = true; done(`[${b.name}] ok (the eval has no phone: nothing was shown)`); }
      else { anyTool = true; done(appTool(state, b)); }
    }
    trace.blocks = rec.blocks.map(x => ({ name: x.name, native: x.native, arg: cut(x.arg, 200), body: cut(x.body, 800), code: x.code, out: tail(x.out, 600), res: tail(x.res, 600), blocked: x.blocked }));
    trace.gate = { edited: [...gate.edited], lastEdit: gate.lastEdit, lastVerify: gate.lastVerify, lastVerifyOk: gate.lastVerifyOk };
    const last = GUARD.parse(text).slice(-1)[0];
    const again = blocks.some(b => b.name === 'run' || b.name === 'recall' || b.name === 'write_file') || nat.length > 0 || (last && last.kind === 'tool');
    messages.push(...toolMsgs);
    if (results.length) messages.push({ role: 'user', content: 'Tool results (from the app, not the user):\n' + results.join('\n\n') });
    if (stop) { tr.stoppedByGuard = true; break; }
    if (!again) {
      if (guard) {
        const fin = gate.canFinish(text);
        if (!fin.ok && (step < budget - 1 || bonus < BONUS_MAX)) { nudge(); tr.guardEvents.push({ step, gate: true }); trace.events.push('gate: ' + cut(fin.message, 200)); messages.push({ role: 'user', content: fin.message }); continue; }
        // v2.11: a shell command in a ```bash fence, announced as run or with output stated: one bounce
        const sf = GUARD.unranShellFence(text);
        if (sf.flag && !fenceAsked && (step < budget - 1 || bonus < BONUS_MAX)) { nudge(); fenceAsked = true; tr.guardEvents.push({ step, fence: sf.cmd }); trace.events.push('fence: ' + sf.cmd); rec.hidden = true; messages.push({ role: 'user', content: GUARD.FENCE_MSG }); continue; }
        // v2.9: results claimed with no tool run in this turn get one check
        const cl = GUARD.unbackedClaims(text, { ran: anyTool });
        if (cl.flag && !claimAsked && (step < budget - 1 || bonus < BONUS_MAX)) { nudge(); claimAsked = true; tr.guardEvents.push({ step, claim: cl.reasons }); trace.events.push('claim: ' + cl.reasons.join(',') + ' · ' + cl.quote); rec.hidden = true; messages.push({ role: 'user', content: GUARD.CLAIM_MSG }); continue; }
        if (cl.flag) { tr.claimFlagged = true; trace.events.push('claim flagged (not verified)'); }
      }
      break;
    }
  }
  tr.maxSteps = budget; tr.bonusSteps = bonus;
  // final user-facing text = markdown of the last step (what the user reads last)
  const lastStep = tr.steps[tr.steps.length - 1];
  tr.final = lastStep ? GUARD.parse(lastStep.text || lastStep.reply).filter(b => b.kind === 'md').map(b => b.text).join('\n').trim() : '';
  tr.allText = tr.steps.filter(s => !s.hidden).map(s => s.reply).join('\n\n');
  return tr;
}
