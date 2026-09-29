/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import net.runelite.api.Experience;
import net.runelite.api.Skill;
import net.runelite.client.hiscore.HiscoreSkill;
import net.runelite.client.hiscore.HiscoreSkillType;
import static chronicle.Json.*;
import static chronicle.KillCounts.kindOf;
import static chronicle.Ui.*;

final class Reference
{
	private Reference()
	{
	}

	@RequiredArgsConstructor
	static final class Boss
	{
		final String name;
		final int sprite;
	}

	static List<Boss> bossRoster;

	static synchronized List<Boss> bossRoster(Gson gson)
	{
		if (bossRoster != null)
		{
			return bossRoster;
		}
		List<Boss> out = new ArrayList<>();
		try
		{
			for (HiscoreSkill s
				: HiscoreSkill.values())
			{
				if (s.getType() == HiscoreSkillType.BOSS)
				{
					out.add(new Boss(s.getName(), s.getSpriteId()));
				}
			}
		}
		catch (RuntimeException | LinkageError ex)
		{
			out.clear();
		}
		if (!out.isEmpty())
		{
			bossRoster = out;
			return out;
		}
		try (InputStream in = ChroniclePanel.class.getResourceAsStream("osrs_bosses.json"))
		{
			if (in != null)
			{
				JsonArray arr = gson.fromJson(
					new InputStreamReader(in, StandardCharsets.UTF_8),
					JsonArray.class);
				for (JsonElement e : arr)
				{
					JsonObject o = e.getAsJsonObject();
					out.add(new Boss(o.get("name").getAsString(),
						o.has("sprite") ? o.get("sprite").getAsInt() : -1));
				}
			}
		}
		catch (Exception ex)
		{
		}
		bossRoster = out;
		return out;
	}

	static final JsonObject FIGHTS = table("panel_fights.json");

	static final Map<String, String> LOG_PAGE_FOR = strMap(FIGHTS, "logPage");

	static final Map<String, List<String>> PAYS_OUT = lists(obj(FIGHTS, "paysOut"), false);

	static final Map<String, List<String>> FOUGHT_AS = lists(obj(FIGHTS, "foughtAs"), true);

	static Map<String, List<String>> lists(JsonObject o, boolean byKind)
	{
		Map<String, List<String>> out = new LinkedHashMap<>();
		o.entrySet().forEach(e -> out.put(byKind ? kindOf(e.getKey()) : e.getKey(), strs(e.getValue())));
		return out;
	}

	static final JsonObject KINDS = table("panel_kinds.json");

	static final Set<String> PICKPOCKETED = new HashSet<>(strs(KINDS.get("pickpocketed")));

	static final Set<String> MONSTER_PAGES = new HashSet<>(strs(KINDS.get("monsterPages")));

	static final List<String> OPENED = strs(KINDS.get("opened"));

	static final List<String> GATHERED = strs(KINDS.get("gathered"));

	static final Map<String, String> PAGE_SKILL = strMap(KINDS, "pageSkills");

	static final List<String> SKILL_ORDER_NAMES = strs(KINDS.get("skillOrder"));

	static final List<Skill> SKILLS = Arrays.asList(Skill.values());

	static final List<String> SKILL_KEYS = SKILLS.stream().map(sk -> low(sk.name())).collect(Collectors.toList());

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

	static List<Skill> skillOrder()
	{
		List<Skill> out = new ArrayList<>();
		for (String name : SKILL_ORDER_NAMES)
		{
			if (skill(name) != null)
			{
				out.add(skill(name));
			}
		}
		for (Skill sk : SKILLS)
		{
			if (!out.contains(sk))
			{
				out.add(sk);
			}
		}
		return out;
	}

	static Map<String, Map<String, List<String>>> taxonomy;

	static synchronized Map<String, Map<String, List<String>>> taxonomy(
		Gson gson)
	{
		if (taxonomy != null)
		{
			return taxonomy;
		}
		Map<String, Map<String, List<String>>> out = new LinkedHashMap<>();
		try (InputStream in = ChroniclePanel.class.getResourceAsStream("clog_taxonomy.json"))
		{
			if (in != null)
			{
				JsonObject rootTax = gson.fromJson(
					new InputStreamReader(in, StandardCharsets.UTF_8),
					JsonObject.class);
				for (Entry<String, JsonElement> tab : rootTax.entrySet())
				{
					Map<String, List<String>> pages = new LinkedHashMap<>();
					for (Entry<String, JsonElement> pg
						: tab.getValue().getAsJsonObject().entrySet())
					{
						List<String> slots = new ArrayList<>();
						for (JsonElement it : pg.getValue().getAsJsonArray())
						{
							slots.add(it.getAsString());
						}
						pages.put(pg.getKey(), slots);
					}
					out.put(tab.getKey(), pages);
				}
			}
		}
		catch (Exception e)
		{
		}
		taxonomy = out;
		return out;
	}

	static Set<String> sharedSlotNames;

	static synchronized Set<String> sharedSlotNames(
		Gson gson)
	{
		if (sharedSlotNames != null)
		{
			return sharedSlotNames;
		}
		Map<String, Integer> homes = new LinkedHashMap<>();
		for (Entry<String, Map<String, List<String>>> tab : taxonomy(gson).entrySet())
		{
			for (Entry<String, List<String>> pg : tab.getValue().entrySet())
			{
				Set<String> onThisPage = new HashSet<>();
				for (String slot : pg.getValue())
				{
					onThisPage.add(low(slot));
				}
				for (String slot : onThisPage)
				{
					homes.merge(slot, 1, Integer::sum);
				}
			}
		}
		Set<String> shared = new HashSet<>();
		for (Entry<String, Integer> e : homes.entrySet())
		{
			if (e.getValue() > 1)
			{
				shared.add(e.getKey());
			}
		}
		sharedSlotNames = shared;
		return shared;
	}

	static final JsonObject COMBAT_BUNDLE = table("osrs_combat_achievements.json");

	static final JsonObject CA_TASKS = obj(COMBAT_BUNDLE, "tasks");

	static final long CA_POINTS = asLong(obj(obj(COMBAT_BUNDLE, "_meta"), "totals").get("points"));

	static final JsonObject DIARY_TASKS = obj(table("osrs_achievement_diaries.json"), "diaries");

	static final String[] CLUE_TIERS = {
		"Beginner", "Easy", "Medium", "Hard", "Elite", "Master"};

	static final Skill[] COMBAT_SKILLS = {
		Skill.ATTACK, Skill.STRENGTH,
		Skill.DEFENCE, Skill.HITPOINTS,
		Skill.RANGED, Skill.MAGIC,
		Skill.PRAYER};

	static Integer openingCombat(HistoryLog.Levels opened)
	{
		if (opened == null || opened.of == null)
		{
			return null;
		}
		int[] lv = new int[COMBAT_SKILLS.length];
		for (int i = 0; i < COMBAT_SKILLS.length; i++)
		{
			Integer l = opened.of.get(low(COMBAT_SKILLS[i].name()));
			if (l == null || l <= 0)
			{
				return null;
			}
			lv[i] = l;
		}
		return Experience.getCombatLevel(lv[0], lv[1], lv[2], lv[3],
			lv[5], lv[4], lv[6]);
	}

	static Integer combatOf(Map<String, Integer> levels)
	{
		HistoryLog.Levels l = new HistoryLog.Levels();
		l.of.putAll(levels);
		return openingCombat(l);
	}

	static final Map<String, String[]> SKILL_ALIASES = Map.of(
		"runecraft", new String[]{"runecrafting", "rc"},
		"hitpoints", new String[]{"hp"},
		"woodcutting", new String[]{"wc"},
		"firemaking", new String[]{"fm"},
		"construction", new String[]{"con"});

	static boolean paysOutThrough(String fight, String source)
	{
		return PAYS_OUT.getOrDefault(fight, Collections.emptyList()).stream().anyMatch(source::equalsIgnoreCase);
	}

	static boolean namesInBrackets(String source, String boss)
	{
		int open = source.lastIndexOf('(');
		int close = source.lastIndexOf(')');
		return open > 0 && close > open
			&& kindOf(source.substring(open + 1, close))
				.equals(kindOf(boss));
	}

	static String beforeBracket(String source)
	{
		int open = source.lastIndexOf('(');
		return open > 0 ? source.substring(0, open).trim() : source;
	}

	static String bare(String name)
	{
		String n = name == null ? "" : low(name.trim());
		return n.startsWith("the ") ? n.substring(4) : n;
	}

	static List<String> words(String name, String against)
	{
		List<String> out = new ArrayList<>();
		for (String w : name.split("\\s+"))
		{
			if (w.length() > 3 && !against.contains(w))
			{
				out.add(w);
			}
		}
		return out;
	}

	static boolean namesOneOf(String said, List<String> words)
	{
		for (String w : words)
		{
			if (said.contains(w))
			{
				return true;
			}
		}
		return false;
	}

	static boolean containsAny(String low, List<String> words)
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

	static String letters(String s)
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

	static final String[][] SKILLED = {
		{"Pickpockets", "THIEVING"}, {"Trapped", "HUNTER"},
		{"Caught", "HUNTER"}, {"Harvested", "HUNTER"},
	};

	static final String KIND_BOSS = "Bosses";

	static final String KIND_ACTIVITY = "Activities";

	static final String KIND_SKILLING = "Skilling";

	static final String KIND_MONSTER = "Monsters";

	static final String[][] ACTIVITIES = {
		{"Clues", "", "clues"},
		{"Rifts closed", "Guardians of the Rift", ""},
		{"Soul Wars", "Soul Wars", ""},
		{"Collections", "", "log"},
		{"Quests", "", "quests"},
		{"Diaries", "", "diaries"},
	};
}
