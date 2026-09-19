"""Build the combat achievements table from the wiki's own rendered tier pages.

Keyed by the game's task id (data-ca-task-id), which is stable across renames.
"""
import json, re, html, subprocess, sys, time

UA = 'Chronicle-plugin-dev/1.0 (cjrfitzgerald@gmail.com)'
TIERS = ["Easy", "Medium", "Hard", "Elite", "Master", "Grandmaster"]
API = ("https://oldschool.runescape.wiki/api.php?action=parse&page=Combat%20Achievements/"
       "{tier}&prop=text&format=json&formatversion=2")

def strip(c):
    return html.unescape(re.sub(r'<[^>]+>', '', c)).strip()

out, dupes = {}, []
for tier in TIERS:
    raw = subprocess.run(["curl", "-s", "-A", UA, API.format(tier=tier)],
                         capture_output=True, text=True).stdout
    page = json.loads(raw).get("parse", {}).get("text", "")
    rows = re.findall(r'<tr data-ca-task-id="(\d+)">(.*?)</tr>', page, re.S)
    for tid, body in rows:
        cells = [strip(c) for c in re.findall(r'<td[^>]*>(.*?)</td>', body, re.S)]
        if len(cells) < 4:
            continue
        monster, name, task, kind = cells[0], cells[1], cells[2], cells[3]
        if tid in out:
            dupes.append(tid)
        out[tid] = {"name": name, "tier": tier.lower(), "monster": monster,
                    "task": task, "type": kind}
    print(f"  {tier:<12} {len(rows):>3} tasks", file=sys.stderr)
    time.sleep(0.4)

doc = {
    "_meta": {
        "generated": time.strftime("%Y-%m-%d"),
        "source": "oldschool.runescape.wiki, Combat Achievements/<tier>",
        "schema": 1,
        "note": ("Keyed by the game's own combat achievement task id, which the wiki "
                 "carries as data-ca-task-id and which survives a task being renamed. "
                 "`task` is what the player must DO and is the line the panel shows."),
    },
    "tasks": dict(sorted(out.items(), key=lambda kv: int(kv[0]))),
}
json.dump(doc, open(sys.argv[1], "w"), indent=1, ensure_ascii=False)
print(f"  {len(out)} tasks, {len(dupes)} duplicate ids", file=sys.stderr)
