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
}
