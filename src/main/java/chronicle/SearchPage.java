/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the
 * BSD 2-Clause License (see LICENSE) are met.
 */
package chronicle;

import chronicle.ChroniclePanel.Tab;
import chronicle.ChroniclePanel.View;
import chronicle.Kit.Line;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import net.runelite.api.Skill;
import static chronicle.ChroniclePanel.UNFILED;
import static chronicle.ChroniclePanel.journeyMoved;
import static chronicle.Json.has;
import static chronicle.Json.low;
import static chronicle.Json.obj;
import static chronicle.Json.safeLong;
import static chronicle.Kit.ACCENT_RED;
import static chronicle.Kit.ACCENT_SESSION;
import static chronicle.Kit.NAME_FLOOR;
import static chronicle.Kit.ROW_GAP;
import static chronicle.Kit.boardRowRoom;
import static chronicle.Kit.column;
import static chronicle.Kit.day;
import static chronicle.Kit.figure;
import static chronicle.Kit.ghostRow;
import static chronicle.Kit.link;
import static chronicle.Kit.note;
import static chronicle.Kit.part;
import static chronicle.Kit.row;
import static chronicle.Kit.rowMetrics;
import static chronicle.Kit.stub;
import static chronicle.Kit.tip;
import static chronicle.Kit.value;
import static chronicle.Kit.vgap;
import static chronicle.Kit.wrappedTip;
import static chronicle.LocalStore.kindOf;
import static chronicle.Period.DAY;
import static chronicle.Period.TASK_DAY;
import static chronicle.Period.dayOf;
import static chronicle.Text.count;
import static chronicle.Text.fmt;
import static chronicle.Text.gp;
import static chronicle.Text.gps;
import static chronicle.Text.named;
import static chronicle.Text.pb;
import static chronicle.panel.StatRegistry.prettify;

final class SearchPage
{
	private final ChroniclePanel panel;

	SearchPage(ChroniclePanel panel)
	{
		this.panel = panel;
	}

	private boolean hasKindOnTask(String kind)
	{
		for (String name : panel.build.taskItems().keySet())
		{
			String k = ItemKinds.kindOf(name);
			if (UNFILED.equals(kind) ? k == null : kind.equals(k))
			{
				return true;
			}
		}
		return false;
	}

	private static final class Hit
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

	private static final int SEARCH_CAP = 4;

	private static void goTo(List<Hit> go, String ql, String name, String figure, Runnable to,
		long weight, String... also)
	{
		int sc = Search.matchScore(ql, name);
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

	private int searchGroup(JPanel p, String title, List<Hit> hits)
	{
		if (hits.isEmpty())
		{
			return 0;
		}
		hits.sort((a, b) -> a.score != b.score ? Integer.compare(a.score, b.score)
			: Long.compare(b.weight, a.weight));
		p.add(panel.group(title));
		String key = "search:" + title;
		int cap = panel.drillShown.getOrDefault(key, SEARCH_CAP);
		FontMetrics fm = rowMetrics();
		for (Hit h : Sources.firstN(hits, cap))
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
		panel.drillMore(p, key, hits.size(), cap);
		return hits.size();
	}

	private void fetchJourneyForSearch()
	{
		if (panel.journeyFetching)
		{
			return;
		}
		panel.journeyFetching = true;
		panel.fetchSlayerJourney(j -> SwingUtilities.invokeLater(() ->
		{
			panel.journeyFetching = false;
			if (j == null)
			{
				return;
			}
			boolean moved = journeyMoved(panel.journeyCache != null ? panel.journeyCache : panel.historyJourney, j);
			panel.journeyCache = j;
			if (moved && !panel.searchQuery().isEmpty())
			{
				panel.rebuildInPlace();
			}
		}));
	}

	JPanel buildSearch(String q)
	{
		JPanel p = column();
		String ql = low(q);
		int total = 0;
		panel.searchFirst = null;

		List<Hit> go = new ArrayList<>();
		Map<String, long[]> sheet = panel.live.skillSheet();
		for (Skill sk : Sources.skillOrder())
		{
			String key = low(sk.name());
			String name = prettify(key);
			long[] cur = sheet.get(key);
			Runnable to = sk == Skill.SLAYER ? () -> panel.openSlayer("Tasks") : () -> panel.openSkill(name);
			goTo(go, ql, name, cur != null && cur[0] > 0 ? "level " + cur[0] : "", to, 2,
				Search.SKILL_ALIASES.getOrDefault(key, new String[0]));
		}
		Standings.Quests quests = panel.build.standings.quests();
		goTo(go, ql, "Quests", quests.known > 0 ? fmt(quests.done.size()) + " / " + fmt(quests.known) : "",
			() -> panel.openSheetPage("quests"), 1, "quest");
		goTo(go, ql, "Collection log", panel.live.clogAvailable() > 0
			? fmt(panel.live.clogFinished()) + " / " + fmt(panel.live.clogAvailable()) : "",
			() -> panel.openSheetPage("log"), 1, "clog", "log");
		goTo(go, ql, "Achievement diaries", "", () -> panel.openSheetPage("diaries"), 1,
			"diary", "diaries");
		goTo(go, ql, "Combat achievements", "", () -> panel.openSheetPage("combat"), 1,
			"ca", "cas", "combat tasks");
		goTo(go, ql, "Clues", "", () -> panel.openSheetPage("clues"), 1,
			"clue", "clue scrolls", "caskets", "treasure trails");
		goTo(go, ql, "Records", "", panel::openRecords, 1,
			"record", "best", "bests", "pb", "personal best");
		goTo(go, ql, "Calendar", "", panel::openCalendar, 1, "days", "days written");
		goTo(go, ql, "Recap", "", () ->
		{
			panel.subByTab.put(Tab.RECORD, "Recap");
			panel.applyTab(Tab.RECORD);
			panel.rebuild();
		}, 1, "summary");
		goTo(go, ql, "All trackers", "", panel::openAllTrackers, 1, "trackers", "counters");
		goTo(go, ql, "Kill log", "", () -> panel.openSlayer("Monsters"), 1, "killlog", "kill count", "kc");
		goTo(go, ql, "Left behind", "", () ->
		{
			panel.applyTab(View.DROPS);
			panel.dropsLeftBehind = true;
			panel.rebuild();
		}, 1, "untaken", "left on the floor");
		goTo(go, ql, "Info", "what the journal holds", panel::openInfo, 1, "journal holds");
		String kind = ItemKinds.named(q);
		if (kind != null && !ql.isEmpty())
		{
			final String pick = kind;
			int ks = Search.matchScore(ql, kind);
			ks = ks < 0 || ks > 2 ? 2 : ks;
			go.add(new Hit(kind, "every one you have had", null, null,
				() -> panel.openLootKind(pick, false), ks, 0));
			if (panel.everOnTask() && hasKindOnTask(pick))
			{
				go.add(new Hit(kind, "from slayer tasks", null, null, () -> panel.openLootKind(pick, true), ks, -1));
			}
		}
		total += searchGroup(p, "Go to", go);

		List<Hit> fights = new ArrayList<>();
		Set<String> kinds = new HashSet<>();
		Map<String, SourceRow> ledgerRows = new HashMap<>();
		for (SourceRow r : panel.build.sources())
		{
			ledgerRows.put(r.name, r);
		}
		for (Kills.Boss b : Kills.bossRoster(panel.gson))
		{
			int sc = Search.matchScore(ql, b.name);
			if (sc < 0 || !kinds.add(kindOf(b.name)))
			{
				continue;
			}
			final String open = panel.build.kills.bossLootSource(b);
			kinds.add(kindOf(open));
			SourceRow r = ledgerRows.get(open);
			boolean own = r != null && kindOf(open).equals(kindOf(b.name))
				&& panel.build.kills.isKillSource(open);
			long n = own ? panel.build.kills.standingKills(r) : panel.build.kills.bossKills(b.name);
			fights.add(new Hit(b.name, n > 0 ? fmt(n) + " kc" : "-", null,
				r == null ? null : gps(r.value) + (r.pb != null ? " · PB " + pb(r.pb) : ""),
				() -> panel.openSourceLoose(open), sc, n));
		}
		for (SourceRow r : panel.build.sources())
		{
			int sc = Search.matchScore(ql, r.name);
			if (sc < 0 || !kinds.add(kindOf(r.name)))
			{
				continue;
			}
			boolean killed = panel.build.kills.isKillSource(r.name);
			long n = killed ? panel.build.kills.standingKills(r) : r.loots;
			fights.add(new Hit(r.name, killed ? fmt(n) + " kc" : count(r.loots, "drop"), null,
				gps(r.value) + (r.pb != null ? " · PB " + pb(r.pb) : ""),
				() -> panel.openSource(r.name), sc, n));
		}
		JsonObject cl = panel.build.clog();
		for (Entry<String, JsonElement> e : obj(cl, "slayer_kcs").entrySet())
		{
			int sc = Search.matchScore(ql, e.getKey());
			if (sc < 0 || !kinds.add(kindOf(e.getKey())))
			{
				continue;
			}
			long n = safeLong(e.getValue());
			fights.add(new Hit(e.getKey(), fmt(n) + " kc", null, "In the Kill Log", () -> panel.openSlayer("Monsters"), sc, n));
		}
		total += searchGroup(p, "Bosses and monsters", fights);

		List<Hit> tasks = new ArrayList<>();
		final SlayerJourney journey = panel.journeyCache != null ? panel.journeyCache : panel.historyJourney;
		fetchJourneyForSearch();
		if (journey != null)
		{
			Map<String, int[]> byTask = new LinkedHashMap<>();
			for (int i = 0; i < journey.tasks.size(); i++)
			{
				SlayerTask t = journey.tasks.get(i);
				if (t.task == null || Search.matchScore(ql, t.task) < 0)
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
					if (panel.journeyCache == null)
					{
						panel.journeyCache = journey;
					}
					panel.applyTab(View.SLAYER);
					panel.showTask(at);
				}, Search.matchScore(ql, name), seen[0]));
			}
		}
		total += searchGroup(p, "Slayer tasks", tasks);

		Map<String, long[]> itemAgg = new LinkedHashMap<>();
		Map<String, List<String>> itemSrcs = new LinkedHashMap<>();
		for (SourceRow src : panel.build.sources())
		{
			for (BagItem b : panel.store.sourceItems(src.name))
			{
				if (Search.matchScore(ql, b.name) < 0)
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
			items.add(new Hit(itm, "×" + fmt(e.getValue()[0]), null, tip, () -> panel.openItem(itm),
				Search.matchScore(ql, itm), e.getValue()[1]));
		}
		for (UntakenRow u : panel.store.untaken().items)
		{
			if (itemAgg.containsKey(u.name) || Search.matchScore(ql, u.name) < 0)
			{
				continue;
			}
			items.add(new Hit(u.name, "×" + fmt(u.qty) + " left", ACCENT_RED, "Left on the floor", () ->
			{
				panel.applyTab(View.DROPS);
				panel.dropsLeftBehind = true;
				panel.leftBehindItem = u.name;
				panel.rebuild();
			}, Search.matchScore(ql, u.name), u.value));
		}
		total += searchGroup(p, "Items", items);

		List<Hit> log = new ArrayList<>();
		ClogBook book = panel.build.book();
		Set<String> slotSeen = new HashSet<>();
		for (Map<String, List<String>> tab : book.tax.values())
		{
			for (Entry<String, List<String>> pg : tab.entrySet())
			{
				final String page = pg.getKey();
				int ps = Search.matchScore(ql, page);
				boolean[] lit = null;
				if (ps >= 0)
				{
					lit = book.lit(page, pg.getValue());
					log.add(new Hit(page, ClogBook.held(lit) + " / " + pg.getValue().size(), null, "The page",
						() -> panel.openLogPage(page), Math.max(0, ps - 1), 2));
				}
				for (String slot : pg.getValue())
				{
					int sc = Search.matchScore(ql, slot);
					if (sc < 0 || !slotSeen.add(slot))
					{
						continue;
					}
					if (lit == null)
					{
						lit = book.lit(page, pg.getValue());
					}
					boolean got = false;
					for (int i = 0; i < lit.length; i++)
					{
						got |= lit[i] && pg.getValue().get(i).equalsIgnoreCase(slot);
					}
					log.add(new Hit(slot, page, got ? ACCENT_SESSION : ACCENT_RED,
						got ? "Held" : "Missing", () -> panel.openLogPage(page), sc, got ? 1 : 0));
				}
			}
		}
		total += searchGroup(p, "Collection log", log);

		List<Hit> questHits = new ArrayList<>();
		JsonObject states = obj(panel.build.standings.achievements(), "quests");
		for (String name : states.keySet())
		{
			int sc = Search.matchScore(ql, name);
			if (sc < 0)
			{
				continue;
			}
			String state = states.get(name).getAsString();
			boolean done = "FINISHED".equals(state);
			questHits.add(new Hit(name, done ? "complete" : "IN_PROGRESS".equals(state)
				? "in progress" : "not started", done ? ACCENT_SESSION : ACCENT_RED, null,
				() -> panel.openSheetPage("quests"), sc, done ? 1 : 0));
		}
		total += searchGroup(p, "Quests", questHits);

		if (ql.length() >= 3)
		{
			Set<Integer> done = panel.build.standings.caDone();
			JsonObject cas = Standings.CA_TASKS;
			List<Hit> caHits = new ArrayList<>();
			for (String id : cas.keySet())
			{
				JsonObject t = cas.getAsJsonObject(id);
				String name = t.get("name").getAsString();
				String task = t.get("task").getAsString();
				int sc = Search.matchScore(ql, name);
				if (sc < 0 && low(task).contains(ql))
				{
					sc = 3;
				}
				if (sc < 0)
				{
					continue;
				}
				boolean has = done.contains(Integer.parseInt(id));
				caHits.add(new Hit(name, Text.capital(low(t.get("tier").getAsString())),
					done.isEmpty() ? null : has ? ACCENT_SESSION : ACCENT_RED,
					t.get("monster").getAsString() + ": " + task, () -> panel.openSheetPage("combat"), sc,
					has ? 1 : 0));
			}
			total += searchGroup(p, "Combat achievements", caHits);

			JsonObject diaries = Standings.DIARY_TASKS;
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
						diaryHits.add(new Hit(Search.firstSentence(task), low(tier), null,
							region + " " + low(tier) + ": " + task
								+ (needs.isEmpty() || "None".equalsIgnoreCase(needs) ? "" : " Needs: " + needs),
							() -> panel.openSheetPage("diaries"), 3, 0));
					}
				}
			}
			total += searchGroup(p, "Diary tasks", diaryHits);
		}

		List<Hit> stats = new ArrayList<>();
		for (Entry<String, Long> e : panel.build.withLedgerSpend(panel.build.lifetime()).entrySet())
		{
			if (e.getValue() == 0 || StatRegistry.hidden(e.getKey()))
			{
				continue;
			}
			String label = StatRegistry.label(e.getKey());
			int sc = Search.bestScore(ql, label, e.getKey());
			if (sc < 0)
			{
				continue;
			}
			stats.add(new Hit(label, StatRegistry.isGp(e.getKey()) ? gps(e.getValue()) : fmt(e.getValue()),
				null, null, panel::openAllTrackers, sc, e.getValue()));
		}
		total += searchGroup(p, "Trackers", stats);

		List<Hit> journal = new ArrayList<>();
		int thisYear = LocalDate.now().getYear();
		String qj = ql.replace("'", "");
		for (Search.Line line : panel.index.feed(panel.build))
		{
			if (qj.trim().isEmpty() || !line.key.contains(qj))
			{
				continue;
			}
			final long at = line.at;
			String day = at <= 0 ? "" : (dayOf(at).getYear() == thisYear ? DAY : TASK_DAY)
				.format(Instant.ofEpochMilli(at));
			journal.add(new Hit(line.text, day, null, null, () -> panel.openJournalOn(at), 0, at));
		}
		total += searchGroup(p, "Journal", journal);

		if (total == 0)
		{
			p.add(note("Nothing matches \"" + q + "\" yet."));
			final String near = Search.nearest(panel.build, ql);
			if (near != null)
			{
				JPanel r = row("Did you mean", near, panel.accent());
				door(r, () -> panel.searchField.setText(near));
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

	private void door(JPanel r, Runnable go)
	{
		link(r, go);
		if (panel.searchFirst == null)
		{
			panel.searchFirst = go;
		}
	}
}
