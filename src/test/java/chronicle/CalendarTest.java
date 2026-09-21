/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.JsonObject;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The days written, as a month behind the nameplate's Days written row: a
 * cell for every day, shaded by how long the account sat, a door from each
 * written day to the Journal on it, arrows stepping the month.
 */
public class CalendarTest
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

	@Test
	public void theMonthShadesItsDaysAndOpensThem() throws Exception
	{
		// a month wholly in the past: yesterday's, from its first day
		LocalDate first = LocalDate.now().minusDays(1).withDayOfMonth(1);
		LocalDate second = first.plusDays(1);
		PanelPreviewTest.StubPlugin s = new PanelPreviewTest.StubPlugin(null);
		s.feed.add(session(first, 200));
		s.feed.add(session(second, 50));
		final ChroniclePanel[] hold = new ChroniclePanel[1];
		SwingUtilities.invokeAndWait(() -> hold[0] = new ChroniclePanel(s));
		Field cm = ChroniclePanel.class.getDeclaredField("calendarMonth");
		cm.setAccessible(true);
		cm.set(hold[0], YearMonth.from(first));
		final List<Component> flat = new ArrayList<>();
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				Method m = ChroniclePanel.class.getDeclaredMethod("buildCalendar");
				m.setAccessible(true);
				flatten((Component) m.invoke(hold[0]), flat);
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		List<String> said = new ArrayList<>();
		List<String> hovers = new ArrayList<>();
		for (Component c : flat)
		{
			if (c instanceof JLabel && ((JLabel) c).getText() != null)
			{
				said.add(((JLabel) c).getText());
			}
			if (c instanceof JComponent && ((JComponent) c).getToolTipText() != null)
			{
				hovers.add(((JComponent) c).getToolTipText());
			}
		}
		String title = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.UK).format(first).toUpperCase(Locale.ROOT);
		assertTrue(said.toString(), said.contains(title));
		assertTrue(hovers.toString(), hovers.contains("3h 20m · 1 sitting"));
		assertTrue(hovers.toString(), hovers.contains("50m · 1 sitting"));
		assertTrue(said.toString(), said.contains("2 days written · 4h 10m"));
		// every day of the month is a cell
		int cells = 0;
		for (int d = 1; d <= first.lengthOfMonth(); d++)
		{
			if (said.contains(String.valueOf(d)))
			{
				cells++;
			}
		}
		assertEquals(first.lengthOfMonth(), cells);
		// the first day opens the Journal on it
		JPanel cell = null;
		for (Component c : flat)
		{
			if (c instanceof JPanel && "3h 20m · 1 sitting".equals(((JPanel) c).getToolTipText()))
			{
				cell = (JPanel) c;
			}
		}
		assertNotNull(cell);
		assertEquals(Cursor.HAND_CURSOR, cell.getCursor().getType());
		final JPanel door = cell;
		SwingUtilities.invokeAndWait(() ->
		{
			for (MouseListener l : door.getMouseListeners())
			{
				l.mousePressed(new MouseEvent(door, MouseEvent.MOUSE_PRESSED, 0L, 0, 1, 1, 1, false));
			}
		});
		Field g = ChroniclePanel.class.getDeclaredField("histGranularity");
		g.setAccessible(true);
		assertEquals("Day", g.get(hold[0]));
		Field cur = ChroniclePanel.class.getDeclaredField("histCursor");
		cur.setAccessible(true);
		assertEquals(first, cur.get(hold[0]));
		Field sc = ChroniclePanel.class.getDeclaredField("showCalendar");
		sc.setAccessible(true);
		assertEquals(false, sc.get(hold[0]));
	}

	@Test
	public void theNameplateOpensIt() throws Exception
	{
		PanelPreviewTest.StubPlugin stub = PanelPreviewTest.fixtureStub();
		final ChroniclePanel[] hold = new ChroniclePanel[1];
		SwingUtilities.invokeAndWait(() -> hold[0] = new ChroniclePanel(stub));
		PanelPreviewTest.regatherHistory(hold[0]);
		final JPanel[] door = new JPanel[1];
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				Method m = ChroniclePanel.class.getDeclaredMethod("buildJournal");
				m.setAccessible(true);
				List<Component> flat = new ArrayList<>();
				flatten((Component) m.invoke(hold[0]), flat);
				for (Component c : flat)
				{
					if (c instanceof JPanel && "The days, as a calendar".equals(((JPanel) c).getToolTipText()))
					{
						door[0] = (JPanel) c;
					}
				}
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		assertNotNull("Days written is not a door", door[0]);
		SwingUtilities.invokeAndWait(() ->
		{
			for (MouseListener l : door[0].getMouseListeners())
			{
				l.mousePressed(new MouseEvent(door[0], MouseEvent.MOUSE_PRESSED, 0L, 0, 1, 1, 1, false));
			}
		});
		Field sc = ChroniclePanel.class.getDeclaredField("showCalendar");
		sc.setAccessible(true);
		assertEquals(true, sc.get(hold[0]));
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
