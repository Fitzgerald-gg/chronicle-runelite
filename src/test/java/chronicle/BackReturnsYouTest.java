/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import javax.swing.SwingUtilities;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class BackReturnsYouTest
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

	@Before
	public void clearNavigation() throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				set("sheetPage", null);
				set("detailItem", null);
				set("detailSource", null);
				set("detailSkill", null);
				set("allTrackers", false);
				set("showInfo", false);
				((java.util.Deque<?>) get("detailStack")).clear();
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
	}

	private static Object get(String field) throws Exception
	{
		Field f = ChroniclePanel.class.getDeclaredField(field);
		f.setAccessible(true);
		return f.get(panel);
	}

	private static void set(String field, Object v) throws Exception
	{
		Field f = ChroniclePanel.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(panel, v);
	}

	private static void on(String method, Object... args) throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				Method m = args.length == 0
					? ChroniclePanel.class.getDeclaredMethod(method)
					: ChroniclePanel.class.getDeclaredMethod(method, String.class);
				m.setAccessible(true);
				if (args.length == 0)
				{
					m.invoke(panel);
				}
				else
				{
					m.invoke(panel, args);
				}
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
	}

	@Test
	public void oneBackOffASheetPagesDrillReturnsToThatPage() throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				set("sheetPage", "clues");
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		on("openSource", "Clue Scroll (Hard)");
		assertEquals("the drill did not open", "Clue Scroll (Hard)", get("detailSource"));
		assertEquals("opening a drill must not lose the page under it",
			"clues", get("sheetPage"));

		on("backDetail");
		assertNull("one Back should leave the source", get("detailSource"));
		assertEquals("and land back on the page the reader drilled from, not the"
			+ " sheet root", "clues", get("sheetPage"));

		on("backDetail");
		assertNull("a second Back then leaves the page", get("sheetPage"));
	}

	@Test
	public void oneBackOffASkillsDrillReturnsToThatSkill() throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				set("sheetPage", null);
				set("detailSource", null);
				set("detailItem", null);
				set("detailSkill", "Fishing");
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		on("openSource", "Fishing Trawler");
		on("backDetail");
		assertNull("one Back should leave the source", get("detailSource"));
		assertEquals("and land back on the skill it was opened from",
			"Fishing", get("detailSkill"));
	}
}
