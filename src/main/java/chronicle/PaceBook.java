/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import net.runelite.api.Experience;

class PaceBook
{
	private static final int MAX_LEVEL = 99;
	private static final long MAX_XP = 200_000_000L;

	private static final int MAX_ACTIVE_DAYS = 7;
	private static final int RECENCY_DAYS = 30;

	private static final int MIN_ACTIVE_DAYS = 2;

	private PaceBook()
	{
	}

	@RequiredArgsConstructor
	static final class Pace
	{
		final double xpPerActiveDay;

		final int activeDays;

		final Integer targetLevel;

		final long targetXp;

		final long daysOfPlay;

		final LocalDate lastActive;

		boolean hasHorizon()
		{
			return daysOfPlay > 0;
		}

		boolean dormant()
		{
			return daysOfPlay <= 0 && targetXp > 0;
		}
	}

	static Pace forSkill(TreeMap<LocalDate, HistoryLog.Baseline> spine, String skill,
		long currentXp)
	{
		return forSkill(spine, skill, currentXp, LocalDate.now());
	}

	static Pace forSkill(TreeMap<LocalDate, HistoryLog.Baseline> spine, String skill,
		long currentXp, LocalDate asOf)
	{
		long xp = Math.max(0, currentXp);
		long targetXp = nextMark(xp);
		Integer targetLevel = targetXp > 0 && targetXp <= xpForLevel(MAX_LEVEL)
			? levelAt(targetXp) : null;
		long remaining = targetXp > 0 ? targetXp - xp : 0;

		List<Long> gains = new ArrayList<>();
		LocalDate lastActive = null;

		if (spine != null && skill != null && asOf != null)
		{
			LocalDate cutoff = asOf.minusDays(RECENCY_DAYS);
			Long later = null;
			LocalDate laterDate = null;
			for (Map.Entry<LocalDate, HistoryLog.Baseline> e : spine.descendingMap().entrySet())
			{
				Long value = e.getValue() != null ? e.getValue().skills.get(skill) : null;
				if (value == null)
				{
					later = null;
					laterDate = null;
					continue;
				}
				if (later != null)
				{
					long gain = later - value;
					if (gain > 0)
					{
						if (lastActive == null)
						{
							lastActive = laterDate;
						}
						if (gains.size() < MAX_ACTIVE_DAYS && !laterDate.isBefore(cutoff))
						{
							gains.add(gain);
						}
					}
				}
				later = value;
				laterDate = e.getKey();
				if (lastActive != null
					&& (gains.size() >= MAX_ACTIVE_DAYS || laterDate.isBefore(cutoff)))
				{
					break;
				}
			}
		}

		long total = 0;
		for (long g : gains)
		{
			total += g;
		}
		int activeDays = gains.size();
		double pace = activeDays > 0 ? (double) total / activeDays : 0.0;

		long daysOfPlay = activeDays < MIN_ACTIVE_DAYS || pace <= 0 || remaining <= 0
			? 0 : (long) Math.ceil(remaining / pace);
		return new Pace(pace, activeDays, targetLevel, targetXp, daysOfPlay, lastActive);
	}

	private static long nextMark(long xp)
	{
		if (xp >= MAX_XP)
		{
			return 0;
		}
		if (xp >= xpForLevel(MAX_LEVEL))
		{
			return MAX_XP;
		}
		return xpForLevel(levelAt(xp) + 1);
	}

	static long xpForLevel(int level)
	{
		return Experience.getXpForLevel(Math.max(1, Math.min(MAX_LEVEL, level)));
	}

	static int levelAt(long xp)
	{
		return Math.min(MAX_LEVEL, virtualLevelAt(xp));
	}

	static int virtualLevelAt(long xp)
	{
		return Experience.getLevelForXp((int) Math.max(0, Math.min(xp, Integer.MAX_VALUE)));
	}
}
