/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import javax.swing.SwingUtilities;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The sitting, as a period the whole panel can be read through.
 *
 * <p>Every other period is a date range, measured as the distance between two
 * of the history spine's closed baselines. The sitting has no closing baseline,
 * because it has not closed, and it needs none: the counters ARE the session,
 * exactly, with nothing subtracted from anything.
 */
public class SessionPeriodTest
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

	private static void period(String g) throws Exception
	{
		Field f = ChroniclePanel.class.getDeclaredField("histGranularity");
		f.setAccessible(true);
		f.set(panel, g);
		Field from = ChroniclePanel.class.getDeclaredField("histFrom");
		from.setAccessible(true);
		from.set(panel, null);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Long> countersNow() throws Exception
	{
		Method m = ChroniclePanel.class.getDeclaredMethod("countersForPeriod");
		m.setAccessible(true);
		return (Map<String, Long>) m.invoke(panel);
	}

	private static String label() throws Exception
	{
		Method m = ChroniclePanel.class.getDeclaredMethod("window");
		m.setAccessible(true);
		Object w = m.invoke(panel);
		Field l = w.getClass().getDeclaredField("label");
		l.setAccessible(true);
		return (String) l.get(w);
	}

	@Test
	public void itIsOfferedAlongsideTheOthers()
	{
		assertEquals(Arrays.asList("Lifetime", "Year", "Month", "Week", "Day", "Session"),
			Arrays.asList(ChroniclePanel.PERIODS));
	}

	@Test
	public void itNamesItselfInPlainWords() throws Exception
	{
		period("Session");
		assertEquals("This session", label());
	}

	/**
	 * The figures come straight off the session counters, so they are exact and
	 * need no baseline. A spine-derived period returns null when it has fewer
	 * than two baselines to measure between; this one never can.
	 */
	@Test
	public void itsFiguresAreTheSessionItselfNotADifferenceOfBaselines() throws Exception
	{
		period("Session");
		Map<String, Long> said = countersNow();
		assertTrue("the sitting answered with nothing at all", said != null);

		Map<String, Integer> raw = PanelPreviewTest.fixtureStub().sessionCounters();
		for (Map.Entry<String, Integer> e : raw.entrySet())
		{
			if (e.getValue() != null && e.getValue() != 0)
			{
				assertEquals("counter " + e.getKey() + " is not the session's own",
					Long.valueOf(e.getValue().longValue()), said.get(e.getKey()));
			}
		}
	}

	/** A counter at zero this sitting is absent, not a row reading nought. */
	@Test
	public void aCounterUntouchedThisSittingIsNotCarried() throws Exception
	{
		period("Session");
		for (Map.Entry<String, Long> e : countersNow().entrySet())
		{
			assertTrue(e.getKey() + " was carried at zero", e.getValue() != 0);
		}
	}

	/** And it is not the whole record, however short the sitting has been. */
	@Test
	public void itIsNotMistakenForLifetime() throws Exception
	{
		period("Session");
		Method whole = ChroniclePanel.class.getDeclaredMethod("wholeRecord");
		whole.setAccessible(true);
		assertFalse((Boolean) whole.invoke(panel));
	}

	/**
	 * No arrows. There is one sitting and it is this one; stepping the cursor off
	 * it would name another day and go on calling it "This session".
	 */
	@Test
	public void thereIsNoSteppingToAPreviousSitting() throws Exception
	{
		period("Session");
		final List<String> labels = new java.util.ArrayList<>();
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				Method m = ChroniclePanel.class.getDeclaredMethod("periodRow");
				m.setAccessible(true);
				java.awt.Component row = (java.awt.Component) m.invoke(panel);
				java.util.List<java.awt.Component> flat = new java.util.ArrayList<>();
				flatten(row, flat);
				for (java.awt.Component c : flat)
				{
					if (c instanceof javax.swing.JLabel)
					{
						labels.add(((javax.swing.JLabel) c).getText());
					}
				}
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		assertFalse("the sitting offered a step backwards: " + labels,
			labels.contains("<"));
		assertFalse(labels.contains(">"));
		assertTrue(labels.contains("This session"));
	}

	private static void flatten(java.awt.Component c, List<java.awt.Component> out)
	{
		out.add(c);
		if (c instanceof java.awt.Container)
		{
			for (java.awt.Component k : ((java.awt.Container) c).getComponents())
			{
				flatten(k, out);
			}
		}
	}
}
