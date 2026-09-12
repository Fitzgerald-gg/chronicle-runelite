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
 * The History tab's account of a period: the headline strip at the top, the
 * lens detail under it, and the "Tracked progress" card, where every figure
 * the record holds for the period is filed into one of the tab's groups.
 *
 * <p>The headline is the figures a reader wants first, each a plain labelled
 * row and only where the period holds one: the time played and the sessions
 * summed off the feed, the experience, the total level from end to end with
 * the levels gained beside it, the 99s reached, the kills, the slayer tasks,
 * the drops with what they were worth, and the deaths. One line under it says
 * what the figures are dated from when that is later than the period's start.
 *
 * <p>The groups run in a fixed order and are drawn only where the period put
 * something in them, each a fold whose head carries the number of lines it
 * opens to. Inside one: its own figures, then the sections the Stats tab files
 * the rest of the counters into, each reconciling to its floor with the
 * remainder as a ghost "Other". A figure the journal can name (the slayer
 * tasks, the pets, the log slots, the quests, the diaries, the combat
 * achievements, the levels) is itself a fold onto those names and their dates,
 * and a list past its cap ends in a "Show N more" tail. Some figures read the
 * journal rather than the spine and reach back past it: the slayer segments
 * closed inside the period, and the feed's dated entries counted by type when
 * the feed reaches back past the window's start. The folds are keyed apart
 * from the Stats tab's, so a reader's fold on one tab leaves the other as it
 * was.
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
			put(a, b, "lootLeftKills", 5, 9);
			put(a, b, "kills", 300, 312);
			put(a, b, "slayerTasksCompleted", 40, 45);
			put(a, b, "clogSlotsObtained", 400, 407);
			put(a, b, "damageDealt", 500_000, 560_000);
			put(a, b, "deaths", 20, 23);
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

	// a spine of three lines twenty days ago, ten days ago and today, every
	// skill on each, under a live sheet a long way past all three: a window
	// closed between the older two draws its own last line, and one reaching
	// today draws the sheet. The closing line can lack Sailing (the imported
	// past predates it) and can predate kill counts, which the last line and
	// the live ledger carry either way. The ledger stands a few kills past
	// today's line, so a figure tells which one a live period read, and it
	// counts Nechryael, a source the collection log has no page for
	private static PanelPreviewTest.StubPlugin spanned(boolean sailingOnClose,
		boolean kcsOnClose)
	{
		PanelPreviewTest.StubPlugin s =
			new PanelPreviewTest.StubPlugin(Mockito.mock(ItemManager.class));
		LocalDate today = LocalDate.now();
		HistoryLog.Baseline a = new HistoryLog.Baseline();
		HistoryLog.Baseline b = new HistoryLog.Baseline();
		HistoryLog.Baseline c = new HistoryLog.Baseline();
		long overall = 0;
		for (net.runelite.api.Skill sk : net.runelite.api.Skill.values())
		{
			if (sk == net.runelite.api.Skill.OVERALL)
			{
				continue;
			}
			String key = sk.name().toLowerCase(Locale.ROOT);
			a.skills.put(key, 1_000_000L);   // level 73
			if (sailingOnClose || sk != net.runelite.api.Skill.SAILING)
			{
				b.skills.put(key, 1_250_000L);   // level 75
			}
			c.skills.put(key, 1_400_000L);   // level 76
			s.skills.put(key, new long[]{99, 13_100_000L});
			overall += 99;
		}
		s.skills.put("overall", new long[]{overall, 0});
		if (kcsOnClose)
		{
			a.kcs.put("Zulrah", 100L);
			b.kcs.put("Zulrah", 108L);
			a.kcs.put("Nechryael", 580L);
			b.kcs.put("Nechryael", 600L);
		}
		c.kcs.put("Zulrah", 130L);
		c.kcs.put("Nechryael", 630L);
		s.kcs.put("Zulrah", 135L);
		s.kcs.put("Nechryael", 630L);
		s.ledgerKcs.put("Nechryael", 630L);
		s.history.put(today.minusDays(20), a);
		s.history.put(today.minusDays(10), b);
		s.history.put(today, c);
		return s;
	}

	// A spine read the way the client reads it, so each line's completeness is
	// derived where the line is parsed rather than set by hand. One string per
	// line of the file.
	private static java.util.TreeMap<LocalDate, HistoryLog.Baseline> readSpine(String... lines)
		throws Exception
	{
		File dir = Files.createTempDirectory("chronicle-history-grid").toFile();
		File f = new File(dir, LocalStore.slug("Tester") + HistoryLog.SPINE_SUFFIX);
		Files.write(f.toPath(), String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
		return new HistoryLog(new com.google.gson.Gson()).read(dir, "Tester");
	}

	// The owner's first year, in miniature: a complete snapshot on 1 January
	// carrying nothing but hitpoints, so every other skill stood at zero, and a
	// complete close on 31 December.
	private static PanelPreviewTest.StubPlugin firstYear() throws Exception
	{
		PanelPreviewTest.StubPlugin s =
			new PanelPreviewTest.StubPlugin(Mockito.mock(ItemManager.class));
		s.history = readSpine(
			"{\"date\":\"2022-01-01\",\"skills\":{\"hitpoints\":1151,\"overall\":1151}}",
			"{\"date\":\"2022-12-31\",\"skills\":{\"attack\":13034431,\"hitpoints\":1250000,"
				+ "\"overall\":14284431}}");
		return s;
	}

	// the number of skills the client draws in the grid: every one but Overall,
	// which the api keeps as a field beside the constants rather than among them
	private static int skillCount()
	{
		int n = 0;
		for (net.runelite.api.Skill sk : net.runelite.api.Skill.values())
		{
			if (sk != net.runelite.api.Skill.OVERALL)
			{
				n++;
			}
		}
		return n;
	}

	// the labels of the skill grid alone: from the first cell's icon text to
	// the end of the last cell
	private static List<String> grid(List<String> all)
	{
		int at = all.indexOf("ATT");
		assertTrue(all.toString(), at >= 0);
		return all.subList(at, all.size());
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
		return task(name, ts, inProgress, 100);
	}

	// the same, with the kills the segment holds
	private static ChronicleApiClient.SlayerTask task(String name, double ts, boolean inProgress,
		long kills)
	{
		return new ChronicleApiClient.SlayerTask(name, kills, inProgress ? 150 : 0, 0, ts, 1_000L,
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

	// the headline strip's labels, from its caption to the lens detail or the
	// groups below it
	private static List<String> headline(List<String> all)
	{
		int at = all.indexOf("THE PERIOD");
		assertTrue(all.toString(), at >= 0);
		int end = all.size();
		for (int i = at + 1; i < all.size(); i++)
		{
			String label = all.get(i);
			if (label.equals("ATT") || label.equals("TRACKED PROGRESS")
				|| label.equals("BOSSES AND ACTIVITIES")
				|| label.equals("EVERYTHING ELSE COUNTED") || label.startsWith("MILESTONES"))
			{
				end = i;
				break;
			}
		}
		return all.subList(at, end);
	}

	// the figure the total level line closes on, whether or not it names the
	// level it opened from
	private static String standingLevel(List<String> all)
	{
		String total = beside(headline(all), "Total level");
		assertNotNull(all.toString(), total);
		int at = total.indexOf(" to ");
		String to = at < 0 ? total : total.substring(at + 4);
		int dot = to.indexOf(" · ");
		return dot < 0 ? to : to.substring(0, dot);
	}

	// the card's labels under one group head, up to the next one named
	private static List<String> between(List<String> card, String from, String to)
	{
		int a = card.indexOf(from);
		assertTrue(from + " is not on the card: " + card, a >= 0);
		int b = to == null ? -1 : card.indexOf(to);
		return card.subList(a, b < 0 ? card.size() : b);
	}

	// the first label beginning with {@code prefix}, or -1
	private static int indexStarting(List<String> all, String prefix)
	{
		for (int i = 0; i < all.size(); i++)
		{
			if (all.get(i).startsWith(prefix))
			{
				return i;
			}
		}
		return -1;
	}

	/**
	 * The wrapped note holding {@code word}, joined back into the one line it
	 * was written as, or null when no note holds it. note() measures its own
	 * wrap and mounts one label per line in a panel of its own, so a note is
	 * the only box whose children are all labels.
	 */
	private static String noteHolding(Container c, String word)
	{
		for (Component child : c.getComponents())
		{
			if (child instanceof JPanel
				&& ((JPanel) child).getLayout() instanceof javax.swing.BoxLayout)
			{
				List<String> lines = new ArrayList<>();
				boolean allLabels = ((JPanel) child).getComponentCount() > 0;
				for (Component k : ((JPanel) child).getComponents())
				{
					if (k instanceof JLabel)
					{
						lines.add(((JLabel) k).getText());
					}
					else
					{
						allLabels = false;
						break;
					}
				}
				if (allLabels && String.join(" ", lines).contains(word))
				{
					return String.join(" ", lines);
				}
			}
			if (child instanceof Container)
			{
				String hit = noteHolding((Container) child, word);
				if (hit != null)
				{
					return hit;
				}
			}
		}
		return null;
	}

	// a panel on the same stub with every group open, so a figure inside one
	// is on the render
	private static ChroniclePanel opened(PanelPreviewTest.StubPlugin s) throws Exception
	{
		ChroniclePanel p = panel(s);
		for (String group : chronicle.panel.HistoryProgress.GROUPS)
		{
			openFolds(p).add("history:" + group);
		}
		return p;
	}

	// the Loot group's labels on a stub, the group open
	private static List<String> loot(PanelPreviewTest.StubPlugin s) throws Exception
	{
		ChroniclePanel p = panel(s);
		openFolds(p).add("history:Loot");
		return card(labels(history(p)));
	}

	private static String fmt(long n)
	{
		return String.format(Locale.UK, "%,d", n);
	}

	// how many labels on the card begin with {@code prefix}
	private static int countStarting(List<String> card, String prefix)
	{
		int n = 0;
		for (String label : card)
		{
			if (label.startsWith(prefix))
			{
				n++;
			}
		}
		return n;
	}

	// the indent a named row is drawn at: the left edge of its own border
	private static int leftInset(Container c, String name)
	{
		JPanel row = rowNamed(c, name);
		assertNotNull(name + " is not on the render", row);
		return row.getBorder().getBorderInsets(row).left;
	}

	// the day a stamp falls on, as the tab's lists write it
	private static String day(long ms)
	{
		return DateTimeFormatter.ofPattern("d MMM", Locale.UK).withZone(ZoneId.systemDefault())
			.format(java.time.Instant.ofEpochMilli(ms));
	}

	// one played session: the minutes it ran
	private static JsonObject session(long ts, long minutes)
	{
		JsonObject e = new JsonObject();
		e.addProperty("ts", ts);
		e.addProperty("type", "SESSION");
		JsonObject data = new JsonObject();
		data.addProperty("minutes", minutes);
		e.add("data", data);
		return e;
	}

	// one played session carrying its whole take: what it received, what it
	// left on the floor, and the kills that left it
	private static JsonObject session(long ts, long minutes, long drops, long dropsGp,
		long left, long leftGp, long leftKills)
	{
		JsonObject e = session(ts, minutes);
		JsonObject d = e.getAsJsonObject("data");
		d.addProperty("drops", drops);
		d.addProperty("dropsGp", dropsGp);
		d.addProperty("left", left);
		d.addProperty("leftGp", leftGp);
		d.addProperty("leftKills", leftKills);
		return e;
	}

	// a feed entry carrying a second field: a level, a diary's difficulty, a
	// combat achievement's tier
	private static JsonObject entry(long ts, String type, String key, String val,
		String key2, String val2)
	{
		JsonObject e = entry(ts, type, key, val);
		e.getAsJsonObject("data").addProperty(key2, val2);
		return e;
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
	public void theHeadlineReadsInOrderWithItsFigures() throws Exception
	{
		List<String> head = headline(labels(history(panel(stub(true)))));
		// the figures a reader wants first, each a plain labelled row and no
		// sentence: what the period cost, what it added, where it left the
		// sheet, and the counts the groups below break down. The drops carry
		// what they were worth beside them, one line for one pair. Time played,
		// the 99s and the slayer kills are absent: this record holds no
		// session, reached no 99 and never carried the kills.
		assertEquals(Arrays.asList(
			"THE PERIOD",
			"Experience", "+50k",
			"Total level", "73",
			"Kills", "+12",
			"Slayer tasks completed", "+5",
			"Drops received", "+12 · 2.5M gp",
			"Deaths", "+3"), head);
	}

	@Test
	public void timePlayedSumsTheSessionMinutesInsideTheWindow() throws Exception
	{
		long now = System.currentTimeMillis();
		PanelPreviewTest.StubPlugin s = stub(true);
		// no session on the record: neither line is drawn
		List<String> bare = headline(labels(history(panel(s))));
		assertNull(bare.toString(), beside(bare, "Time played"));
		assertNull(bare.toString(), beside(bare, "Sessions"));

		// three sessions inside the week and one before it, the slice reaching
		// back past the window's start: the three are summed, the older one is
		// out, and the pair leads the strip
		s.feed.add(session(now - DAY_MS, 95));
		s.feed.add(session(now - 2 * DAY_MS, 42));
		s.feed.add(session(now - 3 * DAY_MS, 58));
		s.feed.add(session(now - 20 * DAY_MS, 400));
		List<String> head = headline(labels(history(panel(s))));
		assertEquals(head.toString(), "3h 15m", beside(head, "Time played"));
		assertEquals(head.toString(), "3", beside(head, "Sessions"));
		assertEquals(head.toString(), Arrays.asList("THE PERIOD", "Time played", "3h 15m",
			"Sessions", "3", "Experience"), head.subList(0, 6));

		// a slice that begins inside the window cannot say what it missed
		s.feed.remove(3);
		List<String> inside = headline(labels(history(panel(s))));
		assertNull(inside.toString(), beside(inside, "Time played"));
		assertNull(inside.toString(), beside(inside, "Sessions"));
	}

	@Test
	public void theGroupsReadInOrderEachWithItsCount() throws Exception
	{
		List<String> card = card(labels(history(panel(stub(true)))));
		// one shut fold per group, in the tab's fixed order, each carrying the
		// number of lines it opens to. Travel and Achievement are absent: this
		// period moved nothing either of them claims.
		assertEquals(Arrays.asList(
			"TRACKED PROGRESS",
			"EXPERIENCE", "1",
			"COMBAT", "4",
			"LOOT", "6",
			"SKILLING", "1",
			"UPKEEP", "3",
			"THE REST", "1"), card);
	}

	@Test
	public void aGroupsCountIsTheLinesItOpensTo() throws Exception
	{
		ChroniclePanel p = panel(stub(true));
		openFolds(p).add("history:Combat");
		List<String> card = card(labels(history(p)));
		int at = card.indexOf("COMBAT");
		assertEquals(card.toString(), Arrays.asList("COMBAT", "4",
			"Kills", "+12", "Slayer tasks completed", "+5", "Damage dealt", "+60,000",
			"Deaths", "+3", "LOOT"), card.subList(at, at + 11));
	}

	@Test
	public void aFamilysFlatRowsRunOnAsTheGroupsOwnRows() throws Exception
	{
		ChroniclePanel p = panel(stub(true));
		openFolds(p).add("history:Upkeep");
		List<String> card = card(labels(history(p)));
		// vials shattered is Living's flat list on the Stats tab: here it is a
		// row of the group itself, above the folds, and no head repeats the
		// name the group already carries
		int at = card.indexOf("UPKEEP");
		assertEquals(card.toString(), Arrays.asList("UPKEEP", "3", "Vials shattered", "+3",
			"Food", "+20", "Potions", "+225", "THE REST"), card.subList(at, at + 9));
		assertFalse(card.toString(), card.contains("LIVING"));
	}

	@Test
	public void everyCapitalisedLineInTheCardIsAGroupHead() throws Exception
	{
		JPanel view = history(panel(stub(true)));
		List<String> card = card(labels(view));
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
		assertEquals(card.toString(), 6, heads);
		// the sections live inside their group in normal case, so no family
		// name and no section name stands as a head of its own
		for (String shouted : new String[]{"LIVING", "LEDGER & ROADS", "FISHING", "POTIONS"})
		{
			assertFalse(card.toString(), card.contains(shouted));
		}
	}

	@Test
	public void aFlatKeyThatHeadsAFoldShowsOnceAsItsFigure() throws Exception
	{
		ChroniclePanel p = panel(stub(true));
		openFolds(p).add("history:Upkeep");
		List<String> shut = card(labels(history(p)));
		// doses drunk is Living's flat key and the Potions floor: it heads the
		// fold and is no row beside it, so the figure reads once; meals eaten
		// heads Food the same way
		assertFalse(shut.toString(), shut.contains("Doses drunk"));
		assertFalse(shut.toString(), shut.contains("Meals eaten"));
		assertEquals(shut.toString(), 1, java.util.Collections.frequency(shut, "+225"));
		int at = shut.indexOf("Potions");
		assertEquals(shut.toString(), Arrays.asList("Potions", "+225", "THE REST"),
			shut.subList(at, at + 3));

		// open, the fold reads its typed rows against the floor
		openFolds(p).add("history:Living:Potions");
		List<String> open = card(labels(history(p)));
		at = open.indexOf("Potions");
		assertEquals(open.toString(),
			Arrays.asList("Potions", "+225", "Prayer", "+140", "Other", "+85", "THE REST"),
			open.subList(at, at + 7));
		assertFalse(open.toString(), open.contains("Doses drunk"));
	}

	@Test
	public void aClickOnTheHeadOpensTheGroupThenTheFoldToItsRowsAndTheGhost() throws Exception
	{
		ChroniclePanel p = panel(stub(true));
		JPanel view = history(p);
		List<String> shut = labels(view);
		// a shut group hides its sections, and a shut section its rows
		assertFalse(shut.toString(), shut.contains("Fishing"));
		assertFalse(shut.toString(), shut.contains("Shark"));
		assertTrue(openFolds(p).isEmpty());

		// the group's head opens it to its sections
		JPanel group = rowNamed(view, "SKILLING");
		assertNotNull(shut.toString(), group);
		assertEquals(java.awt.Cursor.HAND_CURSOR, group.getCursor().getType());
		click(group);
		assertEquals(java.util.Collections.singleton("history:Skilling"), openFolds(p));
		view = history(p);
		List<String> open = card(labels(view));
		int at = open.indexOf("SKILLING");
		assertEquals(open.toString(), Arrays.asList("SKILLING", "1", "Fishing", "+50", "UPKEEP"),
			open.subList(at, at + 5));

		// and the section's head opens it to its rows and the leftover
		click(rowNamed(view, "Fishing"));
		assertEquals(new java.util.HashSet<>(Arrays.asList("history:Skilling", FOLD)),
			openFolds(p));
		open = card(labels(history(p)));
		at = open.indexOf("Fishing");
		assertEquals(open.toString(),
			Arrays.asList("Fishing", "+50", "Shark", "+30", "Other", "+20", "UPKEEP"),
			open.subList(at, at + 7));

		// and the same click shuts it again
		click(rowNamed(history(p), "Fishing"));
		assertEquals(java.util.Collections.singleton("history:Skilling"), openFolds(p));
	}

	@Test
	public void theOpenHeadTakesTheAccentAndOneFoldsStateIsItsOwn() throws Exception
	{
		ChroniclePanel p = panel(stub(true));
		openFolds(p).add("history:Upkeep");
		openFolds(p).add("history:Living:Food");
		JPanel view = history(p);
		List<String> card = card(labels(view));
		int at = card.indexOf("Food");
		// meals eaten is the floor: the typed row reconciles to it and the rest
		// is the ghost
		assertEquals(card.toString(),
			Arrays.asList("Food", "+20", "Shark", "+16", "Other", "+4", "Potions"),
			card.subList(at, at + 7));
		// Potions stays shut: one fold's state is its own
		assertEquals(card.toString(), 1, java.util.Collections.frequency(card, "Shark"));

		JLabel openName = (JLabel) ((BorderLayout) rowNamed(view, "Food").getLayout())
			.getLayoutComponent(BorderLayout.CENTER);
		JLabel shutName = (JLabel) ((BorderLayout) rowNamed(view, "Potions").getLayout())
			.getLayoutComponent(BorderLayout.CENTER);
		assertFalse(openName.getForeground().equals(shutName.getForeground()));
	}

	@Test
	public void theStatsTabsFoldsLeaveTheHistoryFoldsShut() throws Exception
	{
		ChroniclePanel p = panel(stub(true));
		openFolds(p).add("Skilling");
		openFolds(p).add("Skilling:Fishing");
		openFolds(p).add("Living:Food");
		List<String> card = card(labels(history(p)));
		assertFalse(card.toString(), card.contains("Fishing"));
		assertFalse(card.toString(), card.contains("Shark"));
		assertFalse(card.toString(), card.contains("Other"));
	}

	@Test
	public void aListPastItsCapShowsATailAndTheTailRaisesIt() throws Exception
	{
		long now = System.currentTimeMillis();
		PanelPreviewTest.StubPlugin s = stub(true);
		String[] items = {"Abyssal whip", "Abyssal head", "Abyssal dagger", "Kraken tentacle",
			"Dragon pickaxe", "Occult necklace", "Zamorakian spear", "Dragon warhammer",
			"Saradomin sword"};
		for (int i = 0; i < items.length; i++)
		{
			s.feed.add(entry(now - DAY_MS - i * 60_000L, "COLLECTION", "itemName", items[i]));
		}
		s.feed.add(entry(now - 20 * DAY_MS, "COLLECTION", "itemName", "Rune platebody"));
		ChroniclePanel p = panel(s);
		openFolds(p).add("history:Loot");
		openFolds(p).add("history:list:clogSlotsObtained");
		JPanel view = history(p);
		List<String> card = card(labels(view));
		assertEquals(card.toString(), "+9", beside(card, "Collection log slots"));
		// the cap is honoured: six names, then the tail saying what one more
		// click adds
		for (int i = 0; i < 6; i++)
		{
			assertTrue(card.toString(), card.contains(items[i]));
		}
		assertFalse(card.toString(), card.contains(items[6]));
		assertTrue(card.toString(), card.contains("Show 3 more"));

		// a click on the tail raises the cap, and the list ends where it ends
		click(rowNamed(view, "Show 3 more"));
		List<String> more = card(labels(history(p)));
		assertTrue(more.toString(), more.contains(items[8]));
		assertFalse(more.toString(), more.contains("Show 3 more"));
	}

	@Test
	public void aListFarPastItsCapSaysWhatIsLeftAndOneClickBringsIt() throws Exception
	{
		// nineteen names under a cap of six: the tail names everything still
		// folded away, and one click mounts all of it. The owner's own windows
		// run far longer, a year of slayer tasks past ninety, and a tail that
		// said six every time would be fifteen clicks that never say so.
		long now = System.currentTimeMillis();
		PanelPreviewTest.StubPlugin s = stub(true);
		for (int i = 0; i < 19; i++)
		{
			s.feed.add(entry(now - DAY_MS - i * 60_000L, "COLLECTION", "itemName", "Slot " + i));
		}
		s.feed.add(entry(now - 20 * DAY_MS, "COLLECTION", "itemName", "Rune platebody"));
		ChroniclePanel p = panel(s);
		openFolds(p).add("history:Loot");
		openFolds(p).add("history:list:clogSlotsObtained");
		JPanel view = history(p);
		List<String> card = card(labels(view));
		assertEquals(card.toString(), "+19", beside(card, "Collection log slots"));
		assertEquals(card.toString(), 6, countStarting(card, "Slot "));
		assertTrue(card.toString(), card.contains("Show 13 more"));

		click(rowNamed(view, "Show 13 more"));
		List<String> more = card(labels(history(p)));
		assertEquals(more.toString(), 19, countStarting(more, "Slot "));
		assertFalse(more.toString(), more.stream().anyMatch(l -> l.startsWith("Show ")));
	}

	@Test
	public void aFeedThatBeginsInsideTheWindowNamesNothing() throws Exception
	{
		// the feed's oldest entry falls inside the period, so the slice cannot
		// say what it missed: the figure stands at the spine's own delta of
		// seven and the row stays a plain row, rather than a fold whose head
		// says seven and whose list names one
		long now = System.currentTimeMillis();
		PanelPreviewTest.StubPlugin s = stub(true);
		s.feed.add(entry(now - DAY_MS, "COLLECTION", "itemName", "Abyssal whip"));
		ChroniclePanel p = panel(s);
		openFolds(p).add("history:Loot");
		openFolds(p).add("history:list:clogSlotsObtained");
		JPanel view = history(p);
		List<String> card = card(labels(view));
		assertEquals(card.toString(), "+7", beside(card, "Collection log slots"));
		assertFalse(card.toString(), card.contains("Abyssal whip"));
		assertEquals(card.toString(), java.awt.Cursor.DEFAULT_CURSOR,
			rowNamed(view, "Collection log slots").getCursor().getType());
	}

	@Test
	public void aFigureTheJournalCanOnlyPartlyNameClosesWithTheShortfall() throws Exception
	{
		// two pets inside the window and one of them imported, carrying no
		// name: the fold opens to the one it can name and closes with the one
		// it cannot, so the head's figure is accounted for on screen
		long now = System.currentTimeMillis();
		PanelPreviewTest.StubPlugin s = stub(true);
		s.feed.add(entry(now - DAY_MS, "PET", "petName", "Vorki"));
		JsonObject bare = new JsonObject();
		bare.addProperty("ts", now - 2 * DAY_MS);
		bare.addProperty("type", "PET");
		JsonObject data = new JsonObject();
		data.addProperty("imported", true);
		bare.add("data", data);
		s.feed.add(bare);
		s.feed.add(entry(now - 20 * DAY_MS, "COLLECTION", "itemName", "Rune platebody"));
		ChroniclePanel p = panel(s);
		openFolds(p).add("history:Loot");
		openFolds(p).add("history:list:petsObtained");
		List<String> card = card(labels(history(p)));
		int at = card.indexOf("Pets");
		assertEquals(card.toString(), Arrays.asList("Pets", "+2", "Vorki", day(now - DAY_MS),
			"Not named in the record", "+1"), card.subList(at, at + 6));
	}

	@Test
	public void aSectionsRowsAndAListsNamesSitInFromTheGroupsOwnRows() throws Exception
	{
		// three indents, and each one a step: a group's own rows, a section's
		// head, and the rows or names a section or a list opens to
		long now = System.currentTimeMillis();
		PanelPreviewTest.StubPlugin s = stub(true);
		s.feed.add(entry(now - DAY_MS, "COLLECTION", "itemName", "Abyssal whip"));
		s.feed.add(entry(now - 20 * DAY_MS, "COLLECTION", "itemName", "Rune platebody"));
		ChroniclePanel p = panel(s);
		openFolds(p).add("history:Loot");
		openFolds(p).add("history:list:clogSlotsObtained");
		openFolds(p).add("history:Skilling");
		openFolds(p).add(FOLD);
		JPanel view = history(p);
		int group = leftInset(view, "Drops received");
		assertEquals(leftInset(view, "SKILLING"), group);
		assertTrue(leftInset(view, "Fishing") > group);
		assertTrue(leftInset(view, "Shark") > leftInset(view, "Fishing"));
		assertEquals(leftInset(view, "Abyssal whip"), leftInset(view, "Shark"));
		assertEquals(leftInset(view, "Other"), leftInset(view, "Shark"));
	}

	@Test
	public void theGainsTailSitsWithTheGainsItPages() throws Exception
	{
		// Experience's ranked gains are the group's own rows, so the tail that
		// pages them stands at their indent and not one step in
		PanelPreviewTest.StubPlugin s =
			new PanelPreviewTest.StubPlugin(Mockito.mock(ItemManager.class));
		HistoryLog.Baseline a = new HistoryLog.Baseline();
		HistoryLog.Baseline b = new HistoryLog.Baseline();
		String[] skills = {"attack", "strength", "defence", "slayer", "mining", "fishing",
			"cooking", "woodcutting"};
		for (int i = 0; i < skills.length; i++)
		{
			a.skills.put(skills[i], 1_000_000L);
			b.skills.put(skills[i], 1_000_000L + 10_000L * (skills.length - i));
		}
		LocalDate today = LocalDate.now();
		s.history.put(today.minusDays(10), a);
		s.history.put(today, b);
		ChroniclePanel p = panel(s);
		openFolds(p).add("history:Experience");
		JPanel view = history(p);
		assertEquals(leftInset(view, "Show 2 more"), leftInset(view, "Attack"));
	}

	@Test
	public void theNamedListsComeFromTheFeedInsideTheWindow() throws Exception
	{
		long now = System.currentTimeMillis();
		PanelPreviewTest.StubPlugin s = stub(true);
		s.feed.add(entry(now - DAY_MS, "LEVEL", "skill", "Slayer", "level", "92"));
		s.feed.add(entry(now - 2 * DAY_MS, "PET", "petName", "Vorki"));
		s.feed.add(entry(now - 3 * DAY_MS, "COLLECTION", "itemName", "Abyssal whip"));
		s.feed.add(entry(now - 4 * DAY_MS, "QUEST", "questName", "Dragon Slayer II"));
		s.feed.add(entry(now - 5 * DAY_MS, "COMBAT_ACHIEVEMENT", "task", "Perfect Zulrah",
			"tier", "ELITE"));
		s.feed.add(entry(now - 6 * DAY_MS, "DIARY", "area", "Karamja", "difficulty", "Elite"));
		// one of each before the window, so the slice reaches back and none of
		// them counts
		s.feed.add(entry(now - 20 * DAY_MS, "LEVEL", "skill", "Mining", "level", "80"));
		s.feed.add(entry(now - 21 * DAY_MS, "PET", "petName", "Baby mole"));
		s.feed.add(entry(now - 22 * DAY_MS, "COLLECTION", "itemName", "Rune platebody"));
		ChroniclePanel p = panel(s);
		for (String group : new String[]{"Experience", "Loot", "Achievement"})
		{
			openFolds(p).add("history:" + group);
		}
		for (String key : new String[]{"levelsGained", "petsObtained", "clogSlotsObtained",
			"questsCompleted", "combatAchievements", "diariesCompleted"})
		{
			openFolds(p).add("history:list:" + key);
		}
		List<String> card = card(labels(history(p)));
		// each figure opens to the names the journal holds for it, dated
		assertEquals(card.toString(), Arrays.asList("Levels gained", "+1",
			"Slayer 92", day(now - DAY_MS)),
			card.subList(card.indexOf("Levels gained"), card.indexOf("Levels gained") + 4));
		assertEquals(card.toString(), Arrays.asList("Pets", "+1", "Vorki", day(now - 2 * DAY_MS)),
			card.subList(card.indexOf("Pets"), card.indexOf("Pets") + 4));
		assertEquals(card.toString(), Arrays.asList("Collection log slots", "+1",
			"Abyssal whip", day(now - 3 * DAY_MS)),
			card.subList(card.indexOf("Collection log slots"),
				card.indexOf("Collection log slots") + 4));
		assertEquals(card.toString(), Arrays.asList("Quests completed", "+1",
			"Dragon Slayer II", day(now - 4 * DAY_MS)),
			card.subList(card.indexOf("Quests completed"), card.indexOf("Quests completed") + 4));
		// the combat achievement carries its tier, the diary its difficulty
		assertEquals(card.toString(), Arrays.asList("Combat achievements", "+1",
			"Elite · Perfect Zulrah", day(now - 5 * DAY_MS)),
			card.subList(card.indexOf("Combat achievements"),
				card.indexOf("Combat achievements") + 4));
		assertEquals(card.toString(), Arrays.asList("Diaries completed", "+1",
			"Karamja Elite", day(now - 6 * DAY_MS)),
			card.subList(card.indexOf("Diaries completed"),
				card.indexOf("Diaries completed") + 4));
		// nothing dated outside the window is named
		for (String outside : new String[]{"Mining 80", "Baby mole", "Rune platebody"})
		{
			assertFalse(card.toString(), card.contains(outside));
		}
	}

	@Test
	public void aFigureTheJournalCannotNameStaysAPlainRow() throws Exception
	{
		long now = System.currentTimeMillis();
		PanelPreviewTest.StubPlugin s = stub(true);
		// an imported milestone carries no name: it counts, and the row it
		// counts under opens to nothing, so it is no fold at all
		JsonObject bare = new JsonObject();
		bare.addProperty("ts", now - DAY_MS);
		bare.addProperty("type", "PET");
		JsonObject data = new JsonObject();
		data.addProperty("imported", true);
		bare.add("data", data);
		s.feed.add(bare);
		s.feed.add(entry(now - 20 * DAY_MS, "COLLECTION", "itemName", "Rune platebody"));
		ChroniclePanel p = panel(s);
		openFolds(p).add("history:Loot");
		openFolds(p).add("history:list:petsObtained");
		JPanel view = history(p);
		List<String> card = card(labels(view));
		assertEquals(card.toString(), "+1", beside(card, "Pets"));
		assertEquals(card.toString(), java.awt.Cursor.DEFAULT_CURSOR,
			rowNamed(view, "Pets").getCursor().getType());
	}

	@Test
	public void theTasksListNamesTheSegmentsClosedInsideThePeriod() throws Exception
	{
		ChroniclePanel p = panel(stub(true));
		double now = System.currentTimeMillis() / 1000.0;
		set(p, "historyJourney", journey(
			task("Abyssal demons", now, true, 90),
			task("Gargoyles", now - 2 * 86_400, false, 137),
			task("Nechryael", now - 4 * 86_400, false, 58),
			task("Dust devils", now - 20 * 86_400, false, 200)));
		openFolds(p).add("history:Combat");
		openFolds(p).add("history:list:slayerTasksCompleted");
		List<String> card = card(labels(history(p)));
		int at = card.indexOf("Slayer tasks completed");
		// newest first, each with the kills it took and the day it closed
		assertEquals(card.toString(), Arrays.asList("Slayer tasks completed", "+2",
			"Gargoyles", "137 · " + day((long) ((now - 2 * 86_400) * 1000)),
			"Nechryael", "58 · " + day((long) ((now - 4 * 86_400) * 1000))),
			card.subList(at, at + 6));
		// the task in hand is nobody's yet, and the one closed before the week
		// belongs to that week
		assertFalse(card.toString(), card.contains("Abyssal demons"));
		assertFalse(card.toString(), card.contains("Dust devils"));
	}

	@Test
	public void everyFamilyWithAMovedCounterIsReachable() throws Exception
	{
		PanelPreviewTest.StubPlugin s =
			new PanelPreviewTest.StubPlugin(Mockito.mock(ItemManager.class));
		HistoryLog.Baseline a = new HistoryLog.Baseline();
		HistoryLog.Baseline b = new HistoryLog.Baseline();
		// one key out of every family and every section the registry files
		// them into
		put(a, b, "damageTaken", 100_000, 140_000);       // Combat, its flat list
		put(a, b, "hitsMissed", 4_000, 4_600);            // Combat, its flat list
		put(a, b, "fishCaught", 1_000, 1_050);            // Skilling
		put(a, b, "vialsShattered", 10, 13);              // Living, its flat list
		put(a, b, "foodEaten", 200, 220);                 // Living, Food
		put(a, b, "coinsFromAlchemy", 1_000_000, 1_400_000);   // Ledger, its own row
		put(a, b, "untakenLootValue", 2_000_000, 2_900_000);   // Ledger, The purse
		put(a, b, "examines", 400, 430);                  // Ledger, Odds & ends
		put(a, b, "teleportsTotal", 1_000, 1_060);        // Ledger, Teleports
		put(a, b, "teleportsVarrock", 300, 318);          // Ledger, Destinations
		put(a, b, "tilesRan", 900_000, 940_000);          // Ledger, On foot
		LocalDate today = LocalDate.now();
		s.history.put(today.minusDays(10), a);
		s.history.put(today, b);
		ChroniclePanel p = panel(s);
		for (String group : chronicle.panel.HistoryProgress.GROUPS)
		{
			openFolds(p).add("history:" + group);
		}
		for (String section : new String[]{"Skilling:Fishing", "Living:Food",
			"Ledger & Roads:The purse", "Ledger & Roads:Odds & ends",
			"Ledger & Roads:Teleports", "Ledger & Roads:Destinations",
			"Ledger & Roads:On foot"})
		{
			openFolds(p).add("history:" + section);
		}
		List<String> card = card(labels(history(p)));
		// every one of them is reachable, under the group that claims it
		for (String label : new String[]{"Damage taken", "Hits missed", "Fishing",
			"Vials shattered", "Food", "The purse", "Uncollected loot", "Coins from alchemy",
			"Odds & ends", "Examines", "Teleports", "Destinations", "Varrock", "On foot",
			"Tiles run"})
		{
			assertTrue(label + " is out of reach: " + card, card.contains(label));
		}
		// and each under the right head
		assertTrue(card.toString(), between(card, "COMBAT", "SKILLING").contains("Damage taken"));
		assertTrue(card.toString(), between(card, "SKILLING", "UPKEEP").contains("Fishing"));
		assertTrue(card.toString(), between(card, "UPKEEP", "TRAVEL").contains("Vials shattered"));
		assertTrue(card.toString(), between(card, "TRAVEL", "THE REST").contains("Teleports"));
		assertTrue(card.toString(),
			between(card, "THE REST", null).contains("Coins from alchemy"));
	}

	@Test
	public void slayerTasksCountTheClosedSegmentsDatedInsideThePeriod() throws Exception
	{
		ChroniclePanel p = panel(stub(true));
		// no journey read yet: the spine's delta stands
		assertEquals("+5", beside(headline(labels(history(p))), "Slayer tasks completed"));

		// the journey: a task in hand today, two closed inside the week and one
		// closed before it. Only the closed segments dated inside count.
		double now = System.currentTimeMillis() / 1000.0;
		set(p, "historyJourney", journey(
			task("Abyssal demons", now, true),
			task("Gargoyles", now - 2 * 86_400, false),
			task("Nechryael", now - 4 * 86_400, false),
			task("Dust devils", now - 20 * 86_400, false)));
		assertEquals("+2", beside(headline(labels(history(p))), "Slayer tasks completed"));

		// nothing closed inside the week: no line, whatever the spine's delta says
		set(p, "historyJourney", journey(
			task("Abyssal demons", now, true),
			task("Dust devils", now - 20 * 86_400, false)));
		assertNull(beside(headline(labels(history(p))), "Slayer tasks completed"));
	}

	@Test
	public void slayerKillsSumTheClosedSegmentsDatedInsideThePeriod() throws Exception
	{
		ChroniclePanel p = panel(stub(true));
		openFolds(p).add("history:Combat");
		// the spine never carries the figure: no journey read yet, no line
		assertNull(beside(card(labels(history(p))), "Slayer kills"));

		// the journey: a task in hand today, two closed inside the week and one
		// closed before it. The kills of the closed segments dated inside are
		// the figure; the one in hand and the one before the week are not.
		double now = System.currentTimeMillis() / 1000.0;
		set(p, "historyJourney", journey(
			task("Abyssal demons", now, true, 90),
			task("Gargoyles", now - 2 * 86_400, false, 137),
			task("Nechryael", now - 4 * 86_400, false, 58),
			task("Dust devils", now - 20 * 86_400, false, 200)));
		List<String> card = card(labels(history(p)));
		assertEquals(card.toString(), "+195", beside(card, "Slayer kills"));
		// right after the tasks line, in the group's fixed order
		assertEquals(card.toString(), card.indexOf("Slayer tasks completed") + 2,
			card.indexOf("Slayer kills"));

		// nothing closed inside the week: no line
		set(p, "historyJourney", journey(
			task("Abyssal demons", now, true, 90),
			task("Dust devils", now - 20 * 86_400, false, 200)));
		assertNull(beside(card(labels(history(p))), "Slayer kills"));
	}

	@Test
	public void theKillsToggleNamesEveryMonster() throws Exception
	{
		// the lens reads Skills and Kills: the list under it is every source the
		// record counted, not bosses alone
		PanelPreviewTest.StubPlugin s = stub(true);
		s.kcs.put("Zulrah", 108L);
		s.kcs.put("Nechryael", 622L);
		s.ledgerKcs.put("Nechryael", 622L);
		ChroniclePanel p = panel(s);
		List<String> all = labels(history(p));
		assertEquals(all.toString(), all.indexOf("Skills") + 1, all.indexOf("Kills"));
		assertFalse(all.toString(), all.contains("Bosses"));

		// under it, the log's pages first and the ledger's own sources apart,
		// each at its standing count with the period's gain beside
		set(p, "histBosses", true);
		all = labels(history(p));
		int bosses = all.indexOf("BOSSES AND ACTIVITIES");
		int rest = all.indexOf("EVERYTHING ELSE COUNTED");
		assertTrue(all.toString(), bosses > 0 && rest > bosses);
		assertEquals(all.toString(), "Zulrah", all.get(bosses + 1));
		assertEquals(all.toString(), "108  +8", all.get(bosses + 2));
		assertEquals(all.toString(), "Nechryael", all.get(rest + 1));
		assertEquals(all.toString(), "622", all.get(rest + 2));
		assertFalse(all.toString(), all.subList(bosses, rest).contains("Nechryael"));
	}

	@Test
	public void theKillsLensKeepsTheHeadlineAboveIt() throws Exception
	{
		// the headline is the period's, not the lens's: the same strip stands
		// over the kill list, and the Kills line it carries is the sum of the
		// gains under it
		PanelPreviewTest.StubPlugin s = stub(true);
		s.kcs.put("Zulrah", 108L);
		ChroniclePanel p = panel(s);
		set(p, "histBosses", true);
		List<String> all = labels(history(p));
		assertEquals(all.toString(), "+12", beside(headline(all), "Kills"));
		assertTrue(all.toString(), all.indexOf("THE PERIOD")
			< all.indexOf("BOSSES AND ACTIVITIES"));
		// and the old head card counting kills twice is gone
		assertEquals(all.toString(), 1, java.util.Collections.frequency(all, "THE PERIOD"));
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
		assertEquals("+2",
			beside(headline(labels(history(holder[0]))), "Slayer tasks completed"));
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
		openFolds(p).add("history:Loot");
		set(p, "historyJourney", journey(
			task("Gargoyles", toMs / 1000.0, false),
			task("Nechryael", (toMs - 1_000) / 1000.0, false),
			task("Bloodvelds", fromMs / 1000.0, false),
			task("Dust devils", (fromMs - 1_000) / 1000.0, false)));
		List<String> all = labels(history(p));
		assertEquals(all.toString(), "+2",
			beside(headline(all), "Slayer tasks completed"));
		assertEquals(all.toString(), "+2", beside(card(all), "Collection log slots"));
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
		assertEquals("+2", beside(loot(s), "Collection log slots"));

		// a feed that begins inside the week cannot say what it missed: the
		// spine's delta stands
		s.feed.remove(3);
		assertEquals("+7", beside(loot(s), "Collection log slots"));

		// reaching back with no slot inside the week: no line, whatever the
		// spine's delta says
		s.feed.clear();
		s.feed.add(entry(now - DAY_MS, "PET", "petName", "Abyssal orphan"));
		s.feed.add(entry(now - 20 * DAY_MS, "COLLECTION", "itemName", "Dragon pickaxe"));
		assertNull(beside(loot(s), "Collection log slots"));
	}

	@Test
	public void deathsCountTheFeedsEntriesWhenTheFeedReachesBackAndTheSpinesOtherwise()
		throws Exception
	{
		long now = System.currentTimeMillis();
		PanelPreviewTest.StubPlugin s = stub(true);
		s.feed.add(entry(now - DAY_MS, "DEATH", "killerName", "Vorkath"));
		s.feed.add(entry(now - DAY_MS - 3_600_000L, "DEATH", "killerName", "Zulrah"));
		s.feed.add(entry(now - 2 * DAY_MS, "COLLECTION", "itemName", "Abyssal head"));
		s.feed.add(entry(now - 2 * DAY_MS - 3_600_000L, "DEATH", "killerName", "Vorkath"));
		s.feed.add(entry(now - 3 * DAY_MS, "DEATH", "killerName", "Vorkath"));
		s.feed.add(entry(now - 4 * DAY_MS, "DEATH", "killerName", "Kraken"));
		s.feed.add(entry(now - 20 * DAY_MS, "DEATH", "killerName", "Vorkath"));
		// the feed reaches back past the week's start: its five deaths inside
		// the week beat the spine's three, and the one before the week is out
		assertEquals("+5", beside(headline(labels(history(panel(s)))), "Deaths"));

		// a feed that begins inside the week cannot say what it missed: the
		// spine's delta stands
		s.feed.remove(6);
		assertEquals("+3", beside(headline(labels(history(panel(s)))), "Deaths"));

		// reaching back with no death inside the week: no line, whatever the
		// spine's delta says
		s.feed.clear();
		s.feed.add(entry(now - DAY_MS, "COLLECTION", "itemName", "Abyssal head"));
		s.feed.add(entry(now - 20 * DAY_MS, "DEATH", "killerName", "Vorkath"));
		assertNull(beside(headline(labels(history(panel(s)))), "Deaths"));
	}

	@Test
	public void theFeedsOtherTypesEachCountTheirOwnEntriesInsideTheWindow() throws Exception
	{
		long now = System.currentTimeMillis();
		PanelPreviewTest.StubPlugin s = stub(true);
		// a different count per type inside the week, one of each type before
		// it, a log slot inside the week and one older still so the feed
		// reaches back
		String[][] types = {
			{"DEATH", "killerName", "Vorkath"},
			{"PET", "petName", "Vorki"},
			{"QUEST", "questName", "Dragon Slayer II"},
			{"DIARY", "area", "Karamja"},
			{"COMBAT_ACHIEVEMENT", "task", "Perfect Zulrah"},
			{"LEVEL", "skill", "Slayer"}};
		int[] inside = {5, 1, 2, 3, 4, 6};
		for (int t = 0; t < types.length; t++)
		{
			for (int i = 0; i < inside[t]; i++)
			{
				s.feed.add(entry(now - DAY_MS - t * 3_600_000L - i * 60_000L,
					types[t][0], types[t][1], types[t][2]));
			}
			s.feed.add(entry(now - 10 * DAY_MS - t * 3_600_000L, types[t][0], types[t][1],
				types[t][2]));
		}
		s.feed.add(entry(now - DAY_MS - 6 * 3_600_000L, "COLLECTION", "itemName", "Abyssal whip"));
		s.feed.add(entry(now - 20 * DAY_MS, "COLLECTION", "itemName", "Dragon pickaxe"));
		List<String> all = labels(history(opened(s)));
		List<String> card = card(all);
		assertEquals(card.toString(), "+5", beside(headline(all), "Deaths"));
		assertEquals(card.toString(), "+1", beside(card, "Pets"));
		assertEquals(card.toString(), "+2", beside(card, "Quests completed"));
		assertEquals(card.toString(), "+3", beside(card, "Diaries completed"));
		assertEquals(card.toString(), "+4", beside(card, "Combat achievements"));
		assertEquals(card.toString(), "+6", beside(card, "Levels gained"));
		assertEquals(card.toString(), "+1", beside(card, "Collection log slots"));
		// each under the group that claims it
		assertTrue(card.toString(), between(card, "EXPERIENCE", "COMBAT").contains("Levels gained"));
		assertTrue(card.toString(), between(card, "LOOT", "SKILLING").contains("Pets"));
		assertTrue(card.toString(),
			between(card, "ACHIEVEMENT", "THE REST").contains("Quests completed"));

		// a type absent from the feed draws no line: the spine never carries
		// these, so there is nothing to fall back on
		s.feed.removeIf(e -> "DIARY".equals(e.get("type").getAsString()));
		card = card(labels(history(opened(s))));
		assertNull(card.toString(), beside(card, "Diaries completed"));
		assertEquals(card.toString(), "+1", beside(card, "Pets"));
		assertEquals(card.toString(), "+6", beside(card, "Levels gained"));

		// a feed that begins inside the week says nothing for any of them
		s.feed.removeIf(e -> e.get("ts").getAsLong() < now - 6 * DAY_MS);
		all = labels(history(opened(s)));
		card = card(all);
		for (String label : new String[]{"Pets", "Quests completed", "Diaries completed",
			"Combat achievements", "Levels gained"})
		{
			assertNull(card.toString(), beside(card, label));
		}
		// while the keys the spine carries fall back to its delta
		assertEquals(card.toString(), "+3", beside(headline(all), "Deaths"));
		assertEquals(card.toString(), "+7", beside(card, "Collection log slots"));
	}

	@Test
	public void anEntryWithNoUsableStampIsSkipped() throws Exception
	{
		long now = System.currentTimeMillis();
		PanelPreviewTest.StubPlugin s = stub(true);
		s.feed.add(entry(now - DAY_MS, "DEATH", "killerName", "Vorkath"));
		// one entry stamped with a word, one with no stamp at all: neither is
		// counted, neither is a milestone, and neither stops the card drawing
		s.feed.add(entry(now - 2 * DAY_MS, "DEATH", "killerName", "Zulrah"));
		s.feed.get(1).addProperty("ts", "soon");
		JsonObject bare = new JsonObject();
		bare.addProperty("type", "PET");
		s.feed.add(bare);
		s.feed.add(entry(now - 20 * DAY_MS, "COLLECTION", "itemName", "Dragon pickaxe"));
		List<String> all = labels(history(opened(s)));
		assertEquals(all.toString(), "+1", beside(headline(all), "Deaths"));
		assertNull(all.toString(), beside(card(all), "Pets"));
		assertTrue(all.toString(), all.contains("MILESTONES · 1"));
	}

	@Test
	public void nothingTrackedDrawsTheExperienceGroupAlone() throws Exception
	{
		List<String> all = labels(history(panel(stub(false))));
		// no counter moved, so no group but Experience has anything to hold,
		// and the xp the period gained is still reachable there
		assertEquals(all.toString(), Arrays.asList("TRACKED PROGRESS", "EXPERIENCE", "1"),
			card(all));
		// the headline and the grid above it still draw
		assertTrue(all.toString(), all.contains("THE PERIOD"));
		assertEquals(all.toString(), "+50k", beside(headline(all), "Experience"));
	}

	@Test
	public void theExperienceGroupRanksThePerSkillGains() throws Exception
	{
		PanelPreviewTest.StubPlugin s =
			new PanelPreviewTest.StubPlugin(Mockito.mock(ItemManager.class));
		HistoryLog.Baseline a = new HistoryLog.Baseline();
		HistoryLog.Baseline b = new HistoryLog.Baseline();
		a.skills.put("attack", 1_000_000L);
		b.skills.put("attack", 1_050_000L);
		a.skills.put("slayer", 2_000_000L);
		b.skills.put("slayer", 2_400_000L);
		a.skills.put("mining", 500_000L);
		b.skills.put("mining", 505_000L);
		LocalDate today = LocalDate.now();
		s.history.put(today.minusDays(10), a);
		s.history.put(today, b);
		ChroniclePanel p = panel(s);
		openFolds(p).add("history:Experience");
		List<String> card = card(labels(history(p)));
		// biggest first, each in the registry's own spelling
		assertEquals(card.toString(), Arrays.asList("TRACKED PROGRESS", "EXPERIENCE", "3",
			"Slayer", "+400k", "Attack", "+50k", "Mining", "+5,000"), card);
	}

	@Test
	public void aPastPeriodDrawsTheLevelsItsClosingLineEndedOn() throws Exception
	{
		// a window closed ten days ago, under a live sheet that stands at 99 in
		// everything: the grid reads the closing line's xp as levels (1.25M is
		// 75), each lit with the period's gain, and the headline reads those
		// levels from end to end, not the sheet's
		LocalDate today = LocalDate.now();
		ChroniclePanel p = panel(spanned(true, true));
		set(p, "histFrom", today.minusDays(15));
		set(p, "histTo", today.minusDays(5));
		List<String> all = labels(history(p));
		List<String> grid = grid(all);
		assertEquals(grid.toString(), "75", grid.get(1));
		assertEquals(grid.toString(), "+250k", grid.get(2));
		assertEquals(grid.toString(), "75", beside(grid, "SAI"));
		assertFalse(grid.toString(), grid.contains("99"));
		assertEquals(all.toString(),
			fmt(73L * skillCount()) + " to " + fmt(75L * skillCount())
				+ " · +" + fmt(2L * skillCount()),
			beside(headline(all), "Total level"));
	}

	@Test
	public void aPeriodReachingTodayDrawsTheLiveSheet() throws Exception
	{
		// the closing line is today's, and the sheet is that state a few
		// minutes fresher: the week, a typed range ending today, and the year
		// under the cursor all read the sheet's 99s and its own total
		LocalDate today = LocalDate.now();
		String standing = fmt(99L * skillCount());
		ChroniclePanel p = panel(spanned(true, true));
		List<String> all = labels(history(p));
		assertEquals(all.toString(), "99", grid(all).get(1));
		assertEquals(all.toString(), standing, standingLevel(all));

		set(p, "histFrom", today.minusDays(15));
		set(p, "histTo", today);
		all = labels(history(p));
		assertEquals(all.toString(), "99", grid(all).get(1));
		assertEquals(all.toString(), standing, standingLevel(all));

		set(p, "histFrom", null);
		set(p, "histTo", null);
		set(p, "histGranularity", "Year");
		all = labels(history(p));
		assertEquals(all.toString(), "99", grid(all).get(1));
		assertEquals(all.toString(), standing, standingLevel(all));
	}

	@Test
	public void aSkillTheClosingLineLacksDrawsTheLevelCarriedToIt() throws Exception
	{
		// the closing line lists everything but Sailing: the closing state
		// carries Sailing's 1,000,000 from the line before it, so the cell
		// draws 73 while the rest stand at 75, and the headline totals all of them
		LocalDate today = LocalDate.now();
		ChroniclePanel p = panel(spanned(false, true));
		set(p, "histFrom", today.minusDays(15));
		set(p, "histTo", today.minusDays(5));
		List<String> all = labels(history(p));
		List<String> grid = grid(all);
		assertEquals(grid.toString(), "73", beside(grid, "SAI"));
		assertEquals(grid.toString(), "75", grid.get(1));
		assertEquals(all.toString(), fmt(75L * (skillCount() - 1) + 73L), standingLevel(all));
	}

	@Test
	public void aYearOpeningOnACompleteSnapshotGainsEverySkillsWholeXp() throws Exception
	{
		// the opening snapshot lists hitpoints and nothing else, and its skills
		// account for the overall it carries, so every other skill stood at
		// zero: attack gains all 13,034,431 rather than nothing
		ChroniclePanel p = panel(firstYear());
		set(p, "histFrom", LocalDate.parse("2022-01-01"));
		set(p, "histTo", LocalDate.parse("2022-12-31"));
		List<String> all = labels(history(p));
		List<String> grid = grid(all);
		assertEquals(grid.toString(), Arrays.asList("ATT", "99", "+13.0M", "HIT", "75", "+1.2M"),
			grid.subList(0, 6));
		assertEquals(all.toString(), "+14.3M", beside(headline(all), "Experience"));
	}

	@Test
	public void aYearOnACompleteRecordDrawsEverySkillAndCountsTheUnrecordedAtOne()
		throws Exception
	{
		// no line ever carried the other 22 skills, and a complete line accounts
		// for every xp there was, so each stood at zero, which is level 1: no
		// cell is blank and the total counts every skill in the game
		ChroniclePanel p = panel(firstYear());
		set(p, "histFrom", LocalDate.parse("2022-01-01"));
		set(p, "histTo", LocalDate.parse("2022-12-31"));
		List<String> all = labels(history(p));
		List<String> grid = grid(all);
		assertFalse(grid.toString(), grid.contains("-"));
		assertEquals(grid.toString(), "1", beside(grid, "MIN"));
		assertEquals(all.toString(), fmt(99L + 75L + skillCount() - 2), standingLevel(all));
	}

	@Test
	public void theHeadlineCountsTheLevelsAndTheNinetyNinesThePeriodAdded() throws Exception
	{
		// the opening state stands at hitpoints 10, the game's own floor, and
		// 23 skills at level 1; the close at 99, 75 and 22 ones: the total
		// level line reads from end to end with the 163 levels beside it, and
		// one 99 was reached. This is the year the site publishes as 33 to
		// 1,206, and the opening is that same 33 in miniature.
		ChroniclePanel p = panel(firstYear());
		set(p, "histFrom", LocalDate.parse("2022-01-01"));
		set(p, "histTo", LocalDate.parse("2022-12-31"));
		List<String> all = labels(history(p));
		long opening = 10L + skillCount() - 1;
		long closing = 99L + 75L + skillCount() - 2;
		assertEquals(all.toString(),
			fmt(opening) + " to " + fmt(closing) + " · +" + fmt(closing - opening),
			beside(headline(all), "Total level"));
		assertEquals(all.toString(), "1", beside(headline(all), "99s reached"));
	}

	@Test
	public void hitpointsBelowTheGamesOwnFloorStillDrawsLevelTen() throws Exception
	{
		// 1,151 xp is level 9 on the bare curve, and no account can stand
		// there: every character is made at 1,154 xp, level 10. A line reading
		// below the floor draws the floor, at both ends of the window, so the
		// opening totals the 33 the site publishes rather than 32
		PanelPreviewTest.StubPlugin s =
			new PanelPreviewTest.StubPlugin(Mockito.mock(ItemManager.class));
		s.history = readSpine(
			"{\"date\":\"2026-01-01\",\"skills\":{\"hitpoints\":1151,\"overall\":1151}}",
			"{\"date\":\"2026-06-01\",\"skills\":{\"attack\":13034431,\"hitpoints\":1151,"
				+ "\"overall\":13035582}}");
		ChroniclePanel p = panel(s);
		set(p, "histFrom", LocalDate.parse("2026-01-01"));
		set(p, "histTo", LocalDate.parse("2026-06-30"));
		List<String> all = labels(history(p));
		assertEquals(all.toString(), "10", beside(grid(all), "HIT"));
		long opening = 10L + skillCount() - 1;
		long closing = 99L + 10L + skillCount() - 2;
		assertEquals(all.toString(),
			fmt(opening) + " to " + fmt(closing) + " · +" + fmt(closing - opening),
			beside(headline(all), "Total level"));
	}

	@Test
	public void aWindowOpeningBeforeTheRecordMeasuresFromItsEarliestLine() throws Exception
	{
		// no line stands at or before the window's start, so the opening is
		// taken at the earliest line on record, the one the note names, and not
		// at the empty state the window's own start day stands at
		ChroniclePanel p = panel(firstYear());
		set(p, "histFrom", LocalDate.parse("2021-06-01"));
		set(p, "histTo", LocalDate.parse("2022-12-31"));
		JPanel view = history(p);
		List<String> all = labels(view);
		assertNotNull(all.toString(), noteHolding(view, "earliest baseline on record"));
		long opening = 10L + skillCount() - 1;
		long closing = 99L + 75L + skillCount() - 2;
		assertEquals(all.toString(),
			fmt(opening) + " to " + fmt(closing) + " · +" + fmt(closing - opening),
			beside(headline(all), "Total level"));
	}

	@Test
	public void theNinetyNinesCountTheOnesReachedNotTheOnesStoodOn() throws Exception
	{
		// the period opens with one 99 already standing and closes with three:
		// two were reached inside it, which is the figure, not the three the
		// account now stands on
		PanelPreviewTest.StubPlugin s =
			new PanelPreviewTest.StubPlugin(Mockito.mock(ItemManager.class));
		s.history = readSpine(
			"{\"date\":\"2026-01-01\",\"skills\":{\"attack\":13034431,\"strength\":5000000,"
				+ "\"defence\":5000000,\"hitpoints\":5000000,\"overall\":28034431}}",
			"{\"date\":\"2026-06-01\",\"skills\":{\"attack\":14000000,\"strength\":13034431,"
				+ "\"defence\":13034431,\"hitpoints\":5000000,\"overall\":45068862}}");
		ChroniclePanel p = panel(s);
		set(p, "histFrom", LocalDate.parse("2026-01-01"));
		set(p, "histTo", LocalDate.parse("2026-06-30"));
		List<String> head = headline(labels(history(p)));
		assertEquals(head.toString(), "2", beside(head, "99s reached"));
	}

	@Test
	public void theLevelsGainedAreMeasuredLineToLineNotAgainstTheLiveSheet()
		throws Exception
	{
		// a window reaching today, whose closing line stands at 76 in every
		// skill under a sheet that stands at 99: the standing figure is the
		// sheet's, the way a live period reads it, but the gain is the three
		// levels a skill moved between the two lines, the same pair the
		// experience beside it was measured between. The sheet stands past
		// that close, so the opening is not named beside it: an opening and a
		// gain that do not reach the standing figure would read as broken
		LocalDate today = LocalDate.now();
		ChroniclePanel p = panel(spanned(true, true));
		set(p, "histFrom", today.minusDays(15));
		set(p, "histTo", today);
		List<String> all = labels(history(p));
		assertEquals(all.toString(),
			fmt(99L * skillCount()) + " · +" + fmt(3L * skillCount()),
			beside(headline(all), "Total level"));
		// and the sheet's own 99s are nobody's gain: the closing line has none
		assertNull(all.toString(), beside(headline(all), "99s reached"));
	}

	@Test
	public void aClosedPeriodNamesItsOpeningBesideItsClose() throws Exception
	{
		// nothing stands past the closing line on a period that ended, so the
		// three figures close on each other: the opening, the standing, and the
		// distance between them
		LocalDate today = LocalDate.now();
		ChroniclePanel p = panel(spanned(true, true));
		set(p, "histFrom", today.minusDays(15));
		set(p, "histTo", today.minusDays(5));
		List<String> all = labels(history(p));
		assertEquals(all.toString(),
			fmt(73L * skillCount()) + " to " + fmt(75L * skillCount())
				+ " · +" + fmt(2L * skillCount()),
			beside(headline(all), "Total level"));
	}

	@Test
	public void theCountersMeasureBetweenTheStatesTheWindowStoodAt() throws Exception
	{
		// counters are cumulative, so a line that stops carrying one leaves it
		// standing: the opening measures drops from the 120 carried to it, not
		// from the 100 that is the earliest on record, and the close measures
		// kills at the 40 it carries though its own line says nothing of them
		PanelPreviewTest.StubPlugin s =
			new PanelPreviewTest.StubPlugin(Mockito.mock(ItemManager.class));
		s.history = readSpine(
			"{\"date\":\"2026-01-01\",\"counters\":{\"dropsReceived\":100,\"kills\":5}}",
			"{\"date\":\"2026-02-01\",\"counters\":{\"dropsReceived\":120,\"kills\":10}}",
			"{\"date\":\"2026-03-01\",\"counters\":{\"kills\":10}}",
			"{\"date\":\"2026-03-15\",\"counters\":{\"kills\":40}}",
			"{\"date\":\"2026-04-01\",\"counters\":{\"dropsReceived\":150}}");
		ChroniclePanel p = panel(s);
		set(p, "histFrom", LocalDate.parse("2026-03-02"));
		set(p, "histTo", LocalDate.parse("2026-04-30"));
		List<String> head = headline(labels(history(p)));
		assertEquals(head.toString(), "+30", beside(head, "Drops received"));
		assertEquals(head.toString(), "+30", beside(head, "Kills"));
	}

	@Test
	public void aPeriodOpeningOnAPartialLineMeasuresFromTheStateItStoodAt() throws Exception
	{
		// the opening line carries attack alone, and mining stands at the
		// 1,500,000 the line before it closed on, not at the 1,000,000 that is
		// the earliest mining on record
		PanelPreviewTest.StubPlugin s =
			new PanelPreviewTest.StubPlugin(Mockito.mock(ItemManager.class));
		s.history = readSpine(
			"{\"date\":\"2026-01-01\",\"skills\":{\"mining\":1000000,\"attack\":1000000,"
				+ "\"overall\":2000000}}",
			"{\"date\":\"2026-02-01\",\"skills\":{\"mining\":1500000,\"attack\":1000000,"
				+ "\"overall\":2500000}}",
			"{\"date\":\"2026-03-01\",\"skills\":{\"attack\":1100000}}",
			"{\"date\":\"2026-04-01\",\"skills\":{\"attack\":1200000,\"mining\":1600000}}");
		ChroniclePanel p = panel(s);
		set(p, "histFrom", LocalDate.parse("2026-03-02"));
		set(p, "histTo", LocalDate.parse("2026-04-30"));
		List<String> grid = grid(labels(history(p)));
		assertEquals(grid.toString(), "+100k", grid.get(grid.indexOf("MIN") + 2));
		assertEquals(grid.toString(), "+100k", grid.get(grid.indexOf("ATT") + 2));
	}

	@Test
	public void aPeriodWhoseEndsDrawDifferentSkillsCountsNoLevelsAndNoNines() throws Exception
	{
		// the opening line is partial and no complete line predates it, so it
		// can speak for attack alone while the close speaks for every skill:
		// the difference would read 22 silences as levels gained and the 99 the
		// close carries as one the period reached, so the total level line
		// stands alone with no opening beside it and the 99s say nothing
		PanelPreviewTest.StubPlugin s =
			new PanelPreviewTest.StubPlugin(Mockito.mock(ItemManager.class));
		s.history = readSpine(
			"{\"date\":\"2026-05-01\",\"skills\":{\"attack\":1000000}}",
			"{\"date\":\"2026-06-01\",\"skills\":{\"attack\":13034431,\"hitpoints\":1300000,"
				+ "\"overall\":14334431}}");
		ChroniclePanel p = panel(s);
		set(p, "histFrom", LocalDate.parse("2026-05-02"));
		set(p, "histTo", LocalDate.parse("2026-06-30"));
		List<String> all = labels(history(p));
		String total = beside(headline(all), "Total level");
		assertFalse(all.toString(), total.contains(" to "));
		assertFalse(all.toString(), total.contains("+"));
		assertFalse(all.toString(), all.contains("99s reached"));
		assertEquals(all.toString(), "99", grid(all).get(1));
		assertTrue(all.toString(), all.contains("+12.0M"));
	}

	@Test
	public void aPeriodThatAddsNoLevelDrawsNeitherFigure() throws Exception
	{
		// the levels and the 99s are what the period added, so a window that
		// added none of either says nothing about them
		LocalDate today = LocalDate.now();
		PanelPreviewTest.StubPlugin s = spanned(true, true);
		s.history.get(today.minusDays(10)).skills
			.putAll(s.history.get(today.minusDays(20)).skills);
		ChroniclePanel p = panel(s);
		set(p, "histFrom", today.minusDays(15));
		set(p, "histTo", today.minusDays(5));
		List<String> all = labels(history(p));
		assertEquals(all.toString(), fmt(73L * skillCount()),
			beside(headline(all), "Total level"));
		assertFalse(all.toString(), all.contains("99s reached"));
	}

	@Test
	public void theKillsToggleCarriesACountTheClosingLineOmits() throws Exception
	{
		// kill counts are cumulative: a line that stops carrying one does not
		// undo it, so the count stands where it reached
		LocalDate today = LocalDate.now();
		PanelPreviewTest.StubPlugin s = spanned(true, true);
		s.history.get(today.minusDays(10)).kcs.remove("Zulrah");
		ChroniclePanel p = panel(s);
		set(p, "histBosses", true);
		set(p, "histFrom", today.minusDays(15));
		set(p, "histTo", today.minusDays(5));
		List<String> all = labels(history(p));
		assertEquals(all.toString(), "100", beside(all, "Zulrah"));
	}

	@Test
	public void aSkillTheRecordHadNotBegunToCarryLeavesTheTotalWhole() throws Exception
	{
		// no line up to the close carries Sailing, the way the imported past
		// predates it: its cell draws no level, and the headline still sums the
		// skills the record carried, since a skill the record had not yet begun
		// to carry is no shortfall
		LocalDate today = LocalDate.now();
		PanelPreviewTest.StubPlugin s = spanned(false, true);
		s.history.get(today.minusDays(20)).skills.remove("sailing");
		ChroniclePanel p = panel(s);
		set(p, "histFrom", today.minusDays(15));
		set(p, "histTo", today.minusDays(5));
		List<String> all = labels(history(p));
		List<String> grid = grid(all);
		assertEquals(grid.toString(), "-", beside(grid, "SAI"));
		assertEquals(grid.toString(), "75", grid.get(1));
		assertEquals(all.toString(), fmt(75L * (skillCount() - 1)), standingLevel(all));
	}

	@Test
	public void theKillsToggleStandsAtTheClosingLinesCounts() throws Exception
	{
		// the standing column is the closing line's count, with the period's
		// gain beside it; neither today's line's 130 nor the live ledger's 135
		// is anywhere on a past window
		LocalDate today = LocalDate.now();
		ChroniclePanel p = panel(spanned(true, true));
		set(p, "histBosses", true);
		set(p, "histFrom", today.minusDays(15));
		set(p, "histTo", today.minusDays(5));
		List<String> all = labels(history(p));
		assertEquals(all.toString(), "108  +8", beside(all, "Zulrah"));
		for (String label : all)
		{
			assertFalse(label, label.startsWith("130") || label.startsWith("135"));
		}

		// the week reaching today reads the ledger, fresher than today's line:
		// its 135 stands, not the line's 130, beside the gain measured between
		// the lines
		set(p, "histFrom", null);
		set(p, "histTo", null);
		all = labels(history(p));
		assertEquals(all.toString(), "135  +22", beside(all, "Zulrah"));
	}

	@Test
	public void theKillsToggleStandsTheLedgersOwnSourcesAtTheClosingLine() throws Exception
	{
		// a source the collection log has no page for sits under Everything
		// else counted, and on a past window it stands at the closing line's
		// count like any other row, not the ledger's: the ledger only sorts the
		// name there. The week reaching today reads the ledger.
		LocalDate today = LocalDate.now();
		ChroniclePanel p = panel(spanned(true, true));
		set(p, "histBosses", true);
		set(p, "histFrom", today.minusDays(15));
		set(p, "histTo", today.minusDays(5));
		List<String> all = labels(history(p));
		int rest = all.indexOf("EVERYTHING ELSE COUNTED");
		assertTrue(all.toString(), rest > all.indexOf("BOSSES AND ACTIVITIES"));
		assertEquals(all.toString(), "Nechryael", all.get(rest + 1));
		assertEquals(all.toString(), "600  +20", all.get(rest + 2));
		for (String label : all)
		{
			assertFalse(label, label.startsWith("630"));
		}

		set(p, "histFrom", null);
		set(p, "histTo", null);
		all = labels(history(p));
		rest = all.indexOf("EVERYTHING ELSE COUNTED");
		assertEquals(all.toString(), "Nechryael", all.get(rest + 1));
		assertEquals(all.toString(), "630  +30", all.get(rest + 2));
	}

	@Test
	public void aClosingLineBeforeKillCountsSaysSoInsteadOfTodaysCounts() throws Exception
	{
		// the window closed before the spine carried kill counts: a note names
		// the first line that does, and today's ledger stays off the past
		LocalDate today = LocalDate.now();
		ChroniclePanel p = panel(spanned(true, false));
		set(p, "histBosses", true);
		set(p, "histFrom", today.minusDays(15));
		set(p, "histTo", today.minusDays(5));
		List<String> all = labels(history(p));
		String joined = String.join(" ", all);
		assertTrue(joined, joined.contains("Kill counts were not on the record when this "
			+ "period closed. They begin on " + today.format(FULL) + "."));
		assertFalse(all.toString(), all.contains("Zulrah"));
		assertFalse(all.toString(), all.contains("BOSSES AND ACTIVITIES"));
		assertFalse(joined, joined.contains("No kill counts recorded yet"));
	}

	@Test
	public void theTabSaysWhatTheFiguresAreDatedFrom() throws Exception
	{
		LocalDate today = LocalDate.now();
		// an imported line opens the period with no counters at all, the
		// trackers join later and the journal-derived loot totals later still:
		// both dates, one line, under the headline and above the grid
		ChroniclePanel p = panel(staged(true));
		set(p, "histFrom", today.minusDays(40));
		set(p, "histTo", today);
		JPanel view = history(p);
		assertEquals("Counters since " + today.minusDays(10).format(FULL)
				+ " · loot and kills since " + today.minusDays(3).format(FULL),
			noteHolding(view, "Counters since"));
		List<String> all = labels(view);
		assertTrue(all.toString(),
			all.indexOf("THE PERIOD") < indexStarting(all, "Counters since"));
		assertTrue(all.toString(), indexStarting(all, "Counters since") < all.indexOf("ATT"));

		// the counters go back to the period's start line and only the loot
		// totals came later: one clause, leading the line
		ChroniclePanel q = panel(staged(false));
		set(q, "histFrom", today.minusDays(40));
		set(q, "histTo", today);
		assertEquals("Loot and kills since " + today.minusDays(3).format(FULL),
			noteHolding(history(q), "Loot and kills since"));

		// both go back to the start line: no line at all
		assertNull(noteHolding(history(panel(stub(true))), "Counters since"));
		assertNull(noteHolding(history(panel(stub(true))), "Loot and kills since"));
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
			noteHolding(history(p), "Counters since"));
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
		JPanel view = history(p);
		assertEquals("Counters since " + today.minusDays(10).format(FULL),
			noteHolding(view, "Counters since"));
		assertNull(noteHolding(view, "loot and kills"));
	}
	@Test
	public void openingTheTabAfreshDrawsEveryListFromItsTop() throws Exception
	{
		long now = System.currentTimeMillis();
		PanelPreviewTest.StubPlugin s = stub(true);
		String[] items = {"Abyssal whip", "Abyssal head", "Abyssal dagger", "Kraken tentacle",
			"Dragon pickaxe", "Occult necklace", "Zamorakian spear"};
		for (int i = 0; i < items.length; i++)
		{
			s.feed.add(entry(now - DAY_MS - i * 60_000L, "COLLECTION", "itemName", items[i]));
		}
		s.feed.add(entry(now - 20 * DAY_MS, "COLLECTION", "itemName", "Rune platebody"));
		ChroniclePanel p = panel(s);
		openFolds(p).add("history:Loot");
		openFolds(p).add("history:list:clogSlotsObtained");
		click(rowNamed(history(p), "Show 1 more"));
		assertFalse(card(labels(history(p))).contains("Show 1 more"));

		// the tab shown from scratch pages every list back to its top, the way
		// it drops a drilled detail and a query
		edt(() ->
		{
			Method m = ChroniclePanel.class.getDeclaredMethod("applyTab",
				Class.forName("chronicle.ChroniclePanel$View"));
			m.setAccessible(true);
			m.invoke(p, get(p, "view"));
		});
		List<String> card = card(labels(history(p)));
		assertTrue(card.toString(), card.contains("Show 1 more"));
		assertFalse(card.toString(), card.contains(items[6]));
	}

	@Test
	public void sessionsAreTimePlayedAndNotMilestones() throws Exception
	{
		// eighteen sessions in a week used to fill the milestone list and push
		// the pets and log slots out of it; the period's own head already says
		// how long was played
		long now = System.currentTimeMillis();
		PanelPreviewTest.StubPlugin s = stub(true);
		s.feed.add(entry(now - 400 * DAY_MS, "COLLECTION", "itemName", "Older than the window"));
		for (int i = 0; i < 8; i++)
		{
			s.feed.add(session(now - DAY_MS - i * 60_000L, 30));
		}
		s.feed.add(entry(now - DAY_MS, "PET", "petName", "Abyssal orphan"));
		JPanel view = history(panel(s));
		List<String> all = labels(view);
		assertTrue(all.toString(), all.contains("Time played"));
		assertTrue(all.toString(), all.contains("Sessions"));
		assertFalse(all.toString(), all.stream().anyMatch(l -> l.startsWith("Session:")));
		assertTrue(all.toString(), all.stream().anyMatch(l -> l.contains("Abyssal orphan")));
	}

	@Test
	public void theMilestoneListPagesLikeEveryOtherList() throws Exception
	{
		// a year's worth of milestones used to stop dead at six with no way on
		long now = System.currentTimeMillis();
		PanelPreviewTest.StubPlugin s = stub(true);
		for (int i = 0; i < 9; i++)
		{
			s.feed.add(entry(now - DAY_MS - i * 60_000L, "COLLECTION", "itemName", "Slot " + i));
		}
		ChroniclePanel p = panel(s);
		JPanel view = history(p);
		List<String> all = labels(view);
		assertTrue(all.toString(), all.contains("Show 3 more"));

		click(rowNamed(view, "Show 3 more"));
		List<String> more = labels(history(p));
		assertEquals(more.toString(), 9, countStarting(more, "Log slot: Slot "));
	}

	@Test
	public void aQuestListNamesTheQuestNotTheChatLine() throws Exception
	{
		// the journal stores the line the game said it in, and the list used to
		// print it whole
		long now = System.currentTimeMillis();
		PanelPreviewTest.StubPlugin s = stub(true);
		s.feed.add(entry(now - 400 * DAY_MS, "COLLECTION", "itemName", "Older than the window"));
		s.feed.add(entry(now - DAY_MS, "QUEST", "questName",
			"You have completed Fallen From Grace!"));
		ChroniclePanel p = panel(s);
		openFolds(p).add("history:Achievement");
		openFolds(p).add("history:list:questsCompleted");
		List<String> card = card(labels(history(p)));
		assertTrue(card.toString(), card.contains("Fallen From Grace"));
		assertFalse(card.toString(),
			card.stream().anyMatch(l -> l.startsWith("You have completed")));
	}

	@Test
	public void aQuestIsNamedNotQuotedFromTheChatBox()
	{
		assertEquals("Fallen From Grace",
			ChroniclePanel.questName("You have completed Fallen From Grace!"));
		assertEquals("Dragon Slayer II", ChroniclePanel.questName("Dragon Slayer II"));
		assertEquals("Recipe for Disaster",
			ChroniclePanel.questName("you have completed Recipe for Disaster."));
	}

	@Test
	public void theSessionsOwnTakeCarriesTheThreeLootFigures() throws Exception
	{
		// the spine holds the journal's lifetime totals and can say nothing about
		// a period until two of its lines carry them; a sitting says what it took
		// on the day it ran
		long now = System.currentTimeMillis();
		PanelPreviewTest.StubPlugin s = stub(true);
		s.feed.add(entry(now - 400 * DAY_MS, "COLLECTION", "itemName", "Older than the window"));
		s.feed.add(session(now - DAY_MS, 60, 400, 2_000_000, 9, 44_000, 5));
		s.feed.add(session(now - 2 * DAY_MS, 30, 286, 1_000_000, 5, 20_000, 3));
		ChroniclePanel p = panel(s);
		openFolds(p).add("history:Loot");
		List<String> card = card(labels(history(p)));
		assertEquals(card.toString(), "+686", beside(card, "Drops received"));
		assertEquals(card.toString(), "+678", beside(card, "Drops taken"));
		assertEquals(card.toString(), "+14 · 64k gp", beside(card, "Left on the floor"));
		assertEquals(card.toString(), "+3.0M gp", beside(card, "Loot value"));
		assertEquals(card.toString(), "+2.9M gp", beside(card, "Loot kept"));
	}

	@Test
	public void aFloorTheRecordCannotDateIsNotClaimedAsKept() throws Exception
	{
		// sessions that predate the left-behind tally know what they received and
		// not what they left: saying every drop was taken would be a claim the
		// record cannot make
		long now = System.currentTimeMillis();
		PanelPreviewTest.StubPlugin s = stub(true);
		s.feed.add(entry(now - 400 * DAY_MS, "COLLECTION", "itemName", "Older than the window"));
		JsonObject older = session(now - DAY_MS, 60);
		older.getAsJsonObject("data").addProperty("drops", 400);
		older.getAsJsonObject("data").addProperty("dropsGp", 2_000_000);
		s.feed.add(older);
		// and the spine has never carried the floor either
		for (HistoryLog.Baseline b : s.history.values())
		{
			b.counters.remove("lootLeftKills");
			b.counters.remove("lootLeftCount");
			b.counters.remove("lootLeftValue");
		}
		ChroniclePanel p = panel(s);
		openFolds(p).add("history:Loot");
		List<String> card = card(labels(history(p)));
		assertEquals(card.toString(), "+400", beside(card, "Drops received"));
		assertEquals(card.toString(), "+2.0M gp", beside(card, "Loot value"));
		assertFalse(card.toString(), card.contains("Drops taken"));
		assertFalse(card.toString(), card.contains("Loot kept"));
		assertFalse(card.toString(), card.contains("Left on the floor"));
	}

	@Test
	public void theSessionRecordCarriesWhatWasLeftBehind() throws Exception
	{
		// the one seam no harness reaches: the session summary is written by the
		// plugin against the live client. Without the floor on it, a period can
		// say what it received and never what it kept.
		File src = new File("src/main/java/chronicle/ChroniclePlugin.java");
		if (!src.isFile())
		{
			return;
		}
		String text = new String(Files.readAllBytes(src.toPath()), StandardCharsets.UTF_8);
		int at = text.indexOf("localStore.record(\"SESSION\"");
		assertTrue("the session summary must be recorded", at > 0);
		String body = text.substring(Math.max(0, at - 1200), at);
		assertTrue("a session says what it received", body.contains("\"drops\""));
		assertTrue("and what it left on the floor", body.contains("\"left\""));
		assertTrue("and what that was worth", body.contains("\"leftGp\""));
		assertTrue("and the kills that left it", body.contains("\"leftKills\""));
	}
}
