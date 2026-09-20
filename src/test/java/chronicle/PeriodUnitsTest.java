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

/**
 * Two figures on one row have to be in the same window.
 *
 * <p>What a consumable COST is accumulated forever and is not in the history
 * spine, so there is no windowed version of it. Printed beside a count that IS
 * windowed, it reads as one arithmetic: "Sharks 10 · 4.0M gp" invites the reader
 * to work out a price per shark from a week's eating and a career's spend.
 *
 * <p>Nothing structural can see this. Both figures are present, both are
 * correct on their own, and the row has the same shape either way.
 */
public class PeriodUnitsTest
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

	private static void set(String field, Object v) throws Exception
	{
		Field f = ChroniclePanel.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(panel, v);
	}

	/** The Ledger board's rows, as label text, for a given period. */
	private static List<String> ledgerRows(String granularity) throws Exception
	{
		final List<String> out = new ArrayList<>();
		// A narrowed board reads the history spine, and without this it answers
		// "Reading your history..." instead of any rows at all - which is a board
		// that agrees with every assertion about what it must not print.
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				set("histGranularity", granularity);
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		PanelPreviewTest.regatherHistory(panel);
		PanelPreviewTest.awaitHistory(panel);
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				set("histGranularity", granularity);
				set("statsFamily", "Living");
				Method m = ChroniclePanel.class.getDeclaredMethod("buildStats");
				m.setAccessible(true);
				List<Component> flat = new ArrayList<>();
				flatten((Component) m.invoke(panel), flat);
				for (Component c : flat)
				{
					if (c instanceof JLabel && ((JLabel) c).getText() != null)
					{
						out.add(((JLabel) c).getText());
					}
				}
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

	private static boolean anyCarriesGp(List<String> said)
	{
		for (String s : said)
		{
			if (s.contains(" · ") && s.endsWith(" gp"))
			{
				return true;
			}
		}
		return false;
	}

	@Test
	public void onLifetimeAConsumableRowCarriesWhatItCost() throws Exception
	{
		assertTrue("the lifetime board should pair a count with its lifetime gp",
			anyCarriesGp(ledgerRows("Lifetime")));
	}

	@Test
	public void onANarrowedPeriodItDoesNot() throws Exception
	{
		List<String> said = ledgerRows("Month");
		// The board has to have drawn something, or "prints no gp" is satisfied by
		// a board that prints nothing.
		assertTrue("the narrowed board drew no rows at all: " + said,
			said.contains("FOOD") || said.contains("Doses drunk"));
		assertFalse("a lifetime gp figure is riding a period count: " + said,
			anyCarriesGp(said));
	}
}
