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
import chronicle.Board.Kind;
import chronicle.Board.Obtained;
import chronicle.Board.SkillStand;
import chronicle.Board.Span;
import chronicle.Period.Window;
import chronicle.counters.ExperienceStatTracker;
import chronicle.counters.StatKeys;
import chronicle.panel.StatRegistry;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Rectangle;
import java.awt.event.HierarchyEvent;
import java.awt.event.MouseListener;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.AbstractMap;
import java.util.ArrayDeque;
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
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.MenuElement;
import javax.swing.MenuSelectionManager;
import javax.swing.ScrollPaneConstants;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.ToolTipManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import lombok.RequiredArgsConstructor;
import net.runelite.api.Skill;
import net.runelite.api.SpriteID;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.hiscore.HiscoreSkill;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.IconTextField;
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.ui.components.materialtabs.MaterialTabGroup;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.ImageUtil;
import static chronicle.Feed.*;
import static chronicle.LocalStore.kindOf;
import static chronicle.Pictures.*;
import static chronicle.Reference.*;
import static chronicle.Ui.*;
import static chronicle.panel.StatRegistry.prettify;

class ChroniclePanel extends PluginPanel
{

	enum View
	{
		HOME, DROPS, SLAYER, STATS, JOURNAL, SHEET, RECAP
	}

	enum Tab
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
	final Map<Tab, String> subByTab = new EnumMap<>(Tab.class);
	final IconTextField searchField = new IconTextField();
	private final Timer searchDebounce;
	private final Timer homeTicker;

	private View view = View.HOME;
	Tab tab = Tab.RECORD;
	private String detailItem;
	private String detailSource;
	private String detailSkill;
	boolean allTrackers;
	private boolean showInfo;
	private boolean showRecords;
	private boolean showCalendar;
	private final ArrayDeque<String[]> detailStack = new ArrayDeque<>();
	private int dropsShown = ROW_CAP;
	private String clogTab = "Bosses";
	private String clogPageSel;

	private final JPanel band = new JPanel(new BorderLayout());
	private final JLabel bandText = new JLabel();
	private final Period period = new Period();
	private final Board board;
	final TrackersScreen trackers;
	private final SearchScreen search;
	private final JournalScreen journal;
	private final RecapScreen recap;

	ChroniclePanel(ChroniclePlugin plugin)
	{
		super(false);
		this.plugin = plugin;
		board = new Board(plugin, period, this::rebuildInPlace);
		trackers = new TrackersScreen(this, board);
		search = new SearchScreen(this, board);
		journal = new JournalScreen(this, board);
		recap = new RecapScreen(this, board);

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
			if (search.searchFirst != null)
			{
				search.searchFirst.run();
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
		OverlayScrollBarUI.install(scrollPane);
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
		ToolTipManager.sharedInstance().setInitialDelay(220);
		ToolTipManager.sharedInstance().setDismissDelay(20_000);

		band.setAlignmentX(Component.CENTER_ALIGNMENT);
		band.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
		band.setBorder(pad(3, 8, 3, 8));
		bandText.setFont(small());
		band.add(bandText, BorderLayout.CENTER);
		band.setVisible(false);
		north.add(band);

		board.gatherHistory();
		rebuild();
	}

	private void addTab(String icon, String tooltip, Tab target)
	{
		MaterialTab mt = new MaterialTab(new ImageIcon(ImageUtil.loadImageResource(ChroniclePanel.class, icon)),
			tabGroup, new JPanel());
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
			case SHEET:
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
			case SHEET:
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

	void applyTab(Tab target)
	{
		tab = target;
		applyCommon();
	}

	void applyTab(View target)
	{
		tab = tabFor(target);
		subByTab.put(tab, subFor(target));
		applyCommon();
	}

	void applyCommon()
	{
		view = viewOf();
		sheetPage = null;
		if (tab == Tab.TRACKERS)
		{
			trackers.statsFamily = StatRegistry.FAMILIES[0];
		}
		else if (tab == Tab.RECORD && "Ledger".equals(sub())
			&& !"Ledger & Roads".equals(trackers.statsFamily) && !"Living".equals(trackers.statsFamily))
		{
			trackers.statsFamily = "Ledger & Roads";
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

	private JPanel buildSheet()
	{
		JPanel p = column();
		p.add(buildHistory());
		p.add(activitySheet());
		p.add(buildKills());
		return p;
	}

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
				return SpriteID.TAB_QUESTS;
			case "Diaries":
				return SpriteID.TAB_QUESTS_GREEN_ACHIEVEMENT_DIARIES;
			default:
				return 0;
		}
	}

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
		if (board.resolveSourceNamed(source) == null && openLogPage(source))
		{
			return;
		}
		openSourceLoose(source);
	}

	boolean openLogPage(String page)
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
			for (int i = 0; i < CLUE_TIERS.length; i++)
			{
				SourceRow r = board.clue(CLUE_TIERS[i]);
				if (r != null)
				{
					each[i] = Math.max(r.kc, r.loots);
					worth[i] = r.value;
					all += each[i];
					allWorth += r.value;
				}
			}
			figure = all;
			List<String> lines = new ArrayList<>(Arrays.asList("All", fmt(all) + tail(allWorth)));
			for (int i = 0; i < CLUE_TIERS.length; i++)
			{
				lines.add(CLUE_TIERS[i]);
				lines.add(each[i] == 0 ? "0" : fmt(each[i]) + tail(worth[i]));
			}
			hover = tip("Clues", lines);
		}
		else if ("Collections".equals(label))
		{
			figure = plugin.clogFinished();
			int[] logStanding = board.clogStanding();
			hover = tip("Collection log",
				"Obtained", fmt(figure),
				"Available", logStanding != null ? fmt(logStanding[1]) : "not yet",
				"Share", logStanding != null ? share(logStanding[0], logStanding[1]) : "-");
		}
		else if ("Quests".equals(label))
		{
			JsonObject q = obj(board.achievements(), "quests");
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
				"Complete", fmt(done),
				"In progress", fmt(started),
				"Known", fmt(q.size()));
		}
		else if ("Diaries".equals(label))
		{
			long[] d = board.diaryStanding();
			figure = d[0];
			hover = tip("Achievement diaries",
				"Tiers done", d[0] + " / " + d[1],
				"Regions finished", fmt(d[2]),
				"Regions", fmt(d[3]));
		}
		else
		{
			long named = board.namedLine(source, label);
			figure = named > 0 ? named : board.bossKills(source);
			hover = tip(label, "Count", fmt(figure));
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
			lit ? LIT : DIM);
		JPanel text = new JPanel(new GridLayout(moved > 0 ? 2 : 1, 1));
		text.setBackground(DARKER);
		text.add(fig);
		if (moved > 0)
		{
			text.add(styled(new JLabel("+" + fmt(moved), JLabel.RIGHT), small(), ACCENT));
		}
		cell.add(text, BorderLayout.CENTER);
		return cell;
	}

	private JPanel buildKills()
	{
		JPanel p = column();
		board.movedKcs = null;
		board.rolledKcs = null;
		board.rollUsed = false;
		List<Boss> roster = bossRoster(plugin.gson());
		if (roster.isEmpty())
		{
			return noted(p, "The boss roster did not load.");
		}
		if (!period.whole() && !period.session() && board.span() == null)
		{
			p.add(noPeriod());
			return p;
		}
		if (!period.whole())
		{
			List<Boss> had = new ArrayList<>();
			for (Boss b : roster)
			{
				if (board.bossKillsInWindow(b.name) > 0)
				{
					had.add(b);
				}
			}
			if (had.isEmpty())
			{
				String unkept = board.notCounting(true);
				return noted(p, unkept != null ? unkept : board.inside("Nothing on the boss sheet was killed"));
			}
			roster = had;
		}
		JPanel grid = grid3();
		for (Boss b : roster)
		{
			grid.add(bossCell(b));
		}
		LocalDate shortFrom = board.rollUsed ? board.rollShortOf() : null;
		if (shortFrom != null)
		{
			spaced(p, note("Kills the journal cannot date are counted from loot "
				+ "instead, which reaches back only to " + shortFrom.format(FULL_DAY)
				+ " and sees a kill only where it dropped something."), 4);
		}
		spaced(p, grid);
		return p;
	}

	private JPanel bossCell(Boss b)
	{
		final long kc = board.bossKillsInWindow(b.name);
		JPanel cell = tile(3, 3);
		cell.setToolTipText(bossTip(b));

		JLabel icon = new JLabel();
		if (b.sprite > 0)
		{
			wearSprite(icon, b.sprite, 24, 24);
		}
		cell.add(icon, BorderLayout.WEST);

		JLabel fig = styled(new JLabel(kc > 0 ? fmt(kc) : "-", JLabel.RIGHT), small(),
			kc > 0 ? LIT : DIM);
		cell.add(fig, BorderLayout.EAST);
		final String open = board.bossLootSource(b);
		link(cell, () -> openSourceLoose(open));
		return cell;
	}

	private String bossTip(Boss b)
	{
		List<String> lines = new ArrayList<>();
		String kind = kindOf(b.name);
		SourceRow src = null;
		List<SourceRow> paidOut = new ArrayList<>();
		for (SourceRow r : board.sources())
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
		if (!period.whole())
		{
			long inWin = board.bossKillsInWindow(b.name);
			long[] paid = board.sourceInWindow(b.name);
			lines.add(board.window().label);
			lines.add((inWin < 0 ? "-" : count(inWin, "kill"))
				+ tail(paid[1]));
		}
		long known = board.bossKills(b.name);
		lines.add("Kills tracked");
		lines.add(known > 0 ? fmt(known) : src != null ? fmt(src.loots) : "-");
		for (Entry<String, Long> pb : board.pageLines(b.name, "pb_lines"))
		{
			lines.add(pb.getKey());
			lines.add(clock(pb.getValue()));
		}
		double[] timed = period.whole() && src != null ? new double[]{src.timed, src.timeSum}
			: board.sourceTimesInWindow(b.name);
		if (timed[0] > 0)
		{
			lines.add("Average kill");
			lines.add(pb(timed[1] / timed[0]) + " · " + fmt((long) timed[0]) + " timed");
		}
		long here = board.minutesAt(b.name, period.whole() ? board.counters() : board.periodCounters());
		if (here > 0 && board.minutesCoverPeriod())
		{
			lines.add("Time here");
			lines.add(hoursMinutes(here));
		}
		for (Entry<String, Long> ln : board.logLines(b.name))
		{
			lines.add(ln.getKey());
			lines.add(fmt(ln.getValue()));
		}
		if (src != null)
		{
			lines.add("Drops");
			lines.add(paidFigure(src));
		}
		for (SourceRow r : paidOut)
		{
			lines.add(beforeBracket(r.name));
			lines.add(paidFigure(r));
		}
		if (src == null && paidOut.isEmpty())
		{
			lines.add("Loot");
			lines.add("none yet");
		}
		return tip(b.name, lines);
	}

	private String paidFigure(SourceRow r)
	{
		return qtyGp(board.tallyOf(plugin.sourceItems(r.name))[0], r.value);
	}

	private void clearSearch()
	{
		searchField.setText("");
		searchDebounce.stop();
	}

	String searchQuery()
	{
		return searchField.getText() == null ? "" : searchField.getText().trim();
	}

	private final AtomicBoolean queued =
		new AtomicBoolean();

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
			case STATS:
				return (moved & (MOVED_COUNTERS | MOVED_RECORD)) != 0;
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
			PointerInfo at = MouseInfo.getPointerInfo();
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
		return scrollPane.getVerticalScrollBar().getValueIsAdjusting();
	}

	private static boolean popupShowing()
	{
		MenuElement[] path = MenuSelectionManager
			.defaultManager().getSelectedPath();
		return path != null && path.length > 0;
	}

	private void watchForReturn()
	{
		getWrappedPanel().addHierarchyListener(e ->
		{
			if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) == 0
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

	void rebuild()
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
		board.reset();
		trackers.resourcesDropped = 0;
		artWaiting.clear();
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
		JPanel body = !searchQuery().isEmpty() ? search.buildSearch(searchQuery())
			: detailItem != null ? buildItemDetail(detailItem)
			: detailSource != null ? buildSourceDetail(detailSource)
			: detailSkill != null ? trackers.buildSkillDetail(detailSkill)
			: allTrackers ? trackers.buildAllTrackers()
			: showRecords ? journal.buildRecords()
			: showCalendar ? journal.buildCalendar()
			: showInfo ? journal.buildInfo()
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
			case DROPS:
				return buildDrops();
			case SLAYER:
				return buildSlayer();
			case STATS:
				return trackers.buildStats();
			case JOURNAL:
				return journal.buildJournal();
			case RECAP:
				return recap.buildRecap();
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
		Color ink = red ? ColorScheme.PROGRESS_ERROR_COLOR : ACCENT;
		bandFixes = !red;
		for (MouseListener l : band.getMouseListeners())
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

	private static long splitOf(Map<String, Integer> sess)
	{
		long n = 0;
		for (String k : DAMAGE_SPLIT)
		{
			n += sess.getOrDefault(k, 0);
		}
		return n;
	}

	void foldHead(JPanel head, String fold, String tip)
	{
		JLabel name = part(head, BorderLayout.CENTER);
		if (foldOpen(fold))
		{
			name.setForeground(ACCENT);
		}
		head.setToolTipText(tip);
		folds(head, fold);
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
			addTaskCard(p, task, "Slayer task", GREEN);
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
					GREEN);
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
				GREEN));
			mounted++;
			if (plugin.sessionUntakenKills() > 0)
			{
				strip.add(row("Drops taken",
					fmt(Math.max(0, plugin.sessionLoots() - plugin.sessionUntakenKills())),
					GREEN));
				mounted++;
			}
		}
		long[] untaken = plugin.sessionUntakenTally();
		if (untaken[0] > 0)
		{
			strip.add(row("Left behind", qtyGp(untaken[0], untaken[1])));
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
		for (JsonObject e : plugin.feedNewest(Board.FEED_SCAN_DEEP))
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
			strip.add(namedRow("Levels", said, GREEN));
			mounted++;
		}
		if (!slots.isEmpty())
		{
			strip.add(namedRow(plural(slots.size(), "Log slot"), slots, GREEN));
			mounted++;
		}
		if (!pets.isEmpty())
		{
			strip.add(namedRow(plural(pets.size(), "Pet"), pets, GREEN));
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
			boolean open = foldOpen(stateKey, true);
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
		strip.add(folds(sessionRow(key, value), listKey));
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

	JPanel quietHead(String name, String count, String stateKey)
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

	private JPanel dropsInWindow(JPanel p)
	{
		Window win = board.window();
		LocalStore.LootWindow sitting = period.session() ? plugin.sessionLootWindow() : null;
		long rollFrom = plugin.lootRollFrom();
		long fromMs = startMs(win.start);
		if (sitting == null && rollFrom <= 0)
		{
			return noted(p, Board.UNDATED);
		}
		if (sitting == null && rollFrom > fromMs)
		{
			LocalDate began = dayOf(rollFrom);
			return noted(p, "The dated loot roll begins " + began.format(FULL_DAY)
				+ ", which is inside " + board.periodInSentence() + ". Naming the part it can see "
				+ "as the whole period would be worse than saying nothing.");
		}
		LocalStore.LootWindow w = sitting != null ? sitting
			: plugin.lootBetween(win.start, win.end);
		if (!dropsLeftBehind && dropsByKind)
		{
			if (w.items.isEmpty())
			{
				return noted(p, board.inside("Nothing taken"));
			}
			return kindLens(p, win.label, bagOf(w.items), "win:");
		}
		List<String[]> ranked = dropsLeftBehind ? w.leftItems : w.sources;
		if (ranked.isEmpty())
		{
			return noted(p, board.inside("Nothing " + (dropsLeftBehind ? "left behind" : "taken")));
		}
		JPanel head = dropsLeftBehind ? tallyCard("Left behind", "Items", fmt(w.left), RED, w.leftValue)
			: tallyCard("Drops received", "Drops", fmt(w.loots), ACCENT, w.value);
		if (dropsLeftBehind)
		{
			head.add(row("Kills that left one", fmt(w.leftKills)));
		}
		spaced(p, head);
		final String key = dropsLeftBehind ? "win:left" : "win:source";
		final int cap = drillShown.getOrDefault(key, ROW_CAP);
		for (String[] r : firstN(ranked, cap))
		{
			JPanel line = row(r[0], qtyGp(safeParse(r[1]), safeParse(r[2])));
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
		drillMore(p, key, ranked.size(), cap);
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

	private boolean onTaskOnly;

	boolean dropsLeftBehind;
	private String lootKind;
	private String lootTask;

	private JPanel buildDrops()
	{
		JPanel p = column();
		List<JPanel> axes = new ArrayList<>();
		axes.add(toggle(dropsLeftBehind ? "Left behind" : "Received", relens(() -> dropsLeftBehind = !dropsLeftBehind)));
		if (!dropsLeftBehind || period.whole())
		{
			axes.add(toggle(dropsByKind
				? (dropsLeftBehind ? "By item" : "By kind") : "By source", relens(() -> dropsByKind = !dropsByKind)));
		}
		final boolean canAskOnTask = !dropsLeftBehind && dropsByKind && board.everOnTask();
		if (canAskOnTask)
		{
			axes.add(toggle(onTaskOnly ? "On task" : "All", relens(() -> onTaskOnly = !onTaskOnly)));
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
			long[] w = board.windowMs();
			List<BagItem> taskBag = plugin.onTaskLoot(w[0], w[1], null, period.whole());
			if (taskBag.isEmpty())
			{
				return noted(p, board.inside("No task closed"));
			}
			return kindLens(p, period.whole() ? "On-task loot"
				: "Tasks closed in " + board.window().label, taskBag, "ontask:");
		}
		if (!period.whole())
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
		List<SourceRow> sources = new ArrayList<>(board.sources());
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
		JPanel lifeHead = tallyCard("Drops received", "Drops", fmt(everyDrop), ACCENT, everyValue);
		lifeHead.add(row("Sources", fmt(sources.size())));
		spaced(p, lifeHead);
		for (SourceRow r : firstN(sources, dropsShown))
		{
			boolean killed = board.isKillSource(r.name);
			String sub = (killed ? fmt(board.standingKills(r)) + " kc" : count(r.loots, "drop"))
				+ (r.pb != null ? " · PB " + pb(r.pb) : "");
			listCard(p, row(r.name, gps(r.value), ACCENT), sub,
				r.loots > 0 ? perOne(r.value, r.loots, killed) : "", () -> openSource(r.name));
		}
		more(p, sources.size(), dropsShown, false, n -> dropsShown = n);
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
		for (Kind k : board.kindsOf(bag))
		{
			JPanel r = row(k.name, qtyGp(k.qty, k.value), ACCENT);
			r.setToolTipText(count(k.distinct, "distinct item"));
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
		BagItem top = most(bag, b -> b.value);
		if (top == null)
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
		final long[] sum = board.tallyOf(bag);
		if (lootKind != null)
		{
			return kindDrill(p, bag, key);
		}
		JPanel head = bagCard(title, bag, sum);
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
		final long[] mine = board.tallyOf(kept);
		spaced(p, bagCard(lootKind, kept, mine));
		p.add(backToKinds(kept.size()));
		if (kept.isEmpty())
		{
			return noted(p, "Nothing of this kind here.");
		}
		p.add(copyHeader(lootKind, () -> copyPicture(
			lootPicture(lootKind, kept, mine, false), true)));
		int cap = drillShown.getOrDefault(key + lootKind, ROW_CAP);
		addBagRows(p, firstN(kept, cap));
		drillMore(p, key + lootKind, kept.size(), cap);
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
		JPanel head = tallyCard("Left behind", "Items", fmt(totalQty), RED, totalVal);
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
		for (UntakenRow r : firstN(list, cap))
		{
			listCard(p, row(r.name, gps(r.value), RED),
				byItem ? "\u00d7" + fmt(r.qty) : fmt(r.qty) + " left", r.qty > 0 ? perOne(r.value, r.qty, false) : "",
				() -> showLeftBehind(byItem ? null : r.name, byItem ? r.name : null));
		}
		drillMore(p, key, list.size(), cap);
		return p;
	}

	List<GrindBook.GrindRow> grindsCache;
	private boolean grindsFetching;

	SlayerJourney journeyCache;
	boolean journeyFetching;
	private int detailTask = -1;
	private String leftBehindSource;
	String leftBehindItem;

	void resetAccountCaches()
	{
		journeyCache = null;
		journeyFetching = false;
		detailTask = -1;
		leftBehindSource = null;
		leftBehindItem = null;
		grindsCache = null;
		grindsFetching = false;
		board.forget();
		detailItem = null;
		detailSource = null;
		detailStack.clear();
		drillShown.clear();
		histListShown.clear();
		openFolds.clear();
		signatureItems.clear();
		scaledIcons.clear();
		board.gatherHistory();
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
			showTask(at);
		}
	}

	void showTask(int at)
	{
		detailTask = at;
		rebuild();
	}

	private void showLeftBehind(String source, String item)
	{
		leftBehindSource = source;
		leftBehindItem = item;
		rebuild();
	}

	void openSlayer(String lens)
	{
		slayerLens = lens;
		applyTab(View.SLAYER);
	}

	private JPanel buildSlayer()
	{
		JPanel p = column();
		ChronicleEventCapture.SlayerView task = plugin.slayerView();
		if (task != null)
		{
			addTaskCard(p, task, "Current task", ACCENT);
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
				boolean moved = Board.journeyMoved(journeyCache, j);
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
		long[] ms = board.windowMs();
		final List<BagItem> bag = plugin.onTaskLoot(ms[0], ms[1], lootTask,
			period.whole());
		if (bag.isEmpty())
		{
			p.add(taskPicker());
			return noted(p, lootTask != null
				? board.inside("No loot logged on " + lootTask)
				: period.whole()
					? "No task loot in the journal yet. It collects as tasks close."
					: board.inside("No task loot"));
		}
		final long[] sum = board.tallyOf(bag);
		final long qty = sum[0];
		final long value = sum[1];

		if (lootKind != null)
		{
			p.add(taskPicker());
			return kindDrill(p, bag, "task:");
		}
		final long[] tally = plugin.onTaskTally(ms[0], ms[1], lootTask, period.whole());
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
		spaced(page, bagCard(title, bag, sum));
		if (kinds)
		{
			addKindRows(page, bag);
		}
		else
		{
			addBagRows(page, bag);
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
			spaced(page, row("Task", lootTask, ACCENT), 4);
		}
		addKindRows(page, bag);
		return page;
	}

	JPanel moreRow(long remaining, Runnable reveal)
	{
		return moreRow("Show " + fmt(remaining) + " more", reveal);
	}

	JPanel moreRow(String label, Runnable reveal)
	{
		JPanel more = ghostRow(label, "");
		link(more, reveal);
		return more;
	}

	private Runnable relens(Runnable change)
	{
		return () ->
		{
			change.run();
			lootKind = null;
			rebuildInPlace();
		};
	}

	private void more(JPanel p, int size, int cap, boolean inset, IntConsumer show)
	{
		if (size > cap)
		{
			JPanel more = moreRow(size - cap, () ->
			{
				show.accept(size);
				rebuildInPlace();
			});
			p.add(inset ? nested(more) : more);
		}
	}

	void drillMore(JPanel p, String key, int size, int cap)
	{
		more(p, size, cap, false, n -> drillShown.put(key, n));
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

	private JPanel bagCard(String title, List<BagItem> bag, long[] sum)
	{
		JPanel head = tallyCard(title, "Items", fmt(sum[0]), ACCENT, sum[1]);
		head.add(row("Distinct items", fmt(bag.size())));
		return head;
	}

	private JPanel backToKinds(int held)
	{
		return backRow("< All kinds", count(held, "item"), () ->
		{
			lootKind = null;
			rebuildInPlace();
		});
	}

	private void addBagRows(JPanel p, List<BagItem> bag)
	{
		for (BagItem b : bag)
		{
			JPanel r = row(named(b.name, b.qty),
				b.value > 0 ? gps(b.value) : "");
			link(r, () -> openItem(b.name));
			p.add(r);
		}
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
			if (Board.UNFILED.equals(lootKind) ? k == null : lootKind.equals(k))
			{
				kept.add(b);
			}
		}
		return kept;
	}

	private JPanel onTaskHead(long qty, long value, long[] tally)
	{
		JPanel head = tallyCard("On-task loot", "Items", fmt(qty), ACCENT, value);
		head.add(row("Tasks", fmt(tally[2])));
		if (tally[0] > 0)
		{
			head.add(row("Kills logged", fmt(tally[0])));
		}
		if (tally[1] > 0)
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
			styled(take, small(), DIM);
			take.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			take.setToolTipText(tip);
		}
		return take;
	}

	void reportCopy(JLabel take, boolean ok)
	{
		take.setText(ok ? "copied" : "cannot copy");
		take.setForeground(ok ? ACCENT : ColorScheme.PROGRESS_ERROR_COLOR);
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

	JPanel copyHeaderLater(String title, Consumer<JLabel> copy)
	{
		JPanel r = row(title, "copy");
		styled(part(r, BorderLayout.CENTER), small(), ACCENT);
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
		List<Entry<String, Long>> kcs = new ArrayList<>(LocalStore.killLogCounts(board.clogNow()).entrySet());
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
		drillMore(card, "killlog", kcs.size(), cap);
		p.add(card);
		return p;
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
		link(bestRow, () -> showTask(at));
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
		head.add(row("Kills logged", kills, ACCENT));
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
		JPanel all = row("All kills of " + t.task, "", ACCENT, true);
		link(all, () ->
		{
			detailTask = -1;
			openSourceLoose(t.task);
		});
		p.add(all);
		return p;
	}

	private JPanel buildLeftBehindDetail()
	{
		JPanel p = column();
		p.add(backRow("< Back", "", () -> showLeftBehind(null, null)));
		p.add(vgap(4));

		if (leftBehindSource != null)
		{
			List<BagItem> bag = plugin.untakenItemsOf(leftBehindSource);
			UntakenRow left = find(plugin.untakenSources(), u -> u.name, leftBehindSource, true);
			spaced(p, tallyCard(leftBehindSource.toUpperCase(Locale.ROOT), "Left on the floor",
				count(left == null ? 0 : left.qty, "item"), RED, left == null ? 0 : left.value));
			if (bag.isEmpty())
			{
				return noted(p, "The count above is older than the itemised record. "
					+ "What this source leaves behind is listed here from now on.");
			}
			p.add(group("Declined"));
			for (BagItem b : bag)
			{
				JPanel r = row(named(b.name, b.qty),
					gps(b.value), RED);
				link(r, () -> showLeftBehind(null, b.name));
				p.add(r);
			}
			return p;
		}

		List<UntakenRow> sources = plugin.untakenSourcesOf(leftBehindItem);
		UntakenRow held = find(plugin.untakenItems(), u -> u.name, leftBehindItem, true);
		spaced(p, tallyCard(leftBehindItem.toUpperCase(Locale.ROOT), "Left behind",
			"×" + fmt(held == null ? 0 : held.qty), RED, held == null ? 0 : held.value));
		if (sources.isEmpty())
		{
			return noted(p, "No source itemised for this yet.");
		}
		p.add(group("Left where"));
		for (UntakenRow r : sources)
		{
			JPanel row = row(r.name, "×" + qtyGp(r.qty, r.value), RED);
			link(row, () -> showLeftBehind(r.name, null));
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
			if (board.insideWindow((long) (t.ts * 1000)) && (period.whole() || !t.inProgress))
			{
				shown.add(t);
				where.add(i);
			}
		}
		if (shown.isEmpty() && !period.whole())
		{
			p.add(note(board.inside("No tasks closed")));
			return;
		}
		long tasksDone = j.completedTasks;
		long killsOnTask = j.totalKills;
		long onTaskLoot = j.totalValueGp;
		if (!period.whole())
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
		head.add(row("Tasks done", fmt(tasksDone), ACCENT));
		head.add(row("Kills on task", fmt(killsOnTask)));
		head.add(row("On-task loot", gps(onTaskLoot)));
		if (j.totalXpEst > 0)
		{
			head.add(row("Slayer xp (est.)", gp(j.totalXpEst)));
		}
		spaced(p, head);
		for (int k = 0; k < Math.min(shown.size(), slayerShown); k++)
		{
			SlayerTask t = shown.get(k);
			final int at = where.get(k);
			String kills = t.inProgress && t.assignment > t.kills
				? fmt(t.kills) + " / " + fmt(t.assignment)
				: count(t.kills, "kill");
			if (t.noLootKills > 0)
			{
				kills += " · " + fmt(t.noLootKills) + " no-drop";
			}
			listCard(p, row(t.task, t.totalValue > 0 ? gps(t.totalValue) : "", ACCENT, t.inProgress),
				kills, t.ts > 0 ? day((long) (t.ts * 1000)) : "", () -> showTask(at));
		}
		if (shown.size() > slayerShown)
		{
			more(p, shown.size(), slayerShown, false, n -> slayerShown = n);
			p.add(vgap(4));
		}
	}

	final Map<String, Integer> drillShown = new LinkedHashMap<>();

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
		journal.calendarMonth = YearMonth.from(period.cursor);
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

	void openSourceLoose(String name)
	{
		openSource(board.resolveSource(name));
	}

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

	JPanel backRow(String label, String right, Runnable go)
	{
		JPanel r = row(label, right);
		styled(part(r, BorderLayout.CENTER), small(), ACCENT);
		link(r, go);
		return r;
	}

	JPanel backRow()
	{
		return backRow(null);
	}

	JPanel backRow(BooleanSupplier copy)
	{
		JPanel r = backRow("< Back", copy == null ? "" : "copy", this::backDetail);
		JLabel take = copy == null ? null : copyLabel(r, "Copy this page as a picture");
		if (take != null)
		{
			take.addMouseListener(clicker(() -> reportCopy(take, copy.getAsBoolean())));
		}
		return r;
	}

	private boolean drawingCopy;

	boolean copyPage(Supplier<JPanel> page)
	{
		drawingCopy = true;
		try
		{
			return copyPicture(stripChrome(page.get()));
		}
		catch (Throwable ignored)
		{
			return false;
		}
		finally
		{
			drawingCopy = false;
		}
	}

	private JPanel buildItemDetail(String name)
	{
		JPanel p = column();
		long got = 0;
		long worth = 0;
		int found = 0;
		final List<Object[]> srcs = new ArrayList<>();
		for (SourceRow r : board.sources())
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
		final long[] inWindow = period.whole() ? null : Board.rowOf(board.lootWindow().items, name);
		long other = 0;
		long otherValue = 0;
		if (inWindow != null)
		{
			srcs.clear();
			other = inWindow[0];
			otherValue = inWindow[1];
			String[] headRow = Board.rowFor(board.lootWindow().items, name, false);
			String spelled = headRow != null ? headRow[0] : name;
			for (Map.Entry<String, List<BagItem>> e : board.periodItems().entrySet())
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
		spaced(p, backRow(() -> copyPage(() -> buildItemDetail(name))), 4);
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
		final boolean hasTask = board.taskItemsEver().containsKey(properName(name));
		long[] tw = board.windowMs();
		long[] mine = inWindow == null ? board.taskItemsEver().get(properName(name))
			: plugin.onTaskItems(tw[0], tw[1]).getOrDefault(properName(name), new long[2]);
		if (hasTask && onTaskOnly)
		{
			head.add(row("Obtained on task", "×" + fmt(mine[0]), ACCENT));
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
			head.add(row("Obtained", "×" + fmt(qty), ACCENT));
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
			hold.add(toggle(onTaskOnly ? "On task" : "All", relens(() -> onTaskOnly = !onTaskOnly)));
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
		final int srcCap = Math.max(drawingCopy ? COPY_MOST : 40, drillShown.getOrDefault("item:src:" + name, 0));
		for (Object[] s : firstN(srcs, srcCap))
		{
			JPanel r = row((String) s[0], "×" + fmt((long) s[1])
				+ tail((long) s[2]));
			final String src = (String) s[0];
			link(r, () -> openSource(src));
			p.add(r);
		}
		drillMore(p, "item:src:" + name, srcs.size(), srcCap);
		addOther(p, "×" + fmt(Math.max(0, other)) + tail(Math.max(0, otherValue)), other > 0 || otherValue > 0);
		return p;
	}

	private String lootSince()
	{
		long from = plugin.lootDetailFrom();
		Window w = board.window();
		return period.session() || from > 0 && from <= startMs(w.start) ? null
			: from <= 0 ? Board.UNDATED
			: !drawingCopy ? "Loot since " + dayOf(from).format(FULL_DAY)
			: "Loot is dated for " + (dayOf(from).isAfter(w.end) ? "none" : "only part") + " of "
			+ board.periodInSentence() + ".";
	}

	private String sinceLine(String since)
	{
		return since != null || period.whole() || !drawingCopy ? since
			: "The figures above are " + board.periodInSentence() + "'s.";
	}

	private JPanel nothing(JPanel p, String what, String since)
	{
		return since != null ? p : noted(p, board.inside("Nothing " + what));
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
		String key = keyOf(board.taskItemsEver().keySet(), typed);
		return key == null ? typed : key;
	}

	private JPanel byTaskRows(JPanel p, String name)
	{
		long[] w = board.windowMs();
		List<Object[]> split = plugin.onTaskItemByTask(name, w[0], w[1]);
		if (split.isEmpty())
		{
			return noted(p, board.inside("No task paid this"));
		}
		p.add(group("By task"));
		final int taskCap = Math.max(drawingCopy ? COPY_MOST : 40, drillShown.getOrDefault("item:task:" + name, 0));
		for (Object[] t : firstN(split, taskCap))
		{
			p.add(row("Task: " + t[0], "×" + fmt((long) t[1]) + tail((long) t[2])));
		}
		drillMore(p, "item:task:" + name, split.size(), taskCap);
		return p;
	}

	private void addKillSources(JPanel head, SourceRow sr, long headline)
	{
		if (headline < 0)
		{
			return;
		}
		String want = LocalStore.chatKind(sr.name);
		Map<String, Long> rows = new LinkedHashMap<>();
		putKind(rows, "Kill Log", LocalStore.killLogCounts(board.clogNow()), want);
		putKind(rows, "Said in chat", plugin.chatKills(), want);
		putKind(rows, "Collection log", LocalStore.pageKillLines(board.clogNow()), want);
		putKind(rows, "Running count", plugin.anchoredKills(), want);
		if (sr.loots > 0)
		{
			rows.put("Drops logged", (long) sr.loots);
		}
		Long dropped = board.taskKillsEver().get(sr.name);
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
		if (!foldOpen(key))
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

	private void addAssignments(JPanel p, String npc)
	{
		long[] w = board.windowMs();
		List<LocalStore.Assignment> was = plugin.onTaskAssignments(npc, w[0], w[1]);
		if (was.isEmpty())
		{
			return;
		}
		p.add(group("Killed on task"));
		int cap = drillShown.getOrDefault("ontask:src:" + npc, ROW_CAP);
		for (LocalStore.Assignment a : firstN(was, cap))
		{
			p.add(row("Task: " + a.task, fmt(a.killsHere)));
		}
		drillMore(p, "ontask:src:" + npc, was.size(), cap);
		p.add(vgap(6));
	}

	private void addFloorRow(JPanel head, String name)
	{
		for (UntakenRow u : plugin.untakenSources())
		{
			if (u.qty > 0 && u.name.equalsIgnoreCase(name))
			{
				JPanel r = row("Left behind", qtyGp(u.qty, u.value)
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

	private JPanel buildSourceDetail(String name)
	{
		JPanel p = column();
		final SourceRow sr = find(board.sources(), r -> r.name, name, false);
		String[] row = period.whole() ? null : Board.rowFor(board.lootWindow().sources, sr != null ? sr.name : name, sr != null);
		long[] inWindow = period.whole() ? null
			: row == null ? new long[2] : new long[]{safeParse(row[1]), safeParse(row[2])};
		String own = row != null ? row[0] : sr != null ? sr.name : name;
		final List<BagItem> bag = inWindow == null ? plugin.sourceItems(own)
			: new ArrayList<>(board.periodItems().getOrDefault(own, new ArrayList<>()));
		bag.sort(Comparator.comparingLong((BagItem b) -> b.value).reversed());
		final long other = inWindow == null ? 0 : inWindow[1] - board.tallyOf(bag)[1];
		final boolean unfiled = other > 0 || inWindow != null && !period.session()
			&& plugin.unfiledSources(board.window().start, board.window().end).contains(own);
		spaced(p, backRow(() -> copyPage(() -> buildSourceDetail(name))), 4);
		JPanel head = card(name);
		if (sr != null)
		{
			boolean killed = board.isKillSource(sr.name);
			long shown = inWindow != null ? inWindow[0]
				: killed ? board.standingKills(sr) : sr.loots;
			head.add(row(killed ? "Kills" : "Times looted", fmt(shown), ACCENT));
			long worth = inWindow != null ? inWindow[1] : sr.value;
			long over = inWindow != null ? inWindow[0] : sr.loots;
			head.add(row("Worth", gps(worth)
				+ (over > 0 ? " · " + perOne(worth, over, killed) : "")));
			if (inWindow == null)
			{
				addKillSources(head, sr, killed ? shown : -1);
				addFloorRow(head, sr.name);
			}
			for (Entry<String, Long> pbLine : board.pageLines(sr.name, "pb_lines"))
			{
				head.add(row(pbLine.getKey(), clock(pbLine.getValue())));
			}
			for (Entry<String, Long> ln : board.logLines(sr.name))
			{
				head.add(row(ln.getKey(), fmt(ln.getValue())));
			}
			if (sr.pb != null)
			{
				JsonObject rec = board.records().get(low(sr.name));
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
				: board.lootWindow().times.getOrDefault(own, new double[2]);
			if (timed[0] > 0)
			{
				head.add(row("Average kill", pb(timed[1] / timed[0]) + " · "
					+ fmt((long) timed[0]) + " timed"));
			}
			long here = board.minutesAt(sr.name, inWindow == null ? board.counters() : board.periodCounters());
			if (here > 0 && board.minutesCoverPeriod())
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
							g.percentileDry >= 90 ? RED : null));
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
			int cap = drawingCopy ? COPY_MOST : drillShown.getOrDefault(name, 25);
			addBagRows(p, firstN(bag, cap));
			if (bag.size() > cap)
			{
				p.add(vgap(3));
			}
			drillMore(p, name, bag.size(), cap);
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
		for (JsonObject e : plugin.feedNewest(Board.FEED_SCAN_DEEP))
		{
			if ("COLLECTION".equals(typeOf(e)) && board.insideWindow(safeLong(e.get("ts"))))
			{
				got.add(e);
			}
		}
		if (got.isEmpty())
		{
			return noted(p, board.inside("Nothing new was logged"));
		}
		JPanel head = card("Collection log");
		head.add(row("Slots logged", fmt(got.size()), ACCENT));
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
		if (!period.whole())
		{
			return logInWindow(p);
		}
		int[] standing = board.clogStanding();
		int fin = plugin.clogFinished();
		JPanel head = card("Collection log");
		if (standing != null)
		{
			head.add(row(fmt(standing[0]) + " / " + fmt(standing[1]),
				Math.round(100f * standing[0] / standing[1]) + "%", ACCENT));
			head.add(progress((float) standing[0] / standing[1]));
		}
		else if (fin > 0)
		{
			head.add(row("Slots obtained", fmt(fin), ACCENT));
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
			pills.add(pill(tab, tab.equals(clogTab), 4, board.tabStanding(board.clogNow(), tab), () ->
			{
				clogTab = tab;
				clogPageSel = null;
				rebuild();
			}));
		}
		spaced(p, pills);

		JsonObject cl = board.clogNow();
		Obtained ob = Board.obtained(cl);
		Map<String, Long> kcs = Board.pageCounts(cl);

		Map<String, List<String>> pages = tax.getOrDefault(clogTab, new LinkedHashMap<>());
		for (Entry<String, List<String>> pg : pages.entrySet())
		{
			String page = pg.getKey();
			List<String> slots = pg.getValue();
			boolean[] lit = Board.lightSlots(slots, ob.byPage.get(low(page)), ob.all,
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
				complete ? GREEN : null, complete);
			String lines = Board.pageHeaderTip(cl, page);
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
					? board.petsByName() : Collections.emptyMap();
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
				Map<String, Long> landed = board.landedSlots();
				for (int i = 0; i < slots.size(); i++)
				{
					String slot = slots.get(i);
					JPanel r = row(slot, "",
						lit[i] || known.get(low(slot)) != null
							? GREEN : RED, true);
					Long when = landed.get(low(slot));
					if (when != null)
					{
						r.setToolTipText(tip(slot, "Landed", dated(when)));
					}
					drill.add(r);
					List<JPanel> d = detail.get(i);
					if (d.isEmpty())
					{
						continue;
					}
					String foldKey = "pets:" + page + ":" + low(slot);
					folds(r, foldKey);
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
		StringBuilder sb = new StringBuilder(pct(chase.percentileDry, "Under ", "Over ") + " of players have " + chase.pet
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
					line.append(skill(pet.source.toUpperCase(Locale.ROOT)) != null
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
				chase.percentileDry >= 90 ? RED : null);
			out.add(tipped(r, chaseTip(chase)));
		}
		return out;
	}

	private static String holdShare(GrindBook.PetChase chase)
	{
		return pct(chase.percentileDry, "<", ">") + " have";
	}

	private final Set<String> openFolds = new HashSet<>();

	private static final String FOLD_HOME_XP = "home:xp";
	private static final String FOLD_HOME_DAMAGE = "home:damage";
	private static final List<String> DAMAGE_SPLIT = Arrays.asList(
		"damageDealtMelee", "damageDealtRanged", "damageDealtMagic");

	boolean foldOpen(String key)
	{
		return openFolds.contains(key);
	}

	boolean foldOpen(String key, boolean byDefault)
	{
		return openFolds.contains(key) != byDefault;
	}

	private JPanel folds(JPanel head, String key)
	{
		link(head, () -> toggleFold(key));
		return head;
	}

	private void toggleFold(String key)
	{
		if (!openFolds.remove(key))
		{
			openFolds.add(key);
		}
		rebuildInPlace();
	}

	void rebuildInPlace()
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

	JPanel subHead(String label, String totalStr, String stateKey)
	{
		JPanel head = row(label, totalStr);
		styled(part(head, BorderLayout.CENTER), small(), DIM);
		head.setBorder(pad(3, 10, 1, 2));
		return folds(head, stateKey);
	}

	private static final int HIST_LIST_CAP = 6;
	private final Map<String, Integer> histListShown = new LinkedHashMap<>();

	private int shownCap(String key)
	{
		return histListShown.getOrDefault(key, HIST_LIST_CAP);
	}

	private void addMore(JPanel card, String key, int size, int cap, boolean inset)
	{
		more(card, size, cap, inset, n -> histListShown.put(key, n));
	}

	private static String countersSince(
		SortedMap<LocalDate, Baseline> spine,
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

	private final Map<String, Integer> signatureItems = new LinkedHashMap<>();

	private static final int ICON_W = 22;
	private static final int ICON_H = 18;

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
		total.setToolTipText(periodTip != null ? periodTip.replace("</body>", dimLine("Opens the records") + "</body>")
			: tip("Total level", "Opens", "the records"));
		link(total, this::openRecords);
		if (period.whole())
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
		boolean climbed = !period.whole() && was != null && cb > was;
		JLabel fig = new JLabel(cb > 0 ? (climbed ? climb(was, cb) : fmt(cb)) : "-",
			JLabel.RIGHT);
		fig.setFont(small());
		fig.setForeground(cb > 0 && (period.whole() || combatSkillsMoved(gain))
			? LIT : DIM);
		cell.add(fig, BorderLayout.EAST);
		Map<String, Long> c = board.counters();
		long[] ca = board.combatStanding();
		cell.setToolTipText(tip("Combat",
			"Achievement points", ca[1] > 0 ? fmt(ca[0]) + " / " + fmt(ca[1]) : fmt(ca[0]),
			"Tiers unlocked", fmt(ca[2]) + " / 6",
			"Damage dealt", fmt(c.getOrDefault(StatKeys.DAMAGE_DEALT, 0L)),
			"Highest hit", fmt(c.getOrDefault(StatKeys.HIGHEST_HIT, 0L))));
		link(cell, () ->
		{
			sheetPage = "combat";
			rebuild();
		});
		return cell;
	}

	private long activityMoved(String label, String source)
	{
		if (period.whole())
		{
			return -1;
		}
		if ("Collections".equals(label))
		{
			return board.stirred("COLLECTION");
		}
		if ("Quests".equals(label))
		{
			return board.stirred("QUEST");
		}
		if ("Diaries".equals(label))
		{
			return board.stirred("DIARY");
		}
		if ("Clues".equals(label))
		{
			long all = 0;
			for (String tier : CLUE_TIERS)
			{
				all += board.rolled("Clue Scroll (" + tier + ")");
			}
			return all;
		}
		if (!source.isEmpty())
		{
			long rolled = board.rolled(source);
			return rolled > 0 && board.namedLine(source, label) > 0 ? -1 : rolled;
		}
		return 0;
	}

	private String skillTip(String craft)
	{
		Map<String, Long> now = board.periodCounters();
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
		List<String> lines = new ArrayList<>();
		for (String key : floors)
		{
			lines.add(StatRegistry.rowLabel(key));
			lines.add(fmt(now.get(key)));
		}
		for (Entry<String, Long> e : named)
		{
			if (lines.size() >= 12)
			{
				break;
			}
			lines.add(StatRegistry.rowLabel(e.getKey()));
			lines.add(fmt(e.getValue()));
		}
		return tip(craft, lines);
	}

	private String slayerTip()
	{
		long[] tally = board.taskTally();
		return tip("Slayer",
			"Tasks tracked", fmt(tally[2]),
			"Kills on task", fmt(tally[0]),
			"On-task loot", gps(tally[3]));
	}

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

	private void buildClues(JPanel p)
	{
		long all = 0;
		long allWorth = 0;
		List<SourceRow> mine = new ArrayList<>();
		for (String tier : CLUE_TIERS)
		{
			SourceRow r = board.clue(tier);
			if (r != null)
			{
				mine.add(r);
				all += Math.max(r.kc, r.loots);
				allWorth += r.value;
			}
		}
		JPanel head = card("Clues");
		head.add(row("Caskets opened", fmt(all), ACCENT));
		head.add(row("Worth", gps(allWorth), ACCENT));
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
			SourceRow r = board.clue(tier);
			if (r == null)
			{
				p.add(row(tier, "-", DIM, true));
				continue;
			}
			long n = Math.max(r.kc, r.loots);
			JPanel line = row(tier, qtyGp(n, r.value), ACCENT);
			final String open = r.name;
			link(line, () -> openSource(open));
			line.setToolTipText(tip(tier + " clues",
				"Caskets", fmt(n),
				"Worth", gps(r.value),
				"Each", n > 0 ? gps(r.value / n) : "-"));
			p.add(line);
		}
	}

	private void buildQuests(JPanel p)
	{
		JsonObject q = obj(board.achievements(), "quests");
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
		head.add(row("Complete", fmt(done.size()) + " / " + fmt(q.size()), ACCENT));
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
		boolean open = foldOpen(foldKey, openByDefault);
		p.add(quietHead(heading, fmt(names.size()), foldKey));
		if (!open)
		{
			p.add(vgap(4));
			return;
		}
		for (String n : names)
		{
			p.add(row(n, "", held ? null : DIM, !held));
		}
		p.add(vgap(4));
	}

	private void buildDiaries(JPanel p)
	{
		JsonObject tasks = DIARY_TASKS;
		JsonObject mine = obj(board.achievements(), "diaries");
		boolean known = mine.size() > 0;
		JPanel head = card("Achievement diaries");
		if (known)
		{
			long[] d = board.diaryStanding();
			head.add(row("Tiers done", d[0] + " / " + d[1], ACCENT));
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
					known && !got ? DIM : null,
					known && !got);
				line.setToolTipText(taskTip(region + " " + tier,
					tiers.getAsJsonArray(tier)));
				p.add(line);
			}
			p.add(vgap(4));
		}
	}

	private static String taskTip(String title, JsonArray tasks)
	{
		final int cap = 8;
		StringBuilder sb = new StringBuilder(TIP_OPEN).append(dimLine(title));
		for (int i = 0; i < tasks.size() && i < cap; i++)
		{
			JsonObject t = tasks.get(i).getAsJsonObject();
			sb.append("<div>").append(clip(t.get("task").getAsString(), 78)).append("</div>");
			String needs = t.has("requirements") ? t.get("requirements").getAsString() : "";
			if (!needs.isEmpty())
			{
				sb.append(dimLine("&nbsp;&nbsp;" + clip(needs, 70)));
			}
		}
		if (tasks.size() > cap)
		{
			sb.append(dimLine("and " + (tasks.size() - cap) + " more"));
		}
		return sb.append(TIP_CLOSE).toString();
	}

	private void buildCombatAchievements(JPanel p)
	{
		JsonObject all = CA_TASKS;
		long[] c = board.combatStanding();
		JPanel head = card("Combat achievements");
		head.add(row("Points", c[1] > 0 ? fmt(c[0]) + " / " + fmt(c[1]) : fmt(c[0]),
			ACCENT));
		head.add(row("Tiers unlocked", fmt(c[2]) + " / 6"));
		Set<Integer> headDone = board.caDone();
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
			head.add(row("Tasks done", fmt(named) + " / " + fmt(all.size()), ACCENT));
		}
		spaced(p, head);
		if (unnamed > 0)
		{
			spaced(p, note("You have also done " + count(unnamed, "combat achievement")
				+ " added to the game since this copy of"
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
				: count(n, "task"), foldKey));
			if (!open)
			{
				continue;
			}
			for (JsonObject task : e.getValue())
			{
				boolean has = known && done.contains(task.get("id").getAsInt());
				JPanel line = row(withoutSource(task.get("name").getAsString(), e.getKey()),
					prettyTier(task.get("tier").getAsString()),
					known ? (has ? GREEN : RED) : null, known);
				line.setToolTipText(tip(task.get("name").getAsString(),
					"Tier", task.get("tier").getAsString(),
					"Where", caSource(task.get("monster").getAsString()),
					"Task", task.get("task").getAsString()));
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
		long xp = sumOf(gains);
		return tip(period.session() ? "This sitting"
			: period.whole() ? "Lifetime" : "The period",
			"Time played", hoursMinutes(played[0]),
			"Sessions", fmt(played[1]),
			"Experience", "+" + gp(xp));
	}

	private JPanel totalLevelTile(SkillStand stand, HistoryLog.Levels opened)
	{
		HistoryLog.Levels shut = stand.closed;
		boolean paired = !period.whole() && opened != null && shut.drawn == opened.drawn;
		long levels = paired ? shut.total - opened.total : 0;
		String figure = fmt(stand.standing);
		if (levels > 0)
		{
			figure = (stand.standing == shut.total ? climb(opened.total, stand.standing) : figure)
				+ " · +" + fmt(levels);
		}
		JPanel cell = levelTile("Total level");
		if (periodTip != null)
		{
			cell.setToolTipText(periodTip);
		}
		JLabel fig = new JLabel(figure, JLabel.RIGHT);
		fig.setFont(small());
		fig.setForeground(levels > 0 ? ACCENT
			: period.whole() ? Color.WHITE : DIM);
		cell.add(fig, BorderLayout.EAST);
		return cell;
	}

	private JPanel skillCell(Skill sk, long level, Long gained, Long from)
	{
		JPanel cell = tile(3, 4);
		final String craft = prettify(low(sk.name()));
		boolean slayer = Skill.SLAYER.equals(sk);
		cell.setToolTipText(slayer ? slayerTip() : skillTip(craft));
		link(cell, slayer ? () -> openSlayer("Tasks") : () -> openSkill(craft));

		JLabel icon = new JLabel();
		BufferedImage img = skillIcon(sk);
		if (img != null)
		{
			icon.setIcon(new ImageIcon(img));
		}
		else
		{
			icon.setText(sk.name().substring(0, Math.min(3, sk.name().length())));
			styled(icon, small(), DIM);
		}
		cell.add(icon, BorderLayout.WEST);

		JPanel text = new JPanel(new GridLayout(gained != null ? 2 : 1, 1));
		text.setBackground(DARKER);
		boolean climbed = from != null && level > from && !period.whole();
		JLabel lvl = styled(new JLabel(level <= 0 ? "-"
			: climbed ? climb(from, level) : String.valueOf(level)), small(),
			gained != null ? Color.WHITE : DIM);
		text.add(lvl);

		if (gained != null)
		{
			text.add(styled(new JLabel((period.whole() ? "" : "+") + xpShort(gained)), small(), ACCENT));
		}
		cell.add(text, BorderLayout.CENTER);
		return cell;
	}

	private static final Map<Skill, BufferedImage> SKILL_ICONS =
		new EnumMap<>(Skill.class);

	BufferedImage skillIcon(Skill sk)
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

	private String periodTip;
	private String sheetPage;
	private String measuredSince;

	final Map<String, BufferedImage> art = new HashMap<>();
	private final Set<String> artAsked = new HashSet<>();
	private final Map<String, List<Object[]>> artWaiting = new LinkedHashMap<>();

	void wearSprite(JLabel label, int spriteId, int w, int h)
	{
		wear(label, "sprite:" + spriteId, w, h, done ->
		{
			SpriteManager sm = plugin.sprites();
			if (sm != null)
			{
				sm.getSpriteAsync(spriteId, 0, done);
			}
			return sm != null;
		});
	}

	private void wear(JLabel label, String key, int w, int h,
		Predicate<Consumer<BufferedImage>> fetch)
	{
		BufferedImage have = art.get(key);
		if (have != null)
		{
			dress(label, key + "@" + w, have, w, h);
			return;
		}
		artWaiting.computeIfAbsent(key, k -> new ArrayList<>()).add(new Object[]{label, w, h});
		if (!artAsked.add(key))
		{
			return;
		}
		try
		{
			if (!fetch.test(img -> SwingUtilities.invokeLater(() -> landed(key, img))))
			{
				artAsked.remove(key);
			}
		}
		catch (Throwable ignored)
		{
		}
	}

	private void landed(String key, BufferedImage img)
	{
		if (img == null)
		{
			return;
		}
		art.put(key, img);
		for (Object[] want : artWaiting.getOrDefault(key, Collections.emptyList()))
		{
			dress((JLabel) want[0], key + "@" + want[1], img, (Integer) want[1], (Integer) want[2]);
		}
		artWaiting.remove(key);
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

	private JPopupMenu periodMenu()
	{
		JPopupMenu menu = new JPopupMenu();
		for (String g : Period.NAMES)
		{
			menuItem(menu, g, g.equals(period.granularity) && period.from == null, () ->
			{
				period.choose(g, board.window().end);
				rebuildInPlace();
			});
		}
		menu.addSeparator();
		menuItem(menu, "Exact dates", period.from != null, () -> onSetExactDates(
			period.suggestedFrom(), period.suggestedTo()));
		return menu;
	}

	private void menuItem(JPopupMenu menu, String text, boolean on, Runnable go)
	{
		JMenuItem item = new JMenuItem(text);
		item.setFont(small());
		if (on)
		{
			item.setForeground(ACCENT);
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
		JPanel r = row("Task", lootTask == null ? "Every task" : lootTask, ACCENT);
		styled(part(r, BorderLayout.CENTER), small(), DIM);
		JLabel pick = part(r, BorderLayout.EAST);
		pick.setFont(small());
		pick.setToolTipText("Narrow this board to one task");
		link(pick, () -> taskMenu().show(r, 0, r.getHeight()));
		link(r, () -> taskMenu().show(r, 0, r.getHeight()));
		return r;
	}

	JPanel noPeriod()
	{
		return note(board.historySpine == null
			? "Reading your history..."
			: "Nothing closed inside " + board.periodInSentence() + ". A period is the distance "
				+ "between two baselines, and this window holds fewer than two.");
	}

	private JPanel periodRow()
	{
		final Window w = board.window();
		JPanel r = stepStrip();
		if (showingSitting())
		{
			return fixedPeriod(r, "This session");
		}
		if (!searchQuery().isEmpty())
		{
			return fixedPeriod(r, "Whole record");
		}
		if (period.steps())
		{
			arrows(r, () -> stepPeriod(-1), period.canStepForward(), () -> stepPeriod(1), null);
		}
		JLabel lbl = styled(new JLabel(w.label, JLabel.CENTER), FontManager.getRunescapeFont(),
			ACCENT);
		lbl.setToolTipText("Choose the period");
		link(lbl, () -> periodMenu().show(r, 0, r.getHeight()));
		r.add(lbl, BorderLayout.CENTER);
		return r;
	}

	void arrows(JPanel r, Runnable back, boolean ahead, Runnable forward, JLabel title)
	{
		JLabel b = new JLabel("<");
		JLabel fwd = new JLabel(">");
		for (JLabel arrow : new JLabel[]{b, fwd})
		{
			arrow.setFont(FontManager.getRunescapeBoldFont());
			arrow.setBorder(pad(0, 6, 0, 6));
		}
		b.setForeground(ACCENT);
		link(b, back);
		fwd.setForeground(ahead ? ACCENT : DIM);
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

	private void stepPeriod(int by)
	{
		period.step(by);
		rebuild();
	}

	private JPanel buildHistory()
	{
		JPanel p = column();
		Window periodWin = board.window();
		final LocalDate pStart = periodWin.start;
		final LocalDate pEnd = periodWin.end;
		final boolean live = !pEnd.isBefore(LocalDate.now());

		if (board.historySpine == null || !LocalDate.now().equals(board.historyDay)
			|| newestTs(plugin.feedNewest(1)) != board.historyFeedTs)
		{
			board.gatherHistory();
		}
		if (board.historySpine == null)
		{
			return noted(p, "Reading your history…");
		}
		TreeMap<LocalDate, Baseline> hist = board.historySpine;

		Entry<LocalDate, Baseline> before =
			hist.floorEntry(pStart.minusDays(1));
		Entry<LocalDate, Baseline> from =
			HistoryLog.windowStart(hist, pStart, pEnd);
		Entry<LocalDate, Baseline> at = hist.floorEntry(pEnd);
		if (at == null || from == null
			|| (at.getKey().equals(from.getKey()) && !board.closesOnTheClient(from, pStart, pEnd)))
		{
			String empty;
			if (hist.isEmpty())
			{
				empty = "The record starts today: baselines close at each login, "
					+ "day rollover and logout, and a period is the distance "
					+ "between two of them.";
			}
			else if (hist.firstKey().isBefore(pStart)
				&& ("Day".equals(period.granularity) || "Week".equals(period.granularity)))
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
			Map<String, Long> closesOn = live ? board.closingSkills(closing.skills, true) : closing.skills;
			List<Entry<String, Long>> gains = new ArrayList<>();
			for (Entry<String, Long> e : HistoryLog.gained(opening.skills,
				earliest.skills, closesOn, opening.complete).entrySet())
			{
				if (!"overall".equals(e.getKey()))
				{
					gains.add(e);
				}
			}
			if (period.session())
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

			long fromMs = period.session() ? board.windowMs()[0]
				: startMs(pStart);
			long toMs = period.session() ? board.windowMs()[1]
				: startMs(pEnd.plusDays(1));
			long[] played = {0, 0};
			long oldest = oldestTs(board.historyFeed, false);
			if ((oldest > 0 && oldest < fromMs) || period.whole())
			{
				for (JsonObject e : board.historyFeed)
				{
					long filed = filedAt(e);
					if (filed >= fromMs && filed < toMs && "SESSION".equals(typeOf(e)))
					{
						played[0] += sessionMinutes(e);
						played[1]++;
					}
				}
			}
			long began = plugin.sessionStart();
			if (period.session() ? live : (began > 0 && began >= fromMs && began < toMs))
			{
				long running = plugin.sessionElapsedMinutes();
				if (running > 0)
				{
					played[0] += running;
					played[1]++;
				}
			}
			if (period.whole())
			{
				played[0] = Math.max(played[0], plugin.gamePlaytimeMinutes());
			}

			Baseline sittingOpen = null;
			if (period.session())
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
				sittingOpen = Board.baselineAt(openXp);
				closing = Board.baselineAt(closesOn);
			}
			SkillStand stand = board.skillStand(closing, live);
			HistoryLog.Levels opened = sittingOpen != null
				? HistoryLog.levels(sittingOpen, stand.keys)
				: HistoryLog.levels(opening, stand.keys);
			periodTip = periodTip(played, gains);
			LocalDate lootSince = null;
			long lootFromTs = earliestDatedLoot(board.historyFeed, plugin.lootRollFrom());
			if (lootFromTs > 0)
			{
				LocalDate sat = dayOf(lootFromTs);
				if (sat.isAfter(pStart))
				{
					lootSince = sat;
				}
			}
			String since = period.whole() ? null
				: countersSince(hist.headMap(at.getKey(), true), from.getKey(),
					lootSince, lootFromTs > 0);
			if (since != null)
			{
				spaced(p, note(since), 5);
			}

			addSkillGrid(p, gains, stand, opened);
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
		LocalDate f = Period.parse(fromField.getText());
		LocalDate t = Period.parse(toField.getText());
		if (f == null || t == null)
		{
			JOptionPane.showMessageDialog(this,
				"Dates read as yyyy-mm-dd (or d/m/yyyy). Nothing changed.");
			return;
		}
		period.exact(f, t);
		rebuild();
	}

	void openJournalOn(long ts)
	{
		if (ts > 0)
		{
			period.day(ts);
		}
		openJournal("All");
	}

	void openJournal(String lens)
	{
		journal.journalLens = lens;
		applyTab(View.JOURNAL);
	}

	void promptImport()
	{
		JFileChooser fc = new JFileChooser();
		fc.setDialogTitle("Import a Chronicle journal");
		fc.setFileFilter(new FileNameExtensionFilter(
			"Chronicle journal (*.json)", "json"));
		if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
		{
			plugin.actionImport(fc.getSelectedFile());
		}
	}

	void openSheetPage(String page)
	{
		applyTab(Tab.STANDING);
		sheetPage = page;
		rebuild();
	}

	JPanel backPage()
	{
		JPanel p = column();
		spaced(p, backRow(), 4);
		return p;
	}

	private static final class ScrollColumn extends JPanel implements Scrollable
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
}
