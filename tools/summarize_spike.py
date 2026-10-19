"""Summarise a WACR spike log bundle (zip from the app's Save/Share logs button, or an extracted folder).

Usage:  python tools/summarize_spike.py spike-logs/WACR-logs_SM-A736B_20261020-101500.zip [-o summary.md]
Writes summary.md next to the input (or -o) and prints it. Pure stdlib.
"""
import argparse
import collections
import io
import json
import pathlib
import sys
import zipfile
from datetime import datetime

TIMELINE_TAGS = {"gate", "notif", "audio", "fgs", "sweep", "app", "export"}
TIMELINE_A11Y = {"window_state", "connected", "unbound", "interrupt", "sampler_start", "sampler_stop", "screenshot_failed", "click"}
NOISY = {("sweep", "tick"), ("audio", "playback_configs")}
MAX_TIMELINE = 160


class Bundle:
    def __init__(self, path: pathlib.Path):
        self.path = path
        if path.is_file():
            self.zip = zipfile.ZipFile(path)
            self.names = self.zip.namelist()
        else:
            self.zip = None
            self.names = [p.relative_to(path).as_posix() for p in path.rglob("*") if p.is_file()]

    def read(self, name: str) -> str:
        if self.zip:
            return self.zip.read(name).decode("utf-8", "replace")
        return (self.path / name).read_text("utf-8", "replace")


def hhmmss(ts):
    return datetime.fromtimestamp(ts / 1000).strftime("%H:%M:%S")


def brief(d, limit=160):
    s = json.dumps(d, ensure_ascii=False, separators=(",", ":"))
    return s if len(s) <= limit else s[: limit - 1] + "…"


def load_events(b: Bundle, run: str):
    out = []
    for line in io.StringIO(b.read(f"runs/{run}/events.jsonl")):
        line = line.strip()
        if line:
            try:
                out.append(json.loads(line))
            except json.JSONDecodeError:
                out.append({"ts": 0, "tag": "parse", "event": "bad_line", "data": {"line": line[:120]}})
    return out


def timeline(events):
    rows, last, repeat = [], None, 0
    for e in events:
        tag, ev, d = e.get("tag"), e.get("event"), e.get("data") or {}
        if (tag, ev) in NOISY:
            continue
        if tag == "a11y" and ev not in TIMELINE_A11Y:
            continue
        if tag == "notif" and not (d.get("category") == "call" or ev.startswith("listener")):
            continue
        if tag == "sweep" and ev == "source_result":
            continue  # shown in the table
        key = (tag, ev, brief(d, 80))
        if key == last:
            repeat += 1
            continue
        if repeat:
            rows[-1] = rows[-1][:-2] + f" (×{repeat + 1}) |"
        repeat, last = 0, key
        rows.append(f"| {hhmmss(e.get('ts', 0))} | {tag}/{ev} | {brief(d).replace('|', '¦')} |")
    if repeat:
        rows[-1] = rows[-1][:-2] + f" (×{repeat + 1}) |"
    if len(rows) > MAX_TIMELINE:
        half = MAX_TIMELINE // 2
        rows = rows[:half] + [f"| … | … | {len(rows) - MAX_TIMELINE} rows omitted |"] + rows[-half:]
    return rows


def sweep_table(events):
    rows = []
    for e in events:
        if e.get("tag") == "sweep" and e.get("event") == "source_result":
            d = e["data"]
            rows.append("| {sweep} | {inCall} | {source} | {route} | {host} | {status} | {own} | {remote} | {ownP95} | {remoteP95} | {zeroPct} | {silenced} |".format(
                **{k: d.get(k, "?") for k in ("sweep", "inCall", "source", "route", "host", "status", "own", "remote", "ownP95", "remoteP95", "zeroPct", "silenced")}))
    return rows


def fgs_verdict(events):
    started = [e for e in events if e.get("tag") == "fgs" and e.get("event") == "started"]
    failed = [e for e in events if e.get("tag") == "fgs" and e.get("event") == "start_failed"]
    hosts = sorted({(e.get("data") or {}).get("host") for e in events if e.get("tag") == "sweep" and e.get("event") == "start"} - {None})
    lines = [f"- Sweep hosts used: {', '.join(hosts) or 'none'}",
             f"- FGS started: {len(started)} · FGS start failures: {len(failed)}"]
    for e in failed[:3]:
        lines.append(f"  - {hhmmss(e['ts'])} {brief(e.get('data'), 220)}")
    return lines


def top_ids(b: Bundle, run: str, n=40):
    counts, texts = collections.Counter(), collections.defaultdict(set)
    for name in b.names:
        if name.startswith(f"runs/{run}/tree/") and name.endswith(".json"):
            try:
                nodes = json.loads(b.read(name)).get("nodes", [])
            except json.JSONDecodeError:
                continue
            for node in nodes:
                vid = node.get("id")
                if vid:
                    vid = vid.split(":id/")[-1]
                    counts[vid] += 1
                    for k in ("text", "cd"):
                        if node.get(k) and len(texts[vid]) < 4:
                            texts[vid].add(str(node[k])[:40])
    return [f"| {vid} | {c} | {' ¦ '.join(sorted(texts[vid]))} |" for vid, c in counts.most_common(n)]


def summarize(b: Bundle) -> str:
    out = [f"# Spike summary — {b.path.name}", ""]
    if "bundle.json" in b.names:
        meta = json.loads(b.read("bundle.json"))
        dev = meta.get("device", {})
        out += ["## Device", "", f"- Created: {meta.get('created')} · redacted={meta.get('redacted')} · audio={meta.get('audioIncluded')}",
                f"- {brief({k: dev.get(k) for k in ('model', 'sdkInt', 'release', 'securityPatch', 'oneUi', 'whatsapp', 'whatsappBusiness')}, 400)}",
                f"- Access: {brief({k: dev.get(k) for k in ('permissions', 'accessibilityEnabled', 'notificationAccess', 'ignoringBatteryOptimizations')}, 400)}", ""]
    runs = sorted({n.split("/")[1] for n in b.names if n.startswith("runs/") and n.endswith("/events.jsonl")})
    out.append(f"Runs in bundle: {len(runs)}")
    for run in runs:
        events = load_events(b, run)
        errors = [e for e in events if (e.get("data") or {}).get("error")]
        out += ["", f"## Run `{run}` — {len(events)} events", ""]
        out += fgs_verdict(events)
        st = sweep_table(events)
        if st:
            out += ["", "### Source sweep", "", "| sweep | in call | source | route | host | status | own | remote | own p95 | remote p95 | zero% | silenced |",
                    "|---|---|---|---|---|---|---|---|---|---|---|---|", *st]
        if f"runs/{run}/matrix.md" in b.names:
            out += ["", "### matrix.md", "", b.read(f"runs/{run}/matrix.md").strip()]
        tl = timeline(events)
        if tl:
            out += ["", "### Signal timeline", "", "| time | event | data |", "|---|---|---|", *tl]
        ids = top_ids(b, run)
        if ids:
            out += ["", "### Call-screen view ids (tree dumps)", "", "| id | seen | sample text |", "|---|---|---|", *ids]
        if errors:
            out += ["", f"### Errors ({len(errors)})", ""] + [f"- {hhmmss(e['ts'])} {e['tag']}/{e['event']} {brief(e['data'], 220)}" for e in errors[:20]]
        if f"runs/{run}/crash.txt" in b.names:
            out += ["", "### crash.txt (first 30 lines)", "", "```", *b.read(f"runs/{run}/crash.txt").splitlines()[:30], "```"]
    return "\n".join(out) + "\n"


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("bundle", type=pathlib.Path)
    ap.add_argument("-o", "--out", type=pathlib.Path)
    a = ap.parse_args()
    if not a.bundle.exists():
        sys.exit(f"not found: {a.bundle}")
    text = summarize(Bundle(a.bundle))
    out = a.out or (a.bundle.with_suffix("") if a.bundle.is_file() else a.bundle) / "summary.md"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(text, "utf-8")
    sys.stdout.reconfigure(encoding="utf-8")
    print(text)
    print(f"→ {out}", file=sys.stderr)


if __name__ == "__main__":
    main()
