"""Build the combat achievements table from the wiki's own rendered tier pages.

Keyed by the game's task id (data-ca-task-id), which is stable across renames.
"""
import json, re, html, subprocess, sys, time

UA = 'Chronicle-plugin-dev/1.0 (cjrfitzgerald@gmail.com)'
TIERS = ["Easy", "Medium", "Hard", "Elite", "Master", "Grandmaster"]
API = ("https://oldschool.runescape.wiki/api.php?action=parse&page=Combat%20Achievements/"
       "{tier}&prop=text&format=json&formatversion=2")

# The panel paints in the RuneScape pixel font, which has no glyph for a bullet,
# an en dash, a curly quote or an arrow: each would paint .notdef, a hollow box,
# in the middle of a requirement. Mapped to the nearest character the font DOES
# carry. This changes how a line looks, never what it says.
PAINTABLE = {
    "\u2022": "\u00b7",   # bullet -> middot
    "\u2013": "-",        # en dash
    "\u2014": "-",        # em dash, which the font has but the house style bans
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
        "note": ("Keyed by the game's own combat achievement task id, which the wiki "
                 "carries as data-ca-task-id and which survives a task being renamed. "
                 "`task` is what the player must DO and is the line the panel shows. "
                 "The totals are the wiki's own tier table, kept here so the bundle can "
                 "be checked against the count it claims rather than against itself."),
        # The wiki's Combat Achievements page states these in a table this script
        # does not read, which is what makes them an outside check rather than a
        # restatement of what was scraped.
        "points": {"easy": 1, "medium": 2, "hard": 3, "elite": 4, "master": 5,
                   "grandmaster": 6},
        "totals": {"easy": 41, "medium": 64, "hard": 89, "elite": 166, "master": 173,
                   "grandmaster": 122, "tasks": 655, "points": 2697},
    },
    "tasks": dict(sorted(out.items(), key=lambda kv: int(kv[0]))),
}
json.dump(doc, open(sys.argv[1], "w"), indent=1, ensure_ascii=False)
print(f"  {len(out)} tasks, {len(dupes)} duplicate ids", file=sys.stderr)
