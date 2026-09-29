/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import chronicle.Board.Obtained;
import chronicle.LocalStore.BagItem;
import chronicle.LocalStore.SlayerJourney;
import chronicle.LocalStore.SlayerTask;
import chronicle.LocalStore.SourceRow;
import chronicle.LocalStore.UntakenRow;
import chronicle.panel.StatRegistry;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FontMetrics;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.regex.Pattern;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import net.runelite.api.Skill;
import static chronicle.Feed.*;
import static chronicle.LocalStore.kindOf;
import static chronicle.Pictures.*;
import static chronicle.Reference.*;
import static chronicle.Ui.*;
import static chronicle.panel.StatRegistry.prettify;

final class SearchScreen
{
	private final ChroniclePanel ui;
	private final Board board;
	private final ChroniclePlugin plugin;
	private final Period period;

	SearchScreen(ChroniclePanel ui, Board board)
	{
		this.ui = ui;
		this.board = board;
		this.plugin = board.plugin;
		this.period = board.period;
	}

	JPanel buildSearch(String q)
	{
		JPanel p = column();
		String ql = low(q);
		int total = 0;
		searchFirst = null;

		List<Hit> go = new ArrayList<>();
		Map<String, long[]> sheet = plugin.skillSheet();
		for (Skill sk : skillOrder())
		{
			String key = low(sk.name());
			String name = prettify(key);
			long[] cur = sheet.get(key);
			Runnable to = sk == Skill.SLAYER ? () -> ui.openSlayer("Tasks") : () -> ui.openSkill(name);
			goTo(go, ql, name, cur != null && cur[0] > 0 ? "level " + cur[0] : "", to, 2,
				SKILL_ALIASES.getOrDefault(key, new String[0]));
		}
		JsonObject quests = obj(board.achievements(), "quests");
		long questsDone = 0;
		for (String name : quests.keySet())
		{
			questsDone += "FINISHED".equals(quests.get(name).getAsString()) ? 1 : 0;
		}
		goTo(go, ql, "Quests", quests.size() > 0 ? fmt(questsDone) + " / " + fmt(quests.size()) : "",
			() -> ui.openSheetPage("quests"), 1, "quest");
		goTo(go, ql, "Collection log", plugin.clogAvailable() > 0
			? fmt(plugin.clogFinished()) + " / " + fmt(plugin.clogAvailable()) : "",
			() -> ui.openSheetPage("log"), 1, "clog", "log");
		goTo(go, ql, "Achievement diaries", "", () -> ui.openSheetPage("diaries"), 1,
			"diary", "diaries");
		goTo(go, ql, "Combat achievements", "", () -> ui.openSheetPage("combat"), 1,
			"ca", "cas", "combat tasks");
		goTo(go, ql, "Clues", "", () -> ui.openSheetPage("clues"), 1,
			"clue", "clue scrolls", "caskets", "treasure trails");
		goTo(go, ql, "Records", "", ui::openRecords, 1,
			"record", "best", "bests", "pb", "personal best");
		goTo(go, ql, "Calendar", "", ui::openCalendar, 1, "days", "days written");
		goTo(go, ql, "Recap", "", () ->
		{
			ui.subByTab.put(ChroniclePanel.Tab.RECORD, "Recap");
			ui.applyTab(ChroniclePanel.Tab.RECORD);
			ui.rebuild();
		}, 1, "summary");
		goTo(go, ql, "All trackers", "", ui::openAllTrackers, 1, "trackers", "counters");
		goTo(go, ql, "Kill log", "", () -> ui.openSlayer("Monsters"), 1, "killlog", "kill count", "kc");
		goTo(go, ql, "Left behind", "", () ->
		{
			ui.applyTab(ChroniclePanel.View.DROPS);
			ui.loot.dropsLeftBehind = true;
			ui.rebuild();
		}, 1, "untaken", "left on the floor");
		goTo(go, ql, "Info", "what the journal holds", ui::openInfo, 1, "journal holds");
		String kind = ItemKinds.named(q);
		if (kind != null && !ql.isEmpty())
		{
			final String pick = kind;
			int ks = matchScore(ql, kind);
			ks = ks < 0 || ks > 2 ? 2 : ks;
			go.add(new Hit(kind, "every one you have had", null, null,
				() -> ui.openLootKind(pick, false), ks, 0));
			if (board.everOnTask() && board.hasKindOnTask(pick))
			{
				go.add(new Hit(kind, "from slayer tasks", null, null, () -> ui.openLootKind(pick, true), ks, -1));
			}
		}
		total += searchGroup(p, "Go to", go);

		List<Hit> fights = new ArrayList<>();
		Set<String> kinds = new HashSet<>();
		Map<String, SourceRow> ledgerRows = new HashMap<>();
		for (SourceRow r : board.sources())
		{
			ledgerRows.put(r.name, r);
		}
		for (Boss b : bossRoster(plugin.gson()))
		{
			int sc = matchScore(ql, b.name);
			if (sc < 0 || !kinds.add(kindOf(b.name)))
			{
				continue;
			}
			final String open = board.bossLootSource(b);
			kinds.add(kindOf(open));
			SourceRow r = ledgerRows.get(open);
			boolean own = r != null && kindOf(open).equals(kindOf(b.name))
				&& board.isKillSource(open);
			long n = own ? board.standingKills(r) : board.bossKills(b.name);
			fights.add(new Hit(b.name, n > 0 ? fmt(n) + " kc" : "-", null,
				r == null ? null : gps(r.value) + (r.pb != null ? " · PB " + pb(r.pb) : ""),
				() -> ui.openSourceLoose(open), sc, n));
		}
		for (SourceRow r : board.sources())
		{
			int sc = matchScore(ql, r.name);
			if (sc < 0 || !kinds.add(kindOf(r.name)))
			{
				continue;
			}
			boolean killed = board.isKillSource(r.name);
			long n = killed ? board.standingKills(r) : r.loots;
			fights.add(new Hit(r.name, killed ? fmt(n) + " kc" : count(r.loots, "drop"), null,
				gps(r.value) + (r.pb != null ? " · PB " + pb(r.pb) : ""),
				() -> ui.openSource(r.name), sc, n));
		}
		JsonObject cl = board.clogNow();
		for (Entry<String, JsonElement> e : obj(cl, "slayer_kcs").entrySet())
		{
			int sc = matchScore(ql, e.getKey());
			if (sc < 0 || !kinds.add(kindOf(e.getKey())))
			{
				continue;
			}
			long n = safeLong(e.getValue());
			fights.add(new Hit(e.getKey(), fmt(n) + " kc", null, "In the Kill Log", () -> ui.openSlayer("Monsters"), sc, n));
		}
		total += searchGroup(p, "Bosses and monsters", fights);

		List<Hit> tasks = new ArrayList<>();
		final SlayerJourney journey = ui.journeyCache != null ? ui.journeyCache : board.historyJourney;
		fetchJourneyForSearch();
		if (journey != null)
		{
			Map<String, int[]> byTask = new LinkedHashMap<>();
			for (int i = 0; i < journey.tasks.size(); i++)
			{
				SlayerTask t = journey.tasks.get(i);
				if (t.task == null || matchScore(ql, t.task) < 0)
				{
					continue;
				}
				int[] seen = byTask.computeIfAbsent(low(t.task), k -> new int[]{0, -1});
				seen[0]++;
				if (seen[1] < 0)
				{
					seen[1] = i;
				}
			}
			for (int[] seen : byTask.values())
			{
				final int at = seen[1];
				String name = journey.tasks.get(at).task;
				tasks.add(new Hit(name, count(seen[0], "task"), null, "Opens the newest", () ->
				{
					if (ui.journeyCache == null)
					{
						ui.journeyCache = journey;
					}
					ui.applyTab(ChroniclePanel.View.SLAYER);
					ui.showTask(at);
				}, matchScore(ql, name), seen[0]));
			}
		}
		total += searchGroup(p, "Slayer tasks", tasks);

		Map<String, long[]> itemAgg = new LinkedHashMap<>();
		Map<String, List<String>> itemSrcs = new LinkedHashMap<>();
		for (SourceRow src : board.sources())
		{
			for (BagItem b : plugin.sourceItems(src.name))
			{
				if (matchScore(ql, b.name) < 0)
				{
					continue;
				}
				long[] agg = itemAgg.computeIfAbsent(b.name, k -> new long[2]);
				agg[0] += b.qty;
				agg[1] += b.value;
				itemSrcs.computeIfAbsent(b.name, k -> new ArrayList<>()).add(src.name);
			}
		}
		List<Hit> items = new ArrayList<>();
		for (Entry<String, long[]> e : itemAgg.entrySet())
		{
			final String itm = e.getKey();
			List<String> from = itemSrcs.get(itm);
			String tip = (e.getValue()[1] > 0 ? gp(e.getValue()[1]) + " gp · " : "") + "from "
				+ String.join(", ", from.subList(0, Math.min(4, from.size())))
				+ (from.size() > 4 ? " and " + (from.size() - 4) + " more" : "");
			items.add(new Hit(itm, "×" + fmt(e.getValue()[0]), null, tip, () -> ui.openItem(itm),
				matchScore(ql, itm), e.getValue()[1]));
		}
		for (UntakenRow u : plugin.untakenItems())
		{
			if (itemAgg.containsKey(u.name) || matchScore(ql, u.name) < 0)
			{
				continue;
			}
			items.add(new Hit(u.name, "×" + fmt(u.qty) + " left", RED, "Left on the floor", () ->
			{
				ui.applyTab(ChroniclePanel.View.DROPS);
				ui.loot.dropsLeftBehind = true;
				ui.leftBehindItem = u.name;
				ui.rebuild();
			}, matchScore(ql, u.name), u.value));
		}
		total += searchGroup(p, "Items", items);

		List<Hit> log = new ArrayList<>();
		Obtained ob = Board.obtained(board.clogNow());
		Set<String> slotSeen = new HashSet<>();
		for (Map<String, List<String>> tab : taxonomy(plugin.gson()).values())
		{
			for (Entry<String, List<String>> pg : tab.entrySet())
			{
				final String page = pg.getKey();
				int ps = matchScore(ql, page);
				boolean[] lit = null;
				if (ps >= 0)
				{
					lit = Board.lightSlots(pg.getValue(), ob.byPage.get(low(page)),
						ob.all, sharedSlotNames(plugin.gson()));
					int held = 0;
					for (boolean l : lit)
					{
						held += l ? 1 : 0;
					}
					log.add(new Hit(page, held + " / " + pg.getValue().size(), null, "The page",
						() -> ui.openLogPage(page), Math.max(0, ps - 1), 2));
				}
				for (String slot : pg.getValue())
				{
					int sc = matchScore(ql, slot);
					if (sc < 0 || !slotSeen.add(slot))
					{
						continue;
					}
					if (lit == null)
					{
						lit = Board.lightSlots(pg.getValue(), ob.byPage.get(low(page)),
							ob.all, sharedSlotNames(plugin.gson()));
					}
					boolean got = false;
					for (int i = 0; i < lit.length; i++)
					{
						got |= lit[i] && pg.getValue().get(i).equalsIgnoreCase(slot);
					}
					log.add(new Hit(slot, page, got ? GREEN : RED,
						got ? "Held" : "Missing", () -> ui.openLogPage(page), sc, got ? 1 : 0));
				}
			}
		}
		total += searchGroup(p, "Collection log", log);

		List<Hit> questHits = new ArrayList<>();
		for (String name : quests.keySet())
		{
			int sc = matchScore(ql, name);
			if (sc < 0)
			{
				continue;
			}
			String state = quests.get(name).getAsString();
			boolean done = "FINISHED".equals(state);
			questHits.add(new Hit(name, done ? "complete" : "IN_PROGRESS".equals(state)
				? "in progress" : "not started", done ? GREEN : RED, null,
				() -> ui.openSheetPage("quests"), sc, done ? 1 : 0));
		}
		total += searchGroup(p, "Quests", questHits);

		if (ql.length() >= 3)
		{
			Set<Integer> done = board.caDone();
			JsonObject cas = CA_TASKS;
			List<Hit> caHits = new ArrayList<>();
			for (String id : cas.keySet())
			{
				JsonObject t = cas.getAsJsonObject(id);
				String name = t.get("name").getAsString();
				String task = t.get("task").getAsString();
				int sc = matchScore(ql, name);
				if (sc < 0 && low(task).contains(ql))
				{
					sc = 3;
				}
				if (sc < 0)
				{
					continue;
				}
				boolean has = done.contains(Integer.parseInt(id));
				caHits.add(new Hit(name, prettyTier(low(t.get("tier").getAsString())),
					done.isEmpty() ? null : has ? GREEN : RED,
					t.get("monster").getAsString() + ": " + task, () -> ui.openSheetPage("combat"), sc,
					has ? 1 : 0));
			}
			total += searchGroup(p, "Combat achievements", caHits);

			JsonObject diaries = DIARY_TASKS;
			List<Hit> diaryHits = new ArrayList<>();
			for (String region : diaries.keySet())
			{
				JsonObject tiers = diaries.getAsJsonObject(region);
				for (String tier : tiers.keySet())
				{
					for (JsonElement e : tiers.getAsJsonArray(tier))
					{
						JsonObject t = e.getAsJsonObject();
						String task = t.get("task").getAsString();
						if (!low(task).contains(ql))
						{
							continue;
						}
						String needs = t.has("requirements") ? t.get("requirements").getAsString() : "";
						diaryHits.add(new Hit(firstSentence(task), low(tier), null,
							region + " " + low(tier) + ": " + task
								+ (needs.isEmpty() || "None".equalsIgnoreCase(needs) ? "" : " Needs: " + needs),
							() -> ui.openSheetPage("diaries"), 3, 0));
					}
				}
			}
			total += searchGroup(p, "Diary tasks", diaryHits);
		}

		List<Hit> stats = new ArrayList<>();
		for (Entry<String, Long> e : board.withLedgerSpend(board.counters()).entrySet())
		{
			if (e.getValue() == 0 || StatRegistry.hidden(e.getKey()))
			{
				continue;
			}
			String label = StatRegistry.label(e.getKey());
			int sc = bestScore(ql, label, e.getKey());
			if (sc < 0)
			{
				continue;
			}
			stats.add(new Hit(label, StatRegistry.isGp(e.getKey()) ? gps(e.getValue()) : fmt(e.getValue()),
				null, null, ui::openAllTrackers, sc, e.getValue()));
		}
		total += searchGroup(p, "Trackers", stats);

		List<Hit> journal = new ArrayList<>();
		int thisYear = LocalDate.now().getYear();
		String qj = ql.replace("'", "");
		for (Object[] line : board.searchFeed())
		{
			if (qj.trim().isEmpty() || !((String) line[0]).contains(qj))
			{
				continue;
			}
			final long at = (Long) line[2];
			String day = at <= 0 ? "" : (dayOf(at).getYear() == thisYear ? DAY : TASK_DAY)
				.format(Instant.ofEpochMilli(at));
			journal.add(new Hit((String) line[1], day, null, null, () -> ui.openJournalOn(at), 0, at));
		}
		total += searchGroup(p, "Journal", journal);

		if (total == 0)
		{
			p.add(note("Nothing matches \"" + q + "\" yet."));
			final String near = nearestName(ql);
			if (near != null)
			{
				JPanel r = row("Did you mean", near, ACCENT);
				door(r, () -> ui.searchField.setText(near));
				p.add(r);
			}
		}
		else
		{
			p.add(vgap(6));
			p.add(ghostRow("enter opens the first row", ""));
		}
		return p;
	}

	String nearestName(String ql)
	{
		if (ql.length() < 4)
		{
			return null;
		}
		List<String> names = new ArrayList<>();
		for (SourceRow r : board.sources())
		{
			names.add(r.name);
			for (BagItem b : plugin.sourceItems(r.name))
			{
				names.add(b.name);
			}
		}
		for (Map<String, List<String>> tab : taxonomy(plugin.gson()).values())
		{
			names.addAll(tab.keySet());
			for (List<String> slots : tab.values())
			{
				names.addAll(slots);
			}
		}
		for (Boss b : bossRoster(plugin.gson()))
		{
			names.add(b.name);
		}
		JsonObject cl = board.clogNow();
		names.addAll(obj(cl, "slayer_kcs").keySet());
		names.addAll(obj(board.achievements(), "quests").keySet());
		for (Skill sk : skillOrder())
		{
			names.add(prettify(low(sk.name())));
		}
		names.addAll(Arrays.asList("Quests", "Collection log", "Achievement diaries",
			"Combat achievements", "Clues", "Records", "Calendar", "Recap", "All trackers",
			"Kill log", "Left behind"));
		String best = null;
		int nearest = 3;
		for (String name : names)
		{
			String low = low(name);
			int d = editsBetween(ql, low, nearest);
			for (String word : NEAR_WORDS.split(low))
			{
				if (word.length() >= 4)
				{
					d = Math.min(d, editsBetween(ql, word, nearest));
				}
			}
			if (d < nearest)
			{
				nearest = d;
				best = name;
			}
		}
		return best;
	}

	static final Pattern NEAR_WORDS = Pattern.compile("[^a-z0-9']+");

	static int editsBetween(String a, String b, int cap)
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
				int swap = prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
				cur[j] = Math.min(swap, Math.min(prev[j] + 1, cur[j - 1] + 1));
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

	static final class Hit
	{
		final String name;
		final String figure;
		final Color color;
		final String tip;
		final Runnable go;
		final int score;
		final long weight;

		Hit(String name, String figure, Color color, String tip, Runnable go, int score, long weight)
		{
			this.name = name;
			this.figure = figure == null ? "" : figure;
			this.color = color;
			this.tip = tip;
			this.go = go;
			this.score = score;
			this.weight = weight;
		}
	}

	static final int SEARCH_CAP = 4;

	static int matchScore(String ql, String name)
	{
		if (name == null || ql.isEmpty())
		{
			return -1;
		}
		String raw = low(name);
		if (raw.equals(ql) || (ql.endsWith("s") && raw.equals(ql.substring(0, ql.length() - 1)))
			|| (raw.endsWith("s") && raw.substring(0, raw.length() - 1).equals(ql)))
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
			if (w.isEmpty())
			{
				continue;
			}
			boolean begins = false;
			for (String x : words)
			{
				begins |= x.startsWith(w);
			}
			every &= begins;
		}
		if (every || initials(q, words))
		{
			return 2;
		}
		return n.contains(q) ? 3 : -1;
	}

	static final Pattern NAME_WORDS = Pattern.compile("[^a-z0-9]+");

	static final Pattern QUERY_WORDS = Pattern.compile("\\s+");

	static boolean initials(String q, String[] words)
	{
		if (q.length() < 2 || q.indexOf(' ') >= 0 || words.length < 2)
		{
			return false;
		}
		StringBuilder all = new StringBuilder();
		StringBuilder lean = new StringBuilder();
		for (String w : words)
		{
			if (w.isEmpty())
			{
				continue;
			}
			all.append(w.charAt(0));
			if (!w.equals("the") && !w.equals("of"))
			{
				lean.append(w.charAt(0));
			}
		}
		return q.contentEquals(all) || q.contentEquals(lean);
	}

	static void goTo(List<Hit> go, String ql, String name, String figure, Runnable to,
		long weight, String... also)
	{
		int sc = matchScore(ql, name);
		sc = sc > 2 ? -1 : sc;
		for (String a : also)
		{
			if (a.equals(ql))
			{
				sc = 0;
			}
		}
		if (sc >= 0)
		{
			go.add(new Hit(name, figure, null, null, to, sc, weight));
		}
	}

	static int bestScore(String ql, String... names)
	{
		int best = -1;
		for (String n : names)
		{
			int s = matchScore(ql, n);
			if (s >= 0 && (best < 0 || s < best))
			{
				best = s;
			}
		}
		return best;
	}

	int searchGroup(JPanel p, String title, List<Hit> hits)
	{
		if (hits.isEmpty())
		{
			return 0;
		}
		hits.sort((a, b) -> a.score != b.score ? Integer.compare(a.score, b.score)
			: Long.compare(b.weight, a.weight));
		p.add(group(title));
		String key = "search:" + title;
		int cap = ui.drillShown.getOrDefault(key, SEARCH_CAP);
		FontMetrics fm = rowMetrics();
		for (Hit h : firstN(hits, cap))
		{
			int room = boardRowRoom() - ROW_GAP - (h.figure.isEmpty() ? 0 : fm.stringWidth(h.figure));
			String shown = h.name;
			for (int keep = shown.length() - 1; fm.stringWidth(shown) > room && keep >= NAME_FLOOR; keep--)
			{
				shown = stub(h.name, keep);
			}
			JPanel r = row(shown, h.figure, null, false);
			if (h.color != null)
			{
				part(r, BorderLayout.CENTER)
					.setForeground(h.color);
			}
			String tip = h.tip;
			if (!shown.equals(h.name))
			{
				tip = tip == null ? h.name : h.name + ": " + tip;
			}
			if (tip != null)
			{
				r.setToolTipText(wrappedTip(tip));
			}
			door(r, h.go);
			p.add(r);
		}
		ui.drillMore(p, key, hits.size(), cap);
		return hits.size();
	}

	Runnable searchFirst;

	void door(JPanel r, Runnable go)
	{
		link(r, go);
		if (searchFirst == null)
		{
			searchFirst = go;
		}
	}

	void fetchJourneyForSearch()
	{
		if (ui.journeyFetching)
		{
			return;
		}
		ui.journeyFetching = true;
		plugin.fetchSlayerJourney(j -> SwingUtilities.invokeLater(() ->
		{
			ui.journeyFetching = false;
			if (j == null)
			{
				return;
			}
			boolean moved = Board.journeyMoved(ui.journeyCache != null ? ui.journeyCache : board.historyJourney, j);
			ui.journeyCache = j;
			if (moved && !ui.searchQuery().isEmpty())
			{
				ui.rebuildInPlace();
			}
		}));
	}
}
