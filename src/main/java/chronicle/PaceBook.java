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

/**
 * Pace and horizon for one skill, off the journal's calendar spine.
 *
 * <p>Pace is xp per ACTIVE day, meaning days the skill actually gained. Idle
 * days never reach the divisor, so a skill touched once in a year still reports
 * what a day at it is worth. The horizon that follows is counted in days of
 * play and never named as a date. The record can't say which calendar days
 * will be played.
 *
 * <p>Pure function of its arguments.
 */
class PaceBook
{
	// the curve stops at 99; past it the only mark left is the 200m ceiling
	private static final int MAX_LEVEL = 99;
	private static final long MAX_XP = 200_000_000L;

	// the rate is the newest 7 active days falling inside the last 30
	private static final int MAX_ACTIVE_DAYS = 7;
	private static final int RECENCY_DAYS = 30;

	// below this there is no horizon, only the pace
	private static final int MIN_ACTIVE_DAYS = 2;

	private PaceBook()
	{
	}

	/** A skill's rate, the mark ahead of it, and the basis the rate stands on. */
	@RequiredArgsConstructor
	static final class Pace
	{
		final double xpPerActiveDay;

		final int activeDays;

		// null when the mark ahead is the 200m ceiling, or when the skill is done
		final Integer targetLevel;

		// 0 when there is nothing left ahead
		final long targetXp;

		// active days away, 0 when there is no horizon
		final long daysOfPlay;

		// last day the skill moved anywhere in the spine, recency window ignored;
		// null if the record has never seen it move
		final LocalDate lastActive;

		boolean hasHorizon()
		{
			return daysOfPlay > 0;
		}

		// a mark ahead, but too little recent movement to aim at it. the panel
		// prints lastActive instead
		boolean dormant()
		{
			return daysOfPlay <= 0 && targetXp > 0;
		}
	}

	// the spine supplies the rate; currentXp supplies what is still owed. the
	// horizon shortens as the day is played
	static Pace forSkill(TreeMap<LocalDate, HistoryLog.Baseline> spine, String skill,
		long currentXp)
	{
		return forSkill(spine, skill, currentXp, LocalDate.now());
	}

	// the window is measured from asOf. taking it from the spine's newest line
	// would let a journal idle since spring call spring's pace current
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
			// newest first, one pair at a time: a day's gain is its baseline minus
			// the nearest earlier one, the same subtraction the History tab makes
			Long later = null;
			LocalDate laterDate = null;
			for (Map.Entry<LocalDate, HistoryLog.Baseline> e : spine.descendingMap().entrySet())
			{
				Long value = e.getValue() != null ? e.getValue().skills.get(skill) : null;
				if (value == null)
				{
					// a missing key means no data, not 0. imported baselines predate
					// newer skills, and reading the hole as 0 turns a lifetime of xp
					// into one day's gain. break the pair instead of spanning it
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
				// quota full or past the window, with lastActive already known:
				// nothing left for the walk to find
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

		// short of a horizon the rate and the mark ahead still stand; only the
		// projection is withheld, which the caller reads off daysOfPlay
		long daysOfPlay = activeDays < MIN_ACTIVE_DAYS || pace <= 0 || remaining <= 0
			? 0 : (long) Math.ceil(remaining / pace);
		return new Pace(pace, activeDays, targetLevel, targetXp, daysOfPlay, lastActive);
	}

	// the next level while one remains, then the 200m ceiling, then 0 for done
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

	/** Xp at {@code level}, clamped to the 1-99 the curve covers. */
	static long xpForLevel(int level)
	{
		return Experience.getXpForLevel(Math.max(1, Math.min(MAX_LEVEL, level)));
	}

	/** The level {@code xp} has reached; anything past 99 reads as 99. */
	static int levelAt(long xp)
	{
		return Math.min(MAX_LEVEL, virtualLevelAt(xp));
	}

	/**
	 * The level {@code xp} has reached, counting past 99.
	 *
	 * <p>Kept apart from levelAt, which the pace line measures against: a
	 * projection to the next level should stop at 99 where the game does, and a
	 * sheet showing what an account has actually done should not.
	 */
	static int virtualLevelAt(long xp)
	{
		// Experience throws on negative xp, which reads here as level 1
		return Experience.getLevelForXp((int) Math.max(0, Math.min(xp, Integer.MAX_VALUE)));
	}
}
