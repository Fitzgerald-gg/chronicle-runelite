/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * The kind lens head names the one item that made the most. Ranked kinds bury
 * it inside whichever kind holds it, and a single unique files under
 * "Everything else" below a bulk kind like Runes.
 */
public class KindLensDearestTest
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

	private static String dearest(List<LocalStore.BagItem> bag) throws Exception
	{
		PanelPreviewTest.StubPlugin stub = PanelPreviewTest.fixtureStub();
		final String[] out = new String[1];
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				ChroniclePanel p = new ChroniclePanel(stub);
				Method column = ChroniclePanel.class.getDeclaredMethod("column");
				column.setAccessible(true);
				Method m = ChroniclePanel.class.getDeclaredMethod("kindLens",
					JPanel.class, String.class, List.class, String.class);
				m.setAccessible(true);
				JPanel board = (JPanel) m.invoke(p, column.invoke(p), "Drops", bag, "test");
				out[0] = beside(board, "Dearest");
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		return out[0];
	}

	@Test
	public void theHeadNamesTheDearestItem() throws Exception
	{
		List<LocalStore.BagItem> bag = new ArrayList<>();
		bag.add(new LocalStore.BagItem(560, "Death rune", 4_000, 800_000L));
		bag.add(new LocalStore.BagItem(4151, "Abyssal whip", 1, 1_500_000L));
		bag.add(new LocalStore.BagItem(995, "Coins", 900_000, 900_000L));
		String said = dearest(bag);
		assertNotNull("no dearest row", said);
		assertEquals("Abyssal whip · 1.5M gp", said);
	}

	@Test
	public void aBagWorthNothingHasNoDearest() throws Exception
	{
		List<LocalStore.BagItem> bag = new ArrayList<>();
		bag.add(new LocalStore.BagItem(526, "Bones", 40, 0L));
		assertNull(dearest(bag));
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
