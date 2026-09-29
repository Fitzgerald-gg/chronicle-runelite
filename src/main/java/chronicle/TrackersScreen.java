/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import chronicle.Board.Span;
import chronicle.LocalStore.SourceRow;
import chronicle.counters.StatKeys;
import chronicle.panel.StatRegistry;
import com.google.gson.JsonObject;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import javax.swing.JPanel;
import static chronicle.Feed.typeOf;
import static chronicle.Json.*;
import static chronicle.Ui.*;

final class TrackersScreen extends Screen
{
	private static final List<String> LEDGER_FAMILIES = List.of("Ledger & Roads", "Living");
	private static final Set<String> FOLDING = Set.of("Food", "Potions", "Teleports", "Destinations", "Thralls");
	private static final String FOLD_DEATHS = "Combat:deaths";

	String statsFamily = StatRegistry.FAMILIES[0];
	private Map<String, Long> consumables = Map.of();
	private long resourcesDropped;

	TrackersScreen(ChroniclePanel ui, Board board)
	{
		super(ui, board);
	}

	void reset(ChroniclePanel.View v)
	{
		if (v == ChroniclePanel.View.TRACKERS)
		{
			statsFamily = StatRegistry.FAMILIES[0];
		}
		else if (v == ChroniclePanel.View.LEDGER && !LEDGER_FAMILIES.contains(statsFamily))
		{
			statsFamily = LEDGER_FAMILIES.get(0);
		}
	}

	private Map<String, Long> read()
	{
		consumables = store.consumableValues();
		Map<String, Long> counters = board.countersForPeriod();
		resourcesDropped = counters == null ? 0 : counters.getOrDefault("resourcesDroppedValue", 0L);
		return counters;
	}

	JPanel buildStats()
	{
		JPanel p = column();
		JPanel pills = new JPanel(new GridLayout(0, 2, 3, 3));
		pills.setBackground(DARK);
		for (String fam : ui.view == ChroniclePanel.View.LEDGER ? LEDGER_FAMILIES : List.of(StatRegistry.FAMILIES))
		{
			pills.add(pill(fam, fam.equals(statsFamily), 7, null, () ->
			{
				statsFamily = fam;
				ui.rebuildInPlace();
			}));
		}
		p.add(pills);
		if (ui.view == ChroniclePanel.View.TRACKERS)
		{
			p.add(moreRow("every counter in one place", ui::openAllTrackers));
		}
		p.add(vgap(4));
		Map<String, Long> counters = read();
		if (counters == null)
		{
			p.add(board.noPeriod());
			return p;
		}
		Map<String, List<Entry<String, Long>>> sections = new LinkedHashMap<>();
		Map<String, Long> floors = new LinkedHashMap<>();
		counters.forEach((key, v) ->
		{
			if (v == 0 || StatRegistry.hidden(key) || !StatRegistry.family(key).equals(statsFamily))
			{
				return;
			}
			String sec = StatRegistry.subgroup(key);
			String heads = sec.isEmpty() ? StatRegistry.headOf(statsFamily, key) : null;
			if (StatRegistry.isFloor(key) || heads != null)
			{
				floors.merge(heads != null ? heads : sec, v, Long::sum);
			}
			else
			{
				sections.computeIfAbsent(sec, k -> new ArrayList<>()).add(Map.entry(key, v));
			}
		});
		if (sections.isEmpty() && floors.isEmpty())
		{
			String unkept = board.notCounting(false);
			return noted(p, unkept != null ? unkept : period.whole() ? "Nothing under " + statsFamily + " yet."
				: board.inside("Nothing under " + statsFamily));
		}
		List<Entry<String, Long>> destinations = statsFamily.equals("Ledger & Roads") ? sections.remove("Destinations") : null;
		if (destinations != null && !sections.containsKey("Teleports") && !floors.containsKey("Teleports"))
		{
			sections.put("Destinations", destinations);
			destinations = null;
		}
		for (String sec : sectionOrder(sections, floors))
		{
			List<Entry<String, Long>> rows = new ArrayList<>(sections.getOrDefault(sec, List.of()));
			rows.sort(StatRegistry::compareRows);
			if (sec.isEmpty())
			{
				for (Entry<String, Long> e : rows)
				{
					if ("deaths".equals(e.getKey()) && e.getValue() > 0)
					{
						addDeaths(p, rowValue(e));
					}
					else
					{
						p.add(row(StatRegistry.rowLabel(e.getKey()), rowValue(e)));
					}
				}
			}
			else if (!rows.isEmpty() || floors.getOrDefault(sec, 0L) != 0)
			{
				addSection(p, sec, rows, floors.getOrDefault(sec, 0L), counters,
					sec.equals("Teleports") ? destinations : null);
			}
		}
		return p;
	}

	private void addSection(JPanel p, String sec, List<Entry<String, Long>> rows, long floor, Map<String, Long> counters,
		List<Entry<String, Long>> destinations)
	{
		long shown = sumOf(rows);
		long typed = rows.stream().filter(e -> StatRegistry.typed(e.getKey())).mapToLong(Entry::getValue).sum();
		boolean anyTyped = rows.stream().anyMatch(e -> StatRegistry.typed(e.getKey()));
		long ghost = sec.equals("Teleports") && floor - shown >= 1 ? floor - shown
			: anyTyped && floor - typed >= 1 ? floor - typed : 0;
		boolean skilling = statsFamily.equals("Skilling");
		if (!skilling && !FOLDING.contains(sec))
		{
			p.add(group(sec));
			statRows(p, rows);
			return;
		}
		String fold = statsFamily + ":" + sec;
		p.add(ui.quietHead(sec, fmt(Math.max(shown + ghost, floor)) + tail(sectionGp(sec, rows, counters)), fold));
		if (!ui.foldOpen(fold))
		{
			return;
		}
		if (skilling)
		{
			addPace(p, sec);
		}
		if (!skilling || !addCraftVerbs(p, sec, rows, counters))
		{
			statRows(p, rows);
			if (rows.isEmpty() && floor > 0)
			{
				List<Entry<String, Long>> named = new ArrayList<>();
				for (String fk : StatRegistry.floorKeys(sec))
				{
					long fv = counters.getOrDefault(fk, 0L);
					if (fv > 0 && !StatRegistry.hidden(fk))
					{
						named.add(Map.entry(fk, fv));
					}
				}
				named.sort(StatRegistry::compareRows);
				named.forEach(e -> p.add(row(StatRegistry.label(e.getKey()), fmt(e.getValue()))));
				ghost = named.isEmpty() ? ghost : 0;
			}
			if (ghost > 0)
			{
				p.add(ghostRow(sec.equals("Teleports") ? "Other means" : "Other", fmt(ghost)));
			}
		}
		if (destinations != null && !destinations.isEmpty())
		{
			destinations.sort(StatRegistry::compareRows);
			String destFold = "Ledger & Roads:Destinations";
			p.add(ui.subHead("Destinations", fmt(sumOf(destinations)), destFold));
			if (ui.foldOpen(destFold))
			{
				destinations.forEach(e -> p.add(row(StatRegistry.label(e.getKey()), value(e))));
			}
		}
	}

	private long sectionGp(String sec, List<Entry<String, Long>> rows, Map<String, Long> counters)
	{
		if (period.whole())
		{
			return rows.stream().mapToLong(e -> consumables.getOrDefault(e.getKey(), 0L)).sum();
		}
		if (!sec.equals("Food") && !sec.equals("Potions"))
		{
			return 0;
		}
		String gpKey = sec.equals("Food") ? "foodConsumedValue" : "potionsConsumedValue";
		Span s = board.span();
		return period.session() || s != null && s.opening.counters.containsKey(gpKey) ? counters.getOrDefault(gpKey, 0L) : 0;
	}

	private List<String> sectionOrder(Map<String, List<Entry<String, Long>>> sections, Map<String, Long> floors)
	{
		Set<String> present = new LinkedHashSet<>(sections.keySet());
		present.addAll(floors.keySet());
		List<String> order = new ArrayList<>();
		if (statsFamily.equals("Skilling"))
		{
			order.addAll(present);
			order.sort(Comparator.comparingLong((String s) -> floors.getOrDefault(s, 0L) > 0 ? floors.get(s)
				: sumOf(sections.getOrDefault(s, List.of()))).reversed());
			return order;
		}
		for (String sec : StatRegistry.fixedSections(statsFamily))
		{
			if (present.remove(sec))
			{
				order.add(sec);
			}
		}
		order.addAll(present);
		return order;
	}

	private boolean addCraftVerbs(JPanel p, String craft, List<Entry<String, Long>> rows, Map<String, Long> counters)
	{
		Map<String, List<Entry<String, Long>>> byVerb = new LinkedHashMap<>();
		List<Entry<String, Long>> leaves = new ArrayList<>();
		for (Entry<String, Long> e : rows)
		{
			String suf = StatRegistry.suffixOf(e.getKey());
			(suf == null ? leaves : byVerb.computeIfAbsent(suf, k -> new ArrayList<>())).add(e);
		}
		if (byVerb.size() < 2)
		{
			return false;
		}
		leaves.forEach(e -> p.add(row(StatRegistry.rowLabel(e.getKey()), value(e))));
		Map<String, Long> totals = new LinkedHashMap<>();
		byVerb.forEach((verb, list) ->
		{
			String floorKey = StatRegistry.suffixFloor(craft, verb);
			totals.put(verb, Math.max(floorKey != null ? counters.getOrDefault(floorKey, 0L) : 0L, sumOf(list)));
		});
		List<String> verbs = new ArrayList<>(byVerb.keySet());
		verbs.sort(Comparator.comparingLong((String v) -> totals.get(v)).reversed());
		for (String verb : verbs)
		{
			String fold = "Skilling:" + craft + ":" + verb;
			p.add(ui.subHead(StatRegistry.suffixLabel(verb), fmt(totals.get(verb)), fold));
			if (ui.foldOpen(fold))
			{
				statRows(p, byVerb.get(verb));
				long other = totals.get(verb) - sumOf(byVerb.get(verb));
				if (other >= 1)
				{
					p.add(ghostRow("Other", fmt(other)));
				}
			}
		}
		return true;
	}

	private void addDeaths(JPanel p, String figure)
	{
		JPanel head = row("Deaths", figure);
		ui.foldHead(head, FOLD_DEATHS, "Who dealt them");
		p.add(head);
		if (!ui.foldOpen(FOLD_DEATHS))
		{
			return;
		}
		Map<String, long[]> killers = new LinkedHashMap<>();
		for (JsonObject e : store.feedNewest(Board.FEED_SCAN_DEEP))
		{
			long ts = asLong(e.get("ts"));
			if ("DEATH".equals(typeOf(e)) && board.insideWindow(ts))
			{
				JsonObject d = obj(e, "data");
				long[] t = killers.computeIfAbsent(has(d, "killerName") ? d.get("killerName").getAsString() : "Unknown",
					k -> new long[2]);
				t[0]++;
				t[1] = Math.max(t[1], ts);
			}
		}
		List<Entry<String, long[]>> ranked = new ArrayList<>(killers.entrySet());
		ranked.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
		for (Entry<String, long[]> k : ranked)
		{
			JPanel r = row(k.getKey(), fmt(k.getValue()[0]) + " · last " + day(k.getValue()[1]));
			p.add("Unknown".equals(k.getKey()) ? r : link(r, () -> ui.openSourceLoose(k.getKey())));
		}
	}

	JPanel buildAllTrackers()
	{
		JPanel p = ui.backPage();
		Map<String, Long> counters = read();
		if (counters == null)
		{
			p.add(board.noPeriod());
			return p;
		}
		Map<String, Map<String, List<Entry<String, Long>>>> filed = new LinkedHashMap<>();
		for (String fam : StatRegistry.FAMILIES)
		{
			filed.put(fam, new LinkedHashMap<>());
		}
		int kept = 0;
		for (Entry<String, Long> e : counters.entrySet())
		{
			if (e.getValue() == null || e.getValue() <= 0 || StatRegistry.hidden(e.getKey()))
			{
				continue;
			}
			String fam = StatRegistry.family(e.getKey());
			String sec = StatRegistry.subgroup(e.getKey());
			String heads = sec.isEmpty() ? StatRegistry.headOf(fam, e.getKey()) : null;
			filed.computeIfAbsent(fam, k -> new LinkedHashMap<>())
				.computeIfAbsent(heads != null ? heads : sec, k -> new ArrayList<>()).add(e);
			kept++;
		}
		JPanel head = card("Trackers");
		head.add(row("Counters", fmt(kept), ACCENT));
		head.add(row("Reading", period.whole() ? "Lifetime" : board.window().label));
		spaced(p, head);
		if (kept == 0)
		{
			return noted(p, board.inside("Nothing tracked"));
		}
		filed.forEach((fam, sections) ->
		{
			if (sections.isEmpty())
			{
				return;
			}
			p.add(group(fam));
			sections.forEach((sec, rows) ->
			{
				rows.sort(StatRegistry::compareRows);
				if (!sec.isEmpty())
				{
					p.add(ghostRow(sec, ""));
				}
				statRows(p, rows);
			});
			p.add(vgap(4));
		});
		return p;
	}

	JPanel buildSkillDetail(String craft)
	{
		JPanel p = ui.backPage();
		consumables = store.consumableValues();
		resourcesDropped = 0;
		String key = low(craft);
		JPanel head = card(craft);
		Span s = board.span();
		Long now = board.liveXp(key);
		if (now == null && s != null)
		{
			now = s.closing.skills.get(key);
		}
		Long was = period.session() ? now == null ? null : Math.max(0, now - board.sessionXp(key))
			: s == null ? null : s.opening.skills.get(key);
		long gained = !period.whole() && was != null && now != null && now > was ? now - was : 0;
		if (now != null && now > 0)
		{
			head.add(row("Level", String.valueOf(PaceBook.levelAt(now)), ACCENT));
			head.add(row("Experience", gp(now)));
			if (gained > 0)
			{
				head.add(row("Gained", "+" + gp(gained), ACCENT));
			}
		}
		else
		{
			head.add(row("Level", "-"));
		}
		long minutes = (period.whole() ? board.counters() : board.periodCounters()).getOrDefault(StatKeys.timeKey(craft), 0L);
		if (minutes > 0 && board.minutesCoverPeriod())
		{
			boolean rate = gained > 0 && minutes >= 30;
			head.add(row("Time", hoursMinutes(minutes) + (rate ? " · " + gp(Math.round(gained * 60.0 / minutes)) + " xp/h" : "")));
		}
		spaced(p, head);
		Map<String, Long> counters = board.countersForPeriod();
		if (counters == null)
		{
			p.add(board.noPeriod());
			return p;
		}
		List<Entry<String, Long>> rows = new ArrayList<>();
		counters.forEach((k, v) ->
		{
			if (v != null && v > 0 && "Skilling".equals(StatRegistry.family(k)) && craft.equalsIgnoreCase(StatRegistry.subgroup(k)))
			{
				rows.add(Map.entry(k, v));
			}
		});
		List<SourceRow> ground = board.skillGround(craft);
		if (rows.isEmpty() && ground.isEmpty())
		{
			String unkept = board.notCounting(false);
			return noted(p, unkept != null ? unkept
				: "Nothing is tracked under " + craft + (period.whole() ? "." : " in " + board.periodInSentence() + "."));
		}
		rows.sort(StatRegistry::compareRows);
		addPace(p, craft);
		statRows(p, rows);
		if (!ground.isEmpty())
		{
			p.add(vgap(6));
			p.add(group("WHAT IT HAS EVER PAID"));
			ground.forEach(r -> p.add(link(row(r.name, gps(r.value), ACCENT), () -> ui.openSource(r.name))));
		}
		return p;
	}

	private void addPace(JPanel p, String skill)
	{
		PaceBook.Pace pace;
		try
		{
			pace = plugin.pace(skill);
		}
		catch (RuntimeException e)
		{
			return;
		}
		if (pace != null && pace.hasHorizon())
		{
			String target = pace.targetLevel != null ? String.valueOf(pace.targetLevel) : "200m";
			p.add(ghostRow(target + " in " + count(pace.daysOfPlay, "day") + " of play", gp((long) pace.xpPerActiveDay) + "/day"));
			if (pace.activeDays < 3)
			{
				p.add(ghostRow("measured over " + count(pace.activeDays, "day"), ""));
			}
		}
		else if (pace != null && pace.dormant() && pace.lastActive != null)
		{
			p.add(ghostRow("last moved " + pace.lastActive.format(TASK_DAY), ""));
		}
	}

	private void statRows(JPanel p, List<Entry<String, Long>> rows)
	{
		rows.forEach(e -> p.add(row(StatRegistry.rowLabel(e.getKey()), rowValue(e))));
	}

	private String rowValue(Entry<String, Long> e)
	{
		if (e.getKey().equals("resourcesGatheredValue") && resourcesDropped > 0)
		{
			return value(e) + " · " + gp(resourcesDropped) + " dropped";
		}
		long cv = period.whole() ? consumables.getOrDefault(e.getKey(), 0L) : 0;
		return cv > 0 ? value(e) + " · " + gps(cv) : value(e);
	}

	private static String value(Entry<String, Long> e)
	{
		return StatRegistry.isGp(e.getKey()) ? gps(e.getValue()) : fmt(e.getValue());
	}
}
