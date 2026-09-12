/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the BSD 2-Clause
 * License (see LICENSE) are met.
 */
package chronicle;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import javax.swing.JLabel;
import javax.swing.JPanel;
import net.runelite.client.game.ItemManager;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.Mockito;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The History tab's "Tracked progress" card: the period's headline figures in
 * their fixed order, then every other counter folded under the family and
 * section the Stats tab files it in. A fold starts shut, its head carries the
 * section's period total, and a click on the head opens it to the rows and the
 * leftover "Other". The folds are keyed apart from the Stats tab's.
 */
public class HistoryProgressCardTest
{
	private static final String FOLD = "history:Skilling:Fishing";

	@BeforeClass
	public static void headless()
	{
		System.setProperty("java.awt.headless", "true");
	}

	// two baselines ten days apart: whatever the weekday, the week is measured
	// from the older one, and the summary reads the difference
	private static PanelPreviewTest.StubPlugin stub(boolean counters)
	{
		PanelPreviewTest.StubPlugin s =
			new PanelPreviewTest.StubPlugin(Mockito.mock(ItemManager.class));
		HistoryLog.Baseline a = new HistoryLog.Baseline();
		HistoryLog.Baseline b = new HistoryLog.Baseline();
		a.skills.put("attack", 1_000_000L);
		b.skills.put("attack", 1_050_000L);
		if (counters)
		{
			put(a, b, "dropsReceived", 100, 112);
			put(a, b, "lootValue", 1_000_000, 3_500_000);
			put(a, b, "lootLeftCount", 10, 13);
			put(a, b, "lootLeftValue", 100_000, 400_000);
			put(a, b, "kills", 300, 312);
			put(a, b, "slayerTasksCompleted", 40, 42);
			put(a, b, "damageDealt", 500_000, 560_000);
			put(a, b, "resourcesGatheredValue", 1_000_000, 1_250_000);
			put(a, b, "resourcesDroppedValue", 50_000, 80_000);
			put(a, b, "fishCaught", 1_000, 1_050);
			put(a, b, "sharkCaught", 600, 630);
			put(a, b, "foodEaten", 200, 220);
			put(a, b, "sharkEaten", 150, 166);
			a.kcs.put("Zulrah", 100L);
			b.kcs.put("Zulrah", 108L);
			a.kcs.put("Vorkath", 50L);
			b.kcs.put("Vorkath", 54L);
		}
		LocalDate today = LocalDate.now();
		s.history.put(today.minusDays(10), a);
		s.history.put(today, b);
		return s;
	}

	private static void put(HistoryLog.Baseline a, HistoryLog.Baseline b, String key,
		long from, long to)
	{
		a.counters.put(key, from);
		b.counters.put(key, to);
	}

	// a panel with the spine already gathered, the way the tab reads once its
	// worker has landed: the cache fields are set so buildHistory draws at once
	private static ChroniclePanel panel(PanelPreviewTest.StubPlugin stub) throws Exception
	{
		final ChroniclePanel[] holder = new ChroniclePanel[1];
		edt(() -> holder[0] = new ChroniclePanel(stub));
		ChroniclePanel p = holder[0];
		set(p, "historySpine", stub.history);
		set(p, "historyDay", LocalDate.now());
		set(p, "histGranularity", "Week");
		return p;
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

	private static JPanel history(ChroniclePanel panel) throws Exception
	{
		final JPanel[] out = new JPanel[1];
		edt(() ->
		{
			Method m = ChroniclePanel.class.getDeclaredMethod("buildHistory");
			m.setAccessible(true);
			out[0] = (JPanel) m.invoke(panel);
		});
		return out[0];
	}

	private static List<String> labels(Container c)
	{
		List<String> out = new ArrayList<>();
		collect(c, out);
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

	// the card's labels, from its caption to the end of the view
	private static List<String> card(List<String> all)
	{
		int at = all.indexOf("TRACKED PROGRESS");
		return at < 0 ? new ArrayList<>() : all.subList(at, all.size());
	}

	// the row whose name label reads {@code name}, or null
	private static JPanel rowNamed(Container c, String name)
	{
		for (Component child : c.getComponents())
		{
			if (child instanceof JPanel && ((JPanel) child).getLayout() instanceof BorderLayout)
			{
				Component centre = ((BorderLayout) ((JPanel) child).getLayout())
					.getLayoutComponent(BorderLayout.CENTER);
				if (centre instanceof JLabel && name.equals(((JLabel) centre).getText()))
				{
					return (JPanel) child;
				}
			}
			if (child instanceof Container)
			{
				JPanel hit = rowNamed((Container) child, name);
				if (hit != null)
				{
					return hit;
				}
			}
		}
		return null;
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
	public void theCardIsTrackedProgressAndTheMoversAreGone() throws Exception
	{
		List<String> all = labels(history(panel(stub(true))));
		assertTrue(all.toString(), all.contains("TRACKED PROGRESS"));
		assertFalse(all.toString(), all.contains("THE PERIOD'S MOVERS"));

		// and the old caption is gone from the source, not only from this render
		File src = new File("src/main/java/chronicle/ChroniclePanel.java");
		if (src.isFile())
		{
			String text = new String(Files.readAllBytes(src.toPath()), StandardCharsets.UTF_8);
			assertFalse("old movers caption still in the source",
				text.contains("The period's movers"));
		}
	}

	@Test
	public void theSummaryReadsInOrderWithItsFigures() throws Exception
	{
		List<String> card = card(labels(history(panel(stub(true)))));
		// the summary in its fixed order, gp in gp, the floor's value beside its
		// count and the dropped value beside the gathered, then the families and
		// their shut folds with the period totals
		assertEquals(Arrays.asList(
			"TRACKED PROGRESS",
			"Drops received", "+12",
			"Loot value", "+2.5M gp",
			"Left on the floor", "+3 · 300k gp",
			"Loot kept", "+2.2M gp",
			"Kills", "+12",
			"Slayer tasks completed", "+2",
			"Damage dealt", "+60,000",
			"Gathered", "+250k gp · 30k dropped",
			"LIVING",
			"Meals eaten", "+20",
			"FOOD", "+16",
			"SKILLING",
			"FISHING", "+50"), card);
	}

	@Test
	public void aFamilysFlatRowsDrawPlainUnderItsHeading() throws Exception
	{
		// meals eaten is Living's top list on the Stats tab, not a section: it
		// draws as a row under the family, with no fold and no family-named head
		List<String> card = card(labels(history(panel(stub(true)))));
		assertEquals(card.toString(), 1, java.util.Collections.frequency(card, "LIVING"));
		assertTrue(card.toString(), card.indexOf("LIVING") < card.indexOf("Meals eaten"));
		assertTrue(card.toString(), card.indexOf("Meals eaten") < card.indexOf("FOOD"));
	}

	@Test
	public void aFamilyHeadsOnlyWhenItHasASection() throws Exception
	{
		List<String> card = card(labels(history(panel(stub(true)))));
		assertTrue(card.toString(), card.indexOf("LIVING") < card.indexOf("SKILLING"));
		// Combat's only mover (damage) is a summary line, so Combat has no section
		assertFalse(card.toString(), card.contains("COMBAT"));
		assertFalse(card.toString(), card.contains("LEDGER & ROADS"));
	}

	@Test
	public void aClickOnTheHeadOpensTheFoldToItsRowsAndTheGhost() throws Exception
	{
		ChroniclePanel p = panel(stub(true));
		JPanel view = history(p);
		List<String> shut = labels(view);
		assertFalse(shut.toString(), shut.contains("Shark"));
		assertFalse(shut.toString(), shut.contains("Other"));
		assertTrue(openFolds(p).isEmpty());

		// the head's clicker toggles the history-keyed fold and redraws
		JPanel head = rowNamed(view, "FISHING");
		assertNotNull(shut.toString(), head);
		assertEquals(java.awt.Cursor.HAND_CURSOR, head.getCursor().getType());
		edt(() ->
		{
			MouseEvent click = new MouseEvent(head, MouseEvent.MOUSE_PRESSED,
				System.currentTimeMillis(), 0, 1, 1, 1, false);
			for (MouseListener l : head.getMouseListeners())
			{
				l.mousePressed(click);
			}
		});
		assertEquals(java.util.Collections.singleton(FOLD), openFolds(p));

		List<String> open = card(labels(history(p)));
		int at = open.indexOf("FISHING");
		assertTrue(open.toString(), at >= 0);
		assertEquals(Arrays.asList("FISHING", "+50", "Shark", "+30", "Other", "+20"),
			open.subList(at, Math.min(at + 6, open.size())));

		// and the same click shuts it again
		JPanel openHead = rowNamed(history(p), "FISHING");
		edt(() ->
		{
			MouseEvent click = new MouseEvent(openHead, MouseEvent.MOUSE_PRESSED,
				System.currentTimeMillis(), 0, 1, 1, 1, false);
			for (MouseListener l : openHead.getMouseListeners())
			{
				l.mousePressed(click);
			}
		});
		assertTrue(openFolds(p).isEmpty());
	}

	@Test
	public void theOpenHeadTakesTheAccentAndOneFoldsStateIsItsOwn() throws Exception
	{
		ChroniclePanel p = panel(stub(true));
		openFolds(p).add("history:Living:Food");
		JPanel view = history(p);
		List<String> card = card(labels(view));
		int at = card.indexOf("FOOD");
		// Food has no floor, so its total is its rows' sum and nothing is left over
		assertEquals(Arrays.asList("FOOD", "+16", "Shark", "+16", "SKILLING"),
			card.subList(at, at + 5));
		// Fishing stays shut: one fold's state is its own
		assertEquals(1, java.util.Collections.frequency(card, "Shark"));

		JLabel openName = (JLabel) ((BorderLayout) rowNamed(view, "FOOD").getLayout())
			.getLayoutComponent(BorderLayout.CENTER);
		JLabel shutName = (JLabel) ((BorderLayout) rowNamed(view, "FISHING").getLayout())
			.getLayoutComponent(BorderLayout.CENTER);
		assertFalse(openName.getForeground().equals(shutName.getForeground()));
	}

	@Test
	public void theStatsTabsFoldsLeaveTheHistoryFoldsShut() throws Exception
	{
		ChroniclePanel p = panel(stub(true));
		openFolds(p).add("Skilling:Fishing");
		openFolds(p).add("Living:Food");
		List<String> card = card(labels(history(p)));
		assertFalse(card.toString(), card.contains("Shark"));
		assertFalse(card.toString(), card.contains("Other"));
	}

	@Test
	public void nothingTrackedDrawsNoCard() throws Exception
	{
		List<String> all = labels(history(panel(stub(false))));
		assertFalse(all.toString(), all.contains("TRACKED PROGRESS"));
		// the xp grid above it still draws
		assertTrue(all.toString(), all.contains("THE PERIOD"));
	}
}
