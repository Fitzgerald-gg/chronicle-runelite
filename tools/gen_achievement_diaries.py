"""Build the achievement diary table from the wiki's twelve region pages.

RuneLite core ships these as compiled per-region classes, but DiaryRequirement is
package private, so reading them means reflecting into another plugin's internals.
Bundled instead, and checked the same way the combat achievements are.
"""
import json, re, html, subprocess, sys, time

UA = 'Chronicle-plugin-dev/1.0 (cjrfitzgerald@gmail.com)'
REGIONS = ["Ardougne", "Desert", "Falador", "Fremennik", "Kandarin", "Karamja",
           "Kourend & Kebos", "Lumbridge & Draynor", "Morytania", "Varrock",
           "Western Provinces", "Wilderness"]
TIERS = ["Easy", "Medium", "Hard", "Elite"]
API = ("https://oldschool.runescape.wiki/api.php?action=parse&page={page}"
       "&prop=text&format=json&formatversion=2")
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

WS = re.compile(r"\s+")
LEAD = re.compile(r"^\d+\.\s*")
# A wiki footnote reference, "[a]" or "[d 1]", pointing at a note that is not on
# the row and so means nothing once the row is on its own. "[sic]" stays: that one
# is the wiki telling the reader the GAME has the typo, which is worth keeping.
FOOTNOTE = re.compile(r"\[(?!sic\])[a-z]{0,2}\s?\d*\]")

def text(cell):
    # tags out with NO space, so "<a>mine</a>." stays "mine."
    s = paintable(WS.sub(" ", html.unescape(re.sub(r"<[^>]+>", "", cell))).strip())
    return FOOTNOTE.sub("", s).strip()

def outer_tables(body, attr_pat):
    """Every table matching attr_pat, with nested tables removed.

    A diary cell can hold a hidden tooltip that is itself a table, and a
    non-greedy match ends at ITS closing tag while a row scan picks up ITS rows.
    That is how three dialogue-option digits became three Fremennik tasks.
    """
    out = []
    for m in re.finditer(attr_pat, body):
        depth, i = 1, m.end()
        while depth and i < len(body):
            nxt_open = body.find("<table", i)
            nxt_close = body.find("</table>", i)
            if nxt_close < 0:
                break
            if 0 <= nxt_open < nxt_close:
                depth += 1
                i = nxt_open + 6
            else:
                depth -= 1
                i = nxt_close + 8
        chunk = body[m.end():i]
        # drop whole nested tables, innermost first
        prev = None
        while prev != chunk:
            prev = chunk
            chunk = re.sub(r"<table\b(?:(?!<table\b).)*?</table>", "", chunk, flags=re.S)
        out.append((m.group(1), chunk))
    return out

out = {}
for region in REGIONS:
    page = (region + " Diary").replace(" ", "%20").replace("&", "%26")
    raw = subprocess.run(["curl", "-s", "-A", UA, API.format(page=page)],
                         capture_output=True, text=True).stdout
    body = json.loads(raw).get("parse", {}).get("text", "")
    got = 0
    # The task tables name themselves. Scanning forward from the tier HEADING
    # instead found whatever table came first, which on Karamja is the audio
    # player and cost that region 45 of its 50 tasks without a word.
    tables = outer_tables(
        body, r'<table[^>]*data-diary-name="[^"]*"[^>]*data-diary-tier="([^"]*)"[^>]*>')
    for tier, chunk in tables:
        if tier.capitalize() not in TIERS:
            continue
        for r in re.findall(r"<tr>(.*?)</tr>", chunk, re.S):
            cells = re.findall(r"<t[dh][^>]*>(.*?)</t[dh]>", r, re.S)
            if len(cells) < 2:
                continue
            task = LEAD.sub("", text(cells[0]))
            req = text(cells[1])
            if not task or task == "Task":
                continue
            out.setdefault(region, {}).setdefault(tier.lower(), []).append(
                {"task": task, "requirements": req})
            got += 1
    print("  %-20s %3d tasks" % (region, got), file=sys.stderr)
    time.sleep(0.4)

doc = {
    "_meta": {
        "generated": time.strftime("%Y-%m-%d"),
        "source": "oldschool.runescape.wiki, <region> Diary",
        "schema": 1,
        "note": ("Region -> tier -> the tasks in the order the diary lists them, each "
                 "with what the wiki states it requires. `task` is what the player must "
                 "DO and is the line the panel shows."),
    },
    "diaries": out,
}
json.dump(doc, open(sys.argv[1], "w"), indent=1, ensure_ascii=False)
print("  %d regions, %d tasks" % (len(out),
      sum(len(v) for r in out.values() for v in r.values())), file=sys.stderr)
