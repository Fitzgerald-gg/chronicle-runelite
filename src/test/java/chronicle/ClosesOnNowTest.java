/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import javax.swing.SwingUtilities;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * A period that reaches today closes on now, not on the last snapshot.
 *
 * <p>The history spine is written once a day. Everything measured against its
 * newest line therefore stopped at whenever it was last written, which for a
 * period ending today is some hours ago - so a board saying what this week had
 * done was missing everything since midnight and caught up overnight. The same
 * flaw appeared three times over: the skills, the kill counts and every counter.
 */
public class ClosesOnNowTest
{
	private static ChroniclePanel panel;

	@BeforeClass
	public static void build() throws Exception
	{
		System.setProperty("java.awt.headless", "true");
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				javax.swing.UIManager.setLookAndFeel(
					new net.runelite.client.ui.laf.RuneLiteLAF());
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		PanelPreviewTest.StubPlugin stub = PanelPreviewTest.fixtureStub();
		final ChroniclePanel[] hold = new ChroniclePanel[1];
		SwingUtilities.invokeAndWait(() -> hold[0] = new ChroniclePanel(stub));
		panel = hold[0];
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Long> closingNow(Map<String, Long> closing,
		Map<String, Long> live, String granularity) throws Exception
	{
		Field g = ChroniclePanel.class.getDeclaredField("histGranularity");
		g.setAccessible(true);
		g.set(panel, granularity);
		Field from = ChroniclePanel.class.getDeclaredField("histFrom");
		from.setAccessible(true);
		from.set(panel, null);
		Method m = ChroniclePanel.class.getDeclaredMethod("closingNow", Map.class, Map.class);
		m.setAccessible(true);
		return (Map<String, Long>) m.invoke(panel, closing, live);
	}

	private static Map<String, Long> map(Object... kv)
	{
		Map<String, Long> m = new HashMap<>();
		for (int i = 0; i + 1 < kv.length; i += 2)
		{
			m.put((String) kv[i], ((Number) kv[i + 1]).longValue());
		}
		return m;
	}

	/** Today's figures reach a period that includes today. */
	@Test
	public void aPeriodEndingTodayTakesTheLiveFigure() throws Exception
	{
		Map<String, Long> got = closingNow(map("logsChopped", 1000L),
			map("logsChopped", 1040L), "Week");
		assertEquals("the forty chopped since the spine was written were lost",
			Long.valueOf(1040L), got.get("logsChopped"));
	}

	/**
	 * Merged upward, never downward. These figures only grow, so the larger is
	 * the later; a live reading that is somehow behind is a reading to ignore
	 * rather than a reason to lose what the record already holds.
	 */
	@Test
	public void aLiveFigureBehindTheSnapshotIsIgnored() throws Exception
	{
		Map<String, Long> got = closingNow(map("logsChopped", 1000L),
			map("logsChopped", 900L), "Week");
		assertEquals(Long.valueOf(1000L), got.get("logsChopped"));
	}

	/** And a key only the spine knows keeps what the spine holds. */
	@Test
	public void aKeyTheLiveSideNeverHeardOfIsKept() throws Exception
	{
		Map<String, Long> got = closingNow(map("clogSlots", 214L),
			map("logsChopped", 5L), "Week");
		assertEquals(Long.valueOf(214L), got.get("clogSlots"));
		assertEquals(Long.valueOf(5L), got.get("logsChopped"));
	}

	/**
	 * TRAP: a period that ENDED. September's figures are September's, and
	 * pouring today's totals into them would report this month's woodcutting as
	 * last month's.
	 */
	@Test
	public void aPeriodThatEndedIsLeftAlone() throws Exception
	{
		Field cursor = ChroniclePanel.class.getDeclaredField("histCursor");
		cursor.setAccessible(true);
		Object was = cursor.get(panel);
		try
		{
			cursor.set(panel, java.time.LocalDate.now().minusYears(2));
			Map<String, Long> closing = map("logsChopped", 1000L);
			Map<String, Long> got = closingNow(closing, map("logsChopped", 99_999L),
				"Month");
			assertEquals("a closed month was handed today's totals",
				Long.valueOf(1000L), got.get("logsChopped"));
		}
		finally
		{
			cursor.set(panel, was);
		}
	}

	/** Nothing to merge is nothing to do. */
	@Test
	public void noLiveReadingLeavesTheSnapshotExactlyAsItWas() throws Exception
	{
		Map<String, Long> closing = map("logsChopped", 1000L);
		assertEquals(closing, closingNow(closing, new HashMap<>(), "Week"));
		assertNull(closingNow(null, map("logsChopped", 1L), "Week"));
	}
}
