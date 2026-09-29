/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the
 * BSD 2-Clause License (see LICENSE) are met.
 */
package chronicle;

import chronicle.LocalStore.SourceRow;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import static chronicle.Json.low;
import static chronicle.Json.obj;
import static chronicle.Json.safeLong;
import static chronicle.Json.table;

@RequiredArgsConstructor
final class Standings
{
	static final String[] CLUE_TIERS = {"Beginner", "Easy", "Medium", "Hard", "Elite", "Master"};

	private static final JsonObject COMBAT_BUNDLE = table("osrs_combat_achievements.json");
	static final JsonObject CA_TASKS = obj(COMBAT_BUNDLE, "tasks");
	private static final long CA_POINTS = safeLong(obj(obj(COMBAT_BUNDLE, "_meta"), "totals").get("points"));
	static final JsonObject DIARY_TASKS = obj(table("osrs_achievement_diaries.json"), "diaries");

	private final Build build;
	private JsonObject achievements;

	static final class Quests
	{
		final List<String> done = new ArrayList<>();
		final List<String> going = new ArrayList<>();
		final List<String> not = new ArrayList<>();
		int known;
	}

	JsonObject achievements()
	{
		if (achievements == null)
		{
			achievements = build.plugin.achievements();
		}
		return achievements;
	}

	Quests quests()
	{
		Quests out = new Quests();
		JsonObject q = obj(achievements(), "quests");
		out.known = q.size();
		for (String name : q.keySet())
		{
			String state = q.get(name).getAsString();
			("FINISHED".equals(state) ? out.done : "IN_PROGRESS".equals(state) ? out.going : out.not)
				.add(name);
		}
		return out;
	}

	SourceRow clue(String tier)
	{
		return Sources.find(build.sources(), r -> r.name, "Clue Scroll (" + tier + ")", false);
	}

	static long caskets(SourceRow r)
	{
		return Math.max(r.kc, r.loots);
	}

	long[] diaries()
	{
		JsonObject d = obj(achievements(), "diaries");
		long done = 0;
		long all = 0;
		long whole = 0;
		for (String region : d.keySet())
		{
			JsonObject tiers = d.getAsJsonObject(region);
			long here = 0;
			for (String tier : tiers.keySet())
			{
				all++;
				if (tiers.get(tier).getAsBoolean())
				{
					done++;
					here++;
				}
			}
			if (here > 0 && here == tiers.size())
			{
				whole++;
			}
		}
		return new long[]{done, all, whole, d.size()};
	}

	JsonObject diaryHeld(String region)
	{
		JsonObject mine = obj(achievements(), "diaries");
		String key = low(region);
		for (String k : mine.keySet())
		{
			if (k.equalsIgnoreCase(key) || key.startsWith(low(k)))
			{
				return mine.getAsJsonObject(k);
			}
		}
		return null;
	}

	long[] combat()
	{
		JsonObject c = obj(achievements(), "combat");
		long points = c.has("points") ? c.get("points").getAsLong() : 0;
		long tiers = 0;
		JsonObject t = obj(c, "tiers");
		for (String k : t.keySet())
		{
			if (t.get(k).getAsLong() > 0)
			{
				tiers++;
			}
		}
		long possible = 0;
		for (JsonObject e : build.feed.all())
		{
			JsonObject data = obj(e, "data");
			if (possible == 0 && "COMBAT_ACHIEVEMENT".equals(Feed.typeOf(e)) && data.has("totalPossiblePoints"))
			{
				possible = data.get("totalPossiblePoints").getAsLong();
			}
		}
		return new long[]{points, possible == 0 ? CA_POINTS : possible, tiers};
	}

	Set<Integer> caDone()
	{
		Set<Integer> out = new HashSet<>();
		JsonObject c = obj(achievements(), "combat");
		if (!c.has("tasksDone") || !c.get("tasksDone").isJsonArray())
		{
			return out;
		}
		for (JsonElement e : c.getAsJsonArray("tasksDone"))
		{
			try
			{
				out.add(e.getAsInt());
			}
			catch (RuntimeException ignored)
			{
			}
		}
		return out;
	}

	static Map<String, List<JsonObject>> caBySource()
	{
		Map<String, List<JsonObject>> out = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		for (String id : CA_TASKS.keySet())
		{
			JsonObject task = CA_TASKS.getAsJsonObject(id).deepCopy();
			task.addProperty("id", Integer.parseInt(id));
			out.computeIfAbsent(caSource(task.get("monster").getAsString()), k -> new ArrayList<>()).add(task);
		}
		return out;
	}

	static String caSource(String monster)
	{
		return monster == null || monster.trim().isEmpty()
			|| "N/A".equalsIgnoreCase(monster.trim()) ? "Anywhere" : monster;
	}
}
