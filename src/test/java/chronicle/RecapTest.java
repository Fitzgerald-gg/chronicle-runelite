/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The Recap: the period as one plate under Record, every row a figure and
 * a door to the board it was read off. Composed, not described: there is no
 * sentence on it, so there is nothing to sound like anyone.
 */
public class RecapTest
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

	private static Object field(ChroniclePanel p, String name) throws Exception
	{
		Field f = ChroniclePanel.class.getDeclaredField(name);
		f.setAccessible(true);
		return f.get(p);
	}

	private static JPanel recap(ChroniclePanel p, String granularity) throws Exception
	{
		final JPanel[] out = new JPanel[1];
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				Field g = ChroniclePanel.class.getDeclaredField("histGranularity");
				g.setAccessible(true);
				g.set(p, granularity);
				Method m = ChroniclePanel.class.getDeclaredMethod("buildRecap");
				m.setAccessible(true);
				out[0] = (JPanel) m.invoke(p);
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		return out[0];
	}

	@Test
	@SuppressWarnings("unchecked")
	public void recapIsASubTabOfTheRecord() throws Exception
	{
		Field f = ChroniclePanel.class.getDeclaredField("SUBS");
		f.setAccessible(true);
		Map<Object, String[]> subs = (Map<Object, String[]>) f.get(null);
		for (Map.Entry<Object, String[]> e : subs.entrySet())
		{
			if ("RECORD".equals(e.getKey().toString()))
			{
				assertTrue(Arrays.toString(e.getValue()), Arrays.asList(e.getValue()).contains("Recap"));
				return;
			}
		}
		throw new AssertionError("no RECORD tab");
	}

	@Test
	public void theWholeRecordsPlateIsFiguresAndDoors() throws Exception
	{
		PanelPreviewTest.StubPlugin stub = PanelPreviewTest.fixtureStub();
		final ChroniclePanel[] hold = new ChroniclePanel[1];
		SwingUtilities.invokeAndWait(() -> hold[0] = new ChroniclePanel(stub));
		PanelPreviewTest.regatherHistory(hold[0]);
		JPanel plate = recap(hold[0], "Lifetime");
		List<JPanel> rows = doors(plate);
		assertTrue("the plate has no rows", rows.size() >= 3);
		String drops = beside(plate, "Drops");
		assertNotNull("no Drops row", drops);
		long loots = 0;
		for (LocalStore.SourceRow r : stub.sources)
		{
			loots += r.loots;
		}
		assertTrue(drops, drops.startsWith(String.format(java.util.Locale.UK, "%,d", loots) + " · "));
		assertNotNull("no Killed most row", beside(plate, "Killed most"));
		assertNotNull("no Pet row", beside(plate, "Pet"));
		// every row is a door
		List<String> said = new ArrayList<>();
		collect(plate, said);
		for (JPanel r : rows)
		{
			assertEquals(left(r), Cursor.HAND_CURSOR, r.getCursor().getType());
		}
		// and no row carries a sentence: figures joined by the panel's own dot
		for (JPanel r : rows)
		{
			String right = right(r);
			assertTrue(right, !right.endsWith(".") && !right.contains("  "));
		}
	}

	@Test
	public void aDoorOpensTheBoardItWasReadOff() throws Exception
	{
		PanelPreviewTest.StubPlugin stub = PanelPreviewTest.fixtureStub();
		final ChroniclePanel[] hold = new ChroniclePanel[1];
		SwingUtilities.invokeAndWait(() -> hold[0] = new ChroniclePanel(stub));
		PanelPreviewTest.regatherHistory(hold[0]);
		JPanel plate = recap(hold[0], "Lifetime");
		JPanel pet = rowNamed(plate, "Pet");
		assertNotNull(pet);
		SwingUtilities.invokeAndWait(() ->
		{
			for (MouseListener l : pet.getMouseListeners())
			{
				l.mousePressed(new MouseEvent(pet, MouseEvent.MOUSE_PRESSED, 0L, 0, 1, 1, 1, false));
			}
		});
		assertEquals("Feats", field(hold[0], "journalLens"));
		assertEquals("JOURNAL", String.valueOf(field(hold[0], "view")));
	}

	@Test
	public void anEmptyPeriodSaysSoOnce() throws Exception
	{
		PanelPreviewTest.StubPlugin stub = new PanelPreviewTest.StubPlugin(null);
		final ChroniclePanel[] hold = new ChroniclePanel[1];
		SwingUtilities.invokeAndWait(() -> hold[0] = new ChroniclePanel(stub));
		JPanel plate = recap(hold[0], "Week");
		List<String> said = new ArrayList<>();
		collect(plate, said);
		assertTrue(said.toString(), said.stream().anyMatch(s -> s.startsWith("Nothing inside ")));
		assertEquals(0, doors(plate).size());
	}

	private static List<JPanel> doors(Component c)
	{
		List<Component> flat = new ArrayList<>();
		flatten(c, flat);
		List<JPanel> out = new ArrayList<>();
		for (Component k : flat)
		{
			if (k instanceof JPanel && ((JPanel) k).getLayout() instanceof BorderLayout
				&& k.getCursor().getType() == Cursor.HAND_CURSOR && left((JPanel) k) != null
				&& !left((JPanel) k).equals("Recap"))
			{
				out.add((JPanel) k);
			}
		}
		return out;
	}

	private static String left(JPanel r)
	{
		Component mid = ((BorderLayout) r.getLayout()).getLayoutComponent(BorderLayout.CENTER);
		return mid instanceof JLabel ? ((JLabel) mid).getText() : null;
	}

	private static String right(JPanel r)
	{
		Component east = ((BorderLayout) r.getLayout()).getLayoutComponent(BorderLayout.EAST);
		return east instanceof JLabel ? ((JLabel) east).getText() : "";
	}

	private static JPanel rowNamed(Component c, String l)
	{
		List<Component> flat = new ArrayList<>();
		flatten(c, flat);
		for (Component k : flat)
		{
			if (k instanceof JPanel && ((JPanel) k).getLayout() instanceof BorderLayout && l.equals(left((JPanel) k)))
			{
				return (JPanel) k;
			}
		}
		return null;
	}

	private static String beside(Component c, String l)
	{
		JPanel r = rowNamed(c, l);
		return r == null ? null : right(r);
	}

	private static void collect(Component c, List<String> out)
	{
		if (c instanceof JLabel && ((JLabel) c).getText() != null)
		{
			out.add(((JLabel) c).getText());
		}
		if (c instanceof Container)
		{
			for (Component k : ((Container) c).getComponents())
			{
				collect(k, out);
			}
		}
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
