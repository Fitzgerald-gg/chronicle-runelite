/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.JsonObject;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Nothing is bound to the search box. Every capped list opens from where it
 * stands, on the one "Show N more" control the panel uses; the kill log
 * stopped at twenty and sent the reader to search for the rest, and an
 * item's sources and tasks stopped at forty with a label and no door.
 */
public class EveryListOpensTest
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

	private static JPanel build(ChroniclePanel p, String method, Object... args) throws Exception
	{
		final JPanel[] out = new JPanel[1];
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				Class<?>[] types = new Class<?>[args.length];
				for (int i = 0; i < args.length; i++)
				{
					types[i] = args[i] instanceof String ? String.class : JPanel.class;
				}
				Method m = ChroniclePanel.class.getDeclaredMethod(method, types);
				m.setAccessible(true);
				out[0] = (JPanel) m.invoke(p, args);
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		return out[0];
	}

	private static JPanel column(ChroniclePanel p) throws Exception
	{
		Method m = ChroniclePanel.class.getDeclaredMethod("column");
		m.setAccessible(true);
		return (JPanel) m.invoke(p);
	}

	/** Rows whose left label passes {@code test}, in order. */
	private static List<JPanel> rows(Component c)
	{
		List<Component> flat = new ArrayList<>();
		flatten(c, flat);
		List<JPanel> out = new ArrayList<>();
		for (Component k : flat)
		{
			if (k instanceof JPanel && ((JPanel) k).getLayout() instanceof BorderLayout
				&& ((BorderLayout) ((JPanel) k).getLayout())
				.getLayoutComponent(BorderLayout.CENTER) instanceof JLabel)
			{
				out.add((JPanel) k);
			}
		}
		return out;
	}

	private static String left(JPanel row)
	{
		return ((JLabel) ((BorderLayout) row.getLayout()).getLayoutComponent(BorderLayout.CENTER)).getText();
	}

	private static JPanel rowStarting(Component c, String prefix)
	{
		for (JPanel r : rows(c))
		{
			if (left(r) != null && left(r).startsWith(prefix))
			{
				return r;
			}
		}
		return null;
	}

	private static int rowsStarting(Component c, String prefix)
	{
		int n = 0;
		for (JPanel r : rows(c))
		{
			if (left(r) != null && left(r).startsWith(prefix))
			{
				n++;
			}
		}
		return n;
	}

	private static void press(JPanel row) throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
			for (MouseListener l : row.getMouseListeners())
			{
				l.mousePressed(new MouseEvent(row, MouseEvent.MOUSE_PRESSED, 0L, 0, 1, 1, 1, false));
			}
		});
	}

	private static void assertNoLabelSaying(Component c, String needle)
	{
		List<Component> flat = new ArrayList<>();
		flatten(c, flat);
		for (Component k : flat)
		{
			if (k instanceof JLabel && ((JLabel) k).getText() != null)
			{
				assertFalse(((JLabel) k).getText(), ((JLabel) k).getText().contains(needle));
			}
		}
	}

	@Test
	public void theKillLogOpensToEverySpecies() throws Exception
	{
		PanelPreviewTest.StubPlugin stub = PanelPreviewTest.fixtureStub();
		JsonObject kcs = new JsonObject();
		for (int i = 1; i <= 35; i++)
		{
			kcs.addProperty("Species " + i, 1000 - i);
		}
		stub.clog.add("slayer_kcs", kcs);
		final ChroniclePanel[] hold = new ChroniclePanel[1];
		SwingUtilities.invokeAndWait(() -> hold[0] = new ChroniclePanel(stub));
		ChroniclePanel p = hold[0];
		JPanel capped = build(p, "addKillLog", column(p));
		assertEquals(30, rowsStarting(capped, "Species "));
		assertNoLabelSaying(capped, "earch");
		JPanel more = rowStarting(capped, "Show 5 more");
		assertNotNull("the cap has no door past it", more);
		press(more);
		JPanel opened = build(p, "addKillLog", column(p));
		assertEquals(35, rowsStarting(opened, "Species "));
		assertNull(rowStarting(opened, "Show "));
	}

	@Test
	public void anItemsSourcesOpenToEveryOne() throws Exception
	{
		PanelPreviewTest.StubPlugin stub = PanelPreviewTest.fixtureStub();
		for (int i = 1; i <= 45; i++)
		{
			stub.sources.add(new LocalStore.SourceRow("Source " + i, 10, 10, 1_000L * i, null, 0, 0, java.util.Collections.emptySet(), 0, 0));
			List<LocalStore.BagItem> bag = new ArrayList<>();
			bag.add(new LocalStore.BagItem(995, "Coins", 100L * i, 100L * i));
			stub.bags.put("Source " + i, bag);
		}
		final ChroniclePanel[] hold = new ChroniclePanel[1];
		SwingUtilities.invokeAndWait(() -> hold[0] = new ChroniclePanel(stub));
		ChroniclePanel p = hold[0];
		JPanel capped = build(p, "buildItemDetail", "Coins");
		assertEquals(40, rowsStarting(capped, "Source "));
		JPanel more = rowStarting(capped, "Show 5 more");
		assertNotNull("the cap has no door past it", more);
		press(more);
		JPanel opened = build(p, "buildItemDetail", "Coins");
		assertEquals(45, rowsStarting(opened, "Source "));
		assertNull(rowStarting(opened, "Show "));
	}

	/** And the sweep: no board anywhere hands a list off to the search box. */
	@Test
	public void noBoardHandsAListToTheSearchBox() throws Exception
	{
		String src = new String(java.nio.file.Files.readAllBytes(
			java.nio.file.Paths.get("src/main/java/chronicle/ChroniclePanel.java")),
			java.nio.charset.StandardCharsets.UTF_8);
		assertFalse("a list still ends in a pointer to search",
			src.contains("Search finds") || src.contains("search finds"));
		// and no list ends in an inert "+ N more" tail; the one board that drew
		// it, an on-task picture nothing built, is gone
		int tails = 0;
		int at = -1;
		while ((at = src.indexOf("ghostRow(\"+ \"", at + 1)) >= 0)
		{
			tails++;
		}
		assertEquals("an inert tail with no door past it", 0, tails);
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
