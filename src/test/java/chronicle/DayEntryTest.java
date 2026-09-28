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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
		s.feed.add(session(day.minusDays(1), 20, 45));
		s.history.put(day.minusDays(1), line(1_000_000L, 500_000L));
		s.history.put(day, line(1_251_000L, 520_000L));
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
		List<String> lines = ChroniclePanel.wrapClauses(
			"2 sittings · 2h 50m · +271k xp, most in Hunter · 98 drops · 1.1M gp",
			ChroniclePanel.boardRowRoom());
		assertEquals(lines, said.subList(at + 1, at + 1 + lines.size()));
		String before = DateTimeFormatter.ofPattern("d MMM", Locale.UK).format(day.minusDays(1)).toUpperCase(Locale.ROOT);
		int b = said.indexOf(before);
		assertTrue(said.toString(), b >= 0);
		assertEquals("1 sitting · 45m", said.get(b + 1));
	}

	@Test
	public void theFirstDayBackDoesNotClaimTheGap() throws Exception
	{
		LocalDate back = LocalDate.now().minusDays(2);
		PanelPreviewTest.StubPlugin s = new PanelPreviewTest.StubPlugin(null);
		s.feed.add(session(back, 20, 60));
		s.history.put(back.minusDays(8), line(1_000_000L, 500_000L));
		s.history.put(back, line(3_000_000L, 500_000L));
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

	private static JsonObject sitting(java.time.LocalDateTime closed, long minutes, long xp,
		long drops, long dropsGp, String skill)
	{
		JsonObject e = new JsonObject();
		e.addProperty("ts", closed.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
		e.addProperty("type", "SESSION");
		JsonObject d = new JsonObject();
		d.addProperty("minutes", minutes);
		d.addProperty("xp", xp);
		d.addProperty("drops", drops);
		d.addProperty("dropsGp", dropsGp);
		JsonObject sk = new JsonObject();
		sk.addProperty(skill, xp);
		d.add("skills", sk);
		e.add("data", d);
		return e;
	}

	@Test
	public void aSittingAcrossMidnightBelongsToTheDayItBegan() throws Exception
	{
		LocalDate day = LocalDate.now().minusDays(3);
		PanelPreviewTest.StubPlugin s = new PanelPreviewTest.StubPlugin(null);
		s.feed.add(sitting(day.plusDays(1).atTime(17, 0), 8, 2_540, 1, 4_000, "fletching"));
		s.feed.add(sitting(day.plusDays(1).atTime(0, 2), 152, 354_610, 129, 554_521, "hunter"));
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
		String began = DateTimeFormatter.ofPattern("d MMM", Locale.UK).format(day).toUpperCase(Locale.ROOT);
		String after = DateTimeFormatter.ofPattern("d MMM", Locale.UK).format(day.plusDays(1))
			.toUpperCase(Locale.ROOT);
		int b = said.indexOf(began);
		int a = said.indexOf(after);
		assertTrue(said.toString(), b >= 0 && a >= 0 && a < b);
		String beganLine = String.join(" · ", said.subList(b + 1, said.size()));
		assertTrue(said.toString(), beganLine.startsWith("1 sitting · 2h 32m · +354k xp, most in Hunter"
			+ " · 129 drops · 554k gp"));
		assertTrue(said.toString(), said.subList(b, said.size()).contains("Session · 2h 32m"));
		assertFalse(said.toString(), said.subList(a, b).contains("Session · 2h 32m"));
		List<String> next = ChroniclePanel.wrapClauses(
			"1 sitting · 8m · +2,540 xp, most in Fletching · 1 drop · 4,000 gp",
			ChroniclePanel.boardRowRoom());
		assertEquals(next, said.subList(a + 1, a + 1 + next.size()));
	}

	private static List<String> journal(PanelPreviewTest.StubPlugin s) throws Exception
	{
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
		return said;
	}

	@Test
	public void anOrdinaryDayReadsTheSpineNotTheWrittenSittings() throws Exception
	{
		LocalDate day = LocalDate.now().minusDays(2);
		PanelPreviewTest.StubPlugin s = new PanelPreviewTest.StubPlugin(null);
		s.feed.add(sitting(day.atTime(15, 0), 60, 10_000, 2, 1_000, "hunter"));
		s.history.put(day.minusDays(1), line(1_000_000L, 500_000L));
		s.history.put(day, line(1_090_000L, 500_000L));
		s.dayTotals.put(DateTimeFormatter.ofPattern("yyyy-MM-dd").format(day), new long[]{9, 40_000L, 0, 0});
		List<String> said = journal(s);
		String heading = DateTimeFormatter.ofPattern("d MMM", Locale.UK).format(day).toUpperCase(Locale.ROOT);
		int at = said.indexOf(heading);
		assertTrue(said.toString(), at >= 0);
		String entry = String.join(" · ", said.subList(at + 1, Math.min(said.size(), at + 4)));
		assertTrue(entry, entry.contains("+90k xp, most in Hunter"));
		assertTrue(entry, entry.contains("9 drops · 40k gp"));
	}

	@Test
	public void aSittingWithNoXpStillLetsTheDayNameItsSkill() throws Exception
	{
		LocalDate day = LocalDate.now().minusDays(3);
		PanelPreviewTest.StubPlugin s = new PanelPreviewTest.StubPlugin(null);
		JsonObject idle = sitting(day.atTime(20, 0), 6, 0, 0, 0, "hunter");
		idle.getAsJsonObject("data").remove("skills");
		s.feed.add(idle);
		s.feed.add(sitting(day.plusDays(1).atTime(0, 30), 120, 300_000, 0, 0, "hunter"));
		s.history.put(day, line(1_000_000L, 500_000L));
		s.history.put(day.plusDays(1), line(1_050_000L, 900_000L));
		List<String> said = journal(s);
		String heading = DateTimeFormatter.ofPattern("d MMM", Locale.UK).format(day).toUpperCase(Locale.ROOT);
		int at = said.indexOf(heading);
		assertTrue(said.toString(), at >= 0);
		String entry = String.join(" · ", said.subList(at + 1, Math.min(said.size(), at + 4)));
		assertTrue(entry, entry.contains("most in Hunter"));
	}

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
