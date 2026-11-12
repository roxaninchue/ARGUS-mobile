/* guard.js: the "system around the model" for A team bots.
 *
 * Models can't be fine-tuned, so this layer makes their tool use, code and replies safer and more complete:
 *   1. Tool blocks   normalize() fixes common syntax slips; validate() rejects fake / no-op / unsafe run blocks
 *                    (sudo, destructive paths outside /root, fork bombs, raw devices) and wraps commands in a timeout.
 *   2. Loop control  StepGuard caps steps and stops identical command+result repeats; feedback() turns a failed
 *                    run into a result message with the stderr tail and a concrete retry hint.
 *   3. Code gate     CompletenessGate tracks code edits and verification runs. A bot may not say "done" on a coding
 *                    job until build/tests/demo ran end-to-end after the last edit and passed; if it can't finish, it
 *                    must ship a smaller fully-working version and say what was cut. checkScript() builds the
 *                    auto-detect test/lint/stub-scan script to run in the workspace.
 *   4. Policy        precheck(user text) and postcheck(reply) catch clearly illegal or seriously harmful requests
 *                    and replies, answer with a brief refusal plus a safe alternative, and let look-alikes through
 *                    (own-system security, chemistry/biology education, lock-picking hobby, medical questions).
 *   5. PROMPT        a hardened system-prompt section for terminal/tool use, completeness and refusals.
 *
 * Plain JS, no dependencies. Works as a browser global (window.GUARD) and as a CommonJS/ESM-importable module.
 * Wired into chat.js since v2.8 (prompt, run checks, feedback, loop stop, code gate, refusals).
 */
(function (root, factory) {
  const G = factory();
  if (typeof module === 'object' && module.exports) module.exports = G;
  else root.GUARD = G;
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  /* ------------------------------------------------------------------ 1. tool blocks */
  const JOB_TOOLS = ['job_status', 'job_wait', 'job_kill'];   // v2.15: background jobs (bgjobs.js)
  const MGR_TOOLS = ['agents_list', 'agent_create', 'agent_update', 'agent_delete', 'agent_delegate', 'agent_status', 'memory_read', 'memory_write', 'memory_forget', 'routine_create', 'routine_list', 'routine_cancel', 'retry_job', 'browser'];   // v2.15: the Manager's tools (manager.js runs them; memory_read is offered to every bot)
  const TOOLS = ['run', 'write_file', 'remember', 'recall', 'forget', 'add_job', 'done_job', 'update_agent', 'note_team', 'cloud'].concat(JOB_TOOLS, MGR_TOOLS);   // v2.10: write_file also as a fence (```write_file <path>), the fallback when a model writes the call as text
  const ALIASES = { shell: 'run', bash_run: 'run', run_shell: 'run', terminal: 'run', exec: 'run', execute: 'run', cmd: 'run',
    memory: 'remember', save_memory: 'remember', search_memory: 'recall', addjob: 'add_job', 'add-job': 'add_job',
    donejob: 'done_job', 'done-job': 'done_job', complete_job: 'done_job', 'update-agent': 'update_agent',
    writefile: 'write_file', 'write-file': 'write_file', write_to_file: 'write_file', create_file: 'write_file' };
  const OPEN = /^[ \t]*(`{3,}|~{3})[ \t]*([A-Za-z_-]+)?[ \t]*:?[ \t]*([^\n`]*)$/;

  /** Parse a full reply into md and tool blocks. Mirrors chat.js scanBlocks for finished text. */
  function parse(text) {
    const lines = String(text || '').replace(/\r\n?/g, '\n').split('\n');
    const out = []; let md = [], i = 0;
    const flush = () => { if (md.join('\n').trim()) out.push({ kind: 'md', text: md.join('\n').replace(/\n+$/, '') }); md = []; };
    while (i < lines.length) {
      const m = OPEN.exec(lines[i]);
      if (!m) { md.push(lines[i++]); continue; }
      const fence = m[1], raw = (m[2] || '').toLowerCase(), name = TOOLS.includes(raw) ? raw : null;
      const close = new RegExp('^[ \\t]*' + fence[0] + '{' + fence.length + ',}[ \\t]*$');
      const body = []; let j = i + 1, closed = false;
      while (j < lines.length) { if (close.test(lines[j])) { closed = true; break; } body.push(lines[j++]); }
      if (name) { flush(); out.push({ kind: 'tool', name, arg: (m[3] || '').trim(), body: body.join('\n'), closed }); }
      else { md.push(...lines.slice(i, closed ? j + 1 : j)); }
      i = closed ? j + 1 : j;
    }
    flush();
    return out;
  }

  /** v2.10: chat.js cuts the stream when a run/recall/write_file fence closes. Returns the index just after that closing
   *  fence (so text after it can be dropped), or null when there is none. */
  function cutAfterFirstResultBlock(text) {
    const lines = String(text || '').split('\n'); let pos = 0, inFence = null, tool = false;
    for (const l of lines) {
      const end = pos + l.length;
      if (inFence) {
        if (inFence.test(l)) { if (tool) return end; inFence = null; }
      } else {
        const m = OPEN.exec(l);
        if (m) { inFence = new RegExp('^[ \\t]*' + m[1][0] + '{' + m[1].length + ',}[ \\t]*$'); tool = /^(run|recall|write_file|cloud)$/i.test(m[2] || ''); }
      }
      pos = end + 1;
    }
    return null;
  }

  /** Fix common tool-block slips so the app's parser sees what the model meant. Returns {text, fixes[]}. */
  function normalize(text) {
    const fixes = []; let t = String(text || '').replace(/\r\n?/g, '\n');
    // ``` RUN / ```Run / ~~~run / ```run: -> ```run ; aliases (```shell_exec etc.) -> canonical
    t = t.replace(/^([ \t]*)(`{3,}|~{3})[ \t]*([A-Za-z_-]+)[ \t]*:?[ \t]*$/gm, (all, ind, f, w) => {
      const lw = w.toLowerCase(), canon = TOOLS.includes(lw) ? lw : ALIASES[lw];
      if (!canon) return all;
      const fixed = '```' + canon;
      if (all.trim() !== fixed) fixes.push(`fence "${all.trim()}" -> "${fixed}"`);
      return fixed;
    });
    t = t.replace(/^[ \t]*~~~[ \t]*$/gm, () => { fixes.push('~~~ close -> ```'); return '```'; });
    // one-line form: ```run ls -la```  -> multi-line
    t = t.replace(/^```(run|recall|forget|done_job)[ \t]+([^\n`]+?)[ \t]*```[ \t]*$/gm, (a, n, b) => { fixes.push(`inline ${n} block expanded`); return '```' + n + '\n' + b + '\n```'; });
    // unclosed final tool block
    const blocks = parse(t);
    const last = blocks[blocks.length - 1];
    if (last && last.kind === 'tool' && !last.closed) { t = t.replace(/\n*$/, '\n```'); fixes.push(`closed unterminated ${last.name} block`); }
    // "$ " prompt prefixes inside run blocks
    t = t.replace(/(^```run[^\n]*\n)([\s\S]*?)(\n```)/gm, (a, o, body, c) => {
      const ls = body.split('\n');
      if (ls.length && ls.every(l => !l.trim() || /^\s*\$ /.test(l))) { fixes.push('stripped "$ " prompts'); body = ls.map(l => l.replace(/^(\s*)\$ /, '$1')).join('\n'); }
      return o + body + c;
    });
    return { text: t, fixes };
  }

  /* --- command safety --- */
  function insideWorkspace(p, cwd) {
    p = String(p || '').replace(/^["']|["']$/g, '');
    if (!p || p === '-' || p.startsWith('-')) return true;
    if (/^(\/root|~|\$HOME|\$\{HOME\})(\/|$)/.test(p)) return !/^(\/root|~|\$HOME|\$\{HOME\})\/?\*?$/.test(p); // the whole home is not ok
    if (/^\/tmp(\/|$)/.test(p)) return true;
    if (p.startsWith('/')) return false;
    if (/^\.\.(\/|$)/.test(p)) return (cwd || '/root') !== '/root' && (cwd || '/root').startsWith('/root/');
    return true; // relative to cwd (inside /root by default)
  }
  /** Split a script into simple commands (good enough for policy; not a full shell parser). */
  function commands(script) {
    return String(script || '').replace(/\\\n/g, ' ').split(/\n|;|&&|\|\||\|(?!\|)/).map(s => s.trim()).filter(Boolean);
  }
  const HARD_BLOCK = [
    [/:\(\)\s*\{\s*:\s*\|\s*:\s*&\s*\}\s*;?\s*:/, 'fork bomb'],
    [/\bmkfs(\.\w+)?\b/, 'formats a filesystem'],
    [/\bdd\b[^\n]*\bof=\/dev\/(?!null\b)/, 'writes to a raw device'],
    [/>\s*\/dev\/(sd|nvme|mmcblk|block)/, 'writes to a raw device'],
    [/\b(shutdown|reboot|halt|poweroff)\b/, 'power commands'],
    [/\bchmod\s+(-R\s+)?[0-7]{3,4}\s+\/(\s|$)/, 'chmod on /'],
    [/\bchown\s+-R\s+\S+\s+\/(\s|$)/, 'chown -R on /'],
    [/\b(rm|mv|cp)\b[^\n]*\s\/(system|data|vendor|proc|sys|dev)(\/|\s|$)/, 'touches Android/host paths'],
    [/--no-preserve-root/, 'rm --no-preserve-root'],
  ];
  /** Inspect a run script. Returns {ok, script, errors[], warnings[]} with sudo stripped and a timeout wrapper. */
  /* v2.11: apply_patch. gpt-oss is trained to edit files with apply_patch (*** Begin Patch format) and reaches for it
   * even when told it is missing (py-fizzbuzz-cli, txt-replace-many on 2026-11-09). checkRun turns
   * `apply_patch <<'EOF' … EOF` (or apply_patch '…') into a python3 script that applies it: all hunks or nothing, files
   * inside /root or /tmp only, a clear error naming the hunk that did not match. A first line
   * "# apply_patch edits: a.py b.py" tells the completeness gate which files changed. */
  const AP_PY = "import sys, os, base64\nP = base64.b64decode(sys.argv[1]).decode('utf-8', 'replace').replace('\\r\\n', '\\n')\nL = P.split('\\n')\ndef die(m):\n    sys.stderr.write('apply_patch: ' + m + '\\nNothing was changed.\\n'); sys.exit(1)\ndef chk(p):\n    p = p.strip()\n    if not p: die('a file line has no path')\n    if '..' in p.split('/'): die('\"..\" is not allowed in ' + p)\n    a = os.path.abspath(os.path.expanduser(p))\n    if not (a.startswith('/root/') or a.startswith('/tmp/')): die('only files inside /root or /tmp can be patched (' + p + ')')\n    return os.path.expanduser(p)\ni, ops = 0, []\nwhile i < len(L) and not L[i].startswith('*** Begin Patch'): i += 1\nif i == len(L): die('no \"*** Begin Patch\" line; use the format *** Begin Patch / *** Update File: path / @@ / -old / +new / *** End Patch')\ni += 1\nwhile i < len(L):\n    l = L[i]\n    if l.startswith('*** End Patch'): break\n    if l.startswith('*** Add File:'):\n        p = chk(l.split(':', 1)[1]); i += 1; body = []\n        while i < len(L) and not L[i].startswith('*** '):\n            body.append(L[i][1:] if L[i].startswith('+') else L[i]); i += 1\n        while body and body[-1] == '': body.pop()\n        ops.append(('add', p, body)); continue\n    if l.startswith('*** Delete File:'):\n        ops.append(('del', chk(l.split(':', 1)[1]))); i += 1; continue\n    if l.startswith('*** Update File:'):\n        p = chk(l.split(':', 1)[1]); i += 1; mv = None\n        if i < len(L) and L[i].startswith('*** Move to:'): mv = chk(L[i].split(':', 1)[1]); i += 1\n        hunks, cur = [], None\n        while i < len(L) and not (L[i].startswith('*** ') and not L[i].startswith('*** End of File')):\n            x = L[i]\n            if x.startswith('@@'):\n                cur = {'hdr': x[2:].strip().strip('@').strip(), 'lines': []}; hunks.append(cur)\n            elif not x.startswith('*** End of File'):\n                if cur is None: cur = {'hdr': '', 'lines': []}; hunks.append(cur)\n                cur['lines'].append(x)\n            i += 1\n        for h in hunks:\n            while h['lines'] and h['lines'][-1] == '': h['lines'].pop()\n        ops.append(('upd', p, mv, hunks)); continue\n    i += 1\nif not ops: die('the patch has no *** Add File / *** Update File / *** Delete File sections')\ndef find(lines, old, start):\n    for norm in (lambda s: s, lambda s: s.rstrip(), lambda s: s.strip()):\n        o = [norm(x) for x in old]\n        for base in (start, 0):\n            for k in range(base, len(lines) - len(o) + 1):\n                if all(norm(lines[k + j]) == o[j] for j in range(len(o))): return k\n    return -1\nout, done = {}, []\nfor op in ops:\n    if op[0] == 'add':\n        out[op[1]] = '\\n'.join(op[2]) + '\\n'; done.append('added ' + op[1]); continue\n    if op[0] == 'del':\n        if not os.path.isfile(op[1]): die(op[1] + ' does not exist')\n        out[op[1]] = None; done.append('deleted ' + op[1]); continue\n    _, p, mv, hunks = op\n    if p in out and out[p] is not None: src = out[p]\n    elif os.path.isfile(p): src = open(p, encoding='utf-8').read()\n    else: die(p + ' does not exist (use *** Add File: for a new file)')\n    nl = src.endswith('\\n') or src == ''\n    lines = src.split('\\n')\n    if nl and lines and lines[-1] == '': lines.pop()\n    pos = 0\n    for n, h in enumerate(hunks, 1):\n        old, new = [], []\n        for x in h['lines']:\n            if x.startswith('+'): new.append(x[1:])\n            elif x.startswith('-'): old.append(x[1:])\n            else:\n                t = x[1:] if x.startswith(' ') else x; old.append(t); new.append(t)\n        if h['hdr']:\n            for k in range(pos, len(lines)):\n                if lines[k].strip() == h['hdr'] or (len(h['hdr']) > 3 and h['hdr'] in lines[k]): pos = k; break\n        if not old:\n            at = pos + 1 if h['hdr'] and pos < len(lines) else len(lines)\n            lines[at:at] = new; pos = at + len(new); continue\n        k = find(lines, old, pos)\n        if k < 0:\n            die('hunk %d: these lines are not in %s (copy them exactly from the file, or cat it first):\\n%s' % (n, p, '\\n'.join(old[:6])))\n        res, oi = [], 0\n        for x in h['lines']:   # context lines keep the file's own text (whitespace included)\n            if x.startswith('+'): res.append(x[1:])\n            elif x.startswith('-'): oi += 1\n            else: res.append(lines[k + oi]); oi += 1\n        lines[k:k + len(old)] = res; pos = k + len(res)\n    txt = '\\n'.join(lines) + ('\\n' if nl and lines else '')\n    if mv: out[p] = None; out[mv] = txt; done.append('updated %s -> %s' % (p, mv))\n    else: out[p] = txt; done.append('updated ' + p)\nfor p, t in out.items():\n    if t is None:\n        if os.path.exists(p): os.remove(p)\n        continue\n    d = os.path.dirname(p)\n    if d: os.makedirs(d, exist_ok=True)\n    with open(p, 'w', encoding='utf-8') as f: f.write(t)\nprint('Done! ' + ', '.join(done))\n";
  const AP_HEREDOC = /(^|\n|&&|;|\|\|)([ \t]*)apply_patch(?:[ \t]+-)?[ \t]*<<-?[ \t]*(['"]?)(\w+)\3([^\n]*)\n([\s\S]*?)\n[ \t]*\4[ \t]*(?=\n|$)/g;
  const AP_QUOTED = /(^|\n|&&|;|\|\|)([ \t]*)apply_patch[ \t]+(['"])(\*\*\* Begin Patch[\s\S]*?\*\*\* End Patch)[ \t]*\n?\3/g;
  /** Paths a patch touches (Add/Update/Delete/Move to). */
  function patchPaths(patch) { const out = []; let m; const re = /^\*\*\* (?:Add File|Update File|Delete File|Move to):\s*(.+?)\s*$/gm; while ((m = re.exec(String(patch || '')))) out.push(m[1]); return out; }
  /** Pull apply_patch calls out of a script. Returns {script with placeholders, patches[]} (patches empty when none). */
  function extractPatches(script) {
    const patches = [];
    const put = (pre, ind, body) => { patches.push(body); return `${pre}${ind}apply_patch __ARGUS_PATCH_${patches.length - 1}__`; };
    let s = String(script || '').replace(AP_HEREDOC, (m, pre, ind, q, tag, rest, body) => put(pre, ind, body) + rest);
    s = s.replace(AP_QUOTED, (m, pre, ind, q, body) => put(pre, ind, q === "'" ? body.replace(/'\\''/g, "'") : body));
    return { script: s, patches };
  }
  function expandPatches(script, patches) {
    if (!patches.length) return script;
    const paths = [].concat(...patches.map(patchPaths));
    const s = String(script).replace(/apply_patch __ARGUS_PATCH_(\d+)__/g, (m, n) => `python3 - '${b64(patches[+n])}' <<'__ARGUS_AP__'\n${AP_PY}\n__ARGUS_AP__`);
    return '# apply_patch edits: ' + paths.join(' ') + '\n' + s;
  }
  /** A whole apply_patch call as a run script (native tool call named apply_patch). */
  function patchCommand(patch) { return "apply_patch <<'PATCH'\n" + String(patch || '').replace(/\s+$/, '') + "\nPATCH"; }

  function checkRun(body, opts) {
    opts = opts || {};
    const errors = [], warnings = [];
    let script = String(body == null ? '' : body).replace(/^\n+|\s+$/g, '');
    if (!script) errors.push('empty run block');
    const ap = extractPatches(script); script = ap.script;   // v2.11: checks below see apply_patch, not the patch text
    for (const p of [].concat(...ap.patches.map(patchPaths))) { const w = checkWrite(p, ''); if (!w.ok) errors.push('blocked: apply_patch ' + p + ': ' + w.error); }
    // sudo: you are root
    if (/(^|[\s;&|(])sudo\b/.test(script)) {
      warnings.push('removed sudo: you are already root');
      script = script.replace(/(^|\n|[;&|(]\s*|&&\s*|\|\|\s*)([ \t]*)sudo\s+(?:-[EHnSs]+\s+)*/g, '$1$2');
    }
    for (const [re, why] of HARD_BLOCK) if (re.test(script)) errors.push('blocked: ' + why);
    for (const c of commands(script)) {
      const w = c.split(/\s+/);
      if (/^(rm|shred|unlink)$/.test(w[0]) || (w[0] === 'find' && /-delete\b|-exec\s+rm\b/.test(c))) {
        const targets = w[0] === 'find' ? [w[1] || '.'] : w.slice(1).filter(x => !x.startsWith('-'));
        for (const p of targets) if (!insideWorkspace(p, opts.cwd)) errors.push(`blocked: ${w[0]} outside the workspace (${p})`);
        if (/^(rm)$/.test(w[0]) && targets.some(p => /^(\/root|~|\$HOME|\$\{HOME\})\/?\*?$|^\/?\*$|^\/$/.test(p))) errors.push('blocked: deleting the whole workspace/home');
      }
      if (/^(mv|cp|chmod|chown|truncate|ln)$/.test(w[0])) {
        const targets = w.slice(1).filter(x => !x.startsWith('-'));
        const dest = targets[targets.length - 1];
        if (dest && /^\/(etc|usr|bin|sbin|lib|boot|var\/lib\/dpkg)(\/|$)/.test(dest) && !opts.allowSystem) warnings.push(`writes into system path ${dest}; prefer the workspace or apt-get`);
        if (dest && /^\/(system|data|vendor|proc|sys|dev)(\/|$)/.test(dest)) errors.push(`blocked: ${w[0]} into ${dest}`);
      }
      if (/^git$/.test(w[0]) && /\bpush\b.*(--force\b|\s-f\b)/.test(c)) warnings.push('force push: only if the user asked for it');
      if (/\b(curl|wget)\b[^|]*\|\s*(sudo\s+)?(ba)?sh\b/.test(c)) warnings.push('piping a download into sh: check the source first');
    }
    // fake / no-op run blocks
    const fake = fakeRun(script);
    if (fake) errors.push(fake);
    // long-running servers without backgrounding
    if (/^(python3? -m http\.server|npm (start|run dev)|node \S+server\S*\.js|flask run|uvicorn)\b/m.test(script) && !/&\s*$|nohup|timeout\s+\d/m.test(script)) warnings.push('starts a server in the foreground: background it (cmd > log 2>&1 &) or use timeout, then test it with curl');
    const timeout = Math.max(1, Math.min(opts.timeout || 120, 600));
    script = expandPatches(script, ap.patches);
    return { ok: errors.length === 0, script, patched: [].concat(...ap.patches.map(patchPaths)), errors, warnings, timeout, wrapped: `timeout -k 5 ${timeout} bash -lc ${shq(script)}` };
  }
  function shq(s) { return "'" + String(s).replace(/'/g, `'\\''`) + "'"; }

  /** v2.10: files this job wrote that a script would delete (rm/unlink/shred, find -delete). A model "cleaned up" its own
   *  deliverable in the 2026-11-09 code run (py-slugify). `edited` is the CompletenessGate's set. Returns the paths. */
  function deletesEdited(script, edited) {
    const norm = p => String(p || '').replace(/^["']|["']$/g, '').replace(/^(~|\$HOME|\$\{HOME\})(?=\/|$)/, '/root').replace(/^(?!\/)/, '/root/').replace(/\/+$/, '');
    const mine = new Set([...(edited || [])].map(norm)), hit = [];
    if (!mine.size) return hit;
    let cwd = '/root';
    for (const c of commands(script)) {
      const w = c.split(/\s+/);
      if (w[0] === 'cd' && w[1]) { cwd = norm(w[1]); continue; }
      if (!/^(rm|unlink|shred)$/.test(w[0])) continue;
      for (const t of w.slice(1).filter(x => !x.startsWith('-'))) {
        const p = /^(\/|~|\$HOME|\$\{HOME\})/.test(t) ? norm(t) : norm(cwd + '/' + t.replace(/^\.\//, ''));
        for (const f of mine) if (f === p || f.startsWith(p + '/')) hit.push(f);
      }
    }
    return [...new Set(hit)];
  }
  const DELETE_MSG = f => `blocked: that deletes ${f.join(', ')}, which you wrote for this task. Keep it: the user needs it. Delete only scratch files you made for testing.`;

  /** A run block that does nothing real, or pretends to be another tool, or contains made-up output. */
  function fakeRun(script) {
    const s = String(script || '').trim();
    const lines = s.split('\n').map(l => l.trim()).filter(l => l && !l.startsWith('#'));
    if (!lines.length) return 'no-op run block (only comments)';
    if (lines.every(l => /^(true|:|exit( 0)?|echo(\s+[^$`>|;&<(]*)?|printf\s+["'][^"'$`%]*["'])$/.test(l)))
      return 'no-op run block: echo/true does nothing; say it in text instead, or run the real command';
    if (/^\s*echo\b.*\b(saved|remembered|added (the )?job|job (added|done)|marked .* done|memory updated|task complete)\b/im.test(s))
      return 'fake action: use the remember/add_job/done_job/update_agent block, not an echo';
    if (/(>>?|tee( -a)?)\s*\S*(memory|memories|jobs?|tasks?)\.(txt|md|json)\b/i.test(s) && !/\/root\/\S+\/(memory|jobs?|tasks?)\./.test(s))
      return 'fake action: memory and jobs live in the app; use remember / add_job, not a file';
    if (/^\s*(\$ |root@[^#\n]*# )\S/m.test(s) && /^(?!\s*(\$ |root@|#))\S.*$/m.test(s) && /\n/.test(s))
      return 'run block contains pasted prompt/output lines; put only the commands';
    if (/^(total \d+|drwx|-rw-|Cloning into|Successfully installed|exit (code )?\d+$)/m.test(s))
      return 'run block contains made-up output; put only the commands and wait for the real result';
    return null;
  }

  /** Validate every tool block in a reply. Returns {text, blocks, problems[], fixes[]}. */
  function validate(reply, opts) {
    const n = normalize(reply), blocks = parse(n.text).filter(b => b.kind === 'tool'), problems = [];
    blocks.forEach((b, i) => {
      if (b.name === 'run') {
        const r = checkRun(b.body, opts); b.check = r;
        r.errors.forEach(e => problems.push({ i, name: b.name, error: e }));
      } else if (!b.body.trim() && !(b.name === 'remember' && b.arg)) problems.push({ i, name: b.name, error: `empty ${b.name} block` });
      if (b.name === 'remember' && /\b(password|passwd|api[_ -]?key|secret|token|bearer)\b\s*[:=]?\s*\S{6,}/i.test(b.body)) problems.push({ i, name: b.name, error: 'secrets are never stored in memory' });
      if (b.name === 'update_agent' && !/^\s*\w+\s*:/m.test(b.body)) problems.push({ i, name: b.name, error: 'update_agent needs "field: value" lines (name, role, email, portfolio, model)' });
    });
    // text that pretends a command already ran
    const md = parse(n.text).filter(b => b.kind === 'md').map(b => b.text).join('\n');
    if (blocks.length && /```run[\s\S]*```[\s\S]*\n(output|result)s?:\s*\n/i.test(n.text)) problems.push({ i: -1, name: 'text', error: 'invented output after a run block: stop and wait for the result' });
    if (!blocks.some(b => b.name === 'run') && /^\s*(\$|root@\S*#)\s+\S+[\s\S]*\n(total \d+|drwx|-rw-)/m.test(md)) problems.push({ i: -1, name: 'text', error: 'shows terminal output without running anything' });
    return { text: n.text, blocks, problems, fixes: n.fixes };
  }

  /* ------------------------------------------------------------------ 2. loop control and feedback */
  class StepGuard {
    constructor(opts) { opts = opts || {}; this.max = opts.maxSteps || 8; this.repeat = opts.maxRepeats || 2; this.steps = 0; this.seen = new Map(); this.fails = 0; }
    key(cmd, res) { return String(cmd).replace(/\s+/g, ' ').trim() + '\u0000' + (res == null ? '' : String(res.code) + ':' + String(res.out || '').slice(-400)); }
    /** Call before executing a step. Returns {ok, reason}. */
    before(cmd) {
      this.steps++;
      if (this.steps > this.max) return { ok: false, reason: `step limit (${this.max}) reached: summarize what's done and what's left, then stop` };
      return { ok: true };
    }
    /** Call after a command ran. Returns {stop, hint}. Same command with the same result more than `repeat` times stops the loop. */
    after(cmd, res) {
      const k = this.key(cmd, res), n = (this.seen.get(k) || 0) + 1; this.seen.set(k, n);
      this.fails = res && res.code !== 0 ? this.fails + 1 : 0;
      if (n > this.repeat) return { stop: true, hint: 'Same command, same result, ' + n + ' times. Stop retrying it: try a different approach or tell the user what is blocking.' };
      if (n === this.repeat) return { stop: false, hint: 'You already ran this exact command and got the same result. Change something before running it again.' };
      if (this.fails >= 3) return { stop: false, hint: 'Three failures in a row. Step back: read the error, check assumptions (paths, versions, network), and try one different approach.' };
      return { stop: false, hint: '' };
    }
  }

  const HINTS = [
    // v2.9: models burned steps installing pytest offline; the standard library runs the same tests
    [/pytest: (command )?not found|No module named '?pytest/, () => 'pytest is not installed here and the network may be off. Do not try to install it: run the tests with the standard library (python3 -m unittest discover -v, or python3 -m unittest <module>).'],
    // v2.10: models reach for apply_patch, which isn't on the phone
    [/apply_patch: (command )?not found/, () => 'apply_patch only works as apply_patch <<\'EOF\' with the patch (*** Begin Patch … *** End Patch) and EOF on its own line. Or rewrite the file with write_file.'],
    // v2.11: the app's apply_patch reports a hunk that did not match
    [/apply_patch: hunk \d+: these lines are not in/, () => 'The patch context did not match the file. cat the file (or sed -n) and copy the lines exactly, or rewrite the file with write_file.'],
    // v2.11: unittest found nothing (exit 5), py-slugify on 2026-11-09 then gave up and pasted fake output
    [/Ran 0 tests|NO TESTS RAN|no tests ran/, () => 'No tests were found. List them (find . -name "test*.py") and run them by path: cd into the folder that has them and run python3 -m unittest discover -s <dir> -v, or python3 <test_file>.py.'],
    [/command not found|: not found/i, m => { const c = (/(?:bash: (?:line \d+: )?)?([\w.+-]+): (?:command )?not found/.exec(m) || [])[1]; return `"${c || 'that command'}" isn't installed. Check with "command -v", install it (apt-get install -y <pkg>, pip install <pkg>) or use a tool that is there.`; }],
    [/No module named '?([\w.]+)/, m => `Python module missing (${(/No module named '?([\w.]+)/.exec(m) || [])[1]}). Install it with pip, or use the standard library.`],
    [/Cannot find module '([^']+)'/, m => `Node module missing (${(/Cannot find module '([^']+)'/.exec(m) || [])[1]}). npm install it in the project, or fix the path.`],
    [/No such file or directory/i, () => 'A path is wrong. List the directory (ls -la) and use the real path; do not guess.'],
    [/Permission denied/i, () => 'Permission denied. You are root, so sudo will not help: check the file mode (chmod +x) or whether it is outside the workspace.'],
    [/Could not resolve host|Network is unreachable|Temporary failure in name resolution|ENOTFOUND|EAI_AGAIN/i, () => 'No network. Use local files, a local package (pip install ./pkg.whl, npm install ./pkg.tgz --offline) or tell the user.'],
    [/SyntaxError|IndentationError|unexpected token|syntax error/i, () => 'Syntax error. Open the file around the reported line (sed -n "START,ENDp" file), fix it, and re-run.'],
    [/(\d+) (failed|errors?)\b|FAILED|AssertionError|not ok \d+/, () => 'Tests failed. Read the first failure, fix the code (not the test), and re-run the tests.'],
    [/fatal: not a git repository/i, () => 'Not a git repo here: cd into the repo or run git init.'],
    [/merge conflict|CONFLICT \(/i, () => 'Merge conflict: open the files with <<<<<<< markers, resolve, git add, then commit.'],
    [/address already in use|EADDRINUSE/i, () => 'Port in use: find the process (ss -ltnp or lsof -i) and stop it, or use another port.'],
    [/Killed|out of memory|MemoryError/i, () => 'Out of memory: work on less data at once.'],
  ];
  const APPERR = /CANNOT LINK EXECUTABLE|proot (error|warning|info)|proot: |workspace (is )?not ready|libtalloc|Bad system call/i;
  /** Build the tool-result message for a finished run: exit code, tail of output (stderr is merged), and a retry hint. */
  function feedback(res, loopHint) {
    const code = res && res.code, out = String((res && res.out) || '') + (res && res.err ? '\n' + res.err : '');
    let s = `[run] exit ${code}${res && res.timedOut ? ' (timed out)' : ''}\n` + (out.length > 6000 ? '…' + out.slice(-6000) : out.trim() || '(no output)');
    if (APPERR.test(out)) return s + '\nNOTE: this is an app/workspace-level failure, not something to fix from the shell. Tell the user and suggest updating the ARGUS app.';
    if (res && res.timedOut) s += '\nHINT: it timed out. Run long jobs in the background (cmd > log 2>&1 &) and check the log, or make the work smaller.';
    else if (code !== 0) { const h = HINTS.find(([re]) => re.test(out)); s += '\nHINT: ' + (h ? h[1](out) : 'It failed. Read the error above and fix the cause before trying again; do not repeat the same command unchanged.'); }
    if (loopHint) s += '\nLOOP: ' + loopHint;
    return s;
  }

  /* ------------------------------------------------------------------ 3. code gate */
  const CODE_EXT = /\.(py|js|mjs|cjs|ts|tsx|jsx|go|rs|c|cc|cpp|h|hpp|java|kt|rb|php|sh|lua|swift)$/;
  /** Files a run script writes (heredocs, redirects, tee, sed -i, python/node writes, patch, mv). Heuristic. */
  function editedFiles(script) {
    const s = String(script || ''), files = new Set(); let m;
    const re = [/(?:^|[\s;&|])(?:cat|printf|echo)[^\n>]*>>?\s*([^\s;&|<>]+)/g, /\btee(?:\s+-a)?\s+([^\s;&|<>]+)/g, /\bsed\s+-i\S*\s+(?:'[^']*'|"[^"]*"|\S+)\s+([^\s;&|]+)/g,
      /open\(\s*['"]([^'"]+)['"]\s*,\s*['"][wa]/g, /writeFileSync\(\s*['"]([^'"]+)['"]/g, /\b(?:touch|cp|mv)\s+(?:-\S+\s+)*(?:\S+\s+)*?([^\s;&|]+\.\w+)\s*(?:$|[;&|\n])/gm, /^\+\+\+ b\/(\S+)/gm];
    for (const r of re) while ((m = r.exec(s))) if (!/^\/dev\//.test(m[1])) files.add(m[1].replace(/^['"]|['"]$/g, ''));
    const ap = /^# apply_patch edits: (.*)$/m.exec(s); if (ap) ap[1].split(/\s+/).filter(Boolean).forEach(f => files.add(f));   // v2.11
    return [...files];
  }
  const VERIFY = /\b(pytest|python3? -m (unittest|pytest)|npm (test|run (test|build|lint))|node --test|go (test|build|vet)|cargo (test|build)|make( \w+)?|ctest|mvn|gradle|ruff|flake8|eslint|tsc|py_compile|node --check|bash -n|\.\/(run|test|build)\S*\.sh)\b/;
  // v2.9: also stdin scripts (python3 - <<'PY' … PY, node - <<'JS', … | python3), which models use to run quick tests
  const RUNS_PROGRAM = /\b(python3?|node|bash|sh|\.\/)\s*\S+\.(py|js|mjs|sh)\b|\bpython3? -[mc]\b|\bnode -e\b|\b(python3?|node)\s+-(\s|$)|\|\s*(python3?|node)\b|\bcurl\b|(^|\s)\.\/[\w.-]+/;
  const DONE_CLAIM = /\b(done|finished|complete[ds]?|all set|ready to use|it works|works now|implemented|ship(ped)?|fixed)\b/i;
  const SCOPE_NOTE = /\b(cut|left out|didn'?t (get to|include|implement)|not (yet )?(included|implemented|done)|skipped|out of scope|smaller (version|demo)|scoped (it )?down|next steps?|still (to do|missing))\b/i;

  class CompletenessGate {
    constructor() { this.edited = new Set(); this.lastEdit = -1; this.lastVerify = -1; this.lastVerifyOk = false; this.step = 0; this.asked = 0; }
    /** Record a run block and its result. */
    onRun(script, res) {
      this.step++;
      script = String(script || '').replace(/python3 - '[^']*' <<'__ARGUS_AP__'\n[\s\S]*?\n__ARGUS_AP__/g, 'apply_patch');   // v2.11: a patch edits, it does not verify
      const f = editedFiles(script).filter(x => CODE_EXT.test(x));
      if (f.length) { f.forEach(x => this.edited.add(x)); this.lastEdit = this.step; }
      if (VERIFY.test(script) || (RUNS_PROGRAM.test(script) && !f.length)) { this.lastVerify = this.step; this.lastVerifyOk = !!res && res.code === 0 && !res.timedOut && !STUB_OUT.test(String(res.out || '')); }
      else if (f.length && (VERIFY.test(script) || RUNS_PROGRAM.test(script))) { this.lastVerify = this.step; this.lastVerifyOk = !!res && res.code === 0; }
    }
    /** v2.9: a file written by the write_file tool (no shell script to parse). */
    onWrite(path) { this.step++; if (CODE_EXT.test(String(path || ''))) { this.edited.add(String(path)); this.lastEdit = this.step; } }
    get codeTouched() { return this.edited.size > 0; }
    /** May the bot finish with this final text? Returns {ok, message} where message is fed back if not ok. */
    canFinish(finalText) {
      if (!this.codeTouched) return { ok: true };
      const verified = this.lastVerify >= this.lastEdit && this.lastVerifyOk;
      const claims = DONE_CLAIM.test(finalText || '');
      if (verified) return { ok: true };
      if (this.asked >= 2) return { ok: true, warn: 'finished without a passing end-to-end check' };
      this.asked++;
      if (this.lastVerify < this.lastEdit) return { ok: false, autoCheck: true, message: `GATE: you changed code (${[...this.edited].slice(0, 5).join(', ')}) but have not run it since. Before you say ${claims ? '"done"' : 'anything is finished'}: run the build, the tests and the demo end-to-end in one run block, with checks (asserts or tests) for every rule the task states, invalid input and edge cases included, not just the happy path. If something fails and you cannot fix everything, cut scope to a smaller version that fully works, make the checks pass, and state exactly what you cut.` };
      return { ok: false, message: 'GATE: the last check failed. Fix the code (not the test), or scope down to a smaller version that fully works and passes, then re-run. Do not ship partial code or stubs; say what you cut.' };
    }
    /** Final-answer check: a coding job that cut scope must say what was cut. */
    needsScopeNote(finalText, cutSomething) { return !!cutSomething && !SCOPE_NOTE.test(finalText || ''); }
  }
  const STUB_OUT = /\b(TODO|FIXME|NotImplementedError|not implemented)\b/;

  /** Shell script that detects and runs build, tests, lint and a stub scan in a project dir. Exit 0 only if all pass. */
  function checkScript(dir) {
    return [
      `cd ${shq(dir || '.')} || exit 2`,
      'fail=0',
      'say(){ printf "\\n== %s\\n" "$*"; }',
      'if [ -f package.json ]; then say npm; if grep -q \'"build"\' package.json; then npm run -s build || fail=1; fi; if grep -q \'"test"\' package.json && ! grep -q \'no test specified\' package.json; then npm test --silent || fail=1; fi; fi',
      'if [ -f Makefile ] && grep -qE "^test:" Makefile; then say make test; make test || fail=1; fi',
      'if [ -f go.mod ]; then say go; go build ./... && go test ./... || fail=1; fi',
      'if [ -f Cargo.toml ]; then say cargo; cargo test -q || fail=1; fi',
      'PY=$(find . -name "*.py" -not -path "*/node_modules/*" -not -path "*/.venv/*" | head -500)',
      'if [ -n "$PY" ]; then say python compile; python3 -m py_compile $PY || fail=1;',
      '  if command -v ruff >/dev/null; then ruff check -q . || fail=1; elif python3 -c "import pyflakes" 2>/dev/null; then python3 -m pyflakes $PY || fail=1; fi;',
      '  if ls tests/test_*.py test_*.py */test_*.py >/dev/null 2>&1; then say python tests; if python3 -c "import pytest" 2>/dev/null; then python3 -m pytest -q || fail=1; else python3 -m unittest discover -s "$(ls -d tests 2>/dev/null || echo .)" -t . -q || fail=1; fi; fi; fi',
      'JS=$(find . \\( -name "*.js" -o -name "*.mjs" \\) -not -path "*/node_modules/*" | head -500)',
      'if [ -n "$JS" ]; then say js syntax; for f in $JS; do node --check "$f" || fail=1; done;',
      '  if [ ! -f package.json ] && ls test/*.test.*js tests/*.test.*js *.test.*js >/dev/null 2>&1; then say node tests; node --test || fail=1; fi; fi',
      'say stubs; if grep -rnE "\\b(TODO|FIXME|XXX)\\b|NotImplementedError|throw new Error\\(.(not implemented|TODO)" --include=*.py --include=*.js --include=*.mjs --include=*.ts --include=*.go --include=*.rs . 2>/dev/null | grep -v node_modules | grep -v "/test" ; then echo "stubs or TODOs left"; fail=1; fi',
      'echo; [ $fail = 0 ] && echo "CHECKS PASSED" || echo "CHECKS FAILED"; exit $fail',
    ].join('\n');
  }

  /* ------------------------------------------------------------------ 4. policy */
  // Category-level patterns only: no operational detail lives here. Each needs an intent + a harmful object.
  const I = "(make|making|build|building|synthesi[sz]e|synthesi[sz]ing|produce|producing|weaponi[sz]e|culture|culturing|enhance|enhancing|create|creating|write|code|deploy|spread|need|want|get|instructions|recipe|guide|step[- ]by[- ]step|walk me through|tell me how|explain how|show me how|how (do|can|would|should) i|how to|help me)";
  const P = "(make|making|build|building|synthesi[sz]e|synthesi[sz]ing|produce|producing|weaponi[sz]e|culture|culturing|enhance|enhancing|create|creating|assemble|assembling|instructions (for|to)|recipe|step[- ]by[- ]step)";
  const R = (re) => new RegExp(re.replace(/\{I\}/g, I).replace(/\{P\}/g, P), 'i');
  const HARM = [
    { cat: 'weapons', re: R("\\b{P}\\b[^.?!]{0,60}\\b(nerve agents?|sarin|vx|chemical weapons?|bio-?weapons?|biological weapons?|anthrax|smallpox|dirty bomb|nuclear (bomb|weapon)s?|pipe bombs?|explosive devices?|ied|bombs?)(?![-\\w])") },
    { cat: 'weapons', re: /\b(enhanc\w*|increas\w*|boost\w*|make)\b[^.?!]{0,40}\b(pathogens?|virus(es)?|bacteri\w*)\b[^.?!]{0,60}\b(spread\w*|transmissib\w*|lethal\w*|deadl\w*|virulen\w*)/i },
    { cat: 'weapons', re: /\b(untraceable|ghost)\s+(gun|firearm|rifle|pistol)s?\b|\b(convert|make)\b[^.]{0,30}\b(full[- ]?auto|machine gun)\b/i },
    { cat: 'malware', re: R("\\b{I}\\b[^.?!]{0,50}\\b(ransomware|botnet|worm that spreads|self-spreading|credential[- ]?stealer|info[- ]?stealer|password[- ]stealer|rootkit)\\b") },
    { cat: 'malware', re: /\b(keylogger|spyware|stalkerware|rat\b|remote access trojan)\b[^.?!]{0,60}\b(spy|without|secretly|girlfriend|boyfriend|wife|husband|partner|someone|their|her|his)\b/i },
    { cat: 'intrusion', re: /\b(hack\w*|break\w* into|get\w* into|access\w*|take over|brute[- ]?forc\w*|crack\w*)\b[^.?!]{0,40}\b(my (ex|girlfriend|boyfriend|wife|husband|partner|boss|neighbou?r|coworker|school|university|employer|teacher)'?s?|someone(?: else)?'?s|another person'?s|(a|the|their) (bank|hospital|government|school|company|competitor)'?s?|that isn'?t mine|not mine)\b[^.?!]{0,60}\b(accounts?|email|phones?|instagram|facebook|whatsapp|network|servers?|systems?|website|database|wifi|wi-fi|password|grading)\b/i },
    { cat: 'intrusion', re: /\b(hospital|bank|school)'?s? (patient |customer |student )?(database|system|records?)\b[^.?!]{0,30}\b(isn'?t mine|not mine|without (permission|authori[sz]ation))/i },
    { cat: 'intrusion', re: /\bddos\b\s+(a|an|the|their|this|that|my (competitor|rival|ex|school)|someone|competitor)/i },
    { cat: 'fraud', re: /\b(phishing (page|email|kit|site)s? (to|that) (steal|harvest)\w*|clone (a )?credit cards?|carding|launder\w* (money|cash|crypto|funds)|steal\w* (someone'?s )?identit(y|ies)|scam\w* (elderly|old|vulnerable) people|counterfeit (money|bills|currency|notes|cash|kwacha|dollars|euros))\b/i },
    { cat: 'fraud', re: /\b(make|create|forge|print|produce|get)\b[^.?!]{0,20}\bfake (ids?|passports?|driver'?s? licen[cs]es?|identity documents?|invoices? to)\b/i },
    { cat: 'fraud', re: /\b(print|make|produce)\w*\b[^.?!]{0,15}\bcounterfeit\b/i },
    { cat: 'drugs', re: /\b(synthesi[sz]e|cook|make|manufactur\w*|produc\w*)\b[^.?!]{0,40}\b(meth(amphetamine)?|fentanyl|heroin|crack cocaine|cocaine|mdma|lsd)\b/i },
    { cat: 'violence', re: /\b(how (do i|to|can i|would i)|help me|plan\w*)\b[^.?!]{0,40}\b(kill|murder|poison|hurt|attack|kidnap)\b[^.?!]{0,40}\b(someone|a person|people|my \w+( \w+)?|him|her|them|a crowd)\b/i },
    { cat: 'violence', re: /\bpoison\w*\b[^.?!]{0,40}\b(give|slip|put)\b[^.?!]{0,30}\b(someone|him|her|them|my \w+)\b|\b(poison|kill)\w*\b[^.?!]{0,60}\b(nobody (notices|knows|finds out)|without (anyone|them) (noticing|knowing)|not get caught|undetect\w*)/i },
    { cat: 'stalking', re: /\b(track|spy on|monitor|follow|find the (home )?address of)\b[^.?!]{0,40}\b(my (ex|wife|husband|girlfriend|boyfriend|partner)|someone|a person|her|him)\b[^.?!]{0,40}\b(without (them|her|him) knowing|secretly|without consent|hidden)\b/i },
    { cat: 'csam', re: /\b(sexual|nude|explicit|porn)\w*\b[^.?!]{0,40}\b(child|children|minor|kid|underage|teen(?:ager)?s? under)\b|\b(child|minor|underage)\b[^.?!]{0,40}\b(sexual|nude|explicit|porn)/i },
    { cat: 'evasion', re: /\b(evade|avoid|escape)\b[^.?!]{0,30}\b(police|law enforcement|a warrant|arrest)\b|\b(dispose of|hide|get rid of)\b[^.?!]{0,20}\b(a body|the body|evidence of (a|the) (crime|murder))\b/i },
    { cat: 'selfharm', re: /\b(best|easiest|painless|quickest)\s+(way|method)s?\s+to\s+(kill myself|end my life|commit suicide)\b/i },
  ];
  // Look-alikes that must get help.
  const LEGIT = /\b(my own|i own|our own|our (company|org)'?s?|authori[sz]ed|with permission|pentest(ing)? (my|our)|ctf|capture the flag|home ?lab|my (lab|router|server|homelab|vm)|for (a|my) (class|course|exam|thesis)|history of|how does .* work|why (is|are) .* (dangerous|toxic|illegal)|safety|protect|defend|detect|prevent|recogni[sz]e|signs of|symptoms|overdose (signs|symptoms)|dosage|side effects|first aid|lock ?pick(ing)? (as a )?hobby|locksport|my (own )?lock|fiction|novel|story)\b/i;
  const CRISIS = { cat: 'selfharm', text: "I can't help with that, but I don't want you to be alone with it. If you're in danger right now, call your local emergency number. In Zambia you can reach Lifeline/Childline on 933 or 116; elsewhere, findahelpline.com lists free, confidential lines. If you'd like, tell me what's going on." };
  const ALT = {
    weapons: 'I can explain the history, the treaties that ban them, or how detection and protection work.',
    malware: 'I can help you harden your own systems, set up detection, or explain how this class of attack is defended against.',
    intrusion: 'If it is your own account or system, I can help you recover it or test it with permission; if someone else\'s, the owner or the platform\'s support is the route.',
    fraud: 'I can help you spot and report scams or protect your own accounts.',
    drugs: 'I can share harm-reduction and addiction-support resources, or explain the pharmacology at a textbook level.',
    violence: 'If someone is in danger, contact local emergency services. If you\'re dealing with conflict, I can help think it through.',
    stalking: 'If you are worried about someone\'s safety, I can point you to the right services; I can also help with consent-based location sharing.',
    csam: 'If you know of a child at risk, report it to local police or NCMEC/IWF.',
    evasion: 'I can explain your legal rights or how to find a lawyer.',
  };
  function refusal(cat) {
    if (cat === 'selfharm') return CRISIS.text;
    return "I can't help with that. " + (ALT[cat] || 'I can help with a safe, legal version of what you need.');
  }
  /** Before sending to the model. Returns {action: 'refuse'|'allow', category?, reply?}. Only clear cases refuse. */
  function precheck(userText) {
    const t = String(userText || '');
    const hit = HARM.find(h => h.re.test(t));
    if (!hit) return { action: 'allow' };
    if (hit.cat !== 'csam' && hit.cat !== 'weapons' && LEGIT.test(t) && !/\b(steal|without (them|her|him) knowing|secretly|untraceable)\b/i.test(t)) return { action: 'allow', category: hit.cat, note: 'look-alike: answer helpfully at a safe level' };
    return { action: 'refuse', category: hit.cat, reply: refusal(hit.cat) };
  }
  const OPERATIONAL = /\b(step \d|\d\.\s+\w+|ingredients|you will need|materials:|precursor|reagent|yield|grams? of|ml of|payload|exploit code|shellcode|import (socket|pynput|ctypes)|encrypt(s|ing)? (all )?(the )?files)\b/i;
  /** After the model replies (and before tool blocks run). Replaces clearly harmful assistance with a refusal. */
  function postcheck(userText, reply) {
    const pre = precheck(userText);
    const r = String(reply || '');
    if (pre.action !== 'refuse') return { action: 'allow' };
    if (looksLikeRefusal(r) && !OPERATIONAL.test(r) && !/```run/.test(r)) return { action: 'allow', category: pre.category, note: 'model refused' };
    return { action: 'replace', category: pre.category, reply: pre.reply };
  }
  const REFUSE_RE = /\b(i can'?t|i cannot|i won'?t|i'?m not able to|i am not able to|i will not|unable to help|can'?t help with|not something i can|i must decline|i'?m not going to)\b/i;
  // Models often reply with typographic apostrophes (I’m, can’t); fold them to ' before matching.
  function looksLikeRefusal(text) { return REFUSE_RE.test(String(text || '').slice(0, 400).replace(/[‘’ʼ＇]/g, "'")); }

  /* ------------------------------------------------------------------ 5. hardened prompt section */
  const PROMPT = [
    'TERMINAL AND TOOLS (strict).',
    '- To run commands, write a ```run block on its own lines: only the commands, no "$ " prompts, no output. Multi-line scripts are fine; keep the block intact.',
    '- After a ```run or ```recall block, stop and wait for the real result. Never invent, predict or paste output. Never claim a command ran unless you saw its result.',
    '- Use the right tool: remember/recall/forget for memory, add_job/done_job for jobs, update_agent for bot settings. Never fake these with echo, files or a run block. Never write a run block that does nothing (echo, true).',
    '- You are root in Ubuntu under proot on a phone. Never use sudo. Work inside /root. Never delete, move or chmod anything outside /root or /tmp, never touch /system, /data, /dev, /proc. Never run rm -rf on / or on the whole home.',
    '- The network may be off. Prefer local files and offline installs (pip install ./x.whl, npm install ./x.tgz --offline). Do not loop on downloads.',
    '- Long jobs: background them (cmd > log 2>&1 &) and check the log. Servers: start in the background, test with curl, then stop them.',
    '- When a command fails: read the error, fix the cause, try a different approach. Never repeat the same failing command unchanged more than once. After three failures, stop and tell the user what blocks you.',
    '- Before editing, look (ls, cat, grep). Make the smallest change that fixes the task. Do not touch tests to make them pass.',
    '- Edit files with write_file (whole file) or apply_patch <<\'EOF\' (*** Begin Patch / *** Update File: path / @@ / -old / +new / *** End Patch) inside a run block.',
    '- Before you call code done, test every rule the task states, invalid input and edge cases included, not only the example.',
    '',
    'FINISHING CODE (strict).',
    '- Complete beats ambitious. A smaller version that fully works is better than a bigger one that is broken.',
    '- Before you say done on any coding job, run the build, the tests and the demo end-to-end in a run block and see them pass. No TODO, FIXME, stubs, placeholders or missing files.',
    '- If you cannot finish everything, cut scope: ship the part that fully works, make its checks pass, and say exactly what you cut and why. Never ship partial or untested code as finished.',
    '- Final message: what works, how you verified it (the command and its result), and what was cut, in a few lines.',
    '',
    'SAFETY.',
    '- Refuse clearly illegal or seriously harmful help (weapons capable of mass harm, malware for others\' systems, breaking into accounts or systems that are not the user\'s, fraud, making hard drugs, violence, stalking, sexual content involving minors). Refuse in one or two sentences, without a lecture, and offer a safe alternative. Never run commands for such a request.',
    '- Do help with look-alikes: security on the user\'s own systems or a CTF, how things work at a textbook level, chemistry and biology education, lock-picking as a hobby, medical and dosage questions (with "see a doctor" when it matters), fiction.',
  ].join('\n');


  /* ------------------------------------------------------------------ 6. native tools (v2.9) */
  // OpenAI-style function tools. The app and the eval harness send these as `tools`; when the model answers with
  // tool_calls they run through the same code as the fenced blocks (fromToolCall turns a call into a block). When a
  // model returns no tool_calls, the fence parser above is the fallback.
  const WRITE_MAX = 60 * 1024;   // bytes of content per write_file call (bridge + shell-argument headroom)
  const str = (d) => ({ type: 'string', description: d });
  const fn = (name, description, properties, required) => ({ type: 'function', function: { name, description, parameters: { type: 'object', properties, required: required || [] } } });
  function toolSchemas(opts) {
    const ws = !opts || opts.ws !== false, l = [];
    const cloud = opts && opts.cloud ? (opts.cloud === 'codespaces' ? 'codespaces' : 'orgo') : null;   // v2.14: only when Cloud computer is on
    if (ws) {
      l.push(fn('run', 'Run a bash script (multi-line is fine) as root in your Ubuntu workspace and get its exit code and output. Only real shell commands.', Object.assign({ command: str('The bash script to run') }, opts && opts.jobs ? { background: { type: 'boolean', description: 'true for long work (installs, builds, servers): start it as a background job right away' } } : {}), ['command']));   // v2.15 WIP: background param only once bgjobs is wired (opts.jobs)
      l.push(fn('write_file', 'Write a whole text file in the workspace (creates parent folders, overwrites). Up to ~60 KB of content per call; for bigger files write the rest with run (cat >> file).', { path: str('Absolute path under /root or /tmp, or relative to /root'), content: str('The full file content') }, ['path', 'content']));
    }
    l.push(fn('remember', 'Save a note to memory.', { text: str('The note'), scope: { type: 'string', enum: ['me', 'team'], description: '"me" is private to you, "team" is shared with every bot' } }, ['text']));
    l.push(fn('recall', 'Search memory.', { query: str('What to look for') }, ['query']));
    l.push(fn('forget', 'Remove a note from memory.', { text: str("The note's text") }, ['text']));
    l.push(fn('add_job', 'Add a job and start it for a bot. Only for handing a separate task to another bot or a later follow-up, never for the request you were just given.', { title: str('What the job is'), due: str('When, e.g. friday'), for: str('Bot name (default: you)'), priority: { type: 'string', enum: ['low', 'normal', 'high'] }, waits: str('Title of a job this one waits on') }, ['title']));
    l.push(fn('done_job', 'Mark a job done, only when it is really finished and verified. Give blocked to end it as blocked instead.', { title: str('The job title'), blocked: str('Why it cannot be finished (optional)') }, ['title']));
    l.push(fn('update_agent', 'Change your own name, role, email or portfolio, or those of a bot that reports to you.', { name: str('New name'), role: str('New role'), email: str('New email'), portfolio: str('New portfolio'), for: str('Bot to change (default: you)') }, []));
    if (ws) {
      l.push(fn('attach', 'Post workspace files or folders into the chat as attachment cards (images inline, HTML preview, open/share/save).', { paths: { type: 'array', items: { type: 'string' }, description: 'Workspace paths' } }, ['paths']));
      l.push(fn('save', "Copy a workspace file or folder (zipped) to the phone's Downloads/ARGUS folder.", { path: str('Workspace path') }, ['path']));
    }
    if (ws && opts && opts.jobs) {   // v2.15 WIP: not offered until the run supervisor in bgjobs.js is wired into chat.js
      l.push(fn('job_status', 'Status, exit code and log tail of a background job.', { id: str('Job id (default: your latest)') }, []));
      l.push(fn('job_wait', 'Wait up to `seconds` (max 120) for a background job, then return its status and log tail. Prefer ending your turn: the app pings you when it finishes.', { id: str('Job id'), seconds: { type: 'integer', description: '1-120' } }, []));
      l.push(fn('job_kill', 'Stop a background job.', { id: str('Job id') }, ['id']));
    }
    if (cloud) l.push(cloudSchema(cloud));
    return l;
  }
  const NATIVE_TOOLS = ['run', 'write_file', 'remember', 'recall', 'forget', 'add_job', 'done_job', 'update_agent', 'attach', 'save', 'cloud', 'browser'];

  /* ------------------------------------------------------------------ 6b. cloud computer (v2.14) */
  // An optional cloud computer (Orgo desktop or a GitHub codespace) that costs money or free-tier hours while it runs.
  // Bots must use the phone's own Ubuntu (run) for everything; cloud is only for a real browser, a JavaScript or login
  // site, a GUI or x86-only software. cloudSteer() bounces plain shell jobs back to run before anything starts.
  const CLOUD_ACTIONS = { orgo: ['run', 'screen', 'click', 'type', 'key', 'extend', 'stop'], codespaces: ['run', 'screen', 'extend', 'stop'] };
  function cloudSchema(p) {
    const gui = p === 'orgo';
    return fn('cloud', 'The cloud computer (' + (gui ? 'an x86 Ubuntu desktop with google-chrome' : 'a GitHub codespace, x86 Linux, no desktop') + '). It costs money or free hours while it runs, so use it ONLY when the task needs a real web browser, a site that needs JavaScript or a login, a GUI, or x86-only software. Everything else (curl, git, python, node, files, builds, tests) goes to the run tool on the phone\'s Ubuntu. It starts on first use and stops by itself when idle or when your reply ends. Files on it are separate from your workspace.' + (gui ? ' action screen takes a screenshot; click/type/key drive the desktop.' : ' For a browser, start Chromium headless with --remote-debugging-port=9222 so the user can watch.'),
      { action: { type: 'string', enum: CLOUD_ACTIONS[p], description: 'run: a bash script on the cloud computer; screen: screenshot' + (gui ? '; click: x,y; type: text; key: e.g. Enter or ctrl+l' : '') + '; extend: keep it past the 60 min cap (minutes); stop: stop it now' },
        command: str('For run: the bash script'), x: { type: 'integer', description: 'For click' }, y: { type: 'integer', description: 'For click' }, text: str('For type'), key: str('For key'), minutes: { type: 'integer', description: 'For extend (1-60)' } }, ['action']);
  }
  function CLOUD_PROMPT(p) {
    return ['CLOUD COMPUTER (' + (p === 'codespaces' ? 'GitHub codespace' : 'Orgo desktop') + ', costs money or free hours while it runs).',
      '- Use the local run tool (the phone\'s Ubuntu) for everything. Use the cloud tool ONLY when the task needs a real web browser, a site that needs JavaScript or a login, a GUI, or x86-only software.',
      '- Never use cloud for plain shell work: curl, wget, git, pip, python, node, npm, files, builds or tests run with run.',
      '- It starts on your first cloud call and is stopped after a few idle minutes and when your reply ends. Do all the cloud steps in one go, then report.',
      p === 'codespaces' ? '- There is no desktop. For a browser use headless Chromium (or Playwright) started with --remote-debugging-port=9222; cloud screen then shows its page.' : '- On the desktop: cloud run "google-chrome URL &" to open a page, cloud screen to look, click/type/key to act, screen again to check.',
      '- Files there are separate from your workspace: copy results back with cat or base64 in the output when needed.',
      '- Local browsers: if you drive Chromium in your own workspace, start it with --remote-debugging-port=9222 so the user can watch it live.'].join('\n');
  }
  // Commands that are plain shell work: no reason to start (and pay for) the cloud computer.
  const PLAIN = /^(curl|wget|git|gh|pip3?|python3?|node|npm|npx|yarn|pnpm|ls|cat|echo|printf|mkdir|cp|mv|rm|touch|grep|rg|sed|awk|find|tar|zip|unzip|gzip|jq|head|tail|wc|sort|uniq|cut|tr|diff|make|gcc|g\+\+|cc|go|cargo|rustc|java|javac|pytest|apt|apt-get|dpkg|chmod|chown|ln|pwd|cd|export|which|date|whoami|uname|df|du|free|ps|sleep|true|base64|sha256sum|md5sum|tee|xargs|test|\[)$/;
  const GUI = /\b(chrome|chromium|firefox|playwright|puppeteer|selenium|webdriver|xdotool|wmctrl|scrot|xdg-open|DISPLAY|remote-debugging-port|headless|xvfb|x11|gnome|xfce|wine|box64|qemu|electron|libreoffice|soffice|vnc|screenshot|amd64|x86_64|x86-64)\b/i;
  /** First word of each command segment (leading VAR=value assignments, sudo, time, nohup and "(" skipped). */
  function firstWords(c) {
    return commands(c).map(seg => seg.replace(/^[({\s]+/, '').split(/\s+/).filter(Boolean)).map(ws => { let i = 0; while (i < ws.length && (/^[A-Za-z_][A-Za-z0-9_]*=/.test(ws[i]) || /^(sudo|time|nohup|command)$/.test(ws[i]))) i++; return (ws[i] || '').replace(/^.*\//, ''); }).filter(w => w && !/^#/.test(w) && !/^[)}]$/.test(w));
  }
  /** {steer, reason}: a cloud run that is plain shell work goes back to run. ctx.used: this turn already uses the
   *  cloud computer (then follow-up shell steps there are fine: its files live there). */
  function cloudSteer(cmd, ctx) {
    const c = String(cmd || ''); if (!c.trim()) return { steer: false };
    if (GUI.test(c) || (ctx && ctx.used)) return { steer: false };
    const words = firstWords(c);
    if (!words.length || !words.every(w => PLAIN.test(w))) return { steer: false };
    return { steer: true, reason: '"' + words.filter((w, i, a) => a.indexOf(w) === i).slice(0, 4).join(', ') + '" is plain shell work. Use the run tool (the phone\'s Ubuntu) for it. The cloud computer is only for a real web browser, a site that needs JavaScript or a login, a GUI, or x86-only software.' };
  }
  /** One OpenAI tool_call -> {name, arg, body, id, args} (the same shape the fence parser gives), or {error}. */
  function fromToolCall(tc) {
    const f = (tc && tc.function) || tc || {}, id = (tc && tc.id) || '';
    let name = String(f.name || '').trim().toLowerCase(); name = ALIASES[name] || (name === 'note_team' ? 'remember' : name);
    const raw = f.arguments == null ? '{}' : f.arguments; let a;
    if (typeof raw === 'object') a = raw;
    else { try { a = JSON.parse(raw || '{}'); } catch (e) { return { name: name || 'unknown', arg: '', body: '', id, args: String(raw), error: 'the arguments were not valid JSON (' + e.message + '); the call may have been cut off, so send smaller pieces' }; } }
    if (!a || typeof a !== 'object') a = {};
    const s = (v) => (v == null ? '' : String(v));
    const out = { name, arg: '', body: '', id, args: typeof raw === 'string' ? raw : JSON.stringify(raw) };
    if (name === 'apply_patch' || name === 'patch') { out.name = 'run'; out.body = patchCommand(s(a.input || a.patch || a.content || a.diff || (typeof raw === 'string' && /\*\*\* Begin Patch/.test(raw) ? raw : ''))); return out; }   // v2.11
    switch (name) {
      case 'run': out.body = s(a.command || a.cmd || a.script); if (a.background === true || a.background === 'true') out.bg = true; break;
      case 'write_file': out.arg = s(a.path || a.file || a.filename).trim(); out.body = s(a.content != null ? a.content : a.text); break;
      case 'remember': out.arg = /team|shared|everyone/i.test(s(a.scope)) ? 'team' : 'me'; out.body = s(a.text || a.note); break;
      case 'recall': out.body = s(a.query || a.text); break;
      case 'forget': out.body = s(a.text || a.query); break;
      case 'add_job': out.body = [s(a.title)].concat(['due', 'for', 'priority', 'waits'].filter(k => a[k]).map(k => k + ': ' + s(a[k]))).join('\n'); break;
      case 'done_job': out.body = s(a.title) + (a.blocked ? '\nblocked: ' + s(a.blocked) : ''); break;
      case 'update_agent': out.body = ['name', 'role', 'email', 'portfolio', 'for'].filter(k => a[k]).map(k => k + ': ' + s(a[k])).join('\n'); break;
      case 'attach': out.body = (Array.isArray(a.paths) ? a.paths : [a.paths || a.path]).filter(Boolean).map(s).join('\n'); break;
      case 'save': out.body = s(a.path || (Array.isArray(a.paths) ? a.paths[0] : '')); break;
      case 'browser': out.body = JSON.stringify(a); break;   // v2.16d
      case 'cloud': {   // v2.14
        let act = s(a.action || (a.command ? 'run' : '')).toLowerCase().trim(); if (act === 'screenshot') act = 'screen';
        out.arg = act; out.cloud = { action: act || 'run', command: s(a.command || a.cmd), x: a.x == null ? NaN : +a.x, y: a.y == null ? NaN : +a.y, text: s(a.text), key: s(a.key), minutes: a.minutes == null ? 30 : +a.minutes };
        out.body = act === 'run' ? s(a.command || a.cmd) : act === 'type' ? s(a.text) : act === 'click' ? s(a.x) + ' ' + s(a.y) : act === 'key' ? s(a.key) : act === 'extend' ? s(a.minutes || 30) : act;
        break;
      }
      default:
        if (MGR_TOOLS.includes(name) || JOB_TOOLS.includes(name)) { out.body = JSON.stringify(a); break; }   // v2.15: manager.js reads the JSON body
        if (typeof CONN !== 'undefined' && CONN.isTool && CONN.isTool(name)) { out.body = JSON.stringify(a); break; }   // v2.17: the signed-in user's connector tools (connect.js runs them)
        return Object.assign(out, { error: 'unknown tool "' + name + '"' });
    }
    return out;
  }
  /** Check a write_file call. Returns {ok, path, bytes, error}. Paths: absolute under /root or /tmp, ~/x, or relative to /root. */
  function checkWrite(path, content) {
    let p = String(path || '').trim().replace(/^['"]|['"]$/g, '');
    if (!p) return { ok: false, error: 'no path given' };
    if (p === '~' || p.startsWith('~/')) p = '/root' + p.slice(1); else if (!p.startsWith('/')) p = '/root/' + p.replace(/^\.\//, '');
    if (/(^|\/)\.\.(\/|$)/.test(p)) return { ok: false, error: '".." is not allowed in the path' };
    if (!/^\/(root|tmp)\/[^\0]+$/.test(p) || /\/$/.test(p)) return { ok: false, error: 'write files inside /root or /tmp (a file path, not a folder)' };
    const bytes = typeof TextEncoder !== 'undefined' ? new TextEncoder().encode(String(content == null ? '' : content)).length : Buffer.byteLength(String(content || ''));
    if (bytes > WRITE_MAX) return { ok: false, path: p, bytes, error: `content is ${Math.round(bytes / 1024)} KB, over the ~60 KB per call limit: write the first part with write_file and append the rest with run (cat >> file <<'EOF')` };
    return { ok: true, path: p, bytes };
  }
  function b64(s) {
    if (typeof Buffer !== 'undefined') return Buffer.from(String(s), 'utf8').toString('base64');
    const b = new TextEncoder().encode(String(s)); let bin = ''; for (let i = 0; i < b.length; i += 0x8000) bin += String.fromCharCode.apply(null, b.subarray(i, i + 0x8000)); return btoa(bin);
  }
  /** Shell script that writes `content` to `path` byte-exact (base64, so quotes and heredoc markers can't break it). */
  function writeScript(path, content) {
    const q = shq(path);
    return `mkdir -p "$(dirname ${q})" && printf %s '${b64(content)}' | base64 -d > ${q} && wc -c < ${q}`;
  }

  /* ------------------------------------------------------------------ 7. unbacked claims (v2.9) */
  // A reply that states command output or results when no tool ran in that turn invented them. Hypotheticals
  // ("if you run", "you should see"), negations ("I haven't run it") and offers ("let me run") don't count.
  const CLAIMS = [
    ['ran', /\b(i|we)(?:'ve| have)? (?:just |already )?(ran|run|executed|tested|re-?ran|compiled|built|checked|verified|installed|deleted|removed|created|wrote|written|saved|moved|copied|updated)\b(?! (?:it|this|that) (?:for|on) you\b)/i],
    ['result', /\b(the |that |this |your )?(command|script|test(?:s| suite)?|build|program|check|query|run|ping|curl|install(?:ation)?)\b[^.!?\n]{0,30}\b(returned|printed|outputs?|output(?:ted)?|shows?|showed|shown|produced|passed|succeeded|failed|exited|completed|finished|says|said|reports?|reported)\b/i],
    ['output', /\bhere(?:'s| is| are) (?:the )?(?:actual |real |full )?(output|results?|logs?)\b|(^|\n)\s*(output|result|stdout|stderr)s?\s*:\s*(\n|`)/i],
    ['exit', /\bexit(?:ed)? (?:with )?(?:code|status) \d+\b|\b\d+ (?:tests? )?(?:passed|failed)\b|\ball (?:\d+ )?tests? (?:passed|pass)\b|\btests? (?:all )?passed\b/i],
    ['state', /\b(there (?:are|is)|you have|it has|the (?:folder|directory|repo|file) has) \d+ (?:files?|folders?|director(?:y|ies)|lines?|processes|tests?|errors?|commits?|rows?|entries)\b/i],
    ['terminal', /(^|\n)\s*(total \d+\s*\n|[d-][rwx-]{9}[.@+]?\s+\d+\s|root@[\w.-]+:[^\n]*[#$]|\d+ packets transmitted|PING \S+ \()/],
  ];
  const HEDGE = /\b(if|when|once|after|you can|you could|you'?ll|you will|you should|you'd|would|should|could|might|may|expect(?:ed)?|example|e\.g\.|sample|like this|haven'?t|have not|hasn'?t|didn'?t|did not|can'?t|cannot|couldn'?t|not yet|never|unable|let me|i'?ll|i will|i'?m going to|going to|i can|try|plan|want to|need to|before)\b/i;
  function sentences(t) { return String(t || '').replace(/```[\s\S]*?```/g, m => /```(text|txt|output|console|log)?\n/.test(m) ? m : ' ').split(/(?<=[.!?])\s+|\n{2,}/); }
  /** Does this reply claim output/results that no tool produced? opts.ran: a tool ran in this turn. Returns {flag, reasons[], quote}. */
  function unbackedClaims(text, opts) {
    if (opts && opts.ran) return { flag: false, reasons: [] };
    const reasons = []; let quote = '';
    for (const s of sentences(text)) {
      for (const [k, re] of CLAIMS) {
        const m = re.exec(s); if (!m) continue;
        if (k !== 'terminal' && HEDGE.test(s)) continue;
        if (k === 'terminal') {   // example output ("expected output looks like this:") is not a claim
          const at = String(text).indexOf(s.trim().slice(0, 20)), ctx = (at > 0 ? String(text).slice(Math.max(0, at - 240), at) : '') + s.slice(0, m.index);
          if (/\b(example|expected|sample|should look|would look|looks like|like this|you'?ll see|you should see|will (list|show|print))\b/i.test(ctx)) continue;
        }
        if (!reasons.includes(k)) reasons.push(k); if (!quote) quote = s.trim().slice(0, 160);
      }
    }
    return { flag: reasons.length > 0, reasons, quote };
  }
  const CLAIM_MSG = 'CHECK: your reply states command output or results, but no tool ran in this turn, so the app can\'t back them up. If you need a result, run the command now (run tool or ```run block) and report only what it really prints. If the result came from an earlier turn, say so. Otherwise say plainly that you have not run it. Do not repeat the unverified claim.';

  /* v2.11: a shell command shown in a ```bash fence instead of run. py-slugify (2026-11-09) wrote its test in a bash
   * fence, pasted "Output: hello-world-2026" under it and stopped; nothing ran. When a reply announces a run or states
   * output next to such a fence and no tool ran in that step, the model gets one bounce. */
  const SHELL_FENCE = /```[ \t]*(bash|sh|shell|zsh|console|terminal)?[ \t]*\n([\s\S]*?)```/g;
  const CMD_LINE = /^\s*(?:\$ )?(python3?|pytest|node|npm|npx|pip3?|cd|ls|cat|git|make|bash|sh|sed|grep|find|mkdir|rm|cp|mv|chmod|curl|go|cargo|java|javac|gcc)\b/;
  const RUN_INTENT = /\b(let me|let'?s|i'?ll|i will|i'?m going to|now i'?ll|going to) (run|execute|test|check|verify|try)\b|\b(running|executing)\b[^.\n]{0,40}(:|\bnow\b)|(^|\n)\s*(output|result)s?\s*:|\ball (checks|tests) pass|\bit (prints|printed|outputs|returned)\b/i;
  const FOR_USER = /\byou (can|could|should|may|might|'ll|will) (run|use|try|execute)\b|\bto run (it|this) yourself\b/i;
  /** {flag, cmd}: the reply shows a shell command in a non-run fence and talks as if it ran or will run now. */
  function unranShellFence(text) {
    const t = String(text || ''); let m, cmd = '';
    SHELL_FENCE.lastIndex = 0;
    while ((m = SHELL_FENCE.exec(t))) { const body = m[2] || ''; if (m[1] ? body.trim() : CMD_LINE.test(body)) { cmd = body.trim().split('\n')[0].slice(0, 120); break; } }
    if (!cmd) return { flag: false };
    const prose = t.replace(SHELL_FENCE, ' ');
    if (FOR_USER.test(prose) && !/\b(let me|i'?ll|i will)\b/i.test(prose)) return { flag: false };
    return { flag: RUN_INTENT.test(t), cmd };
  }
  const FENCE_MSG = 'CHECK: you put a command in a ```bash block. That only displays it: nothing ran and there is no output yet. If you meant to run it, call the run tool (or use a ```run block) and report only what it prints. If it was only an example for the user, say so plainly.';

  /* ------------------------------------------------------------------ 7. conversation context (v2.15) */
  // The model must always get the conversation, not only the last message. history() in chat.js keeps the newest turns
  // verbatim and folds older ones into a running summary (summarizeTurns); flattenToolTurns() rewrites native tool
  // turns as plain text for a call made without tools (OpenAI-compatible APIs reject role:"tool" without tools).
  const clip1 = (t, n) => { t = String(t == null ? '' : t).replace(/\s+/g, ' ').trim(); return t.length > n ? t.slice(0, n - 1) + '…' : t; };
  const textOfC = c => typeof c === 'string' ? c : Array.isArray(c) ? c.map(p => (p && p.type === 'text' ? p.text : p && p.type === 'image_url' ? '[image]' : '')).join(' ') : '';
  /** OpenAI messages -> the same conversation with no tool_calls / role:"tool" (call results become text). */
  function flattenToolTurns(msgs) {
    const out = [];
    for (const m of msgs || []) {
      if (m.role === 'tool') { out.push({ role: 'user', content: 'Tool result (from the app, not the user):\n' + textOfC(m.content) }); continue; }
      if (m.role === 'assistant' && Array.isArray(m.tool_calls) && m.tool_calls.length) {
        const calls = m.tool_calls.map(c => { const f = c.function || {}; return 'Called ' + f.name + ' ' + clip1(f.arguments, 600); }).join('\n');
        out.push({ role: 'assistant', content: ((m.content ? m.content + '\n' : '') + calls).trim() }); continue;
      }
      out.push(m);
    }
    // merge consecutive same-role text turns so strict providers accept the order
    const merged = [];
    for (const m of out) { const l = merged[merged.length - 1]; if (l && l.role === m.role && m.role !== 'system' && typeof l.content === 'string' && typeof m.content === 'string') l.content += '\n\n' + m.content; else merged.push(Object.assign({}, m)); }
    return merged;
  }
  /** Older chat turns ({role, text, tools[]}) -> a compact running summary, newest lines kept when over the cap. */
  function summarizeTurns(turns, prev, cap) {
    cap = cap || 3500; const lines = [];
    for (const t of turns || []) {
      const txt = clip1(t.text, t.role === 'user' ? 220 : 260);
      if (t.role === 'user' && txt) lines.push('- User: ' + txt);
      else if (t.role === 'assistant') { const tl = (t.tools || []).length ? ' [used ' + [...new Set(t.tools)].join(', ') + ']' : ''; if (txt || tl) lines.push('- You: ' + (txt || '(tool work)') + tl); }
    }
    let body = (prev ? String(prev).split('\n').filter(l => /^- /.test(l)) : []).concat(lines);
    let n = 0; const keep = [];
    for (let i = body.length - 1; i >= 0; i--) { n += body[i].length + 1; if (n > cap) break; keep.unshift(body[i]); }
    if (keep.length < body.length) keep.unshift('- (earlier turns condensed away)');
    return keep.join('\n');
  }
  /** A reply that interrogates: two or more questions, or a bulleted list of questions. */
  function interrogates(text) {
    const t = String(text || ''); const qs = (t.match(/\?(\s|$)/g) || []).length;
    const bulletQs = t.split('\n').filter(l => /^\s*([-*•]|\d+[.)])\s+.*\?\s*$/.test(l)).length;
    return { flag: bulletQs >= 2 || qs >= 3, questions: qs, bulletQs };
  }
  /** v2.16c: a reply that asks the user where or what something is (or says it can't see it) before looking. */
  const LOOK_RE = [
    /\bI(?:'| a)?m not (?:seeing|finding)\b/i, /\bI (?:can(?:'|no)?t|couldn'?t|could not|don'?t|do not) (?:see|find|locate|spot)\b(?! (?:images?|pictures?|photos?|the (?:image|picture|photo|screenshot)|your screen))/i,
    /\b(?:no|not seeing any|don'?t have any) (?:record|sign|trace) of\b/i,
    /\b(?:could|can) you (?:share|tell me|remind me|point me|let me know)\b[^?]*\b(?:which|what|where|name|called|path|folder|link)\b/i,
    /\bwhich (?:site|website|page|file|folder|project|app|repo|script|document)\b[^?]*\?/i,
    /\bwhat (?:name|was it called|did we call|is it called)\b[^?]*\?/i, /\bwhere (?:is it|did (?:you|we) (?:save|put|keep))\b[^?]*\?/i
  ];
  function asksInsteadOfLooking(text) { const t = String(text || ''); const hit = LOOK_RE.find(r => r.test(t)); return { flag: !!hit, why: hit ? String(hit) : '' }; }
  const LOOK_MSG = 'Check from the app: you told the user you can\'t find something, or asked them where or what it is, without looking. Look first: run ls and find in /root (YOUR WORKSPACE in the system prompt lists what is there), and use recall or agent_status. Then answer with what you found. Ask the user only if nothing fits, and say where you looked.';
  const INTERROG_MSG = 'Your reply asked several questions. Read the conversation again: a short message usually answers your last question. Answer with what you can do now and ask at most one short question, only if the answer would change the result. No bullet lists of questions.';

  /* ------------------------------------------------------------------ 8. long-running commands (v2.15) */
  // A server started in a run block (python3 -m http.server, npm start, node server.js, "cmd &") used to hold the run
  // open: its stdout kept the output pipe alive, so the step never ended. detachServers() rewrites such lines to
  // `nohup … > /tmp/argus-bg/<id>.log 2>&1 &`, waits 2 s, and prints the pid, the log and the URL.
  const SERVER = /^\s*(?:(?:sudo|exec|nohup)\s+)?(?:python3?\s+-m\s+(?:http\.server|flask\s+run|uvicorn)|python3?\s+\S*(?:server|app|manage)\.py\b(?:\s+runserver)?|flask\s+run|uvicorn\s|gunicorn\s|node\s+\S*(?:server|app|index)\.m?js\b|npm\s+(?:start|run\s+(?:dev|start|serve|preview))\b|yarn\s+(?:start|dev)\b|pnpm\s+(?:start|dev)\b|npx\s+(?:serve|http-server|vite|next\s+dev)\b|http-server\b|serve\s+-|php\s+-S\s|ruby\s+-run\s+-e\s+httpd|rails\s+s(?:erver)?\b|hugo\s+server|jekyll\s+serve|live-server\b)/i;
  function portOf(line) {
    const m = /(?:-p|--port)[ =](\d{2,5})\b/.exec(line) || /\bphp\s+-S\s+[\w.]*:(\d{2,5})/.exec(line) || /http\.server\s+(\d{2,5})\b/.exec(line) || /\bPORT=(\d{2,5})\b/.exec(line) || /:(\d{4,5})\b/.exec(line);
    if (m) return +m[1];
    if (/http\.server/.test(line)) return 8000;
    if (/flask\s+run/.test(line)) return 5000;
    if (/uvicorn/.test(line)) return 8000;
    if (/npm\s+(start|run)|yarn|pnpm|vite|next\s+dev/.test(line)) return null;
    if (/http-server|live-server/.test(line)) return 8080;
    if (/npx\s+serve|serve\s+-/.test(line)) return 3000;
    return null;
  }
  function detachServers(script, id) {
    id = String(id || Date.now().toString(36)).replace(/[^A-Za-z0-9_-]/g, '');
    const lines = String(script || '').split('\n'); let n = 0, port = null, log = null;
    const out = lines.map(l => {
      const t = l.trim(); if (!t || /^#/.test(t) || /<<\s*['"]?\w+/.test(t)) return l;
      const amp = /[^&]&\s*$/.test(t) && !/>\s*\S+\s+2>&1\s*&\s*$/.test(t) && !/^nohup\b.*>/.test(t);
      const srv = SERVER.test(t) && !/&\s*$/.test(t);
      if (!amp && !srv) return l;
      const cmd = t.replace(/&\s*$/, '').replace(/^nohup\s+/, '').trim(); n++;
      log = '/tmp/argus-bg/' + id + '-' + n + '.log'; port = port || portOf(cmd);
      return `mkdir -p /tmp/argus-bg; nohup bash -c ${JSON.stringify(cmd).replace(/\$/g, '\\$').replace(/`/g, '\\`')} > ${log} 2>&1 < /dev/null & echo "[background] started pid $! · log ${log}"`;
    });
    if (!n) return null;
    out.push(`sleep 2; U=$(grep -oE 'https?://(localhost|127\\.0\\.0\\.1|0\\.0\\.0\\.0)(:[0-9]+)?[^ ]*' ${log} 2>/dev/null | head -n 1); [ -n "$U" ] && echo "[background] URL $U"${port ? ` || echo "[background] URL http://localhost:${port}"` : ''}; tail -n 5 ${log} 2>/dev/null; true`);
    return { script: out.join('\n'), port, log, count: n };
  }

  /* ------------------------------------------------------------------ 9. narration + provider shapes (v2.15) */
  /** A short "here is what I'll do" line with no tool call after it (the turn would end on it). */
  function isNarration(text) {
    const t = String(text || '').trim();
    if (!t || t.length > 240 || /\?\s*$/.test(t) || t.split('\n').length > 3) return false;
    return /^(let me|let's|i'll|i will|i'm going to|first,?|next,?|now,?|then,?|checking|installing|setting up|starting|creating|writing|building|running|looking)\b/i.test(t) && /[.:…]\s*$/.test(t);
  }
  const NARRATE_MSG = 'Go ahead: do that now with your tools.';
  const NARRATE_PROMPT = 'NARRATE. Before a batch of tool calls, write one short sentence saying what you are about to do (e.g. "Let me check what is in the workspace."). When the plan changes, one short status line (e.g. "Flask is missing, installing it in the background."). No filler. Put the final answer last.';
  /** OpenAI-style messages -> Anthropic Messages API {system, messages} with text, tool_use and tool_result blocks in order. */
  function toAnthropic(msgs) {
    const sys = [], out = [];
    const push = (role, block) => { const l = out[out.length - 1]; if (l && l.role === role) l.content.push(block); else out.push({ role, content: [block] }); };
    for (const m of msgs || []) {
      if (m.role === 'system') { sys.push(textOfC(m.content)); continue; }
      if (m.role === 'tool') { push('user', { type: 'tool_result', tool_use_id: m.tool_call_id, content: textOfC(m.content) }); continue; }
      if (m.role === 'assistant') {
        if (m.content) push('assistant', { type: 'text', text: textOfC(m.content) });
        for (const c of m.tool_calls || []) { let input = {}; try { input = JSON.parse(c.function.arguments || '{}'); } catch (_) { input = { raw: c.function.arguments }; } push('assistant', { type: 'tool_use', id: c.id, name: c.function.name, input }); }
        continue;
      }
      if (Array.isArray(m.content)) for (const p of m.content) push('user', p.type === 'image_url' ? { type: 'image', source: { type: 'url', url: p.image_url.url } } : { type: 'text', text: p.text || '' });
      else push('user', { type: 'text', text: String(m.content == null ? '' : m.content) });
    }
    return { system: sys.join('\n\n'), messages: out };
  }

  return { TOOLS, ALIASES, parse, normalize, validate, checkRun, fakeRun, commands, insideWorkspace, StepGuard, feedback, editedFiles, CompletenessGate, checkScript, precheck, postcheck, refusal, looksLikeRefusal, PROMPT, _HARM: HARM, deletesEdited, DELETE_MSG, cutAfterFirstResultBlock,
    isNarration, NARRATE_MSG, NARRATE_PROMPT, toAnthropic, JOB_TOOLS, detachServers, SERVER_RE: SERVER, MGR_TOOLS, flattenToolTurns, summarizeTurns, interrogates, INTERROG_MSG, asksInsteadOfLooking, LOOK_MSG,
    toolSchemas, NATIVE_TOOLS, fromToolCall, cloudSchema, CLOUD_PROMPT, cloudSteer, CLOUD_ACTIONS, firstWords, checkWrite, writeScript, WRITE_MAX, unbackedClaims, CLAIM_MSG, unranShellFence, FENCE_MSG, patchPaths, extractPatches, AP_PY };
});
