const test = require('node:test'), assert = require('node:assert'), fs = require('node:fs'), path = require('node:path');
const { render } = require('../md');
test('blocks', () => { assert.strictEqual(render('# Title\n\nline one\nline two\n\n- a\n* b'), '<h1>Title</h1>\n<p>line one line two</p>\n<ul><li>a</li><li>b</li></ul>'); assert.strictEqual(render('###### six'), '<h6>six</h6>'); assert.strictEqual(render('####### seven'), '<p>####### seven</p>'); });
test('inline', () => { assert.strictEqual(render('**b** and *i* and `x*y*`'), '<p><strong>b</strong> and <em>i</em> and <code>x*y*</code></p>'); assert.strictEqual(render('[ok](https://a.io) [bad](javascript:void)'), '<p><a href="https://a.io">ok</a> bad</p>'); });
test('escaping', () => { assert.strictEqual(render('a < b & "c" > d'), '<p>a &lt; b &amp; &quot;c&quot; &gt; d</p>'); assert.strictEqual(render('```\n<script>**x**</script>\n```'), '<pre><code>&lt;script&gt;**x**&lt;/script&gt;</code></pre>'); });
test('modules', () => { assert.ok(fs.readdirSync(path.join(__dirname, '..', 'md')).filter(f => f.endsWith('.js')).length >= 2); });
