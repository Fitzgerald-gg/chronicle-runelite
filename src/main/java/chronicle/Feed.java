/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import chronicle.HistoryLog.Baseline;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.LongFunction;
import static chronicle.Json.*;
import static chronicle.Reference.*;
import static chronicle.Ui.*;
import static chronicle.panel.StatRegistry.prettify;

final class Feed
{
	private static final long[] TOTAL_LEVELS = {1000, 1500, 2000, 2200, 2277, 2376};
	private static final long[] NINETY_NINES = {5, 10, 15, 20};
	private static final long[] COMBAT_LEVELS = {100, 126};
	private static final long[] SKILL_XP = {10_000_000L, 50_000_000L, 100_000_000L, 200_000_000L};
	private static final long[] OVERALL_XP = {100_000_000L, 250_000_000L, 500_000_000L, 1_000_000_000L};
	private static final long[] LOG_SLOTS = {500, 1000, 1500};

	private Feed()
	{
	}

	static String typeOf(JsonObject e)
	{
		return e.has("type") ? e.get("type").getAsString() : "";
	}

	static String feedLine(JsonObject e)
	{
		String type = typeOf(e);
		JsonObject d = obj(e, "data");
		switch (type)
		{
			case "PET":
				return "Pet: " + str(d, "petName", "a new companion");
			case "COLLECTION":
				return "Log slot: " + str(d, "itemName", "new item");
			case "RECORD":
			{
				double time = d.has("time") ? d.get("time").getAsDouble() : 0;
				double was = d.has("was") ? d.get("was").getAsDouble() : 0;
				return "Record: " + str(d, "source", "") + " " + pb(time)
					+ (was > 0 ? ", was " + pb(was) : "");
			}
			case "MILESTONE":
				return "Milestone: " + str(d, "text", "");
			case "COMBAT_ACHIEVEMENT":
				return "CA " + str(d, "tier", "") + ": " + str(d, "task", "task");
			case "QUEST":
				return "Quest: " + str(d, "questName", str(d, "quest", "complete"));
			case "DIARY":
				return "Diary: " + str(d, "area", "") + " " + str(d, "difficulty", "");
			case "CLUE":
				return "Clue: " + str(d, "clueType", "casket opened");
			case "LEVEL":
				return "Level: " + str(d, "skill", "a skill") + " " + str(d, "level", "");
			case "DEATH":
			{
				String k = str(d, "killerName", "");
				return k.isEmpty() ? "Died" : "Died to " + k;
			}
			case "SESSION":
			{
				long mins = asLong(d.get("minutes"));
				long xp = asLong(d.get("xp"));
				long drops = asLong(d.get("drops"));
				long dropsGp = asLong(d.get("dropsGp"));
				StringBuilder line = new StringBuilder("Session: ");
				line.append(hoursMinutes(mins));
				if (xp > 0)
				{
					line.append(" · +").append(gp(xp)).append(" xp");
					String most = mostOf(d);
					if (most != null)
					{
						line.append(", most in ").append(most);
					}
				}
				if (drops > 0)
				{
					line.append(" · ").append(count(drops, "drop"));
					if (dropsGp > 0)
					{
						line.append(" (").append(gp(dropsGp)).append(" gp)");
					}
				}
				return line.toString();
			}
			case "SLAYER":
			{
				String t = str(d, "slayerTask", str(d, "task", ""));
				String kc = str(d, "killCount", "");
				return "Task complete" + (t.isEmpty() ? "" : ": " + t)
					+ (kc.isEmpty() ? "" : ", " + kc + " killed");
			}
			default:
				return type.isEmpty() ? "Milestone" : prettify(low(type));
		}
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

	static String sessionTitle(JsonObject e)
	{
		return "Session · " + hoursMinutes(asLong(obj(e, "data").get("minutes")));
	}

	static String sessionFigures(JsonObject e)
	{
		JsonObject d = obj(e, "data");
		long xp = asLong(d.get("xp"));
		long drops = asLong(d.get("drops"));
		StringBuilder right = new StringBuilder();
		if (xp > 0)
		{
			right.append('+').append(gp(xp)).append(" xp");
		}
		if (drops > 0)
		{
			right.append(right.length() > 0 ? " · " : "").append(count(drops, "drop"));
		}
		return right.toString();
	}

	private static String mostOf(JsonObject d)
	{
		Entry<String, JsonElement> top = most(obj(d, "skills").entrySet(), e -> asLong(e.getValue()));
		return top == null ? null : prettify(top.getKey());
	}

	static String stamp(JsonObject e)
	{
		long ts = asLong(e.get("ts"));
		return ts > 0 ? DAY.format(Instant.ofEpochMilli(ts)) : "";
	}

	static long sittingStart(JsonObject e)
	{
		JsonObject d = obj(e, "data");
		long start = asLong(d.get("start"));
		if (start > 0)
		{
			return start;
		}
		long ts = asLong(e.get("ts"));
		return ts > 0 ? ts - sessionMinutes(e) * 60_000L : ts;
	}

	static long filedAt(JsonObject e)
	{
		return "SESSION".equals(typeOf(e)) ? sittingStart(e) : asLong(e.get("ts"));
	}

	static long sessionMinutes(JsonObject e)
	{
		JsonObject d = obj(e, "data");
		return Math.max(0, asLong(d.get("minutes")));
	}

	private static String questName(String raw)
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

	static long oldestTs(List<JsonObject> feed, boolean sittings)
	{
		long oldest = 0;
		for (JsonObject e : feed)
		{
			long ts = !sittings ? asLong(e.get("ts"))
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
		return feed.isEmpty() ? 0 : asLong(feed.get(0).get("ts"));
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

	static Map<String, Long> standings(Baseline b, List<String> keys)
	{
		Map<String, Long> out = new LinkedHashMap<>();
		HistoryLog.Levels lv = HistoryLog.levels(b, keys);
		if (b.complete)
		{
			out.put("total", (long) lv.total);
			out.put("nines", (long) lv.nines);
			Integer combat = openingCombat(lv);
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

	static void crossings(Map<String, Long> prev, Map<String, Long> now, long ts, List<JsonObject> into)
	{
		cross(prev, now, ts, into, "total", TOTAL_LEVELS, t -> "Total level " + fmt(t));
		cross(prev, now, ts, into, "nines", NINETY_NINES, t -> ordinal(t) + " 99");
		cross(prev, now, ts, into, "combat", COMBAT_LEVELS, t -> "Combat level " + t);
		for (String key : now.keySet())
		{
			if (key.startsWith("xp:"))
			{
				cross(prev, now, ts, into, key, SKILL_XP, t -> threshold(t) + " xp in " + prettify(key.substring(3)));
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

	private static String threshold(long xp)
	{
		return xp % 1_000_000_000L == 0 ? xp / 1_000_000_000L + "B" : xp / 1_000_000L + "M";
	}

	private static JsonObject milestone(long ts, String text)
	{
		JsonObject e = Json.of("ts", ts, "type", "MILESTONE");
		JsonObject d = new JsonObject();
		d.addProperty("text", text);
		e.add("data", d);
		return e;
	}

	static List<String> counted(List<String> names)
	{
		Map<String, Integer> n = new LinkedHashMap<>();
		names.forEach(s -> n.merge(s, 1, Integer::sum));
		List<String> out = new ArrayList<>();
		n.entrySet().forEach(e -> out.add(e.getKey() + (e.getValue() > 1 ? " ×" + e.getValue() : "")));
		return out;
	}

	static String featOf(String type)
	{
		switch (type)
		{
			case "COLLECTION":
				return "Collection log";
			case "QUEST":
				return "Quests";
			case "DIARY":
				return "Diaries";
			default:
				return "Combat achievements";
		}
	}
}
