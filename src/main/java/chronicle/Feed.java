/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the
 * BSD 2-Clause License (see LICENSE) are met.
 */
package chronicle;

import chronicle.HistoryLog.Baseline;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.LongFunction;
import java.util.function.ToLongFunction;
import lombok.RequiredArgsConstructor;
import static chronicle.Json.has;
import static chronicle.Json.low;
import static chronicle.Json.obj;
import static chronicle.Json.safeLong;
import static chronicle.Period.dayOf;
import static chronicle.Period.noon;
import static chronicle.Text.count;
import static chronicle.Text.fmt;
import static chronicle.Text.gp;
import static chronicle.Text.hoursMinutes;
import static chronicle.Text.ordinal;
import static chronicle.Text.tail;
import static chronicle.Text.threshold;
import static chronicle.panel.StatRegistry.prettify;

@RequiredArgsConstructor
final class Feed
{
	static final int DEEP = 4000;

	private final Build build;
	private List<JsonObject> deep;

	List<JsonObject> deep()
	{
		if (deep == null)
		{
			deep = build.plugin.feedNewest(DEEP);
		}
		return deep;
	}

	static String typeOf(JsonObject e)
	{
		return e.has("type") ? e.get("type").getAsString() : "";
	}

	private List<JsonObject> milestones;

	private static final long[] TOTAL_LEVELS = {1000, 1500, 2000, 2200, 2277, 2376};

	private static final long[] NINETY_NINES = {5, 10, 15, 20};

	private static final long[] COMBAT_LEVELS = {100, 126};

	private static final long[] SKILL_XP = {10_000_000L, 50_000_000L, 100_000_000L, 200_000_000L};

	private static final long[] OVERALL_XP = {100_000_000L, 250_000_000L, 500_000_000L, 1_000_000_000L};

	private static final long[] LOG_SLOTS = {500, 1000, 1500};

	List<JsonObject> milestones()
	{
		if (milestones == null)
		{
			milestones = new ArrayList<>();
			TreeMap<LocalDate, Baseline> spine = build.spine();
			if (spine != null && spine.size() > 1)
			{
				Map<String, Long> prev = null;
				for (Entry<LocalDate, Baseline> day : spine.entrySet())
				{
					Map<String, Long> now = standings(day.getValue(), HistoryLog.SKILL_KEYS);
					if (prev != null)
					{
						long ts = noon(day.getKey());
						crossings(prev, now, ts, milestones);
					}
					Map<String, Long> carried = prev == null
						? new LinkedHashMap<>() : new LinkedHashMap<>(prev);
					carried.putAll(now);
					prev = carried;
				}
				Collections.reverse(milestones);
			}
		}
		return milestones;
	}

	private static Map<String, Long> standings(Baseline b, List<String> keys)
	{
		Map<String, Long> out = new LinkedHashMap<>();
		HistoryLog.Levels lv = HistoryLog.levels(b, keys);
		if (b.complete)
		{
			out.put("total", (long) lv.total);
			out.put("nines", (long) lv.nines);
			Integer combat = HistoryLog.combat(lv);
			if (combat != null)
			{
				out.put("combat", (long) combat);
			}
		}
		for (String key : keys)
		{
			Long xp = b.skills.get(key);
			if (xp != null)
			{
				out.put("xp:" + key, xp);
			}
		}
		Long overall = b.skills.get("overall");
		if (overall != null)
		{
			out.put("overall", overall);
		}
		Long slots = b.counters.get("clogSlotsObtained");
		if (slots != null)
		{
			out.put("slots", slots);
		}
		return out;
	}

	private static void crossings(Map<String, Long> prev, Map<String, Long> now, long ts,
		List<JsonObject> into)
	{
		cross(prev, now, ts, into, "total", TOTAL_LEVELS, t -> "Total level " + fmt(t));
		cross(prev, now, ts, into, "nines", NINETY_NINES, t -> ordinal(t) + " 99");
		cross(prev, now, ts, into, "combat", COMBAT_LEVELS, t -> "Combat level " + t);
		for (String key : now.keySet())
		{
			if (key.startsWith("xp:"))
			{
				cross(prev, now, ts, into, key, SKILL_XP, t -> threshold(t) + " xp in "
					+ prettify(key.substring(3)));
			}
		}
		cross(prev, now, ts, into, "overall", OVERALL_XP, t -> threshold(t) + " xp overall");
		cross(prev, now, ts, into, "slots", LOG_SLOTS, t -> fmt(t) + " collection log slots");
	}

	private static void cross(Map<String, Long> prev, Map<String, Long> now, long ts,
		List<JsonObject> into, String key, long[] at, LongFunction<String> text)
	{
		Long before = prev.get(key);
		Long after = now.get(key);
		for (long t : at)
		{
			if (before != null && after != null && before < t && after >= t)
			{
				into.add(milestone(ts, text.apply(t)));
			}
		}
	}

	private static JsonObject milestone(long ts, String text)
	{
		JsonObject e = new JsonObject();
		e.addProperty("ts", ts);
		e.addProperty("type", "MILESTONE");
		JsonObject d = new JsonObject();
		d.addProperty("text", text);
		e.add("data", d);
		return e;
	}

	List<JsonObject> withMilestones(List<JsonObject> feed)
	{
		List<JsonObject> marks = milestones();
		if (marks.isEmpty())
		{
			return feed;
		}
		List<JsonObject> out = new ArrayList<>(feed.size() + marks.size());
		int m = 0;
		boolean liveHead = !feed.isEmpty() && feed.get(0).has("live");
		for (int i = 0; i < feed.size(); i++)
		{
			long ts = safeLong(feed.get(i).get("ts"));
			while ((i > 0 || !liveHead) && m < marks.size()
				&& safeLong(marks.get(m).get("ts")) > ts)
			{
				out.add(marks.get(m++));
			}
			out.add(feed.get(i));
		}
		while (m < marks.size())
		{
			out.add(marks.get(m++));
		}
		return out;
	}

	private Map<String, Long> landed;

	Map<String, Long> landed()
	{
		if (landed == null)
		{
			landed = new LinkedHashMap<>();
			for (JsonObject e : deep())
			{
				if (!"COLLECTION".equals(typeOf(e)))
				{
					continue;
				}
				JsonObject d = obj(e, "data");
				if (has(d, "itemName"))
				{
					landed.put(low(d.get("itemName").getAsString()),
						safeLong(e.get("ts")));
				}
			}
		}
		return landed;
	}

	private Map<String, JsonObject> records;

	Map<String, JsonObject> records()
	{
		if (records == null)
		{
			records = new LinkedHashMap<>();
			for (JsonObject e : deep())
			{
				if (!"RECORD".equals(typeOf(e)))
				{
					continue;
				}
				JsonObject d = obj(e, "data");
				if (has(d, "source"))
				{
					records.putIfAbsent(low(d.get("source").getAsString()), e);
				}
			}
		}
		return records;
	}

	private Map<LocalDate, long[]> days;

	private Map<String, long[]> dayTotals;

	Map<LocalDate, long[]> days()
	{
		if (days == null)
		{
			days = new LinkedHashMap<>();
			daySkills = new LinkedHashMap<>();
			crossedDays = new HashSet<>();
			for (JsonObject e : build.plugin.feedWithSitting(DEEP))
			{
				if (!"SESSION".equals(typeOf(e)))
				{
					continue;
				}
				JsonObject d = obj(e, "data");
				LocalDate day = dayOf(sittingStart(e));
				long ended = safeLong(e.get("ts"));
				if (ended > 0)
				{
					LocalDate last = dayOf(ended);
					for (LocalDate on = day; last.isAfter(day) && !on.isAfter(last); on = on.plusDays(1))
					{
						crossedDays.add(on);
					}
				}
				long[] t = days.computeIfAbsent(day, k -> new long[8]);
				t[0] += sessionMinutes(e);
				t[1]++;
				if (d.has("xp"))
				{
					t[2] += safeLong(d.get("xp"));
					t[3]++;
				}
				if (d.has("drops"))
				{
					t[4] += safeLong(d.get("drops"));
					t[5] += safeLong(d.get("dropsGp"));
					t[6]++;
				}
				if (d.has("xp") && safeLong(d.get("xp")) == 0 && !d.has("skills"))
				{
					t[7]++;
				}
				if (d.has("skills") && d.get("skills").isJsonObject())
				{
					t[7]++;
					Map<String, Long> by = daySkills.computeIfAbsent(day, k -> new LinkedHashMap<>());
					for (Entry<String, JsonElement> sk : d.getAsJsonObject("skills").entrySet())
					{
						by.merge(sk.getKey(), safeLong(sk.getValue()), Long::sum);
					}
				}
			}
		}
		return days;
	}

	private Map<LocalDate, Map<String, Long>> daySkills;

	private Set<LocalDate> crossedDays;

	Map<String, long[]> dayTotals()
	{
		if (dayTotals == null)
		{
			dayTotals = build.plugin.dayTotals();
		}
		return dayTotals;
	}

	static final DateTimeFormatter ROLL_DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

	Object[] dayXp(LocalDate day)
	{
		TreeMap<LocalDate, Baseline> spine = build.spine();
		if (spine == null)
		{
			return null;
		}
		Baseline at = spine.get(day);
		Entry<LocalDate, Baseline> before = spine.lowerEntry(day);
		if (at == null || before == null || !before.getKey().plusDays(1).equals(day))
		{
			return null;
		}
		long total = 0;
		long most = 0;
		String top = null;
		for (Entry<String, Long> e : at.skills.entrySet())
		{
			Long was = before.getValue().skills.get(e.getKey());
			if ("overall".equals(e.getKey()) || was == null)
			{
				continue;
			}
			long gained = e.getValue() - was;
			if (gained <= 0)
			{
				continue;
			}
			total += gained;
			if (gained > most)
			{
				most = gained;
				top = e.getKey();
			}
		}
		return total > 0 ? new Object[]{total, prettify(top)} : null;
	}

	String dayEntry(LocalDate day)
	{
		List<String> clauses = new ArrayList<>();
		long[] sat = days().get(day);
		if (sat != null && sat[1] > 0)
		{
			clauses.add(count(sat[1], "sitting")
				+ (sat[0] > 0 ? " · " + hoursMinutes(sat[0]) : ""));
		}
		boolean crossed = crossedDays != null && crossedDays.contains(day);
		boolean saysXp = crossed && sat != null && sat[1] > 0 && sat[3] == sat[1];
		Object[] xp = saysXp ? null : dayXp(day);
		if (saysXp && sat[2] > 0)
		{
			Map<String, Long> by = sat[7] == sat[1] && daySkills != null ? daySkills.get(day) : null;
			String most = by == null ? null : topOf(by);
			if (most != null)
			{
				most = prettify(most);
			}
			else
			{
				Object[] spine = dayXp(day);
				most = spine != null ? (String) spine[1] : null;
			}
			clauses.add("+" + gp(sat[2]) + " xp" + (most != null ? ", most in " + most : ""));
		}
		else if (xp != null)
		{
			clauses.add("+" + gp((Long) xp[0]) + " xp, most in " + xp[1]);
		}
		long[] loot = crossed && sat != null && sat[1] > 0 && sat[6] == sat[1]
			? new long[]{sat[4], sat[5]} : dayTotals().get(ROLL_DAY.format(day));
		if (loot != null && loot[0] > 0)
		{
			clauses.add(count(loot[0], "drop")
				+ tail(loot[1]));
		}
		return clauses.isEmpty() ? null : String.join(" · ", clauses);
	}

	private Map<String, Long> types;

	long inWindow(String type)
	{
		if (types == null)
		{
			types = new LinkedHashMap<>();
			for (JsonObject e : deep())
			{
				if (build.period().inside(safeLong(e.get("ts"))))
				{
					types.merge(typeOf(e), 1L, Long::sum);
				}
			}
		}
		Long n = types.get(type);
		return n == null ? 0 : n;
	}

	static String questName(String raw)
	{
		String q = raw == null ? "" : raw.trim();
		int at = low(q).indexOf("you have completed ");
		if (at >= 0)
		{
			q = q.substring(at + "you have completed ".length()).trim();
		}
		while (q.endsWith("!") || q.endsWith("."))
		{
			q = q.substring(0, q.length() - 1).trim();
		}
		return q.isEmpty() ? raw : q;
	}

	static String feedName(JsonObject e)
	{
		JsonObject d = obj(e, "data");
		switch (typeOf(e))
		{
			case "PET":
				return has(d, "petName") ? d.get("petName").getAsString() : null;
			case "COLLECTION":
				return has(d, "itemName") ? d.get("itemName").getAsString() : null;
			case "QUEST":
				return has(d, "questName") ? questName(d.get("questName").getAsString())
					: has(d, "quest") ? questName(d.get("quest").getAsString()) : null;
			case "DIARY":
				return has(d, "area")
					? d.get("area").getAsString()
					+ (has(d, "difficulty") ? " " + d.get("difficulty").getAsString() : "")
					: null;
			case "COMBAT_ACHIEVEMENT":
				return has(d, "task")
					? (has(d, "tier")
					? prettify(low(d.get("tier").getAsString()))
					+ " · " : "") + d.get("task").getAsString()
					: null;
			case "LEVEL":
				return has(d, "skill")
					? prettify(low(d.get("skill").getAsString()))
					+ (has(d, "level") ? " " + d.get("level").getAsString() : "")
					: null;
			default:
				return null;
		}
	}

	static long sessionMinutes(JsonObject e)
	{
		JsonObject d = obj(e, "data");
		return Math.max(0, safeLong(d.get("minutes")));
	}

	static long sittingStart(JsonObject e)
	{
		JsonObject d = obj(e, "data");
		long start = safeLong(d.get("start"));
		if (start > 0)
		{
			return start;
		}
		long ts = safeLong(e.get("ts"));
		return ts > 0 ? ts - sessionMinutes(e) * 60_000L : ts;
	}

	static long filedAt(JsonObject e)
	{
		return "SESSION".equals(typeOf(e)) ? sittingStart(e) : safeLong(e.get("ts"));
	}

	static boolean sittingsCover(List<JsonObject> feed, long fromMs)
	{
		long oldest = oldestTs(feed, true);
		return oldest > 0 && oldest <= fromMs;
	}

	static long earliestDatedLoot(List<JsonObject> feed, long rollFrom)
	{
		long sittings = oldestTs(feed, true);
		if (sittings <= 0)
		{
			return rollFrom;
		}
		return rollFrom <= 0 ? sittings : Math.min(sittings, rollFrom);
	}

	static long oldestTs(List<JsonObject> feed, boolean sittings)
	{
		long oldest = 0;
		for (JsonObject e : feed)
		{
			long ts = !sittings ? safeLong(e.get("ts"))
				: "SESSION".equals(typeOf(e)) ? sittingStart(e) : 0;
			if (ts > 0 && (oldest == 0 || ts < oldest))
			{
				oldest = ts;
			}
		}
		return oldest;
	}

	static long newestTs(List<JsonObject> feed)
	{
		return feed.isEmpty() ? 0 : safeLong(feed.get(0).get("ts"));
	}

	static String topOf(Map<String, Long> by)
	{
		Entry<String, Long> top = most(by.entrySet(), Entry::getValue);
		return top == null ? null : top.getKey();
	}

	static <T> T most(Iterable<T> all, ToLongFunction<T> size)
	{
		T top = null;
		long most = 0;
		for (T t : all)
		{
			long n = size.applyAsLong(t);
			if (n > most)
			{
				most = n;
				top = t;
			}
		}
		return top;
	}
}
