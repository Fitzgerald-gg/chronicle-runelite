/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LootMirrorTest
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

	private static void set(String f, Object v) throws Exception
	{
		Field fd = ChroniclePanel.class.getDeclaredField(f);
		fd.setAccessible(true);
		fd.set(panel, v);
	}

	private static List<Component> drops(boolean left, boolean second) throws Exception
	{
		final List<Component> out = new ArrayList<>();
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				set("histGranularity", "Lifetime");
				set("histFrom", null);
				set("dropsLeftBehind", left);
				set("dropsByKind", second);
				set("lootKind", null);
				Method m = ChroniclePanel.class.getDeclaredMethod("buildDrops");
				m.setAccessible(true);
				flatten((Component) m.invoke(panel), out);
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		return out;
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

	private static List<String> texts(List<Component> cs)
	{
		List<String> out = new ArrayList<>();
		for (Component c : cs)
		{
			if (c instanceof JLabel && ((JLabel) c).getText() != null)
			{
				out.add(((JLabel) c).getText());
			}
		}
		return out;
	}

	private static boolean exactly(List<String> said, String label)
	{
		for (String s : said)
		{
			if (label.equals(s))
			{
				return true;
			}
		}
		return false;
	}

	@Test
	public void bothSidesOfTheCoinOfferTheSameAxes() throws Exception
	{
		List<String> received = texts(drops(false, false));
		assertTrue(received.toString(), exactly(received, "Received"));
		assertTrue("Received lost its second axis", exactly(received, "By source"));

		List<String> left = texts(drops(true, false));
		assertTrue(left.toString(), exactly(left, "Left behind"));
		assertTrue("Left behind was drawn without the axis Received has",
			exactly(left, "By source"));
	}

	@Test
	public void theSecondAxisNamesWhatEachSideHolds() throws Exception
	{
		assertTrue("Received's other axis is kinds",
			exactly(texts(drops(false, true)), "By kind"));
		assertTrue("Left behind's other axis is items",
			exactly(texts(drops(true, true)), "By item"));
	}

	@Test
	public void leftBehindDrawsOneListAtATime() throws Exception
	{
		List<String> bySource = texts(drops(true, false));
		assertTrue(bySource.toString(), exactly(bySource, "Sources"));
		assertFalse("both lists were drawn at once", exactly(bySource, "Distinct items"));

		List<String> byItem = texts(drops(true, true));
		assertTrue(byItem.toString(), exactly(byItem, "Distinct items"));
		assertFalse(exactly(byItem, "Sources"));
	}

	@Test
	public void redBelongsToTheHeadAndNotToEveryRow() throws Exception
	{
		Field f = ChroniclePanel.class.getDeclaredField("ACCENT_RED");
		f.setAccessible(true);
		java.awt.Color red = (java.awt.Color) f.get(null);

		int reds = 0;
		for (Component c : drops(true, false))
		{
			if (c instanceof JLabel && red.equals(c.getForeground()))
			{
				reds++;
			}
		}
		assertTrue("nothing was marked at all", reds > 0);
		assertTrue("red is on every row rather than on the board's head: " + reds,
			reds <= 12);
	}

	@Test
	public void bothReadingsDrawTheSameShape() throws Exception
	{
		List<String> received = texts(drops(false, false));
		List<String> left = texts(drops(true, false));
		for (String head : new String[]{"Worth"})
		{
			assertTrue("Left behind's head is not Received's: " + left,
				exactly(left, head));
		}
		assertTrue("Received lost its head: " + received, exactly(received, "Worth"));
	}
}
