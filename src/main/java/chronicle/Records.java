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
import com.google.gson.JsonObject;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import static chronicle.Feed.sessionMinutes;
import static chronicle.Feed.sittingStart;
import static chronicle.Feed.typeOf;
import static chronicle.Period.noon;
import static chronicle.Text.count;
import static chronicle.Text.dated;
import static chronicle.Text.fmt;
import static chronicle.Text.gp;
import static chronicle.Text.gps;
import static chronicle.Text.hoursMinutes;

final class Records
{
	static final long UNDATED = Long.MIN_VALUE;

	@RequiredArgsConstructor
	static final class Line
	{
		final String left;
		final String figure;
		final long ts;
		final String hover;
	}

	private final List<Line> out = new ArrayList<>();

	private Records()
	{
	}

	static List<Line> of(Build build)
	{
		Records r = new Records();
		r.read(build);
		return r.out;
	}

	private void add(String left, String figure, long ts, String hover)
	{
		out.add(new Line(left, figure, ts, hover));
	}

	private void read(Build build)
	{
		long[][] best = {{0, 0}, {0, 0}};
		for (JsonObject e : build.feed.all())
		{
			if ("SESSION".equals(typeOf(e)))
			{
				rank(best, sessionMinutes(e), sittingStart(e));
			}
		}
		if (best[0][0] > 0)
		{
			add("Longest session", hoursMinutes(best[0][0]), best[0][1],
				best[1][0] > 0 ? "Was " + hoursMinutes(best[1][0]) + " · " + dated(best[1][1]) : null);
		}

		TreeMap<LocalDate, Baseline> spine = build.spine();
		if (spine != null && spine.size() > 1)
		{
			long[][] bigXp = {{0, 0}, {0, 0}};
			String bigSkill = null;
			long[][] bigKills = {{0, 0}, {0, 0}};
			int run = 0;
			int longest = 0;
			LocalDate runEnd = null;
			LocalDate prevDay = null;
			Entry<LocalDate, Baseline> before = null;
			for (Entry<LocalDate, Baseline> day : spine.entrySet())
			{
				run = prevDay != null && prevDay.plusDays(1).equals(day.getKey()) ? run + 1 : 1;
				if (run > longest)
				{
					longest = run;
					runEnd = day.getKey();
				}
				prevDay = day.getKey();
				if (before != null)
				{
					long ts = noon(day.getKey());
					Object[] xp = build.feed.dayXp(day.getKey());
					if (rank(bigXp, xp == null ? 0 : (Long) xp[0], ts))
					{
						bigSkill = (String) xp[1];
					}
					long kills = 0;
					for (Entry<String, Long> k : day.getValue().kcs.entrySet())
					{
						Long was = before.getValue().kcs.get(k.getKey());
						if (was != null && k.getValue() > was)
						{
							kills += k.getValue() - was;
						}
					}
					rank(bigKills, kills, ts);
				}
				before = day;
			}
			if (bigXp[0][0] > 0)
			{
				add("Biggest day", "+" + gp(bigXp[0][0]) + " xp", bigXp[0][1],
					(bigSkill != null ? "Most in " + bigSkill : "")
						+ (bigXp[1][0] > 0 ? (bigSkill != null ? " · " : "") + "was +" + gp(bigXp[1][0])
						+ " · " + dated(bigXp[1][1]) : ""));
			}
			if (bigKills[0][0] > 0)
			{
				add("Most kills in a day", fmt(bigKills[0][0]), bigKills[0][1],
					bigKills[1][0] > 0 ? "Was " + fmt(bigKills[1][0]) + " · " + dated(bigKills[1][1]) : null);
			}
			if (longest > 1 && runEnd != null)
			{
				add("Longest streak of days played",
					fmt(longest) + " days", noon(runEnd), null);
			}
		}

		long[][] rich = {{0, 0}, {0, 0}};
		long[][] busy = {{0, 0}, {0, 0}};
		for (Entry<String, long[]> d : build.lootDays().totals().entrySet())
		{
			long ts;
			try
			{
				ts = noon(LocalDate.parse(d.getKey(), Feed.ROLL_DAY));
			}
			catch (RuntimeException e)
			{
				continue;
			}
			long[] t = d.getValue();
			rank(rich, t[1], ts, t[0]);
			rank(busy, t[0], ts, t[1]);
		}
		if (rich[0][0] > 0)
		{
			add("Richest day", gps(rich[0][0]), rich[0][1],
				count(rich[0][2], "drop") + (rich[1][0] > 0 ? " · was " + gp(rich[1][0]) + " · "
					+ dated(rich[1][1]) : ""));
		}
		if (busy[0][0] > 0)
		{
			add("Most drops in a day", fmt(busy[0][0]), busy[0][1],
				gps(busy[0][2]) + (busy[1][0] > 0 ? " · was " + fmt(busy[1][0]) + " · "
					+ dated(busy[1][1]) : ""));
		}

		long hit = build.lifetime().getOrDefault("highestHit", 0L);
		if (hit > 0)
		{
			add("Highest hit", fmt(hit), UNDATED, null);
		}
	}

	private static boolean rank(long[][] top, long... e)
	{
		if (e[0] > top[0][0])
		{
			top[1] = top[0];
			top[0] = e;
			return true;
		}
		if (e[0] > top[1][0])
		{
			top[1] = e;
		}
		return false;
	}
}
