/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import chronicle.panel.StatRegistry;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
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

	@Test
	public void theSourcePageSaysHowLongWasSpentThere() throws Exception
	{
		PanelPreviewTest.StubPlugin stub = PanelPreviewTest.fixtureStub();
		stub.lifetime.put("timeAbyssalDemons", 860L);
		final JPanel[] page = new JPanel[1];
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				ChroniclePanel p = new ChroniclePanel(stub);
				Method m = ChroniclePanel.class.getDeclaredMethod("buildSourceDetail", String.class);
				m.setAccessible(true);
				page[0] = (JPanel) m.invoke(p, "Abyssal demons");
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		String here = beside(page[0], "Time here");
		assertTrue(String.valueOf(here), here != null && here.startsWith("14h 20m · ")
			&& here.endsWith(" kills an hour"));
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
