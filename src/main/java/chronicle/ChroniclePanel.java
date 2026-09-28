/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the
 * BSD 2-Clause License (see LICENSE) are met.
 */
package chronicle;

import chronicle.HistoryLog.Baseline;
import chronicle.LocalStore.BagItem;
import chronicle.LocalStore.SlayerJourney;
import chronicle.LocalStore.SlayerTask;
import chronicle.LocalStore.SourceRow;
import chronicle.LocalStore.UntakenRow;
import chronicle.counters.ExperienceStatTracker;
import chronicle.counters.StatKeys;
import chronicle.panel.HistoryProgress;
import chronicle.panel.StatRegistry;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GridBagConstraints;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import java.util.function.LongFunction;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.api.Skill;
import net.runelite.client.hiscore.HiscoreSkill;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.IconTextField;
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.ui.components.materialtabs.MaterialTabGroup;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.OSType;
import net.runelite.http.api.item.ItemPrice;
import static chronicle.LocalStore.kindOf;
import static chronicle.panel.StatRegistry.prettify;

class ChroniclePanel extends PluginPanel
{
	private static final DateTimeFormatter DAY =
		DateTimeFormatter.ofPattern("d MMM", Locale.UK).withZone(ZoneId.systemDefault());
	private static final DateTimeFormatter CLOCK =
		DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());
	private static final DateTimeFormatter TASK_DAY =
		DateTimeFormatter.ofPattern("d MMM yy", Locale.UK).withZone(ZoneId.systemDefault());
	private static final DateTimeFormatter FULL_DAY =
		DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK).withZone(ZoneId.systemDefault());
	private static final DateTimeFormatter MONTH_YEAR =
		DateTimeFormatter.ofPattern("MMMM yyyy", Locale.UK).withZone(ZoneId.systemDefault());
	private static final Color DARK = ColorScheme.DARK_GRAY_COLOR;
	private static final Color DARKER = ColorScheme.DARKER_GRAY_COLOR;
	private static final Color ACCENT_LIFETIME = ColorScheme.BRAND_ORANGE;
	private static final Color ACCENT_SESSION = new Color(85, 163, 90);
	private static final Color ACCENT_RED = new Color(196, 84, 74);

	private static final Color TILE_LIT = new Color(198, 198, 198);
	private static final int ROW_CAP = 30;
	private static final int PANEL_INSET = 8;
	private static final int CARD_INSET = 8;
	private static final int ROW_INSET = 2;
	private static final int ROW_GAP = 8;

	private enum View
	{
		HOME, DROPS, SLAYER, LOG, STATS, HISTORY, JOURNAL, KILLS, SHEET, RECAP
	}

	private enum Tab
	{
		RECORD, STANDING, LOOT, TRACKERS
	}

	private static final Map<Tab, String[]> SUBS = new EnumMap<>(Tab.class);

	static
	{
		SUBS.put(Tab.RECORD, new String[]{"Now", "Journal", "Ledger", "Recap"});
		SUBS.put(Tab.STANDING, new String[0]);
		SUBS.put(Tab.LOOT, new String[]{"Loot", "Slayer"});
		SUBS.put(Tab.TRACKERS, new String[0]);
	}

	private final ChroniclePlugin plugin;

	private final JPanel display = new JPanel(new BorderLayout());

	private final ScrollColumn canvas = new ScrollColumn();
	private final JScrollPane scrollPane = new JScrollPane(canvas,
		ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
		ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
	private final MaterialTabGroup tabGroup = new MaterialTabGroup();
	private final Map<Tab, String> subByTab = new EnumMap<>(Tab.class);
	private final IconTextField searchField = new IconTextField();
	private final Timer searchDebounce;
	private final Timer homeTicker;

	private View view = View.HOME;
	private Tab tab = Tab.RECORD;
	private String detailItem;
	private String detailSource;
	private String detailSkill;
	private boolean allTrackers;
	private boolean showInfo;
	private boolean showRecords;
	private boolean showCalendar;
	private YearMonth calendarMonth = YearMonth.now();
	private final java.util.ArrayDeque<String[]> detailStack = new java.util.ArrayDeque<>();
	private String statsFamily = StatRegistry.FAMILIES[0];
	private int dropsShown = ROW_CAP;
	private String clogTab = "Bosses";
	private String clogPageSel;

	private final JPanel band = new JPanel(new BorderLayout());
	private final JLabel bandText = new JLabel();
	private String histGranularity = "Lifetime";
	private LocalDate histCursor = LocalDate.now();
	private LocalDate histFrom;
	private LocalDate histTo;
	private static Map<String, Map<String, List<String>>> taxonomy;

	ChroniclePanel(ChroniclePlugin plugin)
	{
		super(false);
		this.plugin = plugin;

		watchForReturn();
		setLayout(new BorderLayout());
		setBorder(pad(
			PANEL_INSET, PANEL_INSET, PANEL_INSET, PANEL_INSET));
		setBackground(DARK);

		north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
		north.setBackground(DARK);

		searchField.setIcon(IconTextField.Icon.SEARCH);
		searchField.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 16, 28));
		searchField.setBackground(DARKER);
		searchField.setHoverBackgroundColor(ColorScheme.DARK_GRAY_HOVER_COLOR);
		searchDebounce = new Timer(150, e -> onSearchChanged());
		searchDebounce.setRepeats(false);
		searchField.addActionListener(e ->
		{
			if (searchQuery().isEmpty())
			{
				return;
			}
			if (searchDebounce.isRunning())
			{
				searchDebounce.stop();
				onSearchChanged();
			}
			if (searchFirst != null)
			{
				searchFirst.run();
			}
		});
		searchField.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				searchDebounce.restart();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				searchDebounce.restart();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				searchDebounce.restart();
			}
		});
		periodHolder.setBackground(DARK);
		periodHolder.setAlignmentX(Component.CENTER_ALIGNMENT);
		periodHolder.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
		periodHolder.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 16, 22));
		spaced(north, periodHolder, 3);

		tabGroup.setLayout(new GridLayout(1, 4, 2, 0));
		addTab("tab_record.png", "Record", Tab.RECORD);
		addTab("tab_standing.png", "Standing", Tab.STANDING);
		addTab("tab_loot.png", "Loot", Tab.LOOT);
		addTab("tab_trackers.png", "Trackers", Tab.TRACKERS);
		spaced(north, tabGroup, 7);
		spaced(north, searchField, 8);

		add(north, BorderLayout.NORTH);
		add(display, BorderLayout.CENTER);

		canvas.setBackground(DARK);
		scrollPane.setBorder(null);
		scrollPane.getVerticalScrollBar().setUnitIncrement(14);
		overlayBar(scrollPane);
		display.add(scrollPane, BorderLayout.CENTER);

		homeTicker = new Timer(3000, e ->
		{
			if (staleWhileHidden && everShown && getWrappedPanel().isShowing()
				&& !popupShowing())
			{
				staleWhileHidden = false;
				update();
				return;
			}
			if (showingSitting())
			{
				update();
			}
		});
		homeTicker.start();
		javax.swing.ToolTipManager.sharedInstance().setInitialDelay(220);
		javax.swing.ToolTipManager.sharedInstance().setDismissDelay(20_000);

		band.setAlignmentX(Component.CENTER_ALIGNMENT);
		band.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
		band.setBorder(pad(3, 8, 3, 8));
		bandText.setFont(small());
		band.add(bandText, BorderLayout.CENTER);
		band.setVisible(false);
		north.add(band);

		gatherHistory();
		rebuild();
	}

	private static ImageIcon tabIcon(String name)
	{
		return new ImageIcon(ImageUtil.loadImageResource(ChroniclePanel.class, name));
	}

	private void addTab(String icon, String tooltip, Tab target)
	{
		MaterialTab mt = new MaterialTab(tabIcon(icon), tabGroup, new JPanel());
		mt.setToolTipText(tooltip);
		mt.setOnSelectEvent(() ->
		{
			applyTab(target);
			return true;
		});
		tabGroup.addTab(mt);
		if (target == Tab.RECORD)
		{
			tabGroup.select(mt);
		}
	}

	private JPanel subStrip()
	{
		String[] subs = SUBS.get(tab);
		if (subs == null || subs.length == 0 || !searchQuery().isEmpty()
			|| detailItem != null || detailSource != null || detailSkill != null
			|| allTrackers || showRecords || showCalendar || detailTask >= 0
			|| leftBehindSource != null || leftBehindItem != null)
		{
			return null;
		}
		JPanel strip = new JPanel(new GridLayout(1, subs.length, 3, 3));
		strip.setBackground(DARK);
		String on = sub();
		for (String name : subs)
		{
			strip.add(pill(name, name.equals(on), 4, null, () ->
			{
				subByTab.put(tab, name);
				applyCommon();
			}));
		}
		return strip;
	}

	private JLabel pill(String name, boolean on, int side, String tip, Runnable pick)
	{
		JLabel pill = new JLabel(name, JLabel.CENTER);
		pill.setOpaque(true);
		pill.setBorder(pad(2, side, 2, side));
		pill.setFont(small());
		pill.setBackground(DARKER);
		pill.setForeground(on ? accent() : dim());
		if (tip != null)
		{
			pill.setToolTipText(tip);
		}
		link(pill, pick);
		return pill;
	}

	private String sub()
	{
		String[] subs = SUBS.get(tab);
		if (subs == null || subs.length == 0)
		{
			return "";
		}
		String chosen = subByTab.get(tab);
		for (String s : subs)
		{
			if (s.equals(chosen))
			{
				return s;
			}
		}
		return subs[0];
	}

	private View viewOf()
	{
		switch (tab)
		{
			case STANDING:
				return View.SHEET;
			case LOOT:
				return "Slayer".equals(sub()) ? View.SLAYER : View.DROPS;
			case TRACKERS:
				return View.STATS;
			case RECORD:
			default:
				switch (sub())
				{
					case "Journal":
						return View.JOURNAL;
					case "Ledger":
						return View.STATS;
					case "Recap":
						return View.RECAP;
					case "Now":
					default:
						return View.HOME;
				}
		}
	}

	private Tab tabFor(View v)
	{
		switch (v)
		{
			case DROPS:
			case SLAYER:
				return Tab.LOOT;
			case KILLS:
			case SHEET:
			case HISTORY:
			case LOG:
				return Tab.STANDING;
			case STATS:
				return Tab.TRACKERS;
			case JOURNAL:
			case HOME:
			default:
				return Tab.RECORD;
		}
	}

	private String subFor(View v)
	{
		switch (v)
		{
			case KILLS:
			case SHEET:
			case HISTORY:
			case LOG:
			case STATS:
				return "";
			case DROPS:
				return "Loot";
			case SLAYER:
				return "Slayer";
			case JOURNAL:
				return "Journal";
			case RECAP:
				return "Recap";
			case HOME:
			default:
				return "Now";
		}
	}

	private void applyTab(Tab target)
	{
		tab = target;
		applyCommon();
	}

	private void applyTab(View target)
	{
		tab = tabFor(target);
		subByTab.put(tab, subFor(target));
		applyCommon();
	}

	private void applyCommon()
	{
		view = viewOf();
		sheetPage = null;
		if (tab == Tab.STANDING)
		{
			histFacet = "Skills";
		}
		else if (tab == Tab.TRACKERS)
		{
			statsFamily = StatRegistry.FAMILIES[0];
		}
		else if (tab == Tab.RECORD && "Ledger".equals(sub())
			&& !"Ledger & Roads".equals(statsFamily) && !"Living".equals(statsFamily))
		{
			statsFamily = "Ledger & Roads";
		}
		dropsShown = ROW_CAP;
		slayerShown = ROW_CAP;
		lootKind = null;
		lootTask = null;
		drillShown.clear();
		histListShown.clear();
		detailItem = null;
		detailSource = null;
		detailSkill = null;
		leaveSentPage();
		detailTask = -1;
		leftBehindSource = null;
		leftBehindItem = null;
		detailStack.clear();
		clearSearch();
		rebuild();
	}

	private static final class Boss
	{
		final String name;
		final int sprite;

		Boss(String name, int sprite)
		{
			this.name = name;
			this.sprite = sprite;
		}
	}

	private static List<Boss> bossRoster;
	private Map<String, Long> movedKcs;
	private Map<String, Long> rolledKcs;
	private boolean rollUsed;

	private static synchronized List<Boss> bossRoster(Gson gson)
	{
		if (bossRoster != null)
		{
			return bossRoster;
		}
		List<Boss> out = new ArrayList<>();
		try
		{
			for (HiscoreSkill s
				: HiscoreSkill.values())
			{
				if (s.getType() == net.runelite.client.hiscore.HiscoreSkillType.BOSS)
				{
					out.add(new Boss(s.getName(), s.getSpriteId()));
				}
			}
		}
		catch (RuntimeException | LinkageError ex)
		{
			out.clear();
		}
		if (!out.isEmpty())
		{
			bossRoster = out;
			return out;
		}
		try (java.io.InputStream in = ChroniclePanel.class.getResourceAsStream("osrs_bosses.json"))
		{
			if (in != null)
			{
				JsonArray arr = gson.fromJson(
					new InputStreamReader(in, StandardCharsets.UTF_8),
					JsonArray.class);
				for (JsonElement e : arr)
				{
					JsonObject o = e.getAsJsonObject();
					out.add(new Boss(o.get("name").getAsString(),
						o.has("sprite") ? o.get("sprite").getAsInt() : -1));
				}
			}
		}
		catch (Exception ex)
		{
		}
		bossRoster = out;
		return out;
	}

	private static final JsonObject FIGHTS = table("panel_fights.json");

	private static final Map<String, String> LOG_PAGE_FOR = strMap(FIGHTS, "logPage");
	private static final Map<String, List<String>> PAYS_OUT = new LinkedHashMap<>();
	private static final Map<String, List<String>> FOUGHT_AS = new LinkedHashMap<>();

	static
	{
		obj(FIGHTS, "paysOut").entrySet().forEach(e -> PAYS_OUT.put(e.getKey(), strs(e.getValue())));
		obj(FIGHTS, "foughtAs").entrySet().forEach(e ->
			FOUGHT_AS.put(kindOf(e.getKey()), strs(e.getValue())));
	}

	private static JsonObject table(String name)
	{
		try (InputStreamReader in = new InputStreamReader(ChroniclePanel.class.getResourceAsStream(
			name), StandardCharsets.UTF_8))
		{
			return new com.google.gson.JsonParser().parse(in).getAsJsonObject();
		}
		catch (Exception ex)
		{
			return new JsonObject();
		}
	}

	private static List<String> strs(JsonElement a)
	{
		List<String> out = new ArrayList<>();
		if (a != null)
		{
			a.getAsJsonArray().forEach(n -> out.add(n.getAsString()));
		}
		return out;
	}

	private static Map<String, String> strMap(JsonObject t, String key)
	{
		Map<String, String> out = new LinkedHashMap<>();
		obj(t, key).entrySet().forEach(e -> out.put(e.getKey(), e.getValue().getAsString()));
		return out;
	}

	private Map<String, Long> kcByKind;

	private Map<String, Long> kcByKind()
	{
		if (kcByKind != null)
		{
			return kcByKind;
		}
		Map<String, Long> out = new LinkedHashMap<>();
		for (Entry<String, Long> e : plugin.killCounts().entrySet())
		{
			out.merge(LocalStore.chatKind(e.getKey()), e.getValue(), Math::max);
		}
		for (SourceRow r : sources())
		{
			out.merge(kindOf(r.name), (long) r.kc, Math::max);
		}
		kcByKind = out;
		return out;
	}

	private long bossKills(String name)
	{
		JsonObject cl = clogNow();
		long best = Math.max(0, lookup(cl, "slayer_kcs", name));
		String kind = kindOf(name);
		Long byKind = kcByKind().get(kind);
		if (byKind != null)
		{
			best = Math.max(best, byKind);
		}
		if (best > 0)
		{
			return best;
		}
		for (Entry<String, Long> ln : pageLines(name, "kc_lines"))
		{
			String said = low(ln.getKey());
			if (said.contains("kill") || said.contains("completion"))
			{
				return ln.getValue();
			}
		}
		String page = LOG_PAGE_FOR.getOrDefault(name, name);
		return Math.max(0, lookup(cl, "kcs", page));
	}

	private static long lookup(JsonObject clog, String map, String key)
	{
		if (clog == null)
		{
			return -1;
		}
		JsonElement v = getIgnoreCase(obj(clog, map), key);
		return v == null ? -1 : safeLong(v);
	}

	private static JsonElement getIgnoreCase(JsonObject o, String key)
	{
		JsonElement v = o.get(key);
		if (v != null)
		{
			return v;
		}
		for (Entry<String, JsonElement> e : o.entrySet())
		{
			if (e.getKey().equalsIgnoreCase(key))
			{
				return e.getValue();
			}
		}
		return null;
	}

	private long bossKillsInWindow(String name)
	{
		if (wholeRecord())
		{
			return bossKills(name);
		}
		if (sessionPeriod())
		{
			rollUsed = true;
			Long rolled = rolledKills(name);
			return rolled == null ? 0 : rolled;
		}
		Span s = span();
		if (s == null)
		{
			return -1;
		}
		if (movedKcs == null)
		{
			movedKcs = HistoryLog.gained(s.opening.kcs, s.earliest.kcs,
				closingNow(s.closing.kcs, plugin.killCounts()));
		}
		Long moved = movedKcs.get(name);
		if (moved == null)
		{
			for (Entry<String, Long> e : movedKcs.entrySet())
			{
				if (e.getKey().equalsIgnoreCase(name))
				{
					moved = e.getValue();
					break;
				}
			}
		}
		if (moved != null)
		{
			return Math.max(0, moved);
		}
		Long rolled = rolledKills(name);
		if (rolled != null)
		{
			rollUsed = true;
			return rolled;
		}
		return -1;
	}

	private Long rolledKills(String name)
	{
		if (plugin.lootRollFrom() <= 0)
		{
			return null;
		}
		if (rolledKcs == null)
		{
			rolledKcs = new LinkedHashMap<>();
			for (String[] r : lootWindow().sources)
			{
				long n = safeParse(r[1]);
				if (n > 0)
				{
					rolledKcs.put(kindOf(r[0]), n);
				}
			}
		}
		return rolledKcs.get(kindOf(name));
	}

	private LocalDate rollShortOf()
	{
		long from = plugin.lootRollFrom();
		if (from <= 0)
		{
			return null;
		}
		Window w = window();
		LocalDate began = dayOf(from);
		return began.isAfter(w.start) ? began : null;
	}

	private List<Entry<String, Long>> logLines(String boss)
	{
		List<Entry<String, Long>> out = pageLines(boss, "kc_lines");
		long kills = bossKills(boss);
		out.removeIf(ln ->
		{
			String said = low(ln.getKey());
			return ln.getValue() == kills
				&& (said.contains("kill") || said.contains("completion"));
		});
		return out;
	}

	private String tabStanding(JsonObject cl, String tab)
	{
		if (cl == null)
		{
			return null;
		}
		JsonObject counts = obj(cl, "cat_counts");
		String key = low(tab);
		long total = counts.has(key + "_total") ? safeLong(counts.get(key + "_total")) : 0;
		if (total <= 0)
		{
			return null;
		}
		long got = counts.has(key + "_obtained")
			? safeLong(counts.get(key + "_obtained")) : 0;
		return tip(tab, new String[]{"Obtained", "Available", "Share"},
			new String[]{fmt(got), fmt(total),
				Math.round(got * 1000.0 / total) / 10.0 + "%"});
	}

	private static Map<String, Long> pageCounts(JsonObject cl)
	{
		Map<String, Long> out = new LinkedHashMap<>();
		if (cl == null)
		{
			return out;
		}
		Set<String> lined = new HashSet<>();
		for (String pageName : obj(cl, "kc_lines").keySet())
		{
			lined.add(low(pageName));
		}
		for (Entry<String, JsonElement> e
			: obj(cl, "kcs").entrySet())
		{
			String key = low(e.getKey());
			if (!lined.contains(key))
			{
				out.merge(key, safeLong(e.getValue()), Math::max);
			}
		}
		for (Entry<String, Long> e : LocalStore.pageKillLines(cl).entrySet())
		{
			out.put(low(e.getKey()), e.getValue());
		}
		return out;
	}

	private static String pageHeaderTip(JsonObject cl, String page)
	{
		if (cl == null)
		{
			return null;
		}
		JsonElement found = getIgnoreCase(obj(cl, "kc_lines"), page);
		if (found == null || !found.isJsonObject() || found.getAsJsonObject().size() == 0)
		{
			return null;
		}
		List<String> labels = new ArrayList<>();
		List<String> figures = new ArrayList<>();
		for (Entry<String, JsonElement> ln
			: found.getAsJsonObject().entrySet())
		{
			labels.add(ln.getKey());
			figures.add(fmt(safeLong(ln.getValue())));
		}
		return tip(page, labels.toArray(new String[0]), figures.toArray(new String[0]));
	}

	private List<Entry<String, Long>> pageLines(String boss, String map)
	{
		List<Entry<String, Long>> out = new ArrayList<>();
		JsonObject cl = clogNow();
		if (cl == null)
		{
			return out;
		}
		JsonElement found = getIgnoreCase(obj(cl, map), LOG_PAGE_FOR.getOrDefault(boss, boss));
		if (found == null || !found.isJsonObject())
		{
			return out;
		}
		for (Entry<String, JsonElement> ln
			: found.getAsJsonObject().entrySet())
		{
			long n = safeLong(ln.getValue());
			if (n > 0 && lineBelongsTo(boss, ln.getKey()))
			{
				out.add(new AbstractMap.SimpleEntry<>(ln.getKey(), n));
			}
		}
		return out;
	}

	private boolean isKillSource(String name)
	{
		return killKinds().contains(kindOf(name))
			|| taskKillsEver().containsKey(name);
	}

	private Set<String> killKinds;

	private Set<String> killKinds()
	{
		if (killKinds != null)
		{
			return killKinds;
		}
		Set<String> out = new HashSet<>();
		for (Boss b : bossRoster(plugin.gson()))
		{
			out.add(kindOf(b.name));
		}
		JsonObject cl = clogNow();
		if (cl != null)
		{
			for (String said : obj(cl, "slayer_kcs").keySet())
			{
				out.add(kindOf(said));
			}
		}
		killKinds = out;
		return out;
	}

	private static boolean namesInBrackets(String source, String boss)
	{
		int open = source.lastIndexOf('(');
		int close = source.lastIndexOf(')');
		return open > 0 && close > open
			&& kindOf(source.substring(open + 1, close))
				.equals(kindOf(boss));
	}

	private static String beforeBracket(String source)
	{
		int open = source.lastIndexOf('(');
		return open > 0 ? source.substring(0, open).trim() : source;
	}

	private static String clock(long seconds)
	{
		long h = seconds / 3600;
		long m = (seconds % 3600) / 60;
		long s = seconds % 60;
		return h > 0 ? String.format(Locale.UK, "%d:%02d:%02d", h, m, s)
			: String.format(Locale.UK, "%d:%02d", m, s);
	}

	private boolean lineBelongsTo(String boss, String label)
	{
		if (label == null || label.isEmpty())
		{
			return false;
		}
		if (Character.isDigit(label.charAt(label.length() - 1)))
		{
			return false;
		}
		String said = low(label);
		String mine = bare(boss);
		String best = null;
		for (Boss b : bossRoster(plugin.gson()))
		{
			String name = bare(b.name);
			if (!name.isEmpty() && said.contains(name)
				&& (best == null || name.length() > best.length()))
			{
				best = name;
			}
		}
		if (best != null)
		{
			return best.equals(mine);
		}
		String page = LOG_PAGE_FOR.getOrDefault(boss, boss);
		if (!page.equalsIgnoreCase(boss))
		{
			return namesOneOf(said, words(mine, bare(page)));
		}
		for (Boss other : bossRoster(plugin.gson()))
		{
			String onPage = LOG_PAGE_FOR.getOrDefault(other.name, other.name);
			if (other.name.equalsIgnoreCase(boss) || !onPage.equalsIgnoreCase(page))
			{
				continue;
			}
			if (namesOneOf(said, words(bare(other.name), mine)))
			{
				return false;
			}
		}
		return true;
	}

	private static List<String> words(String name, String against)
	{
		List<String> out = new ArrayList<>();
		for (String w : name.split("\\s+"))
		{
			if (w.length() > 3 && !against.contains(w))
			{
				out.add(w);
			}
		}
		return out;
	}

	private static boolean namesOneOf(String said, List<String> words)
	{
		for (String w : words)
		{
			if (said.contains(w))
			{
				return true;
			}
		}
		return false;
	}

	private static String bare(String name)
	{
		String n = name == null ? "" : low(name.trim());
		return n.startsWith("the ") ? n.substring(4) : n;
	}

	private JPanel buildSheet()
	{
		JPanel p = column();
		String was = histFacet;
		try
		{
			histFacet = "Skills";
			p.add(buildHistory());
		}
		finally
		{
			histFacet = was;
		}
		p.add(activitySheet());
		p.add(buildKills());
		return p;
	}

	private static final String[][] ACTIVITIES = {
		{"Clues", "", "clues"},
		{"Rifts closed", "Guardians of the Rift", ""},
		{"Soul Wars", "Soul Wars", ""},
		{"Collections", "", "log"},
		{"Quests", "", "quests"},
		{"Diaries", "", "diaries"},
	};

	private static int activitySprite(String label)
	{
		switch (label)
		{
			case "Clues":
				return HiscoreSkill.CLUE_SCROLL_ALL.getSpriteId();
			case "Rifts closed":
				return HiscoreSkill.RIFTS_CLOSED.getSpriteId();
			case "Soul Wars":
				return HiscoreSkill.SOUL_WARS_ZEAL.getSpriteId();
			case "Collections":
				return HiscoreSkill.COLLECTIONS_LOGGED.getSpriteId();
			case "Quests":
				return net.runelite.api.SpriteID.TAB_QUESTS;
			case "Diaries":
				return net.runelite.api.SpriteID.TAB_QUESTS_GREEN_ACHIEVEMENT_DIARIES;
			default:
				return 0;
		}
	}

	private static final String[] CLUE_TIERS = {
		"Beginner", "Easy", "Medium", "Hard", "Elite", "Master"};

	private JPanel activitySheet()
	{
		JPanel p = column();
		JPanel grid = grid3();
		for (String[] a : ACTIVITIES)
		{
			grid.add(activityCell(a[0], a[1], a[2]));
		}
		spaced(p, grid);
		return p;
	}

	private void openActivity(String source)
	{
		if (resolveSourceNamed(source) == null && openLogPage(source))
		{
			return;
		}
		openSourceLoose(source);
	}

	private boolean openLogPage(String page)
	{
		for (Entry<String, Map<String, List<String>>> tab : taxonomy(plugin.gson()).entrySet())
		{
			if (tab.getValue().containsKey(page))
			{
				applyTab(Tab.STANDING);
				sheetPage = "log";
				clogTab = tab.getKey();
				clogPageSel = page;
				rebuild();
				return true;
			}
		}
		return false;
	}

	private JPanel activityCell(String label, String source, String page)
	{
		JPanel cell = tile(3, 3);
		JLabel icon = new JLabel();
		icon.setPreferredSize(new Dimension(24, 24));
		long figure;
		String hover;
		if ("Clues".equals(label))
		{
			long[] each = new long[CLUE_TIERS.length];
			long[] worth = new long[CLUE_TIERS.length];
			long all = 0;
			long allWorth = 0;
			for (SourceRow r : sources())
			{
				for (int i = 0; i < CLUE_TIERS.length; i++)
				{
					if (r.name.equalsIgnoreCase("Clue Scroll (" + CLUE_TIERS[i] + ")"))
					{
						each[i] = Math.max(r.kc, r.loots);
						worth[i] = r.value;
						all += each[i];
						allWorth += r.value;
					}
				}
			}
			figure = all;
			String[] labels = new String[CLUE_TIERS.length + 1];
			String[] figures = new String[CLUE_TIERS.length + 1];
			labels[0] = "All";
			figures[0] = fmt(all) + tail(allWorth);
			for (int i = 0; i < CLUE_TIERS.length; i++)
			{
				labels[i + 1] = CLUE_TIERS[i];
				figures[i + 1] = each[i] == 0 ? "0"
					: fmt(each[i]) + tail(worth[i]);
			}
			hover = tip("Clues", labels, figures);
		}
		else if ("Collections".equals(label))
		{
			figure = plugin.clogFinished();
			int[] logStanding = clogStanding();
			hover = tip("Collection log",
				new String[]{"Obtained", "Available", "Share"},
				new String[]{fmt(figure),
					logStanding != null ? fmt(logStanding[1]) : "not yet",
					logStanding != null
						? Math.round(logStanding[0] * 1000.0 / logStanding[1]) / 10.0 + "%"
						: "-"});
		}
		else if ("Quests".equals(label))
		{
			JsonObject q = obj(achievements(), "quests");
			long done = 0;
			long started = 0;
			for (String k : q.keySet())
			{
				String state = q.get(k).getAsString();
				if ("FINISHED".equals(state))
				{
					done++;
				}
				else if ("IN_PROGRESS".equals(state))
				{
					started++;
				}
			}
			figure = done;
			hover = tip("Quests",
				new String[]{"Complete", "In progress", "Known"},
				new String[]{fmt(done), fmt(started), fmt(q.size())});
		}
		else if ("Diaries".equals(label))
		{
			long[] d = diaryStanding();
			figure = d[0];
			hover = tip("Achievement diaries",
				new String[]{"Tiers done", "Regions finished", "Regions"},
				new String[]{d[0] + " / " + d[1], fmt(d[2]), fmt(d[3])});
		}
		else
		{
			long named = namedLine(source, label);
			figure = named > 0 ? named : bossKills(source);
			hover = tip(label, new String[]{"Count"}, new String[]{fmt(figure)});
		}
		wearSprite(icon, activitySprite(label), ICON_W, ICON_H);
		cell.setToolTipText(hover);
		if (!page.isEmpty())
		{
			final String to = page;
			link(cell, () ->
			{
				sheetPage = to;
				rebuild();
			});
		}
		else if (!source.isEmpty())
		{
			link(cell, () -> openActivity(source));
		}
		cell.add(icon, BorderLayout.WEST);
		long moved = activityMoved(label, source);
		boolean lit = figure > 0 && moved != 0;
		JLabel fig = styled(new JLabel(figure > 0 ? fmt(figure) : "-", JLabel.RIGHT), small(),
			lit ? TILE_LIT : dim());
		JPanel text = new JPanel(new GridLayout(moved > 0 ? 2 : 1, 1));
		text.setBackground(DARKER);
		text.add(fig);
		if (moved > 0)
		{
			JLabel by = styled(new JLabel("+" + fmt(moved), JLabel.RIGHT), small(), accent());
			text.add(by);
		}
		cell.add(text, BorderLayout.CENTER);
		return cell;
	}

	private String notCounting(boolean kills)
	{
		TreeMap<LocalDate, Baseline> spine = historySpine;
		Window w = window();
		if (wholeRecord() || sessionPeriod() || spine == null || w == null)
		{
			return null;
		}
		for (Entry<LocalDate, Baseline> e : spine.entrySet())
		{
			if (!(kills ? e.getValue().kcs : e.getValue().counters).isEmpty())
			{
				return w.end.isBefore(e.getKey())
					? "The record keeps no " + (kills ? "kill counts" : "counters")
					+ " before " + e.getKey().format(FULL_DAY) + "."
					: null;
			}
		}
		return null;
	}

	private JPanel buildKills()
	{
		JPanel p = column();
		movedKcs = null;
		rolledKcs = null;
		rollUsed = false;
		List<Boss> roster = bossRoster(plugin.gson());
		if (roster.isEmpty())
		{
			return noted(p, "The boss roster did not load.");
		}
		if (!wholeRecord() && !sessionPeriod() && span() == null)
		{
			p.add(noPeriod());
			return p;
		}
		if (!wholeRecord())
		{
			List<Boss> had = new ArrayList<>();
			for (Boss b : roster)
			{
				if (bossKillsInWindow(b.name) > 0)
				{
					had.add(b);
				}
			}
			if (had.isEmpty())
			{
				String unkept = notCounting(true);
				return noted(p, unkept != null ? unkept : "Nothing on the boss sheet was killed inside "
					+ periodInSentence() + ".");
			}
			roster = had;
		}
		JPanel opening = bossSheet(roster);
		LocalDate shortFrom = rollUsed ? rollShortOf() : null;
		if (shortFrom != null)
		{
			spaced(p, note("Kills the journal cannot date are counted from loot "
				+ "instead, which reaches back only to " + shortFrom.format(FULL_DAY)
				+ " and sees a kill only where it dropped something."), 4);
		}
		spaced(p, opening);
		return p;
	}

	private JPanel bossSheet(List<Boss> rows)
	{
		JPanel grid = grid3();
		for (Boss b : rows)
		{
			grid.add(bossCell(b));
		}
		return grid;
	}

	private JPanel bossCell(Boss b)
	{
		final long kc = bossKillsInWindow(b.name);
		JPanel cell = tile(3, 3);
		cell.setToolTipText(bossTip(b));

		JLabel icon = new JLabel();
		if (b.sprite > 0)
		{
			wearSprite(icon, b.sprite, 24, 24);
		}
		cell.add(icon, BorderLayout.WEST);

		JLabel fig = styled(new JLabel(kc > 0 ? fmt(kc) : "-", JLabel.RIGHT), small(),
			kc > 0 ? TILE_LIT : dim());
		cell.add(fig, BorderLayout.EAST);
		final String open = bossLootSource(b);
		link(cell, () -> openSourceLoose(open));
		return cell;
	}

	private String bossTip(Boss b)
	{
		List<String> labels = new ArrayList<>();
		List<String> figures = new ArrayList<>();
		String kind = kindOf(b.name);
		SourceRow src = null;
		List<SourceRow> paidOut = new ArrayList<>();
		for (SourceRow r : sources())
		{
			if (kindOf(r.name).equals(kind))
			{
				if (src == null)
				{
					src = r;
				}
				continue;
			}
			if (namesInBrackets(r.name, b.name)
				|| paysOutThrough(b.name, r.name))
			{
				paidOut.add(r);
			}
		}
		if (!wholeRecord())
		{
			long inWin = bossKillsInWindow(b.name);
			long[] paid = sourceInWindow(b.name);
			labels.add(window().label);
			figures.add((inWin < 0 ? "-" : count(inWin, "kill"))
				+ tail(paid[1]));
		}
		long known = bossKills(b.name);
		labels.add("Kills tracked");
		figures.add(known > 0 ? fmt(known) : src != null ? fmt(src.loots) : "-");
		for (Entry<String, Long> pb : pageLines(b.name, "pb_lines"))
		{
			labels.add(pb.getKey());
			figures.add(clock(pb.getValue()));
		}
		double[] timed = wholeRecord() && src != null ? new double[]{src.timed, src.timeSum}
			: sourceTimesInWindow(b.name);
		if (timed[0] > 0)
		{
			labels.add("Average kill");
			figures.add(pb(timed[1] / timed[0]) + " · " + fmt((long) timed[0]) + " timed");
		}
		long here = minutesAt(b.name, wholeRecord() ? counters() : periodCounters());
		if (here > 0 && minutesCoverPeriod())
		{
			labels.add("Time here");
			figures.add(hoursMinutes(here));
		}
		for (Entry<String, Long> ln : logLines(b.name))
		{
			labels.add(ln.getKey());
			figures.add(fmt(ln.getValue()));
		}
		if (src != null)
		{
			labels.add("Drops");
			figures.add(paidFigure(src));
		}
		for (SourceRow r : paidOut)
		{
			labels.add(beforeBracket(r.name));
			figures.add(paidFigure(r));
		}
		if (src == null && paidOut.isEmpty())
		{
			labels.add("Loot");
			figures.add("none yet");
		}
		return tip(b.name, labels.toArray(new String[0]),
			figures.toArray(new String[0]));
	}

	private String paidFigure(SourceRow r)
	{
		return fmt(tallyOf(plugin.sourceItems(r.name))[0]) + " \u00b7 " + gps(r.value);
	}

	private String bossLootSource(Boss b)
	{
		String kind = kindOf(b.name);
		for (SourceRow r : sources())
		{
			if (kindOf(r.name).equals(kind))
			{
				return r.name;
			}
		}
		SourceRow best = null;
		for (SourceRow r : sources())
		{
			if ((namesInBrackets(r.name, b.name) || paysOutThrough(b.name, r.name))
				&& (best == null || r.value > best.value))
			{
				best = r;
			}
		}
		return best != null ? best.name : b.name;
	}

	private static boolean paysOutThrough(String fight, String source)
	{
		for (String name : PAYS_OUT.getOrDefault(fight, Collections.emptyList()))
		{
			if (name.equalsIgnoreCase(source))
			{
				return true;
			}
		}
		return false;
	}

	private Color accent()
	{
		return ACCENT_LIFETIME;
	}

	private void clearSearch()
	{
		searchField.setText("");
		searchDebounce.stop();
	}

	private String searchQuery()
	{
		return searchField.getText() == null ? "" : searchField.getText().trim();
	}

	private Map<String, Long> counters()
	{
		return plugin.lifetimeCounters();
	}

	private final java.util.concurrent.atomic.AtomicBoolean queued =
		new java.util.concurrent.atomic.AtomicBoolean();

	private boolean everShown;

	private boolean staleWhileHidden;

	static final int MOVED_RECORD = 1;

	static final int MOVED_COUNTERS = 2;

	static final int MOVED_SKILLS = 4;

	static final int MOVED_CLOG = 8;

	private static final int MOVED_ANY = MOVED_RECORD | MOVED_COUNTERS
		| MOVED_SKILLS | MOVED_CLOG;

	private boolean viewCares(int moved)
	{
		if ((moved & MOVED_ANY) == 0)
		{
			return false;
		}
		switch (view)
		{
			case DROPS:
			case SLAYER:
			case JOURNAL:
				return (moved & MOVED_RECORD) != 0;
			case LOG:
			case KILLS:
				return (moved & (MOVED_RECORD | MOVED_CLOG)) != 0;
			case STATS:
				return (moved & (MOVED_COUNTERS | MOVED_RECORD)) != 0;
			case SHEET:
			case HISTORY:
			case HOME:
			default:
				return true;
		}
	}

	void update()
	{
		update(MOVED_ANY);
	}

	void update(int moved)
	{
		if (!queued.compareAndSet(false, true))
		{
			return;
		}
		SwingUtilities.invokeLater(() ->
		{
			queued.set(false);
			if (everShown && !getWrappedPanel().isShowing())
			{
				staleWhileHidden = true;
				return;
			}
			if (popupShowing() || scrollHeld() || beingRead())
			{
				staleWhileHidden = true;
				return;
			}
			if (!viewCares(moved))
			{
				return;
			}
			long floor = redrawFloorMs();
			if (floor > 0 && System.currentTimeMillis() - lastBuildAt < floor)
			{
				staleWhileHidden = true;
				return;
			}
			keepScroll = true;
			try
			{
				rebuild();
			}
			finally
			{
				keepScroll = false;
			}
		});
	}

	private Point lastPointer;

	private boolean beingRead()
	{
		Point was = lastPointer;
		Point now = null;
		try
		{
			java.awt.PointerInfo at = java.awt.MouseInfo.getPointerInfo();
			if (at != null)
			{
				now = at.getLocation();
				Point origin = getWrappedPanel().getLocationOnScreen();
				Rectangle over = new Rectangle(origin,
					getWrappedPanel().getSize());
				if (!over.contains(now))
				{
					now = null;
				}
			}
		}
		catch (RuntimeException e)
		{
			now = null;
		}
		lastPointer = now;
		return now != null && now.equals(was);
	}

	private boolean scrollHeld()
	{
		JScrollPane pane = scrollPane;
		return pane != null && pane.getVerticalScrollBar().getValueIsAdjusting();
	}

	private static boolean popupShowing()
	{
		javax.swing.MenuElement[] path = javax.swing.MenuSelectionManager
			.defaultManager().getSelectedPath();
		return path != null && path.length > 0;
	}

	private void watchForReturn()
	{
		getWrappedPanel().addHierarchyListener(e ->
		{
			if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) == 0
				|| !getWrappedPanel().isShowing())
			{
				return;
			}
			everShown = true;
			if (staleWhileHidden)
			{
				staleWhileHidden = false;
				update();
			}
		});
	}

	private boolean keepScroll;

	private final JPanel periodHolder = new JPanel(new BorderLayout());
	private final JPanel north = new JPanel();

	private long buildsRun;

	private void rebuild()
	{
		long began = System.nanoTime();
		try
		{
			rebuildNow();
		}
		finally
		{
			lastBuildNanos = System.nanoTime() - began;
			lastBuildAt = System.currentTimeMillis();
		}
	}

	private long lastBuildNanos;
	private long lastBuildAt;

	private long redrawFloorMs()
	{
		long ms = lastBuildNanos / 1_000_000L;
		return ms < 12 ? 0 : Math.min(2000L, ms * 60L);
	}

	private void rebuildNow()
	{
		buildsRun++;
		buildSources = null;
		buildClog = null;
		buildSpan = null;
		spanAsked = false;
		taskKillsEverCache = null;
		taskItemsEver = null;
		buildAchievements = null;
		milestones = null;
		landedSlots = null;
		records = null;
		daysPlayed = null;
		dayTotals = null;
		resourcesDropped = 0;
		kcByKind = null;
		chatKcByKind = null;
		killKinds = null;
		movedTypes = null;
		buildPeriodCounters = null;
		periodCountersAsked = false;
		movedKcs = null;
		rolledKcs = null;
		rollUsed = false;
		skilled = null;
		ledgerNames = null;
		sourceKinds.clear();
		facetWaiting.clear();
		itemWaiting.clear();
		paintBand(plugin.journalWarning(), plugin.captureWarning(),
			plugin.captureWarningWhy());
		if (aboveBoard != null)
		{
			display.remove(aboveBoard);
			aboveBoard = null;
		}
		measuredSince = null;
		periodHolder.removeAll();
		periodHolder.add(periodRow(), BorderLayout.CENTER);
		periodHolder.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 16, 22));
		periodHolder.invalidate();
		north.invalidate();
		north.revalidate();
		north.repaint();
		JPanel body = !searchQuery().isEmpty() ? buildSearch(searchQuery())
			: detailItem != null ? buildItemDetail(detailItem)
			: detailSource != null ? buildSourceDetail(detailSource)
			: detailSkill != null ? buildSkillDetail(detailSkill)
			: allTrackers ? buildAllTrackers()
			: showRecords ? buildRecords()
			: showCalendar ? buildCalendar()
			: showInfo ? buildInfo()
			: detailTask >= 0 ? buildTaskDetail(detailTask)
			: leftBehindSource != null || leftBehindItem != null ? buildLeftBehindDetail()
			: sheetPage != null ? buildSheetPage()
			: buildView();
		periodHolder.setToolTipText(measuredSince);
		unlight();
		canvas.removeAll();
		canvas.add(body, BorderLayout.NORTH);
		JPanel above = new JPanel();
		above.setLayout(new BoxLayout(above, BoxLayout.Y_AXIS));
		above.setBackground(DARK);
		JPanel subs = subStrip();
		if (subs != null)
		{
			spaced(above, subs);
		}
		if (above.getComponentCount() > 0)
		{
			aboveBoard = above;
			display.add(above, BorderLayout.NORTH);
		}
		canvas.revalidate();
		canvas.repaint();
		display.revalidate();
		display.repaint();
		if (!keepScroll)
		{
			scrollPane.getVerticalScrollBar().setValue(0);
		}
	}

	private JPanel buildView()
	{
		switch (view)
		{
			case SHEET:
				return buildSheet();
			case KILLS:
				return buildKills();
			case DROPS:
				return buildDrops();
			case SLAYER:
				return buildSlayer();
			case LOG:
				return buildLog();
			case STATS:
				return buildStats();
			case HISTORY:
				return buildHistory();
			case JOURNAL:
				return buildJournal();
			case RECAP:
				return buildRecap();
			case HOME:
			default:
				return buildHome();
		}
	}

	private JPanel aboveBoard;

	private void onSearchChanged()
	{
		drillShown.keySet().removeIf(k -> k.startsWith("search:"));
		rebuild();
	}

	private static final String[] HOME_PINNED = {
		"totalXpGained", "damageDealt", "consumedValue"
	};

	private static String homeLabel(String key)
	{
		switch (key)
		{
			case "totalXpGained":
				return "Xp gained";
			case "consumedValue":
				return "Consumed";
			default:
				return StatRegistry.label(key);
		}
	}

	private boolean bandFixes;

	private void paintBand(String stalled, String capture, String captureWhy)
	{
		if (stalled == null && capture == null)
		{
			band.setVisible(false);
			bandFixes = false;
			return;
		}
		boolean red = stalled != null;
		Color ink = red ? ColorScheme.PROGRESS_ERROR_COLOR : accent();
		bandFixes = !red;
		for (java.awt.event.MouseListener l : band.getMouseListeners())
		{
			band.removeMouseListener(l);
		}
		if (bandFixes)
		{
			band.addMouseListener(clicker(() ->
			{
				plugin.turnOnMissingCapture();
				update();
			}));
		}
		bandText.setText(red ? "Not saving the journal"
			: capture + "  \u00b7  click to turn it on");
		bandText.setForeground(ink);
		band.setBackground(wash(ink));
		band.setOpaque(true);
		band.setToolTipText(red ? stalled : captureWhy);
		band.setCursor(bandFixes
			? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
			: Cursor.getDefaultCursor());
		band.setVisible(true);
	}

	private static Color wash(Color c)
	{
		Color g = DARK;
		double a = 0.22;
		return new Color(
			(int) Math.round(g.getRed() + (c.getRed() - g.getRed()) * a),
			(int) Math.round(g.getGreen() + (c.getGreen() - g.getGreen()) * a),
			(int) Math.round(g.getBlue() + (c.getBlue() - g.getBlue()) * a));
	}

	private static long splitOf(Map<String, Integer> sess)
	{
		long n = 0;
		for (String k : DAMAGE_SPLIT)
		{
			n += sess.getOrDefault(k, 0);
		}
		return n;
	}

	private void foldHead(JPanel head, String fold, String tip)
	{
		JLabel name = part(head, BorderLayout.CENTER);
		if (foldOpen(fold))
		{
			name.setForeground(accent());
		}
		head.setToolTipText(tip);
		link(head, () -> toggleFold(fold));
	}

	private void addXpBySkill(JPanel strip)
	{
		List<ExperienceStatTracker.SkillGain> gains = plugin.sessionSkillXp();
		if (gains.isEmpty())
		{
			strip.add(ghostRow("no skill breakdown yet", ""));
			return;
		}
		for (ExperienceStatTracker.SkillGain g : gains)
		{
			String right = "+" + gp(g.xp)
				+ (g.perHour >= 0 ? " · " + gp(g.perHour) + "/h" : "");
			JPanel r = row(g.skill.getName(), right);
			r.setBorder(pad(1, 10, 1, 2));
			strip.add(r);
		}
	}

	private JPanel buildHome()
	{
		JPanel p = column();
		String stalled = plugin.journalWarning();
		if (stalled != null)
		{
			spaced(p, note(stalled));
		}

		ChronicleEventCapture.SlayerView task = plugin.slayerView();
		if (task != null && plugin.slayerSeenThisSession())
		{
			addTaskCard(p, task, "Slayer task", ACCENT_SESSION);
		}

		long began = plugin.sessionStart();
		long ran = plugin.sessionElapsedMinutes();
		JPanel strip = began > 0 && ran > 0
			? card("This session", "since " + CLOCK.format(Instant.ofEpochMilli(began))
				+ " · " + hoursMinutes(ran))
			: card("This session");
		Map<String, Integer> sess = plugin.sessionView();
		int mounted = 0;
		Set<String> shownKeys = new HashSet<>();
		for (String key : HOME_PINNED)
		{
			long v = sess.getOrDefault(key, 0);
			if (v > 0)
			{
				boolean isXp = "totalXpGained".equals(key);
				boolean isDamage = "damageDealt".equals(key) && splitOf(sess) > 0;
				JPanel r = row(homeLabel(key),
					StatRegistry.isGp(key) ? gps(v)
						: (isXp ? "+" + gp(v) : fmt(v)),
					ACCENT_SESSION);
				if (isXp)
				{
					foldHead(r, FOLD_HOME_XP, "Each skill's xp and xp per hour this session");
				}
				if (isDamage)
				{
					foldHead(r, FOLD_HOME_DAMAGE, "The damage this session, by style");
				}
				strip.add(r);
				if (isXp && foldOpen(FOLD_HOME_XP))
				{
					addXpBySkill(strip);
				}
				if (isDamage && foldOpen(FOLD_HOME_DAMAGE))
				{
					for (String split : DAMAGE_SPLIT)
					{
						long sv = sess.getOrDefault(split, 0);
						if (sv > 0)
						{
							strip.add(row(StatRegistry.label(split), fmt(sv)));
							mounted++;
						}
					}
				}
				shownKeys.add(key);
				mounted++;
			}
		}
		if (plugin.sessionLoots() > 0)
		{
			strip.add(row("Drops received",
				plugin.sessionLoots() + " · " + gps(plugin.sessionLootValue()),
				ACCENT_SESSION));
			mounted++;
			if (plugin.sessionUntakenKills() > 0)
			{
				strip.add(row("Drops taken",
					fmt(Math.max(0, plugin.sessionLoots() - plugin.sessionUntakenKills())),
					ACCENT_SESSION));
				mounted++;
			}
		}
		long[] untaken = plugin.sessionUntakenTally();
		if (untaken[0] > 0)
		{
			strip.add(row("Left behind", fmt(untaken[0]) + " · " + gps(untaken[1])));
			mounted++;
		}
		mounted += addSittingFeats(strip);
		mounted += addSessionMovers(strip, plugin.sessionDisplayCounters(), shownKeys);
		if (mounted == 0)
		{
			strip.add(row("A fresh page", ""));
		}
		spaced(p, strip);

		List<LocalStore.RecentDrop> recent = plugin.recentDrops();
		if (!recent.isEmpty())
		{
			JPanel card = card("Recent drops");
			JPanel grid = new JPanel(new GridLayout(0, 5, 3, 3));
			grid.setBackground(DARKER);
			grid.setAlignmentX(Component.LEFT_ALIGNMENT);
			int shown = 0;
			for (LocalStore.RecentDrop d : recent)
			{
				if (shown++ >= 10)
				{
					break;
				}
				grid.add(sprite(d.itemId, d.name, d.quantity));
			}
			card.add(grid);
			spaced(p, card);
		}

		return p;
	}

	private static String parentOf(String key, Map<String, Integer> sess)
	{
		if (StatRegistry.isFloor(key))
		{
			return null;
		}
		String sec = StatRegistry.subgroup(key);
		if (sec.isEmpty())
		{
			return null;
		}
		List<String> floors = StatRegistry.floorKeys(
			sec.equals("Destinations") ? "Teleports" : sec);
		for (String f : floors)
		{
			if (sess.getOrDefault(f, 0) > 0)
			{
				return f;
			}
		}
		return null;
	}

	private int addSittingFeats(JPanel strip)
	{
		long since = plugin.sessionStart();
		if (since <= 0)
		{
			return 0;
		}
		Map<String, Long> levels = new LinkedHashMap<>();
		List<String> slots = new ArrayList<>();
		List<String> pets = new ArrayList<>();
		for (JsonObject e : plugin.feedNewest(FEED_SCAN_DEEP))
		{
			if (safeLong(e.get("ts")) < since)
			{
				continue;
			}
			JsonObject d = obj(e, "data");
			switch (typeOf(e))
			{
				case "LEVEL":
					if (has(d, "skill") && has(d, "level"))
					{
						levels.merge(prettify(
							low(d.get("skill").getAsString())),
							safeLong(d.get("level")), Math::max);
					}
					break;
				case "COLLECTION":
					if (has(d, "itemName"))
					{
						slots.add(d.get("itemName").getAsString());
					}
					break;
				case "PET":
					if (has(d, "petName"))
					{
						pets.add(d.get("petName").getAsString());
					}
					break;
				default:
					break;
			}
		}
		int mounted = 0;
		if (!levels.isEmpty())
		{
			List<String> said = new ArrayList<>();
			for (Entry<String, Long> l : levels.entrySet())
			{
				said.add(l.getKey() + " " + l.getValue());
			}
			strip.add(namedRow("Levels", said, ACCENT_SESSION));
			mounted++;
		}
		if (!slots.isEmpty())
		{
			strip.add(namedRow(slots.size() == 1 ? "Log slot" : "Log slots", slots, ACCENT_SESSION));
			mounted++;
		}
		if (!pets.isEmpty())
		{
			strip.add(namedRow(pets.size() == 1 ? "Pet" : "Pets", pets, ACCENT_SESSION));
			mounted++;
		}
		return mounted;
	}

	private JPanel namedRow(String label, List<String> names, Color color)
	{
		String right = names.size() <= 2 ? String.join(" · ", names) : "+" + names.size();
		JPanel r = row(label, right, color);
		r.setToolTipText(String.join(" · ", names));
		return r;
	}

	private int addSessionMovers(JPanel strip, Map<String, Integer> sess,
		Set<String> shownKeys)
	{
		Map<String, List<Entry<String, Long>>> byFamily = new LinkedHashMap<>();
		Map<String, List<Entry<String, Long>>> under = new LinkedHashMap<>();
		for (Entry<String, Integer> e : sess.entrySet())
		{
			String key = e.getKey();
			if (e.getValue() <= 0 || shownKeys.contains(key) || StatRegistry.hidden(key)
				|| DAMAGE_SPLIT.contains(key))
			{
				continue;
			}
			Entry<String, Long> moved =
				new AbstractMap.SimpleEntry<>(key, (long) e.getValue());
			String parent = parentOf(key, sess);
			if (parent != null)
			{
				under.computeIfAbsent(parent, k -> new ArrayList<>()).add(moved);
				continue;
			}
			byFamily.computeIfAbsent(StatRegistry.family(key), f -> new ArrayList<>())
				.add(moved);
		}

		int mounted = 0;
		for (String family : StatRegistry.FAMILIES)
		{
			List<Entry<String, Long>> rows = byFamily.get(family);
			if (rows == null || rows.isEmpty())
			{
				continue;
			}
			rows.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
			String stateKey = "session:" + family;
			boolean open = !foldOpen(stateKey);
			strip.add(quietHead(family, open ? "" : fmt(rows.size()), stateKey));
			mounted++;
			if (!open)
			{
				continue;
			}
			for (Entry<String, Long> e : rows)
			{
				mounted += addMoverRow(strip, e.getKey(), e.getValue(),
					under.get(e.getKey()));
			}
		}
		return mounted;
	}

	private int addMoverRow(JPanel strip, String key, long value,
		List<Entry<String, Long>> kids)
	{
		if (kids == null || kids.isEmpty())
		{
			strip.add(sessionRow(key, value));
			return 1;
		}
		String listKey = "session:row:" + key;
		boolean open = foldOpen(listKey);
		JPanel head = sessionRow(key, value);
		link(head, () -> toggleFold(listKey));
		strip.add(head);
		int mounted = 1;
		if (!open)
		{
			return mounted;
		}
		kids.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
		int cap = shownCap(listKey);
		int shown = 0;
		long named = 0;
		for (Entry<String, Long> k : kids)
		{
			named += k.getValue();
			if (shown++ >= cap)
			{
				continue;
			}
			strip.add(nested(row(StatRegistry.rowLabel(k.getKey()), fmt(k.getValue()))));
			mounted++;
		}
		addMore(strip, listKey, kids.size(), cap, true);
		if (value - named >= 1)
		{
			strip.add(nested(ghostRow(
				"Teleports".equals(StatRegistry.subgroup(kids.get(0).getKey()))
					|| "Destinations".equals(StatRegistry.subgroup(kids.get(0).getKey()))
					? "Other means" : "Other",
				fmt(value - named))));
			mounted++;
		}
		return mounted;
	}

	private JPanel quietHead(String name, String count, String stateKey)
	{
		JPanel head = subHead(name.toUpperCase(Locale.ROOT), count, stateKey);
		head.setBorder(pad(7, 2, 1, 2));
		return head;
	}

	private JPanel sessionRow(String key, long v)
	{
		return row(StatRegistry.label(key),
			StatRegistry.isGp(key) ? gps(v) : fmt(v));
	}

	private static final String UNDATED = "No loot has been dated yet. The roll keeps one entry a "
		+ "day and starts with the next drop that lands.";

	private JPanel dropsInWindow(JPanel p)
	{
		Window win = window();
		LocalStore.LootWindow sitting = sessionPeriod() ? plugin.sessionLootWindow() : null;
		long rollFrom = plugin.lootRollFrom();
		long fromMs = startMs(win.start);
		if (sitting == null && rollFrom <= 0)
		{
			return noted(p, UNDATED);
		}
		if (sitting == null && rollFrom > fromMs)
		{
			LocalDate began = dayOf(rollFrom);
			return noted(p, "The dated loot roll begins " + began.format(FULL_DAY)
				+ ", which is inside " + periodInSentence() + ". Naming the part it can see "
				+ "as the whole period would be worse than saying nothing.");
		}
		LocalStore.LootWindow w = sitting != null ? sitting
			: plugin.lootBetween(win.start, win.end);
		if (!dropsLeftBehind && dropsByKind)
		{
			if (w.items.isEmpty())
			{
				return noted(p, "Nothing taken inside " + periodInSentence() + ".");
			}
			return kindLens(p, win.label, bagOf(w.items), "win:");
		}
		List<String[]> ranked = dropsLeftBehind ? w.leftItems : w.sources;
		if (ranked.isEmpty())
		{
			return noted(p, "Nothing " + (dropsLeftBehind ? "left behind" : "taken")
				+ " inside " + periodInSentence() + ".");
		}
		JPanel head = card(dropsLeftBehind ? "Left behind" : "Drops received");
		if (dropsLeftBehind)
		{
			head.add(row("Items", fmt(w.left), ACCENT_RED));
			head.add(worthRow(w.leftValue));
			head.add(row("Kills that left one", fmt(w.leftKills)));
		}
		else
		{
			head.add(row("Drops", fmt(w.loots), accent()));
			head.add(worthRow(w.value));
		}
		spaced(p, head);
		final String key = dropsLeftBehind ? "win:left" : "win:source";
		final int cap = drillShown.getOrDefault(key, ROW_CAP);
		int mounted = 0;
		for (String[] r : ranked)
		{
			if (mounted++ >= cap)
			{
				p.add(expander(key, cap, ranked.size()));
				break;
			}
			JPanel line = row(r[0], fmt(safeParse(r[1])) + " · "
				+ gps(safeParse(r[2])));
			final String name = r[0];
			link(line, () ->
			{
				if (dropsLeftBehind)
				{
					openItem(name);
				}
				else
				{
					openSourceLoose(name);
				}
			});
			p.add(line);
		}
		return p;
	}

	private static List<BagItem> bagOf(List<String[]> rows)
	{
		List<BagItem> bag = new ArrayList<>();
		for (String[] r : rows)
		{
			bag.add(new BagItem(0, r[0], safeParse(r[1]), safeParse(r[2])));
		}
		return bag;
	}

	private long[] windowMs()
	{
		if (wholeRecord())
		{
			return new long[]{Long.MIN_VALUE / 2, Long.MAX_VALUE / 2};
		}
		if (sessionPeriod())
		{
			long began = plugin.sessionStart();
			return new long[]{began > 0 ? began
				: startMs(LocalDate.now()),
				System.currentTimeMillis()};
		}
		Window w = window();
		return new long[]{
			startMs(w.start),
			startMs(w.end.plusDays(1)) - 1};
	}

	private boolean onTaskOnly;

	private List<BagItem> onTaskBag()
	{
		long[] w = windowMs();
		return plugin.onTaskLoot(w[0], w[1], null, wholeRecord());
	}

	private boolean hasKindOnTask(String kind)
	{
		for (String name : taskItemsEver().keySet())
		{
			String k = ItemKinds.kindOf(name);
			if (UNFILED.equals(kind) ? k == null : kind.equals(k))
			{
				return true;
			}
		}
		return false;
	}

	private boolean everOnTask()
	{
		return !taskItemsEver().isEmpty();
	}

	private Map<String, long[]> taskItemsEver;

	private Map<String, long[]> taskItemsEver()
	{
		if (taskItemsEver == null)
		{
			taskItemsEver = plugin.onTaskItems(Long.MIN_VALUE / 2, Long.MAX_VALUE / 2);
		}
		return taskItemsEver;
	}

	private boolean dropsLeftBehind;
	private String lootKind;
	private String lootTask;

	private JPanel buildDrops()
	{
		JPanel p = column();
		List<JPanel> axes = new ArrayList<>();
		axes.add(toggle(dropsLeftBehind ? "Left behind" : "Received", () ->
		{
			dropsLeftBehind = !dropsLeftBehind;
			lootKind = null;
			rebuildInPlace();
		}));
		if (!dropsLeftBehind || wholeRecord())
		{
			axes.add(toggle(dropsByKind
				? (dropsLeftBehind ? "By item" : "By kind") : "By source", () ->
			{
				dropsByKind = !dropsByKind;
				lootKind = null;
				rebuildInPlace();
			}));
		}
		final boolean canAskOnTask = !dropsLeftBehind && dropsByKind && everOnTask();
		if (canAskOnTask)
		{
			axes.add(toggle(onTaskOnly ? "On task" : "All", () ->
			{
				onTaskOnly = !onTaskOnly;
				lootKind = null;
				rebuildInPlace();
			}));
		}
		JPanel lens = new JPanel(new GridLayout(1, axes.size(), 3, 3));
		lens.setBackground(DARK);
		for (JPanel a : axes)
		{
			lens.add(a);
		}
		spaced(p, lens);
		if (canAskOnTask && onTaskOnly)
		{
			List<BagItem> taskBag = onTaskBag();
			if (taskBag.isEmpty())
			{
				return noted(p, "No task closed inside " + periodInSentence() + ".");
			}
			return kindLens(p, wholeRecord() ? "On-task loot"
				: "Tasks closed in " + window().label, taskBag, "ontask:");
		}
		if (!wholeRecord())
		{
			return dropsInWindow(p);
		}

		if (dropsLeftBehind)
		{
			return buildLeftBehind(p);
		}
		if (dropsByKind)
		{
			return buildLootByKind(p);
		}
		List<SourceRow> sources = new ArrayList<>(sources());
		sources.sort(Comparator.comparingLong((SourceRow r) -> r.value).reversed());
		if (sources.isEmpty())
		{
			return noted(p, "Drops appear here as you play: every kill, priced as it lands.");
		}
		long everyDrop = 0;
		long everyValue = 0;
		for (SourceRow r : sources)
		{
			everyDrop += r.loots;
			everyValue += r.value;
		}
		JPanel lifeHead = card("Drops received");
		lifeHead.add(row("Drops", fmt(everyDrop), accent()));
		lifeHead.add(worthRow(everyValue));
		lifeHead.add(row("Sources", fmt(sources.size())));
		spaced(p, lifeHead);
		int shown = 0;
		for (SourceRow r : sources)
		{
			if (shown++ >= dropsShown)
			{
				break;
			}
			JPanel card = cardPlain();
			card.add(row(r.name, gps(r.value), accent()));
			boolean killed = isKillSource(r.name);
			String sub = (killed ? fmt(standingKills(r)) + " kc"
				: count(r.loots, "drop"))
				+ (r.pb != null ? " · PB " + pb(r.pb) : "");
			card.add(row(sub, r.loots > 0
				? gp(r.value / Math.max(1, r.loots))
					+ (killed ? " gp/drop" : " gp each") : ""));
			link(card, () -> openSource(r.name));
			spaced(p, card, 4);
		}
		if (sources.size() > dropsShown)
		{
			final int every = sources.size();
			p.add(moreRow(every - dropsShown, () ->
			{
				dropsShown = every;
				rebuildInPlace();
			}));
		}
		return p;
	}

	private boolean dropsByKind;

	private JPanel buildLootByKind(JPanel p)
	{
		final List<BagItem> bag = plugin.allLoot();
		if (bag.isEmpty())
		{
			return noted(p, "Drops appear here as you play: every kill, priced as it lands.");
		}
		return kindLens(p, "Everything dropped", bag, "all:");
	}

	private void addKindRows(JPanel p, List<BagItem> bag)
	{
		for (Kind k : kindsOf(bag))
		{
			JPanel r = row(k.name, fmt(k.qty) + " \u00b7 " + gps(k.value), accent());
			r.setToolTipText(fmt(k.distinct)
				+ (k.distinct == 1 ? " distinct item" : " distinct items"));
			link(r, () ->
			{
				lootKind = k.name;
				rebuildInPlace();
			});
			p.add(r);
		}
	}

	private void dearestRow(JPanel head, List<BagItem> bag)
	{
		BagItem top = null;
		for (BagItem b : bag)
		{
			if (top == null || b.value > top.value)
			{
				top = b;
			}
		}
		if (top == null || top.value <= 0)
		{
			return;
		}
		final String name = top.name;
		JPanel r = row("Dearest", name + " · " + gps(top.value));
		link(r, () -> openItem(name));
		head.add(r);
	}

	private JPanel kindLens(JPanel p, String title, List<BagItem> bag,
		String key)
	{
		final long[] sum = tallyOf(bag);
		if (lootKind != null)
		{
			return kindDrill(p, bag, key);
		}
		JPanel head = card(title);
		bagRows(head, bag, sum);
		dearestRow(head, bag);
		spaced(p, head);
		LinkedHashMap<String, BooleanSupplier> ways =
			new LinkedHashMap<>();
		ways.put("These kinds", () -> copyPicture(lootPicture(title, bag, sum, true)));
		ways.put("Every item", () -> copyPicture(lootPicture(title, bag, sum, false), true));
		p.add(copyHeader("Drops", ways));
		addKindRows(p, bag);
		return p;
	}

	private JPanel kindDrill(JPanel p, List<BagItem> bag, String key)
	{
		final List<BagItem> kept = ofKind(bag);
		final long[] mine = tallyOf(kept);
		JPanel head = card(lootKind);
		bagRows(head, kept, mine);
		spaced(p, head);
		p.add(backToKinds(kept.size()));
		if (kept.isEmpty())
		{
			return noted(p, "Nothing of this kind here.");
		}
		p.add(copyHeader(lootKind, () -> copyPicture(
			lootPicture(lootKind, kept, mine, false), true)));
		addBagRows(p, kept, drillShown.getOrDefault(key + lootKind, ROW_CAP),
			key + lootKind);
		return p;
	}

	private JPanel buildLeftBehind(JPanel p)
	{
		List<UntakenRow> rows = plugin.untakenSources();
		rows.sort(Comparator.comparingLong((UntakenRow r) -> r.value).reversed());
		long totalQty = 0;
		long totalVal = 0;
		for (UntakenRow r : rows)
		{
			totalQty += r.qty;
			totalVal += r.value;
		}
		if (rows.isEmpty())
		{
			return noted(p, "What you walk past gets counted here, priced at the "
				+ "moment you declined it.");
		}
		List<UntakenRow> items = plugin.untakenItems();
		items.sort(Comparator.comparingLong((UntakenRow r) -> r.value).reversed());
		JPanel head = card("Left behind");
		head.add(row("Items", fmt(totalQty), ACCENT_RED));
		head.add(worthRow(totalVal));
		head.add(row(dropsByKind ? "Distinct items" : "Sources",
			fmt(dropsByKind ? items.size() : rows.size())));
		spaced(p, head);
		if (dropsByKind && items.isEmpty())
		{
			return noted(p, "Nothing walked past has been priced yet.");
		}
		final boolean byItem = dropsByKind;
		List<UntakenRow> list = byItem ? items : rows;
		String key = byItem ? "left:item" : "left:source";
		final int cap = drillShown.getOrDefault(key, ROW_CAP);
		int shown = 0;
		for (UntakenRow r : list)
		{
			if (shown++ >= cap)
			{
				p.add(expander(key, cap, list.size()));
				break;
			}
			JPanel card = cardPlain();
			card.add(row(r.name, gps(r.value), ACCENT_RED));
			card.add(row(byItem ? "\u00d7" + fmt(r.qty) : fmt(r.qty) + " left", r.qty > 0
				? gp(r.value / Math.max(1, r.qty)) + " gp each" : ""));
			link(card, () ->
			{
				leftBehindItem = byItem ? r.name : null;
				leftBehindSource = byItem ? null : r.name;
				rebuild();
			});
			spaced(p, card, 4);
		}
		return p;
	}

	private List<GrindBook.GrindRow> grindsCache;
	private boolean grindsFetching;

	private SlayerJourney journeyCache;
	private boolean journeyFetching;
	private int detailTask = -1;
	private String leftBehindSource;
	private String leftBehindItem;

	void resetAccountCaches()
	{
		journeyCache = null;
		journeyFetching = false;
		searchFeed = null;
		searchFeedSpine = null;
		detailTask = -1;
		leftBehindSource = null;
		leftBehindItem = null;
		grindsCache = null;
		grindsFetching = false;
		historySpine = null;
		historyFeed = new ArrayList<>();
		historyJourney = null;
		historyDay = null;
		historyFeedTs = 0;
		historyEpoch++;
		historyGathering = false;
		detailItem = null;
		detailSource = null;
		detailStack.clear();
		drillShown.clear();
		histListShown.clear();
		openFolds.clear();
		signatureItems.clear();
		scaledIcons.clear();
		gatherHistory();
		rebuild();
	}

	void shutdown()
	{
		homeTicker.stop();
		searchDebounce.stop();
	}

	private String slayerLens = "Tasks";
	private int slayerShown = ROW_CAP;

	private void addTaskCard(JPanel p, ChronicleEventCapture.SlayerView task, String title, Color ink)
	{
		JPanel card = card(title);
		card.add(row(task.task, task.remaining + " left", ink));
		if (task.initial > 0)
		{
			card.add(progress(1f - (float) task.remaining / task.initial));
		}
		card.setToolTipText("Open this task's page");
		link(card, this::openCurrentTask);
		spaced(p, card);
	}

	private void openCurrentTask()
	{
		ChronicleEventCapture.SlayerView live = plugin.slayerView();
		if (live == null)
		{
			return;
		}
		SlayerJourney journey = journeyCache;
		if (journey == null)
		{
			plugin.fetchSlayerJourney(read -> SwingUtilities.invokeLater(() ->
			{
				if (read != null)
				{
					journeyCache = read;
					openCurrentTask();
				}
			}));
			return;
		}
		int at = -1;
		for (int i = 0; i < journey.tasks.size(); i++)
		{
			SlayerTask t = journey.tasks.get(i);
			if (t.inProgress && t.task.equalsIgnoreCase(live.task))
			{
				at = i;
				break;
			}
		}
		applyTab(View.SLAYER);
		if (at >= 0)
		{
			detailTask = at;
			rebuild();
		}
	}

	private JPanel buildSlayer()
	{
		JPanel p = column();
		ChronicleEventCapture.SlayerView task = plugin.slayerView();
		if (task != null)
		{
			addTaskCard(p, task, "Current task", accent());
		}

		JPanel lens = new JPanel(new GridLayout(1, 3, 3, 3));
		lens.setBackground(DARK);
		for (String l : new String[]{"Tasks", "Monsters", "Drops"})
		{
			lens.add(pill(l, l.equals(slayerLens), 4, null, () ->
			{
				slayerLens = l;
				rebuildInPlace();
			}));
		}
		spaced(p, lens);

		if (!journeyFetching)
		{
			journeyFetching = true;
			plugin.fetchSlayerJourney(j -> SwingUtilities.invokeLater(() ->
			{
				journeyFetching = false;
				if (j == null)
				{
					return;
				}
				boolean moved = journeyMoved(journeyCache, j);
				journeyCache = j;
				if (moved && view == View.SLAYER && "Tasks".equals(slayerLens))
				{
					rebuildInPlace();
				}
			}));
		}
		if ("Monsters".equals(slayerLens))
		{
			return addKillLog(p);
		}
		if ("Drops".equals(slayerLens))
		{
			return addOnTaskLoot(p);
		}

		if (journeyCache != null)
		{
			addJourney(p, journeyCache);
		}
		else
		{
			p.add(note("Reading the task journey from the journal…"));
		}
		return p;
	}

	private JPanel addOnTaskLoot(JPanel p)
	{
		long[] ms = windowMs();
		final List<BagItem> bag = plugin.onTaskLoot(ms[0], ms[1], lootTask,
			wholeRecord());
		if (bag.isEmpty())
		{
			p.add(taskPicker());
			return noted(p, lootTask != null
				? "No loot logged on " + lootTask + " inside " + periodInSentence() + "."
				: wholeRecord()
					? "No task loot in the journal yet. It collects as tasks close."
					: "No task loot inside " + periodInSentence() + ".");
		}
		final long[] sum = tallyOf(bag);
		final long qty = sum[0];
		final long value = sum[1];

		if (lootKind != null)
		{
			p.add(taskPicker());
			return kindDrill(p, bag, "task:");
		}
		final long[] tally = plugin.onTaskTally(ms[0], ms[1], lootTask, wholeRecord());
		spaced(p, onTaskHead(qty, value, tally));
		p.add(taskPicker());

		LinkedHashMap<String, BooleanSupplier> ways =
			new LinkedHashMap<>();
		ways.put("These kinds", () -> copyPicture(kindsPicture(bag, qty, value, tally)));
		ways.put("Every item", () -> copyPicture(
			lootPicture(lootTask == null ? "On-task loot" : lootTask, bag,
				new long[]{qty, value}, false), true));
		p.add(copyHeader("Drops", ways));
		addKindRows(p, bag);
		return p;
	}

	private JPanel lootPicture(String title, List<BagItem> bag, long[] sum, boolean kinds)
	{
		JPanel page = column();
		JPanel head = card(title);
		bagRows(head, bag, sum);
		spaced(page, head);
		if (kinds)
		{
			for (Kind k : kindsOf(bag))
			{
				page.add(row(k.name, fmt(k.qty) + " \u00b7 " + gps(k.value), accent()));
			}
			return page;
		}
		for (BagItem b : bag)
		{
			page.add(row(named(b.name, b.qty),
				b.value > 0 ? gps(b.value) : ""));
		}
		return page;
	}

	private JPanel kindsPicture(List<BagItem> bag, long qty, long value,
		long[] tally)
	{
		JPanel page = column();
		spaced(page, onTaskHead(qty, value, tally));
		if (lootTask != null)
		{
			spaced(page, row("Task", lootTask, accent()), 4);
		}
		for (Kind k : kindsOf(bag))
		{
			page.add(row(k.name, fmt(k.qty) + " \u00b7 " + gps(k.value), accent()));
		}
		return page;
	}

	private JPanel moreRow(long remaining, Runnable reveal)
	{
		return moreRow("Show " + fmt(remaining) + " more", reveal);
	}

	private JPanel moreRow(String label, Runnable reveal)
	{
		JPanel more = ghostRow(label, "");
		link(more, reveal);
		return more;
	}

	private JPanel actionRow(String label, Runnable go)
	{
		JPanel r = row(label, "", accent(), true);
		link(r, go);
		return r;
	}

	private JPanel expander(String key, int cap, int of)
	{
		return moreRow(of - cap, () ->
		{
			drillShown.put(key, of);
			rebuildInPlace();
		});
	}

	private static final class Kind
	{
		final String name;
		long qty;
		long value;
		int distinct;

		Kind(String name)
		{
			this.name = name;
		}
	}

	private static final String UNFILED = "Everything else";

	private List<Kind> kindsOf(List<BagItem> bag)
	{
		Map<String, Kind> by = new LinkedHashMap<>();
		for (BagItem b : bag)
		{
			String k = ItemKinds.kindOf(b.name);
			Kind row = by.computeIfAbsent(k == null ? UNFILED : k, Kind::new);
			row.qty += b.qty;
			row.value += b.value;
			row.distinct++;
		}
		List<Kind> out = new ArrayList<>(by.values());
		out.sort(Comparator.comparingLong((Kind k) -> k.value).reversed());
		return out;
	}

	private JLabel sprite(int itemId, String name, long qty)
	{
		JLabel slot = new JLabel();
		slot.setPreferredSize(new Dimension(36, 32));
		slot.setHorizontalAlignment(JLabel.CENTER);
		slot.setToolTipText(named(name, qty));
		link(slot, () -> openItem(name));
		plugin.items().getImage(itemId, (int) Math.min(Integer.MAX_VALUE, qty), qty > 1).addTo(slot);
		return slot;
	}

	private void bagRows(JPanel head, java.util.Collection<?> bag, long[] sum)
	{
		head.add(row("Items", fmt(sum[0]), accent()));
		head.add(worthRow(sum[1]));
		head.add(row("Distinct items", fmt(bag.size())));
	}

	private JPanel backToKinds(int held)
	{
		return backRow("< All kinds", fmt(held) + (held == 1 ? " item" : " items"), () ->
		{
			lootKind = null;
			rebuildInPlace();
		});
	}

	private void addBagRows(JPanel p, List<BagItem> bag, int cap, String key)
	{
		int mounted = 0;
		for (BagItem b : bag)
		{
			if (mounted++ >= cap)
			{
				p.add(expander(key, cap, bag.size()));
				break;
			}
			JPanel r = row(named(b.name, b.qty),
				b.value > 0 ? gps(b.value) : "");
			link(r, () -> openItem(b.name));
			p.add(r);
		}
	}

	private long[] tallyOf(List<BagItem> bag)
	{
		long q = 0;
		long v = 0;
		for (BagItem b : bag)
		{
			q += b.qty;
			v += b.value;
		}
		return new long[]{q, v};
	}

	private List<BagItem> ofKind(List<BagItem> bag)
	{
		if (lootKind == null)
		{
			return bag;
		}
		List<BagItem> kept = new ArrayList<>();
		for (BagItem b : bag)
		{
			String k = ItemKinds.kindOf(b.name);
			if (UNFILED.equals(lootKind) ? k == null : lootKind.equals(k))
			{
				kept.add(b);
			}
		}
		return kept;
	}

	private JPanel onTaskHead(long qty, long value, long[] tally)
	{
		JPanel head = card("On-task loot");
		head.add(row("Items", fmt(qty), accent()));
		head.add(worthRow(value));
		if (tally != null && tally.length > 2)
		{
			head.add(row("Tasks", fmt(tally[2])));
		}
		if (tally != null && tally.length > 0 && tally[0] > 0)
		{
			head.add(row("Kills logged", fmt(tally[0])));
		}
		if (tally != null && tally.length > 1 && tally[1] > 0)
		{
			head.add(row("Superiors", fmt(tally[1])));
		}
		return head;
	}

	private static JLabel copyLabel(JPanel r, String tip)
	{
		JLabel take = part(r, BorderLayout.EAST);
		if (take != null)
		{
			styled(take, small(), dim());
			take.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			take.setToolTipText(tip);
		}
		return take;
	}

	private void reportCopy(JLabel take, boolean ok)
	{
		take.setText(ok ? "copied" : "cannot copy");
		take.setForeground(ok ? accent() : ColorScheme.PROGRESS_ERROR_COLOR);
	}

	private JPanel copyHeader(String title,
		LinkedHashMap<String, BooleanSupplier> choices)
	{
		return copyHeaderLater(title, take ->
		{
			JPopupMenu menu = new JPopupMenu();
			for (Entry<String, BooleanSupplier> e
				: choices.entrySet())
			{
				menuItem(menu, e.getKey(), false, () -> reportCopy(take, e.getValue().getAsBoolean()));
			}
			menu.show(take, 0, take.getHeight());
		});
	}

	private JPanel copyHeaderLater(String title, java.util.function.Consumer<JLabel> copy)
	{
		JPanel r = row(title, "copy");
		JLabel t = styled(part(r, BorderLayout.CENTER), small(), accent());
		JLabel take = copyLabel(r, "Copy this board as a picture");
		if (take != null)
		{
			take.addMouseListener(clicker(() -> copy.accept(take)));
		}
		return r;
	}

	private JPanel copyHeader(String title, BooleanSupplier copy)
	{
		return copyHeaderLater(title, take -> reportCopy(take, copy.getAsBoolean()));
	}

	private JPanel addKillLog(JPanel p)
	{
		List<Entry<String, Long>> kcs = new ArrayList<>(LocalStore.killLogCounts(clogNow()).entrySet());
		if (kcs.isEmpty())
		{
			return noted(p, "No kill log yet. It copies itself the next time you open "
				+ "the Slayer Kill Log in game.");
		}
		kcs.sort(Entry.<String, Long>comparingByValue().reversed());
		JPanel card = card("Kill log");
		final int cap = drillShown.getOrDefault("killlog", ROW_CAP);
		for (Entry<String, Long> e : firstN(kcs, cap))
		{
			JPanel r = row(e.getKey(), fmt(e.getValue()));
			final String mob = e.getKey();
			link(r, () -> openSourceLoose(mob));
			card.add(r);
		}
		if (kcs.size() > cap)
		{
			card.add(expander("killlog", cap, kcs.size()));
		}
		p.add(card);
		return p;
	}

	private static boolean journeyMoved(SlayerJourney was,
		SlayerJourney now)
	{
		if (was == null)
		{
			return true;
		}
		if (was.completedTasks != now.completedTasks
			|| was.totalKills != now.totalKills
			|| was.tasks.size() != now.tasks.size())
		{
			return true;
		}
		return !was.tasks.isEmpty()
			&& was.tasks.get(0).kills != now.tasks.get(0).kills;
	}

	private void addTaskAgainstRecord(JPanel head, SlayerJourney j, int index,
		SlayerTask t)
	{
		int earlier = 0;
		long sumValue = 0;
		long sumKills = 0;
		int bestAt = -1;
		for (int i = 0; i < j.tasks.size(); i++)
		{
			SlayerTask o = j.tasks.get(i);
			if (i == index || o.inProgress || o.ts >= t.ts || !o.task.equalsIgnoreCase(t.task))
			{
				continue;
			}
			earlier++;
			sumValue += o.totalValue;
			sumKills += o.kills;
			if (bestAt < 0 || o.totalValue > j.tasks.get(bestAt).totalValue)
			{
				bestAt = i;
			}
		}
		if (earlier < 2)
		{
			return;
		}
		head.add(row("Usual", gp(sumValue / earlier) + " gp · " + fmt(sumKills / earlier)
			+ " kills · over " + earlier + " tasks"));
		SlayerTask best = j.tasks.get(bestAt);
		JPanel bestRow = row("Best", gp(best.totalValue) + " gp · "
			+ day((long) (best.ts * 1000)));
		final int at = bestAt;
		link(bestRow, () ->
		{
			detailTask = at;
			rebuild();
		});
		head.add(bestRow);
	}

	private JPanel buildTaskDetail(int index)
	{
		JPanel p = column();
		p.add(backRow("< Back", "", () ->
		{
			detailTask = -1;
			rebuild();
		}));
		p.add(vgap(4));
		SlayerJourney j = journeyCache;
		SlayerTask t = j != null && index >= 0 && index < j.tasks.size()
			? j.tasks.get(index) : null;
		if (t == null)
		{
			return noted(p, "That task is no longer in the journal.");
		}
		JPanel head = card(t.task.toUpperCase(Locale.ROOT));
		String kills = t.inProgress && t.assignment > t.kills
			? fmt(t.kills) + " / " + fmt(t.assignment) : fmt(t.kills);
		head.add(row("Kills logged", kills, accent()));
		if (t.noLootKills > 0)
		{
			head.add(row("Killed without loot", fmt(t.noLootKills)));
		}
		head.add(worthRow(t.totalValue));
		if (t.ts > 0)
		{
			head.add(row(t.inProgress ? "Started" : "Finished",
				day((long) (t.ts * 1000))));
		}
		addTaskAgainstRecord(head, j, index, t);
		spaced(p, head);

		List<UntakenRow> monsters = plugin.slayerTaskMonsters(index);
		if (!monsters.isEmpty())
		{
			p.add(group("Killed"));
			for (UntakenRow m : monsters)
			{
				JPanel r = row(m.name, "×" + fmt(m.qty));
				link(r, () -> openSourceLoose(m.name));
				p.add(r);
			}
			p.add(vgap(6));
		}

		List<BagItem> bag = plugin.slayerTaskItems(index);
		if (bag.isEmpty())
		{
			p.add(note("No loot recorded against this task."));
		}
		else
		{
			p.add(group("Loot from this task"));
			for (BagItem it : bag)
			{
				JPanel r = row(named(it.name, it.qty),
					gps(it.value));
				link(r, () -> openItem(it.name));
				p.add(r);
			}
		}
		p.add(vgap(8));
		p.add(actionRow("All kills of " + t.task, () ->
		{
			detailTask = -1;
			openSourceLoose(t.task);
		}));
		return p;
	}

	private JPanel buildLeftBehindDetail()
	{
		JPanel p = column();
		p.add(backRow("< Back", "", () ->
		{
			leftBehindSource = null;
			leftBehindItem = null;
			rebuild();
		}));
		p.add(vgap(4));

		if (leftBehindSource != null)
		{
			List<BagItem> bag = plugin.untakenItemsOf(leftBehindSource);
			long qty = 0;
			long val = 0;
			for (UntakenRow r : plugin.untakenSources())
			{
				if (r.name.equals(leftBehindSource))
				{
					qty = r.qty;
					val = r.value;
					break;
				}
			}
			JPanel head = card(leftBehindSource.toUpperCase(Locale.ROOT));
			head.add(row("Left on the floor", count(qty, "item"), ACCENT_RED));
			head.add(worthRow(val));
			spaced(p, head);
			if (bag.isEmpty())
			{
				return noted(p, "The count above is older than the itemised record. "
					+ "What this source leaves behind is listed here from now on.");
			}
			p.add(group("Declined"));
			for (BagItem b : bag)
			{
				JPanel r = row(named(b.name, b.qty),
					gps(b.value), ACCENT_RED);
				link(r, () ->
				{
					leftBehindItem = b.name;
					leftBehindSource = null;
					rebuild();
				});
				p.add(r);
			}
			return p;
		}

		List<UntakenRow> sources = plugin.untakenSourcesOf(leftBehindItem);
		long qty = 0;
		long val = 0;
		for (UntakenRow r : plugin.untakenItems())
		{
			if (r.name.equals(leftBehindItem))
			{
				qty = r.qty;
				val = r.value;
				break;
			}
		}
		JPanel head = card(leftBehindItem.toUpperCase(Locale.ROOT));
		head.add(row("Left behind", "×" + fmt(qty), ACCENT_RED));
		head.add(worthRow(val));
		spaced(p, head);
		if (sources.isEmpty())
		{
			return noted(p, "No source itemised for this yet.");
		}
		p.add(group("Left where"));
		for (UntakenRow r : sources)
		{
			JPanel row = row(r.name, "×" + fmt(r.qty) + " · " + gps(r.value), ACCENT_RED);
			link(row, () ->
			{
				leftBehindSource = r.name;
				leftBehindItem = null;
				rebuild();
			});
			p.add(row);
		}
		return p;
	}

	private void addJourney(JPanel p, SlayerJourney j)
	{
		if (j.tasks.isEmpty() && j.completedTasks == 0)
		{
			p.add(note("No tasks in the journal yet. They collect as "
				+ "you play with the Slayer plugin on."));
			return;
		}
		List<SlayerTask> shown = new ArrayList<>();
		List<Integer> where = new ArrayList<>();
		for (int i = 0; i < j.tasks.size(); i++)
		{
			SlayerTask t = j.tasks.get(i);
			if (insideWindow((long) (t.ts * 1000)) && (wholeRecord() || !t.inProgress))
			{
				shown.add(t);
				where.add(i);
			}
		}
		if (shown.isEmpty() && !wholeRecord())
		{
			p.add(nothingInWindow("tasks closed"));
			return;
		}
		long tasksDone = j.completedTasks;
		long killsOnTask = j.totalKills;
		long onTaskLoot = j.totalValueGp;
		if (!wholeRecord())
		{
			tasksDone = 0;
			killsOnTask = 0;
			onTaskLoot = 0;
			for (SlayerTask t : shown)
			{
				if (!t.inProgress)
				{
					tasksDone++;
				}
				killsOnTask += t.kills;
				onTaskLoot += t.totalValue;
			}
		}
		JPanel head = card("The journey");
		head.add(row("Tasks done", fmt(tasksDone), accent()));
		head.add(row("Kills on task", fmt(killsOnTask)));
		head.add(row("On-task loot", gps(onTaskLoot)));
		if (j.totalXpEst > 0)
		{
			head.add(row("Slayer xp (est.)", gp(j.totalXpEst)));
		}
		spaced(p, head);
		for (int k = 0; k < shown.size() && k < slayerShown; k++)
		{
			SlayerTask t = shown.get(k);
			JPanel card = cardPlain();
			final int at = where.get(k);
			link(card, () ->
			{
				detailTask = at;
				rebuild();
			});
			card.add(row(t.task, t.totalValue > 0 ? gps(t.totalValue) : "",
				accent(), t.inProgress));
			String kills = t.inProgress && t.assignment > t.kills
				? fmt(t.kills) + " / " + fmt(t.assignment)
				: count(t.kills, "kill");
			if (t.noLootKills > 0)
			{
				kills += " · " + fmt(t.noLootKills) + " no-drop";
			}
			card.add(row(kills, t.ts > 0
				? day((long) (t.ts * 1000)) : ""));
			spaced(p, card, 4);
		}
		if (shown.size() > slayerShown)
		{
			final int every = shown.size();
			p.add(moreRow(every - slayerShown, () ->
			{
				slayerShown = every;
				rebuildInPlace();
			}));
			p.add(vgap(4));
		}
	}

	private final Map<String, Integer> drillShown = new LinkedHashMap<>();

	private void leaveSentPage()
	{
		allTrackers = false;
		showInfo = false;
		showRecords = false;
		showCalendar = false;
	}

	private void leaveAll()
	{
		leaveSentPage();
		detailItem = null;
		detailSource = null;
		detailSkill = null;
		detailTask = -1;
		clearSearch();
	}

	void openRecords()
	{
		leaveAll();
		showRecords = true;
		rebuild();
	}

	void openCalendar()
	{
		leaveAll();
		showCalendar = true;
		calendarMonth = YearMonth.from(histCursor);
		rebuild();
	}

	void openItem(String name)
	{
		leaveSentPage();
		pushDetail();
		detailItem = name;
		detailSource = null;
		clearSearch();
		rebuild();
	}

	private JPanel buildInfo()
	{
		JPanel p = column();
		spaced(p, backRow(() -> copyPicture(stripChrome(buildInfo()))), 4);
		Map<String, Long> f = plugin.journalFacts();

		JPanel loot = facts(card("Loot"), f, accent(), "Sources", "sources",
			"Item rows", "itemRows", "Loot events", "lootEvents");
		loot.add(worthRow(f.getOrDefault("lootWorth", 0L)));
		facts(loot, f, null, "Dated days", "lootDays");
		loot.add(row("Left behind", fmt(f.getOrDefault("untakenItems", 0L)) + " items, "
			+ fmt(f.getOrDefault("untakenSources", 0L)) + " sources"));
		spaced(p, loot);

		spaced(p, facts(card("Slayer"), f, accent(), "Assignments", "tasks",
			"Closed", "tasksClosed"));

		JPanel log = card("Collection log");
		long availKnown = f.getOrDefault("clogAvailable", 0L);
		log.add(row("Slots filled", fmt(f.getOrDefault("clogSlots", 0L))
			+ (availKnown > 0 ? " of " + fmt(availKnown) : ""), accent()));
		spaced(p, facts(log, f, null, "Items named", "clogItems",
			"Pages with a count", "clogPages", "Kill Log lines", "killLogLines",
			"Labelled kill lines", "pageKillLines"));

		spaced(p, facts(card("Counted"), f, accent(), "Trackers", "trackers",
			"Skills", "skills", "Feed entries", "feed", "Chat kill counts", "chatCounts",
			"Anchored counts", "anchors"));

		JPanel file = card("On disk");
		file.add(row("Journal", bytes(f.getOrDefault("journalBytes", 0L)), accent()));
		file.add(row("History spine", bytes(f.getOrDefault("spineBytes", 0L))));
		p.add(facts(file, f, null, "Schema", "schema"));
		return p;
	}

	private static JPanel facts(JPanel c, Map<String, Long> f, Color lead, String... rows)
	{
		for (int i = 0; i < rows.length; i += 2)
		{
			c.add(row(rows[i], fmt(f.getOrDefault(rows[i + 1], 0L)), i == 0 ? lead : null));
		}
		return c;
	}

	private static String bytes(long n)
	{
		if (n >= 1024 * 1024)
		{
			return String.format("%.1f MB", n / (1024.0 * 1024.0));
		}
		return n >= 1024 ? fmt(n / 1024) + " KB" : fmt(n) + " B";
	}

	void openInfo()
	{
		leaveAll();
		showInfo = true;
		rebuild();
	}

	void openAllTrackers()
	{
		leaveAll();
		allTrackers = true;
		rebuild();
	}

	void openLootKind(String kind, boolean onTask)
	{
		tab = Tab.LOOT;
		subByTab.put(Tab.LOOT, "Loot");
		applyCommon();
		dropsLeftBehind = false;
		dropsByKind = true;
		onTaskOnly = onTask;
		lootKind = kind;
		rebuild();
	}

	void openSkill(String craft)
	{
		leaveAll();
		detailSkill = craft;
		rebuild();
	}

	void openSource(String name)
	{
		leaveSentPage();
		pushDetail();
		detailSource = name;
		detailItem = null;
		clearSearch();
		rebuild();
	}

	private long[] itemInWindow(String name)
	{
		return rowOf(lootWindow().items, name);
	}

	private double[] sourceTimesInWindow(String name)
	{
		for (Entry<String, double[]> e : lootWindow().times.entrySet())
		{
			if (e.getKey().equalsIgnoreCase(name))
			{
				return e.getValue();
			}
		}
		return new double[]{0, 0};
	}

	private long[] sourceInWindow(String name)
	{
		return rowOf(lootWindow().sources, name);
	}

	private static long[] rowOf(List<String[]> rows, String name)
	{
		String[] r = rowFor(rows, name, false);
		return r == null ? new long[]{0, 0} : new long[]{safeParse(r[1]), safeParse(r[2])};
	}

	private static String[] rowFor(List<String[]> rows, String name, boolean exact)
	{
		String[] loose = null;
		for (String[] r : rows)
		{
			if (r[0].equals(name))
			{
				return r;
			}
			loose = loose == null && !exact && r[0].equalsIgnoreCase(name) ? r : loose;
		}
		return loose;
	}

	void openSourceLoose(String name)
	{
		openSource(resolveSource(name));
	}

	private String resolveSource(String name)
	{
		String named = resolveSourceNamed(name);
		if (named != null)
		{
			return named;
		}
		String kind = kindOf(name);
		SourceRow best = null;
		for (SourceRow r : sources())
		{
			if ((kindOf(r.name).equals(kind) || namesInBrackets(r.name, name))
				&& (best == null || r.value > best.value))
			{
				best = r;
			}
		}
		return best != null ? best.name : name;
	}

	private String resolveSourceNamed(String name)
	{
		if (ledgerNames == null)
		{
			Map<String, String> index = new HashMap<>();
			for (SourceRow r : sources())
			{
				index.putIfAbsent(low(r.name), r.name);
			}
			ledgerNames = index;
		}
		String low = low(name);
		String hit = ledgerNames.get(low);
		if (hit == null && low.endsWith("s"))
		{
			hit = ledgerNames.get(low.substring(0, low.length() - 1));
		}
		return hit;
	}

	private Map<String, String> ledgerNames;

	private void pushDetail()
	{
		if (detailItem != null)
		{
			detailStack.push(new String[]{"i", detailItem});
		}
		else if (detailSource != null)
		{
			detailStack.push(new String[]{"s", detailSource});
		}
		while (detailStack.size() > 16)
		{
			detailStack.removeLast();
		}
	}

	private void backDetail()
	{
		if (showInfo || showRecords || showCalendar)
		{
			showInfo = false;
			showRecords = false;
			showCalendar = false;
			rebuild();
			return;
		}
		if (detailItem != null || detailSource != null)
		{
			String[] prev = detailStack.poll();
			if (prev == null)
			{
				detailItem = null;
				detailSource = null;
			}
			else if ("i".equals(prev[0]))
			{
				detailItem = prev[1];
				detailSource = null;
			}
			else
			{
				detailSource = prev[1];
				detailItem = null;
			}
			rebuild();
			return;
		}
		if (detailSkill != null)
		{
			detailSkill = null;
			rebuild();
			return;
		}
		if (allTrackers)
		{
			allTrackers = false;
			rebuild();
			return;
		}
		if (sheetPage != null)
		{
			sheetPage = null;
			rebuild();
			return;
		}
		rebuild();
	}

	private JPanel backRow(String label, String right, Runnable go)
	{
		JPanel r = row(label, right);
		JLabel l = styled(part(r, BorderLayout.CENTER), small(), accent());
		link(r, go);
		return r;
	}

	private JPanel backRow()
	{
		return backRow(null);
	}

	private JPanel backRow(BooleanSupplier copy)
	{
		JPanel r = backRow("< Back", copy == null ? "" : "copy", this::backDetail);
		JLabel take = copy == null ? null : copyLabel(r, "Copy this page as a picture");
		if (take != null)
		{
			take.addMouseListener(clicker(() -> reportCopy(take, copy.getAsBoolean())));
		}
		return r;
	}

	private static Transferable clip(DataFlavor flavor, Supplier<Object> data)
	{
		return new Transferable()
		{
			@Override
			public DataFlavor[] getTransferDataFlavors()
			{
				return new DataFlavor[]{flavor};
			}

			@Override
			public boolean isDataFlavorSupported(DataFlavor f)
			{
				return flavor.equals(f);
			}

			@Override
			public Object getTransferData(DataFlavor f)
				throws UnsupportedFlavorException
			{
				if (flavor.equals(f))
				{
					return data.get();
				}
				throw new UnsupportedFlavorException(f);
			}
		};
	}

	private static boolean toClipboard(Image image)
	{
		if (image == null)
		{
			return false;
		}
		try
		{
			Transferable payload = pngPayload(image);
			java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
				.setContents(payload != null ? payload : clip(DataFlavor.imageFlavor, () -> image), null);
			return true;
		}
		catch (Throwable ignored)
		{
			return false;
		}
	}

	private static final DataFlavor PNG_BYTES = pngFlavor();
	private static boolean pngNativeMapped;

	private static DataFlavor pngFlavor()
	{
		try
		{
			return new DataFlavor("image/png;class=java.io.InputStream");
		}
		catch (ClassNotFoundException ignored)
		{
			return null;
		}
	}

	private static Transferable pngPayload(Image image)
	{
		if (PNG_BYTES == null || OSType.getOSType() != OSType.MacOS
			|| !(image instanceof java.awt.image.RenderedImage))
		{
			return null;
		}
		try
		{
			java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
			javax.imageio.stream.MemoryCacheImageOutputStream ios =
				new javax.imageio.stream.MemoryCacheImageOutputStream(out);
			if (!javax.imageio.ImageIO.write((java.awt.image.RenderedImage) image, "png", ios))
			{
				return null;
			}
			ios.flush();
			final byte[] png = out.toByteArray();
			if (png.length == 0)
			{
				return null;
			}
			mapPngNative();
			return clip(PNG_BYTES, () -> new java.io.ByteArrayInputStream(png));
		}
		catch (Throwable ignored)
		{
			return null;
		}
	}

	private static synchronized void mapPngNative()
	{
		if (pngNativeMapped)
		{
			return;
		}
		((java.awt.datatransfer.SystemFlavorMap)
			java.awt.datatransfer.SystemFlavorMap.getDefaultFlavorMap())
			.addUnencodedNativeForFlavor(PNG_BYTES, "public.png");
		pngNativeMapped = true;
	}

	private static final int COPY_MAX_HEIGHT = 20000;

	private static Image pageImage(JPanel page, int width)
	{
		try
		{
			int w = width;
			page.setSize(w, COPY_MAX_HEIGHT);
			layOut(page);
			int full = Math.max(1, page.getPreferredSize().height);
			page.setSize(w, full);
			layOut(page);

			int h = Math.min(full, COPY_MAX_HEIGHT);
			int lost = h < full ? pastTheEdge(page, h) : 0;
			BufferedImage img = new BufferedImage(
				w, h, BufferedImage.TYPE_INT_RGB);
			java.awt.Graphics2D g = img.createGraphics();
			g.setColor(DARK);
			g.fillRect(0, 0, w, h);
			page.printAll(g);
			if (lost > 0)
			{
				sayWhatDidNotFit(g, w, h, lost);
			}
			g.dispose();
			return img;
		}
		catch (Throwable ignored)
		{
			return null;
		}
	}

	private static int pastTheEdge(JPanel page, int cut)
	{
		int n = 0;
		for (Component k : page.getComponents())
		{
			if (k.getY() >= cut)
			{
				n++;
			}
		}
		return n;
	}

	private static void sayWhatDidNotFit(java.awt.Graphics2D g, int w, int h, int lost)
	{
		int band = 20;
		g.setColor(DARKER);
		g.fillRect(0, h - band, w, band);
		g.setColor(ColorScheme.LIGHT_GRAY_COLOR);
		g.setFont(small());
		String said = fmt(lost) + " more, past the height a picture can hold";
		g.drawString(said, 6, h - 6);
	}

	private static final int COPY_WIDTH = 340;

	private static void layOut(Component c)
	{
		c.doLayout();
		if (c instanceof java.awt.Container)
		{
			for (Component k : ((java.awt.Container) c).getComponents())
			{
				layOut(k);
			}
		}
	}

	private static final int COPY_ROWS = 60;
	private static final int COPY_COLUMNS = 6;
	private static final int COPY_GAP = 10;
	private static final int COPY_MOST = COPY_COLUMNS * 200;

	private static boolean copyPicture(JPanel page)
	{
		return copyPicture(page, false);
	}

	private static boolean copyPicture(JPanel page, boolean tall)
	{
		return toClipboard(copyImage(page, tall));
	}

	static Image copyImage(JPanel page)
	{
		return copyImage(page, false);
	}

	static Image copyImage(JPanel page, boolean tall)
	{
		int cols = tall ? 1 : copyColumns(page.getComponentCount());
		return pageImage(reflowed(page, cols), copyImageWidth(cols));
	}

	private static int copyColumns(int rows)
	{
		int held = Math.max(0, Math.min(rows, COPY_MOST));
		return Math.max(1, Math.min(COPY_COLUMNS, (held + COPY_ROWS - 1) / COPY_ROWS));
	}

	private static int copyImageWidth(int cols)
	{
		return COPY_WIDTH * cols + COPY_GAP * (cols - 1);
	}

	private static JPanel reflowed(JPanel page, int cols)
	{
		if (cols <= 1)
		{
			return page;
		}
		Component[] kids = page.getComponents();
		page.removeAll();
		int per = (kids.length + cols - 1) / cols;
		JPanel grid = new JPanel(new GridLayout(1, cols, COPY_GAP, 0));
		grid.setBackground(DARK);
		grid.setAlignmentX(Component.LEFT_ALIGNMENT);
		for (int c = 0; c < cols; c++)
		{
			JPanel col = column();
			for (int i = c * per; i < Math.min(kids.length, (c + 1) * per); i++)
			{
				col.add(kids[i]);
			}
			JPanel cell = new JPanel(new BorderLayout());
			cell.setBackground(DARK);
			cell.add(col, BorderLayout.NORTH);
			grid.add(cell);
		}
		JPanel out = column();
		out.add(grid);
		return out;
	}

	private int itemSourceCap = 40;

	private boolean drawingCopy;

	private boolean copyItemPage(String name)
	{
		int was = itemSourceCap;
		try
		{
			itemSourceCap = COPY_MOST;
			drawingCopy = true;
			return copyPicture(stripChrome(buildItemDetail(name)));
		}
		catch (Throwable ignored)
		{
			return false;
		}
		finally
		{
			drawingCopy = false;
			itemSourceCap = was;
		}
	}

	private static JPanel stripChrome(JPanel page)
	{
		if (page.getComponentCount() > 2)
		{
			page.remove(1);
			page.remove(0);
		}
		return page;
	}

	private boolean copySourcePage(String name)
	{
		Integer was = drillShown.get(name);
		try
		{
			drillShown.put(name, COPY_MOST);
			drawingCopy = true;
			return copyPicture(stripChrome(buildSourceDetail(name)));
		}
		catch (Throwable ignored)
		{
			return false;
		}
		finally
		{
			drawingCopy = false;
			if (was == null)
			{
				drillShown.remove(name);
			}
			else
			{
				drillShown.put(name, was);
			}
		}
	}

	private JPanel buildItemDetail(String name)
	{
		JPanel p = column();
		long got = 0;
		long worth = 0;
		int found = 0;
		final List<Object[]> srcs = new ArrayList<>();
		for (SourceRow r : sources())
		{
			for (BagItem b : plugin.sourceItems(r.name))
			{
				if (b.name.equalsIgnoreCase(name))
				{
					got += b.qty;
					worth += b.value;
					if (found == 0 && b.itemId > 0)
					{
						found = b.itemId;
					}
					srcs.add(new Object[]{r.name, b.qty, b.value});
				}
			}
		}
		final long[] inWindow = wholeRecord() ? null : itemInWindow(name);
		long other = 0;
		long otherValue = 0;
		if (inWindow != null)
		{
			srcs.clear();
			other = inWindow[0];
			otherValue = inWindow[1];
			String[] headRow = rowFor(lootWindow().items, name, false);
			String spelled = headRow != null ? headRow[0] : name;
			for (Map.Entry<String, List<BagItem>> e : periodItems().entrySet())
			{
				for (BagItem b : e.getValue())
				{
					if (b.name.equals(spelled))
					{
						srcs.add(new Object[]{e.getKey(), b.qty, b.value});
						other -= b.qty;
						otherValue -= b.value;
					}
				}
			}
		}
		srcs.sort((a, b) -> Long.compare((long) b[1], (long) a[1]));
		final long qty = inWindow != null ? inWindow[0] : got;
		final long value = inWindow != null ? inWindow[1] : worth;
		final int itemId = found;
		spaced(p, backRow(() -> copyItemPage(name)), 4);
		JPanel head = card(name);
		if (itemId > 0)
		{
			JLabel slot = new JLabel();
			slot.setPreferredSize(new Dimension(36, 32));
			slot.setAlignmentX(Component.LEFT_ALIGNMENT);
			AsyncBufferedImage img = plugin.items().getImage(itemId,
				(int) Math.min(Integer.MAX_VALUE, Math.max(1, qty)), qty > 1);
			img.addTo(slot);
			head.add(slot);
		}
		final boolean hasTask = taskItemsEver().containsKey(properName(name));
		long[] tw = windowMs();
		long[] mine = inWindow == null ? taskItemsEver().get(properName(name))
			: plugin.onTaskItems(tw[0], tw[1]).getOrDefault(properName(name), new long[2]);
		if (hasTask && onTaskOnly)
		{
			head.add(row("Obtained on task", "×" + fmt(mine[0]), accent()));
			if (inWindow == null || lootSince() == null)
			{
				head.add(row("All sources", "×" + fmt(qty)));
			}
			JPanel priced = worthRow(mine[1]);
			priced.setToolTipText("Priced as the drop landed, or in bulk on the day "
				+ "the history was imported");
			head.add(priced);
		}
		else
		{
			head.add(row("Obtained", "×" + fmt(qty), accent()));
			if (value > 0)
			{
				head.add(worthRow(value));
			}
		}
		if (inWindow == null && !drawingCopy)
		{
			long[] days = plugin.itemDays(name);
			days[2] = days.length > 3 && days[3] < got ? 0 : days[2];
			if (days[2] == 1)
			{
				head.add(row("Dropped on", dated(days[0])));
			}
			else if (days[2] > 1)
			{
				head.add(row("First dropped", dated(days[0])));
				head.add(row("Last dropped", dated(days[1])));
				head.add(row("Days it landed", fmt(days[2])));
			}
		}
		p.add(head);
		String since = inWindow == null || hasTask && onTaskOnly ? null : lootSince();
		if (inWindow != null && sinceLine(since) != null)
		{
			p.add(vgap(4));
			p.add(note(sinceLine(since)));
		}
		p.add(vgap(6));
		if (hasTask)
		{
			JPanel hold = column();
			hold.add(toggle(onTaskOnly ? "On task" : "All", () ->
			{
				onTaskOnly = !onTaskOnly;
				lootKind = null;
				rebuildInPlace();
			}));
			hold.add(vgap(6));
			p.add(hold);
		}
		if (hasTask && onTaskOnly)
		{
			return byTaskRows(p, properName(name));
		}
		if (srcs.isEmpty() && other <= 0)
		{
			return inWindow != null ? nothing(p, "dropped", since)
				: noted(p, "The journal hasn't seen this item drop yet.");
		}
		p.add(group("From"));
		final int srcCap = Math.max(itemSourceCap, drillShown.getOrDefault("item:src:" + name, 0));
		int mounted = 0;
		for (Object[] s : srcs)
		{
			if (mounted++ >= srcCap)
			{
				p.add(expander("item:src:" + name, srcCap, srcs.size()));
				break;
			}
			JPanel r = row((String) s[0], "×" + fmt((long) s[1])
				+ tail((long) s[2]));
			final String src = (String) s[0];
			link(r, () -> openSource(src));
			p.add(r);
		}
		addOther(p, "×" + fmt(Math.max(0, other)) + tail(Math.max(0, otherValue)), other > 0 || otherValue > 0);
		return p;
	}

	private Map<String, List<BagItem>> periodItems()
	{
		Window w = window();
		return sessionPeriod() ? plugin.itemsBySource(null, null) : plugin.itemsBySource(w.start, w.end);
	}

	private String lootSince()
	{
		long from = plugin.lootDetailFrom();
		Window w = window();
		return sessionPeriod() || from > 0 && from <= startMs(w.start) ? null
			: from <= 0 ? UNDATED
			: !drawingCopy ? "Loot since " + dayOf(from).format(FULL_DAY)
			: "Loot is dated for " + (dayOf(from).isAfter(w.end) ? "none" : "only part") + " of "
			+ periodInSentence() + ".";
	}

	private String sinceLine(String since)
	{
		return since != null || wholeRecord() || !drawingCopy ? since
			: "The figures above are " + periodInSentence() + "'s.";
	}

	private JPanel nothing(JPanel p, String what, String since)
	{
		return since != null ? p : noted(p, "Nothing " + what + " inside " + periodInSentence() + ".");
	}

	private static void addOther(JPanel p, String figure, boolean show)
	{
		if (show)
		{
			JPanel r = ghostRow("Other", figure);
			r.setToolTipText("Dropped on days the record kept whole rather than by source");
			p.add(r);
		}
	}

	private String properName(String typed)
	{
		for (String key : taskItemsEver().keySet())
		{
			if (key.equalsIgnoreCase(typed))
			{
				return key;
			}
		}
		return typed;
	}

	private JPanel byTaskRows(JPanel p, String name)
	{
		long[] w = windowMs();
		List<Object[]> split = plugin.onTaskItemByTask(name, w[0], w[1]);
		if (split.isEmpty())
		{
			return noted(p, "No task paid this inside " + periodInSentence() + ".");
		}
		p.add(group("By task"));
		final int taskCap = Math.max(itemSourceCap, drillShown.getOrDefault("item:task:" + name, 0));
		int mounted = 0;
		for (Object[] t : split)
		{
			if (mounted++ >= taskCap)
			{
				p.add(expander("item:task:" + name, taskCap, split.size()));
				break;
			}
			p.add(row("Task: " + t[0], "×" + fmt((long) t[1])
				+ tail((long) t[2])));
		}
		return p;
	}

	private long standingKills(SourceRow sr)
	{
		long own = sr.kc > 0 ? sr.kc : sr.loots;
		Long said = chatKcByKind().get(LocalStore.chatKind(sr.name));
		return said == null ? own : Math.max(own, said);
	}

	private Map<String, Long> chatKcByKind;

	private Map<String, Long> chatKcByKind()
	{
		if (chatKcByKind == null)
		{
			Map<String, Long> out = new LinkedHashMap<>();
			for (Entry<String, Long> e : plugin.killCounts().entrySet())
			{
				out.merge(LocalStore.chatKind(e.getKey()), e.getValue(), Math::max);
			}
			chatKcByKind = out;
		}
		return chatKcByKind;
	}

	private void addKillSources(JPanel head, SourceRow sr, long headline)
	{
		if (headline < 0)
		{
			return;
		}
		String want = LocalStore.chatKind(sr.name);
		Map<String, Long> rows = new LinkedHashMap<>();
		putKind(rows, "Kill Log", LocalStore.killLogCounts(clogNow()), want);
		putKind(rows, "Said in chat", plugin.chatKills(), want);
		putKind(rows, "Collection log", LocalStore.pageKillLines(clogNow()), want);
		putKind(rows, "Running count", plugin.anchoredKills(), want);
		if (sr.loots > 0)
		{
			rows.put("Drops logged", (long) sr.loots);
		}
		Long dropped = taskKillsEver().get(sr.name);
		if (dropped != null && dropped > 0)
		{
			rows.put("Dropped on task", dropped);
		}
		boolean accounted = false;
		for (Long v : rows.values())
		{
			accounted |= v != null && v.longValue() == headline;
		}
		if (rows.size() < 2 || !accounted)
		{
			return;
		}
		final String key = "kcsrc:" + sr.name;
		head.add(quietHead("What says so", "", key));
		if (!openFolds.contains(key))
		{
			return;
		}
		for (Entry<String, Long> e : rows.entrySet())
		{
			head.add(row(e.getKey(), fmt(e.getValue())));
		}
	}

	private static void putKind(Map<String, Long> rows, String label,
		Map<String, Long> from, String want)
	{
		for (Entry<String, Long> e : from.entrySet())
		{
			if (LocalStore.chatKind(e.getKey()).equals(want) && e.getValue() > 0)
			{
				rows.put(label, e.getValue());
				return;
			}
		}
	}

	private Map<String, Long> taskKillsEverCache;

	private Map<String, Long> taskKillsEver()
	{
		if (taskKillsEverCache == null)
		{
			taskKillsEverCache = plugin.onTaskKills(Long.MIN_VALUE / 2, Long.MAX_VALUE / 2);
		}
		return taskKillsEverCache;
	}

	private void addAssignments(JPanel p, String npc)
	{
		long[] w = windowMs();
		List<LocalStore.Assignment> was = plugin.onTaskAssignments(npc, w[0], w[1]);
		if (was.isEmpty())
		{
			return;
		}
		p.add(group("Killed on task"));
		int cap = drillShown.getOrDefault("ontask:src:" + npc, ROW_CAP);
		int mounted = 0;
		for (LocalStore.Assignment a : was)
		{
			if (mounted++ >= cap)
			{
				p.add(expander("ontask:src:" + npc, cap, was.size()));
				break;
			}
			p.add(row("Task: " + a.task, fmt(a.killsHere)));
		}
		p.add(vgap(6));
	}

	private void addFloorRow(JPanel head, String name)
	{
		for (UntakenRow u : plugin.untakenSources())
		{
			if (u.qty > 0 && u.name.equalsIgnoreCase(name))
			{
				JPanel r = row("Left behind", fmt(u.qty) + " · " + gps(u.value)
					+ (u.kills > 0 ? " · " + count(u.kills, "kill") : ""));
				r.setToolTipText("Open what was left on the floor");
				link(r, () ->
				{
					detailSource = null;
					leftBehindSource = u.name;
					rebuild();
				});
				head.add(r);
				return;
			}
		}
	}

	private boolean minutesCoverPeriod()
	{
		if (wholeRecord())
		{
			return false;
		}
		if (sessionPeriod())
		{
			return true;
		}
		Span s = span();
		if (s == null)
		{
			return false;
		}
		for (String key : s.opening.counters.keySet())
		{
			if (StatKeys.isTime(key))
			{
				return true;
			}
		}
		return false;
	}

	private long minutesAt(String name, Map<String, Long> counters)
	{
		long minutes = counters.getOrDefault(StatKeys.timeKey(name), 0L);
		for (String npc : FOUGHT_AS.getOrDefault(kindOf(name),
			Collections.emptyList()))
		{
			minutes += counters.getOrDefault(StatKeys.timeKey(npc), 0L);
		}
		return minutes;
	}

	private JPanel buildSourceDetail(String name)
	{
		JPanel p = column();
		SourceRow found = null;
		for (SourceRow r : sources())
		{
			if (r.name.equals(name))
			{
				found = r;
				break;
			}
			found = found == null && r.name.equalsIgnoreCase(name) ? r : found;
		}
		final SourceRow sr = found;
		String[] row = wholeRecord() ? null : rowFor(lootWindow().sources, sr != null ? sr.name : name, sr != null);
		long[] inWindow = wholeRecord() ? null
			: row == null ? new long[2] : new long[]{safeParse(row[1]), safeParse(row[2])};
		String own = row != null ? row[0] : sr != null ? sr.name : name;
		final List<BagItem> bag = inWindow == null ? plugin.sourceItems(own)
			: new ArrayList<>(periodItems().getOrDefault(own, new ArrayList<>()));
		bag.sort(Comparator.comparingLong((BagItem b) -> b.value).reversed());
		final long other = inWindow == null ? 0 : inWindow[1] - tallyOf(bag)[1];
		final boolean unfiled = other > 0 || inWindow != null && !sessionPeriod()
			&& plugin.unfiledSources(window().start, window().end).contains(own);
		spaced(p, backRow(() -> copySourcePage(name)), 4);
		JPanel head = card(name);
		if (sr != null)
		{
			boolean killed = isKillSource(sr.name);
			long shown = inWindow != null ? inWindow[0]
				: killed ? standingKills(sr) : sr.loots;
			head.add(row(killed ? "Kills" : "Times looted", fmt(shown), accent()));
			long worth = inWindow != null ? inWindow[1] : sr.value;
			long over = inWindow != null ? inWindow[0] : sr.loots;
			head.add(row("Worth", gps(worth)
				+ (over > 0 ? " · " + gp(worth / Math.max(1, over))
					+ (killed ? " gp/drop" : " gp each") : "")));
			if (inWindow == null)
			{
				addKillSources(head, sr, killed ? shown : -1);
				addFloorRow(head, sr.name);
			}
			for (Entry<String, Long> pbLine : pageLines(sr.name, "pb_lines"))
			{
				head.add(row(pbLine.getKey(), clock(pbLine.getValue())));
			}
			for (Entry<String, Long> ln : logLines(sr.name))
			{
				head.add(row(ln.getKey(), fmt(ln.getValue())));
			}
			if (sr.pb != null)
			{
				JsonObject rec = records().get(low(sr.name));
				JsonObject recData = rec != null ? rec.getAsJsonObject("data") : null;
				JPanel best = row("Personal best", pb(sr.pb) + (rec != null
					? " · set " + day(safeLong(rec.get("ts"))) : ""));
				if (recData != null && recData.has("was"))
				{
					best.setToolTipText("Was " + pb(recData.get("was").getAsDouble()));
				}
				head.add(best);
			}
			double[] timed = inWindow == null ? new double[]{sr.timed, sr.timeSum}
				: lootWindow().times.getOrDefault(own, new double[2]);
			if (timed[0] > 0)
			{
				head.add(row("Average kill", pb(timed[1] / timed[0]) + " · "
					+ fmt((long) timed[0]) + " timed"));
			}
			long here = minutesAt(sr.name, inWindow == null ? counters() : periodCounters());
			if (here > 0 && minutesCoverPeriod())
			{
				boolean rate = killed && shown > 0 && here >= 30;
				head.add(row("Time here", hoursMinutes(here)
					+ (rate ? " · " + rateText(shown * 60.0 / here) + " kills/h" : "")));
			}
			if (sr.firstMs > 0 && !drawingCopy)
			{
				head.add(row("Tracked since",
					day(sr.firstMs)));
			}
			if (grindsCache == null && !grindsFetching && !drawingCopy)
			{
				grindsFetching = true;
				plugin.fetchGrinds(rows2 -> SwingUtilities.invokeLater(() ->
				{
					grindsFetching = false;
					if (rows2 == null)
					{
						return;
					}
					grindsCache = rows2;
					if (sr.name.equals(detailSource))
					{
						rebuildInPlace();
					}
				}));
			}
			if (grindsCache != null && !drawingCopy)
			{
				for (GrindBook.GrindRow g : grindsCache)
				{
					if (g.boss.equalsIgnoreCase(sr.name))
					{
						head.add(row("Chasing " + g.item,
							fmt(g.kc) + " / " + fmt(g.rate) + " kc",
							g.percentileDry >= 90 ? ACCENT_RED : null));
						break;
					}
				}
			}
		}
		spaced(p, head);
		String since = inWindow == null ? null : lootSince();
		if (sinceLine(since) != null)
		{
			spaced(p, note(sinceLine(since)), 5);
		}
		if (sr != null)
		{
			addAssignments(p, sr.name);
		}
		if (!bag.isEmpty() || unfiled)
		{
			JPanel grid = new JPanel(new GridLayout(0, 5, 3, 3));
			grid.setBackground(DARK);
			int sprites = 0;
			for (BagItem b : bag)
			{
				if (b.itemId <= 0)
				{
					continue;
				}
				if (sprites++ >= 10)
				{
					break;
				}
				grid.add(sprite(b.itemId, b.name, b.qty));
			}
			if (sprites > 0)
			{
				spaced(p, grid, 5);
			}
			p.add(group("Loot"));
			int cap = drillShown.getOrDefault(name, 25);
			addBagRows(p, bag.subList(0, Math.min(cap, bag.size())), cap, name);
			if (bag.size() > cap)
			{
				p.add(vgap(3));
				p.add(expander(name, cap, bag.size()));
			}
			addOther(p, gps(Math.max(0, other)), unfiled);
			return p;
		}
		return sr == null ? noted(p, "The journal has no drops from this source yet.")
			: inWindow != null ? nothing(p, "from " + name, since)
			: noted(p, "Items fill in as you play. The journal prices each drop the "
			+ "moment it lands.");
	}

	private JPanel logInWindow(JPanel p)
	{
		List<JsonObject> got = new ArrayList<>();
		for (JsonObject e : plugin.feedNewest(FEED_SCAN_DEEP))
		{
			if ("COLLECTION".equals(typeOf(e)) && insideWindow(safeLong(e.get("ts"))))
			{
				got.add(e);
			}
		}
		if (got.isEmpty())
		{
			return noted(p, "Nothing new was logged inside " + periodInSentence() + ".");
		}
		JPanel head = card("Collection log");
		head.add(row("Slots logged", fmt(got.size()), accent()));
		spaced(p, head);
		for (JsonObject e : got)
		{
			JsonObject d = obj(e, "data");
			final String name = str(d, "itemName", "new item");
			JPanel line = row(name, stamp(e));
			link(line, () -> openItem(name));
			p.add(line);
		}
		return p;
	}

	private JPanel buildLog()
	{
		JPanel p = column();
		if (!wholeRecord())
		{
			return logInWindow(p);
		}
		int[] standing = clogStanding();
		int fin = plugin.clogFinished();
		JPanel head = card("Collection log");
		if (standing != null)
		{
			head.add(row(fmt(standing[0]) + " / " + fmt(standing[1]),
				Math.round(100f * standing[0] / standing[1]) + "%", accent()));
			head.add(progress((float) standing[0] / standing[1]));
		}
		else if (fin > 0)
		{
			head.add(row("Slots obtained", fmt(fin), accent()));
			head.add(row("Open your log in game once for the total", ""));
		}
		else
		{
			head.add(row("Open your log in game once to fill this in", ""));
		}
		spaced(p, head);

		Map<String, Map<String, List<String>>> tax = taxonomy(plugin.gson());
		JPanel pills = new JPanel(new GridLayout(0, 3, 3, 3));
		pills.setBackground(DARK);
		for (String tab : tax.keySet())
		{
			pills.add(pill(tab, tab.equals(clogTab), 4, tabStanding(clogNow(), tab), () ->
			{
				clogTab = tab;
				clogPageSel = null;
				rebuild();
			}));
		}
		spaced(p, pills);

		JsonObject cl = clogNow();
		Obtained ob = obtained(cl);
		Map<String, Long> kcs = pageCounts(cl);

		Map<String, List<String>> pages = tax.getOrDefault(clogTab, new LinkedHashMap<>());
		for (Entry<String, List<String>> pg : pages.entrySet())
		{
			String page = pg.getKey();
			List<String> slots = pg.getValue();
			boolean[] lit = lightSlots(slots, ob.byPage.get(low(page)), ob.all,
				sharedSlotNames(plugin.gson()));
			int got = 0;
			for (boolean b : lit)
			{
				got += b ? 1 : 0;
			}
			Long kc = kcs.get(low(page));
			boolean open = page.equals(clogPageSel);
			boolean complete = got == slots.size() && !slots.isEmpty();
			JPanel rowP = row(page, got + "/" + slots.size()
				+ (kc != null && kc > 0 ? " · " + fmt(kc) + " kc" : ""),
				complete ? ACCENT_SESSION : null, complete);
			String lines = pageHeaderTip(cl, page);
			if (lines != null)
			{
				rowP.setToolTipText(lines);
			}
			link(rowP, () ->
			{
				clogPageSel = open ? null : page;
				rebuild();
			});
			p.add(rowP);
			if (open)
			{
				JPanel drill = cardPlain();
				boolean petPage = low(page).contains("pet");
				Map<String, LocalStore.PetRow> known = petPage
					? petsByName() : Collections.emptyMap();
				Map<String, GrindBook.PetChase> chases = petPage
					? plugin.petChases(slots) : Collections.emptyMap();
				List<List<JPanel>> detail = new ArrayList<>();
				boolean anyDetail = false;
				for (int i = 0; i < slots.size(); i++)
				{
					String key = low(slots.get(i));
					List<JPanel> d = petDetail(lit[i], known.get(key), chases.get(key));
					detail.add(d);
					anyDetail |= !d.isEmpty();
				}
				if (anyDetail)
				{
					spaced(drill, note("Click pet to see odds. Skilling odds are based "
						+ "on current level."), 3);
				}
				Map<String, Long> landed = landedSlots();
				for (int i = 0; i < slots.size(); i++)
				{
					String slot = slots.get(i);
					JPanel r = row(slot, "",
						lit[i] || known.get(low(slot)) != null
							? ACCENT_SESSION : ACCENT_RED, true);
					Long when = landed.get(low(slot));
					if (when != null)
					{
						r.setToolTipText(tip(slot, new String[]{"Landed"},
							new String[]{dated(when)}));
					}
					drill.add(r);
					List<JPanel> d = detail.get(i);
					if (d.isEmpty())
					{
						continue;
					}
					String foldKey = "pets:" + page + ":" + low(slot);
					link(r, () -> toggleFold(foldKey));
					if (foldOpen(foldKey))
					{
						for (JPanel line : d)
						{
							drill.add(line);
						}
					}
				}
				spaced(p, drill, 3);
			}
		}

		if ("Other".equals(clogTab))
		{
			Set<String> known = new HashSet<>();
			for (Map<String, List<String>> tabPages : tax.values())
			{
				for (String pageName : tabPages.keySet())
				{
					known.add(low(pageName));
				}
			}
			List<String> strangers = new ArrayList<>();
			for (String pageName : ob.byPage.keySet())
			{
				if (!known.contains(pageName))
				{
					strangers.add(pageName);
				}
			}
			Collections.sort(strangers);
			if (!strangers.isEmpty())
			{
				p.add(vgap(6));
				p.add(group("NEW SINCE THIS RELEASE"));
				for (String pageName : strangers)
				{
					Map<String, Long> held = ob.byPage.get(pageName);
					Long kc = kcs.get(pageName);
					p.add(row(prettyPage(pageName),
						fmt(held == null ? 0 : held.size()) + " held"
							+ (kc != null && kc > 0 ? " \u00b7 " + fmt(kc) + " kc" : "")));
				}
				p.add(ghostRow("Chronicle has no slot list for "
					+ (strangers.size() == 1 ? "this page" : "these pages")
					+ " yet, so only what you hold is known.", ""));
			}
		}
		return p;
	}

	private static String chaseSources(GrindBook.PetChase chase)
	{
		if (chase.activity != null)
		{
			return chase.activity + ", " + fmt(chase.kc) + " " + chase.unit;
		}
		return sourceLine(chase.sources, chase.sources.size(), "").whole();
	}

	private static final String ELLIPSIS = "…";
	private static final int NAME_FLOOR = 3;
	private static final JLabel MEASURE = new JLabel();

	static FontMetrics rowMetrics()
	{
		return MEASURE.getFontMetrics(FontManager.getRunescapeFont());
	}

	private static int scrollbarWidth()
	{
		return OVERLAY_BAR_W;
	}

	private static final int OVERLAY_BAR_W = 5;

	private static void overlayBar(JScrollPane scroll)
	{
		javax.swing.JScrollBar bar = scroll.getVerticalScrollBar();
		OverlayScrollBarUI ui = new OverlayScrollBarUI();
		bar.setUI(ui);
		scroll.addMouseWheelListener(e -> ui.wake());
		bar.setOpaque(false);
		bar.setPreferredSize(new Dimension(OVERLAY_BAR_W, 0));
	}

	private static final class OverlayScrollBarUI
		extends javax.swing.plaf.basic.BasicScrollBarUI
	{
		private static final int IDLE_MS = 700;
		private static final int STEP_MS = 40;
		private static final float STEP = 0.12f;
		private static final Color THUMB = new Color(0xB0, 0xB0, 0xB0);

		private float alpha;
		private long lastMove;
		private Timer fader;

		@Override
		protected JButton createDecreaseButton(int orientation)
		{
			return nothing();
		}

		@Override
		protected JButton createIncreaseButton(int orientation)
		{
			return nothing();
		}

		private static JButton nothing()
		{
			JButton b = new JButton();
			Dimension none = new Dimension(0, 0);
			b.setPreferredSize(none);
			b.setMinimumSize(none);
			b.setMaximumSize(none);
			b.setFocusable(false);
			return b;
		}

		@Override
		protected void installListeners()
		{
			super.installListeners();
			scrollbar.addAdjustmentListener(e ->
			{
				if (e.getValueIsAdjusting())
				{
					wake();
				}
			});
		}

		@Override
		protected void paintTrack(java.awt.Graphics g, JComponent c,
			Rectangle bounds)
		{
		}

		@Override
		protected void paintThumb(java.awt.Graphics g, JComponent c,
			Rectangle t)
		{
			if (alpha <= 0.02f || t.isEmpty())
			{
				return;
			}
			java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
			g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
				java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setComposite(java.awt.AlphaComposite.getInstance(
				java.awt.AlphaComposite.SRC_OVER, Math.min(1f, alpha)));
			g2.setColor(THUMB);
			int h = Math.max(OVERLAY_BAR_W * 2, t.height - 4);
			g2.fillRoundRect(t.x, t.y + 2, OVERLAY_BAR_W, h,
				OVERLAY_BAR_W, OVERLAY_BAR_W);
			g2.dispose();
		}

		void wake()
		{
			lastMove = System.currentTimeMillis();
			alpha = 1f;
			scrollbar.repaint();
			if (fader == null)
			{
				fader = new Timer(STEP_MS, e -> tick());
			}
			if (!fader.isRunning())
			{
				fader.start();
			}
		}

		private void tick()
		{
			if (scrollbar == null || !scrollbar.isShowing())
			{
				alpha = 0f;
				fader.stop();
				return;
			}
			if (System.currentTimeMillis() - lastMove < IDLE_MS)
			{
				return;
			}
			alpha -= STEP;
			if (alpha <= 0f)
			{
				alpha = 0f;
				fader.stop();
			}
			scrollbar.repaint();
		}

		@Override
		public void uninstallUI(JComponent c)
		{
			if (fader != null)
			{
				fader.stop();
			}
			super.uninstallUI(c);
		}
	}

	static int chaseRoom(String share, FontMetrics fm)
	{
		return PluginPanel.PANEL_WIDTH + PluginPanel.SCROLLBAR_WIDTH
			- 2 * PANEL_INSET - scrollbarWidth() - 2 * CARD_INSET
			- 2 * ROW_INSET - ROW_GAP - fm.stringWidth(share);
	}

	private static final class Line
	{
		final List<String> pieces = new ArrayList<>();
		final List<Integer> names = new ArrayList<>();

		void fixed(String s)
		{
			pieces.add(s);
		}

		void name(String s)
		{
			names.add(pieces.size());
			pieces.add(s);
		}

		String whole()
		{
			StringBuilder sb = new StringBuilder();
			for (String s : pieces)
			{
				sb.append(s);
			}
			return sb.toString();
		}
	}

	private static String stub(String name, int keep)
	{
		if (name.length() <= keep)
		{
			return name;
		}
		int end = keep;
		while (end > 0 && name.charAt(end - 1) == ' ')
		{
			end--;
		}
		return name.substring(0, end) + ELLIPSIS;
	}

	private static String fitLine(Line line, List<Integer> order, int floor,
		FontMetrics fm, int avail)
	{
		Line work = new Line();
		work.pieces.addAll(line.pieces);
		if (fm.stringWidth(work.whole()) <= avail)
		{
			return work.whole();
		}
		for (int idx : order)
		{
			String name = line.pieces.get(idx);
			for (int keep = name.length() - 1; keep >= floor; keep--)
			{
				work.pieces.set(idx, stub(name, keep));
				String s = work.whole();
				if (fm.stringWidth(s) <= avail)
				{
					return s;
				}
			}
			String shortest = stub(name, floor);
			work.pieces.set(idx,
				fm.stringWidth(shortest) < fm.stringWidth(name) ? shortest : name);
		}
		return null;
	}

	private static Line sourceLine(List<GrindBook.PetSource> src, int kept, String mark)
	{
		Line l = new Line();
		for (int i = 0; i < kept; i++)
		{
			if (i > 0)
			{
				l.fixed(" · ");
			}
			l.name(src.get(i).boss);
			l.fixed(", kc " + fmt(src.get(i).kc));
		}
		if (kept < src.size())
		{
			l.fixed(mark + (src.size() - kept));
		}
		return l;
	}

	private static final String[] DROP_MARKS = {" · +", " +"};

	private static List<Integer> tailFirst(Line l, int from)
	{
		List<Integer> order = new ArrayList<>(l.names.subList(from, l.names.size()));
		Collections.reverse(order);
		return order;
	}

	static String fitChase(GrindBook.PetChase chase, String share)
	{
		FontMetrics fm = rowMetrics();
		int avail = chaseRoom(share, fm);
		if (chase.activity != null)
		{
			Line l = new Line();
			l.name(chase.activity);
			l.fixed(", " + fmt(chase.kc) + " ");
			l.name(chase.unit);
			String s = fitLine(l, tailFirst(l, 0), NAME_FLOOR, fm, avail);
			if (s == null)
			{
				s = fitLine(l, tailFirst(l, 0), 1, fm, avail);
			}
			return s != null ? s : l.whole();
		}
		List<GrindBook.PetSource> src = chase.sources;
		if (src.isEmpty())
		{
			return "";
		}
		for (int kept = src.size(); kept >= 1; kept--)
		{
			for (String mark : kept < src.size() ? DROP_MARKS : new String[]{""})
			{
				Line l = sourceLine(src, kept, mark);
				String s = fitLine(l, tailFirst(l, 1), NAME_FLOOR, fm, avail);
				if (s != null)
				{
					return s;
				}
			}
		}
		Line l = sourceLine(src, 1, DROP_MARKS[DROP_MARKS.length - 1]);
		String s = fitLine(l, l.names, 1, fm, avail);
		return s != null ? s : l.whole();
	}

	private static String chaseTip(GrindBook.PetChase chase)
	{
		double pct = chase.percentileDry;
		String share = pct < 1 ? "Under 1%" : pct > 99 ? "Over 99%" : Math.round(pct) + "%";
		StringBuilder sb = new StringBuilder(share + " of players have " + chase.pet
			+ " by this point. " + chaseSources(chase));
		if (chase.activity != null && chase.sources.size() > 1)
		{
			sb.append(", mostly ").append(low(chase.sources.get(0).boss));
		}
		if (chase.level > 0)
		{
			sb.append(". Priced at ").append(chase.level)
				.append(", the level you hold now, not the level each one was rolled at");
		}
		return sb.append(".").toString();
	}

	private static List<JPanel> petDetail(boolean lit, LocalStore.PetRow pet,
		GrindBook.PetChase chase)
	{
		List<JPanel> out = new ArrayList<>();
		if (pet != null)
		{
			StringBuilder line = new StringBuilder();
			if (pet.source != null && !pet.source.isEmpty())
			{
				line.append(pet.source);
				if (pet.kc > 0)
				{
					line.append(isSkill(pet.source)
						? ", " + fmt(pet.kc) + " xp"
						: ", kc " + fmt(pet.kc));
				}
			}
			if (line.length() > 0)
			{
				out.add(ghostRow(line.toString(), pet.ts > 0
					? day(pet.ts) : ""));
			}
		}
		else if (!lit && chase != null)
		{
			String share = holdShare(chase);
			JPanel r = ghostRow(fitChase(chase, share), share,
				chase.percentileDry >= 90 ? ACCENT_RED : null);
			out.add(tipped(r, chaseTip(chase)));
		}
		return out;
	}

	private static JPanel tipped(JPanel r, String tip)
	{
		r.setToolTipText(tip);
		for (Component c : r.getComponents())
		{
			if (c instanceof JComponent)
			{
				((JComponent) c).setToolTipText(tip);
			}
		}
		return r;
	}

	private static String holdShare(GrindBook.PetChase chase)
	{
		double pct = chase.percentileDry;
		if (pct < 1)
		{
			return "<1% have";
		}
		if (pct > 99)
		{
			return ">99% have";
		}
		return Math.round(pct) + "% have";
	}

	private static boolean isSkill(String source)
	{
		for (Skill sk : Skill.values())
		{
			if (sk.name().equalsIgnoreCase(source))
			{
				return true;
			}
		}
		return false;
	}

	private Map<String, LocalStore.PetRow> petsByName()
	{
		Map<String, LocalStore.PetRow> out = new LinkedHashMap<>();
		for (LocalStore.PetRow r : plugin.pets())
		{
			out.putIfAbsent(low(r.name), r);
		}
		return out;
	}

	private static final class Obtained
	{
		final Map<String, Long> all = new LinkedHashMap<>();
		final Map<String, Map<String, Long>> byPage = new LinkedHashMap<>();
	}

	private static String prettyPage(String key)
	{
		StringBuilder sb = new StringBuilder(key.length());
		boolean head = true;
		for (int i = 0; i < key.length(); i++)
		{
			char c = key.charAt(i);
			sb.append(head ? Character.toUpperCase(c) : c);
			head = c == ' ' || c == '(';
		}
		return sb.toString();
	}

	private int[] clogStanding()
	{
		int avail = plugin.clogAvailable();
		return avail > 0 ? new int[]{plugin.clogFinished(), avail} : null;
	}

	private static Obtained obtained(JsonObject cl)
	{
		Obtained o = new Obtained();
		for (Entry<String, JsonElement> e
			: obj(cl, "clog_items").entrySet())
		{
			o.all.merge(low(e.getKey()), safeLong(e.getValue()), Math::max);
		}
		for (Entry<String, JsonElement> pg
			: obj(cl, "by_cat").entrySet())
		{
			if (!pg.getValue().isJsonObject())
			{
				continue;
			}
			Map<String, Long> items = new LinkedHashMap<>();
			for (Entry<String, JsonElement> it
				: pg.getValue().getAsJsonObject().entrySet())
			{
				items.merge(low(it.getKey()),
					safeLong(it.getValue()), Math::max);
			}
			o.byPage.put(low(pg.getKey()), items);
		}
		return o;
	}

	private static boolean[] lightSlots(List<String> slots, Map<String, Long> pageItems,
		Map<String, Long> owned, Set<String> sharedNames)
	{
		boolean[] lit = new boolean[slots.size()];
		Map<String, Integer> dupes = new LinkedHashMap<>();
		for (String slot : slots)
		{
			dupes.merge(low(slot), 1, Integer::sum);
		}
		Map<String, Integer> seen = new LinkedHashMap<>();
		for (int i = 0; i < slots.size(); i++)
		{
			String key = low(slots.get(i));
			long onPage = pageItems != null ? pageItems.getOrDefault(key, 0L) : 0L;
			boolean globalMaySpeak = pageItems == null || !sharedNames.contains(key);
			long global = globalMaySpeak ? owned.getOrDefault(key, 0L) : 0L;
			long have = Math.max(onPage, global);
			if (dupes.get(key) > 1)
			{
				int idx = seen.merge(key, 1, Integer::sum) - 1;
				lit[i] = idx < have;
			}
			else
			{
				lit[i] = have > 0
					|| (pageItems != null && pageItems.containsKey(key))
					|| (globalMaySpeak && owned.containsKey(key));
			}
		}
		return lit;
	}

	private static Set<String> sharedSlotNames;

	private static synchronized Set<String> sharedSlotNames(
		Gson gson)
	{
		if (sharedSlotNames != null)
		{
			return sharedSlotNames;
		}
		Map<String, Integer> homes = new LinkedHashMap<>();
		for (Entry<String, Map<String, List<String>>> tab : taxonomy(gson).entrySet())
		{
			for (Entry<String, List<String>> pg : tab.getValue().entrySet())
			{
				Set<String> onThisPage = new HashSet<>();
				for (String slot : pg.getValue())
				{
					onThisPage.add(low(slot));
				}
				for (String slot : onThisPage)
				{
					homes.merge(slot, 1, Integer::sum);
				}
			}
		}
		Set<String> shared = new HashSet<>();
		for (Entry<String, Integer> e : homes.entrySet())
		{
			if (e.getValue() > 1)
			{
				shared.add(e.getKey());
			}
		}
		sharedSlotNames = shared;
		return shared;
	}

	private static long safeLong(JsonElement e)
	{
		try
		{
			return e != null && !e.isJsonNull() ? e.getAsLong() : 0;
		}
		catch (RuntimeException ex)
		{
			return 0;
		}
	}

	private static synchronized Map<String, Map<String, List<String>>> taxonomy(
		Gson gson)
	{
		if (taxonomy != null)
		{
			return taxonomy;
		}
		Map<String, Map<String, List<String>>> out = new LinkedHashMap<>();
		try (java.io.InputStream in = ChroniclePanel.class.getResourceAsStream("clog_taxonomy.json"))
		{
			if (in != null)
			{
				JsonObject rootTax = gson.fromJson(
					new InputStreamReader(in, StandardCharsets.UTF_8),
					JsonObject.class);
				for (Entry<String, JsonElement> tab : rootTax.entrySet())
				{
					Map<String, List<String>> pages = new LinkedHashMap<>();
					for (Entry<String, JsonElement> pg
						: tab.getValue().getAsJsonObject().entrySet())
					{
						List<String> slots = new ArrayList<>();
						for (JsonElement it : pg.getValue().getAsJsonArray())
						{
							slots.add(it.getAsString());
						}
						pages.put(pg.getKey(), slots);
					}
					out.put(tab.getKey(), pages);
				}
			}
		}
		catch (Exception e)
		{
		}
		taxonomy = out;
		return out;
	}

	private void addPaceLine(JPanel p, String section)
	{
		PaceBook.Pace pace;
		try
		{
			pace = plugin.pace(section);
		}
		catch (RuntimeException e)
		{
			return;
		}
		if (pace == null)
		{
			return;
		}
		if (pace.hasHorizon())
		{
			String target = pace.targetLevel != null
				? String.valueOf(pace.targetLevel) : "200m";
			p.add(ghostRow(target + " in " + fmt(pace.daysOfPlay)
				+ (pace.daysOfPlay == 1 ? " day of play" : " days of play"),
				gp((long) pace.xpPerActiveDay) + "/day"));
			if (pace.activeDays < 3)
			{
				p.add(ghostRow("measured over " + pace.activeDays
					+ (pace.activeDays == 1 ? " day" : " days"), ""));
			}
		}
		else if (pace.dormant() && pace.lastActive != null)
		{
			p.add(ghostRow("last moved " + pace.lastActive.format(TASK_DAY), ""));
		}
	}

	private final Set<String> openFolds = new HashSet<>();

	private static final String FOLD_HOME_XP = "home:xp";
	private static final String FOLD_HOME_DAMAGE = "home:damage";
	private static final List<String> DAMAGE_SPLIT = Arrays.asList(
		"damageDealtMelee", "damageDealtRanged", "damageDealtMagic");

	private boolean foldOpen(String key)
	{
		return openFolds.contains(key);
	}

	private void toggleFold(String key)
	{
		if (!openFolds.remove(key))
		{
			openFolds.add(key);
		}
		rebuildInPlace();
	}

	private void rebuildInPlace()
	{
		keepScroll = true;
		try
		{
			rebuild();
		}
		finally
		{
			keepScroll = false;
		}
	}

	private List<SourceRow> buildSources;
	private JsonObject buildClog;
	private Span buildSpan;
	private boolean spanAsked;

	private List<SourceRow> sources()
	{
		if (buildSources == null)
		{
			buildSources = plugin.dropSources();
		}
		return buildSources;
	}

	private JsonObject clogNow()
	{
		if (buildClog == null)
		{
			buildClog = plugin.clogSnapshot();
		}
		return buildClog;
	}

	private Map<String, Long> consumVals = new LinkedHashMap<>();

	private long resourcesDropped;

	private Map<String, Long> withLedgerSpend(Map<String, Long> base)
	{
		Map<String, Long> out = new LinkedHashMap<>(base);
		long food = 0;
		long potions = 0;
		for (Entry<String, Long> e : plugin.consumableValues().entrySet())
		{
			long v = e.getValue() == null ? 0 : e.getValue();
			if (v <= 0)
			{
				continue;
			}
			if (e.getKey().endsWith("Eaten"))
			{
				food += v;
			}
			else if (e.getKey().endsWith("Doses"))
			{
				potions += v;
			}
		}
		if (food > 0)
		{
			out.merge("foodConsumedValue", food, Math::max);
		}
		if (potions > 0)
		{
			out.merge("potionsConsumedValue", potions, Math::max);
		}
		if (food + potions > 0)
		{
			out.merge("consumedValue", food + potions, Math::max);
		}
		return out;
	}

	private void statRows(JPanel p, List<Entry<String, Long>> rows)
	{
		for (Entry<String, Long> e : rows)
		{
			p.add(row(StatRegistry.rowLabel(e.getKey()), rowValue(e)));
		}
	}

	private String rowValue(Entry<String, Long> e)
	{
		String base = value(e);
		if (e.getKey().equals("resourcesGatheredValue") && resourcesDropped > 0)
		{
			return base + " · " + gp(resourcesDropped) + " dropped";
		}
		Long cv = wholeRecord() ? consumVals.get(e.getKey()) : null;
		return cv != null && cv > 0 ? base + " · " + gps(cv) : base;
	}

	private JPanel buildAllTrackers()
	{
		JPanel p = backPage();
		consumVals = plugin.consumableValues();
		Map<String, Long> counters = countersForPeriod();
		if (counters == null)
		{
			p.add(noPeriod());
			return p;
		}
		resourcesDropped = counters.getOrDefault("resourcesDroppedValue", 0L);
		Map<String, Map<String, List<Entry<String, Long>>>> filed = new LinkedHashMap<>();
		for (String fam : StatRegistry.FAMILIES)
		{
			filed.put(fam, new LinkedHashMap<>());
		}
		int kept = 0;
		for (Entry<String, Long> e : counters.entrySet())
		{
			if (e.getValue() == null || e.getValue() <= 0 || StatRegistry.hidden(e.getKey()))
			{
				continue;
			}
			Map<String, List<Entry<String, Long>>> fam =
				filed.computeIfAbsent(StatRegistry.family(e.getKey()), k -> new LinkedHashMap<>());
			String sec = StatRegistry.subgroup(e.getKey());
			String heads = sec.isEmpty()
				? StatRegistry.headOf(StatRegistry.family(e.getKey()), e.getKey()) : null;
			fam.computeIfAbsent(heads != null ? heads : sec, k -> new ArrayList<>()).add(e);
			kept++;
		}
		JPanel head = card("Trackers");
		head.add(row("Counters", fmt(kept), accent()));
		head.add(row("Reading", wholeRecord() ? "Lifetime" : window().label));
		spaced(p, head);
		if (kept == 0)
		{
			return noted(p, "Nothing tracked inside " + periodInSentence() + ".");
		}
		for (Entry<String, Map<String, List<Entry<String, Long>>>> fam : filed.entrySet())
		{
			if (fam.getValue().isEmpty())
			{
				continue;
			}
			p.add(group(fam.getKey()));
			for (Entry<String, List<Entry<String, Long>>> sec : fam.getValue().entrySet())
			{
				List<Entry<String, Long>> rows = sec.getValue();
				rows.sort(StatRegistry::compareRows);
				if (!sec.getKey().isEmpty())
				{
					p.add(ghostRow(sec.getKey(), ""));
				}
				statRows(p, rows);
			}
			p.add(vgap(4));
		}
		return p;
	}

	private static final String FOLD_DEATHS = "Combat:deaths";

	private void addDeathsFold(JPanel p, String figure)
	{
		JPanel head = row("Deaths", figure);
		foldHead(head, FOLD_DEATHS, "Who dealt them");
		p.add(head);
		if (!foldOpen(FOLD_DEATHS))
		{
			return;
		}
		Map<String, long[]> killers = new LinkedHashMap<>();
		for (JsonObject e : plugin.feedNewest(FEED_SCAN_DEEP))
		{
			long ts = safeLong(e.get("ts"));
			if (!"DEATH".equals(typeOf(e)) || !insideWindow(ts))
			{
				continue;
			}
			JsonObject d = obj(e, "data");
			String who = has(d, "killerName") ? d.get("killerName").getAsString() : "Unknown";
			long[] t = killers.computeIfAbsent(who, k -> new long[2]);
			t[0]++;
			t[1] = Math.max(t[1], ts);
		}
		List<Entry<String, long[]>> ranked = new ArrayList<>(killers.entrySet());
		ranked.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
		for (Entry<String, long[]> k : ranked)
		{
			JPanel r = row(k.getKey(), fmt(k.getValue()[0]) + " · last "
				+ day(k.getValue()[1]));
			if (!"Unknown".equals(k.getKey()))
			{
				final String who = k.getKey();
				link(r, () -> openSourceLoose(who));
			}
			p.add(r);
		}
	}

	private JPanel buildStats()
	{
		JPanel p = column();
		consumVals = plugin.consumableValues();
		String[] families = tab == Tab.RECORD
			? new String[]{"Ledger & Roads", "Living"}
			: StatRegistry.FAMILIES;
		JPanel pills = new JPanel(new GridLayout(0, 2, 3, 3));
		pills.setBackground(DARK);
		for (String fam : families)
		{
			pills.add(pill(fam, fam.equals(statsFamily), 7, null, () ->
			{
				statsFamily = fam;
				rebuildInPlace();
			}));
		}
		p.add(pills);
		if (tab == Tab.TRACKERS && !allTrackers)
		{
			p.add(moreRow("every counter in one place", this::openAllTrackers));
		}
		p.add(vgap(4));

		Map<String, Long> counters = countersForPeriod();
		if (counters == null)
		{
			p.add(noPeriod());
			return p;
		}
		resourcesDropped = counters.getOrDefault("resourcesDroppedValue", 0L);
		Map<String, List<Entry<String, Long>>> rowsBySection = new LinkedHashMap<>();
		Map<String, Long> floorTotals = new LinkedHashMap<>();
		for (Entry<String, Long> e : counters.entrySet())
		{
			if (e.getValue() == 0 || StatRegistry.hidden(e.getKey())
				|| !StatRegistry.family(e.getKey()).equals(statsFamily))
			{
				continue;
			}
			String sec = StatRegistry.subgroup(e.getKey());
			String heads = sec.isEmpty() ? StatRegistry.headOf(statsFamily, e.getKey()) : null;
			if (StatRegistry.isFloor(e.getKey()) || heads != null)
			{
				floorTotals.merge(heads != null ? heads : sec, e.getValue(), Long::sum);
				continue;
			}
			rowsBySection.computeIfAbsent(sec, k -> new ArrayList<>()).add(e);
		}
		if (rowsBySection.isEmpty() && floorTotals.isEmpty())
		{
			String unkept = notCounting(false);
			return noted(p, unkept != null ? unkept : wholeRecord()
				? "Nothing under " + statsFamily + " yet."
				: "Nothing under " + statsFamily + " inside " + periodInSentence() + ".");
		}

		List<Entry<String, Long>> destRows = statsFamily.equals("Ledger & Roads")
			? rowsBySection.remove("Destinations") : null;
		if (destRows != null && !rowsBySection.containsKey("Teleports")
			&& !floorTotals.containsKey("Teleports"))
		{
			rowsBySection.put("Destinations", destRows);
			destRows = null;
		}

		List<String> order = sectionOrder(rowsBySection, floorTotals);
		for (String sec : order)
		{
			List<Entry<String, Long>> rows =
				rowsBySection.getOrDefault(sec, new ArrayList<>());
			rows.sort(StatRegistry::compareRows);
			long floor = floorTotals.getOrDefault(sec, 0L);

			if (sec.isEmpty())
			{
				for (Entry<String, Long> e : rows)
				{
					if ("deaths".equals(e.getKey()) && e.getValue() > 0)
					{
						addDeathsFold(p, rowValue(e));
						continue;
					}
					p.add(row(StatRegistry.rowLabel(e.getKey()), rowValue(e)));
				}
				continue;
			}

			if (rows.isEmpty() && floor == 0)
			{
				continue;
			}

			long typedSum = 0;
			boolean anyTyped = false;
			long shown = 0;
			for (Entry<String, Long> e : rows)
			{
				shown += e.getValue();
				if (StatRegistry.typed(e.getKey()))
				{
					anyTyped = true;
					typedSum += e.getValue();
				}
			}
			long ghost = anyTyped && floor - typedSum >= 1 ? floor - typedSum : 0;
			if (sec.equals("Teleports") && floor - shown >= 1)
			{
				ghost = floor - shown;
			}
			long total = Math.max(shown + ghost, floor);

			boolean foldable = statsFamily.equals("Skilling")
				|| sec.equals("Food") || sec.equals("Potions")
				|| sec.equals("Teleports") || sec.equals("Destinations")
				|| sec.equals("Thralls");
			if (!foldable)
			{
				p.add(group(sec));
				statRows(p, rows);
				continue;
			}

			String stateKey = statsFamily + ":" + sec;
			boolean open = foldOpen(stateKey);
			long secGp = 0;
			if (wholeRecord())
			{
				for (Entry<String, Long> e : rows)
				{
					Long cv = consumVals.get(e.getKey());
					if (cv != null)
					{
						secGp += cv;
					}
				}
			}
			else if (sec.equals("Food") || sec.equals("Potions"))
			{
				String gpKey = sec.equals("Food") ? "foodConsumedValue" : "potionsConsumedValue";
				Span s = span();
				if (sessionPeriod() || (s != null && s.opening.counters.containsKey(gpKey)))
				{
					secGp = counters.getOrDefault(gpKey, 0L);
				}
			}
			p.add(quietHead(sec, fmt(total) + tail(secGp),
				stateKey));
			if (open)
			{
				if (statsFamily.equals("Skilling"))
				{
					addPaceLine(p, sec);
				}
				boolean nested = statsFamily.equals("Skilling")
					&& addCraftNested(p, sec, rows, counters);
				if (!nested)
				{
					statRows(p, rows);
					if (rows.isEmpty() && floor > 0)
					{
						List<Entry<String, Long>> floors = new ArrayList<>();
						for (String fk : StatRegistry.floorKeys(sec))
						{
							long fv = counters.getOrDefault(fk, 0L);
							if (fv > 0 && !StatRegistry.hidden(fk))
							{
								floors.add(new AbstractMap.SimpleEntry<>(fk, fv));
							}
						}
						floors.sort(StatRegistry::compareRows);
						for (Entry<String, Long> fe : floors)
						{
							p.add(row(StatRegistry.label(fe.getKey()), fmt(fe.getValue())));
						}
						if (!floors.isEmpty())
						{
							ghost = 0;
						}
					}
					if (ghost > 0)
					{
						p.add(ghostRow(sec.equals("Teleports") ? "Other means" : "Other",
							fmt(ghost)));
					}
				}
				if (sec.equals("Teleports") && destRows != null && !destRows.isEmpty())
				{
					addDestinationsFold(p, destRows);
				}
			}
		}
		return p;
	}

	private List<String> sectionOrder(Map<String, List<Entry<String, Long>>> rowsBySection,
		Map<String, Long> floorTotals)
	{
		LinkedHashSet<String> present = new LinkedHashSet<>();
		present.addAll(rowsBySection.keySet());
		present.addAll(floorTotals.keySet());
		List<String> order = new ArrayList<>();
		if (statsFamily.equals("Skilling"))
		{
			List<String> crafts = new ArrayList<>(present);
			crafts.sort(Comparator.comparingLong((String s) ->
			{
				long floor = floorTotals.getOrDefault(s, 0L);
				if (floor > 0)
				{
					return floor;
				}
				long sum = 0;
				for (Entry<String, Long> e
					: rowsBySection.getOrDefault(s, new ArrayList<>()))
				{
					sum += e.getValue();
				}
				return sum;
			}).reversed());
			order.addAll(crafts);
		}
		else
		{
			for (String sec : StatRegistry.fixedSections(statsFamily))
			{
				if (present.remove(sec))
				{
					order.add(sec);
				}
			}
			order.addAll(present);
		}
		return order;
	}

	private boolean addCraftNested(JPanel p, String craft,
		List<Entry<String, Long>> rows, Map<String, Long> counters)
	{
		Map<String, List<Entry<String, Long>>> byVerb = new LinkedHashMap<>();
		List<Entry<String, Long>> leaves = new ArrayList<>();
		for (Entry<String, Long> e : rows)
		{
			String suf = StatRegistry.suffixOf(e.getKey());
			if (suf == null)
			{
				leaves.add(e);
			}
			else
			{
				byVerb.computeIfAbsent(suf, k -> new ArrayList<>()).add(e);
			}
		}
		if (byVerb.size() < 2)
		{
			return false;
		}
		for (Entry<String, Long> e : leaves)
		{
			p.add(row(StatRegistry.rowLabel(e.getKey()), value(e)));
		}
		List<String> verbs = new ArrayList<>(byVerb.keySet());
		Map<String, Long> verbTotal = new LinkedHashMap<>();
		for (String verb : verbs)
		{
			String floorKey = StatRegistry.suffixFloor(craft, verb);
			long floorVal = floorKey != null ? counters.getOrDefault(floorKey, 0L) : 0L;
			long sum = 0;
			for (Entry<String, Long> e : byVerb.get(verb))
			{
				sum += e.getValue();
			}
			verbTotal.put(verb, Math.max(floorVal, sum));
		}
		verbs.sort(Comparator.comparingLong(
			(String v) -> verbTotal.getOrDefault(v, 0L)).reversed());
		for (String verb : verbs)
		{
			String stateKey = "Skilling:" + craft + ":" + verb;
			boolean open = foldOpen(stateKey);
			p.add(subHead(StatRegistry.suffixLabel(verb),
				fmt(verbTotal.getOrDefault(verb, 0L)), stateKey));
			if (open)
			{
				long sum = 0;
				for (Entry<String, Long> e : byVerb.get(verb))
				{
					p.add(row(StatRegistry.rowLabel(e.getKey()), rowValue(e)));
					sum += e.getValue();
				}
				long verbGhost = verbTotal.get(verb) - sum;
				if (verbGhost >= 1)
				{
					p.add(ghostRow("Other", fmt(verbGhost)));
				}
			}
		}
		return true;
	}

	private void addDestinationsFold(JPanel p, List<Entry<String, Long>> destRows)
	{
		destRows.sort(StatRegistry::compareRows);
		long sum = 0;
		for (Entry<String, Long> e : destRows)
		{
			sum += e.getValue();
		}
		String stateKey = "Ledger & Roads:Destinations";
		boolean open = foldOpen(stateKey);
		p.add(subHead("Destinations", fmt(sum), stateKey));
		if (open)
		{
			for (Entry<String, Long> e : destRows)
			{
				p.add(row(StatRegistry.label(e.getKey()), value(e)));
			}
		}
	}

	private JPanel subHead(String label, String totalStr, String stateKey)
	{
		JPanel head = row(label, totalStr);
		JLabel name = styled(part(head, BorderLayout.CENTER), small(), dim());
		head.setBorder(pad(3, 10, 1, 2));
		link(head, () -> toggleFold(stateKey));
		return head;
	}

	private static String value(Entry<String, Long> e)
	{
		return StatRegistry.isGp(e.getKey()) ? gps(e.getValue()) : fmt(e.getValue());
	}

	private static JPanel ghostRow(String left, String right)
	{
		return ghostRow(left, right, null);
	}

	private static JPanel ghostRow(String left, String right, Color rightColor)
	{
		JPanel r = row(left, right, rightColor);
		part(r, BorderLayout.CENTER)
			.setForeground(dim().darker());
		return r;
	}

	private static final int HISTORY_FEED_SCAN = 2000;
	private static final int FEED_SCAN_DEEP = 4000;

	private TreeMap<LocalDate, Baseline> historySpine;
	private List<JsonObject> historyFeed = new ArrayList<>();
	private SlayerJourney historyJourney;
	private LocalDate historyDay;
	private long historyFeedTs;
	private boolean historyGathering;
	private int historyEpoch;

	private static final class HistoryData
	{
		final TreeMap<LocalDate, Baseline> spine;
		final List<JsonObject> feed;
		final SlayerJourney journey;
		final LocalDate day;

		HistoryData(TreeMap<LocalDate, Baseline> spine,
			List<JsonObject> feed, SlayerJourney journey,
			LocalDate day)
		{
			this.spine = spine;
			this.feed = feed;
			this.journey = journey;
			this.day = day;
		}
	}

	private void gatherHistory()
	{
		if (historyGathering)
		{
			return;
		}
		historyGathering = true;
		final int epoch = historyEpoch;
		new SwingWorker<HistoryData, Void>()
		{
			@Override
			protected HistoryData doInBackground()
			{
				return new HistoryData(plugin.historyBaselines(),
					plugin.feedNewest(HISTORY_FEED_SCAN), plugin.slayerJourney(),
					LocalDate.now());
			}

			@Override
			protected void done()
			{
				if (epoch != historyEpoch)
				{
					return;
				}
				historyGathering = false;
				HistoryData d;
				try
				{
					d = get();
				}
				catch (InterruptedException e)
				{
					return;
				}
				catch (java.util.concurrent.ExecutionException e)
				{
					return;
				}
				historySpine = d.spine;
				historyFeed = d.feed;
				historyJourney = d.journey;
				historyDay = d.day;
				historyFeedTs = newestTs(d.feed);
				rebuildInPlace();
			}
		}.execute();
	}

	private static long newestTs(List<JsonObject> feed)
	{
		return feed.isEmpty() ? 0 : safeLong(feed.get(0).get("ts"));
	}

	private static final int HIST_LIST_CAP = 6;
	private final Map<String, Integer> histListShown = new LinkedHashMap<>();

	private JPanel trackedProgress(HistoryProgress progress,
		List<Entry<String, Long>> gains, Map<String, List<String[]>> named)
	{
		JPanel card = card("Tracked progress");
		for (String name : HistoryProgress.GROUPS)
		{
			HistoryProgress.Group g = progress.group(name);
			boolean experience = "Experience".equals(name);
			List<HistoryProgress.Row> rows = g != null ? g.rows()
				: Collections.<HistoryProgress.Row>emptyList();
			List<HistoryProgress.Section> secs = g != null ? g.sections()
				: Collections.<HistoryProgress.Section>emptyList();
			int lines = rows.size() + secs.size() + (experience ? gains.size() : 0);
			if (lines == 0)
			{
				continue;
			}
			String stateKey = "history:shut:" + name;
			boolean open = !foldOpen(stateKey);
			card.add(quietHead(name, fmt(lines), stateKey));
			if (!open)
			{
				continue;
			}
			if (experience)
			{
				int cap = shownCap(GAINS_LIST);
				for (Entry<String, Long> e : firstN(gains, cap))
				{
					card.add(row(prettify(e.getKey()), "+" + gp(e.getValue())));
				}
				addMore(card, GAINS_LIST, gains.size(), cap, false);
			}
			for (HistoryProgress.Row r : rows)
			{
				addGroupRow(card, r, named.get(r.key()));
			}
			for (HistoryProgress.Section s : secs)
			{
				addGroupSection(card, s);
			}
		}
		return card;
	}

	private static final String GAINS_LIST = "history:xp";

	private void addGroupRow(JPanel card, HistoryProgress.Row r, List<String[]> list)
	{
		if (list == null || list.isEmpty())
		{
			card.add(row(r.label(), "+" + figure(r)));
			return;
		}
		String listKey = "history:list:" + r.key();
		boolean open = foldOpen(listKey);
		JPanel head = row(r.label(), "+" + figure(r));
		link(head, () -> toggleFold(listKey));
		card.add(head);
		if (open)
		{
			int cap = shownCap(listKey);
			for (String[] entry : firstN(list, cap))
			{
				card.add(nested(ghostRow(entry[0], entry[1])));
			}
			addMore(card, listKey, list.size(), cap, true);
			long unnamed = r.gp() ? 0 : r.value() - list.size();
			if (unnamed > 0)
			{
				card.add(nested(ghostRow("Not named in the record", "+" + fmt(unnamed))));
			}
		}
	}

	private void addGroupSection(JPanel card, HistoryProgress.Section s)
	{
		String stateKey = "history:" + s.family() + ":" + s.name();
		boolean open = foldOpen(stateKey);
		String total = s.summed()
			? "+" + (s.gp() ? gps(s.total()) : fmt(s.total()))
			: fmt(s.rows().size());
		card.add(subHead(s.name(), total, stateKey));
		if (!open)
		{
			return;
		}
		int cap = shownCap(stateKey);
		for (HistoryProgress.Row r : firstN(s.rows(), cap))
		{
			card.add(nested(row(r.label(), "+" + figure(r))));
		}
		addMore(card, stateKey, s.rows().size(), cap, true);
		if (s.ghost() > 0)
		{
			card.add(nested(ghostRow(s.ghostLabel(), "+" + fmt(s.ghost()))));
		}
	}

	private static JPanel nested(JPanel r)
	{
		r.setBorder(pad(1, ROW_INSET + 12, 1, ROW_INSET));
		return r;
	}

	private int shownCap(String key)
	{
		Integer n = histListShown.get(key);
		return n == null ? HIST_LIST_CAP : n;
	}

	private void addMore(JPanel card, String key, int size, int cap, boolean inset)
	{
		if (size <= cap)
		{
			return;
		}
		JPanel tail = ghostRow("Show " + fmt(size - cap) + " more", "");
		JPanel more = inset ? nested(tail) : tail;
		link(more, () ->
		{
			histListShown.put(key, size);
			rebuildInPlace();
		});
		card.add(more);
	}

	private static String countersSince(
		java.util.SortedMap<LocalDate, Baseline> spine,
		LocalDate startLine, LocalDate lootFrom, boolean lootFromSittings)
	{
		LocalDate counters = HistoryLog.firstCarrying(spine, null);
		LocalDate loot = lootFromSittings
			? lootFrom : HistoryLog.firstCarrying(spine, "dropsReceived");
		StringBuilder note = new StringBuilder();
		LocalDate since = startLine;
		if (counters != null && (since == null || counters.isAfter(since)))
		{
			note.append("Counters since ").append(counters.format(FULL_DAY));
			since = counters;
		}
		if (loot != null && (lootFromSittings || since == null || loot.isAfter(since)))
		{
			String what = lootFromSittings ? "loot" : "loot and kills";
			note.append(note.length() == 0
				? prettyTier(what) + " since "
				: " · " + what + " since ")
				.append(loot.format(FULL_DAY));
		}
		return note.length() == 0 ? null : note.toString();
	}

	private static boolean closedInside(SlayerTask t, long fromMs, long toMs)
	{
		long ms = (long) (t.ts * 1000);
		return !t.inProgress && ms >= fromMs && ms < toMs;
	}

	private static List<String[]> closedTaskNames(SlayerJourney j,
		long fromMs, long toMs)
	{
		List<SlayerTask> closed = new ArrayList<>();
		for (SlayerTask t : j.tasks)
		{
			if (closedInside(t, fromMs, toMs))
			{
				closed.add(t);
			}
		}
		closed.sort((a, b) -> Double.compare(b.ts, a.ts));
		List<String[]> out = new ArrayList<>(closed.size());
		for (SlayerTask t : closed)
		{
			long ms = (long) (t.ts * 1000);
			out.add(new String[]{t.task, fmt(t.kills) + " · "
				+ DAY.format(Instant.ofEpochMilli(ms))});
		}
		return out;
	}

	static String questName(String raw)
	{
		String q = raw == null ? "" : raw.trim();
		int at = low(q).indexOf("you have completed ");
		if (at >= 0)
		{
			q = q.substring(at + "you have completed ".length()).trim();
		}
		while (q.endsWith("!") || q.endsWith("."))
		{
			q = q.substring(0, q.length() - 1).trim();
		}
		return q.isEmpty() ? raw : q;
	}

	private static String feedName(JsonObject e)
	{
		JsonObject d = obj(e, "data");
		switch (typeOf(e))
		{
			case "PET":
				return has(d, "petName") ? d.get("petName").getAsString() : null;
			case "COLLECTION":
				return has(d, "itemName") ? d.get("itemName").getAsString() : null;
			case "QUEST":
				return has(d, "questName") ? questName(d.get("questName").getAsString())
					: has(d, "quest") ? questName(d.get("quest").getAsString()) : null;
			case "DIARY":
				return has(d, "area")
					? d.get("area").getAsString()
					+ (has(d, "difficulty") ? " " + d.get("difficulty").getAsString() : "")
					: null;
			case "COMBAT_ACHIEVEMENT":
				return has(d, "task")
					? (has(d, "tier")
					? prettify(low(d.get("tier").getAsString()))
					+ " · " : "") + d.get("task").getAsString()
					: null;
			case "LEVEL":
				return has(d, "skill")
					? prettify(low(d.get("skill").getAsString()))
					+ (has(d, "level") ? " " + d.get("level").getAsString() : "")
					: null;
			default:
				return null;
		}
	}

	private static boolean has(JsonObject o, String key)
	{
		return o.has(key) && !o.get(key).isJsonNull()
			&& !o.get(key).getAsString().trim().isEmpty();
	}

	private static long sessionMinutes(JsonObject e)
	{
		JsonObject d = obj(e, "data");
		return Math.max(0, safeLong(d.get("minutes")));
	}

	static long sittingStart(JsonObject e)
	{
		JsonObject d = obj(e, "data");
		long start = safeLong(d.get("start"));
		if (start > 0)
		{
			return start;
		}
		long ts = safeLong(e.get("ts"));
		return ts > 0 ? ts - sessionMinutes(e) * 60_000L : ts;
	}

	static long filedAt(JsonObject e)
	{
		return "SESSION".equals(typeOf(e)) ? sittingStart(e) : safeLong(e.get("ts"));
	}

	private static long noon(LocalDate d)
	{
		return d.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
	}

	private static LocalDate dayOf(long ms)
	{
		return Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate();
	}

	private static long startMs(LocalDate d)
	{
		return d.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
	}

	private static final JsonObject KINDS = table("panel_kinds.json");

	private static final Map<String, String> FEED_SUMMARY_KEYS = strMap(KINDS, "feedSummaryKeys");

	private static boolean sittingsCover(List<JsonObject> feed, long fromMs)
	{
		long oldest = oldestTs(feed, true);
		return oldest > 0 && oldest <= fromMs;
	}

	private static long earliestDatedLoot(List<JsonObject> feed, long rollFrom)
	{
		long sittings = oldestTs(feed, true);
		if (sittings <= 0)
		{
			return rollFrom;
		}
		return rollFrom <= 0 ? sittings : Math.min(sittings, rollFrom);
	}

	private List<String[]> itemLines(List<String[]> ranked, boolean always)
	{
		List<String[]> out = new ArrayList<>();
		for (String[] r : ranked)
		{
			long val = safeParse(r[2]);
			out.add(new String[]{r[0], fmt(safeParse(r[1])) + (always ? " · " + gps(val) : tail(val))});
		}
		return out;
	}

	private static long safeParse(String s)
	{
		try
		{
			return Long.parseLong(s);
		}
		catch (NumberFormatException e)
		{
			return 0;
		}
	}

	private static long oldestTs(List<JsonObject> feed, boolean sittings)
	{
		long oldest = 0;
		for (JsonObject e : feed)
		{
			long ts = !sittings ? safeLong(e.get("ts"))
				: "SESSION".equals(typeOf(e)) ? sittingStart(e) : 0;
			if (ts > 0 && (oldest == 0 || ts < oldest))
			{
				oldest = ts;
			}
		}
		return oldest;
	}

	private static String figure(HistoryProgress.Row r)
	{
		String base = r.gp() ? gps(r.value()) : fmt(r.value());
		return r.gpNote() > 0 ? base + " · " + gp(r.gpNote()) + " " + r.gpNoteWord() : base;
	}

	private void addLootValues(JPanel p, HistoryProgress progress)
	{
		long received = summaryValue(progress, "lootValue");
		HistoryProgress.Row keptRow = summaryRow(progress, "lootKept");
		long kept = keptRow == null ? received : keptRow.value();
		long left = Math.max(0, received - kept);
		JPanel card = card("What it was worth");
		card.add(row("Loot received", gps(received), accent()));
		card.add(row("Loot taken", gps(kept)));
		card.add(row("Loot left", gps(left)));
		card.add(row("Discarded", gps(summaryValue(progress, "itemsDroppedValue"))));
		card.add(row("Upkeep", gps(summaryValue(progress, "consumedValue"))));
		spaced(p, card);
	}

	private void addKinds(JPanel p, Map<String, Long> beforeKc, Map<String, Long> earliestKc,
		Map<String, Long> nowKc, boolean live, LocalDate from,
		LocalDate to, String first, String second)
	{
		boolean whole = wholeRecord();
		skilled = null;
		ledgerNames = null;
		sourceKinds.clear();
		Map<String, Long> standing = live ? plugin.killCounts() : nowKc;
		Map<String, Long> gained;
		Map<String, Long> worth;
		if (sessionPeriod())
		{
			gained = new LinkedHashMap<>();
			worth = new LinkedHashMap<>();
			for (String[] r : plugin.sessionLootWindow().sources)
			{
				gained.put(r[0], safeParse(r[1]));
				worth.put(r[0], safeParse(r[2]));
			}
		}
		else
		{
			gained = HistoryLog.gained(beforeKc, earliestKc, nowKc);
			worth = periodWorth(from, to);
		}
		Map<String, Long> loose = loosely(worth);
		Map<String, List<Entry<String, Long>>> byKind = new LinkedHashMap<>();
		for (String name : whole ? standing.keySet() : union(gained.keySet(), worth.keySet()))
		{
			long figure = whole ? standing.getOrDefault(name, 0L) : gained.getOrDefault(name, 0L);
			if (figure <= 0 && paidFor(worth, loose, name) <= 0)
			{
				continue;
			}
			byKind.computeIfAbsent(sourceKind(name), k -> new ArrayList<>())
				.add(new AbstractMap.SimpleEntry<>(name, figure));
		}
		Comparator<Entry<String, Long>> byPaid = (a, b) ->
		{
			long wa = paidFor(worth, loose, a.getKey());
			long wb = paidFor(worth, loose, b.getKey());
			if (wa != wb)
			{
				return Long.compare(wb, wa);
			}
			return b.getValue().equals(a.getValue())
				? a.getKey().compareToIgnoreCase(b.getKey())
				: Long.compare(b.getValue(), a.getValue());
		};
		boolean drew = false;
		for (String kind : new String[]{first, second})
		{
			List<Entry<String, Long>> rows = byKind.get(kind);
			if (rows == null || rows.isEmpty())
			{
				continue;
			}
			rows.sort(byPaid);
			addKindBand(p, kind, rows, worth, loose, kind.equals(first));
			drew = true;
		}
		if (!drew)
		{
			spaced(p, ghostRow(whole ? "Nothing counted yet." : "Nothing counted this period.", ""));
		}
	}

	private static Set<String> union(Set<String> a, Set<String> b)
	{
		Set<String> out = new LinkedHashSet<>(a);
		out.addAll(b);
		return out;
	}

	private Map<String, Long> periodWorth(LocalDate from, LocalDate to)
	{
		Map<String, Long> out = new LinkedHashMap<>();
		if (wholeRecord())
		{
			for (SourceRow r : sources())
			{
				out.merge(r.name, r.value, Long::sum);
			}
		}
		else
		{
			for (String[] r : plugin.lootBetween(from, to).sources)
			{
				out.merge(r[0], safeParse(r[2]), Long::sum);
			}
		}
		return out;
	}

	private static long paidFor(Map<String, Long> worth, Map<String, Long> loose, String name)
	{
		Long exact = worth.get(name);
		return exact != null ? exact : loose.getOrDefault(kindOf(name), 0L);
	}

	private static Map<String, Long> loosely(Map<String, Long> worth)
	{
		Map<String, Long> out = new LinkedHashMap<>();
		for (Entry<String, Long> e : worth.entrySet())
		{
			out.merge(kindOf(e.getKey()), e.getValue(), Long::sum);
		}
		return out;
	}

	private static final Set<String> PICKPOCKETED = new HashSet<>(strs(KINDS.get("pickpocketed")));

	private static final String[][] SKILLED = {
		{"Pickpockets", "THIEVING"}, {"Trapped", "HUNTER"},
		{"Caught", "HUNTER"}, {"Harvested", "HUNTER"},
	};

	private Map<String, String> skilledKeys()
	{
		if (skilled == null)
		{
			Map<String, String> found = new LinkedHashMap<>();
			for (String key : counters().keySet())
			{
				for (String[] verb : SKILLED)
				{
					if (key.endsWith(verb[0]) && !key.endsWith("Failed" + verb[0]))
					{
						found.putIfAbsent(
							letters(key.substring(0, key.length() - verb[0].length())), verb[1]);
						break;
					}
				}
			}
			skilled = found;
		}
		return skilled;
	}

	private Map<String, String> skilled;

	private static String letters(String s)
	{
		StringBuilder out = new StringBuilder();
		for (char c : s.toCharArray())
		{
			if (Character.isLetterOrDigit(c))
			{
				out.append(Character.toLowerCase(c));
			}
		}
		int end = out.length();
		return end > 1 && out.charAt(end - 1) == 's' ? out.substring(0, end - 1) : out.toString();
	}

	static final String KIND_BOSS = "Bosses";
	static final String KIND_ACTIVITY = "Activities";
	static final String KIND_SKILLING = "Skilling";
	static final String KIND_MONSTER = "Monsters";

	private final Map<String, String> sourceKinds = new LinkedHashMap<>();

	private String sourceKind(String name)
	{
		return sourceKinds.computeIfAbsent(name, this::decideKind);
	}

	private String decideKind(String name)
	{
		if (PICKPOCKETED.contains(low(name))
			|| skilledKeys().containsKey(letters(name)))
		{
			return KIND_SKILLING;
		}
		for (Entry<String, Map<String, List<String>>> tab : taxonomy(plugin.gson()).entrySet())
		{
			if (!tab.getValue().containsKey(name))
			{
				continue;
			}
			String t = low(tab.getKey());
			if (t.contains("boss") || t.contains("raid"))
			{
				return KIND_BOSS;
			}
			if (t.contains("clue") || t.contains("minigame"))
			{
				return KIND_ACTIVITY;
			}
			return MONSTER_PAGES.contains(name) ? KIND_MONSTER : KIND_SKILLING;
		}
		String low = low(name);
		return containsAny(low, OPENED) ? KIND_ACTIVITY
			: containsAny(low, GATHERED) ? KIND_SKILLING : KIND_MONSTER;
	}

	private static final Set<String> MONSTER_PAGES = new HashSet<>(strs(KINDS.get("monsterPages")));

	private static final List<String> OPENED = strs(KINDS.get("opened"));
	private static final List<String> GATHERED = strs(KINDS.get("gathered"));

	private static boolean containsAny(String low, List<String> words)
	{
		for (String w : words)
		{
			if (low.contains(w))
			{
				return true;
			}
		}
		return false;
	}

	private final Map<String, Integer> signatureItems = new LinkedHashMap<>();

	private int signatureItem(String source)
	{
		Integer known = signatureItems.get(source);
		if (known != null)
		{
			return known;
		}
		String own = resolveSourceNamed(source);
		List<BagItem> bag = own == null ? new ArrayList<>()
			: new ArrayList<>(plugin.sourceItems(own));
		bag.sort((a, b) -> Long.compare(b.value, a.value));
		int best = 0;
		for (BagItem b : bag)
		{
			best = b.itemId > 0 ? b.itemId : itemNamed(b.name);
			if (best > 0)
			{
				break;
			}
		}
		if (best == 0)
		{
			best = pagedItem(source);
		}
		if (best > 0 || nameIndex() != null)
		{
			signatureItems.put(source, best);
		}
		return best;
	}

	private int pagedItem(String page)
	{
		for (Map<String, List<String>> pages : taxonomy(plugin.gson()).values())
		{
			List<String> slots = pages.get(page);
			if (slots == null)
			{
				continue;
			}
			for (String slot : slots)
			{
				int id = itemNamed(slot);
				if (id > 0)
				{
					return id;
				}
			}
			return 0;
		}
		return 0;
	}

	private Map<String, Integer> itemsByName;

	private int itemNamed(String name)
	{
		Map<String, Integer> index = nameIndex();
		if (index == null || name == null || name.isEmpty())
		{
			return 0;
		}
		Integer id = index.get(low(name));
		return id == null ? 0 : id;
	}

	private Map<String, Integer> nameIndex()
	{
		if (itemsByName != null)
		{
			return itemsByName;
		}
		List<ItemPrice> all = plugin.items().search("");
		if (all == null || all.isEmpty())
		{
			return null;
		}
		Map<String, Integer> byName = new HashMap<>();
		for (ItemPrice price : all)
		{
			if (price.getName() != null)
			{
				byName.putIfAbsent(low(price.getName()), price.getId());
			}
		}
		itemsByName = byName;
		return itemsByName;
	}

	private void addKindBand(JPanel p, String kind, List<Entry<String, Long>> rows,
		Map<String, Long> worth, Map<String, Long> loose, boolean withIcons)
	{
		String stateKey = "history:kind:" + kind;
		boolean open = !foldOpen(stateKey);
		p.add(quietHead(kind, open ? "" : fmt(rows.size()), stateKey));
		if (!open)
		{
			p.add(vgap(4));
			return;
		}
		Integer asked = histListShown.get(stateKey);
		int cap = asked == null ? BAND_CAP : asked;
		JPanel card = cardPlain();
		for (Entry<String, Long> e : firstN(rows, cap))
		{
			card.add(kindRow(e.getKey(), e.getValue(),
				paidFor(worth, loose, e.getKey()), withIcons));
		}
		addMore(card, stateKey, rows.size(), cap, false);
		spaced(p, card);
	}

	private static final int BAND_CAP = 12;
	private static final int ICON_W = 22;
	private static final int ICON_H = 18;

	private JPanel kindRow(String name, long figure, long worth, boolean withIcon)
	{
		JPanel r = row(name, null);
		r.setToolTipText(name + ", " + fmt(figure)
			+ (worth > 0 ? " · " + fmt(worth) + " gp" : ""));

		if (withIcon)
		{
			JLabel icon = new JLabel();
			icon.setPreferredSize(new Dimension(ICON_W, ICON_H));
			mountKindIcon(icon, name);
			r.add(icon, BorderLayout.WEST);
		}

		JPanel figures = new JPanel();
		figures.setLayout(new BoxLayout(figures, BoxLayout.X_AXIS));
		figures.setOpaque(false);
		JLabel count = styled(new JLabel(fmt(figure)), FontManager.getRunescapeFont(), dim());
		figures.add(count);
		if (worth > 0)
		{
			figures.add(javax.swing.Box.createHorizontalStrut(6));
			JLabel paid = styled(new JLabel(gp(worth)), FontManager.getRunescapeFont(),
				accent());
			figures.add(paid);
		}
		r.add(figures, BorderLayout.EAST);

		link(r, () -> openSourceLoose(name));
		return r;
	}

	private void mountKindIcon(JLabel label, String name)
	{
		int item = signatureItem(name);
		if (item > 0)
		{
			mountItem(label, item);
			return;
		}
		Skill skill = skillOf(name);
		if (skill != null)
		{
			BufferedImage img = skillIcon(skill);
			if (img != null)
			{
				dress(label, "skill:" + skill.name(), img, ICON_W, ICON_H);
				return;
			}
		}
		wearSprite(label, kindSprite(sourceKind(name)), ICON_W, ICON_H);
	}

	private final Map<Integer, List<JLabel>> itemWaiting = new LinkedHashMap<>();
	private final Set<AsyncBufferedImage> itemAsked =
		Collections.newSetFromMap(new java.util.WeakHashMap<>());

	private void mountItem(JLabel label, int itemId)
	{
		ImageIcon have = scaledIcons.get("item:" + itemId);
		if (have != null)
		{
			label.setIcon(have);
			return;
		}
		AsyncBufferedImage img = plugin.items().getImage(itemId, 1, false);
		if (img == null)
		{
			return;
		}
		itemWaiting.computeIfAbsent(itemId, k -> new ArrayList<>()).add(label);
		if (!itemAsked.add(img))
		{
			return;
		}
		img.onLoaded(() -> SwingUtilities.invokeLater(() ->
		{
			ImageIcon icon = fit(img, ICON_W, ICON_H);
			scaledIcons.put("item:" + itemId, icon);
			List<JLabel> waiting = itemWaiting.remove(itemId);
			if (waiting != null)
			{
				for (JLabel one : waiting)
				{
					one.setIcon(icon);
					one.repaint();
				}
			}
		}));
	}

	private static int kindSprite(String kind)
	{
		if (KIND_ACTIVITY.equals(kind))
		{
			return 1053;
		}
		return KIND_SKILLING.equals(kind) ? 775 : 774;
	}

	private Skill skillOf(String name)
	{
		String skill = PAGE_SKILL.get(name);
		if (skill == null)
		{
			return null;
		}
		try
		{
			return Skill.valueOf(skill);
		}
		catch (IllegalArgumentException e)
		{
			return null;
		}
	}

	private static final Map<String, String> PAGE_SKILL = strMap(KINDS, "pageSkills");

	private static final List<String> SKILL_ORDER_NAMES = strs(KINDS.get("skillOrder"));

	private static List<Skill> skillOrder()
	{
		List<Skill> out = new ArrayList<>();
		for (String name : SKILL_ORDER_NAMES)
		{
			try
			{
				out.add(Skill.valueOf(name));
			}
			catch (IllegalArgumentException dropped)
			{
			}
		}
		for (Skill sk : Skill.values())
		{
			if (sk != Skill.OVERALL && !out.contains(sk))
			{
				out.add(sk);
			}
		}
		return out;
	}

	private static final class SkillStand
	{
		final List<Skill> order;
		final List<String> keys;
		final Map<Skill, Long> levels;
		final long standing;
		final HistoryLog.Levels closed;

		SkillStand(List<Skill> order, List<String> keys,
			Map<Skill, Long> levels, long standing, HistoryLog.Levels closed)
		{
			this.order = order;
			this.keys = keys;
			this.levels = levels;
			this.standing = standing;
			this.closed = closed;
		}
	}

	private static Baseline baselineAt(Map<String, Long> xp)
	{
		Baseline at = new Baseline();
		at.skills.putAll(xp);
		at.complete = true;
		return at;
	}

	private SkillStand skillStand(Baseline closing, boolean live)
	{
		Map<String, long[]> sheet = live ? plugin.skillSheet() : Collections.emptyMap();
		List<Skill> order = skillOrder();
		List<String> keys = new ArrayList<>();
		for (Skill sk : order)
		{
			keys.add(low(sk.name()));
		}
		HistoryLog.Levels closed = HistoryLog.levels(closing, keys);
		Map<Skill, Long> levels =
			new EnumMap<>(Skill.class);
		long total = 0;
		for (Skill sk : order)
		{
			String key = low(sk.name());
			long[] cur = sheet.get(key);
			long level = cur != null && cur[0] > 0 ? cur[0] : closed.of.get(key);
			total += level;
			long shown = wholeRecord() && cur != null && cur.length > 1 && cur[1] > 0
				? PaceBook.virtualLevelAt(cur[1]) : level;
			levels.put(sk, Math.max(level, shown));
		}
		long[] ov = sheet.get("overall");
		return new SkillStand(order, keys, levels,
			ov != null && ov[0] > 0 ? ov[0] : total, closed);
	}

	private static final String[] HEADLINE_KEYS = {"kills", "slayerTasksCompleted"};

	private JPanel headline(HistoryProgress progress, List<Entry<String, Long>> gains,
		SkillStand stand, HistoryLog.Levels opened, long[] played)
	{
		JPanel card = card(sessionPeriod() ? "This sitting" : "The period");
		long xp = 0;
		for (Entry<String, Long> g : gains)
		{
			xp += g.getValue();
		}
		if ("PvM".equals(histFacet))
		{
			card.add(row("Monsters slain", "+" + fmt(summaryValue(progress, "kills"))));
			card.add(row("Deaths", "+" + fmt(summaryValue(progress, "deaths"))));
			card.add(row("Slayer tasks completed",
				"+" + fmt(summaryValue(progress, "slayerTasksCompleted"))));
			return card;
		}
		boolean fixed = "Skills".equals(histFacet);
		if (fixed || played[1] > 0)
		{
			card.add(row("Time played", hoursMinutes(played[0])));
			if (!sessionPeriod())
			{
				card.add(row("Sessions", fmt(played[1])));
			}
		}
		if (fixed || xp > 0)
		{
			card.add(row("Experience", "+" + gp(xp), xp > 0 ? accent() : null));
		}
		HistoryLog.Levels closed = stand.closed;
		boolean paired = closed.drawn == opened.drawn;
		if (fixed || paired && closed.nines > opened.nines)
		{
			card.add(row("99s reached", fmt(paired ? Math.max(0, closed.nines - opened.nines) : 0)));
		}
		if (fixed)
		{
			return card;
		}
		for (String key : HEADLINE_KEYS)
		{
			HistoryProgress.Row r = summaryRow(progress, key);
			if (r != null)
			{
				card.add(row(r.label(), "+" + figure(r)));
			}
		}
		HistoryProgress.Row drops = summaryRow(progress, "dropsReceived");
		HistoryProgress.Row value = summaryRow(progress, "lootValue");
		if (drops != null)
		{
			card.add(row(drops.label(), "+" + fmt(drops.value())
				+ (value != null ? " · " + gp(value.value()) : "")));
		}
		else if (value != null)
		{
			card.add(row(value.label(), "+" + figure(value)));
		}
		HistoryProgress.Row deaths = summaryRow(progress, "deaths");
		if (deaths != null)
		{
			card.add(row(deaths.label(), "+" + figure(deaths)));
		}
		return card;
	}

	private static long summaryValue(HistoryProgress progress, String key)
	{
		HistoryProgress.Row r = summaryRow(progress, key);
		return r == null ? 0 : r.value();
	}

	private static HistoryProgress.Row summaryRow(HistoryProgress progress, String key)
	{
		for (HistoryProgress.Row r : progress.summary())
		{
			if (r.key().equals(key))
			{
				return r;
			}
		}
		return null;
	}

	private static String rateText(double perHour)
	{
		return perHour >= 10 ? fmt(Math.round(perHour)) : String.format(Locale.UK, "%.1f", perHour);
	}

	private static String count(long n, String one)
	{
		return fmt(n) + " " + (n == 1 ? one : one + "s");
	}

	private static String hoursMinutes(long minutes)
	{
		return minutes >= 60 ? (minutes / 60) + "h " + (minutes % 60) + "m" : minutes + "m";
	}

	private void addSkillGrid(JPanel p, List<Entry<String, Long>> gains, SkillStand stand,
		HistoryLog.Levels opened)
	{
		Map<String, Long> gain = new LinkedHashMap<>();
		for (Entry<String, Long> g : gains)
		{
			gain.put(g.getKey(), g.getValue());
		}
		List<Skill> order = stand.order;
		Map<Skill, Long> levels = stand.levels;

		JPanel grid = grid3();
		for (Skill sk : order)
		{
			String key = low(sk.name());
			Integer was = opened == null ? null
				: opened.virtual.getOrDefault(key, opened.of.get(key));
			Long from = was == null ? null : Long.valueOf(was.longValue());
			grid.add(skillCell(sk, levels.get(sk), gain.get(key), from));
		}
		spaced(p, grid, 3);
		JPanel combat = combatLevelTile(gain, opened);
		JPanel total = totalLevelTile(stand, opened);
		total.setToolTipText(periodTip != null ? tipLine(periodTip, "Opens the records")
			: tip("Total level", new String[]{"Opens"}, new String[]{"the records"}));
		link(total, this::openRecords);
		if (wholeRecord())
		{
			JPanel levels2 = new JPanel(new GridLayout(1, 2, 2, 2));
			levels2.setBackground(DARK);
			levels2.setAlignmentX(Component.LEFT_ALIGNMENT);
			levels2.add(combat);
			levels2.add(total);
			p.add(levels2);
		}
		else
		{
			spaced(p, combat, 2);
			p.add(total);
		}
		p.add(vgap(6));
	}

	private JPanel combatLevelTile(Map<String, Long> gain, HistoryLog.Levels opened)
	{
		JPanel cell = levelTile("Combat");
		int cb = plugin.combatLevel();
		Integer was = openingCombat(opened);
		boolean climbed = !wholeRecord() && was != null && cb > was;
		JLabel fig = new JLabel(cb > 0 ? (climbed ? fmt(was) + " to " + fmt(cb) : fmt(cb)) : "-",
			JLabel.RIGHT);
		fig.setFont(small());
		fig.setForeground(cb > 0 && (wholeRecord() || combatSkillsMoved(gain))
			? TILE_LIT : dim());
		cell.add(fig, BorderLayout.EAST);
		Map<String, Long> c = counters();
		long[] ca = combatStanding();
		cell.setToolTipText(tip("Combat",
			new String[]{"Achievement points", "Tiers unlocked", "Damage dealt",
				"Highest hit"},
			new String[]{
				ca[1] > 0 ? fmt(ca[0]) + " / " + fmt(ca[1]) : fmt(ca[0]),
				fmt(ca[2]) + " / 6",
				fmt(c.getOrDefault(StatKeys.DAMAGE_DEALT, 0L)),
				fmt(c.getOrDefault(StatKeys.HIGHEST_HIT, 0L))}));
		link(cell, () ->
		{
			sheetPage = "combat";
			rebuild();
		});
		return cell;
	}

	private Map<String, Long> movedTypes;

	private long stirred(String type)
	{
		if (movedTypes == null)
		{
			movedTypes = new LinkedHashMap<>();
			for (JsonObject e : plugin.feedNewest(FEED_SCAN_DEEP))
			{
				if (insideWindow(safeLong(e.get("ts"))))
				{
					movedTypes.merge(typeOf(e), 1L, Long::sum);
				}
			}
		}
		Long n = movedTypes.get(type);
		return n == null ? 0 : n;
	}

	private long activityMoved(String label, String source)
	{
		if (wholeRecord())
		{
			return -1;
		}
		if ("Collections".equals(label))
		{
			return stirred("COLLECTION");
		}
		if ("Quests".equals(label))
		{
			return stirred("QUEST");
		}
		if ("Diaries".equals(label))
		{
			return stirred("DIARY");
		}
		if ("Clues".equals(label))
		{
			long all = 0;
			for (String tier : CLUE_TIERS)
			{
				Long n = rolledKills("Clue Scroll (" + tier + ")");
				all += n == null ? 0 : n;
			}
			return all;
		}
		if (!source.isEmpty())
		{
			Long n = rolledKills(source);
			long rolled = n == null ? 0 : n;
			return rolled > 0 && namedLine(source, label) > 0 ? -1 : rolled;
		}
		return 0;
	}

	private long namedLine(String source, String label)
	{
		for (Entry<String, Long> ln : pageLines(source, "kc_lines"))
		{
			if (ln.getKey().equalsIgnoreCase(label))
			{
				return ln.getValue();
			}
		}
		return 0;
	}

	private Map<String, Long> buildPeriodCounters;
	private boolean periodCountersAsked;

	private Map<String, Long> periodCounters()
	{
		if (!periodCountersAsked)
		{
			periodCountersAsked = true;
			buildPeriodCounters = countersForPeriod();
		}
		return buildPeriodCounters == null ? Collections.emptyMap()
			: buildPeriodCounters;
	}

	private String skillTip(String craft)
	{
		Map<String, Long> now = periodCounters();
		List<String> floors = new ArrayList<>();
		List<Entry<String, Long>> named = new ArrayList<>();
		for (String key : StatRegistry.headlines(craft))
		{
			Long v = now.get(key);
			if (v == null || v <= 0)
			{
				continue;
			}
			if (StatRegistry.isFloor(key))
			{
				floors.add(key);
			}
			else
			{
				named.add(new AbstractMap.SimpleEntry<>(key, v));
			}
		}
		named.sort(Entry.<String, Long>comparingByValue().reversed());
		List<String> labels = new ArrayList<>();
		List<String> figures = new ArrayList<>();
		for (String key : floors)
		{
			labels.add(StatRegistry.rowLabel(key));
			figures.add(fmt(now.get(key)));
		}
		for (Entry<String, Long> e : named)
		{
			if (labels.size() >= 6)
			{
				break;
			}
			labels.add(StatRegistry.rowLabel(e.getKey()));
			figures.add(fmt(e.getValue()));
		}
		return tip(craft, labels.toArray(new String[0]),
			figures.toArray(new String[0]));
	}

	private String slayerTip()
	{
		long[] ms = windowMs();
		long[] tally = plugin.onTaskTally(ms[0], ms[1], null, wholeRecord());
		long paid = tallyOf(plugin.onTaskLoot(ms[0], ms[1], null, wholeRecord()))[1];
		return tip("Slayer", new String[]{"Tasks tracked", "Kills on task", "On-task loot"},
			new String[]{fmt(tally[2]), fmt(tally[0]), gps(paid)});
	}

	private static Integer openingCombat(HistoryLog.Levels opened)
	{
		if (opened == null || opened.of == null)
		{
			return null;
		}
		int[] lv = new int[COMBAT_SKILLS.length];
		for (int i = 0; i < COMBAT_SKILLS.length; i++)
		{
			Integer l = opened.of.get(low(COMBAT_SKILLS[i].name()));
			if (l == null || l <= 0)
			{
				return null;
			}
			lv[i] = l;
		}
		return net.runelite.api.Experience.getCombatLevel(lv[0], lv[1], lv[2], lv[3],
			lv[5], lv[4], lv[6]);
	}

	private static final Skill[] COMBAT_SKILLS = {
		Skill.ATTACK, Skill.STRENGTH,
		Skill.DEFENCE, Skill.HITPOINTS,
		Skill.RANGED, Skill.MAGIC,
		Skill.PRAYER};

	private static boolean combatSkillsMoved(Map<String, Long> gain)
	{
		for (Skill sk : COMBAT_SKILLS)
		{
			Long g = gain.get(low(sk.name()));
			if (g != null && g > 0)
			{
				return true;
			}
		}
		return false;
	}

	private JsonObject buildAchievements;

	private JsonObject achievements()
	{
		if (buildAchievements == null)
		{
			buildAchievements = plugin.achievements();
		}
		return buildAchievements;
	}

	private long[] diaryStanding()
	{
		JsonObject d = obj(achievements(), "diaries");
		long done = 0;
		long all = 0;
		long whole = 0;
		for (String region : d.keySet())
		{
			JsonObject tiers = d.getAsJsonObject(region);
			long here = 0;
			for (String tier : tiers.keySet())
			{
				all++;
				if (tiers.get(tier).getAsBoolean())
				{
					done++;
					here++;
				}
			}
			if (here > 0 && here == tiers.size())
			{
				whole++;
			}
		}
		return new long[]{done, all, whole, d.size()};
	}

	private long bundledPoints()
	{
		bundledCombat = bundle(plugin.gson(), "osrs_combat_achievements.json", bundledCombat);
		return safeLong(obj(obj(bundledCombat, "_meta"), "totals").get("points"));
	}

	private long[] combatStanding()
	{
		JsonObject c = obj(achievements(), "combat");
		long points = c.has("points") ? c.get("points").getAsLong() : 0;
		long tiers = 0;
		JsonObject t = obj(c, "tiers");
		for (String k : t.keySet())
		{
			if (t.get(k).getAsLong() > 0)
			{
				tiers++;
			}
		}
		long possible = 0;
		long seen = 0;
		for (JsonObject e : plugin.feedNewest(FEED_SCAN_DEEP))
		{
			if (!"COMBAT_ACHIEVEMENT".equals(str(e, "type", "")))
			{
				continue;
			}
			seen++;
			JsonObject data = obj(e, "data");
			if (possible == 0 && data.has("totalPossiblePoints"))
			{
				possible = data.get("totalPossiblePoints").getAsLong();
			}
		}
		if (possible == 0)
		{
			possible = bundledPoints();
		}
		return new long[]{points, possible, tiers, seen};
	}

	private JPanel buildSheetPage()
	{
		JPanel p = backPage();
		if ("log".equals(sheetPage))
		{
			p.add(buildLog());
		}
		else if ("clues".equals(sheetPage))
		{
			buildClues(p);
		}
		else if ("quests".equals(sheetPage))
		{
			buildQuests(p);
		}
		else if ("diaries".equals(sheetPage))
		{
			buildDiaries(p);
		}
		else
		{
			buildCombatAchievements(p);
		}
		return p;
	}

	private static JsonObject bundledDiaries;
	private static JsonObject bundledCombat;

	private static JsonObject bundle(Gson gson, String name,
		JsonObject cached)
	{
		if (cached != null)
		{
			return cached;
		}
		try (InputStreamReader r = new InputStreamReader(
			ChroniclePanel.class.getResourceAsStream("/chronicle/" + name),
			StandardCharsets.UTF_8))
		{
			return gson.fromJson(r, JsonObject.class);
		}
		catch (Exception e)
		{
			return new JsonObject();
		}
	}

	private void buildClues(JPanel p)
	{
		long all = 0;
		long allWorth = 0;
		List<SourceRow> mine = new ArrayList<>();
		for (String tier : CLUE_TIERS)
		{
			for (SourceRow r : sources())
			{
				if (r.name.equalsIgnoreCase("Clue Scroll (" + tier + ")"))
				{
					mine.add(r);
					all += Math.max(r.kc, r.loots);
					allWorth += r.value;
				}
			}
		}
		JPanel head = card("Clues");
		head.add(row("Caskets opened", fmt(all), accent()));
		head.add(row("Worth", gps(allWorth), accent()));
		head.add(row("Tiers seen", fmt(mine.size()) + " / " + CLUE_TIERS.length));
		spaced(p, head);
		if (mine.isEmpty())
		{
			p.add(note("No clue casket has been opened while Chronicle was watching."));
			return;
		}
		p.add(group("BY TIER"));
		for (String tier : CLUE_TIERS)
		{
			SourceRow r = null;
			for (SourceRow s : mine)
			{
				if (s.name.equalsIgnoreCase("Clue Scroll (" + tier + ")"))
				{
					r = s;
					break;
				}
			}
			if (r == null)
			{
				p.add(row(tier, "-", dim(), true));
				continue;
			}
			long n = Math.max(r.kc, r.loots);
			JPanel line = row(tier, fmt(n) + " \u00b7 " + gps(r.value), accent());
			final String open = r.name;
			link(line, () -> openSource(open));
			line.setToolTipText(tip(tier + " clues",
				new String[]{"Caskets", "Worth", "Each"},
				new String[]{fmt(n), gps(r.value),
					n > 0 ? gps(r.value / n) : "-"}));
			p.add(line);
		}
	}

	private Set<Integer> caDone()
	{
		Set<Integer> out = new HashSet<>();
		JsonObject c = obj(achievements(), "combat");
		if (!c.has("tasksDone") || !c.get("tasksDone").isJsonArray())
		{
			return out;
		}
		for (JsonElement e : c.getAsJsonArray("tasksDone"))
		{
			try
			{
				out.add(e.getAsInt());
			}
			catch (RuntimeException ignored)
			{
			}
		}
		return out;
	}

	private void buildQuests(JPanel p)
	{
		JsonObject q = obj(achievements(), "quests");
		if (q.size() == 0)
		{
			p.add(note("The quest list arrives when you next log in."));
			return;
		}
		List<String> done = new ArrayList<>();
		List<String> going = new ArrayList<>();
		List<String> not = new ArrayList<>();
		for (String name : q.keySet())
		{
			String state = q.get(name).getAsString();
			("FINISHED".equals(state) ? done : "IN_PROGRESS".equals(state) ? going : not)
				.add(name);
		}
		JPanel head = card("Quests");
		head.add(row("Complete", fmt(done.size()) + " / " + fmt(q.size()), accent()));
		head.add(row("In progress", fmt(going.size())));
		head.add(row("Not started", fmt(not.size())));
		spaced(p, head);
		addNames(p, "IN PROGRESS", going, true, true);
		addNames(p, "COMPLETE", done, true, false);
		addNames(p, "NOT STARTED", not, false, false);
	}

	private void addNames(JPanel p, String heading, List<String> names, boolean held,
		boolean openByDefault)
	{
		if (names.isEmpty())
		{
			return;
		}
		Collections.sort(names);
		String foldKey = "quests:" + heading;
		boolean open = openFolds.contains(foldKey) != openByDefault;
		p.add(quietHead(heading, fmt(names.size()), foldKey));
		if (!open)
		{
			p.add(vgap(4));
			return;
		}
		for (String n : names)
		{
			p.add(row(n, "", held ? null : dim(), !held));
		}
		p.add(vgap(4));
	}

	private void buildDiaries(JPanel p)
	{
		bundledDiaries = bundle(plugin.gson(), "osrs_achievement_diaries.json", bundledDiaries);
		JsonObject tasks = bundledDiaries.has("diaries")
			? bundledDiaries.getAsJsonObject("diaries") : new JsonObject();
		JsonObject mine = obj(achievements(), "diaries");
		boolean known = mine.size() > 0;
		JPanel head = card("Achievement diaries");
		if (known)
		{
			long[] d = diaryStanding();
			head.add(row("Tiers done", d[0] + " / " + d[1], accent()));
			head.add(row("Regions finished", fmt(d[2]) + " / " + fmt(d[3])));
		}
		spaced(p, head);
		if (!known)
		{
			spaced(p, note("Which tiers you have finished arrives when you next log in. "
				+ "Until then this is what each one asks for."), 4);
		}
		for (String region : tasks.keySet())
		{
			JsonObject tiers = tasks.getAsJsonObject(region);
			String key = low(region);
			JsonObject held = null;
			for (String k : mine.keySet())
			{
				if (k.equalsIgnoreCase(key) || key.startsWith(low(k)))
				{
					held = mine.getAsJsonObject(k);
					break;
				}
			}
			p.add(group(region.toUpperCase(Locale.ROOT)));
			for (String tier : new String[]{"easy", "medium", "hard", "elite"})
			{
				if (!tiers.has(tier))
				{
					continue;
				}
				int n = tiers.getAsJsonArray(tier).size();
				boolean got = held != null && held.has(tier) && held.get(tier).getAsBoolean();
				JPanel line = row(prettyTier(tier),
					fmt(n) + " tasks",
					known && !got ? dim() : null,
					known && !got);
				line.setToolTipText(taskTip(region + " " + tier,
					tiers.getAsJsonArray(tier)));
				p.add(line);
			}
			p.add(vgap(4));
		}
	}

	private static String prettyTier(String tier)
	{
		return tier == null || tier.isEmpty() ? ""
			: Character.toUpperCase(tier.charAt(0)) + tier.substring(1);
	}

	private static String taskTip(String title, JsonArray tasks)
	{
		final int CAP = 8;
		StringBuilder sb = new StringBuilder("<html><body style='padding:2px'>");
		sb.append("<div style='color:#8f8f8f'>").append(title).append("</div>");
		for (int i = 0; i < tasks.size() && i < CAP; i++)
		{
			JsonObject t = tasks.get(i).getAsJsonObject();
			String task = t.get("task").getAsString();
			sb.append("<div>").append(task.length() > 78 ? task.substring(0, 78) + "..." : task)
				.append("</div>");
			String needs = t.has("requirements") ? t.get("requirements").getAsString() : "";
			if (!needs.isEmpty())
			{
				sb.append("<div style='color:#8f8f8f'>&nbsp;&nbsp;")
					.append(needs.length() > 70 ? needs.substring(0, 70) + "..." : needs)
					.append("</div>");
			}
		}
		if (tasks.size() > CAP)
		{
			sb.append("<div style='color:#8f8f8f'>and ").append(tasks.size() - CAP)
				.append(" more</div>");
		}
		return sb.append("</body></html>").toString();
	}

	private void buildCombatAchievements(JPanel p)
	{
		bundledCombat = bundle(plugin.gson(), "osrs_combat_achievements.json", bundledCombat);
		JsonObject all = bundledCombat.has("tasks")
			? bundledCombat.getAsJsonObject("tasks") : new JsonObject();
		long[] c = combatStanding();
		JPanel head = card("Combat achievements");
		head.add(row("Points", c[1] > 0 ? fmt(c[0]) + " / " + fmt(c[1]) : fmt(c[0]),
			accent()));
		head.add(row("Tiers unlocked", fmt(c[2]) + " / 6"));
		Set<Integer> headDone = caDone();
		long named = 0;
		for (int id : headDone)
		{
			if (all.has(String.valueOf(id)))
			{
				named++;
			}
		}
		long unnamed = headDone.size() - named;
		if (!headDone.isEmpty())
		{
			head.add(row("Tasks done", fmt(named) + " / " + fmt(all.size()), accent()));
		}
		spaced(p, head);
		if (unnamed > 0)
		{
			spaced(p, note("You have also done " + fmt(unnamed) + " combat achievement"
				+ (unnamed == 1 ? "" : "s") + " added to the game since this copy of"
				+ " Chronicle was built. They are counted by the game, not named"
				+ " here, until the plugin updates."), 4);
		}
		Set<Integer> done = headDone;
		boolean known = !done.isEmpty();
		if (!known)
		{
			spaced(p, note("Which tasks you have done arrives when you next log in. "
				+ "Until then this is what each tier asks for."), 4);
		}
		Map<String, List<JsonObject>> bySource = new TreeMap<>(
			String.CASE_INSENSITIVE_ORDER);
		for (String id : all.keySet())
		{
			JsonObject task = all.getAsJsonObject(id).deepCopy();
			task.addProperty("id", Integer.parseInt(id));
			bySource.computeIfAbsent(caSource(task.get("monster").getAsString()),
				k -> new ArrayList<>()).add(task);
		}
		for (Entry<String, List<JsonObject>> e : bySource.entrySet())
		{
			long got = 0;
			for (JsonObject task : e.getValue())
			{
				if (done.contains(task.get("id").getAsInt()))
				{
					got++;
				}
			}
			String foldKey = "ca:" + e.getKey();
			boolean open = foldOpen(foldKey);
			int n = e.getValue().size();
			p.add(quietHead(e.getKey(), known
				? fmt(got) + " / " + fmt(n)
				: fmt(n) + (n == 1 ? " task" : " tasks"), foldKey));
			if (!open)
			{
				continue;
			}
			for (JsonObject task : e.getValue())
			{
				boolean has = known && done.contains(task.get("id").getAsInt());
				JPanel line = row(withoutSource(task.get("name").getAsString(), e.getKey()),
					prettyTier(task.get("tier").getAsString()),
					known ? (has ? ACCENT_SESSION : ACCENT_RED) : null, known);
				line.setToolTipText(tip(task.get("name").getAsString(),
					new String[]{"Tier", "Where", "Task"},
					new String[]{task.get("tier").getAsString(),
						caSource(task.get("monster").getAsString()),
						task.get("task").getAsString()}));
				p.add(line);
			}
			p.add(vgap(4));
		}
	}

	private static String withoutSource(String name, String source)
	{
		if (name == null || source == null || name.length() <= source.length()
			|| !name.regionMatches(true, 0, source, 0, source.length()))
		{
			return name;
		}
		String rest = name.substring(source.length()).trim();
		return rest.isEmpty() ? name : rest;
	}

	private static String caSource(String monster)
	{
		return monster == null || monster.trim().isEmpty()
			|| "N/A".equalsIgnoreCase(monster.trim()) ? "Anywhere" : monster;
	}

	private String periodTip(long[] played, List<Entry<String, Long>> gains)
	{
		long xp = 0;
		for (Entry<String, Long> g : gains)
		{
			xp += g.getValue();
		}
		return tip(sessionPeriod() ? "This sitting"
			: wholeRecord() ? "Lifetime" : "The period",
			new String[]{"Time played", "Sessions", "Experience"},
			new String[]{hoursMinutes(played[0]), fmt(played[1]), "+" + gp(xp)});
	}

	private static String tipLine(String card, String line)
	{
		return card.replace("</body>", "<div style='color:#8f8f8f'>" + line + "</div></body>");
	}

	private static String tip(String title, String[] labels, String[] figures)
	{
		StringBuilder sb = new StringBuilder("<html><body style='padding:2px'>");
		sb.append("<div style='color:#8f8f8f'>").append(title).append("</div>");
		for (int i = 0; i < labels.length && i < figures.length; i++)
		{
			String figure = figures[i].length() > 78 ? figures[i].substring(0, 78) + "..."
				: figures[i];
			sb.append("<div>").append(labels[i]).append(": <span style='color:#c8a25a'>")
				.append(figure).append("</span></div>");
		}
		return sb.append("</body></html>").toString();
	}

	private JPanel totalLevelTile(SkillStand stand, HistoryLog.Levels opened)
	{
		HistoryLog.Levels shut = stand.closed;
		boolean paired = !wholeRecord() && opened != null && shut.drawn == opened.drawn;
		long levels = paired ? shut.total - opened.total : 0;
		String figure = fmt(stand.standing);
		if (levels > 0)
		{
			figure = (stand.standing == shut.total
				? fmt(opened.total) + " to " + figure : figure) + " · +" + fmt(levels);
		}
		JPanel cell = levelTile("Total level");
		if (periodTip != null)
		{
			cell.setToolTipText(periodTip);
		}
		JLabel fig = new JLabel(figure, JLabel.RIGHT);
		fig.setFont(small());
		fig.setForeground(levels > 0 ? accent()
			: wholeRecord() ? Color.WHITE : dim());
		cell.add(fig, BorderLayout.EAST);
		return cell;
	}

	private JPanel skillCell(Skill sk, long level, Long gained, Long from)
	{
		JPanel cell = tile(3, 4);
		final String craft = prettify(low(sk.name()));
		boolean slayer = Skill.SLAYER.equals(sk);
		cell.setToolTipText(slayer ? slayerTip() : skillTip(craft));
		link(cell, slayer ? () ->
		{
			slayerLens = "Tasks";
			applyTab(View.SLAYER);
		} : () -> openSkill(craft));

		JLabel icon = new JLabel();
		BufferedImage img = skillIcon(sk);
		if (img != null)
		{
			icon.setIcon(new ImageIcon(img));
		}
		else
		{
			icon.setText(sk.name().substring(0, Math.min(3, sk.name().length())));
			styled(icon, small(), dim());
		}
		cell.add(icon, BorderLayout.WEST);

		JPanel text = new JPanel(new GridLayout(gained != null ? 2 : 1, 1));
		text.setBackground(DARKER);
		boolean climbed = from != null && level > from && !wholeRecord();
		JLabel lvl = styled(new JLabel(level <= 0 ? "-"
			: climbed ? fmt(from) + " to " + fmt(level) : String.valueOf(level)), small(),
			gained != null ? Color.WHITE : dim());
		text.add(lvl);

		if (gained != null)
		{
			JLabel g = styled(new JLabel((wholeRecord() ? "" : "+") + xpShort(gained)), small(),
				accent());
			text.add(g);
		}
		cell.add(text, BorderLayout.CENTER);
		return cell;
	}

	private static final Map<Skill, BufferedImage> SKILL_ICONS =
		new EnumMap<>(Skill.class);

	private BufferedImage skillIcon(Skill sk)
	{
		return SKILL_ICONS.computeIfAbsent(sk, s ->
		{
			try
			{
				return plugin.skillIcons().getSkillImage(s, true);
			}
			catch (Throwable e)
			{
				return null;
			}
		});
	}

	private String histFacet = "Skills";
	private String periodTip;
	private String sheetPage;
	private String measuredSince;

	private final Map<Integer, BufferedImage> facetIcons = new LinkedHashMap<>();
	private final Set<Integer> facetAsked = new HashSet<>();
	private final Map<Integer, List<Object[]>> facetWaiting = new LinkedHashMap<>();

	private void wearSprite(JLabel label, int spriteId, int w, int h)
	{
		BufferedImage have = facetIcons.get(spriteId);
		if (have != null)
		{
			dress(label, "sprite:" + spriteId + "@" + w, have, w, h);
			return;
		}
		facetWaiting.computeIfAbsent(spriteId, k -> new ArrayList<>())
			.add(new Object[]{label, w, h});
		try
		{
			net.runelite.client.game.SpriteManager sm = plugin.sprites();
			if (sm == null || !facetAsked.add(spriteId))
			{
				return;
			}
			sm.getSpriteAsync(spriteId, 0, img -> SwingUtilities.invokeLater(() ->
			{
				if (img == null)
				{
					return;
				}
				facetIcons.put(spriteId, img);
				List<Object[]> waiting = facetWaiting.remove(spriteId);
				if (waiting != null)
				{
					for (Object[] want : waiting)
					{
						dress((JLabel) want[0], "sprite:" + spriteId + "@" + want[1],
							img, (Integer) want[1], (Integer) want[2]);
					}
				}
			}));
		}
		catch (Throwable ignored)
		{
		}
	}

	private final Map<String, ImageIcon> scaledIcons = new LinkedHashMap<>();

	private void dress(JLabel label, String key, BufferedImage img, int w, int h)
	{
		ImageIcon icon = scaledIcons.get(key);
		if (icon == null)
		{
			icon = w <= 0 || h <= 0 ? new ImageIcon(img) : fit(img, w, h);
			scaledIcons.put(key, icon);
		}
		label.setIcon(icon);
		label.setText("");
	}

	private static ImageIcon fit(BufferedImage img, int w, int h)
	{
		double scale = Math.min(w / (double) img.getWidth(), h / (double) img.getHeight());
		return new ImageIcon(img.getScaledInstance(
			Math.max(1, (int) Math.round(img.getWidth() * scale)),
			Math.max(1, (int) Math.round(img.getHeight() * scale)),
			Image.SCALE_SMOOTH));
	}

	private boolean wholeRecord()
	{
		return "Lifetime".equals(histGranularity) && histFrom == null;
	}

	static final String[] PERIODS = {"Lifetime", "Year", "Month", "Week", "Day",
		"Session"};

	static final String SESSION = "Session";

	private boolean sessionPeriod()
	{
		return SESSION.equals(histGranularity) && histFrom == null;
	}

	private LocalDate periodFrom;
	private LocalDate periodTo;

	private JPopupMenu periodMenu()
	{
		JPopupMenu menu = new JPopupMenu();
		for (String g : PERIODS)
		{
			menuItem(menu, g, g.equals(histGranularity) && histFrom == null, () ->
			{
				LocalDate keep = window().end;
				LocalDate today = LocalDate.now();
				histGranularity = g;
				histFrom = null;
				histTo = null;
				histCursor = keep.isAfter(today) ? today : keep;
				rebuildInPlace();
			});
		}
		menu.addSeparator();
		menuItem(menu, "Exact dates", histFrom != null, () -> onSetExactDates(
			periodFrom != null ? periodFrom : LocalDate.now().minusDays(6),
			periodTo != null ? periodTo : LocalDate.now()));
		return menu;
	}

	private void menuItem(JPopupMenu menu, String text, boolean on, Runnable go)
	{
		JMenuItem item = new JMenuItem(text);
		item.setFont(small());
		if (on)
		{
			item.setForeground(accent());
		}
		item.addActionListener(e -> go.run());
		menu.add(item);
	}

	private JPopupMenu taskMenu()
	{
		JPopupMenu menu = new JPopupMenu();
		menuItem(menu, "Every task", lootTask == null, () -> pickTask(null));
		menu.addSeparator();
		for (String task : plugin.taskNames())
		{
			menuItem(menu, task, task.equals(lootTask), () -> pickTask(task));
		}
		return menu;
	}

	private void pickTask(String task)
	{
		lootTask = task;
		lootKind = null;
		rebuildInPlace();
	}

	private JPanel taskPicker()
	{
		JPanel r = row("Task", lootTask == null ? "Every task" : lootTask, accent());
		JLabel name = styled(part(r, BorderLayout.CENTER), small(), dim());
		JLabel pick = part(r, BorderLayout.EAST);
		pick.setFont(small());
		pick.setToolTipText("Narrow this board to one task");
		link(pick, () -> taskMenu().show(r, 0, r.getHeight()));
		link(r, () -> taskMenu().show(r, 0, r.getHeight()));
		return r;
	}

	private static final class Window
	{
		final LocalDate start;
		final LocalDate end;
		final String label;

		Window(LocalDate start, LocalDate end, String label)
		{
			this.start = start;
			this.end = end;
			this.label = label;
		}
	}

	private LocalStore.LootWindow lootWindow()
	{
		Window w = window();
		return sessionPeriod() ? plugin.sessionLootWindow() : plugin.lootBetween(w.start, w.end);
	}

	private Window window()
	{
		LocalDate end = histCursor;
		LocalDate start;
		String label;
		if (histFrom != null && histTo != null)
		{
			start = histFrom;
			end = histTo.isAfter(LocalDate.now()) ? LocalDate.now() : histTo;
			label = start.format(TASK_DAY) + " - " + end.format(TASK_DAY);
		}
		else
		{
			switch (histGranularity)
			{
				case SESSION:
				{
					long began = plugin.sessionStart();
					start = began > 0
						? dayOf(began)
						: LocalDate.now();
					end = LocalDate.now();
					label = "This session";
					break;
				}
				case "Lifetime":
					start = historySpine == null || historySpine.isEmpty()
						? end.minusYears(30) : historySpine.firstKey();
					end = LocalDate.now();
					label = "Lifetime";
					break;
				case "Day":
					start = end;
					label = end.format(FULL_DAY);
					break;
				case "Month":
					start = end.withDayOfMonth(1);
					end = start.plusMonths(1).minusDays(1);
					label = start.format(MONTH_YEAR);
					break;
				case "Year":
					start = end.withDayOfYear(1);
					end = start.plusYears(1).minusDays(1);
					label = String.valueOf(start.getYear());
					break;
				case "Week":
				default:
					start = end.minusDays(6);
					label = start.format(DAY) + " - " + end.format(FULL_DAY);
					break;
			}
		}
		periodFrom = start;
		periodTo = end;
		return new Window(start, end, label);
	}

	private static final class Span
	{
		final Baseline opening;
		final Baseline earliest;
		final Baseline closing;

		Span(Baseline opening, Baseline earliest,
			Baseline closing)
		{
			this.opening = opening;
			this.earliest = earliest;
			this.closing = closing;
		}
	}

	private Span span()
	{
		if (spanAsked)
		{
			return buildSpan;
		}
		spanAsked = true;
		buildSpan = foldSpan();
		return buildSpan;
	}

	private boolean closesOnTheClient(Entry<LocalDate, Baseline> from,
		LocalDate start, LocalDate end)
	{
		if (from == null || end.isBefore(LocalDate.now()))
		{
			return false;
		}
		return sessionPeriod() || !from.getKey().isBefore(start.minusDays(1));
	}

	private Span foldSpan()
	{
		if (historySpine == null)
		{
			gatherHistory();
			return null;
		}
		if (historySpine.isEmpty())
		{
			return null;
		}
		Window w = window();
		Entry<LocalDate, Baseline> from =
			HistoryLog.windowStart(historySpine, w.start, w.end);
		Entry<LocalDate, Baseline> at =
			historySpine.floorEntry(w.end);
		if (at == null || from == null
			|| (at.getKey().equals(from.getKey()) && !closesOnTheClient(from, w.start, w.end)))
		{
			return null;
		}
		return new Span(HistoryLog.stateAt(historySpine, from.getKey()),
			HistoryLog.earliest(historySpine, at.getKey()),
			HistoryLog.stateAt(historySpine, at.getKey()));
	}

	private Map<String, Long> closingNow(Map<String, Long> closing, Map<String, Long> live)
	{
		if (closing == null || live == null || live.isEmpty() || !periodReachesToday())
		{
			return closing;
		}
		Map<String, Long> out = new HashMap<>(closing);
		for (Entry<String, Long> e : live.entrySet())
		{
			if (e.getValue() != null)
			{
				out.merge(e.getKey(), e.getValue(), Math::max);
			}
		}
		return out;
	}

	private boolean periodReachesToday()
	{
		Window w = window();
		return w != null && !w.end.isBefore(LocalDate.now());
	}

	private Map<String, Long> countersForPeriod()
	{
		if (wholeRecord())
		{
			return withLedgerSpend(counters());
		}
		if (sessionPeriod())
		{
			Map<String, Long> out = new LinkedHashMap<>();
			for (Entry<String, Integer> e : plugin.sessionView().entrySet())
			{
				if (e.getValue() != null && e.getValue() != 0)
				{
					out.put(e.getKey(), e.getValue().longValue());
				}
			}
			return out;
		}
		Span s = span();
		if (s == null)
		{
			return null;
		}
		return peaksNotDeltas(HistoryLog.gained(s.opening.counters,
			s.earliest.counters, closingNow(s.closing.counters, counters())), s);
	}

	private String periodInSentence()
	{
		String label = window().label;
		return label.startsWith("This ")
			? Character.toLowerCase(label.charAt(0)) + label.substring(1) : label;
	}

	private static Map<String, Long> peaksNotDeltas(Map<String, Long> moved, Span s)
	{
		for (String key : StatRegistry.peakKeys())
		{
			if (!moved.containsKey(key))
			{
				continue;
			}
			long opened = s.opening.counters == null ? 0
				: s.opening.counters.getOrDefault(key, 0L);
			long shut = s.closing.counters == null ? 0
				: s.closing.counters.getOrDefault(key, 0L);
			if (shut > opened)
			{
				moved.put(key, shut);
			}
			else
			{
				moved.remove(key);
			}
		}
		return moved;
	}

	private boolean insideWindow(long ts)
	{
		if (wholeRecord())
		{
			return true;
		}
		if (ts <= 0)
		{
			return !sessionPeriod();
		}
		long[] ms = windowMs();
		return ts >= ms[0] && ts <= ms[1];
	}

	private JPanel nothingInWindow(String what)
	{
		return note("No " + what + " inside " + periodInSentence() + ".");
	}

	private List<SourceRow> skillGround(String craft)
	{
		Set<String> ownTile = new HashSet<>();
		for (String[] a : ACTIVITIES)
		{
			if (!a[1].isEmpty())
			{
				ownTile.add(low(a[1]));
			}
		}
		List<SourceRow> out = new ArrayList<>();
		for (SourceRow r : sources())
		{
			if (ownTile.contains(low(r.name)))
			{
				continue;
			}
			Skill sk = skillOf(r.name);
			if (sk != null && sk.name().equalsIgnoreCase(craft) && r.value > 0)
			{
				out.add(r);
			}
		}
		out.sort((x, y) -> Long.compare(y.value, x.value));
		return out;
	}

	private Long liveXp(String key)
	{
		long[] cur = plugin.skillSheet().get(key);
		return cur != null && cur.length > 1 && cur[1] > 0 ? cur[1] : null;
	}

	private long sessionXp(String key)
	{
		for (ExperienceStatTracker.SkillGain g : plugin.sessionSkillXp())
		{
			if (g.skill != null && g.xp > 0
				&& key.equalsIgnoreCase(g.skill.name()))
			{
				return g.xp;
			}
		}
		return 0;
	}

	private JPanel buildSkillDetail(String craft)
	{
		JPanel p = backPage();
		consumVals = plugin.consumableValues();
		String key = low(craft);

		JPanel head = card(craft);
		Span s = span();
		Long now = liveXp(key);
		if (now == null)
		{
			now = s == null ? null : s.closing.skills.get(key);
		}
		Long was = sessionPeriod()
			? (now == null ? null : Math.max(0, now - sessionXp(key)))
			: (s == null ? null : s.opening.skills.get(key));
		if (now != null && now > 0)
		{
			head.add(row("Level", String.valueOf(PaceBook.levelAt(now)), accent()));
			head.add(row("Experience", gp(now)));
			if (!wholeRecord() && was != null && now > was)
			{
				head.add(row("Gained", "+" + gp(now - was), accent()));
			}
		}
		else
		{
			head.add(row("Level", "-"));
		}
		long minutes = (wholeRecord() ? counters() : periodCounters())
			.getOrDefault(StatKeys.timeKey(craft), 0L);
		if (minutes > 0 && minutesCoverPeriod())
		{
			long gained = !wholeRecord() && was != null && now != null && now > was ? now - was : 0;
			boolean rate = gained > 0 && minutes >= 30;
			head.add(row("Time", hoursMinutes(minutes)
				+ (rate ? " · " + gp(Math.round(gained * 60.0 / minutes)) + " xp/h" : "")));
		}
		spaced(p, head);

		Map<String, Long> counters = countersForPeriod();
		if (counters == null)
		{
			p.add(noPeriod());
			return p;
		}
		List<Entry<String, Long>> rows = new ArrayList<>();
		for (Entry<String, Long> e : counters.entrySet())
		{
			if (e.getValue() == null || e.getValue() <= 0
				|| !"Skilling".equals(StatRegistry.family(e.getKey()))
				|| !craft.equalsIgnoreCase(StatRegistry.subgroup(e.getKey())))
			{
				continue;
			}
			rows.add(e);
		}
		List<SourceRow> ground = skillGround(craft);
		if (rows.isEmpty() && ground.isEmpty())
		{
			String unkept = notCounting(false);
			return noted(p, unkept != null ? unkept : "Nothing is tracked under " + craft
				+ (wholeRecord() ? "." : " in " + periodInSentence() + "."));
		}
		rows.sort(StatRegistry::compareRows);
		addPaceLine(p, craft);
		statRows(p, rows);
		if (!ground.isEmpty())
		{
			p.add(vgap(6));
			p.add(group("WHAT IT HAS EVER PAID"));
			for (SourceRow r : ground)
			{
				JPanel line = row(r.name, gps(r.value), accent());
				link(line, () -> openSource(r.name));
				p.add(line);
			}
		}
		return p;
	}

	private JPanel noPeriod()
	{
		return note(historySpine == null
			? "Reading your history..."
			: "Nothing closed inside " + periodInSentence() + ". A period is the distance "
				+ "between two baselines, and this window holds fewer than two.");
	}

	private static JPanel fixedPeriod(JPanel r, String scope)
	{
		JLabel fixed = styled(new JLabel(scope, JLabel.CENTER), FontManager.getRunescapeFont(),
			dim());
		r.add(fixed, BorderLayout.CENTER);
		return r;
	}

	private JPanel periodRow()
	{
		final Window w = window();
		JPanel r = stepStrip();
		if (showingSitting())
		{
			return fixedPeriod(r, "This session");
		}
		if (!searchQuery().isEmpty())
		{
			return fixedPeriod(r, "Whole record");
		}
		if ((!"Lifetime".equals(histGranularity) && !sessionPeriod()) || histFrom != null)
		{
			arrows(r, () -> stepPeriod(-1), canStepForward(), () -> stepPeriod(1), null);
		}
		JLabel lbl = styled(new JLabel(w.label, JLabel.CENTER), FontManager.getRunescapeFont(),
			accent());
		lbl.setToolTipText("Choose the period");
		link(lbl, () -> periodMenu().show(r, 0, r.getHeight()));
		r.add(lbl, BorderLayout.CENTER);
		return r;
	}

	private static JPanel stepStrip()
	{
		JPanel r = new JPanel(new BorderLayout());
		r.setBackground(DARKER);
		r.setBorder(pad(3, 8, 3, 8));
		r.setAlignmentX(Component.LEFT_ALIGNMENT);
		r.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
		return r;
	}

	private void arrows(JPanel r, Runnable back, boolean ahead, Runnable forward, JLabel title)
	{
		JLabel b = new JLabel("<");
		JLabel fwd = new JLabel(">");
		for (JLabel arrow : new JLabel[]{b, fwd})
		{
			arrow.setFont(FontManager.getRunescapeBoldFont());
			arrow.setBorder(pad(0, 6, 0, 6));
		}
		b.setForeground(accent());
		link(b, back);
		fwd.setForeground(ahead ? accent() : dim());
		if (ahead)
		{
			link(fwd, forward);
		}
		r.add(b, BorderLayout.WEST);
		if (title != null)
		{
			r.add(title, BorderLayout.CENTER);
		}
		r.add(fwd, BorderLayout.EAST);
	}

	private boolean showingSitting()
	{
		return view == View.HOME && !allTrackers && detailSkill == null
			&& detailItem == null && detailSource == null && detailTask < 0
			&& leftBehindSource == null && leftBehindItem == null
			&& searchQuery().isEmpty();
	}

	private boolean canStepForward()
	{
		if (histFrom != null && histTo != null)
		{
			return histTo.isBefore(LocalDate.now());
		}
		return !stepForward(histCursor).isAfter(LocalDate.now());
	}

	private void stepPeriod(int by)
	{
		if (histFrom != null && histTo != null)
		{
			long span = java.time.temporal.ChronoUnit.DAYS.between(histFrom, histTo) + 1;
			histFrom = by < 0 ? histFrom.minusDays(span) : histFrom.plusDays(span);
			histTo = by < 0 ? histTo.minusDays(span) : histTo.plusDays(span);
		}
		else if (by < 0)
		{
			histCursor = stepBack(histCursor);
		}
		else
		{
			LocalDate next = stepForward(histCursor);
			histCursor = next.isAfter(LocalDate.now())
				? LocalDate.now() : next;
		}
		rebuild();
	}

	private JPanel buildHistory()
	{
		JPanel p = column();
		Window periodWin = window();
		final LocalDate pStart = periodWin.start;
		final LocalDate pEnd = periodWin.end;
		final boolean live = !pEnd.isBefore(LocalDate.now());

		if (historySpine == null || !LocalDate.now().equals(historyDay)
			|| newestTs(plugin.feedNewest(1)) != historyFeedTs)
		{
			gatherHistory();
		}
		if (historySpine == null)
		{
			return noted(p, "Reading your history…");
		}
		TreeMap<LocalDate, Baseline> hist = historySpine;

		Entry<LocalDate, Baseline> before =
			hist.floorEntry(pStart.minusDays(1));
		Entry<LocalDate, Baseline> from =
			HistoryLog.windowStart(hist, pStart, pEnd);
		Entry<LocalDate, Baseline> at = hist.floorEntry(pEnd);
		if (at == null || from == null
			|| (at.getKey().equals(from.getKey()) && !closesOnTheClient(from, pStart, pEnd)))
		{
			String empty;
			if (hist.isEmpty())
			{
				empty = "The record starts today: baselines close at each login, "
					+ "day rollover and logout, and a period is the distance "
					+ "between two of them.";
			}
			else if (hist.firstKey().isBefore(pStart)
				&& ("Day".equals(histGranularity) || "Week".equals(histGranularity)))
			{
				empty = "The imported past resolves by month. Switch to Month "
					+ "or Year to read this era. Daily detail begins with the plugin.";
			}
			else
			{
				empty = "Nothing recorded in this period.";
			}
			p.add(note(empty));
		}
		else
		{
			if (before == null)
			{
				measuredSince = "Measured since " + from.getKey().format(FULL_DAY)
					+ ", the earliest baseline on record.";
			}
			else if (before.getKey().isBefore(pStart.minusDays(1)))
			{
				measuredSince = "Measured since " + before.getKey().format(FULL_DAY)
					+ ", the nearest earlier baseline.";
			}
			Baseline earliest = HistoryLog.earliest(hist, at.getKey());
			Baseline closing = HistoryLog.stateAt(hist, at.getKey());
			Baseline opening = HistoryLog.stateAt(hist, from.getKey());
			Map<String, Long> closesOn = live ? closingSkills(closing.skills, true) : closing.skills;
			List<Entry<String, Long>> gains = new ArrayList<>();
			for (Entry<String, Long> e : HistoryLog.gained(opening.skills,
				earliest.skills, closesOn, opening.complete).entrySet())
			{
				if (!"overall".equals(e.getKey()))
				{
					gains.add(e);
				}
			}
			if (sessionPeriod())
			{
				gains.clear();
				for (ExperienceStatTracker.SkillGain g : plugin.sessionSkillXp())
				{
					if (g.skill != null && g.xp > 0)
					{
						gains.add(new AbstractMap.SimpleEntry<>(
							low(g.skill.name()), g.xp));
					}
				}
			}
			gains.sort(Entry.<String, Long>comparingByValue().reversed());

			long fromMs = sessionPeriod() ? windowMs()[0]
				: startMs(pStart);
			long toMs = sessionPeriod() ? windowMs()[1]
				: startMs(pEnd.plusDays(1));
			Map<String, Long> fromFeed = new HashMap<>();
			Map<String, List<String[]>> named = new LinkedHashMap<>();
			long[] played = {0, 0};
			long[] took = {0, 0, 0, 0, 0, 0};
			for (JsonObject e : historyFeed)
			{
				long ts = safeLong(e.get("ts"));
				long filed = filedAt(e);
				if (filed >= fromMs && filed < toMs)
				{
					String type = typeOf(e);
					String key = FEED_SUMMARY_KEYS.get(type);
					if (key != null)
					{
						fromFeed.merge(key, 1L, Long::sum);
						String line = feedName(e);
						if (line != null)
						{
							named.computeIfAbsent(key, k -> new ArrayList<>())
								.add(new String[]{line, DAY.format(Instant.ofEpochMilli(ts))});
						}
					}
					if ("SESSION".equals(type))
					{
						played[0] += sessionMinutes(e);
						played[1]++;
						JsonObject d = obj(e, "data");
						took[0] += safeLong(d.get("drops"));
						took[1] += safeLong(d.get("dropsGp"));
						took[2] += safeLong(d.get("left"));
						took[3] += safeLong(d.get("leftGp"));
						took[4] += safeLong(d.get("leftKills"));
						if (d.has("leftKills"))
						{
							took[5]++;
						}
					}
				}
			}
			Map<String, Long> retro = new HashMap<>();
			boolean sessionsHoldTheFloor = false;
			boolean sessionsSpeak = false;
			if (historyJourney != null)
			{
				long closedN = 0;
				long closedKills = 0;
				for (SlayerTask t : historyJourney.tasks)
				{
					if (closedInside(t, fromMs, toMs))
					{
						closedN++;
						closedKills += t.kills;
					}
				}
				retro.put("slayerTasksCompleted", closedN);
				retro.put("slayerKills", closedKills);
				List<String[]> tasks = closedTaskNames(historyJourney, fromMs, toMs);
				if (!tasks.isEmpty())
				{
					named.put("slayerTasksCompleted", tasks);
				}
			}
			long oldest = oldestTs(historyFeed, false);
			boolean reachesBack = oldest > 0 && oldest < fromMs;
			if (reachesBack)
			{
				for (String key : FEED_SUMMARY_KEYS.values())
				{
					retro.put(key, fromFeed.getOrDefault(key, 0L));
				}
				long rollFrom = plugin.lootRollFrom();
				LocalStore.LootWindow dated = null;
				if (sessionPeriod())
				{
					dated = plugin.sessionLootWindow();
				}
				else if (rollFrom > 0 && rollFrom <= fromMs)
				{
					dated = plugin.lootBetween(pStart, pEnd);
				}
				if (dated != null)
				{
					sessionsSpeak = true;
					sessionsHoldTheFloor = true;
					retro.put("dropsReceived", dated.loots);
					retro.put("lootValue", dated.value);
					retro.put("lootLeftCount", dated.left);
					retro.put("lootLeftValue", dated.leftValue);
					retro.put("lootLeftKills", dated.leftKills);
					if (!dated.items.isEmpty())
					{
						named.put("lootValue", itemLines(dated.items, false));
					}
					if (!dated.leftItems.isEmpty())
					{
						named.put("lootLeftCount", itemLines(dated.leftItems, false));
					}
					if (!dated.sources.isEmpty())
					{
						named.put("dropsReceived", itemLines(dated.sources, true));
					}
				}
				else if (sittingsCover(historyFeed, fromMs) && played[1] > 0 && took[0] > 0)
				{
					sessionsSpeak = true;
					retro.put("dropsReceived", took[0]);
					retro.put("lootValue", took[1]);
					retro.put("lootLeftCount", took[2]);
					retro.put("lootLeftValue", took[3]);
					retro.put("lootLeftKills", took[4]);
					sessionsHoldTheFloor = took[5] == played[1];
				}
			}
			else
			{
				if (!"Lifetime".equals(histGranularity) || histFrom != null)
				{
					for (String key : FEED_SUMMARY_KEYS.values())
					{
						named.remove(key);
					}
					played[0] = 0;
					played[1] = 0;
				}
			}

			long began = plugin.sessionStart();
			if (sessionPeriod() ? live : (began > 0 && began >= fromMs && began < toMs))
			{
				long running = plugin.sessionElapsedMinutes();
				if (running > 0)
				{
					played[0] += running;
					played[1]++;
				}
			}
			if (wholeRecord())
			{
				long theirs = plugin.gamePlaytimeMinutes();
				if (theirs > played[0])
				{
					played[0] = theirs;
				}
			}

			LocalDate leftFrom = HistoryLog.firstCarrying(
				hist.headMap(at.getKey(), true), "lootLeftKills");
			boolean leftDated = sessionsSpeak
				? sessionsHoldTheFloor
				: (leftFrom != null && !leftFrom.isAfter(from.getKey()));
			boolean whole = wholeRecord();
			HistoryProgress progress = HistoryProgress.of(
				whole ? withLedgerSpend(closing.counters)
					: HistoryLog.gained(opening.counters, earliest.counters,
						closing.counters),
				null, whole ? new HashMap<>() : retro, leftDated || whole);
			Baseline sittingOpen = null;
			if (sessionPeriod())
			{
				Map<String, Long> openXp = new HashMap<>(closesOn);
				for (ExperienceStatTracker.SkillGain g : plugin.sessionSkillXp())
				{
					if (g.skill == null || g.xp <= 0)
					{
						continue;
					}
					String key = low(g.skill.name());
					Long had = openXp.get(key);
					if (had != null)
					{
						openXp.put(key, Math.max(0, had - g.xp));
					}
				}
				sittingOpen = baselineAt(openXp);
				closing = baselineAt(closesOn);
			}
			SkillStand stand = skillStand(closing, live);
			HistoryLog.Levels opened = sittingOpen != null
				? HistoryLog.levels(sittingOpen, stand.keys)
				: HistoryLog.levels(opening, stand.keys);
			if (view == View.SHEET)
			{
				periodTip = periodTip(played, gains);
			}
			else
			{
				spaced(p, headline(progress, gains, stand, opened, played), 5);
			}
			LocalDate lootSince = null;
			long lootFromTs = earliestDatedLoot(historyFeed, plugin.lootRollFrom());
			if (lootFromTs > 0)
			{
				LocalDate sat = dayOf(lootFromTs);
				if (sat.isAfter(pStart))
				{
					lootSince = sat;
				}
			}
			String since = wholeRecord() ? null
				: countersSince(hist.headMap(at.getKey(), true), from.getKey(),
					lootSince, lootFromTs > 0);
			if (since != null)
			{
				spaced(p, note(since), 5);
			}

			if ("PvM".equals(histFacet))
			{
				addLootValues(p, progress);
				addKinds(p, opening.kcs, earliest.kcs, closing.kcs, live, pStart, pEnd,
					KIND_BOSS, KIND_MONSTER);
			}
			else if ("Activities".equals(histFacet))
			{
				addKinds(p, opening.kcs, earliest.kcs, closing.kcs, live, pStart, pEnd,
					KIND_ACTIVITY, KIND_SKILLING);
			}
			else if ("Trackers".equals(histFacet))
			{
				if (wholeRecord())
				{
					p.add(buildStats());
				}
				else if (!progress.groups().isEmpty() || !gains.isEmpty())
				{
					spaced(p, trackedProgress(progress, gains, named), 5);
				}
			}
			else
			{
				addSkillGrid(p, gains, stand, opened);
			}
		}

		return p;
	}

	private void onSetExactDates(LocalDate from, LocalDate to)
	{
		JTextField fromField = new JTextField(from.toString());
		JTextField toField = new JTextField(to.toString());
		JPanel form = new JPanel(new GridLayout(0, 1, 0, 4));
		form.add(new JLabel("From (yyyy-mm-dd):"));
		form.add(fromField);
		form.add(new JLabel("To (yyyy-mm-dd):"));
		form.add(toField);
		int ok = JOptionPane.showConfirmDialog(this, form,
			"Exact dates", JOptionPane.OK_CANCEL_OPTION);
		if (ok != JOptionPane.OK_OPTION)
		{
			return;
		}
		LocalDate f = parseDate(fromField.getText());
		LocalDate t = parseDate(toField.getText());
		if (f == null || t == null)
		{
			JOptionPane.showMessageDialog(this,
				"Dates read as yyyy-mm-dd (or d/m/yyyy). Nothing changed.");
			return;
		}
		if (t.isBefore(f))
		{
			LocalDate swap = f;
			f = t;
			t = swap;
		}
		histFrom = f;
		histTo = t;
		rebuild();
	}

	private static LocalDate parseDate(String text)
	{
		String s = text == null ? "" : text.trim();
		try
		{
			return LocalDate.parse(s);
		}
		catch (RuntimeException ignored)
		{
		}
		try
		{
			return LocalDate.parse(s,
				DateTimeFormatter.ofPattern("d/M/yyyy"));
		}
		catch (RuntimeException ignored)
		{
			return null;
		}
	}

	private LocalDate stepBack(LocalDate d)
	{
		switch (histGranularity)
		{
			case "Day":
				return d.minusDays(1);
			case "Month":
				return d.withDayOfMonth(1).minusDays(1);
			case "Year":
				return d.withDayOfYear(1).minusDays(1);
			case "Week":
			default:
				return d.minusDays(7);
		}
	}

	private LocalDate stepForward(LocalDate d)
	{
		switch (histGranularity)
		{
			case "Day":
				return d.plusDays(1);
			case "Month":
				return d.withDayOfMonth(1).plusMonths(2).minusDays(1);
			case "Year":
				return d.withDayOfYear(1).plusYears(2).minusDays(1);
			case "Week":
			default:
				return d.plusDays(7);
		}
	}

	private static final String[][] JOURNAL_LENSES = {
		{"All"},
		{"Log", "COLLECTION"},
		{"Slayer", "SLAYER"},
		{"Feats", "COMBAT_ACHIEVEMENT", "QUEST", "DIARY", "CLUE", "PET", "LEVEL", "RECORD",
			"MILESTONE"},
		{"Deaths", "DEATH"},
		{"Sessions", "SESSION"},
	};
	private String journalLens = "All";
	private int journalShown = 60;

	private void openJournalOn(long ts)
	{
		if (ts > 0)
		{
			histGranularity = "Day";
			histFrom = null;
			histTo = null;
			histCursor = dayOf(ts);
		}
		journalLens = "All";
		applyTab(View.JOURNAL);
	}

	private List<JsonObject> milestones;

	private static final long[] TOTAL_LEVELS = {1000, 1500, 2000, 2200, 2277, 2376};
	private static final long[] NINETY_NINES = {5, 10, 15, 20};
	private static final long[] COMBAT_LEVELS = {100, 126};
	private static final long[] SKILL_XP = {10_000_000L, 50_000_000L, 100_000_000L, 200_000_000L};
	private static final long[] OVERALL_XP = {100_000_000L, 250_000_000L, 500_000_000L, 1_000_000_000L};
	private static final long[] LOG_SLOTS = {500, 1000, 1500};

	private List<JsonObject> milestones()
	{
		if (milestones == null)
		{
			milestones = new ArrayList<>();
			TreeMap<LocalDate, Baseline> spine = historySpine;
			if (spine != null && spine.size() > 1)
			{
				List<String> keys = new ArrayList<>();
				for (Skill sk : Skill.values())
				{
					if (sk != Skill.OVERALL)
					{
						keys.add(low(sk.name()));
					}
				}
				Map<String, Long> prev = null;
				for (Entry<LocalDate, Baseline> day : spine.entrySet())
				{
					Map<String, Long> now = standings(day.getValue(), keys);
					if (prev != null)
					{
						long ts = noon(day.getKey());
						crossings(prev, now, ts, milestones);
					}
					Map<String, Long> carried = prev == null
						? new LinkedHashMap<>() : new LinkedHashMap<>(prev);
					carried.putAll(now);
					prev = carried;
				}
				Collections.reverse(milestones);
			}
		}
		return milestones;
	}

	private static Map<String, Long> standings(Baseline b, List<String> keys)
	{
		Map<String, Long> out = new LinkedHashMap<>();
		HistoryLog.Levels lv = HistoryLog.levels(b, keys);
		if (b.complete)
		{
			out.put("total", (long) lv.total);
			out.put("nines", (long) lv.nines);
			Integer combat = openingCombat(lv);
			if (combat != null)
			{
				out.put("combat", (long) combat);
			}
		}
		for (String key : keys)
		{
			Long xp = b.skills.get(key);
			if (xp != null)
			{
				out.put("xp:" + key, xp);
			}
		}
		Long overall = b.skills.get("overall");
		if (overall != null)
		{
			out.put("overall", overall);
		}
		Long slots = b.counters.get("clogSlotsObtained");
		if (slots != null)
		{
			out.put("slots", slots);
		}
		return out;
	}

	private static void crossings(Map<String, Long> prev, Map<String, Long> now, long ts,
		List<JsonObject> into)
	{
		cross(prev, now, ts, into, "total", TOTAL_LEVELS, t -> "Total level " + fmt(t));
		cross(prev, now, ts, into, "nines", NINETY_NINES, t -> ordinal(t) + " 99");
		cross(prev, now, ts, into, "combat", COMBAT_LEVELS, t -> "Combat level " + t);
		for (String key : now.keySet())
		{
			if (key.startsWith("xp:"))
			{
				cross(prev, now, ts, into, key, SKILL_XP, t -> threshold(t) + " xp in "
					+ prettify(key.substring(3)));
			}
		}
		cross(prev, now, ts, into, "overall", OVERALL_XP, t -> threshold(t) + " xp overall");
		cross(prev, now, ts, into, "slots", LOG_SLOTS, t -> fmt(t) + " collection log slots");
	}

	private static void cross(Map<String, Long> prev, Map<String, Long> now, long ts,
		List<JsonObject> into, String key, long[] at, LongFunction<String> text)
	{
		Long before = prev.get(key);
		Long after = now.get(key);
		for (long t : at)
		{
			if (before != null && after != null && before < t && after >= t)
			{
				into.add(milestone(ts, text.apply(t)));
			}
		}
	}

	private static String threshold(long xp)
	{
		return xp % 1_000_000_000L == 0 ? xp / 1_000_000_000L + "B" : xp / 1_000_000L + "M";
	}

	private static JsonObject milestone(long ts, String text)
	{
		JsonObject e = new JsonObject();
		e.addProperty("ts", ts);
		e.addProperty("type", "MILESTONE");
		JsonObject d = new JsonObject();
		d.addProperty("text", text);
		e.add("data", d);
		return e;
	}

	private static String ordinal(long n)
	{
		long last = n % 10;
		long tens = n % 100;
		String suffix = tens >= 11 && tens <= 13 ? "th"
			: last == 1 ? "st" : last == 2 ? "nd" : last == 3 ? "rd" : "th";
		return n + suffix;
	}

	private List<JsonObject> withMilestones(List<JsonObject> feed)
	{
		List<JsonObject> marks = milestones();
		if (marks.isEmpty())
		{
			return feed;
		}
		List<JsonObject> out = new ArrayList<>(feed.size() + marks.size());
		int m = 0;
		boolean liveHead = !feed.isEmpty() && feed.get(0).has("live");
		for (int i = 0; i < feed.size(); i++)
		{
			long ts = safeLong(feed.get(i).get("ts"));
			while ((i > 0 || !liveHead) && m < marks.size()
				&& safeLong(marks.get(m).get("ts")) > ts)
			{
				out.add(marks.get(m++));
			}
			out.add(feed.get(i));
		}
		while (m < marks.size())
		{
			out.add(marks.get(m++));
		}
		return out;
	}

	private Map<String, Long> landedSlots;

	private Map<String, Long> landedSlots()
	{
		if (landedSlots == null)
		{
			landedSlots = new LinkedHashMap<>();
			for (JsonObject e : plugin.feedNewest(FEED_SCAN_DEEP))
			{
				if (!"COLLECTION".equals(typeOf(e)))
				{
					continue;
				}
				JsonObject d = obj(e, "data");
				if (has(d, "itemName"))
				{
					landedSlots.put(low(d.get("itemName").getAsString()),
						safeLong(e.get("ts")));
				}
			}
		}
		return landedSlots;
	}

	private Map<String, JsonObject> records;

	private Map<String, JsonObject> records()
	{
		if (records == null)
		{
			records = new LinkedHashMap<>();
			for (JsonObject e : plugin.feedNewest(FEED_SCAN_DEEP))
			{
				if (!"RECORD".equals(typeOf(e)))
				{
					continue;
				}
				JsonObject d = obj(e, "data");
				if (has(d, "source"))
				{
					records.putIfAbsent(low(d.get("source").getAsString()), e);
				}
			}
		}
		return records;
	}

	private Map<LocalDate, long[]> daysPlayed;
	private Map<String, long[]> dayTotals;

	private Map<LocalDate, long[]> daysPlayed()
	{
		if (daysPlayed == null)
		{
			daysPlayed = new LinkedHashMap<>();
			daySkills = new LinkedHashMap<>();
			crossedDays = new HashSet<>();
			for (JsonObject e : plugin.feedWithSitting(FEED_SCAN_DEEP))
			{
				if (!"SESSION".equals(typeOf(e)))
				{
					continue;
				}
				JsonObject d = obj(e, "data");
				LocalDate day = dayOf(sittingStart(e));
				long ended = safeLong(e.get("ts"));
				if (ended > 0)
				{
					LocalDate last = dayOf(ended);
					for (LocalDate on = day; last.isAfter(day) && !on.isAfter(last); on = on.plusDays(1))
					{
						crossedDays.add(on);
					}
				}
				long[] t = daysPlayed.computeIfAbsent(day, k -> new long[8]);
				t[0] += sessionMinutes(e);
				t[1]++;
				if (d.has("xp"))
				{
					t[2] += safeLong(d.get("xp"));
					t[3]++;
				}
				if (d.has("drops"))
				{
					t[4] += safeLong(d.get("drops"));
					t[5] += safeLong(d.get("dropsGp"));
					t[6]++;
				}
				if (d.has("xp") && safeLong(d.get("xp")) == 0 && !d.has("skills"))
				{
					t[7]++;
				}
				if (d.has("skills") && d.get("skills").isJsonObject())
				{
					t[7]++;
					Map<String, Long> by = daySkills.computeIfAbsent(day, k -> new LinkedHashMap<>());
					for (Entry<String, JsonElement> sk : d.getAsJsonObject("skills").entrySet())
					{
						by.merge(sk.getKey(), safeLong(sk.getValue()), Long::sum);
					}
				}
			}
		}
		return daysPlayed;
	}

	private Map<LocalDate, Map<String, Long>> daySkills;
	private Set<LocalDate> crossedDays;

	private Map<String, long[]> dayTotals()
	{
		if (dayTotals == null)
		{
			dayTotals = plugin.dayTotals();
		}
		return dayTotals;
	}

	private static final DateTimeFormatter ROLL_DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

	private Object[] dayXp(LocalDate day)
	{
		TreeMap<LocalDate, Baseline> spine = historySpine;
		if (spine == null)
		{
			return null;
		}
		Baseline at = spine.get(day);
		Entry<LocalDate, Baseline> before = spine.lowerEntry(day);
		if (at == null || before == null || !before.getKey().plusDays(1).equals(day))
		{
			return null;
		}
		long total = 0;
		long most = 0;
		String top = null;
		for (Entry<String, Long> e : at.skills.entrySet())
		{
			Long was = before.getValue().skills.get(e.getKey());
			if ("overall".equals(e.getKey()) || was == null)
			{
				continue;
			}
			long gained = e.getValue() - was;
			if (gained <= 0)
			{
				continue;
			}
			total += gained;
			if (gained > most)
			{
				most = gained;
				top = e.getKey();
			}
		}
		return total > 0 ? new Object[]{total, prettify(top)} : null;
	}

	static int boardRowRoom()
	{
		return PluginPanel.PANEL_WIDTH + PluginPanel.SCROLLBAR_WIDTH
			- 2 * PANEL_INSET - scrollbarWidth() - 2 * ROW_INSET;
	}

	static List<String> wrapClauses(String text, int room)
	{
		FontMetrics fm = rowMetrics();
		List<String> out = new ArrayList<>();
		String line = null;
		for (String clause : text.split(" \u00b7 "))
		{
			String tried = line == null ? clause : line + " \u00b7 " + clause;
			if (line != null && fm.stringWidth(tried) > room)
			{
				out.add(line);
				line = clause;
			}
			else
			{
				line = tried;
			}
		}
		if (line != null)
		{
			out.add(line);
		}
		return out;
	}

	private String dayEntry(LocalDate day)
	{
		List<String> clauses = new ArrayList<>();
		long[] sat = daysPlayed().get(day);
		if (sat != null && sat[1] > 0)
		{
			clauses.add(sat[1] + (sat[1] == 1 ? " sitting" : " sittings")
				+ (sat[0] > 0 ? " · " + hoursMinutes(sat[0]) : ""));
		}
		boolean crossed = crossedDays != null && crossedDays.contains(day);
		boolean saysXp = crossed && sat != null && sat[1] > 0 && sat[3] == sat[1];
		Object[] xp = saysXp ? null : dayXp(day);
		if (saysXp && sat[2] > 0)
		{
			Map<String, Long> by = sat[7] == sat[1] && daySkills != null ? daySkills.get(day) : null;
			String most = by == null ? null : topOf(by);
			if (most != null)
			{
				most = prettify(most);
			}
			else
			{
				Object[] spine = dayXp(day);
				most = spine != null ? (String) spine[1] : null;
			}
			clauses.add("+" + gp(sat[2]) + " xp" + (most != null ? ", most in " + most : ""));
		}
		else if (xp != null)
		{
			clauses.add("+" + gp((Long) xp[0]) + " xp, most in " + xp[1]);
		}
		long[] loot = crossed && sat != null && sat[1] > 0 && sat[6] == sat[1]
			? new long[]{sat[4], sat[5]} : dayTotals().get(ROLL_DAY.format(day));
		if (loot != null && loot[0] > 0)
		{
			clauses.add(fmt(loot[0]) + (loot[0] == 1 ? " drop" : " drops")
				+ tail(loot[1]));
		}
		return clauses.isEmpty() ? null : String.join(" · ", clauses);
	}

	private JPanel buildRecap()
	{
		JPanel p = column();
		p.add(copyHeaderLater("Recap", take ->
		{
			take.setText("copying");
			copyRecapPicture(take);
		}));
		p.add(recapPlate());
		return p;
	}

	private void copyRecapPicture(JLabel take)
	{
		RecapPicture.Facts facts;
		try
		{
			facts = recapFacts();
		}
		catch (Throwable t)
		{
			reportCopy(take, false);
			return;
		}
		Set<Integer> want = new LinkedHashSet<>();
		for (RecapPicture.BossLine b : facts.bosses)
		{
			if (b.sprite > 0 && !facetIcons.containsKey(b.sprite))
			{
				want.add(b.sprite);
			}
		}
		for (int id : want)
		{
			wearSprite(new JLabel(), id, 22, 22);
		}
		long deadline = System.currentTimeMillis() + 1500;
		Timer wait = new Timer(100, null);
		wait.addActionListener(e ->
		{
			boolean all = true;
			for (int id : want)
			{
				all &= facetIcons.containsKey(id);
			}
			if (all || System.currentTimeMillis() > deadline)
			{
				wait.stop();
				reportCopy(take, toClipboard(recapImage(facts)));
			}
		});
		wait.setInitialDelay(want.isEmpty() ? 0 : 100);
		wait.start();
	}

	BufferedImage recapImage(RecapPicture.Facts facts)
	{
		try
		{
			return RecapPicture.paint(facts, this::skillIcon, facetIcons::get);
		}
		catch (Throwable t)
		{
			return null;
		}
	}

	RecapPicture.Facts recapFacts()
	{
		RecapPicture.Facts f = new RecapPicture.Facts();
		f.whole = wholeRecord();
		f.session = sessionPeriod();
		f.title = f.whole ? "The whole record" : window().label;
		Span s = f.whole || f.session ? null : span();
		recapSkills(f, s);
		recapBosses(f, s);
		recapMonsters(f, s);
		recapLoot(f);
		recapSlayerAndClues(f);
		recapTrackers(f);
		recapFeats(f);
		recapTiles(f);
		return f;
	}

	private void recapSkills(RecapPicture.Facts f, Span s)
	{
		Map<String, long[]> sheet = plugin.skillSheet();
		Map<String, Long> closing = null;
		if (!f.whole && !f.session)
		{
			if (s == null)
			{
				f.skillsNote = notCounting(false) != null
					? "The record keeps no experience this far back."
					: "Nothing closed inside " + periodInSentence()
						+ ": a period is the distance between two baselines, and this one holds fewer than two.";
				return;
			}
			closing = closingSkills(s.closing.skills, periodReachesToday());
		}
		Map<String, Integer> startLevels = new LinkedHashMap<>();
		Map<String, Integer> endLevels = new LinkedHashMap<>();
		long startXp = 0;
		long endXp = 0;
		boolean startsKnown = true;
		for (Skill sk : Skill.values())
		{
			if (sk == Skill.OVERALL)
			{
				continue;
			}
			String key = low(sk.name());
			Long end;
			Long start = null;
			if (closing != null)
			{
				end = closing.get(key);
				Long base = s.opening.skills.get(key);
				if (base == null)
				{
					base = s.opening.complete ? Long.valueOf(0L) : s.earliest.skills.get(key);
				}
				start = base == null || end == null ? null : Math.min(base, end);
			}
			else
			{
				long[] cur = sheet.get(key);
				end = cur != null && cur.length > 1 ? cur[1] : null;
				if (f.session && end != null)
				{
					start = Math.max(0, end - sessionXp(key));
				}
			}
			if (end == null)
			{
				continue;
			}
			int lEnd = f.whole ? PaceBook.virtualLevelAt(end) : PaceBook.levelAt(end);
			Integer lStart = start == null ? null : PaceBook.levelAt(start);
			if (sk == Skill.HITPOINTS)
			{
				lEnd = Math.max(10, lEnd);
				lStart = lStart == null ? null : Math.max(10, lStart);
			}
			f.skills.add(new RecapPicture.SkillLine(sk, prettify(key), lStart, lEnd,
				start, end));
			endLevels.put(key, Math.min(99, lEnd));
			endXp += end;
			if (start == null)
			{
				startsKnown = false;
			}
			else
			{
				startLevels.put(key, lStart);
				startXp += start;
			}
		}
		if (f.skills.isEmpty())
		{
			return;
		}
		long[] overall = sheet.get("overall");
		long totalEnd = f.whole && overall != null && overall[0] > 0 ? overall[0] : sum(endLevels);
		f.totalLevel = new Long[]{startsKnown ? sum(startLevels) : null, totalEnd};
		f.totalXp = new Long[]{startsKnown ? startXp : null,
			f.whole && overall != null && overall.length > 1 && overall[1] > 0 ? overall[1] : endXp};
		Integer cEnd = combatOf(endLevels);
		if (f.whole && plugin.combatLevel() > 0)
		{
			cEnd = plugin.combatLevel();
		}
		Integer cStart = startsKnown ? combatOf(startLevels) : null;
		f.combat = cEnd == null ? null : new Long[]{cStart == null ? null : (long) cStart, (long) cEnd};
	}

	private static long sum(Map<String, Integer> levels)
	{
		long t = 0;
		for (int l : levels.values())
		{
			t += l;
		}
		return t;
	}

	private static Integer combatOf(Map<String, Integer> levels)
	{
		HistoryLog.Levels l = new HistoryLog.Levels();
		l.of.putAll(levels);
		return openingCombat(l);
	}

	private void recapBosses(RecapPicture.Facts f, Span s)
	{
		List<Boss> roster = bossRoster(plugin.gson());
		Map<String, Long> closing = s == null ? null : closingNow(s.closing.kcs, plugin.killCounts());
		for (Boss b : roster)
		{
			if (f.whole)
			{
				long k = bossKills(b.name);
				if (k > 0)
				{
					f.bosses.add(new RecapPicture.BossLine(b.name, b.sprite, null, k, k));
				}
				continue;
			}
			long moved = bossKillsInWindow(b.name);
			if (moved <= 0)
			{
				continue;
			}
			Long start = null;
			Long end = null;
			String key = closing == null || movedKcs == null ? null : keyIgnoringCase(movedKcs, b.name);
			if (key != null)
			{
				end = closing.get(key);
				start = s.opening.kcs.containsKey(key) ? s.opening.kcs.get(key) : s.earliest.kcs.get(key);
				if (start == null || end == null || end - start != moved)
				{
					start = null;
					end = null;
				}
			}
			f.bosses.add(new RecapPicture.BossLine(b.name, b.sprite, start, end, moved));
		}
		if (!f.whole)
		{
			f.bosses.sort((a, b) -> Long.compare(b.gained, a.gained));
		}
		if (rollUsed)
		{
			f.notes.add("Kill counts without a start and an end are read from loot, and count only the kills that dropped something.");
		}
		if (f.bosses.isEmpty() && !f.whole)
		{
			f.bossesNote = notCounting(true) != null
				? "The record keeps no kill counts this far back."
				: "No boss was killed inside " + periodInSentence() + ".";
		}
	}

	private static String keyIgnoringCase(Map<String, Long> map, String name)
	{
		if (map.containsKey(name))
		{
			return name;
		}
		for (String k : map.keySet())
		{
			if (k.equalsIgnoreCase(name))
			{
				return k;
			}
		}
		return null;
	}

	private void recapMonsters(RecapPicture.Facts f, Span s)
	{
		Map<String, Long> by = new LinkedHashMap<>();
		Map<String, Long> worth = new LinkedHashMap<>();
		if (f.whole)
		{
			by.putAll(plugin.killCounts());
			for (SourceRow r : sources())
			{
				worth.merge(r.name, r.value, Long::sum);
			}
		}
		else if (f.session)
		{
			for (String[] r : plugin.sessionLootWindow().sources)
			{
				by.merge(r[0], safeParse(r[1]), Long::sum);
				worth.merge(r[0], safeParse(r[2]), Long::sum);
			}
		}
		else
		{
			if (s == null)
			{
				return;
			}
			by.putAll(HistoryLog.gained(s.opening.kcs, s.earliest.kcs,
				closingNow(s.closing.kcs, plugin.killCounts())));
			Window w = window();
			worth.putAll(periodWorth(w.start, w.end));
		}
		Map<String, Long> loose = loosely(worth);
		List<Entry<String, Long>> kept = new ArrayList<>();
		for (Entry<String, Long> e : by.entrySet())
		{
			if (e.getValue() > 0 && recapMonster(e.getKey()))
			{
				kept.add(e);
			}
		}
		kept.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
		for (Entry<String, Long> e : kept.subList(0, Math.min(10, kept.size())))
		{
			long paid = paidFor(worth, loose, e.getKey());
			f.monsters.add(new RecapPicture.Named(e.getKey(), fmt(e.getValue()),
				paid > 0 ? gps(paid) : null));
		}
		if (f.session && !f.monsters.isEmpty())
		{
			f.monstersNote = "A sitting counts the kills that dropped something.";
		}
	}

	private boolean recapMonster(String name)
	{
		if (!KIND_MONSTER.equals(sourceKind(name)))
		{
			return false;
		}
		String kind = kindOf(name);
		for (Boss b : bossRoster(plugin.gson()))
		{
			if (kindOf(b.name).equals(kind) || namesInBrackets(name, b.name)
				|| paysOutThrough(b.name, name))
			{
				return false;
			}
		}
		for (List<String> npcs : FOUGHT_AS.values())
		{
			for (String npc : npcs)
			{
				if (kindOf(npc).equals(kind))
				{
					return false;
				}
			}
		}
		return true;
	}

	private void recapLoot(RecapPicture.Facts f)
	{
		if (f.whole)
		{
			long[] loot = periodLoot();
			if (loot[0] > 0)
			{
				f.loot.add(new RecapPicture.Named("Drops", fmt(loot[0]), gps(loot[1])));
			}
			if (loot[2] > 0)
			{
				f.loot.add(new RecapPicture.Named("Left behind", fmt(loot[2]), gps(loot[3])));
			}
			List<SourceRow> rows = new ArrayList<>(sources());
			rows.sort((a, b) -> Long.compare(b.value, a.value));
			for (SourceRow r : rows.subList(0, Math.min(8, rows.size())))
			{
				if (r.value > 0)
				{
					f.sources.add(new RecapPicture.Named(r.name, null, gps(r.value)));
				}
			}
			List<BagItem> bag = new ArrayList<>(plugin.allLoot());
			bag.sort((a, b) -> Long.compare(b.value, a.value));
			for (BagItem b : bag.subList(0, Math.min(6, bag.size())))
			{
				if (b.value > 0)
				{
					f.items.add(new RecapPicture.Named(b.name + " ×" + fmt(b.qty), null,
						gps(b.value)));
				}
			}
			return;
		}
		LocalStore.LootWindow win;
		if (f.session)
		{
			win = plugin.sessionLootWindow();
		}
		else
		{
			long from = plugin.lootRollFrom();
			if (from <= 0 || from > windowMs()[0])
			{
				f.lootNote = "Loot is not dated this far back, so this period's cannot be told from the rest.";
				return;
			}
			Window w = window();
			win = plugin.lootBetween(w.start, w.end);
		}
		if (win.loots > 0)
		{
			f.loot.add(new RecapPicture.Named("Drops", fmt(win.loots), gps(win.value)));
		}
		if (win.left > 0)
		{
			f.loot.add(new RecapPicture.Named("Left behind", fmt(win.left), gps(win.leftValue)));
		}
		for (String[] r : win.sources.subList(0, Math.min(8, win.sources.size())))
		{
			long v = safeParse(r[2]);
			if (v > 0)
			{
				f.sources.add(new RecapPicture.Named(r[0], null, gps(v)));
			}
		}
		for (String[] r : win.items.subList(0, Math.min(6, win.items.size())))
		{
			long v = safeParse(r[2]);
			if (v > 0)
			{
				f.items.add(new RecapPicture.Named(r[0] + " ×" + fmt(safeParse(r[1])), null,
					gps(v)));
			}
		}
		if (f.loot.isEmpty())
		{
			f.lootNote = "Nothing dropped inside " + periodInSentence() + ".";
		}
	}

	private void recapSlayerAndClues(RecapPicture.Facts f)
	{
		long[] ms = windowMs();
		long[] tally = plugin.onTaskTally(ms[0], ms[1], null, f.whole);
		if (tally[2] > 0)
		{
			long paid = tallyOf(plugin.onTaskLoot(ms[0], ms[1], null, f.whole))[1];
			f.slayer.add(new RecapPicture.Named("Tasks", fmt(tally[2]), null));
			if (tally[1] > 0)
			{
				f.slayer.add(new RecapPicture.Named("Superiors", fmt(tally[1]), null));
			}
			if (paid > 0)
			{
				f.slayer.add(new RecapPicture.Named("On-task loot", null, gps(paid)));
			}
		}
		for (String tier : CLUE_TIERS)
		{
			String source = "Clue Scroll (" + tier + ")";
			long n = 0;
			long v = 0;
			if (f.whole)
			{
				for (SourceRow r : sources())
				{
					if (r.name.equalsIgnoreCase(source))
					{
						n = Math.max(r.kc, r.loots);
						v = r.value;
					}
				}
			}
			else
			{
				Long rolled = rolledKills(source);
				n = rolled == null ? 0 : rolled;
				v = sourceInWindow(source)[1];
			}
			if (n > 0)
			{
				f.clues.add(new RecapPicture.Named(tier, fmt(n), v > 0 ? gps(v) : null));
			}
		}
	}

	private static final Map<String, Integer> RECAP_TRACKER_ROWS = new LinkedHashMap<>();

	static
	{
		RECAP_TRACKER_ROWS.put("Combat", 8);
		RECAP_TRACKER_ROWS.put("Skilling", 10);
		RECAP_TRACKER_ROWS.put("Living", 6);
		RECAP_TRACKER_ROWS.put("Ledger & Roads", 10);
	}

	private void recapTrackers(RecapPicture.Facts f)
	{
		Map<String, Long> counters = countersForPeriod();
		if (counters == null)
		{
			f.trackersNote = notCounting(false) != null
				? "The record keeps no counters this far back."
				: "Nothing closed inside " + periodInSentence() + ".";
			return;
		}
		for (Entry<String, Integer> fam : RECAP_TRACKER_ROWS.entrySet())
		{
			List<String> order = StatRegistry.fixedSections(fam.getKey());
			List<Entry<String, Long>> rows = new ArrayList<>();
			for (Entry<String, Long> e : counters.entrySet())
			{
				String key = e.getKey();
				if (e.getValue() == null || e.getValue() <= 0 || StatRegistry.hidden(key)
					|| !fam.getKey().equals(StatRegistry.family(key)) || StatRegistry.typed(key)
					|| "Destinations".equals(StatRegistry.subgroup(key))
					|| StatRegistry.label(key).startsWith("·"))
				{
					continue;
				}
				rows.add(e);
			}
			rows.sort((a, b) ->
			{
				int sa = order.indexOf(StatRegistry.subgroup(a.getKey()));
				int sb = order.indexOf(StatRegistry.subgroup(b.getKey()));
				if (sa != sb)
				{
					return Integer.compare(sa < 0 ? order.size() : sa, sb < 0 ? order.size() : sb);
				}
				return Long.compare(b.getValue(), a.getValue());
			});
			List<RecapPicture.Named> out = new ArrayList<>();
			for (Entry<String, Long> e : rows.subList(0, Math.min(fam.getValue(), rows.size())))
			{
				String key = e.getKey();
				boolean money = StatRegistry.isGp(key);
				out.add(new RecapPicture.Named(StatRegistry.label(key),
					money ? null : fmt(e.getValue()), money ? gps(e.getValue()) : null));
			}
			if (!out.isEmpty())
			{
				f.trackers.put(fam.getKey(), out);
			}
		}
	}

	private void recapFeats(RecapPicture.Facts f)
	{
		Map<String, List<String>> named = new LinkedHashMap<>();
		for (String k : new String[]{"Milestones", "Collection log", "Pets", "Personal bests",
			"Quests", "Diaries", "Combat achievements", "Deaths"})
		{
			named.put(k, new ArrayList<>());
		}
		for (JsonObject m : milestones())
		{
			if (insideWindow(safeLong(m.get("ts"))) && m.has("data"))
			{
				named.get("Milestones").add(m.getAsJsonObject("data").get("text").getAsString());
			}
		}
		Set<String> bests = new HashSet<>();
		for (JsonObject e : plugin.feedNewest(20_000))
		{
			if (!insideWindow(safeLong(e.get("ts"))))
			{
				continue;
			}
			String type = typeOf(e);
			JsonObject d = obj(e, "data");
			switch (type)
			{
				case "COMBAT_ACHIEVEMENT":
					if (!f.whole && d.has("task"))
					{
						named.get("Combat achievements").add(d.get("task").getAsString());
					}
					break;
				case "COLLECTION":
				case "QUEST":
				case "DIARY":
					if (!f.whole)
					{
						String n = feedName(e);
						if (n != null)
						{
							named.get(featOf(type)).add(n);
						}
					}
					break;
				case "PET":
					if (d.has("petName"))
					{
						named.get("Pets").add(d.get("petName").getAsString());
					}
					break;
				case "RECORD":
					if (d.has("source") && d.has("time")
						&& bests.add(low(d.get("source").getAsString())))
					{
						named.get("Personal bests").add(d.get("source").getAsString() + " "
							+ pb(d.get("time").getAsDouble()));
					}
					break;
				case "DEATH":
					if (!f.whole)
					{
						named.get("Deaths").add(d.has("killerName")
							? d.get("killerName").getAsString() : "Unknown");
					}
					break;
				default:
					break;
			}
		}
		if (!f.whole)
		{
			List<String> out = new ArrayList<>();
			for (RecapPicture.SkillLine l : f.skills)
			{
				if (l.levelStart != null && l.levelStart < l.levelEnd)
				{
					out.add(l.name + " " + l.levelEnd);
				}
			}
			if (!out.isEmpty())
			{
				f.feats.put("Levels", out);
			}
		}
		if (f.whole && plugin.clogAvailable() > 0)
		{
			f.feats.put("Collection log", Collections.singletonList(
				fmt(plugin.clogFinished()) + " of " + fmt(plugin.clogAvailable()) + " slots"));
			named.remove("Collection log");
		}
		for (Entry<String, List<String>> e : named.entrySet())
		{
			if (!e.getValue().isEmpty())
			{
				f.feats.put(e.getKey(), counted(e.getValue()));
			}
		}
	}

	private static List<String> counted(List<String> names)
	{
		Map<String, Integer> n = new LinkedHashMap<>();
		for (String s : names)
		{
			n.merge(s, 1, Integer::sum);
		}
		List<String> out = new ArrayList<>();
		for (Entry<String, Integer> e : n.entrySet())
		{
			out.add(e.getKey() + (e.getValue() > 1 ? " ×" + e.getValue() : ""));
		}
		return out;
	}

	private static String featOf(String type)
	{
		switch (type)
		{
			case "COLLECTION":
				return "Collection log";
			case "QUEST":
				return "Quests";
			case "DIARY":
				return "Diaries";
			default:
				return "Combat achievements";
		}
	}

	private void recapTiles(RecapPicture.Facts f)
	{
		long[] sat = sittingsInWindow(new LocalDate[1]);
		long minutes = sat[0];
		int sittings = (int) sat[1];
		long games = f.whole ? plugin.gamePlaytimeMinutes() : 0;
		if (games > minutes)
		{
			f.tiles.add(new RecapPicture.Tile("Played", hoursMinutes(games), "the game's own count"));
		}
		else if (sittings > 0)
		{
			f.tiles.add(new RecapPicture.Tile("Played", hoursMinutes(minutes),
				fmt(sittings) + (sittings == 1 ? " sitting" : " sittings")));
		}
		long[] xp = periodXp();
		if (xp != null && xp[0] > 0)
		{
			String most = periodXpMost();
			f.tiles.add(new RecapPicture.Tile("Experience", (f.whole ? "" : "+") + gp(xp[0]),
				most == null ? null : "most in " + most));
		}
		if (f.totalLevel != null && f.totalLevel[1] != null)
		{
			Long a = f.totalLevel[0];
			long b = f.totalLevel[1];
			if (f.whole)
			{
				f.tiles.add(new RecapPicture.Tile("Total level", fmt(b),
					f.combat != null && f.combat[1] != null ? "combat " + f.combat[1] : null));
			}
			else if (a != null && b > a)
			{
				f.tiles.add(new RecapPicture.Tile("Levels", "+" + fmt(b - a),
					fmt(a) + " to " + fmt(b) + " total"));
			}
		}
		for (RecapPicture.Named n : f.loot)
		{
			if ("Drops".equals(n.name))
			{
				f.tiles.add(new RecapPicture.Tile("Loot", n.gp, n.figure + " drops"));
			}
		}
		long bossKills = 0;
		RecapPicture.BossLine top = null;
		for (RecapPicture.BossLine b : f.bosses)
		{
			bossKills += b.gained;
			if (top == null || b.gained > top.gained)
			{
				top = b;
			}
		}
		if (bossKills > 0)
		{
			f.tiles.add(new RecapPicture.Tile("Boss kills", (f.whole ? "" : "+") + fmt(bossKills),
				"most " + top.name));
		}
		for (RecapPicture.Named n : f.slayer)
		{
			if ("Tasks".equals(n.name))
			{
				String paid = null;
				for (RecapPicture.Named m : f.slayer)
				{
					if (m.gp != null)
					{
						paid = m.gp + " on task";
					}
				}
				f.tiles.add(new RecapPicture.Tile("Slayer tasks", n.figure, paid));
			}
		}
		List<String> slots = f.feats.get("Collection log");
		if (f.whole && plugin.clogAvailable() > 0)
		{
			f.tiles.add(new RecapPicture.Tile("Collection log", fmt(plugin.clogFinished()),
				"of " + fmt(plugin.clogAvailable())));
		}
		else if (!f.whole && slots != null)
		{
			f.tiles.add(new RecapPicture.Tile("Log slots", "+" + fmt(slots.size()),
				"latest " + slots.get(0)));
		}
		List<String> pets = f.feats.get("Pets");
		if (pets != null && f.tiles.size() < 8)
		{
			f.tiles.add(new RecapPicture.Tile("Pets", (f.whole ? "" : "+") + fmt(pets.size()),
				"latest " + pets.get(0)));
		}
		while (f.tiles.size() > 8)
		{
			f.tiles.remove(f.tiles.size() - 1);
		}
	}

	private long[] sittingsInWindow(LocalDate[] busiest)
	{
		long[] sat = new long[3];
		if (sessionPeriod())
		{
			sat[0] = plugin.sessionElapsedMinutes();
			sat[1] = sat[0] > 0 ? 1 : 0;
		}
		else
		{
			for (Entry<LocalDate, long[]> d : daysPlayed().entrySet())
			{
				if (!insideWindow(noon(d.getKey())))
				{
					continue;
				}
				sat[0] += d.getValue()[0];
				sat[1] += d.getValue()[1];
				if (d.getValue()[0] > sat[2])
				{
					sat[2] = d.getValue()[0];
					busiest[0] = d.getKey();
				}
			}
		}
		return sat;
	}

	private JPanel recapPlate()
	{
		JPanel plate = card(wholeRecord() ? "The whole record" : window().label);
		int held = plate.getComponentCount();

		LocalDate[] busiest = new LocalDate[1];
		long[] sat = sittingsInWindow(busiest);
		long minutes = sat[0];
		int sittings = (int) sat[1];
		if (sittings > 0)
		{
			plateRow(plate, "Played", hoursMinutes(minutes) + " · " + fmt(sittings)
				+ (sittings == 1 ? " sitting" : " sittings"), () ->
			{
				journalLens = "Sessions";
				applyTab(View.JOURNAL);
			});
		}
		if (busiest[0] != null && sittings > 1)
		{
			final LocalDate day = busiest[0];
			plateRow(plate, "Busiest day", TASK_DAY.format(day.atStartOfDay(
				ZoneId.systemDefault()).toInstant()) + " · " + hoursMinutes(sat[2]),
				() -> openJournalOn(noon(day)));
		}

		long[] xp = periodXp();
		if (xp != null && xp[0] > 0)
		{
			String most = periodXpMost();
			plateRow(plate, wholeRecord() ? "Xp" : "Xp gained",
				(wholeRecord() ? "" : "+") + gp(xp[0]) + " xp"
					+ (most != null ? ", most in " + most : ""),
				() -> applyTab(View.SHEET));
		}
		long[] levels = periodLevels();
		if (levels != null)
		{
			plateRow(plate, wholeRecord() ? "Total level" : "Levels",
				wholeRecord() ? fmt(levels[0]) : "+" + fmt(levels[0]) + " · " + fmt(levels[1]) + " now",
				() -> applyTab(View.SHEET));
		}

		long[] loot = periodLoot();
		if (loot[0] > 0)
		{
			plateRow(plate, "Drops", fmt(loot[0]) + " · " + gps(loot[1]),
				() -> applyTab(View.DROPS));
		}
		String[] dearest = periodDearest();
		if (dearest != null)
		{
			final String item = dearest[0];
			Line said = new Line();
			said.name(item);
			said.fixed(" · " + gps(Long.parseLong(dearest[1])));
			plateRow(plate, "Dearest drop", said, () -> openItem(item));
		}
		if (loot[2] > 0)
		{
			plateRow(plate, "Left behind", fmt(loot[2]) + " · " + gps(loot[3]), () ->
			{
				dropsLeftBehind = true;
				applyTab(View.DROPS);
			});
		}

		Map<String, Long> counters = wholeRecord() ? withLedgerSpend(counters()) : periodCounters();
		Runnable toLiving = () ->
		{
			statsFamily = "Living";
			subByTab.put(Tab.RECORD, "Ledger");
			applyCommon();
		};
		long food = counters.getOrDefault("foodEaten", 0L);
		if (food > 0)
		{
			plateRow(plate, "Food", fmt(food)
				+ splitSpend("foodConsumedValue", counters), toLiving);
		}
		long doses = counters.getOrDefault("potionDoses", 0L);
		if (doses > 0)
		{
			plateRow(plate, "Potions", count(doses, "dose")
				+ splitSpend("potionsConsumedValue", counters), toLiving);
		}

		String[] killed = periodKilledMost();
		if (killed != null)
		{
			final String who = killed[0];
			Line said = new Line();
			said.name(who);
			said.fixed(" · " + killed[1]);
			plateRow(plate, "Killed most", said, () -> openSourceLoose(who));
		}
		long[] ms = windowMs();
		long[] tally = plugin.onTaskTally(ms[0], ms[1], null, wholeRecord());
		if (tally[2] > 0)
		{
			long paid = tallyOf(plugin.onTaskLoot(ms[0], ms[1], null, wholeRecord()))[1];
			plateRow(plate, "Tasks", fmt(tally[2]) + tail(paid), () ->
			{
				slayerLens = "Tasks";
				applyTab(View.SLAYER);
			});
		}

		Map<String, long[]> lines = new LinkedHashMap<>();
		Map<String, String> firstNamed = new LinkedHashMap<>();
		for (JsonObject e : plugin.feedNewest(FEED_SCAN_DEEP))
		{
			String type = typeOf(e);
			if (!insideWindow(safeLong(e.get("ts"))))
			{
				continue;
			}
			lines.computeIfAbsent(type, k -> new long[1])[0]++;
			String name = feedName(e);
			if (name != null)
			{
				firstNamed.putIfAbsent(type, name);
			}
		}
		feedPlateRow(plate, lines, firstNamed, "COLLECTION", "Log slot", "Log slots", "Log");
		feedPlateRow(plate, lines, firstNamed, "PET", "Pet", "Pets", "Feats");
		feedPlateRow(plate, lines, firstNamed, "QUEST", "Quest", "Quests", "Feats");
		feedPlateRow(plate, lines, firstNamed, "DIARY", "Diary", "Diaries", "Feats");
		feedPlateRow(plate, lines, firstNamed, "COMBAT_ACHIEVEMENT", "Combat achievement",
			"Combat achievements", "Feats");
		feedPlateRow(plate, lines, firstNamed, "DEATH", "Death", "Deaths", "Deaths");

		if (plate.getComponentCount() == held)
		{
			plate.add(note(wholeRecord() ? "Nothing on the record yet."
				: "Nothing inside " + periodInSentence() + "."));
		}
		return plate;
	}

	private String splitSpend(String key, Map<String, Long> counters)
	{
		Span s = span();
		if (!wholeRecord() && !sessionPeriod() && (s == null || !s.opening.counters.containsKey(key)))
		{
			return "";
		}
		long spend = counters.getOrDefault(key, 0L);
		return tail(spend);
	}

	private void plateRow(JPanel plate, String left, String right, Runnable go)
	{
		JPanel r = row(left, right);
		link(r, go);
		plate.add(r);
	}

	private void plateRow(JPanel plate, String left, Line right, Runnable go)
	{
		String whole = right.whole();
		String fitted = fitLine(right, right.names, NAME_FLOOR, rowMetrics(),
			chaseRoom(left, rowMetrics()));
		plateRow(plate, left, fitted != null ? fitted : whole, go);
		if (fitted != null && !fitted.equals(whole))
		{
			((JPanel) plate.getComponent(plate.getComponentCount() - 1))
				.setToolTipText(left + ": " + whole);
		}
	}

	private void feedPlateRow(JPanel plate, Map<String, long[]> lines, Map<String, String> named,
		String type, String one, String many, String lens)
	{
		long[] n = lines.get(type);
		if (n == null || n[0] == 0)
		{
			return;
		}
		String name = named.get(type);
		Line right = new Line();
		right.fixed(fmt(n[0]));
		if (name != null)
		{
			right.fixed(" · ");
			right.name(name);
		}
		plateRow(plate, n[0] == 1 ? one : many, right, () ->
		{
			journalLens = lens;
			applyTab(View.JOURNAL);
		});
	}

	private long[] periodXp()
	{
		if (wholeRecord())
		{
			long[] overall = plugin.skillSheet().get("overall");
			return overall != null && overall.length > 1 ? new long[]{overall[1]} : null;
		}
		if (sessionPeriod())
		{
			long xp = 0;
			for (ExperienceStatTracker.SkillGain g : plugin.sessionSkillXp())
			{
				xp += Math.max(0, g.xp);
			}
			return new long[]{xp};
		}
		Map<String, Long> gains = periodSkillGains();
		if (gains == null)
		{
			return null;
		}
		long total = 0;
		for (long g : gains.values())
		{
			total += Math.max(0, g);
		}
		return new long[]{total};
	}

	private String periodXpMost()
	{
		Map<String, Long> by = new LinkedHashMap<>();
		if (sessionPeriod())
		{
			ExperienceStatTracker.SkillGain top = null;
			for (ExperienceStatTracker.SkillGain g : plugin.sessionSkillXp())
			{
				if (g.xp > 0 && (top == null || g.xp > top.xp))
				{
					top = g;
				}
			}
			return top == null ? null : prettify(
				low(top.skill.name()));
		}
		if (wholeRecord())
		{
			for (Entry<String, long[]> e : plugin.skillSheet().entrySet())
			{
				if (!"overall".equals(e.getKey()) && e.getValue().length > 1)
				{
					by.put(e.getKey(), e.getValue()[1]);
				}
			}
		}
		else
		{
			Map<String, Long> gains = periodSkillGains();
			if (gains == null)
			{
				return null;
			}
			by.putAll(gains);
		}
		String top = topOf(by);
		return top == null ? null : prettify(top);
	}

	private static String topOf(Map<String, Long> by)
	{
		String top = null;
		long most = 0;
		for (Entry<String, Long> e : by.entrySet())
		{
			if (e.getValue() > most)
			{
				most = e.getValue();
				top = e.getKey();
			}
		}
		return top;
	}

	private long[] periodLevels()
	{
		if (wholeRecord())
		{
			long[] overall = plugin.skillSheet().get("overall");
			return overall != null && overall[0] > 0 ? new long[]{overall[0], overall[0]} : null;
		}
		if (sessionPeriod())
		{
			long[] overall = plugin.skillSheet().get("overall");
			long gained = 0;
			for (JsonObject e : plugin.feedNewest(FEED_SCAN_DEEP))
			{
				if ("LEVEL".equals(typeOf(e)) && insideWindow(safeLong(e.get("ts"))))
				{
					gained++;
				}
			}
			return gained > 0 && overall != null ? new long[]{gained, overall[0]} : null;
		}
		Span s = span();
		if (s == null || !s.opening.complete || !s.closing.complete)
		{
			return null;
		}
		List<String> keys = new ArrayList<>();
		for (Skill sk : Skill.values())
		{
			if (sk != Skill.OVERALL)
			{
				keys.add(low(sk.name()));
			}
		}
		Baseline shut = new Baseline();
		shut.skills.putAll(closingSkills(s.closing.skills, periodReachesToday()));
		shut.complete = true;
		HistoryLog.Levels was = HistoryLog.levels(s.opening, keys);
		HistoryLog.Levels now = HistoryLog.levels(shut, keys);
		return now.total > was.total ? new long[]{now.total - was.total, now.total} : null;
	}

	private Map<String, Long> periodSkillGains()
	{
		Span s = span();
		if (s == null)
		{
			return null;
		}
		Map<String, Long> out = new LinkedHashMap<>(HistoryLog.gained(s.opening.skills,
			s.earliest.skills, closingSkills(s.closing.skills, periodReachesToday()), s.opening.complete));
		out.remove("overall");
		return out;
	}

	private Map<String, Long> closingSkills(Map<String, Long> skills, boolean live)
	{
		Map<String, Long> close = new HashMap<>(skills);
		if (live)
		{
			for (Entry<String, long[]> e : plugin.skillSheet().entrySet())
			{
				if (e.getValue() != null && e.getValue().length > 1 && e.getValue()[1] > 0)
				{
					close.merge(e.getKey(), e.getValue()[1], Math::max);
				}
			}
		}
		return close;
	}

	private long[] periodLoot()
	{
		if (wholeRecord())
		{
			long[] out = new long[4];
			for (SourceRow r : sources())
			{
				out[0] += r.loots;
				out[1] += r.value;
			}
			for (UntakenRow u : plugin.untakenSources())
			{
				out[2] += u.qty;
				out[3] += u.value;
			}
			return out;
		}
		LocalStore.LootWindow win = lootWindow();
		return new long[]{win.loots, win.value, win.left, win.leftValue};
	}

	private String[] periodDearest()
	{
		if (wholeRecord())
		{
			return null;
		}
		LocalStore.LootWindow win = lootWindow();
		return win.items.isEmpty() ? null : new String[]{win.items.get(0)[0], win.items.get(0)[2]};
	}

	private String[] periodKilledMost()
	{
		if (wholeRecord())
		{
			SourceRow top = null;
			for (SourceRow r : sources())
			{
				if (isKillSource(r.name) && (top == null || standingKills(r) > standingKills(top)))
				{
					top = r;
				}
			}
			return top == null ? null : new String[]{top.name, fmt(standingKills(top))};
		}
		if (sessionPeriod())
		{
			String top = null;
			long most = 0;
			for (String[] s : plugin.sessionLootWindow().sources)
			{
				long n = safeParse(s[1]);
				if (n > most)
				{
					most = n;
					top = s[0];
				}
			}
			return top == null ? null : new String[]{top, fmt(most)};
		}
		Span s = span();
		if (s == null)
		{
			return null;
		}
		Map<String, Long> moved = HistoryLog.gained(s.opening.kcs, s.earliest.kcs,
			closingNow(s.closing.kcs, plugin.killCounts()));
		String top = topOf(moved);
		return top == null ? null : new String[]{top, fmt(moved.get(top))};
	}

	private JPanel buildRecords()
	{
		JPanel p = backPage();
		JPanel book = card("Records");
		int held = book.getComponentCount();

		long[][] best = {{0, 0}, {0, 0}};
		for (JsonObject e : plugin.feedNewest(FEED_SCAN_DEEP))
		{
			if ("SESSION".equals(typeOf(e)))
			{
				rank(best, sessionMinutes(e), sittingStart(e));
			}
		}
		if (best[0][0] > 0)
		{
			recordRow(book, "Longest sitting", hoursMinutes(best[0][0]), best[0][1],
				best[1][0] > 0 ? "Was " + hoursMinutes(best[1][0]) + " · " + dated(best[1][1]) : null);
		}

		TreeMap<LocalDate, Baseline> spine = historySpine;
		if (spine != null && spine.size() > 1)
		{
			long[][] bigXp = {{0, 0}, {0, 0}};
			String bigSkill = null;
			long[][] bigKills = {{0, 0}, {0, 0}};
			int run = 0;
			int longest = 0;
			LocalDate runEnd = null;
			LocalDate prevDay = null;
			Entry<LocalDate, Baseline> before = null;
			for (Entry<LocalDate, Baseline> day : spine.entrySet())
			{
				run = prevDay != null && prevDay.plusDays(1).equals(day.getKey()) ? run + 1 : 1;
				if (run > longest)
				{
					longest = run;
					runEnd = day.getKey();
				}
				prevDay = day.getKey();
				if (before != null)
				{
					long ts = noon(day.getKey());
					Object[] xp = dayXp(day.getKey());
					if (rank(bigXp, xp == null ? 0 : (Long) xp[0], ts))
					{
						bigSkill = (String) xp[1];
					}
					long kills = 0;
					for (Entry<String, Long> k : day.getValue().kcs.entrySet())
					{
						Long was = before.getValue().kcs.get(k.getKey());
						if (was != null && k.getValue() > was)
						{
							kills += k.getValue() - was;
						}
					}
					rank(bigKills, kills, ts);
				}
				before = day;
			}
			if (bigXp[0][0] > 0)
			{
				recordRow(book, "Biggest day", "+" + gp(bigXp[0][0]) + " xp", bigXp[0][1],
					(bigSkill != null ? "Most in " + bigSkill : "")
						+ (bigXp[1][0] > 0 ? (bigSkill != null ? " · " : "") + "was +" + gp(bigXp[1][0])
						+ " · " + dated(bigXp[1][1]) : ""));
			}
			if (bigKills[0][0] > 0)
			{
				recordRow(book, "Most kills in a day", fmt(bigKills[0][0]), bigKills[0][1],
					bigKills[1][0] > 0 ? "Was " + fmt(bigKills[1][0]) + " · " + dated(bigKills[1][1]) : null);
			}
			if (longest > 1 && runEnd != null)
			{
				recordRow(book, "Longest run of days written",
					fmt(longest) + " days", noon(runEnd), null);
			}
		}

		long[][] rich = {{0, 0}, {0, 0}};
		long[][] busy = {{0, 0}, {0, 0}};
		for (Entry<String, long[]> d : dayTotals().entrySet())
		{
			long ts;
			try
			{
				ts = noon(LocalDate.parse(d.getKey(), ROLL_DAY));
			}
			catch (RuntimeException e)
			{
				continue;
			}
			long[] t = d.getValue();
			rank(rich, t[1], ts, t[0]);
			rank(busy, t[0], ts, t[1]);
		}
		if (rich[0][0] > 0)
		{
			recordRow(book, "Richest day", gps(rich[0][0]), rich[0][1],
				count(rich[0][2], "drop") + (rich[1][0] > 0 ? " · was " + gp(rich[1][0]) + " · "
					+ dated(rich[1][1]) : ""));
		}
		if (busy[0][0] > 0)
		{
			recordRow(book, "Most drops in a day", fmt(busy[0][0]), busy[0][1],
				gps(busy[0][2]) + (busy[1][0] > 0 ? " · was " + fmt(busy[1][0]) + " · "
					+ dated(busy[1][1]) : ""));
		}

		long hit = counters().getOrDefault("highestHit", 0L);
		if (hit > 0)
		{
			book.add(row("Highest hit", fmt(hit)));
		}
		if (book.getComponentCount() == held)
		{
			book.add(note("No record set yet."));
		}
		p.add(book);
		return p;
	}

	private static String dated(long ts)
	{
		return FULL_DAY.format(Instant.ofEpochMilli(ts));
	}

	private static String day(long ms)
	{
		return TASK_DAY.format(Instant.ofEpochMilli(ms));
	}

	private void recordRow(JPanel book, String left, String figure, long ts, String hover)
	{
		JPanel r = row(left, figure + " · " + dated(ts));
		if (hover != null && !hover.isEmpty())
		{
			r.setToolTipText(hover);
		}
		book.add(r);
	}

	private static boolean rank(long[][] top, long... e)
	{
		if (e[0] > top[0][0])
		{
			top[1] = top[0];
			top[0] = e;
			return true;
		}
		if (e[0] > top[1][0])
		{
			top[1] = e;
		}
		return false;
	}

	private JPanel buildCalendar()
	{
		JPanel p = backPage();
		JPanel head = stepStrip();
		JLabel title = styled(new JLabel(MONTH_YEAR.format(calendarMonth.atDay(1)
			.atStartOfDay(ZoneId.systemDefault()).toInstant()).toUpperCase(Locale.ROOT), JLabel.CENTER),
			FontManager.getRunescapeFont(), accent());
		arrows(head, () ->
		{
			calendarMonth = calendarMonth.minusMonths(1);
			rebuildInPlace();
		}, calendarMonth.isBefore(YearMonth.now()), () ->
		{
			calendarMonth = calendarMonth.plusMonths(1);
			rebuildInPlace();
		}, title);
		spaced(p, head, 4);

		JPanel grid = new JPanel(new GridLayout(0, 7, 2, 2));
		grid.setBackground(DARK);
		grid.setAlignmentX(Component.LEFT_ALIGNMENT);
		for (String d : new String[]{"M", "T", "W", "T", "F", "S", "S"})
		{
			JLabel l = styled(new JLabel(d, JLabel.CENTER), small(), dim());
			grid.add(l);
		}
		Map<LocalDate, long[]> played = daysPlayed();
		TreeMap<LocalDate, Baseline> spine = historySpine;
		long most = 1;
		for (int d = 1; d <= calendarMonth.lengthOfMonth(); d++)
		{
			long[] t = played.get(calendarMonth.atDay(d));
			most = Math.max(most, t == null ? 0 : t[0]);
		}
		int lead = calendarMonth.atDay(1).getDayOfWeek().getValue() - 1;
		for (int i = 0; i < lead; i++)
		{
			grid.add(blankCell());
		}
		long monthMinutes = 0;
		int written = 0;
		LocalDate today = LocalDate.now();
		for (int d = 1; d <= calendarMonth.lengthOfMonth(); d++)
		{
			LocalDate day = calendarMonth.atDay(d);
			long[] t = played.get(day);
			boolean onSpine = spine != null && spine.containsKey(day);
			boolean future = day.isAfter(today);
			JPanel cell = new JPanel(new BorderLayout());
			cell.setPreferredSize(new Dimension(26, 24));
			JLabel n = new JLabel(String.valueOf(d), JLabel.CENTER);
			n.setFont(small());
			if (t != null && t[0] > 0)
			{
				monthMinutes += t[0];
				written++;
				float weight = 0.25f + 0.75f * Math.min(1f, (float) t[0] / most);
				cell.setBackground(wash(accent(), weight));
				n.setForeground(Color.WHITE);
				cell.setToolTipText(hoursMinutes(t[0]) + " · " + t[1] + (t[1] == 1 ? " sitting" : " sittings"));
			}
			else if (onSpine || (t != null && t[1] > 0))
			{
				written++;
				cell.setBackground(wash(accent(), 0.18f));
				n.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
				cell.setToolTipText("Written");
			}
			else
			{
				cell.setBackground(DARKER);
				n.setForeground(future ? DARK.brighter()
					: dim());
			}
			if (!future && (onSpine || t != null))
			{
				final long ts = noon(day);
				link(cell, () -> openJournalOn(ts));
			}
			cell.add(n, BorderLayout.CENTER);
			grid.add(cell);
		}
		while (grid.getComponentCount() % 7 != 0)
		{
			grid.add(blankCell());
		}
		spaced(p, grid, 4);
		p.add(ghostRow(written == 0 ? "nothing written this month"
			: fmt(written) + (written == 1 ? " day written" : " days written")
			+ (monthMinutes > 0 ? " · " + hoursMinutes(monthMinutes) : ""), ""));
		return p;
	}

	private static JPanel blankCell()
	{
		JPanel c = new JPanel();
		c.setBackground(DARK);
		c.setPreferredSize(new Dimension(26, 24));
		return c;
	}

	private static Color wash(Color c, float weight)
	{
		Color base = DARKER;
		return new Color(
			Math.round(base.getRed() + (c.getRed() - base.getRed()) * weight),
			Math.round(base.getGreen() + (c.getGreen() - base.getGreen()) * weight),
			Math.round(base.getBlue() + (c.getBlue() - base.getBlue()) * weight));
	}

	private JPanel buildJournal()
	{
		JPanel p = column();
		addFrontispiece(p);

		JPanel lenses = new JPanel(new GridLayout(0, 3, 3, 3));
		lenses.setBackground(DARK);
		for (String[] lens : JOURNAL_LENSES)
		{
			lenses.add(pill(lens[0], lens[0].equals(journalLens), 4, null, () ->
			{
				journalLens = lens[0];
				journalShown = 60;
				rebuild();
			}));
		}
		spaced(p, lenses);

		Set<String> wanted = new HashSet<>();
		for (String[] lens : JOURNAL_LENSES)
		{
			if (lens[0].equals(journalLens))
			{
				wanted.addAll(Arrays.asList(lens).subList(1, lens.length));
			}
		}
		List<JsonObject> all = plugin.feedWithSitting(4000);
		if (all.size() >= 4000 && !wholeRecord() && windowMs()[0] < oldestTs(all, false))
		{
			all = plugin.feedWithSitting(JOURNAL_DEEP);
		}
		all = withMilestones(all);
		List<JsonObject> feed = new ArrayList<>();
		for (JsonObject e : all)
		{
			boolean kind = wanted.isEmpty() || wanted.contains(typeOf(e));
			if (kind && insideWindow(filedAt(e)))
			{
				feed.add(e);
			}
		}
		feed.sort((a, b) -> dayOf(filedAt(b)).compareTo(dayOf(filedAt(a))));
		if (feed.isEmpty() && !wholeRecord())
		{
			p.add(nothingInWindow("All".equals(journalLens)
				? "milestones" : low(journalLens)));
			return p;
		}
		if (feed.isEmpty())
		{
			return noted(p, "All".equals(journalLens)
				? "Milestones (pets, log slots, tasks, quests, deaths) are noted "
					+ "here as they happen."
				: "Nothing of that kind on the record yet.");
		}

		String lastDay = null;
		for (JsonObject e : firstN(feed, journalShown))
		{
			long ts = filedAt(e);
			String day = ts > 0 ? DAY.format(Instant.ofEpochMilli(ts)) : "";
			if (!day.equals(lastDay))
			{
				lastDay = day;
				JLabel g = styled(new JLabel(day.toUpperCase(Locale.ROOT)), small(), accent());
				g.setAlignmentX(Component.LEFT_ALIGNMENT);
				g.setBorder(pad(7, 2, 3, 0));
				p.add(g);
				String entry = dayEntry(dayOf(ts));
				if (entry != null)
				{
					for (String line : wrapClauses(entry, boardRowRoom()))
					{
						p.add(ghostRow(line, ""));
					}
				}
			}
			if ("SESSION".equals(typeOf(e)))
			{
				String[] parts = sessionParts(e);
				JPanel sr = row(parts[0], parts[1]);
				sr.setToolTipText(parts[2]);
				p.add(sr);
			}
			else
			{
				p.add(row(feedLine(e), ""));
			}
		}
		if (feed.size() > journalShown)
		{
			p.add(vgap(6));
			p.add(moreRow("Read further back", () ->
			{
				journalShown += 60;
				rebuildInPlace();
			}));
		}
		return p;
	}

	private void addFrontispiece(JPanel p)
	{
		String rsn = plugin.displayRsn();
		JPanel plate = card(rsn != null && !rsn.isEmpty()
			? "The journal of " + rsn : "The journal");

		long since = plugin.keptSince();
		TreeMap<LocalDate, Baseline> spine = historySpine;
		if (since > 0)
		{
			plate.add(row("Kept since",
				day(since), accent()));
		}
		if (spine != null && !spine.isEmpty())
		{
			JPanel days = row("Days written", fmt(spine.size()));
			days.setToolTipText("The days, as a calendar");
			link(days, this::openCalendar);
			plate.add(days);
		}
		Map<String, long[]> sheet = plugin.skillSheet();
		long[] overall = sheet.get("overall");
		int combat = plugin.combatLevel();
		if (overall != null && overall[0] > 0)
		{
			plate.add(row("Total level", fmt(overall[0])
				+ (combat > 0 ? " · combat " + combat : "")));
		}
		int[] logStanding = clogStanding();
		int fin = plugin.clogFinished();
		if (logStanding != null)
		{
			plate.add(row("Collection log",
				fmt(logStanding[0]) + " / " + fmt(logStanding[1])));
		}
		else if (fin > 0)
		{
			plate.add(row("Collection log", fmt(fin) + " obtained"));
		}
		List<JsonObject> marks = milestones();
		if (!marks.isEmpty())
		{
			JsonObject last = marks.get(0);
			plate.add(row("Last milestone", str(last.getAsJsonObject("data"), "text", "")
				+ " · " + day(safeLong(last.get("ts")))));
		}
		plate.add(moreRow("what the journal holds", this::openInfo));
		p.add(plate);

		String note = frontispieceNote();
		if (note != null)
		{
			p.add(ghostRow(note, ""));
		}
		p.add(vgap(6));
	}

	private String frontispieceNote()
	{
		if (grindsCache != null)
		{
			for (GrindBook.GrindRow g : grindsCache)
			{
				if (g.percentileDry >= 90)
				{
					return "still owed a " + low(g.item)
						+ " at " + fmt(g.kc) + " " + low(g.boss);
				}
			}
		}
		List<JsonObject> recent = plugin.feedNewest(2);
		if (recent.size() == 2)
		{
			long a = recent.get(0).has("ts") ? recent.get(0).get("ts").getAsLong() : 0;
			long b = recent.get(1).has("ts") ? recent.get(1).get("ts").getAsLong() : 0;
			long days = (a - b) / 86_400_000L;
			if (days >= 30)
			{
				return "resumed after " + days + " days away";
			}
		}
		return null;
	}

	void promptImport()
	{
		JFileChooser fc = new JFileChooser();
		fc.setDialogTitle("Import a Chronicle journal");
		fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
			"Chronicle journal (*.json)", "json"));
		if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
		{
			plugin.actionImport(fc.getSelectedFile());
		}
	}

	private String nearestName(String ql)
	{
		if (ql.length() < 4)
		{
			return null;
		}
		List<String> names = new ArrayList<>();
		for (SourceRow r : sources())
		{
			names.add(r.name);
			for (BagItem b : plugin.sourceItems(r.name))
			{
				names.add(b.name);
			}
		}
		for (Map<String, List<String>> tab : taxonomy(plugin.gson()).values())
		{
			names.addAll(tab.keySet());
			for (List<String> slots : tab.values())
			{
				names.addAll(slots);
			}
		}
		for (Boss b : bossRoster(plugin.gson()))
		{
			names.add(b.name);
		}
		JsonObject cl = clogNow();
		names.addAll(obj(cl, "slayer_kcs").keySet());
		names.addAll(obj(achievements(), "quests").keySet());
		for (Skill sk : skillOrder())
		{
			names.add(prettify(low(sk.name())));
		}
		names.addAll(Arrays.asList("Quests", "Collection log", "Achievement diaries",
			"Combat achievements", "Clues", "Records", "Calendar", "Recap", "All trackers",
			"Kill log", "Left behind"));
		String best = null;
		int nearest = 3;
		for (String name : names)
		{
			String low = low(name);
			int d = editsBetween(ql, low, nearest);
			for (String word : NEAR_WORDS.split(low))
			{
				if (word.length() >= 4)
				{
					d = Math.min(d, editsBetween(ql, word, nearest));
				}
			}
			if (d < nearest)
			{
				nearest = d;
				best = name;
			}
		}
		return best;
	}

	private static final Pattern NEAR_WORDS = Pattern.compile("[^a-z0-9']+");

	private static int editsBetween(String a, String b, int cap)
	{
		if (Math.abs(a.length() - b.length()) >= cap)
		{
			return cap;
		}
		int[] prev = new int[b.length() + 1];
		int[] cur = new int[b.length() + 1];
		for (int j = 0; j <= b.length(); j++)
		{
			prev[j] = j;
		}
		for (int i = 1; i <= a.length(); i++)
		{
			cur[0] = i;
			int rowMin = i;
			for (int j = 1; j <= b.length(); j++)
			{
				int swap = prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
				cur[j] = Math.min(swap, Math.min(prev[j] + 1, cur[j - 1] + 1));
				rowMin = Math.min(rowMin, cur[j]);
			}
			if (rowMin >= cap)
			{
				return cap;
			}
			int[] t = prev;
			prev = cur;
			cur = t;
		}
		return Math.min(cap, prev[b.length()]);
	}

	private static final class Hit
	{
		final String name;
		final String figure;
		final Color color;
		final String tip;
		final Runnable go;
		final int score;
		final long weight;

		Hit(String name, String figure, Color color, String tip, Runnable go, int score, long weight)
		{
			this.name = name;
			this.figure = figure == null ? "" : figure;
			this.color = color;
			this.tip = tip;
			this.go = go;
			this.score = score;
			this.weight = weight;
		}
	}

	private static final int SEARCH_CAP = 4;

	private static final Map<String, String[]> SKILL_ALIASES = new LinkedHashMap<>();

	static
	{
		SKILL_ALIASES.put("runecraft", new String[]{"runecrafting", "rc"});
		SKILL_ALIASES.put("hitpoints", new String[]{"hp"});
		SKILL_ALIASES.put("woodcutting", new String[]{"wc"});
		SKILL_ALIASES.put("firemaking", new String[]{"fm"});
		SKILL_ALIASES.put("construction", new String[]{"con"});
	}

	static int matchScore(String ql, String name)
	{
		if (name == null || ql.isEmpty())
		{
			return -1;
		}
		String raw = low(name);
		if (raw.equals(ql) || (ql.endsWith("s") && raw.equals(ql.substring(0, ql.length() - 1)))
			|| (raw.endsWith("s") && raw.substring(0, raw.length() - 1).equals(ql)))
		{
			return 0;
		}
		String n = raw.replace("'", "");
		String q = ql.replace("'", "");
		if (q.trim().isEmpty())
		{
			return -1;
		}
		if (n.equals(q))
		{
			return 0;
		}
		if (n.startsWith(q))
		{
			return 1;
		}
		String[] words = NAME_WORDS.split(n);
		boolean every = true;
		for (String w : QUERY_WORDS.split(q))
		{
			if (w.isEmpty())
			{
				continue;
			}
			boolean begins = false;
			for (String x : words)
			{
				begins |= x.startsWith(w);
			}
			every &= begins;
		}
		if (every || initials(q, words))
		{
			return 2;
		}
		return n.contains(q) ? 3 : -1;
	}

	private static final Pattern NAME_WORDS = Pattern.compile("[^a-z0-9]+");
	private static final Pattern QUERY_WORDS = Pattern.compile("\\s+");

	private static boolean initials(String q, String[] words)
	{
		if (q.length() < 2 || q.indexOf(' ') >= 0 || words.length < 2)
		{
			return false;
		}
		StringBuilder all = new StringBuilder();
		StringBuilder lean = new StringBuilder();
		for (String w : words)
		{
			if (w.isEmpty())
			{
				continue;
			}
			all.append(w.charAt(0));
			if (!w.equals("the") && !w.equals("of"))
			{
				lean.append(w.charAt(0));
			}
		}
		return q.contentEquals(all) || q.contentEquals(lean);
	}

	private static void goTo(List<Hit> go, String ql, String name, String figure, Runnable to,
		long weight, String... also)
	{
		int sc = matchScore(ql, name);
		sc = sc > 2 ? -1 : sc;
		for (String a : also)
		{
			if (a.equals(ql))
			{
				sc = 0;
			}
		}
		if (sc >= 0)
		{
			go.add(new Hit(name, figure, null, null, to, sc, weight));
		}
	}

	private static int bestScore(String ql, String... names)
	{
		int best = -1;
		for (String n : names)
		{
			int s = matchScore(ql, n);
			if (s >= 0 && (best < 0 || s < best))
			{
				best = s;
			}
		}
		return best;
	}

	private int searchGroup(JPanel p, String title, List<Hit> hits)
	{
		if (hits.isEmpty())
		{
			return 0;
		}
		hits.sort((a, b) -> a.score != b.score ? Integer.compare(a.score, b.score)
			: Long.compare(b.weight, a.weight));
		p.add(group(title));
		String key = "search:" + title;
		int cap = drillShown.getOrDefault(key, SEARCH_CAP);
		FontMetrics fm = rowMetrics();
		for (int i = 0; i < hits.size(); i++)
		{
			if (i >= cap)
			{
				p.add(expander(key, cap, hits.size()));
				break;
			}
			Hit h = hits.get(i);
			int room = boardRowRoom() - ROW_GAP - (h.figure.isEmpty() ? 0 : fm.stringWidth(h.figure));
			String shown = h.name;
			for (int keep = shown.length() - 1; fm.stringWidth(shown) > room && keep >= NAME_FLOOR; keep--)
			{
				shown = stub(h.name, keep);
			}
			JPanel r = row(shown, h.figure, null, false);
			if (h.color != null)
			{
				part(r, BorderLayout.CENTER)
					.setForeground(h.color);
			}
			String tip = h.tip;
			if (!shown.equals(h.name))
			{
				tip = tip == null ? h.name : h.name + ": " + tip;
			}
			if (tip != null)
			{
				r.setToolTipText(wrappedTip(tip));
			}
			door(r, h.go);
			p.add(r);
		}
		return hits.size();
	}

	static String wrappedTip(String text)
	{
		if (text == null || text.length() <= 60 || text.startsWith("<html>"))
		{
			return text;
		}
		String safe = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
		return "<html><body style='width:220px'>" + safe + "</body></html>";
	}

	private void openSheetPage(String page)
	{
		applyTab(Tab.STANDING);
		sheetPage = page;
		rebuild();
	}

	private static final int JOURNAL_DEEP = 20_000;

	private void fetchJourneyForSearch()
	{
		if (journeyFetching)
		{
			return;
		}
		journeyFetching = true;
		plugin.fetchSlayerJourney(j -> SwingUtilities.invokeLater(() ->
		{
			journeyFetching = false;
			if (j == null)
			{
				return;
			}
			boolean moved = journeyMoved(journeyCache != null ? journeyCache : historyJourney, j);
			journeyCache = j;
			if (moved && !searchQuery().isEmpty())
			{
				rebuildInPlace();
			}
		}));
	}

	private List<Object[]> searchFeed;
	private long searchFeedTs = -1;
	private Object searchFeedSpine;

	private List<Object[]> searchFeed()
	{
		long newest = newestTs(plugin.feedNewest(1));
		if (searchFeed == null || newest != searchFeedTs || historySpine != searchFeedSpine)
		{
			List<Object[]> out = new ArrayList<>();
			List<JsonObject> all = new ArrayList<>(plugin.feedNewest(JOURNAL_DEEP));
			all.addAll(milestones());
			for (JsonObject e : all)
			{
				String line = feedLine(e);
				if (line != null && !line.isEmpty())
				{
					out.add(new Object[]{low(line).replace("'", ""), line, filedAt(e)});
				}
			}
			searchFeed = out;
			searchFeedTs = newest;
			searchFeedSpine = historySpine;
		}
		return searchFeed;
	}

	private JPanel buildSearch(String q)
	{
		JPanel p = column();
		String ql = low(q);
		int total = 0;
		searchFirst = null;

		List<Hit> go = new ArrayList<>();
		Map<String, long[]> sheet = plugin.skillSheet();
		for (Skill sk : skillOrder())
		{
			String key = low(sk.name());
			String name = prettify(key);
			long[] cur = sheet.get(key);
			Runnable to = sk == Skill.SLAYER ? () ->
			{
				slayerLens = "Tasks";
				applyTab(View.SLAYER);
			} : () -> openSkill(name);
			goTo(go, ql, name, cur != null && cur[0] > 0 ? "level " + cur[0] : "", to, 2,
				SKILL_ALIASES.getOrDefault(key, new String[0]));
		}
		JsonObject quests = obj(achievements(), "quests");
		long questsDone = 0;
		for (String name : quests.keySet())
		{
			questsDone += "FINISHED".equals(quests.get(name).getAsString()) ? 1 : 0;
		}
		goTo(go, ql, "Quests", quests.size() > 0 ? fmt(questsDone) + " / " + fmt(quests.size()) : "",
			() -> openSheetPage("quests"), 1, "quest");
		goTo(go, ql, "Collection log", plugin.clogAvailable() > 0
			? fmt(plugin.clogFinished()) + " / " + fmt(plugin.clogAvailable()) : "",
			() -> openSheetPage("log"), 1, "clog", "log");
		goTo(go, ql, "Achievement diaries", "", () -> openSheetPage("diaries"), 1,
			"diary", "diaries");
		goTo(go, ql, "Combat achievements", "", () -> openSheetPage("combat"), 1,
			"ca", "cas", "combat tasks");
		goTo(go, ql, "Clues", "", () -> openSheetPage("clues"), 1,
			"clue", "clue scrolls", "caskets", "treasure trails");
		goTo(go, ql, "Records", "", this::openRecords, 1,
			"record", "best", "bests", "pb", "personal best");
		goTo(go, ql, "Calendar", "", this::openCalendar, 1, "days", "days written");
		goTo(go, ql, "Recap", "", () ->
		{
			subByTab.put(Tab.RECORD, "Recap");
			applyTab(Tab.RECORD);
			rebuild();
		}, 1, "summary");
		goTo(go, ql, "All trackers", "", this::openAllTrackers, 1, "trackers", "counters");
		goTo(go, ql, "Kill log", "", () ->
		{
			slayerLens = "Monsters";
			applyTab(View.SLAYER);
		}, 1, "killlog", "kill count", "kc");
		goTo(go, ql, "Left behind", "", () ->
		{
			applyTab(View.DROPS);
			dropsLeftBehind = true;
			rebuild();
		}, 1, "untaken", "left on the floor");
		goTo(go, ql, "Info", "what the journal holds", this::openInfo, 1, "journal holds");
		String kind = ItemKinds.named(q);
		if (kind != null && !ql.isEmpty())
		{
			final String pick = kind;
			int ks = matchScore(ql, kind);
			ks = ks < 0 || ks > 2 ? 2 : ks;
			go.add(new Hit(kind, "every one you have had", null, null,
				() -> openLootKind(pick, false), ks, 0));
			if (everOnTask() && hasKindOnTask(pick))
			{
				go.add(new Hit(kind, "from slayer tasks", null, null, () -> openLootKind(pick, true), ks, -1));
			}
		}
		total += searchGroup(p, "Go to", go);

		List<Hit> fights = new ArrayList<>();
		Set<String> kinds = new HashSet<>();
		Map<String, SourceRow> ledgerRows = new HashMap<>();
		for (SourceRow r : sources())
		{
			ledgerRows.put(r.name, r);
		}
		for (Boss b : bossRoster(plugin.gson()))
		{
			int sc = matchScore(ql, b.name);
			if (sc < 0 || !kinds.add(kindOf(b.name)))
			{
				continue;
			}
			final String open = bossLootSource(b);
			kinds.add(kindOf(open));
			SourceRow r = ledgerRows.get(open);
			boolean own = r != null && kindOf(open).equals(kindOf(b.name))
				&& isKillSource(open);
			long n = own ? standingKills(r) : bossKills(b.name);
			fights.add(new Hit(b.name, n > 0 ? fmt(n) + " kc" : "-", null,
				r == null ? null : gps(r.value) + (r.pb != null ? " · PB " + pb(r.pb) : ""),
				() -> openSourceLoose(open), sc, n));
		}
		for (SourceRow r : sources())
		{
			int sc = matchScore(ql, r.name);
			if (sc < 0 || !kinds.add(kindOf(r.name)))
			{
				continue;
			}
			boolean killed = isKillSource(r.name);
			long n = killed ? standingKills(r) : r.loots;
			fights.add(new Hit(r.name, killed ? fmt(n) + " kc" : count(r.loots, "drop"), null,
				gps(r.value) + (r.pb != null ? " · PB " + pb(r.pb) : ""),
				() -> openSource(r.name), sc, n));
		}
		JsonObject cl = clogNow();
		for (Entry<String, JsonElement> e : obj(cl, "slayer_kcs").entrySet())
		{
			int sc = matchScore(ql, e.getKey());
			if (sc < 0 || !kinds.add(kindOf(e.getKey())))
			{
				continue;
			}
			long n = safeLong(e.getValue());
			fights.add(new Hit(e.getKey(), fmt(n) + " kc", null, "In the Kill Log", () ->
			{
				slayerLens = "Monsters";
				applyTab(View.SLAYER);
			}, sc, n));
		}
		total += searchGroup(p, "Bosses and monsters", fights);

		List<Hit> tasks = new ArrayList<>();
		final SlayerJourney journey = journeyCache != null ? journeyCache : historyJourney;
		fetchJourneyForSearch();
		if (journey != null)
		{
			Map<String, int[]> byTask = new LinkedHashMap<>();
			for (int i = 0; i < journey.tasks.size(); i++)
			{
				SlayerTask t = journey.tasks.get(i);
				if (t.task == null || matchScore(ql, t.task) < 0)
				{
					continue;
				}
				int[] seen = byTask.computeIfAbsent(low(t.task), k -> new int[]{0, -1});
				seen[0]++;
				if (seen[1] < 0)
				{
					seen[1] = i;
				}
			}
			for (int[] seen : byTask.values())
			{
				final int at = seen[1];
				String name = journey.tasks.get(at).task;
				tasks.add(new Hit(name, count(seen[0], "task"), null, "Opens the newest", () ->
				{
					if (journeyCache == null)
					{
						journeyCache = journey;
					}
					applyTab(View.SLAYER);
					detailTask = at;
					rebuild();
				}, matchScore(ql, name), seen[0]));
			}
		}
		total += searchGroup(p, "Slayer tasks", tasks);

		Map<String, long[]> itemAgg = new LinkedHashMap<>();
		Map<String, List<String>> itemSrcs = new LinkedHashMap<>();
		for (SourceRow src : sources())
		{
			for (BagItem b : plugin.sourceItems(src.name))
			{
				if (matchScore(ql, b.name) < 0)
				{
					continue;
				}
				long[] agg = itemAgg.computeIfAbsent(b.name, k -> new long[2]);
				agg[0] += b.qty;
				agg[1] += b.value;
				itemSrcs.computeIfAbsent(b.name, k -> new ArrayList<>()).add(src.name);
			}
		}
		List<Hit> items = new ArrayList<>();
		for (Entry<String, long[]> e : itemAgg.entrySet())
		{
			final String itm = e.getKey();
			List<String> from = itemSrcs.get(itm);
			String tip = (e.getValue()[1] > 0 ? gp(e.getValue()[1]) + " gp · " : "") + "from "
				+ String.join(", ", from.subList(0, Math.min(4, from.size())))
				+ (from.size() > 4 ? " and " + (from.size() - 4) + " more" : "");
			items.add(new Hit(itm, "×" + fmt(e.getValue()[0]), null, tip, () -> openItem(itm),
				matchScore(ql, itm), e.getValue()[1]));
		}
		for (UntakenRow u : plugin.untakenItems())
		{
			if (itemAgg.containsKey(u.name) || matchScore(ql, u.name) < 0)
			{
				continue;
			}
			items.add(new Hit(u.name, "×" + fmt(u.qty) + " left", ACCENT_RED, "Left on the floor", () ->
			{
				applyTab(View.DROPS);
				dropsLeftBehind = true;
				leftBehindItem = u.name;
				rebuild();
			}, matchScore(ql, u.name), u.value));
		}
		total += searchGroup(p, "Items", items);

		List<Hit> log = new ArrayList<>();
		Obtained ob = obtained(clogNow());
		Set<String> slotSeen = new HashSet<>();
		for (Map<String, List<String>> tab : taxonomy(plugin.gson()).values())
		{
			for (Entry<String, List<String>> pg : tab.entrySet())
			{
				final String page = pg.getKey();
				int ps = matchScore(ql, page);
				boolean[] lit = null;
				if (ps >= 0)
				{
					lit = lightSlots(pg.getValue(), ob.byPage.get(low(page)),
						ob.all, sharedSlotNames(plugin.gson()));
					int held = 0;
					for (boolean l : lit)
					{
						held += l ? 1 : 0;
					}
					log.add(new Hit(page, held + " / " + pg.getValue().size(), null, "The page",
						() -> openLogPage(page), Math.max(0, ps - 1), 2));
				}
				for (String slot : pg.getValue())
				{
					int sc = matchScore(ql, slot);
					if (sc < 0 || !slotSeen.add(slot))
					{
						continue;
					}
					if (lit == null)
					{
						lit = lightSlots(pg.getValue(), ob.byPage.get(low(page)),
							ob.all, sharedSlotNames(plugin.gson()));
					}
					boolean got = false;
					for (int i = 0; i < lit.length; i++)
					{
						got |= lit[i] && pg.getValue().get(i).equalsIgnoreCase(slot);
					}
					log.add(new Hit(slot, page, got ? ACCENT_SESSION : ACCENT_RED,
						got ? "Held" : "Missing", () -> openLogPage(page), sc, got ? 1 : 0));
				}
			}
		}
		total += searchGroup(p, "Collection log", log);

		List<Hit> questHits = new ArrayList<>();
		for (String name : quests.keySet())
		{
			int sc = matchScore(ql, name);
			if (sc < 0)
			{
				continue;
			}
			String state = quests.get(name).getAsString();
			boolean done = "FINISHED".equals(state);
			questHits.add(new Hit(name, done ? "complete" : "IN_PROGRESS".equals(state)
				? "in progress" : "not started", done ? ACCENT_SESSION : ACCENT_RED, null,
				() -> openSheetPage("quests"), sc, done ? 1 : 0));
		}
		total += searchGroup(p, "Quests", questHits);

		if (ql.length() >= 3)
		{
			Set<Integer> done = caDone();
			bundledCombat = bundle(plugin.gson(), "osrs_combat_achievements.json", bundledCombat);
			JsonObject cas = bundledCombat.has("tasks") ? bundledCombat.getAsJsonObject("tasks") : new JsonObject();
			List<Hit> caHits = new ArrayList<>();
			for (String id : cas.keySet())
			{
				JsonObject t = cas.getAsJsonObject(id);
				String name = t.get("name").getAsString();
				String task = t.get("task").getAsString();
				int sc = matchScore(ql, name);
				if (sc < 0 && low(task).contains(ql))
				{
					sc = 3;
				}
				if (sc < 0)
				{
					continue;
				}
				boolean has = done.contains(Integer.parseInt(id));
				caHits.add(new Hit(name, prettyTier(low(t.get("tier").getAsString())),
					done.isEmpty() ? null : has ? ACCENT_SESSION : ACCENT_RED,
					t.get("monster").getAsString() + ": " + task, () -> openSheetPage("combat"), sc,
					has ? 1 : 0));
			}
			total += searchGroup(p, "Combat achievements", caHits);

			bundledDiaries = bundle(plugin.gson(), "osrs_achievement_diaries.json", bundledDiaries);
			JsonObject diaries = bundledDiaries.has("diaries")
				? bundledDiaries.getAsJsonObject("diaries") : new JsonObject();
			List<Hit> diaryHits = new ArrayList<>();
			for (String region : diaries.keySet())
			{
				JsonObject tiers = diaries.getAsJsonObject(region);
				for (String tier : tiers.keySet())
				{
					for (JsonElement e : tiers.getAsJsonArray(tier))
					{
						JsonObject t = e.getAsJsonObject();
						String task = t.get("task").getAsString();
						if (!low(task).contains(ql))
						{
							continue;
						}
						String needs = t.has("requirements") ? t.get("requirements").getAsString() : "";
						diaryHits.add(new Hit(firstSentence(task), low(tier), null,
							region + " " + low(tier) + ": " + task
								+ (needs.isEmpty() || "None".equalsIgnoreCase(needs) ? "" : " Needs: " + needs),
							() -> openSheetPage("diaries"), 3, 0));
					}
				}
			}
			total += searchGroup(p, "Diary tasks", diaryHits);
		}

		List<Hit> stats = new ArrayList<>();
		for (Entry<String, Long> e : withLedgerSpend(counters()).entrySet())
		{
			if (e.getValue() == 0 || StatRegistry.hidden(e.getKey()))
			{
				continue;
			}
			String label = StatRegistry.label(e.getKey());
			int sc = bestScore(ql, label, e.getKey());
			if (sc < 0)
			{
				continue;
			}
			stats.add(new Hit(label, StatRegistry.isGp(e.getKey()) ? gps(e.getValue()) : fmt(e.getValue()),
				null, null, this::openAllTrackers, sc, e.getValue()));
		}
		total += searchGroup(p, "Trackers", stats);

		List<Hit> journal = new ArrayList<>();
		int thisYear = LocalDate.now().getYear();
		String qj = ql.replace("'", "");
		for (Object[] line : searchFeed())
		{
			if (qj.trim().isEmpty() || !((String) line[0]).contains(qj))
			{
				continue;
			}
			final long at = (Long) line[2];
			String day = at <= 0 ? "" : (dayOf(at).getYear() == thisYear ? DAY : TASK_DAY)
				.format(Instant.ofEpochMilli(at));
			journal.add(new Hit((String) line[1], day, null, null, () -> openJournalOn(at), 0, at));
		}
		total += searchGroup(p, "Journal", journal);

		if (total == 0)
		{
			p.add(note("Nothing matches \"" + q + "\" yet."));
			final String near = nearestName(ql);
			if (near != null)
			{
				JPanel r = row("Did you mean", near, accent());
				door(r, () -> searchField.setText(near));
				p.add(r);
			}
		}
		else
		{
			p.add(vgap(6));
			p.add(ghostRow("enter opens the first row", ""));
		}
		return p;
	}

	static String firstSentence(String task)
	{
		int note = task.indexOf(" Note:");
		String s = note > 0 ? task.substring(0, note) : task;
		for (int stop = s.indexOf(". "); stop > 0; stop = s.indexOf(". ", stop + 1))
		{
			if (Character.isLowerCase(s.charAt(stop - 1)) && stop + 2 < s.length()
				&& Character.isUpperCase(s.charAt(stop + 2)))
			{
				return s.substring(0, stop + 1);
			}
		}
		return s;
	}

	private Runnable searchFirst;

	private void door(JPanel r, Runnable go)
	{
		link(r, go);
		if (searchFirst == null)
		{
			searchFirst = go;
		}
	}

	private static String typeOf(JsonObject e)
	{
		return e.has("type") ? e.get("type").getAsString() : "";
	}

	private static String stamp(JsonObject e)
	{
		long ts = e.has("ts") ? e.get("ts").getAsLong() : 0;
		return ts > 0 ? DAY.format(Instant.ofEpochMilli(ts)) : "";
	}

	private static String[] sessionParts(JsonObject e)
	{
		JsonObject d = obj(e, "data");
		long xp = safeLong(d.get("xp"));
		long drops = safeLong(d.get("drops"));
		StringBuilder right = new StringBuilder();
		if (xp > 0)
		{
			right.append('+').append(gp(xp)).append(" xp");
		}
		if (drops > 0)
		{
			right.append(right.length() > 0 ? " · " : "").append(count(drops, "drop"));
		}
		return new String[]{"Session · " + hoursMinutes(safeLong(d.get("minutes"))),
			right.toString(), feedLine(e)};
	}

	private static String mostOf(JsonObject d)
	{
		String top = null;
		long most = 0;
		for (Entry<String, JsonElement> e : obj(d, "skills").entrySet())
		{
			long xp = safeLong(e.getValue());
			if (xp > most)
			{
				most = xp;
				top = e.getKey();
			}
		}
		return top == null ? null : prettify(top);
	}

	private static String feedLine(JsonObject e)
	{
		String type = typeOf(e);
		JsonObject d = obj(e, "data");
		switch (type)
		{
			case "PET":
				return "Pet: " + str(d, "petName", "a new companion");
			case "COLLECTION":
				return "Log slot: " + str(d, "itemName", "new item");
			case "RECORD":
			{
				double time = d.has("time") ? d.get("time").getAsDouble() : 0;
				double was = d.has("was") ? d.get("was").getAsDouble() : 0;
				return "Record: " + str(d, "source", "") + " " + pb(time)
					+ (was > 0 ? ", was " + pb(was) : "");
			}
			case "MILESTONE":
				return "Milestone: " + str(d, "text", "");
			case "COMBAT_ACHIEVEMENT":
				return "CA " + str(d, "tier", "") + ": " + str(d, "task", "task");
			case "QUEST":
				return "Quest: " + str(d, "questName", str(d, "quest", "complete"));
			case "DIARY":
				return "Diary: " + str(d, "area", "") + " " + str(d, "difficulty", "");
			case "CLUE":
				return "Clue: " + str(d, "clueType", "casket opened");
			case "LEVEL":
				return "Level: " + str(d, "skill", "a skill") + " " + str(d, "level", "");
			case "DEATH":
			{
				String k = str(d, "killerName", "");
				return k.isEmpty() ? "Died" : "Died to " + k;
			}
			case "SESSION":
			{
				long mins = d.has("minutes") ? d.get("minutes").getAsLong() : 0;
				long xp = d.has("xp") ? d.get("xp").getAsLong() : 0;
				long drops = d.has("drops") ? d.get("drops").getAsLong() : 0;
				long dropsGp = d.has("dropsGp") ? d.get("dropsGp").getAsLong() : 0;
				StringBuilder line = new StringBuilder("Session: ");
				line.append(hoursMinutes(mins));
				if (xp > 0)
				{
					line.append(" · +").append(gp(xp)).append(" xp");
					String most = mostOf(d);
					if (most != null)
					{
						line.append(", most in ").append(most);
					}
				}
				if (drops > 0)
				{
					line.append(" · ").append(count(drops, "drop"));
					if (dropsGp > 0)
					{
						line.append(" (").append(gp(dropsGp)).append(" gp)");
					}
				}
				return line.toString();
			}
			case "SLAYER":
			{
				String t = str(d, "slayerTask", str(d, "task", ""));
				String kc = str(d, "killCount", "");
				return "Task complete" + (t.isEmpty() ? "" : ": " + t)
					+ (kc.isEmpty() ? "" : ", " + kc + " killed");
			}
			default:
				return type.isEmpty() ? "Milestone" : prettify(low(type));
		}
	}

	private static JsonObject obj(JsonObject o, String key)
	{
		return o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key) : new JsonObject();
	}

	private static String str(JsonObject o, String key, String fallback)
	{
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : fallback;
	}

	private static JPanel column()
	{
		JPanel p = new JPanel(new java.awt.GridBagLayout())
		{
			private final GridBagConstraints gbc = new GridBagConstraints();

			{
				gbc.gridx = 0;
				gbc.gridwidth = GridBagConstraints.REMAINDER;
				gbc.weightx = 1;
				gbc.fill = GridBagConstraints.HORIZONTAL;
			}

			@Override
			protected void addImpl(Component comp, Object constraints, int index)
			{
				super.addImpl(comp, constraints == null ? gbc : constraints, index);
			}
		};
		p.setBackground(DARK);
		return p;
	}

	private static final class ScrollColumn extends JPanel implements javax.swing.Scrollable
	{
		private ScrollColumn()
		{
			super(new BorderLayout());
		}

		@Override
		public Dimension getPreferredScrollableViewportSize()
		{
			return getPreferredSize();
		}

		@Override
		public int getScrollableUnitIncrement(Rectangle r, int o, int d)
		{
			return 16;
		}

		@Override
		public int getScrollableBlockIncrement(Rectangle r, int o, int d)
		{
			return 80;
		}

		@Override
		public boolean getScrollableTracksViewportWidth()
		{
			return true;
		}

		@Override
		public boolean getScrollableTracksViewportHeight()
		{
			return false;
		}
	}

	private static JPanel card(String caption, String right)
	{
		JPanel c = cardPlain();
		JPanel head = new JPanel(new BorderLayout());
		head.setBackground(DARKER);
		head.setAlignmentX(Component.LEFT_ALIGNMENT);
		JLabel cap = styled(new JLabel(caption.toUpperCase(Locale.ROOT)), small(), dim());
		JLabel note = styled(new JLabel(right), small(), dim());
		head.add(cap, BorderLayout.WEST);
		head.add(note, BorderLayout.EAST);
		head.setMaximumSize(new Dimension(Integer.MAX_VALUE, head.getPreferredSize().height));
		spaced(c, head, 3);
		return c;
	}

	private static JPanel card(String caption)
	{
		JPanel c = cardPlain();
		JLabel cap = styled(new JLabel(caption.toUpperCase(Locale.ROOT)), small(), dim());
		cap.setAlignmentX(Component.LEFT_ALIGNMENT);
		spaced(c, cap, 3);
		return c;
	}

	private static JLabel styled(JLabel l, Font f, Color c)
	{
		l.setFont(f);
		l.setForeground(c);
		return l;
	}

	private static Font small()
	{
		return FontManager.getRunescapeSmallFont();
	}

	private static Color dim()
	{
		return ColorScheme.LIGHT_GRAY_COLOR.darker();
	}

	private static JPanel cardPlain()
	{
		JPanel c = new JPanel()
		{
			@Override
			public Dimension getMaximumSize()
			{
				return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
			}
		};
		c.setLayout(new BoxLayout(c, BoxLayout.Y_AXIS));
		c.setBackground(DARKER);
		c.setBorder(pad(6, CARD_INSET, 6, CARD_INSET));
		c.setAlignmentX(Component.LEFT_ALIGNMENT);
		return c;
	}

	private static JPanel row(String left, String right, Color color, boolean colorName)
	{
		JPanel r = row(left, right, color);
		if (colorName && color != null)
		{
			part(r, BorderLayout.CENTER).setForeground(color);
		}
		return r;
	}

	private static JPanel row(String left, String right)
	{
		return row(left, right, null);
	}

	private static JPanel row(String left, String right, Color rightColor)
	{
		JPanel r = new JPanel(new BorderLayout(ROW_GAP, 0));
		r.setOpaque(false);
		r.setAlignmentX(Component.LEFT_ALIGNMENT);
		r.setBorder(pad(1, ROW_INSET, 1, ROW_INSET));
		JLabel l = new JLabel(left);
		l.setFont(FontManager.getRunescapeFont());
		r.add(l, BorderLayout.CENTER);
		if (right != null && !right.isEmpty())
		{
			JLabel v = styled(new JLabel(right), FontManager.getRunescapeFont(),
				rightColor != null ? rightColor : dim());
			r.add(v, BorderLayout.EAST);
		}
		return r;
	}

	private static JPanel worthRow(long v)
	{
		return row("Worth", gps(v));
	}

	private JPanel progress(float frac)
	{
		JPanel outer = new JPanel(new BorderLayout());
		outer.setBackground(ColorScheme.SCROLL_TRACK_COLOR);
		outer.setPreferredSize(new Dimension(10, 4));
		outer.setMinimumSize(new Dimension(10, 4));
		outer.setMaximumSize(new Dimension(Integer.MAX_VALUE, 4));
		outer.setAlignmentX(Component.LEFT_ALIGNMENT);
		JPanel inner = new JPanel();
		inner.setBackground(accent());
		inner.setPreferredSize(new Dimension(
			Math.max(1, Math.round(frac * (PluginPanel.PANEL_WIDTH - 40))), 4));
		JPanel holder = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		holder.setOpaque(false);
		holder.add(inner);
		outer.add(holder, BorderLayout.WEST);
		return outer;
	}

	private JLabel group(String name)
	{
		JLabel g = styled(new JLabel(name.toUpperCase(Locale.ROOT)), small(), accent());
		g.setAlignmentX(Component.LEFT_ALIGNMENT);
		g.setBorder(pad(8, 2, 3, 0));
		return g;
	}

	private static final int NOTE_WIDTH = 190;

	private static JPanel note(String text)
	{
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setOpaque(false);
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		Font f = small();
		FontMetrics fm = p.getFontMetrics(f);
		List<String> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" "))
		{
			String candidate = line.length() == 0 ? word : line + " " + word;
			if (fm.stringWidth(candidate) > NOTE_WIDTH && line.length() > 0)
			{
				lines.add(line.toString());
				line = new StringBuilder(word);
			}
			else
			{
				line = new StringBuilder(candidate);
			}
		}
		if (line.length() > 0)
		{
			lines.add(line.toString());
		}
		for (String l : lines)
		{
			JLabel lab = styled(new JLabel(l), f, dim());
			lab.setAlignmentX(Component.LEFT_ALIGNMENT);
			p.add(lab);
		}
		return p;
	}

	private static javax.swing.border.Border pad(int t, int l, int b, int r)
	{
		return BorderFactory.createEmptyBorder(t, l, b, r);
	}

	private static JPanel grid3()
	{
		JPanel grid = new JPanel(new GridLayout(0, 3, 2, 2));
		grid.setBackground(DARK);
		grid.setAlignmentX(Component.LEFT_ALIGNMENT);
		return grid;
	}

	private static JPanel levelTile(String title)
	{
		JPanel cell = tile(4, 6);
		cell.setAlignmentX(Component.LEFT_ALIGNMENT);
		cell.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
		JLabel name = styled(new JLabel(title), small(), dim());
		cell.add(name, BorderLayout.CENTER);
		return cell;
	}

	private static <T> List<T> firstN(List<T> l, int cap)
	{
		return l.subList(0, Math.min(cap, l.size()));
	}

	private static JPanel tile(int v, int h)
	{
		JPanel cell = new JPanel(new BorderLayout(3, 0));
		cell.setBackground(DARKER);
		cell.setBorder(pad(v, h, v, h));
		return cell;
	}

	private static JLabel part(JComponent row, String where)
	{
		return (JLabel) ((BorderLayout) row.getLayout()).getLayoutComponent(where);
	}

	private static void spaced(JComponent p, Component c)
	{
		p.add(c);
		p.add(vgap(6));
	}

	private static void spaced(JComponent p, Component c, int gap)
	{
		p.add(c);
		p.add(vgap(gap));
	}

	private JPanel backPage()
	{
		JPanel p = column();
		spaced(p, backRow(), 4);
		return p;
	}

	private static JPanel noted(JPanel p, String text)
	{
		p.add(note(text));
		return p;
	}

	private static Component vgap(int h)
	{
		JPanel p = new JPanel();
		p.setOpaque(false);
		p.setPreferredSize(new Dimension(1, h));
		p.setMinimumSize(new Dimension(1, h));
		p.setMaximumSize(new Dimension(Integer.MAX_VALUE, h));
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		return p;
	}

	private static final int HOVER_LIFT = 15;

	private static Color behind(Component c)
	{
		for (Component p = c.getParent(); p != null; p = p.getParent())
		{
			if (p.isOpaque() && p.getBackground() != null)
			{
				return p.getBackground();
			}
		}
		return DARKER;
	}

	private static Color hoverOf(Color ground)
	{
		if (DARKER.equals(ground))
		{
			return ColorScheme.DARKER_GRAY_HOVER_COLOR;
		}
		if (DARK.equals(ground))
		{
			return ColorScheme.DARK_GRAY_HOVER_COLOR;
		}
		return new Color(
			Math.min(255, ground.getRed() + HOVER_LIFT),
			Math.min(255, ground.getGreen() + HOVER_LIFT),
			Math.min(255, ground.getBlue() + HOVER_LIFT));
	}

	private JPanel toggle(String reading, Runnable flip)
	{
		JPanel cell = new JPanel(new BorderLayout());
		cell.setBackground(DARKER);
		cell.setBorder(pad(2, 4, 2, 4));
		JLabel l = styled(new JLabel(reading, JLabel.CENTER), small(), accent());
		cell.add(l, BorderLayout.CENTER);
		link(cell, flip);
		return cell;
	}

	private static boolean stillUnder(MouseEvent e)
	{
		boolean over = false;
		boolean pointerKnown = false;
		try
		{
			over = e.getComponent().getMousePosition() != null;
			pointerKnown = java.awt.MouseInfo.getPointerInfo() != null;
		}
		catch (RuntimeException ignored)
		{
		}
		return stillUnder(over, pointerKnown, e.getComponent().contains(e.getPoint()));
	}

	static boolean stillUnder(boolean overComponent, boolean pointerKnown,
		boolean eventSaysInside)
	{
		if (overComponent)
		{
			return true;
		}
		if (pointerKnown)
		{
			return false;
		}
		return eventSaysInside;
	}

	private static Runnable litNow;

	private static void unlight()
	{
		Runnable was = litNow;
		litNow = null;
		if (was != null)
		{
			was.run();
		}
	}

	private static MouseAdapter clicker(Runnable r)
	{
		return new MouseAdapter()
		{
			private boolean lit;
			private boolean wasOpaque;
			private Color wasBackground;
			private JComponent target;

			@Override
			public void mousePressed(MouseEvent e)
			{
				r.run();
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				if (lit || !(e.getComponent() instanceof JComponent))
				{
					return;
				}
				unlight();
				JComponent c = (JComponent) e.getComponent();
				target = c;
				wasOpaque = c.isOpaque();
				wasBackground = c.getBackground();
				Color ground = wasOpaque && wasBackground != null
					? wasBackground : behind(c);
				c.setBackground(hoverOf(ground));
				c.setOpaque(true);
				c.repaint();
				lit = true;
				litNow = this::putBack;
			}

			private void putBack()
			{
				if (!lit || target == null)
				{
					return;
				}
				target.setOpaque(wasOpaque);
				target.setBackground(wasBackground);
				target.repaint();
				lit = false;
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				if (!lit || !(e.getComponent() instanceof JComponent))
				{
					return;
				}
				if (stillUnder(e))
				{
					return;
				}
				litNow = null;
				putBack();
			}
		};
	}

	private static void link(Component c, Runnable go)
	{
		c.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		c.addMouseListener(clicker(go));
	}

	private static String fmt(long n)
	{
		return String.format(Locale.UK, "%,d", n);
	}

	private static String gp(long n)
	{
		if (Math.abs(n) >= 1_000_000_000L)
		{
			return String.format(Locale.UK, "%.2fB", n / 1_000_000_000.0);
		}
		if (Math.abs(n) >= 1_000_000L)
		{
			return String.format(Locale.UK, "%.1fM", n / 1_000_000.0);
		}
		if (Math.abs(n) >= 10_000L)
		{
			return String.format(Locale.UK, "%dk", n / 1_000);
		}
		return fmt(n);
	}

	private static String low(String s)
	{
		return s.toLowerCase(Locale.ROOT);
	}

	private static String gps(long n)
	{
		return gp(n) + " gp";
	}

	private static String named(String name, long qty)
	{
		return name + (qty > 1 ? " \u00d7" + fmt(qty) : "");
	}

	private static String tail(long v)
	{
		return v > 0 ? " · " + gps(v) : "";
	}

	private static String xpShort(long n)
	{
		long a = Math.abs(n);
		if (a >= 100_000L)
		{
			return gp(n);
		}
		if (a >= 1_000L)
		{
			String k = String.format(Locale.UK, "%.1f", n / 1_000.0);
			return (k.endsWith(".0") ? k.substring(0, k.length() - 2) : k) + "k";
		}
		return fmt(n);
	}

	private static String pb(double seconds)
	{
		long s = Math.round(seconds);
		long h = s / 3600;
		long m = (s % 3600) / 60;
		long sec = s % 60;
		return h > 0 ? String.format("%d:%02d:%02d", h, m, sec) : String.format("%d:%02d", m, sec);
	}
}
