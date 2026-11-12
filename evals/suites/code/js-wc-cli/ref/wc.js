const fs = require('fs');

function count(buf) {
  const text = buf.toString('utf8');
  return { lines: (text.match(/\n/g) || []).length, words: text.split(/\s+/).filter(Boolean).length, bytes: buf.length };
}

function main(argv) {
  const json = argv.includes('--json');
  const files = argv.filter(a => a !== '--json');
  if (files.length !== 1) { process.stderr.write('usage: node wc.js [--json] FILE\n'); return 2; }
  let buf;
  try { buf = fs.readFileSync(files[0]); } catch (e) { process.stderr.write(`wc: ${files[0]}: ${e.code === 'ENOENT' ? 'No such file' : e.message}\n`); return 1; }
  const c = count(buf);
  process.stdout.write(json ? JSON.stringify({ ...c, file: files[0] }) + '\n' : `${c.lines} ${c.words} ${c.bytes} ${files[0]}\n`);
  return 0;
}

if (require.main === module) process.exitCode = main(process.argv.slice(2));
module.exports = { count, main };
