// node --test tools/check-do-not-port.test.mjs : the register check passes on this repo and catches a planted break.
import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, cpSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { RULES, scan, registerIds } from './check-do-not-port.mjs';

const repo = new URL('..', import.meta.url).pathname;

test('this repo is clean and the register matches the rules', () => {
  assert.deepEqual(scan(repo).hits, []);
  assert.deepEqual([...registerIds(repo)].sort(), RULES.map(r => r.id).sort());
});

test('each kind of break is caught', () => {
  const d = mkdtempSync(join(tmpdir(), 'dnp-'));
  mkdirSync(join(d, 'docs/overhaul'), { recursive: true });
  cpSync(join(repo, 'docs/overhaul/do-not-port.md'), join(d, 'docs/overhaul/do-not-port.md'));
  mkdirSync(join(d, 'app/src/main/java/x'), { recursive: true });
  mkdirSync(join(d, 'app/src/main/assets/www'), { recursive: true });
  writeFileSync(join(d, 'app/src/main/java/x/A.kt'), 'val e = System.getenv("ALLOWED_EMAILS")\nweb.addJavascriptInterface(b, "Native")\n');
  writeFileSync(join(d, 'app/src/main/assets/www/cactus.wasm'), '');
  mkdirSync(join(d, 'app/src/main/assets/legal'), { recursive: true });
  writeFileSync(join(d, 'app/src/main/assets/legal/N.md'), 'Grokbot credit is allowed here');
  const ids = scan(d).hits.map(h => h.split(' ')[0]).sort();
  assert.deepEqual(ids, ['AT-01', 'AT-CACTUS', 'BH-76']);
});
