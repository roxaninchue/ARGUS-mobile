// node tools/check-no-blaze.test.mjs
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { countLine, compare, scan, loadAllow } from './check-no-blaze.mjs';

const allow = [/applicationId = "com\.blaze\.assistant"/g, /blaze:\/\//g, /"BLAZE"/g];
assert.equal(countLine('applicationId = "com.blaze.assistant"', allow), 0);
assert.equal(countLine('namespace = "com.blaze.assistant"', allow), 1);
assert.equal(countLine('open blaze://add-source or BlazeApp', allow), 1);
assert.equal(countLine('Role "BLAZE" vs BLAZE_SHOTS', allow), 1);
assert.equal(countLine('Argus only', allow), 0);

assert.deepEqual(compare({ a: 2 }, { a: 2 }).grew, []);
assert.deepEqual(compare({ a: 2 }, { a: 1 }).grew, []);
assert.equal(compare({ a: 2 }, { a: 3 }).grew.length, 1);
assert.equal(compare({}, { b: 1 }).grew.length, 1);

const root = mkdtempSync(join(tmpdir(), 'nb-'));
mkdirSync(join(root, 'tools/rename'), { recursive: true });
mkdirSync(join(root, 'src'), { recursive: true });
writeFileSync(join(root, 'tools/rename/keep-blaze.txt'), '# c\nblaze://  # links\n');
writeFileSync(join(root, 'src/BlazeApp.kt'), 'class BlazeApp // blaze://x\n');
writeFileSync(join(root, 'src/Ok.kt'), 'val a = "Argus"\n');
writeFileSync(join(root, 'CHANGELOG.md'), 'Blaze history\n');
const counts = scan(root, loadAllow(root));
assert.deepEqual(counts, { 'src/BlazeApp.kt': 2 }); // one in the text, one in the file name; CHANGELOG skipped
console.log('check-no-blaze tests passed');
