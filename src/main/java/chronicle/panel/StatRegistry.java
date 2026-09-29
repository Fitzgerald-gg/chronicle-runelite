/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle.panel;

import chronicle.counters.Tables;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.AllArgsConstructor;

public final class StatRegistry
{
	public static final String[] FAMILIES = {
		"Living", "Combat", "Skilling", "Ledger & Roads"
	};

	static final JsonObject TABLES = Tables.load("stat_registry.json");

	private static final Set<String> COMBAT = Tables.set(TABLES, "combat");
	private static final String THRALL_SUFFIX = "ThrallsSummoned";
	private static final Set<String> LIVING_FLAT = Tables.set(TABLES, "livingFlat");
	private static final Set<String> LEDGER = Tables.set(TABLES, "ledger");
	private static final Set<String> HIDE = Tables.set(TABLES, "hide");
	private static final Set<String> SUMMARY = Tables.set(TABLES, "summary");
	private static final Set<String> PEAK = Tables.set(TABLES, "peak");

	@AllArgsConstructor
	private static final class SkillSpec
	{
		final String name;
		final String[] suffixes;
		final String[] floors;
		final String[] keys;
	}

	private static final List<SkillSpec> SKILLS = new ArrayList<>();

	private static final Map<String, String> KEY_SKILL = new HashMap<>();
	private static final Set<String> FLOORS = new HashSet<>();

	static
	{
		for (JsonElement e : TABLES.getAsJsonArray("skills"))
		{
			JsonArray a = e.getAsJsonArray();
			SkillSpec s = new SkillSpec(a.get(0).getAsString(), Tables.strings(a.get(1)),
				Tables.strings(a.get(2)), Tables.strings(a.get(3)));
			SKILLS.add(s);
			for (String k : s.keys)
			{
				KEY_SKILL.put(k, s.name);
			}
			for (String k : s.floors)
			{
				KEY_SKILL.put(k, s.name);
				FLOORS.add(k);
			}
		}
		FLOORS.add("teleportsTotal");
		FLOORS.add("teleports");
		FLOORS.add("thrallsSummoned");
	}

	private static final Map<String, String> LABELS = Tables.map(TABLES, "labels");
	private static final Map<String, String> TELE_NAMES = Tables.map(TABLES, "teleNames");
	private static final Map<String, String> SUFFIX_LABELS = Tables.map(TABLES, "suffixLabels");
	private static final Map<String, String> SUFFIX_FLOORS = Tables.map(TABLES, "suffixFloors");

	private StatRegistry()
	{
	}

	public static boolean hidden(String key)
	{
		return key.startsWith("_") || HIDE.contains(key) || SUMMARY.contains(key)
			|| chronicle.counters.StatKeys.isTime(key);
	}

	public static Set<String> peakKeys()
	{
		return Collections.unmodifiableSet(PEAK);
	}

	public static boolean isFloor(String key)
	{
		return FLOORS.contains(key);
	}

	private static boolean thrallTyped(String key)
	{
		return key.endsWith(THRALL_SUFFIX) && !key.equals(THRALL_SUFFIX);
	}

	public static String skillOf(String key)
	{
		String claimed = KEY_SKILL.get(key);
		if (claimed != null)
		{
			return claimed;
		}
		if (isGp(key) || COMBAT.contains(key))
		{
			return null;
		}
		String[] hit = matchedSuffix(key);
		return hit != null ? hit[0] : null;
	}

	private static String[] matchedSuffix(String key)
	{
		for (SkillSpec s : SKILLS)
		{
			for (String suf : s.suffixes)
			{
				if (key.endsWith(suf) && !key.equals(suf))
				{
					return new String[]{s.name, suf};
				}
			}
		}
		return null;
	}

	public static String family(String key)
	{
		if (LIVING_FLAT.contains(key))
		{
			return "Living";
		}
		if (COMBAT.contains(key) || thrallTyped(key))
		{
			return "Combat";
		}
		if (LEDGER.contains(key) || isGp(key))
		{
			return "Ledger & Roads";
		}
		if (skillOf(key) != null)
		{
			return "Skilling";
		}
		if (key.endsWith("Eaten") || key.endsWith("Doses") || key.contains("Drunk")
			|| key.startsWith("food") || key.startsWith("potion") || key.startsWith("vials"))
		{
			return "Living";
		}
		return "Ledger & Roads";
	}

	public static List<String> headlines(String skill)
	{
		for (SkillSpec s : SKILLS)
		{
			if (!s.name.equalsIgnoreCase(skill))
			{
				continue;
			}
			List<String> out = new ArrayList<>(Arrays.asList(s.floors));
			out.addAll(Arrays.asList(s.keys));
			return out;
		}
		return Collections.emptyList();
	}

	public static String subgroup(String key)
	{
		switch (family(key))
		{
			case "Skilling":
				return skillOf(key);
			case "Combat":
				return key.equals("thrallsSummoned") || thrallTyped(key) ? "Thralls" : "";
			case "Living":
				return LIVING_FLAT.contains(key) ? "" : key.endsWith("Eaten") ? "Food"
					: key.endsWith("Doses") ? "Potions" : "";
			default:
				if (key.startsWith("teleportsVia") || key.equals("teleportsTotal") || key.equals("teleports")
					|| key.equals("teleportsFairyRing") || key.equals("teleportsSpiritTree"))
				{
					return "Teleports";
				}
				if (key.startsWith("teleports"))
				{
					return "Destinations";
				}
				if (key.startsWith("tiles") || key.startsWith("distance"))
				{
					return "On foot";
				}
				return isGp(key) ? "The purse" : "Odds & ends";
		}
	}

	public static String headOf(String family, String key)
	{
		for (String sec : fixedSections(family))
		{
			if (!sec.isEmpty() && floorKeys(sec).contains(key))
			{
				return sec;
			}
		}
		return null;
	}

	public static List<String> floorKeys(String subgroup)
	{
		SkillSpec s = spec(subgroup);
		if (s != null)
		{
			return Arrays.asList(s.floors);
		}
		switch (subgroup)
		{
			case "Teleports":
				return Arrays.asList("teleportsTotal", "teleports");
			case "Food":
				return Collections.singletonList("foodEaten");
			case "Potions":
				return Collections.singletonList("potionDoses");
			case "Thralls":
				return Collections.singletonList("thrallsSummoned");
			default:
				return Collections.emptyList();
		}
	}

	public static String label(String key)
	{
		String explicit = LABELS.get(key);
		if (explicit != null)
		{
			return explicit;
		}
		if (key.startsWith("teleportsVia"))
		{
			return "· by " + key.substring("teleportsVia".length()).toLowerCase(Locale.ROOT);
		}
		if (key.equals("teleportsTotal") || key.equals("teleports"))
		{
			return "Teleports";
		}
		if (key.startsWith("teleports") && key.length() > "teleports".length())
		{
			return teleName(key);
		}
		return prettify(key);
	}

	public static String rowLabel(String key)
	{
		String verb = suffixOf(key);
		if (verb != null)
		{
			return typedName(key, verb);
		}
		if (livingTyped(key))
		{
			return typedName(key, key.endsWith("Eaten") ? "Eaten" : "Doses");
		}
		return thrallTyped(key) ? typedName(key, THRALL_SUFFIX) : label(key);
	}

	private static String teleName(String key)
	{
		String fixed = TELE_NAMES.get(key);
		if (fixed != null)
		{
			return fixed;
		}
		String s = prettify(key.substring("teleports".length()));
		StringBuilder out = new StringBuilder(s.length());
		boolean cap = true;
		for (char c : s.toCharArray())
		{
			out.append(cap && Character.isLetter(c) ? Character.toUpperCase(c) : c);
			cap = c == ' ';
		}
		return out.toString();
	}

	private static String typedName(String key, String suffix)
	{
		String base = key.substring(0, key.length() - suffix.length());
		String s = prettify(base).replaceAll("(?i)\\(?level\\s*\\d+\\)?", " ")
			.replaceAll("[()]", " ").replaceAll("\\s+", " ").trim();
		return s.isEmpty() ? label(key) : s;
	}

	public static String suffixOf(String key)
	{
		if (KEY_SKILL.containsKey(key) || skillOf(key) == null)
		{
			return null;
		}
		String[] hit = matchedSuffix(key);
		return hit != null ? hit[1] : null;
	}

	public static String suffixLabel(String suffix)
	{
		String fixed = SUFFIX_LABELS.get(suffix);
		return fixed != null ? fixed : prettify(Character.toLowerCase(suffix.charAt(0)) + suffix.substring(1));
	}

	public static String suffixFloor(String craft, String suffix)
	{
		String cand = SUFFIX_FLOORS.getOrDefault(suffix, Character.toLowerCase(suffix.charAt(0)) + suffix.substring(1));
		SkillSpec s = spec(craft);
		return s != null && Arrays.asList(s.floors).contains(cand) ? cand : null;
	}

	private static SkillSpec spec(String name)
	{
		for (SkillSpec s : SKILLS)
		{
			if (s.name.equals(name))
			{
				return s;
			}
		}
		return null;
	}

	public static boolean typed(String key)
	{
		return !KEY_SKILL.containsKey(key) && (suffixOf(key) != null || thrallTyped(key) || livingTyped(key));
	}

	private static boolean livingTyped(String key)
	{
		return family(key).equals("Living") && !LIVING_FLAT.contains(key)
			&& (key.endsWith("Eaten") || key.endsWith("Doses"));
	}

	public static boolean isGp(String key)
	{
		return key.endsWith("Value") || key.startsWith("coins");
	}

	public static String prettify(String key)
	{
		StringBuilder out = new StringBuilder(key.length() + 8);
		for (int i = 0; i < key.length(); i++)
		{
			char c = key.charAt(i);
			if (i == 0)
			{
				out.append(Character.toUpperCase(c));
			}
			else if (Character.isUpperCase(c))
			{
				out.append(' ').append(Character.toLowerCase(c));
			}
			else
			{
				out.append(c);
			}
		}
		return polish(out.toString());
	}

	private static String polish(String label)
	{
		String s = label.replaceAll("(?<=\\S)\\(", " (")
			.replaceAll("\\(level ?(\\d+)\\)", "(lvl $1)");
		String[] words = s.split(" ");
		StringBuilder out = new StringBuilder(s.length());
		String prev = null;
		for (String w : words)
		{
			if (prev != null && w.equalsIgnoreCase(prev))
			{
				continue;
			}
			if (out.length() > 0)
			{
				out.append(' ');
			}
			out.append(w);
			prev = w;
		}
		return out.toString();
	}

	public static int compareRows(Map.Entry<String, Long> a, Map.Entry<String, Long> b)
	{
		int byValue = Long.compare(b.getValue(), a.getValue());
		return byValue != 0 ? byValue : rowLabel(a.getKey()).compareToIgnoreCase(rowLabel(b.getKey()));
	}

	public static List<String> fixedSections(String family)
	{
		switch (family)
		{
			case "Living":
				return Arrays.asList("", "Food", "Potions");
			case "Combat":
				return Arrays.asList("", "Thralls");
			case "Ledger & Roads":
				return Arrays.asList("The purse", "On foot", "Teleports", "Destinations", "Odds & ends");
			default:
				return new ArrayList<>(Collections.singletonList(""));
		}
	}
}
