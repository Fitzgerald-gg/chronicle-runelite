"""Build osrs_achievement_diaries.json from the wiki's twelve region pages."""
import json, re, html, subprocess, sys, time

UA = 'Chronicle-plugin-dev/1.0 (cjrfitzgerald@gmail.com)'
REGIONS = ["Ardougne", "Desert", "Falador", "Fremennik", "Kandarin", "Karamja",
           "Kourend & Kebos", "Lumbridge & Draynor", "Morytania", "Varrock",
           "Western Provinces", "Wilderness"]
TIERS = ["Easy", "Medium", "Hard", "Elite"]
API = ("https://oldschool.runescape.wiki/api.php?action=parse&page={page}"
       "&prop=text&format=json&formatversion=2")
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

WS = re.compile(r"\s+")
LEAD = re.compile(r"^\d+\.\s*")
# footnote markers like [a] or [d 1], but not [sic]
FOOTNOTE = re.compile(r"\[(?!sic\])[a-z]{0,2}\s?\d*\]")

# "70 Defence[not boostable]" gets its space back
GLUED_NOTE = re.compile(r"(?<=\S)\[")

def text(cell):
    s = paintable(WS.sub(" ", html.unescape(re.sub(r"<[^>]+>", "", cell))).strip())
    return GLUED_NOTE.sub(" [", FOOTNOTE.sub("", s)).strip()

def outer_tables(body, attr_pat):
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
    },
    "diaries": out,
}
json.dump(doc, open(sys.argv[1], "w"), indent=1, ensure_ascii=False)
print("  %d regions, %d tasks" % (len(out),
      sum(len(v) for r in out.values() for v in r.values())), file=sys.stderr)
