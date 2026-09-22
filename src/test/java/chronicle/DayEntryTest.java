/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.JsonObject;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Under each day's heading in the Journal, the day's own line: sittings and
 * minutes, xp and where most of it went, drops and their gp. Figures only,
 * each clause present only where the record has one.
 */
public class DayEntryTest
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

	private static JsonObject session(LocalDate day, int hour, long minutes)
	{
		JsonObject e = new JsonObject();
		e.addProperty("ts", day.atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
		e.addProperty("type", "SESSION");
		JsonObject d = new JsonObject();
		d.addProperty("minutes", minutes);
		e.add("data", d);
		return e;
	}

	private static HistoryLog.Baseline line(long hunter, long fishing)
	{
		HistoryLog.Baseline b = new HistoryLog.Baseline();
		b.skills.put("hunter", hunter);
		b.skills.put("fishing", fishing);
		b.skills.put("overall", hunter + fishing);
		b.complete = true;
		return b;
	}

	@Test
	public void theDayWritesItsOwnEntry() throws Exception
	{
		LocalDate day = LocalDate.now().minusDays(2);
		PanelPreviewTest.StubPlugin s = new PanelPreviewTest.StubPlugin(null);
		s.feed.add(session(day, 21, 70));
		s.feed.add(session(day, 14, 100));
		s.feed.add(session(day.minusDays(1), 20, 45));   // the day before: no xp, no loot
		s.history.put(day.minusDays(1), line(1_000_000L, 500_000L));
		s.history.put(day, line(1_251_000L, 520_000L));   // +251k hunter, +20k fishing
		s.dayTotals.put(DateTimeFormatter.ofPattern("yyyy-MM-dd").format(day), new long[]{98, 1_100_000L, 0, 0});
		final ChroniclePanel[] hold = new ChroniclePanel[1];
		SwingUtilities.invokeAndWait(() -> hold[0] = new ChroniclePanel(s));
		PanelPreviewTest.regatherHistory(hold[0]);
		final List<String> said = new ArrayList<>();
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				Method m = ChroniclePanel.class.getDeclaredMethod("buildJournal");
				m.setAccessible(true);
				collect((Component) m.invoke(hold[0]), said);
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		String heading = DateTimeFormatter.ofPattern("d MMM", Locale.UK).format(day).toUpperCase(Locale.ROOT);
		int at = said.indexOf(heading);
		assertTrue(said.toString(), at >= 0);
		// wrapped at its clauses where the column is too narrow for it
		List<String> lines = ChroniclePanel.wrapClauses(
			"2 sittings · 2h 50m · +271k xp, most in Hunter · 98 drops · 1.1M gp",
			ChroniclePanel.boardRowRoom());
		assertEquals(lines, said.subList(at + 1, at + 1 + lines.size()));
		String before = DateTimeFormatter.ofPattern("d MMM", Locale.UK).format(day.minusDays(1)).toUpperCase(Locale.ROOT);
		int b = said.indexOf(before);
		assertTrue(said.toString(), b >= 0);
		assertEquals("1 sitting · 45m", said.get(b + 1));
	}

	/**
	 * A week away and the next line carries the whole gap. Attributing that to
	 * the first day back is a figure nobody earned in a day, so the day says
	 * what it can and leaves the xp out.
	 */
	@Test
	public void theFirstDayBackDoesNotClaimTheGap() throws Exception
	{
		LocalDate back = LocalDate.now().minusDays(2);
		PanelPreviewTest.StubPlugin s = new PanelPreviewTest.StubPlugin(null);
		s.feed.add(session(back, 20, 60));
		s.history.put(back.minusDays(8), line(1_000_000L, 500_000L));
		s.history.put(back, line(3_000_000L, 500_000L));   // two million over eight days
		final ChroniclePanel[] hold = new ChroniclePanel[1];
		SwingUtilities.invokeAndWait(() -> hold[0] = new ChroniclePanel(s));
		PanelPreviewTest.regatherHistory(hold[0]);
		final List<String> said = new ArrayList<>();
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				Method m = ChroniclePanel.class.getDeclaredMethod("buildJournal");
				m.setAccessible(true);
				collect((Component) m.invoke(hold[0]), said);
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		String heading = DateTimeFormatter.ofPattern("d MMM", Locale.UK)
			.format(back).toUpperCase(Locale.ROOT);
		int at = said.indexOf(heading);
		assertTrue(said.toString(), at >= 0);
		assertEquals("1 sitting · 1h 0m", said.get(at + 1));
	}

	/**
	 * A long day wraps rather than running off the column. As one label it cut
	 * at "most i..." and the drops were never on screen.
	 */
	@Test
	public void aLongDayWrapsAtItsClauses() throws Exception
	{
		String day = "3 sittings · 1h 12m · +162k xp, most in Hunter · 61 drops · 1.2M gp";
		int room = ChroniclePanel.boardRowRoom();
		java.awt.FontMetrics fm = ChroniclePanel.rowMetrics();
		assertTrue("the fixture line fits anyway, so this proves nothing",
			fm.stringWidth(day) > room);
		List<String> lines = ChroniclePanel.wrapClauses(day, room);
		assertTrue(lines.toString(), lines.size() >= 2);
		for (String l : lines)
		{
			assertTrue("a wrapped line still runs off: " + l, fm.stringWidth(l) <= room);
		}
		assertEquals("a clause was lost or split", day, String.join(" · ", lines));
		// and a short one stays one line
		assertEquals(java.util.Collections.singletonList("1 sitting · 45m"),
			ChroniclePanel.wrapClauses("1 sitting · 45m", room));
	}

	private static void collect(Component c, List<String> out)
	{
		if (c instanceof JLabel && ((JLabel) c).getText() != null && !((JLabel) c).getText().isEmpty())
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
