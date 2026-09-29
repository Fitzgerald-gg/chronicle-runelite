/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import chronicle.Board.Obtained;
import chronicle.LocalStore.BagItem;
import chronicle.LocalStore.SourceRow;
import chronicle.LocalStore.UntakenRow;
import chronicle.SlayerLog.SlayerJourney;
import chronicle.panel.StatRegistry;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FontMetrics;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.regex.Pattern;
import javax.swing.JPanel;
import lombok.RequiredArgsConstructor;
import net.runelite.api.Skill;
import static chronicle.Json.*;
import static chronicle.KillCounts.kindOf;
import static chronicle.Reference.*;
import static chronicle.Ui.*;
import static chronicle.panel.StatRegistry.prettify;

final class SearchScreen extends Screen
{
	private static final int CAP = 4;
	private static final Pattern NAME_WORDS = Pattern.compile("[^a-z0-9]+");
	private static final Pattern QUERY_WORDS = Pattern.compile("\\s+");
	private static final Pattern NEAR_WORDS = Pattern.compile("[^a-z0-9']+");
	private static final List<String> PAGES = List.of("Quests", "Collection log", "Achievement diaries",
		"Combat achievements", "Clues", "Records", "Calendar", "Recap", "All trackers", "Kill log", "Left behind");

	Runnable searchFirst;

	@RequiredArgsConstructor
	private static final class Hit
	{
		final String name;
		final String figure;
		final Color color;
		final String tip;
		final Runnable go;
		final int score;
		final long weight;
	}

	SearchScreen(ChroniclePanel ui, Board board)
	{
		super(ui, board);
	}

	JPanel buildSearch(String q)
	{
		JPanel p = column();
		String ql = low(q);
		searchFirst = null;
		int total = section(p, "Go to", places(q, ql))
			+ section(p, "Bosses and monsters", fights(ql))
			+ section(p, "Slayer tasks", tasks(ql))
			+ section(p, "Items", items(ql))
			+ section(p, "Collection log", logSlots(ql))
			+ section(p, "Quests", quests(ql));
		if (ql.length() >= 3)
		{
			total += section(p, "Combat achievements", combatTasks(ql)) + section(p, "Diary tasks", diaryTasks(ql));
		}
		total += section(p, "Trackers", trackers(ql)) + section(p, "Journal", journal(ql));
		if (total > 0)
		{
			p.add(vgap(6));
			p.add(ghostRow("enter opens the first row", ""));
			return p;
		}
		p.add(note("Nothing matches \"" + q + "\" yet."));
		String near = nearestName(ql);
		if (near != null)
		{
			p.add(door(row("Did you mean", near, ACCENT), () -> ui.searchField.setText(near)));
		}
		return p;
	}

	private List<Hit> places(String q, String ql)
	{
		List<Hit> go = new ArrayList<>();
		Map<String, long[]> sheet = plugin.skillSheet();
		for (Skill sk : skillOrder())
		{
			String key = low(sk.name());
			String name = prettify(key);
			long[] cur = sheet.get(key);
			goTo(go, ql, name, cur != null && cur[0] > 0 ? "level " + cur[0] : "",
				sk == Skill.SLAYER ? () -> ui.openSlayer("Tasks") : () -> ui.openSkill(name), 2,
				SKILL_ALIASES.getOrDefault(key, new String[0]));
		}
		JsonObject quests = obj(board.achievements(), "quests");
		long done = quests.entrySet().stream().filter(e -> "FINISHED".equals(e.getValue().getAsString())).count();
		goTo(go, ql, "Quests", quests.size() > 0 ? fmt(done) + " / " + fmt(quests.size()) : "", () -> ui.openSheetPage("quests"), 1, "quest");
		goTo(go, ql, "Collection log", plugin.clogAvailable() > 0 ? fmt(plugin.clogFinished()) + " / " + fmt(plugin.clogAvailable()) : "",
			() -> ui.openSheetPage("log"), 1, "clog", "log");
		goTo(go, ql, "Achievement diaries", "", () -> ui.openSheetPage("diaries"), 1, "diary", "diaries");
		goTo(go, ql, "Combat achievements", "", () -> ui.openSheetPage("combat"), 1, "ca", "cas", "combat tasks");
		goTo(go, ql, "Clues", "", () -> ui.openSheetPage("clues"), 1, "clue", "clue scrolls", "caskets", "treasure trails");
		goTo(go, ql, "Records", "", ui::openRecords, 1, "record", "best", "bests", "pb", "personal best");
		goTo(go, ql, "Calendar", "", ui::openCalendar, 1, "days", "days written");
		goTo(go, ql, "Recap", "", () -> ui.show(ChroniclePanel.View.RECAP), 1, "summary");
		goTo(go, ql, "All trackers", "", ui::openAllTrackers, 1, "trackers", "counters");
		goTo(go, ql, "Kill log", "", () -> ui.openSlayer("Monsters"), 1, "killlog", "kill count", "kc");
		goTo(go, ql, "Left behind", "", () -> ui.openLeftBehind(null), 1, "untaken", "left on the floor");
		goTo(go, ql, "Info", "what the journal holds", ui::openInfo, 1, "journal holds");
		String kind = ItemKinds.named(q);
		if (kind != null && !ql.isEmpty())
		{
			int ks = matchScore(ql, kind);
			ks = ks < 0 || ks > 2 ? 2 : ks;
			go.add(new Hit(kind, "every one you have had", null, null, () -> ui.openLootKind(kind, false), ks, 0));
			if (board.everOnTask() && board.hasKindOnTask(kind))
			{
				go.add(new Hit(kind, "from slayer tasks", null, null, () -> ui.openLootKind(kind, true), ks, -1));
			}
		}
		return go;
	}

	private List<Hit> fights(String ql)
	{
		List<Hit> fights = new ArrayList<>();
		Set<String> kinds = new HashSet<>();
		Map<String, SourceRow> rows = new HashMap<>();
		board.sources().forEach(r -> rows.put(r.name, r));
		for (Boss b : bossRoster(plugin.gson()))
		{
			int sc = matchScore(ql, b.name);
			if (sc < 0 || !kinds.add(kindOf(b.name)))
			{
				continue;
			}
			String open = board.bossLootSource(b);
			kinds.add(kindOf(open));
			SourceRow r = rows.get(open);
			boolean own = r != null && kindOf(open).equals(kindOf(b.name)) && board.isKillSource(open);
			long n = own ? board.standingKills(r) : board.bossKills(b.name);
			fights.add(new Hit(b.name, n > 0 ? fmt(n) + " kc" : "-", null, r == null ? null : worth(r),
				() -> ui.openSourceLoose(open), sc, n));
		}
		for (SourceRow r : board.sources())
		{
			int sc = matchScore(ql, r.name);
			if (sc >= 0 && kinds.add(kindOf(r.name)))
			{
				boolean killed = board.isKillSource(r.name);
				long n = killed ? board.standingKills(r) : r.loots;
				fights.add(new Hit(r.name, killed ? fmt(n) + " kc" : count(r.loots, "drop"), null, worth(r),
					() -> ui.openSource(r.name), sc, n));
			}
		}
		for (Entry<String, JsonElement> e : obj(board.clogNow(), "slayer_kcs").entrySet())
		{
			int sc = matchScore(ql, e.getKey());
			if (sc >= 0 && kinds.add(kindOf(e.getKey())))
			{
				long n = asLong(e.getValue());
				fights.add(new Hit(e.getKey(), fmt(n) + " kc", null, "In the Kill Log", () -> ui.openSlayer("Monsters"), sc, n));
			}
		}
		return fights;
	}

	private static String worth(SourceRow r)
	{
		return gps(r.value) + (r.pb != null ? " · PB " + pb(r.pb) : "");
	}

	private List<Hit> tasks(String ql)
	{
		List<Hit> tasks = new ArrayList<>();
		SlayerJourney journey = ui.slayer.journeyCache != null ? ui.slayer.journeyCache : board.historyJourney;
		ui.slayer.fetchJourney(true);
		if (journey == null)
		{
			return tasks;
		}
		Map<String, int[]> byTask = new LinkedHashMap<>();
		for (int i = 0; i < journey.tasks.size(); i++)
		{
			String task = journey.tasks.get(i).task;
			if (task != null && matchScore(ql, task) >= 0)
			{
				int at = i;
				byTask.computeIfAbsent(low(task), k -> new int[]{0, at})[0]++;
			}
		}
		for (int[] seen : byTask.values())
		{
			String name = journey.tasks.get(seen[1]).task;
			tasks.add(new Hit(name, count(seen[0], "task"), null, "Opens the newest", () ->
			{
				if (ui.slayer.journeyCache == null)
				{
					ui.slayer.journeyCache = journey;
				}
				ui.show(ChroniclePanel.View.SLAYER);
				ui.showTask(seen[1]);
			}, matchScore(ql, name), seen[0]));
		}
		return tasks;
	}

	private List<Hit> items(String ql)
	{
		Map<String, long[]> agg = new LinkedHashMap<>();
		Map<String, List<String>> from = new LinkedHashMap<>();
		for (SourceRow src : board.sources())
		{
			for (BagItem b : store.sourceItems(src.name))
			{
				if (matchScore(ql, b.name) >= 0)
				{
					long[] a = agg.computeIfAbsent(b.name, k -> new long[2]);
					a[0] += b.qty;
					a[1] += b.value;
					from.computeIfAbsent(b.name, k -> new ArrayList<>()).add(src.name);
				}
			}
		}
		List<Hit> items = new ArrayList<>();
		agg.forEach((item, a) ->
		{
			List<String> srcs = from.get(item);
			String tip = (a[1] > 0 ? gp(a[1]) + " gp · " : "") + "from " + String.join(", ", firstN(srcs, 4))
				+ (srcs.size() > 4 ? " and " + (srcs.size() - 4) + " more" : "");
			items.add(new Hit(item, "×" + fmt(a[0]), null, tip, () -> ui.openItem(item), matchScore(ql, item), a[1]));
		});
		for (UntakenRow u : store.untakenItems())
		{
			if (!agg.containsKey(u.name) && matchScore(ql, u.name) >= 0)
			{
				items.add(new Hit(u.name, "×" + fmt(u.qty) + " left", RED, "Left on the floor",
					() -> ui.openLeftBehind(u.name), matchScore(ql, u.name), u.value));
			}
		}
		return items;
	}

	private List<Hit> logSlots(String ql)
	{
		List<Hit> log = new ArrayList<>();
		Obtained ob = Board.obtained(board.clogNow());
		Set<String> seen = new HashSet<>();
		for (Map<String, List<String>> tab : taxonomy(plugin.gson()).values())
		{
			for (Entry<String, List<String>> pg : tab.entrySet())
			{
				String page = pg.getKey();
				List<String> slots = pg.getValue();
				boolean[] lit = Board.lightSlots(slots, ob.byPage.get(low(page)), ob.all, sharedSlotNames(plugin.gson()));
				int ps = matchScore(ql, page);
				if (ps >= 0)
				{
					int held = 0;
					for (boolean l : lit)
					{
						held += l ? 1 : 0;
					}
					log.add(new Hit(page, held + " / " + slots.size(), null, "The page", () -> ui.openLogPage(page), Math.max(0, ps - 1), 2));
				}
				for (String slot : slots)
				{
					int sc = matchScore(ql, slot);
					if (sc >= 0 && seen.add(slot))
					{
						boolean got = false;
						for (int i = 0; i < lit.length; i++)
						{
							got |= lit[i] && slots.get(i).equalsIgnoreCase(slot);
						}
						log.add(new Hit(slot, page, got ? GREEN : RED, got ? "Held" : "Missing", () -> ui.openLogPage(page), sc, got ? 1 : 0));
					}
				}
			}
		}
		return log;
	}

	private List<Hit> quests(String ql)
	{
		List<Hit> hits = new ArrayList<>();
		for (Entry<String, JsonElement> q : obj(board.achievements(), "quests").entrySet())
		{
			int sc = matchScore(ql, q.getKey());
			if (sc >= 0)
			{
				String state = q.getValue().getAsString();
				boolean done = "FINISHED".equals(state);
				hits.add(new Hit(q.getKey(), done ? "complete" : "IN_PROGRESS".equals(state) ? "in progress" : "not started",
					done ? GREEN : RED, null, () -> ui.openSheetPage("quests"), sc, done ? 1 : 0));
			}
		}
		return hits;
	}

	private List<Hit> combatTasks(String ql)
	{
		Set<Integer> done = board.caDone();
		List<Hit> hits = new ArrayList<>();
		for (String id : CA_TASKS.keySet())
		{
			JsonObject t = CA_TASKS.getAsJsonObject(id);
			String name = t.get("name").getAsString();
			String task = t.get("task").getAsString();
			int sc = matchScore(ql, name);
			sc = sc < 0 && low(task).contains(ql) ? 3 : sc;
			if (sc >= 0)
			{
				boolean has = done.contains(Integer.parseInt(id));
				hits.add(new Hit(name, prettyTier(low(t.get("tier").getAsString())), done.isEmpty() ? null : has ? GREEN : RED,
					t.get("monster").getAsString() + ": " + task, () -> ui.openSheetPage("combat"), sc, has ? 1 : 0));
			}
		}
		return hits;
	}

	private List<Hit> diaryTasks(String ql)
	{
		List<Hit> hits = new ArrayList<>();
		for (Entry<String, JsonObject> region : objects(DIARY_TASKS))
		{
			for (Entry<String, JsonElement> tier : region.getValue().entrySet())
			{
				for (JsonObject t : objects(tier.getValue().getAsJsonArray()))
				{
					String task = t.get("task").getAsString();
					if (low(task).contains(ql))
					{
						String needs = str(t, "requirements", "");
						hits.add(new Hit(firstSentence(task), low(tier.getKey()), null,
							region.getKey() + " " + low(tier.getKey()) + ": " + task
								+ (needs.isEmpty() || "None".equalsIgnoreCase(needs) ? "" : " Needs: " + needs),
							() -> ui.openSheetPage("diaries"), 3, 0));
					}
				}
			}
		}
		return hits;
	}

	private List<Hit> trackers(String ql)
	{
		List<Hit> hits = new ArrayList<>();
		board.withLedgerSpend(board.counters()).forEach((key, v) ->
		{
			String label = StatRegistry.label(key);
			int sc = Math.min(score(matchScore(ql, label)), score(matchScore(ql, key)));
			if (v != 0 && !StatRegistry.hidden(key) && sc < Integer.MAX_VALUE)
			{
				hits.add(new Hit(label, StatRegistry.isGp(key) ? gps(v) : fmt(v), null, null, ui::openAllTrackers, sc, v));
			}
		});
		return hits;
	}

	private static int score(int s)
	{
		return s < 0 ? Integer.MAX_VALUE : s;
	}

	private List<Hit> journal(String ql)
	{
		List<Hit> hits = new ArrayList<>();
		int year = LocalDate.now().getYear();
		String qj = ql.replace("'", "");
		if (qj.trim().isEmpty())
		{
			return hits;
		}
		for (Object[] line : board.searchFeed())
		{
			if (((String) line[0]).contains(qj))
			{
				long at = (Long) line[2];
				String day = at <= 0 ? "" : (dayOf(at).getYear() == year ? DAY : TASK_DAY).format(Instant.ofEpochMilli(at));
				hits.add(new Hit((String) line[1], day, null, null, () -> ui.openJournalOn(at), 0, at));
			}
		}
		return hits;
	}

	private int section(JPanel p, String title, List<Hit> hits)
	{
		if (hits.isEmpty())
		{
			return 0;
		}
		hits.sort((a, b) -> a.score != b.score ? Integer.compare(a.score, b.score) : Long.compare(b.weight, a.weight));
		p.add(group(title));
		String key = "search:" + title;
		int cap = ui.cap(key, CAP);
		FontMetrics fm = rowMetrics();
		for (Hit h : firstN(hits, cap))
		{
			String figure = h.figure == null ? "" : h.figure;
			int room = boardRowRoom() - ROW_GAP - (figure.isEmpty() ? 0 : fm.stringWidth(figure));
			String shown = h.name;
			for (int keep = shown.length() - 1; fm.stringWidth(shown) > room && keep >= NAME_FLOOR; keep--)
			{
				shown = stub(h.name, keep);
			}
			JPanel r = row(shown, figure, null, false);
			if (h.color != null)
			{
				part(r, BorderLayout.CENTER).setForeground(h.color);
			}
			String tip = shown.equals(h.name) ? h.tip : h.tip == null ? h.name : h.name + ": " + h.tip;
			if (tip != null)
			{
				r.setToolTipText(wrappedTip(tip));
			}
			p.add(door(r, h.go));
		}
		ui.drillMore(p, key, hits.size(), cap);
		return hits.size();
	}

	private JPanel door(JPanel r, Runnable go)
	{
		if (searchFirst == null)
		{
			searchFirst = go;
		}
		return link(r, go);
	}

	private static void goTo(List<Hit> go, String ql, String name, String figure, Runnable to, long weight, String... also)
	{
		int sc = matchScore(ql, name);
		sc = sc > 2 ? -1 : sc;
		for (String a : also)
		{
			sc = a.equals(ql) ? 0 : sc;
		}
		if (sc >= 0)
		{
			go.add(new Hit(name, figure, null, null, to, sc, weight));
		}
	}

	private static int matchScore(String ql, String name)
	{
		if (name == null || ql.isEmpty())
		{
			return -1;
		}
		String raw = low(name);
		if (raw.equals(ql) || ql.endsWith("s") && raw.equals(ql.substring(0, ql.length() - 1))
			|| raw.endsWith("s") && raw.substring(0, raw.length() - 1).equals(ql))
		{
			return 0;
		}
		String n = raw.replace("'", "");
		String q = ql.replace("'", "");
		if (q.trim().isEmpty())
		{
			return -1;
		}
		if (n.equals(q))
		{
			return 0;
		}
		if (n.startsWith(q))
		{
			return 1;
		}
		String[] words = NAME_WORDS.split(n);
		boolean every = true;
		for (String w : QUERY_WORDS.split(q))
		{
			boolean begins = w.isEmpty();
			for (String x : words)
			{
				begins |= x.startsWith(w);
			}
			every &= begins;
		}
		return every || initials(q, words) ? 2 : n.contains(q) ? 3 : -1;
	}

	private static boolean initials(String q, String[] words)
	{
		if (q.length() < 2 || q.indexOf(' ') >= 0 || words.length < 2)
		{
			return false;
		}
		StringBuilder all = new StringBuilder();
		StringBuilder lean = new StringBuilder();
		for (String w : words)
		{
			if (!w.isEmpty())
			{
				all.append(w.charAt(0));
				if (!w.equals("the") && !w.equals("of"))
				{
					lean.append(w.charAt(0));
				}
			}
		}
		return q.contentEquals(all) || q.contentEquals(lean);
	}

	private String nearestName(String ql)
	{
		if (ql.length() < 4)
		{
			return null;
		}
		List<String> names = new ArrayList<>();
		for (SourceRow r : board.sources())
		{
			names.add(r.name);
			store.sourceItems(r.name).forEach(b -> names.add(b.name));
		}
		for (Map<String, List<String>> tab : taxonomy(plugin.gson()).values())
		{
			names.addAll(tab.keySet());
			tab.values().forEach(names::addAll);
		}
		bossRoster(plugin.gson()).forEach(b -> names.add(b.name));
		names.addAll(obj(board.clogNow(), "slayer_kcs").keySet());
		names.addAll(obj(board.achievements(), "quests").keySet());
		skillOrder().forEach(sk -> names.add(prettify(low(sk.name()))));
		names.addAll(PAGES);
		String best = null;
		int nearest = 3;
		for (String name : names)
		{
			String low = low(name);
			int d = editsBetween(ql, low, nearest);
			for (String word : NEAR_WORDS.split(low))
			{
				d = word.length() >= 4 ? Math.min(d, editsBetween(ql, word, nearest)) : d;
			}
			if (d < nearest)
			{
				nearest = d;
				best = name;
			}
		}
		return best;
	}

	private static int editsBetween(String a, String b, int cap)
	{
		if (Math.abs(a.length() - b.length()) >= cap)
		{
			return cap;
		}
		int[] prev = new int[b.length() + 1];
		int[] cur = new int[b.length() + 1];
		for (int j = 0; j <= b.length(); j++)
		{
			prev[j] = j;
		}
		for (int i = 1; i <= a.length(); i++)
		{
			cur[0] = i;
			int rowMin = i;
			for (int j = 1; j <= b.length(); j++)
			{
				cur[j] = Math.min(prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1), Math.min(prev[j] + 1, cur[j - 1] + 1));
				rowMin = Math.min(rowMin, cur[j]);
			}
			if (rowMin >= cap)
			{
				return cap;
			}
			int[] t = prev;
			prev = cur;
			cur = t;
		}
		return Math.min(cap, prev[b.length()]);
	}
}
