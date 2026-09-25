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
import java.util.HashSet;
import java.util.Set;
import javax.swing.JLabel;
import javax.swing.JPanel;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.Mockito;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
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

	// Noon on a day some days back: a sitting is filed by when it began, and one
	// closing "forty days ago" at ten past midnight began on the day before.
	private static long noonDaysAgo(int days)
	{
		return LocalDate.now().minusDays(days).atTime(12, 0)
			.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
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

	/**
	 * The skill grid flattened the way it reads: the three letter code that
	 * stands in for each icon, then the level (or the pair it climbed through),
	 * and nothing else. The tile also carries the skill's name and its xp now,
	 * and every test written against the older two line tile counts positions,
	 * so those two are dropped here rather than in thirty assertions.
	 */
	private static List<String> grid(List<String> all)
	{
		int at = all.indexOf("ATT");
		assertTrue(all.toString(), at >= 0);
		List<String> cells = all.subList(at, all.size());
		java.util.Set<String> codes = new java.util.LinkedHashSet<>();
		for (net.runelite.api.Skill sk : net.runelite.api.Skill.values())
		{
			codes.add(sk.name().substring(0, Math.min(3, sk.name().length())));
		}
		List<String> out = new ArrayList<>();
		for (int i = 0; i < cells.size(); i++)
		{
			if (!codes.contains(cells.get(i)))
			{
				continue;
			}
			out.add(cells.get(i));
			if (i + 1 < cells.size())
			{
				out.add(cells.get(i + 1));
			}
		}
		return out;
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

	private static LocalStore.SlayerTask task(String name, double ts, boolean inProgress)
	{
		return task(name, ts, inProgress, 100);
	}

	// the same, with the kills the segment holds
	private static LocalStore.SlayerTask task(String name, double ts, boolean inProgress,
		long kills)
	{
		return new LocalStore.SlayerTask(name, kills, inProgress ? 150 : 0, 0, ts, 1_000L,
			inProgress);
	}

	private static LocalStore.SlayerJourney journey(LocalStore.SlayerTask... tasks)
	{
		return new LocalStore.SlayerJourney(tasks.length, 100L * tasks.length,
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
		// the card most tests read lives on the Trackers facet; the skill grid and
		// the boards have tests of their own that ask for theirs
		set(p, "histFacet", "Trackers");
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

	// the tab read on its PvM facet, for tests that want the kills, the deaths
	// or the slayer tasks the headline used to carry for every facet at once
	private static JPanel pvm(PanelPreviewTest.StubPlugin stub) throws Exception
	{
		ChroniclePanel p = panel(stub);
		set(p, "histFacet", "PvM");
		return history(p);
	}

	// the Journal tab as the client draws it, for the milestones the history
	// board hands over to it
	private static JPanel journal(ChroniclePanel panel) throws Exception
	{
		final JPanel[] out = new JPanel[1];
		edt(() ->
		{
			Method m = ChroniclePanel.class.getDeclaredMethod("buildJournal");
			m.setAccessible(true);
			out[0] = (JPanel) m.invoke(panel);
		});
		return out[0];
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void setView(ChroniclePanel panel, String name) throws Exception
	{
		Class<?> type = Class.forName("chronicle.ChroniclePanel$View");
		Field f = ChroniclePanel.class.getDeclaredField("view");
		f.setAccessible(true);
		f.set(panel, Enum.valueOf((Class<Enum>) type.asSubclass(Enum.class), name));
	}

	/** The period row on its own: it is above the tabs now, not inside a tab. */
	private static List<String> periodLabels(ChroniclePanel panel) throws Exception
	{
		// On the sitting the row states its scope instead of offering to change
		// it, so a test about the CONTROL has to stand on a board it governs.
		setView(panel, "HISTORY");
		return periodLabelsAsShown(panel);
	}

	/** The row exactly as the board currently on show would draw it. */
	private static List<String> periodLabelsAsShown(ChroniclePanel panel) throws Exception
	{
		final JPanel[] out = new JPanel[1];
		edt(() ->
		{
			Method m = ChroniclePanel.class.getDeclaredMethod("periodRow");
			m.setAccessible(true);
			out[0] = (JPanel) m.invoke(panel);
		});
		return labels(out[0]);
	}

	private static JPanel kills(ChroniclePanel panel) throws Exception
	{
		final JPanel[] out = new JPanel[1];
		edt(() ->
		{
			Method m = ChroniclePanel.class.getDeclaredMethod("buildKills");
			m.setAccessible(true);
			out[0] = (JPanel) m.invoke(panel);
		});
		return out[0];
	}

	private static JPanel history(ChroniclePanel panel) throws Exception
	{
		final JPanel[] out = new JPanel[1];
		edt(() ->
		{
			Method m = ChroniclePanel.class.getDeclaredMethod("buildHistory");
			m.setAccessible(true);
			JPanel body = (JPanel) m.invoke(panel);
			// This helper reads the history board WITH its period row, so the
			// panel has to be standing on that board while the row is built: on
			// the sitting the row states its scope instead of offering to change
			// it. Put back afterwards, or a helper quietly changes which board
			// the test that called it is standing on.
			Object stood = get(panel, "view");
			setView(panel, "HISTORY");
			// The period used to be built inside this tab and handed up as
			// `historyControls`. It governs every tab now, so it is built once
			// above the strip; the tab still has to be read WITH it, because what
			// the boards say is only true of the window the row names.
			Method pr = ChroniclePanel.class.getDeclaredMethod("periodRow");
			pr.setAccessible(true);
			JPanel controls = (JPanel) pr.invoke(panel);
			Field vf = ChroniclePanel.class.getDeclaredField("view");
			vf.setAccessible(true);
			vf.set(panel, stood);
			JPanel whole = new JPanel();
			whole.setLayout(new javax.swing.BoxLayout(whole, javax.swing.BoxLayout.Y_AXIS));
			if (controls != null)
			{
				whole.add(controls);
			}
			whole.add(body);
			out[0] = whole;
		});
		return out[0];
	}

	/**
	 * A boss tile's hover, as a flat list of its title, labels and figures.
	 *
	 * <p>These used to be a card the cell expanded into below its row, read off
	 * the board with labels(kills(p)). The card IS the hover now - the click goes
	 * to the boss's loot instead - so the same assertions read it from here.
	 */
	private static List<String> bossHover(ChroniclePanel panel, String boss)
		throws Exception
	{
		// Over the whole record: a narrowed period draws only the bosses it
		// holds, and these are about what the card SAYS rather than which cells
		// a week puts on the grid.
		set(panel, "histGranularity", "Lifetime");
		JPanel board = kills(panel);
		final String[] tip = {null};
		List<java.awt.Component> flat = new ArrayList<>();
		collectComponents(board, flat);
		for (java.awt.Component c : flat)
		{
			if (!(c instanceof javax.swing.JComponent))
			{
				continue;
			}
			String t = ((javax.swing.JComponent) c).getToolTipText();
			if (t != null && t.contains(">" + boss + "<"))
			{
				tip[0] = t;
				break;
			}
		}
		List<String> out = new ArrayList<>();
		if (tip[0] == null)
		{
			return out;
		}
		java.util.regex.Matcher m = java.util.regex.Pattern
			.compile("<div[^>]*>([^<]*)(?:<span[^>]*>([^<]*)</span>)?")
			.matcher(tip[0]);
		while (m.find())
		{
			String label = m.group(1) == null ? "" : m.group(1).replaceAll(":\\s*$", "").trim();
			if (!label.isEmpty())
			{
				out.add(label);
			}
			if (m.group(2) != null && !m.group(2).trim().isEmpty())
			{
				out.add(m.group(2).trim());
			}
		}
		return out;
	}

	private static void collectComponents(java.awt.Component c,
		List<java.awt.Component> out)
	{
		out.add(c);
		if (c instanceof Container)
		{
			for (java.awt.Component k : ((Container) c).getComponents())
			{
				collectComponents(k, out);
			}
		}
	}

	private static List<String> labels(Container c)
	{
		List<String> out = new ArrayList<>();
		collect(c, out);
		return out;
	}

	private static net.runelite.http.api.item.ItemPrice priced(int id, String name)
	{
		net.runelite.http.api.item.ItemPrice p = new net.runelite.http.api.item.ItemPrice();
		p.setId(id);
		p.setName(name);
		return p;
	}

	private static void collect(Container c, List<String> out)
	{
		for (Component child : c.getComponents())
		{
			// an icon's label says nothing; it is the column a row keeps for it
			if (child instanceof JLabel && ((JLabel) child).getText() != null
				&& !((JLabel) child).getText().isEmpty())
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

	// the group heads a card draws, in the order it draws them
	private static List<String> groupHeads(List<String> card)
	{
		List<String> out = new ArrayList<>();
		for (String line : card)
		{
			for (String g : chronicle.panel.HistoryProgress.GROUPS)
			{
				if (line.equals(g.toUpperCase(Locale.UK)))
				{
					out.add(line);
				}
			}
		}
		return out;
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
				|| label.equals("WHAT IT WAS WORTH") || label.equals("BOSSES")
				|| label.equals("MONSTERS") || label.equals("ACTIVITIES")
				|| label.equals("SKILLING")
				// the bands' own empty state, which is body and not headline
				|| label.startsWith("Nothing counted"))
			{
				end = i;
				break;
			}
		}
		return all.subList(at, end);
	}

	// the figure the total level line closes on, whether or not it names the
	// level it opened from
	// The total level moved out of the headline and onto its own tile under the
	// skill grid, where the owner asked for it. It reads the same either way.
	private static String standingLevel(List<String> all)
	{
		String total = beside(all, "Total level");
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
		List<String> head = headline(labels(pvm(stub(true))));
		// the figures a reader wants first, each a plain labelled row and no
		// sentence: what the period cost, what it added, where it left the
		// sheet, and the counts the groups below break down. The drops carry
		// what they were worth beside them, one line for one pair. Time played,
		// the 99s and the slayer kills are absent: this record holds no
		// session, reached no 99 and never carried the kills.
		assertEquals(Arrays.asList(
			"THE PERIOD",
			"Monsters slain", "+12",
			"Deaths", "+3",
			"Slayer tasks completed", "+5"), head);
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
		// the groups stand open, in the tab's fixed order, each head carrying the
		// number of lines it holds. Travel and Achievement are absent: this period
		// moved nothing either of them claims.
		assertEquals(card.toString(), Arrays.asList(
			"EXPERIENCE", "COMBAT", "LOOT", "SKILLING", "UPKEEP", "THE REST"),
			groupHeads(card));
		assertEquals(card.toString(), "1", beside(card, "EXPERIENCE"));
		assertEquals(card.toString(), "4", beside(card, "COMBAT"));
		assertEquals(card.toString(), "6", beside(card, "LOOT"));
		assertEquals(card.toString(), "1", beside(card, "SKILLING"));
		assertEquals(card.toString(), "3", beside(card, "UPKEEP"));
		assertEquals(card.toString(), "1", beside(card, "THE REST"));
		// and a group that stands open shows what it holds without a click
		assertTrue(card.toString(), card.contains("Fishing"));
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
	public void aClickOnTheHeadShutsTheGroupAndTheFoldInsideIsItsOwn() throws Exception
	{
		ChroniclePanel p = panel(stub(true));
		JPanel view = history(p);
		List<String> open = card(labels(view));
		// a group stands open on its sections, and a section stands shut on its rows
		assertTrue(open.toString(), open.contains("Fishing"));
		assertFalse(open.toString(), open.contains("Shark"));
		assertTrue(openFolds(p).isEmpty());
		int at = open.indexOf("SKILLING");
		assertEquals(open.toString(), Arrays.asList("SKILLING", "1", "Fishing", "+50", "UPKEEP"),
			open.subList(at, at + 5));

		// the group's head shuts it, and shutting one leaves the rest standing
		JPanel group = rowNamed(view, "SKILLING");
		assertNotNull(open.toString(), group);
		assertEquals(java.awt.Cursor.HAND_CURSOR, group.getCursor().getType());
		click(group);
		assertEquals(java.util.Collections.singleton("history:shut:Skilling"), openFolds(p));
		List<String> shut = card(labels(history(p)));
		assertFalse(shut.toString(), shut.contains("Fishing"));
		assertTrue(shut.toString(), shut.contains("SKILLING"));
		assertTrue(shut.toString(), shut.contains("COMBAT"));

		// and the same click opens it again
		click(rowNamed(history(p), "SKILLING"));
		assertTrue(openFolds(p).isEmpty());
		view = history(p);

		// the section's head opens it to its rows and the leftover
		click(rowNamed(view, "Fishing"));
		assertEquals(java.util.Collections.singleton(FOLD), openFolds(p));
		open = card(labels(history(p)));
		at = open.indexOf("Fishing");
		assertEquals(open.toString(),
			Arrays.asList("Fishing", "+50", "Shark", "+30", "Other", "+20", "UPKEEP"),
			open.subList(at, at + 7));

		// and the same click shuts it again
		click(rowNamed(history(p), "Fishing"));
		assertTrue(openFolds(p).toString(), openFolds(p).isEmpty());
	}

	@Test
	public void everyHeadReadsTheSameAndOneFoldsStateIsItsOwn() throws Exception
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

		// a heading is structure, not a figure the reader earned, so it reads the
		// same open or shut and the rows beneath are what say which it is
		JLabel openName = (JLabel) ((BorderLayout) rowNamed(view, "Food").getLayout())
			.getLayoutComponent(BorderLayout.CENTER);
		JLabel shutName = (JLabel) ((BorderLayout) rowNamed(view, "Potions").getLayout())
			.getLayoutComponent(BorderLayout.CENTER);
		assertEquals(openName.getForeground(), shutName.getForeground());
		assertEquals(openName.getFont(), shutName.getFont());
		// and no heading anywhere on the card wears the accent
		for (String head : new String[]{"Food", "Potions", "UPKEEP", "COMBAT"})
		{
			JPanel h = rowNamed(view, head);
			if (h == null)
			{
				continue;
			}
			JLabel name = (JLabel) ((BorderLayout) h.getLayout())
				.getLayoutComponent(BorderLayout.CENTER);
			assertFalse(head + " wears the accent",
				name.getForeground().equals(ColorScheme.BRAND_ORANGE));
		}
	}

	@Test
	public void theStatsTabsFoldsLeaveTheHistoryFoldsAlone() throws Exception
	{
		ChroniclePanel p = panel(stub(true));
		openFolds(p).add("Skilling");
		openFolds(p).add("Skilling:Fishing");
		openFolds(p).add("Living:Food");
		List<String> card = card(labels(history(p)));
		// the section heads stand because their group does, and not one of them
		// has been opened by a key belonging to the other tab
		assertTrue(card.toString(), card.contains("Fishing"));
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
		set(p, "histFacet", "Trackers");
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
	public void thePvmFacetSplitsBossesFromMonsters() throws Exception
	{
		// the facets name themselves across the top, and the boss board is not a
		// facet of its own any more
		PanelPreviewTest.StubPlugin s = stub(true);
		s.kcs.put("Zulrah", 108L);
		s.kcs.put("Nechryael", 622L);
		s.ledgerKcs.put("Nechryael", 622L);
		ChroniclePanel p = panel(s);
		List<String> all = labels(history(p));
		assertFalse(all.toString(), all.contains("Bosses"));

		// under the facet, two bands: the collection log's own boss pages, then
		// everything else the record counted killing
		set(p, "histFacet", "PvM");
		set(p, "histGranularity", "Lifetime");
		all = labels(history(p));
		int bosses = all.indexOf("BOSSES");
		int rest = all.indexOf("MONSTERS");
		assertTrue(all.toString(), bosses > 0 && rest > bosses);
		assertEquals(all.toString(), "Zulrah", all.get(bosses + 1));
		assertEquals(all.toString(), "Nechryael", all.get(rest + 1));
		assertFalse(all.toString(), all.subList(bosses, rest).contains("Nechryael"));
	}

	@Test
	public void aLifetimeTotalLevelStandsWithoutCountingUpFromOne() throws Exception
	{
		// a lifetime encompasses everything that came before, so the tile says
		// where the sheet stands. It used to add "· +2,197", the levels counted
		// up from one, which is arithmetic nobody asked for.
		ChroniclePanel p = panel(stub(true));
		set(p, "histFacet", "Skills");
		set(p, "histGranularity", "Lifetime");
		String lifetime = standingLevel(labels(history(p)));
		assertNotNull(lifetime);
		assertFalse(lifetime, lifetime.contains("·"));
		assertFalse(lifetime, lifetime.contains(" to "));
	}

	@Test
	public void aThievedNameIsNotAKill() throws Exception
	{
		// a pickpocket target counts loots the way a monster counts kills, and
		// nothing in the shape of the row tells them apart: the game fixes who
		// can be robbed, so the panel names them. They belong under Skilling,
		// and never on a kill board.
		PanelPreviewTest.StubPlugin s = stub(true);
		s.kcs.put("Knight", 13_533L);
		s.kcs.put("Nechryael", 622L);
		s.ledgerKcs.put("Knight", 13_533L);
		s.ledgerKcs.put("Nechryael", 622L);
		ChroniclePanel p = panel(s);
		set(p, "histGranularity", "Lifetime");
		set(p, "histFacet", "PvM");
		List<String> pvm = labels(history(p));
		assertTrue(pvm.toString(), pvm.contains("Nechryael"));
		assertFalse(pvm.toString(), pvm.contains("Knight"));

		set(p, "histFacet", "Activities");
		List<String> act = labels(history(p));
		int skilling = act.indexOf("SKILLING");
		assertTrue(act.toString(), skilling > 0);
		assertEquals(act.toString(), "Knight", act.get(skilling + 1));
		assertFalse(act.toString(), act.contains("Nechryael"));
	}

	@Test
	public void aThievedNameTheTableNeverHeardOfNamesItself() throws Exception
	{
		// the record mints a counter per thieving target, so a name the table has
		// never heard of still says what it is and needs no edit here when the
		// game adds one
		PanelPreviewTest.StubPlugin s = stub(true);
		s.kcs.put("Wealthy Trader", 300L);
		s.ledgerKcs.put("Wealthy Trader", 300L);
		s.lifetime.put("wealthyTraderPickpockets", 300L);
		s.lifetime.put("wealthyTraderFailedPickpockets", 40L);
		ChroniclePanel p = panel(s);
		set(p, "histGranularity", "Lifetime");
		set(p, "histFacet", "PvM");
		assertFalse(labels(history(p)).toString(),
			labels(history(p)).contains("Wealthy Trader"));

		set(p, "histFacet", "Activities");
		List<String> act = labels(history(p));
		int skilling = act.indexOf("SKILLING");
		assertTrue(act.toString(), skilling > 0);
		assertTrue(act.toString(), act.subList(skilling, act.size()).contains("Wealthy Trader"));
	}

	@Test
	public void aNameTheRecordCountsAsCaughtIsNotAKill() throws Exception
	{
		// the same rule as the thieved names, widened to the verbs the record
		// counts a skill by. The counter is camel case and plural and the ledger's
		// name is neither, so both sides are reduced to singular letters.
		PanelPreviewTest.StubPlugin s = stub(true);
		s.kcs.put("Moonlight moth", 1_400L);
		s.kcs.put("Nechryael", 622L);
		s.ledgerKcs.put("Moonlight moth", 1_400L);
		s.lifetime.put("moonlightMothsTrapped", 1_400L);
		ChroniclePanel p = panel(s);
		set(p, "histGranularity", "Lifetime");
		set(p, "histFacet", "PvM");
		assertFalse(labels(history(p)).toString(),
			labels(history(p)).contains("Moonlight moth"));

		set(p, "histFacet", "Activities");
		List<String> act = labels(history(p));
		int skilling = act.indexOf("SKILLING");
		assertTrue(act.toString(), skilling > 0);
		assertTrue(act.toString(),
			act.subList(skilling, act.size()).contains("Moonlight moth"));
	}

	@Test
	public void aMinigamePageIsAnActivityAndAMinedPageIsSkilling() throws Exception
	{
		// the collection log's own tabs decide it: its Minigames and Clues are
		// activities, and the rest of its Other tab is ground a skill was
		// trained on
		PanelPreviewTest.StubPlugin s = stub(true);
		s.kcs.put("Guardians of the Rift", 5_218L);
		s.kcs.put("Motherlode Mine", 40L);
		ChroniclePanel p = panel(s);
		set(p, "histGranularity", "Lifetime");
		set(p, "histFacet", "Activities");
		List<String> all = labels(history(p));
		int act = all.indexOf("ACTIVITIES");
		int skilling = all.indexOf("SKILLING");
		assertTrue(all.toString(), act > 0 && skilling > act);
		assertTrue(all.toString(), all.subList(act, skilling).contains("Guardians of the Rift"));
		assertTrue(all.toString(), all.subList(skilling, all.size()).contains("Motherlode Mine"));
	}

	@Test
	public void anActivityWithNoDropsIsKnownByItsLogPage() throws Exception
	{
		// an activity pays in points and reward crates, so the drop ledger has no
		// portrait of it. Its collection log page does: the first slot the item
		// cache can name stands in, and only an exact name is taken, since the
		// cache's own search is a substring scan.
		PanelPreviewTest.StubPlugin s = stub(true);
		// the cache answers the empty string with its whole price list, which is
		// how the panel builds its name index; untradeables are not in it
		Mockito.when(s.items().search("")).thenReturn(Arrays.asList(
			priced(13_258, "Angler hat"), priced(13_259, "Angler top"),
			priced(12_019, "Coal bag")));
		ChroniclePanel p = panel(s);
		Method m = ChroniclePanel.class.getDeclaredMethod("signatureItem", String.class);
		m.setAccessible(true);
		assertEquals(13_258, m.invoke(p, "Fishing Trawler"));
		// a page whose every slot the cache cannot name draws no icon at all
		assertEquals(0, m.invoke(p, "Soul Wars"));
	}

	@Test
	public void aBandFoldsAwayAndSaysWhatItIsHolding() throws Exception
	{
		// every band on the tab folds, and shut it carries its own count
		PanelPreviewTest.StubPlugin s = stub(true);
		s.kcs.put("Zulrah", 108L);
		s.kcs.put("Vorkath", 54L);
		ChroniclePanel p = panel(s);
		set(p, "histGranularity", "Lifetime");
		set(p, "histFacet", "PvM");
		List<String> open = labels(history(p));
		assertTrue(open.toString(), open.contains("Zulrah"));
		assertTrue(open.toString(), open.contains("Vorkath"));

		click(rowNamed(history(p), "BOSSES"));
		List<String> shut = labels(history(p));
		assertFalse(shut.toString(), shut.contains("Zulrah"));
		assertEquals(shut.toString(), "2", beside(shut, "BOSSES"));
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
		set(p, "histFacet", "PvM");
		List<String> all = labels(history(p));
		assertEquals(all.toString(), "+12", beside(headline(all), "Monsters slain"));
		assertTrue(all.toString(), all.indexOf("THE PERIOD") < all.indexOf("BOSSES"));
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
		set(holder[0], "histFacet", "Trackers");
		set(holder[0], "histGranularity", "Week");
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
		set(p, "histFacet", "Trackers");
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
	}

	@Test
	public void nothingTrackedDrawsTheExperienceGroupAlone() throws Exception
	{
		List<String> all = labels(history(panel(stub(false))));
		// no counter moved, so no group but Experience has anything to hold,
		// and the xp the period gained is still reachable there
		assertEquals(all.toString(), java.util.Collections.singletonList("EXPERIENCE"),
			groupHeads(card(all)));
		assertEquals(all.toString(), "1", beside(card(all), "EXPERIENCE"));
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
		set(p, "histFacet", "Skills");
		set(p, "histFrom", today.minusDays(15));
		set(p, "histTo", today.minusDays(5));
		List<String> all = labels(history(p));
		List<String> grid = grid(all);
		assertEquals(grid.toString(), "73 to 75", grid.get(1));
		assertEquals(grid.toString(), "73 to 75", beside(grid, "SAI"));
		assertFalse(grid.toString(), grid.contains("99"));
		assertEquals(all.toString(),
			fmt(73L * skillCount()) + " to " + fmt(75L * skillCount())
				+ " · +" + fmt(2L * skillCount()),
			beside(all, "Total level"));
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
		set(p, "histFacet", "Skills");
		List<String> all = labels(history(p));
		// the week opens on the line five days back, which stood at 75
		assertEquals(all.toString(), "75 to 99", grid(all).get(1));
		assertEquals(all.toString(), standing, standingLevel(all));

		set(p, "histFrom", today.minusDays(15));
		set(p, "histTo", today);
		all = labels(history(p));
		assertEquals(all.toString(), "73 to 99", grid(all).get(1));
		assertEquals(all.toString(), standing, standingLevel(all));

		set(p, "histFrom", null);
		set(p, "histTo", null);
		set(p, "histGranularity", "Year");
		all = labels(history(p));
		assertEquals(all.toString(), "73 to 99", grid(all).get(1));
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
		set(p, "histFacet", "Skills");
		set(p, "histFrom", today.minusDays(15));
		set(p, "histTo", today.minusDays(5));
		List<String> all = labels(history(p));
		List<String> grid = grid(all);
		assertEquals(grid.toString(), "73", beside(grid, "SAI"));
		assertEquals(grid.toString(), "73 to 75", grid.get(1));
		assertEquals(all.toString(), fmt(75L * (skillCount() - 1) + 73L), standingLevel(all));
	}

	@Test
	public void aYearOpeningOnACompleteSnapshotGainsEverySkillsWholeXp() throws Exception
	{
		// the opening snapshot lists hitpoints and nothing else, and its skills
		// account for the overall it carries, so every other skill stood at
		// zero: attack gains all 13,034,431 rather than nothing
		ChroniclePanel p = panel(firstYear());
		set(p, "histFacet", "Skills");
		set(p, "histFrom", LocalDate.parse("2022-01-01"));
		set(p, "histTo", LocalDate.parse("2022-12-31"));
		List<String> all = labels(history(p));
		List<String> grid = grid(all);
		assertEquals(grid.toString(), Arrays.asList("ATT", "1 to 99", "HIT", "10 to 75"),
			grid.subList(0, 4));
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
		set(p, "histFacet", "Skills");
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
		set(p, "histFacet", "Skills");
		set(p, "histFrom", LocalDate.parse("2022-01-01"));
		set(p, "histTo", LocalDate.parse("2022-12-31"));
		List<String> all = labels(history(p));
		long opening = 10L + skillCount() - 1;
		long closing = 99L + 75L + skillCount() - 2;
		assertEquals(all.toString(),
			fmt(opening) + " to " + fmt(closing) + " · +" + fmt(closing - opening),
			beside(all, "Total level"));
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
		set(p, "histFacet", "Skills");
		set(p, "histFrom", LocalDate.parse("2026-01-01"));
		set(p, "histTo", LocalDate.parse("2026-06-30"));
		List<String> all = labels(history(p));
		assertEquals(all.toString(), "10", beside(grid(all), "HIT"));
		long opening = 10L + skillCount() - 1;
		long closing = 99L + 10L + skillCount() - 2;
		assertEquals(all.toString(),
			fmt(opening) + " to " + fmt(closing) + " · +" + fmt(closing - opening),
			beside(all, "Total level"));
	}

	@Test
	public void aWindowOpeningBeforeTheRecordMeasuresFromItsEarliestLine() throws Exception
	{
		// no line stands at or before the window's start, so the opening is
		// taken at the earliest line on record, the one the note names, and not
		// at the empty state the window's own start day stands at
		ChroniclePanel p = panel(firstYear());
		set(p, "histFacet", "Skills");
		set(p, "histFrom", LocalDate.parse("2021-06-01"));
		set(p, "histTo", LocalDate.parse("2022-12-31"));
		JPanel view = history(p);
		List<String> all = labels(view);
		// The caveat is not a row of the board any more: it hangs on the period
		// control, which is what it qualifies, and costs the board nothing until
		// a reader asks for it.
		assertNull("the board should not spend two lines saying this",
			noteHolding(view, "earliest baseline on record"));
		assertTrue("the period still has to say what it measures from: "
				+ get(p, "measuredSince"),
			String.valueOf(get(p, "measuredSince")).contains("earliest baseline on record"));
		long opening = 10L + skillCount() - 1;
		long closing = 99L + 75L + skillCount() - 2;
		assertEquals(all.toString(),
			fmt(opening) + " to " + fmt(closing) + " · +" + fmt(closing - opening),
			beside(all, "Total level"));
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
		set(p, "histFacet", "Skills");
		set(p, "histFrom", today.minusDays(15));
		set(p, "histTo", today);
		List<String> all = labels(history(p));
		assertEquals(all.toString(),
			fmt(99L * skillCount()) + " · +" + fmt(3L * skillCount()),
			beside(all, "Total level"));
		// and the sheet's own 99s are nobody's gain: the closing line has none
		assertEquals(all.toString(), "0", beside(headline(all), "99s reached"));
	}

	@Test
	public void aClosedPeriodNamesItsOpeningBesideItsClose() throws Exception
	{
		// nothing stands past the closing line on a period that ended, so the
		// three figures close on each other: the opening, the standing, and the
		// distance between them
		LocalDate today = LocalDate.now();
		ChroniclePanel p = panel(spanned(true, true));
		set(p, "histFacet", "Skills");
		set(p, "histFrom", today.minusDays(15));
		set(p, "histTo", today.minusDays(5));
		List<String> all = labels(history(p));
		assertEquals(all.toString(),
			fmt(73L * skillCount()) + " to " + fmt(75L * skillCount())
				+ " · +" + fmt(2L * skillCount()),
			beside(all, "Total level"));
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
		set(p, "histFacet", "Trackers");
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
		set(p, "histFacet", "Skills");
		set(p, "histFrom", LocalDate.parse("2026-03-02"));
		set(p, "histTo", LocalDate.parse("2026-04-30"));
		List<String> grid = grid(labels(history(p)));
		// each names where it came from as well as where it ended
		assertEquals(grid.toString(), "74", grid.get(grid.indexOf("ATT") + 1));
		assertEquals(grid.toString(), "77", grid.get(grid.indexOf("MIN") + 1));
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
		set(p, "histFacet", "Skills");
		set(p, "histFrom", LocalDate.parse("2026-05-02"));
		set(p, "histTo", LocalDate.parse("2026-06-30"));
		List<String> all = labels(history(p));
		String total = beside(all, "Total level");
		assertFalse(all.toString(), total.contains(" to "));
		assertFalse(all.toString(), total.contains("+"));
		assertEquals(all.toString(), "0", beside(all, "99s reached"));
		assertEquals(all.toString(), "73 to 99", grid(all).get(1));
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
		set(p, "histFacet", "Skills");
		set(p, "histFrom", today.minusDays(15));
		set(p, "histTo", today.minusDays(5));
		List<String> all = labels(history(p));
		assertEquals(all.toString(), fmt(73L * skillCount()),
			beside(all, "Total level"));
		assertEquals(all.toString(), "0", beside(all, "99s reached"));
	}

	@Test
	public void aCountTheClosingLineOmitsIsNeverALoss() throws Exception
	{
		// kill counts are cumulative: a line that stops carrying one does not
		// undo it. The window reports the gain, and where the closing line
		// dropped the key the state still stands at what it reached, so there
		// is no gain to draw and no negative figure anywhere.
		LocalDate today = LocalDate.now();
		PanelPreviewTest.StubPlugin s = spanned(true, true);
		s.history.get(today.minusDays(10)).kcs.remove("Zulrah");
		ChroniclePanel p = panel(s);
		set(p, "histFacet", "PvM");
		set(p, "histFrom", today.minusDays(15));
		set(p, "histTo", today.minusDays(5));
		List<String> all = labels(history(p));
		assertNull(all.toString(), beside(all, "Zulrah"));
		for (String label : all)
		{
			assertFalse(label, label.startsWith("-"));
		}
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
		set(p, "histFacet", "Skills");
		set(p, "histFrom", today.minusDays(15));
		set(p, "histTo", today.minusDays(5));
		List<String> all = labels(history(p));
		List<String> grid = grid(all);
		assertEquals(grid.toString(), "-", beside(grid, "SAI"));
		assertEquals(grid.toString(), "73 to 75", grid.get(1));
		assertEquals(all.toString(), fmt(75L * (skillCount() - 1)), standingLevel(all));
	}

	@Test
	public void aWindowDrawsWhatItAddedAndALifetimeWhatItStandsAt() throws Exception
	{
		// a window is an account of itself: it draws the eight the period added
		// and nothing else, so neither today's line's 130 nor the live ledger's
		// 135 is anywhere on a past window
		LocalDate today = LocalDate.now();
		ChroniclePanel p = panel(spanned(true, true));
		set(p, "histFacet", "PvM");
		set(p, "histFrom", today.minusDays(15));
		set(p, "histTo", today.minusDays(5));
		List<String> all = labels(history(p));
		assertEquals(all.toString(), "8", beside(all, "Zulrah"));
		for (String label : all)
		{
			assertFalse(label, label.startsWith("130") || label.startsWith("135"));
		}

		// a lifetime encompasses everything before it, so it stands the count
		// itself, read off the ledger, which is fresher than today's line
		set(p, "histFrom", null);
		set(p, "histTo", null);
		set(p, "histGranularity", "Lifetime");
		all = labels(history(p));
		assertEquals(all.toString(), "135", beside(all, "Zulrah"));
	}

	@Test
	public void aLedgerSourceWithNoLogPageSitsUnderMonsters() throws Exception
	{
		// a source the collection log has no page for is still something killed:
		// it sits in the second band, under the log's own boss pages, and on a
		// past window it draws the twenty that window added, not the ledger's
		// standing six hundred and thirty
		LocalDate today = LocalDate.now();
		ChroniclePanel p = panel(spanned(true, true));
		set(p, "histFacet", "PvM");
		set(p, "histFrom", today.minusDays(15));
		set(p, "histTo", today.minusDays(5));
		List<String> all = labels(history(p));
		int rest = all.indexOf("MONSTERS");
		assertTrue(all.toString(), rest > all.indexOf("BOSSES"));
		assertEquals(all.toString(), "Nechryael", all.get(rest + 1));
		assertEquals(all.toString(), "20", all.get(rest + 2));
		for (String label : all)
		{
			assertFalse(label, label.startsWith("630"));
		}

		set(p, "histFrom", null);
		set(p, "histTo", null);
		set(p, "histGranularity", "Lifetime");
		all = labels(history(p));
		rest = all.indexOf("MONSTERS");
		assertEquals(all.toString(), "Nechryael", all.get(rest + 1));
		assertEquals(all.toString(), "630", all.get(rest + 2));
	}

	@Test
	public void aWindowClosedBeforeKillCountsDrawsNoneOfTodaysCounts() throws Exception
	{
		// the window closed before the spine carried kill counts: it says so
		// plainly, draws no band, and today's ledger stays off the past
		LocalDate today = LocalDate.now();
		ChroniclePanel p = panel(spanned(true, false));
		set(p, "histFacet", "PvM");
		set(p, "histFrom", today.minusDays(15));
		set(p, "histTo", today.minusDays(5));
		List<String> all = labels(history(p));
		assertTrue(all.toString(), all.contains("Nothing counted this period."));
		assertFalse(all.toString(), all.contains("Zulrah"));
		assertFalse(all.toString(), all.contains("BOSSES"));
		assertFalse(all.toString(), all.contains("MONSTERS"));
	}

	@Test
	public void theTabSaysWhatTheFiguresAreDatedFrom() throws Exception
	{
		LocalDate today = LocalDate.now();
		// an imported line opens the period with no counters at all, the
		// trackers join later and the journal-derived loot totals later still:
		// both dates, one line, under the headline and above the grid
		ChroniclePanel p = panel(staged(true));
		set(p, "histFacet", "Skills");
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
	public void sessionsAreTimePlayedAndTheMilestonesAreTheJournals() throws Exception
	{
		// the period's own head says how long was played, and the entries
		// themselves are the Journal tab's business: the same feed, read day by
		// day, and drawing it on both tabs was clutter
		long now = System.currentTimeMillis();
		PanelPreviewTest.StubPlugin s = stub(true);
		s.feed.add(entry(now - 400 * DAY_MS, "COLLECTION", "itemName", "Older than the window"));
		// the record has been keeping sittings since well before this window
		s.feed.add(session(noonDaysAgo(40), 15, 0, 0, 0, 0, 0));
		for (int i = 0; i < 8; i++)
		{
			s.feed.add(session(now - DAY_MS - i * 60_000L, 30));
		}
		s.feed.add(entry(now - DAY_MS, "PET", "petName", "Abyssal orphan"));
		ChroniclePanel p = panel(s);
		List<String> all = labels(history(p));
		assertTrue(all.toString(), all.contains("Time played"));
		assertTrue(all.toString(), all.contains("Sessions"));
		assertFalse(all.toString(), all.stream().anyMatch(l -> l.startsWith("Session:")));
		// no milestone list on this tab at all
		assertFalse(all.toString(), all.stream().anyMatch(l -> l.startsWith("MILESTONES")));
		assertFalse(all.toString(), all.stream().anyMatch(l -> l.contains("Abyssal orphan")));

		List<String> journal = labels(journal(p));
		assertTrue(journal.toString(),
			journal.stream().anyMatch(l -> l.contains("Abyssal orphan")));
	}

	@Test
	public void theJournalCarriesTheMilestonesTheTabNoLongerDraws() throws Exception
	{
		// the Progression tab used to end in a milestone list of its own. It is
		// the Journal's account, and the Journal draws every one of them.
		long now = System.currentTimeMillis();
		PanelPreviewTest.StubPlugin s = stub(true);
		for (int i = 0; i < 9; i++)
		{
			s.feed.add(entry(now - DAY_MS - i * 60_000L, "COLLECTION", "itemName", "Slot " + i));
		}
		ChroniclePanel p = panel(s);
		List<String> all = labels(history(p));
		assertFalse(all.toString(), all.stream().anyMatch(l -> l.startsWith("MILESTONES")));
		assertEquals(all.toString(), 0, countStarting(all, "Log slot: Slot "));

		List<String> journal = labels(journal(p));
		assertEquals(journal.toString(), 9, countStarting(journal, "Log slot: Slot "));
	}

	@Test
	public void aQuestListNamesTheQuestNotTheChatLine() throws Exception
	{
		// the journal stores the line the game said it in, and the list used to
		// print it whole
		long now = System.currentTimeMillis();
		PanelPreviewTest.StubPlugin s = stub(true);
		s.feed.add(entry(now - 400 * DAY_MS, "COLLECTION", "itemName", "Older than the window"));
		// the record has been keeping sittings since well before this window
		s.feed.add(session(noonDaysAgo(40), 15, 0, 0, 0, 0, 0));
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
		// the record has been keeping sittings since well before this window
		s.feed.add(session(noonDaysAgo(40), 15, 0, 0, 0, 0, 0));
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
		// the record has been keeping sittings since well before this window
		s.feed.add(session(noonDaysAgo(40), 15, 0, 0, 0, 0, 0));
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
	public void theFloorAndTheTakeAreReadFromTheSameSittings() throws Exception
	{
		// the spine carries the record's lifetime floor and the sittings carry
		// a part of the period; reading the take off one and the floor off the
		// other sets a part against a whole, and the subtraction then reports
		// more drops picked up than the period ever received
		long now = System.currentTimeMillis();
		PanelPreviewTest.StubPlugin s = stub(true);
		s.feed.add(entry(now - 400 * DAY_MS, "COLLECTION", "itemName", "Older than the window"));
		// the record has been keeping sittings since well before this window
		s.feed.add(session(noonDaysAgo(40), 15, 0, 0, 0, 0, 0));
		JsonObject sat = session(now - DAY_MS, 60);
		sat.getAsJsonObject("data").addProperty("drops", 400);
		sat.getAsJsonObject("data").addProperty("dropsGp", 2_000_000);
		s.feed.add(sat);
		ChroniclePanel p = panel(s);
		openFolds(p).add("history:Loot");
		List<String> card = card(labels(history(p)));
		assertEquals(card.toString(), "+400", beside(card, "Drops received"));
		assertEquals(card.toString(), "+2.0M gp", beside(card, "Loot value"));
		// the spine's own floor stood at three stacks over nine kills; none of
		// it is this sitting's, so none of it is drawn
		assertFalse(card.toString(), card.contains("Left on the floor"));
		assertFalse(card.toString(), card.contains("Drops taken"));
		assertFalse(card.toString(), card.contains("Loot kept"));
	}

	@Test
	public void aFloorOnlySomeSittingsCountedIsNotThePeriodsFloor() throws Exception
	{
		// one sitting counted what it left and the other never did: taking the
		// two together reads the older one as a sitting that left nothing, and
		// the period would be told it picked up everything that one received
		long now = System.currentTimeMillis();
		PanelPreviewTest.StubPlugin s = stub(true);
		s.feed.add(entry(now - 400 * DAY_MS, "COLLECTION", "itemName", "Older than the window"));
		// the record has been keeping sittings since well before this window
		s.feed.add(session(noonDaysAgo(40), 15, 0, 0, 0, 0, 0));
		s.feed.add(session(now - DAY_MS, 60, 400, 2_000_000, 9, 44_000, 5));
		JsonObject older = session(now - 2 * DAY_MS, 30);
		older.getAsJsonObject("data").addProperty("drops", 286);
		older.getAsJsonObject("data").addProperty("dropsGp", 1_000_000);
		s.feed.add(older);
		ChroniclePanel p = panel(s);
		openFolds(p).add("history:Loot");
		List<String> card = card(labels(history(p)));
		assertEquals(card.toString(), "+686", beside(card, "Drops received"));
		assertEquals(card.toString(), "+3.0M gp", beside(card, "Loot value"));
		assertFalse(card.toString(), card.contains("Drops taken"));
		assertFalse(card.toString(), card.contains("Loot kept"));
	}

	@Test
	public void aPeriodWhoseSittingsBeginAfterItDoesSaysSo() throws Exception
	{
		// the year is the window and the record holds a fortnight of sittings:
		// the loot figures are theirs, so the line names the day they start and
		// not the day the spine began carrying the journal's own totals
		long now = System.currentTimeMillis();
		LocalDate today = LocalDate.now();
		PanelPreviewTest.StubPlugin s = stub(true);
		s.feed.add(entry(now - 400 * DAY_MS, "COLLECTION", "itemName", "Older than the window"));
		// the record has been keeping sittings since well before this window
		s.feed.add(session(noonDaysAgo(40), 15, 0, 0, 0, 0, 0));
		s.feed.add(session(now - DAY_MS, 60, 400, 2_000_000, 9, 44_000, 5));
		s.feed.add(session(now - 2 * DAY_MS, 30, 286, 1_000_000, 5, 20_000, 3));
		ChroniclePanel p = panel(s);
		set(p, "histGranularity", "Year");
		assertEquals("Loot since " + today.minusDays(40).format(FULL),
			noteHolding(history(p), "Loot since"));
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
		assertTrue("the session summary must be recorded",
			text.contains("localStore.record(\"SESSION\""));
		// Read the builder rather than the characters before the call: the two
		// were next to each other until the sitting in progress needed the same
		// figures without writing them down, and a test that scanned backwards
		// from the call failed for the shape of the file rather than for its
		// meaning.
		int at = text.indexOf("private JsonObject sessionData(");
		assertTrue("the figures a sitting is summed into must be built in one"
			+ " place, or the live line and the written one diverge", at > 0);
		String body = text.substring(at, Math.min(text.length(), at + 1200));
		assertTrue("a session says how long it ran", body.contains("\"minutes\""));
		assertTrue("a session says what it received", body.contains("\"drops\""));
		assertTrue("and what it left on the floor", body.contains("\"left\""));
		assertTrue("and what that was worth", body.contains("\"leftGp\""));
		assertTrue("and the kills that left it", body.contains("\"leftKills\""));
		// and the sitting in progress is built from that same method
		assertTrue("the live sitting must be the same line it will become",
			text.contains("line.add(\"data\", sessionData("));
	}

	@Test
	public void navigatingAwayFromASearchDoesNotRebuildTheNewPageAgain() throws Exception
	{
		// Every open* clears the search box and rebuilds. Clearing a box that had
		// something in it is a document change, and the 150ms debounce cannot tell
		// navigation from typing: it fires a second full build of the page that
		// was just built, and lands it back at the top. The trackers page is
		// reached by typing "Trackers", so it paid this every single time.
		ChroniclePanel p = panel(stub(true));
		Field sf = ChroniclePanel.class.getDeclaredField("searchField");
		sf.setAccessible(true);
		Field sd = ChroniclePanel.class.getDeclaredField("searchDebounce");
		sd.setAccessible(true);
		Method open = ChroniclePanel.class.getDeclaredMethod("openAllTrackers");
		open.setAccessible(true);

		final boolean[] armed = new boolean[2];
		edt(() ->
		{
			Object box = sf.get(p);
			box.getClass().getMethod("setText", String.class).invoke(box, "trackers");
			// typing must still arm it, or the search stops working
			armed[0] = ((javax.swing.Timer) sd.get(p)).isRunning();
			open.invoke(p);
			armed[1] = ((javax.swing.Timer) sd.get(p)).isRunning();
		});
		assertTrue("typing no longer arms the search", armed[0]);
		assertFalse("navigation armed the search debounce, which will rebuild "
			+ "the page it just opened 150ms later", armed[1]);
	}

	@Test
	public void theHomeTickLeavesADrillOpenedFromItAlone() throws Exception
	{
		// Home refreshes itself every three seconds. A page opened FROM Home keeps
		// Home's view, so an incomplete guard rebuilds that page instead: the whole
		// scroll pane is replaced and the bar re-set under a reader who is in the
		// middle of scrolling it. On the trackers page, which is two hundred rows
		// and four thousand pixels tall, that reads as the scroll lagging.
		ChroniclePanel p = panel(stub(true));
		setView(p, "HOME");
		Method open = ChroniclePanel.class.getDeclaredMethod("openAllTrackers");
		open.setAccessible(true);
		Field d = ChroniclePanel.class.getDeclaredField("display");
		d.setAccessible(true);

		final Object[] seen = new Object[3];
		edt(() ->
		{
			open.invoke(p);
			javax.swing.JPanel display = (javax.swing.JPanel) d.get(p);
			seen[0] = boardIn(display);
			seen[2] = labels(display);
			tick(p);
		});
		// The tick queues its draw rather than running one inline, so the read has
		// to come after that queue has drained or it reads the board from before.
		edt(() ->
		{
		});
		edt(() -> seen[1] = boardIn((javax.swing.JPanel) d.get(p)));
		@SuppressWarnings("unchecked")
		List<String> said = (List<String>) seen[2];
		assertTrue("this is not the trackers page: " + said, said.contains("TRACKERS"));
		assertSame("the home tick rebuilt the page the reader was scrolling",
			seen[0], seen[1]);
	}

	@Test
	public void theHomeTickStillRefreshesHomeItself() throws Exception
	{
		// and the guard must not go so wide that the sitting stops refreshing,
		// which is the whole reason the tick exists
		ChroniclePanel p = panel(stub(true));
		setView(p, "HOME");
		Field d = ChroniclePanel.class.getDeclaredField("display");
		d.setAccessible(true);
		// The board has had its breath, so the redraw floor is not what this is
		// about and cannot decide it on a slow machine.
		Field at = ChroniclePanel.class.getDeclaredField("lastBuildAt");
		at.setAccessible(true);
		final Object[] seen = new Object[2];
		edt(() ->
		{
			at.setLong(p, 0L);
			javax.swing.JPanel display = (javax.swing.JPanel) d.get(p);
			seen[0] = boardIn(display);
			tick(p);
		});
		edt(() ->
		{
		});
		edt(() -> seen[1] = boardIn((javax.swing.JPanel) d.get(p)));
		assertNotSame("the sitting stopped refreshing on its own tick",
			seen[0], seen[1]);
	}

	/**
	 * The BOARD, which is what a rebuild replaces.
	 *
	 * <p>Not display.getComponent(0): the scroll pane is hung once and kept for
	 * the life of the panel, precisely so the viewport holds the reader's place
	 * and the overlay bar does not flash on every push. Its identity is stable by
	 * design now, so probing it would make these tests pass whatever happened.
	 */
	private static java.awt.Component boardIn(javax.swing.JPanel display)
	{
		for (java.awt.Component c : display.getComponents())
		{
			if (c instanceof javax.swing.JScrollPane)
			{
				java.awt.Component view =
					((javax.swing.JScrollPane) c).getViewport().getView();
				return view instanceof java.awt.Container
					&& ((java.awt.Container) view).getComponentCount() > 0
					? ((java.awt.Container) view).getComponent(0) : view;
			}
		}
		return null;
	}

	/** Fire the home ticker by hand, the way three seconds would. */
	private static void tick(ChroniclePanel panel) throws Exception
	{
		Field t = ChroniclePanel.class.getDeclaredField("homeTicker");
		t.setAccessible(true);
		javax.swing.Timer timer = (javax.swing.Timer) t.get(panel);
		for (java.awt.event.ActionListener al : timer.getActionListeners())
		{
			al.actionPerformed(new java.awt.event.ActionEvent(timer, 0, ""));
		}
	}

	@Test
	public void aRefreshFromThePluginLeavesTheReaderWhereTheyWere() throws Exception
	{
		// update() is the record changing under a reader who did not ask to go
		// anywhere: a push landing, the status line moving, the history read
		// arriving. It threw the whole scroll pane away and hung a fresh one,
		// which starts at the top, so on a long page the reader was returned to
		// the first line every push interval.
		ChroniclePanel p = panel(stub(true));
		Field d = ChroniclePanel.class.getDeclaredField("display");
		d.setAccessible(true);
		javax.swing.JPanel display = (javax.swing.JPanel) d.get(p);
		p.setSize(240, 200);
		p.doLayout();
		display.setSize(240, 200);
		display.validate();
		javax.swing.JScrollBar bar =
			((javax.swing.JScrollPane) display.getComponent(0)).getVerticalScrollBar();
		int room = bar.getMaximum() - bar.getVisibleAmount();
		assertTrue("the view must be longer than the panel, room " + room, room > 20);
		bar.setValue(Math.min(120, room));
		assertTrue("the reader must be able to scroll at all", bar.getValue() > 0);

		p.update();
		edt(() ->
		{
		});   // let the queued rebuild run
		display.setSize(240, 200);
		display.validate();
		javax.swing.JScrollBar moved =
			((javax.swing.JScrollPane) display.getComponent(0)).getVerticalScrollBar();
		assertTrue("a refresh from the plugin threw the reader back to the top",
			moved.getValue() > 0);
	}

	@Test
	public void openingAFoldLeavesTheReaderWhereTheyWere() throws Exception
	{
		// every fold click used to hang a fresh scroll pane, which starts at the
		// top: on a view with eight groups to open, reading it meant scrolling
		// back down after every click
		ChroniclePanel p = panel(stub(true));
		Field d = ChroniclePanel.class.getDeclaredField("display");
		d.setAccessible(true);
		javax.swing.JPanel display = (javax.swing.JPanel) d.get(p);
		p.setSize(240, 200);
		p.doLayout();
		display.setSize(240, 200);
		display.validate();
		javax.swing.JScrollBar bar =
			((javax.swing.JScrollPane) display.getComponent(0)).getVerticalScrollBar();
		int room = bar.getMaximum() - bar.getVisibleAmount();
		assertTrue("the view must be longer than the panel, room " + room, room > 20);
		bar.setValue(Math.min(120, room));
		int target = bar.getValue();   // as far as this pane will go
		assertTrue("the reader must be able to scroll at all", target > 0);

		// a fold belonging to a view this one is not showing: the content is the
		// same afterwards, so only the reader's place is under test
		Method toggle = ChroniclePanel.class.getDeclaredMethod("toggleFold", String.class);
		toggle.setAccessible(true);
		toggle.invoke(p, "history:Loot");
		display.setSize(240, 200);
		display.validate();

		javax.swing.JScrollBar moved =
			((javax.swing.JScrollPane) display.getComponent(0)).getVerticalScrollBar();
		// as far down as the redrawn view allows, and never back to the first line
		assertTrue("the fold click threw the reader to the top",
			moved.getValue() > 0);
	}

	// ---- the tab a reader opens ------------------------------------------

	@Test
	public void aBandIsCappedAndOneClickBringsTheRest() throws Exception
	{
		// uncapped, a board of two hundred names drew all of them
		PanelPreviewTest.StubPlugin s = stub(true);
		for (int i = 0; i < 26; i++)
		{
			s.kcs.put("Mob " + (char) ('A' + i), 100L + i);
		}
		ChroniclePanel p = panel(s);
		set(p, "histGranularity", "Lifetime");
		set(p, "histFacet", "PvM");
		List<String> all = labels(history(p));
		assertTrue(all.toString(), all.indexOf("MONSTERS") >= 0);
		assertTrue(all.toString(), all.contains("Show 14 more"));
		// twelve stand, ranked, and the other fourteen are held back
		assertTrue(all.toString(), all.contains("Mob Z"));
		assertFalse(all.toString(), all.contains("Mob A"));
		assertFalse(all.toString(), all.contains("Mob N"));

		click(rowNamed(history(p), "Show 14 more"));
		all = labels(history(p));
		assertFalse(all.toString(), all.contains("Show 14 more"));
		assertTrue(all.toString(), all.contains("Mob Z"));
		assertTrue(all.toString(), all.contains("Mob A"));
	}

	@Test
	public void theWindowControlsHangAboveTheBodyAndNotInsideIt() throws Exception
	{
		// they choose the period, so they must not scroll away from the figures
		// they chose
		ChroniclePanel p = panel(stub(true));
		final JPanel[] body = new JPanel[1];
		edt(() ->
		{
			Method m = ChroniclePanel.class.getDeclaredMethod("buildHistory");
			m.setAccessible(true);
			body[0] = (JPanel) m.invoke(p);
		});
		List<String> inBody = labels(body[0]);
		assertFalse("the period row is still inside the scrolling body: " + inBody,
			inBody.contains("Period"));
		// It hangs above the TABS now, not above one tab's body: it governs every
		// board except the sitting, so it cannot belong to any one of them.
		set(p, "histGranularity", "Lifetime");
		List<String> up = periodLabels(p);
		assertFalse("the period row is empty: " + up, up.isEmpty());
		assertTrue("the period row does not name its window: " + up, up.contains("Lifetime"));
	}

	@Test
	public void aSectionWhoseRowsMixUnitsSaysHowManyItHolds() throws Exception
	{
		// "Odds & ends" gathers a tile count beside a shop's takings, so there is
		// no total to print. A heading with nothing beside it says neither what is
		// inside nor that anything is, so it carries the count.
		PanelPreviewTest.StubPlugin s = stub(true);
		boolean opening = true;
		for (HistoryLog.Baseline b : s.history.values())
		{
			b.counters.put("examines", opening ? 10L : 50L);
			b.counters.put("animalsPetted", opening ? 1L : 4L);
			opening = false;
		}
		ChroniclePanel p = panel(s);
		List<String> card = card(labels(history(p)));
		int at = card.indexOf("Odds & ends");
		assertTrue(card.toString(), at >= 0);
		assertEquals(card.toString(), "2", card.get(at + 1));
	}

	@Test
	public void aPeriodTheSittingsCannotReachDrawsNoLootFigure() throws Exception
	{
		// Nothing in the record dates a drop. The ledger keeps lifetime totals per
		// source, the feed never carried a loot entry, and the spine began holding
		// the loot totals partway through the account's life. A fortnight of
		// receipts under a heading that says a year is worse than no figure, so
		// none is drawn and the note says the day loot can be counted from.
		long now = System.currentTimeMillis();
		LocalDate today = LocalDate.now();
		PanelPreviewTest.StubPlugin s = stub(true);
		for (HistoryLog.Baseline b : s.history.values())
		{
			b.counters.remove("dropsReceived");
			b.counters.remove("lootValue");
		}
		s.feed.add(entry(now - 400 * DAY_MS, "COLLECTION", "itemName", "Older than the window"));
		// the record has been keeping sittings since well before this window
		s.feed.add(session(noonDaysAgo(40), 15, 0, 0, 0, 0, 0));
		s.feed.add(session(now - DAY_MS, 60, 400, 2_000_000, 9, 44_000, 5));
		ChroniclePanel p = panel(s);
		set(p, "histGranularity", "Year");
		List<String> card = card(labels(history(p)));
		assertFalse(card.toString(), card.contains("Drops received"));
		assertFalse(card.toString(), card.contains("Loot value"));
		assertEquals("Loot since " + today.minusDays(40).format(FULL),
			noteHolding(history(p), "Loot since"));
	}

	@Test
	public void aPeriodTheSittingsDoReachCarriesTheirTake() throws Exception
	{
		// the same record, read over a window the sittings cover end to end
		long now = System.currentTimeMillis();
		PanelPreviewTest.StubPlugin s = stub(true);
		for (HistoryLog.Baseline b : s.history.values())
		{
			b.counters.remove("dropsReceived");
			b.counters.remove("lootValue");
		}
		s.feed.add(entry(now - 400 * DAY_MS, "COLLECTION", "itemName", "Older than the window"));
		// the record has been keeping sittings since well before this window
		s.feed.add(session(noonDaysAgo(40), 15, 0, 0, 0, 0, 0));
		s.feed.add(session(now - DAY_MS, 60, 400, 2_000_000, 9, 44_000, 5));
		ChroniclePanel p = panel(s);
		List<String> card = card(labels(history(p)));
		assertEquals(card.toString(), "+400", beside(card, "Drops received"));
		assertEquals(card.toString(), "+2.0M gp", beside(card, "Loot value"));
	}

	@Test
	public void theDatedRollAnswersThePeriodAndNamesWhatTheLootWas() throws Exception
	{
		// the roll keeps one entry a day holding what was taken and what was
		// left, so it answers a window exactly and can say what the loot was.
		// Where it reaches back it is the source, over any sitting.
		long now = System.currentTimeMillis();
		LocalDate today = LocalDate.now();
		PanelPreviewTest.StubPlugin s = stub(true);
		for (HistoryLog.Baseline b : s.history.values())
		{
			b.counters.remove("dropsReceived");
			b.counters.remove("lootValue");
		}
		s.feed.add(entry(now - 400 * DAY_MS, "COLLECTION", "itemName", "Older than the window"));
		// a sitting says 400 drops; the roll, which reaches back further, says 981
		s.feed.add(session(noonDaysAgo(40), 15, 0, 0, 0, 0, 0));
		s.feed.add(session(now - DAY_MS, 60, 400, 2_000_000, 9, 44_000, 5));
		s.lootRollDay = today.minusDays(300);
		LocalStore.LootWindow w = new LocalStore.LootWindow();
		w.loots = 981;
		w.value = 81_147_381L;
		w.left = 44;
		w.leftValue = 12_500;
		w.leftKills = 30;
		w.items.add(new String[]{"Granite hammer", "1", "10086894"});
		w.items.add(new String[]{"Abyssal whip", "4", "3356000"});
		w.leftItems.add(new String[]{"Belladonna seed", "9", "958"});
		w.sources.add(new String[]{"Nechryael", "622", "3495578"});
		s.lootWindow = w;

		ChroniclePanel p = panel(s);
		set(p, "histGranularity", "Year");
		List<String> card = card(labels(history(p)));
		assertEquals(card.toString(), "+981", beside(card, "Drops received"));
		assertEquals(card.toString(), "+81.1M gp", beside(card, "Loot value"));
		assertEquals(card.toString(), "+44 · 12k gp", beside(card, "Left on the floor"));
		// the sitting's 400 is not what the year was told
		assertFalse(card.toString(), card.contains("+400"));

		// and the loot itself is a click away
		openFolds(p).add("history:list:lootValue");
		List<String> open = card(labels(history(p)));
		assertEquals(open.toString(), "1 · 10.1M gp", beside(open, "Granite hammer"));
		assertEquals(open.toString(), "4 · 3.4M gp", beside(open, "Abyssal whip"));

		openFolds(p).add("history:list:lootLeftCount");
		open = card(labels(history(p)));
		assertEquals(open.toString(), "9 · 958 gp", beside(open, "Belladonna seed"));
	}

	@Test
	public void aRollThatBeginsInsideThePeriodDoesNotAnswerForIt() throws Exception
	{
		// it started keeping days partway through, so it can say nothing about
		// what came before and must not be read as the period's account
		long now = System.currentTimeMillis();
		LocalDate today = LocalDate.now();
		PanelPreviewTest.StubPlugin s = stub(true);
		for (HistoryLog.Baseline b : s.history.values())
		{
			b.counters.remove("dropsReceived");
			b.counters.remove("lootValue");
		}
		s.feed.add(entry(now - 400 * DAY_MS, "COLLECTION", "itemName", "Older than the window"));
		s.lootRollDay = today.minusDays(2);
		LocalStore.LootWindow w = new LocalStore.LootWindow();
		w.loots = 12;
		w.value = 900;
		s.lootWindow = w;
		ChroniclePanel p = panel(s);
		set(p, "histGranularity", "Year");
		List<String> card = card(labels(history(p)));
		assertFalse(card.toString(), card.contains("Drops received"));
		assertEquals("Loot since " + today.minusDays(2).format(FULL),
			noteHolding(history(p), "Loot since"));
	}

	@Test
	public void lifetimeIsAPeriodLikeAnyOtherAndRunsFromTheFirstLine() throws Exception
	{
		// the whole record, from the first line it holds to today, as a fifth
		// choice beside the four windows
		LocalDate today = LocalDate.now();
		PanelPreviewTest.StubPlugin s = stub(true);
		// a line older than any window but Lifetime, so the two can be told apart
		HistoryLog.Baseline first = new HistoryLog.Baseline();
		first.skills.put("attack", 900_000L);
		s.history.put(today.minusDays(90), first);
		ChroniclePanel p = panel(s);
		set(p, "histGranularity", "Lifetime");
		assertTrue(periodLabels(p).toString(), periodLabels(p).contains("Lifetime"));
		List<String> all = labels(history(p));
		// the period's own figures still draw, and they measure from the record's
		// first line rather than from the window a shorter period would open on
		assertTrue(all.toString(), all.contains("THE PERIOD"));
		assertEquals(all.toString(), "+150k", beside(headline(all), "Experience"));

		set(p, "histGranularity", "Week");
		assertFalse(periodLabels(p).toString(), periodLabels(p).isEmpty());
		assertEquals(labels(history(p)).toString(), "+50k",
			beside(headline(labels(history(p))), "Experience"));
	}

	@Test
	public void theArrowsGoWhereLifetimeCannot() throws Exception
	{
		// a control that can do nothing is worse than no control
		ChroniclePanel p = panel(stub(true));
		assertTrue("the week's arrows should step", arrowShows(history(p), "<"));
		assertTrue("the week's arrows should step", arrowShows(history(p), ">"));

		set(p, "histGranularity", "Lifetime");
		assertFalse("lifetime has nowhere to step back to", arrowShows(history(p), "<"));
		assertFalse("lifetime has nowhere to step forward to", arrowShows(history(p), ">"));
	}

	/**
	 * TRAP: the forward arrow is still DRAWN on the present window, so a test
	 * that only asks whether it is on screen passes whatever it does. It is
	 * drawn on purpose - the label between the two would slide if it vanished -
	 * and it is the colour and the cursor that say whether it is live.
	 *
	 * <p>A log has no forward. A reader arrives on the present window and returns
	 * to it every time they pick a period, so this arrow spent most of its life
	 * computing the next granule, finding it after today and clamping back: a
	 * press that redrew the same board, from a control wearing the same accent
	 * as the live one beside it.
	 */
	@Test
	public void theForwardArrowIsInertOnThePresentWindow() throws Exception
	{
		ChroniclePanel p = panel(stub(true));
		set(p, "histGranularity", "Week");
		JLabel fwd = arrow(history(p), ">");
		assertTrue("the forward arrow is gone, and the label will slide", fwd != null);
		assertEquals("the present week offers a step into next week",
			ColorScheme.LIGHT_GRAY_COLOR.darker(), fwd.getForeground());
		assertEquals("and answers the cursor as though it would take it",
			java.awt.Cursor.getDefaultCursor(), fwd.getCursor());
		assertEquals("and would act on a press", 0, fwd.getMouseListeners().length);

		// stepped back, there IS somewhere forward to go, and it says so
		Method step = ChroniclePanel.class.getDeclaredMethod("stepPeriod", int.class);
		step.setAccessible(true);
		edt(() -> step.invoke(p, -1));
		JLabel live = arrow(history(p), ">");
		assertEquals("a week in the past cannot step forward to the present",
			accentOf(p), live.getForeground());
		assertTrue("and it is dead when it should not be",
			live.getMouseListeners().length > 0);
	}

	private static java.awt.Color accentOf(ChroniclePanel p) throws Exception
	{
		Method m = ChroniclePanel.class.getDeclaredMethod("accent");
		m.setAccessible(true);
		final java.awt.Color[] c = new java.awt.Color[1];
		edt(() -> c[0] = (java.awt.Color) m.invoke(p));
		return c[0];
	}

	private static JLabel arrow(Container c, String text)
	{
		for (Component k : c.getComponents())
		{
			if (k instanceof JLabel && text.equals(((JLabel) k).getText()))
			{
				return (JLabel) k;
			}
			if (k instanceof Container)
			{
				JLabel deeper = arrow((Container) k, text);
				if (deeper != null)
				{
					return deeper;
				}
			}
		}
		return null;
	}

	// whether the stepper's arrow is on screen, which is not the same as whether
	// the label exists: a hidden component is still in the tree
	private static boolean arrowShows(Container c, String text)
	{
		for (Component k : c.getComponents())
		{
			if (k instanceof JLabel && text.equals(((JLabel) k).getText()))
			{
				return k.isVisible();
			}
			if (k instanceof Container && arrowShows((Container) k, text))
			{
				return true;
			}
		}
		return false;
	}

	@Test
	public void theListOffersEveryPeriodWidestFirst() throws Exception
	{
		// five pills across a 225px panel clipped the longest word; a list does
		// not, however many periods there come to be. Any two days are the last
		// entry: at Lifetime there is no dateline to click, so the list is the
		// only way to a window of one's own choosing.
		ChroniclePanel p = panel(stub(true));
		history(p);
		java.lang.reflect.Method m = ChroniclePanel.class.getDeclaredMethod("periodMenu");
		m.setAccessible(true);
		javax.swing.JPopupMenu menu = (javax.swing.JPopupMenu) m.invoke(p);
		List<String> offered = new ArrayList<>();
		for (Component k : menu.getComponents())
		{
			if (k instanceof javax.swing.JMenuItem)
			{
				offered.add(((javax.swing.JMenuItem) k).getText());
			}
		}
		assertEquals(Arrays.asList("Lifetime", "Year", "Month", "Week", "Day",
			"Session", "Exact dates"), offered);
	}

	@Test
	public void theRowNamesTheWindowItIsShowing() throws Exception
	{
		// The row names the WINDOW, not the granularity that chose it: "September
		// 2026" tells a reader which month they are looking at and "Month" does
		// not. Lifetime is the one period whose window has no better name.
		ChroniclePanel p = panel(stub(true));
		for (String period : ChroniclePanel.PERIODS)
		{
			set(p, "histGranularity", period);
			List<String> named = periodLabels(p);
			assertFalse("nothing names the period " + period, named.isEmpty());
			for (String t : named)
			{
				assertFalse(period + " drew an empty label", t.trim().isEmpty());
			}
		}
		set(p, "histGranularity", "Lifetime");
		assertTrue(periodLabels(p).toString(), periodLabels(p).contains("Lifetime"));
		// and exact dates name both their ends, since no granularity named them
		LocalDate today = LocalDate.now();
		set(p, "histFrom", today.minusDays(3));
		set(p, "histTo", today);
		List<String> exact = periodLabels(p);
		assertTrue("exact dates are unnamed: " + exact,
			exact.toString().contains("-"));
	}

	@Test
	public void theTabOpensOnTheWholeRecord() throws Exception
	{
		// a window is a narrowing of the record, so the record is where it starts.
		// Read off a fresh panel, since the test helper pins the period itself.
		final ChroniclePanel[] holder = new ChroniclePanel[1];
		PanelPreviewTest.StubPlugin s = stub(true);
		edt(() -> holder[0] = new ChroniclePanel(s));
		Field f = ChroniclePanel.class.getDeclaredField("histGranularity");
		f.setAccessible(true);
		assertEquals("Lifetime", f.get(holder[0]));
	}

	// ---- the four readings of a period -----------------------------------

	@Test
	public void theStripChoosesWhatThePeriodIsRead() throws Exception
	{
		// four facets, each owning its own figures, so a reader after a skill is
		// not scrolling past a boss board to reach it
		ChroniclePanel p = panel(stub(true));
		set(p, "histFacet", "Skills");
		List<String> skills = labels(history(p));
		assertTrue(skills.toString(), skills.contains("ATT"));
		assertFalse(skills.toString(), skills.contains("TRACKED PROGRESS"));

		set(p, "histFacet", "Trackers");
		List<String> trackers = labels(history(p));
		assertTrue(trackers.toString(), trackers.contains("TRACKED PROGRESS"));
		assertFalse(trackers.toString(), trackers.contains("ATT"));

		set(p, "histFacet", "PvM");
		List<String> pvm = labels(history(p));
		assertFalse(pvm.toString(), pvm.contains("ATT"));
		assertFalse(pvm.toString(), pvm.contains("TRACKED PROGRESS"));

		// and the headline stands above all of them
		for (String facet : new String[]{"Skills", "PvM", "Activities", "Trackers"})
		{
			set(p, "histFacet", facet);
			assertTrue(facet, labels(history(p)).contains("THE PERIOD"));
		}
	}

	@Test
	public void aSkillTileSaysWhereItCameFromAndWhatItGained() throws Exception
	{
		// the tile carries the icon, the levels it moved between and the xp.
		// The name is not on it: three across leaves 62px and "Construction"
		// alone wants 64, and the icon is what a reader looks for anyway.
		LocalDate today = LocalDate.now();
		ChroniclePanel p = panel(spanned(false, false));
		set(p, "histFacet", "Skills");
		set(p, "histFrom", today.minusDays(15));
		set(p, "histTo", today.minusDays(5));
		List<String> all = labels(history(p));
		int at = all.indexOf("ATT");
		assertTrue(all.toString(), at >= 0);
		// icon, the pair, the xp. No unit on the figure: it is a skill, so it is
		// experience, and "+250k xp" did not fit the cell - it clipped to
		// "+250k ..." and spent the room on the one word nobody needed.
		assertEquals(all.toString(), Arrays.asList("ATT", "73 to 75", "+250k"),
			all.subList(at, at + 3));
	}

	@Test
	public void theWholeSheetHasATileOfItsOwn() throws Exception
	{
		// with an odd number of skills a twenty fifth cell would sit alone in a
		// half empty row, so the total stands under the grid rather than in it
		LocalDate today = LocalDate.now();
		ChroniclePanel p = panel(spanned(false, false));
		set(p, "histFacet", "Skills");
		set(p, "histFrom", today.minusDays(15));
		set(p, "histTo", today.minusDays(5));
		List<String> all = labels(history(p));
		int at = all.indexOf("Total level");
		assertTrue("no total level tile: " + all, at >= 0);
		// it opens where the sheet opened and says what the period added
		String tile = all.get(at + 1);
		assertTrue(tile, tile.startsWith(fmt(73L * skillCount()) + " to "));
		assertTrue(tile, tile.contains(" · +"));
		// and it sits after the last skill, not among them
		assertTrue(all.toString(), at > all.indexOf("ATT"));
	}

	@Test
	public void activitiesAreTheLogsOwnPagesAndNotTheBosses() throws Exception
	{
		// the site reads these off Jagex's hiscores, which this plugin has never
		// asked for. The collection log is the record's own account of the same
		// ground, so a minigame page counts and a boss page does not.
		PanelPreviewTest.StubPlugin s = stub(true);
		s.kcs.put("Tempoross", 455L);
		s.kcs.put("Zulrah", 108L);
		ChroniclePanel p = panel(s);
		set(p, "histFacet", "Activities");
		List<String> all = labels(history(p));
		assertFalse("a boss is not an activity: " + all, all.contains("Zulrah"));

		set(p, "histFacet", "PvM");
		assertTrue(labels(history(p)).toString(), labels(history(p)).contains("Zulrah"));
	}

	@Test
	public void whatThePeriodWasWorthReadsInOneUnit() throws Exception
	{
		// counts are the boards' business; these five are the money
		ChroniclePanel p = panel(stub(true));
		set(p, "histFacet", "PvM");
		List<String> all = labels(history(p));
		int at = all.indexOf("WHAT IT WAS WORTH");
		assertTrue("no value card: " + all, at >= 0);
		for (String name : new String[]{"Loot received", "Loot taken", "Loot left",
			"Discarded", "Upkeep"})
		{
			String v = beside(all, name);
			assertNotNull(name + " is missing: " + all, v);
			assertTrue(name + " is not a value: " + v, v.endsWith(" gp"));
		}
		// the stub received 2.5M and left 300k of it behind
		assertEquals(all.toString(), "2.5M gp", beside(all, "Loot received"));
		assertEquals(all.toString(), "2.2M gp", beside(all, "Loot taken"));
		assertEquals(all.toString(), "300k gp", beside(all, "Loot left"));
	}

	@Test
	public void everyCountedNameCarriesWhatItPaid() throws Exception
	{
		// a lifetime reads the drop ledger, which is the whole account, and every
		// band ranks by what its names paid rather than by how often they died
		PanelPreviewTest.StubPlugin s = stub(true);
		s.kcs.put("Zulrah", 108L);
		s.kcs.put("Nechryael", 622L);
		s.kcs.put("Man", 4L);
		s.ledgerKcs.put("Nechryael", 622L);
		s.ledgerKcs.put("Man", 4L);
		s.sources = Arrays.asList(
			new LocalStore.SourceRow("Zulrah", 108, 108, 5_000_000L, null, 0, 0, java.util.Collections.emptySet(), 0, 0),
			new LocalStore.SourceRow("Nechryael", 622, 622, 3_400_000L, null, 0, 0, java.util.Collections.emptySet(), 0, 0),
			new LocalStore.SourceRow("Man", 4, 4, 120L, null, 0, 0, java.util.Collections.emptySet(), 0, 0));
		ChroniclePanel p = panel(s);
		set(p, "histFacet", "PvM");
		set(p, "histGranularity", "Lifetime");
		List<String> all = labels(history(p));
		int bosses = all.indexOf("BOSSES");
		assertTrue("no boss band: " + all, bosses >= 0);
		// the count is the figure and the payment is the accent beside it, two
		// labels so they read as two things
		assertEquals(all.toString(), Arrays.asList("Zulrah", "108", "5.0M"),
			all.subList(bosses + 1, bosses + 4));
		int mobs = all.indexOf("MONSTERS");
		assertEquals(all.toString(), Arrays.asList("Nechryael", "622", "3.4M"),
			all.subList(mobs + 1, mobs + 4));
		// the man was robbed, not killed: he is on the other facet entirely
		assertFalse(all.toString(), all.contains("Man"));
	}

	@Test
	public void lifetimeOnTheTrackersFacetIsTheOldStatsTab() throws Exception
	{
		// a window shows what moved in it; a lifetime has no earlier end to
		// measure against, so it shows the totals themselves, which is what the
		// Stats tab carried before it was folded in here
		ChroniclePanel p = panel(stub(true));
		set(p, "histFacet", "Trackers");
		set(p, "histGranularity", "Week");
		List<String> window = labels(history(p));
		assertTrue(window.toString(), window.contains("TRACKED PROGRESS"));
		assertFalse(window.toString(), window.contains("Ledger & Roads"));

		set(p, "histGranularity", "Lifetime");
		List<String> lifetime = labels(history(p));
		// the Stats tab's own family pills, which the progress card never had.
		// They are the TAB's families now: the Ledger holds what a life costs and
		// where it went, PvM's fourth board is Combat, and Skilling is opened from
		// a cell in the grid rather than offered as a pill here.
		assertTrue("no family pills: " + lifetime, lifetime.contains("Ledger & Roads"));
		assertTrue(lifetime.toString(), lifetime.contains("Living"));
		assertFalse("Skilling is reached from the grid now: " + lifetime,
			lifetime.contains("Skilling"));
		assertFalse(lifetime.toString(), lifetime.contains("TRACKED PROGRESS"));
	}

	@Test
	public void aLifetimeIsTheTotalsAndNotADeltaFromTheFirstLine() throws Exception
	{
		// a delta measured from the first line reports nothing for every total
		// that joined the spine later: a board listing 166M of loot once sat
		// under a headline reading zero
		LocalDate today = LocalDate.now();
		PanelPreviewTest.StubPlugin s = stub(true);
		HistoryLog.Baseline first = new HistoryLog.Baseline();
		first.skills.put("attack", 900_000L);
		s.history.put(today.minusDays(90), first);   // carries no counter at all
		ChroniclePanel p = panel(s);
		set(p, "histFacet", "PvM");

		set(p, "histGranularity", "Week");
		assertEquals("the week is a delta", "+12",
			beside(headline(labels(history(p))), "Monsters slain"));

		set(p, "histGranularity", "Lifetime");
		// the closing line stands at 312 kills, which is what a lifetime means
		assertEquals("the lifetime is the total", "+312",
			beside(headline(labels(history(p))), "Monsters slain"));
	}

	@Test
	public void aTabIconIsNeverWorthABlankTab() throws Exception
	{
		// SpriteManager.getSprite asserts it is on the client thread and a panel
		// is built on the event thread, so asking threw an AssertionError. That
		// is an Error, not an exception, and a catch written for RuntimeException
		// let it past: the whole History tab came out blank.
		PanelPreviewTest.StubPlugin s = stub(true);
		s.spritesThrow = true;
		ChroniclePanel p = panel(s);
		set(p, "histFacet", "Skills");
		List<String> all = labels(history(p));
		assertTrue("the tab is blank: " + all, all.contains("THE PERIOD"));
		assertTrue(all.toString(), all.contains("ATT"));
	}

	@Test
	public void everyCraftTheRegistryFilesUnderIsOpenableFromTheGrid() throws Exception
	{
		// Scoping the stats pills to their tab is only safe while the Skilling
		// family has another way in, and that way is a cell in the grid. A craft
		// the registry can file a counter under but no cell can open is an orphan,
		// and orphaning it once already cost a revert.
		java.lang.reflect.Field f = chronicle.panel.StatRegistry.class.getDeclaredField("SKILLS");
		f.setAccessible(true);
		List<?> specs = (List<?>) f.get(null);
		assertFalse("no crafts at all", specs.isEmpty());

		Set<String> openable = new HashSet<>();
		for (net.runelite.api.Skill sk : net.runelite.api.Skill.values())
		{
			openable.add(chronicle.panel.StatRegistry.prettify(sk.name().toLowerCase(Locale.ROOT)));
		}
		List<String> orphans = new ArrayList<>();
		for (Object spec : specs)
		{
			java.lang.reflect.Field nf = spec.getClass().getDeclaredField("name");
			nf.setAccessible(true);
			String craft = (String) nf.get(spec);
			// the registry files by craft and subgroup() hands that straight back,
			// so this is exactly what openSkill would be asked to find
			if (!openable.contains(craft))
			{
				orphans.add(craft);
			}
		}
		assertTrue("crafts no grid cell can open: " + orphans, orphans.isEmpty());

		// and the family only ever claims a key it could name a craft for, so a
		// Skilling counter can never fall into the family's flat top list
		for (String key : new String[]{"logsChopped", "oresMined", "runesCrafted",
			"bonesBuried", "lapsCompleted"})
		{
			if ("Skilling".equals(chronicle.panel.StatRegistry.family(key)))
			{
				assertFalse(key + " files under Skilling with no craft",
					chronicle.panel.StatRegistry.subgroup(key).isEmpty());
			}
		}
	}

	@Test
	public void aSkillCellOpensOnItsOwnTrackers() throws Exception
	{
		// The grid has always carried a tooltip, which is a mouse listener, and a
		// hand cursor that did nothing. Its counters had no other way in once the
		// family pills stopped offering Skilling, so the cell has to open.
		PanelPreviewTest.StubPlugin st = stub(true);
		st.lifetime.put("logsChopped", 95L);
		ChroniclePanel p = panel(st);
		set(p, "histFacet", "Skills");
		set(p, "histGranularity", "Lifetime");
		int clickable = 0;
		for (Container cell : panels(history(p)))
		{
			if (cell.getMouseListeners().length > 0 && labels(cell).size() >= 2)
			{
				clickable++;
			}
		}
		assertTrue("no cell in the grid opens: " + clickable, clickable >= 20);

		// and what it opens on is that skill's own trackers, under its own head
		Method open = ChroniclePanel.class.getDeclaredMethod("openSkill", String.class);
		open.setAccessible(true);
		edt(() -> open.invoke(p, "Woodcutting"));
		final JPanel[] drill = new JPanel[1];
		edt(() ->
		{
			Method m = ChroniclePanel.class.getDeclaredMethod("buildSkillDetail", String.class);
			m.setAccessible(true);
			drill[0] = (JPanel) m.invoke(p, "Woodcutting");
		});
		List<String> said = labels(drill[0]);
		assertTrue("no way back out: " + said, said.contains("< Back"));
		assertTrue(said.toString(), said.contains("WOODCUTTING"));
		assertTrue("the cell asked an xp question: " + said, said.contains("Level"));
		assertTrue("nothing of woodcutting's own: " + said,
			said.contains("Logs chopped"));
	}

	/** Every container under one, for walking a grid of cells. */
	private static List<Container> panels(Container c)
	{
		List<Container> out = new ArrayList<>();
		for (Component k : c.getComponents())
		{
			if (k instanceof Container)
			{
				out.add((Container) k);
				out.addAll(panels((Container) k));
			}
		}
		return out;
	}

	@Test
	public void aWindowWithNoDatedLootShowsNoLoot() throws Exception
	{
		// The ledger's running totals cannot be narrowed, so the board reads the
		// dated roll instead. A range the roll holds nothing for shows nothing:
		// that IS the answer, and filling it with the lifetime would be the same
		// lie the boss counts told.
		PanelPreviewTest.StubPlugin st = stub(true);
		st.lootRollDay = LocalDate.now().minusYears(1);
		st.lootWindow = new LocalStore.LootWindow();
		ChroniclePanel p = panel(st);
		set(p, "histGranularity", "Week");
		List<String> empty = labels(drops(p));
		assertTrue("an empty window said nothing about itself: " + empty,
			empty.toString().contains("Nothing taken inside"));
		assertFalse("a lifetime figure leaked into the window: " + empty,
			empty.toString().contains(" gp"));

		// and where the roll does hold the window, it is the roll that is drawn
		LocalStore.LootWindow held = new LocalStore.LootWindow();
		held.loots = 4;
		held.value = 1_234;
		held.sources.add(new String[]{"Vorkath", "4", "1234"});
		st.lootWindow = held;
		List<String> some = labels(drops(p));
		assertTrue(some.toString(), some.contains("Vorkath"));
		assertTrue(some.toString(), some.contains("4 · 1,234 gp"));

		// a roll that does not reach the window's start says so rather than
		// reporting the part it can see as the whole
		st.lootRollDay = LocalDate.now();
		List<String> short_ = labels(drops(p));
		assertTrue("a partial roll was drawn as the whole: " + short_,
			short_.toString().contains("begins"));
	}

	private static JPanel drops(ChroniclePanel panel) throws Exception
	{
		final JPanel[] out = new JPanel[1];
		edt(() ->
		{
			Method m = ChroniclePanel.class.getDeclaredMethod("buildDrops");
			m.setAccessible(true);
			out[0] = (JPanel) m.invoke(panel);
		});
		return out[0];
	}

	@Test
	public void somethingThatIsNotKilledIsNotCalledAKill() throws Exception
	{
		// The Rift is searched, a casket is opened, a reward cart is emptied.
		// Calling any of it "kills tracked" tells the reader something untrue
		// about what they did.
		PanelPreviewTest.StubPlugin st = stub(true);
		st.sources = Arrays.asList(
			new LocalStore.SourceRow("Guardians of the Rift", 4_955, 4_955,
				39_768_743L, null, 0, 0, java.util.Collections.emptySet(), 0, 0),
			new LocalStore.SourceRow("Vorkath", 156, 143, 81_000_000L, null, 0, 0, java.util.Collections.emptySet(), 0, 0));
		st.bags.put("Guardians of the Rift", Arrays.asList(
			new LocalStore.BagItem(0, "Abyssal pearls", 4_000, 39_768_743L)));

		ChroniclePanel p = panel(st);
		Method bd = ChroniclePanel.class.getDeclaredMethod("buildSourceDetail", String.class);
		bd.setAccessible(true);
		final List<List<String>> seen = new ArrayList<>();
		edt(() ->
		{
			seen.add(labels((JPanel) bd.invoke(p, "Guardians of the Rift")));
			seen.add(labels((JPanel) bd.invoke(p, "Vorkath")));
		});
		List<String> rift = seen.get(0);
		assertFalse("the Rift is not killed: " + rift, rift.contains("Kills"));
		assertTrue(rift.toString(), rift.contains("Times looted"));
		assertFalse("the take is still being taken: " + rift, rift.contains("The take"));
		assertTrue(rift.toString(), rift.contains("Worth"));
		// and a fight still says kills
		assertTrue(seen.get(1).toString(), seen.get(1).contains("Kills"));
	}

	@Test
	public void aFightPaidOutInAContainerStillShowsItsLoot() throws Exception
	{
		// Wintertodt hands its loot over in a cart and Tempoross in a pool, under
		// a source of that name rather than the boss's. Looking only for a source
		// spelled like the boss found none of it, so four and a half million gp
		// sat in the journal under a card saying no loot had reached it.
		PanelPreviewTest.StubPlugin st = stub(true);
		st.sources = Arrays.asList(
			new LocalStore.SourceRow("Reward cart (Wintertodt)", 97, 97, 4_550_382L, null, 0, 0, java.util.Collections.emptySet(), 0, 0),
			new LocalStore.SourceRow("Reward pool (Tempoross)", 114, 114, 2_269_132L, null, 0, 0, java.util.Collections.emptySet(), 0, 0),
			new LocalStore.SourceRow("Casket (Tempoross)", 25, 25, 230_772L, null, 0, 0, java.util.Collections.emptySet(), 0, 0));
		com.google.gson.JsonObject cl = st.clog != null ? st.clog
			: new com.google.gson.JsonObject();
		com.google.gson.JsonObject log = new com.google.gson.JsonObject();
		log.addProperty("Wintertodt", 447);
		log.addProperty("Tempoross", 455);
		cl.add("slayer_kcs", log);
		st.clog = cl;

		ChroniclePanel p = panel(st);
		List<String> todt = bossHover(p, "Wintertodt");
		assertFalse("the cart's loot is in the journal: " + todt,
			String.join(" ", todt).contains("No loot from here has reached"));
		assertTrue("the container is not named as what it is: " + todt,
			todt.contains("Reward cart"));
		assertTrue(todt.toString(), todt.contains("Kills tracked"));
		assertTrue("the kills are not the boss's own: " + todt, todt.contains("447"));

		// and a fight paid out through two of them shows both
		List<String> temp = bossHover(p, "Tempoross");
		assertTrue(temp.toString(), temp.contains("Reward pool"));
		assertTrue(temp.toString(), temp.contains("Casket"));
	}

	@Test
	public void aFightWhoseTakingsAreFiledUnderAnotherNameShowsThem() throws Exception
	{
		// The Gauntlet's payout is filed against the fight that hands it over,
		// not against the Gauntlet. The creatures inside it are NOT the fight's
		// loot: a crystalline bear's shards are the bear's.
		PanelPreviewTest.StubPlugin st = stub(true);
		st.sources = Arrays.asList(
			new LocalStore.SourceRow("Corrupted Hunllef", 3, 3, 95_036L, null, 0, 0, java.util.Collections.emptySet(), 0, 0),
			new LocalStore.SourceRow("Corrupted Rat", 18, 18, 0L, null, 0, 0, java.util.Collections.emptySet(), 0, 0));

		ChroniclePanel p = panel(st);
		List<String> card = bossHover(p, "The Corrupted Gauntlet");
		assertFalse("95k of it is in the ledger: " + card,
			String.join(" ", card).contains("No loot from here has reached"));
		assertTrue("the payout is not shown: " + card, card.contains("Corrupted Hunllef"));
		assertFalse("a filler creature was counted as the fight's loot: " + card,
			card.contains("Corrupted Rat"));

		// and a fight whose payout has genuinely never been looted still says so,
		// in the two words a hover card has room for
		List<String> none = bossHover(p, "The Gauntlet");
		assertTrue(none.toString(), none.contains("none yet"));
	}

	@Test
	public void aBestTimeIsShownAsATime() throws Exception
	{
		// "Personal Best: 3:46" was read by a count expression as 46 kills. It is
		// a time, and it is worth showing as one.
		PanelPreviewTest.StubPlugin st = stub(true);
		com.google.gson.JsonObject cl = st.clog != null ? st.clog
			: new com.google.gson.JsonObject();
		com.google.gson.JsonObject pages = new com.google.gson.JsonObject();
		com.google.gson.JsonObject temp = new com.google.gson.JsonObject();
		temp.addProperty("Personal Best", 226);          // 3:46
		pages.add("Tempoross", temp);
		com.google.gson.JsonObject gaunt = new com.google.gson.JsonObject();
		gaunt.addProperty("Personal Best", 535);         // 8:55
		gaunt.addProperty("Personal Best Corrupted", 791);   // 13:11
		pages.add("The Gauntlet", gaunt);
		cl.add("pb_lines", pages);
		st.clog = cl;

		ChroniclePanel p = panel(st);
		List<String> pool = bossHover(p, "Tempoross");
		assertTrue(pool.toString(), pool.contains("3:46"));

		// a page counting two fights gives each its own, and neither the other's
		List<String> plain = bossHover(p, "The Gauntlet");
		assertTrue(plain.toString(), plain.contains("8:55"));
		assertFalse("the corrupted best is on the plain Gauntlet: " + plain,
			plain.contains("13:11"));

		List<String> corrupt = bossHover(p, "The Corrupted Gauntlet");
		assertTrue(corrupt.toString(), corrupt.contains("13:11"));
		assertFalse("it took the plain Gauntlet's best: " + corrupt,
			corrupt.contains("8:55"));
	}

	@Test
	public void theBossCardNamesTheCountersTheLogPageCarries() throws Exception
	{
		// The page's own counters are not all kill counts, and a number without
		// its label cannot be told apart from one: Wintertodt's line counts
		// rewards claimed, and read as kills it said 1,078 where 447 were killed.
		// So the card names them the way the log does.
		PanelPreviewTest.StubPlugin st = stub(true);
		com.google.gson.JsonObject cl = st.clog != null ? st.clog
			: new com.google.gson.JsonObject();
		com.google.gson.JsonObject lines = new com.google.gson.JsonObject();
		com.google.gson.JsonObject todt = new com.google.gson.JsonObject();
		todt.addProperty("Rewards claimed", 1_078);
		lines.add("Wintertodt", todt);
		com.google.gson.JsonObject gauntlet = new com.google.gson.JsonObject();
		// the page carries a best time and both fights' completions
		gauntlet.addProperty("Personal Best: 8", 55);
		gauntlet.addProperty("Personal Best Corrupted: 13", 11);
		gauntlet.addProperty("Gauntlet completion count", 31);
		gauntlet.addProperty("Corrupted Gauntlet completion count", 1);
		lines.add("The Gauntlet", gauntlet);
		cl.add("kc_lines", lines);
		st.clog = cl;

		ChroniclePanel p = panel(st);
		List<String> todtCard = bossHover(p, "Wintertodt");
		assertTrue("the counter is unnamed: " + todtCard,
			todtCard.contains("Rewards claimed"));
		assertTrue(todtCard.toString(), todtCard.contains("1,078"));

		// A PERSONAL BEST IS A TIME. "Personal Best: 8:55" is read off its last
		// ": number" and comes back as 55 under the label "Personal Best: 8".
		// Capture turns those away now, but a journal already holding one keeps
		// it, because these lines are floor-merged and never removed.
		List<String> card = bossHover(p, "The Gauntlet");
		assertFalse("a best time is being read as a count: " + card,
			card.contains("Personal Best: 8"));
		assertFalse(card.toString(), card.contains("Personal Best Corrupted: 13"));
		// no loot from the Gauntlet ever reaches the journal, but the log counted
		// it: a dash where a number is known reads as untracked
		assertTrue("the count the log holds is not shown: " + card,
			card.contains("Kills tracked") && card.contains("31"));
		// and the line restating it is not printed under it
		assertFalse("the headline is repeated as a line: " + card,
			card.contains("Gauntlet completion count"));
		// the corrupted fight is its own row on the board, so its count is read
		// there rather than twice, once on each
		assertFalse("the corrupted count is on the plain Gauntlet's card: " + card,
			card.contains("Corrupted Gauntlet completion count"));

		List<String> corrupted = bossHover(p, "The Corrupted Gauntlet");
		assertTrue("the corrupted fight lost its own count: " + corrupted,
			corrupted.contains("Kills tracked") && corrupted.contains("1"));
		// and it must not inherit the plain Gauntlet's page counter, which on an
		// older journal is half of a best time: 55 where 1 was completed
		assertFalse("it took the whole page's counter: " + corrupted,
			corrupted.contains("55"));
	}

	@Test
	public void killsTheSpineCannotDateAreCountedFromWhatTheyDropped() throws Exception
	{
		// A species whose count only reached the journal today has no earlier
		// baseline to measure against, so the spine drops it -- and 22 Sarachnis
		// killed this morning drew a dash. The loot roll dates them anyway: it
		// keeps one entry a day per source, so what a kill dropped places it even
		// when the kill count cannot.
		PanelPreviewTest.StubPlugin st = stub(true);
		st.lootRollDay = LocalDate.now().minusDays(2);
		LocalStore.LootWindow held = new LocalStore.LootWindow();
		held.sources.add(new String[]{"Sarachnis", "22", "626995"});
		st.lootWindow = held;
		ChroniclePanel p = panel(st);
		set(p, "histGranularity", "Week");
		List<String> week = labels(kills(p));
		assertTrue("the roll could date these and the board still said nothing: "
			+ week, week.contains("22"));

		// and it says so once, rather than leaving a number that means something
		// its neighbours do not
		assertTrue("the board did not declare the mixed source: " + week,
			week.toString().contains("counted from loot"));

		// a boss the roll has nothing for stays a dash: not a nought, which would
		// claim it counted none
		st.lootWindow = new LocalStore.LootWindow();
		List<String> bare = labels(kills(p));
		assertFalse("a ghost came back: " + bare, bare.contains("22"));
	}

	@Test
	public void theBossBoardCountsTheWindowAndNotTheLifetime() throws Exception
	{
		// The period governs this board too. A boss sheet drawn under "last week"
		// that still says 2,023 Zalcano is the same lie the log page counter told
		// when it said 1,078 Wintertodt: a number answering a question nobody
		// asked, under a heading claiming it answered theirs.
		PanelPreviewTest.StubPlugin st = stub(true);
		com.google.gson.JsonObject cl = st.clog != null ? st.clog
			: new com.google.gson.JsonObject();
		com.google.gson.JsonObject pages = new com.google.gson.JsonObject();
		pages.addProperty("Zalcano", 2_023);
		cl.add("kcs", pages);
		st.clog = cl;
		ChroniclePanel p = panel(st);

		set(p, "histGranularity", "Lifetime");
		List<String> ever = labels(kills(p));
		assertTrue("a lifetime counts the kills themselves: " + ever,
			ever.contains("2,023"));

		set(p, "histGranularity", "Week");
		List<String> week = labels(kills(p));
		assertFalse("the lifetime count survived into a week: " + week,
			week.contains("2,023"));
	}

	@Test
	public void theSittingStatesItsScopeRatherThanLeavingTheRowEmpty() throws Exception
	{
		// The sitting is now and can be nothing else, so the period cannot govern
		// it. Hiding the row there moved every tab under it, and a strip that
		// jumps as you move between tabs reads as the panel misbehaving -- so the
		// row draws at the same height in the same place and says what it is.
		ChroniclePanel p = panel(stub(true));
		setView(p, "HOME");
		List<String> sitting = periodLabelsAsShown(p);
		assertTrue("the sitting does not name its scope: " + sitting,
			sitting.contains("This session"));
		// and it is a statement, not a control: nothing to step it with
		assertFalse(sitting.toString(), sitting.contains("<"));
		assertFalse(sitting.toString(), sitting.contains(">"));

		// every other board still gets the control itself
		setView(p, "KILLS");
		set(p, "histGranularity", "Week");
		List<String> elsewhere = periodLabelsAsShown(p);
		assertFalse("the sitting's label leaked onto another board: " + elsewhere,
			elsewhere.contains("This session"));
		assertTrue("no stepper where the period governs: " + elsewhere,
			elsewhere.contains("<"));
	}

	@Test
	public void trackersOpensOnEveryCounterInOnePlace() throws Exception
	{
		// The stats table files by family and a tab shows one family's half of the
		// sheet, so the whole thing had nowhere to be asked for. The search asks.
		PanelPreviewTest.StubPlugin st = stub(true);
		st.lifetime.put("logsChopped", 95L);
		st.lifetime.put("hitsBlocked", 12L);
		ChroniclePanel p = panel(st);
		// seeded on the lifetime map, so read the lifetime
		set(p, "histGranularity", "Lifetime");
		Method open = ChroniclePanel.class.getDeclaredMethod("openAllTrackers");
		open.setAccessible(true);
		edt(() -> open.invoke(p));

		final JPanel[] board = new JPanel[1];
		edt(() ->
		{
			Method m = ChroniclePanel.class.getDeclaredMethod("buildAllTrackers");
			m.setAccessible(true);
			board[0] = (JPanel) m.invoke(p);
		});
		List<String> said = labels(board[0]);
		assertTrue("no way back out: " + said, said.contains("< Back"));
		assertTrue(said.toString(), said.contains("TRACKERS"));
		// families that no single tab shows together
		assertTrue("skilling is missing: " + said, said.contains("Logs chopped"));
		assertTrue("combat is missing: " + said, said.contains("Hits blocked"));
		// and it says which period it is reading, since it is not under a tab
		assertTrue("the board does not name its scope: " + said,
			said.contains("Reading"));

		// the row above a drill describes what is ON SCREEN, not the tab it was
		// opened from: the trackers page reads the period, so "This session"
		// would be describing the wrong thing
		setView(p, "HOME");
		assertFalse("the sitting's label leaked onto a drill: " + periodLabelsAsShown(p),
			periodLabelsAsShown(p).contains("This session"));
	}



	@Test
	public void aLootPageCanBeTakenAwayAsAPicture() throws Exception
	{
		// A page worth reading is a page worth sharing, and what shares is a
		// picture: the places this goes take images, and text lost the columns
		// that made it readable.
		ChroniclePanel p = panel(stub(true));
		Method bd = ChroniclePanel.class.getDeclaredMethod("buildSourceDetail", String.class);
		bd.setAccessible(true);
		Method strip = ChroniclePanel.class.getDeclaredMethod("stripChrome", JPanel.class);
		strip.setAccessible(true);
		Method pi = ChroniclePanel.class.getDeclaredMethod("copyImage", JPanel.class);
		pi.setAccessible(true);

		final Object[] out = new Object[2];
		edt(() ->
		{
			JPanel page = (JPanel) bd.invoke(p, "Zalcano");
			List<String> before = labels(page);
			out[1] = before.contains("< Back");
			out[0] = pi.invoke(null, strip.invoke(null, page));
		});
		assertTrue("the page had no way back to take off", (Boolean) out[1]);
		assertNotNull("no picture was drawn", out[0]);

		java.awt.Image img = (java.awt.Image) out[0];
		assertTrue("drawn at the sidebar's width, so long names truncate",
			img.getWidth(null) > 225);
		assertTrue("nothing was drawn", img.getHeight(null) > 0);
	}

	@Test
	public void whatGoesOnTheClipboardIsAnActualPng() throws Exception
	{
		// AWT's imageFlavor advertises public.png on a Mac and then hands back
		// TIFF under it, so a chat client asks for a PNG, receives a TIFF, calls
		// it image.png and uploads a file nobody can open. That is not a thing a
		// reader can see in a preview: the only way to hold it is to look at the
		// bytes that leave.
		Method pp = ChroniclePanel.class.getDeclaredMethod("pngPayload",
			java.awt.Image.class);
		pp.setAccessible(true);
		java.awt.image.BufferedImage img =
			new java.awt.image.BufferedImage(120, 90, java.awt.image.BufferedImage.TYPE_INT_RGB);
		java.awt.Graphics2D g = img.createGraphics();
		g.setColor(java.awt.Color.ORANGE);
		g.fillRect(0, 0, 120, 90);
		g.dispose();

		Object payload = pp.invoke(null, img);
		if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac"))
		{
			// everywhere else AWT writes a real image and must be left alone
			assertNull("the mac path fired off a mac", payload);
			return;
		}
		assertNotNull("nothing was encoded to put on the clipboard", payload);
		java.awt.datatransfer.Transferable t = (java.awt.datatransfer.Transferable) payload;
		java.awt.datatransfer.DataFlavor[] fl = t.getTransferDataFlavors();
		assertEquals("the clipboard was offered more than the PNG", 1, fl.length);
		assertEquals("image/png", fl[0].getPrimaryType() + "/" + fl[0].getSubType());

		byte[] bytes = readAll((java.io.InputStream) t.getTransferData(fl[0]));
		// the eight bytes every PNG starts with, which the TIFF did not
		byte[] magic = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a};
		for (int i = 0; i < magic.length; i++)
		{
			assertEquals("byte " + i + " is not PNG, it is "
				+ String.format("%02x", bytes[i]), magic[i], bytes[i]);
		}
		// and it decodes back to the picture it was made from
		java.awt.image.BufferedImage back = javax.imageio.ImageIO.read(
			new java.io.ByteArrayInputStream(bytes));
		assertNotNull("the bytes do not decode as an image", back);
		assertEquals(120, back.getWidth());
		assertEquals(90, back.getHeight());
		// encoded, not raw: the whole page went across as six megabytes before
		assertTrue("this is not compressed, it is a bitmap: " + bytes.length,
			bytes.length < 120 * 90 * 4);
		// and the board is read more than once, so the bytes must survive it
		assertEquals("the second read came back short", bytes.length,
			readAll((java.io.InputStream) t.getTransferData(fl[0])).length);
	}

	private static byte[] readAll(java.io.InputStream in) throws Exception
	{
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		byte[] buf = new byte[8192];
		int n;
		while ((n = in.read(buf)) > 0)
		{
			out.write(buf, 0, n);
		}
		return out.toByteArray();
	}

	@Test
	public void aBoardTooLongForOneColumnIsSetInSeveralRatherThanCutOff() throws Exception
	{
		// A whole grind to 99 leaves hundreds of kinds of loot behind it, and that
		// is precisely the board somebody wants to show. Sixty rows is a readable
		// column, not a limit on what can be shared: past sixty the picture takes
		// another column rather than a tail saying what was thrown away.
		ChroniclePanel p = panel(stub(true));
		final List<LocalStore.BagItem> bag = new ArrayList<>();
		for (int i = 0; i < 287; i++)
		{
			bag.add(new LocalStore.BagItem(i + 1, "Thing " + i, 1, 10));
		}
		Method ot = ChroniclePanel.class.getDeclaredMethod("lootPicture",
			String.class, List.class, long[].class, boolean.class);
		ot.setAccessible(true);
		Method ci = ChroniclePanel.class.getDeclaredMethod("copyImage", JPanel.class);
		ci.setAccessible(true);

		final Object[] out = new Object[2];
		edt(() ->
		{
			JPanel page = (JPanel) ot.invoke(p, "On-task loot", bag, new long[]{287, 2_870}, false);
			// read before drawing: the drawing takes the page apart to set it
			out[0] = labels(page);
			out[1] = ci.invoke(null, page);
		});
		@SuppressWarnings("unchecked")
		List<String> said = (List<String>) out[0];
		assertTrue("the first row is missing: " + said.size(), said.contains("Thing 0"));
		assertTrue("the board was cut short at " + said.size() + " rows",
			said.contains("Thing 286"));
		for (String line : said)
		{
			assertFalse("a tail was written instead of another column: " + line,
				line.endsWith(" more"));
		}

		java.awt.Image img = (java.awt.Image) out[1];
		assertNotNull("no picture was drawn", img);
		assertTrue("287 rows were drawn in one column: " + img.getWidth(null),
			img.getWidth(null) >= 340 * 4);
		assertTrue("the ribbon was never broken up: " + img.getHeight(null),
			img.getHeight(null) < 2_000);
	}

	@Test
	public void thePictureDropsTheNavigationItWasBuiltWith() throws Exception
	{
		// The way back and the copy are navigation and have no business in a
		// picture somebody is sharing; the card is what follows them.
		ChroniclePanel p = panel(stub(true));
		Method bd = ChroniclePanel.class.getDeclaredMethod("buildSourceDetail", String.class);
		bd.setAccessible(true);
		Method strip = ChroniclePanel.class.getDeclaredMethod("stripChrome", JPanel.class);
		strip.setAccessible(true);
		final List<String> after = new ArrayList<>();
		edt(() ->
		{
			JPanel page = (JPanel) strip.invoke(null, bd.invoke(p, "Zalcano"));
			after.addAll(labels(page));
		});
		assertFalse("the way back rode along: " + after, after.contains("< Back"));
		assertFalse("the copy rode along: " + after, after.contains("copy"));
	}

	@Test
	public void theStripCarriesTheFourTabs() throws Exception
	{
		// What each tab is FOR, rather than what its fields are called. The eight
		// tabs this replaced are still the boards underneath, reached by a tab and
		// a sub-tab; "Progression" is gone as a destination because the period it
		// used to own now governs every tab from above the strip.
		ChroniclePanel p = panel(stub(true));
		java.lang.reflect.Field f = ChroniclePanel.class.getDeclaredField("tabGroup");
		f.setAccessible(true);
		Container strip = (Container) f.get(p);
		List<String> tips = new ArrayList<>();
		for (Component k : strip.getComponents())
		{
			if (k instanceof javax.swing.JComponent)
			{
				String tip = ((javax.swing.JComponent) k).getToolTipText();
				if (tip != null)
				{
					tips.add(tip);
				}
			}
		}
		assertEquals("tabs: " + tips, 4, tips.size());
		for (String want : new String[]{"Record", "Standing", "Loot", "Trackers"})
		{
			assertTrue("tabs: " + tips, tips.contains(want));
		}
		// nothing on the sheet ranks the account against another player
		assertFalse("tabs: " + tips, tips.contains("Hiscores"));
		assertFalse("tabs: " + tips, tips.contains("History"));
		assertFalse("Progression is no longer a destination: " + tips,
			tips.contains("Progression"));
		// PvM and Skilling were two tabs built from one widget whose cells behaved
		// in opposite ways on a click. They are one sheet now, in the order the
		// game's own hiscores panel puts it.
		assertFalse("PvM and Skilling are one sheet: " + tips, tips.contains("PvM"));
		assertFalse("PvM and Skilling are one sheet: " + tips, tips.contains("Skilling"));
		// And the collection log stopped being a tab when the sheet's activity
		// tiles became the way into it, which is what gave the loot a tab of its
		// own instead of a sub-tab under the sheet.
		assertFalse("the log is reached from the sheet now: " + tips,
			tips.contains("Collection log"));
	}

	@Test
	public void aLifetimeSaysWhereASkillStandsAndNotWhereItBegan() throws Exception
	{
		// everything came before a lifetime, so naming the start tells a reader
		// what they already assumed
		LocalDate today = LocalDate.now();
		ChroniclePanel p = panel(spanned(false, false));
		set(p, "histFacet", "Skills");

		// a window names both ends
		set(p, "histFrom", today.minusDays(15));
		set(p, "histTo", today.minusDays(5));
		assertEquals(grid(labels(history(p))).toString(), "73 to 75",
			grid(labels(history(p))).get(1));

		set(p, "histFrom", null);
		set(p, "histTo", null);
		set(p, "histGranularity", "Lifetime");
		List<String> lifetime = grid(labels(history(p)));
		assertFalse("a lifetime named a start: " + lifetime, lifetime.get(1).contains(" to "));
		assertFalse("the total named a start: " + labels(history(p)),
			String.valueOf(beside(labels(history(p)), "Total level")).contains(" to "));
	}

	@Test
	public void aLifetimeCountsEverySittingTheRecordHolds() throws Exception
	{
		// the feed's oldest entry can begin after the record's first line, and a
		// lifetime is every day it holds, so its sittings are all of them
		long now = System.currentTimeMillis();
		LocalDate today = LocalDate.now();
		PanelPreviewTest.StubPlugin s = stub(true);
		HistoryLog.Baseline first = new HistoryLog.Baseline();
		first.skills.put("attack", 500L);
		s.history.put(today.minusDays(400), first);
		s.feed.add(session(now - 2 * DAY_MS, 95));
		ChroniclePanel p = panel(s);
		set(p, "histFacet", "Skills");
		set(p, "histGranularity", "Lifetime");
		List<String> all = labels(history(p));
		assertEquals(all.toString(), "1h 35m", beside(all, "Time played"));
		assertEquals(all.toString(), "1", beside(all, "Sessions"));
	}

	/**
	 * A sitting still running after midnight counts in the day it began, where
	 * its closing line will be filed, and where the Recap, the Calendar and the
	 * Journal already put it. Gated on the period reaching today, it was in no
	 * History board at all until logout.
	 */
	@Test
	public void aRunningSittingPastMidnightCountsInTheDayItBegan() throws Exception
	{
		LocalDate yesterday = LocalDate.now().minusDays(1);
		PanelPreviewTest.StubPlugin s = stub(true);
		s.sessionStartMs = yesterday.atTime(23, 0).atZone(java.time.ZoneId.systemDefault())
			.toInstant().toEpochMilli();
		s.sessionElapsed = 90;
		for (int back = 2; back >= 1; back--)
		{
			HistoryLog.Baseline b = new HistoryLog.Baseline();
			b.skills.put("attack", 1_050_000L - back);
			s.history.put(LocalDate.now().minusDays(back), b);
		}
		ChroniclePanel p = panel(s);
		set(p, "histFacet", "Skills");
		set(p, "histGranularity", "Day");
		set(p, "histCursor", yesterday);
		List<String> day = labels(history(p));
		assertEquals(day.toString(), "1h 30m", beside(day, "Time played"));
		// and today, which it did not begin in, leaves it out
		set(p, "histCursor", LocalDate.now());
		assertEquals("0m", beside(labels(history(p)), "Time played"));
	}

	@Test
	public void aSpriteIsAskedForOnceAndNotOncePerBuild() throws Exception
	{
		// every build used to queue four more tasks on the client thread, and a
		// reader clicking about queued them faster than the client drained them
		PanelPreviewTest.StubPlugin s = stub(true);
		s.spriteManager = Mockito.mock(net.runelite.client.game.SpriteManager.class);
		Mockito.doAnswer(inv ->
		{
			s.spriteAsks.add(inv.getArgument(0));
			return null;
		}).when(s.spriteManager).getSpriteAsync(Mockito.anyInt(), Mockito.anyInt(),
			Mockito.any(java.util.function.Consumer.class));

		ChroniclePanel p = panel(s);
		for (int i = 0; i < 6; i++)
		{
			history(p);
		}
		// The facet strip that used to do the asking is gone -- the tabs replaced
		// it -- so what matters is not how many sprites a build wants but that
		// building again wants no more of them.
		int asked = s.spriteAsks.size();
		for (int i = 0; i < 6; i++)
		{
			history(p);
		}
		assertEquals("asked again: " + s.spriteAsks, asked, s.spriteAsks.size());

		// The bands want sprites too, for a line with nothing of its own to wear,
		// and a board of two hundred rebuilt on every click is exactly where an
		// unbounded queue would show.
		s.kcs.put("Zulrah", 108L);
		s.kcs.put("Nechryael", 622L);
		set(p, "histFacet", "PvM");
		set(p, "histGranularity", "Lifetime");
		// A board opened for the first time may legitimately want a sprite nobody
		// has asked for yet. What it may never do is want one again per build.
		history(p);
		int bands = s.spriteAsks.size();
		for (int i = 0; i < 6; i++)
		{
			history(p);
		}
		assertEquals("the bands asked again: " + s.spriteAsks, bands, s.spriteAsks.size());
		assertEquals("the same sprite twice: " + s.spriteAsks,
			s.spriteAsks.size(), new java.util.HashSet<>(s.spriteAsks).size());
	}

	@Test
	public void theDearestDropIsFoundEvenWhenTheLedgerKeptOnlyItsName() throws Exception
	{
		// The ledger's bag mostly came from the old cloud journal, which kept item
		// NAMES and no ids at all. Read straight, the dearest row's id is zero for
		// four sources in five and the whole first step of the chain is dead, so a
		// named row is looked up in the item cache by name.
		PanelPreviewTest.StubPlugin s = stub(true);
		s.itemManager = livingItems();
		Mockito.when(s.items().search("")).thenReturn(Arrays.asList(
			priced(23_962, "Crystal shard"), priced(23_957, "Crystal tool seed")));
		s.kcs.put("Zalcano", 2_024L);
		s.sources = Arrays.asList(
			new LocalStore.SourceRow("Zalcano", 2_024, 2_024, 81_900_000L, null, 0, 0, java.util.Collections.emptySet(), 0, 0));
		s.bags.put("Zalcano", Arrays.asList(
			// the dearest carries no id, the way the imported rows do
			new LocalStore.BagItem(0, "Crystal tool seed", 2, 46_173_662L),
			new LocalStore.BagItem(23_962, "Crystal shard", 4_340, 0L)));

		ChroniclePanel p = panel(s);
		set(p, "histGranularity", "Lifetime");
		set(p, "histFacet", "PvM");
		Method sig = ChroniclePanel.class.getDeclaredMethod("signatureItem", String.class);
		sig.setAccessible(true);
		assertEquals("the dearest row won, not the only one carrying an id",
			23_957, sig.invoke(p, "Zalcano"));

		// and the image behind it is shrunk once, however often the board is
		// redrawn: an image the cache already holds answers onLoaded through the
		// client thread, so waiting on it again every build is the queue that made
		// the whole plugin heavy
		for (int i = 0; i < 6; i++)
		{
			history(p);
		}
		Mockito.verify(s.items(), Mockito.times(1)).getImage(23_957, 1, false);
	}

	// an item cache that hands back a loaded image, and a client thread that runs
	// what it is given: without both, nothing downstream of onLoaded ever happens
	private static ItemManager livingItems()
	{
		ClientThread ct = Mockito.mock(ClientThread.class);
		Mockito.doAnswer(inv ->
		{
			((Runnable) inv.getArgument(0)).run();
			return null;
		}).when(ct).invokeLater(Mockito.any(Runnable.class));
		ItemManager im = Mockito.mock(ItemManager.class);
		java.util.Map<Integer, net.runelite.client.util.AsyncBufferedImage> made =
			new java.util.HashMap<>();
		Mockito.when(im.getImage(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyBoolean()))
			.thenAnswer(inv -> made.computeIfAbsent(inv.getArgument(0), id ->
			{
				net.runelite.client.util.AsyncBufferedImage img =
					new net.runelite.client.util.AsyncBufferedImage(ct, 36, 32,
						java.awt.image.BufferedImage.TYPE_INT_ARGB);
				img.loaded();
				return img;
			}));
		return im;
	}

	@Test
	public void aNameTheLedgerOnlyPartlySharesIsNotBorrowedForAnIcon() throws Exception
	{
		// the click-through resolver will take any source whose name contains this
		// one or is contained by it, which is right for a click and wrong for an
		// icon: the restaurant would wear whatever the pickpocketed gnome dropped
		PanelPreviewTest.StubPlugin s = stub(true);
		Mockito.when(s.items().search("")).thenReturn(Arrays.asList(
			priced(1_061, "Chef's hat"), priced(12_020, "Gnome scarf")));
		s.kcs.put("Gnome Restaurant", 40L);
		s.sources = Arrays.asList(
			new LocalStore.SourceRow("Gnome", 2, 2, 177L, null, 0, 0, java.util.Collections.emptySet(), 0, 0));
		s.bags.put("Gnome", Arrays.asList(
			new LocalStore.BagItem(0, "Chef's hat", 1, 355L)));
		ChroniclePanel p = panel(s);
		Method sig = ChroniclePanel.class.getDeclaredMethod("signatureItem", String.class);
		sig.setAccessible(true);
		assertFalse("the restaurant borrowed the gnome's bag",
			Integer.valueOf(1_061).equals(sig.invoke(p, "Gnome Restaurant")));
	}

	@Test
	public void anAnswerFoundBeforeTheItemCacheLoadedIsNotKept() throws Exception
	{
		// the item cache loads its prices over the network and starts out empty. A
		// nothing found in that window is a not-yet, and keeping it would pin the
		// row to its fallback icon for the rest of the session.
		PanelPreviewTest.StubPlugin s = stub(true);
		Mockito.when(s.items().search("")).thenReturn(new ArrayList<>());
		s.kcs.put("Zalcano", 2_024L);
		s.sources = Arrays.asList(
			new LocalStore.SourceRow("Zalcano", 2_024, 2_024, 81_900_000L, null, 0, 0, java.util.Collections.emptySet(), 0, 0));
		s.bags.put("Zalcano", Arrays.asList(
			new LocalStore.BagItem(0, "Crystal tool seed", 2, 46_173_662L)));
		ChroniclePanel p = panel(s);
		Method sig = ChroniclePanel.class.getDeclaredMethod("signatureItem", String.class);
		sig.setAccessible(true);
		assertEquals(0, sig.invoke(p, "Zalcano"));

		// the prices land a moment later, and the answer is there to be found
		Mockito.when(s.items().search("")).thenReturn(Arrays.asList(
			priced(23_957, "Crystal tool seed")));
		assertEquals("the empty answer was kept", 23_957, sig.invoke(p, "Zalcano"));
	}

	@Test
	public void aLineWithNothingOfItsOwnWearsItsSkillThenItsFacet() throws Exception
	{
		// the icon chain, in the order the record can answer it. Nothing here has
		// a drop on the ledger and the item cache names nothing, so a thieving
		// target falls to its skill and a monster falls to its facet's sprite.
		PanelPreviewTest.StubPlugin s = stub(true);
		s.skillIconManager = Mockito.mock(net.runelite.client.game.SkillIconManager.class);
		Mockito.when(s.skillIconManager.getSkillImage(net.runelite.api.Skill.HERBLORE, true))
			.thenReturn(new java.awt.image.BufferedImage(
				25, 25, java.awt.image.BufferedImage.TYPE_INT_ARGB));
		s.spriteManager = Mockito.mock(net.runelite.client.game.SpriteManager.class);
		Mockito.doAnswer(inv ->
		{
			s.spriteAsks.add((Integer) inv.getArgument(0));
			((java.util.function.Consumer<java.awt.image.BufferedImage>) inv.getArgument(2))
				.accept(new java.awt.image.BufferedImage(
					32, 32, java.awt.image.BufferedImage.TYPE_INT_ARGB));
			return null;
		}).when(s.spriteManager).getSpriteAsync(Mockito.anyInt(), Mockito.anyInt(),
			Mockito.any(java.util.function.Consumer.class));

		// two minigame pages, neither with a drop on the ledger: one the record
		// can name a skill for, one it cannot
		s.kcs.put("Mastering Mixology", 240L);
		s.kcs.put("Soul Wars", 346L);
		ChroniclePanel p = panel(s);
		set(p, "histGranularity", "Lifetime");
		set(p, "histFacet", "Activities");
		JPanel view = history(p);

		javax.swing.JLabel mixology = iconBeside(view, "Mastering Mixology");
		assertNotNull("no icon column on the mixology line", mixology);
		assertNotNull("mixology wore nothing", mixology.getIcon());
		assertTrue("not downsized: " + mixology.getIcon().getIconHeight(),
			mixology.getIcon().getIconHeight() <= 18);

		// Soul Wars is a fight, not a skill, so it falls to the facet's own
		// sprite, which lands asynchronously
		javax.swing.JLabel souls = iconBeside(view, "Soul Wars");
		assertNotNull("no icon column on the soul wars line", souls);
		edt(() ->
		{
		});
		assertNotNull("soul wars wore nothing", souls.getIcon());
		assertTrue("not downsized: " + souls.getIcon().getIconHeight(),
			souls.getIcon().getIconHeight() <= 18);
		assertTrue("the minigames sprite was never asked for: " + s.spriteAsks,
			s.spriteAsks.contains(1053));
	}

	// the icon label of the row whose name is given, or null where it has none
	private static javax.swing.JLabel iconBeside(Container c, String name)
	{
		JPanel row = rowNamed(c, name);
		if (row == null)
		{
			return null;
		}
		Component west = ((BorderLayout) row.getLayout())
			.getLayoutComponent(BorderLayout.WEST);
		return west instanceof javax.swing.JLabel ? (javax.swing.JLabel) west : null;
	}

	/**
	 * Levels past ninety nine.
	 *
	 * <p>The game stops naming them at 99 and the experience curve does not, so a
	 * sheet showing what an account has actually done shows the level the
	 * experience has reached. The totals and the count of 99s are the game's own
	 * statistics and stay where the game puts them.
	 */
	@Test
	public void aSkillPastNinetyNineShowsTheLevelItHasActuallyReached() throws Exception
	{
		java.lang.reflect.Method real = PaceBook.class
			.getDeclaredMethod("levelAt", long.class);
		real.setAccessible(true);
		java.lang.reflect.Method virt = PaceBook.class
			.getDeclaredMethod("virtualLevelAt", long.class);
		virt.setAccessible(true);

		// 13,034,431 is exactly 99; 14,391,160 is 100
		assertEquals(99, ((Integer) real.invoke(null, 13_034_431L)).intValue());
		assertEquals(99, ((Integer) virt.invoke(null, 13_034_431L)).intValue());
		assertEquals("the game stops at 99 and the pace line goes with it",
			99, ((Integer) real.invoke(null, 200_000_000L)).intValue());
		assertEquals("a maxed skill has reached 126", 126,
			((Integer) virt.invoke(null, 200_000_000L)).intValue());
		assertEquals(100, ((Integer) virt.invoke(null, 14_391_160L)).intValue());

		// and the two agree everywhere below 99, or the sheet would disagree with
		// itself on every ordinary account
		for (long xp : new long[]{0, 83, 1_154, 100_000, 5_000_000, 13_034_430L})
		{
			assertEquals("the curves part company below 99 at " + xp,
				real.invoke(null, xp), virt.invoke(null, xp));
		}
	}

	/**
	 * The total is the game's, and stays the game's.
	 *
	 * <p>Virtual levels are what a tile SHOWS, because the experience curve does
	 * not stop where the game stops naming levels. The total is a different kind
	 * of thing: it is a statistic the game itself keeps and publishes, a player
	 * knows their own, and it appears on the hiscores. Summing virtual levels
	 * would produce a number that is not it - on the owner's own account, 2,253
	 * where the game says 2,235 - and no amount of being defensible makes that
	 * the number he would be looking for.
	 */
	@Test
	public void theTotalCountsTheLevelsTheGameNamesAndNotTheOnesPastThem()
		throws Exception
	{
		Class<?> baseline = Class.forName("chronicle.HistoryLog$Baseline");
		java.lang.reflect.Constructor<?> c = baseline.getDeclaredConstructor();
		c.setAccessible(true);
		Object at = c.newInstance();
		java.lang.reflect.Field skills = baseline.getDeclaredField("skills");
		skills.setAccessible(true);
		@SuppressWarnings("unchecked")
		java.util.Map<String, Long> xp = (java.util.Map<String, Long>) skills.get(at);
		// 13,034,431 is exactly 99; 50M is 112; 200M is the cap at 126
		xp.put("attack", 13_034_431L);
		xp.put("strength", 50_000_000L);
		xp.put("defence", 200_000_000L);
		java.lang.reflect.Field complete = baseline.getDeclaredField("complete");
		complete.setAccessible(true);
		complete.setBoolean(at, true);

		java.lang.reflect.Method levels = HistoryLog.class.getDeclaredMethod(
			"levels", baseline, java.util.List.class);
		levels.setAccessible(true);
		Object got = levels.invoke(null, at,
			java.util.Arrays.asList("attack", "strength", "defence"));

		java.lang.reflect.Field of = got.getClass().getDeclaredField("of");
		java.lang.reflect.Field virtual = got.getClass().getDeclaredField("virtual");
		java.lang.reflect.Field total = got.getClass().getDeclaredField("total");
		java.lang.reflect.Field nines = got.getClass().getDeclaredField("nines");
		of.setAccessible(true);
		virtual.setAccessible(true);
		total.setAccessible(true);
		nines.setAccessible(true);
		@SuppressWarnings("unchecked")
		java.util.Map<String, Integer> real = (java.util.Map<String, Integer>) of.get(got);
		@SuppressWarnings("unchecked")
		java.util.Map<String, Integer> past =
			(java.util.Map<String, Integer>) virtual.get(got);

		assertEquals("the game names them all 99", Integer.valueOf(99), real.get("attack"));
		assertEquals(Integer.valueOf(99), real.get("strength"));
		assertEquals(Integer.valueOf(99), real.get("defence"));

		assertEquals("and the curve carries on", Integer.valueOf(99), past.get("attack"));
		assertEquals(Integer.valueOf(112), past.get("strength"));
		assertEquals(Integer.valueOf(126), past.get("defence"));

		assertEquals("the total counts what the game names, not what the curve"
			+ " reaches", 99 * 3, total.getInt(got));
		assertEquals("and so does the count of 99s", 3, nines.getInt(got));
	}
}
