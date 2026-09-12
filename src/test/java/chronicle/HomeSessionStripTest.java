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
	public void everyTrackerSitsUnderAHeading() throws Exception
	{
		// one rule for the whole strip: a combat tracker is not left bare while a
		// skilling one gets a parent. Every row has a heading above it.
		List<String> strip = home(moved(
			"hitsBlocked", 6, "herbsSacked", 248, "distanceWalked", 169,
			"foodEaten", 20));
		for (String label : new String[]{
			StatRegistry.label("hitsBlocked"), StatRegistry.label("herbsSacked"),
			StatRegistry.label("distanceWalked"), StatRegistry.label("foodEaten")})
		{
			int at = strip.indexOf(label);
			assertTrue(strip.toString() + " has no " + label, at > 0);
			assertTrue(label + " stands under no heading",
				lastHeadingBefore(strip, at) != null);
		}
		assertEquals(strip.toString(), "COMBAT",
			lastHeadingBefore(strip, strip.indexOf(StatRegistry.label("hitsBlocked"))));
		assertEquals(strip.toString(), "SKILLING",
			lastHeadingBefore(strip, strip.indexOf(StatRegistry.label("herbsSacked"))));
		assertEquals(strip.toString(), "LIVING",
			lastHeadingBefore(strip, strip.indexOf(StatRegistry.label("foodEaten"))));
		assertEquals(strip.toString(), "LEDGER & ROADS",
			lastHeadingBefore(strip, strip.indexOf(StatRegistry.label("distanceWalked"))));
	}

	// the heading a row reads under, or null when it stands loose
	private static String lastHeadingBefore(List<String> strip, int at)
	{
		String head = null;
		for (int i = 0; i < at; i++)
		{
			for (String f : StatRegistry.FAMILIES)
			{
				if (strip.get(i).equals(f.toUpperCase(java.util.Locale.ROOT)))
				{
					head = strip.get(i);
				}
			}
		}
		return head;
	}

	@Test
	public void aHerbSackRunIsOneRowAndNamesTheTrackerThatMoved() throws Exception
	{
		// the sack types every herb it swallows; the session moved one tracker,
		// and the row says which one rather than naming a family
		List<String> strip = home(moved(
			"herbsSacked", 248, "guamLeafSacked", 60, "cadantineSacked", 35,
			"kwuarmSacked", 40, "avantoeSacked", 27, "dwarfWeedSacked", 12));
		assertEquals(strip.toString(), "248",
			beside(strip, StatRegistry.label("herbsSacked")));
		assertFalse(strip.toString(), strip.contains("Guam leaf"));
		assertFalse(strip.toString(), strip.contains("Cadantine"));
	}

	@Test
	public void aPlaceReachedIsOneOfTheTeleportsTheTotalCounted() throws Exception
	{
		List<String> strip = home(moved(
			"teleportsTotal", 3, "teleportsVarrock", 2, "teleportsLumbridge", 1));
		assertEquals(strip.toString(), "3", beside(strip, StatRegistry.label("teleportsTotal")));
		assertFalse(strip.toString(), strip.contains("Varrock"));
		assertFalse(strip.toString(), strip.contains("Lumbridge"));
	}

	@Test
	public void aTrackerWhoseParentNeverMovedKeepsItsOwnRow() throws Exception
	{
		// nothing above it can speak for it, so hiding it would lose the session
		List<String> strip = home(moved("teleportsVarrock", 2));
		assertEquals(strip.toString(), "2", beside(strip, StatRegistry.label("teleportsVarrock")));
	}

	@Test
	public void theLogsAFletcherCutReconcileToOneRow() throws Exception
	{
		// mapleLogsFletched and its siblings are logsFletched typed by log, and
		// they sum to it exactly
		List<String> strip = home(moved("logsFletched", 14_556,
			"mapleLogsFletched", 12_865, "magicLogsFletched", 866,
			"yewLogsFletched", 420, "willowLogsFletched", 405));
		assertEquals(strip.toString(), "14,556",
			beside(strip, StatRegistry.label("logsFletched")));
		for (String typed : new String[]{"mapleLogsFletched", "magicLogsFletched",
			"yewLogsFletched", "willowLogsFletched"})
		{
			assertFalse(strip.toString(), strip.contains(StatRegistry.label(typed)));
			assertFalse(strip.toString(), strip.contains(StatRegistry.rowLabel(typed)));
		}
	}

	@Test
	public void aHeadingCarriesNoFigureUntilItIsShut() throws Exception
	{
		// open, the rows beneath speak for it; shut, it says what it is holding
		List<String> open = home(moved("hitsBlocked", 6, "deaths", 2));
		// nothing stands between the heading and the first row it holds
		assertEquals(open.toString(), StatRegistry.label("hitsBlocked"),
			beside(open, "COMBAT"));
		assertEquals(open.toString(), "6", beside(open, StatRegistry.label("hitsBlocked")));

		List<String> shut = home(moved("hitsBlocked", 6, "deaths", 2), "session:Combat");
		assertEquals(shut.toString(), "2", beside(shut, "COMBAT"));
		assertFalse(shut.toString(), shut.contains(StatRegistry.label("hitsBlocked")));
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
	}
}