/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Today's baseline is not written until the day rolls over or the client
 * closes. Until then a window ending today holds only the line it opened on,
 * and every board that measures between two lines read as empty on a day the
 * player had plainly been playing: no skills on the sheet, no boss grid, no
 * counters. The live sheet, ledger and trackers close it instead.
 */
public class TodayClosesOnTheClientTest
{
	@BeforeClass
	public static void headless() throws Exception
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
	}

	/** A record whose newest line is YESTERDAY, as every client has until it closes. */
	private static PanelPreviewTest.StubPlugin cold(LocalDate newest)
	{
		PanelPreviewTest.StubPlugin s = new PanelPreviewTest.StubPlugin(null);
		s.history.put(newest.minusDays(1), line(200_000_000L, 12_000L));
		s.history.put(newest, line(201_000_000L, 12_400L));
		// the live sheet, which is where a window reaching today closes
		s.skills.put("attack", new long[]{99, 202_000_000L});
		s.skills.put("overall", new long[]{2_200, 202_000_000L});
		s.lifetime.put("tilesRan", 13_000L);
		return s;
	}

	private static HistoryLog.Baseline line(long attack, long tiles)
	{
		HistoryLog.Baseline b = new HistoryLog.Baseline();
		b.skills.put("attack", attack);
		b.skills.put("overall", attack);
		b.counters.put("tilesRan", tiles);
		b.complete = true;
		return b;
	}

	private static List<String> sheet(PanelPreviewTest.StubPlugin s, String granularity)
		throws Exception
	{
		final ChroniclePanel[] hold = new ChroniclePanel[1];
		SwingUtilities.invokeAndWait(() -> hold[0] = new ChroniclePanel(s));
		PanelPreviewTest.regatherHistory(hold[0]);
		final List<String> said = new ArrayList<>();
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				Field g = ChroniclePanel.class.getDeclaredField("histGranularity");
				g.setAccessible(true);
				g.set(hold[0], granularity);
				Field c = ChroniclePanel.class.getDeclaredField("histCursor");
				c.setAccessible(true);
				c.set(hold[0], LocalDate.now());
				Method m = ChroniclePanel.class.getDeclaredMethod("buildSheet");
				m.setAccessible(true);
				collect((Component) m.invoke(hold[0]), said);
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		return said;
	}

	@Test
	public void todayDrawsItsSkillsBeforeItsBaselineIsWritten() throws Exception
	{
		List<String> said = sheet(cold(LocalDate.now().minusDays(1)), "Day");
		String all = String.join(" | ", said);
		assertFalse(all, all.contains("The imported past resolves by month"));
		assertFalse(all, all.contains("Nothing recorded in this period"));
		assertFalse(all, all.contains("holds fewer than two"));
		// the grid, and today's gain over yesterday's line
		assertTrue(all, said.contains("ATT"));
		assertTrue(all, said.contains("+1.0M"));
	}

	@Test
	public void theSittingDrawsItsSkillsTheSameWay() throws Exception
	{
		PanelPreviewTest.StubPlugin s = cold(LocalDate.now().minusDays(1));
		s.sessionStartMs = System.currentTimeMillis() - 90 * 60_000L;
		s.sessionElapsed = 90;
		List<String> said = sheet(s, "Session");
		String all = String.join(" | ", said);
		assertFalse(all, all.contains("Nothing recorded in this period"));
		assertTrue(all, said.contains("ATT"));
	}

	/**
	 * And a record three days cold still says so: measuring to the client
	 * would carry three days of gain under today's date.
	 */
	@Test
	public void aRecordDaysColdDoesNotPrintThoseDaysUnderToday() throws Exception
	{
		List<String> said = sheet(cold(LocalDate.now().minusDays(3)), "Day");
		String all = String.join(" | ", said);
		assertTrue(all, all.contains("The imported past resolves by month")
			|| all.contains("Nothing recorded in this period"));
	}

	private static void collect(Component c, List<String> out)
	{
		if (c instanceof JLabel && ((JLabel) c).getText() != null
			&& !((JLabel) c).getText().isEmpty())
		{
			out.add(((JLabel) c).getText());
		}
		if (c instanceof Container)
		{
			for (Component k : ((Container) c).getComponents())
			{
				collect(k, out);
			}
		}
	}
}
