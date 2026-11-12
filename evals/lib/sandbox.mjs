// Sandbox: each task gets a fresh temp dir mounted as /root inside an unprivileged user+mount+pid+net namespace
// (unshare -Urmnpf). The agent is "root" there, like the phone's proot, with no network, a private /tmp, and
// /workspace + /home remounted read-only. Docker-less and chroot-ish: the host filesystem is visible but the only
// writable places are /root (the task dir) and /tmp (tmpfs). Every command has a hard timeout.
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const PREP = [
  'set -e',
  'mount --bind "$EVAL_DIR" /root',
  'mount -t tmpfs tmpfs /tmp',
  'for d in /workspace /home /var/tmp /opt /srv /mnt; do [ -d "$d" ] && mount --bind "$d" "$d" 2>/dev/null && mount -o remount,bind,ro "$d" 2>/dev/null || true; done',
  'python3 -c "import socket,fcntl,struct;s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM);fcntl.ioctl(s,0x8914,struct.pack(\'16sH14s\',b\'lo\',0x49,b\'\'))" 2>/dev/null || true',
  'set +e',
  'cd /root',
  'cmd="$EVAL_CMD"; unset EVAL_CMD EVAL_DIR',
  'exec timeout -k 3 "$EVAL_TIMEOUT" bash --norc --noprofile -c "$cmd" 2>&1',
].join('\n');

let probed = null;
export function sandboxAvailable() {
  if (probed !== null) return probed;
  const r = spawnSync('unshare', ['-Urmnpf', '--mount-proc', 'true'], { encoding: 'utf8' });
  probed = r.status === 0;
  return probed;
}

export class Sandbox {
  constructor(prefix = 'aeval-') {
    if (!sandboxAvailable()) throw new Error('unshare user namespaces are not available here; the harness refuses to run agent commands unsandboxed');
    this.dir = fs.mkdtempSync(path.join(os.tmpdir(), prefix));
  }
  /** Run a bash script as root in /root. Returns {code, out, timedOut, ms}. */
  run(script, { timeout = 30, maxOut = 20000 } = {}) {
    const t0 = Date.now();
    const r = spawnSync('unshare', ['-Urmnpf', '--mount-proc', 'bash', '--norc', '--noprofile', '-c', PREP], {
      stdio: ['ignore', 'pipe', 'pipe'], encoding: 'utf8', timeout: (timeout + 10) * 1000, maxBuffer: 16 * 1024 * 1024,
      env: { PATH: '/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin', HOME: '/root', USER: 'root', LANG: 'C.UTF-8', TERM: 'dumb',
        EVAL_DIR: this.dir, EVAL_CMD: script, EVAL_TIMEOUT: String(timeout), npm_config_update_notifier: 'false', PIP_DISABLE_PIP_VERSION_CHECK: '1', PYTHONDONTWRITEBYTECODE: '1', PIP_NO_INDEX: '' },
    });
    let out = (r.stdout || '') + (r.stderr || '');
    const timedOut = r.status === 124 || r.status === 137 || r.error?.code === 'ETIMEDOUT';
    if (out.length > maxOut) out = '…' + out.slice(-maxOut);
    return { code: r.status ?? -1, out, timedOut, ms: Date.now() - t0 };
  }
  /** Write files (relative to /root) from the host side. */
  write(files) {
    for (const [rel, content] of Object.entries(files || {})) {
      const p = path.join(this.dir, rel); fs.mkdirSync(path.dirname(p), { recursive: true }); fs.writeFileSync(p, content);
    }
  }
  read(rel) { try { return fs.readFileSync(path.join(this.dir, rel), 'utf8'); } catch { return null; } }
  copyIn(srcDir, destRel = '') {
    if (fs.existsSync(srcDir)) fs.cpSync(srcDir, path.join(this.dir, destRel), { recursive: true });
  }
  /** Relative paths of all files under /root (skipping heavy dirs). */
  files(rel = '') {
    const base = path.join(this.dir, rel), out = [];
    const walk = d => { for (const e of fs.readdirSync(d, { withFileTypes: true })) { if (/^(node_modules|\.git|__pycache__|\.npm|\.cache|\.local)$/.test(e.name)) continue; const p = path.join(d, e.name); e.isDirectory() ? walk(p) : out.push(path.relative(base, p)); } };
    if (fs.existsSync(base)) walk(base);
    return out.sort();
  }
  dispose() { try { fs.rmSync(this.dir, { recursive: true, force: true }); } catch { /* chmod'd files */ spawnSync('chmod', ['-R', 'u+rwX', this.dir]); fs.rmSync(this.dir, { recursive: true, force: true }); } }
}
