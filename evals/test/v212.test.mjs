// v2.12: the gateway's wire names live in one place (MODELS.WIRE) and must keep the values the deployed gateway reads.
import test from 'node:test';
import assert from 'node:assert/strict';
import { MODELS, parseChain, APP_CHAIN } from '../lib/gateway.mjs';

test('MODELS.WIRE keeps the deployed gateway contract (rename only with a gateway redeploy)', () => {
  assert.deepEqual({ ...MODELS.WIRE }, { PREFER: 'X-Blaze-Prefer', AUTO: 'blaze-auto', RESP_PREFIX: 'x-blaze-',
    AVOID: 'X-Blaze-Avoid', LIVE: '/v1/models/live', STATUS: 'x-blaze-prefer-status', ROUTE: 'x-blaze-route', FALLBACK: 'x-blaze-fallback' });   // v2.13: gateway 0.9.2 contract
  assert.ok(Object.isFrozen(MODELS.WIRE));
});

test('the app chain ends on the Auto id, and --model auto means Auto', () => {
  assert.equal(APP_CHAIN[APP_CHAIN.length - 1], MODELS.WIRE.AUTO);
  assert.deepEqual(parseChain('auto'), [MODELS.WIRE.AUTO]);
  assert.deepEqual(parseChain('groq/openai/gpt-oss-120b,auto'), ['groq/openai/gpt-oss-120b', MODELS.WIRE.AUTO]);
});
