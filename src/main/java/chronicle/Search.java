/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the
 * BSD 2-Clause License (see LICENSE) are met.
 */
package chronicle;

import chronicle.LocalStore.BagItem;
import chronicle.LocalStore.SourceRow;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import net.runelite.api.Skill;
import static chronicle.Feed.filedAt;
import static chronicle.Feed.newestTs;
import static chronicle.Json.low;
import static chronicle.Json.obj;
import static chronicle.panel.StatRegistry.prettify;

@RequiredArgsConstructor
final class Search
{
	static final int JOURNAL_DEEP = 20_000;
	static final Map<String, String[]> SKILL_ALIASES = new LinkedHashMap<>();
	private static final Pattern NEAR_WORDS = Pattern.compile("[^a-z0-9']+");
	private static final Pattern NAME_WORDS = Pattern.compile("[^a-z0-9]+");
	private static final Pattern QUERY_WORDS = Pattern.compile("\\s+");

	static
	{
		SKILL_ALIASES.put("runecraft", new String[]{"runecrafting", "rc"});
		SKILL_ALIASES.put("hitpoints", new String[]{"hp"});
		SKILL_ALIASES.put("woodcutting", new String[]{"wc"});
		SKILL_ALIASES.put("firemaking", new String[]{"fm"});
		SKILL_ALIASES.put("construction", new String[]{"con"});
	}

	@RequiredArgsConstructor
	static final class Line
	{
		final String key;
		final String text;
		final long at;
	}

	private final LocalStore store;
	private List<Line> feed;
	private long feedTs = -1;
	private Object feedSpine;

	void forget()
	{
		feed = null;
		feedSpine = null;
	}

	List<Line> feed(Build build)
	{
		long newest = newestTs(store.feedNewest(1));
		if (feed == null || newest != feedTs || build.spine() != feedSpine)
		{
			List<Line> out = new ArrayList<>();
			List<JsonObject> all = new ArrayList<>(store.feedNewest(JOURNAL_DEEP));
			all.addAll(build.feed.milestones());
			for (JsonObject e : all)
			{
				String line = Feed.line(e);
				if (line != null && !line.isEmpty())
				{
					out.add(new Line(low(line).replace("'", ""), line, filedAt(e)));
				}
			}
			feed = out;
			feedTs = newest;
			feedSpine = build.spine();
		}
		return feed;
	}

	static String nearest(Build build, String ql)
	{
		if (ql.length() < 4)
		{
			return null;
		}
		List<String> names = new ArrayList<>();
		for (SourceRow r : build.sources())
		{
			names.add(r.name);
			for (BagItem b : build.store.sourceItems(r.name))
			{
				names.add(b.name);
			}
		}
		for (Map<String, List<String>> tab : ClogBook.taxonomy(build.gson).values())
		{
			names.addAll(tab.keySet());
			for (List<String> slots : tab.values())
			{
				names.addAll(slots);
			}
		}
		for (Kills.Boss b : Kills.bossRoster(build.gson))
		{
			names.add(b.name);
		}
		JsonObject cl = build.clog();
		names.addAll(obj(cl, "slayer_kcs").keySet());
		names.addAll(obj(build.standings.achievements(), "quests").keySet());
		for (Skill sk : Sources.skillOrder())
		{
			names.add(prettify(low(sk.name())));
		}
		names.addAll(Arrays.asList("Quests", "Collection log", "Achievement diaries",
			"Combat achievements", "Clues", "Records", "Calendar", "Recap", "All trackers",
			"Kill log", "Left behind"));
		String best = null;
		int nearest = 3;
		for (String name : names)
		{
			String low = low(name);
			int d = editsBetween(ql, low, nearest);
			for (String word : NEAR_WORDS.split(low))
			{
				if (word.length() >= 4)
				{
					d = Math.min(d, editsBetween(ql, word, nearest));
				}
			}
			if (d < nearest)
			{
				nearest = d;
				best = name;
			}
		}
		return best;
	}

	static int matchScore(String ql, String name)
	{
		if (name == null || ql.isEmpty())
		{
			return -1;
		}
		String raw = low(name);
		if (raw.equals(ql) || (ql.endsWith("s") && raw.equals(ql.substring(0, ql.length() - 1)))
			|| (raw.endsWith("s") && raw.substring(0, raw.length() - 1).equals(ql)))
		{
			return 0;
		}
		String n = raw.replace("'", "");
		String q = ql.replace("'", "");
		if (q.trim().isEmpty())
		{
			return -1;
		}
		if (n.equals(q))
		{
			return 0;
		}
		if (n.startsWith(q))
		{
			return 1;
		}
		String[] words = NAME_WORDS.split(n);
		boolean every = true;
		for (String w : QUERY_WORDS.split(q))
		{
			if (w.isEmpty())
			{
				continue;
			}
			boolean begins = false;
			for (String x : words)
			{
				begins |= x.startsWith(w);
			}
			every &= begins;
		}
		if (every || initials(q, words))
		{
			return 2;
		}
		return n.contains(q) ? 3 : -1;
	}

	private static boolean initials(String q, String[] words)
	{
		if (q.length() < 2 || q.indexOf(' ') >= 0 || words.length < 2)
		{
			return false;
		}
		StringBuilder all = new StringBuilder();
		StringBuilder lean = new StringBuilder();
		for (String w : words)
		{
			if (w.isEmpty())
			{
				continue;
			}
			all.append(w.charAt(0));
			if (!w.equals("the") && !w.equals("of"))
			{
				lean.append(w.charAt(0));
			}
		}
		return q.contentEquals(all) || q.contentEquals(lean);
	}

	static int bestScore(String ql, String... names)
	{
		int best = -1;
		for (String n : names)
		{
			int s = matchScore(ql, n);
			if (s >= 0 && (best < 0 || s < best))
			{
				best = s;
			}
		}
		return best;
	}

	private static int editsBetween(String a, String b, int cap)
	{
		if (Math.abs(a.length() - b.length()) >= cap)
		{
			return cap;
		}
		int[] prev = new int[b.length() + 1];
		int[] cur = new int[b.length() + 1];
		for (int j = 0; j <= b.length(); j++)
		{
			prev[j] = j;
		}
		for (int i = 1; i <= a.length(); i++)
		{
			cur[0] = i;
			int rowMin = i;
			for (int j = 1; j <= b.length(); j++)
			{
				int swap = prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
				cur[j] = Math.min(swap, Math.min(prev[j] + 1, cur[j - 1] + 1));
				rowMin = Math.min(rowMin, cur[j]);
			}
			if (rowMin >= cap)
			{
				return cap;
			}
			int[] t = prev;
			prev = cur;
			cur = t;
		}
		return Math.min(cap, prev[b.length()]);
	}

	static String firstSentence(String task)
	{
		int note = task.indexOf(" Note:");
		String s = note > 0 ? task.substring(0, note) : task;
		for (int stop = s.indexOf(". "); stop > 0; stop = s.indexOf(". ", stop + 1))
		{
			if (Character.isLowerCase(s.charAt(stop - 1)) && stop + 2 < s.length()
				&& Character.isUpperCase(s.charAt(stop + 2)))
			{
				return s.substring(0, stop + 1);
			}
		}
		return s;
	}
}
