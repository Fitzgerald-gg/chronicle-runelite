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
import java.util.List;
import java.util.Set;
import javax.swing.JLabel;
import javax.swing.JPanel;
import net.runelite.client.game.ItemManager;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.Mockito;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Thralls are the one fold on the Combat tab: the floor heads it with the total,
 * and it opens to a row per tier and kind with the unresolved remainder as a
 * ghost "Other", the way a craft does on the Skilling tab.
 */
public class ThrallFoldTest
{
	@BeforeClass
	public static void headless()
	{
		System.setProperty("java.awt.headless", "true");
	}

	private static ChroniclePanel panel(PanelPreviewTest.StubPlugin stub) throws Exception
	{
		final ChroniclePanel[] holder = new ChroniclePanel[1];
		edt(() -> holder[0] = new ChroniclePanel(stub));
		return holder[0];
	}

	private static void set(ChroniclePanel panel, String field, Object val) throws Exception
	{
		Field f = ChroniclePanel.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(panel, val);
	}

	@SuppressWarnings("unchecked")
	private static Set<String> openFolds(ChroniclePanel panel) throws Exception
	{
		Field f = ChroniclePanel.class.getDeclaredField("openFolds");
		f.setAccessible(true);
		return (Set<String>) f.get(panel);
	}

	private static List<String> statsLabels(ChroniclePanel panel) throws Exception
	{
		final List<String> out = new ArrayList<>();
		edt(() ->
		{
			Method m = ChroniclePanel.class.getDeclaredMethod("buildStats");
			m.setAccessible(true);
			collect((JPanel) m.invoke(panel), out);
		});
		return out;
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
	public void thrallsFoldOnTheCombatTab() throws Exception
	{
		PanelPreviewTest.StubPlugin stub =
			new PanelPreviewTest.StubPlugin(Mockito.mock(ItemManager.class));
		stub.lifetime.put("damageDealt", 1_500L);
		stub.lifetime.put("thrallsSummoned", 10L);
		stub.lifetime.put("lesserGhostlyThrallsSummoned", 4L);
		stub.lifetime.put("greaterSkeletalThrallsSummoned", 5L);
		ChroniclePanel p = panel(stub);
		set(p, "statsFamily", "Combat");

		// shut: the fold head carries the floor total, and the floor is no row
		List<String> shut = statsLabels(p);
		assertTrue(shut.toString(), shut.contains("Damage dealt"));
		assertTrue(shut.toString(), shut.contains("THRALLS"));
		assertTrue(shut.toString(), shut.contains("10"));
		assertFalse(shut.toString(), shut.contains("Thralls raised"));
		assertFalse(shut.toString(), shut.contains("Lesser ghostly"));

		// open: one row per tier and kind, and the one the rows leave over
		openFolds(p).add("Combat:Thralls");
		List<String> open = statsLabels(p);
		assertTrue(open.toString(), open.contains("Lesser ghostly"));
		assertTrue(open.toString(), open.contains("Greater skeletal"));
		assertTrue(open.toString(), open.contains("Other"));
		assertTrue(open.toString(), open.contains("1"));
		assertFalse(open.toString(), open.contains("Thralls raised"));
	}
}
