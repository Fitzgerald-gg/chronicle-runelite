/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import chronicle.Period.Window;
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
import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
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
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.ToolTipManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import lombok.RequiredArgsConstructor;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.IconTextField;
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.ui.components.materialtabs.MaterialTabGroup;
import net.runelite.client.util.ImageUtil;
import static chronicle.Pictures.copyPicture;
import static chronicle.Pictures.stripChrome;
import static chronicle.Reference.taxonomy;
import static chronicle.Ui.*;

class ChroniclePanel extends PluginPanel
{
	static final int MOVED_RECORD = 1;
	static final int MOVED_COUNTERS = 2;
	static final int MOVED_SKILLS = 4;
	static final int MOVED_CLOG = 8;
	private static final int MOVED_ANY = MOVED_RECORD | MOVED_COUNTERS | MOVED_SKILLS | MOVED_CLOG;
	private static final int FOLD_CAP = 6;

	enum Tab
	{
		RECORD("tab_record.png", "Record"),
		STANDING("tab_standing.png", "Standing"),
		LOOT("tab_loot.png", "Loot"),
		TRACKERS("tab_trackers.png", "Trackers");

		final String icon;
		final String tip;

		Tab(String icon, String tip)
		{
			this.icon = icon;
			this.tip = tip;
		}
	}

	enum View
	{
		NOW(Tab.RECORD, "Now"),
		JOURNAL(Tab.RECORD, "Journal"),
		LEDGER(Tab.RECORD, "Ledger"),
		RECAP(Tab.RECORD, "Recap"),
		STANDING(Tab.STANDING, null),
		LOOT(Tab.LOOT, "Loot"),
		SLAYER(Tab.LOOT, "Slayer"),
		TRACKERS(Tab.TRACKERS, null);

		final Tab tab;
		final String sub;

		View(Tab tab, String sub)
		{
			this.tab = tab;
			this.sub = sub;
		}
	}

	enum Page
	{
		ITEM, SOURCE, SKILL, TRACKERS, RECORDS, CALENDAR, INFO, TASK, LEFT_SOURCE, LEFT_ITEM, SHEET
	}

	@RequiredArgsConstructor
	static final class Place
	{
		final Page page;
		final String name;
		final int index;
	}

	private final ChroniclePlugin plugin;
	private final Period period = new Period();
	private final Board board;
	final Art art;
	final HomeScreen home;
	final StandingScreen standing;
	final PagesScreen pages;
	final SlayerScreen slayer;
	final LootScreen loot;
	final DetailScreen detail;
	final TrackersScreen trackers;
	final SearchScreen search;
	final JournalScreen journal;
	final RecapScreen recap;

	View view = View.NOW;
	Place place;
	private final ArrayDeque<Place> back = new ArrayDeque<>();
	private final Map<Tab, View> lastView = new EnumMap<>(Tab.class);
	private final Set<String> openFolds = new HashSet<>();
	private final Map<String, Integer> shown = new HashMap<>();
	String measuredSince;
	boolean drawingCopy;

	private final JPanel north = new JPanel();
	private final JPanel periodHolder = new JPanel(new BorderLayout());
	private final MaterialTabGroup tabGroup = new MaterialTabGroup();
	final IconTextField searchField = new IconTextField();
	private final JPanel band = new JPanel(new BorderLayout());
	private final JLabel bandText = new JLabel();
	private final JPanel display = new JPanel(new BorderLayout());
	private final ScrollColumn canvas = new ScrollColumn();
	private final JScrollPane scrollPane = new JScrollPane(canvas,
		ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
	private JPanel subStrip;
	private final Timer searchDebounce;
	private final Timer ticker;

	private final AtomicBoolean queued = new AtomicBoolean();
	private boolean everShown;
	private boolean stale;
	private boolean keepScroll;
	private Point lastPointer;
	private long lastBuildNanos;
	private long lastBuildAt;

	ChroniclePanel(ChroniclePlugin plugin)
	{
		super(false);
		this.plugin = plugin;
		board = new Board(plugin, period, this::rebuildInPlace);
		art = new Art(plugin);
		home = new HomeScreen(this, board);
		standing = new StandingScreen(this, board);
		pages = new PagesScreen(this, board);
		slayer = new SlayerScreen(this, board);
		loot = new LootScreen(this, board);
		detail = new DetailScreen(this, board);
		trackers = new TrackersScreen(this, board);
		search = new SearchScreen(this, board);
		journal = new JournalScreen(this, board);
		recap = new RecapScreen(this, board);

		searchDebounce = new Timer(150, e -> onSearchChanged());
		searchDebounce.setRepeats(false);
		setLayout(new BorderLayout());
		setBorder(pad(PANEL_INSET, PANEL_INSET, PANEL_INSET, PANEL_INSET));
		setBackground(DARK);
		north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
		north.setBackground(DARK);

		periodHolder.setBackground(DARK);
		periodHolder.setAlignmentX(Component.CENTER_ALIGNMENT);
		periodHolder.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
		periodHolder.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 16, 22));
		spaced(north, periodHolder, 3);

		tabGroup.setLayout(new GridLayout(1, 4, 2, 0));
		for (Tab t : Tab.values())
		{
			MaterialTab mt = new MaterialTab(new ImageIcon(ImageUtil.loadImageResource(ChroniclePanel.class, t.icon)),
				tabGroup, new JPanel());
			mt.setToolTipText(t.tip);
			mt.setOnSelectEvent(() ->
			{
				show(lastView.getOrDefault(t, firstView(t)));
				return true;
			});
			tabGroup.addTab(mt);
			if (t == Tab.RECORD)
			{
				tabGroup.select(mt);
			}
		}
		spaced(north, tabGroup, 7);

		searchField.setIcon(IconTextField.Icon.SEARCH);
		searchField.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 16, 28));
		searchField.setBackground(DARKER);
		searchField.setHoverBackgroundColor(ColorScheme.DARK_GRAY_HOVER_COLOR);
		searchField.addActionListener(e -> enterSearch());
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
		spaced(north, searchField, 8);

		band.setAlignmentX(Component.CENTER_ALIGNMENT);
		band.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
		band.setBorder(pad(3, 8, 3, 8));
		bandText.setFont(small());
		band.add(bandText, BorderLayout.CENTER);
		band.setVisible(false);
		north.add(band);

		canvas.setBackground(DARK);
		scrollPane.setBorder(null);
		scrollPane.getVerticalScrollBar().setUnitIncrement(14);
		OverlayScrollBarUI.install(scrollPane);
		display.add(scrollPane, BorderLayout.CENTER);
		add(north, BorderLayout.NORTH);
		add(display, BorderLayout.CENTER);

		getWrappedPanel().addHierarchyListener(e ->
		{
			if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && getWrappedPanel().isShowing())
			{
				everShown = true;
				catchUp();
			}
		});
		ticker = new Timer(3000, e ->
		{
			if (stale && everShown && getWrappedPanel().isShowing() && !popupShowing())
			{
				catchUp();
			}
			else if (showingSitting())
			{
				update();
			}
		});
		ticker.start();
		ToolTipManager.sharedInstance().setInitialDelay(220);
		ToolTipManager.sharedInstance().setDismissDelay(20_000);

		board.gatherHistory();
		rebuild();
	}

	void shutdown()
	{
		ticker.stop();
		searchDebounce.stop();
	}

	void resetAccountCaches()
	{
		slayer.forget();
		detail.forget();
		board.forget();
		place = null;
		back.clear();
		shown.clear();
		openFolds.clear();
		board.gatherHistory();
		rebuild();
	}

	private static View firstView(Tab t)
	{
		for (View v : View.values())
		{
			if (v.tab == t)
			{
				return v;
			}
		}
		return View.NOW;
	}

	void show(View v)
	{
		switchTo(v);
		rebuild();
	}

	private void switchTo(View v)
	{
		view = v;
		lastView.put(v.tab, v);
		trackers.reset(v);
		loot.reset();
		slayer.reset();
		shown.clear();
		place = null;
		back.clear();
		clearSearch();
	}

	void open(Page page, String name)
	{
		open(new Place(page, name, -1));
	}

	private void open(Place to)
	{
		if (place != null)
		{
			back.push(place);
			while (back.size() > 16)
			{
				back.removeLast();
			}
		}
		place = to;
		clearSearch();
		rebuild();
	}

	void back()
	{
		place = back.poll();
		rebuild();
	}

	boolean showing(Page page, String name)
	{
		return place != null && place.page == page && name.equals(place.name);
	}

	void openItem(String name)
	{
		open(Page.ITEM, name);
	}

	void openSource(String name)
	{
		open(Page.SOURCE, name);
	}

	void openSourceLoose(String name)
	{
		openSource(board.resolveSource(name));
	}

	void openSkill(String craft)
	{
		open(Page.SKILL, craft);
	}

	void openAllTrackers()
	{
		open(Page.TRACKERS, null);
	}

	void openRecords()
	{
		open(Page.RECORDS, null);
	}

	void openInfo()
	{
		open(Page.INFO, null);
	}

	void openCalendar()
	{
		journal.calendarMonth = YearMonth.from(period.cursor);
		open(Page.CALENDAR, null);
	}

	void showTask(int index)
	{
		open(new Place(Page.TASK, null, index));
	}

	void showLeftBehind(String source, String item)
	{
		open(source != null ? Page.LEFT_SOURCE : Page.LEFT_ITEM, source != null ? source : item);
	}

	void openSheetPage(String page)
	{
		switchTo(View.STANDING);
		place = new Place(Page.SHEET, page, -1);
		rebuild();
	}

	void openLogPage(String page)
	{
		for (Map.Entry<String, Map<String, List<String>>> tab : taxonomy(plugin.gson()).entrySet())
		{
			if (tab.getValue().containsKey(page))
			{
				pages.clogTab = tab.getKey();
				pages.clogPageSel = page;
				openSheetPage("log");
				return;
			}
		}
	}

	boolean hasLogPage(String page)
	{
		return taxonomy(plugin.gson()).values().stream().anyMatch(t -> t.containsKey(page));
	}

	void openActivity(String source)
	{
		if (board.resolveSourceNamed(source) == null && hasLogPage(source))
		{
			openLogPage(source);
		}
		else
		{
			openSourceLoose(source);
		}
	}

	void openSlayer(String lens)
	{
		switchTo(View.SLAYER);
		slayer.slayerLens = lens;
		rebuild();
	}

	void openJournal(String lens)
	{
		switchTo(View.JOURNAL);
		journal.journalLens = lens;
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

	void openLedger(String family)
	{
		switchTo(View.LEDGER);
		trackers.statsFamily = family;
		rebuild();
	}

	void openLootKind(String kind, boolean onTask)
	{
		switchTo(View.LOOT);
		loot.dropsByKind = true;
		loot.onTaskOnly = onTask;
		loot.lootKind = kind;
		rebuild();
	}

	void openLeftBehind(String item)
	{
		switchTo(View.LOOT);
		loot.dropsLeftBehind = true;
		place = item == null ? null : new Place(Page.LEFT_ITEM, item, -1);
		rebuild();
	}

	void promptImport()
	{
		JFileChooser fc = new JFileChooser();
		fc.setDialogTitle("Import a Chronicle journal");
		fc.setFileFilter(new FileNameExtensionFilter("Chronicle journal (*.json)", "json"));
		if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
		{
			plugin.actionImport(fc.getSelectedFile());
		}
	}

	String searchQuery()
	{
		return searchField.getText() == null ? "" : searchField.getText().trim();
	}

	private void clearSearch()
	{
		searchField.setText("");
		searchDebounce.stop();
	}

	private void onSearchChanged()
	{
		shown.keySet().removeIf(k -> k.startsWith("search:"));
		rebuild();
	}

	private void enterSearch()
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
			if (everShown && !getWrappedPanel().isShowing() || popupShowing()
				|| scrollPane.getVerticalScrollBar().getValueIsAdjusting() || beingRead())
			{
				stale = true;
			}
			else if (cares(moved))
			{
				long floor = lastBuildNanos < 12_000_000L ? 0 : Math.min(2000L, lastBuildNanos / 1_000_000L * 60L);
				if (System.currentTimeMillis() - lastBuildAt < floor)
				{
					stale = true;
				}
				else
				{
					rebuildInPlace();
				}
			}
		});
	}

	private void catchUp()
	{
		if (stale)
		{
			stale = false;
			update();
		}
	}

	private boolean cares(int moved)
	{
		switch (view)
		{
			case LOOT:
			case SLAYER:
			case JOURNAL:
				return (moved & MOVED_RECORD) != 0;
			case LEDGER:
			case TRACKERS:
				return (moved & (MOVED_COUNTERS | MOVED_RECORD)) != 0;
			default:
				return (moved & MOVED_ANY) != 0;
		}
	}

	private boolean beingRead()
	{
		Point was = lastPointer;
		lastPointer = null;
		try
		{
			PointerInfo at = MouseInfo.getPointerInfo();
			if (at != null && new Rectangle(getWrappedPanel().getLocationOnScreen(), getWrappedPanel().getSize())
				.contains(at.getLocation()))
			{
				lastPointer = at.getLocation();
			}
		}
		catch (RuntimeException ignored)
		{
		}
		return lastPointer != null && lastPointer.equals(was);
	}

	private static boolean popupShowing()
	{
		MenuElement[] path = MenuSelectionManager.defaultManager().getSelectedPath();
		return path != null && path.length > 0;
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

	void rebuild()
	{
		long began = System.nanoTime();
		board.reset();
		art.forget();
		paintBand(plugin.journalWarning(), plugin.captureWarning(), plugin.captureWarningWhy());
		measuredSince = null;
		periodHolder.removeAll();
		periodHolder.add(periodRow(), BorderLayout.CENTER);
		north.revalidate();
		north.repaint();
		JPanel body = !searchQuery().isEmpty() ? search.buildSearch(searchQuery())
			: place != null ? buildPlace(place) : buildView();
		periodHolder.setToolTipText(measuredSince);
		unlight();
		canvas.removeAll();
		canvas.add(body, BorderLayout.NORTH);
		if (subStrip != null)
		{
			display.remove(subStrip);
		}
		subStrip = view.sub == null || place != null || !searchQuery().isEmpty() ? null : subStrip();
		if (subStrip != null)
		{
			display.add(subStrip, BorderLayout.NORTH);
		}
		display.revalidate();
		display.repaint();
		if (!keepScroll)
		{
			scrollPane.getVerticalScrollBar().setValue(0);
		}
		lastBuildNanos = System.nanoTime() - began;
		lastBuildAt = System.currentTimeMillis();
	}

	private JPanel buildPlace(Place at)
	{
		switch (at.page)
		{
			case ITEM:
				return detail.buildItemDetail(at.name);
			case SOURCE:
				return detail.buildSourceDetail(at.name);
			case SKILL:
				return trackers.buildSkillDetail(at.name);
			case TRACKERS:
				return trackers.buildAllTrackers();
			case RECORDS:
				return journal.buildRecords();
			case CALENDAR:
				return journal.buildCalendar();
			case INFO:
				return journal.buildInfo();
			case TASK:
				return slayer.buildTaskDetail(at.index);
			case LEFT_SOURCE:
				return detail.buildLeftBehindDetail(at.name, null);
			case LEFT_ITEM:
				return detail.buildLeftBehindDetail(null, at.name);
			default:
				return pages.buildSheetPage(at.name);
		}
	}

	private JPanel buildView()
	{
		switch (view)
		{
			case JOURNAL:
				return journal.buildJournal();
			case LEDGER:
			case TRACKERS:
				return trackers.buildStats();
			case RECAP:
				return recap.build();
			case STANDING:
				return standing.buildSheet();
			case LOOT:
				return loot.buildDrops();
			case SLAYER:
				return slayer.buildSlayer();
			default:
				return home.buildHome();
		}
	}

	private JPanel subStrip()
	{
		JPanel strip = new JPanel(new BorderLayout());
		strip.setBackground(DARK);
		JPanel pills = new JPanel(new GridLayout(1, 0, 3, 3));
		pills.setBackground(DARK);
		for (View v : View.values())
		{
			if (v.tab == view.tab)
			{
				pills.add(pill(v.sub, v == view, 4, null, () -> show(v)));
			}
		}
		strip.add(pills, BorderLayout.NORTH);
		strip.add(vgap(6), BorderLayout.SOUTH);
		return strip;
	}

	private void paintBand(String stalled, String capture, String captureWhy)
	{
		band.setVisible(stalled != null || capture != null);
		if (!band.isVisible())
		{
			return;
		}
		boolean red = stalled != null;
		Color ink = red ? ColorScheme.PROGRESS_ERROR_COLOR : ACCENT;
		for (MouseListener l : band.getMouseListeners())
		{
			band.removeMouseListener(l);
		}
		if (!red)
		{
			band.addMouseListener(clicker(() ->
			{
				plugin.turnOnMissingCapture();
				update();
			}));
		}
		bandText.setText(red ? "Not saving the journal" : capture + "  ·  click to turn it on");
		bandText.setForeground(ink);
		band.setBackground(wash(ink));
		band.setOpaque(true);
		band.setToolTipText(red ? stalled : captureWhy);
		band.setCursor(red ? Cursor.getDefaultCursor() : Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
	}

	private boolean showingSitting()
	{
		return view == View.NOW && place == null && searchQuery().isEmpty();
	}

	private JPanel periodRow()
	{
		Window w = board.window();
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
		JLabel label = styled(new JLabel(w.label, JLabel.CENTER), FontManager.getRunescapeFont(), ACCENT);
		label.setToolTipText("Choose the period");
		link(label, () -> periodMenu().show(r, 0, r.getHeight()));
		r.add(label, BorderLayout.CENTER);
		return r;
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
		menuItem(menu, "Exact dates", period.from != null, this::askExactDates);
		return menu;
	}

	private void stepPeriod(int by)
	{
		period.step(by);
		rebuild();
	}

	private void askExactDates()
	{
		JTextField fromField = new JTextField(period.suggestedFrom().toString());
		JTextField toField = new JTextField(period.suggestedTo().toString());
		JPanel form = new JPanel(new GridLayout(0, 1, 0, 4));
		form.add(new JLabel("From (yyyy-mm-dd):"));
		form.add(fromField);
		form.add(new JLabel("To (yyyy-mm-dd):"));
		form.add(toField);
		if (JOptionPane.showConfirmDialog(this, form, "Exact dates", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION)
		{
			return;
		}
		LocalDate f = Period.parse(fromField.getText());
		LocalDate t = Period.parse(toField.getText());
		if (f == null || t == null)
		{
			JOptionPane.showMessageDialog(this, "Dates read as yyyy-mm-dd (or d/m/yyyy). Nothing changed.");
			return;
		}
		period.exact(f, t);
		rebuild();
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
		link(head, () ->
		{
			if (!openFolds.remove(key))
			{
				openFolds.add(key);
			}
			rebuildInPlace();
		});
		return head;
	}

	void foldHead(JPanel head, String fold, String tip)
	{
		if (foldOpen(fold))
		{
			part(head, BorderLayout.CENTER).setForeground(ACCENT);
		}
		head.setToolTipText(tip);
		folds(head, fold);
	}

	JPanel subHead(String label, String total, String fold)
	{
		JPanel head = row(label, total);
		styled(part(head, BorderLayout.CENTER), small(), DIM);
		head.setBorder(pad(3, 10, 1, 2));
		return folds(head, fold);
	}

	JPanel quietHead(String name, String count, String fold)
	{
		JPanel head = subHead(name.toUpperCase(Locale.ROOT), count, fold);
		head.setBorder(pad(7, 2, 1, 2));
		return head;
	}

	int cap(String key, int fallback)
	{
		return shown.getOrDefault(key, fallback);
	}

	int shownCap(String key)
	{
		return cap(key, FOLD_CAP);
	}

	void more(JPanel p, int size, int cap, boolean inset, IntConsumer reveal)
	{
		if (size > cap)
		{
			JPanel more = moreRow("Show " + fmt(size - cap) + " more", () ->
			{
				reveal.accept(size);
				rebuildInPlace();
			});
			p.add(inset ? nested(more) : more);
		}
	}

	void drillMore(JPanel p, String key, int size, int cap)
	{
		more(p, size, cap, false, n -> shown.put(key, n));
	}

	void addMore(JPanel p, String key, int size, int cap, boolean inset)
	{
		more(p, size, cap, inset, n -> shown.put(key, n));
	}

	JPanel backRow(BooleanSupplier copy)
	{
		JPanel r = Ui.backRow("< Back", copy == null ? "" : "copy", this::back);
		JLabel take = copy == null ? null : Pictures.copyLabel(r, "Copy this page as a picture");
		if (take != null)
		{
			take.addMouseListener(clicker(() -> Pictures.reportCopy(take, copy.getAsBoolean())));
		}
		return r;
	}

	JPanel backPage()
	{
		JPanel p = column();
		spaced(p, backRow(null), 4);
		return p;
	}

	boolean copyPage(Supplier<JPanel> page)
	{
		drawingCopy = true;
		try
		{
			return copyPicture(stripChrome(page.get()));
		}
		catch (RuntimeException e)
		{
			return false;
		}
		finally
		{
			drawingCopy = false;
		}
	}

	JLabel sprite(int itemId, String name, long qty)
	{
		return art.item(itemId, name, qty, this::openItem);
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
