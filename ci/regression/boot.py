#!/usr/bin/env python3
"""Starts a real Minecraft server with the MC GUI jar in plugins/ and checks that the plugin comes up clean.

Standard library only, so it runs the same in CI and on a developer's machine (Python 3.9+).

  boot.py resolve --server paper|folia|spigot --mc <1.21.11 | latest | 26.x> [--min 1.21.11]
      Prints key=value lines (project, version, build, channel, name, url, sha256, java) for a server build, in the
      form $GITHUB_OUTPUT takes. paper and folia come from PaperMC's download API (fill.papermc.io/v3); spigot has
      to be given its version and is built by BuildTools; its "build" says which commits it is made from.

  boot.py fetch --server ... --mc <exact version> --out DIR [--build N] [--java PATH]
      Puts a server into DIR: server.jar, and for paper/folia the libraries/ and versions/ that Paperclip unpacks
      (-Dpaperclip.patchonly=true: nothing is started, no world is made). A spigot jar is built with BuildTools.
      DIR is what CI caches, keyed on the build.

  boot.py run --server-dir DIR --jar build/libs/MCGUI-<version>.jar [--java PATH] [--work DIR]
      Copies DIR to a fresh work folder, puts the plugin in plugins/ with a config that stops it from using
      ResourcePack AI's services, starts the server, waits for "Done", sends `mcgui list` and `mcgui stats`, stops
      the server and reads the log. Exit code 1 when:
        * MC GUI did not enable, or was disabled before the server was told to stop;
        * the log has an error (ERROR/SEVERE/FATAL) or an exception that involves MC GUI (ai.resourcepack, MCGUI);
        * `mcgui list` or `mcgui stats` was not answered;
        * the server did not stop on its own within --stop-timeout;
        * the plugin reached for a ResourcePack AI host (the pack host, the usage count, the update check):
          every outgoing http(s) request of the server's JVM goes to a recorder that refuses it and notes the host.
      The server's own console goes to <work>/console.log; a report is written to <work>/boot-report.md (and
      appended to $GITHUB_STEP_SUMMARY when that is set).
      MCGUI_BOOT_PRODUCTION=1 leaves the plugin's own config alone (pack upload, usage count, update check on):
      only to see that the recorder catches them.
"""
import argparse
import atexit
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path

API = "https://fill.papermc.io/v3/projects"
# PaperMC's API refuses a request without a descriptive User-Agent.
UA = "mcgui-ci (https://github.com/resourcepack-ai/mcgui)"
BUILDTOOLS = "https://hub.spigotmc.org/jenkins/job/BuildTools/lastSuccessfulBuild"

# Hosts MC GUI may never reach from a CI server: its pack host and usage count (ResourcePack AI), mcgui.dev's
# import, and SpigotMC's update check. Matched against the host of every request the server's JVM makes.
FORBIDDEN_HOSTS = re.compile(r"(^|\.)resourcepack\.ai$|(^|\.)mcgui\.dev$|^api\.spigotmc\.org$", re.I)
# What makes a log line "about MC GUI".
MINE = re.compile(r"ai\.resourcepack|MCGUI|MC GUI", re.I)

ANSI = re.compile(r"\x1b\[[0-9;?]*[A-Za-z]|\x1b\][^\x07]*\x07")
# [12:00:00 INFO]: text  (Paper, Folia)   or   [12:00:00] [Server thread/INFO]: text  (Spigot, Paper's older form)
RECORD = re.compile(r"^\[\d\d:\d\d:\d\d(?: |\] \[[^\]/]*/)(\w+)\]:? ?(.*)$")
# What a logged throwable looks like on the lines after a record's first: a stack frame, a cause, or the throwable's own
# line. (An INFO line that merely names an exception class in a sentence is not one.)
EXCEPTION = re.compile(r"^\s+at [\w.$<>/]+\(|^Caused by:|^[\w.$]+(?:Exception|Error)\b")


def log(msg):
    print(f"boot.py: {msg}", flush=True)


def fail(msg):
    print(f"boot.py: ERROR: {msg}", file=sys.stderr, flush=True)
    sys.exit(1)


# ---------------------------------------------------------------- resolving and fetching

def get_json(url, tries=4):
    last = None
    for i in range(tries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/json"})
            with urllib.request.urlopen(req, timeout=30) as r:
                return json.load(r)
        except (urllib.error.URLError, TimeoutError, ConnectionError) as e:
            last = e
            time.sleep(2 * (i + 1))
    fail(f"{url}: {last}")


def vkey(v):
    return tuple(int(p) for p in v.split("."))


def stable(v):
    """A release: digits and dots only (not 1.21.11-rc3 or 26.3-pre1)."""
    return re.fullmatch(r"\d+(\.\d+)+", v) is not None


def resolve(server, mc, minimum=None):
    if server == "spigot":
        if not re.fullmatch(r"\d+(\.\d+)+", mc):
            fail("spigot needs an exact --mc version (BuildTools builds one version at a time)")
        # What the jar is made from: the commits Spigot's own version file names, so the cache holds until they change.
        meta = get_json(f"https://hub.spigotmc.org/versions/{mc}.json")
        refs = meta["refs"]
        build = f"{refs['Spigot'][:10]}-{refs['CraftBukkit'][:10]}-{meta.get('toolsVersion', 0)}"
        java = min(meta.get("javaVersions", [65])) - 44
        return {"project": "spigot", "version": mc, "build": build, "channel": "BUILDTOOLS",
                "name": f"spigot-{mc}.jar", "url": "", "sha256": "", "java": str(java)}
    project = get_json(f"{API}/{server}")
    versions = sorted({v for vs in project["versions"].values() for v in vs if stable(v)}, key=vkey, reverse=True)
    if minimum:
        versions = [v for v in versions if vkey(v) >= vkey(minimum)]
    if mc == "latest":
        pick = versions[:1]
    elif mc.endswith(".x"):
        pick = [v for v in versions if v.startswith(mc[:-1])][:1]
    else:
        pick = [mc] if mc in versions or mc in {v for vs in project["versions"].values() for v in vs} else []
    if not pick:
        fail(f"{server} has no version matching {mc!r} (it publishes {', '.join(versions[:8])}, ...)")
    version = pick[0]
    build = get_json(f"{API}/{server}/versions/{version}/builds/latest")
    dl = build["downloads"]["server:default"]
    info = get_json(f"{API}/{server}/versions/{version}")
    java = info.get("version", {}).get("java", {}).get("version", {}).get("minimum", 21)
    return {"project": server, "version": version, "build": str(build["id"]), "channel": build.get("channel", ""),
            "name": dl["name"], "url": dl["url"], "sha256": dl["checksums"]["sha256"], "java": str(java)}


def java_exe(path):
    if path:
        p = Path(path)
        return str(p / "bin" / "java") if p.is_dir() else str(p)
    home = os.environ.get("JAVA_HOME")
    return str(Path(home) / "bin" / "java") if home else "java"


def download(url, dest, sha256=None):
    import hashlib
    last = None
    for i in range(4):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            h = hashlib.sha256()
            with urllib.request.urlopen(req, timeout=60) as r, open(dest, "wb") as f:
                while True:
                    chunk = r.read(1 << 16)
                    if not chunk:
                        break
                    h.update(chunk)
                    f.write(chunk)
            if sha256 and h.hexdigest() != sha256:
                fail(f"{url}: sha256 {h.hexdigest()}, the API said {sha256}")
            return
        except (urllib.error.URLError, TimeoutError, ConnectionError) as e:
            last = e
            time.sleep(3 * (i + 1))
    fail(f"{url}: {last}")


def fetch(server, mc, out, build, java):
    out = Path(out)
    info = resolve(server, mc)
    if build and info["build"] != build:
        if server == "spigot":
            log(f"spigot build {info['build']} now, {build} was asked for: BuildTools builds the latest, using it")
        else:
            b = get_json(f"{API}/{server}/versions/{mc}/builds/{build}")
            dl = b["downloads"]["server:default"]
            info.update(build=build, name=dl["name"], url=dl["url"], sha256=dl["checksums"]["sha256"])
    out.mkdir(parents=True, exist_ok=True)
    jar = out / "server.jar"
    started = time.time()
    if server == "spigot":
        build_spigot(info["version"], jar, java)
    else:
        log(f"downloading {info['name']}")
        download(info["url"], jar, info["sha256"])
        # Paperclip downloads Mojang's jar, patches it and unpacks libraries/ and versions/, then stops: no world.
        log("unpacking the server (paperclip.patchonly)")
        r = subprocess.run([java_exe(java), "-Xmx1G", "-Dpaperclip.patchonly=true", "-jar", "server.jar"], cwd=out,
                           stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=900)
        print(r.stdout[-2000:])
        for need in ("libraries", "versions"):
            if not (out / need).is_dir():
                fail(f"paperclip left no {need}/ in {out} (exit {r.returncode})")
        # Nothing of the run itself belongs in what is cached.
        shutil.rmtree(out / "logs", ignore_errors=True)
    log(f"{server} {info['version']} build {info['build']} ready in {out} after {time.time() - started:.0f} s")
    (out / "build.json").write_text(json.dumps(info))


def build_spigot(version, jar, java):
    """Builds Spigot with BuildTools; its checkouts and Maven repository live in a temporary folder, deleted after."""
    tmp = Path(tempfile.mkdtemp(prefix="buildtools-"))
    try:
        bt = tmp / "BuildTools.jar"
        log("downloading BuildTools")
        download(f"{BUILDTOOLS}/artifact/target/BuildTools.jar", bt)
        env = dict(os.environ)
        # BuildTools sets a git identity when there is none: into a file of its own, never the user's.
        env["GIT_CONFIG_GLOBAL"] = str(tmp / "gitconfig")
        (tmp / "gitconfig").write_text("")
        env["GIT_CONFIG_SYSTEM"] = os.devnull
        log(f"BuildTools --rev {version} (this takes a while)")
        r = subprocess.run([java_exe(java), "-Xmx2G", "-jar", "BuildTools.jar", "--rev", version, "--output-dir", str(tmp / "out")],
                           cwd=tmp, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=3300)
        if r.returncode != 0:
            print(r.stdout[-6000:])
            fail(f"BuildTools exited {r.returncode}")
        built = tmp / "out" / f"spigot-{version}.jar"
        if not built.is_file():
            built = tmp / f"spigot-{version}.jar"
        if not built.is_file():
            print(r.stdout[-3000:])
            fail("BuildTools left no spigot jar")
        shutil.copy(built, jar)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


# ---------------------------------------------------------------- the run

class Console:
    """The server's output, kept and written to a file as it arrives."""

    def __init__(self, proc, path):
        self.proc = proc
        self.lines = []
        self.lock = threading.Lock()
        self.file = open(path, "w", encoding="utf-8")
        self.thread = threading.Thread(target=self._pump, daemon=True)
        self.thread.start()

    def _pump(self):
        for raw in self.proc.stdout:
            line = ANSI.sub("", raw.rstrip("\r\n"))
            with self.lock:
                self.lines.append(line)
            self.file.write(line + "\n")
            self.file.flush()

    def mark(self):
        with self.lock:
            return len(self.lines)

    def since(self, mark):
        with self.lock:
            return list(self.lines[mark:])

    def wait_for(self, pattern, timeout, mark=0):
        """The first line after [mark] that matches, or None when [timeout] passes or the server's output ends."""
        rx = re.compile(pattern)
        end = time.time() + timeout
        i = mark
        while True:
            finished = not self.thread.is_alive()
            with self.lock:
                batch = self.lines[i:]
                i = len(self.lines)
            for line in batch:
                if rx.search(line):
                    return line
            if finished or time.time() >= end:
                return None
            time.sleep(0.2)

    def send(self, command):
        log(f"> {command}")
        try:
            self.proc.stdin.write(command + "\n")
            self.proc.stdin.flush()
        except (BrokenPipeError, ValueError):
            pass


class Recorder:
    """An HTTP proxy that refuses everything and notes where it was asked to go.

    The server's JVM is pointed at it (http(s).proxyHost), so every request the plugin makes with java.net.http or
    HttpURLConnection arrives here as a CONNECT (or a plain request) and is answered 403: nothing leaves the machine.
    """

    def __init__(self):
        self.hosts = []
        self.lock = threading.Lock()
        self.sock = socket.socket()
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.bind(("127.0.0.1", 0))
        self.sock.listen(64)
        self.port = self.sock.getsockname()[1]
        threading.Thread(target=self._accept, daemon=True).start()

    def _accept(self):
        while True:
            try:
                conn, _ = self.sock.accept()
            except OSError:
                return
            threading.Thread(target=self._serve, args=(conn,), daemon=True).start()

    def _serve(self, conn):
        try:
            conn.settimeout(10)
            data = b""
            while b"\r\n" not in data and len(data) < 8192:
                chunk = conn.recv(4096)
                if not chunk:
                    break
                data += chunk
            first = data.split(b"\r\n", 1)[0].decode("latin-1")
            parts = first.split()
            if len(parts) >= 2:
                target = parts[1]
                host = re.sub(r"^\w+://", "", target).split("/", 1)[0]
                host = host.rsplit("@", 1)[-1]
                host = re.sub(r":\d+$", "", host).strip("[]").lower()
                with self.lock:
                    self.hosts.append((parts[0], host))
            conn.sendall(b"HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
        except OSError:
            pass
        finally:
            conn.close()

    def asked(self):
        with self.lock:
            return list(self.hosts)


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def mcgui_config(pack_port, production):
    if production:
        return None
    # MC GUI reads its own config.yml for everything below; a key left out takes the plugin's default.
    return f"""\
# Written by ci/regression/boot.py: keeps a CI server away from ResourcePack AI's services.
# The pack is served from this machine only (never uploaded to packs.resourcepack.ai).
resource-pack:
  mode: serve
  bind: 127.0.0.1
  port: {pack_port}
  public-url: "http://127.0.0.1:{pack_port}/pack.zip"
# No anonymous usage count (studio.resourcepack.ai), no update check (api.spigotmc.org), no web fetches, no import.
telemetry: false
update-check: false
allow-web: false
import-url: ""
"""


SERVER_PROPERTIES = """\
server-ip=127.0.0.1
server-port={port}
online-mode=false
enable-query=false
enable-rcon=false
enable-jmx-monitoring=false
level-type=minecraft\\:flat
generate-structures=false
view-distance=2
simulation-distance=2
spawn-protection=0
max-players=2
max-tick-time=-1
sync-chunk-writes=false
motd=mcgui ci
"""


def classify(lines):
    """Log records (a first line and the lines that continue it, such as a stack trace) as (level, text)."""
    records = []
    for line in lines:
        m = RECORD.match(line)
        if m:
            records.append([m.group(1).upper(), [line]])
        elif records:
            records[-1][1].append(line)
        else:
            records.append(["", [line]])
    return [(lvl, "\n".join(txt)) for lvl, txt in records]


def run(args):
    jar = Path(args.jar)
    if not jar.is_file():
        fail(f"{jar} is not a file: build the plugin first (sh gradlew build -x test)")
    if re.search(r"-dev\.jar$|-sources\.jar$", jar.name):
        fail(f"{jar.name} is not the plugin: it is build/libs/MCGUI-<version>.jar, without -dev or -sources")
    src = Path(args.server_dir)
    if not (src / "server.jar").is_file():
        fail(f"{src}/server.jar is missing: boot.py fetch makes it")
    work = Path(args.work).resolve()
    if work.exists():
        shutil.rmtree(work)
    shutil.copytree(src, work)
    (work / "plugins" / "MCGUI").mkdir(parents=True)
    shutil.copy(jar, work / "plugins" / jar.name)
    production = os.environ.get("MCGUI_BOOT_PRODUCTION") == "1"
    pack_port = free_port()
    cfg = mcgui_config(pack_port, production)
    if cfg:
        (work / "plugins" / "MCGUI" / "config.yml").write_text(cfg)
    (work / "eula.txt").write_text("eula=true\n")
    (work / "server.properties").write_text(SERVER_PROPERTIES.format(port=free_port()))
    folia = args.server == "folia"

    recorder = Recorder()
    jvm = [java_exe(args.java), f"-Xmx{args.heap}", "-Xms256M", "-Dcom.mojang.eula.agree=true", "-Dterminal.jline=false",
           "-Dterminal.ansi=false", "--enable-native-access=ALL-UNNAMED",
           f"-Dhttp.proxyHost=127.0.0.1", f"-Dhttp.proxyPort={recorder.port}",
           f"-Dhttps.proxyHost=127.0.0.1", f"-Dhttps.proxyPort={recorder.port}",
           "-jar", "server.jar", "--nogui"]
    log(f"starting {args.server} in {work}")
    proc = subprocess.Popen(jvm, cwd=work, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            text=True, bufsize=1, encoding="utf-8", errors="replace")
    atexit.register(lambda: proc.poll() is None and proc.kill())
    console = Console(proc, work / "console.log")
    problems = []
    notes = []
    stopped_at = None
    t0 = time.time()

    done = console.wait_for(r"\bDone \(\d+[.,]\d+s\)!", args.startup_timeout)
    if done is None:
        problems.append(f"the server did not finish starting within {args.startup_timeout} s"
                        + (f" (it exited with {proc.poll()})" if proc.poll() is not None else ""))
    else:
        log(f"started after {time.time() - t0:.0f} s: {done.strip()}")
        enabled = console.wait_for(r"Enabling MCGUI", 5) or any("Enabling MCGUI" in x for x in console.since(0))
        if not enabled:
            problems.append("MC GUI did not enable (no 'Enabling MCGUI' in the log)")
        # list, then stats once the plugin has warmed up (its renderer and first pictures), up to --warm-timeout.
        mark = console.mark()
        console.send("mcgui list")
        got = console.wait_for(r"Pages:|No pages yet", 30, mark)
        if got is None:
            problems.append("`mcgui list` was not answered within 30 s")
        else:
            notes.append(f"`mcgui list`: {got.split(']: ', 1)[-1].strip()}")
        warm_end = time.time() + args.warm_timeout
        stats_ok = False
        while time.time() < warm_end and proc.poll() is None:
            mark = console.mark()
            console.send("mcgui stats")
            got = console.wait_for(r"warmed up -?\d+", 15, mark)
            if got and re.search(r"warmed up \d", got):
                stats_ok = True
                for l in console.since(mark):
                    if "Started in" in l or "wall(s)" in l:
                        notes.append(f"`mcgui stats`: {l.split(']: ', 1)[-1].strip()}")
                break
            if got is None and not any("wall(s)" in l for l in console.since(mark)):
                problems.append("`mcgui stats` was not answered within 15 s")
                break
            time.sleep(3)
        else:
            if not stats_ok and not any("`mcgui stats` was not answered" in p for p in problems):
                problems.append(f"MC GUI had not finished warming up {args.warm_timeout} s after the server started (`mcgui stats` says warmed up -1 ms)")
        # A moment for anything the plugin does on a timer (the usage count is every 30 s), which the recorder would see.
        time.sleep(args.linger)

        stopped_at = console.mark()
        console.send("stop")
        t_stop = time.time()
        try:
            proc.wait(timeout=args.stop_timeout)
            log(f"stopped by itself after {time.time() - t_stop:.0f} s, exit code {proc.returncode}")
            if proc.returncode not in (0, 143):
                problems.append(f"the server exited with code {proc.returncode} after `stop`")
        except subprocess.TimeoutExpired:
            problems.append(f"the server did not stop within {args.stop_timeout} s of `stop`; it was killed")
            proc.kill()
    if proc.poll() is None:
        proc.kill()
    try:
        proc.wait(timeout=30)
    except subprocess.TimeoutExpired:
        pass
    console.thread.join(timeout=10)
    all_lines = console.since(0)
    records = classify(all_lines)

    # Disabled before it was told to stop.
    before_stop = all_lines[:stopped_at] if stopped_at is not None else all_lines
    for l in before_stop:
        if re.search(r"Disabling MCGUI|Error occurred while enabling MCGUI|Could not load .*MCGUI", l):
            problems.append(f"MC GUI did not stay enabled: {l.strip()}")
            break
    if not any("Enabling MCGUI" in l for l in all_lines):
        if not any("MC GUI did not enable" in p for p in problems):
            problems.append("MC GUI did not enable (no 'Enabling MCGUI' in the log)")

    bad = []
    warn = []
    for lvl, text in records:
        if not MINE.search(text):
            continue
        has_exception = any(EXCEPTION.search(l) for l in text.splitlines()[1:])
        if lvl in ("ERROR", "SEVERE", "FATAL") or has_exception:
            bad.append(text)
        elif lvl in ("WARN", "WARNING"):
            warn.append(text)
    if bad:
        problems.append(f"{len(bad)} error(s) or exception(s) involving MC GUI in the log")

    asked = recorder.asked()
    hosts = sorted({h for _, h in asked})
    forbidden = sorted({h for h in hosts if FORBIDDEN_HOSTS.search(h)})
    if production:
        notes.append("MCGUI_BOOT_PRODUCTION=1: the plugin's own config was used")
    elif forbidden:
        problems.append("the plugin reached for " + ", ".join(forbidden) + " (config.yml should have stopped it)")
    # The banner says "Serving" for mode serve and "Hosting ... packs.resourcepack.ai" for mode upload.
    mentions = [l.strip() for l in all_lines if re.search(r"packs\.resourcepack\.ai", l) and MINE.search(l)]
    if mentions and not production:
        problems.append("the log says MC GUI uses ResourcePack AI's pack host: " + mentions[0])

    ok = not problems
    md = [f"#### boot: {args.label or args.server} – {'passed' if ok else 'FAILED'}", ""]
    for p in problems:
        md.append(f"- **{p}**")
    for n in notes:
        md.append(f"- {n}")
    md.append(f"- outgoing http(s) requests the server tried (all refused): {', '.join(f'`{h}`' for h in hosts) or 'none'}")
    if warn:
        md += ["", f"{len(warn)} warning(s) about MC GUI:", "", "```"] + [w.splitlines()[0][:300] for w in warn[:20]] + ["```"]
    if bad:
        md += ["", "Errors:", "", "```"] + ["\n".join(b.splitlines()[:25])[:3000] for b in bad[:5]] + ["```"]
    report = "\n".join(md) + "\n"
    (work / "boot-report.md").write_text(report)
    print(report)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as f:
            f.write(report + "\n")
    if not ok:
        print("---- last 80 lines of the console ----")
        print("\n".join(all_lines[-80:]))
        sys.exit(1)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name in ("resolve", "fetch", "run"):
        p = sub.add_parser(name)
        p.add_argument("--server", choices=["paper", "folia", "spigot"], required=True)
        if name != "run":
            p.add_argument("--mc", required=True)
        if name == "resolve":
            p.add_argument("--min")
        if name == "fetch":
            p.add_argument("--out", required=True)
            p.add_argument("--build")
            p.add_argument("--java")
        if name == "run":
            p.add_argument("--server-dir", required=True)
            p.add_argument("--jar", required=True)
            p.add_argument("--java")
            p.add_argument("--work", default="boot-run")
            p.add_argument("--heap", default="1536M")
            p.add_argument("--label")
            p.add_argument("--startup-timeout", type=int, default=420)
            p.add_argument("--warm-timeout", type=int, default=90)
            p.add_argument("--linger", type=int, default=5)
            p.add_argument("--stop-timeout", type=int, default=90)
    args = ap.parse_args()
    if args.cmd == "resolve":
        for k, v in resolve(args.server, args.mc, args.min).items():
            print(f"{k}={v}")
    elif args.cmd == "fetch":
        fetch(args.server, args.mc, args.out, args.build, args.java)
    else:
        run(args)


if __name__ == "__main__":
    main()
