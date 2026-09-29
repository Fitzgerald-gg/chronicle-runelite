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
import chronicle.Period.Span;
import chronicle.counters.StatKeys;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import net.runelite.client.hiscore.HiscoreSkill;
import net.runelite.client.hiscore.HiscoreSkillType;
import static chronicle.Json.getIgnoreCase;
import static chronicle.Json.keyOf;
import static chronicle.Json.low;
import static chronicle.Json.obj;
import static chronicle.Json.safeLong;
import static chronicle.Json.strMap;
import static chronicle.Json.strs;
import static chronicle.Json.table;
import static chronicle.LocalStore.kindOf;
import static chronicle.Period.dayOf;

@RequiredArgsConstructor
final class Kills
{
	private final Build build;

	void forgetWindow()
	{
		movedKcs = null;
		rolledKcs = null;
		rollUsed = false;
	}

	boolean rollUsed()
	{
		return rollUsed;
	}

	Map<String, Long> movedKcs()
	{
		return movedKcs;
	}

	@RequiredArgsConstructor
	static final class Boss
	{
		final String name;
		final int sprite;
	}

	private static List<Boss> bossRoster;

	private Map<String, Long> movedKcs;

	private Map<String, Long> rolledKcs;

	private boolean rollUsed;

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
		try (InputStream in = Kills.class.getResourceAsStream("osrs_bosses.json"))
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

	static final Map<String, List<String>> PAYS_OUT = new LinkedHashMap<>();

	static final Map<String, List<String>> FOUGHT_AS = new LinkedHashMap<>();

	static
	{
		obj(FIGHTS, "paysOut").entrySet().forEach(e -> PAYS_OUT.put(e.getKey(), strs(e.getValue())));
		obj(FIGHTS, "foughtAs").entrySet().forEach(e ->
			FOUGHT_AS.put(kindOf(e.getKey()), strs(e.getValue())));
	}

	private Map<String, Long> kcByKind;

	private Map<String, Long> kcByKind()
	{
		if (kcByKind != null)
		{
			return kcByKind;
		}
		Map<String, Long> out = new LinkedHashMap<>(chatKcByKind());
		for (SourceRow r : build.sources())
		{
			out.merge(kindOf(r.name), (long) r.kc, Math::max);
		}
		kcByKind = out;
		return out;
	}

	long bossKills(String name)
	{
		JsonObject cl = build.clog();
		long best = Math.max(0, lookup(cl, "slayer_kcs", name));
		String kind = kindOf(name);
		Long byKind = kcByKind().get(kind);
		if (byKind != null)
		{
			best = Math.max(best, byKind);
		}
		if (best > 0)
		{
			return best;
		}
		for (Entry<String, Long> ln : pageLines(name, "kc_lines"))
		{
			String said = low(ln.getKey());
			if (said.contains("kill") || said.contains("completion"))
			{
				return ln.getValue();
			}
		}
		String page = LOG_PAGE_FOR.getOrDefault(name, name);
		return Math.max(0, lookup(cl, "kcs", page));
	}

	private static long lookup(JsonObject clog, String map, String key)
	{
		if (clog == null)
		{
			return -1;
		}
		JsonElement v = getIgnoreCase(obj(clog, map), key);
		return v == null ? -1 : safeLong(v);
	}

	long bossKillsInWindow(String name)
	{
		if (build.period().whole)
		{
			return bossKills(name);
		}
		if (build.period().session)
		{
			rollUsed = true;
			return rolled(name);
		}
		Span s = build.span();
		if (s == null)
		{
			return -1;
		}
		if (movedKcs == null)
		{
			movedKcs = HistoryLog.gained(s.opening.kcs, s.earliest.kcs,
				build.period().closingNow(s.closing.kcs, build.plugin.killCounts()));
		}
		String key = keyOf(movedKcs.keySet(), name);
		Long moved = key == null ? null : movedKcs.get(key);
		if (moved != null)
		{
			return Math.max(0, moved);
		}
		Long rolled = rolledKills(name);
		if (rolled != null)
		{
			rollUsed = true;
			return rolled;
		}
		return -1;
	}

	long rolled(String name)
	{
		Long n = rolledKills(name);
		return n == null ? 0 : n;
	}

	private Long rolledKills(String name)
	{
		if (build.lootDays().rollFrom() <= 0)
		{
			return null;
		}
		if (rolledKcs == null)
		{
			rolledKcs = new LinkedHashMap<>();
			for (LocalStore.Row r : build.lootWindow().sources)
			{
				if (r.qty > 0)
				{
					rolledKcs.put(kindOf(r.name), r.qty);
				}
			}
		}
		return rolledKcs.get(kindOf(name));
	}

	LocalDate rollShortOf()
	{
		long from = build.lootDays().rollFrom();
		if (from <= 0)
		{
			return null;
		}
		Period w = build.period();
		LocalDate began = dayOf(from);
		return began.isAfter(w.start) ? began : null;
	}

	List<Entry<String, Long>> logLines(String boss)
	{
		List<Entry<String, Long>> out = pageLines(boss, "kc_lines");
		long kills = bossKills(boss);
		out.removeIf(ln ->
		{
			String said = low(ln.getKey());
			return ln.getValue() == kills
				&& (said.contains("kill") || said.contains("completion"));
		});
		return out;
	}

	List<Entry<String, Long>> pageLines(String boss, String map)
	{
		List<Entry<String, Long>> out = new ArrayList<>();
		JsonObject cl = build.clog();
		if (cl == null)
		{
			return out;
		}
		JsonElement found = getIgnoreCase(obj(cl, map), LOG_PAGE_FOR.getOrDefault(boss, boss));
		if (found == null || !found.isJsonObject())
		{
			return out;
		}
		for (Entry<String, JsonElement> ln
			: found.getAsJsonObject().entrySet())
		{
			long n = safeLong(ln.getValue());
			if (n > 0 && lineBelongsTo(boss, ln.getKey()))
			{
				out.add(new AbstractMap.SimpleEntry<>(ln.getKey(), n));
			}
		}
		return out;
	}

	boolean isKillSource(String name)
	{
		return killKinds().contains(kindOf(name))
			|| taskKillsEver().containsKey(name);
	}

	private Set<String> killKinds;

	private Set<String> killKinds()
	{
		if (killKinds != null)
		{
			return killKinds;
		}
		Set<String> out = new HashSet<>();
		for (Boss b : bossRoster(build.plugin.gson()))
		{
			out.add(kindOf(b.name));
		}
		JsonObject cl = build.clog();
		if (cl != null)
		{
			for (String said : obj(cl, "slayer_kcs").keySet())
			{
				out.add(kindOf(said));
			}
		}
		killKinds = out;
		return out;
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

	private boolean lineBelongsTo(String boss, String label)
	{
		if (label == null || label.isEmpty())
		{
			return false;
		}
		if (Character.isDigit(label.charAt(label.length() - 1)))
		{
			return false;
		}
		String said = low(label);
		String mine = bare(boss);
		String best = null;
		for (Boss b : bossRoster(build.plugin.gson()))
		{
			String name = bare(b.name);
			if (!name.isEmpty() && said.contains(name)
				&& (best == null || name.length() > best.length()))
			{
				best = name;
			}
		}
		if (best != null)
		{
			return best.equals(mine);
		}
		String page = LOG_PAGE_FOR.getOrDefault(boss, boss);
		if (!page.equalsIgnoreCase(boss))
		{
			return namesOneOf(said, words(mine, bare(page)));
		}
		for (Boss other : bossRoster(build.plugin.gson()))
		{
			String onPage = LOG_PAGE_FOR.getOrDefault(other.name, other.name);
			if (other.name.equalsIgnoreCase(boss) || !onPage.equalsIgnoreCase(page))
			{
				continue;
			}
			if (namesOneOf(said, words(bare(other.name), mine)))
			{
				return false;
			}
		}
		return true;
	}

	private static List<String> words(String name, String against)
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

	private static boolean namesOneOf(String said, List<String> words)
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

	private static String bare(String name)
	{
		String n = name == null ? "" : low(name.trim());
		return n.startsWith("the ") ? n.substring(4) : n;
	}

	static boolean paysOutThrough(String fight, String source)
	{
		return PAYS_OUT.getOrDefault(fight, Collections.emptyList()).stream().anyMatch(source::equalsIgnoreCase);
	}

	String bossLootSource(Boss b)
	{
		String kind = kindOf(b.name);
		for (SourceRow r : build.sources())
		{
			if (kindOf(r.name).equals(kind))
			{
				return r.name;
			}
		}
		SourceRow best = null;
		for (SourceRow r : build.sources())
		{
			if ((namesInBrackets(r.name, b.name) || paysOutThrough(b.name, r.name))
				&& (best == null || r.value > best.value))
			{
				best = r;
			}
		}
		return best != null ? best.name : b.name;
	}

	long standingKills(SourceRow sr)
	{
		long own = sr.kc > 0 ? sr.kc : sr.loots;
		Long said = chatKcByKind().get(LocalStore.chatKind(sr.name));
		return said == null ? own : Math.max(own, said);
	}

	private Map<String, Long> chatKcByKind;

	private Map<String, Long> chatKcByKind()
	{
		if (chatKcByKind == null)
		{
			Map<String, Long> out = new LinkedHashMap<>();
			for (Entry<String, Long> e : build.plugin.killCounts().entrySet())
			{
				out.merge(LocalStore.chatKind(e.getKey()), e.getValue(), Math::max);
			}
			chatKcByKind = out;
		}
		return chatKcByKind;
	}

	Map<String, Long> sayers(SourceRow sr)
	{
		String want = LocalStore.chatKind(sr.name);
		Map<String, Long> rows = new LinkedHashMap<>();
		putKind(rows, "Kill Log", LocalStore.killLogCounts(build.clog()), want);
		putKind(rows, "Said in chat", build.plugin.chatKills(), want);
		putKind(rows, "Collection log", LocalStore.pageKillLines(build.clog()), want);
		putKind(rows, "Running count", build.plugin.anchoredKills(), want);
		if (sr.loots > 0)
		{
			rows.put("Drops logged", (long) sr.loots);
		}
		Long dropped = taskKillsEver().get(sr.name);
		if (dropped != null && dropped > 0)
		{
			rows.put("Dropped on task", dropped);
		}
		return rows;
	}

	private static void putKind(Map<String, Long> rows, String label,
		Map<String, Long> from, String want)
	{
		for (Entry<String, Long> e : from.entrySet())
		{
			if (LocalStore.chatKind(e.getKey()).equals(want) && e.getValue() > 0)
			{
				rows.put(label, e.getValue());
				return;
			}
		}
	}

	private Map<String, Long> taskKillsEver;

	Map<String, Long> taskKillsEver()
	{
		if (taskKillsEver == null)
		{
			taskKillsEver = build.plugin.allTasks().kills();
		}
		return taskKillsEver;
	}

	boolean minutesCoverPeriod()
	{
		if (build.period().whole)
		{
			return false;
		}
		if (build.period().session)
		{
			return true;
		}
		Span s = build.span();
		if (s == null)
		{
			return false;
		}
		for (String key : s.opening.counters.keySet())
		{
			if (StatKeys.isTime(key))
			{
				return true;
			}
		}
		return false;
	}

	long minutesAt(String name, Map<String, Long> counters)
	{
		long minutes = counters.getOrDefault(StatKeys.timeKey(name), 0L);
		for (String npc : FOUGHT_AS.getOrDefault(kindOf(name),
			Collections.emptyList()))
		{
			minutes += counters.getOrDefault(StatKeys.timeKey(npc), 0L);
		}
		return minutes;
	}

	long namedLine(String source, String label)
	{
		for (Entry<String, Long> ln : pageLines(source, "kc_lines"))
		{
			if (ln.getKey().equalsIgnoreCase(label))
			{
				return ln.getValue();
			}
		}
		return 0;
	}
}
