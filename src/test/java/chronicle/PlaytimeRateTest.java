/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The unit the game counts playtime in, worked out rather than assumed.
 *
 * <p>The counter sits in the middle of the account summary's own block of
 * trackers, which is where playtime appears in that panel. RuneLite names it for
 * leagues and reads it nowhere, so nothing in the client says what unit it holds
 * - minutes, seconds and ticks are all plausible and differ by sixty or a
 * hundred times, which is the difference between "matches Hans" and "absurd".
 *
 * <p>So it is not assumed. The counter has to keep time: watched across a couple
 * of minutes of play, the rate it advances at against the wall clock says which
 * unit it is. Anything that does not advance in step with time is not a playtime
 * and is refused, which is also what a wrong id would get.
 */
public class PlaytimeRateTest
{
	/** The rule, given a counter's movement and the real minutes it took. */
	private static double rateOf(long moved, double overMinutes)
	{
		return moved / overMinutes;
	}

	private static Double snap(double rate)
	{
		for (double candidate : new double[]{1, 60, 100})
		{
			if (Math.abs(rate - candidate) <= candidate * 0.2)
			{
				return candidate;
			}
		}
		return null;
	}

	@Test
	public void aCounterOfMinutesIsRecognised()
	{
		assertEquals(Double.valueOf(1), snap(rateOf(3, 3.0)));
		// a minute's rounding either side still reads as minutes
		assertEquals(Double.valueOf(1), snap(rateOf(5, 5.5)));
	}

	@Test
	public void aCounterOfSecondsIsRecognised()
	{
		assertEquals(Double.valueOf(60), snap(rateOf(180, 3.0)));
	}

	/** A tick is six tenths of a second, so a hundred of them to the minute. */
	@Test
	public void aCounterOfTicksIsRecognised()
	{
		assertEquals(Double.valueOf(100), snap(rateOf(300, 3.0)));
	}

	/**
	 * TRAP: a counter that is not a clock. If the id is wrong, or the meaning
	 * changes, what comes back is some other statistic that happens to rise -
	 * kills, coins, experience. None of those keeps time, and a playtime taken
	 * from one would be wrong by whatever the player happened to be doing.
	 */
	@Test
	public void aCounterThatDoesNotKeepTimeIsRefused()
	{
		assertEquals("a counter that barely moved was read as a clock",
			null, snap(rateOf(0, 3.0)));
		assertEquals("kills per minute is not a unit of time",
			null, snap(rateOf(37, 3.0)));
		assertEquals(null, snap(rateOf(5_000, 3.0)));
		assertEquals("half a minute a minute is not any cadence the game keeps",
			null, snap(rateOf(15, 30.0)));
	}

	/** And the reading itself is the counter divided by what it counts in. */
	@Test
	public void theReadingIsTheCounterDividedByItsCadence()
	{
		assertEquals(1_234L, Math.round(1_234 / 1.0));
		assertEquals("seconds", 1_234L, Math.round(74_040 / 60.0));
		assertEquals("ticks", 1_234L, Math.round(123_400 / 100.0));
	}

	/**
	 * Chronicle's own figure is a floor, never a replacement: it is the time this
	 * plugin watched, which begins the day it was installed and knows nothing of
	 * the years before. The game's is the one a player recognises.
	 */
	@Test
	public void theGamesFigureIsOnlyUsedWhenItIsTheLargerOfTheTwo()
	{
		long watched = 5_000;
		long theirs = 120_000;
		assertTrue("the game has seen at least as much as the plugin has",
			theirs > watched);
		assertEquals(120_000L, Math.max(watched, theirs));
		// and a counter that has not answered yet leaves the watched figure alone
		assertEquals(5_000L, Math.max(watched, 0L));
	}
}
