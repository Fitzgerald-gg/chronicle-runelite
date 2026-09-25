/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import javax.swing.SwingUtilities;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/**
 * A loose name opens the thing it names, or nothing.
 *
 * <p>The ledger's "Dagannoth" sits inside "Dagannoth Rex", and a containment
 * fallback handed all three kings the ordinary dagannoth's six hundred kills.
 * A general name must never swallow a specific one: an empty page for a fight
 * the journal has no drops from is the honest answer, and a plural, an "ies"
 * and a container the fight pays out through still resolve.
 */
public class LooseSourceTest
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
				PanelPreviewTest.StubPlugin stub = new PanelPreviewTest.StubPlugin(null);
				stub.sources = new ArrayList<>();
				add(stub, "Dagannoth", 577_290L);
				add(stub, "Dagannoth spawn", 446L);
				add(stub, "Black dragon", 4_000_000L);
				add(stub, "Jelly", 12_000L);
				add(stub, "Abyssal demon", 61_204_113L);
				add(stub, "Reward pool (Tempoross)", 4_112_005L);
				add(stub, "Casket (Tempoross)", 812_400L);
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
		stub.sources.add(new LocalStore.SourceRow(name, 0, 0, value, null, 0, 0, java.util.Collections.emptySet(), 0, 0));
		stub.bags.put(name, new ArrayList<>());
	}

	private static String resolve(String name) throws Exception
	{
		Method m = ChroniclePanel.class.getDeclaredMethod("resolveSource", String.class);
		m.setAccessible(true);
		return (String) m.invoke(panel, name);
	}

	@Test
	public void aGeneralNameNeverSwallowsASpecificOne() throws Exception
	{
		// the reported bug: every king landed on the ordinary dagannoth
		assertEquals("Dagannoth Rex", resolve("Dagannoth Rex"));
		assertEquals("Dagannoth Prime", resolve("Dagannoth Prime"));
		assertEquals("Dagannoth Supreme", resolve("Dagannoth Supreme"));
		// and the same shape anywhere else on the roster
		assertEquals("King Black Dragon", resolve("King Black Dragon"));
		assertEquals("Brutal black dragon", resolve("Brutal black dragon"));
	}

	@Test
	public void theLedgersOwnSpellingStillResolves() throws Exception
	{
		assertEquals("Dagannoth", resolve("Dagannoth"));
		assertEquals("Abyssal demon", resolve("Abyssal demons"));
		assertEquals("Jelly", resolve("Jellies"));
		assertEquals("Dagannoth spawn", resolve("Dagannoth spawns"));
	}

	@Test
	public void aFightOpensWhatPaysItOut() throws Exception
	{
		// the dearest of the containers it pays out through
		assertEquals("Reward pool (Tempoross)", resolve("Tempoross"));
	}

	@Test
	public void aNameTheLedgerHasNeverSeenIsItself() throws Exception
	{
		assertEquals("Vorkath", resolve("Vorkath"));
		assertEquals("", resolve(""));
	}

	/** And the page it lands on says so rather than showing another fight's loot. */
	@Test
	public void theEmptyPageSaysTheJournalHasNoDrops() throws Exception
	{
		final java.util.List<String> said = new ArrayList<>();
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				Method m = ChroniclePanel.class.getDeclaredMethod("buildSourceDetail", String.class);
				m.setAccessible(true);
				collect((java.awt.Component) m.invoke(panel, resolve("Dagannoth Rex")), said);
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		// a note is drawn as one label a line, so read the page as its prose
		String page = String.join(" ", said);
		assertEquals(page, true, said.contains("DAGANNOTH REX"));
		assertEquals(page, true, page.contains("The journal has no drops from this source yet."));
		assertEquals(page, false, said.contains("DAGANNOTH"));
	}

	private static void collect(java.awt.Component c, java.util.List<String> out)
	{
		if (c instanceof javax.swing.JLabel && ((javax.swing.JLabel) c).getText() != null)
		{
			out.add(((javax.swing.JLabel) c).getText());
		}
		if (c instanceof java.awt.Container)
		{
			for (java.awt.Component k : ((java.awt.Container) c).getComponents())
			{
				collect(k, out);
			}
		}
	}
}
