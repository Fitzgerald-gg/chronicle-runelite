/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/**
 * The journey's head under a bounded window counts closed tasks only, which is
 * the rule the store's on-task readers keep (OpenTaskWindowTest): an open task
 * carries its latest kill's stamp, so any window catching one kill would take
 * the whole run. The head admitted it whole and disagreed with the Drops
 * board beside it.
 */
public class NarrowedJourneyTest
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

	private static String killsOnTask(String granularity) throws Exception
	{
		PanelPreviewTest.StubPlugin stub = PanelPreviewTest.fixtureStub();
		double now = System.currentTimeMillis() / 1000.0;
		List<LocalStore.SlayerTask> tasks = new ArrayList<>();
		tasks.add(new LocalStore.SlayerTask("Abyssal demons", 500, 600, 0, now - 60, 900_000L, true));
		tasks.add(new LocalStore.SlayerTask("Nechryael", 20, 0, 0, now - 600, 5_000L, false));
		LocalStore.SlayerJourney j = new LocalStore.SlayerJourney(1, 520, 905_000L, 0, tasks);
		stub.journey = j;
		final String[] out = new String[1];
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				ChroniclePanel p = new ChroniclePanel(stub);
				Field g = ChroniclePanel.class.getDeclaredField("histGranularity");
				g.setAccessible(true);
				g.set(p, granularity);
				Field c = ChroniclePanel.class.getDeclaredField("journeyCache");
				c.setAccessible(true);
				c.set(p, j);
				Method m = ChroniclePanel.class.getDeclaredMethod("buildSlayer");
				m.setAccessible(true);
				out[0] = beside((JPanel) m.invoke(p), "Kills on task");
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		return out[0];
	}

	@Test
	public void aBoundedWindowCountsClosedTasksOnly() throws Exception
	{
		assertEquals("20", killsOnTask("Week"));
	}

	@Test
	public void theWholeRecordCountsTheOpenTaskToo() throws Exception
	{
		assertEquals("520", killsOnTask("Lifetime"));
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
				if (mid instanceof JLabel && left.equals(((JLabel) mid).getText())
					&& east instanceof JLabel)
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
