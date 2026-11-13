#!/usr/bin/env python3
"""Perf gate: run MC GUI's headless benches on a base checkout and a head checkout, alternately, and compare.

    python3 ci/perf/compare.py --base DIR --head DIR --base-cp FILE --head-cp FILE [--rounds 7] [--budget 600] [--out DIR]
    python3 ci/perf/compare.py noise-table RUN.json [RUN.json ...]     # spread of several base-against-base runs

Builds nothing. Both checkouts must have their test classes compiled already, and FILE is what
`sh gradlew -q benchClasspath` printed there (the test runtime classpath of that checkout).

One round runs every bench once on each side, back to back, taking turns at going first. After N rounds each
metric is reduced to the median of its N values per side. Base and head run on the same machine in the same job,
so slow neighbours and drift hit both alike; numbers from two different jobs are never compared.

Rules (ci/perf/thresholds.json, read from the head checkout, first matching rule wins):
  exact   bytes, packets, maps, bytes allocated: fail when head is worse than base by more than rel (a fraction)
          and more than abs (the printed digits' step).
  timing  milliseconds: fail when head is worse by more than rel, and by more than abs ms.
  report  "mode": "report" on a rule keeps the metric in the table and out of the verdict.
A metric (or a whole bench) found on one side only is shown as new or removed, never failed. A bench that
crashes on head fails the run; one that crashes on base only makes its metrics new.

Exit status: 0 pass, 1 a metric got worse than its limit, 2 the run itself broke (a bench crashed on head, no
output parsed, bad arguments).
Python 3 standard library only.
"""
import argparse
import fnmatch
import json
import os
import platform
import re
import statistics
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
PKG = 'ai.resourcepack.ui'


def log(*a):
    print(*a, file=sys.stderr, flush=True)


# ------------------------------------------------------------------------------------------------ the benches
# Page files are relative to the checkout (the benches run with it as working directory). A page that a checkout
# does not have is left out for that side, so a base that predates a page still runs the others.
SHIPPED = 'src/main/resources/pages/'
SAMPLES = 'examples/samples/'
RENDER_PAGES = [SHIPPED + 'menu.html', SHIPPED + 'market.html', SAMPLES + 'landing.html']
HOVER_SPECS = ['pages:menu', 'pages:market', 'pages:shop']
ALLOC_PAGES = [SHIPPED + 'menu.html', SAMPLES + 'landing.html', SHIPPED + 'shop.html', 'src/test/resources/scroll/long.html']
TILE_PAGE = 'src/test/resources/scroll/long.html'
NUM = r'(-?[\d.]+|NaN)'


def num(s):
    try:
        v = float(s)
    except ValueError:
        return None
    return None if v != v else v


def put(out, name, value):
    v = num(value)
    if v is not None:
        out[name] = v


def page_label(path):
    """'src/main/resources/pages/menu.html' -> 'pages/menu'; 'examples/samples/menu.html' -> 'samples/menu'."""
    stem = os.path.splitext(os.path.basename(path))[0]
    if path.startswith(SHIPPED):
        return 'pages/' + stem
    if path.startswith(SAMPLES):
        return 'samples/' + stem
    return 'test/' + stem


def parse_render(text, pages):
    """RenderBench: one line per page, in argument order: 'name full a/b  draw c/d | hover e/f  hover-draw g/h  (desc)'."""
    line = re.compile(r'^(\S+)\s+full\s+' + NUM + r'\s*/\s*' + NUM + r'\s+draw\s+' + NUM + r'\s*/\s*' + NUM
                      + r'\s*\|\s*hover\s+' + NUM + r'\s*/\s*' + NUM + r'\s+hover-draw\s+' + NUM + r'\s*/\s*' + NUM + r'\s+\((.*)\)\s*$')
    rows = [m for m in (line.match(l) for l in text.splitlines()) if m]
    if len(rows) != len(pages):
        raise ValueError(f'RenderBench printed {len(rows)} page lines for {len(pages)} pages')
    out = {}
    for page, m in zip(pages, rows):
        p = page_label(page)
        put(out, f'render.{p}.full-ms', m.group(2))
        put(out, f'render.{p}.draw-ms', m.group(4))
        if 'no :hover rule' not in m.group(10):
            put(out, f'render.{p}.hover-ms', m.group(6))
            put(out, f'render.{p}.hover-draw-ms', m.group(8))
    return out


def parse_hover(text, specs):
    """HoverCost: 'name targets | cpu p50 p90 max | lay p50 | alloc p50 | draw p50 p90 | tiles | failed {why} | ahead cpu, alloc p50, p90, apply, max'."""
    out = {}
    for line in text.splitlines():
        parts = [p.strip() for p in line.split('|')]
        if len(parts) != 8:
            continue
        head = parts[0].split()
        if not head or head[0] not in specs:
            continue
        spec = head[0].replace(':', '/')
        cpu, lay, alloc, draw, tiles, failed, ahead = (parts[i].split() for i in range(1, 8))
        put(out, f'hover.{spec}.repaint-cpu-ms', cpu[0])
        put(out, f'hover.{spec}.layout-cpu-ms', lay[0])
        put(out, f'hover.{spec}.repaint-alloc-mb', alloc[0])
        put(out, f'hover.{spec}.tiles', tiles[0])
        put(out, f'hover.{spec}.not-partial', failed[0])
        put(out, f'hover.{spec}.ahead-cpu-ms', ahead[0])
        put(out, f'hover.{spec}.ahead-alloc-mb', ahead[1])
    return out


def parse_alloc(text, pages):
    """AllocBench: 'page op n render MB draw MB total MB mean MB (pool: made X MB per op, ...)'; 'page op -' when the page has no such op."""
    names = {os.path.splitext(os.path.basename(p))[0]: page_label(p) for p in pages}
    line = re.compile(r'^(\S+)\s+(full|hover|notch)\s+(\d+)\s+' + NUM + r'\s+' + NUM + r'\s+' + NUM + r'\s+' + NUM + r'\s+\(pool: made ' + NUM + ' MB')
    out = {}
    for l in text.splitlines():
        m = line.match(l)
        if not m or m.group(1) not in names:
            continue
        p = names[m.group(1)]
        put(out, f'alloc.{p}.{m.group(2)}.total-mb', m.group(6))
        put(out, f'alloc.{p}.{m.group(2)}.pool-made-mb', m.group(8))
    return out


TILE_MODES = {'row, moved': 'row-moved', 'row, whole': 'row-whole', '80 px, whole': 'notch80-whole'}


def parse_tile(text, _):
    """TileBench: 'mode maps repoints raw KB deflated KB packets CPU med/p90 canvas med/p90', per notch."""
    out = {}
    for l in text.splitlines():
        for mode, slug in TILE_MODES.items():
            if l.startswith(mode):
                f = re.split(r'\s+', l[len(mode):].replace('/', ' / ').strip())
                v = [x for x in f if x != '/']
                if len(v) < 9:
                    raise ValueError(f'TileBench line not understood: {l!r}')
                put(out, f'tile.{slug}.maps', v[0])
                put(out, f'tile.{slug}.repoints', v[1])
                put(out, f'tile.{slug}.raw-kb', v[2])
                put(out, f'tile.{slug}.deflated-kb', v[3])
                put(out, f'tile.{slug}.packets', v[4])
                put(out, f'tile.{slug}.cpu-ms', v[5])
                put(out, f'tile.{slug}.canvas-ms', v[7])
    return out


def existing(root, files):
    return [f for f in files if os.path.isfile(os.path.join(root, f))]


# name -> (main class, system properties, arguments for a checkout, parser, the thing the parser is told about)
BENCHES = {
    'render': dict(main=f'{PKG}.html.RenderBench', props={'runs': '25'}, items=RENDER_PAGES, args=lambda items: items, parse=parse_render),
    'hover': dict(main=f'{PKG}.html.HoverCost', props={'runs': '4', 'samples': SAMPLES.rstrip('/'), 'shipped': SHIPPED.rstrip('/')},
                  items=HOVER_SPECS, args=lambda items: items, parse=parse_hover),
    'alloc': dict(main=f'{PKG}.html.AllocBench', props={'runs': '12'}, items=ALLOC_PAGES, args=lambda items: items, parse=parse_alloc),
    'tile': dict(main=f'{PKG}.html.TileBench', props={'runs': '3', 'notches': '12'}, items=[TILE_PAGE], args=lambda items: items, parse=parse_tile),
}

ABSENT = 'absent'    # the bench's class is not on this side
CRASHED = 'crashed'  # it ran and failed


def run_bench(name, side, root, cp, java, timeout):
    """One run of a bench on one side. Returns (metrics dict, None) or (None, ABSENT | CRASHED + reason)."""
    b = BENCHES[name]
    items = b['items']
    if name == 'hover':
        items = [i for i in items if os.path.isfile(os.path.join(root, (SHIPPED if i.startswith('pages:') else SAMPLES) + i.split(':', 1)[1] + '.html'))]
    else:
        items = existing(root, items)
    if not items:
        return None, (ABSENT, 'its pages are not in this checkout')
    cmd = [java, '-Xmx2g', '-Djava.awt.headless=true'] + [f'-D{k}={v}' for k, v in b['props'].items()] + ['-cp', cp, b['main']] + b['args'](items)
    t0 = time.time()
    try:
        r = subprocess.run(cmd, cwd=root, capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        return None, (CRASHED, f'no result after {timeout}s')
    log(f'  {name:<7} {side:<5} {time.time() - t0:5.1f}s')
    err = r.stderr + r.stdout
    if r.returncode != 0:
        if 'Could not find or load main class' in err:
            return None, (ABSENT, 'the bench does not exist here')
        return None, (CRASHED, f'exit {r.returncode}: ' + ' | '.join(err.strip().splitlines()[-3:]))
    try:
        metrics = b['parse'](r.stdout, items)
    except ValueError as e:
        return None, (CRASHED, str(e))
    if not metrics:
        return None, (CRASHED, 'no numbers in its output: ' + ' | '.join(r.stdout.strip().splitlines()[-2:]))
    return metrics, None


# ------------------------------------------------------------------------------------------------ comparing
def load_rules(path):
    with open(path) as f:
        cfg = json.load(f)
    for r in cfg['rules']:
        if r['kind'] not in ('exact', 'timing') or r.get('mode', 'gate') not in ('gate', 'report'):
            raise ValueError(f'bad rule {r}')
    return cfg


def rule_for(cfg, metric):
    for r in cfg['rules']:
        if fnmatch.fnmatchcase(metric, r['match']):
            return r
    raise ValueError(f'no rule in thresholds.json matches metric {metric}')


def judge(metric, base, head, cfg):
    """One metric's row: values, change, the allowed rise and the verdict."""
    row = {'metric': metric, 'base': None, 'head': None}
    if base:
        row['base_runs'] = base
        row['base'] = statistics.median(base)
    if head:
        row['head_runs'] = head
        row['head'] = statistics.median(head)
    if row['base'] is None or row['head'] is None:
        row['verdict'] = 'new' if row['base'] is None else 'removed'
        return row
    rule = rule_for(cfg, metric)
    b, h = row['base'], row['head']
    allowed = max(rule['rel'] * abs(b), rule.get('abs', 0))
    row['kind'] = rule['kind']
    row['mode'] = rule.get('mode', 'gate')
    row['change'] = (h - b) / b if b else (0.0 if h == b else float('inf'))
    row['allowed'] = allowed
    row['limit'] = limit_text(rule)
    worse = h - b > allowed + 1e-9  # not by the float error of 5.1 - 5.0
    if worse:
        row['verdict'] = 'FAIL' if row['mode'] == 'gate' else 'report (over)'
    else:
        row['verdict'] = 'pass' if row['mode'] == 'gate' else 'report'
    return row


def limit_text(rule):
    if not rule['rel'] and not rule.get('abs'):
        return 'any rise'
    s = f"+{rule['rel'] * 100:g}%"
    if rule.get('abs'):
        s += f" (at least +{rule['abs']:g})"
    return s


def fmt(v):
    if v is None:
        return '-'
    if v == 0:
        return '0'
    return f'{v:.4g}' if abs(v) < 1000 else f'{v:,.0f}'


def pct(row):
    c = row.get('change')
    if c is None:
        return '-'
    if c == float('inf'):
        return 'from 0'
    return f'{c * 100:+.1f}%'


MARK = {'FAIL': '**FAIL**', 'pass': 'pass', 'report': 'report only', 'report (over)': 'report only, over',
        'new': 'new', 'removed': 'removed'}


def table(rows):
    out = ['| metric | base | head | change | limit | verdict |', '|---|---:|---:|---:|---|---|']
    for r in rows:
        out.append(f"| `{r['metric']}` | {fmt(r['base'])} | {fmt(r['head'])} | {pct(r)} | {r.get('limit', '')} | {MARK[r['verdict']]} |")
    return '\n'.join(out)


def summarise(rows, meta, problems, marker):
    failed = [r for r in rows if r['verdict'] == 'FAIL']
    shown = [r for r in rows if r['verdict'] in ('FAIL', 'new', 'removed')]
    c = {}
    for r in rows:
        c[r['verdict']] = c.get(r['verdict'], 0) + 1
    lines = [marker, f"## Perf: {'FAILED' if failed or problems else 'passed'}", '']
    lines.append(f"Head `{meta['head'][:10]}` against base `{meta['base'][:10]}` (the commit the PR branched from), "
                 f"{meta['rounds']} rounds interleaved on one runner ({meta['cpu']}, {meta['cores']} cores), median of rounds. "
                 f"Lower is better everywhere.")
    if meta.get('again'):
        lines.append('')
        lines.append(f"Timing was over a limit after {meta['rounds']} rounds, so {', '.join('`' + a + '`' for a in meta['again'])} ran {meta['extra_rounds']} more; "
                     'the table is the median of all rounds (those benches: ' + str(meta['rounds'] + meta['extra_rounds']) + ').')
    lines.append('')
    lines.append(', '.join(f'{n} {k}' for k, n in sorted(c.items())) or 'no metrics')
    lines.append('')
    for p in problems:
        lines.append(f'- **{p}**')
    if problems:
        lines.append('')
    if failed:
        lines.append(f'Worse than the limit: {", ".join("`" + r["metric"] + "`" for r in failed)}. '
                     'If the cost is deliberate, raise the limit for that metric in `ci/perf/thresholds.json` in this PR and say why in the PR text.')
        lines.append('')
    if shown:
        lines.append(table(shown))
        lines.append('')
    else:
        lines.append('No metric over its limit, none new, none removed.')
        lines.append('')
    lines.append(f'<details><summary>All {len(rows)} metrics</summary>\n')
    lines.append(table(rows))
    lines.append('\n</details>')
    return '\n'.join(lines) + '\n'


def cpu_model():
    try:
        with open('/proc/cpuinfo') as f:
            for l in f:
                if l.startswith('model name'):
                    return l.split(':', 1)[1].strip()
    except OSError:
        pass
    return platform.processor() or platform.machine()


# ------------------------------------------------------------------------------------------------ main
def compare(args):
    cfg = load_rules(args.thresholds)
    if args.noise:
        args.confirm_rounds = 0
    sides = {'base': (os.path.abspath(args.base), open(args.base_cp).read().strip()),
             'head': (os.path.abspath(args.head), open(args.head_cp).read().strip())}
    names = args.benches.split(',') if args.benches else list(BENCHES)
    for n in names:
        if n not in BENCHES:
            log(f'unknown bench {n}')
            return 2
    runs = {s: {} for s in sides}      # side -> metric -> [value per round]
    state = {s: {} for s in sides}     # side -> bench -> ABSENT | CRASHED reason, once seen
    problems = []
    started = time.time()

    def one_round(rnd, which):
        for i, name in enumerate(which):
            order = ['base', 'head'] if (rnd + i) % 2 == 0 else ['head', 'base']
            for side in order:
                if name in state[side] and state[side][name][0] == ABSENT:
                    continue
                root, cp = sides[side]
                metrics, why = run_bench(name, side, root, cp, args.java, args.run_timeout)
                if why:
                    state[side][name] = why
                    log(f'  {name} on {side}: {why[0]}: {why[1]}')
                    continue
                for k, v in metrics.items():
                    runs[side].setdefault(k, []).append(v)

    def judge_all():
        return [judge(m, runs['base'].get(m), runs['head'].get(m), cfg) for m in sorted(set(runs['base']) | set(runs['head']))]

    done = 0
    for rnd in range(args.rounds):
        # Always at least min_rounds; after that, stop starting rounds when the time budget is used up.
        if rnd >= args.min_rounds and time.time() - started > args.budget:
            log(f'time budget of {args.budget}s used up after {rnd} rounds')
            break
        log(f'round {rnd + 1}/{args.rounds}')
        one_round(rnd, names)
        done += 1
    # A timing metric over its limit may be a noisy neighbour's burst; a real slowdown is still there when measured
    # again. So the benches of the failing timing metrics get more rounds and the verdict is taken on all the rounds.
    # Exact metrics (bytes, packets) are not noise and are not measured again.
    rows = judge_all()
    again = [n for n in names if any(r['verdict'] == 'FAIL' and r['kind'] == 'timing' and r['metric'].split('.')[0] == n for r in rows if 'kind' in r)]
    extra = 0
    if again and args.confirm_rounds > 0:
        log(f'timing over the limit in {", ".join(again)}: {args.confirm_rounds} more rounds of those')
        for k in range(args.confirm_rounds):
            one_round(done + k, again)
        extra = args.confirm_rounds
    for name in names:
        for side in sides:
            if name in state[side]:
                kind, why = state[side][name]
                if side == 'head' and kind == CRASHED:
                    problems.append(f'bench `{name}` failed on head: {why}')
                elif side == 'base' and kind == CRASHED and state['head'].get(name, (None,))[0] != CRASHED:
                    problems.append(f'bench `{name}` failed on base, so its metrics are shown as new: {why}')
                elif kind == ABSENT and side == 'head':
                    problems.append(f'bench `{name}` is missing on head: {why}')
    rows = judge_all()
    if not rows:
        problems.append('no metrics at all')
    meta = {'base': args.base_sha or '?', 'head': args.head_sha or '?', 'rounds': done, 'extra_rounds': extra, 'again': again, 'cores': os.cpu_count(), 'cpu': cpu_model(),
            'java': subprocess.run([args.java, '-version'], capture_output=True, text=True).stderr.splitlines()[0],
            'seconds': round(time.time() - started), 'benches': names, 'noise_run': bool(args.noise)}
    os.makedirs(args.out, exist_ok=True)
    with open(os.path.join(args.out, 'perf.json'), 'w') as f:
        json.dump({'meta': meta, 'problems': problems, 'rows': rows}, f, indent=1, sort_keys=True)
    md = summarise(rows, meta, problems, args.marker)
    with open(os.path.join(args.out, 'summary.md'), 'w') as f:
        f.write(md)
    print(md)
    if any(p.startswith('bench `') and 'failed on head' in p or p in ('no metrics at all',) for p in problems):
        return 2
    return 1 if any(r['verdict'] == 'FAIL' for r in rows) else 0


def noise_table(paths):
    """Change of head against base (same commit on both sides) per metric over several jobs: what the noise looks like."""
    per = {}
    kinds = {}
    for p in paths:
        with open(p) as f:
            data = json.load(f)
        for r in data['rows']:
            if r.get('change') is not None and r['change'] != float('inf') and r.get('kind'):
                per.setdefault(r['metric'], []).append(r['change'] * 100)
                kinds[r['metric']] = r['kind']
            elif r.get('change') == float('inf'):
                per.setdefault(r['metric'], []).append(float('inf'))
                kinds[r['metric']] = r.get('kind', '')
    print(f'| metric | kind | jobs | worst rise of head over base | best | spread of rounds inside a job (max, % of median) |')
    print('|---|---|---:|---:|---:|---:|')
    spreads = {}
    for p in paths:
        with open(p) as f:
            for r in json.load(f)['rows']:
                for side in ('base_runs', 'head_runs'):
                    v = r.get(side)
                    if v and statistics.median(v):
                        spreads[r['metric']] = max(spreads.get(r['metric'], 0), (max(v) - min(v)) / statistics.median(v) * 100)
    for m in sorted(per):
        v = per[m]
        print(f'| `{m}` | {kinds[m]} | {len(v)} | {max(v):+.1f}% | {min(v):+.1f}% | {spreads.get(m, 0):.1f}% |')
    return 0


def main():
    if len(sys.argv) > 1 and sys.argv[1] == 'noise-table':
        return noise_table(sys.argv[2:])
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('--base', required=True, help='base checkout, test classes compiled')
    ap.add_argument('--head', required=True, help='head checkout, test classes compiled')
    ap.add_argument('--base-cp', required=True, help='file holding the base checkout\'s test runtime classpath')
    ap.add_argument('--head-cp', required=True, help='file holding the head checkout\'s test runtime classpath')
    ap.add_argument('--base-sha')
    ap.add_argument('--head-sha')
    ap.add_argument('--rounds', type=int, default=7, help='most rounds to run')
    ap.add_argument('--min-rounds', type=int, default=3, help='rounds that always run, whatever the budget')
    ap.add_argument('--budget', type=float, default=600, help='seconds after which no new round starts (once min-rounds are done)')
    ap.add_argument('--confirm-rounds', type=int, default=3, help='more rounds of a bench whose timing metric is over its limit, before the verdict')
    ap.add_argument('--run-timeout', type=float, default=300, help='seconds one bench run may take')
    ap.add_argument('--benches', help='comma list of: ' + ', '.join(BENCHES) + ' (default all)')
    ap.add_argument('--java', default=os.path.join(os.environ.get('JAVA_HOME', ''), 'bin', 'java') if os.environ.get('JAVA_HOME') else 'java')
    ap.add_argument('--thresholds', default=os.path.join(HERE, 'thresholds.json'))
    ap.add_argument('--out', default='build/perf')
    ap.add_argument('--marker', default='<!-- mcgui-perf -->', help='first line of the summary, how the sticky comment is found again')
    ap.add_argument('--noise', action='store_true', help='note in the output that base and head are the same commit')
    return compare(ap.parse_args())


if __name__ == '__main__':
    sys.exit(main())
