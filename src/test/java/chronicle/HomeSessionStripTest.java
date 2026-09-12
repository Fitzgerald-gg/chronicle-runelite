/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the BSD 2-Clause
 * License (see LICENSE) are met.
 */
package chronicle;

import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.JPanel;
import chronicle.panel.StatRegistry;
import net.runelite.client.game.ItemManager;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.Mockito;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The Home tab's session strip, its three loot rows: "Drops received" (the loot
 * events and their gp), "Drops taken" (those events less the kills that left a
 * stack on the floor, floored at none) and "Left behind" (the stacks and their
 * gp). The middle row is in one unit with the first and is drawn whenever
 * anything was received; the stacks under the third are never what it subtracts.
 */
public class HomeSessionStripTest
{
	@BeforeClass
	public static void headless()
	{
		System.setProperty("java.awt.headless", "true");
	}

	private static PanelPreviewTest.StubPlugin stub(int loots, long lootGp, int leftKills,
		long leftStacks, long leftGp)
	{
		PanelPreviewTest.StubPlugin s =
			new PanelPreviewTest.StubPlugin(Mockito.mock(ItemManager.class));
		s.rsn = "Tester";
		s.sessionLoots = loots;
		s.sessionLootValue = lootGp;
		s.sessionUntakenKills = leftKills;
		s.sessionUntaken = new long[]{leftStacks, leftGp};
		return s;
	}

	// the Home tab's labels, once the read the panel primes when it is built has
	// landed, so nothing lands over the render
	private static List<String> home(PanelPreviewTest.StubPlugin stub) throws Exception
	{
		final ChroniclePanel[] holder = new ChroniclePanel[1];
		edt(() -> holder[0] = new ChroniclePanel(stub));
		ChroniclePanel p = holder[0];
		awaitGather(p);
		final JPanel[] out = new JPanel[1];
		edt(() ->
		{
			Method m = ChroniclePanel.class.getDeclaredMethod("buildHome");
			m.setAccessible(true);
			out[0] = (JPanel) m.invoke(p);
		});
		List<String> all = new ArrayList<>();
		collect(out[0], all);
		int at = all.indexOf("THIS SESSION");
		assertTrue(all.toString(), at >= 0);
		return all.subList(at, all.size());
	}

	private static void awaitGather(ChroniclePanel panel) throws Exception
	{
		long deadline = System.currentTimeMillis() + 10_000;
		while (true)
		{
			final boolean[] landed = new boolean[1];
			edt(() -> landed[0] = !(Boolean) get(panel, "historyGathering")
				&& get(panel, "historySpine") != null);
			if (landed[0])
			{
				return;
			}
			assertTrue("the history read never landed", System.currentTimeMillis() < deadline);
			Thread.sleep(20);
		}
	}

	private static Object get(ChroniclePanel panel, String field) throws Exception
	{
		Field f = ChroniclePanel.class.getDeclaredField(field);
		f.setAccessible(true);
		return f.get(panel);
	}

	private static void collect(Container c, List<String> out)
	{
		for (Component child : c.getComponents())
		{
			if (child instanceof JLabel)
			{
				out.add(((JLabel) child).getText());
			}
			if (child instanceof Container)
			{
				collect((Container) child, out);
			}
		}
	}

	// the figure beside a named row, or null when the row is absent
	private static String beside(List<String> strip, String label)
	{
		int at = strip.indexOf(label);
		return at < 0 || at + 1 >= strip.size() ? null : strip.get(at + 1);
	}

	private interface ThrowingRunnable
	{
		void run() throws Exception;
	}

	private static void edt(ThrowingRunnable r) throws Exception
	{
		final Exception[] err = {null};
		javax.swing.SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				r.run();
			}
			catch (Exception e)
			{
				err[0] = e;
			}
		});
		if (err[0] != null)
		{
			throw err[0];
		}
	}

	@Test
	public void theThreeRowsReadInOrderWithTheirFigures() throws Exception
	{
		// 37 loot events, six of which left a stack: 31 taken. Nine stacks were
		// left, and that count is not what was subtracted.
		List<String> strip = home(stub(37, 1_204_113L, 6, 9, 44_120L));
		int at = strip.indexOf("Drops received");
		assertTrue(strip.toString(), at > 0);
		assertEquals(Arrays.asList(
			"Drops received", "37 · 1.2M gp",
			"Drops taken", "31",
			"Left behind", "9 · 44k gp"), strip.subList(at, at + 6));
	}

	@Test
	public void moreKillsLeavingLootThanDropsReceivedFloorsAtNone() throws Exception
	{
		// a kill whose loot arrived before the session's count started can leave
		// its stack inside it: the figure floors rather than going negative
		List<String> strip = home(stub(2, 500, 5, 7, 100));
		assertEquals("2 · 500 gp", beside(strip, "Drops received"));
		assertEquals("0", beside(strip, "Drops taken"));
		assertEquals("7 · 100 gp", beside(strip, "Left behind"));
	}

	@Test
	public void nothingReceivedDrawsNeitherDropsRow() throws Exception
	{
		List<String> strip = home(stub(0, 0, 3, 4, 100));
		assertFalse(strip.toString(), strip.contains("Drops received"));
		assertFalse(strip.toString(), strip.contains("Drops taken"));
		assertEquals("4 · 100 gp", beside(strip, "Left behind"));
	}

	@Test
	public void nothingLeftBehindMeansEveryDropWasTaken() throws Exception
	{
		List<String> strip = home(stub(12, 3_000, 0, 0, 0));
		assertEquals("12", beside(strip, "Drops taken"));
		assertFalse(strip.toString(), strip.contains("Left behind"));
	}

	// ---- what else the session moved, under the parent it belongs to --------

	private static PanelPreviewTest.StubPlugin moved(Object... pairs)
	{
		PanelPreviewTest.StubPlugin s = stub(0, 0, 0, 0, 0);
		for (int i = 0; i + 1 < pairs.length; i += 2)
		{
			s.session.put((String) pairs[i], (Integer) pairs[i + 1]);
		}
		return s;
	}

	// the strip with a set of folds already open, so a test can read both what a
	// parent says shut and what it holds
	private static List<String> home(PanelPreviewTest.StubPlugin stub, String... open)
		throws Exception
	{
		final ChroniclePanel[] holder = new ChroniclePanel[1];
		edt(() -> holder[0] = new ChroniclePanel(stub));
		ChroniclePanel p = holder[0];
		awaitGather(p);
		Field f = ChroniclePanel.class.getDeclaredField("openFolds");
		f.setAccessible(true);
		@SuppressWarnings("unchecked")
		java.util.Set<String> folds = (java.util.Set<String>) f.get(p);
		folds.addAll(Arrays.asList(open));
		final JPanel[] out = new JPanel[1];
		edt(() ->
		{
			Method m = ChroniclePanel.class.getDeclaredMethod("buildHome");
			m.setAccessible(true);
			out[0] = (JPanel) m.invoke(p);
		});
		List<String> all = new ArrayList<>();
		collect(out[0], all);
		int at = all.indexOf("THIS SESSION");
		assertTrue(all.toString(), at >= 0);
		return all.subList(at, all.size());
	}

	@Test
	public void aHerbSackRunIsOneLineNotTwelve() throws Exception
	{
		// the sack types every herb it swallows; the session moved one tracker
		// and the strip says so, rather than naming twelve herbs
		List<String> strip = home(moved(
			"herbsSacked", 247, "guamLeafSacked", 60, "cadantineSacked", 35,
			"kwuarmSacked", 40, "iritLeafSacked", 29, "avantoeSacked", 27,
			"ranarrWeedSacked", 19, "lantadymeSacked", 19, "dwarfWeedSacked", 12,
			"marrentillSacked", 4, "harralanderSacked", 2));
		assertEquals(strip.toString(), "247", beside(strip, "HERBLORE"));
		assertFalse(strip.toString(), strip.contains("Guam leaf"));
		assertFalse(strip.toString(), strip.contains("Cadantine"));
		assertFalse(strip.toString(), strip.contains("Herbs sacked"));
	}

	@Test
	public void theHerbsAreThereWhenTheReaderOpensTheParent() throws Exception
	{
		List<String> strip = home(moved(
			"herbsSacked", 100, "guamLeafSacked", 60, "kwuarmSacked", 40),
			"session:Herblore");
		assertEquals(strip.toString(), "100", beside(strip, "HERBLORE"));
		assertEquals(strip.toString(), "60", beside(strip, "Guam leaf"));
		assertEquals(strip.toString(), "40", beside(strip, "Kwuarm"));
	}

	@Test
	public void whatTheFloorCountedAndTheRowsDidNotIsDrawnAsOther() throws Exception
	{
		// the sack swallowed a hundred and the chat typed sixty of them: the
		// forty it could not name is a row, not a silent gap
		List<String> strip = home(moved("herbsSacked", 100, "guamLeafSacked", 60),
			"session:Herblore");
		assertEquals(strip.toString(), "100", beside(strip, "HERBLORE"));
		assertEquals(strip.toString(), "40", beside(strip, "Other"));
	}

	@Test
	public void aTrackerWithNoParentKeepsItsOwnLine() throws Exception
	{
		List<String> strip = home(moved("hitsMissed", 125, "deaths", 2));
		assertEquals(strip.toString(), "125", beside(strip, StatRegistry.label("hitsMissed")));
		assertEquals(strip.toString(), "2", beside(strip, StatRegistry.label("deaths")));
	}

	@Test
	public void aParentThatMovedAloneDrawsPlainlyRatherThanOpeningOnNothing()
		throws Exception
	{
		// nothing typed under it, so a fold would open on an empty list; the
		// total is the row
		List<String> strip = home(moved("agilityObstacles", 66));
		assertEquals(strip.toString(), "66",
			beside(strip, StatRegistry.label("agilityObstacles")));
		assertFalse(strip.toString(), strip.contains("AGILITY"));
	}

	@Test
	public void aTotalThatHeadsASectionIsShownOnceAsThatSection() throws Exception
	{
		// "Meals eaten" is the Food section's total, so it is the section's
		// figure and not a second line beside it
		List<String> strip = home(moved("foodEaten", 20, "sharkEaten", 12));
		assertEquals(strip.toString(), "20", beside(strip, "FOOD"));
		assertFalse(strip.toString(), strip.contains(StatRegistry.label("foodEaten")));
	}

	@Test
	public void theDamageSplitOpensFromTheDamageItSplits() throws Exception
	{
		// the three styles are one figure broken up, not three more trackers
		List<String> shut = home(moved("damageDealt", 355, "damageDealtMelee", 81,
			"damageDealtRanged", 253, "damageDealtMagic", 21));
		assertEquals(shut.toString(), "355", beside(shut, "Damage dealt"));
		assertFalse(shut.toString(), shut.contains(StatRegistry.label("damageDealtRanged")));

		List<String> open = home(moved("damageDealt", 355, "damageDealtMelee", 81,
			"damageDealtRanged", 253, "damageDealtMagic", 21), "home:damage");
		assertEquals(open.toString(), "253",
			beside(open, StatRegistry.label("damageDealtRanged")));
		assertEquals(open.toString(), "81",
			beside(open, StatRegistry.label("damageDealtMelee")));
	}
}
