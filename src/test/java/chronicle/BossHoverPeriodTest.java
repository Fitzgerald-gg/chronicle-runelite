/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import javax.swing.SwingUtilities;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * One hover rule for the whole sheet: the period first, then the lifetime.
 *
 * <p>Skill hovers answered for the period; a boss hover answered for the
 * lifetime with no word saying so, under a cell printing the window's kills.
 */
public class BossHoverPeriodTest
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

	/** The card, and the window's own label after it. */
	private static String[] tip(String granularity) throws Exception
	{
		PanelPreviewTest.StubPlugin stub = PanelPreviewTest.fixtureStub();
		final String[] out = new String[2];
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				ChroniclePanel p = new ChroniclePanel(stub);
				Field g = ChroniclePanel.class.getDeclaredField("histGranularity");
				g.setAccessible(true);
				g.set(p, granularity);
				Class<?> boss = Class.forName("chronicle.ChroniclePanel$Boss");
				Constructor<?> c = boss.getDeclaredConstructor(String.class, int.class);
				c.setAccessible(true);
				Method m = ChroniclePanel.class.getDeclaredMethod("bossTip", boss);
				m.setAccessible(true);
				out[0] = (String) m.invoke(p, c.newInstance("Commander Zilyana", 0));
				Method w = ChroniclePanel.class.getDeclaredMethod("window");
				w.setAccessible(true);
				Object window = w.invoke(p);
				Field label = window.getClass().getDeclaredField("label");
				label.setAccessible(true);
				out[1] = (String) label.get(window);
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		return out;
	}

	@Test
	public void aNarrowedBoardLeadsWithThePeriod() throws Exception
	{
		String[] card = tip("Week");
		String tip = card[0];
		int period = tip.indexOf(card[1] + ":");
		int lifetime = tip.indexOf("Kills tracked:");
		assertTrue("the period is not on the card: " + tip, period >= 0);
		assertTrue("the lifetime comes before the period: " + tip, period < lifetime);
	}

	@Test
	public void theWholeRecordHasNoPeriodToLeadWith() throws Exception
	{
		String[] card = tip("Lifetime");
		String tip = card[0];
		assertFalse(tip, tip.contains(card[1] + ":"));
		assertTrue(tip, tip.contains("Kills tracked:"));
	}
}
