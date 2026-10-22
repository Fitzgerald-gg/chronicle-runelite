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
import static chronicle.Kit.card;
import static chronicle.Kit.clicker;
import static chronicle.Kit.column;
import static chronicle.Kit.copyHeader;
import static chronicle.Kit.copyLabel;
import static chronicle.Kit.day;
import static chronicle.Kit.dim;
import static chronicle.Kit.ghostRow;
import static chronicle.Kit.link;
import static chronicle.Kit.linkRow;
import static chronicle.Kit.menuItem;
import static chronicle.Kit.moreRow;
import static chronicle.Kit.nested;
import static chronicle.Kit.note;
import static chronicle.Kit.noted;
import static chronicle.Kit.overlayBar;
import static chronicle.Kit.pad;
import static chronicle.Kit.part;
import static chronicle.Kit.progress;
import static chronicle.Kit.reportCopy;
import static chronicle.Kit.row;
import static chronicle.Kit.small;
import static chronicle.Kit.spaced;
import static chronicle.Kit.styled;
import static chronicle.Kit.sumOf;
import static chronicle.Kit.tallyCard;
import static chronicle.Kit.tip;
import static chronicle.Kit.unlight;
import static chronicle.Kit.value;
import static chronicle.Kit.wash;
import static chronicle.LocalStore.kindOf;
import static chronicle.Period.TASK_DAY;
import static chronicle.Period.dayOf;
import static chronicle.Text.count;
import static chronicle.Text.fmt;
import static chronicle.Text.gp;
import static chronicle.Text.gps;
import static chronicle.Text.hoursMinutes;
import static chronicle.Text.named;
import static chronicle.Text.qtyGp;

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
	final ScheduledExecutorService executor;
	final Gson gson;
	final SpriteManager sprites;
	final SkillIconManager skillIcons;
	final GrindBook grindBook;
	Build build;
	final DetailPage detail = new DetailPage(this);
	final SlayerPage slayer = new SlayerPage(this);
	final LootPage loot = new LootPage(this);
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
		pill.setForeground(on ? ACCENT_LIFETIME : dim());
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
		Color ink = red ? ColorScheme.PROGRESS_ERROR_COLOR : ACCENT_LIFETIME;
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

	static final String UNDATED = "No loot has been dated yet. The roll keeps one entry a "
		+ "day and starts with the next drop that lands.";

	boolean onTaskOnly;

	boolean everOnTask()
	{
		return !build.taskItems().isEmpty();
	}


	boolean dropsLeftBehind;
	String lootKind;
	String lootTask;

	boolean dropsByKind;

	static String perOne(long value, long n, boolean killed)
	{
		return gp(value / Math.max(1, n)) + (killed ? " gp/drop" : " gp each");
	}

	void addKindRows(JPanel p, List<BagItem> bag)
	{
		for (Kind k : kindsOf(bag))
		{
			JPanel r = row(k.name, qtyGp(k.qty, k.value), ACCENT_LIFETIME);
			r.setToolTipText(count(k.distinct, "distinct item"));
			link(r, () ->
			{
				lootKind = k.name;
				rebuildInPlace();
			});
			p.add(r);
		}
	}

	JPanel kindDrill(JPanel p, List<BagItem> bag, String key)
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

	List<GrindBook.GrindRow> grindsCache;
	boolean grindsFetching;

	SlayerJourney journeyCache;
	boolean journeyFetching;
	int detailTask = -1;
	String leftBehindSource;
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

	String slayerLens = "Tasks";
	int slayerShown = ROW_CAP;

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

	JPanel lootPicture(String title, List<BagItem> bag, long[] sum, boolean kinds)
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

	JPanel bagCard(String title, List<BagItem> bag, long[] sum)
	{
		JPanel head = tallyCard(title, "Items", fmt(sum[0]), ACCENT_LIFETIME, sum[1]);
		head.add(row("Distinct items", fmt(bag.size())));
		return head;
	}

	private JPanel backToKinds(int held)
	{
		return linkRow("< All kinds", count(held, "item"), () ->
		{
			lootKind = null;
			rebuildInPlace();
		});
	}

	void addBagRows(JPanel p, List<BagItem> bag)
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

	boolean drawingCopy;

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
			ACCENT_LIFETIME);
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
		b.setForeground(ACCENT_LIFETIME);
		link(b, back);
		fwd.setForeground(ahead ? ACCENT_LIFETIME : dim());
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

	JPanel backPage()
	{
		JPanel p = column();
		spaced(p, backRow(), 4);
		return p;
	}

}
