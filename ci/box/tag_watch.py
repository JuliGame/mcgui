#!/usr/bin/env python3
"""MC GUI's tag watcher: each new release tag becomes one request to SteveClaude to build and publish it.

`tick` (what mcgui-tag-watch.timer runs every minute) lists the repository's tags with `git ls-remote --tags`. For
each tag named v<major>.<minor>.<patch> it has not seen before, it checks that the tagged commit is on `main` and
that `version` in build.gradle at that commit equals the tag, and then starts ONE post in the Discord forum
$MCGUI_CI_TAG_FORUM that mentions $MCGUI_CI_TAG_MENTION: the tag, the commit, the compare link from the previous tag
and the checks. A tag whose checks fail is still announced, saying exactly what is wrong and asking not to publish.

The very first tick records the tags that exist without announcing them. A post that fails stays pending and is
retried on the next tick; before every attempt the forum is searched for a post with the same title, so a tick
killed after Discord took the post never posts it twice. A tag is announced once.

It posts through Backchannel's own agent MCP server ($MCGUI_CI_BACKCHANNEL_CMD, stdio), so that code's secret scan
and other guards apply to the post; the bot token stays in Backchannel's config and is never read or logged here.

    tag_watch.py tick
    tag_watch.py tick --dry-run [--fake-tags <ls-remote output>] [--state <file>]   print the posts, send nothing
    tag_watch.py test-post --forum <id>                                             one post marked as a test
"""
import argparse
import json
import os
import re
import shlex
import subprocess
import sys
import time

E = os.environ.get
ROOT = E('MCGUI_CI_ROOT', '/var/lib/mcgui-ci')
REPO = E('MCGUI_CI_REPO', 'resourcepack-ai/mcgui')
URL = f'https://github.com/{REPO}'
MIRROR = os.path.join(ROOT, 'tagwatch', 'mcgui.git')
STATE = os.path.join(ROOT, 'state', 'tag-watch.json')
TAG = re.compile(r'^v(\d+)\.(\d+)\.(\d+)$')
# A Discord bot token's shape, scrubbed from anything this script logs from Backchannel's stderr.
TOKENISH = re.compile(r'[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{20,}')


def log(*a):
    print(*a, file=sys.stderr, flush=True)


def git(*args, check=True):
    r = subprocess.run(['git', '--git-dir', MIRROR, *args], capture_output=True, text=True)
    if check and r.returncode != 0:
        raise RuntimeError(f'git {" ".join(args)}: {r.stderr.strip()[:300]}')
    return r


def semver(tag):
    return tuple(int(x) for x in TAG.match(tag).groups())


def parse_ls_remote(text):
    """{tag: commit sha} for the release tags in `git ls-remote --tags` output (annotated tags peeled)."""
    tags = {}
    for line in text.splitlines():
        if not line.strip():
            continue
        sha, ref = line.split()
        name = ref.removeprefix('refs/tags/')
        peeled = name.endswith('^{}')
        name = name.removesuffix('^{}')
        if TAG.match(name) and (peeled or name not in tags):
            tags[name] = sha
    return tags


def remote_tags():
    r = subprocess.run(['git', 'ls-remote', '--tags', URL + '.git'], capture_output=True, text=True, timeout=60)
    if r.returncode != 0:
        raise RuntimeError(f'git ls-remote: {r.stderr.strip()[:300]}')
    return parse_ls_remote(r.stdout)


def fetch(shas):
    """The mirror, up to date with main and the tags, and holding every commit in [shas]."""
    if not os.path.isdir(MIRROR):
        subprocess.run(['git', 'init', '--quiet', '--bare', MIRROR], check=True)
    git('fetch', '--quiet', '--prune', '--no-tags', URL + '.git', '+refs/heads/main:refs/heads/main', '+refs/tags/*:refs/tags/*')
    missing = [s for s in shas if git('cat-file', '-e', f'{s}^{{commit}}', check=False).returncode != 0]
    if missing:
        git('fetch', '--quiet', '--no-tags', URL + '.git', *missing)


def checks(tag, sha):
    """[(ok, text)] for the two checks on [tag] at commit [sha]."""
    out = []
    on_main = git('merge-base', '--is-ancestor', sha, 'refs/heads/main', check=False).returncode
    if on_main == 0:
        out.append((True, 'the tagged commit is on `main`'))
    elif on_main == 1:
        out.append((False, f'the tagged commit `{sha[:7]}` is **not on `main`** (it is not an ancestor of main `'
                           f'{git("rev-parse", "refs/heads/main").stdout.strip()[:7]}`)'))
    else:
        out.append((False, f'could not check whether `{sha[:7]}` is on `main`'))
    want = tag[1:]
    r = git('show', f'{sha}:build.gradle', check=False)
    m = re.search(r'^\s*version\s*=?\s*[\'"]([^\'"]+)[\'"]', r.stdout, re.M) if r.returncode == 0 else None
    if not m:
        out.append((False, '`build.gradle` at the tagged commit has no `version` line'))
    elif m.group(1) == want:
        out.append((True, f'`version` in `build.gradle` is `{want}`'))
    else:
        out.append((False, f'`version` in `build.gradle` at the tagged commit is **`{m.group(1)}`, not `{want}`**'))
    return out


def previous(tag, all_tags):
    older = [t for t in all_tags if semver(t) < semver(tag)]
    return max(older, key=semver) if older else None


def compose(tag, sha, all_tags, mention, test=False):
    """(title, message) of the request for [tag]."""
    results = checks(tag, sha)
    good = all(ok for ok, _ in results)
    prev = previous(tag, all_tags)
    who = f'<@{mention}>' if mention and not test else '@SteveClaude (not pinged: this is a test)'
    if good:
        title = f'MC GUI: please build and publish {tag}'
        ask = f'{who} MC GUI **{tag}** is tagged. Please build it and publish it.'
    else:
        title = f'MC GUI: {tag} is tagged wrongly, please do not publish'
        ask = (f'{who} MC GUI **{tag}** was tagged, but the tag fails the checks below. '
               f'**Please do not build or publish it.** The fix is a new tag on a correct commit, which gets a post of its own.')
    lines = [ask, '', f'- Tag: [`{tag}`]({URL}/tree/{tag})', f'- Commit: [`{sha}`]({URL}/commit/{sha})']
    lines.append(f'- Changes since {prev}: {URL}/compare/{prev}...{tag}' if prev else '- Changes: the first release tag')
    lines.append('- Checks:')
    lines += [f'  - {"✅" if ok else "❌"} {text}' for ok, text in results]
    lines += ['', '_Sent by the MC GUI tag watcher on the bench box (ci/box/tag_watch.py)._']
    if test:
        title = f'TEST, ignore: {title}'
        lines = ['**This is a test of the MC GUI tag watcher. Nothing was tagged; do not build or publish anything.**', ''] + lines
    return title[:100], '\n'.join(lines), good


# ----------------------------------------------------------------------------------------------------- Backchannel

class Backchannel:
    """Backchannel's agent MCP server over stdio: newline-delimited JSON-RPC."""

    def __init__(self):
        cmd = shlex.split(E('MCGUI_CI_BACKCHANNEL_CMD', ''))
        if not cmd:
            raise RuntimeError('MCGUI_CI_BACKCHANNEL_CMD is not set')
        env = {'PATH': '/usr/local/bin:/usr/bin:/bin', 'HOME': E('HOME', '/root'),
               'BACKCHANNEL_CONFIG': E('MCGUI_CI_BACKCHANNEL_CONFIG', ''),
               'BACKCHANNEL_DENY_PATHS': E('MCGUI_CI_BACKCHANNEL_DENY_PATHS', '')}
        self.p = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                                  env={k: v for k, v in env.items() if v})
        self.id = 0
        self.call('initialize', {'protocolVersion': '2025-06-18', 'capabilities': {},
                                 'clientInfo': {'name': 'mcgui-tag-watch', 'version': '1'}})
        self.send({'jsonrpc': '2.0', 'method': 'notifications/initialized'})

    def send(self, msg):
        self.p.stdin.write(json.dumps(msg) + '\n')
        self.p.stdin.flush()

    def call(self, method, params):
        self.id += 1
        self.send({'jsonrpc': '2.0', 'id': self.id, 'method': method, 'params': params})
        while True:
            line = self.p.stdout.readline()
            if not line:
                raise RuntimeError('the Backchannel server stopped: ' + self.stderr())
            msg = json.loads(line)
            if msg.get('id') == self.id:
                if 'error' in msg:
                    raise RuntimeError(f'Backchannel {method}: {msg["error"].get("message")}')
                return msg['result']

    def tool(self, name, args):
        res = self.call('tools/call', {'name': name, 'arguments': args})
        text = ' '.join(c.get('text', '') for c in res.get('content', []))
        if res.get('isError'):
            raise RuntimeError(f'Backchannel {name}: {TOKENISH.sub("<redacted>", text)[:300]}')
        return text

    def stderr(self):
        try:
            self.p.stdin.close()
            _, err = self.p.communicate(timeout=5)
        except Exception:
            self.p.kill()
            err = ''
        return TOKENISH.sub('<redacted>', err or '').strip()[-300:]

    def close(self):
        if self.p.poll() is None:
            self.p.stdin.close()
            try:
                self.p.wait(5)
            except subprocess.TimeoutExpired:
                self.p.kill()


def post(forum, title, message):
    """Starts the post unless the forum already has one with this title (a tick killed after Discord took it); its
    link or id."""
    bc = Backchannel()
    try:
        listed = json.loads(bc.tool('list_posts', {'forum': forum}))
        for p in listed.get('posts', []):
            if p['title'] == title:
                log(f'the forum already has "{title}" (id {p["id"]}): not posting it again')
                return f'post {p["id"]} (found)'
        return bc.tool('start_post', {'forum': forum, 'title': title, 'message': message})
    finally:
        bc.close()


# ----------------------------------------------------------------------------------------------------- the tick

def load_state(path):
    try:
        return json.load(open(path))
    except FileNotFoundError:
        return None


def save_state(path, state):
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    tmp = path + '.tmp'
    json.dump(state, open(tmp, 'w'), indent=1, sort_keys=True)
    os.replace(tmp, path)


def tick(a):
    tags = parse_ls_remote(open(a.fake_tags).read()) if a.fake_tags else remote_tags()
    state = load_state(a.state)
    if state is None:
        state = {'tags': {t: {'sha': s, 'state': 'baseline', 'at': now()} for t, s in tags.items()}}
        log(f'first run: recorded {", ".join(sorted(tags, key=semver)) or "no tags"} without announcing them')
        if not a.dry_run:
            save_state(a.state, state)
        return 0
    seen = state['tags']
    for t, s in tags.items():
        if t not in seen:
            seen[t] = {'sha': s, 'state': 'pending', 'at': now(), 'tries': 0}
            log(f'new tag {t} at {s[:7]}')
        elif seen[t]['sha'] != s:
            log(f'tag {t} moved from {seen[t]["sha"][:7]} to {s[:7]}; it was {seen[t]["state"]} and is not announced again')
        if a.dry_run and seen[t]['state'] != 'pending':
            print(f'--- {t} at {s[:7]}: seen before ({seen[t]["state"]}), not announced again\n')
    pending = sorted((t for t, v in seen.items() if v['state'] == 'pending'), key=semver)
    if not pending:
        log('no new tags')
        if not a.dry_run:
            save_state(a.state, state)
        return 0
    fetch([seen[t]['sha'] for t in pending])
    failed = 0
    for t in pending:
        v = seen[t]
        title, message, good = compose(t, v['sha'], list(tags) or list(seen), E('MCGUI_CI_TAG_MENTION', ''))
        if a.dry_run:
            print(f'--- would post to forum {a.forum}\ntitle: {title}\n{message}\n')
            continue
        v['tries'] = v.get('tries', 0) + 1
        try:
            got = post(a.forum, title, message)
            v.update(state='posted', post=got, title=title, checks_passed=good, posted_at=now())
            v.pop('error', None)
            log(f'{t}: {got}')
        except Exception as e:
            v['error'] = TOKENISH.sub('<redacted>', str(e))[:300]
            failed += 1
            log(f'{t}: post failed (try {v["tries"]}), retried next tick: {v["error"]}')
        save_state(a.state, state)
    return 1 if failed else 0


def test_post(a):
    """One post in [forum], marked as a test, of what the newest real tag would have looked like."""
    tags = remote_tags()
    tag = max(tags, key=semver)
    fetch([tags[tag]])
    title, message, good = compose(tag, tags[tag], list(tags), E('MCGUI_CI_TAG_MENTION', ''), test=True)
    print(f'title: {title}\n{message}\n')
    print(post(a.forum, title, message))
    return 0


def now():
    return time.strftime('%Y-%m-%dT%H:%M:%S%z')


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest='cmd', required=True)
    t = sub.add_parser('tick')
    t.add_argument('--dry-run', action='store_true', help='print each post instead of sending it; the state is not written')
    t.add_argument('--fake-tags', help='a file of `git ls-remote --tags` output to use instead of the real tags')
    t.add_argument('--state', default=STATE)
    t.add_argument('--forum', default=E('MCGUI_CI_TAG_FORUM', ''))
    tp = sub.add_parser('test-post')
    tp.add_argument('--forum', required=True)
    a = p.parse_args()
    if not a.forum:
        sys.exit('no forum: set MCGUI_CI_TAG_FORUM')
    sys.exit(tick(a) if a.cmd == 'tick' else test_post(a))


if __name__ == '__main__':
    main()
