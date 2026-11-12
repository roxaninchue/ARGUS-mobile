// node --test tools/smoke/check-smoke.test.mjs : the smoke gate check passes on this repo and catches each kind of break.
import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { check, testNames, TEST_FILE, RUNNER } from './check-smoke.mjs';

const repo = new URL('../..', import.meta.url).pathname;

test('this repo keeps the 7-test, 700 ms gate', () => {
  const r = check(repo);
  assert.deepEqual(r.problems, []);
  assert.equal(r.tests.length, 7);
  assert.equal(r.tests.at(-1), 'z_noStallAnywhere');
});

test('test names are read from @Test fun lines, not comments', () => {
  assert.deepEqual(testNames('@Test fun a() {}\n// @Test fun commented\nfun b() {}\n@Test fun `c d`() {}'), ['a', 'c d']);
});

function planted(edit) {
  const d = mkdtempSync(join(tmpdir(), 'smoke-'));
  for (const f of [TEST_FILE, RUNNER]) {
    mkdirSync(dirname(join(d, f)), { recursive: true });
    writeFileSync(join(d, f), edit(f, readFileSync(join(repo, f), 'utf8')));
  }
  return check(d).problems;
}

test('an eighth test, an @Ignore, an assume, a changed gate or runner are caught', () => {
  assert.equal(planted((f, s) => f === TEST_FILE ? s.replace('@Test fun z_noStallAnywhere', '@Test fun y_extra() {}\n    @Test fun z_noStallAnywhere') : s).length, 1);
  assert.equal(planted((f, s) => f === TEST_FILE ? s.replace('@Test fun d_musicPlay', '@org.junit.Ignore @Test fun d_musicPlay') : s).length, 1);
  assert.equal(planted((f, s) => f === TEST_FILE ? s.replace('fun d_musicPlay() {', 'fun d_musicPlay() { org.junit.Assume.assumeTrue(false);') : s).length, 1);
  assert.equal(planted((f, s) => f === TEST_FILE ? s.replace(/STALL_MS = 700L/, 'STALL_MS = 900L') : s).length, 1);
  assert.equal(planted((f, s) => f === RUNNER ? s.replace('"^OK (7 tests)"', '"^OK ("') : s).length, 1);
  assert.equal(planted((f, s) => f === RUNNER ? s.replace('-ge 700', '-ge 900') : s).length, 1);
});
