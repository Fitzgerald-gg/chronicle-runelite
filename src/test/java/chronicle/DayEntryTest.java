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
		assertEquals("2 sittings · 2h 50m · +271k xp, most in Hunter · 98 drops · 1.1M gp", said.get(at + 1));
		String before = DateTimeFormatter.ofPattern("d MMM", Locale.UK).format(day.minusDays(1)).toUpperCase(Locale.ROOT);
		int b = said.indexOf(before);
		assertTrue(said.toString(), b >= 0);
		assertEquals("1 sitting · 45m", said.get(b + 1));
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
