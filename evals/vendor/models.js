/* models.js (v2.13): which model to ask, from the gateway's live list of healthy models, and the fallback rules.
 * Shared by the app (window.MODELS, read by GW in app.js and the chat in chat.js) and the eval harness
 * (evals/lib/gateway.mjs requires this file). v2.13 drops the fixed Groq > Cerebras chain: the app fetches
 * GET /v1/models/live (gateway 0.9.2+, the caller's plan, probe-verified in the last ~6 h, fastest first, never
 * balance-billed Infron models), picks the first entry that fits the task, keeps that pick for the whole turn, and sends
 * it in WIRE.PREFER. If the pick fails, the gateway itself answers with the next healthy model in the lane and says so in
 * the response headers (WIRE.STATUS / WIRE.ROUTE / WIRE.FALLBACK). The app only falls back to Auto (no pick) when
 * the whole lane failed, and keeps no cool-down timers. No model ids are typed anywhere in the app. */
(function (root, factory) {
  const M = factory();
  if (typeof module === 'object' && module.exports) module.exports = M;
  else root.MODELS = M;
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict';
  /** Auto: no pick, the gateway's auto lane chooses. Always the last resort, and the whole chain when no live list is known. */
  const AUTO_M = Object.freeze({ key: 'auto', name: 'Auto', prov: 'ARGUS gateway', pref: null });
  const CHAIN = Object.freeze([AUTO_M]);
  /* Wire names the deployed gateway reads and writes (it lives in a separate repo and predates the ARGUS name). They
   * only change together with a gateway redeploy that accepts the old and new names, so they are defined here once
   * and every caller (app.js, chat.js, evals/lib/gateway.mjs) reads them from WIRE. */
  const WIRE = Object.freeze({ PREFER: 'X-Blaze-Prefer', AUTO: 'blaze-auto', RESP_PREFIX: 'x-blaze-',
    AVOID: 'X-Blaze-Avoid', LIVE: '/v1/models/live', STATUS: 'x-blaze-prefer-status', ROUTE: 'x-blaze-route', FALLBACK: 'x-blaze-fallback' });
  const LIVE_TTL_MS = 5 * 60e3, LIVE_MAX_AGE_MS = 6 * 3600e3, AVOID_MAX = 8;
  const SKIP_ERR_MS = 5 * 60e3, SKIP_PLAN_MS = 24 * 3600e3;
  function planErr(status, msg) { return status === 402 || status === 403 || /model_not_in_plan|\b(plan|upgrade|subscription|not (allowed|available|included)|paid|credits?|premium|tier)\b/i.test(String(msg || '')); }
  /** What to do after a model gave no usable answer before streaming started. status 0 = network; ok=true = empty answer.
   *  Returns {kind, next, skipMs, retryNoTools, fatal}. The caller tries the next model when next is true. */
  function onFail({ status = 0, ok = false, error = '', withTools = false } = {}) {
    if (status === 401) return { kind: 'auth', next: false, fatal: true, skipMs: 0 };
    if (withTools && status === 400 && /tool|function/i.test(String(error))) return { kind: 'no_tools', next: false, retryNoTools: true, skipMs: 0 };
    if (ok) return { kind: 'empty', next: true, skipMs: 0 };
    if (planErr(status, error)) return { kind: /model_not_in_plan/i.test(String(error)) ? 'model_not_in_plan' : 'plan', next: true, skipMs: SKIP_PLAN_MS };
    if (status === 0) return { kind: 'unreachable', next: true, skipMs: SKIP_ERR_MS };
    return { kind: 'http_' + status, next: true, skipMs: SKIP_ERR_MS };
  }
  const ACR = { gpt: 'GPT', oss: 'OSS', glm: 'GLM', ai: 'AI', it: 'IT', vl: 'VL', moe: 'MoE' };
  const PROV = { groq: 'Groq', cerebras: 'Cerebras', gemini: 'Gemini', openrouter: 'OpenRouter', infron: 'Infron', sambanova: 'SambaNova', cloudflare: 'Cloudflare', workers: 'Cloudflare', mistral: 'Mistral', nvidia: 'NVIDIA' };
  /** "openai/gpt-oss-120b:free" -> "GPT OSS 120b". */
  function prettyName(model) {
    const last = String(model || '').split('/').pop().replace(/:free$/i, '');
    return last.split(/[-_\s]+/).filter(Boolean).map(w => ACR[w.toLowerCase()] || (/^\d/.test(w) ? w : w[0].toUpperCase() + w.slice(1))).join(' ') || String(model || '');
  }
  const provName = p => PROV[String(p || '').toLowerCase()] || String(p || '').replace(/^./, c => c.toUpperCase());
  /** A capability the gateway's probe confirmed (verified as a list or an object), or the entry's own flag. */
  function has(e, cap) {
    const v = e && e.verified;
    if (Array.isArray(v)) return v.includes(cap);
    if (v && typeof v === 'object' && cap in v) return !!v[cap];
    return !!(e && e[cap]);
  }
  /** One /v1/models/live entry as a chain member: key = pref = "provider/model". */
  function member(e) {
    const id = String(e.id || (e.provider && e.model ? e.provider + '/' + e.model : ''));
    const cut = id.indexOf('/'), prov = e.provider || id.slice(0, cut), model = e.model || id.slice(cut + 1);
    return Object.freeze({ key: id, pref: id, name: prettyName(model), prov: provName(prov), model, route: e.route || '', lanes: Array.isArray(e.lanes) ? e.lanes.slice() : [],
      ms: +e.latency_ms || 0, success: e.success == null ? null : +e.success, tools: has(e, 'tools'), vision: has(e, 'vision') });
  }
  /** The live list from GET WIRE.LIVE, in the gateway's order (fastest first). Bad entries and Auto are dropped. */
  function parseLive(json) {
    const data = json && Array.isArray(json.data) ? json.data : [];
    const out = [], seen = new Set();
    for (const e of data) {
      if (!e) continue;
      const m = member(e);
      if (m.key.indexOf('/') < 1 || m.key === WIRE.AUTO || seen.has(m.key)) continue;
      seen.add(m.key); out.push(m);
    }
    return out;
  }
  /** What the call needs: tools (agent runs) wants a tools-verified model in the agent lane, images want vision. */
  const fits = (m, need) => (!need.tools || m.tools) && (!need.vision || m.vision);
  /** First live member for the task: lane + capabilities, then capabilities in any lane, then (no tools/vision need) anything. */
  function pick(list, { lane, tools = false, vision = false, avoid = [] } = {}) {
    lane = lane || (tools ? 'agent' : 'chat');
    const av = new Set(avoid || []), pool = (list || []).filter(m => !av.has(m.key));
    const need = { tools, vision };
    return pool.find(m => m.lanes.includes(lane) && fits(m, need)) || pool.find(m => fits(m, need)) || null;
  }
  /** The try order for one call: the turn's pick (kept while it is still listed and fits) or a fresh pick, then Auto. */
  function plan(list, need, keep) {
    need = need || {};
    const still = keep && keep.pref && (list || []).find(m => m.key === keep.key && fits(m, need));
    const first = still || pick(list, need);
    return first ? [first, AUTO_M] : [AUTO_M];
  }
  /** Which model answered, from the response headers (any case): {status, fallback, route, model}. model is the live member
   *  whose route matches WIRE.ROUTE, or null when the list does not know it. */
  function answered(headers, list) {
    const h = {};
    if (headers && typeof headers.forEach === 'function' && !Array.isArray(headers)) headers.forEach((v, k) => { h[String(k).toLowerCase()] = String(v); });
    else for (const k in (headers || {})) h[k.toLowerCase()] = String(headers[k]);
    const route = h[WIRE.ROUTE] || '', status = h[WIRE.STATUS] || '', fb = h[WIRE.FALLBACK];
    return { status, route, fallback: status === 'fallback' || fb === '1', model: (route && (list || []).find(m => m.route === route)) || null };
  }
  /** X-Blaze-Avoid value for models the turn already saw fail (max 8), or '' for none. */
  const avoidHeader = keys => Array.from(new Set((keys || []).filter(Boolean))).slice(0, AVOID_MAX).join(', ');
  return { CHAIN, AUTO: AUTO_M, WIRE, onFail, planErr, prettyName, parseLive, pick, plan, answered, avoidHeader, LIVE_TTL_MS, LIVE_MAX_AGE_MS, SKIP_ERR_MS, SKIP_PLAN_MS };
});
