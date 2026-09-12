/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the BSD 2-Clause
 * License (see LICENSE) are met.
 */
package chronicle.panel;

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

/**
 * A period's progress, shaped for the History tab: a fixed-order summary of
 * the headline figures, then every other counter filed into the families and
 * sections the Stats tab uses, each section carrying its own period total.
 *
 * <p>Input is the period's positive counter deltas, as {@code HistoryLog.gained}
 * returns them: the trackers, and beside them the spine-only totals the plugin
 * derives from the journal (loot events, loot left on the floor, kills, slayer
 * tasks, collection log slots). Nothing here reads the spine or the journal,
 * and nothing here is Swing.
 *
 * <p>"Loot kept" is the owner's "drops picked up", expressed in gp: loot events
 * and items left on the floor are different units (one event can leave several
 * items on the ground, and one item can be part of an event), so the only
 * subtraction that means anything is the value received minus the value left
 * on the floor.
 *
 * <p>Every label comes from {@link StatRegistry}, so a key reads the same here
 * as on the Stats tab. Sections mirror the Stats tab: a key's family and
 * section come from the registry, floors head their section instead of listing
 * as rows, typed rows reconcile to the floor with the remainder as a ghost
 * "Other", and a section's total is the larger of its floor and what its rows
 * and ghost add up to. A family's flat key that heads one of its own sections
 * (potionDoses is the Potions floor, foodEaten the Food floor) files there as
 * the floor, once, never as a row beside the fold. Skilling crafts rank by
 * total, the other families keep the registry's fixed order. Destinations
 * stand as a section of their own under Ledger &amp; Roads, and a family's
 * sectionless rows form a section named after the family, one whose rows mix
 * units and so carries no figure. Summary keys, hidden keys and peak keys
 * (whose delta means nothing) never file.
 *
 * <p>Some summary figures may be read off the journal itself instead of the
 * spine (the slayer lines, and the feed's dated entries counted by type): the
 * caller hands them in as retroactive figures, and a key handed in replaces
 * the spine's delta for that line, or stands alone where the spine never
 * carried the key.
 */
public final class HistoryProgress
{
	/** One figure: a summary line or a section row. */
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

		/** Whether {@link #value()} renders as gp. */
		public boolean gp()
		{
			return gp;
		}

		/** A second figure in gp beside the value ("3 · 1,250 gp"); 0 when there is none. */
		public long gpNote()
		{
			return gpNote;
		}

		/**
		 * The word after the note's figure, as the Stats tab words it: "gp" beside
		 * a count ("3 · 1,250 gp"), "dropped" beside the gathered value
		 * ("2.5M gp · 300k dropped"). Empty when there is no note.
		 */
		public String gpNoteWord()
		{
			return gpNoteWord;
		}
	}

	/** One fold: a section's period total, its rows, and the unresolved remainder. */
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

		/** The Stats family the section files under, one of {@link StatRegistry#FAMILIES}. */
		public String family()
		{
			return family;
		}

		/** The larger of the floor's delta and the rows plus the ghost, as the Stats tab heads it. */
		public long total()
		{
			return total;
		}

		/** Rows by delta descending. */
		public List<Row> rows()
		{
			return rows;
		}

		/** What the floor holds beyond the typed rows; 0 when nothing is left over. */
		public long ghost()
		{
			return ghost;
		}

		/** "Other", or "Other means" under Teleports, as the Stats tab names it. */
		public String ghostLabel()
		{
			return ghostLabel;
		}

		/**
		 * Whether the rows add up to one figure the head can carry: a floor heads
		 * them, every row is gp, or the section counts one kind of action (a
		 * craft, Food, Potions, Thralls, the roads). A family's flat list and
		 * Odds &amp; ends mix units (meals, doses, hitpoints) and carry none.
		 */
		public boolean summed()
		{
			return summed;
		}

		/** Whether {@link #total()} renders as gp: every row is gp. */
		public boolean gp()
		{
			return gp;
		}
	}

	// the counter keys the summary consumes, in summary order. "Left on the
	// floor" reads lootLeftCount and lootLeftValue together, "Loot kept" is
	// derived from lootValue and lootLeftValue, the damage split rides under
	// "Damage dealt", and resourcesDroppedValue is the note on the gathered
	// row, so the list is the keys and not the rows. slayerKills never rides the
	// spine: the History tab reads it off the slayer journey for the period and
	// lays it over the deltas as a retroactive figure. Nor do the pets, quests,
	// diaries, combat achievements and levels: the History tab counts them off
	// the feed's dated entries for the period, the way it counts deaths and
	// collection log slots over the spine's delta. Teleports are not here: the
	// Teleports section carries the period's total with its "Other means", one
	// place for one figure.
	private static final Set<String> SUMMARY_KEYS = new HashSet<>(Arrays.asList(
		"dropsReceived", "lootValue", "lootLeftCount", "lootLeftValue", "kills",
		"slayerTasksCompleted", "slayerKills", "damageDealt", "damageDealtMelee",
		"damageDealtRanged",
		"damageDealtMagic", "deaths", "petsObtained", "questsCompleted", "diariesCompleted",
		"combatAchievements", "levelsGained", "clogSlotsObtained", "distanceRan",
		"distanceWalked",
		"coinsSpentAtShops", "coinsEarnedAtShops", "coinsFromAlchemy", "consumedValue",
		"resourcesGatheredValue", "resourcesDroppedValue", "itemsDroppedValue"));

	private final List<Row> summary;
	private final List<Section> sections;

	private HistoryProgress(List<Row> summary, List<Section> sections)
	{
		this.summary = Collections.unmodifiableList(summary);
		this.sections = Collections.unmodifiableList(sections);
	}

	/** The headline figures, in their fixed order; only those above zero. */
	public List<Row> summary()
	{
		return summary;
	}

	/** Every other counter, filed by family then section; empty sections are absent. */
	public List<Section> sections()
	{
		return sections;
	}

	/** Whether the summary consumes a counter key, keeping it out of the sections. */
	public static boolean summaryKey(String key)
	{
		return SUMMARY_KEYS.contains(key);
	}

	/**
	 * Shape a period.
	 *
	 * @param counters the period's positive counter deltas, spine extras included
	 * @param gp whether a key's figure is gp; null reads the registry
	 */
	public static HistoryProgress of(Map<String, Long> counters, Predicate<String> gp)
	{
		return of(counters, gp, null);
	}

	/**
	 * Shape a period, with summary figures read off the journal itself laid over
	 * the spine's deltas.
	 *
	 * @param counters the period's positive counter deltas, spine extras included
	 * @param gp whether a key's figure is gp; null reads the registry
	 * @param retroactive summary keys with the figure the journal gives them for
	 * the period (closed slayer segments, the feed's dated entries by type); a
	 * key here replaces the spine's delta, a zero included, and a key the
	 * summary does not read is ignored. Null for none.
	 */
	public static HistoryProgress of(Map<String, Long> counters, Predicate<String> gp,
		Map<String, Long> retroactive)
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
		return new HistoryProgress(summary(c, g), sections(c, g));
	}

	private static long at(Map<String, Long> m, String key)
	{
		Long v = m.get(key);
		return v != null ? v : 0L;
	}

	private static List<Row> summary(Map<String, Long> c, Predicate<String> gp)
	{
		List<Row> out = new ArrayList<>();
		add(out, c, gp, "dropsReceived");
		add(out, c, gp, "lootValue");
		long left = at(c, "lootLeftCount");
		long leftGp = at(c, "lootLeftValue");
		if (left > 0)
		{
			out.add(new Row("lootLeftCount", StatRegistry.label("lootLeftCount"), left,
				gp.test("lootLeftCount"), leftGp > 0 ? leftGp : 0, "gp"));
		}
		long kept = at(c, "lootValue") - leftGp;
		if (kept > 0)
		{
			out.add(new Row("lootKept", StatRegistry.label("lootKept"), kept, true));
		}
		add(out, c, gp, "kills");
		add(out, c, gp, "slayerTasksCompleted");
		add(out, c, gp, "slayerKills");
		add(out, c, gp, "damageDealt");
		// the split rides under its parent, with the registry's "· by" labels
		add(out, c, gp, "damageDealtMelee");
		add(out, c, gp, "damageDealtRanged");
		add(out, c, gp, "damageDealtMagic");
		add(out, c, gp, "deaths");
		// the feed's dated entries, counted by type on the History tab
		add(out, c, gp, "petsObtained");
		add(out, c, gp, "questsCompleted");
		add(out, c, gp, "diariesCompleted");
		add(out, c, gp, "combatAchievements");
		add(out, c, gp, "levelsGained");
		add(out, c, gp, "clogSlotsObtained");
		add(out, c, gp, "distanceRan");
		add(out, c, gp, "distanceWalked");
		add(out, c, gp, "coinsSpentAtShops");
		add(out, c, gp, "coinsEarnedAtShops");
		add(out, c, gp, "coinsFromAlchemy");
		add(out, c, gp, "consumedValue");
		long gathered = at(c, "resourcesGatheredValue");
		if (gathered > 0)
		{
			// what the gatherer dropped rides the gathered row as its margin, as
			// on the Stats tab: subtract one from the other and a miner's career
			// reads as zero
			out.add(new Row("resourcesGatheredValue", StatRegistry.label("resourcesGatheredValue"),
				gathered, gp.test("resourcesGatheredValue"), at(c, "resourcesDroppedValue"),
				"dropped"));
		}
		add(out, c, gp, "itemsDroppedValue");
		return out;
	}

	// one summary line, only when the period moved it
	private static void add(List<Row> out, Map<String, Long> c, Predicate<String> gp, String key)
	{
		long v = at(c, key);
		if (v > 0)
		{
			out.add(new Row(key, StatRegistry.label(key), v, gp.test(key)));
		}
	}

	// one section in the making: its rows, and its floor split by floor key
	private static final class Bucket
	{
		final List<Map.Entry<String, Long>> rows = new ArrayList<>();
		final Map<String, Long> floors = new LinkedHashMap<>();
		long floor;
	}

	private static List<Section> sections(Map<String, Long> c, Predicate<String> gp)
	{
		// family -> section -> bucket, in arrival order; the families and the
		// fixed sections are re-ordered below
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
				// a flat key that heads one of the family's own sections files
				// there as the floor: potionDoses is Living's "Doses drunk" and
				// the Potions floor, and one figure is shown once
				String heads = headOf(family, key);
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

	// the section of `family` that `key` is a floor of, or null when none of the
	// family's fixed sections is headed by it
	private static String headOf(String family, String key)
	{
		for (String sec : StatRegistry.fixedSections(family))
		{
			if (!sec.isEmpty() && StatRegistry.floorKeys(sec).contains(key))
			{
				return sec;
			}
		}
		return null;
	}

	// Skilling ranks its crafts by weight (the floor when there is one, else the
	// rows' sum); the other families keep the registry's order, strays last
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
			// means of travel aren't typed rows, but the floor still reconciles:
			// unclassified journeys are "Other means", as on the Stats tab
			ghost = floor - shown;
			ghostLabel = "Other means";
		}
		// the head is never less than the rows under it: a named row outside the
		// floor (herbiboars beside creatures trapped, failed pickpockets beside
		// the successes) lifts it, as on the Stats tab
		long total = Math.max(shown + ghost, floor);

		// a section holding more than one verb names each row's verb: the Stats
		// tab nests them by verb, and "Shark" cooked beside "Shark" burned is not
		// a breakdown
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
			// a floor with no typed rows opens to its floors, one row each: bones
			// buried and bones offered are separate verbs
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
		// the head carries a figure where the rows add up to one: a floor heads
		// them, every row is gp, or the section counts one kind of action. A
		// family's flat list and Odds & ends mix meals, doses and hitpoints.
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
