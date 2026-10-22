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
import chronicle.LocalStore.SlayerJourney;
import chronicle.LocalStore.SlayerTask;
import chronicle.panel.StatRegistry;
import com.google.gson.Gson;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
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
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
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
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.IconTextField;
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.ui.components.materialtabs.MaterialTabGroup;
import net.runelite.client.util.ImageUtil;
import static chronicle.Copy.copyPicture;
import static chronicle.Copy.stripChrome;
import static chronicle.Feed.newestTs;
import static chronicle.Kit.ACCENT_LIFETIME;
import static chronicle.Kit.DARK;
import static chronicle.Kit.DARKER;
import static chronicle.Kit.PANEL_INSET;
import static chronicle.Kit.ROW_CAP;
import static chronicle.Kit.arrows;
import static chronicle.Kit.card;
import static chronicle.Kit.clicker;
import static chronicle.Kit.column;
import static chronicle.Kit.copyLabel;
import static chronicle.Kit.day;
import static chronicle.Kit.dim;
import static chronicle.Kit.fixedPeriod;
import static chronicle.Kit.link;
import static chronicle.Kit.linkRow;
import static chronicle.Kit.menuItem;
import static chronicle.Kit.moreRow;
import static chronicle.Kit.nested;
import static chronicle.Kit.note;
import static chronicle.Kit.overlayBar;
import static chronicle.Kit.pad;
import static chronicle.Kit.part;
import static chronicle.Kit.pill;
import static chronicle.Kit.reportCopy;
import static chronicle.Kit.row;
import static chronicle.Kit.small;
import static chronicle.Kit.spaced;
import static chronicle.Kit.stepStrip;
import static chronicle.Kit.styled;
import static chronicle.Kit.tip;
import static chronicle.Kit.clearHover;
import static chronicle.Kit.wash;
import static chronicle.Period.dayOf;
import static chronicle.Text.count;
import static chronicle.Text.named;

class ChroniclePanel extends PluginPanel
{
	private static final Map<Tab, String[]> SUBS = new EnumMap<>(Tab.class);

	private static final int MOVED_RECORD = 1;
	private static final int MOVED_COUNTERS = 2;
	private static final int MOVED_SKILLS = 4;
	private static final int MOVED_CLOG = 8;

	private static final int MOVED_ANY = MOVED_RECORD | MOVED_COUNTERS
		| MOVED_SKILLS | MOVED_CLOG;

	private static final int HIST_LIST_CAP = 6;
	static final String[] PERIODS = {"Lifetime", "Year", "Month", "Week", "Day",
		"Session"};

	static
	{
		SUBS.put(Tab.RECORD, new String[]{"Now", "Journal", "Ledger", "Recap"});
		SUBS.put(Tab.STANDING, new String[0]);
		SUBS.put(Tab.LOOT, new String[]{"Loot", "Slayer"});
		SUBS.put(Tab.TRACKERS, new String[0]);
	}

	final LocalStore store;
	final Live live;
	final ScheduledExecutorService executor;
	final Gson gson;
	final SpriteManager sprites;
	final SkillIconManager skillIcons;
	final GrindBook grindBook;
	Build build;
	final DetailPage detail = new DetailPage(this);
	final SlayerPage slayer = new SlayerPage(this);
	final LootPage loot = new LootPage(this);
	private final SheetPage sheet = new SheetPage(this);
	final LedgerPage ledger = new LedgerPage(this);
	final LogPage clog = new LogPage(this);
	final HomePage home = new HomePage(this);
	final JournalPage journal = new JournalPage(this);
	final RecapPage recap = new RecapPage(this);
	final RecordsPage records = new RecordsPage(this);
	final SearchPage search = new SearchPage(this);
	final Art art = new Art(this);
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
	View view = View.HOME;
	Tab tab = Tab.RECORD;
	private String detailItem;
	String detailSource;
	private String detailSkill;
	boolean allTrackers;
	private boolean showInfo;
	private boolean showRecords;
	private boolean showCalendar;
	YearMonth calendarMonth = YearMonth.now();
	private final ArrayDeque<String[]> detailStack = new ArrayDeque<>();
	String statsFamily = StatRegistry.FAMILIES[0];
	int dropsShown = ROW_CAP;
	String clogTab = "Bosses";
	String clogPageSel;
	private final JPanel band = new JPanel(new BorderLayout());
	private final JLabel bandText = new JLabel();
	private String histGranularity = "Lifetime";
	private LocalDate histCursor = LocalDate.now();
	private LocalDate histFrom;
	private LocalDate histTo;
	private final AtomicBoolean queued = new AtomicBoolean();
	private final AtomicInteger movedMask = new AtomicInteger();
	private boolean everShown;
	private boolean stale;
	private Point lastPointer;
	private boolean keepScroll;
	private final JPanel periodHolder = new JPanel(new BorderLayout());
	private final JPanel north = new JPanel();
	private long lastBuildNanos;
	private long lastBuildAt;
	private JPanel subBar;
	private boolean bandClickable;
	boolean onTaskOnly;
	boolean dropsLeftBehind;
	String lootKind;
	String lootTask;
	boolean dropsByKind;
	List<GrindBook.GrindRow> grindsCache;
	boolean grindsFetching;
	SlayerJourney journeyCache;
	boolean journeyFetching;
	int detailTask = -1;
	String leftBehindSource;
	String leftBehindItem;
	String slayerLens = "Tasks";
	int slayerShown = ROW_CAP;
	final Map<String, Integer> drillShown = new LinkedHashMap<>();
	boolean drawingCopy;
	private final Set<String> openFolds = new HashSet<>();
	Map<String, Long> consumVals = new LinkedHashMap<>();
	long resourcesDropped;
	TreeMap<LocalDate, Baseline> historySpine;
	SlayerJourney historyJourney;
	LocalDate historyDay;
	long historyFeedTs;
	private boolean historyGathering;
	private int historyEpoch;
	private final Map<String, Integer> histListShown = new LinkedHashMap<>();
	String sheetPage;
	String measuredSince;
	String journalLens = "All";
	int journalShown = 60;
	Runnable searchFirst;

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
		index = new Search(store);

		watchShowing();
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
			if (stale && everShown && getWrappedPanel().isShowing()
				&& !popupShowing())
			{
				stale = false;
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
		if (subs.length == 0 || !searchQuery().isEmpty()
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
		if (subs.length == 0)
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
		closePages();
		detailTask = -1;
		leftBehindSource = null;
		leftBehindItem = null;
		detailStack.clear();
		clearSearch();
		rebuild();
	}

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

	private void clearSearch()
	{
		searchField.setText("");
		searchDebounce.stop();
	}

	String searchQuery()
	{
		return searchField.getText().trim();
	}

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
				stale = true;
				return;
			}
			if (popupShowing() || scrollHeld() || pointerResting())
			{
				stale = true;
				return;
			}
			if (!viewCares(mask))
			{
				return;
			}
			long floor = redrawFloorMs();
			if (floor > 0 && System.currentTimeMillis() - lastBuildAt < floor)
			{
				stale = true;
				return;
			}
			rebuildInPlace();
		});
	}

	private boolean pointerResting()
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

	private void watchShowing()
	{
		getWrappedPanel().addHierarchyListener(e ->
		{
			if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) == 0
				|| !getWrappedPanel().isShowing())
			{
				return;
			}
			everShown = true;
			if (stale)
			{
				stale = false;
				update();
			}
		});
	}

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

	private long redrawFloorMs()
	{
		long ms = lastBuildNanos / 1_000_000L;
		return ms < 12 ? 0 : Math.min(2000L, ms * 60L);
	}

	private void rebuildNow()
	{
		freshBuild();
		resourcesDropped = 0;
		art.forgetWaiting();
		paintBand(store.journalWarning(), live.captureWarning(),
			live.captureWarningWhy());
		if (subBar != null)
		{
			display.remove(subBar);
			subBar = null;
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
			: detailItem != null ? detail.buildItemDetail(detailItem)
			: detailSource != null ? detail.buildSourceDetail(detailSource)
			: detailSkill != null ? detail.buildSkillDetail(detailSkill)
			: allTrackers ? ledger.buildAllTrackers()
			: showRecords ? records.buildRecords()
			: showCalendar ? records.buildCalendar()
			: showInfo ? records.buildInfo()
			: detailTask >= 0 ? detail.buildTaskDetail(detailTask)
			: leftBehindSource != null || leftBehindItem != null ? detail.buildLeftBehindDetail()
			: sheetPage != null ? sheet.buildSheetPage()
			: buildView();
		periodHolder.setToolTipText(measuredSince);
		clearHover();
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
			subBar = above;
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
				return loot.buildDrops();
			case SLAYER:
				return slayer.buildSlayer();
			case STATS:
				return ledger.buildStats();
			case JOURNAL:
				return journal.buildJournal();
			case RECAP:
				return recap.buildRecap();
			case HOME:
			default:
				return home.buildHome();
		}
	}

	private void onSearchChanged()
	{
		drillShown.keySet().removeIf(k -> k.startsWith("search:"));
		rebuild();
	}

	private void paintBand(String stalled, String capture, String captureWhy)
	{
		if (stalled == null && capture == null)
		{
			band.setVisible(false);
			bandClickable = false;
			return;
		}
		boolean red = stalled != null;
		Color ink = red ? ColorScheme.PROGRESS_ERROR_COLOR : ACCENT_LIFETIME;
		bandClickable = !red;
		for (MouseListener l : band.getMouseListeners())
		{
			band.removeMouseListener(l);
		}
		if (bandClickable)
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
		band.setCursor(bandClickable
			? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
			: Cursor.getDefaultCursor());
		band.setVisible(true);
	}

	void foldHead(JPanel head, String fold, String tip)
	{
		JLabel name = part(head, BorderLayout.CENTER);
		if (foldOpen(fold))
		{
			name.setForeground(ACCENT_LIFETIME);
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

	void fetchSlayerJourney(Consumer<SlayerJourney> onDone)
	{
		if (!live.ready())
		{
			onDone.accept(null);
			return;
		}
		executor.submit(() -> onDone.accept(store.slayerJourney()));
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
		art.forgetScaled();
		gatherHistory();
		rebuild();
	}

	void shutdown()
	{
		homeTicker.stop();
		searchDebounce.stop();
	}

	void openCurrentTask()
	{
		ChronicleEventCapture.SlayerView task = live.slayerView();
		if (task == null)
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
			if (t.inProgress && t.task.equalsIgnoreCase(task.task))
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

	void showLeftBehind(String source, String item)
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

	Runnable relens(Runnable change)
	{
		return () ->
		{
			change.run();
			lootKind = null;
			rebuildInPlace();
		};
	}

	void more(JPanel p, int size, int cap, boolean inset, IntConsumer show)
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

	private void closePages()
	{
		allTrackers = false;
		showInfo = false;
		showRecords = false;
		showCalendar = false;
	}

	private void closeAll()
	{
		closePages();
		detailItem = null;
		detailSource = null;
		detailSkill = null;
		detailTask = -1;
		clearSearch();
	}

	void openRecords()
	{
		closeAll();
		showRecords = true;
		rebuild();
	}

	void openCalendar()
	{
		closeAll();
		showCalendar = true;
		calendarMonth = YearMonth.from(histCursor);
		rebuild();
	}

	void openItem(String name)
	{
		closePages();
		pushDetail();
		detailItem = name;
		detailSource = null;
		clearSearch();
		rebuild();
	}

	void openInfo()
	{
		closeAll();
		showInfo = true;
		rebuild();
	}

	void openAllTrackers()
	{
		closeAll();
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
		closeAll();
		detailSkill = craft;
		rebuild();
	}

	void openSource(String name)
	{
		closePages();
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

	JPanel backRow()
	{
		return backRow(null);
	}

	JPanel backRow(BooleanSupplier copy)
	{
		JPanel r = linkRow("< Back", copy == null ? "" : "copy", this::backDetail);
		JLabel take = copy == null ? null : copyLabel(r, "Copy this page as a picture");
		if (take != null)
		{
			take.addMouseListener(clicker(() -> reportCopy(take, copy.getAsBoolean())));
		}
		return r;
	}

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

	JPanel subHead(String label, String totalStr, String stateKey)
	{
		JPanel head = row(label, totalStr);
		styled(part(head, BorderLayout.CENTER), small(), dim());
		head.setBorder(pad(3, 10, 1, 2));
		return folds(head, stateKey);
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

	int shownCap(String key)
	{
		return histListShown.getOrDefault(key, HIST_LIST_CAP);
	}

	void addMore(JPanel card, String key, int size, int cap, boolean inset)
	{
		more(card, size, cap, inset, n -> histListShown.put(key, n));
	}

	boolean wholeRecord()
	{
		return window().whole;
	}

	boolean sessionPeriod()
	{
		return window().session;
	}

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
		Period w = window();
		menuItem(menu, "Exact dates", histFrom != null, () -> onSetExactDates(w.start, w.end));
		return menu;
	}

	private void freshBuild()
	{
		build = new Build(store, live, gson, this::window, () -> historySpine, () -> historyJourney,
			this::gatherHistory);
	}

	Period window()
	{
		return Period.of(histGranularity, histCursor, histFrom, histTo, live.sessionStart(), historySpine);
	}

	JPanel noPeriod()
	{
		return note(historySpine == null
			? "Reading your history..."
			: "Not enough history for " + window().inSentence() + ". A period compares two "
				+ "saved days, and this one has fewer than two.");
	}

	private JPanel periodRow()
	{
		final Period w = window();
		JPanel r = stepStrip();
		if (showingSitting())
		{
			fixedPeriod(r, "This session");
			if (live.ready() && live.sessionStart() > 0)
			{
				addEndSession(r);
			}
			return r;
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
			ACCENT_LIFETIME);
		lbl.setToolTipText("Choose the period");
		link(lbl, () -> periodMenu().show(r, 0, r.getHeight()));
		r.add(lbl, BorderLayout.CENTER);
		return r;
	}

	private void addEndSession(JPanel bar)
	{
		JLabel end = new JLabel("X");
		end.setFont(FontManager.getRunescapeFont());
		end.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
		end.setToolTipText("End this session and start a new one. It is saved to the journal.");
		link(end, live::newSession);
		bar.add(Box.createRigidArea(new Dimension(end.getPreferredSize().width, 0)), BorderLayout.WEST);
		bar.add(end, BorderLayout.EAST);
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
				"Enter dates as yyyy-mm-dd or d/m/yyyy. Nothing changed.");
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
		String s = text.trim();
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

	JPanel backPage()
	{
		JPanel p = column();
		spaced(p, backRow(), 4);
		return p;
	}

	enum View
	{
		HOME, DROPS, SLAYER, STATS, JOURNAL, SHEET, RECAP
	}

	enum Tab
	{
		RECORD, STANDING, LOOT, TRACKERS
	}

	@RequiredArgsConstructor
	private static final class HistoryData
	{
		final TreeMap<LocalDate, Baseline> spine;
		final long feedTs;
		final SlayerJourney journey;
		final LocalDate day;
	}
}
