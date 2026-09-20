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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Received and Left behind are one board read two ways.
 *
 * <p>They did not look like it. Received drew two-line source cards under an
 * axis strip; Left behind drew single-line rows, in red, under no axis at all,
 * with its two lists stacked one below the other - the scroll-to-discover this
 * panel does not do anywhere else. And the same lens drew in two palettes a
 * click apart, because the windowed path never used the red the lifetime path
 * put on every row.
 */
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

	/** Both readings carry the second axis, and it is the same control. */
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

	/** And the axis names what that side actually holds. */
	@Test
	public void theSecondAxisNamesWhatEachSideHolds() throws Exception
	{
		assertTrue("Received's other axis is kinds",
			exactly(texts(drops(false, true)), "By kind"));
		assertTrue("Left behind's other axis is items",
			exactly(texts(drops(true, true)), "By item"));
	}

	/**
	 * One list at a time. Left behind used to draw sources AND items stacked,
	 * which is the one place in the panel a reader had to scroll to find a view
	 * rather than choose it.
	 */
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

	/**
	 * Red is the head's, not every row's. A colour on every row of a list says
	 * nothing the list does not already say, and it was the thing that made one
	 * lens read in two palettes depending on the period.
	 */
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

	/** The two readings draw the same shape: a head card, then cards. */
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
