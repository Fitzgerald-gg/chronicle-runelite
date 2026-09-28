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
			a.skills.put(key, 1_000_000L);
			if (sailingOnClose || sk != net.runelite.api.Skill.SAILING)
			{
				b.skills.put(key, 1_250_000L);
			}
			c.skills.put(key, 1_400_000L);
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

	private static java.util.TreeMap<LocalDate, HistoryLog.Baseline> readSpine(String... lines)
		throws Exception
	{
		File dir = Files.createTempDirectory("chronicle-history-grid").toFile();
		File f = new File(dir, LocalStore.slug("Tester") + HistoryLog.SPINE_SUFFIX);
		Files.write(f.toPath(), String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
		return new HistoryLog(new com.google.gson.Gson()).read(dir, "Tester");
	}

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
		set(p, "histFacet", "Trackers");
		return p;
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

	private static JPanel pvm(PanelPreviewTest.StubPlugin stub) throws Exception
	{
		ChroniclePanel p = panel(stub);
		set(p, "histFacet", "PvM");
		return history(p);
	}

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

	private static List<String> periodLabels(ChroniclePanel panel) throws Exception
	{
		setView(panel, "HISTORY");
		return periodLabelsAsShown(panel);
	}

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
			Object stood = get(panel, "view");
			setView(panel, "HISTORY");
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

	private static List<String> bossHover(ChroniclePanel panel, String boss)
		throws Exception
	{
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

	private static List<String> card(List<String> all)
	{
		int at = all.indexOf("TRACKED PROGRESS");
		return at < 0 ? new ArrayList<>() : all.subList(at, all.size());
	}

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

	private static String beside(List<String> card, String label)
	{
		int at = card.indexOf(label);
		return at < 0 || at + 1 >= card.size() ? null : card.get(at + 1);
	}

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
				|| label.startsWith("Nothing counted"))
			{
				end = i;
				break;
			}
		}
		return all.subList(at, end);
	}

	private static String standingLevel(List<String> all)
	{
		String total = beside(all, "Total level");
		assertNotNull(all.toString(), total);
		int at = total.indexOf(" to ");
		String to = at < 0 ? total : total.substring(at + 4);
		int dot = to.indexOf(" · ");
		return dot < 0 ? to : to.substring(0, dot);
	}

	private static List<String> between(List<String> card, String from, String to)
	{
		int a = card.indexOf(from);
		assertTrue(from + " is not on the card: " + card, a >= 0);
		int b = to == null ? -1 : card.indexOf(to);
		return card.subList(a, b < 0 ? card.size() : b);
	}

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

	private static ChroniclePanel opened(PanelPreviewTest.StubPlugin s) throws Exception
	{
		ChroniclePanel p = panel(s);
		for (String group : chronicle.panel.HistoryProgress.GROUPS)
		{
			openFolds(p).add("history:" + group);
		}
		return p;
	}

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

	private static int leftInset(Container c, String name)
	{
		JPanel row = rowNamed(c, name);
		assertNotNull(name + " is not on the render", row);
		return row.getBorder().getBorderInsets(row).left;
	}

	private static String day(long ms)
	{
		return DateTimeFormatter.ofPattern("d MMM", Locale.UK).withZone(ZoneId.systemDefault())
			.format(java.time.Instant.ofEpochMilli(ms));
	}

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
		List<String> bare = headline(labels(history(panel(s))));
		assertNull(bare.toString(), beside(bare, "Time played"));
		assertNull(bare.toString(), beside(bare, "Sessions"));

		s.feed.add(session(now - DAY_MS, 95));
		s.feed.add(session(now - 2 * DAY_MS, 42));
		s.feed.add(session(now - 3 * DAY_MS, 58));
		s.feed.add(session(now - 20 * DAY_MS, 400));
		List<String> head = headline(labels(history(panel(s))));
		assertEquals(head.toString(), "3h 15m", beside(head, "Time played"));
		assertEquals(head.toString(), "3", beside(head, "Sessions"));
		assertEquals(head.toString(), Arrays.asList("THE PERIOD", "Time played", "3h 15m",
			"Sessions", "3", "Experience"), head.subList(0, 6));

		s.feed.remove(3);
		List<String> inside = headline(labels(history(panel(s))));
		assertNull(inside.toString(), beside(inside, "Time played"));
		assertNull(inside.toString(), beside(inside, "Sessions"));
	}

	@Test
	public void theGroupsReadInOrderEachWithItsCount() throws Exception
	{
		List<String> card = card(labels(history(panel(stub(true)))));
		assertEquals(card.toString(), Arrays.asList(
			"EXPERIENCE", "COMBAT", "LOOT", "SKILLING", "UPKEEP", "THE REST"),
			groupHeads(card));
		assertEquals(card.toString(), "1", beside(card, "EXPERIENCE"));
		assertEquals(card.toString(), "4", beside(card, "COMBAT"));
		assertEquals(card.toString(), "6", beside(card, "LOOT"));
		assertEquals(card.toString(), "1", beside(card, "SKILLING"));
		assertEquals(card.toString(), "3", beside(card, "UPKEEP"));
		assertEquals(card.toString(), "1", beside(card, "THE REST"));
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
		assertFalse(shut.toString(), shut.contains("Doses drunk"));
		assertFalse(shut.toString(), shut.contains("Meals eaten"));
		assertEquals(shut.toString(), 1, java.util.Collections.frequency(shut, "+225"));
		int at = shut.indexOf("Potions");
		assertEquals(shut.toString(), Arrays.asList("Potions", "+225", "THE REST"),
			shut.subList(at, at + 3));

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
		assertTrue(open.toString(), open.contains("Fishing"));
		assertFalse(open.toString(), open.contains("Shark"));
		assertTrue(openFolds(p).isEmpty());
		int at = open.indexOf("SKILLING");
		assertEquals(open.toString(), Arrays.asList("SKILLING", "1", "Fishing", "+50", "UPKEEP"),
			open.subList(at, at + 5));

		JPanel group = rowNamed(view, "SKILLING");
		assertNotNull(open.toString(), group);
		assertEquals(java.awt.Cursor.HAND_CURSOR, group.getCursor().getType());
		click(group);
		assertEquals(java.util.Collections.singleton("history:shut:Skilling"), openFolds(p));
		List<String> shut = card(labels(history(p)));
		assertFalse(shut.toString(), shut.contains("Fishing"));
		assertTrue(shut.toString(), shut.contains("SKILLING"));
		assertTrue(shut.toString(), shut.contains("COMBAT"));

		click(rowNamed(history(p), "SKILLING"));
		assertTrue(openFolds(p).isEmpty());
		view = history(p);

		click(rowNamed(view, "Fishing"));
		assertEquals(java.util.Collections.singleton(FOLD), openFolds(p));
		open = card(labels(history(p)));
		at = open.indexOf("Fishing");
		assertEquals(open.toString(),
			Arrays.asList("Fishing", "+50", "Shark", "+30", "Other", "+20", "UPKEEP"),
			open.subList(at, at + 7));

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
		assertEquals(card.toString(),
			Arrays.asList("Food", "+20", "Shark", "+16", "Other", "+4", "Potions"),
			card.subList(at, at + 7));
		assertEquals(card.toString(), 1, java.util.Collections.frequency(card, "Shark"));

		JLabel openName = (JLabel) ((BorderLayout) rowNamed(view, "Food").getLayout())
			.getLayoutComponent(BorderLayout.CENTER);
		JLabel shutName = (JLabel) ((BorderLayout) rowNamed(view, "Potions").getLayout())
			.getLayoutComponent(BorderLayout.CENTER);
		assertEquals(openName.getForeground(), shutName.getForeground());
		assertEquals(openName.getFont(), shutName.getFont());
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
		for (int i = 0; i < 6; i++)
		{
			assertTrue(card.toString(), card.contains(items[i]));
		}
		assertFalse(card.toString(), card.contains(items[6]));
		assertTrue(card.toString(), card.contains("Show 3 more"));

		click(rowNamed(view, "Show 3 more"));
		List<String> more = card(labels(history(p)));
		assertTrue(more.toString(), more.contains(items[8]));
		assertFalse(more.toString(), more.contains("Show 3 more"));
	}

	@Test
	public void aListFarPastItsCapSaysWhatIsLeftAndOneClickBringsIt() throws Exception
	{
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
		assertEquals(card.toString(), Arrays.asList("Combat achievements", "+1",
			"Elite · Perfect Zulrah", day(now - 5 * DAY_MS)),
			card.subList(card.indexOf("Combat achievements"),
				card.indexOf("Combat achievements") + 4));
		assertEquals(card.toString(), Arrays.asList("Diaries completed", "+1",
			"Karamja Elite", day(now - 6 * DAY_MS)),
			card.subList(card.indexOf("Diaries completed"),
				card.indexOf("Diaries completed") + 4));
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
		assertEquals(card.toString(), Arrays.asList("Slayer tasks completed", "+2",
			"Gargoyles", "137 · " + day((long) ((now - 2 * 86_400) * 1000)),
			"Nechryael", "58 · " + day((long) ((now - 4 * 86_400) * 1000))),
			card.subList(at, at + 6));
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
		put(a, b, "damageTaken", 100_000, 140_000);
		put(a, b, "hitsMissed", 4_000, 4_600);
		put(a, b, "fishCaught", 1_000, 1_050);
		put(a, b, "vialsShattered", 10, 13);
		put(a, b, "foodEaten", 200, 220);
		put(a, b, "coinsFromAlchemy", 1_000_000, 1_400_000);
		put(a, b, "untakenLootValue", 2_000_000, 2_900_000);
		put(a, b, "examines", 400, 430);
		put(a, b, "teleportsTotal", 1_000, 1_060);
		put(a, b, "teleportsVarrock", 300, 318);
		put(a, b, "tilesRan", 900_000, 940_000);
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
		for (String label : new String[]{"Damage taken", "Hits missed", "Fishing",
			"Vials shattered", "Food", "The purse", "Uncollected loot", "Coins from alchemy",
			"Odds & ends", "Examines", "Teleports", "Destinations", "Varrock", "On foot",
			"Tiles run"})
		{
			assertTrue(label + " is out of reach: " + card, card.contains(label));
		}
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
		assertEquals("+5", beside(headline(labels(history(p))), "Slayer tasks completed"));

		double now = System.currentTimeMillis() / 1000.0;
		set(p, "historyJourney", journey(
			task("Abyssal demons", now, true),
			task("Gargoyles", now - 2 * 86_400, false),
			task("Nechryael", now - 4 * 86_400, false),
			task("Dust devils", now - 20 * 86_400, false)));
		assertEquals("+2", beside(headline(labels(history(p))), "Slayer tasks completed"));

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
		assertNull(beside(card(labels(history(p))), "Slayer kills"));

		double now = System.currentTimeMillis() / 1000.0;
		set(p, "historyJourney", journey(
			task("Abyssal demons", now, true, 90),
			task("Gargoyles", now - 2 * 86_400, false, 137),
			task("Nechryael", now - 4 * 86_400, false, 58),
			task("Dust devils", now - 20 * 86_400, false, 200)));
		List<String> card = card(labels(history(p)));
		assertEquals(card.toString(), "+195", beside(card, "Slayer kills"));
		assertEquals(card.toString(), card.indexOf("Slayer tasks completed") + 2,
			card.indexOf("Slayer kills"));

		set(p, "historyJourney", journey(
			task("Abyssal demons", now, true, 90),
			task("Dust devils", now - 20 * 86_400, false, 200)));
		assertNull(beside(card(labels(history(p))), "Slayer kills"));
	}

	@Test
	public void thePvmFacetSplitsBossesFromMonsters() throws Exception
	{
		PanelPreviewTest.StubPlugin s = stub(true);
		s.kcs.put("Zulrah", 108L);
		s.kcs.put("Nechryael", 622L);
		s.ledgerKcs.put("Nechryael", 622L);
		ChroniclePanel p = panel(s);
		List<String> all = labels(history(p));
		assertFalse(all.toString(), all.contains("Bosses"));

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
		PanelPreviewTest.StubPlugin s = stub(true);
		Mockito.when(s.items().search("")).thenReturn(Arrays.asList(
			priced(13_258, "Angler hat"), priced(13_259, "Angler top"),
			priced(12_019, "Coal bag")));
		ChroniclePanel p = panel(s);
		Method m = ChroniclePanel.class.getDeclaredMethod("signatureItem", String.class);
		m.setAccessible(true);
		assertEquals(13_258, m.invoke(p, "Fishing Trawler"));
		assertEquals(0, m.invoke(p, "Soul Wars"));
	}

	@Test
	public void aBandFoldsAwayAndSaysWhatItIsHolding() throws Exception
	{
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
		PanelPreviewTest.StubPlugin s = stub(true);
		s.kcs.put("Zulrah", 108L);
		ChroniclePanel p = panel(s);
		set(p, "histFacet", "PvM");
		List<String> all = labels(history(p));
		assertEquals(all.toString(), "+12", beside(headline(all), "Monsters slain"));
		assertTrue(all.toString(), all.indexOf("THE PERIOD") < all.indexOf("BOSSES"));
		assertEquals(all.toString(), 1, java.util.Collections.frequency(all, "THE PERIOD"));
	}

	@Test
	public void theGatherLandsTheJourneyBesideTheSpine() throws Exception
	{
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
		assertEquals("+2", beside(loot(s), "Collection log slots"));

		s.feed.remove(3);
		assertEquals("+7", beside(loot(s), "Collection log slots"));

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
		assertEquals("+5", beside(headline(labels(history(panel(s)))), "Deaths"));

		s.feed.remove(6);
		assertEquals("+3", beside(headline(labels(history(panel(s)))), "Deaths"));

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
		assertTrue(card.toString(), between(card, "EXPERIENCE", "COMBAT").contains("Levels gained"));
		assertTrue(card.toString(), between(card, "LOOT", "SKILLING").contains("Pets"));
		assertTrue(card.toString(),
			between(card, "ACHIEVEMENT", "THE REST").contains("Quests completed"));

		s.feed.removeIf(e -> "DIARY".equals(e.get("type").getAsString()));
		card = card(labels(history(opened(s))));
		assertNull(card.toString(), beside(card, "Diaries completed"));
		assertEquals(card.toString(), "+1", beside(card, "Pets"));
		assertEquals(card.toString(), "+6", beside(card, "Levels gained"));

		s.feed.removeIf(e -> e.get("ts").getAsLong() < now - 6 * DAY_MS);
		all = labels(history(opened(s)));
		card = card(all);
		for (String label : new String[]{"Pets", "Quests completed", "Diaries completed",
			"Combat achievements", "Levels gained"})
		{
			assertNull(card.toString(), beside(card, label));
		}
		assertEquals(card.toString(), "+3", beside(headline(all), "Deaths"));
		assertEquals(card.toString(), "+7", beside(card, "Collection log slots"));
	}

	@Test
	public void anEntryWithNoUsableStampIsSkipped() throws Exception
	{
		long now = System.currentTimeMillis();
		PanelPreviewTest.StubPlugin s = stub(true);
		s.feed.add(entry(now - DAY_MS, "DEATH", "killerName", "Vorkath"));
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
		assertEquals(all.toString(), java.util.Collections.singletonList("EXPERIENCE"),
			groupHeads(card(all)));
		assertEquals(all.toString(), "1", beside(card(all), "EXPERIENCE"));
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
		assertEquals(card.toString(), Arrays.asList("TRACKED PROGRESS", "EXPERIENCE", "3",
			"Slayer", "+400k", "Attack", "+50k", "Mining", "+5,000"), card);
	}

	@Test
	public void aPastPeriodDrawsTheLevelsItsClosingLineEndedOn() throws Exception
	{
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
		LocalDate today = LocalDate.now();
		String standing = fmt(99L * skillCount());
		ChroniclePanel p = panel(spanned(true, true));
		set(p, "histFacet", "Skills");
		List<String> all = labels(history(p));
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
		ChroniclePanel p = panel(firstYear());
		set(p, "histFacet", "Skills");
		set(p, "histFrom", LocalDate.parse("2021-06-01"));
		set(p, "histTo", LocalDate.parse("2022-12-31"));
		JPanel view = history(p);
		List<String> all = labels(view);
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
		LocalDate today = LocalDate.now();
		ChroniclePanel p = panel(spanned(true, true));
		set(p, "histFacet", "Skills");
		set(p, "histFrom", today.minusDays(15));
		set(p, "histTo", today);
		List<String> all = labels(history(p));
		assertEquals(all.toString(),
			fmt(99L * skillCount()) + " · +" + fmt(3L * skillCount()),
			beside(all, "Total level"));
		assertEquals(all.toString(), "0", beside(headline(all), "99s reached"));
	}

	@Test
	public void aClosedPeriodNamesItsOpeningBesideItsClose() throws Exception
	{
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
		assertEquals(grid.toString(), "74", grid.get(grid.indexOf("ATT") + 1));
		assertEquals(grid.toString(), "77", grid.get(grid.indexOf("MIN") + 1));
	}

	@Test
	public void aPeriodWhoseEndsDrawDifferentSkillsCountsNoLevelsAndNoNines() throws Exception
	{
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

		set(p, "histFrom", null);
		set(p, "histTo", null);
		set(p, "histGranularity", "Lifetime");
		all = labels(history(p));
		assertEquals(all.toString(), "135", beside(all, "Zulrah"));
	}

	@Test
	public void aLedgerSourceWithNoLogPageSitsUnderMonsters() throws Exception
	{
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

		ChroniclePanel q = panel(staged(false));
		set(q, "histFrom", today.minusDays(40));
		set(q, "histTo", today);
		assertEquals("Loot and kills since " + today.minusDays(3).format(FULL),
			noteHolding(history(q), "Loot and kills since"));

		assertNull(noteHolding(history(panel(stub(true))), "Counters since"));
		assertNull(noteHolding(history(panel(stub(true))), "Loot and kills since"));
	}

	@Test
	public void lootJoiningOnTheCountersOwnLineAddsNoClause() throws Exception
	{
		LocalDate today = LocalDate.now();
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
		long now = System.currentTimeMillis();
		PanelPreviewTest.StubPlugin s = stub(true);
		s.feed.add(entry(now - 400 * DAY_MS, "COLLECTION", "itemName", "Older than the window"));
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
		assertFalse(all.toString(), all.stream().anyMatch(l -> l.startsWith("MILESTONES")));
		assertFalse(all.toString(), all.stream().anyMatch(l -> l.contains("Abyssal orphan")));

		List<String> journal = labels(journal(p));
		assertTrue(journal.toString(),
			journal.stream().anyMatch(l -> l.contains("Abyssal orphan")));
	}

	@Test
	public void theJournalCarriesTheMilestonesTheTabNoLongerDraws() throws Exception
	{
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
		long now = System.currentTimeMillis();
		PanelPreviewTest.StubPlugin s = stub(true);
		s.feed.add(entry(now - 400 * DAY_MS, "COLLECTION", "itemName", "Older than the window"));
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
		long now = System.currentTimeMillis();
		PanelPreviewTest.StubPlugin s = stub(true);
		s.feed.add(entry(now - 400 * DAY_MS, "COLLECTION", "itemName", "Older than the window"));
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
		long now = System.currentTimeMillis();
		PanelPreviewTest.StubPlugin s = stub(true);
		s.feed.add(entry(now - 400 * DAY_MS, "COLLECTION", "itemName", "Older than the window"));
		s.feed.add(session(noonDaysAgo(40), 15, 0, 0, 0, 0, 0));
		JsonObject older = session(now - DAY_MS, 60);
		older.getAsJsonObject("data").addProperty("drops", 400);
		older.getAsJsonObject("data").addProperty("dropsGp", 2_000_000);
		s.feed.add(older);
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
		long now = System.currentTimeMillis();
		PanelPreviewTest.StubPlugin s = stub(true);
		s.feed.add(entry(now - 400 * DAY_MS, "COLLECTION", "itemName", "Older than the window"));
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
		assertFalse(card.toString(), card.contains("Left on the floor"));
		assertFalse(card.toString(), card.contains("Drops taken"));
		assertFalse(card.toString(), card.contains("Loot kept"));
	}

	@Test
	public void aFloorOnlySomeSittingsCountedIsNotThePeriodsFloor() throws Exception
	{
		long now = System.currentTimeMillis();
		PanelPreviewTest.StubPlugin s = stub(true);
		s.feed.add(entry(now - 400 * DAY_MS, "COLLECTION", "itemName", "Older than the window"));
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
		long now = System.currentTimeMillis();
		LocalDate today = LocalDate.now();
		PanelPreviewTest.StubPlugin s = stub(true);
		s.feed.add(entry(now - 400 * DAY_MS, "COLLECTION", "itemName", "Older than the window"));
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
		File src = new File("src/main/java/chronicle/ChroniclePlugin.java");
		if (!src.isFile())
		{
			return;
		}
		String text = new String(Files.readAllBytes(src.toPath()), StandardCharsets.UTF_8);
		assertTrue("the session summary must be recorded",
			text.contains("localStore.record(\"SESSION\""));
		int at = text.indexOf("private JsonObject sessionData(");
		assertTrue("the figures a sitting is summed into must be built in one"
			+ " place, or the live line and the written one diverge", at > 0);
		String body = text.substring(at, Math.min(text.length(), at + 1200));
		assertTrue("a session says how long it ran", body.contains("\"minutes\""));
		assertTrue("a session says what it received", body.contains("\"drops\""));
		assertTrue("and what it left on the floor", body.contains("\"left\""));
		assertTrue("and what that was worth", body.contains("\"leftGp\""));
		assertTrue("and the kills that left it", body.contains("\"leftKills\""));
		assertTrue("the live sitting must be the same line it will become",
			text.contains("line.add(\"data\", sessionData("));
	}

	@Test
	public void navigatingAwayFromASearchDoesNotRebuildTheNewPageAgain() throws Exception
	{
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
		ChroniclePanel p = panel(stub(true));
		setView(p, "HOME");
		Field d = ChroniclePanel.class.getDeclaredField("display");
		d.setAccessible(true);
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
		});
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
		int target = bar.getValue();
		assertTrue("the reader must be able to scroll at all", target > 0);

		Method toggle = ChroniclePanel.class.getDeclaredMethod("toggleFold", String.class);
		toggle.setAccessible(true);
		toggle.invoke(p, "history:Loot");
		display.setSize(240, 200);
		display.validate();

		javax.swing.JScrollBar moved =
			((javax.swing.JScrollPane) display.getComponent(0)).getVerticalScrollBar();
		assertTrue("the fold click threw the reader to the top",
			moved.getValue() > 0);
	}

	@Test
	public void aBandIsCappedAndOneClickBringsTheRest() throws Exception
	{
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
		set(p, "histGranularity", "Lifetime");
		List<String> up = periodLabels(p);
		assertFalse("the period row is empty: " + up, up.isEmpty());
		assertTrue("the period row does not name its window: " + up, up.contains("Lifetime"));
	}

	@Test
	public void aSectionWhoseRowsMixUnitsSaysHowManyItHolds() throws Exception
	{
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
		long now = System.currentTimeMillis();
		LocalDate today = LocalDate.now();
		PanelPreviewTest.StubPlugin s = stub(true);
		for (HistoryLog.Baseline b : s.history.values())
		{
			b.counters.remove("dropsReceived");
			b.counters.remove("lootValue");
		}
		s.feed.add(entry(now - 400 * DAY_MS, "COLLECTION", "itemName", "Older than the window"));
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
		long now = System.currentTimeMillis();
		PanelPreviewTest.StubPlugin s = stub(true);
		for (HistoryLog.Baseline b : s.history.values())
		{
			b.counters.remove("dropsReceived");
			b.counters.remove("lootValue");
		}
		s.feed.add(entry(now - 400 * DAY_MS, "COLLECTION", "itemName", "Older than the window"));
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
		long now = System.currentTimeMillis();
		LocalDate today = LocalDate.now();
		PanelPreviewTest.StubPlugin s = stub(true);
		for (HistoryLog.Baseline b : s.history.values())
		{
			b.counters.remove("dropsReceived");
			b.counters.remove("lootValue");
		}
		s.feed.add(entry(now - 400 * DAY_MS, "COLLECTION", "itemName", "Older than the window"));
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
		assertFalse(card.toString(), card.contains("+400"));

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
		LocalDate today = LocalDate.now();
		PanelPreviewTest.StubPlugin s = stub(true);
		HistoryLog.Baseline first = new HistoryLog.Baseline();
		first.skills.put("attack", 900_000L);
		s.history.put(today.minusDays(90), first);
		ChroniclePanel p = panel(s);
		set(p, "histGranularity", "Lifetime");
		assertTrue(periodLabels(p).toString(), periodLabels(p).contains("Lifetime"));
		List<String> all = labels(history(p));
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
		ChroniclePanel p = panel(stub(true));
		assertTrue("the week's arrows should step", arrowShows(history(p), "<"));
		assertTrue("the week's arrows should step", arrowShows(history(p), ">"));

		set(p, "histGranularity", "Lifetime");
		assertFalse("lifetime has nowhere to step back to", arrowShows(history(p), "<"));
		assertFalse("lifetime has nowhere to step forward to", arrowShows(history(p), ">"));
	}

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
		final ChroniclePanel[] holder = new ChroniclePanel[1];
		PanelPreviewTest.StubPlugin s = stub(true);
		edt(() -> holder[0] = new ChroniclePanel(s));
		Field f = ChroniclePanel.class.getDeclaredField("histGranularity");
		f.setAccessible(true);
		assertEquals("Lifetime", f.get(holder[0]));
	}

	@Test
	public void theStripChoosesWhatThePeriodIsRead() throws Exception
	{
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

		for (String facet : new String[]{"Skills", "PvM", "Activities", "Trackers"})
		{
			set(p, "histFacet", facet);
			assertTrue(facet, labels(history(p)).contains("THE PERIOD"));
		}
	}

	@Test
	public void aSkillTileSaysWhereItCameFromAndWhatItGained() throws Exception
	{
		LocalDate today = LocalDate.now();
		ChroniclePanel p = panel(spanned(false, false));
		set(p, "histFacet", "Skills");
		set(p, "histFrom", today.minusDays(15));
		set(p, "histTo", today.minusDays(5));
		List<String> all = labels(history(p));
		int at = all.indexOf("ATT");
		assertTrue(all.toString(), at >= 0);
		assertEquals(all.toString(), Arrays.asList("ATT", "73 to 75", "+250k"),
			all.subList(at, at + 3));
	}

	@Test
	public void theWholeSheetHasATileOfItsOwn() throws Exception
	{
		LocalDate today = LocalDate.now();
		ChroniclePanel p = panel(spanned(false, false));
		set(p, "histFacet", "Skills");
		set(p, "histFrom", today.minusDays(15));
		set(p, "histTo", today.minusDays(5));
		List<String> all = labels(history(p));
		int at = all.indexOf("Total level");
		assertTrue("no total level tile: " + all, at >= 0);
		String tile = all.get(at + 1);
		assertTrue(tile, tile.startsWith(fmt(73L * skillCount()) + " to "));
		assertTrue(tile, tile.contains(" · +"));
		assertTrue(all.toString(), at > all.indexOf("ATT"));
	}

	@Test
	public void activitiesAreTheLogsOwnPagesAndNotTheBosses() throws Exception
	{
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
		assertEquals(all.toString(), "2.5M gp", beside(all, "Loot received"));
		assertEquals(all.toString(), "2.2M gp", beside(all, "Loot taken"));
		assertEquals(all.toString(), "300k gp", beside(all, "Loot left"));
	}

	@Test
	public void everyCountedNameCarriesWhatItPaid() throws Exception
	{
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
		assertEquals(all.toString(), Arrays.asList("Zulrah", "108", "5.0M"),
			all.subList(bosses + 1, bosses + 4));
		int mobs = all.indexOf("MONSTERS");
		assertEquals(all.toString(), Arrays.asList("Nechryael", "622", "3.4M"),
			all.subList(mobs + 1, mobs + 4));
		assertFalse(all.toString(), all.contains("Man"));
	}

	@Test
	public void lifetimeOnTheTrackersFacetIsTheOldStatsTab() throws Exception
	{
		ChroniclePanel p = panel(stub(true));
		set(p, "histFacet", "Trackers");
		set(p, "histGranularity", "Week");
		List<String> window = labels(history(p));
		assertTrue(window.toString(), window.contains("TRACKED PROGRESS"));
		assertFalse(window.toString(), window.contains("Ledger & Roads"));

		set(p, "histGranularity", "Lifetime");
		List<String> lifetime = labels(history(p));
		assertTrue("no family pills: " + lifetime, lifetime.contains("Ledger & Roads"));
		assertTrue(lifetime.toString(), lifetime.contains("Living"));
		assertFalse("Skilling is reached from the grid now: " + lifetime,
			lifetime.contains("Skilling"));
		assertFalse(lifetime.toString(), lifetime.contains("TRACKED PROGRESS"));
	}

	@Test
	public void aLifetimeIsTheTotalsAndNotADeltaFromTheFirstLine() throws Exception
	{
		LocalDate today = LocalDate.now();
		PanelPreviewTest.StubPlugin s = stub(true);
		HistoryLog.Baseline first = new HistoryLog.Baseline();
		first.skills.put("attack", 900_000L);
		s.history.put(today.minusDays(90), first);
		ChroniclePanel p = panel(s);
		set(p, "histFacet", "PvM");

		set(p, "histGranularity", "Week");
		assertEquals("the week is a delta", "+12",
			beside(headline(labels(history(p))), "Monsters slain"));

		set(p, "histGranularity", "Lifetime");
		assertEquals("the lifetime is the total", "+312",
			beside(headline(labels(history(p))), "Monsters slain"));
	}

	@Test
	public void aTabIconIsNeverWorthABlankTab() throws Exception
	{
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
			if (!openable.contains(craft))
			{
				orphans.add(craft);
			}
		}
		assertTrue("crafts no grid cell can open: " + orphans, orphans.isEmpty());

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

		LocalStore.LootWindow held = new LocalStore.LootWindow();
		held.loots = 4;
		held.value = 1_234;
		held.sources.add(new String[]{"Vorkath", "4", "1234"});
		st.lootWindow = held;
		List<String> some = labels(drops(p));
		assertTrue(some.toString(), some.contains("Vorkath"));
		assertTrue(some.toString(), some.contains("4 · 1,234 gp"));

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
		assertTrue(seen.get(1).toString(), seen.get(1).contains("Kills"));
	}

	@Test
	public void aFightPaidOutInAContainerStillShowsItsLoot() throws Exception
	{
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

		List<String> temp = bossHover(p, "Tempoross");
		assertTrue(temp.toString(), temp.contains("Reward pool"));
		assertTrue(temp.toString(), temp.contains("Casket"));
	}

	@Test
	public void aFightWhoseTakingsAreFiledUnderAnotherNameShowsThem() throws Exception
	{
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

		List<String> none = bossHover(p, "The Gauntlet");
		assertTrue(none.toString(), none.contains("none yet"));
	}

	@Test
	public void aBestTimeIsShownAsATime() throws Exception
	{
		PanelPreviewTest.StubPlugin st = stub(true);
		com.google.gson.JsonObject cl = st.clog != null ? st.clog
			: new com.google.gson.JsonObject();
		com.google.gson.JsonObject pages = new com.google.gson.JsonObject();
		com.google.gson.JsonObject temp = new com.google.gson.JsonObject();
		temp.addProperty("Personal Best", 226);
		pages.add("Tempoross", temp);
		com.google.gson.JsonObject gaunt = new com.google.gson.JsonObject();
		gaunt.addProperty("Personal Best", 535);
		gaunt.addProperty("Personal Best Corrupted", 791);
		pages.add("The Gauntlet", gaunt);
		cl.add("pb_lines", pages);
		st.clog = cl;

		ChroniclePanel p = panel(st);
		List<String> pool = bossHover(p, "Tempoross");
		assertTrue(pool.toString(), pool.contains("3:46"));

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
		PanelPreviewTest.StubPlugin st = stub(true);
		com.google.gson.JsonObject cl = st.clog != null ? st.clog
			: new com.google.gson.JsonObject();
		com.google.gson.JsonObject lines = new com.google.gson.JsonObject();
		com.google.gson.JsonObject todt = new com.google.gson.JsonObject();
		todt.addProperty("Rewards claimed", 1_078);
		lines.add("Wintertodt", todt);
		com.google.gson.JsonObject gauntlet = new com.google.gson.JsonObject();
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

		List<String> card = bossHover(p, "The Gauntlet");
		assertFalse("a best time is being read as a count: " + card,
			card.contains("Personal Best: 8"));
		assertFalse(card.toString(), card.contains("Personal Best Corrupted: 13"));
		assertTrue("the count the log holds is not shown: " + card,
			card.contains("Kills tracked") && card.contains("31"));
		assertFalse("the headline is repeated as a line: " + card,
			card.contains("Gauntlet completion count"));
		assertFalse("the corrupted count is on the plain Gauntlet's card: " + card,
			card.contains("Corrupted Gauntlet completion count"));

		List<String> corrupted = bossHover(p, "The Corrupted Gauntlet");
		assertTrue("the corrupted fight lost its own count: " + corrupted,
			corrupted.contains("Kills tracked") && corrupted.contains("1"));
		assertFalse("it took the whole page's counter: " + corrupted,
			corrupted.contains("55"));
	}

	@Test
	public void killsTheSpineCannotDateAreCountedFromWhatTheyDropped() throws Exception
	{
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

		assertTrue("the board did not declare the mixed source: " + week,
			week.toString().contains("counted from loot"));

		st.lootWindow = new LocalStore.LootWindow();
		List<String> bare = labels(kills(p));
		assertFalse("a ghost came back: " + bare, bare.contains("22"));
	}

	@Test
	public void theBossBoardCountsTheWindowAndNotTheLifetime() throws Exception
	{
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
		ChroniclePanel p = panel(stub(true));
		setView(p, "HOME");
		List<String> sitting = periodLabelsAsShown(p);
		assertTrue("the sitting does not name its scope: " + sitting,
			sitting.contains("This session"));
		assertFalse(sitting.toString(), sitting.contains("<"));
		assertFalse(sitting.toString(), sitting.contains(">"));

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
		PanelPreviewTest.StubPlugin st = stub(true);
		st.lifetime.put("logsChopped", 95L);
		st.lifetime.put("hitsBlocked", 12L);
		ChroniclePanel p = panel(st);
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
		assertTrue("skilling is missing: " + said, said.contains("Logs chopped"));
		assertTrue("combat is missing: " + said, said.contains("Hits blocked"));
		assertTrue("the board does not name its scope: " + said,
			said.contains("Reading"));

		setView(p, "HOME");
		assertFalse("the sitting's label leaked onto a drill: " + periodLabelsAsShown(p),
			periodLabelsAsShown(p).contains("This session"));
	}

	@Test
	public void aLootPageCanBeTakenAwayAsAPicture() throws Exception
	{
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
			assertNull("the mac path fired off a mac", payload);
			return;
		}
		assertNotNull("nothing was encoded to put on the clipboard", payload);
		java.awt.datatransfer.Transferable t = (java.awt.datatransfer.Transferable) payload;
		java.awt.datatransfer.DataFlavor[] fl = t.getTransferDataFlavors();
		assertEquals("the clipboard was offered more than the PNG", 1, fl.length);
		assertEquals("image/png", fl[0].getPrimaryType() + "/" + fl[0].getSubType());

		byte[] bytes = readAll((java.io.InputStream) t.getTransferData(fl[0]));
		byte[] magic = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a};
		for (int i = 0; i < magic.length; i++)
		{
			assertEquals("byte " + i + " is not PNG, it is "
				+ String.format("%02x", bytes[i]), magic[i], bytes[i]);
		}
		java.awt.image.BufferedImage back = javax.imageio.ImageIO.read(
			new java.io.ByteArrayInputStream(bytes));
		assertNotNull("the bytes do not decode as an image", back);
		assertEquals(120, back.getWidth());
		assertEquals(90, back.getHeight());
		assertTrue("this is not compressed, it is a bitmap: " + bytes.length,
			bytes.length < 120 * 90 * 4);
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
		assertFalse("tabs: " + tips, tips.contains("Hiscores"));
		assertFalse("tabs: " + tips, tips.contains("History"));
		assertFalse("Progression is no longer a destination: " + tips,
			tips.contains("Progression"));
		assertFalse("PvM and Skilling are one sheet: " + tips, tips.contains("PvM"));
		assertFalse("PvM and Skilling are one sheet: " + tips, tips.contains("Skilling"));
		assertFalse("the log is reached from the sheet now: " + tips,
			tips.contains("Collection log"));
	}

	@Test
	public void aLifetimeSaysWhereASkillStandsAndNotWhereItBegan() throws Exception
	{
		LocalDate today = LocalDate.now();
		ChroniclePanel p = panel(spanned(false, false));
		set(p, "histFacet", "Skills");

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
		set(p, "histCursor", LocalDate.now());
		assertEquals("0m", beside(labels(history(p)), "Time played"));
	}

	@Test
	public void aSpriteIsAskedForOnceAndNotOncePerBuild() throws Exception
	{
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
		int asked = s.spriteAsks.size();
		for (int i = 0; i < 6; i++)
		{
			history(p);
		}
		assertEquals("asked again: " + s.spriteAsks, asked, s.spriteAsks.size());

		s.kcs.put("Zulrah", 108L);
		s.kcs.put("Nechryael", 622L);
		set(p, "histFacet", "PvM");
		set(p, "histGranularity", "Lifetime");
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
		PanelPreviewTest.StubPlugin s = stub(true);
		s.itemManager = livingItems();
		Mockito.when(s.items().search("")).thenReturn(Arrays.asList(
			priced(23_962, "Crystal shard"), priced(23_957, "Crystal tool seed")));
		s.kcs.put("Zalcano", 2_024L);
		s.sources = Arrays.asList(
			new LocalStore.SourceRow("Zalcano", 2_024, 2_024, 81_900_000L, null, 0, 0, java.util.Collections.emptySet(), 0, 0));
		s.bags.put("Zalcano", Arrays.asList(
			new LocalStore.BagItem(0, "Crystal tool seed", 2, 46_173_662L),
			new LocalStore.BagItem(23_962, "Crystal shard", 4_340, 0L)));

		ChroniclePanel p = panel(s);
		set(p, "histGranularity", "Lifetime");
		set(p, "histFacet", "PvM");
		Method sig = ChroniclePanel.class.getDeclaredMethod("signatureItem", String.class);
		sig.setAccessible(true);
		assertEquals("the dearest row won, not the only one carrying an id",
			23_957, sig.invoke(p, "Zalcano"));

		for (int i = 0; i < 6; i++)
		{
			history(p);
		}
		Mockito.verify(s.items(), Mockito.times(1)).getImage(23_957, 1, false);
	}

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

		Mockito.when(s.items().search("")).thenReturn(Arrays.asList(
			priced(23_957, "Crystal tool seed")));
		assertEquals("the empty answer was kept", 23_957, sig.invoke(p, "Zalcano"));
	}

	@Test
	public void aLineWithNothingOfItsOwnWearsItsSkillThenItsFacet() throws Exception
	{
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

	@Test
	public void aSkillPastNinetyNineShowsTheLevelItHasActuallyReached() throws Exception
	{
		java.lang.reflect.Method real = PaceBook.class
			.getDeclaredMethod("levelAt", long.class);
		real.setAccessible(true);
		java.lang.reflect.Method virt = PaceBook.class
			.getDeclaredMethod("virtualLevelAt", long.class);
		virt.setAccessible(true);

		assertEquals(99, ((Integer) real.invoke(null, 13_034_431L)).intValue());
		assertEquals(99, ((Integer) virt.invoke(null, 13_034_431L)).intValue());
		assertEquals("the game stops at 99 and the pace line goes with it",
			99, ((Integer) real.invoke(null, 200_000_000L)).intValue());
		assertEquals("a maxed skill has reached 126", 126,
			((Integer) virt.invoke(null, 200_000_000L)).intValue());
		assertEquals(100, ((Integer) virt.invoke(null, 14_391_160L)).intValue());

		for (long xp : new long[]{0, 83, 1_154, 100_000, 5_000_000, 13_034_430L})
		{
			assertEquals("the curves part company below 99 at " + xp,
				real.invoke(null, xp), virt.invoke(null, xp));
		}
	}

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
