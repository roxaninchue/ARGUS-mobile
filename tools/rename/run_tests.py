#!/usr/bin/env python3
"""Run pure JVM unit tests with standalone kotlinc (no Gradle), pulling in main sources by unresolved name.

  python3 tools/rename/run_tests.py QuickRepliesTest ProactiveDialTest ...
Env: JDK 17 at /workspace/tools/jdk-17.0.21+9, kotlinc at /workspace/tools/kotlinc, extra jars in $JARS (default
/tmp/rnj: json-20240303, kotlinx-coroutines core/test 1.8.1, sqlite-jdbc 3.46.1.3, slf4j-api) plus junit.jar and
hamcrest.jar from the repo root. Output goes to /tmp/rn-tests/<Test>. Files importing android.*/androidx.* are never
added automatically: a test that needs them is reported as "needs Android".
"""
import os, re, subprocess, sys, glob

R = os.getcwd()
JDK = '/workspace/tools/jdk-17.0.21+9'
KC = '/workspace/tools/kotlinc'
JARS = os.environ.get('JARS', '/tmp/rnj')
CP = ':'.join(glob.glob(f'{JARS}/*.jar') + [f'{R}/junit.jar', f'{R}/hamcrest.jar'])
MAIN = 'app/src/main/java/com/argus/assistant'
TEST = 'app/src/test/java/com/argus/assistant'
env = dict(os.environ, JAVA_HOME=JDK, PATH=f'{JDK}/bin:{KC}/bin:' + os.environ['PATH'])

DECL = re.compile(r'^(?:@\S+\s+)*(?:(?:private|internal|public|data|sealed|enum|abstract|open|inline|value|annotation|fun|const|operator|infix|suspend)\s+)*'
                  r'(?:class|object|interface|fun|val|var|typealias)\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?(\w+)', re.M)
index = {}
for f in glob.glob(f'{MAIN}/**/*.kt', recursive=True) + glob.glob('storeapi/src/main/java/**/*.kt', recursive=True) + \
        [g for g in glob.glob(f'{TEST}/**/*.kt', recursive=True) if not g.endswith('Test.kt')]:
    src = open(f, encoding='utf8').read()
    code = '\n'.join(l for l in src.split('\n') if not l.lstrip().startswith(('//', '*', '/*')))
    pure = not re.search(r'^import (android|androidx)\.', src, re.M) and not re.search(r'(?<![.\w"])androidx?\.[a-z]+\.[A-Z]', code) \
        and not f.endswith(('/agent/Settings.kt',))
    for m in DECL.finditer(src):
        index.setdefault(m.group(1), []).append((f, pure))


def compile_(files, out):
    r = subprocess.run(['kotlinc', '-nowarn', '-jvm-target', '17', '-cp', CP, *sorted(files), '-d', out],
                       capture_output=True, text=True, env=env)
    return r.returncode, r.stderr


def run(test):
    tf = glob.glob(f'{TEST}/**/{test}.kt', recursive=True)
    if not tf:
        return test, 'missing', ''
    files, out, impure = set(tf), f'/tmp/rn-tests/{test}', set()
    sf = f'/tmp/rn-tests/{test}.files'
    if os.path.exists(sf):
        files |= set(open(sf).read().split())
    files |= set(os.environ.get('EXTRA', '').split())
    t0 = __import__('time').time()
    # same-package main files referenced by simple name are found through errors below
    for _ in range(25):
        if __import__('time').time() - t0 > float(os.environ.get('BUDGET', '80')):
            open(sf, 'w').write('\n'.join(sorted(files)))
            return test, 'unfinished', f'{len(files)} files so far (rerun to continue)'
        code, err = compile_(files, out)
        if code == 0:
            break
        names = set(re.findall(r"[Uu]nresolved reference:? '?(\w+)'?", err))
        names |= set(re.findall(r"[Uu]nresolved reference '(\w+)'", err))
        for f in list(files):
            names |= set(re.findall(r'com\.argus\.assistant\.[a-z.]+\.([A-Z]\w*)', open(f, encoding='utf8').read()))
        stubbed = {re.search(r'(?:object|class) (\w+)', open(x).read()).group(1) for x in files if x.startswith('/tmp/')}
        names -= stubbed
        add = set()
        for n in names:
            for f, pure in index.get(n, []):
                if f in files:
                    continue
                if pure:
                    add.add(f)
                else:
                    impure.add(f)
        if not add:
            errs = [l for l in err.split('\n') if 'error:' in l][:8]
            return test, 'compile-fail', '\n'.join(errs) + (f'\n  needs Android: {sorted(impure)[:5]}' if impure else '')
        files |= add
        os.makedirs('/tmp/rn-tests', exist_ok=True); open(sf, 'w').write('\n'.join(sorted(files)))
    pkg = re.search(r'^package ([\w.]+)', open(tf[0]).read(), re.M).group(1)
    r = subprocess.run(['java', '-cp', f'{out}:{CP}:{KC}/lib/kotlin-stdlib.jar:{R}/app/src/test/resources',
                        'org.junit.runner.JUnitCore', f'{pkg}.{test}'], capture_output=True, text=True, env=env, cwd=f'{R}/app')
    tail = [l for l in r.stdout.split('\n') if l.startswith(('OK', 'Tests run', 'FAILURES')) or re.match(r'^\d+\) ', l)]
    return test, 'pass' if r.returncode == 0 else 'fail', '\n'.join(tail) + (f'\n  ({len(files)} files)')


if __name__ == '__main__':
    for t in sys.argv[1:]:
        name, status, info = run(t)
        print(f'== {name}: {status}\n{info}', flush=True)
