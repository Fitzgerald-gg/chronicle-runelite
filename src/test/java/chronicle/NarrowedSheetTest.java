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
import net.runelite.client.ui.ColorScheme;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * What a narrowed period is allowed to show, on the two grids of the sheet.
 *
 * <p>Both were drawing a LIFETIME under a heading naming a week. The boss grid
 * is seventy strong and nobody kills seventy things in a week, so every period
 * but the lifetime drew a handful of counts in a field of dashes and the dashes
 * were the board. The activity tiles carry standings - every clue ever opened,
 * the whole collection log - and a standing that stands still under a heading
 * naming a week reads as the week's work.
 *
 * <p>So the boss grid shows what the period holds, and an activity tile the
 * period never touched reads dim, the same way a skill that gained nothing
 * does. A lifetime is exempt from both: there the roster is a checklist of what
 * the account has and has not met, which is a different question, and every
 * standing is by construction a thing the lifetime moved.
 */
public class NarrowedSheetTest
{
	private static final java.awt.Color DIM = ColorScheme.LIGHT_GRAY_COLOR.darker();
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
		PanelPreviewTest.regatherHistory(panel);
		PanelPreviewTest.awaitHistory(panel);
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

	private static List<Component> board(String method) throws Exception
	{
		final List<Component> flat = new ArrayList<>();
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				Method m = ChroniclePanel.class.getDeclaredMethod(method);
				m.setAccessible(true);
				flatten((Component) m.invoke(panel), flat);
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		return flat;
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

	private static int dashes(List<Component> flat)
	{
		int n = 0;
		for (Component c : flat)
		{
			if (c instanceof JLabel && "-".equals(((JLabel) c).getText()))
			{
				n++;
			}
		}
		return n;
	}

	/**
	 * TRAP: a dash is not a small thing said quietly, it is the board. Seventy of
	 * them say one thing seventy times and bury the three counts that are the
	 * answer.
	 */
	@Test
	public void aNarrowedBossGridDrawsOnlyWhatItHolds() throws Exception
	{
		period("Lifetime");
		int wholeRoster = board("buildKills").size();

		period("Week");
		List<Component> week = board("buildKills");
		assertTrue("a narrowed period still mounts the whole roster: "
			+ week.size() + " components against the lifetime's " + wholeRoster,
			week.size() < wholeRoster);
		assertTrue("the narrowed board is still mostly dashes: " + dashes(week),
			dashes(week) <= 2);
	}

	/**
	 * And the lifetime keeps every one of them, dash and all: there the grid is a
	 * checklist of what this account has and has not met. Whole, not a glance
	 * with the rest behind a click: the owner did not agree to it collapsing.
	 */
	@Test
	public void theLifetimeKeepsTheWholeRoster() throws Exception
	{
		period("Lifetime");
		assertTrue("the lifetime lost the bosses it has never killed, which is the"
			+ " half of that board a reader is looking for",
			dashes(board("buildKills")) > 10);
		for (Component c : board("buildKills"))
		{
			if (c instanceof JLabel && ((JLabel) c).getText() != null)
			{
				assertFalse("the roster is collapsed behind " + ((JLabel) c).getText(),
					((JLabel) c).getText().startsWith("Show "));
			}
		}
	}

	/**
	 * An activity tile's figure and the colour it is drawn in, as {text, colour},
	 * or null if the tile is not on the sheet.
	 *
	 * <p>BOTH, because a tile with nothing behind it draws a dash and is dim for
	 * that reason alone. Asserting the colour without the text passes over an
	 * empty tile whatever the rule does, which is how this test first went green
	 * against a build that had the rule switched off.
	 */
	private static Object[] tile(String name) throws Exception
	{
		final Object[][] found = {null};
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				Method m = ChroniclePanel.class.getDeclaredMethod("activitySheet");
				m.setAccessible(true);
				List<Component> flat = new ArrayList<>();
				flatten((Component) m.invoke(panel), flat);
				// the tile is a row: its icon, then its figure. Find the figure by
				// walking to the cell whose tooltip names this activity.
				for (Component c : flat)
				{
					if (!(c instanceof javax.swing.JComponent))
					{
						continue;
					}
					String tip = ((javax.swing.JComponent) c).getToolTipText();
					if (tip == null || !tip.contains(name))
					{
						continue;
					}
					List<Component> inner = new ArrayList<>();
					flatten(c, inner);
					for (Component k : inner)
					{
						if (k instanceof JLabel && ((JLabel) k).getText() != null
							&& !((JLabel) k).getText().isEmpty())
						{
							found[0] = new Object[]{((JLabel) k).getText(),
								k.getForeground()};
							return;
						}
					}
				}
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		return found[0];
	}

	private static void lit(String name, boolean want) throws Exception
	{
		Object[] t = tile(name);
		assertTrue(name + " is not on the sheet", t != null);
		assertFalse(name + " carries no figure, so its colour proves nothing: "
			+ t[0], "-".equals(t[0]));
		assertTrue(name + " reads " + (want ? "dim" : "lit") + " at " + t[0]
			+ ", which is the wrong way round", want != DIM.equals(t[1]));
	}

	/**
	 * TRAP: the tile keeps its STANDING figure either way, so the two states are
	 * the same shape and the same number. Only the colour tells them apart, which
	 * is exactly the kind of rule a structural test cannot see.
	 */
	@Test
	public void anActivityThePeriodNeverTouchedReadsDim() throws Exception
	{
		// A lifetime moved everything it holds, by construction.
		period("Lifetime");
		lit("Collection log", true);
		lit("Rifts closed", true);

		// Both readings inside ONE period, which is the comparison that matters.
		// The fixture logged a collection slot two hours ago and closed no rift
		// today, so the day moved one of these and not the other - and the rift
		// tile still SAYS five thousand two hundred and eighteen either way.
		period("Day");
		lit("Collection log", true);
		lit("Rifts closed", false);
		lit("Soul Wars", false);
	}
}
