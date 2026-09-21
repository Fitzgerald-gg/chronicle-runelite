/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.JsonObject;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Search, as one thing: the list is the resolver.
 *
 * <p>Enter opens the first row on screen, and nothing else. A second resolver
 * behind the key, with an order of its own, sent Enter somewhere the reader
 * could not see. A source named exactly stands before the items named after
 * it, a journal hit opens on its day, a miss names the nearest thing, and the
 * period strip says a search reads the whole record.
 */
public class SearchDoorsTest
{
	private PanelPreviewTest.StubPlugin stub;
	private ChroniclePanel panel;

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

	@Before
	public void build() throws Exception
	{
		stub = PanelPreviewTest.fixtureStub();
		stub.sources.add(new LocalStore.SourceRow("Zulrah", 300, 300, 50_000_000L, null, 0, 0));
		List<LocalStore.BagItem> scales = new ArrayList<>();
		scales.add(new LocalStore.BagItem(12934, "Zulrah's scales", 30_000, 6_000_000L));
		stub.bags.put("Zulrah", scales);
		SwingUtilities.invokeAndWait(() -> panel = new ChroniclePanel(stub));
	}

	private JPanel search(String q) throws Exception
	{
		final JPanel[] out = new JPanel[1];
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				Method m = ChroniclePanel.class.getDeclaredMethod("buildSearch", String.class);
				m.setAccessible(true);
				out[0] = (JPanel) m.invoke(panel, q);
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		return out[0];
	}

	private Object field(String name) throws Exception
	{
		Field f = ChroniclePanel.class.getDeclaredField(name);
		f.setAccessible(true);
		return f.get(panel);
	}

	/** The first row drawn with a hand on it, which is where Enter lands. */
	private static JPanel firstDoor(Component c)
	{
		List<Component> flat = new ArrayList<>();
		flatten(c, flat);
		for (Component k : flat)
		{
			if (k instanceof JPanel && ((JPanel) k).getLayout() instanceof BorderLayout
				&& k.getCursor().getType() == Cursor.HAND_CURSOR)
			{
				return (JPanel) k;
			}
		}
		return null;
	}

	private static String left(JPanel row)
	{
		Component mid = ((BorderLayout) row.getLayout()).getLayoutComponent(BorderLayout.CENTER);
		return mid instanceof JLabel ? ((JLabel) mid).getText() : null;
	}

	private static String beside(Component c, String leftText)
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
				if (mid instanceof JLabel && leftText.equals(((JLabel) mid).getText())
					&& east instanceof JLabel)
				{
					return ((JLabel) east).getText();
				}
			}
		}
		return null;
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

	@Test
	public void enterOpensTheFirstRowOnScreen() throws Exception
	{
		JPanel board = search("nechryael");
		JPanel first = firstDoor(board);
		assertNotNull("nothing on the board opens", first);
		assertEquals("Nechryael", left(first));
		Runnable enter = (Runnable) field("searchFirst");
		assertNotNull("Enter has nowhere to go", enter);
		SwingUtilities.invokeAndWait(enter);
		assertEquals("Nechryael", field("detailSource"));
	}

	@Test
	public void anExactlyNamedSourceStandsBeforeTheItemsNamedAfterIt() throws Exception
	{
		assertEquals("Zulrah", left(firstDoor(search("zulrah"))));
		// and the singular of a plural name counts as exact
		assertEquals("Zulrah", left(firstDoor(search("zulrahs"))));
		// but a query the source does not match exactly ranks the items first
		assertTrue(left(firstDoor(search("zulrah's"))).startsWith("Zulrah's scales"));
	}

	@Test
	public void aMissNamesTheNearestThing() throws Exception
	{
		JPanel board = search("nechrael");
		assertEquals("Nechryael", beside(board, "Did you mean"));
		assertNull(beside(search("qqqqqqqq"), "Did you mean"));
	}

	@Test
	public void theStripSaysASearchReadsTheWholeRecord() throws Exception
	{
		final List<String> said = new ArrayList<>();
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				Field f = ChroniclePanel.class.getDeclaredField("searchField");
				f.setAccessible(true);
				((net.runelite.client.ui.components.IconTextField) f.get(panel)).setText("zulrah");
				Method m = ChroniclePanel.class.getDeclaredMethod("periodRow");
				m.setAccessible(true);
				List<Component> flat = new ArrayList<>();
				flatten((Component) m.invoke(panel), flat);
				for (Component c : flat)
				{
					if (c instanceof JLabel)
					{
						said.add(((JLabel) c).getText());
					}
				}
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		assertTrue(said.toString(), said.contains("Whole record"));
		assertFalse("the strip offers arrows over a list they cannot change: " + said,
			said.contains("<") || said.contains(">"));
	}

	@Test
	public void aJournalHitOpensOnItsDay() throws Exception
	{
		long ts = System.currentTimeMillis() - 40L * 24 * 60 * 60_000L;
		JsonObject e = new JsonObject();
		e.addProperty("ts", ts);
		e.addProperty("type", "SLAYER");
		JsonObject d = new JsonObject();
		d.addProperty("slayerTask", "Bloodveld");
		d.addProperty("killCount", "120");
		e.add("data", d);
		stub.feed.add(0, e);
		JPanel board = search("bloodveld");
		JPanel hit = null;
		List<Component> flat = new ArrayList<>();
		flatten(board, flat);
		for (Component c : flat)
		{
			if (c instanceof JPanel && ((JPanel) c).getLayout() instanceof BorderLayout
				&& left((JPanel) c) != null && left((JPanel) c).contains("Bloodveld"))
			{
				hit = (JPanel) c;
			}
		}
		assertNotNull("the journal line is not on the board", hit);
		press(hit);
		assertEquals("Day", field("histGranularity"));
		assertEquals(Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDate(),
			field("histCursor"));
		assertNull(field("histFrom"));
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
