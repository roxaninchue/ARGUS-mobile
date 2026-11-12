// ARGUS gateway client (OpenAI-compatible). Token from env ARGUS_TOKEN only; never logged or written.
// v2.13: the default model ("chain") is what the app does: fetch the gateway's live healthy list (GET MODELS.WIRE.LIVE),
// MODELS.plan() it (the first entry that fits, tools-verified for tool calls), keep the pick for the run, then Auto; the
// gateway fails over itself and names the model that answered (x-blaze-route), which the run then follows.
// Before v2.13 the default was a fixed chain, read from app/www/models.js (the same constant GW.CHAIN uses),
// with the same fallback rules (MODELS.onFail / MODELS.live): a member that errors or gives no answer (no text, no
// tool_calls) hands over to the next one; a member that failed with 5xx/429/network is skipped for 5 minutes, one the
// plan refuses (402/403, model_not_in_plan) for a day; Auto (no prefer header) is never skipped; no same-model retries
// (the app has none). Every fallthrough is recorded (fn.events) and reported, never silent. With opts.tools the call is
// native and returns {content, tool_calls, model, fellThrough, ...}; without, plain text.
// The gateway answers with model WIRE.AUTO and no header naming the upstream, so "answered by" is the chain member
// we asked for; a fallback response header > 0 (the gateway's own fallback) is recorded as a gateway-side swap.
// Header and model names come from MODELS.WIRE (app/www/models.js), the one place the gateway's wire names live.
import { createRequire } from 'node:module';
const MODELS = createRequire(import.meta.url)('../vendor/models.js');
const GUARD = createRequire(import.meta.url)('../vendor/guard.js');
const { AUTO, PREFER, RESP_PREFIX } = MODELS.WIRE;
// Deployed gateway hostname: renaming it needs a redeploy of the gateway worker (separate repo).
const BASE = process.env.ARGUS_GATEWAY || 'https://ai.blaze-app.workers.dev';

/** The app's chain as provider/model ids (WIRE.AUTO for Auto). */
export const APP_CHAIN = MODELS.CHAIN.map(m => m.pref || AUTO);
export { MODELS };
/** "chain" (the default) = the app's chain objects; a comma list = a custom chain (reported as not the app's). */
export function chainOf(spec) {
  if (!spec || spec === 'chain') return MODELS.CHAIN;
  return String(spec).split(',').map(s => s.trim()).filter(Boolean).map(id => {
    // v2.11: "infron:<model>" goes straight to Infron's OpenAI-compatible API (key from INFRON_API_KEY), not the gateway,
    // so a model the gateway would not route to can be measured on its own. Never part of the app's chain.
    const d = /^infron:(.+)$/.exec(id);
    if (d) return { key: id, name: d[1], prov: 'infron', pref: id, direct: d[1], base: process.env.INFRON_BASE || 'https://llm.onerouter.pro/v1', keyEnv: 'INFRON_API_KEY' };
    return { key: id, name: id, prov: id.split('/')[0], pref: id === AUTO || id === 'auto' ? null : id };   // 'auto' = Auto, same as WIRE.AUTO
  });
}
export const parseChain = spec => chainOf(spec).map(m => m.pref || AUTO);
export const isAppChain = spec => JSON.stringify(parseChain(spec)) === JSON.stringify(APP_CHAIN);

/** The gateway's live healthy models for this token, as MODELS.parseLive members ([] when it cannot be read). */
export async function liveModels() {
  const r = await fetch(BASE + MODELS.WIRE.LIVE, { headers: { authorization: 'Bearer ' + process.env.ARGUS_TOKEN } }).catch(() => null);
  if (!r || !r.ok) return [];
  return MODELS.parseLive(await r.json().catch(() => ({})));
}
export async function healthyModels() {
  const r = await fetch(BASE + '/v1/metrics'); const j = await r.json();
  return (j.ttft?.today_avg_upstream_ms || []).filter(m => m.ok > 0 && m.fail <= m.ok).map(m => `${m.provider}/${m.model}`);
}

async function post(m, body) {
  // direct providers: the key is in INFRON_API_KEY, or (in a sandbox whose proxy injects it on the wire) left out
  const dkey = m.direct ? String(process.env[m.keyEnv] || '') : '';
  const headers = { 'content-type': 'application/json' };
  if (!m.direct) headers.authorization = 'Bearer ' + process.env.ARGUS_TOKEN; else if (dkey && !/^proxy-/.test(dkey)) headers.authorization = 'Bearer ' + dkey;
  if (m.pref && !m.direct) headers[PREFER] = m.pref;
  if (m.direct) body = Object.assign({}, body, { model: m.direct });
  const t0 = Date.now();
  const r = await fetch(m.direct ? m.base + '/chat/completions' : BASE + '/v1/chat/completions', { method: 'POST', headers, body: JSON.stringify(body), signal: AbortSignal.timeout(120000) }).catch(e => ({ ok: false, status: 0, text: async () => String(e), headers: null }));
  const ms = Date.now() - t0, h = k => r.headers?.get?.(k) || '';
  const gw = { fallback: Number(h(RESP_PREFIX + 'fallback') || 0), route: h(RESP_PREFIX + 'route'), status: h(MODELS.WIRE.STATUS), plan: h(RESP_PREFIX + 'plan'), rules: h(RESP_PREFIX + 'rules') };
  if (gw.fallback > 0 && r.headers?.forEach) { const x = []; r.headers.forEach((v, k) => { if (k.startsWith(RESP_PREFIX) && /^(fallback|route|lane|fast-lane|task|ttf[bt]-ms|model|provider|upstream|served)/.test(k.slice(RESP_PREFIX.length))) x.push(k.slice(RESP_PREFIX.length) + '=' + v); }); gw.headers = x.join(' '); }
  if (!r.ok) return { ok: false, status: r.status, error: (await r.text()).slice(0, 300), ms, gw };
  const j = await r.json().catch(() => ({})), c = j.choices?.[0] || {}, msg = c.message || {};
  return { ok: true, status: r.status, ms, gw, usage: j.usage, content: msg.content || '', tool_calls: Array.isArray(msg.tool_calls) && msg.tool_calls.length ? msg.tool_calls : null, finish: c.finish_reason };
}

/** Preflight: one tiny call per chain member, to report unreachable / not-in-plan members up front (3 calls). */
export async function probeChain(spec) {
  if (!process.env.ARGUS_TOKEN) throw new Error('ARGUS_TOKEN is not set');
  const out = [];
  for (const m of chainOf(spec)) {
    const r = await post(m, { model: AUTO, stream: false, max_tokens: 64, messages: [{ role: 'user', content: 'Reply with the word OK.' }] });
    const d = r.ok ? null : MODELS.onFail({ status: r.status, error: r.error });
    out.push({ model: m.pref || AUTO, ok: r.ok && !!String(r.content).trim(), status: r.status, kind: r.ok ? (String(r.content).trim() ? 'ok' : 'empty') : d.kind, error: r.ok ? '' : r.error.slice(0, 160), gatewayFallback: r.gw.fallback, plan: r.gw.plan, rules: r.gw.rules, ms: r.ms });
  }
  return out;
}

/** model: "chain" (default, the app's), a comma list (custom chain) or one id. */
export function gatewayModel(model = 'chain', { maxTokens = 4000, temperature = 0 } = {}) {
  if (!process.env.ARGUS_TOKEN) throw new Error('ARGUS_TOKEN is not set. The gateway needs an ARGUS account token (Authorization: Bearer bz_…). Use --mock to run graders on golden transcripts.');
  const appChain = !model || model === 'chain';
  let chain = chainOf(model), list = null, keep = null; const down = {};
  const usage = { calls: 0, prompt: 0, completion: 0, ms: 0, errors: 0, steps: 0, byModel: {}, fellThrough: 0, gatewayFallback: 0, failures: {} };
  const events = [];   // every fallthrough / skip / gateway-side swap, in order
  const fn = async (messages, opts = {}) => {
    const tools = opts.tools, tried = [];
    usage.steps++;
    if (appChain) { if (!list) list = await liveModels(); chain = MODELS.plan(list, { tools: !!(tools && tools.length) }, keep); }
    // v2.11 fix: MODELS.live() filters the app's CHAIN, so before this a custom --model (a comma list or one id) still
    // ran the app's chain. Apply the same skip rule to this run's own chain.
    const now = Date.now(), live = chain.filter(m => !m.pref || !(down[m.key] > now) || chain.length === 1);
    for (const m of chain) if (m.pref && !live.includes(m)) tried.push({ model: m.pref, error: 'skipped (failed earlier in this run)', kind: 'skipped' });
    for (const m of live) {
      for (let withTools = !!(tools && tools.length), tries = 0; tries < 2; tries++) {
        // v2.15: same as the app: no gateway-side relevance pruning for the gateway (blaze_context "full"), and without
        // tools the native tool turns go as text (APIs reject role "tool" without tools)
        const body = { model: AUTO, messages: withTools ? messages : GUARD.flattenToolTurns(messages), max_tokens: maxTokens, temperature, stream: false };
        if (!m.direct) body.blaze_context = 'full';
        if (withTools) { body.tools = tools; body.tool_choice = 'auto'; }
        const r = await post(m, body); usage.ms += r.ms;
        const id = m.pref || AUTO;
        if (r.ok) { usage.calls++; usage.prompt += r.usage?.prompt_tokens || 0; usage.completion += r.usage?.completion_tokens || 0; }
        if (r.gw.fallback > 0) { usage.gatewayFallback++; events.push({ model: id, kind: 'gateway_fallback', step: usage.steps, ms: r.ms, detail: `${RESP_PREFIX}fallback=${r.gw.fallback}: the gateway reports its own fallback, so the upstream that answered may not be ${id} (it does not say which)`, headers: r.gw.headers || '' }); }
        if (r.ok && (String(r.content || '').trim() || r.tool_calls)) {
          if (appChain && m.pref) { const a = MODELS.answered({ [MODELS.WIRE.ROUTE]: r.gw.route, [MODELS.WIRE.STATUS]: r.gw.status, [MODELS.WIRE.FALLBACK]: String(r.gw.fallback) }, list); keep = a.model || m; if (a.fallback) list = null; }
          usage.byModel[id] = (usage.byModel[id] || 0) + 1; if (tried.some(t => t.kind !== 'skipped')) usage.fellThrough++;
          fn.last = { model: id, fellThrough: tried, finish: r.finish, gatewayFallback: r.gw.fallback };
          return tools ? { content: r.content, tool_calls: r.tool_calls, model: id, finish: r.finish, fellThrough: tried, gatewayFallback: r.gw.fallback } : r.content;
        }
        if (!r.ok) usage.errors++;
        const d = MODELS.onFail({ status: r.status, ok: r.ok, error: r.error, withTools });
        if (d.fatal) throw new Error(`gateway refused the token (${r.status})`);
        if (d.retryNoTools) { events.push({ model: id, kind: 'no_tools', detail: r.error.slice(0, 160) }); withTools = false; continue; }
        const why = r.ok ? 'empty answer' + (r.finish ? ` (finish ${r.finish})` : '') : `HTTP ${r.status}: ${r.error.slice(0, 160)}`;
        tried.push({ model: id, error: why, kind: d.kind });
        usage.failures[`${id} ${d.kind}`] = (usage.failures[`${id} ${d.kind}`] || 0) + 1;
        events.push({ model: id, kind: d.kind, detail: why, skippedFor: d.skipMs ? Math.round(d.skipMs / 60e3) + ' min' : '' });
        if (d.skipMs && m.pref && !appChain) down[m.key] = Date.now() + d.skipMs;   // the app chain keeps no timers (gateway fails over)
        if (appChain && m.pref) { keep = null; list = null; }
        if (d.kind === 'model_not_in_plan' || d.kind === 'plan' || d.kind === 'unreachable') console.error(`! chain member ${id}: ${d.kind} (${why}); falling to the next member as the app does`);
        break;
      }
    }
    fn.last = { model: null, fellThrough: tried };
    throw new Error('no model in the chain answered: ' + tried.map(t => `${t.model}: ${t.error}`).join(' | ').slice(0, 400));
  };
  fn.usage = usage; fn.chain = chain.map(m => m.pref || AUTO); fn.events = events;
  return fn;
}

/** Optional LLM judge (secondary signal only). Returns {verdict, reason} or null. */
export async function judge(model, rubric, transcriptText) {
  const m = gatewayModel(model, { maxTokens: 400 });
  const out = await m([{ role: 'system', content: 'You grade AI assistant replies strictly. Answer with one line of JSON: {"pass":true|false,"reason":"..."}' },
    { role: 'user', content: `Rubric:\n${rubric}\n\nTranscript:\n${transcriptText.slice(0, 6000)}` }]);
  try { const j = JSON.parse((/\{[\s\S]*\}/.exec(out) || ['{}'])[0]); return { pass: !!j.pass, reason: String(j.reason || '').slice(0, 300) }; } catch { return null; }
}
