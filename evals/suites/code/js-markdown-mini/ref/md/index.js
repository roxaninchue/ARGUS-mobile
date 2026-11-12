const { esc, inline } = require('./inline');
const BLOCK_START = /^(```|#{1,6} |[-*] )/;

function fence(lines, i) {
  const body = [];
  i++;
  while (i < lines.length && !/^```/.test(lines[i])) body.push(lines[i++]);
  return [`<pre><code>${esc(body.join('\n'))}</code></pre>`, i + 1];
}
function heading(lines, i) {
  const n = lines[i].indexOf(' ');
  return [`<h${n}>${inline(lines[i].slice(n + 1).trim())}</h${n}>`, i + 1];
}
function list(lines, i) {
  const items = [];
  while (i < lines.length && /^[-*] /.test(lines[i])) items.push(`<li>${inline(lines[i++].slice(2).trim())}</li>`);
  return [`<ul>${items.join('')}</ul>`, i];
}
function paragraph(lines, i) {
  const para = [];
  while (i < lines.length && lines[i].trim() && !BLOCK_START.test(lines[i])) para.push(lines[i++].trim());
  return [`<p>${inline(para.join(' '))}</p>`, i];
}
function blockAt(lines, i) {
  const l = lines[i];
  if (/^```/.test(l)) return fence(lines, i);
  if (/^#{1,6} /.test(l)) return heading(lines, i);
  if (/^[-*] /.test(l)) return list(lines, i);
  return paragraph(lines, i);
}
function render(src) {
  const lines = String(src).replace(/\r\n?/g, '\n').split('\n'), out = [];
  let i = 0;
  while (i < lines.length) {
    if (!lines[i].trim()) { i++; continue; }
    const [html, next] = blockAt(lines, i);
    out.push(html);
    i = next;
  }
  return out.join('\n');
}
module.exports = { render };
