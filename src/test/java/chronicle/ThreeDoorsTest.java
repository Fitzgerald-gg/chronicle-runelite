/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Three refinements from a walk of the panel as a reader.
 *
 * <p>A period pick used to throw the reader back to today; the combat tile said
 * only where it stood while the total beside it said where it had come from;
 * and three of the six search groups drew rows that named a thing and did
 * nothing when pressed.
 */
public class ThreeDoorsTest
{
	private static PanelPreviewTest.StubPlugin stub;
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
		stub = PanelPreviewTest.fixtureStub();
		final ChroniclePanel[] hold = new ChroniclePanel[1];
		SwingUtilities.invokeAndWait(() -> hold[0] = new ChroniclePanel(stub));
		panel = hold[0];
		PanelPreviewTest.regatherHistory(panel);
		PanelPreviewTest.awaitHistory(panel);
	}

	private static void set(String field, Object v) throws Exception
	{
		Field f = ChroniclePanel.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(panel, v);
	}

	private static Object get(String field) throws Exception
	{
		Field f = ChroniclePanel.class.getDeclaredField(field);
		f.setAccessible(true);
		return f.get(panel);
	}

	/**
	 * TRAP: the reader who stepped Day back to find something and then picked
	 * Week to read the week around it was thrown back to the present.
	 */
	@Test
	public void pickingAPeriodKeepsTheReadersPlace() throws Exception
	{
		LocalDate then = LocalDate.now().minusDays(40);
		set("histGranularity", "Day");
		set("histFrom", null);
		set("histCursor", then);
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				Method m = ChroniclePanel.class.getDeclaredMethod("periodMenu");
				m.setAccessible(true);
				JPopupMenu menu = (JPopupMenu) m.invoke(panel);
				for (Component c : menu.getComponents())
				{
					if (c instanceof javax.swing.JMenuItem
						&& "Week".equals(((javax.swing.JMenuItem) c).getText()))
					{
						((javax.swing.JMenuItem) c).doClick();
					}
				}
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		assertEquals("Week", get("histGranularity"));
		LocalDate cursor = (LocalDate) get("histCursor");
		assertTrue("the pick threw the reader back to today: " + cursor,
			!cursor.isAfter(then) && !cursor.isBefore(then.minusDays(7)));
	}

	/** The combat tile says where it opened, the shape the total tile draws. */
	@Test
	public void theCombatTileNamesWhereItOpenedOnAPeriod() throws Exception
	{
		set("histGranularity", "Week");
		set("histFrom", null);
		// seven opening levels, all below where the fixture's account stands (125)
		Map<String, Long> xp = new HashMap<>();
		for (String k : new String[]{"attack", "strength", "defence", "hitpoints",
			"ranged", "magic", "prayer"})
		{
			xp.put(k, 5_346_332L);   // level 90
		}
		Method at = ChroniclePanel.class.getDeclaredMethod("baselineAt", Map.class);
		at.setAccessible(true);
		Object base = at.invoke(null, xp);
		List<String> keys = new ArrayList<>(xp.keySet());
		Method lv = HistoryLog.class.getDeclaredMethod("levels", HistoryLog.Baseline.class, List.class);
		lv.setAccessible(true);
		Object opened = lv.invoke(null, base, keys);

		final String[] figure = {null};
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				Method m = ChroniclePanel.class.getDeclaredMethod("combatLevelTile",
					Map.class, HistoryLog.Levels.class);
				m.setAccessible(true);
				JPanel tile = (JPanel) m.invoke(panel, new HashMap<String, Long>(), opened);
				for (Component c : tile.getComponents())
				{
					if (c instanceof JLabel && ((JLabel) c).getText().matches(".*\\d.*"))
					{
						figure[0] = ((JLabel) c).getText();
					}
				}
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		assertTrue("the tile does not say where the level opened: " + figure[0],
			figure[0] != null && figure[0].contains(" to "));
		assertTrue("the tile's opening is not below its close: " + figure[0],
			figure[0].endsWith(" to " + stub.combatLevel()));
	}

	/** TRAP: a row that names a thing and does nothing when pressed. */
	@Test
	public void aJournalHitInSearchIsADoor() throws Exception
	{
		final List<JPanel> rows = new ArrayList<>();
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				Method m = ChroniclePanel.class.getDeclaredMethod("buildSearch", String.class);
				m.setAccessible(true);
				JPanel results = (JPanel) m.invoke(panel, "Dragon Slayer");
				List<Component> flat = new ArrayList<>();
				flatten(results, flat);
				for (Component c : flat)
				{
					if (c instanceof JLabel && ((JLabel) c).getText() != null
						&& ((JLabel) c).getText().contains("Dragon Slayer II")
						&& c.getParent() instanceof JPanel)
					{
						rows.add((JPanel) c.getParent());
					}
				}
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		assertTrue("the fixture's quest line did not come up in search", !rows.isEmpty());
		for (JPanel r : rows)
		{
			assertEquals("a search hit that answers no cursor",
				Cursor.getPredefinedCursor(Cursor.HAND_CURSOR), r.getCursor());
			assertTrue("a search hit with nothing behind the press",
				r.getMouseListeners().length > 0);
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
