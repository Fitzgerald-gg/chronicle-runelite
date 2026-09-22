/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import chronicle.panel.StatRegistry;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.MouseEvent;
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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The minutes the trackers file under a fight or a craft reach the pages
 * that divide them, and nowhere else: a source page says how long was spent
 * there and the kills an hour it came to; a skill page the same for xp.
 */
public class TimeRowsTest
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


	/**
	 * And on the whole record it says the hours and no rate: the minutes began
	 * the day the tracker did, and the kills beside them are a career's.
	 */
	@Test
	public void aLifetimeHasNoRateToDivide() throws Exception
	{
		PanelPreviewTest.StubPlugin stub = PanelPreviewTest.fixtureStub();
		stub.lifetime.put("timeAbyssalDemons", 860L);
		assertEquals("14h 20m", beside(page(stub, "Lifetime"), "Time here"));
	}

	/** A window whose opening line already carried the minutes can divide. */
	@Test
	public void aWindowInsideTheMinutesEraReadsItsRate() throws Exception
	{
		PanelPreviewTest.StubPlugin stub = PanelPreviewTest.fixtureStub();
		LocalDate cursor = LocalDate.of(2026, 6, 15);
		stub.history.clear();
		stub.history.put(cursor.withDayOfMonth(5), era(100L));
		stub.history.put(cursor.withDayOfMonth(13), era(160L));
		LocalStore.LootWindow w = new LocalStore.LootWindow();
		w.sources.add(new String[]{"Abyssal demons", "24", "812400"});
		stub.lootWindow = w;
		assertEquals("1h 0m · 24 kills/h", beside(page(stub, "Month", cursor), "Time here"));
	}

	/**
	 * And one that opened before the minutes did says nothing at all: a counter
	 * absent from the opening line was never recorded rather than zero, so the
	 * spine drops it, and the record does not guess at what it cannot date.
	 */
	@Test
	public void aWindowStraddlingTheFirstMinuteSaysNothing() throws Exception
	{
		PanelPreviewTest.StubPlugin stub = PanelPreviewTest.fixtureStub();
		LocalDate cursor = LocalDate.of(2026, 6, 15);
		stub.history.clear();
		HistoryLog.Baseline before = era(0L);
		before.counters.remove("timeAbyssalDemons");
		stub.history.put(cursor.withDayOfMonth(5), before);
		stub.history.put(cursor.withDayOfMonth(13), era(60L));
		LocalStore.LootWindow w = new LocalStore.LootWindow();
		w.sources.add(new String[]{"Abyssal demons", "24", "812400"});
		stub.lootWindow = w;
		assertNull(beside(page(stub, "Month", cursor), "Time here"));
	}

	private static HistoryLog.Baseline era(long minutes)
	{
		HistoryLog.Baseline b = new HistoryLog.Baseline();
		b.skills.put("attack", 1_000_000L);
		b.skills.put("overall", 1_000_000L);
		b.counters.put("timeAbyssalDemons", minutes);
		b.complete = true;
		return b;
	}

	private static JPanel page(PanelPreviewTest.StubPlugin stub, String granularity) throws Exception
	{
		return page(stub, granularity, null);
	}

	private static JPanel page(PanelPreviewTest.StubPlugin stub, String granularity, LocalDate cursor)
		throws Exception
	{
		final ChroniclePanel[] hold = new ChroniclePanel[1];
		SwingUtilities.invokeAndWait(() -> hold[0] = new ChroniclePanel(stub));
		PanelPreviewTest.regatherHistory(hold[0]);
		final JPanel[] out = new JPanel[1];
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				Field g = ChroniclePanel.class.getDeclaredField("histGranularity");
				g.setAccessible(true);
				g.set(hold[0], granularity);
				if (cursor != null)
				{
					Field c = ChroniclePanel.class.getDeclaredField("histCursor");
					c.setAccessible(true);
					c.set(hold[0], cursor);
				}
				Method m = ChroniclePanel.class.getDeclaredMethod("buildSourceDetail", String.class);
				m.setAccessible(true);
				out[0] = (JPanel) m.invoke(hold[0], "Abyssal demons");
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		return out[0];
	}

	@Test
	public void theMinutesAreNeverRowsOfTheirOwn()
	{
		assertTrue(StatRegistry.hidden("timeAbyssalDemons"));
		assertTrue(StatRegistry.hidden("timeIdle"));
		assertEquals(false, StatRegistry.hidden("timesLooted"));
	}

	private static String beside(Component c, String left)
	{
		List<Component> flat = new ArrayList<>();
		flatten(c, flat);
		for (Component k : flat)
		{
			if (k instanceof JPanel && ((JPanel) k).getLayout() instanceof BorderLayout)
			{
				BorderLayout l = (BorderLayout) ((JPanel) k).getLayout();
				Component mid = l.getLayoutComponent(BorderLayout.CENTER);
				Component east = l.getLayoutComponent(BorderLayout.EAST);
				if (mid instanceof JLabel && left.equals(((JLabel) mid).getText()) && east instanceof JLabel)
				{
					return ((JLabel) east).getText();
				}
			}
		}
		return null;
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
