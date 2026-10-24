#!/usr/bin/env python3
"""MC GUI's bench: the headline numbers of every part of the engine in one run, to check a change against.

    python3 bench/bench.py                      # everything (about 15 minutes)
    python3 bench/bench.py --quick              # shorter windows, fewer cells (about 6 minutes)
    python3 bench/bench.py --only idle,hover    # some cells (see CELLS below)
    python3 bench/bench.py --compare build/bench/<earlier>.json

It builds the plugin (and the arcade example), then, holding the machine's timing lock the whole time:

* runs the headless benches on the test classpath (HoverCost, RenderBench): the CPU of a hover repaint and a whole
  picture, per page, on one thread;
* starts a private copy of a Paper server with MC GUI, the arcade example and the Probe plugin (whose fake players
  are real connections made inside the server; it is not in this repository) and runs each cell with fakes,
  reading the server's ticks and threads from Probe (`ticks`, `fake status`, `health`) and the plugin's own cost
  from `/mcgui stats json`;
* prints one table and writes every number to build/bench/<time>.json (raw replies included).

Needs: a Paper 26.2+ server folder that has run once (--server), the Probe plugin jar (--probe), Java 25 for the
server (--java) and Java 21 for Gradle (JAVA_HOME). The vanilla example pages with their textures (--vanilla, see
examples/vanilla/README.md) add the vanilla pages to the hover-cost cell; without them it measures the shipped pages.
"""
import argparse
import fcntl
import json
import os
import re
import shutil
import socket
import statistics
import subprocess
import sys
import time

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ENV = os.environ.get

DEFAULTS = {
    'server': ENV('UIE_BENCH_SERVER', ''),
    'probe': ENV('UIE_BENCH_PROBE', ''),
    'java': ENV('UIE_BENCH_JAVA', '/usr/lib/jvm/java-25-openjdk-amd64/bin/java'),
    'gradle_java': ENV('JAVA_HOME', '/usr/lib/jvm/java-21-openjdk-amd64'),
    'vanilla': ENV('UIE_BENCH_VANILLA', ''),
    'build_lock': ENV('UIE_BENCH_BUILD_LOCK', ''),
    'jvm_lock': ENV('UIE_BENCH_JVM_LOCK', ''),
    'gate': ENV('UIE_BENCH_GATE', ''),
}

ARCADE_GAMES = ['breakout', 'pong', 'tron', 'snake', 'ores2048', 'sweeper', 'dungeon', 'sequencer', 'paint', 'asteroids',
                'racer', 'flappy', 'blocks']
# Every cell, in the order they run. A cell is a function of (bench) returning a dict of numbers.
CELLS = ['headless', 'startup', 'idle', 'look', 'walls', 'views', 'hover', 'latency', 'anim', 'deadline', 'arcade']
QUICK = ['headless', 'startup', 'idle', 'walls', 'views', 'hover', 'latency', 'anim', 'deadline']

# The flat test world: players stand on y -60 round (0, 0); walls hang on a barrier at z -25, facing south.
HOME = (0.5, -60.0, 0.5)
WALL_Z = -24


def log(*a):
    print(*a, file=sys.stderr, flush=True)


def sh(cmd, cwd=None, env=None, check=True):
    r = subprocess.run(cmd, cwd=cwd, env=env, shell=isinstance(cmd, str), capture_output=True, text=True)
    if check and r.returncode != 0:
        raise RuntimeError(f'{cmd} failed ({r.returncode}):\n{r.stdout[-3000:]}\n{r.stderr[-3000:]}')
    return r.stdout


class Lock:
    """flock(1)-compatible lock on a file, held while in the with block."""

    def __init__(self, path):
        self.path = path

    def __enter__(self):
        if not self.path:
            return self
        self.f = open(self.path, 'a')
        t = time.time()
        fcntl.flock(self.f, fcntl.LOCK_EX)
        if time.time() - t > 1:
            log(f'  waited {time.time() - t:.0f} s for {self.path}')
        return self

    def __exit__(self, *e):
        if self.path:
            fcntl.flock(self.f, fcntl.LOCK_UN)
            self.f.close()


# ----------------------------------------------------------------------------------------------------- building

def build(a):
    env = dict(os.environ, JAVA_HOME=a.gradle_java)
    with Lock(a.build_lock):
        log('building the plugin and the test classes')
        out = sh(['sh', 'gradlew', '-q', 'shadowJar', 'benchClasspath', '--offline'], cwd=REPO, env=env)
        cp = [l for l in out.splitlines() if l.strip()][-1].strip()
        log('building the arcade example')
        sh(['sh', '../../gradlew', '-q', 'jar', '--offline'], cwd=os.path.join(REPO, 'examples/arcade'), env=env)
    return built_jars() + (cp,)


def built_jars():
    """The plugin and arcade jars this checkout built, whatever their versions (not the -dev jar the tests use)."""
    def newest(folder, name):
        jars = [os.path.join(folder, f) for f in os.listdir(folder) if re.fullmatch(name + r'-[\d.]+\.jar', f)]
        if not jars:
            raise RuntimeError(f'no {name}-<version>.jar in {folder}: build first')
        return max(jars, key=os.path.getmtime)
    return newest(os.path.join(REPO, 'build/libs'), 'MCGUI'), newest(os.path.join(REPO, 'examples/arcade/build/libs'), 'Arcade')


# ----------------------------------------------------------------------------------------------------- headless

def java21(a):
    return os.path.join(a.gradle_java, 'bin/java')


def headless(b):
    """Hover repaint CPU per page (HoverCost) and a whole picture of the market (RenderBench), one thread each."""
    a = b.a
    pages = ['pages:market']
    if a.vanilla and os.path.isdir(a.vanilla):
        pages += [os.path.join(a.vanilla, p + '.html') for p in ('crates', 'auction', 'quests') if os.path.exists(os.path.join(a.vanilla, p + '.html'))]
    base = [java21(a), '-Xmx2g', '-Djava.awt.headless=true', f'-Dsamples={REPO}/samples', f'-Dshipped={REPO}/src/main/resources/pages']
    out = sh(base + [f'-Druns={3 if a.quick else 5}', '-cp', b.cp, 'ai.resourcepack.ui.html.HoverCost'] + pages, cwd=REPO)
    b.raw['hovercost'] = out
    res = {}
    for line in out.splitlines():
        m = re.match(r'(\S+?)(?:\.html)?\s+(\d+) \|\s+([\d.]+)\s+([\d.]+)\s+([\d.]+) \|', line)
        if m:
            name = m.group(1).split(':')[-1]
            res[f'hover_cpu_ms.{name}'] = float(m.group(3))
            res[f'hover_cpu_ms_p90.{name}'] = float(m.group(4))
    out = sh(base + [f'-Druns={5 if a.quick else 10}', '-Donly=market', '-cp', b.cp, 'ai.resourcepack.ui.html.RenderBench',
                     f'{REPO}/samples', f'{REPO}/src/main/resources/pages'], cwd=REPO)
    b.raw['renderbench'] = out
    for line in out.splitlines():
        m = re.match(r'(market)\s+full\s+([\d.]+)', line)
        if m:
            res['full_ms.market'] = float(m.group(2))
    return res


# ----------------------------------------------------------------------------------------------------- the server

class Server:
    def __init__(self, a, jar, arcade, ports):
        self.a = a
        self.dir = os.path.join(a.work, f'bench-srv-{os.getpid()}')
        self.port, self.probe_port, self.pack_port = ports
        self.jar, self.arcade = jar, arcade
        self.proc = None

    def setup(self):
        a = self.a
        if os.path.exists(self.dir):
            shutil.rmtree(self.dir)
        sh(['rsync', '-a', '--exclude', 'logs', '--exclude', 'world/playerdata', '--exclude', 'plugins/', f'{a.server}/', f'{self.dir}/'])
        os.makedirs(f'{self.dir}/plugins/MCGUI/pages', exist_ok=True)
        props = f'{self.dir}/server.properties'
        kv = {'server-port': self.port, 'query.port': self.port, 'max-players': 200, 'enable-rcon': 'false',
              'spawn-protection': 0, 'view-distance': 6, 'simulation-distance': 4}
        lines = [l for l in open(props).read().splitlines() if l.split('=', 1)[0] not in kv]
        open(props, 'w').write('\n'.join(lines + [f'{k}={v}' for k, v in kv.items()]) + '\n')
        shutil.copy(self.jar, f'{self.dir}/plugins/MCGUI.jar')
        shutil.copy(self.arcade, f'{self.dir}/plugins/Arcade.jar')
        shutil.copy(a.probe, f'{self.dir}/plugins/Probe.jar')
        open(f'{self.dir}/plugins/MCGUI/config.yml', 'w').write(f'resource-pack:\n  port: {self.pack_port}\n')
        pages = f'{self.dir}/plugins/MCGUI/pages'
        shutil.copytree(f'{REPO}/src/main/resources/pages', pages, dirs_exist_ok=True)
        shutil.copytree(f'{REPO}/bench/pages', pages, dirs_exist_ok=True)
        if a.vanilla and os.path.isdir(a.vanilla):
            shutil.copytree(a.vanilla, pages, dirs_exist_ok=True)
        # The market with the viewer's name in it: never a kept first picture, so every open draws one whole.
        m = open(f'{pages}/market.html').read()
        open(f'{pages}/market-own.html', 'w').write(m.replace('<body>', '<body><span style="position:absolute;opacity:0">{player}</span>', 1))

    def start(self):
        log(f'starting the server in {self.dir}')
        self.t0 = time.time()
        self.log = open(f'{self.dir}/console.log', 'w')
        # Heap grown as needed: the VPS's memory is shared, and its limit kills the biggest process (once, this server).
        jfr = [f'-XX:StartFlightRecording=filename={self.a.jfr},settings=profile,dumponexit=true'] if self.a.jfr else []
        self.proc = subprocess.Popen([self.a.java, '-Xms512M', f'-Xmx{self.a.heap}', f'-Dprobe.port={self.probe_port}', *jfr,
                                      '-jar', 'paper.jar', '--nogui'], cwd=self.dir, stdin=subprocess.PIPE,
                                     stdout=self.log, stderr=subprocess.STDOUT, text=True)
        while True:
            try:
                self.P('now', timeout=2)
                break
            except OSError:
                if self.proc.poll() is not None:
                    raise RuntimeError('the server stopped while starting: see ' + self.dir + '/console.log')
                if time.time() - self.t0 > 240:
                    raise RuntimeError('the server did not start in 4 minutes')
                time.sleep(0.5)
        self.ready = time.time() - self.t0

    def stop(self):
        if self.proc and self.proc.poll() is None:
            try:
                self.proc.stdin.write('stop\n')
                self.proc.stdin.flush()
                self.proc.wait(90)
            except Exception:
                self.proc.kill()
        if not self.a.keep and os.path.exists(self.dir):
            shutil.rmtree(self.dir, ignore_errors=True)

    def P(self, line, timeout=300):
        with socket.create_connection(('127.0.0.1', self.probe_port), timeout=timeout) as s:
            s.sendall((line + '\n').encode())
            buf = b''
            while not buf.endswith(b'\n'):
                c = s.recv(1 << 16)
                if not c:
                    break
                buf += c
        t = buf.decode().strip()
        try:
            return json.loads(t)
        except ValueError:
            return t

    def cmd(self, c):
        return self.P('cmd ' + c)

    def stats(self):
        """/mcgui stats json, read back from the console log; None from a build without it."""
        path = f'{self.dir}/logs/latest.log'
        # Whatever the log holds from here on: a command's reply is not always in the log after a `say` sent first.
        at = os.path.getsize(path)
        self.cmd('mcgui stats json')
        # Up to 30 s: a loaded server (the deadline cell) can take seconds to get to a console command.
        for _ in range(300):
            time.sleep(0.1)
            with open(path, 'rb') as f:
                f.seek(at)
                text = f.read().decode(errors='replace')
            for line in text.splitlines():
                i = line.find('{"walls"')
                if i >= 0:
                    return json.loads(line[i:])
            if 'Unknown or incomplete command' in text or 'Usage' in text:
                print('stats: no json:', text[:600].replace(chr(10), ' | '), flush=True)
                return None
        print('stats: no reply in 30 s', flush=True)
        return None


# ----------------------------------------------------------------------------------------------------- measuring

def groups(ticks):
    """Thread group CPU (ms) and allocation (MB) from a `ticks stop` reply: name -> (threads, cpu ms, MB)."""
    return {g[0]: (g[1], g[2], g[3]) for g in (ticks.get('cpu') or {}).get('threads', [])}


def cpu_of(ticks, prefix):
    return sum(v[1] for k, v in groups(ticks).items() if k.startswith(prefix))


def window(b, seconds, tag=None):
    """One measuring window: the server's ticks and threads (Probe), bytes the fakes got, and the plugin's own cost."""
    s = b.srv
    s.P('fake status')
    st0 = s.stats()
    s.P('ticks start')
    time.sleep(seconds)
    t = s.P('ticks stop')
    fs = s.P('fake status')
    st = s.stats()
    secs = t.get('seconds') or seconds
    g = groups(t)
    server = g.get('Server thread', (0, 0, 0))
    out = {
        'tick_ms_p50': t['p50'], 'tick_ms_p99': t['p99'], 'tick_ms_mean': t['mean'], 'tps': t['tps'],
        'cores': (t.get('cpu') or {}).get('cores_used', 0),
        'server_thread_alloc_mb_s': server[2] / secs,
        'cpu_pool_ms_s': cpu_of(t, 'MCGUI-cpu') / secs, 'send_ms_s': cpu_of(t, 'MCGUI-send') / secs,
        'netty_ms_s': cpu_of(t, 'Netty') / secs, 'arcade_ms_s': cpu_of(t, 'Arcade') / secs,
        'fake_client_ms_s': cpu_of(t, 'probe-fake') / secs,
        'rx_mb_s': fs.get('rx_mb_s', 0),
    }
    if st and st0:
        out.update({
            'uie_tick_us': st['tick']['mean_us'], 'uie_tick_us_p50': st['tick']['p50_us'], 'uie_tick_us_p99': st['tick']['p99_us'],
            'uie_tick_bytes': st['tick']['mean_bytes'],
            'pictures': st['jobs']['picture']['jobs'] - st0['jobs']['picture']['jobs'],
            'ahead': st['jobs']['ahead']['jobs'] - st0['jobs']['ahead']['jobs'],
            'picture_run_ms': st['jobs']['picture']['run_ms'], 'picture_wait_ms': st['jobs']['picture']['wait_ms'],
            'timed_out': st['timed_out'] - st0['timed_out'],
        })
    if tag:
        b.raw[tag] = {'ticks': t, 'fakes': fs, 'stats': st}
    return out


def pick(w, *keys, suffix=''):
    """The keys of window [w] it has (a build without /mcgui stats has fewer), each with [suffix]."""
    return {k + suffix: w[k] for k in keys if k in w}


def fakes(b, n, where=HOME, yaw=180.0, pitch=0.0):
    s = b.srv
    r = s.P(f'fake {n}')
    if n and r.get('placed') != n:
        raise RuntimeError(f'fake {n}: {r}')
    if n:
        s.P(f'fakedo grid {where[0]} {where[1]} {where[2]} {yaw} {pitch} 1.5')
    s.P('fakedo still')


def settle(b, seconds):
    time.sleep(seconds * (0.5 if b.a.quick else 1))


def W(b):
    return 6 if b.a.quick else 12


def received(b):
    """MB the fakes have been sent so far, all together."""
    return b.srv.P('fake status').get('rx_mb', 0)


def expect_pictures(b, since, what):
    """Fails the cell if the fakes got nothing since [since] MB: a page that never opened (a renamed command, say)
    would otherwise measure an idle server as if it were the cell."""
    got = received(b) - since
    if got <= 0:
        raise RuntimeError(f'{what}: the fakes were sent no pictures ({got:.1f} MB), so the page never showed; nothing measured')


# ----------------------------------------------------------------------------------------------------- cells

TICK = ('uie_tick_us', 'uie_tick_us_p50', 'uie_tick_us_p99', 'uie_tick_bytes', 'tick_ms_p50', 'tick_ms_p99')


def startup(b):
    """How long the plugin takes to start and warm up, and what it runs and costs at rest with nobody on."""
    s = b.srv
    t0 = time.time()
    while True:
        st = s.stats()
        if st is None or st['warm_ms'] >= 0 or time.time() - t0 > 120:
            break
        time.sleep(1)
    text = open(f'{s.dir}/logs/latest.log', errors='replace').read()
    done = re.search(r'Done \(([\d.]+)s\)', text)
    time.sleep(5)
    rest = window(b, W(b), 'startup')
    health = s.P('health')
    st = s.stats()
    out = {'server_done_s': float(done.group(1)) if done else None, 'jvm_threads': health['threads'], 'heap_mb': health['heap'] / 1e6,
           **pick(rest, 'send_ms_s', 'cpu_pool_ms_s', 'arcade_ms_s', 'uie_tick_us', 'uie_tick_us_p50', 'uie_tick_bytes', suffix='.rest')}
    if st:
        out.update({'enable_ms': st['enable_ms'], 'warm_ms': st['warm_ms'], 'uie_threads': sum(st['threads'].values()),
                    'uie_thread_names': st['threads']})
    return out


def idle(b):
    """0, 50 and 100 players online, standing still, nothing open and no walls."""
    res = {}
    for n in (0, 50, 100):
        fakes(b, n)
        settle(b, 6)
        w = window(b, W(b), f'idle{n}')
        res.update(pick(w, *TICK, 'send_ms_s', 'server_thread_alloc_mb_s', suffix=f'.{n}'))
    return res


def look(b):
    """100 players turning their heads with nothing open: what learning their mice costs."""
    fakes(b, 100)
    b.srv.P('fakedo sweep 40 20 4')
    settle(b, 6)
    w = window(b, W(b), 'look100')
    b.srv.P('fakedo still')
    return pick(w, *TICK)


def place_walls(b, n, page='market', shared=True):
    s = b.srv
    s.cmd(f'fill -50 -61 {WALL_Z - 1} 50 -40 {WALL_Z - 1} minecraft:barrier')
    for i in range(n):
        s.cmd(f'mcgui place {page} 4 3 bw{i} {-40 + i * 5} -56 {WALL_Z} south world{" shared" if shared else ""}')


def remove_walls(b, n):
    for i in range(n):
        b.srv.cmd(f'mcgui remove bw{i}')


def walls(b):
    """Eight shared market walls in view of 100 players, nobody aiming at one: a quiet lobby."""
    fakes(b, 100)
    rx = received(b)
    place_walls(b, 8)
    settle(b, 15)
    expect_pictures(b, rx, 'walls')
    w = window(b, W(b), 'walls')
    remove_walls(b, 8)
    return pick(w, *TICK, 'send_ms_s', 'rx_mb_s')


def views(b):
    """50 players at the market, fullscreen, not moving: what open views cost at rest, and the heap each holds."""
    s = b.srv
    fakes(b, 50)
    h0 = s.P('health')['heap']
    rx = received(b)
    s.P('fakedo cmd 5 mcgui open market {name}')
    settle(b, 20)
    expect_pictures(b, rx, 'views')
    w = window(b, W(b), 'views')
    h1 = s.P('health')['heap']
    s.P('fakedo cmd 10 mcgui close {name}')
    time.sleep(3)
    return {**pick(w, *TICK, 'send_ms_s', 'cpu_pool_ms_s', 'rx_mb_s'), 'heap_mb_per_view': (h1 - h0) / 50 / 1e6}


def hover(b):
    """Players hovering round the market fullscreen: the server's CPU, ticks and bytes, at 10 and 50."""
    s = b.srv
    res = {}
    for n in (10, 50):
        fakes(b, n)
        rx = received(b)
        s.P('fakedo cmd 5 mcgui open market {name}')
        settle(b, 15)
        expect_pictures(b, rx, f'hover {n}')
        s.P('fakedo sweep 20 10 3')
        settle(b, 5)
        w = window(b, W(b), f'hover{n}')
        s.P('fakedo still')
        s.P('fakedo cmd 10 mcgui close {name}')
        time.sleep(3)
        res.update(pick(w, 'cores', 'cpu_pool_ms_s', 'tick_ms_p50', 'tick_ms_p99', 'rx_mb_s', 'pictures', 'ahead', 'picture_run_ms',
                        'timed_out', 'uie_tick_us', suffix=f'.{n}'))
    return res


def hover_ms(s, x, y, wait=0.5):
    """Points Fake1's view at (x, y): ms until the first map patch (a frame over 512 bytes) reached it, or None."""
    s.P('fakedo lat clear')
    t0 = s.P('now')['nano']
    s.cmd(f'mcgui debug Fake1 point {x} {y}')
    time.sleep(wait)
    arrived = [f[0] for f in s.P('fakedo lat')['frames'] if f[0] > t0 and f[1] > 512]
    return (min(arrived) - t0) / 1e6 if arrived else None


def latency(b):
    """One player at the market: a hover by the pointer (`/mcgui debug point`) until its map patches reach them, on the
    server's clock. Cold: from the bottom-left corner, nothing near drawn ahead. Resting: from the target before it,
    after resting there a second, which is when drawing ahead has had its chance."""
    s = b.srv
    fakes(b, 1)
    rx = received(b)
    s.P('fakedo cmd 1 mcgui open market {name}')
    settle(b, 15)
    expect_pictures(b, rx, 'latency')
    targets = b.targets.get('market', [])[:12]
    cold, rest = [], []
    for x, y in targets:
        hover_ms(s, 6, 1146)
        v = hover_ms(s, x, y)
        if v is not None:
            cold.append(v)
    for (x0, y0), (x, y) in zip(targets, targets[1:]):
        hover_ms(s, x0, y0, wait=1.0)
        v = hover_ms(s, x, y)
        if v is not None:
            rest.append(v)
    s.P('fakedo cmd 1 mcgui close {name}')

    def q(v, p):
        return sorted(v)[int(p * (len(v) - 1))] if v else None

    return {'cold_ms_p50': q(cold, 0.5), 'cold_ms_p90': q(cold, 0.9), 'rest_ms_p50': q(rest, 0.5), 'rest_ms_p90': q(rest, 0.9),
            'hovers': len(cold) + len(rest), 'of': 2 * len(targets) - 1}


def anim(b):
    """A page that never stops moving (a spinner), at 1, 10 and 50 viewers: CPU, pictures a second, bytes."""
    s = b.srv
    res = {}
    for n in (1, 10, 50):
        fakes(b, n)
        rx = received(b)
        s.P('fakedo cmd 5 mcgui open bench-anim {name}')
        settle(b, 10)
        expect_pictures(b, rx, f'anim {n}')
        w = window(b, W(b), f'anim{n}')
        s.P('fakedo cmd 10 mcgui close {name}')
        time.sleep(2)
        res.update(pick(w, 'cores', 'cpu_pool_ms_s', 'rx_mb_s', suffix=f'.{n}'))
        if 'pictures' in w:
            res[f'pictures_s.{n}'] = w['pictures'] / W(b)
    return res


def open_ms(s, page, player='Fake1', within=20.0, whole=100_000):
    """Opens [page] for the traced player: ms until the picture reached them, or None. A whole picture is a burst of
    many map packets; a hover is a few, and the traced player is hovering, so it is the first [whole] bytes since the
    command that count."""
    s.P('fakedo lat clear')
    t0 = s.P('now')['nano']
    s.cmd(f'mcgui open {page} {player}')
    end = time.time() + within
    while time.time() < end:
        time.sleep(0.2)
        got = 0
        for f in sorted(f for f in s.P('fakedo lat')['frames'] if f[0] > t0):
            got += f[1]
            if got >= whole:
                return (f[0] - t0) / 1e6
    return None


def deadline(b):
    """A loaded server: 40 players hovering the market, 10 at the spinner, a busy loop on every core but one; meanwhile
    ten players open a market of their own one after another (each a whole first picture), and Fake1 (one of the
    hoverers, the traced one) does too, six times: ms from the command to the picture reaching them. Real pictures must
    not wait behind drawing ahead or animation, and none may be given up."""
    s = b.srv
    fakes(b, 60)
    rx = received(b)
    s.P('fakedo cmd 10 mcgui open market {name}')
    settle(b, 15)
    expect_pictures(b, rx, 'deadline')
    for k in range(41, 51):
        s.cmd(f'mcgui open bench-anim Fake{k}')
    s.P('fakedo sweep 20 10 3')
    settle(b, 5)
    # Before the busy loops: a server they starve can take longer than the wait to answer a console command.
    st0 = s.stats()
    burners = [subprocess.Popen([sys.executable, '-c', 'while True: pass']) for _ in range(max(1, (os.cpu_count() or 2) - 1))]
    shown = []
    try:
        s.P('ticks start')
        for k in range(51, 61):
            s.cmd(f'mcgui open market-own Fake{k}')
            if k < 57:
                shown.append(open_ms(s, 'market-own'))
                s.cmd('mcgui open market Fake1')
            time.sleep(1.5)
        time.sleep(5)
        t = s.P('ticks stop')
        # A server the busy loops starve can miss the probe's own deadline for this: ask again, it keeps the ticks.
        for _ in range(5):
            if 'p99' in t:
                break
            time.sleep(2)
            t = s.P('ticks stop')
        st = s.stats()
    finally:
        for p in burners:
            p.kill()
    b.raw['deadline'] = {'ticks': t, 'stats': st, 'before': st0, 'open_ms': shown}
    s.P('fakedo still')
    s.P('fakedo cmd 10 mcgui close {name}')
    time.sleep(3)
    got = sorted(v for v in shown if v is not None)
    out = {'tick_ms_p99': t['p99'], 'cores': (t.get('cpu') or {}).get('cores_used', 0),
           'open_ms_median': got[len(got) // 2] if got else None, 'open_ms_max': got[-1] if got else None, 'open_never': len(shown) - len(got)}
    if st and st0:
        out['timed_out'] = st['timed_out'] - st0['timed_out']
        # Per kind of job (a build from before kinds has picture and ahead only): how many ran, and the last few
        # hundred's wait for a thread and run, ms [median, p99, most]. `show` is the ten whole first pictures.
        for kind, j in st['jobs'].items():
            out[f'{kind}_jobs'] = j['jobs'] - st0['jobs'].get(kind, {}).get('jobs', 0)
            out[f'{kind}_wait_ms'] = j['wait_ms']
            out[f'{kind}_run_ms'] = j['run_ms']
    return out


IDLE_CABINETS = ['breakout', 'racer', 'dungeon', 'tron']


def arcade(b):
    """Four cabinets with nobody in range (what a quiet arcade costs); then each game on a cabinet playing itself, one
    player watching: CPU (the game loop, the render pool, the sender) and bytes a second. Cabinets are placed from the
    console (`/arcade cabinet <game> <cols> <rows> <x> <y> <z> <facing>`): a fake can aim, but a command it runs
    through `execute as` comes from the console."""
    s = b.srv
    s.cmd(f'fill -90 -61 {WALL_Z - 1} 90 -40 {WALL_Z - 1} minecraft:barrier')
    res = {}
    # Nobody near: the one player stands 120 blocks off, past every wall's view distance (48).
    fakes(b, 1, where=(0.5, -60.0, 100.5))
    xs = [-80 + 40 * i for i in range(len(IDLE_CABINETS))]
    for x, g in zip(xs, IDLE_CABINETS):
        s.cmd(f'arcade cabinet {g} 16 9 {x} -52 {WALL_Z} south world')
    settle(b, 6)
    w = window(b, W(b), 'arcade-idle')
    res['idle_cpu_ms_s'] = w['arcade_ms_s'] + w['cpu_pool_ms_s'] + w['send_ms_s']
    res['idle_loop_ms_s'] = w['arcade_ms_s']
    for x in xs:
        s.cmd(f'arcade remove {x} -52 {WALL_Z} world')
    # One player watching, 10 blocks in front.
    s.P(f'fakedo grid 0.5 -60 {WALL_Z + 10.5} 180 -10 1.5')
    games = ARCADE_GAMES if not b.a.quick else ['breakout', 'racer', 'dungeon']
    for g in games:
        s.cmd(f'arcade cabinet {g} 16 9 -8 -52 {WALL_Z} south world')
        settle(b, 4)
        w = window(b, 6 if b.a.quick else 10, f'arcade-{g}')
        s.cmd(f'arcade remove -8 -52 {WALL_Z} world')
        time.sleep(1)
        res[f'cpu_ms_s.{g}'] = w['arcade_ms_s'] + w['cpu_pool_ms_s'] + w['send_ms_s']
        res[f'rx_mb_s.{g}'] = w['rx_mb_s']
    res['games'] = len(games)
    return res


# ----------------------------------------------------------------------------------------------------- report

def flat(results):
    out = {}
    for cell, r in results.items():
        if isinstance(r, dict):
            for k, v in r.items():
                out[f'{cell}.{k}'] = v
    return out


def fmt(v):
    if isinstance(v, float):
        return f'{v:.3g}' if abs(v) < 100 else f'{v:.0f}'
    if isinstance(v, (list, dict)):
        return json.dumps(v)
    return str(v)


def report(results, compare=None):
    now = flat(results)
    old = flat(compare['results']) if compare else {}
    width = max(len(k) for k in now) if now else 10
    for k, v in now.items():
        line = f'{k:<{width}}  {fmt(v):>12}'
        if k in old:
            line += f'  (was {fmt(old[k])})'
        print(line)


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    for k, v in DEFAULTS.items():
        p.add_argument('--' + k.replace('_', '-'), default=v)
    p.add_argument('--only', default='', help='cells to run, comma separated: ' + ','.join(CELLS))
    p.add_argument('--quick', action='store_true')
    p.add_argument('--no-build', action='store_true', help='use the jars and classpath already built')
    p.add_argument('--jar', help='a MC GUI jar to bench instead of this checkout\'s (headless cells still use the checkout)')
    p.add_argument('--arcade', help='an arcade example jar to bench instead of this checkout\'s')
    p.add_argument('--heap', default='3G')
    p.add_argument('--ports', default='25790', help='first of three ports: server, probe, pack')
    p.add_argument('--work', default=os.path.join(REPO, 'build/bench'))
    p.add_argument('--out', help='JSON file to write (default build/bench/<time>.json)')
    p.add_argument('--compare', help='an earlier run\'s JSON, shown beside each number')
    p.add_argument('--keep', action='store_true', help='keep the server copy afterwards')
    p.add_argument('--jfr', help='record the server with JFR (settings=profile) to this file: `jfr print --events jdk.ExecutionSample`')
    p.add_argument('--tag', default='')
    a = p.parse_args()
    cells = [c for c in (a.only.split(',') if a.only else (QUICK if a.quick else CELLS)) if c]
    for c in cells:
        if c not in CELLS:
            sys.exit(f'no cell {c}: ' + ','.join(CELLS))
    os.makedirs(a.work, exist_ok=True)

    class B:
        pass

    b = B()
    b.a, b.raw = a, {}
    if a.no_build:
        b.cp = open(os.path.join(a.work, 'classpath.txt')).read().strip()
        jar, arc = built_jars()
    else:
        jar, arc, b.cp = build(a)
        open(os.path.join(a.work, 'classpath.txt'), 'w').write(b.cp)
    if a.jar:
        jar = a.jar
    if a.arcade:
        arc = a.arcade
    commit = sh(['git', 'rev-parse', '--short', 'HEAD'], cwd=REPO, check=False).strip()
    dirty = bool(sh(['git', 'status', '--porcelain', '--untracked-files=no'], cwd=REPO, check=False).strip())
    results = {}
    started = time.strftime('%Y-%m-%d %H:%M:%S')
    with Lock(a.jvm_lock):
        if a.gate and os.path.exists(a.gate):
            log(sh(['sh', a.gate, '30'], check=False).strip())
        load = open('/proc/loadavg').read().split()[0]
        if 'headless' in cells or 'latency' in cells:
            out = sh([java21(a), '-cp', b.cp, 'ai.resourcepack.ui.html.BenchTargets', f'{REPO}/src/main/resources/pages/market.html'], cwd=REPO)
            b.targets = {j['page']: j['targets'] for j in map(json.loads, out.splitlines())}
        if 'headless' in cells:
            log('headless')
            results['headless'] = headless(b)
        server_cells = [c for c in cells if c != 'headless']
        if server_cells:
            base = int(a.ports)
            b.srv = Server(a, jar, arc, (base, base + 1, base + 2))
            b.srv.setup()
            try:
                b.srv.start()
                for c in server_cells:
                    log(c)
                    t = time.time()
                    try:
                        results[c] = globals()[c](b)
                    except Exception as e:
                        results[c] = {'error': repr(e)}
                        log(f'  {c} failed: {e!r}')
                    log(f'  {time.time() - t:.0f} s: ' + json.dumps(results[c]))
                    if b.srv.proc.poll() is not None:
                        # Kept for a look: the server's own words on why it stopped.
                        dead = os.path.join(a.work, f'died-{time.strftime("%Y%m%d-%H%M%S")}.log')
                        shutil.copy(f'{b.srv.dir}/console.log', dead)
                        results[c]['server_died'] = dead
                        log(f'  the server stopped during {c}: see {dead}')
                        break
                    b.srv.P('fake 0')
            finally:
                b.srv.stop()
    doc = {'started': started, 'commit': commit, 'dirty': dirty, 'tag': a.tag, 'jar': jar, 'load_at_start': load,
           'cores': os.cpu_count(), 'quick': a.quick, 'results': results, 'raw': b.raw}
    out = a.out or os.path.join(a.work, time.strftime('%Y%m%d-%H%M%S') + (f'-{a.tag}' if a.tag else '') + '.json')
    json.dump(doc, open(out, 'w'), indent=1)
    report(results, json.load(open(a.compare)) if a.compare else None)
    print(f'\nwritten to {out}')


if __name__ == '__main__':
    main()
