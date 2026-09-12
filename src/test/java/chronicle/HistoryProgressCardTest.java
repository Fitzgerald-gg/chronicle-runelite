/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the BSD 2-Clause
 * License (see LICENSE) are met.
 */
package chronicle;

import com.google.gson.JsonObject;
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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The History tab's "Tracked progress" card: the period's headline figures in
 * their fixed order, a line saying what they measure from when that is later
 * than the period's start, then one list of folds in the Stats tab's family
 * and section order with no heading between families. A fold starts shut, its
 * head carries the section's period total where the rows add up to one, and a
 * click on the head opens it to the rows and the leftover "Other". Two summary
 * lines read the journal itself and reach back past the spine: slayer tasks
 * from the closed segments dated inside the period, and collection log slots
 * from the feed's COLLECTION entries when the feed reaches back past the
 * window's start. The note reads the spine only as far as the period's last
 * line, and the journey reaches the card through the read the panel primes
 * when it is built. The folds are keyed apart from the Stats tab's.
 */
public class HistoryProgressCardTest
{
	private static final String FOLD = "history:Skilling:Fishing";
	private static final DateTimeFormatter FULL = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK);
	private static final long DAY_MS = 86_400_000L;

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
			put(a, b, "slayerTasksCompleted", 40, 45);
			put(a, b, "clogSlotsObtained", 400, 407);
			put(a, b, "damageDealt", 500_000, 560_000);
			put(a, b, "resourcesGatheredValue", 1_000_000, 1_250_000);
			put(a, b, "resourcesDroppedValue", 50_000, 80_000);
			put(a, b, "fishCaught", 1_000, 1_050);
			put(a, b, "sharkCaught", 600, 630);
			// meals and doses head their own folds; vials are Living's flat list
			put(a, b, "foodEaten", 200, 220);
			put(a, b, "sharkEaten", 150, 166);
			put(a, b, "potionDoses", 1_000, 1_225);
			put(a, b, "prayerDoses", 500, 640);
			put(a, b, "vialsShattered", 10, 13);
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

	// a spine whose lines joined in stages: an imported baseline carrying skills
	// alone, then the trackers, then the journal-derived loot totals
	private static PanelPreviewTest.StubPlugin staged(boolean importedFirst)
	{
		PanelPreviewTest.StubPlugin s =
			new PanelPreviewTest.StubPlugin(Mockito.mock(ItemManager.class));
		LocalDate today = LocalDate.now();
		if (importedFirst)
		{
			HistoryLog.Baseline imported = new HistoryLog.Baseline();
			imported.skills.put("attack", 900_000L);
			s.history.put(today.minusDays(30), imported);
		}
		HistoryLog.Baseline trackers = new HistoryLog.Baseline();
		trackers.skills.put("attack", 1_000_000L);
		trackers.counters.put("fishCaught", 1_000L);
		s.history.put(today.minusDays(10), trackers);
		// a second trackers line before the loot totals join, so a period
		// closed between the two still holds a gain to draw
		HistoryLog.Baseline more = new HistoryLog.Baseline();
		more.skills.put("attack", 1_010_000L);
		more.counters.put("fishCaught", 1_010L);
		s.history.put(today.minusDays(7), more);
		HistoryLog.Baseline loot = new HistoryLog.Baseline();
		loot.skills.put("attack", 1_020_000L);
		loot.counters.put("fishCaught", 1_020L);
		loot.counters.put("dropsReceived", 100L);
		s.history.put(today.minusDays(3), loot);
		HistoryLog.Baseline now = new HistoryLog.Baseline();
		now.skills.put("attack", 1_050_000L);
		now.counters.put("fishCaught", 1_050L);
		now.counters.put("dropsReceived", 112L);
		s.history.put(today, now);
		return s;
	}

	// a spine where the trackers and the journal-derived loot totals join on
	// the same line, after an imported baseline carrying skills alone
	private static PanelPreviewTest.StubPlugin stagedTogether()
	{
		PanelPreviewTest.StubPlugin s =
			new PanelPreviewTest.StubPlugin(Mockito.mock(ItemManager.class));
		LocalDate today = LocalDate.now();
		HistoryLog.Baseline imported = new HistoryLog.Baseline();
		imported.skills.put("attack", 900_000L);
		s.history.put(today.minusDays(30), imported);
		HistoryLog.Baseline both = new HistoryLog.Baseline();
		both.skills.put("attack", 1_000_000L);
		both.counters.put("fishCaught", 1_000L);
		both.counters.put("dropsReceived", 100L);
		s.history.put(today.minusDays(10), both);
		HistoryLog.Baseline now = new HistoryLog.Baseline();
		now.skills.put("attack", 1_050_000L);
		now.counters.put("fishCaught", 1_050L);
		now.counters.put("dropsReceived", 112L);
		s.history.put(today, now);
		return s;
	}

	private static JsonObject entry(long ts, String type, String key, String val)
	{
		JsonObject e = new JsonObject();
		e.addProperty("ts", ts);
		e.addProperty("type", type);
		JsonObject data = new JsonObject();
		data.addProperty(key, val);
		e.add("data", data);
		return e;
	}

	private static ChronicleApiClient.SlayerTask task(String name, double ts, boolean inProgress)
	{
		return new ChronicleApiClient.SlayerTask(name, 100, inProgress ? 150 : 0, 0, ts, 1_000L,
			inProgress);
	}

	private static ChronicleApiClient.SlayerJourney journey(ChronicleApiClient.SlayerTask... tasks)
	{
		return new ChronicleApiClient.SlayerJourney(tasks.length, 100L * tasks.length,
			1_000L * tasks.length, 0, new ArrayList<>(Arrays.asList(tasks)));
	}

	// a panel with the spine and feed already gathered, the way the tab reads
	// once its worker has landed: the cache fields are set so buildHistory
	// draws at once, and the feed stamp matches the stub's so no gather starts.
	// The read the panel primes when it is built lands first, or it would
	// land over what is set here.
	private static ChroniclePanel panel(PanelPreviewTest.StubPlugin stub) throws Exception
	{
		final ChroniclePanel[] holder = new ChroniclePanel[1];
		edt(() -> holder[0] = new ChroniclePanel(stub));
		ChroniclePanel p = holder[0];
		awaitGather(p);
		set(p, "historySpine", stub.history);
		set(p, "historyFeed", new ArrayList<>(stub.feed));
		set(p, "historyFeedTs", stub.feed.isEmpty() ? 0L : stub.feed.get(0).get("ts").getAsLong());
		set(p, "historyDay", LocalDate.now());
		set(p, "histGranularity", "Week");
		return p;
	}

	// wait for the panel's read of the plugin to land: it is flagged in flight
	// on the EDT when it starts and cleared there when its worker is done
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

	private static void set(ChroniclePanel panel, String field, Object val) throws Exception
	{
		Field f = ChroniclePanel.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(panel, val);
	}

	private static Object get(ChroniclePanel panel, String field) throws Exception
	{
		Field f = ChroniclePanel.class.getDeclaredField(field);
		f.setAccessible(true);
		return f.get(panel);
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

	// the card's labels, from its caption to the milestones card or the end
	private static List<String> card(List<String> all)
	{
		int at = all.indexOf("TRACKED PROGRESS");
		if (at < 0)
		{
			return new ArrayList<>();
		}
		int end = all.size();
		for (int i = at + 1; i < all.size(); i++)
		{
			if (all.get(i).startsWith("MILESTONES"))
			{
				end = i;
				break;
			}
		}
		return all.subList(at, end);
	}

	// the note under the caption: every label between it and the first summary
	// line, joined back into the sentence the wrap split
	private static String noteText(List<String> card)
	{
		return noteText(card, "Drops received");
	}

	// the same, for a card whose first line after the note reads {@code first}
	private static String noteText(List<String> card, String first)
	{
		int end = card.indexOf(first);
		assertTrue(card.toString(), end > 0);
		return String.join(" ", card.subList(1, end));
	}

	// the figure beside a named line, or null when the line is absent
	private static String beside(List<String> card, String label)
	{
		int at = card.indexOf(label);
		return at < 0 || at + 1 >= card.size() ? null : card.get(at + 1);
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

	private static void click(JPanel head) throws Exception
	{
		edt(() ->
		{
			MouseEvent click = new MouseEvent(head, MouseEvent.MOUSE_PRESSED,
				System.currentTimeMillis(), 0, 1, 1, 1, false);
			for (MouseListener l : head.getMouseListeners())
			{
				l.mousePressed(click);
			}
		});
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
		// count and the dropped value beside the gathered, then the shut folds
		// with their period totals: one list, no family heading, and the mixed
		// Living list with no figure at all
		assertEquals(Arrays.asList(
			"TRACKED PROGRESS",
			"Drops received", "+12",
			"Loot value", "+2.5M gp",
			"Left on the floor", "+3 · 300k gp",
			"Loot kept", "+2.2M gp",
			"Kills", "+12",
			"Slayer tasks completed", "+5",
			"Damage dealt", "+60,000",
			"Collection log slots", "+7",
			"Gathered", "+250k gp · 30k dropped",
			"LIVING",
			"FOOD", "+20",
			"POTIONS", "+225",
			"FISHING", "+50"), card);
	}

	@Test
	public void aFamilysFlatRowsFoldUnderItsOwnNameWithNoFigure() throws Exception
	{
		ChroniclePanel p = panel(stub(true));
		JPanel view = history(p);
		List<String> card = card(labels(view));
		// vials shattered is Living's flat list on the Stats tab: here it folds
		// under the family's own name, once, and a list that mixes units puts no
		// figure on its head, so the row has no right-hand label at all
		assertEquals(card.toString(), 1, java.util.Collections.frequency(card, "LIVING"));
		assertFalse(card.toString(), card.contains("Vials shattered"));
		JPanel head = rowNamed(view, "LIVING");
		assertNotNull(card.toString(), head);
		assertNull(((BorderLayout) head.getLayout()).getLayoutComponent(BorderLayout.EAST));
		assertEquals(java.awt.Cursor.HAND_CURSOR, head.getCursor().getType());

		// a click opens it to the rows, under the family-then-section key
		click(head);
		assertEquals(java.util.Collections.singleton("history:Living:Living"), openFolds(p));
		List<String> open = card(labels(history(p)));
		int at = open.indexOf("LIVING");
		assertEquals(Arrays.asList("LIVING", "Vials shattered", "+3", "FOOD"),
			open.subList(at, at + 4));
	}

	@Test
	public void noHeadingStandsBetweenFamilies() throws Exception
	{
		JPanel view = history(panel(stub(true)));
		List<String> card = card(labels(view));
		// Fishing files under Skilling, but no heading names the family above
		// it, and the families with nothing to fold have none either: the folds
		// run on as one list in family-then-section order
		for (String family : new String[]{"SKILLING", "COMBAT", "LEDGER & ROADS"})
		{
			assertFalse(card.toString(), card.contains(family));
		}
		assertTrue(card.toString(), card.indexOf("POTIONS") < card.indexOf("FISHING"));
		// every capitalised line in the card is a fold head: a row with the
		// hand cursor, never a bare heading label
		int heads = 0;
		for (String label : card.subList(1, card.size()))
		{
			if (label.matches("[A-Z][A-Z &]*"))
			{
				JPanel head = rowNamed(view, label);
				assertNotNull(label, head);
				assertEquals(label, java.awt.Cursor.HAND_CURSOR, head.getCursor().getType());
				heads++;
			}
		}
		assertEquals(card.toString(), 4, heads);
	}

	@Test
	public void aFlatKeyThatHeadsAFoldShowsOnceAsItsFigure() throws Exception
	{
		ChroniclePanel p = panel(stub(true));
		List<String> shut = card(labels(history(p)));
		// doses drunk is Living's flat key and the Potions floor: it heads the
		// fold and is no row beside it, so the figure reads once; meals eaten
		// heads Food the same way
		assertFalse(shut.toString(), shut.contains("Doses drunk"));
		assertFalse(shut.toString(), shut.contains("Meals eaten"));
		assertEquals(shut.toString(), 1, java.util.Collections.frequency(shut, "+225"));
		int at = shut.indexOf("POTIONS");
		assertEquals(Arrays.asList("POTIONS", "+225", "FISHING"), shut.subList(at, at + 3));

		// open, the fold reads its typed rows against the floor
		openFolds(p).add("history:Living:Potions");
		List<String> open = card(labels(history(p)));
		at = open.indexOf("POTIONS");
		assertEquals(Arrays.asList("POTIONS", "+225", "Prayer", "+140", "Other", "+85", "FISHING"),
			open.subList(at, at + 7));
		assertFalse(open.toString(), open.contains("Doses drunk"));
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
		click(head);
		assertEquals(java.util.Collections.singleton(FOLD), openFolds(p));

		List<String> open = card(labels(history(p)));
		int at = open.indexOf("FISHING");
		assertTrue(open.toString(), at >= 0);
		assertEquals(Arrays.asList("FISHING", "+50", "Shark", "+30", "Other", "+20"),
			open.subList(at, Math.min(at + 6, open.size())));

		// and the same click shuts it again
		click(rowNamed(history(p), "FISHING"));
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
		// meals eaten is the floor: the typed row reconciles to it and the rest
		// is the ghost
		assertEquals(Arrays.asList("FOOD", "+20", "Shark", "+16", "Other", "+4", "POTIONS"),
			card.subList(at, at + 7));
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
	public void theCardSaysWhatItMeasuresFrom() throws Exception
	{
		LocalDate today = LocalDate.now();
		// an imported line opens the period with no counters at all, the
		// trackers join later and the journal-derived loot totals later still:
		// both dates, under the caption and before the summary
		ChroniclePanel p = panel(staged(true));
		set(p, "histFrom", today.minusDays(40));
		set(p, "histTo", today);
		List<String> card = card(labels(history(p)));
		assertEquals(card.toString(),
			"Counters since " + today.minusDays(10).format(FULL)
				+ " · loot and kills since " + today.minusDays(3).format(FULL),
			noteText(card));

		// the counters go back to the period's start line and only the loot
		// totals came later: one clause, leading the line
		ChroniclePanel q = panel(staged(false));
		set(q, "histFrom", today.minusDays(40));
		set(q, "histTo", today);
		List<String> later = card(labels(history(q)));
		assertEquals(later.toString(), "Loot and kills since " + today.minusDays(3).format(FULL),
			noteText(later));

		// both go back to the start line: no note at all
		assertEquals("", noteText(card(labels(history(panel(stub(true)))))));
	}

	@Test
	public void lootJoiningOnTheCountersOwnLineAddsNoClause() throws Exception
	{
		LocalDate today = LocalDate.now();
		// the trackers and the loot totals join on one line after the imported
		// start: the counters' date is the loot's date too, and a second clause
		// would only say it again
		ChroniclePanel p = panel(stagedTogether());
		set(p, "histFrom", today.minusDays(40));
		set(p, "histTo", today);
		assertEquals("Counters since " + today.minusDays(10).format(FULL),
			noteText(card(labels(history(p)))));
	}

	@Test
	public void aPeriodClosedBeforeTheLootLineJoinedNamesNoLootDate() throws Exception
	{
		LocalDate today = LocalDate.now();
		// the trackers joined ten days ago and the loot totals three days ago:
		// a period closed between the two measures its counters from the
		// trackers' line and names no loot date after its own end
		ChroniclePanel p = panel(staged(true));
		set(p, "histFrom", today.minusDays(20));
		set(p, "histTo", today.minusDays(5));
		List<String> card = card(labels(history(p)));
		assertEquals(card.toString(), "Counters since " + today.minusDays(10).format(FULL),
			noteText(card, "FISHING"));
		assertFalse(card.toString(), String.join(" ", card).contains("loot and kills"));
	}

	@Test
	public void slayerTasksCountTheClosedSegmentsDatedInsideThePeriod() throws Exception
	{
		ChroniclePanel p = panel(stub(true));
		// no journey read yet: the spine's delta stands
		assertEquals("+5", beside(card(labels(history(p))), "Slayer tasks completed"));

		// the journey: a task in hand today, two closed inside the week and one
		// closed before it. Only the closed segments dated inside count.
		double now = System.currentTimeMillis() / 1000.0;
		set(p, "historyJourney", journey(
			task("Abyssal demons", now, true),
			task("Gargoyles", now - 2 * 86_400, false),
			task("Nechryael", now - 4 * 86_400, false),
			task("Dust devils", now - 20 * 86_400, false)));
		assertEquals("+2", beside(card(labels(history(p))), "Slayer tasks completed"));

		// nothing closed inside the week: no line, whatever the spine's delta says
		set(p, "historyJourney", journey(
			task("Abyssal demons", now, true),
			task("Dust devils", now - 20 * 86_400, false)));
		assertNull(beside(card(labels(history(p))), "Slayer tasks completed"));
	}

	@Test
	public void theGatherLandsTheJourneyBesideTheSpine() throws Exception
	{
		// the panel primes one read of the plugin when it is built: the spine,
		// the feed and the journey together. Nothing is set by hand here, so
		// the tasks-completed line reads the journey the worker landed, two
		// closed segments inside the week, over the spine's five
		PanelPreviewTest.StubPlugin s = stub(true);
		double now = System.currentTimeMillis() / 1000.0;
		s.journey = journey(
			task("Abyssal demons", now, true),
			task("Gargoyles", now - 2 * 86_400, false),
			task("Nechryael", now - 4 * 86_400, false),
			task("Dust devils", now - 20 * 86_400, false));
		final ChroniclePanel[] holder = new ChroniclePanel[1];
		edt(() -> holder[0] = new ChroniclePanel(s));
		awaitGather(holder[0]);
		assertEquals("+2", beside(card(labels(history(holder[0]))), "Slayer tasks completed"));
	}

	@Test
	public void theWindowRunsFromThePeriodsFirstMidnightToTheNext() throws Exception
	{
		// the week is [its first day's midnight, the midnight after its last
		// day): a segment or a log slot stamped one second before the first
		// midnight is out, one stamped on it is in, one a second before the
		// closing midnight is in and one on the closing midnight is out
		LocalDate today = LocalDate.now();
		ZoneId zone = ZoneId.systemDefault();
		long fromMs = today.minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli();
		long toMs = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
		PanelPreviewTest.StubPlugin s = stub(true);
		s.feed.add(entry(toMs, "COLLECTION", "itemName", "Abyssal whip"));
		s.feed.add(entry(toMs - 1_000, "COLLECTION", "itemName", "Abyssal head"));
		s.feed.add(entry(fromMs, "COLLECTION", "itemName", "Kraken tentacle"));
		s.feed.add(entry(fromMs - 1_000, "COLLECTION", "itemName", "Dragon pickaxe"));
		ChroniclePanel p = panel(s);
		set(p, "historyJourney", journey(
			task("Gargoyles", toMs / 1000.0, false),
			task("Nechryael", (toMs - 1_000) / 1000.0, false),
			task("Bloodvelds", fromMs / 1000.0, false),
			task("Dust devils", (fromMs - 1_000) / 1000.0, false)));
		List<String> card = card(labels(history(p)));
		assertEquals(card.toString(), "+2", beside(card, "Slayer tasks completed"));
		assertEquals(card.toString(), "+2", beside(card, "Collection log slots"));
	}

	@Test
	public void clogSlotsCountTheFeedsCollectionEntriesWhenTheFeedReachesBack() throws Exception
	{
		long now = System.currentTimeMillis();
		PanelPreviewTest.StubPlugin s = stub(true);
		s.feed.add(entry(now - DAY_MS, "COLLECTION", "itemName", "Abyssal whip"));
		s.feed.add(entry(now - DAY_MS - 3_600_000L, "PET", "petName", "Abyssal orphan"));
		s.feed.add(entry(now - 2 * DAY_MS, "COLLECTION", "itemName", "Abyssal head"));
		s.feed.add(entry(now - 20 * DAY_MS, "COLLECTION", "itemName", "Dragon pickaxe"));
		// the feed reaches back past the week's start: its COLLECTION entries
		// inside the week are the count, the pet beside them is not
		assertEquals("+2", beside(card(labels(history(panel(s)))), "Collection log slots"));

		// a feed that begins inside the week cannot say what it missed: the
		// spine's delta stands
		s.feed.remove(3);
		assertEquals("+7", beside(card(labels(history(panel(s)))), "Collection log slots"));

		// reaching back with no slot inside the week: no line, whatever the
		// spine's delta says
		s.feed.clear();
		s.feed.add(entry(now - DAY_MS, "PET", "petName", "Abyssal orphan"));
		s.feed.add(entry(now - 20 * DAY_MS, "COLLECTION", "itemName", "Dragon pickaxe"));
		assertNull(beside(card(labels(history(panel(s)))), "Collection log slots"));
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
