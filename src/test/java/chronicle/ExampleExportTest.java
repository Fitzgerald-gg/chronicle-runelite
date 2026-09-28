/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the BSD 2-Clause
 * License (see LICENSE) are met.
 */
package chronicle;

import com.google.gson.Gson;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import net.runelite.client.game.ItemManager;
import org.junit.BeforeClass;
import org.junit.Test;

public class ExampleExportTest
{
	private static final int STATE_BUDGET =
		Integer.getInteger("chronicle.exampleStates", 400);

	@BeforeClass
	public static void headless() throws Exception
	{
		System.setProperty("java.awt.headless", "true");
		try
		{
			javax.swing.UIManager.setLookAndFeel(
				new net.runelite.client.ui.laf.RuneLiteLAF());
		}
		catch (Throwable t)
		{
			javax.swing.UIManager.put("Panel.background",
				net.runelite.client.ui.ColorScheme.DARK_GRAY_COLOR);
			javax.swing.UIManager.put("ScrollPane.background",
				net.runelite.client.ui.ColorScheme.DARK_GRAY_COLOR);
		}
	}

	private static final String[] STATE_FIELDS = {
		"tab", "subByTab", "view",
		"histFacet", "histGranularity", "histCursor", "histFrom", "histTo",
		"detailSource", "detailItem", "detailSkill", "detailTask",
		"allTrackers", "showInfo", "showRecords", "showCalendar", "calendarMonth", "sheetPage", "slayerLens",
		"clogPageSel", "journalLens", "dropsLeftBehind",
		"dropsByKind", "lootKind", "lootTask", "onTaskOnly",
		"statsFamily", "leftBehindSource", "leftBehindItem", "clogTab",
		"openFolds", "histListShown", "drillShown", "journalShown",
		"dropsShown", "slayerShown",
	};

	private static final Map<String, String> NOT_STATE = notState();

	private static Map<String, String> notState()
	{
		Map<String, String> m = new LinkedHashMap<>();
		for (String n : new String[]{"movedKcs", "rolledKcs", "rollUsed", "kcByKind", "chatKcByKind", "killKinds", "movedTypes", "aboveBoard", "buildPeriodCounters", "periodCountersAsked", "bandFixes",
			"grindsCache", "journeyCache", "ledgerNames", "consumVals",
			"resourcesDropped", "buildSources", "buildClog", "buildSpan",
			"spanAsked", "historySpine", "historyFeed", "historyJourney",
			"historyDay", "historyFeedTs", "historyEpoch", "skilled",
			"itemsByName", "periodFrom", "periodTo", "searchFirst", "milestones", "landedSlots", "records", "daysPlayed", "daySkills", "crossedDays", "dayTotals", "searchFeed", "searchFeedTs", "searchFeedSpine",
			"taskItemsEver", "taskKillsEverCache", "periodTip", "measuredSince", "buildAchievements"})
		{
			m.put(n, "DERIVED: rebuilt from the record, and a restored copy would be stale");
		}
		for (String n : new String[]{"grindsFetching", "journeyFetching",
			"historyGathering"})
		{
			m.put(n, "DERIVED: whether a read is in flight, which the crawl never waits on");
		}
		for (String n : new String[]{"everShown", "staleWhileHidden", "keepScroll",
			"buildsRun", "lastPointer", "lastBuildNanos", "lastBuildAt"})
		{
			m.put(n, "PLUMBING: about drawing, not about where the reader is");
		}
		m.put("itemSourceCap", "CONSTANT: no click moves it, only a copy, which puts it back");
		m.put("drawingCopy", "PLUMBING: true only inside a copy, and put back before it returns");
		return m;
	}

	@Test
	public void everyPanelFieldIsClassified()
	{
		java.util.Set<String> carried = new java.util.LinkedHashSet<>(
			java.util.Arrays.asList(STATE_FIELDS));
		java.util.List<String> unclassified = new java.util.ArrayList<>();
		for (Field f : ChroniclePanel.class.getDeclaredFields())
		{
			int mod = f.getModifiers();
			if (java.lang.reflect.Modifier.isStatic(mod)
				|| java.lang.reflect.Modifier.isFinal(mod))
			{
				continue;
			}
			if (!carried.contains(f.getName()) && !NOT_STATE.containsKey(f.getName()))
			{
				unclassified.add(f.getName());
			}
		}
		org.junit.Assert.assertEquals(
			"a panel field is neither carried by the crawl nor written down as "
			+ "deliberately not carried, so the recording may not be able to reach "
			+ "the screens it governs: " + unclassified,
			java.util.Collections.emptyList(), unclassified);
	}

	@Test
	public void theTwoListsAgreeWithTheClass()
	{
		java.util.Set<String> declared = new java.util.LinkedHashSet<>();
		for (Field f : ChroniclePanel.class.getDeclaredFields())
		{
			declared.add(f.getName());
		}
		for (String n : STATE_FIELDS)
		{
			org.junit.Assert.assertTrue(
				"STATE_FIELDS names a field the panel no longer has: " + n,
				declared.contains(n));
			org.junit.Assert.assertFalse("named in both lists: " + n,
				NOT_STATE.containsKey(n));
		}
		for (String n : NOT_STATE.keySet())
		{
			org.junit.Assert.assertTrue(
				"NOT_STATE names a field the panel no longer has: " + n,
				declared.contains(n));
		}
	}

	@Test
	public void export() throws Exception
	{
		String outDir = System.getProperty("chronicle.exportExample");
		if (outDir == null)
		{
			return;
		}
		File out = new File(outDir);
		File icons = new File(out, "icons");
		icons.mkdirs();

		PanelPreviewTest.StubPlugin stub = exampleStub();
		dressWithRealIcons(stub);
		giveItASitting(stub);
		final ChroniclePanel[] holder = new ChroniclePanel[1];
		edt(() -> holder[0] = new ChroniclePanel(stub));
		ChroniclePanel panel = holder[0];
		awaitGather(panel);

		Crawl crawl = new Crawl(panel, stub, icons);
		crawl.run();

		Map<String, Object> doc = new LinkedHashMap<>();
		doc.put("start", crawl.start);
		doc.put("periods", crawl.periods);
		doc.put("views", crawl.views);
		doc.put("open", crawl.open);
		doc.put("search", crawl.corpus);
		doc.put("probes", crawl.probes);
		doc.put("nodes", crawl.pool.nodes);
		doc.put("states", crawl.states);
		Files.write(new File(out, "panel.json").toPath(),
			new Gson().toJson(doc).getBytes(StandardCharsets.UTF_8));
		System.out.println("example: " + crawl.states.size() + " states, "
			+ crawl.iconCount + " icons");
	}

	private final class Crawl
	{
		private final ChroniclePanel panel;
		private final PanelPreviewTest.StubPlugin plugin;
		private final File icons;
		final Map<String, Object> states = new LinkedHashMap<>();
		String start;
		int iconCount;
		private final Map<String, String> iconNames = new LinkedHashMap<>();
		private final Deque<String> queue = new ArrayDeque<>();
		private final Map<String, Map<String, Object>> snapshots = new LinkedHashMap<>();

		final Pool pool = new Pool();

		Crawl(ChroniclePanel panel, PanelPreviewTest.StubPlugin plugin, File icons)
		{
			this.panel = panel;
			this.plugin = plugin;
			this.icons = icons;
		}

		final Map<String, String> periods = new LinkedHashMap<>();
		final Map<String, String> views = new LinkedHashMap<>();
		final Map<String, Map<String, String>> open = new LinkedHashMap<>();
		final Map<String, Object> corpus = new LinkedHashMap<>();
		final Map<String, Object> probes = new LinkedHashMap<>();

		void run() throws Exception
		{
			start = stateKey();
			snapshots.put(start, snapshot());
			queue.add(start);
			seedPeriods();
			seedViews();
			seedDrills();
			seedBoards();
			gatherCorpus();
			while (!queue.isEmpty() && states.size() < STATE_BUDGET)
			{
				String key = queue.poll();
				if (states.containsKey(key))
				{
					continue;
				}
				restore(snapshots.get(key));
				states.put(key, draw(key));
			}
		}

		private void seedPeriods() throws Exception
		{
			Map<String, Object> home = snapshot();
			Field view = field("view");
			Object history = Enum.valueOf(
				(Class) Class.forName("chronicle.ChroniclePanel$View"), "HISTORY");
			for (String period : ChroniclePanel.PERIODS)
			{
				restore(home);
				view.set(panel, history);
				field("histGranularity").set(panel, period);
				field("histFrom").set(panel, null);
				field("histTo").set(panel, null);
				String key = stateKey();
				periods.put(period, key);
				if (!snapshots.containsKey(key))
				{
					snapshots.put(key, snapshot());
					queue.add(key);
				}
			}
			restore(home);
		}

		private void seedViews() throws Exception
		{
			Map<String, Object> home = snapshot();
			Class<?> viewType = Class.forName("chronicle.ChroniclePanel$View");
			Method applyTab = ChroniclePanel.class.getDeclaredMethod("applyTab", viewType);
			applyTab.setAccessible(true);
			for (Object v : viewType.getEnumConstants())
			{
				restore(home);
				final Object target = v;
				edt(() -> applyTab.invoke(panel, target));
				String key = stateKey();
				views.put(v.toString(), key);
				if (!snapshots.containsKey(key))
				{
					snapshots.put(key, snapshot());
					queue.add(key);
				}
			}
			restore(home);
		}

		@SuppressWarnings({"unchecked", "rawtypes"})
		private void openSub(String tabName, String subName) throws Exception
		{
			Class<?> tabType = Class.forName("chronicle.ChroniclePanel$Tab");
			Object target = Enum.valueOf((Class) tabType, tabName);
			if (subName != null)
			{
				Map<Object, String> subs = (Map<Object, String>) field("subByTab").get(panel);
				subs.put(target, subName);
			}
			Method apply = ChroniclePanel.class.getDeclaredMethod("applyTab", tabType);
			apply.setAccessible(true);
			edt(() -> apply.invoke(panel, target));
		}

		private String firstTaskName()
		{
			java.util.List<String> names = plugin.taskNames();
			return names.isEmpty() ? null : names.get(0);
		}

		private void seedBoards() throws Exception
		{
			Map<String, Object> home = snapshot();
			Class<?> viewType = Class.forName("chronicle.ChroniclePanel$View");
			Method applyTab = ChroniclePanel.class.getDeclaredMethod("applyTab", viewType);
			applyTab.setAccessible(true);

			for (String[] pair : new String[][]{
				{"RECORD", "Now"}, {"RECORD", "Journal"}, {"RECORD", "Ledger"}, {"RECORD", "Recap"},
				{"PVM", "Kills"}, {"PVM", "Loot"}, {"PVM", "Slayer"}, {"PVM", "Combat"},
				{"SKILLING", "Skills"}, {"SKILLING", "Activities"}, {"LOG", null}})
			{
				restore(home);
				openSub(pair[0], pair[1]);
				remember();
			}

			String[][] families = {
				{"Ledger & Roads", "Teleports"}, {"Living", "Food"}
			};
			for (String[] fam : families)
			{
				for (String fold : new String[]{null, fam[1]})
				{
					restore(home);
					final Object stats = Enum.valueOf((Class) viewType, "STATS");
					edt(() -> applyTab.invoke(panel, stats));
					field("statsFamily").set(panel, fam[0]);
					if (fold != null)
					{
						@SuppressWarnings("unchecked")
						java.util.Set<String> folds =
							(java.util.Set<String>) field("openFolds").get(panel);
						folds.add(fam[0] + ":" + fold);
					}
					remember();
				}
			}
			for (String facet : new String[]{"Skills", "Activities"})
			{
				restore(home);
				final Object hist = Enum.valueOf((Class) viewType, "HISTORY");
				edt(() -> applyTab.invoke(panel, hist));
				field("histFacet").set(panel, facet);
				remember();
			}
			for (String lens : new String[]{"Tasks", "Monsters", "Drops"})
			{
				restore(home);
				final Object sl = Enum.valueOf((Class) viewType, "SLAYER");
				edt(() -> applyTab.invoke(panel, sl));
				field("slayerLens").set(panel, lens);
				remember();
			}
			for (boolean left : new boolean[]{false, true})
			{
				restore(home);
				final Object dr = Enum.valueOf((Class) viewType, "DROPS");
				edt(() -> applyTab.invoke(panel, dr));
				field("dropsLeftBehind").set(panel, left);
				remember();
			}
			for (boolean onTask : new boolean[]{false, true})
			{
				for (String kind : new String[]{null, "Runes", "Everything else"})
				{
					restore(home);
					final Object dr = Enum.valueOf((Class) viewType, "DROPS");
					edt(() -> applyTab.invoke(panel, dr));
					field("dropsByKind").set(panel, true);
					field("onTaskOnly").set(panel, onTask);
					field("lootKind").set(panel, kind);
					remember();
				}
			}
			String task = firstTaskName();
			if (task != null)
			{
				restore(home);
				final Object sl = Enum.valueOf((Class) viewType, "SLAYER");
				edt(() -> applyTab.invoke(panel, sl));
				field("slayerLens").set(panel, "Drops");
				field("lootTask").set(panel, task);
				remember();
			}
			for (String src : new String[]{"Dust devil", "Vorkath"})
			{
				restore(home);
				@SuppressWarnings("unchecked")
				java.util.Set<String> folds =
					(java.util.Set<String>) field("openFolds").get(panel);
				folds.add("kcsrc:" + src);
				field("detailSource").set(panel, src);
				remember();
			}
			for (String item : new String[]{"Fire rune", "Coins"})
			{
				restore(home);
				field("detailItem").set(panel, item);
				field("onTaskOnly").set(panel, true);
				remember();
			}
			restore(home);
			field("showInfo").set(panel, true);
			remember();
			restore(home);
			restore(home);
			field("detailTask").set(panel, 0);
			remember();

			restore(home);
			Method openAll = ChroniclePanel.class.getDeclaredMethod("openAllTrackers");
			openAll.setAccessible(true);
			edt(() -> openAll.invoke(panel));
			remember();

			restore(home);
			Method openSkill = ChroniclePanel.class.getDeclaredMethod("openSkill", String.class);
			openSkill.setAccessible(true);
			edt(() -> openSkill.invoke(panel, "Slayer"));
			remember();

			restore(home);
		}

		private void seedDrills() throws Exception
		{
			Map<String, Object> home = snapshot();
			Method openSource = ChroniclePanel.class.getDeclaredMethod("openSource", String.class);
			Method openItem = ChroniclePanel.class.getDeclaredMethod("openItem", String.class);
			openSource.setAccessible(true);
			openItem.setAccessible(true);

			Map<String, String> sources = new LinkedHashMap<>();
			java.util.Set<String> itemNames = new java.util.LinkedHashSet<>();
			for (LocalStore.SourceRow r : plugin.dropSources())
			{
				restore(home);
				edt(() -> openSource.invoke(panel, r.name));
				sources.put(r.name, remember());
				for (LocalStore.BagItem b : plugin.sourceItems(r.name))
				{
					itemNames.add(b.name);
				}
			}
			Map<String, String> items = new LinkedHashMap<>();
			for (String name : itemNames)
			{
				restore(home);
				edt(() -> openItem.invoke(panel, name));
				items.put(name, remember());
			}
			open.put("source", sources);
			open.put("item", items);
			restore(home);
		}

		private void gatherCorpus() throws Exception
		{
			List<Object> trackers = new ArrayList<>();
			for (Map.Entry<String, Long> e : plugin.lifetimeCounters().entrySet())
			{
				if (e.getValue() != 0 && !chronicle.panel.StatRegistry.hidden(e.getKey()))
				{
					trackers.add(java.util.Arrays.asList(e.getKey(),
						chronicle.panel.StatRegistry.label(e.getKey()), e.getValue(),
						chronicle.panel.StatRegistry.isGp(e.getKey()) ? 1 : 0));
				}
			}
			corpus.put("trackers", trackers);

			Map<String, long[]> agg = new LinkedHashMap<>();
			Map<String, List<String>> from = new LinkedHashMap<>();
			List<Object> sources = new ArrayList<>();
			for (LocalStore.SourceRow src : plugin.dropSources())
			{
				sources.add(java.util.Arrays.asList(src.name, src.kc, src.value));
				for (LocalStore.BagItem b : plugin.sourceItems(src.name))
				{
					long[] a = agg.computeIfAbsent(b.name, k -> new long[2]);
					a[0] += b.qty;
					a[1] += b.value;
					from.computeIfAbsent(b.name, k -> new ArrayList<>())
						.add(src.name + (b.qty > 1 ? " \u00d7" + fmtNum(b.qty) : ""));
				}
			}
			List<Object> items = new ArrayList<>();
			for (Map.Entry<String, long[]> e : agg.entrySet())
			{
				items.add(java.util.Arrays.asList(e.getKey(), e.getValue()[0], e.getValue()[1],
					from.get(e.getKey())));
			}
			corpus.put("items", items);
			corpus.put("sources", sources);
			List<Object> rows = new ArrayList<>();
			for (LocalStore.SourceRow src : plugin.dropSources())
			{
				for (LocalStore.BagItem b : plugin.sourceItems(src.name))
				{
					rows.add(java.util.Arrays.asList(b.name, b.value));
				}
			}
			corpus.put("rows", rows);

			Method obtained = ChroniclePanel.class.getDeclaredMethod("obtained",
				com.google.gson.JsonObject.class);
			Method slotHeld = ChroniclePanel.class.getDeclaredMethod("slotHeld", String.class,
				String.class, List.class, Class.forName("chronicle.ChroniclePanel$Obtained"),
				java.util.Set.class);
			Method sharedNames = ChroniclePanel.class.getDeclaredMethod(
				"sharedSlotNames", Gson.class);
			sharedNames.setAccessible(true);
			java.util.Set<String> shared =
				(java.util.Set<String>) sharedNames.invoke(null, plugin.gson());
			Method taxonomy = ChroniclePanel.class.getDeclaredMethod("taxonomy", Gson.class);
			obtained.setAccessible(true);
			slotHeld.setAccessible(true);
			taxonomy.setAccessible(true);
			Object ob = obtained.invoke(null, plugin.clogSnapshot());
			Map<String, Map<String, List<String>>> tax =
				(Map<String, Map<String, List<String>>>) taxonomy.invoke(null, plugin.gson());
			List<Object> slots = new ArrayList<>();
			java.util.Set<String> seenSlot = new java.util.LinkedHashSet<>();
			for (Map<String, List<String>> tab : tax.values())
			{
				for (Map.Entry<String, List<String>> page : tab.entrySet())
				{
					for (String slot : page.getValue())
					{
						if (seenSlot.add(slot))
						{
							boolean got = (Boolean) slotHeld.invoke(null, slot, page.getKey(),
								page.getValue(), ob, shared);
							slots.add(java.util.Arrays.asList(slot, page.getKey(), got ? 1 : 0));
						}
					}
				}
			}
			corpus.put("slots", slots);

			Method feedLine = ChroniclePanel.class.getDeclaredMethod("feedLine",
				com.google.gson.JsonObject.class);
			Method stamp = ChroniclePanel.class.getDeclaredMethod("stamp",
				com.google.gson.JsonObject.class);
			feedLine.setAccessible(true);
			stamp.setAccessible(true);
			List<Object> feed = new ArrayList<>();
			for (com.google.gson.JsonObject e : plugin.feedNewest(500))
			{
				feed.add(java.util.Arrays.asList(feedLine.invoke(null, e), stamp.invoke(null, e)));
			}
			corpus.put("feed", feed);

			java.util.Set<String> taskKinds = new java.util.LinkedHashSet<>();
			for (LocalStore.BagItem b : plugin.onTaskLoot(
				Long.MIN_VALUE / 2, Long.MAX_VALUE / 2, null, true))
			{
				String k = ItemKinds.kindOf(b.name);
				taskKinds.add(k == null ? "Everything else" : k);
			}
			corpus.put("taskKinds", new java.util.ArrayList<>(taskKinds));

			probeSearch();
		}

		private void probeSearch() throws Exception
		{
			Method buildSearch = ChroniclePanel.class.getDeclaredMethod("buildSearch",
				String.class);
			buildSearch.setAccessible(true);
			List<String> queries = new ArrayList<>(java.util.Arrays.asList(
				"a", "e", "rune", "dragon", "whip", "bones", "zulrah", "abyssal", "herb",
				"teleport", "slayer", "pet", "level", "clue", "coins", "seed", "kraken",
				"qqqzzz", "Rune", "RUNE", " rune ", "chaos", "blood", "vial", "shark", "log"));
			for (Object list : new Object[]{corpus.get("trackers"), corpus.get("items"),
				corpus.get("sources"), corpus.get("slots"), corpus.get("feed")})
			{
				List<Object> rows = (List<Object>) list;
				for (int i = 0; i < rows.size(); i += Math.max(1, rows.size() / 6))
				{
					String first = String.valueOf(((List<Object>) rows.get(i)).get(0));
					if (first.length() > 3)
					{
						queries.add(first.substring(0, Math.min(6, first.length())));
					}
				}
			}
			Map<String, Object> out = new LinkedHashMap<>();
			Map<String, Object> home = snapshot();
			for (String raw : queries)
			{
				String q = raw.trim();
				restore(home);
				final JPanel[] drawn = new JPanel[1];
				edt(() ->
				{
					try
					{
						drawn[0] = (JPanel) buildSearch.invoke(panel, q);
					}
					catch (Exception e)
					{
						throw new RuntimeException(e);
					}
				});
				Map<String, Object> tree = node(drawn[0], new ArrayList<>(), new ArrayList<>());
				Map<String, Object> one = new LinkedHashMap<>();
				one.put("root", pool.intern(tree));
				one.put("enter", pressEnter(q, home));
				out.put(q, one);
			}
			restore(home);
			probes.putAll(out);
		}

		private String pressEnter(String q, Map<String, Object> home) throws Exception
		{
			restore(home);
			Field fieldRef = ChroniclePanel.class.getDeclaredField("searchField");
			fieldRef.setAccessible(true);
			Object box = fieldRef.get(panel);
			Field inner = box.getClass().getDeclaredField("textField");
			inner.setAccessible(true);
			Object flat = inner.get(box);
			edt(() ->
			{
				Method setText = flat.getClass().getMethod("setText", String.class);
				setText.invoke(flat, q);
				Method getTextField = flat.getClass().getMethod("getTextField");
				javax.swing.JTextField jt = (javax.swing.JTextField) getTextField.invoke(flat);
				jt.postActionEvent();
			});
			String landed = remember();
			restore(home);
			edt(() ->
			{
				Method setText = flat.getClass().getMethod("setText", String.class);
				setText.invoke(flat, "");
			});
			return landed;
		}

		private String remember() throws Exception
		{
			String key = stateKey();
			if (!snapshots.containsKey(key))
			{
				snapshots.put(key, snapshot());
				queue.add(key);
			}
			return key;
		}

		private Map<String, Object> draw(String key) throws Exception
		{
			JPanel body = build();
			List<int[]> clicks = new ArrayList<>();
			Map<String, Object> tree = node(body, new ArrayList<>(), clicks);

			Map<String, String> edges = new LinkedHashMap<>();
			for (int[] path : clicks)
			{
				restore(snapshots.get(key));
				JPanel fresh = build();
				Component target = at(fresh, path);
				if (target == null)
				{
					continue;
				}
				if (!click(target))
				{
					continue;
				}
				String landed = stateKey();
				if (landed.equals(key))
				{
					continue;
				}
				edges.put(join(path), landed);
				if (!snapshots.containsKey(landed))
				{
					snapshots.put(landed, snapshot());
					queue.add(landed);
				}
			}
			restore(snapshots.get(key));

			Map<String, Object> state = new LinkedHashMap<>();
			state.put("root", pool.intern(tree));
			state.put("go", edges);
			return state;
		}

		private JPanel build() throws Exception
		{
			final JPanel[] made = new JPanel[1];
			edt(() ->
			{
				Method rebuild = ChroniclePanel.class.getDeclaredMethod("rebuild");
				rebuild.setAccessible(true);
				rebuild.invoke(panel);
				made[0] = panel;
			});
			return made[0];
		}

		private Map<String, Object> node(Component c, List<Integer> path, List<int[]> clicks)
			throws Exception
		{
			Map<String, Object> n = new LinkedHashMap<>();
			if (c instanceof javax.swing.JScrollPane)
			{
				javax.swing.JScrollPane pane = (javax.swing.JScrollPane) c;
				javax.swing.JViewport port = pane.getViewport();
				Component view = port == null ? null : port.getView();
				if (view != null)
				{
					return node(view, path, clicks);
				}
			}
			if (c instanceof JLabel)
			{
				JLabel l = (JLabel) c;
				if (l.getText() != null && !l.getText().isEmpty())
				{
					n.put("t", l.getText());
				}
				n.put("fg", hex(l.getForeground()));
				n.put("f", fontKey(l));
				if (l.getIcon() != null)
				{
					n.put("ic", icon(l.getIcon()));
				}
				if (l.getHorizontalAlignment() == JLabel.CENTER)
				{
					n.put("mid", true);
				}
				java.awt.Dimension want = l.getPreferredSize();
				if (want != null && want.width > 0)
				{
					n.put("w", want.width);
				}
			}
			if (c.isOpaque() && c.getBackground() != null)
			{
				n.put("bg", hex(c.getBackground()));
			}
			if (c instanceof javax.swing.JComponent)
			{
				javax.swing.border.Border b = ((javax.swing.JComponent) c).getBorder();
				java.awt.Insets in = b == null ? null : b.getBorderInsets(c);
				if (in != null && (in.top | in.left | in.bottom | in.right) != 0)
				{
					n.put("p", new int[]{in.top, in.right, in.bottom, in.left});
				}
			}
			if (c instanceof javax.swing.text.JTextComponent)
			{
				n.put("field", true);
			}
			if (c.getMouseListeners().length > 0)
			{
				n.put("go", true);
				int[] copy = new int[path.size()];
				for (int i = 0; i < copy.length; i++)
				{
					copy[i] = path.get(i);
				}
				clicks.add(copy);
			}
			if (c instanceof Container && ((Container) c).getComponentCount() > 0)
			{
				Container box = (Container) c;
				n.put("l", layout(box));
				java.awt.Dimension pref = c.getPreferredSize();
				if (box.getLayout() == null && pref != null)
				{
					n.put("h", pref.height);
				}
				List<Object> kids = new ArrayList<>();
				int shown = 0;
				for (int i = 0; i < box.getComponentCount(); i++)
				{
					Component k = box.getComponent(i);
					if (!k.isVisible())
					{
						continue;
					}
					path.add(shown++);
					Map<String, Object> kid = node(k, path, clicks);
					Object where = constraint(box, k);
					if (where != null)
					{
						kid.put("at", where);
					}
					kids.add(kid);
					path.remove(path.size() - 1);
				}
				n.put("c", kids);
			}
			else if (!n.containsKey("t") && !n.containsKey("ic"))
			{
				java.awt.Dimension pref = c.getPreferredSize();
				n.put("gap", pref == null ? 0 : pref.height);
			}
			return n;
		}

		private String icon(Icon ic) throws Exception
		{
			BufferedImage img = new BufferedImage(Math.max(1, ic.getIconWidth()),
				Math.max(1, ic.getIconHeight()), BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = img.createGraphics();
			ic.paintIcon(null, g, 0, 0);
			g.dispose();
			java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
			ImageIO.write(img, "png", bytes);
			String hash = sha(bytes.toByteArray()).substring(0, 12);
			if (!iconNames.containsKey(hash))
			{
				Files.write(new File(icons, hash + ".png").toPath(), bytes.toByteArray());
				iconNames.put(hash, hash);
				iconCount++;
			}
			return hash;
		}

		private String stateKey() throws Exception
		{
			StringBuilder b = new StringBuilder();
			for (String name : STATE_FIELDS)
			{
				Object v = get(name);
				b.append(name).append('=').append(v).append(';');
			}
			return sha(b.toString().getBytes(StandardCharsets.UTF_8)).substring(0, 12);
		}

		private Map<String, Object> snapshot() throws Exception
		{
			Map<String, Object> out = new LinkedHashMap<>();
			for (String name : STATE_FIELDS)
			{
				Object v = get(name);
				if (v instanceof java.util.Set)
				{
					v = new java.util.LinkedHashSet<>((java.util.Set<?>) v);
				}
				else if (v instanceof Map)
				{
					v = new LinkedHashMap<>((Map<?, ?>) v);
				}
				out.put(name, v);
			}
			return out;
		}

		private void restore(Map<String, Object> snap) throws Exception
		{
			for (Map.Entry<String, Object> e : snap.entrySet())
			{
				Field f = field(e.getKey());
				if (f == null)
				{
					continue;
				}
				Object v = e.getValue();
				if (v instanceof java.util.Set)
				{
					java.util.Set<Object> live = (java.util.Set<Object>) f.get(panel);
					live.clear();
					live.addAll((java.util.Set<Object>) v);
				}
				else if (v instanceof Map)
				{
					Map<Object, Object> live = (Map<Object, Object>) f.get(panel);
					live.clear();
					live.putAll((Map<Object, Object>) v);
				}
				else
				{
					f.set(panel, v);
				}
			}
		}

		private Object get(String name) throws Exception
		{
			Field f = field(name);
			return f == null ? null : f.get(panel);
		}
	}

	private static final class Pool
	{
		final List<Object> nodes = new ArrayList<>();
		private final Map<String, Integer> byContent = new LinkedHashMap<>();
		private final Gson gson = new Gson();

		int intern(Map<String, Object> node)
		{
			List<Object> kids = (List<Object>) node.get("c");
			if (kids != null)
			{
				List<Object> asIndexes = new ArrayList<>(kids.size());
				for (Object kid : kids)
				{
					asIndexes.add(intern((Map<String, Object>) kid));
				}
				node.put("c", asIndexes);
			}
			String content = gson.toJson(node);
			Integer known = byContent.get(content);
			if (known != null)
			{
				return known;
			}
			int at = nodes.size();
			nodes.add(node);
			byContent.put(content, at);
			return at;
		}
	}

	private static Field field(String name)
	{
		try
		{
			Field f = ChroniclePanel.class.getDeclaredField(name);
			f.setAccessible(true);
			return f;
		}
		catch (NoSuchFieldException e)
		{
			return null;
		}
	}

	private static String layout(Container c)
	{
		java.awt.LayoutManager m = c.getLayout();
		if (m instanceof BorderLayout)
		{
			return "border";
		}
		if (m instanceof GridLayout)
		{
			GridLayout g = (GridLayout) m;
			return "grid:" + g.getRows() + ":" + g.getColumns()
				+ ":" + g.getHgap() + ":" + g.getVgap();
		}
		if (m instanceof BoxLayout)
		{
			return ((BoxLayout) m).getAxis() == BoxLayout.X_AXIS ? "rowbox" : "col";
		}
		return m == null ? "free" : "col";
	}

	private static Object constraint(Container parent, Component child)
	{
		if (!(parent.getLayout() instanceof BorderLayout))
		{
			return null;
		}
		BorderLayout b = (BorderLayout) parent.getLayout();
		for (String where : new String[]{BorderLayout.CENTER, BorderLayout.WEST,
			BorderLayout.EAST, BorderLayout.NORTH, BorderLayout.SOUTH})
		{
			if (b.getLayoutComponent(where) == child)
			{
				return where.substring(0, 1);
			}
		}
		return null;
	}

	private static String fontKey(JLabel l)
	{
		java.awt.Font f = l.getFont();
		if (f == null)
		{
			return "n";
		}
		String name = f.getName().toLowerCase(java.util.Locale.ROOT);
		if (name.contains("small"))
		{
			return "s";
		}
		return f.isBold() ? "b" : "n";
	}

	private static Component at(Component root, int[] path)
	{
		Component c = unwrap(root);
		for (int want : path)
		{
			if (!(c instanceof Container))
			{
				return null;
			}
			Container box = (Container) c;
			Component found = null;
			int shown = 0;
			for (int i = 0; i < box.getComponentCount(); i++)
			{
				Component k = box.getComponent(i);
				if (!k.isVisible())
				{
					continue;
				}
				if (shown++ == want)
				{
					found = k;
					break;
				}
			}
			if (found == null)
			{
				return null;
			}
			c = unwrap(found);
		}
		return c;
	}

	private static Component unwrap(Component c)
	{
		while (c instanceof javax.swing.JScrollPane)
		{
			javax.swing.JViewport port = ((javax.swing.JScrollPane) c).getViewport();
			Component view = port == null ? null : port.getView();
			if (view == null)
			{
				return c;
			}
			c = view;
		}
		return c;
	}

	private static String join(int[] path)
	{
		StringBuilder b = new StringBuilder();
		for (int i : path)
		{
			if (b.length() > 0)
			{
				b.append('.');
			}
			b.append(i);
		}
		return b.toString();
	}

	private static boolean click(Component c) throws Exception
	{
		final boolean[] ok = {true};
		edt(() ->
		{
			MouseEvent e = new MouseEvent(c, MouseEvent.MOUSE_PRESSED,
				System.currentTimeMillis(), 0, 1, 1, 1, false);
			for (MouseListener l : c.getMouseListeners())
			{
				try
				{
					l.mousePressed(e);
				}
				catch (Throwable t)
				{
					ok[0] = false;
				}
			}
		});
		return ok[0];
	}

	private static String fmtNum(long n)
	{
		return String.format(java.util.Locale.UK, "%,d", n);
	}

	private static String hex(java.awt.Color c)
	{
		return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
	}

	private static String sha(byte[] b) throws Exception
	{
		StringBuilder out = new StringBuilder();
		for (byte x : MessageDigest.getInstance("SHA-256").digest(b))
		{
			out.append(String.format("%02x", x));
		}
		return out.toString();
	}

	private interface Run
	{
		void run() throws Exception;
	}

	private static void edt(Run r) throws Exception
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

	private static void awaitGather(ChroniclePanel panel) throws Exception
	{
		Method m = ChroniclePanel.class.getDeclaredMethod("gatherHistory");
		m.setAccessible(true);
		edt(() ->
		{
			try
			{
				m.invoke(panel);
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		Thread.sleep(5000);
	}

	private static void giveItASitting(PanelPreviewTest.StubPlugin stub)
	{
		if (stub.history == null || stub.history.size() < 2)
		{
			return;
		}
		List<java.time.LocalDate> days = new ArrayList<>(stub.history.keySet());
		Map<String, Long> before = stub.history.get(days.get(days.size() - 2)).counters;
		Map<String, Long> after = stub.history.get(days.get(days.size() - 1)).counters;
		for (Map.Entry<String, Long> e : after.entrySet())
		{
			long moved = e.getValue() - before.getOrDefault(e.getKey(), e.getValue());
			if (moved > 0 && moved < Integer.MAX_VALUE)
			{
				stub.session.put(e.getKey(), (int) moved);
			}
		}
		Integer loots = stub.session.get("dropsReceived");
		stub.sessionLoots = loots == null ? 0 : loots;
		Integer worth = stub.session.get("lootValue");
		stub.sessionLootValue = worth == null ? 0 : worth;
	}

	private static void dressWithRealIcons(PanelPreviewTest.StubPlugin stub) throws Exception
	{
		String dir = System.getProperty("chronicle.exampleIcons");
		if (dir == null)
		{
			return;
		}
		File icons = new File(dir);
		net.runelite.client.callback.ClientThread ct =
			org.mockito.Mockito.mock(net.runelite.client.callback.ClientThread.class);
		org.mockito.Mockito.doAnswer(inv ->
		{
			((Runnable) inv.getArgument(0)).run();
			return null;
		}).when(ct).invokeLater(org.mockito.Mockito.any(Runnable.class));

		ItemManager im = org.mockito.Mockito.mock(ItemManager.class);
		Map<Integer, net.runelite.client.util.AsyncBufferedImage> drawn = new LinkedHashMap<>();
		org.mockito.Mockito.when(im.getImage(org.mockito.Mockito.anyInt(),
			org.mockito.Mockito.anyInt(), org.mockito.Mockito.anyBoolean()))
			.thenAnswer(inv -> drawn.computeIfAbsent(inv.getArgument(0), id ->
			{
				File f = new File(icons, "item-" + id + ".png");
				try
				{
					BufferedImage src = f.exists() ? ImageIO.read(f) : null;
					if (src == null)
					{
						return null;
					}
					net.runelite.client.util.AsyncBufferedImage img =
						new net.runelite.client.util.AsyncBufferedImage(ct,
							src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
					Graphics2D g = img.createGraphics();
					g.drawImage(src, 0, 0, null);
					g.dispose();
					img.loaded();
					return img;
				}
				catch (Exception e)
				{
					return null;
				}
			}));
		List<net.runelite.http.api.item.ItemPrice> prices = new ArrayList<>();
		String map = System.getProperty("chronicle.exampleItemNames");
		if (map != null && new File(map).exists())
		{
			com.google.gson.JsonArray arr = new com.google.gson.JsonParser()
				.parse(new String(Files.readAllBytes(new File(map).toPath()),
					StandardCharsets.UTF_8)).getAsJsonArray();
			for (com.google.gson.JsonElement e : arr)
			{
				net.runelite.http.api.item.ItemPrice pr =
					new net.runelite.http.api.item.ItemPrice();
				pr.setId(e.getAsJsonObject().get("id").getAsInt());
				pr.setName(e.getAsJsonObject().get("name").getAsString());
				prices.add(pr);
			}
		}
		org.mockito.Mockito.when(im.search(org.mockito.Mockito.anyString()))
			.thenAnswer(inv ->
			{
				String q = ((String) inv.getArgument(0)).toLowerCase(java.util.Locale.ROOT);
				List<net.runelite.http.api.item.ItemPrice> hit = new ArrayList<>();
				for (net.runelite.http.api.item.ItemPrice pr : prices)
				{
					if (pr.getName().toLowerCase(java.util.Locale.ROOT).contains(q))
					{
						hit.add(pr);
					}
				}
				return hit;
			});
		stub.itemManager = im;

		String spriteDir = System.getProperty("chronicle.exampleSprites");
		File sprites = spriteDir == null ? null : new File(spriteDir);
		net.runelite.client.game.SpriteManager sm =
			org.mockito.Mockito.mock(net.runelite.client.game.SpriteManager.class);
		org.mockito.Mockito.doAnswer(inv ->
		{
			int id = inv.getArgument(0);
			int frame = inv.getArgument(1);
			File f = sprites == null ? null
				: new File(sprites, "sprite-" + id + "-" + frame + ".png");
			if (f == null || !f.isFile())
			{
				f = new File(icons, "sprite-" + id + ".png");
			}
			BufferedImage img = f.isFile() ? ImageIO.read(f) : null;
			if (img != null)
			{
				((java.util.function.Consumer<BufferedImage>) inv.getArgument(2)).accept(img);
			}
			return null;
		}).when(sm).getSpriteAsync(org.mockito.Mockito.anyInt(), org.mockito.Mockito.anyInt(),
			org.mockito.Mockito.any(java.util.function.Consumer.class));
		stub.spriteManager = sm;

		net.runelite.client.game.SkillIconManager skills =
			org.mockito.Mockito.mock(net.runelite.client.game.SkillIconManager.class);
		org.mockito.Mockito.when(skills.getSkillImage(org.mockito.Mockito.any(),
			org.mockito.Mockito.anyBoolean())).thenAnswer(inv ->
			{
				String name = ((net.runelite.api.Skill) inv.getArgument(0))
					.name().toLowerCase(java.util.Locale.ROOT);
				java.io.InputStream in = ExampleExportTest.class
					.getResourceAsStream("/skill_icons_small/" + name + ".png");
				return in == null ? null : ImageIO.read(in);
			});
		stub.skillIconManager = skills;
	}

	private static PanelPreviewTest.StubPlugin exampleStub() throws Exception
	{
		String dir = System.getProperty("chronicle.exampleJournal");
		Method m = PanelPreviewTest.class.getDeclaredMethod("journalStub", String.class,
			String.class);
		m.setAccessible(true);
		return (PanelPreviewTest.StubPlugin) m.invoke(null, dir, "Example");
	}
}
