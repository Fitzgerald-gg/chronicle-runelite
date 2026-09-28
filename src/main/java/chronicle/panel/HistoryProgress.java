/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the BSD 2-Clause
 * License (see LICENSE) are met.
 */
package chronicle.panel;

import chronicle.counters.Tables;
import com.google.gson.JsonElement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

public final class HistoryProgress
{
	public static final class Row
	{
		private final String key;
		private final String label;
		private final long value;
		private final boolean gp;
		private final long gpNote;
		private final String gpNoteWord;

		Row(String key, String label, long value, boolean gp)
		{
			this(key, label, value, gp, 0, "");
		}

		Row(String key, String label, long value, boolean gp, long gpNote, String gpNoteWord)
		{
			this.key = key;
			this.label = label;
			this.value = value;
			this.gp = gp;
			this.gpNote = gpNote;
			this.gpNoteWord = gpNoteWord;
		}

		public String key()
		{
			return key;
		}

		public String label()
		{
			return label;
		}

		public long value()
		{
			return value;
		}

		public boolean gp()
		{
			return gp;
		}

		public long gpNote()
		{
			return gpNote;
		}

		public String gpNoteWord()
		{
			return gpNoteWord;
		}
	}

	public static final class Section
	{
		private final String name;
		private final String family;
		private final long total;
		private final List<Row> rows;
		private final long ghost;
		private final String ghostLabel;
		private final boolean summed;
		private final boolean gp;

		Section(String name, String family, long total, List<Row> rows, long ghost, String ghostLabel,
			boolean summed, boolean gp)
		{
			this.name = name;
			this.family = family;
			this.total = total;
			this.rows = Collections.unmodifiableList(rows);
			this.ghost = ghost;
			this.ghostLabel = ghostLabel;
			this.summed = summed;
			this.gp = gp;
		}

		public String name()
		{
			return name;
		}

		public String family()
		{
			return family;
		}

		public long total()
		{
			return total;
		}

		public List<Row> rows()
		{
			return rows;
		}

		public long ghost()
		{
			return ghost;
		}

		public String ghostLabel()
		{
			return ghostLabel;
		}

		public boolean summed()
		{
			return summed;
		}

		public boolean gp()
		{
			return gp;
		}
	}

	public static final class Group
	{
		private final String name;
		private final List<Row> rows;
		private final List<Section> sections;

		Group(String name, List<Row> rows, List<Section> sections)
		{
			this.name = name;
			this.rows = Collections.unmodifiableList(rows);
			this.sections = Collections.unmodifiableList(sections);
		}

		public String name()
		{
			return name;
		}

		public List<Row> rows()
		{
			return rows;
		}

		public List<Section> sections()
		{
			return sections;
		}
	}

	public static final String[] GROUPS = {
		"Experience", "Combat", "Loot", "Skilling", "Upkeep", "Travel", "Achievement",
		"The rest"
	};

	private static final Map<String, List<String>> GROUP_ROWS = new LinkedHashMap<>();

	static
	{
		for (Map.Entry<String, JsonElement> e
			: StatRegistry.TABLES.getAsJsonObject("groupRows").entrySet())
		{
			GROUP_ROWS.put(e.getKey(), Arrays.asList(Tables.strings(e.getValue())));
		}
	}

	private static final Set<String> TRAVEL_SECTIONS =
		new HashSet<>(Arrays.asList("Teleports", "Destinations", "On foot"));

	private static final Set<String> SUMMARY_KEYS = Tables.set(StatRegistry.TABLES, "historySummaryKeys");
	private static final String[] SUMMARY_RUN = Tables.strings(StatRegistry.TABLES.get("summaryRun"));

	private final List<Row> summary;
	private final List<Section> sections;
	private final List<Group> groups;

	private HistoryProgress(List<Row> summary, List<Section> sections)
	{
		this.summary = Collections.unmodifiableList(summary);
		this.sections = Collections.unmodifiableList(sections);
		this.groups = Collections.unmodifiableList(grouped(summary, sections));
	}

	public List<Row> summary()
	{
		return summary;
	}

	public List<Section> sections()
	{
		return sections;
	}

	public List<Group> groups()
	{
		return groups;
	}

	public Group group(String name)
	{
		for (Group g : groups)
		{
			if (g.name().equals(name))
			{
				return g;
			}
		}
		return null;
	}

	public static String groupOf(Section s)
	{
		switch (s.family())
		{
			case "Combat":
				return "Combat";
			case "Skilling":
				return "Skilling";
			case "Living":
				return "Upkeep";
			default:
				return TRAVEL_SECTIONS.contains(s.name()) ? "Travel" : "The rest";
		}
	}

	private static List<Group> grouped(List<Row> summary, List<Section> sections)
	{
		Map<String, Row> byKey = new LinkedHashMap<>();
		for (Row r : summary)
		{
			byKey.put(r.key(), r);
		}
		Map<String, List<Row>> rows = new LinkedHashMap<>();
		Map<String, List<Section>> secs = new LinkedHashMap<>();
		for (String g : GROUPS)
		{
			List<Row> mine = new ArrayList<>();
			for (String key : GROUP_ROWS.get(g))
			{
				Row r = byKey.get(key);
				if (r != null)
				{
					mine.add(r);
				}
			}
			rows.put(g, mine);
			secs.put(g, new ArrayList<>());
		}
		for (Section s : sections)
		{
			String g = groupOf(s);
			if (s.name().equals(s.family()) && s.ghost() == 0)
			{
				rows.get(g).addAll(s.rows());
			}
			else
			{
				secs.get(g).add(s);
			}
		}
		List<Group> out = new ArrayList<>();
		for (String g : GROUPS)
		{
			if (!rows.get(g).isEmpty() || !secs.get(g).isEmpty())
			{
				out.add(new Group(g, rows.get(g), secs.get(g)));
			}
		}
		return out;
	}

	public static HistoryProgress of(Map<String, Long> counters, Predicate<String> gp,
		Map<String, Long> retroactive, boolean leftDated)
	{
		Map<String, Long> c = new LinkedHashMap<>();
		if (counters != null)
		{
			c.putAll(counters);
		}
		if (retroactive != null)
		{
			for (Map.Entry<String, Long> e : retroactive.entrySet())
			{
				if (e.getKey() != null && e.getValue() != null && SUMMARY_KEYS.contains(e.getKey()))
				{
					c.put(e.getKey(), e.getValue());
				}
			}
		}
		Predicate<String> g = gp != null ? gp : StatRegistry::isGp;
		return new HistoryProgress(summary(c, g, leftDated), sections(c, g));
	}

	private static long at(Map<String, Long> m, String key)
	{
		Long v = m.get(key);
		return v != null ? v : 0L;
	}

	private static List<Row> summary(Map<String, Long> c, Predicate<String> gp, boolean leftDated)
	{
		List<Row> out = new ArrayList<>();
		add(out, c, gp, "dropsReceived");
		long received = at(c, "dropsReceived");
		if (received > 0 && leftDated)
		{
			out.add(new Row("dropsTaken", StatRegistry.label("dropsTaken"),
				Math.max(0, received - at(c, "lootLeftKills")), gp.test("dropsTaken")));
		}
		add(out, c, gp, "lootValue");
		long left = at(c, "lootLeftCount");
		long leftGp = at(c, "lootLeftValue");
		if (left > 0)
		{
			out.add(new Row("lootLeftCount", StatRegistry.label("lootLeftCount"), left,
				gp.test("lootLeftCount"), leftGp > 0 ? leftGp : 0, "gp"));
		}
		long kept = at(c, "lootValue") - leftGp;
		if (kept > 0 && leftDated)
		{
			out.add(new Row("lootKept", StatRegistry.label("lootKept"), kept, true));
		}
		for (String key : SUMMARY_RUN)
		{
			add(out, c, gp, key);
		}
		long gathered = at(c, "resourcesGatheredValue");
		if (gathered > 0)
		{
			out.add(new Row("resourcesGatheredValue", StatRegistry.label("resourcesGatheredValue"),
				gathered, gp.test("resourcesGatheredValue"), at(c, "resourcesDroppedValue"),
				"dropped"));
		}
		add(out, c, gp, "itemsDroppedValue");
		return out;
	}

	private static void add(List<Row> out, Map<String, Long> c, Predicate<String> gp, String key)
	{
		long v = at(c, key);
		if (v > 0)
		{
			out.add(new Row(key, StatRegistry.label(key), v, gp.test(key)));
		}
	}

	private static final class Bucket
	{
		final List<Map.Entry<String, Long>> rows = new ArrayList<>();
		final Map<String, Long> floors = new LinkedHashMap<>();
		long floor;
	}

	private static List<Section> sections(Map<String, Long> c, Predicate<String> gp)
	{
		Map<String, Map<String, Bucket>> byFamily = new LinkedHashMap<>();
		for (Map.Entry<String, Long> e : c.entrySet())
		{
			String key = e.getKey();
			Long v = e.getValue();
			if (key == null || v == null || v <= 0 || SUMMARY_KEYS.contains(key)
				|| StatRegistry.hidden(key) || StatRegistry.isPeak(key))
			{
				continue;
			}
			String family = StatRegistry.family(key);
			String sec = StatRegistry.subgroup(key);
			boolean floor = StatRegistry.isFloor(key);
			if (sec.isEmpty())
			{
				String heads = StatRegistry.headOf(family, key);
				if (heads != null)
				{
					sec = heads;
					floor = true;
				}
			}
			Bucket b = byFamily.computeIfAbsent(family, f -> new LinkedHashMap<>())
				.computeIfAbsent(sec, s -> new Bucket());
			if (floor)
			{
				b.floor += v;
				b.floors.put(key, v);
			}
			else
			{
				b.rows.add(e);
			}
		}

		List<Section> out = new ArrayList<>();
		for (String family : StatRegistry.FAMILIES)
		{
			Map<String, Bucket> secs = byFamily.get(family);
			if (secs == null)
			{
				continue;
			}
			for (String sec : sectionOrder(family, secs))
			{
				Section s = section(family, sec, secs.get(sec), gp);
				if (s != null)
				{
					out.add(s);
				}
			}
		}
		return out;
	}

	private static List<String> sectionOrder(String family, Map<String, Bucket> secs)
	{
		List<String> order = new ArrayList<>();
		if (family.equals("Skilling"))
		{
			order.addAll(secs.keySet());
			order.sort(Comparator.comparingLong((String s) -> weight(secs.get(s))).reversed()
				.thenComparing(String::compareToIgnoreCase));
			return order;
		}
		Set<String> present = new LinkedHashSet<>(secs.keySet());
		for (String sec : StatRegistry.fixedSections(family))
		{
			if (present.remove(sec))
			{
				order.add(sec);
			}
		}
		order.addAll(present);
		return order;
	}

	private static long weight(Bucket b)
	{
		if (b.floor > 0)
		{
			return b.floor;
		}
		long sum = 0;
		for (Map.Entry<String, Long> e : b.rows)
		{
			sum += e.getValue();
		}
		return sum;
	}

	private static Section section(String family, String sec, Bucket b, Predicate<String> gp)
	{
		List<Map.Entry<String, Long>> rows = new ArrayList<>(b.rows);
		rows.sort(StatRegistry::compareRows);
		long typedSum = 0;
		boolean anyTyped = false;
		long shown = 0;
		Set<String> verbs = new HashSet<>();
		for (Map.Entry<String, Long> e : rows)
		{
			shown += e.getValue();
			if (StatRegistry.typed(e.getKey()))
			{
				anyTyped = true;
				typedSum += e.getValue();
			}
			String verb = StatRegistry.suffixOf(e.getKey());
			if (verb != null)
			{
				verbs.add(verb);
			}
		}
		long floor = b.floor;
		long ghost = anyTyped && floor - typedSum >= 1 ? floor - typedSum : 0;
		String ghostLabel = "Other";
		if (sec.equals("Teleports") && floor - shown >= 1)
		{
			ghost = floor - shown;
			ghostLabel = "Other means";
		}
		long total = Math.max(shown + ghost, floor);

		boolean verbed = verbs.size() > 1;
		List<Row> lines = new ArrayList<>(rows.size());
		for (Map.Entry<String, Long> e : rows)
		{
			String key = e.getKey();
			String label;
			if (!StatRegistry.typed(key))
			{
				label = StatRegistry.label(key);
			}
			else
			{
				label = verbed ? StatRegistry.rowLabelWithVerb(key) : StatRegistry.rowLabel(key);
			}
			lines.add(new Row(key, label, e.getValue(), gp.test(key)));
		}
		if (lines.isEmpty() && floor > 0)
		{
			List<Map.Entry<String, Long>> floors = new ArrayList<>(b.floors.entrySet());
			floors.sort(StatRegistry::compareRows);
			for (Map.Entry<String, Long> fe : floors)
			{
				lines.add(new Row(fe.getKey(), StatRegistry.label(fe.getKey()), fe.getValue(),
					gp.test(fe.getKey())));
			}
			ghost = 0;
		}
		if (lines.isEmpty() && ghost == 0)
		{
			return null;
		}
		boolean allGp = !lines.isEmpty();
		for (Row r : lines)
		{
			allGp &= r.gp();
		}
		boolean summed = floor > 0 || allGp || !(sec.isEmpty() || sec.equals("Odds & ends"));
		return new Section(sec.isEmpty() ? family : sec, family, total, lines, ghost, ghostLabel,
			summed, allGp);
	}
}
