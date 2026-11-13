#!/usr/bin/env python3
"""MC GUI's server-bench gate: the full server bench (bench/bench.py) on every dev -> main promotion, on this box.

The box has no public inbound network and the repository is public, so it runs no GitHub runner: this poller asks
GitHub instead. `tick` (what mcgui-server-bench.timer runs every 2 minutes):

* finds the open PR whose base is $MCGUI_CI_BASE and whose head is the branch $MCGUI_CI_HEAD of $MCGUI_CI_REPO itself
  (a fork's branch of that name is ignored, always);
* if its head commit has no final commit status with context $MCGUI_CI_CONTEXT, posts `pending`, benches the base
  commit (unless a result for it is stored) and the head commit, compares them under gates.json, writes the
  before/after table as one PR comment (updated in place) and posts `success` or `failure`. A fault of the box or
  the run (out of memory, out of time, the box too busy to measure, GitHub unreachable) is retried on the next tick,
  up to $MCGUI_CI_MAX_ATTEMPTS attempts per commit, and then reported as `error` with the reason.

Repository code at the commit under test (its Gradle build, bench.py, the server) runs only in a transient systemd
unit as the unprivileged CI user, through bench_unit.sh: no GitHub token in its environment, /root and /home
hidden, no new privileges. Only this script, deployed root-owned to /opt/mcgui-ci and never updated from a checkout,
holds the token and talks to GitHub.

Other commands, for people:

    server_bench.py run <sha> [--out f.json]        bench one commit the way a tick does (no GitHub writes)
    server_bench.py compare <base.json> <head.json> the verdict and table for two results, under gates.json
    server_bench.py noise <a.json> <b.json> ...     spread of every number across runs of one commit
"""
import argparse
import base64
import fcntl
import fnmatch
import json
import math
import os
import re
import shutil
import signal
import statistics
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
E = os.environ.get
ROOT = E('MCGUI_CI_ROOT', '/var/lib/mcgui-ci')
CFG = {
    'repo': E('MCGUI_CI_REPO', 'resourcepack-ai/mcgui'),
    'base': E('MCGUI_CI_BASE', 'main'),
    'head': E('MCGUI_CI_HEAD', 'dev'),
    'context': E('MCGUI_CI_CONTEXT', 'server-bench'),
    'gh_user': E('MCGUI_CI_GH_USER', ''),
    'max_attempts': int(E('MCGUI_CI_MAX_ATTEMPTS', '3')),
    'user': E('MCGUI_CI_USER', 'mcgui-ci'),
    'slice': E('MCGUI_CI_SLICE', 'atlas-mcgui.slice'),
    # The full bench peaked at 4.2 GB (4.0 GB of it the server JVM, 3 GB heap) on the box: throttle past 4.75, kill at 5.25.
    'memory_high': E('MCGUI_CI_MEMORY_HIGH', '4864M'),
    'memory_max': E('MCGUI_CI_MEMORY_MAX', '5376M'),
    'build_timeout': E('MCGUI_CI_BUILD_TIMEOUT', '25min'),
    'bench_timeout': E('MCGUI_CI_BENCH_TIMEOUT', '40min'),
    'build_lock': E('MCGUI_CI_BUILD_LOCK', ''),
    'jvm_lock': E('MCGUI_CI_JVM_LOCK', ''),
    'gate_minutes': E('MCGUI_CI_GATE_MINUTES', '30'),
    'bench_args': E('MCGUI_CI_BENCH_ARGS', ''),
    'ports': E('MCGUI_CI_PORTS', '25990'),
    'java21': E('MCGUI_CI_JAVA21', '/usr/lib/jvm/java-21-openjdk-amd64'),
    'java25': E('MCGUI_CI_JAVA25', '/usr/lib/jvm/java-25-openjdk-amd64/bin/java'),
}
RESULTS = os.path.join(ROOT, 'results')
LOGS = os.path.join(ROOT, 'logs')
STATE = os.path.join(ROOT, 'state')
WORK = os.path.join(ROOT, 'work')
HOME = os.path.join(ROOT, 'home')
UNITS = ('mcgui-bench-build.service', 'mcgui-bench-run.service')
FINAL = ('success', 'failure', 'error')
MARKER = '<!-- mcgui-ci:server-bench:{context} -->'


def log(*a):
    print(*a, file=sys.stderr, flush=True)


class RunFault(Exception):
    """A run that did not finish: retried on the next tick until the attempts run out. [head_code] is a fault the
    head commit itself caused (it does not build), reported as `failure` once the attempts are used up, not `error`."""

    def __init__(self, reason, head_code=False):
        super().__init__(reason)
        self.reason = reason
        self.head_code = head_code


# ----------------------------------------------------------------------------------------------------- GitHub (gh)

GH_ENV = None


def gh_env():
    """The environment for gh: root's gh login for $MCGUI_CI_GH_USER, passed to gh alone and never to a bench."""
    global GH_ENV
    if GH_ENV is None:
        env = dict(os.environ, GH_NO_UPDATE_NOTIFIER='1', GH_PROMPT_DISABLED='1', NO_COLOR='1')
        if CFG['gh_user']:
            r = subprocess.run(['gh', 'auth', 'token', '--hostname', 'github.com', '--user', CFG['gh_user']],
                               capture_output=True, text=True)
            if r.returncode != 0 or not r.stdout.strip():
                raise RunFault(f'no gh login for {CFG["gh_user"]} on the box')
            env['GH_TOKEN'] = r.stdout.strip()
        GH_ENV = env
    return GH_ENV


def gh(path, method='GET', body=None, paginate=False):
    cmd = ['gh', 'api', '-X', method, path, '-H', 'Accept: application/vnd.github+json']
    if paginate:
        cmd += ['--paginate', '--slurp']
    if body is not None:
        cmd += ['--input', '-']
    for attempt in range(3):
        r = subprocess.run(cmd, input=json.dumps(body) if body is not None else None, capture_output=True, text=True,
                           env=gh_env())
        if r.returncode == 0:
            out = r.stdout.strip()
            if paginate:
                # --slurp: one array of every page's array.
                return [x for page in json.loads(out) for x in page]
            return json.loads(out) if out else None
        time.sleep(2 * (attempt + 1))
    raise RunFault(f'GitHub: {method} {path.split("?")[0]} failed: {r.stderr.strip()[:200]}')


def promotion_prs():
    """The open PRs from this repository's own $MCGUI_CI_HEAD into $MCGUI_CI_BASE (GitHub allows one at most)."""
    repo, owner = CFG['repo'], CFG['repo'].split('/')[0]
    prs = gh(f'repos/{repo}/pulls?state=open&base={CFG["base"]}&head={owner}:{CFG["head"]}&per_page=100', paginate=True)
    return [p for p in prs or []
            if p['state'] == 'open' and p['base']['ref'] == CFG['base'] and p['head']['ref'] == CFG['head']
            and (p['head'].get('repo') or {}).get('full_name') == repo and p['base']['repo']['full_name'] == repo]


def latest_status(sha):
    """The newest status with our context on [sha], or None. GitHub lists them newest first."""
    for s in gh(f'repos/{CFG["repo"]}/commits/{sha}/statuses?per_page=100', paginate=True) or []:
        if s['context'] == CFG['context']:
            return s
    return None


def post_status(sha, state, description, url=None):
    body = {'state': state, 'context': CFG['context'], 'description': description[:140]}
    if url:
        body['target_url'] = url
    gh(f'repos/{CFG["repo"]}/statuses/{sha}', 'POST', body)
    log(f'status {state} on {sha[:7]}: {description[:140]}')


def upsert_comment(number, body):
    """Our one comment on the PR, made or edited in place; its URL."""
    marker = MARKER.format(context=CFG['context'])
    body = marker + '\n' + body
    for c in gh(f'repos/{CFG["repo"]}/issues/{number}/comments?per_page=100', paginate=True) or []:
        if c['body'].startswith(marker):
            return gh(f'repos/{CFG["repo"]}/issues/comments/{c["id"]}', 'PATCH', {'body': body})['html_url']
    return gh(f'repos/{CFG["repo"]}/issues/{number}/comments', 'POST', {'body': body})['html_url']


def head_rules(sha):
    """gates.json as committed at the head commit (an accepted cost is a reviewed change to it, landing with the
    change), or the deployed copy when the commit has none."""
    try:
        meta = gh(f'repos/{CFG["repo"]}/contents/ci/box/gates.json?ref={sha}')
        text, where = base64.b64decode(meta['content']).decode(), f'ci/box/gates.json at {sha[:7]}'
    except RunFault as e:
        if 'HTTP 404' not in e.reason:
            raise
        text, where = open(os.path.join(HERE, 'gates.json')).read(), 'the deployed gates.json'
    try:
        return check_rules(json.loads(text)), where
    except (ValueError, KeyError, TypeError) as e:
        raise RunFault(f'{where} is not valid: {e}', head_code=True)


# ----------------------------------------------------------------------------------------------------- running

class Lock:
    """flock(1)-compatible lock on [path], held in the with block; [wait]=False raises BlockingIOError if taken."""

    def __init__(self, path, wait=True):
        self.path, self.wait, self.f = path, wait, None

    def __enter__(self):
        if self.path:
            self.f = open(self.path, 'a')
            t = time.time()
            fcntl.flock(self.f, fcntl.LOCK_EX | (0 if self.wait else fcntl.LOCK_NB))
            if time.time() - t > 1:
                log(f'waited {time.time() - t:.0f} s for {self.path}')
        return self

    def __exit__(self, *e):
        if self.f:
            self.f.close()


def stop_units():
    """Stops a bench unit left running by a poller that was killed: we hold the poller lock, so nothing owns it."""
    for u in UNITS:
        subprocess.run(['systemctl', 'stop', u], capture_output=True)
        subprocess.run(['systemctl', 'reset-failed', u], capture_output=True)


def unit(name, args, logfile, timeout):
    """Runs bench_unit.sh [args] as the CI user in its own sandboxed unit and waits for it; raises RunFault with the
    unit's result if it did not exit 0."""
    env = {
        'HOME': HOME, 'GRADLE_USER_HOME': os.path.join(HOME, '.gradle'), 'JAVA_HOME': CFG['java21'],
        'PATH': f'{CFG["java21"]}/bin:/usr/local/bin:/usr/bin:/bin', 'LANG': 'C.UTF-8',
        'MCGUI_CI_WORK': WORK, 'MCGUI_CI_REPO_URL': f'https://github.com/{CFG["repo"]}.git',
        'MCGUI_CI_BENCH_ARGS': CFG['bench_args'], 'MCGUI_CI_PORTS': CFG['ports'],
        'UIE_BENCH_SERVER': os.path.join(ROOT, 'server'), 'UIE_BENCH_PROBE': os.path.join(ROOT, 'probe', 'probe-plugin.jar'),
        'UIE_BENCH_JAVA': CFG['java25'],
        # The locks and the quiet-box gate are this poller's: the unit cannot open the box's lock files.
        'UIE_BENCH_BUILD_LOCK': '', 'UIE_BENCH_JVM_LOCK': '', 'UIE_BENCH_GATE': '',
    }
    props = {
        'User': CFG['user'], 'Group': CFG['user'],
        'MemoryHigh': CFG['memory_high'], 'MemoryMax': CFG['memory_max'], 'MemorySwapMax': '1G',
        'RuntimeMaxSec': CFG['build_timeout'] if name.endswith('build') else CFG['bench_timeout'],
        'CPUWeight': '25', 'IOWeight': '25',
        'WorkingDirectory': WORK,
        'ProtectHome': 'yes', 'ProtectSystem': 'strict', 'ReadWritePaths': f'{WORK} {HOME}',
        'PrivateTmp': 'yes', 'PrivateDevices': 'yes', 'NoNewPrivileges': 'yes', 'RestrictSUIDSGID': 'yes',
        'ProtectKernelTunables': 'yes', 'ProtectKernelModules': 'yes', 'ProtectControlGroups': 'yes',
        'CapabilityBoundingSet': '', 'UMask': '0022',
        # Other users' processes hidden; /proc/loadavg stays (bench.py reads it).
        'ProtectProc': 'invisible', 'ProtectClock': 'yes', 'ProtectKernelLogs': 'yes', 'ProtectHostname': 'yes',
        'RestrictNamespaces': 'yes', 'RestrictRealtime': 'yes', 'LockPersonality': 'yes',
        'SystemCallArchitectures': 'native', 'SystemCallFilter': '@system-service',
        'RestrictAddressFamilies': 'AF_UNIX AF_INET AF_INET6 AF_NETLINK',
        'StandardOutput': f'append:{logfile}', 'StandardError': f'append:{logfile}',
    }
    cmd = ['systemd-run', f'--unit={name}', f'--slice={CFG["slice"]}', '--collect', '--wait', '--service-type=exec']
    cmd += [f'--property={k}={v}' for k, v in props.items()] + [f'--setenv={k}={v}' for k, v in env.items()]
    cmd += [os.path.join(HERE, 'bench_unit.sh')] + list(args)
    t = time.time()
    r = subprocess.run(cmd, capture_output=True, text=True)
    out = r.stdout + r.stderr
    m = re.search(r'Finished with result: (\S+)', out)
    result = m.group(1) if m else ('success' if r.returncode == 0 else 'unknown')
    log(f'{name}: {result} in {time.time() - t:.0f} s')
    if result != 'success':
        why = {'oom-kill': 'out of memory (MemoryMax ' + CFG['memory_max'] + ')',
               'timeout': 'out of time (RuntimeMaxSec ' + props['RuntimeMaxSec'] + ')',
               'signal': 'killed by a signal', 'exit-code': 'exited with an error'}.get(result, result)
        if not m:
            why = 'could not be started: ' + ' '.join(out.split())[:200]
        tail = last_lines(logfile, 4)
        raise RunFault(f'{name.split("-")[-1]} {why}' + (f': {tail}' if tail else ''),
                       head_code=result == 'exit-code' and name.endswith('build'))
    return time.time() - t


def last_lines(path, n):
    try:
        lines = [l.strip() for l in open(path, errors='replace').read().splitlines() if l.strip() and not l.startswith('==')]
        return ' | '.join(lines[-n:])[-300:]
    except OSError:
        return ''


def result_path(sha):
    return os.path.join(RESULTS, f'{sha}.json')


def bench_sha(sha, out=None):
    """Builds and benches [sha] in the sandbox; the stored result (with its log path and time taken added)."""
    logfile = os.path.join(LOGS, f'{time.strftime("%Y%m%d-%H%M%S")}-{sha[:12]}.log')
    open(logfile, 'a').close()
    log(f'benching {sha[:7]}, log {logfile}')
    t0 = time.time()
    with Lock(CFG['build_lock']):
        build_s = unit('mcgui-bench-build', ['build', sha], logfile, CFG['build_timeout'])
    raw = os.path.join(WORK, 'out', f'{sha}.json')
    if os.path.exists(raw):
        os.remove(raw)
    with Lock(CFG['jvm_lock']):
        g = subprocess.run(['sh', os.path.join(HERE, 'gate.sh'), CFG['gate_minutes']], capture_output=True, text=True)
        gate = g.stdout.strip()
        with open(logfile, 'a') as f:
            f.write(f'== gate: {gate}\n')
        if g.returncode != 0:
            raise RunFault(f'the box stayed too busy to measure for {CFG["gate_minutes"]} minutes ({gate})')
        bench_s = unit('mcgui-bench-run', ['bench', sha, raw], logfile, CFG['bench_timeout'])
    try:
        doc = json.load(open(raw))
    except (OSError, ValueError) as e:
        raise RunFault(f'the bench wrote no result ({e}); see {logfile}')
    if not sha.startswith(str(doc.get('commit', ''))) or not doc.get('commit') or not doc.get('results'):
        raise RunFault(f'the bench result is for {doc.get("commit")!r}, not {sha[:7]}, or empty; see {logfile}')
    doc['ci'] = {'sha': sha, 'log': logfile, 'build_s': round(build_s), 'bench_s': round(bench_s),
                 'total_s': round(time.time() - t0), 'gate': gate, 'finished': time.strftime('%Y-%m-%d %H:%M:%S')}
    dest = out or result_path(sha)
    tmp = dest + '.tmp'
    json.dump(doc, open(tmp, 'w'), indent=1)
    os.replace(tmp, dest)
    log(f'{sha[:7]} benched in {time.time() - t0:.0f} s: {dest}')
    return doc


def stored_or_bench(sha):
    p = result_path(sha)
    if os.path.exists(p):
        log(f'stored result for {sha[:7]}: {p}')
        return json.load(open(p))
    return bench_sha(sha)


# ----------------------------------------------------------------------------------------------------- comparing

KINDS = ('exact', 'timing', 'report')


def check_rules(doc):
    """gates.json, validated: a list of rules, each {match, kind, worse, rel, abs}; first match wins."""
    rules = doc['rules']
    for r in rules:
        if not isinstance(r['match'], str) or r['kind'] not in KINDS:
            raise ValueError(f'bad rule {r}')
        if r['kind'] != 'report':
            if r['worse'] not in ('higher', 'lower') or float(r['rel']) < 0 or float(r.get('abs', 0)) < 0:
                raise ValueError(f'bad rule {r}')
    return rules


def load_rules(path=None):
    return check_rules(json.load(open(path or os.path.join(HERE, 'gates.json'))))


def flat(results):
    out = {}
    for cell, r in (results or {}).items():
        if isinstance(r, dict):
            for k, v in r.items():
                out[f'{cell}.{k}'] = v
    return out


def number(v):
    return isinstance(v, (int, float)) and not isinstance(v, bool) and math.isfinite(v)


def rule_for(metric, rules):
    for r in rules:
        if fnmatch.fnmatchcase(metric, r['match']):
            return r
    return {'kind': 'report'}


def compare(base, head, rules):
    """Rows (metric, base, head, rule, verdict, note) for every number of either run, and the failing rows.
    verdict: 'fail', 'ok' or 'report' (not gated)."""
    old, new = flat(base['results']), flat(head['results'])
    rows, fails = [], []
    cells = sorted({k.split('.')[0] for k in list(old) + list(new)})
    for cell in cells:
        be, he = old.get(f'{cell}.error'), new.get(f'{cell}.error')
        if he and not be:
            row = (f'{cell}.error', '', str(he)[:120], None, 'fail', 'the cell failed at head, not at base')
            rows.append(row)
            fails.append(row)
        elif he or be:
            rows.append((f'{cell}.error', str(be or '')[:120], str(he or '')[:120], None, 'report', 'failed at base'))
    for k in sorted(set(old) | set(new)):
        o, n = old.get(k), new.get(k)
        if k.endswith('.error') or k.endswith('.server_died') or not (number(o) or number(n)):
            continue
        r = rule_for(k, rules)
        if r['kind'] == 'report':
            rows.append((k, o, n, r, 'report', ''))
            continue
        if not number(o):
            rows.append((k, o, n, r, 'report', 'not measured at base'))
            continue
        if not number(n):
            row = (k, o, n, r, 'fail', 'measured at base, missing at head')
            rows.append(row)
            fails.append(row)
            continue
        rel, ab = float(r['rel']), float(r.get('abs', 0))
        limit = o * (1 + rel) + ab if r['worse'] == 'higher' else o * (1 - rel) - ab
        worse = n > limit if r['worse'] == 'higher' else n < limit
        row = (k, o, n, r, 'fail' if worse else 'ok', f'limit {fmt(limit)}')
        rows.append(row)
        if worse:
            fails.append(row)
    return rows, fails


def fmt(v):
    if v is None or v == '':
        return '–'
    if isinstance(v, float):
        return f'{v:.3g}' if abs(v) < 100 else f'{v:.0f}'
    return str(v)


def change(o, n):
    if not (number(o) and number(n)):
        return ''
    if o == 0:
        return '' if n == 0 else 'new'
    return f'{(n - o) / abs(o) * 100:+.0f}%'


def rule_text(r):
    if not r:
        return 'the cell must run'
    if r['kind'] == 'report':
        return 'report'
    sign = '+' if r['worse'] == 'higher' else '-'
    s = f'{r["kind"]}: {sign}{float(r["rel"]) * 100:.0f}%'
    if float(r.get('abs', 0)):
        s += f' {sign} {fmt(float(r["abs"]))}'
    return s


def table(rows):
    out = ['| metric | base | head | change | rule | |', '|---|---:|---:|---:|---|---|']
    for k, o, n, r, v, note in rows:
        mark = {'fail': '❌', 'ok': '✅', 'report': ''}[v]
        cell_note = f' {note}' if note and v == 'fail' else ''
        out.append(f'| `{k}` | {fmt(o)} | {fmt(n)} | {change(o, n)} | {rule_text(r)} | {mark}{cell_note} |')
    return '\n'.join(out)


def describe(fails, gated, base_sha, head_sha):
    if not fails:
        return f'no regression in {gated} gated numbers against base {base_sha[:7]}'
    parts = []
    for k, o, n, r, v, note in fails:
        parts.append(f'{k} {change(o, n) or note}')
    return f'{len(fails)} regression{"s" if len(fails) > 1 else ""}: ' + ', '.join(parts)


def report_md(pr, base, head, rows, fails, where):
    gated = sum(1 for r in rows if r[4] != 'report')
    bci, hci = base.get('ci', {}), head.get('ci', {})
    verdict = '❌ **failure**' if fails else '✅ **success**'
    lines = [
        f'### Server bench: {verdict}',
        '',
        f'`bench/bench.py {CFG["bench_args"] or "(full)"}` on the bench box, base `{bci.get("sha", "")[:7]}` '
        f'({CFG["base"]}) against head `{hci.get("sha", "")[:7]}` ({CFG["head"]}). '
        f'{gated} numbers gated by {where}; {len(fails)} worse than their margin.',
        '',
        f'- base: run {base.get("started", "?")}, load at start {base.get("load_at_start", "?")}, log `{bci.get("log", "?")}`',
        f'- head: run {head.get("started", "?")}, load at start {head.get("load_at_start", "?")}, log `{hci.get("log", "?")}`'
        f', {hci.get("total_s", "?")} s',
        '',
    ]
    failing = [r for r in rows if r[4] == 'fail']
    if failing:
        lines += ['#### Worse than allowed', '', table(failing), '']
    lines += ['<details><summary>Gated numbers</summary>', '', table([r for r in rows if r[4] == 'ok']), '', '</details>', '']
    lines += ['<details><summary>Report only (too noisy, or not a cost)</summary>', '',
              table([r for r in rows if r[4] == 'report']), '', '</details>', '']
    lines.append('An accepted cost is a reviewed change to `ci/box/gates.json`. Re-run: push a new commit.')
    return '\n'.join(lines)


# ----------------------------------------------------------------------------------------------------- the tick

def attempts_path(sha):
    return os.path.join(STATE, 'attempts', f'{CFG["context"]}-{sha}.json')


def read_attempts(sha):
    try:
        return json.load(open(attempts_path(sha)))
    except (OSError, ValueError):
        return {'n': 0, 'last': ''}


def write_attempts(sha, a):
    os.makedirs(os.path.dirname(attempts_path(sha)), exist_ok=True)
    json.dump(a, open(attempts_path(sha), 'w'))


def check_pr(pr):
    head_sha, base_sha, number = pr['head']['sha'], pr['base']['sha'], pr['number']
    st = latest_status(head_sha)
    if st and st['state'] in FINAL:
        log(f'#{number} head {head_sha[:7]} already has {st["state"]} ({st["description"]}): nothing to do')
        return
    a = read_attempts(head_sha)
    if a['n'] >= CFG['max_attempts']:
        post_status(head_sha, 'failure' if a.get('head_code') else 'error',
                    f'gave up after {a["n"]} attempts: {a["last"] or "each was killed before it finished"}')
        return
    if st:
        log(f'#{number} head {head_sha[:7]} has a stale pending from an earlier run ({st["description"]}): retrying')
    post_status(head_sha, 'pending', f'attempt {a["n"] + 1}/{CFG["max_attempts"]}: benching base {base_sha[:7]} and head {head_sha[:7]}')
    # Counted before the run: an attempt killed half way (a reboot, OOM) still counts towards the cap.
    a['n'] += 1
    a['last'] = f'attempt {a["n"]} was killed before it finished'
    write_attempts(head_sha, a)
    try:
        rules, where = head_rules(head_sha)
        base = stored_or_bench(base_sha)
        head = stored_or_bench(head_sha)
        rows, fails = compare(base, head, rules)
        gated = sum(1 for r in rows if r[4] != 'report')
        url = upsert_comment(number, report_md(pr, base, head, rows, fails, where))
        post_status(head_sha, 'failure' if fails else 'success', describe(fails, gated, base_sha, head_sha), url)
        os.remove(attempts_path(head_sha))
    except RunFault as e:
        log(f'attempt {a["n"]} for {head_sha[:7]} failed: {e.reason}')
        a.update(last=e.reason, head_code=e.head_code)
        write_attempts(head_sha, a)
        if a['n'] >= CFG['max_attempts']:
            post_status(head_sha, 'failure' if e.head_code else 'error', f'gave up after {a["n"]} attempts: {e.reason}')
        else:
            post_status(head_sha, 'pending', f'attempt {a["n"]} failed, retrying next tick: {e.reason}')


def tick():
    for d in (RESULTS, LOGS, STATE):
        os.makedirs(d, exist_ok=True)
    try:
        lock = Lock(os.path.join(STATE, 'server-bench.lock'), wait=False).__enter__()
    except BlockingIOError:
        log('another tick is running: nothing to do')
        return
    # Killed (systemctl stop, a reboot): take the bench unit down with us, and say so in its log.
    signal.signal(signal.SIGTERM, lambda *a: (stop_units(), sys.exit(143)))
    stop_units()
    try:
        prs = promotion_prs()
        if not prs:
            log(f'no open PR from {CFG["repo"]}:{CFG["head"]} into {CFG["base"]}: nothing to do')
        for pr in prs:
            check_pr(pr)
    except RunFault as e:
        # GitHub unreachable before a status could be posted: the next tick tries again.
        log(f'tick failed: {e.reason}')
        sys.exit(1)
    finally:
        lock.__exit__()


# ----------------------------------------------------------------------------------------------------- noise

def noise(paths):
    """Per number: median, spread (max - min) as a % of the median, across runs of one commit."""
    docs = [json.load(open(p)) for p in paths]
    flats = [flat(d['results']) for d in docs]
    keys = sorted({k for f in flats for k in f})
    rows = []
    for k in keys:
        vs = [f.get(k) for f in flats]
        if not all(number(v) for v in vs):
            continue
        med = statistics.median(vs)
        spread = max(vs) - min(vs)
        rel = spread / abs(med) * 100 if med else (0 if spread == 0 else math.inf)
        rows.append((k, med, spread, rel, vs))
    return rows


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest='cmd', required=True)
    sub.add_parser('tick')
    r = sub.add_parser('run')
    r.add_argument('sha')
    r.add_argument('--out')
    c = sub.add_parser('compare')
    c.add_argument('base')
    c.add_argument('head')
    c.add_argument('--rules')
    n = sub.add_parser('noise')
    n.add_argument('runs', nargs='+')
    a = p.parse_args()
    if a.cmd == 'tick':
        tick()
    elif a.cmd == 'run':
        if not re.fullmatch(r'[0-9a-f]{40}', a.sha):
            sys.exit('give the full 40-character sha')
        for d in (RESULTS, LOGS, STATE):
            os.makedirs(d, exist_ok=True)
        with Lock(os.path.join(STATE, 'server-bench.lock'), wait=False):
            stop_units()
            bench_sha(a.sha, a.out)
    elif a.cmd == 'compare':
        base, head = json.load(open(a.base)), json.load(open(a.head))
        rows, fails = compare(base, head, load_rules(a.rules))
        print(table(rows))
        print()
        print(describe(fails, sum(1 for r in rows if r[4] != 'report'), base.get('commit', '?'), head.get('commit', '?')))
        sys.exit(1 if fails else 0)
    elif a.cmd == 'noise':
        for k, med, spread, rel, vs in noise(a.runs):
            print(f'{k:<52} median {fmt(med):>8}  spread {fmt(spread):>8} ({rel:5.1f}%)  ' + ' '.join(fmt(v) for v in vs))


if __name__ == '__main__':
    main()
