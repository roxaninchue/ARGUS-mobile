// Argus account tokens. argus-auth issues opaque session tokens ("bz_" + 32 random bytes,
// base64url) and stores only base64url(SHA-256(token)) under sess:<hash> in its KV, with the
// user's record at user:<id> listing its live session hashes (see Blaze-dekstop cloud/src/accounts.ts).
// The gateway checks a token the same way, in order of preference:
//   1. AUTH_KV bound (argus-auth's namespace): read sess:<hash> + user:<id> directly (2 KV reads);
//   2. AUTH service binding: GET /v1/me on argus-auth;
//   3. ARGUS_AUTH_URL: the same call over the internet (local dev only; Workers can't fetch
//      another workers.dev Worker on the same account, hence the service binding).
// Results are cached per isolate for a minute (10 s for a rejection).

import type { Env } from "./env";
import { HttpError, safeEqual, sha256 } from "./util";

export interface ArgusUser {
  id: string;
  email: string;
  name?: string;
}

const TOKEN_RE = /^bz_[A-Za-z0-9_-]{20,100}$/;
const cache = new Map<string, { user: ArgusUser | null; exp: number }>();
const MAX_CACHE = 5000;

export function bearer(req: Request): string | null {
  const m = /^Bearer\s+(\S+)$/.exec(req.headers.get("Authorization") || "");
  return m ? m[1]! : null;
}

export function isArgusToken(t: string | null): t is string {
  return !!t && TOKEN_RE.test(t);
}

export function clearAuthCache(): void {
  cache.clear();
}

async function viaKV(kv: KVNamespace, hash: string): Promise<ArgusUser | null> {
  const s = await kv.get<{ uid: string; exp: number }>(`sess:${hash}`, "json");
  if (!s || s.exp < Math.floor(Date.now() / 1000)) return null;
  const u = await kv.get<{ id: string; email: string; name?: string; sessions?: string[] }>(`user:${s.uid}`, "json");
  if (!u || !Array.isArray(u.sessions) || !u.sessions.includes(hash)) return null;
  return { id: u.id, email: (u.email || "").toLowerCase(), name: u.name };
}

async function viaMe(env: Env, token: string): Promise<ArgusUser | null> {
  const init = { headers: { Authorization: `Bearer ${token}` } };
  let res: Response;
  if (env.AUTH) res = await env.AUTH.fetch("https://argus-auth/v1/me", init);
  else if (env.ARGUS_AUTH_URL) res = await fetch(env.ARGUS_AUTH_URL.replace(/\/$/, "") + "/v1/me", init);
  else throw new HttpError(503, "auth_not_configured", "The gateway has no way to check Argus accounts (bind AUTH_KV or AUTH)");
  if (res.status === 401 || res.status === 403) return null;
  if (!res.ok) throw new HttpError(503, "auth_unavailable", "Argus accounts are unreachable right now");
  const b = await res.json<{ user?: { id?: string; email?: string; name?: string } }>();
  if (!b.user?.id) return null;
  return { id: b.user.id, email: (b.user.email || "").toLowerCase(), name: b.user.name };
}

export async function verifyArgusToken(env: Env, token: string, now = Date.now()): Promise<ArgusUser | null> {
  if (!TOKEN_RE.test(token)) return null;
  const hash = await sha256(token);
  const hit = cache.get(hash);
  if (hit && hit.exp > now) return hit.user;
  const user = env.AUTH_KV ? await viaKV(env.AUTH_KV, hash) : await viaMe(env, token);
  if (cache.size >= MAX_CACHE) cache.clear();
  cache.set(hash, { user, exp: now + (user ? 60_000 : 10_000) });
  return user;
}

/** The signed-in Argus account, or a 401. */
export async function requireUser(env: Env, req: Request): Promise<ArgusUser> {
  const t = bearer(req);
  if (!isArgusToken(t)) throw new HttpError(401, "invalid_api_key", "Sign in to Argus: send your Argus account token as 'Authorization: Bearer bz_…'");
  const u = await verifyArgusToken(env, t);
  if (!u) throw new HttpError(401, "invalid_api_key", "Your Argus session has expired. Sign in again.");
  return u;
}

// ---------------- admin ----------------

export function adminEmails(env: Env): string[] {
  return (env.ADMIN_EMAILS || "").split(",").map(s => s.trim().toLowerCase()).filter(Boolean);
}

export interface Admin { kind: "account" | "token"; email: string }

/** A Argus session whose email is in ADMIN_EMAILS, or the ADMIN_TOKEN secret. */
export async function requireAdmin(env: Env, req: Request): Promise<Admin> {
  const t = bearer(req);
  if (!t) throw new HttpError(401, "unauthorized", "Sign in");
  if (env.ADMIN_TOKEN && env.ADMIN_TOKEN.length >= 24 && safeEqual(t, env.ADMIN_TOKEN)) return { kind: "token", email: "admin-token" };
  if (isArgusToken(t)) {
    const u = await verifyArgusToken(env, t);
    if (u && u.email && adminEmails(env).includes(u.email)) return { kind: "account", email: u.email };
  }
  throw new HttpError(401, "unauthorized", "Not an admin");
}
