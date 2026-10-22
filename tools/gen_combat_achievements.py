"""Build osrs_combat_achievements.json from the wiki's six tier pages."""
import json, re, html, subprocess, sys, time

UA = 'Chronicle-plugin-dev/1.0 (cjrfitzgerald@gmail.com)'
TIERS = ["Easy", "Medium", "Hard", "Elite", "Master", "Grandmaster"]
API = ("https://oldschool.runescape.wiki/api.php?action=parse&page=Combat%20Achievements/"
       "{tier}&prop=text&format=json&formatversion=2")

# Characters the RuneScape font cannot draw
PAINTABLE = {
    "\u2022": "\u00b7",
    "\u2013": "-",
    "\u2014": "-",
    "\u2018": "'", "\u2019": "'",
    "\u201c": '"', "\u201d": '"',
    "\u2192": "->",
}

def paintable(s):
    for bad, good in PAINTABLE.items():
        s = s.replace(bad, good)
    return s

def strip(c):
    return paintable(html.unescape(re.sub(r'<[^>]+>', '', c)).strip())

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
        # copied from the wiki's tier table, not scraped
        "points": {"easy": 1, "medium": 2, "hard": 3, "elite": 4, "master": 5,
                   "grandmaster": 6},
        "totals": {"easy": 41, "medium": 64, "hard": 89, "elite": 166, "master": 173,
                   "grandmaster": 122, "tasks": 655, "points": 2697},
    },
    "tasks": dict(sorted(out.items(), key=lambda kv: int(kv[0]))),
}
json.dump(doc, open(sys.argv[1], "w"), indent=1, ensure_ascii=False)
print(f"  {len(out)} tasks, {len(dupes)} duplicate ids", file=sys.stderr)
