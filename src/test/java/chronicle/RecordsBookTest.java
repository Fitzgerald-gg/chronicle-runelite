/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.JsonObject;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The records book behind the Total level cell: the account's bests, each
 * with its date, what it beat on hover. Every line already happened.
 */
public class RecordsBookTest
{
	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK);

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

	private static JsonObject session(LocalDate day, long minutes)
	{
		JsonObject e = new JsonObject();
		e.addProperty("ts", day.atTime(20, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
		e.addProperty("type", "SESSION");
		JsonObject d = new JsonObject();
		d.addProperty("minutes", minutes);
		e.add("data", d);
		return e;
	}

	private static HistoryLog.Baseline line(long hunter, long fishing, long vorkath)
	{
		HistoryLog.Baseline b = new HistoryLog.Baseline();
		b.skills.put("hunter", hunter);
		b.skills.put("fishing", fishing);
		b.skills.put("overall", hunter + fishing);
		b.kcs.put("Vorkath", vorkath);
		b.complete = true;
		return b;
	}

	@Test
	public void theBookReadsTheRecordsBests() throws Exception
	{
		PanelPreviewTest.StubPlugin s = new PanelPreviewTest.StubPlugin(null);
		LocalDate today = LocalDate.now();
		s.feed.add(session(today.minusDays(9), 120));
		s.feed.add(session(today.minusDays(4), 552));
		s.feed.add(session(today.minusDays(1), 30));
		s.history.put(today.minusDays(3), line(1_000_000L, 500_000L, 100));
		s.history.put(today.minusDays(2), line(2_400_000L, 500_000L, 161));   // +1.4M, 61 kills
		s.history.put(today.minusDays(1), line(2_500_000L, 600_000L, 170));   // +200k, 9 kills
		s.dayTotals.put("2026-06-19", new long[]{61, 12_400_000L, 0, 0});
		s.dayTotals.put("2026-08-03", new long[]{90, 1_000_000L, 0, 0});
		s.lifetime.put("highestHit", 73L);
		final ChroniclePanel[] hold = new ChroniclePanel[1];
		SwingUtilities.invokeAndWait(() -> hold[0] = new ChroniclePanel(s));
		PanelPreviewTest.regatherHistory(hold[0]);
		final JPanel[] book = new JPanel[1];
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				Method m = ChroniclePanel.class.getDeclaredMethod("buildRecords");
				m.setAccessible(true);
				book[0] = (JPanel) m.invoke(hold[0]);
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		assertEquals("9h 12m · " + DAY.format(today.minusDays(4)), beside(book[0], "Longest sitting"));
		assertEquals("Was 2h 0m · " + DAY.format(today.minusDays(9)),
			rowNamed(book[0], "Longest sitting").getToolTipText());
		assertEquals("+1.4M xp · " + DAY.format(today.minusDays(2)), beside(book[0], "Biggest day"));
		assertTrue(rowNamed(book[0], "Biggest day").getToolTipText().startsWith("Most in Hunter"));
		assertEquals("61 · " + DAY.format(today.minusDays(2)), beside(book[0], "Most kills in a day"));
		assertEquals("3 days · " + DAY.format(today.minusDays(1)), beside(book[0], "Longest run of days written"));
		assertEquals("12.4M gp · 19 Jun 2026", beside(book[0], "Richest day"));
		assertEquals("90 · 3 Aug 2026", beside(book[0], "Most drops in a day"));
		assertEquals("73", beside(book[0], "Highest hit"));
	}

	@Test
	public void theTotalLevelCellOpensIt() throws Exception
	{
		PanelPreviewTest.StubPlugin stub = PanelPreviewTest.fixtureStub();
		final ChroniclePanel[] hold = new ChroniclePanel[1];
		SwingUtilities.invokeAndWait(() -> hold[0] = new ChroniclePanel(stub));
		PanelPreviewTest.regatherHistory(hold[0]);
		final JPanel[] cell = new JPanel[1];
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				Method m = ChroniclePanel.class.getDeclaredMethod("buildSheet");
				m.setAccessible(true);
				List<Component> flat = new ArrayList<>();
				flatten((Component) m.invoke(hold[0]), flat);
				for (Component c : flat)
				{
					if (c instanceof JPanel && "Total level".equals(left((JPanel) c))
						&& c.getCursor().getType() == Cursor.HAND_CURSOR)
					{
						cell[0] = (JPanel) c;
					}
				}
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		assertNotNull("the Total level cell is not a door", cell[0]);
		SwingUtilities.invokeAndWait(() ->
		{
			for (MouseListener l : cell[0].getMouseListeners())
			{
				l.mousePressed(new MouseEvent(cell[0], MouseEvent.MOUSE_PRESSED, 0L, 0, 1, 1, 1, false));
			}
		});
		Field f = ChroniclePanel.class.getDeclaredField("showRecords");
		f.setAccessible(true);
		assertEquals(true, f.get(hold[0]));
	}

	private static String left(JPanel r)
	{
		if (!(r.getLayout() instanceof BorderLayout))
		{
			return null;
		}
		Component mid = ((BorderLayout) r.getLayout()).getLayoutComponent(BorderLayout.CENTER);
		return mid instanceof JLabel ? ((JLabel) mid).getText() : null;
	}

	private static JPanel rowNamed(Component c, String l)
	{
		List<Component> flat = new ArrayList<>();
		flatten(c, flat);
		for (Component k : flat)
		{
			if (k instanceof JPanel && l.equals(left((JPanel) k)))
			{
				return (JPanel) k;
			}
		}
		return null;
	}

	private static String beside(Component c, String l)
	{
		JPanel r = rowNamed(c, l);
		if (r == null)
		{
			return null;
		}
		Component east = ((BorderLayout) r.getLayout()).getLayoutComponent(BorderLayout.EAST);
		return east instanceof JLabel ? ((JLabel) east).getText() : null;
	}

	private static void flatten(Component c, List<Component> out)
	{
		out.add(c);
		if (c instanceof Container)
		{
			for (Component k : ((Container) c).getComponents())
			{
				flatten(k, out);
			}
		}
	}
}
