// ARGUS (P0-QA-01): docs/overhaul/specs.md gives every spec a ticket, and every ticket it names exists in 06-team-plan.md.
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const read = p => fs.readFileSync(new URL('../../' + p, import.meta.url), 'utf8');

test('every spec row names a ticket that the team plan defines', () => {
  const plan = read('docs/overhaul/06-team-plan.md');
  const defined = new Set([...plan.matchAll(/^\| \*\*(P\d-[A-Z]+-\d+)\*\*/gm)].map(m => m[1]));
  assert.ok(defined.size > 50, 'team plan tickets found');
  const rows = read('docs/overhaul/specs.md').split('\n').filter(l => /^\| \d+ \| \d+ \|/.test(l));
  assert.ok(rows.length > 700, 'spec rows found: ' + rows.length);
  const declared = +/\*\*(\d+) specs, every one with a ticket\.\*\*/.exec(read('docs/overhaul/specs.md'))[1];
  assert.equal(rows.length, declared);
  for (const r of rows) {
    const t = r.split(/(?<!\\)\|/)[4].trim();
    assert.ok(defined.has(t), 'unknown ticket ' + t + ' in: ' + r);
  }
});
