/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import java.awt.Component;
import java.awt.Container;
import java.awt.event.MouseEvent;
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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The activity tiles: what they wear, and where they go.
 *
 * <p>Their emblems used to be worked out the way a boss tile's is, by asking
 * the ledger for the dearest thing that source ever dropped. For a boss that is
 * a fair likeness; for an activity it is nonsense, and for the four with no
 * drop source behind them at all it fell through to one generic tab icon, so
 * Collections, Quests, Diaries and Combat were four identical tiles.
 */
public class ActivityTileTest
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

	private static int sprite(String label) throws Exception
	{
		Method m = ChroniclePanel.class.getDeclaredMethod("activitySprite", String.class);
		m.setAccessible(true);
		return (Integer) m.invoke(null, label);
	}

	/** Every tile has one, and no two share. */
	@Test
	public void everyActivityWearsItsOwnEmblem() throws Exception
	{
		Field f = ChroniclePanel.class.getDeclaredField("ACTIVITIES");
		f.setAccessible(true);
		String[][] roster = (String[][]) f.get(null);
		java.util.Set<Integer> seen = new java.util.HashSet<>();
		for (String[] a : roster)
		{
			int id = sprite(a[0]);
			assertTrue(a[0] + " has no emblem of its own", id > 0);
			assertTrue(a[0] + " wears the same emblem as another tile", seen.add(id));
		}
		assertEquals("every activity is accounted for", roster.length, seen.size());
	}

	/**
	 * The four that are hiscores rows take the hiscores' own art, so it follows
	 * the client rather than a number written down here.
	 */
	@Test
	public void theHiscoresOnesTakeTheHiscoresOwnArt() throws Exception
	{
		assertEquals(net.runelite.client.hiscore.HiscoreSkill.CLUE_SCROLL_ALL.getSpriteId(),
			sprite("Clues"));
		assertEquals(net.runelite.client.hiscore.HiscoreSkill.RIFTS_CLOSED.getSpriteId(),
			sprite("Rifts closed"));
		assertEquals(net.runelite.client.hiscore.HiscoreSkill.SOUL_WARS_ZEAL.getSpriteId(),
			sprite("Soul Wars"));
		assertEquals(
			net.runelite.client.hiscore.HiscoreSkill.COLLECTIONS_LOGGED.getSpriteId(),
			sprite("Collections"));
	}

	private static JPanel tileSaying(String needle) throws Exception
	{
		final JPanel[] found = {null};
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				Method m = ChroniclePanel.class.getDeclaredMethod("activitySheet");
				m.setAccessible(true);
				List<Component> flat = new ArrayList<>();
				flatten((Component) m.invoke(panel), flat);
				for (Component c : flat)
				{
					if (c instanceof JPanel
						&& ((JPanel) c).getToolTipText() != null
						&& ((JPanel) c).getToolTipText().contains(needle))
					{
						found[0] = (JPanel) c;
						return;
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

	private static Object nav(String field) throws Exception
	{
		Field f = ChroniclePanel.class.getDeclaredField(field);
		f.setAccessible(true);
		return f.get(panel);
	}

	/**
	 * The combat achievements are reached from the COMBAT LEVEL, which is what a
	 * reader means when they click the word Combat on a sheet of levels. There
	 * was an activity tile doing the job instead, sitting among the clue scrolls
	 * and the rifts as though it were one of them.
	 */
	@Test
	public void combatIsNotOneOfTheActivities() throws Exception
	{
		Field f = ChroniclePanel.class.getDeclaredField("ACTIVITIES");
		f.setAccessible(true);
		for (String[] a : (String[][]) f.get(null))
		{
			assertTrue("combat is still filed as an activity",
				!"Combat".equals(a[0]));
		}
	}

	/**
	 * Clicked, not merely wired. A listener that is present and a click that
	 * arrives are different questions, and the second is the one being asked.
	 */
	@Test
	public void clickingTheCombatLevelOpensTheCombatAchievements() throws Exception
	{
		final JPanel[] tile = {null};
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				Method m = ChroniclePanel.class.getDeclaredMethod("combatLevelTile");
				m.setAccessible(true);
				tile[0] = (JPanel) m.invoke(panel);
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		assertNotNull(tile[0]);
		assertTrue("the combat level says nothing about the achievements it opens",
			tile[0].getToolTipText().contains("Achievement points"));
		press(tile[0]);
		assertEquals("clicking the combat level did not open the combat"
			+ " achievements", "combat", nav("sheetPage"));
	}

	@Test
	public void clickingTheOtherThreeOpensTheirOwnBoards() throws Exception
	{
		for (String[] pair : new String[][]{{"Quests", "quests"},
			{"Achievement diaries", "diaries"}, {"Collection log", "log"}})
		{
			JPanel tile = tileSaying(pair[0]);
			assertNotNull(pair[0] + " was not drawn", tile);
			press(tile);
			assertEquals(pair[0] + " did not open its board", pair[1], nav("sheetPage"));
		}
	}

	private static void press(JPanel tile) throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
			for (java.awt.event.MouseListener l : tile.getMouseListeners())
			{
				l.mousePressed(new MouseEvent(tile, MouseEvent.MOUSE_PRESSED, 0L, 0,
					3, 3, 1, false));
				l.mouseReleased(new MouseEvent(tile, MouseEvent.MOUSE_RELEASED, 0L, 0,
					3, 3, 1, false));
				l.mouseClicked(new MouseEvent(tile, MouseEvent.MOUSE_CLICKED, 0L, 0,
					3, 3, 1, false));
			}
		});
	}
}
