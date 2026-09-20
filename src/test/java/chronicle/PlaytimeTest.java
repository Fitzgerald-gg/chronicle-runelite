/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Where the game's own playtime comes from, and what has to be done to it.
 *
 * <p>It is NOT a varp. The account summary panel is built by clientscript 3310,
 * which reads VarClientInt 526 for its "Time Played:" row and hands it to
 * clientscript 494 to phrase; 494 divides by 60 for hours and by 24 again for
 * days, so the unit is the minute. The only writer in the cache is clientscript
 * 3970, which the server invokes.
 *
 * <p>This replaced varp 4523. RuneLite names that TRACKING_PLAYTIME_LEAGUES and
 * NOTHING in the game reads it: the tracker panel beside it reads 4510 to 4527
 * for bosses killed, coins gained, fish caught and the rest, and for its
 * playtime row it emits a literal dash. So it read zero on every account
 * forever, and the panel fell back to the hours Chronicle itself had watched,
 * which is a different and much smaller number - eighty of them where the game
 * said far more.
 */
public class PlaytimeTest
{
	private static final long MIN = 60_000L;

	/** TRAP: the id has to be the NAMED one, not a number that looks right. */
	@Test
	public void itReadsTheAccountSummarysOwnCounter() throws Exception
	{
		String src = new String(java.nio.file.Files.readAllBytes(
			java.nio.file.Paths.get("src/main/java/chronicle/ChroniclePlugin.java")),
			java.nio.charset.StandardCharsets.UTF_8);
		assertTrue("the playtime no longer comes from the account summary's counter",
			src.contains("VarClientID.ACCOUNT_SUMMARY_PLAYTIME"));
		assertTrue("a varc is not reachable through getVarpValue",
			src.contains("getVarcIntValue("));
		// The javadoc NAMES 4523 to say why it is wrong, which is the point of
		// it, so the id is only a defect where it is being read.
		for (String line : src.split("\n"))
		{
			if (!line.contains("4523") || line.trim().startsWith("*")
				|| line.trim().startsWith("//"))
			{
				continue;
			}
			assertFalse("the dead leagues varp is being read again; nothing in the"
				+ " game writes it and it reads zero on every account: " + line.trim(),
				line.contains("getVarpValue") || line.contains("= 4523"));
		}
	}

	/**
	 * TRAP: the varc is empty until the server pushes it, which it does when the
	 * account summary is built - so on most logins it reads zero until the player
	 * opens that panel. Taking the zero throws away the figure already on disk
	 * and the panel drops back to Chronicle's own hours, which is the bug this
	 * whole thing was.
	 */
	@Test
	public void anEmptyCounterIsNotAnAnswer()
	{
		long began = 1_000_000_000L;
		// Nothing known, and half an hour of play on top of it. Carried forward
		// without the guard this reports thirty minutes of lifetime playtime,
		// which is not a small error but an invented figure.
		assertEquals("an empty counter was carried forward into a playtime the"
			+ " game never said", 0,
			ChroniclePlugin.carriedForward(0, began + MIN, began + 31 * MIN, began, 31));
		assertEquals("and the same where nothing has been read at all", 0,
			ChroniclePlugin.carriedForward(0, 0, began, began, 31));
		// while a figure that IS known survives a sitting with no fresh reading
		assertEquals("a known figure was lost to an empty reading", 1_020,
			ChroniclePlugin.carriedForward(1_000, began - MIN, began + 5 * MIN, began, 20));
	}

	/**
	 * What the client holds is a SNAPSHOT and does not tick. Left as it landed it
	 * is right when the panel is opened and further behind Hans every minute
	 * after, so the time played since is added.
	 */
	@Test
	public void aSnapshotFromThisSittingIsCarriedForwardByTheClock()
	{
		long began = 1_000_000_000L;
		long took = began + 5 * MIN;         // read five minutes into the sitting
		long now = began + 35 * MIN;         // half an hour later
		assertEquals("the snapshot was left where it landed", 1_030,
			ChroniclePlugin.carriedForward(1_000, took, now, began, 35));
	}

	/**
	 * TRAP: and a snapshot from an EARLIER sitting is carried by this sitting's
	 * own elapsed time, not by the wall clock since it was taken. The hours
	 * between two sittings are not playtime, and counting them would put the
	 * figure ahead of Hans by however long the client was shut - overnight, by
	 * eight hours.
	 */
	@Test
	public void aSnapshotFromAnEarlierSittingDoesNotCountTheHoursTheClientWasShut()
	{
		long began = 1_000_000_000L;
		long took = began - 12 * 60 * MIN;   // yesterday
		long now = began + 20 * MIN;
		assertEquals("the hours the client was shut were counted as playtime", 1_020,
			ChroniclePlugin.carriedForward(1_000, took, now, began, 20));
	}

	/** A clock that has gone backwards adds nothing rather than subtracting. */
	@Test
	public void aBackwardClockDoesNotEatTheFigure()
	{
		long began = 1_000_000_000L;
		assertEquals(1_000, ChroniclePlugin.carriedForward(
			1_000, began + 10 * MIN, began, began, -5));
	}
}
