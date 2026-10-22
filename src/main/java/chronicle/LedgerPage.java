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
import chronicle.Period.Span;
import chronicle.panel.StatRegistry;
import com.google.gson.JsonObject;
import java.awt.GridLayout;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import javax.swing.JPanel;
import static chronicle.Feed.typeOf;
import static chronicle.Json.has;
import static chronicle.Json.obj;
import static chronicle.Json.safeLong;
import static chronicle.Kit.ACCENT_LIFETIME;
import static chronicle.Kit.DARK;
import static chronicle.Kit.card;
import static chronicle.Kit.column;
import static chronicle.Kit.day;
import static chronicle.Kit.ghostRow;
import static chronicle.Kit.group;
import static chronicle.Kit.link;
import static chronicle.Kit.moreRow;
import static chronicle.Kit.nested;
import static chronicle.Kit.noted;
import static chronicle.Kit.pill;
import static chronicle.Kit.row;
import static chronicle.Kit.spaced;
import static chronicle.Kit.sumOf;
import static chronicle.Kit.value;
import static chronicle.Kit.vgap;
import static chronicle.Period.TASK_DAY;
import static chronicle.Text.count;
import static chronicle.Text.fmt;
import static chronicle.Text.gp;
import static chronicle.Text.gps;
import static chronicle.Text.tail;

final class LedgerPage
{
	private static final String FOLD_DEATHS = "Combat:deaths";

	private final ChroniclePanel panel;

	LedgerPage(ChroniclePanel panel)
	{
		this.panel = panel;
	}

	JPanel buildAllTrackers()
	{
		JPanel p = panel.backPage();
		panel.consumVals = panel.store.consumableValues();
		Map<String, Long> counters = panel.build.countersForPeriod();
		if (counters == null)
		{
			p.add(panel.noPeriod());
			return p;
		}
		panel.resourcesDropped = counters.getOrDefault("resourcesDroppedValue", 0L);
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
			Map<String, List<Entry<String, Long>>> fam =
				filed.computeIfAbsent(StatRegistry.family(e.getKey()), k -> new LinkedHashMap<>());
			String sec = StatRegistry.subgroup(e.getKey());
			String heads = sec.isEmpty()
				? StatRegistry.headOf(StatRegistry.family(e.getKey()), e.getKey()) : null;
			fam.computeIfAbsent(heads != null ? heads : sec, k -> new ArrayList<>()).add(e);
			kept++;
		}
		JPanel head = card("Trackers");
		head.add(row("Counters", fmt(kept), ACCENT_LIFETIME));
		head.add(row("Reading", panel.wholeRecord() ? "Lifetime" : panel.window().label));
		spaced(p, head);
		if (kept == 0)
		{
			return noted(p, panel.window().phrase("Nothing tracked"));
		}
		for (Entry<String, Map<String, List<Entry<String, Long>>>> fam : filed.entrySet())
		{
			if (fam.getValue().isEmpty())
			{
				continue;
			}
			p.add(group(fam.getKey()));
			for (Entry<String, List<Entry<String, Long>>> sec : fam.getValue().entrySet())
			{
				List<Entry<String, Long>> rows = sec.getValue();
				rows.sort(StatRegistry::compareRows);
				if (!sec.getKey().isEmpty())
				{
					p.add(ghostRow(sec.getKey(), ""));
				}
				statRows(p, rows);
			}
			p.add(vgap(4));
		}
		return p;
	}

	private void addDeathsFold(JPanel p, String figure)
	{
		JPanel head = row("Deaths", figure);
		panel.foldHead(head, FOLD_DEATHS, "Who dealt them");
		p.add(head);
		if (!panel.foldOpen(FOLD_DEATHS))
		{
			return;
		}
		Map<String, long[]> killers = new LinkedHashMap<>();
		for (JsonObject e : panel.build.feed.all())
		{
			long ts = safeLong(e.get("ts"));
			if (!"DEATH".equals(typeOf(e)) || !panel.window().inside(ts))
			{
				continue;
			}
			JsonObject d = obj(e, "data");
			String who = has(d, "killerName") ? d.get("killerName").getAsString() : "Unknown";
			long[] t = killers.computeIfAbsent(who, k -> new long[2]);
			t[0]++;
			t[1] = Math.max(t[1], ts);
		}
		List<Entry<String, long[]>> ranked = new ArrayList<>(killers.entrySet());
		ranked.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
		for (Entry<String, long[]> k : ranked)
		{
			JPanel r = row(k.getKey(), fmt(k.getValue()[0]) + " · last "
				+ day(k.getValue()[1]));
			if (!"Unknown".equals(k.getKey()))
			{
				final String who = k.getKey();
				link(r, () -> panel.openSourceLoose(who));
			}
			p.add(r);
		}
	}

	JPanel buildStats()
	{
		JPanel p = column();
		panel.consumVals = panel.store.consumableValues();
		String[] families = panel.tab == Tab.RECORD
			? new String[]{"Ledger & Roads", "Living"}
			: StatRegistry.FAMILIES;
		JPanel pills = new JPanel(new GridLayout(0, 2, 3, 3));
		pills.setBackground(DARK);
		for (String fam : families)
		{
			pills.add(pill(fam, fam.equals(panel.statsFamily), 7, null, () ->
			{
				panel.statsFamily = fam;
				panel.rebuildInPlace();
			}));
		}
		p.add(pills);
		if (panel.tab == Tab.TRACKERS && !panel.allTrackers)
		{
			p.add(moreRow("every counter in one place", panel::openAllTrackers));
		}
		p.add(vgap(4));

		Map<String, Long> counters = panel.build.countersForPeriod();
		if (counters == null)
		{
			p.add(panel.noPeriod());
			return p;
		}
		panel.resourcesDropped = counters.getOrDefault("resourcesDroppedValue", 0L);
		Map<String, List<Entry<String, Long>>> rowsBySection = new LinkedHashMap<>();
		Map<String, Long> floorTotals = new LinkedHashMap<>();
		for (Entry<String, Long> e : counters.entrySet())
		{
			if (e.getValue() == 0 || StatRegistry.hidden(e.getKey())
				|| !StatRegistry.family(e.getKey()).equals(panel.statsFamily))
			{
				continue;
			}
			String sec = StatRegistry.subgroup(e.getKey());
			String heads = sec.isEmpty() ? StatRegistry.headOf(panel.statsFamily, e.getKey()) : null;
			if (StatRegistry.isFloor(e.getKey()) || heads != null)
			{
				floorTotals.merge(heads != null ? heads : sec, e.getValue(), Long::sum);
				continue;
			}
			rowsBySection.computeIfAbsent(sec, k -> new ArrayList<>()).add(e);
		}
		if (rowsBySection.isEmpty() && floorTotals.isEmpty())
		{
			String unkept = panel.build.notCounting(false);
			return noted(p, unkept != null ? unkept : panel.wholeRecord()
				? "Nothing under " + panel.statsFamily + " yet."
				: panel.window().phrase("Nothing under " + panel.statsFamily));
		}

		List<Entry<String, Long>> destRows = panel.statsFamily.equals("Ledger & Roads")
			? rowsBySection.remove("Destinations") : null;
		if (destRows != null && !rowsBySection.containsKey("Teleports")
			&& !floorTotals.containsKey("Teleports"))
		{
			rowsBySection.put("Destinations", destRows);
			destRows = null;
		}

		List<String> order = sectionOrder(rowsBySection, floorTotals);
		for (String sec : order)
		{
			List<Entry<String, Long>> rows =
				rowsBySection.getOrDefault(sec, new ArrayList<>());
			rows.sort(StatRegistry::compareRows);
			long floor = floorTotals.getOrDefault(sec, 0L);

			if (sec.isEmpty())
			{
				for (Entry<String, Long> e : rows)
				{
					if ("deaths".equals(e.getKey()) && e.getValue() > 0)
					{
						addDeathsFold(p, rowValue(e));
						continue;
					}
					p.add(row(StatRegistry.rowLabel(e.getKey()), rowValue(e)));
				}
				continue;
			}

			if (rows.isEmpty() && floor == 0)
			{
				continue;
			}

			long typedSum = 0;
			boolean anyTyped = false;
			long shown = 0;
			for (Entry<String, Long> e : rows)
			{
				shown += e.getValue();
				if (StatRegistry.typed(e.getKey()))
				{
					anyTyped = true;
					typedSum += e.getValue();
				}
			}
			long ghost = anyTyped && floor - typedSum >= 1 ? floor - typedSum : 0;
			if (sec.equals("Teleports") && floor - shown >= 1)
			{
				ghost = floor - shown;
			}
			long total = Math.max(shown + ghost, floor);

			boolean foldable = panel.statsFamily.equals("Skilling")
				|| sec.equals("Food") || sec.equals("Potions")
				|| sec.equals("Teleports") || sec.equals("Destinations")
				|| sec.equals("Thralls");
			if (!foldable)
			{
				p.add(group(sec));
				statRows(p, rows);
				continue;
			}

			String stateKey = panel.statsFamily + ":" + sec;
			boolean open = panel.foldOpen(stateKey);
			long secGp = 0;
			if (panel.wholeRecord())
			{
				for (Entry<String, Long> e : rows)
				{
					Long cv = panel.consumVals.get(e.getKey());
					if (cv != null)
					{
						secGp += cv;
					}
				}
			}
			else if (sec.equals("Food") || sec.equals("Potions"))
			{
				String gpKey = sec.equals("Food") ? "foodConsumedValue" : "potionsConsumedValue";
				Span s = panel.build.span();
				if (panel.sessionPeriod() || (s != null && s.opening.counters.containsKey(gpKey)))
				{
					secGp = counters.getOrDefault(gpKey, 0L);
				}
			}
			p.add(panel.quietHead(sec, fmt(total) + tail(secGp),
				stateKey));
			if (open)
			{
				if (panel.statsFamily.equals("Skilling"))
				{
					addPaceLine(p, sec);
				}
				boolean nested = panel.statsFamily.equals("Skilling")
					&& addCraftNested(p, sec, rows, counters);
				if (!nested)
				{
					statRows(p, rows);
					if (rows.isEmpty() && floor > 0)
					{
						List<Entry<String, Long>> floors = new ArrayList<>();
						for (String fk : StatRegistry.floorKeys(sec))
						{
							long fv = counters.getOrDefault(fk, 0L);
							if (fv > 0 && !StatRegistry.hidden(fk))
							{
								floors.add(new AbstractMap.SimpleEntry<>(fk, fv));
							}
						}
						floors.sort(StatRegistry::compareRows);
						for (Entry<String, Long> fe : floors)
						{
							p.add(row(StatRegistry.label(fe.getKey()), fmt(fe.getValue())));
						}
						if (!floors.isEmpty())
						{
							ghost = 0;
						}
					}
					if (ghost > 0)
					{
						p.add(ghostRow(sec.equals("Teleports") ? "Other means" : "Other",
							fmt(ghost)));
					}
				}
				if (sec.equals("Teleports") && destRows != null && !destRows.isEmpty())
				{
					addDestinationsFold(p, destRows);
				}
			}
		}
		return p;
	}

	private List<String> sectionOrder(Map<String, List<Entry<String, Long>>> rowsBySection,
		Map<String, Long> floorTotals)
	{
		LinkedHashSet<String> present = new LinkedHashSet<>();
		present.addAll(rowsBySection.keySet());
		present.addAll(floorTotals.keySet());
		List<String> order = new ArrayList<>();
		if (panel.statsFamily.equals("Skilling"))
		{
			List<String> crafts = new ArrayList<>(present);
			crafts.sort(Comparator.comparingLong((String s) ->
			{
				long floor = floorTotals.getOrDefault(s, 0L);
				if (floor > 0)
				{
					return floor;
				}
				return sumOf(rowsBySection.getOrDefault(s, new ArrayList<>()));
			}).reversed());
			order.addAll(crafts);
		}
		else
		{
			for (String sec : StatRegistry.fixedSections(panel.statsFamily))
			{
				if (present.remove(sec))
				{
					order.add(sec);
				}
			}
			order.addAll(present);
		}
		return order;
	}

	private boolean addCraftNested(JPanel p, String craft,
		List<Entry<String, Long>> rows, Map<String, Long> counters)
	{
		Map<String, List<Entry<String, Long>>> byVerb = new LinkedHashMap<>();
		List<Entry<String, Long>> leaves = new ArrayList<>();
		for (Entry<String, Long> e : rows)
		{
			String suf = StatRegistry.suffixOf(e.getKey());
			if (suf == null)
			{
				leaves.add(e);
			}
			else
			{
				byVerb.computeIfAbsent(suf, k -> new ArrayList<>()).add(e);
			}
		}
		if (byVerb.size() < 2)
		{
			return false;
		}
		for (Entry<String, Long> e : leaves)
		{
			p.add(row(StatRegistry.rowLabel(e.getKey()), value(e)));
		}
		List<String> verbs = new ArrayList<>(byVerb.keySet());
		Map<String, Long> verbTotal = new LinkedHashMap<>();
		for (String verb : verbs)
		{
			String floorKey = StatRegistry.suffixFloor(craft, verb);
			long floorVal = floorKey != null ? counters.getOrDefault(floorKey, 0L) : 0L;
			verbTotal.put(verb, Math.max(floorVal, sumOf(byVerb.get(verb))));
		}
		verbs.sort(Comparator.comparingLong(
			(String v) -> verbTotal.getOrDefault(v, 0L)).reversed());
		for (String verb : verbs)
		{
			String stateKey = "Skilling:" + craft + ":" + verb;
			boolean open = panel.foldOpen(stateKey);
			p.add(panel.subHead(StatRegistry.suffixLabel(verb),
				fmt(verbTotal.getOrDefault(verb, 0L)), stateKey));
			if (open)
			{
				statRows(p, byVerb.get(verb));
				long verbGhost = verbTotal.get(verb) - sumOf(byVerb.get(verb));
				if (verbGhost >= 1)
				{
					p.add(ghostRow("Other", fmt(verbGhost)));
				}
			}
		}
		return true;
	}

	private void addDestinationsFold(JPanel p, List<Entry<String, Long>> destRows)
	{
		destRows.sort(StatRegistry::compareRows);
		long sum = sumOf(destRows);
		String stateKey = "Ledger & Roads:Destinations";
		boolean open = panel.foldOpen(stateKey);
		p.add(panel.subHead("Destinations", fmt(sum), stateKey));
		if (open)
		{
			for (Entry<String, Long> e : destRows)
			{
				p.add(row(StatRegistry.label(e.getKey()), value(e)));
			}
		}
	}

	void addPaceLine(JPanel p, String section)
	{
		PaceBook.Pace pace;
		try
		{
			pace = panel.live.pace(section);
		}
		catch (RuntimeException e)
		{
			return;
		}
		if (pace == null)
		{
			return;
		}
		if (pace.hasHorizon())
		{
			String target = pace.targetLevel != null
				? String.valueOf(pace.targetLevel) : "200m";
			p.add(ghostRow(target + " in " + count(pace.daysOfPlay, "day") + " of play",
				gp((long) pace.xpPerActiveDay) + "/day"));
			if (pace.activeDays < 3)
			{
				p.add(ghostRow("measured over " + count(pace.activeDays, "day"), ""));
			}
		}
		else if (pace.dormant() && pace.lastActive != null)
		{
			p.add(ghostRow("last moved " + pace.lastActive.format(TASK_DAY), ""));
		}
	}

	void statRows(JPanel p, List<Entry<String, Long>> rows)
	{
		for (Entry<String, Long> e : rows)
		{
			p.add(row(StatRegistry.rowLabel(e.getKey()), rowValue(e)));
		}
	}

	String rowValue(Entry<String, Long> e)
	{
		String base = value(e);
		if (e.getKey().equals("resourcesGatheredValue") && panel.resourcesDropped > 0)
		{
			return base + " · " + gp(panel.resourcesDropped) + " dropped";
		}
		Long cv = panel.wholeRecord() ? panel.consumVals.get(e.getKey()) : null;
		return cv != null && cv > 0 ? base + " · " + gps(cv) : base;
	}
}
