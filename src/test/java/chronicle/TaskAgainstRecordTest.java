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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * One task read against the account's own record of the same assignment:
 * what it usually pays and the best it ever did, from the closed tasks before
 * it. An average of one is not a usual.
 */
public class TaskAgainstRecordTest
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

	private static JPanel page(List<LocalStore.SlayerTask> tasks, int index) throws Exception
	{
		PanelPreviewTest.StubPlugin stub = PanelPreviewTest.fixtureStub();
		LocalStore.SlayerJourney j = new LocalStore.SlayerJourney(tasks.size(), 0, 0, 0, tasks);
		stub.journey = j;
		final JPanel[] out = new JPanel[1];
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				ChroniclePanel p = new ChroniclePanel(stub);
				Field c = ChroniclePanel.class.getDeclaredField("journeyCache");
				c.setAccessible(true);
				c.set(p, j);
				Method m = ChroniclePanel.class.getDeclaredMethod("buildTaskDetail", int.class);
				m.setAccessible(true);
				out[0] = (JPanel) m.invoke(p, index);
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		return out[0];
	}

	private static LocalStore.SlayerTask task(String name, long kills, long value, long daysAgo, boolean open)
	{
		double ts = System.currentTimeMillis() / 1000.0 - daysAgo * 86_400.0;
		return new LocalStore.SlayerTask(name, kills, 0, 0, ts, value, open);
	}

	@Test
	public void theUsualAndTheBestComeFromTheEarlierTasks() throws Exception
	{
		List<LocalStore.SlayerTask> tasks = new ArrayList<>();
		tasks.add(task("Nechryael", 174, 1_300_000L, 0, false));   // this one, newest first
		tasks.add(task("Abyssal demons", 200, 900_000L, 3, false)); // another assignment
		tasks.add(task("Nechryael", 175, 2_100_000L, 10, false));
		tasks.add(task("Nechryael", 170, 1_200_000L, 20, false));
		tasks.add(task("Nechryael", 160, 1_000_000L, 30, false));
		JPanel page = page(tasks, 0);
		assertEquals("1.4M gp · 168 kills · over 3 tasks", beside(page, "Usual"));
		String best = beside(page, "Best");
		assertTrue(best, best.startsWith("2.1M gp · "));
	}

	/** Only the CLOSED tasks BEFORE this one, and only of the same assignment. */
	@Test
	public void anOpenTaskAndALaterOneAreNoPartOfTheUsual() throws Exception
	{
		List<LocalStore.SlayerTask> tasks = new ArrayList<>();
		tasks.add(task("Nechryael", 500, 9_000_000L, 0, true));      // still running
		tasks.add(task("Nechryael", 174, 1_300_000L, 5, false));     // the one opened
		tasks.add(task("Nechryael", 175, 2_100_000L, 10, false));
		tasks.add(task("Nechryael", 170, 1_200_000L, 20, false));
		JPanel page = page(tasks, 1);
		// the open one above it and the task itself are both out: 2 earlier, not 3
		assertEquals("1.7M gp · 172 kills · over 2 tasks", beside(page, "Usual"));
	}

	/** The oldest task of an assignment has nothing to be read against. */
	@Test
	public void theFirstOfAnAssignmentHasNoUsual() throws Exception
	{
		List<LocalStore.SlayerTask> tasks = new ArrayList<>();
		tasks.add(task("Nechryael", 174, 1_300_000L, 0, false));
		tasks.add(task("Nechryael", 175, 2_100_000L, 10, false));
		tasks.add(task("Nechryael", 170, 1_200_000L, 20, false));
		assertNull(beside(page(tasks, 2), "Usual"));
	}

	@Test
	public void oneEarlierTaskIsNoUsual() throws Exception
	{
		List<LocalStore.SlayerTask> tasks = new ArrayList<>();
		tasks.add(task("Nechryael", 174, 1_300_000L, 0, false));
		tasks.add(task("Nechryael", 160, 1_000_000L, 30, false));
		JPanel page = page(tasks, 0);
		assertNull(beside(page, "Usual"));
		assertNull(beside(page, "Best"));
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
