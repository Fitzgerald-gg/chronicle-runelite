/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlaytimeTest
{
	private static final long MIN = 60_000L;

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

	@Test
	public void anEmptyCounterIsNotAnAnswer()
	{
		long began = 1_000_000_000L;
		assertEquals("an empty counter was carried forward into a playtime the"
			+ " game never said", 0,
			ChroniclePlugin.carriedForward(0, began + MIN, began + 31 * MIN, began, 31));
		assertEquals("and the same where nothing has been read at all", 0,
			ChroniclePlugin.carriedForward(0, 0, began, began, 31));
		assertEquals("a known figure was lost to an empty reading", 1_020,
			ChroniclePlugin.carriedForward(1_000, began - MIN, began + 5 * MIN, began, 20));
	}

	@Test
	public void aSnapshotFromThisSittingIsCarriedForwardByTheClock()
	{
		long began = 1_000_000_000L;
		long took = began + 5 * MIN;
		long now = began + 35 * MIN;
		assertEquals("the snapshot was left where it landed", 1_030,
			ChroniclePlugin.carriedForward(1_000, took, now, began, 35));
	}

	@Test
	public void aSnapshotFromAnEarlierSittingDoesNotCountTheHoursTheClientWasShut()
	{
		long began = 1_000_000_000L;
		long took = began - 12 * 60 * MIN;
		long now = began + 20 * MIN;
		assertEquals("the hours the client was shut were counted as playtime", 1_020,
			ChroniclePlugin.carriedForward(1_000, took, now, began, 20));
	}

	@Test
	public void aBackwardClockDoesNotEatTheFigure()
	{
		long began = 1_000_000_000L;
		assertEquals(1_000, ChroniclePlugin.carriedForward(
			1_000, began + 10 * MIN, began, began, -5));
	}
}
