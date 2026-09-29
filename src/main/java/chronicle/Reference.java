/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import chronicle.counters.Tables;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.Map;
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
	static List<Boss> bossRoster;
	private static final JsonObject FIGHTS = Tables.load("panel_fights.json");
	static final Map<String, String> LOG_PAGE_FOR = strMap(FIGHTS, "logPage");
	private static final Map<String, List<String>> PAYS_OUT = lists(obj(FIGHTS, "paysOut"), false);
	static final Map<String, List<String>> FOUGHT_AS = lists(obj(FIGHTS, "foughtAs"), true);
	static final JsonObject KINDS = Tables.load("panel_kinds.json");
	static final Set<String> PICKPOCKETED = new HashSet<>(strs(KINDS.get("pickpocketed")));
	static final Set<String> MONSTER_PAGES = new HashSet<>(strs(KINDS.get("monsterPages")));
	static final List<String> OPENED = strs(KINDS.get("opened"));
	static final List<String> GATHERED = strs(KINDS.get("gathered"));
	static final Map<String, String> PAGE_SKILL = strMap(KINDS, "pageSkills");
	private static final List<String> SKILL_ORDER_NAMES = strs(KINDS.get("skillOrder"));
	static final List<Skill> SKILLS = Arrays.asList(Skill.values());
	static final List<String> SKILL_KEYS = SKILLS.stream().map(sk -> low(sk.name())).collect(Collectors.toList());
	static Map<String, Map<String, List<String>>> taxonomy;
	static Set<String> sharedSlotNames;
	private static final JsonObject COMBAT_BUNDLE = Tables.load("osrs_combat_achievements.json");
	static final JsonObject CA_TASKS = obj(COMBAT_BUNDLE, "tasks");
	static final long CA_POINTS = asLong(obj(obj(COMBAT_BUNDLE, "_meta"), "totals").get("points"));
	static final JsonObject DIARY_TASKS = obj(Tables.load("osrs_achievement_diaries.json"), "diaries");

	static final String[] CLUE_TIERS = {
		"Beginner", "Easy", "Medium", "Hard", "Elite", "Master"};

	static final Skill[] COMBAT_SKILLS = {
		Skill.ATTACK, Skill.STRENGTH,
		Skill.DEFENCE, Skill.HITPOINTS,
		Skill.RANGED, Skill.MAGIC,
		Skill.PRAYER};

	static final Map<String, String[]> SKILL_ALIASES = Map.of("runecraft", new String[]{"runecrafting", "rc"},
		"hitpoints", new String[]{"hp"},
		"woodcutting", new String[]{"wc"},
		"firemaking", new String[]{"fm"},
		"construction", new String[]{"con"});

	static boolean paysOutThrough(String fight, String source)
	{
		return PAYS_OUT.getOrDefault(fight, Collections.emptyList()).stream().anyMatch(source::equalsIgnoreCase);
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

	private Reference()
	{
	}

	@RequiredArgsConstructor
	static final class Boss
	{
		final String name;
		final int sprite;
	}

	static synchronized List<Boss> bossRoster()
	{
		if (bossRoster != null)
		{
			return bossRoster;
		}
		List<Boss> out = new ArrayList<>();
		try
		{
			for (HiscoreSkill s : HiscoreSkill.values())
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
		for (JsonElement e : Tables.array("osrs_bosses.json"))
		{
			JsonObject o = e.getAsJsonObject();
			out.add(new Boss(o.get("name").getAsString(), o.has("sprite") ? o.get("sprite").getAsInt() : -1));
		}
		bossRoster = out;
		return out;
	}

	private static Map<String, List<String>> lists(JsonObject o, boolean byKind)
	{
		Map<String, List<String>> out = new LinkedHashMap<>();
		o.entrySet().forEach(e -> out.put(byKind ? kindOf(e.getKey()) : e.getKey(), strs(e.getValue())));
		return out;
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

	static List<Skill> skillOrder()
	{
		List<Skill> out = new ArrayList<>();
		SKILL_ORDER_NAMES.stream().filter(name -> skill(name) != null).forEach(name -> out.add(skill(name)));
		SKILLS.stream().filter(sk -> !out.contains(sk)).forEach(out::add);
		return out;
	}

	static synchronized Map<String, Map<String, List<String>>> taxonomy()
	{
		if (taxonomy != null)
		{
			return taxonomy;
		}
		Map<String, Map<String, List<String>>> out = new LinkedHashMap<>();
		for (Entry<String, JsonElement> tab : Tables.load("clog_taxonomy.json").entrySet())
		{
			Map<String, List<String>> pages = new LinkedHashMap<>();
			for (Entry<String, JsonElement> pg : tab.getValue().getAsJsonObject().entrySet())
			{
				List<String> slots = new ArrayList<>();
				pg.getValue().getAsJsonArray().forEach(it -> slots.add(it.getAsString()));
				pages.put(pg.getKey(), slots);
			}
			out.put(tab.getKey(), pages);
		}
		taxonomy = out;
		return out;
	}

	static synchronized Set<String> sharedSlotNames()
	{
		if (sharedSlotNames != null)
		{
			return sharedSlotNames;
		}
		Map<String, Integer> homes = new LinkedHashMap<>();
		for (Entry<String, Map<String, List<String>>> tab : taxonomy().entrySet())
		{
			for (Entry<String, List<String>> pg : tab.getValue().entrySet())
			{
				Set<String> onThisPage = new HashSet<>();
				pg.getValue().forEach(slot -> onThisPage.add(low(slot)));
				onThisPage.forEach(slot -> homes.merge(slot, 1, Integer::sum));
			}
		}
		Set<String> shared = new HashSet<>();
		homes.entrySet().stream().filter(e -> e.getValue() > 1).forEach(e -> shared.add(e.getKey()));
		sharedSlotNames = shared;
		return shared;
	}

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
		return Experience.getCombatLevel(lv[0], lv[1], lv[2], lv[3], lv[5], lv[4], lv[6]);
	}

	static Integer combatOf(Map<String, Integer> levels)
	{
		HistoryLog.Levels l = new HistoryLog.Levels();
		l.of.putAll(levels);
		return openingCombat(l);
	}

	static boolean namesInBrackets(String source, String boss)
	{
		int open = source.lastIndexOf('(');
		int close = source.lastIndexOf(')');
		return open > 0 && close > open && kindOf(source.substring(open + 1, close))
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
		Arrays.stream(name.split("\\s+")).filter(w -> w.length() > 3 && !against.contains(w)).forEach(out::add);
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
}
