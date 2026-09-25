/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import javax.swing.SwingUtilities;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/**
 * The loot tracker names the NPC that dropped the loot, not the encounter: the
 * Guardians' loot sits under Dusk and the Titans' under whichever king fell
 * last. A boss cell opens where its loot actually is, and an activity that
 * pays in something spent rather than dropped opens its collection log page.
 */
public class LootUnderTheNpcTest
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
				javax.swing.UIManager.setLookAndFeel(new net.runelite.client.ui.laf.RuneLiteLAF());
				PanelPreviewTest.StubPlugin stub = new PanelPreviewTest.StubPlugin(null);
				stub.sources = new ArrayList<>();
				add(stub, "Dusk", 10_528_826L);
				add(stub, "Eldric the Ice King", 287_736L);
				add(stub, "Branda the Fire Queen", 90_000L);
				add(stub, "Barrows", 4_000_000L);
				add(stub, "Dagannoth", 577_290L);
				panel = new ChroniclePanel(stub);
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
	}

	private static void add(PanelPreviewTest.StubPlugin stub, String name, long value)
	{
		stub.sources.add(new LocalStore.SourceRow(name, 0, 1, value, null, 0, 0, java.util.Collections.emptySet(), 0, 0));
		stub.bags.put(name, new ArrayList<>());
	}

	private static String opens(String boss) throws Exception
	{
		Class<?> bc = Class.forName("chronicle.ChroniclePanel$Boss");
		Constructor<?> c = bc.getDeclaredConstructor(String.class, int.class);
		c.setAccessible(true);
		Method m = ChroniclePanel.class.getDeclaredMethod("bossLootSource", bc);
		m.setAccessible(true);
		return (String) m.invoke(panel, c.newInstance(boss, 0));
	}

	@Test
	public void aBossOpensWhereItsLootIsFiled() throws Exception
	{
		assertEquals("Dusk", opens("Grotesque Guardians"));
		// the dearer of the two kings
		assertEquals("Eldric the Ice King", opens("The Royal Titans"));
		assertEquals("Barrows", opens("Barrows Chests"));
		// and a boss with no loot anywhere still opens itself, not a neighbour
		assertEquals("Dagannoth Rex", opens("Dagannoth Rex"));
	}

	@Test
	public void anActivityWithNoLootOpensItsLogPage() throws Exception
	{
		Method m = ChroniclePanel.class.getDeclaredMethod("openActivity", String.class);
		m.setAccessible(true);
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				m.invoke(panel, "Soul Wars");
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		assertEquals("log", field("sheetPage"));
		assertEquals("Minigames", field("clogTab"));
		assertEquals("Soul Wars", field("clogPageSel"));
		assertEquals(null, field("detailSource"));
	}

	private static Object field(String name) throws Exception
	{
		Field f = ChroniclePanel.class.getDeclaredField(name);
		f.setAccessible(true);
		return f.get(panel);
	}
}
