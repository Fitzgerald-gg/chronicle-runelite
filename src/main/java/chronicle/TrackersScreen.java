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
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import javax.swing.JPanel;
import static chronicle.Feed.*;
import static chronicle.Pictures.*;
import static chronicle.Reference.*;
import static chronicle.Ui.*;

final class TrackersScreen
{
	private final ChroniclePanel ui;
	private final Board board;
	private final ChroniclePlugin plugin;
	private final Period period;

	TrackersScreen(ChroniclePanel ui, Board board)
	{
		this.ui = ui;
		this.board = board;
		this.plugin = board.plugin;
		this.period = board.period;
	}

	JPanel buildStats()
	{
		JPanel p = column();
		consumVals = plugin.consumableValues();
		String[] families = ui.view == ChroniclePanel.View.LEDGER ? LEDGER_FAMILIES : StatRegistry.FAMILIES;
		JPanel pills = new JPanel(new GridLayout(0, 2, 3, 3));
		pills.setBackground(DARK);
		for (String fam : families)
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

		Map<String, Long> counters = board.countersForPeriod();
		if (counters == null)
		{
			p.add(board.noPeriod());
			return p;
		}
		resourcesDropped = counters.getOrDefault("resourcesDroppedValue", 0L);
		Map<String, List<Entry<String, Long>>> rowsBySection = new LinkedHashMap<>();
		Map<String, Long> floorTotals = new LinkedHashMap<>();
		for (Entry<String, Long> e : counters.entrySet())
		{
			if (e.getValue() == 0 || StatRegistry.hidden(e.getKey())
				|| !StatRegistry.family(e.getKey()).equals(statsFamily))
			{
				continue;
			}
			String sec = StatRegistry.subgroup(e.getKey());
			String heads = sec.isEmpty() ? StatRegistry.headOf(statsFamily, e.getKey()) : null;
			if (StatRegistry.isFloor(e.getKey()) || heads != null)
			{
				floorTotals.merge(heads != null ? heads : sec, e.getValue(), Long::sum);
				continue;
			}
			rowsBySection.computeIfAbsent(sec, k -> new ArrayList<>()).add(e);
		}
		if (rowsBySection.isEmpty() && floorTotals.isEmpty())
		{
			String unkept = board.notCounting(false);
			return noted(p, unkept != null ? unkept : period.whole()
				? "Nothing under " + statsFamily + " yet."
				: board.inside("Nothing under " + statsFamily));
		}

		List<Entry<String, Long>> destRows = statsFamily.equals("Ledger & Roads")
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

			boolean foldable = statsFamily.equals("Skilling")
				|| sec.equals("Food") || sec.equals("Potions")
				|| sec.equals("Teleports") || sec.equals("Destinations")
				|| sec.equals("Thralls");
			if (!foldable)
			{
				p.add(group(sec));
				statRows(p, rows);
				continue;
			}

			String stateKey = statsFamily + ":" + sec;
			boolean open = ui.foldOpen(stateKey);
			long secGp = 0;
			if (period.whole())
			{
				for (Entry<String, Long> e : rows)
				{
					Long cv = consumVals.get(e.getKey());
					if (cv != null)
					{
						secGp += cv;
					}
				}
			}
			else if (sec.equals("Food") || sec.equals("Potions"))
			{
				String gpKey = sec.equals("Food") ? "foodConsumedValue" : "potionsConsumedValue";
				Span s = board.span();
				if (period.session() || (s != null && s.opening.counters.containsKey(gpKey)))
				{
					secGp = counters.getOrDefault(gpKey, 0L);
				}
			}
			p.add(ui.quietHead(sec, fmt(total) + tail(secGp),
				stateKey));
			if (open)
			{
				if (statsFamily.equals("Skilling"))
				{
					addPaceLine(p, sec);
				}
				boolean nested = statsFamily.equals("Skilling")
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

	JPanel buildAllTrackers()
	{
		JPanel p = ui.backPage();
		consumVals = plugin.consumableValues();
		Map<String, Long> counters = board.countersForPeriod();
		if (counters == null)
		{
			p.add(board.noPeriod());
			return p;
		}
		resourcesDropped = counters.getOrDefault("resourcesDroppedValue", 0L);
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
		head.add(row("Counters", fmt(kept), ACCENT));
		head.add(row("Reading", period.whole() ? "Lifetime" : board.window().label));
		spaced(p, head);
		if (kept == 0)
		{
			return noted(p, board.inside("Nothing tracked"));
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
		if (e.getKey().equals("resourcesGatheredValue") && resourcesDropped > 0)
		{
			return base + " · " + gp(resourcesDropped) + " dropped";
		}
		Long cv = period.whole() ? consumVals.get(e.getKey()) : null;
		return cv != null && cv > 0 ? base + " · " + gps(cv) : base;
	}

	void addDeathsFold(JPanel p, String figure)
	{
		JPanel head = row("Deaths", figure);
		ui.foldHead(head, FOLD_DEATHS, "Who dealt them");
		p.add(head);
		if (!ui.foldOpen(FOLD_DEATHS))
		{
			return;
		}
		Map<String, long[]> killers = new LinkedHashMap<>();
		for (JsonObject e : plugin.feedNewest(Board.FEED_SCAN_DEEP))
		{
			long ts = safeLong(e.get("ts"));
			if (!"DEATH".equals(typeOf(e)) || !board.insideWindow(ts))
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
				link(r, () -> ui.openSourceLoose(who));
			}
			p.add(r);
		}
	}

	static final String FOLD_DEATHS = "Combat:deaths";

	List<String> sectionOrder(Map<String, List<Entry<String, Long>>> rowsBySection,
		Map<String, Long> floorTotals)
	{
		LinkedHashSet<String> present = new LinkedHashSet<>();
		present.addAll(rowsBySection.keySet());
		present.addAll(floorTotals.keySet());
		List<String> order = new ArrayList<>();
		if (statsFamily.equals("Skilling"))
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
			for (String sec : StatRegistry.fixedSections(statsFamily))
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

	boolean addCraftNested(JPanel p, String craft,
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
			boolean open = ui.foldOpen(stateKey);
			p.add(ui.subHead(StatRegistry.suffixLabel(verb),
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

	void addDestinationsFold(JPanel p, List<Entry<String, Long>> destRows)
	{
		destRows.sort(StatRegistry::compareRows);
		long sum = sumOf(destRows);
		String stateKey = "Ledger & Roads:Destinations";
		boolean open = ui.foldOpen(stateKey);
		p.add(ui.subHead("Destinations", fmt(sum), stateKey));
		if (open)
		{
			for (Entry<String, Long> e : destRows)
			{
				p.add(row(StatRegistry.label(e.getKey()), value(e)));
			}
		}
	}

	static String value(Entry<String, Long> e)
	{
		return StatRegistry.isGp(e.getKey()) ? gps(e.getValue()) : fmt(e.getValue());
	}

	Map<String, Long> consumVals = new LinkedHashMap<>();

	long resourcesDropped;

	JPanel buildSkillDetail(String craft)
	{
		JPanel p = ui.backPage();
		consumVals = plugin.consumableValues();
		String key = low(craft);

		JPanel head = card(craft);
		Span s = board.span();
		Long now = board.liveXp(key);
		if (now == null)
		{
			now = s == null ? null : s.closing.skills.get(key);
		}
		Long was = period.session()
			? (now == null ? null : Math.max(0, now - board.sessionXp(key)))
			: (s == null ? null : s.opening.skills.get(key));
		if (now != null && now > 0)
		{
			head.add(row("Level", String.valueOf(PaceBook.levelAt(now)), ACCENT));
			head.add(row("Experience", gp(now)));
			if (!period.whole() && was != null && now > was)
			{
				head.add(row("Gained", "+" + gp(now - was), ACCENT));
			}
		}
		else
		{
			head.add(row("Level", "-"));
		}
		long minutes = (period.whole() ? board.counters() : board.periodCounters())
			.getOrDefault(StatKeys.timeKey(craft), 0L);
		if (minutes > 0 && board.minutesCoverPeriod())
		{
			long gained = !period.whole() && was != null && now != null && now > was ? now - was : 0;
			boolean rate = gained > 0 && minutes >= 30;
			head.add(row("Time", hoursMinutes(minutes)
				+ (rate ? " · " + gp(Math.round(gained * 60.0 / minutes)) + " xp/h" : "")));
		}
		spaced(p, head);

		Map<String, Long> counters = board.countersForPeriod();
		if (counters == null)
		{
			p.add(board.noPeriod());
			return p;
		}
		List<Entry<String, Long>> rows = new ArrayList<>();
		for (Entry<String, Long> e : counters.entrySet())
		{
			if (e.getValue() == null || e.getValue() <= 0
				|| !"Skilling".equals(StatRegistry.family(e.getKey()))
				|| !craft.equalsIgnoreCase(StatRegistry.subgroup(e.getKey())))
			{
				continue;
			}
			rows.add(e);
		}
		List<SourceRow> ground = board.skillGround(craft);
		if (rows.isEmpty() && ground.isEmpty())
		{
			String unkept = board.notCounting(false);
			return noted(p, unkept != null ? unkept : "Nothing is tracked under " + craft
				+ (period.whole() ? "." : " in " + board.periodInSentence() + "."));
		}
		rows.sort(StatRegistry::compareRows);
		addPaceLine(p, craft);
		statRows(p, rows);
		if (!ground.isEmpty())
		{
			p.add(vgap(6));
			p.add(group("WHAT IT HAS EVER PAID"));
			for (SourceRow r : ground)
			{
				JPanel line = row(r.name, gps(r.value), ACCENT);
				link(line, () -> ui.openSource(r.name));
				p.add(line);
			}
		}
		return p;
	}

	void addPaceLine(JPanel p, String section)
	{
		PaceBook.Pace pace;
		try
		{
			pace = plugin.pace(section);
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

	String statsFamily = StatRegistry.FAMILIES[0];
	private static final String[] LEDGER_FAMILIES = {"Ledger & Roads", "Living"};

	void reset(ChroniclePanel.View v)
	{
		if (v == ChroniclePanel.View.TRACKERS)
		{
			statsFamily = StatRegistry.FAMILIES[0];
		}
		else if (v == ChroniclePanel.View.LEDGER && !Arrays.asList(LEDGER_FAMILIES).contains(statsFamily))
		{
			statsFamily = LEDGER_FAMILIES[0];
		}
	}
}
