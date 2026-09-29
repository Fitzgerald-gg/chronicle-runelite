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
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import net.runelite.api.Skill;
import static chronicle.Json.low;
import static chronicle.Json.strMap;
import static chronicle.Json.strs;
import static chronicle.Json.table;
import static chronicle.LocalStore.kindOf;

@RequiredArgsConstructor
final class Sources
{
	static final String BOSS = "Bosses";
	static final String ACTIVITY = "Activities";
	static final String SKILLING = "Skilling";
	static final String MONSTER = "Monsters";

	static final JsonObject KINDS = table("panel_kinds.json");

	private static final Set<String> PICKPOCKETED = new HashSet<>(strs(KINDS.get("pickpocketed")));
	private static final Set<String> MONSTER_PAGES = new HashSet<>(strs(KINDS.get("monsterPages")));
	private static final List<String> OPENED = strs(KINDS.get("opened"));
	private static final List<String> GATHERED = strs(KINDS.get("gathered"));
	private static final Map<String, String> PAGE_SKILL = strMap(KINDS, "pageSkills");
	private static final String[][] SKILLED = {
		{"Pickpockets", "THIEVING"}, {"Trapped", "HUNTER"},
		{"Caught", "HUNTER"}, {"Harvested", "HUNTER"},
	};

	private final Build build;
	private final Map<String, String> kinds = new LinkedHashMap<>();
	private Map<String, String> skilled;
	private Map<String, String> ledgerNames;

	String kind(String name)
	{
		return kinds.computeIfAbsent(name, this::decide);
	}

	private String decide(String name)
	{
		if (PICKPOCKETED.contains(low(name)) || skilled().containsKey(letters(name)))
		{
			return SKILLING;
		}
		for (Entry<String, Map<String, List<String>>> tab : ClogBook.taxonomy(build.plugin.gson()).entrySet())
		{
			if (!tab.getValue().containsKey(name))
			{
				continue;
			}
			String t = low(tab.getKey());
			if (t.contains("boss") || t.contains("raid"))
			{
				return BOSS;
			}
			if (t.contains("clue") || t.contains("minigame"))
			{
				return ACTIVITY;
			}
			return MONSTER_PAGES.contains(name) ? MONSTER : SKILLING;
		}
		String low = low(name);
		return containsAny(low, OPENED) ? ACTIVITY : containsAny(low, GATHERED) ? SKILLING : MONSTER;
	}

	private Map<String, String> skilled()
	{
		if (skilled == null)
		{
			skilled = new LinkedHashMap<>();
			for (String key : build.plugin.lifetimeCounters().keySet())
			{
				for (String[] verb : SKILLED)
				{
					if (key.endsWith(verb[0]) && !key.endsWith("Failed" + verb[0]))
					{
						skilled.putIfAbsent(letters(key.substring(0, key.length() - verb[0].length())), verb[1]);
						break;
					}
				}
			}
		}
		return skilled;
	}

	String named(String name)
	{
		if (ledgerNames == null)
		{
			ledgerNames = new HashMap<>();
			for (SourceRow r : build.sources())
			{
				ledgerNames.putIfAbsent(low(r.name), r.name);
			}
		}
		String low = low(name);
		String hit = ledgerNames.get(low);
		if (hit == null && low.endsWith("s"))
		{
			hit = ledgerNames.get(low.substring(0, low.length() - 1));
		}
		return hit;
	}

	String resolve(String name)
	{
		String named = named(name);
		if (named != null)
		{
			return named;
		}
		String kind = kindOf(name);
		SourceRow best = null;
		for (SourceRow r : build.sources())
		{
			if ((kindOf(r.name).equals(kind) || Kills.namesInBrackets(r.name, name))
				&& (best == null || r.value > best.value))
			{
				best = r;
			}
		}
		return best != null ? best.name : name;
	}

	static Skill skillOf(String name)
	{
		return skill(PAGE_SKILL.get(name));
	}

	static Skill skill(String name)
	{
		try
		{
			return name == null ? null : Skill.valueOf(name);
		}
		catch (IllegalArgumentException e)
		{
			return null;
		}
	}

	private static String letters(String s)
	{
		StringBuilder out = new StringBuilder();
		for (char c : s.toCharArray())
		{
			if (Character.isLetterOrDigit(c))
			{
				out.append(Character.toLowerCase(c));
			}
		}
		int end = out.length();
		return end > 1 && out.charAt(end - 1) == 's' ? out.substring(0, end - 1) : out.toString();
	}

	private static boolean containsAny(String low, List<String> words)
	{
		for (String w : words)
		{
			if (low.contains(w))
			{
				return true;
			}
		}
		return false;
	}
}
