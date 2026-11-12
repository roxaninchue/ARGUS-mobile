#!/usr/bin/env node
// P1-QA-01: proves the low-RAM smoke gate is still the owner's gate (06 §9: "7 low-RAM smoke tests, 700 ms stall gate,
// no @Ignore, no skipping, no threshold changes"). No device needed: it reads the sources. Run from the repo root:
//   node tools/smoke/check-smoke.mjs
// Checks: LowRamSmokeTest has exactly 7 @Test methods and no @Ignore / assume; run-smoke.sh runs that class and passes
// only on "OK (7 tests)"; the stall gate is still 700 ms in both. Exits 1 on any failure.
import { readFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';

export const EXPECTED_TESTS = 7;
export const STALL_MS = 700;
export const TEST_FILE = 'app/src/androidTest/java/com/blaze/assistant/smoke/LowRamSmokeTest.kt';
export const RUNNER = 'tools/smoke/run-smoke.sh';

/** The @Test method names in a Kotlin source, in order. */
export function testNames(src) {
  const code = src.split('\n').map(l => l.replace(/\/\/.*$/, '')).join('\n');   // a commented-out test doesn't count
  return [...code.matchAll(/@Test\s+fun\s+(`[^`]+`|[A-Za-z0-9_]+)/g)].map(m => m[1].replace(/`/g, ''));
}

export function check(root) {
  const problems = [];
  const tf = join(root, TEST_FILE), rf = join(root, RUNNER);
  if (!existsSync(tf)) return { tests: [], problems: [`missing ${TEST_FILE}`] };
  if (!existsSync(rf)) return { tests: [], problems: [`missing ${RUNNER}`] };
  const src = readFileSync(tf, 'utf8'), sh = readFileSync(rf, 'utf8');
  const tests = testNames(src);
  if (tests.length !== EXPECTED_TESTS) problems.push(`LowRamSmokeTest has ${tests.length} tests, the gate is ${EXPECTED_TESTS}: ${tests.join(', ')}`);
  if (new Set(tests).size !== tests.length) problems.push('LowRamSmokeTest has a duplicate test name');
  if (/@(org\.junit\.)?Ignore\b/.test(src)) problems.push('LowRamSmokeTest has an @Ignore (owner rule: no skipping)');
  if (/\bassume(True|False|That|NotNull)\s*\(/.test(src)) problems.push('LowRamSmokeTest skips with assume*() (owner rule: no skipping)');
  if (!new RegExp(`STALL_MS\\s*=\\s*${STALL_MS}L?\\b`).test(src)) problems.push(`LowRamSmokeTest's STALL_MS is no longer ${STALL_MS}`);
  if (!/-e class com\.blaze\.assistant\.smoke\.LowRamSmokeTest\b/.test(sh)) problems.push('run-smoke.sh no longer instruments LowRamSmokeTest');
  if (!sh.includes(`"^OK (${EXPECTED_TESTS} tests)"`)) problems.push(`run-smoke.sh no longer passes only on "OK (${EXPECTED_TESTS} tests)"`);
  if (!new RegExp(`-ge ${STALL_MS}\\b`).test(sh)) problems.push(`run-smoke.sh's cold-start stall gate is no longer ${STALL_MS} ms`);
  return { tests, problems };
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const r = check(process.argv[2] || process.cwd());
  console.log(`low-RAM smoke: ${r.tests.length} tests (${r.tests.join(', ')})`);
  for (const p of r.problems) console.log(`FAIL ${p}`);
  console.log(r.problems.length ? `${r.problems.length} problem(s)` : `OK: ${EXPECTED_TESTS} tests, ${STALL_MS} ms gate, nothing skipped`);
  process.exit(r.problems.length ? 1 : 0);
}
