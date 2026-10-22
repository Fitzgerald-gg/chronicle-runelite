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
import chronicle.Kit.ScrollColumn;
import chronicle.LocalStore.BagItem;
import chronicle.LocalStore.SlayerJourney;
import chronicle.LocalStore.SlayerTask;
import chronicle.LocalStore.SourceRow;
import chronicle.LocalStore.UntakenRow;
import chronicle.Period.Span;
import chronicle.counters.StatKeys;
import chronicle.panel.StatRegistry;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Rectangle;
import java.awt.event.HierarchyEvent;
import java.awt.event.MouseListener;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
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
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.ToolTipManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import lombok.RequiredArgsConstructor;
import net.runelite.api.Skill;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.IconTextField;
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.ui.components.materialtabs.MaterialTabGroup;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.ImageUtil;
import static chronicle.Copy.COPY_MOST;
import static chronicle.Copy.copyPicture;
import static chronicle.Copy.stripChrome;
import static chronicle.Feed.most;
import static chronicle.Feed.newestTs;
import static chronicle.Json.has;
import static chronicle.Json.keyOf;
import static chronicle.Json.low;
import static chronicle.Json.safeLong;
import static chronicle.Kit.ACCENT_LIFETIME;
import static chronicle.Kit.ACCENT_RED;
import static chronicle.Kit.DARK;
import static chronicle.Kit.DARKER;
import static chronicle.Kit.PANEL_INSET;
import static chronicle.Kit.ROW_CAP;
import static chronicle.Kit.card;
import static chronicle.Kit.clicker;
import static chronicle.Kit.column;
import static chronicle.Kit.copyLabel;
import static chronicle.Kit.day;
import static chronicle.Kit.dim;
import static chronicle.Kit.figure;
import static chronicle.Kit.ghostRow;
import static chronicle.Kit.link;
import static chronicle.Kit.listCard;
import static chronicle.Kit.nested;
import static chronicle.Kit.note;
import static chronicle.Kit.noted;
import static chronicle.Kit.overlayBar;
import static chronicle.Kit.pad;
import static chronicle.Kit.part;
import static chronicle.Kit.row;
import static chronicle.Kit.small;
import static chronicle.Kit.spaced;
import static chronicle.Kit.styled;
import static chronicle.Kit.sumOf;
import static chronicle.Kit.tallyCard;
import static chronicle.Kit.tip;
import static chronicle.Kit.unlight;
import static chronicle.Kit.value;
import static chronicle.Kit.vgap;
import static chronicle.Kit.wash;
import static chronicle.Kit.worthRow;
import static chronicle.LocalStore.kindOf;
import static chronicle.Period.FULL_DAY;
import static chronicle.Period.TASK_DAY;
import static chronicle.Period.dayOf;
import static chronicle.Period.startMs;
import static chronicle.Text.count;
import static chronicle.Text.dated;
import static chronicle.Text.fmt;
import static chronicle.Text.gp;
import static chronicle.Text.gps;
import static chronicle.Text.hoursMinutes;
import static chronicle.Text.named;
import static chronicle.Text.pb;
import static chronicle.Text.qtyGp;
import static chronicle.Text.rateText;
import static chronicle.Text.tail;

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

	final LocalStore store;
	final Live live;
	private final ScheduledExecutorService executor;
	final Gson gson;
	final SpriteManager sprites;
	final SkillIconManager skillIcons;
	final GrindBook grindBook;
	Build build;
	final SheetPage sheet = new SheetPage(this);
	final LedgerPage ledger = new LedgerPage(this);
	final LogPage clog = new LogPage(this);
	final NowPage now = new NowPage(this);
	final JournalPage journal = new JournalPage(this);
	final RecapPage recap = new RecapPage(this);
	final RecordsPage records = new RecordsPage(this);
	final SearchPage search = new SearchPage(this);
	final Art art = new Art(this);
	final ItemNames itemNames;
	final Search index;

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
	YearMonth calendarMonth = YearMonth.now();
	private final ArrayDeque<String[]> detailStack = new ArrayDeque<>();
	String statsFamily = StatRegistry.FAMILIES[0];
	private int dropsShown = ROW_CAP;
	String clogTab = "Bosses";
	String clogPageSel;

	private final JPanel band = new JPanel(new BorderLayout());
	private final JLabel bandText = new JLabel();
	private String histGranularity = "Lifetime";
	private LocalDate histCursor = LocalDate.now();
	private LocalDate histFrom;
	private LocalDate histTo;

	ChroniclePanel(LocalStore store, Live live, ScheduledExecutorService executor, Gson gson,
		SpriteManager sprites, SkillIconManager skillIcons)
	{
		super(false);
		this.store = store;
		this.live = live;
		this.executor = executor;
		this.gson = gson;
		this.sprites = sprites;
		this.skillIcons = skillIcons;
		grindBook = new GrindBook(gson);
		freshBuild();
		itemNames = new ItemNames(store, gson, n -> build.names().named(n));
		index = new Search(store);

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
		ToolTipManager.sharedInstance().setInitialDelay(220);
		ToolTipManager.sharedInstance().setDismissDelay(20_000);

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

	JLabel pill(String name, boolean on, int side, String tip, Runnable pick)
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

	static final String[][] ACTIVITIES = {
		{"Clues", "", "clues"},
		{"Rifts closed", "Guardians of the Rift", ""},
		{"Soul Wars", "Soul Wars", ""},
		{"Collections", "", "log"},
		{"Quests", "", "quests"},
		{"Diaries", "", "diaries"},
	};

	void openActivity(String source)
	{
		if (build.names().named(source) == null && openLogPage(source))
		{
			return;
		}
		openSourceLoose(source);
	}

	boolean openLogPage(String page)
	{
		String tab = build.book().tabOf(page);
		if (tab == null)
		{
			return false;
		}
		applyTab(Tab.STANDING);
		sheetPage = "log";
		clogTab = tab;
		clogPageSel = page;
		rebuild();
		return true;
	}

	Color accent()
	{
		return ACCENT_LIFETIME;
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

	private final AtomicBoolean queued = new AtomicBoolean();
	private final AtomicInteger movedMask = new AtomicInteger();

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
			case SHEET:
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
		movedMask.accumulateAndGet(moved, (x, y) -> x | y);
		if (!queued.compareAndSet(false, true))
		{
			return;
		}
		SwingUtilities.invokeLater(() ->
		{
			queued.set(false);
			int mask = movedMask.getAndSet(0);
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
			if (!viewCares(mask))
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
		freshBuild();
		resourcesDropped = 0;
		art.forgetWaiting();
		paintBand(store.journalWarning(), live.captureWarning(),
			live.captureWarningWhy());
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
			: detailSkill != null ? buildSkillDetail(detailSkill)
			: allTrackers ? ledger.buildAllTrackers()
			: showRecords ? records.buildRecords()
			: showCalendar ? records.buildCalendar()
			: showInfo ? records.buildInfo()
			: detailTask >= 0 ? buildTaskDetail(detailTask)
			: leftBehindSource != null || leftBehindItem != null ? buildLeftBehindDetail()
			: sheetPage != null ? sheet.buildSheetPage()
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
				return sheet.buildSheet();
			case DROPS:
				return buildDrops();
			case SLAYER:
				return buildSlayer();
			case STATS:
				return ledger.buildStats();
			case JOURNAL:
				return journal.buildJournal();
			case RECAP:
				return recap.buildRecap();
			case HOME:
			default:
				return now.buildHome();
		}
	}

	private JPanel aboveBoard;

	private void onSearchChanged()
	{
		drillShown.keySet().removeIf(k -> k.startsWith("search:"));
		rebuild();
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
		for (MouseListener l : band.getMouseListeners())
		{
			band.removeMouseListener(l);
		}
		if (bandFixes)
		{
			band.addMouseListener(clicker(() ->
			{
				live.turnOnMissingCapture();
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

	void foldHead(JPanel head, String fold, String tip)
	{
		JLabel name = part(head, BorderLayout.CENTER);
		if (foldOpen(fold))
		{
			name.setForeground(accent());
		}
		head.setToolTipText(tip);
		folds(head, fold);
	}

	JPanel quietHead(String name, String count, String stateKey)
	{
		JPanel head = subHead(name.toUpperCase(Locale.ROOT), count, stateKey);
		head.setBorder(pad(7, 2, 1, 2));
		return head;
	}

	private static final String UNDATED = "No loot has been dated yet. The roll keeps one entry a "
		+ "day and starts with the next drop that lands.";

	private JPanel dropsInWindow(JPanel p)
	{
		Period win = window();
		long rollFrom = build.lootDays().rollFrom();
		if (!win.session && rollFrom <= 0)
		{
			return noted(p, UNDATED);
		}
		if (!win.session && rollFrom > startMs(win.start))
		{
			LocalDate began = dayOf(rollFrom);
			return noted(p, "The dated loot roll begins " + began.format(FULL_DAY)
				+ ", which is inside " + window().inSentence() + ". Naming the part it can see "
				+ "as the whole period would be worse than saying nothing.");
		}
		LocalStore.LootWindow w = build.lootWindow();
		if (!dropsLeftBehind && dropsByKind)
		{
			if (w.items.isEmpty())
			{
				return noted(p, window().phrase("Nothing taken"));
			}
			return kindLens(p, win.label, bagOf(w.items), "win:");
		}
		List<LocalStore.Row> ranked = dropsLeftBehind ? w.leftItems : w.sources;
		if (ranked.isEmpty())
		{
			return noted(p, window().phrase("Nothing " + (dropsLeftBehind ? "left behind" : "taken")));
		}
		JPanel head = dropsLeftBehind ? tallyCard("Left behind", "Items", fmt(w.left), ACCENT_RED, w.leftValue)
			: tallyCard("Drops received", "Drops", fmt(w.loots), accent(), w.value);
		if (dropsLeftBehind)
		{
			head.add(row("Kills that left one", fmt(w.leftKills)));
		}
		spaced(p, head);
		final String key = dropsLeftBehind ? "win:left" : "win:source";
		final int cap = drillShown.getOrDefault(key, ROW_CAP);
		for (LocalStore.Row r : Sources.firstN(ranked, cap))
		{
			JPanel line = row(r.name, qtyGp(r.qty, r.value));
			final String name = r.name;
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

	private static List<BagItem> bagOf(List<LocalStore.Row> rows)
	{
		List<BagItem> bag = new ArrayList<>();
		for (LocalStore.Row r : rows)
		{
			bag.add(new BagItem(0, r.name, r.qty, r.value));
		}
		return bag;
	}

	private boolean onTaskOnly;

	boolean everOnTask()
	{
		return !build.taskItems().isEmpty();
	}


	boolean dropsLeftBehind;
	private String lootKind;
	private String lootTask;

	private JPanel buildDrops()
	{
		JPanel p = column();
		List<JPanel> axes = new ArrayList<>();
		axes.add(toggle(dropsLeftBehind ? "Left behind" : "Received", relens(() -> dropsLeftBehind = !dropsLeftBehind)));
		if (!dropsLeftBehind || wholeRecord())
		{
			axes.add(toggle(dropsByKind
				? (dropsLeftBehind ? "By item" : "By kind") : "By source", relens(() -> dropsByKind = !dropsByKind)));
		}
		final boolean canAskOnTask = !dropsLeftBehind && dropsByKind && everOnTask();
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
			long[] w = window().ms();
			List<BagItem> taskBag = store.tasks(w[0], w[1], null, wholeRecord()).loot();
			if (taskBag.isEmpty())
			{
				return noted(p, window().phrase("No task closed"));
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
		List<SourceRow> sources = new ArrayList<>(build.sources());
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
		JPanel lifeHead = tallyCard("Drops received", "Drops", fmt(everyDrop), accent(), everyValue);
		lifeHead.add(row("Sources", fmt(sources.size())));
		spaced(p, lifeHead);
		for (SourceRow r : Sources.firstN(sources, dropsShown))
		{
			boolean killed = build.kills.isKillSource(r.name);
			String sub = (killed ? fmt(build.kills.standingKills(r)) + " kc" : count(r.loots, "drop"))
				+ (r.pb != null ? " · PB " + pb(r.pb) : "");
			listCard(p, row(r.name, gps(r.value), accent()), sub,
				r.loots > 0 ? perOne(r.value, r.loots, killed) : "", () -> openSource(r.name));
		}
		more(p, sources.size(), dropsShown, false, n -> dropsShown = n);
		return p;
	}

	private boolean dropsByKind;

	private static String perOne(long value, long n, boolean killed)
	{
		return gp(value / Math.max(1, n)) + (killed ? " gp/drop" : " gp each");
	}

	private JPanel buildLootByKind(JPanel p)
	{
		final List<BagItem> bag = store.allLoot();
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
			JPanel r = row(k.name, qtyGp(k.qty, k.value), accent());
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
		final long[] sum = LocalStore.tallyOf(bag);
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
		final long[] mine = LocalStore.tallyOf(kept);
		spaced(p, bagCard(lootKind, kept, mine));
		p.add(backToKinds(kept.size()));
		if (kept.isEmpty())
		{
			return noted(p, "Nothing of this kind here.");
		}
		p.add(copyHeader(lootKind, () -> copyPicture(
			lootPicture(lootKind, kept, mine, false), true)));
		int cap = drillShown.getOrDefault(key + lootKind, ROW_CAP);
		addBagRows(p, Sources.firstN(kept, cap));
		drillMore(p, key + lootKind, kept.size(), cap);
		return p;
	}

	private JPanel buildLeftBehind(JPanel p)
	{
		List<UntakenRow> rows = store.untaken().sources;
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
		List<UntakenRow> items = store.untaken().items;
		items.sort(Comparator.comparingLong((UntakenRow r) -> r.value).reversed());
		JPanel head = tallyCard("Left behind", "Items", fmt(totalQty), ACCENT_RED, totalVal);
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
		for (UntakenRow r : Sources.firstN(list, cap))
		{
			listCard(p, row(r.name, gps(r.value), ACCENT_RED),
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

	void fetchSlayerJourney(Consumer<SlayerJourney> onDone)
	{
		if (!live.ready())
		{
			onDone.accept(null);
			return;
		}
		executor.submit(() -> onDone.accept(store.slayerJourney()));
	}

	private void fetchGrinds(Consumer<List<GrindBook.GrindRow>> onDone)
	{
		if (!live.ready())
		{
			onDone.accept(null);
			return;
		}
		final JsonObject clog = store.clogSnapshot();
		final List<SourceRow> sources = store.dropSources();
		executor.submit(() -> onDone.accept(grindBook.grinds(clog, sources)));
	}

	void resetAccountCaches()
	{
		journeyCache = null;
		journeyFetching = false;
		index.forget();
		detailTask = -1;
		leftBehindSource = null;
		leftBehindItem = null;
		grindsCache = null;
		grindsFetching = false;
		historySpine = null;
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
		itemNames.forget();
		art.forgetScaled();
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

	void addTaskCard(JPanel p, ChronicleEventCapture.SlayerView task, String title, Color ink)
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
		ChronicleEventCapture.SlayerView live = this.live.slayerView();
		if (live == null)
		{
			return;
		}
		SlayerJourney journey = journeyCache;
		if (journey == null)
		{
			fetchSlayerJourney(read -> SwingUtilities.invokeLater(() ->
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
		ChronicleEventCapture.SlayerView task = live.slayerView();
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
			fetchSlayerJourney(j -> SwingUtilities.invokeLater(() ->
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
		long[] ms = window().ms();
		final LocalStore.Tasks tasks = store.tasks(ms[0], ms[1], lootTask, wholeRecord());
		final List<BagItem> bag = tasks.loot();
		if (bag.isEmpty())
		{
			p.add(taskPicker());
			return noted(p, lootTask != null
				? window().phrase("No loot logged on " + lootTask)
				: wholeRecord()
					? "No task loot in the journal yet. It collects as tasks close."
					: window().phrase("No task loot"));
		}
		final long[] sum = LocalStore.tallyOf(bag);
		final long qty = sum[0];
		final long value = sum[1];

		if (lootKind != null)
		{
			p.add(taskPicker());
			return kindDrill(p, bag, "task:");
		}
		final long[] tally = tasks.tally();
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
			spaced(page, row("Task", lootTask, accent()), 4);
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

	static final String UNFILED = "Everything else";

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

	JLabel sprite(int itemId, String name, long qty)
	{
		JLabel slot = new JLabel();
		slot.setPreferredSize(new Dimension(36, 32));
		slot.setHorizontalAlignment(JLabel.CENTER);
		slot.setToolTipText(named(name, qty));
		link(slot, () -> openItem(name));
		store.items().getImage(itemId, (int) Math.min(Integer.MAX_VALUE, qty), qty > 1).addTo(slot);
		return slot;
	}

	private JPanel bagCard(String title, List<BagItem> bag, long[] sum)
	{
		JPanel head = tallyCard(title, "Items", fmt(sum[0]), accent(), sum[1]);
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
			if (UNFILED.equals(lootKind) ? k == null : lootKind.equals(k))
			{
				kept.add(b);
			}
		}
		return kept;
	}

	private JPanel onTaskHead(long qty, long value, long[] tally)
	{
		JPanel head = tallyCard("On-task loot", "Items", fmt(qty), accent(), value);
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

	void reportCopy(JLabel take, boolean ok)
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

	JPanel copyHeaderLater(String title, Consumer<JLabel> copy)
	{
		JPanel r = row(title, "copy");
		styled(part(r, BorderLayout.CENTER), small(), accent());
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
		List<Entry<String, Long>> kcs = new ArrayList<>(LocalStore.killLogCounts(build.clog()).entrySet());
		if (kcs.isEmpty())
		{
			return noted(p, "No kill log yet. It copies itself the next time you open "
				+ "the Slayer Kill Log in game.");
		}
		kcs.sort(Entry.<String, Long>comparingByValue().reversed());
		JPanel card = card("Kill log");
		final int cap = drillShown.getOrDefault("killlog", ROW_CAP);
		for (Entry<String, Long> e : Sources.firstN(kcs, cap))
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

	static boolean journeyMoved(SlayerJourney was,
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

		LocalStore.TaskView seg = store.allTasks().at(index);
		List<UntakenRow> monsters = seg.fought();
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

		List<BagItem> bag = seg.bag();
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
		JPanel all = row("All kills of " + t.task, "", accent(), true);
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
		LocalStore.Untaken untaken = store.untaken();
		p.add(backRow("< Back", "", () -> showLeftBehind(null, null)));
		p.add(vgap(4));

		if (leftBehindSource != null)
		{
			List<BagItem> bag = untaken.itemsOf(leftBehindSource);
			UntakenRow left = Sources.find(untaken.sources, u -> u.name, leftBehindSource, true);
			spaced(p, tallyCard(leftBehindSource.toUpperCase(Locale.ROOT), "Left on the floor",
				count(left == null ? 0 : left.qty, "item"), ACCENT_RED, left == null ? 0 : left.value));
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
				link(r, () -> showLeftBehind(null, b.name));
				p.add(r);
			}
			return p;
		}

		List<UntakenRow> sources = untaken.sourcesOf(leftBehindItem);
		UntakenRow held = Sources.find(untaken.items, u -> u.name, leftBehindItem, true);
		spaced(p, tallyCard(leftBehindItem.toUpperCase(Locale.ROOT), "Left behind",
			"×" + fmt(held == null ? 0 : held.qty), ACCENT_RED, held == null ? 0 : held.value));
		if (sources.isEmpty())
		{
			return noted(p, "No source itemised for this yet.");
		}
		p.add(group("Left where"));
		for (UntakenRow r : sources)
		{
			JPanel row = row(r.name, "×" + qtyGp(r.qty, r.value), ACCENT_RED);
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
			if (window().inside((long) (t.ts * 1000)) && (wholeRecord() || !t.inProgress))
			{
				shown.add(t);
				where.add(i);
			}
		}
		if (shown.isEmpty() && !wholeRecord())
		{
			p.add(note(window().phrase("No tasks closed")));
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
			listCard(p, row(t.task, t.totalValue > 0 ? gps(t.totalValue) : "", accent(), t.inProgress),
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
		openSource(build.names().resolve(name));
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
		styled(part(r, BorderLayout.CENTER), small(), accent());
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
		for (SourceRow r : build.sources())
		{
			for (BagItem b : store.sourceItems(r.name))
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
		final LocalStore.LootWindow win = wholeRecord() ? null : build.lootWindow();
		final LocalStore.Row headRow = win == null ? null : win.item(name);
		final long[] inWindow = win == null ? null
			: headRow == null ? new long[2] : new long[]{headRow.qty, headRow.value};
		long other = 0;
		long otherValue = 0;
		if (inWindow != null)
		{
			srcs.clear();
			other = inWindow[0];
			otherValue = inWindow[1];
			String spelled = headRow != null ? headRow.name : name;
			for (Map.Entry<String, List<BagItem>> e : win.bySource.entrySet())
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
			AsyncBufferedImage img = store.items().getImage(itemId,
				(int) Math.min(Integer.MAX_VALUE, Math.max(1, qty)), qty > 1);
			img.addTo(slot);
			head.add(slot);
		}
		final boolean hasTask = build.taskItems().containsKey(properName(name));
		long[] tw = window().ms();
		long[] mine = inWindow == null ? build.taskItems().get(properName(name))
			: store.tasks(tw[0], tw[1], null, true).items().getOrDefault(properName(name), new long[2]);
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
			long[] days = build.lootDays().itemDays(name);
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
		for (Object[] s : Sources.firstN(srcs, srcCap))
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
		long from = build.lootDays().detailFrom();
		Period w = window();
		return sessionPeriod() || from > 0 && from <= startMs(w.start) ? null
			: from <= 0 ? UNDATED
			: !drawingCopy ? "Loot since " + dayOf(from).format(FULL_DAY)
			: "Loot is dated for " + (dayOf(from).isAfter(w.end) ? "none" : "only part") + " of "
			+ window().inSentence() + ".";
	}

	private String sinceLine(String since)
	{
		return since != null || wholeRecord() || !drawingCopy ? since
			: "The figures above are " + window().inSentence() + "'s.";
	}

	private JPanel nothing(JPanel p, String what, String since)
	{
		return since != null ? p : noted(p, window().phrase("Nothing " + what));
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
		String key = keyOf(build.taskItems().keySet(), typed);
		return key == null ? typed : key;
	}

	private JPanel byTaskRows(JPanel p, String name)
	{
		long[] w = window().ms();
		List<LocalStore.Row> split = store.tasks(w[0], w[1], null, true).byTask(name);
		if (split.isEmpty())
		{
			return noted(p, window().phrase("No task paid this"));
		}
		p.add(group("By task"));
		final int taskCap = Math.max(drawingCopy ? COPY_MOST : 40, drillShown.getOrDefault("item:task:" + name, 0));
		for (LocalStore.Row t : Sources.firstN(split, taskCap))
		{
			p.add(row("Task: " + t.name, "×" + fmt(t.qty) + tail(t.value)));
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
		Map<String, Long> rows = build.kills.sayers(sr);
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

	private void addAssignments(JPanel p, String npc)
	{
		long[] w = window().ms();
		List<LocalStore.Assignment> was = store.tasks(w[0], w[1], null, true).assignments(npc);
		if (was.isEmpty())
		{
			return;
		}
		p.add(group("Killed on task"));
		int cap = drillShown.getOrDefault("ontask:src:" + npc, ROW_CAP);
		for (LocalStore.Assignment a : Sources.firstN(was, cap))
		{
			p.add(row("Task: " + a.task, fmt(a.killsHere)));
		}
		drillMore(p, "ontask:src:" + npc, was.size(), cap);
		p.add(vgap(6));
	}

	private void addFloorRow(JPanel head, String name)
	{
		for (UntakenRow u : store.untaken().sources)
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
		final SourceRow sr = Sources.find(build.sources(), r -> r.name, name, false);
		LocalStore.LootWindow win = wholeRecord() ? null : build.lootWindow();
		LocalStore.Row row = win == null ? null : win.source(sr != null ? sr.name : name, sr != null);
		long[] inWindow = win == null ? null : row == null ? new long[2] : new long[]{row.qty, row.value};
		String own = row != null ? row.name : sr != null ? sr.name : name;
		final List<BagItem> bag = win == null ? store.sourceItems(own)
			: new ArrayList<>(win.bySource.getOrDefault(own, new ArrayList<>()));
		bag.sort(Comparator.comparingLong((BagItem b) -> b.value).reversed());
		final long other = inWindow == null ? 0 : inWindow[1] - LocalStore.tallyOf(bag)[1];
		final boolean unfiled = other > 0 || win != null && win.unfiled.contains(own);
		spaced(p, backRow(() -> copyPage(() -> buildSourceDetail(name))), 4);
		JPanel head = card(name);
		if (sr != null)
		{
			boolean killed = build.kills.isKillSource(sr.name);
			long shown = inWindow != null ? inWindow[0]
				: killed ? build.kills.standingKills(sr) : sr.loots;
			head.add(row(killed ? "Kills" : "Times looted", fmt(shown), accent()));
			long worth = inWindow != null ? inWindow[1] : sr.value;
			long over = inWindow != null ? inWindow[0] : sr.loots;
			head.add(row("Worth", gps(worth)
				+ (over > 0 ? " · " + perOne(worth, over, killed) : "")));
			if (inWindow == null)
			{
				addKillSources(head, sr, killed ? shown : -1);
				addFloorRow(head, sr.name);
			}
			for (Entry<String, Long> pbLine : build.kills.pageLines(sr.name, "pb_lines"))
			{
				head.add(row(pbLine.getKey(), pb(pbLine.getValue())));
			}
			for (Entry<String, Long> ln : build.kills.logLines(sr.name))
			{
				head.add(row(ln.getKey(), fmt(ln.getValue())));
			}
			if (sr.pb != null)
			{
				JsonObject rec = build.feed.records().get(low(sr.name));
				JsonObject recData = rec != null ? rec.getAsJsonObject("data") : null;
				JPanel best = row("Personal best", pb(sr.pb) + (rec != null
					? " · set " + day(safeLong(rec.get("ts"))) : ""));
				if (recData != null && recData.has("was"))
				{
					best.setToolTipText("Was " + pb(recData.get("was").getAsDouble()));
				}
				head.add(best);
			}
			double[] timed = win == null ? new double[]{sr.timed, sr.timeSum}
				: row == null ? new double[2] : new double[]{row.timed, row.timeSum};
			if (timed[0] > 0)
			{
				head.add(row("Average kill", pb(timed[1] / timed[0]) + " · "
					+ fmt((long) timed[0]) + " timed"));
			}
			long here = build.kills.minutesAt(sr.name, inWindow == null ? build.lifetime() : build.periodCounters());
			if (here > 0 && build.kills.minutesCoverPeriod())
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
				fetchGrinds(rows2 -> SwingUtilities.invokeLater(() ->
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
			int cap = drawingCopy ? COPY_MOST : drillShown.getOrDefault(name, 25);
			addBagRows(p, Sources.firstN(bag, cap));
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

	int[] clogStanding()
	{
		int avail = live.clogAvailable();
		return avail > 0 ? new int[]{live.clogFinished(), avail} : null;
	}

	void addPaceLine(JPanel p, String section)
	{
		PaceBook.Pace pace;
		try
		{
			pace = live.pace(section);
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
			p.add(ghostRow(target + " in " + count(pace.daysOfPlay, "day") + " of play",
				gp((long) pace.xpPerActiveDay) + "/day"));
			if (pace.activeDays < 3)
			{
				p.add(ghostRow("measured over " + count(pace.activeDays, "day"), ""));
			}
		}
		else if (pace.dormant() && pace.lastActive != null)
		{
			p.add(ghostRow("last moved " + pace.lastActive.format(TASK_DAY), ""));
		}
	}

	private final Set<String> openFolds = new HashSet<>();

	boolean foldOpen(String key)
	{
		return openFolds.contains(key);
	}

	boolean foldOpen(String key, boolean byDefault)
	{
		return openFolds.contains(key) != byDefault;
	}

	JPanel folds(JPanel head, String key)
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

	Map<String, Long> consumVals = new LinkedHashMap<>();

	long resourcesDropped;

	void statRows(JPanel p, List<Entry<String, Long>> rows)
	{
		for (Entry<String, Long> e : rows)
		{
			p.add(row(StatRegistry.rowLabel(e.getKey()), rowValue(e)));
		}
	}

	String rowValue(Entry<String, Long> e)
	{
		String base = value(e);
		if (e.getKey().equals("resourcesGatheredValue") && resourcesDropped > 0)
		{
			return base + " · " + gp(resourcesDropped) + " dropped";
		}
		Long cv = wholeRecord() ? consumVals.get(e.getKey()) : null;
		return cv != null && cv > 0 ? base + " · " + gps(cv) : base;
	}

	JPanel subHead(String label, String totalStr, String stateKey)
	{
		JPanel head = row(label, totalStr);
		styled(part(head, BorderLayout.CENTER), small(), dim());
		head.setBorder(pad(3, 10, 1, 2));
		return folds(head, stateKey);
	}


	TreeMap<LocalDate, Baseline> historySpine;
	SlayerJourney historyJourney;
	LocalDate historyDay;
	long historyFeedTs;
	private boolean historyGathering;
	private int historyEpoch;

	@RequiredArgsConstructor
	private static final class HistoryData
	{
		final TreeMap<LocalDate, Baseline> spine;
		final long feedTs;
		final SlayerJourney journey;
		final LocalDate day;
	}

	void gatherHistory()
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
				return new HistoryData(live.historyBaselines(),
					newestTs(store.feedNewest(1)), (live.ready() ? store.slayerJourney() : null), LocalDate.now());
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
				catch (InterruptedException | ExecutionException e)
				{
					return;
				}
				historySpine = d.spine;
				historyJourney = d.journey;
				historyDay = d.day;
				historyFeedTs = d.feedTs;
				rebuildInPlace();
			}
		}.execute();
	}

	private static final int HIST_LIST_CAP = 6;
	private final Map<String, Integer> histListShown = new LinkedHashMap<>();

	int shownCap(String key)
	{
		return histListShown.getOrDefault(key, HIST_LIST_CAP);
	}

	void addMore(JPanel card, String key, int size, int cap, boolean inset)
	{
		more(card, size, cap, inset, n -> histListShown.put(key, n));
	}

	static final int ICON_W = 22;
	static final int ICON_H = 18;

	String periodTip(long[] played, List<Entry<String, Long>> gains)
	{
		long xp = sumOf(gains);
		return tip(sessionPeriod() ? "This sitting"
			: wholeRecord() ? "Lifetime" : "The period",
			"Time played", hoursMinutes(played[0]),
			"Sessions", fmt(played[1]),
			"Experience", "+" + gp(xp));
	}

	String periodTip;
	String sheetPage;
	String measuredSince;

	boolean wholeRecord()
	{
		return window().whole;
	}

	static final String[] PERIODS = {"Lifetime", "Year", "Month", "Week", "Day",
		"Session"};

	boolean sessionPeriod()
	{
		return window().session;
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
		for (String task : store.allTasks().names())
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
		styled(part(r, BorderLayout.CENTER), small(), dim());
		JLabel pick = part(r, BorderLayout.EAST);
		pick.setFont(small());
		pick.setToolTipText("Narrow this board to one task");
		link(pick, () -> taskMenu().show(r, 0, r.getHeight()));
		link(r, () -> taskMenu().show(r, 0, r.getHeight()));
		return r;
	}

	private void freshBuild()
	{
		build = new Build(store, live, gson, this::window, () -> historySpine, () -> historyJourney,
			this::gatherHistory);
	}

	Period window()
	{
		Period w = Period.of(histGranularity, histCursor, histFrom, histTo, live.sessionStart(),
			historySpine);
		periodFrom = w.start;
		periodTo = w.end;
		return w;
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
		for (SourceRow r : build.sources())
		{
			if (ownTile.contains(low(r.name)))
			{
				continue;
			}
			Skill sk = Sources.skillOf(r.name);
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
		long[] cur = live.skillSheet().get(key);
		return cur != null && cur.length > 1 && cur[1] > 0 ? cur[1] : null;
	}

	private JPanel buildSkillDetail(String craft)
	{
		JPanel p = backPage();
		consumVals = store.consumableValues();
		String key = low(craft);

		JPanel head = card(craft);
		Span s = build.span();
		Long now = liveXp(key);
		if (now == null)
		{
			now = s == null ? null : s.closing.skills.get(key);
		}
		Long was = sessionPeriod()
			? (now == null ? null : Math.max(0, now - build.summary.sessionXp(key)))
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
		long minutes = (wholeRecord() ? build.lifetime() : build.periodCounters())
			.getOrDefault(StatKeys.timeKey(craft), 0L);
		if (minutes > 0 && build.kills.minutesCoverPeriod())
		{
			long gained = !wholeRecord() && was != null && now != null && now > was ? now - was : 0;
			boolean rate = gained > 0 && minutes >= 30;
			head.add(row("Time", hoursMinutes(minutes)
				+ (rate ? " · " + gp(Math.round(gained * 60.0 / minutes)) + " xp/h" : "")));
		}
		spaced(p, head);

		Map<String, Long> counters = build.countersForPeriod();
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
			String unkept = build.notCounting(false);
			return noted(p, unkept != null ? unkept : "Nothing is tracked under " + craft
				+ (wholeRecord() ? "." : " in " + window().inSentence() + "."));
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

	JPanel noPeriod()
	{
		return note(historySpine == null
			? "Reading your history..."
			: "Nothing closed inside " + window().inSentence() + ". A period is the distance "
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
		final Period w = window();
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

	static JPanel stepStrip()
	{
		JPanel r = new JPanel(new BorderLayout());
		r.setBackground(DARKER);
		r.setBorder(pad(3, 8, 3, 8));
		r.setAlignmentX(Component.LEFT_ALIGNMENT);
		r.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
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
		return !step(histCursor, 1).isAfter(LocalDate.now());
	}

	private void stepPeriod(int by)
	{
		if (histFrom != null && histTo != null)
		{
			long span = ChronoUnit.DAYS.between(histFrom, histTo) + 1;
			histFrom = by < 0 ? histFrom.minusDays(span) : histFrom.plusDays(span);
			histTo = by < 0 ? histTo.minusDays(span) : histTo.plusDays(span);
		}
		else
		{
			LocalDate next = step(histCursor, by);
			histCursor = next.isAfter(LocalDate.now()) ? LocalDate.now() : next;
		}
		rebuild();
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

	private LocalDate step(LocalDate d, int by)
	{
		switch (histGranularity)
		{
			case "Day":
				return d.plusDays(by);
			case "Month":
				return d.withDayOfMonth(1).plusMonths(by + 1).minusDays(1);
			case "Year":
				return d.withDayOfYear(1).plusYears(by + 1).minusDays(1);
			default:
				return d.plusDays(7L * by);
		}
	}

	String journalLens = "All";
	int journalShown = 60;

	void openJournalOn(long ts)
	{
		if (ts > 0)
		{
			histGranularity = "Day";
			histFrom = null;
			histTo = null;
			histCursor = dayOf(ts);
		}
		openJournal("All");
	}

	void openJournal(String lens)
	{
		journalLens = lens;
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
			live.actionImport(fc.getSelectedFile());
		}
	}

	void openSheetPage(String page)
	{
		applyTab(Tab.STANDING);
		sheetPage = page;
		rebuild();
	}

	Runnable searchFirst;

	JPanel progress(float frac)
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

	JLabel group(String name)
	{
		JLabel g = styled(new JLabel(name.toUpperCase(Locale.ROOT)), small(), accent());
		g.setAlignmentX(Component.LEFT_ALIGNMENT);
		g.setBorder(pad(8, 2, 3, 0));
		return g;
	}

	JPanel backPage()
	{
		JPanel p = column();
		spaced(p, backRow(), 4);
		return p;
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

}
