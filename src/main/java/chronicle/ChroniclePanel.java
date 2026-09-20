/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the
 * BSD 2-Clause License (see LICENSE) are met.
 */
package chronicle;

import chronicle.counters.ExperienceStatTracker;
import chronicle.panel.HistoryProgress;
import chronicle.panel.StatRegistry;
import com.google.gson.JsonObject;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.IconTextField;
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.ui.components.materialtabs.MaterialTabGroup;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.OSType;

/**
 * The journal's face: a period row, four tabs (Record, Hiscores, Loot and
 * Trackers), a sub-tab strip under Record and Loot, a search field, and a
 * detail overlay over whichever board is open. Record's Now board reads the live
 * sitting; the period row above the tabs governs every other board. Lists mount
 * a bounded number of rows, and views rebuild on a tab or sub-tab switch, on a
 * push landing, and on Now a slow timer.
 */
class ChroniclePanel extends PluginPanel
{
	// Every date the panel prints, in one locale: the numbers next to them are
	// already forced to Locale.UK, and a JVM-default month name beside them reads
	// as two different clocks.
	private static final DateTimeFormatter DAY =
		DateTimeFormatter.ofPattern("d MMM", Locale.UK).withZone(ZoneId.systemDefault());
	private static final DateTimeFormatter TASK_DAY =
		DateTimeFormatter.ofPattern("d MMM yy", Locale.UK).withZone(ZoneId.systemDefault());
	private static final DateTimeFormatter FULL_DAY =
		DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK).withZone(ZoneId.systemDefault());
	private static final DateTimeFormatter MONTH_YEAR =
		DateTimeFormatter.ofPattern("MMMM yyyy", Locale.UK).withZone(ZoneId.systemDefault());
	private static final Color ACCENT_LIFETIME = ColorScheme.BRAND_ORANGE;
	private static final Color ACCENT_SESSION = new Color(85, 163, 90);
	private static final Color ACCENT_RED = new Color(196, 84, 74);

	/**
	 * What a tile's figure reads when the account HAS the thing: the same value a
	 * row's own name reads at, so a grid and a list on one screen agree.
	 *
	 * <p>The sheet used to draw this three ways at once. The activity and combat
	 * tiles were 165, the boss tiles were pure white at 255, and the rows beside
	 * them were the label default at 198 - three brightnesses for one meaning, on
	 * one screen, all of them meaning "you have this".
	 */
	private static final Color TILE_LIT = new Color(198, 198, 198);
	// Rows mounted per list before a "Show more" button.
	private static final int ROW_CAP = 30;
	// Every inset between the sidebar's own width and a row's label, named where it
	// is applied so a line that measures itself against them cannot drift out of
	// step with the layout. See chaseRoom().
	private static final int PANEL_INSET = 8;   // this panel's border, a side
	private static final int CARD_INSET = 8;    // a card's border, a side
	private static final int ROW_INSET = 2;     // a row's border, a side
	private static final int ROW_GAP = 8;       // a row's gap, name to value

	private enum View
	{
		HOME, DROPS, SLAYER, LOG, STATS, HISTORY, JOURNAL, KILLS, SHEET
	}

	/**
	 * The four tabs the panel carries, and the boards under each. The views above
	 * did not go anywhere: a tab and its sub-tab choose one, so every
	 * board that was already built here is reached a different way rather than
	 * rebuilt. What changes is the navigation, and that the period governs all of
	 * it from one place above the strip.
	 */
	private enum Tab
	{
		RECORD, HISCORES, LOOT, TRACKERS
	}

	private static final Map<Tab, String[]> SUBS = new java.util.EnumMap<>(Tab.class);

	static
	{
		// Named for what it holds rather than for its first child: two of the
		// three are lifetime, and a tab called "This session" whose Journal lists
		// other sittings is a worse lie than the scrolling it was meant to fix.
		SUBS.put(Tab.RECORD, new String[]{"Now", "Journal", "Ledger"});
		// The bosses and the skills were two boards built from ONE widget that
		// behaved in opposite ways on a click: a boss opened a card under its own
		// grid row, a skill took over the screen. They are one sheet now, in the
		// order the game's own hiscores panel puts them, which is the layout a
		// player already knows: the skills, the combat and total levels, the
		// activities, then the bosses.
		// No sub-tabs: the sheet is one board. Its activity tiles are the way into
		// the collection log, the diaries, the combat achievements and the quests,
		// which is what freed a whole tab for the loot.
		SUBS.put(Tab.HISCORES, new String[0]);
		SUBS.put(Tab.LOOT, new String[]{"Loot", "Slayer"});
		// Every counter in one place. Combat's used to hang off PvM's fourth
		// board, which put damage dealt and deaths a tab away from every other
		// tally for no reason a reader could have guessed.
		SUBS.put(Tab.TRACKERS, new String[0]);
	}

	private final ChroniclePlugin plugin;

	private final JPanel display = new JPanel(new BorderLayout());

	/**
	 * The one scroll pane, and the one view inside it, for the life of the panel.
	 *
	 * <p>Every rebuild used to hang a fresh JScrollPane: a new bar, a new UI with
	 * its own faded-out state, and a scroll position to put back by hand in two
	 * passes because a bar with no extent yet clamps to zero. The reader saw the
	 * thumb flash on every push. Swapping what is INSIDE the view instead leaves
	 * the viewport where it was, so there is nothing to restore and nothing to
	 * flash, and a board redrawn under somebody reading it simply changes.
	 */
	private final ScrollColumn canvas = new ScrollColumn();
	private final JScrollPane scrollPane = new JScrollPane(canvas,
		ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
		ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
	// The group gets no display panel: it swaps in each tab's own content
	// component, and ours are empty. rebuild() does the swapping.
	private final MaterialTabGroup tabGroup = new MaterialTabGroup();
	private final Map<Tab, MaterialTab> tabByTab = new java.util.EnumMap<>(Tab.class);
	// the sub-tab each tab was last left on, so coming back lands where you were
	private final Map<Tab, String> subByTab = new java.util.EnumMap<>(Tab.class);
	private final IconTextField searchField = new IconTextField();
	private final Timer searchDebounce;
	private final Timer homeTicker;

	private View view = View.HOME;
	private Tab tab = Tab.RECORD;
	// An item or a source under the glass, overlaying the current tab. Any item
	// or source row anywhere opens one; the back-stack unwinds the hops.
	private String detailItem;
	private String detailSource;
	// A skill under the glass, opened from its cell in the grid. Not on the back
	// stack: the grid is the only place it opens from, so Back is the grid.
	private String detailSkill;
	// Every tracker in one place, which no tab holds: the stats table is filed by
	// family and a reader who wants the whole sheet has nowhere to ask for it.
	private boolean allTrackers;
	// The counts-of-the-record page. Reached by typing "info" and nothing else:
	// it answers questions about the JOURNAL rather than about the account, and
	// a tab for it would be a tab most readers never want.
	private boolean showInfo;
	private final java.util.ArrayDeque<String[]> detailStack = new java.util.ArrayDeque<>();
	private String statsFamily = StatRegistry.FAMILIES[0];
	private int dropsShown = ROW_CAP;
	private String clogTab = "Bosses";
	private String clogPageSel;

	// Whether the journal is reaching disk. Nothing else in the panel shows it:
	// the views are served from memory and look the same either way.
	private final JLabel heartbeat = new JLabel();
	// the tab opens on the whole record; a window is a narrowing of it
	private String histGranularity = "Lifetime";
	// The period's END date (inclusive); the stepper moves it by one granule.
	private java.time.LocalDate histCursor = java.time.LocalDate.now();
	// Exact dates: non-null overrides the granularity pills. Set by clicking the
	// period label, cleared by any pill.
	private java.time.LocalDate histFrom;
	private java.time.LocalDate histTo;
	// The bundled taxonomy: tab -> page -> ordered slot names. Parsed lazily, the
	// first time any board asks for it.
	private static Map<String, Map<String, List<String>>> taxonomy;

	ChroniclePanel(ChroniclePlugin plugin)
	{
		super(false);
		this.plugin = plugin;

		watchForReturn();
		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(
			PANEL_INSET, PANEL_INSET, PANEL_INSET, PANEL_INSET));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
		north.setBackground(ColorScheme.DARK_GRAY_COLOR);

		// ── search ────────────────────────────────────────────────────────
		searchField.setIcon(IconTextField.Icon.SEARCH);
		searchField.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 16, 28));
		searchField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		searchField.setHoverBackgroundColor(ColorScheme.DARK_GRAY_HOVER_COLOR);
		searchDebounce = new Timer(150, e -> onSearchChanged());
		searchDebounce.setRepeats(false);
		// Enter opens whatever the query resolves to, else the first result
		// group's tab.
		searchField.addActionListener(e ->
		{
			String q = searchQuery();
			if (q.isEmpty())
			{
				return;
			}
			// the one query that names a VIEW rather than a thing in the record
			if (q.equalsIgnoreCase("tracker") || q.equalsIgnoreCase("trackers"))
			{
				openAllTrackers();
				return;
			}
			// exact (or singular) source name wins
			for (LocalStore.SourceRow r : sources())
			{
				if (r.name.equalsIgnoreCase(q)
					|| (q.endsWith("s") && r.name.equalsIgnoreCase(q.substring(0, q.length() - 1))))
				{
					openSource(r.name);
					return;
				}
			}
			// exact item name
			for (LocalStore.SourceRow r : sources())
			{
				for (LocalStore.BagItem b : plugin.sourceItems(r.name))
				{
					if (b.name.equalsIgnoreCase(q))
					{
						openItem(b.name);
						return;
					}
				}
			}
			// best containing item, then containing source
			String bestItem = null;
			long bestVal = -1;
			String ql = q.toLowerCase(Locale.ROOT);
			for (LocalStore.SourceRow r : sources())
			{
				for (LocalStore.BagItem b : plugin.sourceItems(r.name))
				{
					if (b.name.toLowerCase(Locale.ROOT).contains(ql) && b.value > bestVal)
					{
						bestVal = b.value;
						bestItem = b.name;
					}
				}
			}
			if (bestItem != null)
			{
				openItem(bestItem);
				return;
			}
			for (LocalStore.SourceRow r : sources())
			{
				if (r.name.toLowerCase(Locale.ROOT).contains(ql))
				{
					openSource(r.name);
					return;
				}
			}
			MaterialTab target = searchJump != null ? tabByTab.get(tabFor(searchJump)) : null;
			if (target != null)
			{
				// The page is read off first: selecting the tab clears it, along
				// with the query that named it.
				final String page = searchJumpPage;
				// select() returns early on the tab already showing, so its
				// onSelectEvent, the only place the query is cleared and the
				// panel rebuilt, never fires. Do that work here instead.
				if (target.isSelected())
				{
					applyTab(searchJump);
				}
				else
				{
					tabGroup.select(target);
				}
				if (page != null)
				{
					sheetPage = page;
					rebuild();
				}
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
		// ── tabs, then search ──
		periodHolder.setBackground(ColorScheme.DARK_GRAY_COLOR);
		// The strip's other rows centre themselves; a LEFT aligned holder among
		// them is pushed right by BoxLayout and loses the width its label needs,
		// which is how "September 2026" came to draw as "September 20...".
		periodHolder.setAlignmentX(Component.CENTER_ALIGNMENT);
		// BoxLayout hands a component its maximum, and a JPanel's default maximum
		// is its preferred, which for an empty holder is nothing at all. Left to
		// that, the row would never take a pixel of the strip.
		periodHolder.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
		periodHolder.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 16, 22));
		north.add(periodHolder);
		north.add(vgap(3));

		tabGroup.setLayout(new GridLayout(1, 4, 2, 0));
		// tab_history is the set's clock, which is the only clock anywhere: not
		// one of the 4,057 named sprites in runelite-api is a clock, an hourglass
		// or a watch, so an all-sprite strip could not have had one.
		addTab("tab_history.png", "Record", Tab.RECORD);
		addTab("tab_pvm.png", "Hiscores", Tab.HISCORES);
		addTab("tab_log.png", "Loot", Tab.LOOT);
		addTab("tab_stats.png", "Trackers", Tab.TRACKERS);
		north.add(tabGroup);
		north.add(vgap(7));
		north.add(searchField);
		north.add(vgap(8));

		add(north, BorderLayout.NORTH);
		add(display, BorderLayout.CENTER);

		// Hung once. Every rebuild swaps what is inside the view, so the viewport
		// keeps the reader's position by simply never being told to move, and the
		// bar keeps its own faded-out state instead of being born again at full
		// brightness on every push.
		canvas.setBackground(ColorScheme.DARK_GRAY_COLOR);
		scrollPane.setBorder(null);
		scrollPane.getVerticalScrollBar().setUnitIncrement(14);
		overlayBar(scrollPane);
		display.add(scrollPane, BorderLayout.CENTER);

		// Home refreshes on a slow tick while it is the visible view. Nothing in
		// the panel rebuilds per game tick.
		homeTicker = new Timer(3000, e ->
		{
			// A page opened from Home leaves the view on HOME, and a tick rebuilds
			// the whole display: the scroll pane is replaced and the bar re-set
			// under a reader who is in the middle of scrolling it. On the trackers
			// page, which runs to two hundred rows and four thousand pixels, that
			// reads as the scroll itself lagging. Only the sitting refreshes, and
			// showingSitting is the one place that knows what the sitting is; this
			// used to keep its own copy of that list and fell behind it twice.
			// A draw owed and never paid: the record moved while a menu was open
			// over the board, and the menu closing is not an event anything here
			// listens for. Paid on the next tick of this timer instead.
			if (staleWhileHidden && everShown && getWrappedPanel().isShowing()
				&& !popupShowing())
			{
				staleWhileHidden = false;
				update();
				return;
			}
			if (showingSitting())
			{
				// Through update() and not straight to rebuild(): this used to draw
				// the board every three seconds whether or not anybody was looking
				// at it, which is the exact waste the visibility guard exists to
				// stop, and it is owed-and-repaid by the branch above when they
				// come back. It keeps the scroll for the same reason update() does.
				update();
			}
		});
		homeTicker.start();
		// Swing waits 750ms before showing a tooltip, which on a board whose tiles
		// carry their names in one is most of a second of nothing every time the
		// cursor moves. Shortened here, and given long enough on screen to read.
		javax.swing.ToolTipManager.sharedInstance().setInitialDelay(220);
		javax.swing.ToolTipManager.sharedInstance().setDismissDelay(20_000);

		JPanel manageRow = new JPanel();
		manageRow.setLayout(new BoxLayout(manageRow, BoxLayout.X_AXIS));
		manageRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		// Match the siblings, or BoxLayout centres this row and shunts it half a
		// panel right.
		manageRow.setAlignmentX(Component.CENTER_ALIGNMENT);
		manageRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 16));
		// Import and the manual push moved into the plugin settings; what is left
		// of this row is the heartbeat, which is the one thing here that reports
		// rather than acts.
		manageRow.add(javax.swing.Box.createHorizontalGlue());
		manageRow.add(heartbeat);
		north.add(manageRow);

		// Prime the History tab's reads off the EDT, before anyone opens it.
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
		tabByTab.put(target, mt);
		if (target == Tab.RECORD)
		{
			tabGroup.select(mt);
		}
	}

	/**
	 * The sub-tabs under the current tab. None where a tab has one board, and
	 * none while a drill or a query has the screen: those are a different screen
	 * rather than a lens on this one, and they carry their own way back.
	 */
	private JPanel subStrip()
	{
		String[] subs = SUBS.get(tab);
		if (subs == null || subs.length == 0 || !searchQuery().isEmpty()
			|| detailItem != null || detailSource != null || detailSkill != null
			|| allTrackers || detailTask >= 0
			|| leftBehindSource != null || leftBehindItem != null)
		{
			return null;
		}
		JPanel strip = new JPanel(new GridLayout(1, subs.length, 3, 3));
		strip.setBackground(ColorScheme.DARK_GRAY_COLOR);
		String on = sub();
		for (String name : subs)
		{
			JLabel pill = new JLabel(name, JLabel.CENTER);
			pill.setOpaque(true);
			pill.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
			pill.setFont(FontManager.getRunescapeSmallFont());
			pill.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			pill.setForeground(name.equals(on) ? accent() : ColorScheme.LIGHT_GRAY_COLOR.darker());
			pill.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
			pill.addMouseListener(clicker(() ->
			{
				subByTab.put(tab, name);
				applyCommon();
			}));
			strip.add(pill);
		}
		return strip;
	}

	/** The sub-tab showing under the current tab, defaulting to its first. */
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

	/** Which board a tab and its sub-tab are asking for. */
	private View viewOf()
	{
		switch (tab)
		{
			case HISCORES:
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
					case "Now":
					default:
						return View.HOME;
				}
		}
	}

	/** Where search's Enter lands a board that is now a tab plus a sub-tab. */
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
				return Tab.HISCORES;
			// The Trackers tab IS the counters board: viewOf maps TRACKERS to
			// STATS, and without the return leg STATS fell through to Record, so
			// asking for the counters landed a tab away from them. The Ledger
			// under Record is also a STATS board, but it is one family of the
			// table and Trackers is the whole of it.
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
				return "";
			case DROPS:
				return "Loot";
			case SLAYER:
				return "Slayer";
			case STATS:
				return "Ledger";
			case JOURNAL:
				return "Journal";
			case HOME:
			default:
				return "Now";
		}
	}

	/** Show a tab from scratch: no drilled detail, no query, nothing paged out.
	 *  Every open detail is dropped, or rebuild() would paint it over the tab. */
	private void applyTab(Tab target)
	{
		tab = target;
		applyCommon();
	}

	/** Open the tab and sub-tab a board lives under. Search's Enter uses this. */
	private void applyTab(View target)
	{
		tab = tabFor(target);
		subByTab.put(tab, subFor(target));
		applyCommon();
	}

	private void applyCommon()
	{
		// The tab and its sub-tab choose the board; two of them also choose which
		// lens of a shared board, since Skilling and PvM both read facets of the
		// one Progression builder, and Combat and the Ledger are families of the
		// one stats table. This belongs to navigation, not to drawing: rebuild()
		// deriving `view` every time would ignore anything that set it directly,
		// which is how the preview harness reaches a board.
		view = viewOf();
		sheetPage = null;
		if (tab == Tab.HISCORES)
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
		// dropsByKind is NOT cleared. It is a lens, like "Left behind" beside
		// it, and a reader who chose to read the ledger by kind has not asked
		// to be put back on sources every time they look at another tab. What
		// IS cleared is where they were standing inside it.
		drillShown.clear();
		histListShown.clear();
		detailItem = null;
		detailSource = null;
		detailSkill = null;
		// A tab click leaves them too, which is the half that bricked the strip.
		leaveSentPage();
		detailTask = -1;
		leftBehindSource = null;
		leftBehindItem = null;
		detailStack.clear();
		clearSearch();
		rebuild();
	}


	// ------------------------------------------------------------------
	// The boss board
	// ------------------------------------------------------------------

	/** One boss on the board: the hiscores name, and the game's own icon for it. */
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
	// the window's kill movement, computed once per build rather than per cell
	private Map<String, Long> movedKcs;
	// and the kills the dated roll can place inside it, for the keys the spine
	// has no base for
	private Map<String, Long> rolledKcs;
	// whether any cell on this build was counted off the roll rather than the spine
	private boolean rollUsed;
	// which cell has its card open, if any

	/**
	 * The board is the hiscores roster, which is the list the official plugin
	 * shows. The collection log is NOT the list: it files by DROP TABLE, so it
	 * gives one page to all three Dagannoth Kings, one to both Gauntlets, and
	 * names three of its own pages "Callisto and Artio", "Venenatis and Spindel"
	 * and "Vet'ion and Calvar'ion". The hiscores counts ENCOUNTERS, which is what
	 * a kill count means.
	 *
	 * <p>The icons are the game's own IconBoss25x25, fetched through the same
	 * SpriteManager the facet strip uses. Three of them are pooled by the game the
	 * way the log pools its pages, so Callisto and Artio wear one icon between
	 * them; the count beside it is still each encounter's own.
	 */
	private static synchronized List<Boss> bossRoster(com.google.gson.Gson gson)
	{
		if (bossRoster != null)
		{
			return bossRoster;
		}
		List<Boss> out = new ArrayList<>();
		// RUNELITE'S OWN LIST FIRST.
		//
		// HiscoreSkill is the enum the official hiscores panel draws from, and it
		// carries the name, the type and the sprite id: everything the bundled file
		// held. Reading it means a boss Jagex adds appears the week RuneLite ships
		// the constant, instead of waiting on a plugin update and a Hub review, and
		// the sprite arrives with it.
		//
		// The bundle stays as the fallback, for a client whose enum has moved.
		try
		{
			for (net.runelite.client.hiscore.HiscoreSkill s
				: net.runelite.client.hiscore.HiscoreSkill.values())
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
				com.google.gson.JsonArray arr = gson.fromJson(
					new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8),
					com.google.gson.JsonArray.class);
				for (com.google.gson.JsonElement e : arr)
				{
					JsonObject o = e.getAsJsonObject();
					out.add(new Boss(o.get("name").getAsString(),
						o.has("sprite") ? o.get("sprite").getAsInt() : -1));
				}
			}
		}
		catch (Exception ex)   // noqa: a missing resource leaves the board empty
		{
			// the same silence the taxonomy keeps: an empty board, not a stack trace
		}
		bossRoster = out;
		return out;
	}

	/** Where the hiscores and the collection log name one fight differently. */
	private static final Map<String, String> LOG_PAGE_FOR = new LinkedHashMap<>();
	private static final Map<String, String> PAYS_OUT = new LinkedHashMap<>();

	static
	{
		LOG_PAGE_FOR.put("TzTok-Jad", "The Fight Caves");
		LOG_PAGE_FOR.put("TzKal-Zuk", "The Inferno");
		LOG_PAGE_FOR.put("Sol Heredit", "Fortis Colosseum");
		// One page counts both, and each of them is its own row on the board.
		LOG_PAGE_FOR.put("The Corrupted Gauntlet", "The Gauntlet");
		// Where a fight's takings are filed under another name entirely. NOT the
		// creatures inside it -- a crystalline bear's shards are not the
		// Gauntlet's loot -- but the payout at the end of it, which the ledger
		// files against the fight that hands it over.
		PAYS_OUT.put("The Gauntlet", "Crystalline Hunllef");
		PAYS_OUT.put("The Corrupted Gauntlet", "Corrupted Hunllef");
	}

	/**
	 * How many of a boss have been killed. The collection log page's own header
	 * counter is NOT a kill count and must not be read as one: Wintertodt's line
	 * counts rewards claimed, so it says 1,078 where 447 were killed, and a page
	 * not opened in a while is simply stale, which is how Vorkath came to read 19
	 * against 156.
	 *
	 * <p>Two sources are honest, both per encounter and both only growing: the
	 * game's own Kill Log, and the killCount the game stamped on a loot event.
	 * Either can be the fresher, so the larger wins. The page counter answers
	 * only where neither of them has anything to say.
	 */
	// Per build: the best kill count known for each KIND, from the chat lines and
	// the ledger's own sources. Cleared with the other per-build memos.
	private Map<String, Long> kcByKind;

	private Map<String, Long> kcByKind()
	{
		if (kcByKind != null)
		{
			return kcByKind;
		}
		Map<String, Long> out = new LinkedHashMap<>();
		for (Map.Entry<String, Long> e : plugin.killCounts().entrySet())
		{
			out.merge(LocalStore.chatKind(e.getKey()), e.getValue(), Math::max);
		}
		for (LocalStore.SourceRow r : sources())
		{
			out.merge(LocalStore.kindOf(r.name), (long) r.kc, Math::max);
		}
		kcByKind = out;
		return out;
	}

	private long bossKills(String name)
	{
		JsonObject cl = clogNow();
		long best = Math.max(0, lookup(cl, "slayer_kcs", name));
		String kind = LocalStore.kindOf(name);
		// The chat line, which the reconciliation treats as first-class and this
		// board did not read at all. It arrives on the kill with nothing opened,
		// where the Kill Log above only moves when a player goes and looks; a
		// board consulting the log alone could fall past both and land on the
		// page counter its own comment below calls a lie.
		//
		// Both sources are indexed by kind once per build rather than walked per
		// boss. Walked, this was the roster times the ledger - seventy one bosses
		// against a hundred and eighty sources, with a string normalised on every
		// pair - and it was most of the thirty seven milliseconds this board took.
		Long byKind = kcByKind().get(kind);
		if (byKind != null)
		{
			best = Math.max(best, byKind);
		}
		if (best > 0)
		{
			return best;
		}
		// The page's own LABELLED line before its headline number. kcs keeps
		// whichever count came first on the page, which may be counting rewards,
		// and on a journal written before best times were turned away it may be
		// half of one: the Gauntlet's page reads 55 where 31 were completed.
		for (Map.Entry<String, Long> ln : pageLines(name))
		{
			String said = ln.getKey().toLowerCase(Locale.ROOT);
			if (said.contains("kill") || said.contains("completion"))
			{
				return ln.getValue();
			}
		}
		String page = LOG_PAGE_FOR.containsKey(name) ? LOG_PAGE_FOR.get(name) : name;
		return Math.max(0, lookup(cl, "kcs", page));
	}

	/**
	 * A count out of one of the clog snapshot's maps, found without caring how the
	 * key was cased. The journal writes page names as the game spells them, but
	 * nothing guarantees that of an imported or older snapshot, and a board that
	 * silently reads zero for a boss it has the count for is worse than a slow
	 * one. Minus one where the map has no such key at all.
	 */
	private static long lookup(JsonObject clog, String map, String key)
	{
		if (clog == null || !clog.has(map) || !clog.get(map).isJsonObject())
		{
			return -1;
		}
		JsonObject o = clog.getAsJsonObject(map);
		com.google.gson.JsonElement v = o.get(key);
		if (v != null)
		{
			return safeLong(v);
		}
		for (Map.Entry<String, com.google.gson.JsonElement> e : o.entrySet())
		{
			if (e.getKey().equalsIgnoreCase(key))
			{
				return safeLong(e.getValue());
			}
		}
		return -1;
	}

	/**
	 * What the window moved this boss's count by. A lifetime is the count itself;
	 * a narrower window is the distance between the two baselines bounding it,
	 * read off the kcs the journal writes on every line. Minus one where the
	 * spine cannot answer the window at all.
	 */
	private long bossKillsInWindow(String name)
	{
		if (wholeRecord())
		{
			return bossKills(name);
		}
		// The sitting is answered off its own roll entry and not off the spine,
		// whose two ends are both today's. Zero rather than a dash where the
		// entry names no kill of this thing: the entry covers the whole sitting,
		// so "none yet" is an answer it can actually give.
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
			// HistoryLog.gained, NOT a hand-rolled closing minus opening. A kill
			// count absent from the opening line was never recorded rather than
			// zero, so subtracting reads its whole standing figure as this
			// period's kills: the week a species first reached the journal, every
			// one of them arrived at once. gained() keeps the earliest recorded
			// base and DROPS a key that has no base at all, which is the honest
			// answer -- the record cannot say what it does not hold.
			// A period reaching today closes on the counts as they stand, not on
			// the spine's newest line. The spine is written once a day, so a boss
			// killed this afternoon was not in this week's figure until tomorrow.
			// Merged upward: these only ever grow, so the larger of the two is
			// the later of the two.
			movedKcs = HistoryLog.gained(s.opening.kcs, s.earliest.kcs,
				closingNow(s.closing.kcs, plugin.killCounts()));
		}
		Long moved = movedKcs.get(name);
		if (moved == null)
		{
			for (Map.Entry<String, Long> e : movedKcs.entrySet())
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
		// The spine has no base to measure from, but that does not mean the
		// record cannot date these kills: the loot roll keeps one entry a day
		// per source, so a species whose count only reached the journal today is
		// still dated by what it dropped. Sarachnis was killed 22 times today and
		// drew a dash, because the spine first heard of it this morning.
		Long rolled = rolledKills(name);
		if (rolled != null)
		{
			rollUsed = true;
			return rolled;
		}
		// minus one, not zero: a dash says the record cannot answer, and drawing
		// a nought would claim it counted none
		return -1;
	}

	/**
	 * Kills the loot roll dates inside the window, per source. A floor rather
	 * than a count: it sees a kill only where the kill dropped something. Null
	 * where the roll holds nothing for this source, or holds no day of the window
	 * at all.
	 */
	private Long rolledKills(String name)
	{
		if (plugin.lootRollFrom() <= 0)
		{
			return null;
		}
		if (rolledKcs == null)
		{
			Window w = window();
			rolledKcs = new LinkedHashMap<>();
			// The sitting keeps its own entry in this same shape, so it is
			// counted the same way rather than declining to answer.
			LocalStore.LootWindow win = sessionPeriod()
				? plugin.sessionLootWindow() : plugin.lootBetween(w.start, w.end);
			for (String[] r : win.sources)
			{
				long n = safeParse(r[1]);
				if (n > 0)
				{
					rolledKcs.put(LocalStore.kindOf(r[0]), n);
				}
			}
		}
		return rolledKcs.get(LocalStore.kindOf(name));
	}

	/**
	 * Whether any cell on the board is counted off the roll rather than the
	 * spine, and the roll does not reach the window's start. Those cells are a
	 * floor over part of the window, which the board has to say once at the top
	 * rather than leave as a number meaning something different from its
	 * neighbours.
	 */
	private java.time.LocalDate rollShortOf()
	{
		long from = plugin.lootRollFrom();
		if (from <= 0)
		{
			return null;
		}
		Window w = window();
		java.time.LocalDate began = java.time.Instant.ofEpochMilli(from)
			.atZone(ZoneId.systemDefault()).toLocalDate();
		return began.isAfter(w.start) ? began : null;
	}

	/**
	 * Every counter this fight's log page carries, by the name the log gives it.
	 * They belong on the card because they answer a different question than the
	 * kill count does: Wintertodt's "Rewards claimed" said 1,078 where 447 were
	 * killed. A counter that only repeats the kill count is left out, however
	 * many of them the page holds, and a line naming another fight has already
	 * gone to that fight in {@link #lineBelongsTo}, so the Gauntlet's card never
	 * carries the Corrupted Gauntlet's completion count.
	 */
	private List<Map.Entry<String, Long>> logLines(String boss)
	{
		List<Map.Entry<String, Long>> out = pageLines(boss);
		// A line that only restates the count the card already carries is noise.
		// What is worth reading beside it is a line counting something ELSE:
		// Wintertodt's rewards claimed against its kills.
		long kills = bossKills(boss);
		out.removeIf(ln ->
		{
			String said = ln.getKey().toLowerCase(Locale.ROOT);
			return ln.getValue() == kills
				&& (said.contains("kill") || said.contains("completion"));
		});
		return out;
	}

	/** The page's lines that are this fight's, as the page wrote them. */
	/**
	 * How much of one collection log tab the game says is held.
	 *
	 * <p>Read off cat_counts, which the capture takes from a pair of varps per
	 * tab and syncs at login. It is the game's own arithmetic over the whole tab,
	 * which is not the same as adding up the page fractions this board draws:
	 * those count slots, and one item can sit on several pages.
	 */
	private String tabStanding(JsonObject cl, String tab)
	{
		if (cl == null || !cl.has("cat_counts") || !cl.get("cat_counts").isJsonObject())
		{
			return null;
		}
		JsonObject counts = cl.getAsJsonObject("cat_counts");
		String key = tab.toLowerCase(Locale.ROOT);
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

	/**
	 * The kill count to print beside each collection log page, keyed lowercase.
	 *
	 * <p>Where the page's own header lines were captured they are the authority,
	 * and LocalStore already knows which of them is a kill count rather than a
	 * best time or a tally of rewards. Where they were not, the unlabelled figure
	 * beside them is all there is, and it is right on a page whose header carried
	 * a single number, which is most of them.
	 *
	 * <p>The two never overlap, which is what makes this safe: a page can only
	 * disagree with itself when its header carries several numbers, and such a
	 * page always has lines. Both of the owner's own disagreements are of that
	 * kind. Wintertodt's bare figure is 1,078 rewards claimed against 447 killed,
	 * and Tempoross's is 46, a personal best, which is a TIME printed as a number
	 * of kills.
	 *
	 * <p>A page whose lines are real but name no kills gets no figure at all.
	 * Barbarian Assault counted high level gambles; that is not a kill count, and
	 * there is no honest way to print it as one.
	 */
	private static Map<String, Long> pageCounts(JsonObject cl)
	{
		Map<String, Long> out = new LinkedHashMap<>();
		if (cl == null)
		{
			return out;
		}
		java.util.Set<String> lined = new java.util.HashSet<>();
		if (cl.has("kc_lines") && cl.get("kc_lines").isJsonObject())
		{
			for (String pageName : cl.getAsJsonObject("kc_lines").keySet())
			{
				lined.add(pageName.toLowerCase(Locale.ROOT));
			}
		}
		if (cl.has("kcs") && cl.get("kcs").isJsonObject())
		{
			for (Map.Entry<String, com.google.gson.JsonElement> e
				: cl.getAsJsonObject("kcs").entrySet())
			{
				String key = e.getKey().toLowerCase(Locale.ROOT);
				if (!lined.contains(key))
				{
					out.merge(key, safeLong(e.getValue()), Math::max);
				}
			}
		}
		for (Map.Entry<String, Long> e : LocalStore.pageKillLines(cl).entrySet())
		{
			out.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
		}
		return out;
	}

	/**
	 * Every counter line a collection log page's header carried, verbatim.
	 *
	 * <p>The row beside it can print only one number, and only when that number is
	 * honestly a kill count. The rest of what the page said is real and is worth
	 * keeping: which is a best time, which is rewards claimed, which is a
	 * completion count. It goes here rather than into the row, where it would be
	 * a wall of text in a 242 pixel column.
	 */
	private static String pageHeaderTip(JsonObject cl, String page)
	{
		if (cl == null || !cl.has("kc_lines") || !cl.get("kc_lines").isJsonObject())
		{
			return null;
		}
		JsonObject pages = cl.getAsJsonObject("kc_lines");
		com.google.gson.JsonElement found = null;
		for (Map.Entry<String, com.google.gson.JsonElement> e : pages.entrySet())
		{
			if (e.getKey().equalsIgnoreCase(page))
			{
				found = e.getValue();
				break;
			}
		}
		if (found == null || !found.isJsonObject() || found.getAsJsonObject().size() == 0)
		{
			return null;
		}
		List<String> labels = new ArrayList<>();
		List<String> figures = new ArrayList<>();
		for (Map.Entry<String, com.google.gson.JsonElement> ln
			: found.getAsJsonObject().entrySet())
		{
			labels.add(ln.getKey());
			figures.add(fmt(safeLong(ln.getValue())));
		}
		return tip(page, labels.toArray(new String[0]), figures.toArray(new String[0]));
	}

	private List<Map.Entry<String, Long>> pageLines(String boss)
	{
		List<Map.Entry<String, Long>> out = new ArrayList<>();
		JsonObject cl = clogNow();
		if (cl == null || !cl.has("kc_lines") || !cl.get("kc_lines").isJsonObject())
		{
			return out;
		}
		String page = LOG_PAGE_FOR.containsKey(boss) ? LOG_PAGE_FOR.get(boss) : boss;
		JsonObject pages = cl.getAsJsonObject("kc_lines");
		com.google.gson.JsonElement found = pages.get(page);
		if (found == null)
		{
			for (Map.Entry<String, com.google.gson.JsonElement> e : pages.entrySet())
			{
				if (e.getKey().equalsIgnoreCase(page))
				{
					found = e.getValue();
					break;
				}
			}
		}
		if (found == null || !found.isJsonObject())
		{
			return out;
		}
		for (Map.Entry<String, com.google.gson.JsonElement> ln
			: found.getAsJsonObject().entrySet())
		{
			long n = safeLong(ln.getValue());
			if (n <= 0 || !lineBelongsTo(boss, ln.getKey()))
			{
				continue;
			}
			out.add(new java.util.AbstractMap.SimpleEntry<>(ln.getKey(), n));
		}
		return out;
	}

	/**
	 * Whether this source is something killed. The roster of fights, or a species
	 * the game's own Kill Log counts; everything else pays out without dying --
	 * the Rift is searched, a casket opened, a reward cart emptied.
	 */
	private boolean isKillSource(String name)
	{
		// And anything a slayer task was fought against. The Kill Log lists the
		// assignment rather than the thing, so it has no line for a superior:
		// Choke devil read "Times looted 13" while the journal held thirteen task
		// kills of it. A monster in a task's own monsters map is a thing this
		// account killed, which is the whole question being asked.
		return killKinds().contains(LocalStore.kindOf(name))
			|| taskKillsEver().containsKey(name);
	}

	// Per build: every kind the record knows to be killed rather than searched,
	// gathered or opened. Built once instead of walking the roster and the Kill
	// Log again for every row of a board.
	private java.util.Set<String> killKinds;

	private java.util.Set<String> killKinds()
	{
		if (killKinds != null)
		{
			return killKinds;
		}
		java.util.Set<String> out = new java.util.HashSet<>();
		for (Boss b : bossRoster(plugin.gson()))
		{
			out.add(LocalStore.kindOf(b.name));
		}
		JsonObject cl = clogNow();
		if (cl != null && cl.has("slayer_kcs") && cl.get("slayer_kcs").isJsonObject())
		{
			for (String said : cl.getAsJsonObject("slayer_kcs").keySet())
			{
				out.add(LocalStore.kindOf(said));
			}
		}
		killKinds = out;
		return out;
	}

	/** One source's takings, openable. */
	private JPanel dropsRow(String label, LocalStore.SourceRow r)
	{
		long qty = 0;
		for (LocalStore.BagItem it : plugin.sourceItems(r.name))
		{
			qty += it.qty;
		}
		JPanel row = row(label, fmt(qty) + " \u00b7 " + gp(r.value) + " gp", null);
		row.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		final String open = r.name;
		row.addMouseListener(clicker(() -> openSource(open)));
		return row;
	}

	/** "Reward cart (Wintertodt)" is Wintertodt's, and is not a boss of its own. */
	private static boolean namesInBrackets(String source, String boss)
	{
		int open = source.lastIndexOf('(');
		int close = source.lastIndexOf(')');
		return open > 0 && close > open
			&& LocalStore.kindOf(source.substring(open + 1, close))
				.equals(LocalStore.kindOf(boss));
	}

	private static String beforeBracket(String source)
	{
		int open = source.lastIndexOf('(');
		return open > 0 ? source.substring(0, open).trim() : source;
	}

	/** m:ss, or h:mm:ss past the hour. */
	private static String clock(long seconds)
	{
		long h = seconds / 3600;
		long m = (seconds % 3600) / 60;
		long s = seconds % 60;
		return h > 0 ? String.format(Locale.UK, "%d:%02d:%02d", h, m, s)
			: String.format(Locale.UK, "%d:%02d", m, s);
	}

	/** The page's best times that are this fight's, in seconds. */
	private List<Map.Entry<String, Long>> bestTimes(String boss)
	{
		List<Map.Entry<String, Long>> out = new ArrayList<>();
		JsonObject cl = clogNow();
		if (cl == null || !cl.has("pb_lines") || !cl.get("pb_lines").isJsonObject())
		{
			return out;
		}
		String page = LOG_PAGE_FOR.containsKey(boss) ? LOG_PAGE_FOR.get(boss) : boss;
		com.google.gson.JsonElement found = null;
		for (Map.Entry<String, com.google.gson.JsonElement> e
			: cl.getAsJsonObject("pb_lines").entrySet())
		{
			if (e.getKey().equalsIgnoreCase(page))
			{
				found = e.getValue();
				break;
			}
		}
		if (found == null || !found.isJsonObject())
		{
			return out;
		}
		for (Map.Entry<String, com.google.gson.JsonElement> ln
			: found.getAsJsonObject().entrySet())
		{
			long secs = safeLong(ln.getValue());
			if (secs > 0 && lineBelongsTo(boss, ln.getKey()))
			{
				out.add(new java.util.AbstractMap.SimpleEntry<>(ln.getKey(), secs));
			}
		}
		return out;
	}

	/**
	 * Whether a page's line is this boss's business.
	 *
	 * <p>Two lines it is not. A PERSONAL BEST IS A TIME: the count is read off the
	 * last ": number" on the line, so "Personal Best: 8:55" came back as 55 under
	 * the label "Personal Best: 8". Capture drops those now, but a journal that
	 * already holds one keeps it, since these lines are floor-merged and never
	 * removed, so they are turned away here too. A label left ending in a digit is
	 * the tell.
	 *
	 * <p>And a page can count more than one fight. The Gauntlet's page carries the
	 * corrupted completions as well, and the corrupted Gauntlet is its own row on
	 * the board: a line goes to whichever roster name it names most exactly, so
	 * "Corrupted Gauntlet completion count" goes to that row rather than being
	 * read twice, once on each.
	 */
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
		String said = label.toLowerCase(Locale.ROOT);
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
		// A line naming no fight at all belongs to whoever the page is: "Personal
		// Best" on the Gauntlet's page is the Gauntlet's. A fight read off
		// somebody else's page has to be named to claim one, and a qualifier that
		// names it is enough -- "Personal Best Corrupted" never says Gauntlet.
		String page = LOG_PAGE_FOR.containsKey(boss) ? LOG_PAGE_FOR.get(boss) : boss;
		if (!page.equalsIgnoreCase(boss))
		{
			return namesOneOf(said, words(mine, bare(page)));
		}
		for (Boss other : bossRoster(plugin.gson()))
		{
			String onPage = LOG_PAGE_FOR.containsKey(other.name)
				? LOG_PAGE_FOR.get(other.name) : other.name;
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

	/** The words of a name that the other name does not also carry. */
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

	/** A roster name as a line would write it: no leading "the". */
	private static String bare(String name)
	{
		String n = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
		return n.startsWith("the ") ? n.substring(4) : n;
	}

	/**
	 * One sheet, in the order the game's own hiscores panel puts it: the skills
	 * grid, the combat and total levels, the activities, then the bosses.
	 *
	 * <p>Nothing here is new. The two grids were already built from one widget
	 * and were the panel's sharpest inconsistency, because a boss cell opened a
	 * card under its own grid row while a skill cell of the same design took over
	 * the screen. Stacked in the layout a player already knows, they are one board
	 * with one verb, and the reader stops having to learn which grid they are on.
	 */
	private JPanel buildSheet()
	{
		JPanel p = column();
		sheetBandDrawn = false;
		String was = histFacet;
		try
		{
			histFacet = "Skills";
			p.add(buildHistory());
		}
		finally
		{
			histFacet = was;
			sheetBandDrawn = false;
		}
		p.add(activitySheet());
		p.add(buildKills());
		return p;
	}

	/**
	 * The activities band, which is also the way into everything the sheet does
	 * not draw itself.
	 *
	 * <p>label, the source it reads, and the page a click opens or "" for a tile
	 * that is only a figure. Five of these carry a destination, and between them
	 * they are why the collection log stopped needing a tab of its own.
	 */
	private static final String[][] ACTIVITIES = {
		{"Clues", "", "clues"},
		{"Rifts closed", "Guardians of the Rift", ""},
		{"Soul Wars", "Soul Wars", ""},
		{"Collections", "", "log"},
		{"Quests", "", "quests"},
		{"Diaries", "", "diaries"},
	};

	/**
	 * The emblem each activity wears.
	 *
	 * <p>These used to be worked out the way a boss tile's is, by asking the
	 * ledger for the dearest thing that source ever dropped. For a boss that is a
	 * fair likeness. For an activity it is nonsense: Rifts wore whichever runes
	 * were priciest that week, Clues wore some hard-clue reward, and the four
	 * with no drop source behind them at all - Collections, Quests, Diaries and
	 * Combat - fell through to a generic tab icon and were indistinguishable from
	 * each other.
	 *
	 * <p>Four of the seven ARE hiscores rows, so they carry their own art and it
	 * is the same art the official panel draws; taking it from the enum means it
	 * follows the client rather than a number written down here. The other three
	 * are not on the hiscores and take the game's own tab icons.
	 */
	private static int activitySprite(String label)
	{
		net.runelite.client.hiscore.HiscoreSkill own = null;
		switch (label)
		{
			case "Clues":
				own = net.runelite.client.hiscore.HiscoreSkill.CLUE_SCROLL_ALL;
				break;
			case "Rifts closed":
				own = net.runelite.client.hiscore.HiscoreSkill.RIFTS_CLOSED;
				break;
			case "Soul Wars":
				own = net.runelite.client.hiscore.HiscoreSkill.SOUL_WARS_ZEAL;
				break;
			case "Collections":
				own = net.runelite.client.hiscore.HiscoreSkill.COLLECTIONS_LOGGED;
				break;
			case "Quests":
				return net.runelite.api.SpriteID.TAB_QUESTS;
			case "Diaries":
				return net.runelite.api.SpriteID.TAB_QUESTS_GREEN_ACHIEVEMENT_DIARIES;
			default:
				return 0;
		}
		return own.getSpriteId();
	}

	private static final String[] CLUE_TIERS = {
		"Beginner", "Easy", "Medium", "Hard", "Elite", "Master"};

	/**
	 * The middle band of the sheet, three across, the way the skills above it and
	 * the bosses below it are drawn.
	 *
	 * <p>It used to be a named list of every drop source filed under Activities or
	 * Skilling, which made the sheet read grid, list, grid where the game's own
	 * panel reads grid, grid, grid. Those sources were never activities anyway:
	 * a Fishing Trawler casket is loot, and the Loot board already holds every one
	 * of them by source.
	 *
	 * <p>Only what the journal can honestly answer is drawn. The hiscores' own
	 * list carries Bounty Hunter, LMS and the PvP arenas, and Chronicle watches
	 * none of those; printing a dash for them would say "you have not done this"
	 * in a panel where a dash means exactly that.
	 */
	private JPanel activitySheet()
	{
		// No heading. The tiles are three across, wearing the game's own emblems,
		// between the skills grid above and the boss grid below; a word naming
		// them costs a row and tells a reader what the icons already say.
		JPanel p = column();
		JPanel grid = new JPanel(new GridLayout(0, 3, 2, 2));
		grid.setBackground(ColorScheme.DARK_GRAY_COLOR);
		grid.setAlignmentX(Component.LEFT_ALIGNMENT);
		for (String[] a : ACTIVITIES)
		{
			grid.add(activityCell(a[0], a[1], a[2]));
		}
		p.add(grid);
		p.add(vgap(6));
		return p;
	}

	private JPanel activityCell(String label, String source, String page)
	{
		JPanel cell = new JPanel(new BorderLayout(3, 0));
		cell.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		cell.setBorder(BorderFactory.createEmptyBorder(3, 3, 3, 3));
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
			for (LocalStore.SourceRow r : sources())
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
			figures[0] = fmt(all) + (allWorth > 0 ? " · " + gp(allWorth) + " gp" : "");
			for (int i = 0; i < CLUE_TIERS.length; i++)
			{
				labels[i + 1] = CLUE_TIERS[i];
				figures[i + 1] = each[i] == 0 ? "0"
					: fmt(each[i]) + (worth[i] > 0 ? " · " + gp(worth[i]) + " gp" : "");
			}
			hover = tip("Clues", labels, figures);
			wearSprite(icon, activitySprite(label), ICON_W, ICON_H);
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
			wearSprite(icon, activitySprite(label), ICON_W, ICON_H);
		}
		else if ("Quests".equals(label))
		{
			JsonObject q = achievements().has("quests")
				&& achievements().get("quests").isJsonObject()
				? achievements().getAsJsonObject("quests") : new JsonObject();
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
			wearSprite(icon, activitySprite(label), ICON_W, ICON_H);
		}
		else if ("Diaries".equals(label))
		{
			long[] d = diaryStanding();
			figure = d[0];
			hover = tip("Achievement diaries",
				new String[]{"Tiers done", "Regions finished", "Regions"},
				new String[]{d[0] + " / " + d[1], fmt(d[2]), fmt(d[3])});
			wearSprite(icon, activitySprite(label), ICON_W, ICON_H);
		}
		else if ("Combat".equals(label))
		{
			long[] c = combatStanding();
			figure = c[0];
			hover = tip("Combat achievements",
				new String[]{"Points", "Tiers unlocked", "Seen by name"},
				new String[]{c[1] > 0 ? fmt(c[0]) + " / " + fmt(c[1]) : fmt(c[0]),
					fmt(c[2]) + " / 6", fmt(c[3])});
			wearSprite(icon, activitySprite(label), ICON_W, ICON_H);
		}
		else
		{
			figure = bossKills(source);
			hover = tip(label, new String[]{"Count"}, new String[]{fmt(figure)});
			wearSprite(icon, activitySprite(label), ICON_W, ICON_H);
		}
		cell.setToolTipText(hover);
		if (!page.isEmpty())
		{
			cell.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
			final String to = page;
			cell.addMouseListener(clicker(() ->
			{
				sheetPage = to;
				rebuild();
			}));
		}
		else if (!source.isEmpty())
		{
			// The tiles with no board of their own ARE loot sources - the rift's
			// rewards, Soul Wars' - and the figure they carry is that source's
			// count. So they open it, rather than being the only things on the
			// sheet that say a number and do nothing when you press them.
			cell.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
			final String open = source;
			cell.addMouseListener(clicker(() -> openSourceLoose(open)));
		}
		cell.add(icon, BorderLayout.WEST);
		boolean lit = figure > 0 && activityStirred(label, source);
		JLabel fig = new JLabel(figure > 0 ? fmt(figure) : "-", JLabel.RIGHT);
		fig.setFont(FontManager.getRunescapeSmallFont());
		fig.setForeground(lit ? TILE_LIT : ColorScheme.LIGHT_GRAY_COLOR.darker());
		cell.add(fig, BorderLayout.EAST);
		return cell;
	}

	/**
	 * The bosses as a sheet, the way the skills are: the roster three across, each
	 * wearing the game's own icon with its count beside it. A boss never fought
	 * shows a dash rather than a zero, because a dash reads as none and a sheet of
	 * forty zeros reads as noise.
	 *
	 * <p>Opening one drops a card in under its own ROW rather than at the foot of
	 * seventy, so what it says is beside what was clicked.
	 */
	private JPanel buildKills()
	{
		JPanel p = column();
		// Cleared HERE, not in rebuild(): the board can be built without one --
		// the preview harness does, and so does a test -- and a memo of the last
		// window's movement surviving into this one is a wrong number, not a
		// stale one.
		movedKcs = null;
		rolledKcs = null;
		rollUsed = false;
		List<Boss> roster = bossRoster(plugin.gson());
		if (roster.isEmpty())
		{
			p.add(note("The boss roster did not load."));
			return p;
		}
		// The sitting is counted off its own roll entry, so it needs no pair of
		// spine lines to be measured between.
		if (!wholeRecord() && !sessionPeriod() && span() == null)
		{
			p.add(noPeriod());
			return p;
		}
		// A narrowed period shows what it HOLDS. The roster is seventy strong and
		// nobody kills seventy things in a week, so every period but the lifetime
		// drew a handful of counts in a field of dashes, and the dashes were the
		// board. The lifetime keeps the whole roster: there it is a checklist of
		// what the account has and has not met, which is a different question.
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
				p.add(note("Nothing on the boss sheet was killed inside "
					+ periodInSentence() + "."));
				return p;
			}
			roster = had;
		}
		// One grid, no longer split around an opened cell: what that cell used to
		// expand into is the hover now, so nothing is inserted mid-sheet and
		// nothing below it moves when a boss is pressed. Built before anything is
		// added, because building is what discovers whether a cell had to fall
		// back to the roll.
		JPanel opening = bossSheet(roster);
		java.time.LocalDate shortFrom = rollUsed ? rollShortOf() : null;
		if (shortFrom != null)
		{
			// A cell the spine cannot date is counted from what the kills
			// dropped, and that reaches back only so far. Said once at the top
			// rather than left as a number meaning something its neighbours do
			// not: those cells are a floor, over part of the window.
			p.add(note("Kills the journal cannot date are counted from loot "
				+ "instead, which reaches back only to " + shortFrom.format(FULL_DAY)
				+ " and sees a kill only where it dropped something."));
			p.add(vgap(4));
		}
		p.add(opening);
		p.add(vgap(6));
		return p;
	}

	private JPanel bossSheet(List<Boss> rows)
	{
		JPanel grid = new JPanel(new GridLayout(0, 3, 2, 2));
		grid.setBackground(ColorScheme.DARK_GRAY_COLOR);
		grid.setAlignmentX(Component.LEFT_ALIGNMENT);
		for (Boss b : rows)
		{
			grid.add(bossCell(b));
		}
		return grid;
	}

	private JPanel bossCell(Boss b)
	{
		final long kc = bossKillsInWindow(b.name);
		JPanel cell = new JPanel(new BorderLayout(3, 0));
		cell.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		cell.setBorder(BorderFactory.createEmptyBorder(3, 3, 3, 3));
		// The same hover card the activity tiles above it draw. These two grids
		// sit on one sheet, and a tile answering in a sentence beside a tile
		// answering in a titled block is two panels pretending to be one. It also
		// has to answer at all: Callisto and Artio share a sprite, as do Vet'ion
		// and Calvar'ion and the three Dagannoth kings, so for some of these tiles
		// the hover is the only thing that says which boss it is.
		// Three states, not two. A count of zero is none killed; a count below
		// zero is the record declining to answer for this window, which is what
		// the dash beside it says. Folding the two together made the hover assert
		// "none yet" over a tile that was saying it did not know.
		cell.setToolTipText(bossTip(b));
		cell.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));

		JLabel icon = new JLabel();
		if (b.sprite > 0)
		{
			// 24, not the sprite's own 25: a cell leaves about 32px beside the
			// icon and a five figure count needs 32 of them.
			wearSprite(icon, b.sprite, 24, 24);
		}
		cell.add(icon, BorderLayout.WEST);

		JLabel fig = new JLabel(kc > 0 ? fmt(kc) : "-", JLabel.RIGHT);
		fig.setFont(FontManager.getRunescapeSmallFont());
		fig.setForeground(kc > 0 ? TILE_LIT : ColorScheme.LIGHT_GRAY_COLOR.darker());
		cell.add(fig, BorderLayout.EAST);
		// Straight to the loot, which is where a reader pressing a boss means to
		// go, and not always a page of its own name: a skilling boss pays out
		// through a cart or a pool.
		final String open = bossLootSource(b);
		cell.addMouseListener(clicker(() -> openSourceLoose(open)));
		return cell;
	}

	/**
	 * What one boss came to. The cell carries the kills the window moved; this
	 * carries the whole record's, which is the count wherever one is known and
	 * the ledger's own loot-event tally where none is, and then what those kills
	 * paid.
	 *
	 * <p>Everything the boss card used to say, as the hover card it should have
	 * been.
	 *
	 * <p>Every cell on this grid expanded into a block below the row it sat in,
	 * and the hover over it said only what the cell already drew. So the reader
	 * had to click to learn anything, the click pushed the rest of the grid down
	 * the page, and a second click was needed to put it back. The block is the
	 * hover now, and the click goes where a reader pressing a boss means to go:
	 * its loot.
	 */
	private String bossTip(Boss b)
	{
		List<String> labels = new ArrayList<>();
		List<String> figures = new ArrayList<>();
		String kind = LocalStore.kindOf(b.name);
		LocalStore.SourceRow src = null;
		// What the fight is paid out through as well as the fight itself. A
		// skilling boss hands its loot over in a container -- "Reward cart
		// (Wintertodt)", "Reward pool (Tempoross)", the casket beside it -- and
		// looking only for a source of the boss's own name found none of it, so
		// four and a half million gp sat in the journal under a card saying no
		// loot had reached it.
		List<LocalStore.SourceRow> paidOut = new ArrayList<>();
		for (LocalStore.SourceRow r : sources())
		{
			if (LocalStore.kindOf(r.name).equals(kind))
			{
				if (src == null)
				{
					src = r;
				}
				continue;
			}
			if (namesInBrackets(r.name, b.name)
				|| r.name.equalsIgnoreCase(PAYS_OUT.get(b.name)))
			{
				paidOut.add(r);
			}
		}
		long known = bossKills(b.name);
		labels.add("Kills tracked");
		figures.add(known > 0 ? fmt(known) : src != null ? fmt(src.loots) : "-");
		// the best time, which is a time
		for (Map.Entry<String, Long> pb : bestTimes(b.name))
		{
			labels.add(pb.getKey());
			figures.add(clock(pb.getValue()));
		}
		// what the page itself counts, which need not be kills at all
		for (Map.Entry<String, Long> ln : logLines(b.name))
		{
			labels.add(ln.getKey());
			figures.add(fmt(ln.getValue()));
		}
		if (src != null)
		{
			labels.add("Drops");
			figures.add(paidFigure(src));
		}
		for (LocalStore.SourceRow r : paidOut)
		{
			// named as the game pays it out, not as a second boss
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

	private String paidFigure(LocalStore.SourceRow r)
	{
		long qty = 0;
		for (LocalStore.BagItem it : plugin.sourceItems(r.name))
		{
			qty += it.qty;
		}
		return fmt(qty) + " \u00b7 " + gp(r.value) + " gp";
	}

	/**
	 * Where a boss's loot actually lives, which is not always under its own name:
	 * a skilling boss pays out through a cart or a pool, and that is the page a
	 * reader pressing the tile wants.
	 */
	private String bossLootSource(Boss b)
	{
		String kind = LocalStore.kindOf(b.name);
		for (LocalStore.SourceRow r : sources())
		{
			if (LocalStore.kindOf(r.name).equals(kind))
			{
				return r.name;
			}
		}
		for (LocalStore.SourceRow r : sources())
		{
			if (namesInBrackets(r.name, b.name)
				|| r.name.equalsIgnoreCase(PAYS_OUT.get(b.name)))
			{
				return r.name;
			}
		}
		return b.name;
	}

	// ------------------------------------------------------------------
	// State + shared bits
	// ------------------------------------------------------------------

	private Color accent()
	{
		return ACCENT_LIFETIME;
	}

	/**
	 * Empty the box as NAVIGATION rather than as typing. Clearing a box that had
	 * something in it is a document change like any other, and the debounce
	 * cannot tell the two apart: left armed, it fires a hundred and fifty
	 * milliseconds later and builds the page again, throwing away the one the
	 * reader is already looking at and landing them back at its top.
	 */
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

	// Whether a rebuild is already on its way to the EDT. A region load, a world
	// hop and a push landing can all ask inside the same tick, and three
	// rebuilds queued back to back draw the same board from the same record
	// three times. The one already queued has not run yet, so it will read
	// everything the later asks would have.
	private final java.util.concurrent.atomic.AtomicBoolean queued =
		new java.util.concurrent.atomic.AtomicBoolean();

	// Whether the panel has ever been on screen. Until it has, nothing can be
	// read from isShowing(): a panel the sidebar has not mounted yet is not
	// showing either, and skipping its first build would leave it empty.
	private boolean everShown;

	// A rebuild asked for while the panel was off screen, owed back when it
	// returns. The sidebar is a tabbed pane and only the selected tab shows, so
	// a record that moves while the reader is on another plugin costs a whole
	// board nobody is looking at.
	private boolean staleWhileHidden;

	/** The ledger: drops, the feed, the kill log, the journal's own record. */
	static final int MOVED_RECORD = 1;

	/** The counters: every tracker, which an hour of play moves constantly. */
	static final int MOVED_COUNTERS = 2;

	/** Experience and levels. */
	static final int MOVED_SKILLS = 4;

	/** The collection log's own capture. */
	static final int MOVED_CLOG = 8;

	private static final int MOVED_ANY = MOVED_RECORD | MOVED_COUNTERS
		| MOVED_SKILLS | MOVED_CLOG;

	/**
	 * Whether the board on screen shows any of what has just moved.
	 *
	 * <p>Every board reads live, but no board reads everything. The drops list
	 * runs to fifteen hundred components and nine thousand pixels when it is
	 * opened out, and laying that out and painting it costs twenty five
	 * milliseconds; doing it because a woodcutting tick moved a counter it does
	 * not show is the whole of the lag.
	 *
	 * <p>A board left undrawn is not left stale: navigating to one rebuilds it,
	 * and anything it DOES show will move soon enough on its own.
	 */
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
				return (moved & (MOVED_RECORD | MOVED_CLOG)) != 0;
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

	/** Rebuild the panel from plugin state. Safe to call from any thread. */
	void update()
	{
		update(MOVED_ANY);
	}

	/**
	 * Rebuild, if the board on screen shows any of what moved.
	 *
	 * <p>Safe to call from any thread.
	 */
	void update(int moved)
	{
		if (!queued.compareAndSet(false, true))
		{
			return;
		}
		SwingUtilities.invokeLater(() ->
		{
			// Cleared first, so a record that moves DURING this build still earns
			// a build of its own.
			queued.set(false);
			if (everShown && !getWrappedPanel().isShowing())
			{
				staleWhileHidden = true;
				return;
			}
			// A menu is open over the board: the period picker, the task picker.
			// Rebuilding replaces the component it was raised from, which closes
			// it under the reader's cursor mid-choice. The record will still have
			// moved when they have chosen, and choosing rebuilds anyway.
			//
			// Or the reader has hold of the scroll bar. rebuild() throws the whole
			// scroll pane away and hangs a fresh one, so a redraw landing mid-drag
			// takes the thumb out from under the mouse. Both are owed and paid by
			// the timer, which is what that timer is now mostly for.
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
				// Owed, and paid by the timer once the board has had its breath.
				staleWhileHidden = true;
				return;
			}
			// The record changing under a reader who did not ask to go anywhere: a
			// push landing, the status line moving, the history read arriving. Only
			// navigation starts at the top; returning a reader to the first line of
			// a page they were in the middle of, every push interval, is the record
			// interrupting them.
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

	// Where the pointer was when this panel last thought about redrawing itself.
	private java.awt.Point lastPointer;

	/**
	 * Whether the reader is reading rather than playing.
	 *
	 * <p>Every board redraws when the record moves, which during a grind is most
	 * ticks. A redraw hangs a fresh component tree, and a tooltip belongs to the
	 * component it was raised from: replace that component and the tooltip goes.
	 * Since a good deal of this panel's detail is deliberately behind a hover -
	 * what a diary tier asks for, what a log page's header said, which boss a
	 * shared sprite is - a redraw every six hundred milliseconds would make those
	 * unreadable exactly when somebody was trying to read them.
	 *
	 * <p>A pointer resting inside the panel, in the same place it was at the last
	 * ask, is somebody reading. A pointer that has moved is somebody on their way
	 * somewhere, and nothing is open to disturb. Outside the panel, they are
	 * playing the game and the panel is theirs to redraw.
	 */
	private boolean beingRead()
	{
		java.awt.Point was = lastPointer;
		java.awt.Point now = null;
		try
		{
			java.awt.PointerInfo at = java.awt.MouseInfo.getPointerInfo();
			if (at != null)
			{
				now = at.getLocation();
				java.awt.Point origin = getWrappedPanel().getLocationOnScreen();
				java.awt.Rectangle over = new java.awt.Rectangle(origin,
					getWrappedPanel().getSize());
				if (!over.contains(now))
				{
					now = null;
				}
			}
		}
		catch (RuntimeException e)
		{
			// headless, or the panel is not on screen: not being read either way
			now = null;
		}
		lastPointer = now;
		return now != null && now.equals(was);
	}

	/** Whether the reader has hold of the scroll bar this instant. */
	private boolean scrollHeld()
	{
		JScrollPane pane = paneIn(display);
		return pane != null && pane.getVerticalScrollBar().getValueIsAdjusting();
	}

	/** Whether any popup menu this panel raised is on screen right now. */
	private static boolean popupShowing()
	{
		javax.swing.MenuElement[] path = javax.swing.MenuSelectionManager
			.defaultManager().getSelectedPath();
		return path != null && path.length > 0;
	}

	/**
	 * Owe the reader a rebuild for every push that landed while they were
	 * looking at something else, and pay it the moment they come back.
	 */
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

	// Whether the time played on show is the game's own total rather than the
	// hours Chronicle has watched. Cleared at the top of every build.
	private boolean playedIsTheGames;

	// True while a rebuild is a redraw of the view the reader is already in: the
	// home ticker, a push landing under them, a fold or a "show more". The
	// viewport holds their place through all of those on its own. False is
	// navigation - a tab, a search, an opened detail - which is a different
	// board, and the one case that takes them back to the top.
	private boolean keepScroll;

	// The period sits above the tab strip, because it governs every tab. Refilled
	// on each rebuild so the label follows the window.
	private final JPanel periodHolder = new JPanel(new BorderLayout());
	// the strip the period, the tabs and the search hang on, kept so the period
	// changing size can invalidate the layout that has to make room for it
	private final JPanel north = new JPanel();

	private static JScrollPane paneIn(java.awt.Container c)
	{
		for (Component k : c.getComponents())
		{
			if (k instanceof JScrollPane)
			{
				return (JScrollPane) k;
			}
		}
		return null;
	}

	// How many boards have actually been drawn. The two guards above exist to
	// keep this well below the number of times update() is called, and this is
	// what holds them to it.
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

	// How long the last draw took, and when it finished. A board opened right
	// out runs to fifteen hundred components and nine thousand pixels, and what
	// it costs to lay out and paint is the only honest guide to how often it can
	// afford to be drawn again.
	private long lastBuildNanos;
	private long lastBuildAt;

	/**
	 * How long a board must be left alone between record-driven draws.
	 *
	 * <p>Nothing for the ordinary boards, which cost a millisecond or two. A
	 * board that took twelve milliseconds is one the reader has opened right out,
	 * and drawing it on every tick spends a twentieth of the client's time saying
	 * what it already said. Navigation is never held back by this - only the
	 * record redrawing under somebody who did not ask it to.
	 */
	private long redrawFloorMs()
	{
		long ms = lastBuildNanos / 1_000_000L;
		return ms < 12 ? 0 : Math.min(2000L, ms * 60L);
	}

	private void rebuildNow()
	{
		buildsRun++;
		// A new pass, so the per-rebuild answers are no longer answered.
		buildSources = null;
		buildClog = null;
		buildSpan = null;
		spanAsked = false;
		taskKillsEverCache = null;
		taskItemsEver = null;
		buildAchievements = null;
		// Set only by buildStats, but read by the all-trackers board too. Left
		// standing it carried one period's dropped figure onto another period's
		// gathered row, and stepping the period never moved it.
		resourcesDropped = 0;
		playedIsTheGames = false;
		kcByKind = null;
		chatKcByKind = null;
		killKinds = null;
		movedTypes = null;
		buildPeriodCounters = null;
		periodCountersAsked = false;
		// These are cleared in buildKills too, and have to be: a test builds that
		// board with no rebuild around it. But the activity tiles above the boss
		// grid ask rolledKills as well, and they are drawn BEFORE buildKills gets
		// to clear it, so on a period change they read the last window's memo.
		movedKcs = null;
		rolledKcs = null;
		rollUsed = false;
		// The labels of the build just discarded are nobody's business now. Left to
		// pile up, an icon that never lands would hold every label the panel ever
		// drew, which is the same unbounded queue that made the trackers page lag.
		// This used to sit inside buildHistory, so it ran only on one tab while the
		// boss sheet and the kind rows queued labels from every other.
		facetWaiting.clear();
		itemWaiting.clear();
		// Three states, worst first. A disk that will not take the journal is red
		// and stops everything; a plugin we lean on being switched off is gold and
		// loses one kind of capture, which the reader can fix in one click and
		// previously had no way to learn about at all.
		String stalled = plugin.journalWarning();
		String capture = plugin.captureWarning();
		Color pulse = stalled != null ? ColorScheme.PROGRESS_ERROR_COLOR
			: capture != null ? accent() : ACCENT_SESSION;
		heartbeat.setText(stalled != null ? "not saving"
			: capture != null ? capture : "logging");
		heartbeat.setToolTipText(stalled != null ? stalled
			: capture != null ? plugin.captureWarningWhy() : null);
		heartbeat.setIcon(dot(pulse));
		heartbeat.setIconTextGap(4);
		heartbeat.setForeground(pulse);
		heartbeat.setFont(FontManager.getRunescapeSmallFont());
		// NOT removeAll: the scroll pane is hung once and kept. Only the strip
		// above it is rebuilt, which is why it is removed by name.
		if (aboveBoard != null)
		{
			display.remove(aboveBoard);
			aboveBoard = null;
		}
		// The period governs every board except the sitting, which is now and can
		// be nothing else. Drawn above the tabs, so it is plainly over all of them
		// rather than looking like one tab's control.
		// Always drawn, on every board, above the tabs: it governs all of them, and
		// on the one it cannot govern it says so rather than leaving. A control
		// that vanishes on one tab moves every tab under it, and the strip jumping
		// as you move between them reads as the panel misbehaving.
		measuredSince = null;
		periodHolder.removeAll();
		periodHolder.add(periodRow(), BorderLayout.CENTER);
		periodHolder.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 16, 22));
		// BoxLayout caches what its children asked for and only drops that cache
		// when the container itself is invalidated. revalidate() alone leaves the
		// strip laying the period out at the height it had last time, which for a
		// holder that starts empty is none at all.
		periodHolder.invalidate();
		north.invalidate();
		north.revalidate();
		north.repaint();
		JPanel body;
		if (!searchQuery().isEmpty())
		{
			body = buildSearch(searchQuery());
		}
		else if (detailItem != null)
		{
			body = buildItemDetail(detailItem);
		}
		else if (detailSource != null)
		{
			body = buildSourceDetail(detailSource);
		}
		else if (detailSkill != null)
		{
			body = buildSkillDetail(detailSkill);
		}
		else if (allTrackers)
		{
			body = buildAllTrackers();
		}
		else if (showInfo)
		{
			body = buildInfo();
		}
		else if (detailTask >= 0)
		{
			body = buildTaskDetail(detailTask);
		}
		else if (leftBehindSource != null || leftBehindItem != null)
		{
			body = buildLeftBehindDetail();
		}
		else if (sheetPage != null)
		{
			body = buildSheetPage();
		}
		else
		{
			switch (view)
			{
				case SHEET:
					body = buildSheet();
					break;
				case KILLS:
					body = buildKills();
					break;
				case DROPS:
					body = buildDrops();
					break;
				case SLAYER:
					body = buildSlayer();
					break;
				case LOG:
					body = buildLog();
					break;
				case STATS:
					body = buildStats();
					break;
				case HISTORY:
					body = buildHistory();
					break;
				case JOURNAL:
					body = buildJournal();
					break;
				case HOME:
				default:
					body = buildHome();
					break;
			}
		}
		// Row heights are width-independent. The bar can't oscillate.
		// The body is what discovers where the period is measured from, and the
		// control was hung before it. Applied here, once, so the row carries its
		// own caveat instead of the board carrying two lines of it.
		periodHolder.setToolTipText(measuredSince);
		// The tile that was lit is about to be taken out of the hierarchy, and a
		// component removed under the cursor is never told it was exited. Put it
		// back before it goes, or its listener keeps a live reference to a tile
		// nobody can reach and the next sweep has nothing to undo.
		unlight();
		canvas.removeAll();
		canvas.add(body, BorderLayout.NORTH);
		// The sub-tabs hang outside the scroll pane, because navigation must not
		// scroll away from the board it moves between. The period is not here: it
		// hangs higher, in periodHolder above the tab strip, because it governs
		// every tab rather than this one board.
		JPanel above = new JPanel();
		above.setLayout(new BoxLayout(above, BoxLayout.Y_AXIS));
		above.setBackground(ColorScheme.DARK_GRAY_COLOR);
		JPanel subs = subStrip();
		if (subs != null)
		{
			above.add(subs);
			above.add(vgap(6));
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
		// The viewport keeps the reader's place by itself now, which is right for
		// a redraw and wrong for NAVIGATION: a tab, a search, an opened detail is
		// a different board, and arriving halfway down one is arriving lost. So
		// the only time anything moves the bar is when the reader asked to go
		// somewhere, which is also the only time they expect it to move.
		if (!keepScroll)
		{
			scrollPane.getVerticalScrollBar().setValue(0);
		}
	}

	// The strip of sub-tabs above the board, held by name so it can be taken down
	// without taking the scroll pane with it.
	private JPanel aboveBoard;

	private void onSearchChanged()
	{
		rebuild();
	}

	// ------------------------------------------------------------------
	// Views
	// ------------------------------------------------------------------

	// Pinned to the top of the session strip, in this order.
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

	// A small filled circle, used as the heartbeat pip.
	private static javax.swing.Icon dot(Color c)
	{
		return new javax.swing.Icon()
		{
			@Override
			public void paintIcon(Component host, java.awt.Graphics g, int x, int y)
			{
				java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
				g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
					java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
				g2.setColor(c);
				g2.fillOval(x, y, 6, 6);
				g2.dispose();
			}

			@Override
			public int getIconWidth()
			{
				return 6;
			}

			@Override
			public int getIconHeight()
			{
				return 6;
			}
		};
	}

	// how much of this session's damage the three styles account for; nothing to
	// open when the record never typed it
	private static long splitOf(Map<String, Integer> sess)
	{
		long n = 0;
		for (String k : DAMAGE_SPLIT)
		{
			n += sess.getOrDefault(k, 0);
		}
		return n;
	}

	private void damageFoldHead(JPanel head)
	{
		JLabel name = (JLabel) ((BorderLayout) head.getLayout())
			.getLayoutComponent(BorderLayout.CENTER);
		if (foldOpen(FOLD_HOME_DAMAGE))
		{
			name.setForeground(accent());
		}
		head.setToolTipText("The damage this session, by style");
		head.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		head.addMouseListener(clicker(() -> toggleFold(FOLD_HOME_DAMAGE)));
	}

	// The pinned xp total doubles as a fold head. Closed it is the row it has always
	// been; open, the name takes the accent this panel's other fold heads use. The
	// state is a key in the panel's one fold register, same as every other fold: the
	// home ticker rebuilds every three seconds and would otherwise shut the fold on
	// the reader between one glance and the next.
	private void xpFoldHead(JPanel head)
	{
		JLabel name = (JLabel) ((BorderLayout) head.getLayout())
			.getLayoutComponent(BorderLayout.CENTER);
		if (foldOpen(FOLD_HOME_XP))
		{
			name.setForeground(accent());
		}
		head.setToolTipText("Each skill's xp and xp per hour this session");
		head.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		head.addMouseListener(clicker(() -> toggleFold(FOLD_HOME_XP)));
	}

	// One row per skill that moved this session, biggest first: the xp it gained and
	// what that comes to per hour. No icons; the strip above is a column of names and
	// figures, and a sprite gutter on these rows alone would break it. Indented under
	// the total they add up to.
	private void addXpBySkill(JPanel strip)
	{
		List<ExperienceStatTracker.SkillGain> gains = plugin.sessionSkillXp();
		if (gains.isEmpty())
		{
			// The split lives in the experience tracker alone, and that is built on
			// the session's first event. Without this the fold opens onto nothing.
			strip.add(ghostRow("no skill breakdown yet", ""));
			return;
		}
		for (ExperienceStatTracker.SkillGain g : gains)
		{
			// The rate is left off until the tally has a minute behind it; a few
			// seconds of play extrapolates to a figure nobody earned.
			String right = "+" + gp(g.xp)
				+ (g.perHour >= 0 ? " · " + gp(g.perHour) + "/h" : "");
			JPanel r = row(g.skill.getName(), right, null);
			r.setBorder(BorderFactory.createEmptyBorder(1, 10, 1, 2));
			strip.add(r);
		}
	}

	private JPanel buildHome()
	{
		JPanel p = column();
		// The pip says the journal has stopped reaching disk; Home says why.
		String stalled = plugin.journalWarning();
		if (stalled != null)
		{
			p.add(note(stalled));
			p.add(vgap(6));
		}

		ChronicleEventCapture.SlayerView task = plugin.slayerView();
		if (task != null && plugin.slayerSeenThisSession())
		{
			JPanel card = card("Slayer task");
			card.add(row(task.task, task.remaining + " left", ACCENT_SESSION));
			if (task.initial > 0)
			{
				card.add(progress(1f - (float) task.remaining / task.initial));
			}
			p.add(card);
			p.add(vgap(6));
		}

		// The session strip: pinned rows, then whatever else moved, ranked.
		JPanel strip = card("This session");
		Map<String, Integer> sess = plugin.sessionCounters();
		int mounted = 0;
		java.util.Set<String> shownKeys = new java.util.HashSet<>();
		for (String key : HOME_PINNED)
		{
			long v = sess.getOrDefault(key, 0);
			if (v > 0)
			{
				boolean isXp = "totalXpGained".equals(key);
				// damage carries its own split, the way xp carries its skills:
				// the three styles are that one figure broken up, not three more
				// trackers, so they open from it rather than standing beside it
				boolean isDamage = "damageDealt".equals(key) && splitOf(sess) > 0;
				JPanel r = row(homeLabel(key),
					StatRegistry.isGp(key) ? gp(v) + " gp"
						: (isXp ? "+" + gp(v) : fmt(v)),
					ACCENT_SESSION);
				if (isXp)
				{
					xpFoldHead(r);
				}
				if (isDamage)
				{
					damageFoldHead(r);
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
							strip.add(row(StatRegistry.label(split), fmt(sv), null));
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
				plugin.sessionLoots() + " · " + gp(plugin.sessionLootValue()) + " gp",
				ACCENT_SESSION));
			mounted++;
			// the kills whose loot was all picked up: the loot events less the kills
			// that left a stack behind, one unit both ways. "Left behind" below
			// counts stacks, so it is not what this subtracts.
			strip.add(row("Drops taken",
				fmt(Math.max(0, plugin.sessionLoots() - plugin.sessionUntakenKills())),
				ACCENT_SESSION));
			mounted++;
		}
		long[] untaken = plugin.sessionUntakenTally();
		if (untaken[0] > 0)
		{
			strip.add(row("Left behind", fmt(untaken[0]) + " · " + gp(untaken[1]) + " gp", null));
			mounted++;
		}
		// Everything else the session moved, one row to a tracker, under the
		// family it belongs to. Where a tracker has a parent total the parent is
		// the row: a herb sack run says "Herbs sacked" once, and the twelve herbs
		// fold out under that row rather than standing as twelve rows of their own.
		mounted += addSessionMovers(strip, plugin.sessionDisplayCounters(), shownKeys);
		if (mounted == 0)
		{
			strip.add(row("A fresh page", "", null));
		}
		p.add(strip);
		p.add(vgap(6));

		List<LocalStore.RecentDrop> recent = plugin.recentDrops();
		if (!recent.isEmpty())
		{
			JPanel card = card("Recent drops");
			JPanel grid = new JPanel(new GridLayout(0, 5, 3, 3));
			grid.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			// LEFT, to match the caption beside it.
			grid.setAlignmentX(Component.LEFT_ALIGNMENT);
			int shown = 0;
			for (LocalStore.RecentDrop d : recent)
			{
				if (shown++ >= 10)
				{
					break;
				}
				JLabel slot = new JLabel();
				slot.setPreferredSize(new Dimension(36, 32));
				slot.setHorizontalAlignment(JLabel.CENTER);
				slot.setToolTipText(d.name + (d.quantity > 1 ? " ×" + fmt(d.quantity) : ""));
				slot.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
				final String itm = d.name;
				slot.addMouseListener(clicker(() -> openItem(itm)));
				AsyncBufferedImage img = plugin.items().getImage(d.itemId, d.quantity, d.quantity > 1);
				img.addTo(slot);
				grid.add(slot);
			}
			card.add(grid);
			p.add(card);
			p.add(vgap(6));
		}

		return p;
	}

	/**
	 * Whether a tracker already has a parent that speaks for it in this list.
	 * The sack types every herb it swallows and the spellbook types every place
	 * it sends you, but the session moved one tracker, not twelve or thirty: the
	 * parent total carries them, and the typed rows fold out under the parent's
	 * own row.
	 * A child is only hidden where its parent actually moved, so nothing the
	 * session did can fall out of the strip.
	 */
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
		// a place reached is one of the teleports the total already counted
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

	/**
	 * The rest of what the session moved, under quiet family headings, one row to
	 * a tracker. A heading carries how many trackers it holds and folds away on a
	 * click; it holds no figure of its own, since the sum of an arrow shaft and a
	 * herbiboar is not a number anybody wants. Returns the lines mounted.
	 */
	private int addSessionMovers(JPanel strip, Map<String, Integer> sess,
		java.util.Set<String> shownKeys)
	{
		Map<String, List<Map.Entry<String, Long>>> byFamily = new LinkedHashMap<>();
		// what each parent is standing for, so its row can open on them
		Map<String, List<Map.Entry<String, Long>>> under = new LinkedHashMap<>();
		for (Map.Entry<String, Integer> e : sess.entrySet())
		{
			String key = e.getKey();
			if (e.getValue() <= 0 || shownKeys.contains(key) || StatRegistry.hidden(key)
				|| DAMAGE_SPLIT.contains(key))
			{
				continue;
			}
			Map.Entry<String, Long> moved =
				new java.util.AbstractMap.SimpleEntry<>(key, (long) e.getValue());
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
			List<Map.Entry<String, Long>> rows = byFamily.get(family);
			if (rows == null || rows.isEmpty())
			{
				continue;
			}
			rows.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
			String stateKey = "session:" + family;
			boolean open = !foldOpen(stateKey);   // these stand open; the fold shuts them
			strip.add(quietHead(family, open ? "" : fmt(rows.size()), stateKey));
			mounted++;
			if (!open)
			{
				continue;
			}
			for (Map.Entry<String, Long> e : rows)
			{
				mounted += addMoverRow(strip, e.getKey(), e.getValue(),
					under.get(e.getKey()));
			}
		}
		return mounted;
	}

	/**
	 * One tracker's line. A tracker standing for others, the herb sack for its
	 * herbs and the teleport total for the places it reached, opens on them: the
	 * parent is what the session moved, and the breakdown is what it moved it on.
	 * What the parent counted and its children could not name reconciles as
	 * "Other" rather than going missing. Returns the lines mounted.
	 */
	private int addMoverRow(JPanel strip, String key, long value,
		List<Map.Entry<String, Long>> kids)
	{
		if (kids == null || kids.isEmpty())
		{
			strip.add(sessionRow(key, value));
			return 1;
		}
		String listKey = "session:row:" + key;
		boolean open = foldOpen(listKey);
		JPanel head = sessionRow(key, value);
		head.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		head.addMouseListener(clicker(() -> toggleFold(listKey)));
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
		for (Map.Entry<String, Long> k : kids)
		{
			named += k.getValue();
			if (shown++ >= cap)
			{
				continue;
			}
			strip.add(nested(row(StatRegistry.rowLabel(k.getKey()), fmt(k.getValue()), null)));
			mounted++;
		}
		addMore(strip, listKey, kids.size(), cap);
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


	/**
	 * A heading that names a band and folds it away. It is structure, not a
	 * figure, so it stays quiet in both states and the accent is left to mean one
	 * thing: what this session earned. Open, the rows beneath speak for it and it
	 * carries nothing; shut, it says how many rows it is holding.
	 */
	private JPanel quietHead(String name, String count, String stateKey)
	{
		JPanel head = row(name.toUpperCase(Locale.ROOT), count, null);
		JLabel headName = (JLabel) ((BorderLayout) head.getLayout())
			.getLayoutComponent(BorderLayout.CENTER);
		headName.setFont(FontManager.getRunescapeSmallFont());
		headName.setForeground(ColorScheme.LIGHT_GRAY_COLOR.darker());
		head.setBorder(BorderFactory.createEmptyBorder(7, 2, 1, 2));
		head.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		head.addMouseListener(clicker(() -> toggleFold(stateKey)));
		return head;
	}

	// one tracker, one line: its name and its figure, in the one register every
	// other line in the strip uses
	private JPanel sessionRow(String key, long v)
	{
		return row(StatRegistry.label(key),
			StatRegistry.isGp(key) ? gp(v) + " gp" : fmt(v), null);
	}

	/**
	 * The loot a window actually holds, off the dated roll rather than off the
	 * ledger's running totals. A range the roll has nothing for shows nothing:
	 * that is the answer, not an empty board to be filled with a lifetime.
	 *
	 * <p>The roll only starts the day it started. A window opening before that
	 * has no dated account of its loot and says so, naming the day one begins,
	 * rather than reporting the part it can see as the whole.
	 */
	private JPanel dropsInWindow(JPanel p)
	{
		Window win = window();
		// The sitting keeps its own entry in the roll's shape, written as the
		// drops land, so it is read here like any other window and every board
		// below this line works without knowing which it got. The roll's own
		// coverage notes are skipped for it: they are answers ABOUT the dated
		// roll, and the sitting is not read from it.
		LocalStore.LootWindow sitting = sessionPeriod() ? plugin.sessionLootWindow() : null;
		long rollFrom = plugin.lootRollFrom();
		long fromMs = win.start.atStartOfDay(ZoneId.systemDefault())
			.toInstant().toEpochMilli();
		if (sitting == null && rollFrom <= 0)
		{
			p.add(note("No loot has been dated yet. The roll keeps one entry a "
				+ "day and starts with the next drop that lands."));
			return p;
		}
		if (sitting == null && rollFrom > fromMs)
		{
			java.time.LocalDate began = java.time.Instant.ofEpochMilli(rollFrom)
				.atZone(ZoneId.systemDefault()).toLocalDate();
			p.add(note("The dated loot roll begins " + began.format(FULL_DAY)
				+ ", which is inside " + periodInSentence() + ". Naming the part it can see "
				+ "as the whole period would be worse than saying nothing."));
			return p;
		}
		LocalStore.LootWindow w = sitting != null ? sitting
			: plugin.lootBetween(win.start, win.end);
		// The roll keeps the window's ITEMS beside its sources, so the kind lens
		// answers a period with the same two screens it draws over the whole
		// ledger: what the month paid in runes, rather than what every month did.
		if (!dropsLeftBehind && dropsByKind)
		{
			if (w.items.isEmpty())
			{
				p.add(note("Nothing taken inside " + periodInSentence() + "."));
				return p;
			}
			return kindLens(p, win.label, bagOf(w.items), "win:");
		}
		// By item, on the left-behind reading, is the same axis the Received
		// reading calls by kind: one list at a time, chosen by the same control.
		// The roll keeps what a window left behind as ITEMS and not by source, so
		// on a narrowed period that reading has one axis and the strip above does
		// not offer a second. An axis that cannot answer is not drawn.
		List<String[]> ranked = dropsLeftBehind ? w.leftItems : w.sources;
		if (ranked.isEmpty())
		{
			p.add(note("Nothing " + (dropsLeftBehind ? "left behind" : "taken")
				+ " inside " + periodInSentence() + "."));
			return p;
		}
		JPanel head = card(dropsLeftBehind ? "Left behind" : "Drops received");
		if (dropsLeftBehind)
		{
			head.add(row("Items", fmt(w.left), ACCENT_RED));
			head.add(row("Worth", gp(w.leftValue) + " gp", null));
			head.add(row("Kills that left one", fmt(w.leftKills), null));
		}
		else
		{
			head.add(row("Drops", fmt(w.loots), accent()));
			head.add(row("Worth", gp(w.value) + " gp", null));
		}
		p.add(head);
		p.add(vgap(6));
		// Capped like every other list on the panel. A year of loot is hundreds
		// of sources, and a board that simply runs on is a board the reader
		// cannot get to the bottom of.
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
				+ gp(safeParse(r[2])) + " gp", null);
			line.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
			final String name = r[0];
			// the drill is that source's or item's whole record, which is a
			// different screen and says so by carrying its own dateline
			line.addMouseListener(clicker(() ->
			{
				if (dropsLeftBehind)
				{
					openItem(name);
				}
				else
				{
					openSourceLoose(name);
				}
			}));
			p.add(line);
		}
		return p;
	}

	/**
	 * The roll's own rows as a bag. The roll holds a name, a count and a worth
	 * per item and no id, which is all the kind lens reads: the taxonomy is
	 * keyed by name, and so is the drill the row opens.
	 */
	private static List<LocalStore.BagItem> bagOf(List<String[]> rows)
	{
		List<LocalStore.BagItem> bag = new ArrayList<>();
		for (String[] r : rows)
		{
			bag.add(new LocalStore.BagItem(0, r[0], safeParse(r[1]), safeParse(r[2])));
		}
		return bag;
	}

	/**
	 * The period as epoch millis, for the records that carry a stamp rather than
	 * a daily baseline. A lifetime admits everything, which is what it means.
	 *
	 * <p>The sitting is the one period whose bounds are NOT a pair of days. Every
	 * other period is a run of whole days and rounding to midnight loses nothing;
	 * a sitting that began this afternoon, rounded the same way, swallows the
	 * whole morning. So it takes the moment the client started, exactly, and runs
	 * to now. This is the only place that difference is expressed, and everything
	 * that dates a line by its stamp reads it from here.
	 */
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
				: java.time.LocalDate.now().atStartOfDay(ZoneId.systemDefault())
					.toInstant().toEpochMilli(),
				System.currentTimeMillis()};
		}
		Window w = window();
		return new long[]{
			w.start.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
			w.end.plusDays(1).atStartOfDay(ZoneId.systemDefault())
				.toInstant().toEpochMilli() - 1};
	}

	// Answered once per rebuild, like the sources and the collection log above.
	// A board of two hundred rows asks whether each of them has an on-task side,
	// and the answer is one pass over ninety three tasks.

	// Whether the loot on show is narrowed to what slayer tasks logged. A lens,
	// like the two beside it, and READ ONLY by a board that also draws the
	// control: a number that changes with no control on screen to explain why is
	// worse than a number the reader cannot narrow at all.
	private boolean onTaskOnly;


	/** What the tasks paid inside the period, as a bag the kind lens can read. */
	private List<LocalStore.BagItem> onTaskBag()
	{
		long[] w = windowMs();
		return plugin.onTaskLoot(w[0], w[1], null, wholeRecord());
	}

	/** Whether any of what the tasks paid is of this kind. */
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

	/**
	 * Whether this account has ANY on-task loot, ever.
	 *
	 * <p>The control is offered on that, not on the period: an account that has
	 * never been given a task is never shown a filter that would do nothing,
	 * and an account that has, keeps the control in a week it happened not to
	 * close one. A pill that disappears because you changed the period, leaving
	 * the reading it set still in force, is a control the reader cannot undo.
	 */
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
	// Which KIND of thing the loot boards are narrowed to, or null for all of
	// them. A kind is a question a reader actually has -- "what have the tasks
	// paid me in runes" -- and the alternative was a query language in a two
	// hundred and twenty five pixel panel.
	private String lootKind;
	// Which task the on-task board is narrowed to, or null for all of them.
	private String lootTask;

	private JPanel buildDrops()
	{
		JPanel p = column();
		// Three modifiers, three buttons. There used to be six.
		//
		// Each of these is a binary, and each was drawn as a PAIR: one pill lit
		// for where you are and one dark for where you are not. Three pairs in
		// three rows is six buttons spent on three answers, and half of them are
		// telling the reader what they are not looking at. A toggle carries its
		// own state, so one pill an axis says the same thing in half the room.
		//
		// The rules that decide WHICH axes exist are unchanged, and they are the
		// reason this is a row and not a sentence: left-behind is a list of items
		// already and has nothing to regroup, and a task carries one items map
		// across all of its monsters, so by source there is no on-task figure to
		// show. An axis that cannot answer is not drawn at all.
		java.util.List<JPanel> axes = new ArrayList<>();
		axes.add(toggle(dropsLeftBehind ? "Left behind" : "Received", () ->
		{
			dropsLeftBehind = !dropsLeftBehind;
			lootKind = null;
			rebuildInPlace();
		}));
		// Offered on both readings, which it was not. Left behind used to stack
		// its two lists one under the other, which is the scroll-to-discover this
		// panel does not do anywhere else, and it left Received carrying an axis
		// the other half of the same coin did not have. The second axis is items
		// on one side and kinds on the other because that is what each side
		// holds; the control is the same control.
		//
		// Except over a window, where the roll keeps what was left behind as
		// items alone: there the reading has one axis, so none is offered.
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
				// Like the two axes above it. The kinds a task paid are not the
				// kinds everything paid, and this axis is only ever drawn while
				// the board is BY KIND, so a narrowing left standing could strand
				// the reader on a kind the new reading has no members of.
				lootKind = null;
				rebuildInPlace();
			}));
		}
		JPanel lens = new JPanel(new GridLayout(1, axes.size(), 3, 3));
		lens.setBackground(ColorScheme.DARK_GRAY_COLOR);
		for (JPanel a : axes)
		{
			lens.add(a);
		}
		p.add(lens);
		p.add(vgap(6));
		// Taken BEFORE the period branch, because the tasks are not the roll. The
		// roll is dated day by day and only begins where it begins; a task
		// carries the stamp of its own close, so the on-task reading answers a
		// window on its own and must not be gated by a roll that cannot.
		if (canAskOnTask && onTaskOnly)
		{
			List<LocalStore.BagItem> taskBag = onTaskBag();
			if (taskBag.isEmpty())
			{
				p.add(note("No task closed inside " + periodInSentence() + "."));
				return p;
			}
			return kindLens(p, wholeRecord() ? "On-task loot"
				: "Tasks closed in " + window().label, taskBag, "ontask:");
		}
		// The period governs this board too. The ledger's running totals cannot be
		// narrowed, but the loot ROLL can: it keeps one entry a day holding what
		// was taken and what was left, with the items and sources beside them, so
		// it answers a window exactly.
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
		List<LocalStore.SourceRow> sources = new ArrayList<>(sources());
		sources.sort(Comparator.comparingLong((LocalStore.SourceRow r) -> r.value).reversed());
		if (sources.isEmpty())
		{
			p.add(note("Drops appear here as you play: every kill, priced as it lands."));
			return p;
		}
		// The head this reading drew over a window and not over the whole record,
		// which left the same board with two shapes depending on the period, and
		// left the other half of the coin carrying a head it did not.
		long everyDrop = 0;
		long everyValue = 0;
		for (LocalStore.SourceRow r : sources)
		{
			everyDrop += r.loots;
			everyValue += r.value;
		}
		JPanel lifeHead = card("Drops received");
		lifeHead.add(row("Drops", fmt(everyDrop), accent()));
		lifeHead.add(row("Worth", gp(everyValue) + " gp", null));
		lifeHead.add(row("Sources", fmt(sources.size()), null));
		p.add(lifeHead);
		p.add(vgap(6));
		int shown = 0;
		for (LocalStore.SourceRow r : sources)
		{
			if (shown++ >= dropsShown)
			{
				break;
			}
			JPanel card = cardPlain();
			card.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
			card.add(row(r.name, gp(r.value) + " gp", accent()));
			// The same two figures the page behind this card shows. It used to
			// take its own from the ledger alone and disagree with the page it
			// opens on 42 sources: Nechryael read 686 kc here and 1,236 there.
			// "kc" is a kill count, and the Rift is searched rather than killed.
			boolean killed = isKillSource(r.name);
			String sub = (killed ? fmt(standingKills(r)) + " kc"
				: fmt(r.loots) + " drops")
				+ (r.pb != null ? " · PB " + pb(r.pb) : "");
			card.add(row(sub, r.loots > 0
				? gp(r.value / Math.max(1, r.loots))
					+ (killed ? " gp/drop" : " gp each") : "", null));
			final String src = r.name;
			card.addMouseListener(clicker(() -> openSource(src)));
			p.add(card);
			p.add(vgap(4));
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

	// Whether the loot board groups by what dropped a thing or by what it is.
	private boolean dropsByKind;


	/**
	 * The whole ledger folded into its kinds, and one kind opened out.
	 *
	 * <p>The same two screens the on-task board draws, over everything rather
	 * than over the journey. A reader who wants every rune they have ever been
	 * given asks here; one who wants only the ones slayer gave asks there.
	 */
	private JPanel buildLootByKind(JPanel p)
	{
		final List<LocalStore.BagItem> bag = plugin.allLoot();
		if (bag.isEmpty())
		{
			p.add(note("Drops appear here as you play: every kill, priced as it lands."));
			return p;
		}
		return kindLens(p, "Everything dropped", bag, "all:");
	}

	/**
	 * A bag's kinds as rows, each opening its own.
	 *
	 * <p>These fourteen lines were written twice, byte for byte, once on the Loot
	 * board and once on the Slayer board's Drops lens. Two copies of a control is
	 * one copy that will be changed and one that will not.
	 */
	private void addKindRows(JPanel p, List<LocalStore.BagItem> bag)
	{
		for (Kind k : kindsOf(bag))
		{
			JPanel r = row(k.name, fmt(k.qty) + " \u00b7 " + gp(k.value) + " gp", accent());
			r.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
			r.setToolTipText(fmt(k.distinct)
				+ (k.distinct == 1 ? " distinct item" : " distinct items"));
			final String pick = k.name;
			r.addMouseListener(clicker(() ->
			{
				lootKind = pick;
				rebuildInPlace();
			}));
			p.add(r);
		}
	}

	/**
	 * A bag read by what its items ARE: the kinds it folds into, or one of them
	 * opened out.
	 *
	 * <p>The same two screens wherever the bag came from -- the whole ledger, a
	 * window of it, one slayer task -- so they are built once. A board that
	 * grew its own answer to being drilled would be a board that disagreed with
	 * the others about what the reader is looking at.
	 *
	 * @param title what the summary card above the kinds is called
	 * @param key   what an opened kind remembers its row cap under, so two
	 *              boards drilled into Runes do not share one cap
	 */
	private JPanel kindLens(JPanel p, String title, List<LocalStore.BagItem> bag,
		String key)
	{
		final long[] sum = tallyOf(bag);
		if (lootKind != null)
		{
			return kindDrill(p, bag, key);
		}
		JPanel head = card(title);
		head.add(row("Items", fmt(sum[0]), accent()));
		head.add(row("Worth", gp(sum[1]) + " gp", null));
		head.add(row("Distinct items", fmt(bag.size()), null));
		p.add(head);
		p.add(vgap(6));
		java.util.LinkedHashMap<String, java.util.function.BooleanSupplier> ways =
			new java.util.LinkedHashMap<>();
		ways.put("These kinds", () -> copyPicture(ledgerKindsPicture(title, bag, sum)));
		ways.put("Every item", () -> copyPicture(lootPicture(title, bag, sum), true));
		p.add(copyHeader("Drops", ways));
		addKindRows(p, bag);
		return p;
	}

	/**
	 * One kind of a bag, opened out: what it came to, the way back, and then
	 * its items. Every board that offers kinds drills through here.
	 */
	private JPanel kindDrill(JPanel p, List<LocalStore.BagItem> bag, String key)
	{
		final List<LocalStore.BagItem> kept = ofKind(bag);
		final long[] mine = tallyOf(kept);
		// The head is the KIND's, not the bag's. It used to carry the whole
		// bag's totals under the kind's name, which is a number that is wrong
		// in the most believable way available.
		JPanel head = card(lootKind);
		head.add(row("Items", fmt(mine[0]), accent()));
		head.add(row("Worth", gp(mine[1]) + " gp", null));
		head.add(row("Distinct items", fmt(kept.size()), null));
		p.add(head);
		p.add(vgap(6));
		p.add(backToKinds(kept.size()));
		if (kept.isEmpty())
		{
			// reachable: a kind opened at one period, or under every task, and
			// then a narrower one chosen that holds none of it. A board that
			// draws nothing at all leaves the reader wondering what broke.
			p.add(note("Nothing of this kind here."));
			return p;
		}
		p.add(copyHeader(lootKind, () -> copyPicture(
			lootPicture(lootKind, kept, mine), true)));
		addBagRows(p, kept, drillShown.getOrDefault(key + lootKind, ROW_CAP),
			key + lootKind);
		return p;
	}

	/** The ledger's kinds as a picture. */
	private JPanel ledgerKindsPicture(String title, List<LocalStore.BagItem> bag,
		long[] sum)
	{
		JPanel page = column();
		JPanel head = card(title);
		head.add(row("Items", fmt(sum[0]), accent()));
		head.add(row("Worth", gp(sum[1]) + " gp", null));
		head.add(row("Distinct items", fmt(bag.size()), null));
		page.add(head);
		page.add(vgap(6));
		for (Kind k : kindsOf(bag))
		{
			page.add(row(k.name, fmt(k.qty) + " \u00b7 " + gp(k.value) + " gp", accent()));
		}
		return page;
	}

	// The uncollected ledger: what was walked past, by source and by item.
	private JPanel buildLeftBehind(JPanel p)
	{
		List<LocalStore.UntakenRow> rows = plugin.untakenSources();
		rows.sort(Comparator.comparingLong((LocalStore.UntakenRow r) -> r.value).reversed());
		long totalQty = 0;
		long totalVal = 0;
		for (LocalStore.UntakenRow r : rows)
		{
			totalQty += r.qty;
			totalVal += r.value;
		}
		if (rows.isEmpty())
		{
			p.add(note("What you walk past gets counted here, priced at the "
				+ "moment you declined it."));
			return p;
		}
		// The same head the Received reading draws, because this is the other half
		// of one board. Red belongs to the head as the board's single gesture: a
		// colour on every row of a list says nothing the list does not already
		// say, and it made one lens read in two palettes a click apart, since the
		// windowed path never used it at all.
		List<LocalStore.UntakenRow> items = plugin.untakenItems();
		items.sort(Comparator.comparingLong((LocalStore.UntakenRow r) -> r.value).reversed());
		JPanel head = card("Left behind");
		head.add(row("Items", fmt(totalQty), ACCENT_RED));
		head.add(row("Worth", gp(totalVal) + " gp", null));
		head.add(row(dropsByKind ? "Distinct items" : "Sources",
			fmt(dropsByKind ? items.size() : rows.size()), null));
		p.add(head);
		p.add(vgap(6));
		if (dropsByKind)
		{
			if (items.isEmpty())
			{
				p.add(note("Nothing walked past has been priced yet."));
				return p;
			}
			final int itemCap = drillShown.getOrDefault("left:item", ROW_CAP);
			int mounted = 0;
			for (LocalStore.UntakenRow r : items)
			{
				if (mounted++ >= itemCap)
				{
					p.add(expander("left:item", itemCap, items.size()));
					break;
				}
				JPanel card = cardPlain();
				card.setCursor(java.awt.Cursor.getPredefinedCursor(
					java.awt.Cursor.HAND_CURSOR));
				card.add(row(r.name, gp(r.value) + " gp", ACCENT_RED));
				card.add(row("\u00d7" + fmt(r.qty), r.qty > 0
					? gp(r.value / Math.max(1, r.qty)) + " gp each" : "", null));
				final String itm = r.name;
				card.addMouseListener(clicker(() ->
				{
					leftBehindItem = itm;
					leftBehindSource = null;
					rebuild();
				}));
				p.add(card);
				p.add(vgap(4));
			}
			return p;
		}
		final int srcCap = drillShown.getOrDefault("left:source", ROW_CAP);
		int shown = 0;
		for (LocalStore.UntakenRow r : rows)
		{
			if (shown++ >= srcCap)
			{
				// It used to stop here without a word. Every list in the panel
				// stops somewhere; the ones a reader can walk past say so.
				p.add(expander("left:source", srcCap, rows.size()));
				break;
			}
			// The same two-line card the Received reading draws for a source: the
			// name and what it came to on top, the count and the rate under it.
			// This is the half of the coin that reads as the other half.
			JPanel card = cardPlain();
			card.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
			card.add(row(r.name, gp(r.value) + " gp", ACCENT_RED));
			card.add(row(fmt(r.qty) + " left", r.qty > 0
				? gp(r.value / Math.max(1, r.qty)) + " gp each" : "", null));
			final String src = r.name;
			card.addMouseListener(clicker(() ->
			{
				leftBehindSource = src;
				leftBehindItem = null;
				rebuild();
			}));
			p.add(card);
			p.add(vgap(4));
		}
		return p;
	}

	// The dryness ledger, read once per session off the first source opened.
	private List<ChronicleApiClient.GrindRow> grindsCache;
	private boolean grindsFetching;

	// The journey fetches once per session on first open; null = not yet asked.
	private ChronicleApiClient.SlayerJourney journeyCache;
	private boolean journeyFetching;
	// Index into the journey (newest-first) of the task under the glass, or -1.
	private int detailTask = -1;
	// The Left behind lens drilled from one end or the other; both null = the list.
	private String leftBehindSource;
	private String leftBehindItem;

	/** Drop every view built from the last account's journal. EDT only. */
	void resetAccountCaches()
	{
		journeyCache = null;
		journeyFetching = false;
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
		// disown any gather still reading the old journal
		historyEpoch++;
		historyGathering = false;
		detailItem = null;
		detailSource = null;
		// Lifetime on-task loot, which is this account's and nobody else's. Its
		// twin taskKillsEverCache was dropped every build; this was dropped on no
		// trigger at all, so a switch showed the previous account's items.
		taskItemsEver = null;
		taskKillsEverCache = null;
		detailStack.clear();
		drillShown.clear();
		histListShown.clear();
		openFolds.clear();
		// the icons and the kinds are read off this account's ledger and counters
		signatureItems.clear();
		scaledIcons.clear();
		itemWaiting.clear();
		sourceKinds.clear();
		skilled = null;
		ledgerNames = null;
		gatherHistory();
		rebuild();
	}

	/** Stop the repeating timers. Called from the plugin's shutDown. */
	void shutdown()
	{
		homeTicker.stop();
		searchDebounce.stop();
	}
	private int slayerShown = ROW_CAP;

	// Which of the Slayer tab's three boards is up. A boolean held two and could
	// not hold a third. Sticky, like every other lens in the panel: applyTab clears
	// what is paged out and what is drilled into, never which lens a reader chose.
	private String slayerLens = "Tasks";

	// The current task, then ONE of two boards: the journal's task-by-task
	// journey, or the game's own count per monster. They answer different
	// questions, so a pill picks between them rather than stacking one under the
	// other, which is what put the kill log two thousand pixels down. The live
	// task belongs to the tab and not to either board, so it stays above the
	// strip and is on screen whichever pill is lit.
	private JPanel buildSlayer()
	{
		JPanel p = column();
		ChronicleEventCapture.SlayerView task = plugin.slayerView();
		if (task != null)
		{
			JPanel card = card("Current task");
			card.add(row(task.task, task.remaining + " left", accent()));
			if (task.initial > 0)
			{
				card.add(progress(1f - (float) task.remaining / task.initial));
			}
			p.add(card);
			p.add(vgap(6));
		}

		JPanel lens = new JPanel(new GridLayout(1, 3, 3, 3));
		lens.setBackground(ColorScheme.DARK_GRAY_COLOR);
		for (String l : new String[]{"Tasks", "Monsters", "Drops"})
		{
			boolean on = l.equals(slayerLens);
			JLabel pill = new JLabel(l, JLabel.CENTER);
			pill.setOpaque(true);
			pill.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
			pill.setFont(FontManager.getRunescapeSmallFont());
			pill.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			pill.setForeground(on ? accent() : ColorScheme.LIGHT_GRAY_COLOR.darker());
			pill.setCursor(java.awt.Cursor.getPredefinedCursor(
				java.awt.Cursor.HAND_CURSOR));
			pill.addMouseListener(clicker(() ->
			{
				slayerLens = l;
				rebuildInPlace();
			}));
			lens.add(pill);
		}
		p.add(lens);
		p.add(vgap(6));

		// Asked for under every pill, because it is what the Tasks board opens
		// on: a read that only fired from that board would leave a reader who
		// had been sitting on another one looking at "Reading the task journey"
		// the first time they came back.
		if (!journeyFetching)
		{
			journeyFetching = true;
			plugin.fetchSlayerJourney(j -> SwingUtilities.invokeLater(() ->
			{
				journeyFetching = false;
				// null = store not mounted. Don't cache it and don't rebuild
				// here; the next rebuild retries.
				if (j == null)
				{
					return;
				}
				boolean moved = journeyMoved(journeyCache, j);
				journeyCache = j;
				// Not while the other board is up: a finished task would repaint
				// over the kill log and throw a reader back to the top of it.
				if (moved && view == View.SLAYER && "Tasks".equals(slayerLens))
				{
					rebuild();
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

		// Paint the cached journey at once, with no flicker, and re-read the journal
		// behind it. The read rebuilds only when the journey has actually moved,
		// so an unchanged journal cannot start a loop.
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

	/**
	 * Everything the tasks gave, summed across the journey and ranked by what it
	 * came to. On-task by construction: the ledger's per-source totals cannot tell
	 * a task kill from a stray one, and the journey only ever held the former.
	 *
	 * <p>It reads the period like every other board, because a task carries the
	 * stamp of its own close.
	 */
	private JPanel addOnTaskLoot(JPanel p)
	{
		Window w = window();
		// windowMs, not a second copy of it: this had its own pair of midnights
		// and so reported the whole day's task loot under the sitting.
		long[] ms = windowMs();
		final List<LocalStore.BagItem> bag = plugin.onTaskLoot(ms[0], ms[1], lootTask,
			wholeRecord());
		if (bag.isEmpty())
		{
			p.add(taskPicker());
			p.add(note(lootTask != null
				? "No loot logged on " + lootTask + " inside " + periodInSentence() + "."
				: wholeRecord()
					? "No task loot in the journal yet. It collects as tasks close."
					: "No task loot inside " + periodInSentence() + "."));
			return p;
		}
		long count = 0;
		long worth = 0;
		for (LocalStore.BagItem b : bag)
		{
			count += b.qty;
			worth += b.value;
		}
		final long qty = count;
		final long value = worth;

		// Drilled into one kind: that kind's items, which is the only place the
		// individual rows still live. The task picker stays above it, because
		// changing task is the other half of the question being asked.
		if (lootKind != null)
		{
			p.add(taskPicker());
			return kindDrill(p, bag, "task:");
		}
		// Narrowed by the task on show, so the card counts the tasks, kills and
		// superiors this bag actually came off. It used to be every task in the
		// window, which is why the figures had to be dropped entirely the
		// moment a reader picked one.
		final long[] tally = plugin.onTaskTally(ms[0], ms[1], lootTask, wholeRecord());
		p.add(onTaskHead(qty, value, tally));
		p.add(vgap(6));
		p.add(taskPicker());

		// Otherwise the kinds, which is what makes this board readable: two
		// hundred and eighty seven rows became sixteen, and the question a
		// reader actually has -- what has slayer paid me in runes -- is one of
		// them rather than a scroll.
		java.util.LinkedHashMap<String, java.util.function.BooleanSupplier> ways =
			new java.util.LinkedHashMap<>();
		ways.put("These kinds", () -> copyKinds(bag, qty, value, tally));
		ways.put("Every item", () -> copyPicture(
			lootPicture(lootTask == null ? "On-task loot" : lootTask, bag,
				new long[]{qty, value}), true));
		p.add(copyHeader("Drops", ways));
		addKindRows(p, bag);
		return p;
	}

	/**
	 * One kind's whole list as a picture: the head, then every item, uncapped.
	 * What is on screen is capped; what gets shared never is.
	 */
	private JPanel lootPicture(String title, List<LocalStore.BagItem> bag, long[] sum)
	{
		JPanel page = column();
		JPanel head = card(title);
		head.add(row("Items", fmt(sum[0]), accent()));
		head.add(row("Worth", gp(sum[1]) + " gp", null));
		head.add(row("Distinct items", fmt(bag.size()), null));
		page.add(head);
		page.add(vgap(6));
		for (LocalStore.BagItem b : bag)
		{
			page.add(row(b.name + (b.qty > 1 ? " \u00d7" + fmt(b.qty) : ""),
				b.value > 0 ? gp(b.value) + " gp" : "", null));
		}
		return page;
	}

	/** The summary as a picture: the head, then the kinds. */
	private JPanel kindsPicture(List<LocalStore.BagItem> bag, long qty, long value,
		long[] tally)
	{
		JPanel page = column();
		page.add(onTaskHead(qty, value, tally));
		page.add(vgap(6));
		if (lootTask != null)
		{
			page.add(row("Task", lootTask, accent()));
			page.add(vgap(4));
		}
		for (Kind k : kindsOf(bag))
		{
			page.add(row(k.name, fmt(k.qty) + " \u00b7 " + gp(k.value) + " gp", accent()));
		}
		return page;
	}

	/**
	 * The copy pill on the summary: the sixteen kinds, or every item under them
	 * in one tall column.
	 */
	private boolean copyKinds(List<LocalStore.BagItem> bag, long qty, long value,
		long[] tally)
	{
		return copyPicture(kindsPicture(bag, qty, value, tally));
	}

	/**
	 * The one way this panel says a list goes on, and the one way it opens.
	 *
	 * <p>There were three. A Swing JButton on some boards, which is the only
	 * heavy chrome in a panel made of rows and reads as a dialog control; a dim
	 * clickable row on others; and on one board a dim row that was not clickable
	 * at all, so it announced that there was more and offered no way to it.
	 *
	 * <p>It opens the whole list rather than another capful. The lists here run to
	 * a couple of hundred rows at the outside, and the panel already mounts more
	 * than that on the trackers board, so paging them is a cost with no benefit:
	 * a list of ninety should not be fifteen clicks that each say six.
	 */
	private JPanel moreRow(long remaining, Runnable reveal)
	{
		return moreRow("Show " + fmt(remaining) + " more", reveal);
	}

	/** The same control, where the tail of a list is not a countable remainder. */
	private JPanel moreRow(String label, Runnable reveal)
	{
		JPanel more = ghostRow(label, "");
		more.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		more.addMouseListener(clicker(reveal));
		return more;
	}

	/**
	 * A row that DOES something rather than revealing more of what is already
	 * there. Drawn in the same shape as the rest of the panel, in accent, because
	 * a Swing button in a column of rows reads as a dialog that wandered in.
	 */
	private JPanel actionRow(String label, Runnable go)
	{
		JPanel r = row(label, "", accent(), true);
		r.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		r.addMouseListener(clicker(go));
		return r;
	}

	/**
	 * The one shape every capped list uses to say it is capped.
	 *
	 * <p>A cap with no way past it is worse than no cap: the reader cannot tell
	 * a short list from a truncated one. This says how many are held back and
	 * opens another page of them.
	 */
	private JPanel expander(String key, int cap, int of)
	{
		return moreRow(of - cap, () ->
		{
			drillShown.put(key, of);
			rebuildInPlace();
		});
	}

	/** What everything of one kind came to, for one row of the summary. */
	private static final class Kind
	{
		final String name;
		long qty;
		long value;
		// how many distinct items this kind holds, which is not a count of kinds
		int distinct;

		Kind(String name)
		{
			this.name = name;
		}
	}

	/**
	 * The name the summary gives everything the taxonomy does not claim. It is
	 * a row like any other and opens like one: a unique is exactly the thing a
	 * reader came to look at, and burying it would be the wrong way round.
	 */
	private static final String UNFILED = "Everything else";

	/** A bag folded into its kinds, dearest first. */
	private List<Kind> kindsOf(List<LocalStore.BagItem> bag)
	{
		Map<String, Kind> by = new LinkedHashMap<>();
		for (LocalStore.BagItem b : bag)
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

	/** The way back out of one kind, and how many distinct items it holds. */
	private JPanel backToKinds(int held)
	{
		JPanel r = row("< All kinds", fmt(held) + (held == 1 ? " item" : " items"), null);
		JLabel back = (JLabel) ((BorderLayout) r.getLayout())
			.getLayoutComponent(BorderLayout.CENTER);
		back.setFont(FontManager.getRunescapeSmallFont());
		back.setForeground(accent());
		r.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		r.addMouseListener(clicker(() ->
		{
			lootKind = null;
			rebuildInPlace();
		}));
		return r;
	}

	/**
	 * A bag as rows, capped, with the cap as a button rather than a dead line.
	 * Every list in the panel stops somewhere; this is the one shape they all
	 * use to say so.
	 */
	private void addBagRows(JPanel p, List<LocalStore.BagItem> bag, int cap, String key)
	{
		int mounted = 0;
		for (LocalStore.BagItem b : bag)
		{
			if (mounted++ >= cap)
			{
				p.add(expander(key, cap, bag.size()));
				break;
			}
			JPanel r = row(b.name + (b.qty > 1 ? " \u00d7" + fmt(b.qty) : ""),
				b.value > 0 ? gp(b.value) + " gp" : "", null);
			r.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
			final String item = b.name;
			r.addMouseListener(clicker(() -> openItem(item)));
			p.add(r);
		}
	}

	private long[] tallyOf(List<LocalStore.BagItem> bag)
	{
		long q = 0;
		long v = 0;
		for (LocalStore.BagItem b : bag)
		{
			q += b.qty;
			v += b.value;
		}
		return new long[]{q, v};
	}

	/**
	 * A bag narrowed to the kind on show, or the whole bag where none is.
	 *
	 * <p>The kind is asked of the item's NAME rather than its id, because that
	 * is what the taxonomy is keyed by and what the row already shows.
	 */
	private List<LocalStore.BagItem> ofKind(List<LocalStore.BagItem> bag)
	{
		if (lootKind == null)
		{
			return bag;
		}
		List<LocalStore.BagItem> kept = new ArrayList<>();
		for (LocalStore.BagItem b : bag)
		{
			String k = ItemKinds.kindOf(b.name);
			if (UNFILED.equals(lootKind) ? k == null : lootKind.equals(k))
			{
				kept.add(b);
			}
		}
		return kept;
	}

	/**
	 * What the on-task take amounts to. The loot itself, then what it was killed
	 * out of: the kills that dropped something, and the superiors among them.
	 * Kills that dropped nothing belong to the task rather than to the loot, so
	 * they are counted on the task's own page and not here.
	 */
	private JPanel onTaskHead(long qty, long value, long[] tally)
	{
		JPanel head = card("On-task loot");
		head.add(row("Items", fmt(qty), accent()));
		head.add(row("Worth", gp(value) + " gp", null));
		// How many TASKS this is the take from, which is the thing that makes
		// the rest of the card mean anything: 81M gp is a different sentence
		// over four hundred tasks than over four. A count of distinct items sat
		// here and said nothing the list below it did not already say.
		if (tally != null && tally.length > 2)
		{
			head.add(row("Tasks", fmt(tally[2]), null));
		}
		if (tally != null && tally.length > 0 && tally[0] > 0)
		{
			head.add(row("Kills logged", fmt(tally[0]), null));
		}
		// Nothing to say to an account that never unlocked Bigger and Badder.
		if (tally != null && tally.length > 1 && tally[1] > 0)
		{
			head.add(row("Superiors", fmt(tally[1]), null));
		}
		return head;
	}

	/**
	 * The board as a picture: the card, then the whole list, set in columns rather
	 * than cut off at sixty. A full grind to 99 runs to hundreds of kinds, and
	 * that is exactly the page somebody wants to show; truncating it would throw
	 * away the thing being shared.
	 */
	private JPanel onTaskLootPicture(List<LocalStore.BagItem> bag, long qty, long value,
		long[] tally)
	{
		JPanel p = column();
		p.add(onTaskHead(qty, value, tally));
		p.add(vgap(6));

		List<LocalStore.BagItem> shown = bag.size() > COPY_MOST
			? bag.subList(0, COPY_MOST)
			: bag;
		for (LocalStore.BagItem b : shown)
		{
			p.add(row(b.name + (b.qty > 1 ? " \u00d7" + fmt(b.qty) : ""),
				b.value > 0 ? gp(b.value) + " gp" : "", null));
		}
		if (shown.size() < bag.size())
		{
			// A label, not the panel's "Show N more" control, and deliberately so:
			// this board is drawn to be copied as a PICTURE, and nothing in a
			// picture can be clicked. It says the picture is short, which is all
			// it can honestly offer.
			p.add(ghostRow("+ " + fmt(bag.size() - shown.size()) + " more", ""));
		}
		return p;
	}


	/**
	 * A copy pill with more than one thing it could copy.
	 *
	 * <p>The summary board can be shared as the sixteen kinds or as every item
	 * under them, and which of those a reader wants is not knowable from here.
	 * The pill still answers in place, because the menu item does the copying
	 * and then writes the word back.
	 */
	private JPanel copyHeader(String title,
		java.util.LinkedHashMap<String, java.util.function.BooleanSupplier> choices)
	{
		JPanel r = row(title, "copy", null);
		BorderLayout layout = (BorderLayout) r.getLayout();
		JLabel t = (JLabel) layout.getLayoutComponent(BorderLayout.CENTER);
		t.setFont(FontManager.getRunescapeSmallFont());
		t.setForeground(accent());
		JLabel take = (JLabel) layout.getLayoutComponent(BorderLayout.EAST);
		if (take == null)
		{
			return r;
		}
		take.setFont(FontManager.getRunescapeSmallFont());
		take.setForeground(ColorScheme.LIGHT_GRAY_COLOR.darker());
		take.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		take.setToolTipText("Copy this board as a picture");
		take.addMouseListener(clicker(() ->
		{
			javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
			for (java.util.Map.Entry<String, java.util.function.BooleanSupplier> e
				: choices.entrySet())
			{
				javax.swing.JMenuItem item = new javax.swing.JMenuItem(e.getKey());
				item.setFont(FontManager.getRunescapeSmallFont());
				item.addActionListener(a ->
				{
					boolean ok = e.getValue().getAsBoolean();
					take.setText(ok ? "copied" : "cannot copy");
					take.setForeground(ok ? accent() : ColorScheme.PROGRESS_ERROR_COLOR);
				});
				menu.add(item);
			}
			menu.show(take, 0, take.getHeight());
		}));
		return r;
	}

	/**
	 * A section head with a copy on its right. A board that is not a drill has no
	 * back row to hang one on, and it should still be shareable.
	 */
	private JPanel copyHeader(String title, java.util.function.BooleanSupplier copy)
	{
		JPanel r = row(title, "copy", null);
		BorderLayout layout = (BorderLayout) r.getLayout();
		JLabel t = (JLabel) layout.getLayoutComponent(BorderLayout.CENTER);
		t.setFont(FontManager.getRunescapeSmallFont());
		t.setForeground(accent());
		JLabel take = (JLabel) layout.getLayoutComponent(BorderLayout.EAST);
		if (take != null)
		{
			take.setFont(FontManager.getRunescapeSmallFont());
			take.setForeground(ColorScheme.LIGHT_GRAY_COLOR.darker());
			take.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
			take.setToolTipText("Copy this board as a picture");
			take.addMouseListener(clicker(() ->
			{
				boolean ok = copy.getAsBoolean();
				take.setText(ok ? "copied" : "cannot copy");
				take.setForeground(ok ? accent() : ColorScheme.PROGRESS_ERROR_COLOR);
			}));
		}
		return r;
	}

	/**
	 * The game's own count per monster, as last scraped from the Kill Log
	 * interface. Its own board now: it answers "how many of these have I
	 * killed", which the task journey never does.
	 */
	private JPanel addKillLog(JPanel p)
	{
		JsonObject cl = clogNow();
		List<Map.Entry<String, Long>> kcs = new ArrayList<>();
		if (cl.has("slayer_kcs") && cl.get("slayer_kcs").isJsonObject())
		{
			for (Map.Entry<String, com.google.gson.JsonElement> e
				: cl.getAsJsonObject("slayer_kcs").entrySet())
			{
				long v = safeLong(e.getValue());
				if (v > 0)
				{
					kcs.add(new java.util.AbstractMap.SimpleEntry<>(e.getKey(), v));
				}
			}
		}
		if (kcs.isEmpty())
		{
			// Behind a pill this is a screen of its own and has to say something.
			// Stacked at the foot of the journey it never did: both guards fell
			// through in silence and the card was simply never built.
			p.add(note("No kill log yet. It copies itself the next time you open "
				+ "the Slayer Kill Log in game."));
			return p;
		}
		kcs.sort(Map.Entry.<String, Long>comparingByValue().reversed());
		JPanel card = card("Kill log");
		int mounted = 0;
		for (Map.Entry<String, Long> e : kcs)
		{
			if (mounted++ >= 20)
			{
				break;
			}
			JPanel r = row(e.getKey(), fmt(e.getValue()), null);
			r.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
			final String mob = e.getKey();
			r.addMouseListener(clicker(() -> openSourceLoose(mob)));
			card.add(r);
		}
		if (kcs.size() > 20)
		{
			card.add(ghostRow("and " + fmt(kcs.size() - 20) + " more. Search finds them", ""));
		}
		p.add(card);
		return p;
	}

	// Has the journey moved since the copy on screen? A finished task, a new
	// one, or another kill on the newest one is everything the block shows.
	private static boolean journeyMoved(ChronicleApiClient.SlayerJourney was,
		ChronicleApiClient.SlayerJourney now)
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

	// One task on its own: what it was made of and what it dropped. The
	// monster's whole lifetime bag is a button away at the bottom.
	private JPanel buildTaskDetail(int index)
	{
		JPanel p = column();
		JPanel back = row("< Back", "", null);
		JLabel bl = (JLabel) ((BorderLayout) back.getLayout()).getLayoutComponent(BorderLayout.CENTER);
		bl.setFont(FontManager.getRunescapeSmallFont());
		bl.setForeground(accent());
		back.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		back.addMouseListener(clicker(() ->
		{
			detailTask = -1;
			rebuild();
		}));
		p.add(back);
		p.add(vgap(4));
		ChronicleApiClient.SlayerJourney j = journeyCache;
		ChronicleApiClient.SlayerTask t = j != null && index >= 0 && index < j.tasks.size()
			? j.tasks.get(index) : null;
		if (t == null)
		{
			p.add(note("That task is no longer in the journal."));
			return p;
		}
		JPanel head = card(t.task.toUpperCase(Locale.ROOT));
		String kills = t.inProgress && t.assignment > t.kills
			? fmt(t.kills) + " / " + fmt(t.assignment) : fmt(t.kills);
		head.add(row("Kills logged", kills, accent()));
		if (t.noLootKills > 0)
		{
			head.add(row("Killed without loot", fmt(t.noLootKills), null));
		}
		head.add(row("Worth", gp(t.totalValue) + " gp", null));
		if (t.ts > 0)
		{
			head.add(row(t.inProgress ? "Started" : "Finished",
				TASK_DAY.format(Instant.ofEpochMilli((long) (t.ts * 1000))), null));
		}
		p.add(head);
		p.add(vgap(6));

		// What the assignment was made of: brutals, superiors and a boss detour
		// all count toward one task.
		List<LocalStore.UntakenRow> monsters = plugin.slayerTaskMonsters(index);
		if (!monsters.isEmpty())
		{
			p.add(group("Killed"));
			for (LocalStore.UntakenRow m : monsters)
			{
				JPanel r = row(m.name, "×" + fmt(m.qty), null);
				r.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
				final String who = m.name;
				r.addMouseListener(clicker(() -> openSourceLoose(who)));
				p.add(r);
			}
			p.add(vgap(6));
		}

		List<LocalStore.BagItem> bag = plugin.slayerTaskItems(index);
		if (bag.isEmpty())
		{
			p.add(note("No loot recorded against this task."));
		}
		else
		{
			p.add(group("Loot from this task"));
			for (LocalStore.BagItem it : bag)
			{
				JPanel r = row(it.name + (it.qty > 1 ? " ×" + fmt(it.qty) : ""),
					gp(it.value) + " gp", null);
				r.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
				final String item = it.name;
				r.addMouseListener(clicker(() -> openItem(item)));
				p.add(r);
			}
		}
		p.add(vgap(8));
		final String taskName = t.task;
		p.add(actionRow("All kills of " + t.task, () ->
		{
			detailTask = -1;
			openSourceLoose(taskName);
		}));
		return p;
	}

	// The Left behind lens drilled from either end: what was left at a source,
	// or where an item was left.
	private JPanel buildLeftBehindDetail()
	{
		JPanel p = column();
		JPanel back = row("< Back", "", null);
		JLabel bl = (JLabel) ((BorderLayout) back.getLayout()).getLayoutComponent(BorderLayout.CENTER);
		bl.setFont(FontManager.getRunescapeSmallFont());
		bl.setForeground(accent());
		back.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		back.addMouseListener(clicker(() ->
		{
			leftBehindSource = null;
			leftBehindItem = null;
			rebuild();
		}));
		p.add(back);
		p.add(vgap(4));

		if (leftBehindSource != null)
		{
			List<LocalStore.BagItem> bag = plugin.untakenItemsOf(leftBehindSource);
			// The headline is the source's own tally. The rows below start later,
			// so they don't sum to it.
			long qty = 0;
			long val = 0;
			for (LocalStore.UntakenRow r : plugin.untakenSources())
			{
				if (r.name.equals(leftBehindSource))
				{
					qty = r.qty;
					val = r.value;
					break;
				}
			}
			JPanel head = card(leftBehindSource.toUpperCase(Locale.ROOT));
			head.add(row("Left on the floor", fmt(qty) + " items", ACCENT_RED));
			head.add(row("Worth", gp(val) + " gp", null));
			p.add(head);
			p.add(vgap(6));
			if (bag.isEmpty())
			{
				p.add(note("The count above is older than the itemised record. "
					+ "What this source leaves behind is listed here from now on."));
				return p;
			}
			p.add(group("Declined"));
			for (LocalStore.BagItem b : bag)
			{
				JPanel r = row(b.name + (b.qty > 1 ? " ×" + fmt(b.qty) : ""),
					gp(b.value) + " gp", ACCENT_RED);
				r.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
				final String itm = b.name;
				r.addMouseListener(clicker(() ->
				{
					leftBehindItem = itm;
					leftBehindSource = null;
					rebuild();
				}));
				p.add(r);
			}
			return p;
		}

		List<LocalStore.UntakenRow> sources = plugin.untakenSourcesOf(leftBehindItem);
		long qty = 0;
		long val = 0;
		for (LocalStore.UntakenRow r : plugin.untakenItems())
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
		head.add(row("Worth", gp(val) + " gp", null));
		p.add(head);
		p.add(vgap(6));
		if (sources.isEmpty())
		{
			p.add(note("No source itemised for this yet."));
			return p;
		}
		p.add(group("Left where"));
		for (LocalStore.UntakenRow r : sources)
		{
			JPanel row = row(r.name, "×" + fmt(r.qty) + " · " + gp(r.value) + " gp", ACCENT_RED);
			row.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
			final String src = r.name;
			row.addMouseListener(clicker(() ->
			{
				leftBehindSource = src;
				leftBehindItem = null;
				rebuild();
			}));
			p.add(row);
		}
		return p;
	}

	private void addJourney(JPanel p, ChronicleApiClient.SlayerJourney j)
	{
		if (j.tasks.isEmpty() && j.completedTasks == 0)
		{
			p.add(note("No tasks in the journal yet. They collect as "
				+ "you play with the Slayer plugin on."));
			return;
		}
		// The period governs this board too. A task carries the stamp of its own
		// close, so a window is a filter over the journey rather than a delta,
		// and the headline counts what it admitted rather than the lifetime.
		List<ChronicleApiClient.SlayerTask> shown = new ArrayList<>();
		List<Integer> where = new ArrayList<>();
		for (int i = 0; i < j.tasks.size(); i++)
		{
			if (insideWindow((long) (j.tasks.get(i).ts * 1000)))
			{
				shown.add(j.tasks.get(i));
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
			for (ChronicleApiClient.SlayerTask t : shown)
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
		head.add(row("Kills on task", fmt(killsOnTask), null));
		head.add(row("On-task loot", gp(onTaskLoot) + " gp", null));
		// Only an imported legacy journal carries this; nothing writes it now.
		if (j.totalXpEst > 0)
		{
			head.add(row("Slayer xp (est.)", gp(j.totalXpEst), null));
		}
		p.add(head);
		p.add(vgap(6));
		int mounted = 0;
		for (int k = 0; k < shown.size(); k++)
		{
			ChronicleApiClient.SlayerTask t = shown.get(k);
			if (mounted++ >= slayerShown)
			{
				break;
			}
			JPanel card = cardPlain();
			// Lit name, no suffix: the card has no room for one.
			card.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
			// the drill indexes the WHOLE journey, not the window's slice of it
			final int at = where.get(k);
			card.addMouseListener(clicker(() ->
			{
				detailTask = at;
				rebuild();
			}));
			card.add(row(t.task, t.totalValue > 0 ? gp(t.totalValue) + " gp" : "",
				accent(), t.inProgress));
			String kills = t.inProgress && t.assignment > t.kills
				? fmt(t.kills) + " / " + fmt(t.assignment)
				: fmt(t.kills) + " kills";
			if (t.noLootKills > 0)
			{
				kills += " · " + fmt(t.noLootKills) + " no-drop";
			}
			card.add(row(kills, t.ts > 0
				? TASK_DAY.format(Instant.ofEpochMilli((long) (t.ts * 1000))) : "", null));
			p.add(card);
			p.add(vgap(4));
		}
		// shown, not j.tasks: the cards above are the window's tasks and the count
		// was the whole journey's, so a sitting with two tasks in it offered to
		// show three hundred more and then mounted a lifetime.
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

	// How much of one drilled-into list is mounted, keyed by that list: a source's
	// own page, a kind drilled out of a bag, the window's source and left-behind
	// lists, the lifetime Walked past board's two, and one monster's on-task
	// assignments. "Show more" raises it per key.
	private final Map<String, Integer> drillShown = new LinkedHashMap<>();

	// ------------------------------------------------------------------
	// The pivot navigation: item view ⇄ source view
	// ------------------------------------------------------------------

	/**
	 * Leave whichever whole-panel page the reader was SENT to.
	 *
	 * <p>rebuild() picks the first of these it finds and it looks for them
	 * BEFORE it looks at the tab, so one left standing by a navigation that did
	 * not think about it beats wherever the reader was actually going. That is
	 * how the info page bricked the tab strip: every click rebuilt it.
	 */
	private void leaveSentPage()
	{
		allTrackers = false;
		showInfo = false;
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

	/**
	 * Every count the journal holds about itself.
	 *
	 * <p>No dates and no name anywhere on it, so it can be handed to somebody
	 * who is helping without handing over the account with it. Everything here
	 * is a size or a tally; nothing is derived and nothing is an opinion.
	 */
	private JPanel buildInfo()
	{
		JPanel p = column();
		p.add(backRow(() -> copyPicture(stripChrome(buildInfo()))));
		p.add(vgap(4));
		Map<String, Long> f = plugin.journalFacts();

		JPanel loot = card("Loot");
		loot.add(row("Sources", fmt(f.getOrDefault("sources", 0L)), accent()));
		loot.add(row("Item rows", fmt(f.getOrDefault("itemRows", 0L)), null));
		loot.add(row("Loot events", fmt(f.getOrDefault("lootEvents", 0L)), null));
		loot.add(row("Worth", gp(f.getOrDefault("lootWorth", 0L)) + " gp", null));
		loot.add(row("Dated days", fmt(f.getOrDefault("lootDays", 0L)), null));
		loot.add(row("Left behind", fmt(f.getOrDefault("untakenItems", 0L)) + " items, "
			+ fmt(f.getOrDefault("untakenSources", 0L)) + " sources", null));
		p.add(loot);
		p.add(vgap(6));

		JPanel slayer = card("Slayer");
		slayer.add(row("Assignments", fmt(f.getOrDefault("tasks", 0L)), accent()));
		slayer.add(row("Closed", fmt(f.getOrDefault("tasksClosed", 0L)), null));
		p.add(slayer);
		p.add(vgap(6));

		JPanel log = card("Collection log");
		// A total of zero is a total nobody has told us, so it is not printed as
		// one. This card reports what the record HOLDS, so the slots still stand.
		long availKnown = f.getOrDefault("clogAvailable", 0L);
		log.add(row("Slots filled", fmt(f.getOrDefault("clogSlots", 0L))
			+ (availKnown > 0 ? " of " + fmt(availKnown) : ""), accent()));
		log.add(row("Items named", fmt(f.getOrDefault("clogItems", 0L)), null));
		log.add(row("Pages with a count", fmt(f.getOrDefault("clogPages", 0L)), null));
		log.add(row("Kill Log lines", fmt(f.getOrDefault("killLogLines", 0L)), null));
		log.add(row("Labelled kill lines", fmt(f.getOrDefault("pageKillLines", 0L)), null));
		p.add(log);
		p.add(vgap(6));

		JPanel counted = card("Counted");
		counted.add(row("Trackers", fmt(f.getOrDefault("trackers", 0L)), accent()));
		counted.add(row("Skills", fmt(f.getOrDefault("skills", 0L)), null));
		counted.add(row("Feed entries", fmt(f.getOrDefault("feed", 0L)), null));
		counted.add(row("Chat kill counts", fmt(f.getOrDefault("chatCounts", 0L)), null));
		counted.add(row("Anchored counts", fmt(f.getOrDefault("anchors", 0L)), null));
		p.add(counted);
		p.add(vgap(6));

		JPanel file = card("On disk");
		file.add(row("Journal", bytes(f.getOrDefault("journalBytes", 0L)), accent()));
		file.add(row("History spine", bytes(f.getOrDefault("spineBytes", 0L)), null));
		file.add(row("Schema", fmt(f.getOrDefault("schema", 0L)), null));
		p.add(file);
		return p;
	}

	/** A file size the way a person says one. */
	private static String bytes(long n)
	{
		if (n >= 1024 * 1024)
		{
			return String.format("%.1f MB", n / (1024.0 * 1024.0));
		}
		return n >= 1024 ? fmt(n / 1024) + " KB" : fmt(n) + " B";
	}

	/** What the journal holds, counted. Found by typing, not by a tab. */
	void openInfo()
	{
		leaveSentPage();
		showInfo = true;
		detailItem = null;
		detailSource = null;
		detailSkill = null;
		detailTask = -1;
		clearSearch();
		rebuild();
	}

	/** Every tracker the record keeps, in one place. */
	void openAllTrackers()
	{
		leaveSentPage();
		allTrackers = true;
		detailItem = null;
		detailSource = null;
		detailSkill = null;
		detailTask = -1;
		clearSearch();
		rebuild();
	}

	/**
	 * A kind of thing, opened on the loot tracker.
	 *
	 * <p>The WHOLE ledger, not the slayer board. This used to land on PvM's
	 * Slayer tab with its Drops lens up, so a reader who typed "runes" wanting
	 * every rune they had ever been given was shown only the ones tasks paid,
	 * with nothing on screen saying so. The on-task reading is still one click
	 * away, and search offers it as its own row when there is one.
	 */
	void openLootKind(String kind, boolean onTask)
	{
		tab = Tab.LOOT;
		subByTab.put(Tab.LOOT, "Loot");
		applyCommon();
		// applyCommon clears where the reader was standing, the way opening a
		// tab does, so the kind and the lens are set AFTER it. applyCommon ends
		// with a rebuild of its own, so this costs two boards: the first
		// unnarrowed, thrown away by the second. Both run inside one EDT event,
		// so the reader only ever sees the narrowed one.
		dropsLeftBehind = false;
		dropsByKind = true;
		onTaskOnly = onTask;
		lootKind = kind;
		rebuild();
	}

	/** One skill under the glass, from its own cell in the grid. */
	void openSkill(String craft)
	{
		leaveSentPage();
		detailSkill = craft;
		detailItem = null;
		detailSource = null;
		detailTask = -1;
		clearSearch();
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

	/**
	 * What the roll says one source paid inside the window, as {count, worth}.
	 *
	 * <p>The sitting reads its own entry and every other period the dated days,
	 * which is the same split every other board makes.
	 */
	private long[] sourceInWindow(String name)
	{
		Window w = window();
		LocalStore.LootWindow win = sessionPeriod() ? plugin.sessionLootWindow()
			: plugin.lootBetween(w.start, w.end);
		for (String[] r : win.sources)
		{
			if (r[0].equalsIgnoreCase(name))
			{
				return new long[]{safeParse(r[1]), safeParse(r[2])};
			}
		}
		return new long[]{0, 0};
	}

	/** Open a source by a loose name (a task's plural, a kill-log row): exact,
	 *  then singular, then containment, else the raw name and an empty view. */
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
		List<LocalStore.SourceRow> all = sources();
		String low = name.toLowerCase(Locale.ROOT);
		LocalStore.SourceRow best = null;
		for (LocalStore.SourceRow r : all)
		{
			String rl = r.name.toLowerCase(Locale.ROOT);
			if ((rl.contains(low) || low.contains(rl))
				&& (best == null || r.value > best.value))
			{
				best = r;
			}
		}
		return best != null ? best.name : name;
	}

	/**
	 * The ledger's spelling of a name, where the ledger has that name: outright,
	 * or as the singular of it. Null otherwise.
	 *
	 * <p>Kept apart from {@link #resolveSource}, whose last resort is any source
	 * whose name merely contains this one or is contained by it. That is right
	 * for a click, which should land somewhere rather than nowhere, and wrong for
	 * an icon: "Gnome Restaurant" would be drawn wearing whatever the pickpocketed
	 * "Gnome" last dropped, and the page's own collection log would never be asked.
	 */
	private String resolveSourceNamed(String name)
	{
		if (ledgerNames == null)
		{
			// Once a build, not once a name. Every call asks the journal for its
			// whole source list, and a board of two hundred names asking two
			// hundred times copies it two hundred times before a single row is
			// drawn.
			Map<String, String> index = new java.util.HashMap<>();
			for (LocalStore.SourceRow r : sources())
			{
				index.putIfAbsent(r.name.toLowerCase(Locale.ROOT), r.name);
			}
			ledgerNames = index;
		}
		String low = name.toLowerCase(Locale.ROOT);
		String hit = ledgerNames.get(low);
		if (hit == null && low.endsWith("s"))
		{
			hit = ledgerNames.get(low.substring(0, low.length() - 1));
		}
		return hit;
	}

	// the ledger's own spelling of every source it holds, by lower case name
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
		// Both of these are pages the reader was SENT to rather than pages they
		// drilled into, so neither is on the detail stack and neither can be
		// left by popping it. Without a branch of its own the back row rebuilds
		// the page it is standing on and reads as a dead button.
		if (showInfo)
		{
			showInfo = false;
			rebuild();
			return;
		}
		// Unwound in the order rebuild() draws them, outermost drill first. It
		// ranks a drilled item or source above a skill, above the all-trackers
		// board, above a sheet page; a back row that clears them in any other
		// order clears something still covered by something else, so the page
		// redraws unchanged and the press reads as dead. Taking the sheet page
		// first did exactly that: Back off a clue tier's source redrew the source,
		// and the second press went to the sheet root rather than to the Clues
		// page the reader came from.
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

	private JPanel backRow()
	{
		return backRow(null);
	}

	/**
	 * The way back, and where a page can be carried off, the way to take it. The
	 * copy rides the right of the same row so it costs no height, and it hands
	 * back a PICTURE of the page: the point is to paste it somewhere that has
	 * never heard of Chronicle, and a picture is the one thing every chat
	 * window takes.
	 *
	 * <p>Swing dispatches a click to the deepest component that is listening, so
	 * the label's own listener takes the copy and the row's takes everything else.
	 */
	private JPanel backRow(java.util.function.BooleanSupplier copy)
	{
		JPanel r = row("< Back", copy == null ? "" : "copy", null);
		BorderLayout layout = (BorderLayout) r.getLayout();
		JLabel l = (JLabel) layout.getLayoutComponent(BorderLayout.CENTER);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(accent());
		r.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		r.addMouseListener(clicker(this::backDetail));
		JLabel take = copy == null ? null
			: (JLabel) layout.getLayoutComponent(BorderLayout.EAST);
		if (take != null)
		{
			take.setFont(FontManager.getRunescapeSmallFont());
			take.setForeground(ColorScheme.LIGHT_GRAY_COLOR.darker());
			take.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
			take.setToolTipText("Copy this page as a picture");
			take.addMouseListener(clicker(() ->
			{
				boolean ok = copy.getAsBoolean();
				take.setText(ok ? "copied" : "cannot copy");
				take.setForeground(ok ? accent() : ColorScheme.PROGRESS_ERROR_COLOR);
			}));
		}
		return r;
	}

	/** A picture on the clipboard, which is the one thing every chat window takes. */
	private static final class PageCopy implements java.awt.datatransfer.Transferable
	{
		private final java.awt.Image image;

		private PageCopy(java.awt.Image image)
		{
			this.image = image;
		}

		@Override
		public java.awt.datatransfer.DataFlavor[] getTransferDataFlavors()
		{
			return new java.awt.datatransfer.DataFlavor[]{
				java.awt.datatransfer.DataFlavor.imageFlavor};
		}

		@Override
		public boolean isDataFlavorSupported(java.awt.datatransfer.DataFlavor flavor)
		{
			return java.awt.datatransfer.DataFlavor.imageFlavor.equals(flavor);
		}

		@Override
		public Object getTransferData(java.awt.datatransfer.DataFlavor flavor)
			throws java.awt.datatransfer.UnsupportedFlavorException
		{
			if (java.awt.datatransfer.DataFlavor.imageFlavor.equals(flavor) && image != null)
			{
				return image;
			}
			throw new java.awt.datatransfer.UnsupportedFlavorException(flavor);
		}
	}

	/** False where there is no desktop clipboard to reach, rather than throwing. */
	private static boolean toClipboard(java.awt.Image image)
	{
		if (image == null)
		{
			return false;
		}
		try
		{
			java.awt.datatransfer.Transferable payload = pngPayload(image);
			java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
				.setContents(payload != null ? payload : new PageCopy(image), null);
			return true;
		}
		catch (Throwable ignored)   // noqa: headless, or a desktop that refuses
		{
			return false;
		}
	}

	/** image/png as bytes, which is the only honest way to say PNG on a Mac. */
	private static final java.awt.datatransfer.DataFlavor PNG_BYTES = pngFlavor();
	private static boolean pngNativeMapped;

	private static java.awt.datatransfer.DataFlavor pngFlavor()
	{
		try
		{
			return new java.awt.datatransfer.DataFlavor("image/png;class=java.io.InputStream");
		}
		catch (ClassNotFoundException ignored)   // noqa: cannot happen for InputStream
		{
			return null;
		}
	}

	/**
	 * The picture as real PNG bytes, on macOS only, or null to leave the ordinary
	 * path alone.
	 *
	 * AWT's imageFlavor is a trap here. It advertises public.png on the Mac
	 * pasteboard and then hands back TIFF under it: every image type on the board
	 * returns the same bytes, and those bytes begin MM\0* rather than the PNG
	 * magic. Anything that asks the pasteboard for a PNG, which is what a chat
	 * client does, receives a TIFF, names it image.png and uploads something
	 * nobody can open. It is also raw, so a page that is ninety kilobytes encoded
	 * went across as six megabytes.
	 *
	 * So the PNG is encoded here and offered as bytes mapped onto the pasteboard's
	 * own public.png. macOS derives a valid TIFF from it for anything that wants
	 * one, and Java reads it back as an image as before. Only the write direction
	 * is mapped: teaching the map to READ public.png as this flavour would change
	 * what every other plugin in the client sees on the clipboard.
	 */
	private static java.awt.datatransfer.Transferable pngPayload(java.awt.Image image)
	{
		if (PNG_BYTES == null || OSType.getOSType() != OSType.MacOS
			|| !(image instanceof java.awt.image.RenderedImage))
		{
			return null;
		}
		try
		{
			java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
			// A memory cache, or ImageIO writes a scratch file to encode a picture
			// that is already in memory.
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
			return new PngCopy(png);
		}
		catch (Throwable ignored)   // noqa: fall back to the image itself
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

	/** Encoded PNG bytes, handed out fresh each time the board is read. */
	private static final class PngCopy implements java.awt.datatransfer.Transferable
	{
		private final byte[] png;

		private PngCopy(byte[] png)
		{
			this.png = png;
		}

		@Override
		public java.awt.datatransfer.DataFlavor[] getTransferDataFlavors()
		{
			return new java.awt.datatransfer.DataFlavor[]{PNG_BYTES};
		}

		@Override
		public boolean isDataFlavorSupported(java.awt.datatransfer.DataFlavor flavor)
		{
			return PNG_BYTES.equals(flavor);
		}

		@Override
		public Object getTransferData(java.awt.datatransfer.DataFlavor flavor)
			throws java.awt.datatransfer.UnsupportedFlavorException
		{
			if (PNG_BYTES.equals(flavor))
			{
				return new java.io.ByteArrayInputStream(png);
			}
			throw new java.awt.datatransfer.UnsupportedFlavorException(flavor);
		}
	}

	/**
	 * The tallest a picture is drawn. A single column of six hundred rows is
	 * thirteen thousand pixels, which is the point of the tall copy; this is
	 * here so a page can never ask for one the size of a building.
	 */
	private static final int COPY_MAX_HEIGHT = 20000;

	/**
	 * A built page drawn to an image at its WHOLE height, not the window's. The
	 * page is a strip in a scroll pane; what wants sharing is all of it.
	 *
	 * <p>Laid out at the height it asks for and then CROPPED, never laid out
	 * inside a ceiling. A column of rows given less room than it wants does not
	 * lose the rows off the bottom: the layout squeezes from the top, and a six
	 * hundred row bag came out with its first hundred rows drawn at no height
	 * at all. The reader saw a picture that started in the middle of their loot
	 * and had no way to know.
	 */
	private static java.awt.Image pageImage(JPanel page, int width)
	{
		try
		{
			int w = width;
			page.setSize(w, COPY_MAX_HEIGHT);
			layOut(page);
			// the whole of it, whatever that is
			int full = Math.max(1, page.getPreferredSize().height);
			page.setSize(w, full);
			layOut(page);

			int h = Math.min(full, COPY_MAX_HEIGHT);
			int lost = h < full ? pastTheEdge(page, h) : 0;
			java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
				w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
			java.awt.Graphics2D g = img.createGraphics();
			g.setColor(ColorScheme.DARK_GRAY_COLOR);
			g.fillRect(0, 0, w, h);
			page.printAll(g);
			if (lost > 0)
			{
				sayWhatDidNotFit(g, w, h, lost);
			}
			g.dispose();
			return img;
		}
		catch (Throwable ignored)   // noqa: a picture is never worth losing the text
		{
			return null;
		}
	}

	/** How many of the page's own rows begin below the cut. */
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

	/**
	 * A picture that had to stop says so, in its own last line. A crop nobody is
	 * told about is the same lie as a squeeze: the reader believes they are
	 * holding the whole list.
	 */
	private static void sayWhatDidNotFit(java.awt.Graphics2D g, int w, int h, int lost)
	{
		int band = 20;
		g.setColor(ColorScheme.DARKER_GRAY_COLOR);
		g.fillRect(0, h - band, w, band);
		g.setColor(ColorScheme.LIGHT_GRAY_COLOR);
		g.setFont(FontManager.getRunescapeSmallFont());
		String said = fmt(lost) + " more, past the height a picture can hold";
		g.drawString(said, 6, h - 6);
	}

	/**
	 * What one column of a shared picture is drawn at. Wider than the panel on
	 * purpose: the sidebar is 225px and long item names truncate in it, which a
	 * reader can live with because they can hover. Somebody receiving a picture
	 * cannot.
	 */
	private static final int COPY_WIDTH = 340;

	/** Lay a tree out by hand: it was never added to a window, so nothing else will. */
	private static void layOut(java.awt.Component c)
	{
		c.doLayout();
		if (c instanceof java.awt.Container)
		{
			for (java.awt.Component k : ((java.awt.Container) c).getComponents())
			{
				layOut(k);
			}
		}
	}

	/**
	 * How deep a column of a shared picture runs before the next one starts. A
	 * board that is longer is not cut: it is set in columns, the way a newspaper
	 * sets a long list. Sixty rows is about a screen; six hundred in one column
	 * is a ribbon nobody reads.
	 */
	private static final int COPY_ROWS = 60;
	/** Columns before the columns themselves start running deeper than sixty. */
	private static final int COPY_COLUMNS = 6;
	/** The space between them, which the picture is widened by. */
	private static final int COPY_GAP = 10;
	/**
	 * The ceiling, which is six columns two hundred deep. A whole grind to 99
	 * leaves a few hundred kinds of loot behind it, so nothing real reaches this;
	 * it is here so that a page can never ask for a picture the size of a wall.
	 */
	private static final int COPY_MOST = COPY_COLUMNS * 200;

	/**
	 * A built page on the clipboard as a picture of itself, set in as many columns
	 * as its own length asks for. Every board that can be copied comes through
	 * here, so none of them can quietly grow a different answer to being long.
	 */
	private static boolean copyPicture(JPanel page)
	{
		return copyPicture(page, false);
	}

	/**
	 * The same, but tall rather than wide when asked.
	 *
	 * <p>Columns are what make a three hundred row board readable at a glance.
	 * A reader who has asked for every item is not glancing: they want the
	 * whole list in the order it ranks, and a single column is the only shape
	 * that keeps that order legible top to bottom.
	 */
	private static boolean copyPicture(JPanel page, boolean tall)
	{
		return toClipboard(copyImage(page, tall));
	}

	/** The picture itself, so that a preview can be drawn without a clipboard. */
	static java.awt.Image copyImage(JPanel page)
	{
		return copyImage(page, false);
	}

	static java.awt.Image copyImage(JPanel page, boolean tall)
	{
		int cols = tall ? 1 : copyColumns(page.getComponentCount());
		return pageImage(reflowed(page, cols), copyImageWidth(cols));
	}

	/** How many columns a list of this many rows is set in. */
	private static int copyColumns(int rows)
	{
		int held = Math.max(0, Math.min(rows, COPY_MOST));
		return Math.max(1, Math.min(COPY_COLUMNS, (held + COPY_ROWS - 1) / COPY_ROWS));
	}

	/** What that many columns is drawn at, gaps included. */
	private static int copyImageWidth(int cols)
	{
		return COPY_WIDTH * cols + COPY_GAP * (cols - 1);
	}

	/**
	 * A tall page set in columns. Its children are dealt out in order, left to
	 * right, so the summary card heads the first column and the list runs on from
	 * under it. Nothing is dropped; the page only changes shape.
	 */
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
		grid.setBackground(ColorScheme.DARK_GRAY_COLOR);
		grid.setAlignmentX(Component.LEFT_ALIGNMENT);
		for (int c = 0; c < cols; c++)
		{
			JPanel col = column();
			for (int i = c * per; i < Math.min(kids.length, (c + 1) * per); i++)
			{
				col.add(kids[i]);
			}
			// Pinned to the top: a short column would otherwise float in the middle
			// of a cell the tallest one sized.
			JPanel cell = new JPanel(new BorderLayout());
			cell.setBackground(ColorScheme.DARK_GRAY_COLOR);
			cell.add(col, BorderLayout.NORTH);
			grid.add(cell);
		}
		JPanel out = column();
		out.add(grid);
		return out;
	}



	// how many sources an item's page mounts; lifted while a copy is drawn
	private int itemSourceCap = 40;

	// Whether the page being built is bound for a picture rather than the panel.
	// A screenshot leaves the reader's own machine, so it carries less than the
	// board does.
	private boolean drawingCopy;


	/** The item's page on the clipboard, as a picture of itself. */
	private boolean copyItemPage(String name)
	{
		int was = itemSourceCap;
		try
		{
			itemSourceCap = COPY_MOST;
			drawingCopy = true;
			return copyPicture(stripChrome(buildItemDetail(name)));
		}
		catch (Throwable ignored)   // noqa: a picture is never worth an exception
		{
			return false;
		}
		finally
		{
			drawingCopy = false;
			itemSourceCap = was;
		}
	}

	/**
	 * The way back and the copy itself off a built page. They are navigation, and
	 * navigation has no business in a picture somebody is sharing; they are the
	 * first two things every drill adds, and its card follows.
	 */
	private static JPanel stripChrome(JPanel page)
	{
		if (page.getComponentCount() > 2)
		{
			page.remove(1);
			page.remove(0);
		}
		return page;
	}

	/**
	 * Put a source's page on the clipboard as a picture of itself. The picture is
	 * built from a FRESH page with the loot cap lifted, because what the reader is
	 * looking at stops at twenty five items behind a "show more" and what they are
	 * sharing should not.
	 */
	private boolean copySourcePage(String name)
	{
		Integer was = drillShown.get(name);
		try
		{
			drillShown.put(name, COPY_MOST);
			drawingCopy = true;
			return copyPicture(stripChrome(buildSourceDetail(name)));
		}
		catch (Throwable ignored)   // noqa: a picture is never worth an exception
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


	// The item under the glass: total obtained, worth, and every source of it.
	private JPanel buildItemDetail(String name)
	{
		JPanel p = column();
		long got = 0;
		long worth = 0;
		int found = 0;
		final List<Object[]> srcs = new ArrayList<>();
		for (LocalStore.SourceRow r : sources())
		{
			for (LocalStore.BagItem b : plugin.sourceItems(r.name))
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
		srcs.sort((a, b) -> Long.compare((long) b[1], (long) a[1]));
		final long qty = got;
		final long value = worth;
		final int itemId = found;
		// read before the row is built: the copy hands back every source, not the
		// forty the page mounts
		p.add(backRow(() -> copyItemPage(name)));
		p.add(vgap(4));
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
		long[] mine = taskItemsEver().get(properName(name, srcs));
		final boolean hasTask = mine != null;
		if (hasTask && onTaskOnly)
		{
			head.add(row("Obtained on task", "×" + fmt(mine[0]), accent()));
			head.add(row("All sources", "×" + fmt(qty), null));
			// The same row the other reading carries. What it is NOT is a price
			// frozen at the drop, for anything imported: a drop that lands now
			// is priced once and written to both trees identically, but the
			// history that came in from the server was valued in bulk on the
			// day it was imported, and the two halves of that import were
			// valued by different means. That is why 19 of my 287 on-task items
			// price higher than the same item does across the whole ledger, and
			// why a Mithril spear reads 7,000 each here against 231 there.
			JPanel priced = row("Worth", gp(mine[1]) + " gp", null);
			priced.setToolTipText("Priced as the drop landed, or in bulk on the day "
				+ "the history was imported");
			head.add(priced);
		}
		else
		{
			head.add(row("Obtained", "×" + fmt(qty), accent()));
			if (value > 0)
			{
				head.add(row("Worth", gp(value) + " gp", null));
			}
		}
		p.add(head);
		p.add(vgap(6));
		if (hasTask)
		{
			// The same control the Loot board draws for the same piece of state.
			// This page used to draw it as a pair of pills, which is the thing
			// toggle() exists to have replaced.
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
		if (srcs.isEmpty())
		{
			p.add(note("The journal hasn't seen this item drop yet."));
			return p;
		}
		if (hasTask && onTaskOnly)
		{
			return byTaskRows(p, properName(name, srcs));
		}
		p.add(group("From"));
		int mounted = 0;
		for (Object[] s : srcs)
		{
			if (mounted++ >= itemSourceCap)
			{
				p.add(ghostRow("+ " + (srcs.size() - itemSourceCap) + " more sources", ""));
				break;
			}
			JPanel r = row((String) s[0], "×" + fmt((long) s[1])
				+ ((long) s[2] > 0 ? " · " + gp((long) s[2]) + " gp" : ""), null);
			r.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
			final String src = (String) s[0];
			r.addMouseListener(clicker(() -> openSource(src)));
			p.add(r);
		}
		return p;
	}

	/**
	 * The ledger's own spelling of an item name.
	 *
	 * <p>The task bag is keyed by the name the game gave the drop, and a page
	 * can be opened from a search box where the reader typed it in any case at
	 * all. Matching on the bag's own key is what makes "fire rune" find it.
	 */
	private String properName(String typed, List<Object[]> srcs)
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

	/**
	 * One item split by the TASK that paid it.
	 *
	 * <p>Not by monster, which the record cannot say: a task carries one items
	 * map over every monster in it, and one of mine holds 45 blue dragons and
	 * 97 Vorkath. The rows do not open anything, because what they name is an
	 * assignment rather than a place the panel has a page for.
	 */
	private JPanel byTaskRows(JPanel p, String name)
	{
		long[] w = windowMs();
		List<Object[]> split = plugin.onTaskItemByTask(name, w[0], w[1]);
		if (split.isEmpty())
		{
			p.add(note("No task paid this inside " + periodInSentence() + "."));
			return p;
		}
		p.add(group("By task"));
		int mounted = 0;
		for (Object[] t : split)
		{
			if (mounted++ >= itemSourceCap)
			{
				p.add(ghostRow("+ " + (split.size() - itemSourceCap) + " more tasks", ""));
				break;
			}
			p.add(row("Task: " + t[0], "×" + fmt((long) t[1])
				+ ((long) t[2] > 0 ? " · " + gp((long) t[2]) + " gp" : ""), null));
		}
		return p;
	}

	/**
	 * The reconciled count, floored by what this journal has actually seen.
	 *
	 * <p>Bridged by chatKind rather than kindOf: killCounts is keyed as the game
	 * spells it, so the ledger's "Dust devil" has to reach "Dust devils" and its
	 * "Mad Angel" has to reach "The Mad Angel". Twenty four of the 182 sources
	 * need the bridge.
	 *
	 * <p>Floored, because a reconciled figure that came back empty would
	 * otherwise print zero over a page full of loot. It never does on a real
	 * journal, and a preview built from a hand-made stub is exactly where it
	 * would.
	 */
	private long standingKills(LocalStore.SourceRow sr)
	{
		long own = sr.kc > 0 ? sr.kc : sr.loots;
		// Indexed once per build rather than walked per row. It used to return on
		// the FIRST chat key that normalised to this kind, which on a map with no
		// order is whichever one came out first; the best of them is both a
		// better answer and the same answer every time.
		Long said = chatKcByKind().get(LocalStore.chatKind(sr.name));
		return said == null ? own : Math.max(own, said);
	}

	// Per build: the best chat-reported kill count per kind.
	private Map<String, Long> chatKcByKind;

	private Map<String, Long> chatKcByKind()
	{
		if (chatKcByKind == null)
		{
			Map<String, Long> out = new LinkedHashMap<>();
			for (Map.Entry<String, Long> e : plugin.killCounts().entrySet())
			{
				out.merge(LocalStore.chatKind(e.getKey()), e.getValue(), Math::max);
			}
			chatKcByKind = out;
		}
		return chatKcByKind;
	}

	/**
	 * What says so: every count of this fight the record actually holds, under
	 * the name of whoever said it.
	 *
	 * <p>Three kinds of statement and one observation, and they disagree because
	 * they are counting from different places rather than because one of them is
	 * broken. None is ever written as a share of another: Choke devil has
	 * thirteen drops on task against the eleven the game ever stamped a count
	 * on, because the game stamps none on a superior, and Tombs of Amascut reads
	 * two on its page against three in the ledger.
	 *
	 * <p>Drawn shut, and not at all where the record holds only one figure or
	 * where the headline matches none of them. A headline nothing accounts for
	 * is the one outcome worse than a headline that needed explaining.
	 */
	private void addKillSources(JPanel head, LocalStore.SourceRow sr, long headline)
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
		for (Map.Entry<String, Long> e : rows.entrySet())
		{
			head.add(row(e.getKey(), fmt(e.getValue()), null));
		}
	}

	/** One map's figure for this source, where it has one. */
	private static void putKind(Map<String, Long> rows, String label,
		Map<String, Long> from, String want)
	{
		for (Map.Entry<String, Long> e : from.entrySet())
		{
			if (LocalStore.chatKind(e.getKey()).equals(want) && e.getValue() > 0)
			{
				rows.put(label, e.getValue());
				return;
			}
		}
	}

	// Lifetime, unlike taskKills(), because every figure it stands beside is a
	// lifetime running total and a window would put a slice among them.
	private Map<String, Long> taskKillsEverCache;

	private Map<String, Long> taskKillsEver()
	{
		if (taskKillsEverCache == null)
		{
			taskKillsEverCache = plugin.onTaskKills(Long.MIN_VALUE / 2, Long.MAX_VALUE / 2);
		}
		return taskKillsEverCache;
	}

	/**
	 * The slayer assignments this monster turned up in, newest first.
	 *
	 * <p>Labelled for the TASK, because that is what the figure beside it
	 * belongs to. The kills are this monster's own and exact; a task's worth is
	 * the whole assignment's and is not printed here at all, since one of mine
	 * holds 45 blue dragons, 3 babies and 97 Vorkath, and no share of that
	 * belongs to any one of them.
	 */
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
			// No tooltip. Nothing here opens, and setToolTipText registers the
			// row with the ToolTipManager, which adds a mouse listener: the row
			// then reads as a control to anything asking whether it can be
			// clicked, including the recording harness.
			p.add(row("Task: " + a.task, fmt(a.killsHere), null));
		}
		p.add(vgap(6));
	}

	// The source under the glass: kills tracked, the take, and its whole bag.
	private JPanel buildSourceDetail(String name)
	{
		JPanel p = column();
		LocalStore.SourceRow found = null;
		for (LocalStore.SourceRow r : sources())
		{
			if (r.name.equalsIgnoreCase(name))
			{
				found = r;
				break;
			}
		}
		final LocalStore.SourceRow sr = found;
		// What THIS period's roll says this source paid, where the period is not
		// the whole record. The page below reads these in place of the ledger's
		// lifetime figures, so clicking a source on a board narrowed to a week
		// opens that week rather than silently opening everything.
		long[] inWindow = wholeRecord() ? null
			: sourceInWindow(sr != null ? sr.name : name);
		// Read before the row is built, because the copy hands back the WHOLE
		// page: every loot line, not the twenty five the page mounts. The sitting
		// keeps its own items per source and can answer exactly; a longer period
		// cannot, and the page says so rather than quietly showing a lifetime.
		final List<LocalStore.BagItem> bag = sessionPeriod()
			? plugin.sessionSourceItems(sr != null ? sr.name : name)
			: plugin.sourceItems(sr != null ? sr.name : name);
		bag.sort(Comparator.comparingLong((LocalStore.BagItem b) -> b.value).reversed());
		p.add(backRow(() -> copySourcePage(name)));
		p.add(vgap(4));
		JPanel head = card(name);
		if (sr != null)
		{
			// Not everything that drops loot is killed. The Rift is searched, a
			// casket is opened, a cart is emptied: calling any of that "kills
			// tracked" is the page telling the reader something untrue about what
			// they did.
			boolean killed = isKillSource(sr.name);
			// The same figure the board the reader just clicked was showing.
			// This page used to work one out for itself off the ledger alone and
			// disagree with it on 42 sources: Nechryael's card said 686 kc and
			// its page said 686, while the game's own Kill Log says 1,236.
			//
			// "Tracked" has to go with it. The reconciled count carries kills
			// from before this plugin was ever installed, so the one word the
			// old label leaned on is the one thing it is not.
			long shown = inWindow != null ? inWindow[0]
				: killed ? standingKills(sr) : sr.loots;
			head.add(row(killed ? "Kills" : "Times looted", fmt(shown), accent()));
			// Divided by DROPS, not by kills. sr.value accrues once per loot
			// event beside sr.loots, so loots is the only divisor its numerator
			// matches; over sr.kc it read 38% high on Brutal black dragon, whose
			// kc is an import floor of 50 under 69 logged drops.
			long worth = inWindow != null ? inWindow[1] : sr.value;
			long over = inWindow != null ? inWindow[0] : sr.loots;
			head.add(row("Worth", gp(worth) + " gp"
				+ (over > 0 ? " · " + gp(worth / Math.max(1, over))
					+ (killed ? " gp/drop" : " gp each") : ""), null));
			// The reconciled kill count and the log's own lines are lifetime
			// standings, and stay off a narrowed page rather than sitting under
			// figures that are not.
			if (inWindow == null)
			{
				addKillSources(head, sr, killed ? shown : -1);
			}
			// what the log's own page counts for it, in the log's own words
			for (Map.Entry<String, Long> pbLine : bestTimes(sr.name))
			{
				head.add(row(pbLine.getKey(), clock(pbLine.getValue()), null));
			}
			for (Map.Entry<String, Long> ln : logLines(sr.name))
			{
				head.add(row(ln.getKey(), fmt(ln.getValue()), null));
			}
			if (sr.pb != null)
			{
				head.add(row("Personal best", pb(sr.pb), null));
			}
			// Not on a picture. It is the reader's own bookkeeping rather than
			// anything about the fight, and on an account whose ledger carries
			// imported rows it can name a day before the account existed, which
			// is a date nobody should be handing out with a screenshot.
			if (sr.firstMs > 0 && !drawingCopy)
			{
				head.add(row("Tracked since",
					TASK_DAY.format(Instant.ofEpochMilli(sr.firstMs)), null));
			}
			// The chase, when the dryness ledger knows one for this source. Not
			// on a picture: how dry somebody is running is the most personal
			// line on the page, and a screenshot of a drop is not the place to
			// volunteer it.
			if (grindsCache == null && !grindsFetching && !drawingCopy)
			{
				grindsFetching = true;
				final String src = sr.name;
				plugin.fetchGrinds(rows2 -> SwingUtilities.invokeLater(() ->
				{
					grindsFetching = false;
					if (rows2 == null)
					{
						return;   // store not mounted; retry on the next rebuild
					}
					grindsCache = rows2;
					if (src.equals(detailSource))
					{
						rebuild();
					}
				}));
			}
			if (grindsCache != null && !drawingCopy)
			{
				for (ChronicleApiClient.GrindRow g : grindsCache)
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
		p.add(head);
		p.add(vgap(6));
		if (sr != null)
		{
			addAssignments(p, sr.name);
		}
		if (!bag.isEmpty())
		{
			JPanel grid = new JPanel(new GridLayout(0, 5, 3, 3));
			grid.setBackground(ColorScheme.DARK_GRAY_COLOR);
			int sprites = 0;
			for (LocalStore.BagItem b : bag)
			{
				if (b.itemId <= 0)
				{
					continue;
				}
				if (sprites++ >= 10)
				{
					break;
				}
				JLabel slot = new JLabel();
				slot.setPreferredSize(new Dimension(36, 32));
				slot.setHorizontalAlignment(JLabel.CENTER);
				slot.setToolTipText(b.name + (b.qty > 1 ? " ×" + fmt(b.qty) : ""));
				// Opens the item, as the identical grid on Now does. It was the
				// one sprite grid in the panel a reader could not click: same
				// five setup lines, same tooltip naming the item, and then
				// nothing behind it.
				slot.setCursor(java.awt.Cursor.getPredefinedCursor(
					java.awt.Cursor.HAND_CURSOR));
				final String spriteItem = b.name;
				slot.addMouseListener(clicker(() -> openItem(spriteItem)));
				AsyncBufferedImage img = plugin.items().getImage(b.itemId,
					(int) Math.min(Integer.MAX_VALUE, b.qty), b.qty > 1);
				img.addTo(slot);
				grid.add(slot);
			}
			if (sprites > 0)
			{
				p.add(grid);
				p.add(vgap(5));
			}
			p.add(group("Loot"));
			// Said once, under the heading it applies to. The dated roll keeps a
			// day's items in one heap rather than per source, so a week can say
			// what this source paid and not what it paid it in; only the sitting,
			// which keeps its own, can break it down.
			if (inWindow != null && !sessionPeriod())
			{
				p.add(note("The figures above are " + periodInSentence() + "'s. "
					+ "What follows is everything this source has ever paid: the "
					+ "dated roll keeps a day's items together rather than under "
					+ "the thing that dropped them."));
			}
			int cap = drillShown.getOrDefault(name, 25);
			int mounted = 0;
			for (LocalStore.BagItem b : bag)
			{
				if (mounted++ >= cap)
				{
					break;
				}
				JPanel r = row(b.name + (b.qty > 1 ? " ×" + fmt(b.qty) : ""),
					b.value > 0 ? gp(b.value) + " gp" : "", null);
				r.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
				final String itm = b.name;
				r.addMouseListener(clicker(() -> openItem(itm)));
				p.add(r);
			}
			if (bag.size() > cap)
			{
				final String key = name;
				final int every = bag.size();
				p.add(vgap(3));
				p.add(moreRow(every - cap, () ->
				{
					drillShown.put(key, every);
					rebuildInPlace();
				}));
			}
			return p;
		}
		p.add(note(sr == null
			? "The journal has no drops from this source yet."
			: "Items fill in as you play. The journal prices each drop the "
			+ "moment it lands."));
		return p;
	}

	/**
	 * What the log gained inside the window, read off the journal rather than off
	 * the log. Every slot is dated as it lands, so "what did I log this month" is
	 * a question the record can answer even though the log itself holds no dates
	 * at all.
	 */
	private JPanel logInWindow(JPanel p)
	{
		Window w = window();
		List<JsonObject> got = new ArrayList<>();
		for (JsonObject e : plugin.feedNewest(4000))
		{
			if ("COLLECTION".equals(typeOf(e)) && insideWindow(safeLong(e.get("ts"))))
			{
				got.add(e);
			}
		}
		if (got.isEmpty())
		{
			p.add(note("Nothing new was logged inside " + periodInSentence() + "."));
			return p;
		}
		JPanel head = card("Collection log");
		head.add(row("Slots logged", fmt(got.size()), accent()));
		p.add(head);
		p.add(vgap(6));
		for (JsonObject e : got)
		{
			JsonObject d = e.has("data") && e.get("data").isJsonObject()
				? e.getAsJsonObject("data") : new JsonObject();
			final String name = str(d, "itemName", "new item");
			JPanel line = row(name, stamp(e), null);
			line.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
			line.addMouseListener(clicker(() -> openItem(name)));
			p.add(line);
		}
		return p;
	}

	private JPanel buildLog()
	{
		JPanel p = column();
		// The log records what has been obtained and not when, so the SHEET
		// cannot be narrowed. The journal dates every slot as it lands though, so
		// a window is still answerable -- from the other end.
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
			// The slots are known and the total is not, which is a real state: the
			// count rises on a chat event, the total only on a login sync.
			head.add(row("Slots obtained", fmt(fin), accent()));
			head.add(row("Open your log in game once for the total", "", null));
		}
		else
		{
			head.add(row("Open your log in game once to fill this in", "", null));
		}
		p.add(head);
		p.add(vgap(6));

		Map<String, Map<String, List<String>>> tax = taxonomy(plugin.gson());
		// Three per row; five clips the names.
		JPanel pills = new JPanel(new GridLayout(0, 3, 3, 3));
		pills.setBackground(ColorScheme.DARK_GRAY_COLOR);
		for (String tab : tax.keySet())
		{
			JLabel pill = new JLabel(tab, JLabel.CENTER);
			pill.setOpaque(true);
			pill.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
			pill.setFont(FontManager.getRunescapeSmallFont());
			pill.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			pill.setForeground(tab.equals(clogTab) ? accent() : ColorScheme.LIGHT_GRAY_COLOR.darker());
			// The game's own count for this tab, which the capture has always
			// taken and shipped and the panel has never shown. It goes on the
			// hover rather than on the pill: five pills across a 242 pixel column
			// have no room for a fraction, and "16 / 611" beside "Clues" would
			// cost the word itself.
			String tabTip = tabStanding(clogNow(), tab);
			if (tabTip != null)
			{
				pill.setToolTipText(tabTip);
			}
			pill.setCursor(java.awt.Cursor.getPredefinedCursor(
				java.awt.Cursor.HAND_CURSOR));
			pill.addMouseListener(clicker(() ->
			{
				clogTab = tab;
				clogPageSel = null;
				rebuild();
			}));
			pills.add(pill);
		}
		p.add(pills);
		p.add(vgap(6));

		JsonObject cl = clogNow();
		Obtained ob = obtained(cl);
		// The page's own kill line, not the unlabelled figure beside it. That
		// figure is whatever the page happened to put first, and on the pages that
		// carry more than one line it is not a kill count at all: Wintertodt's is
		// rewards claimed, 1,078 against 447 killed, and Tempoross's is a personal
		// best, 46, which is a TIME being printed as a number of kills. LocalStore
		// already knows the rule, and a page whose only lines are rewards or a best
		// time honestly has no kill count to show.
		Map<String, Long> kcs = pageCounts(cl);

		Map<String, List<String>> pages = tax.getOrDefault(clogTab, new LinkedHashMap<>());
		for (Map.Entry<String, List<String>> pg : pages.entrySet())
		{
			String page = pg.getKey();
			List<String> slots = pg.getValue();
			boolean[] lit = lightSlots(slots, ob.byPage.get(page.toLowerCase(Locale.ROOT)), ob.all,
				sharedSlotNames(plugin.gson()));
			int got = 0;
			for (boolean b : lit)
			{
				got += b ? 1 : 0;
			}
			Long kc = kcs.get(page.toLowerCase(Locale.ROOT));
			boolean open = page.equals(clogPageSel);
			boolean complete = got == slots.size() && !slots.isEmpty();
			JPanel rowP = row(page, got + "/" + slots.size()
				+ (kc != null && kc > 0 ? " · " + fmt(kc) + " kc" : ""),
				complete ? ACCENT_SESSION : null, complete);
			// Everything the page's header said, in the game's own words, since
			// the row itself can only carry the one figure that is a kill count.
			String lines = pageHeaderTip(cl, page);
			if (lines != null)
			{
				rowP.setToolTipText(lines);
			}
			rowP.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
			rowP.addMouseListener(clicker(() ->
			{
				clogPageSel = open ? null : page;
				rebuild();
			}));
			p.add(rowP);
			if (open)
			{
				JPanel drill = cardPlain();
				// Pets pages have a dated line to put under a slot. Source and
				// count only appear on rows an older record supplied; the plugin's
				// own pet emit carries the name alone.
				boolean petPage = page.toLowerCase(Locale.ROOT).contains("pet");
				Map<String, LocalStore.PetRow> known = petPage
					? petsByName() : java.util.Collections.emptyMap();
				// And a pet still out there gets the same line read the other way:
				// what has been killed for it, and how much of the field holds it
				// by that point. Only where the rate book prices the pet and the
				// journal has a kill count; the rest of the page is untouched.
				Map<String, GrindBook.PetChase> chases = petPage
					? plugin.petChases(slots) : java.util.Collections.emptyMap();
				// What each slot has to say for itself, settled before a row is
				// mounted: seventy of these lines at once is a wall, so the page is
				// a list of names and each one gives its line up only when asked.
				// The note above them has to know whether any of them has one.
				List<List<JPanel>> detail = new ArrayList<>();
				boolean anyDetail = false;
				for (int i = 0; i < slots.size(); i++)
				{
					String key = slots.get(i).toLowerCase(Locale.ROOT);
					List<JPanel> d = petDetail(lit[i], known.get(key), chases.get(key));
					detail.add(d);
					anyDetail |= !d.isEmpty();
				}
				// One note doing two jobs: that the rows open, and the caveat on
				// what opening one shows. A skilling pet's odds move with the level,
				// and the journal knows the level held now, not the one each log was
				// cut at, so those figures run a little dry.
				if (anyDetail)
				{
					drill.add(note("Click pet to see odds. Skilling odds are based "
						+ "on current level."));
					drill.add(vgap(3));
				}
				for (int i = 0; i < slots.size(); i++)
				{
					String slot = slots.get(i);
					// A pet the journal recorded lights even if its page was never
					// opened. Green owned, red missing, as in game.
					JPanel r = row(slot, "",
						lit[i] || known.get(slot.toLowerCase(Locale.ROOT)) != null
							? ACCENT_SESSION : ACCENT_RED, true);
					drill.add(r);
					List<JPanel> d = detail.get(i);
					if (d.isEmpty())
					{
						// Nothing under it to uncover, so it is not a fold: no hand
						// cursor promising one, and no click that does nothing.
						continue;
					}
					String foldKey = "pets:" + page + ":" + slot.toLowerCase(Locale.ROOT);
					r.setCursor(java.awt.Cursor.getPredefinedCursor(
						java.awt.Cursor.HAND_CURSOR));
					r.addMouseListener(clicker(() -> toggleFold(foldKey)));
					if (foldOpen(foldKey))
					{
						for (JPanel line : d)
						{
							drill.add(line);
						}
					}
				}
				p.add(drill);
				p.add(vgap(3));
			}
		}

		// Pages the GAME has and this release's list does not. Jagex adds a
		// collection log page and a plugin update takes as long as it takes, so
		// without this the reader's own capture of that page is on their disk and
		// nowhere on screen for a month. It cannot be filed under a tab, because
		// the scrape records a page's title and not which tab it sat under, so it
		// is shown once, on Other, where the game itself puts what does not fit.
		if ("Other".equals(clogTab))
		{
			java.util.Set<String> known = new java.util.HashSet<>();
			for (Map<String, List<String>> tabPages : tax.values())
			{
				for (String pageName : tabPages.keySet())
				{
					known.add(pageName.toLowerCase(Locale.ROOT));
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
			java.util.Collections.sort(strangers);
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
							+ (kc != null && kc > 0 ? " \u00b7 " + fmt(kc) + " kc" : ""),
						null));
				}
				p.add(ghostRow("Chronicle has no slot list for "
					+ (strangers.size() == 1 ? "this page" : "these pages")
					+ " yet, so only what you hold is known.", ""));
			}
		}
		return p;
	}

	// Where the kills went, in the same breath an owned pet uses for its own
	// provenance. Heaviest source first, which is what lets fitChase() give up the
	// tail of the line and keep the source that matters.
	private static String chaseSources(GrindBook.PetChase chase)
	{
		// A skilling pet's sources are the twenty tree types behind one grind, not
		// twenty grinds. Naming them all would spend the whole line on a list nobody
		// reads; the activity and its total say what was done, and which tree carried
		// it is a detail, which is where details go.
		if (chase.activity != null)
		{
			return chase.activity + ", " + fmt(chase.kc) + " " + chase.unit;
		}
		StringBuilder sb = new StringBuilder();
		for (GrindBook.PetSource s : chase.sources)
		{
			if (sb.length() > 0)
			{
				sb.append(" · ");
			}
			sb.append(s.boss).append(", kc ").append(fmt(s.kc));
		}
		return sb.toString();
	}

	// ── fitting the chase line ──────────────────────────────────────────
	//
	// A JLabel clips its own end, so a long source name walks the kill count off
	// the row and leaves "kc" standing over nothing. A name read half way still
	// names the boss; a figure read half way is worth less than no figure at all.
	// So the letters give way here and every digit stays. The hover keeps the
	// sentence whole either way.

	// Three dots as one glyph. The game font draws it as three single pixels, and
	// it is 7px wide where "..." is 9, worth two more letters of a name. Checked
	// against the .notdef box the font falls back to for a glyph it lacks, which
	// is why an em dash appears nowhere in these strings.
	private static final String ELLIPSIS = "…";
	// Letters kept in front of the ellipsis before a name stops being a name. Only
	// the last source standing is cut below this, and then only to save its count.
	private static final int NAME_FLOOR = 3;
	// Rows are fitted as they are built, before any of them has a graphics context
	// of its own, so the measure comes off a label that is never shown.
	private static final JLabel MEASURE = new JLabel();

	static FontMetrics rowMetrics()
	{
		return MEASURE.getFontMetrics(FontManager.getRunescapeFont());
	}

	// What a board gives up to the bar. The bar itself is laid over the board
	// rather than beside it (overlayBar), so this is not the look and feel's
	// width any more: it is the gutter the content is held out of so the thumb
	// never crosses a figure.
	private static int scrollbarWidth()
	{
		return OVERLAY_BAR_W;
	}

	/** The gutter the floating thumb lives in, and the width it is drawn at. */
	private static final int OVERLAY_BAR_W = 5;

	// The bar shows only while the reader is moving, in a gutter half the width
	// the look and feel wanted. A sidebar is 225px wide, and a track painted down
	// the whole height was the one piece of chrome always on screen that never
	// said anything.
	private static void overlayBar(JScrollPane scroll)
	{
		javax.swing.JScrollBar bar = scroll.getVerticalScrollBar();
		OverlayScrollBarUI ui = new OverlayScrollBarUI();
		bar.setUI(ui);
		// The thumb answers the READER, not the record. A wheel turn over the
		// board and a drag of the thumb are the two ways a person moves this;
		// everything else adjusting it is the board being redrawn underneath
		// them, and a thumb that lights up for that flashes on every push.
		scroll.addMouseWheelListener(e -> ui.wake());
		bar.setOpaque(false);
		// Half what the look and feel asked for, and the figure scrollbarWidth()
		// answers with, so the two cannot drift apart.
		bar.setPreferredSize(new Dimension(OVERLAY_BAR_W, 0));
		// The gutter IS the reservation: the layout holds the content out of these
		// five pixels while the bar is up and hands them back when it is not, so a
		// board that fits keeps its whole width. Nothing else is needed here, and
		// an earlier viewport inset took the gutter off boards that never scroll.
	}

	/**
	 * A scrollbar with no track and no buttons, drawn over the board rather than
	 * beside it, and faded out once the reader stops moving. The thumb is the only
	 * thing it ever paints.
	 */
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
			// Only while the thumb is under the mouse. Every other adjustment is
			// the content changing height beneath a reader who did not ask for
			// anything, which is most ticks of a grind.
			scrollbar.addAdjustmentListener(e ->
			{
				if (e.getValueIsAdjusting())
				{
					wake();
				}
			});
		}

		@Override
		protected void paintTrack(java.awt.Graphics g, javax.swing.JComponent c,
			java.awt.Rectangle bounds)
		{
			// The board is the track.
		}

		@Override
		protected void paintThumb(java.awt.Graphics g, javax.swing.JComponent c,
			java.awt.Rectangle t)
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

		/** Show the thumb, and start the clock that takes it away again. */
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

		// The timer stops itself once the thumb is gone, and again the moment its
		// bar leaves the screen, so a scroll pane a rebuild threw away does not
		// leave one running behind it.
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
		public void uninstallUI(javax.swing.JComponent c)
		{
			if (fader != null)
			{
				fader.stop();
			}
			super.uninstallUI(c);
		}
	}

	// The pixels a chase row's name has, worked out from the layout rather than
	// read off a picture. The sidebar is a fixed width and every inset between it
	// and the label is one of ours:
	//
	//     225   PluginPanel.PANEL_WIDTH       the sidebar's content
	//   +  17   PluginPanel.SCROLLBAR_WIDTH   and its allowance for a bar; this
	//                                         panel is unwrapped (super(false)),
	//                                         so it is the whole 242 itself
	//   -  16   this panel's border, PANEL_INSET a side (the constructor)
	//   -   5   the gutter the floating bar is held in, OVERLAY_BAR_W above
	//   -  16   the drill card's border, CARD_INSET a side (cardPlain)
	//   -   4   the row's border, ROW_INSET a side (row)
	//   -   8   ROW_GAP, between the name and the share
	//   = 193   less the share, which BorderLayout draws at its preferred width.
	static int chaseRoom(String share, FontMetrics fm)
	{
		return PluginPanel.PANEL_WIDTH + PluginPanel.SCROLLBAR_WIDTH
			- 2 * PANEL_INSET - scrollbarWidth() - 2 * CARD_INSET
			- 2 * ROW_INSET - ROW_GAP - fm.stringWidth(share);
	}

	// A line under construction: the pieces in order, and which of them are names
	// and may be shortened. Everything else is a figure and is not negotiable.
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

	// A name cut to its first letters. Any trailing space goes with them:
	// "Commander …" is a ragged thing to print.
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

	/**
	 * Shortens the named pieces of a line, in the order given, until the whole of it
	 * measures no wider than {@code avail}. Pieces outside {@code order} are left
	 * exactly as they are, which is where the figures live. Returns null when even
	 * every one of those names cut to {@code floor} letters is still too wide: the
	 * caller's cue to give up a whole source rather than cut any further.
	 */
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
			// As short as this one goes. Keep whichever form is the narrower (a
			// stub of a short name can cost more than the name) and move along to
			// the next name in the order.
			String shortest = stub(name, floor);
			work.pieces.set(idx,
				fm.stringWidth(shortest) < fm.stringWidth(name) ? shortest : name);
		}
		return null;
	}

	// The first {@code kept} sources, heaviest first, and a count of the ones left
	// off the end so the reader knows they are there. The hover names them.
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

	// How the sources left off are marked. The first form sets them off with the
	// same separator the kept ones use, so "kc 1 · +1" cannot be read as a count of
	// two; the second is for a row too tight to afford that, where a bare mark
	// still beats giving up another name.
	private static final String[] DROP_MARKS = {" · +", " +"};

	// The names of a line, last first: a line gives up its tail before its head,
	// because the sources are sorted with the one that carried the grind in front.
	private static List<Integer> tailFirst(Line l, int from)
	{
		List<Integer> order = new ArrayList<>(l.names.subList(from, l.names.size()));
		java.util.Collections.reverse(order);
		return order;
	}

	// The chase line as the row will print it: the words chaseSources() gives the
	// hover, cut to what the row can hold.
	static String fitChase(GrindBook.PetChase chase, String share)
	{
		FontMetrics fm = rowMetrics();
		int avail = chaseRoom(share, fm);
		if (chase.activity != null)
		{
			// A skilling chase is one activity and the noun its attempts are
			// counted in, with the count between them. The noun gives way first,
			// then the activity; the count gives way to nothing.
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
				// The trailing names give way first, and the leading one is not cut
				// into at all while there is still a whole source to give up.
				String s = fitLine(l, tailFirst(l, 1), NAME_FLOOR, fm, avail);
				if (s != null)
				{
					return s;
				}
			}
		}
		// One source left and its name is still too long for the row. Now it has to
		// give: a boss half-named is still that boss, and "kc" over nothing is not
		// a kill count.
		Line l = sourceLine(src, 1, DROP_MARKS[DROP_MARKS.length - 1]);
		String s = fitLine(l, l.names, 1, fm, avail);
		return s != null ? s : l.whole();
	}

	// The whole sentence, kept for the hover: the row itself has about 135px (see
	// chaseRoom()), and two sources with long names run well past it.
	private static String chaseTip(GrindBook.PetChase chase)
	{
		double pct = chase.percentileDry;
		String share = pct < 1 ? "Under 1%" : pct > 99 ? "Over 99%" : Math.round(pct) + "%";
		StringBuilder sb = new StringBuilder(share + " of players have " + chase.pet
			+ " by this point. " + chaseSources(chase));
		if (chase.activity != null && chase.sources.size() > 1)
		{
			// the line spent itself on the activity, so the hover names the one
			// source that carried it. Only where there were others to carry it
			// instead: "Mad Angel, 124 kills, mostly mad angel" says nothing twice.
			sb.append(", mostly ").append(chase.sources.get(0).boss.toLowerCase(Locale.ROOT));
		}
		if (chase.level > 0)
		{
			sb.append(". Priced at ").append(chase.level)
				.append(", the level you hold now, not the level each one was rolled at");
		}
		return sb.append(".").toString();
	}

	// Everything a pets page has to say under one slot, built whether or not the
	// slot is open: an owned pet's provenance and date, or how far the chase for an
	// unearned one has run. Empty where the journal has neither, and an empty list
	// is what makes a row inert rather than a fold with nothing behind it.
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
					// A skilling pet has no kill count; what was recorded is the
					// xp behind it.
					line.append(isSkill(pet.source)
						? ", " + fmt(pet.kc) + " xp"
						: ", kc " + fmt(pet.kc));
				}
			}
			// No provenance, no line: the plugin's own pet emit carries the name
			// alone, and an empty label with a date adrift at the right margin
			// reads as a fault.
			if (line.length() > 0)
			{
				out.add(ghostRow(line.toString(), pet.ts > 0
					? TASK_DAY.format(Instant.ofEpochMilli(pet.ts)) : ""));
			}
		}
		else if (!lit && chase != null)
		{
			// The share is drawn at its own width, so it is what the name has left
			// to fit inside.
			String share = holdShare(chase);
			JPanel r = ghostRow(fitChase(chase, share), share,
				chase.percentileDry >= 90 ? ACCENT_RED : null);
			out.add(tipped(r, chaseTip(chase)));
		}
		return out;
	}

	// One tooltip over a row and everything in it: a panel's own tip never fires,
	// the label under the pointer swallows it.
	private static JPanel tipped(JPanel r, String tip)
	{
		r.setToolTipText(tip);
		for (Component c : r.getComponents())
		{
			if (c instanceof javax.swing.JComponent)
			{
				((javax.swing.JComponent) c).setToolTipText(tip);
			}
		}
		return r;
	}

	// The share of players who hold the pet by this point. Clipped at both ends
	// rather than rounded to them: "0% have" under a kill count that exists reads
	// as a fault, and nothing short of certainty should print as certainty. Terse
	// because the row is barely 190px and this is measured out of the source list's
	// share of it, not added beside it.
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

	// True when a pet's source names a skill, not a monster.
	private static boolean isSkill(String source)
	{
		for (net.runelite.api.Skill sk : net.runelite.api.Skill.values())
		{
			if (sk.name().equalsIgnoreCase(source))
			{
				return true;
			}
		}
		return false;
	}

	// The journal's pet record, keyed by lower-cased name for slot lookup.
	private Map<String, LocalStore.PetRow> petsByName()
	{
		Map<String, LocalStore.PetRow> out = new LinkedHashMap<>();
		for (LocalStore.PetRow r : plugin.pets())
		{
			out.putIfAbsent(r.name.toLowerCase(Locale.ROOT), r);
		}
		return out;
	}

	// The two halves of the journal's stored log: the whole-log obtained set, and
	// each page's own capture. Names lower-cased for slot lookup.
	private static final class Obtained
	{
		final Map<String, Long> all = new LinkedHashMap<>();
		final Map<String, Map<String, Long>> byPage = new LinkedHashMap<>();
	}

	/**
	 * A captured page name put back into title case for display. by_cat is keyed
	 * lowercase so the two sources can be matched; the game wrote it properly.
	 */
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

	/**
	 * The collection log's standing: how many slots are held, and how many there
	 * are, or null when the game has not said how many there are.
	 *
	 * <p>One rule, because this figure was computed four different ways on four
	 * surfaces. The total is not something Chronicle can work out: the taxonomy
	 * lists page entries and one item can sit on several pages, so counting it
	 * gives a different number from the log's own. It comes from a pair of varps
	 * the client syncs at login, which costs nothing and needs no interface
	 * opened - and until it arrives it is zero, which is a not-yet and not an
	 * answer.
	 *
	 * <p>The old readings each dealt with that zero on their own account. One
	 * floored the total at a literal 1712, which stood in for a missing total and
	 * also overrode any real total below it. Another printed the obtained count as
	 * the total, so an account waiting on its first sync read "412 / 412": a
	 * collection log, finished. Neither is now possible, because neither surface
	 * gets to invent a denominator.
	 */
	private int[] clogStanding()
	{
		int avail = plugin.clogAvailable();
		return avail > 0 ? new int[]{plugin.clogFinished(), avail} : null;
	}

	private static Obtained obtained(JsonObject cl)
	{
		Obtained o = new Obtained();
		if (cl.has("clog_items") && cl.get("clog_items").isJsonObject())
		{
			for (Map.Entry<String, com.google.gson.JsonElement> e
				: cl.getAsJsonObject("clog_items").entrySet())
			{
				o.all.merge(e.getKey().toLowerCase(Locale.ROOT), safeLong(e.getValue()), Math::max);
			}
		}
		if (cl.has("by_cat") && cl.get("by_cat").isJsonObject())
		{
			for (Map.Entry<String, com.google.gson.JsonElement> pg
				: cl.getAsJsonObject("by_cat").entrySet())
			{
				if (!pg.getValue().isJsonObject())
				{
					continue;
				}
				Map<String, Long> items = new LinkedHashMap<>();
				for (Map.Entry<String, com.google.gson.JsonElement> it
					: pg.getValue().getAsJsonObject().entrySet())
				{
					items.merge(it.getKey().toLowerCase(Locale.ROOT), safeLong(it.getValue()), Math::max);
				}
				o.byPage.put(pg.getKey().toLowerCase(Locale.ROOT), items);
			}
		}
		return o;
	}

	/**
	 * Which slots of a page the player holds. A slot lights when its name is in
	 * the page's own capture or the whole-log obtained set; duplicate-named slots
	 * (My Notes' 26 "Ancient page" entries) light positionally, k copies lighting
	 * the first k, as the game does.
	 */
	private static boolean[] lightSlots(List<String> slots, Map<String, Long> pageItems,
		Map<String, Long> owned, java.util.Set<String> sharedNames)
	{
		boolean[] lit = new boolean[slots.size()];
		Map<String, Integer> dupes = new LinkedHashMap<>();
		for (String slot : slots)
		{
			dupes.merge(slot.toLowerCase(Locale.ROOT), 1, Integer::sum);
		}
		Map<String, Integer> seen = new LinkedHashMap<>();
		for (int i = 0; i < slots.size(); i++)
		{
			String key = slots.get(i).toLowerCase(Locale.ROOT);
			long onPage = pageItems != null ? pageItems.getOrDefault(key, 0L) : 0L;
			// The whole-log set says an item is held; it does not say WHERE from,
			// and the game tracks that per page. An abyssal whip from the Sire
			// leaves by_cat["abyssal sire"] holding four of them and
			// by_cat["slayer"] holding none, so reading the log-wide set onto the
			// Slayer page lights a slot the game says is empty.
			//
			// So the log-wide set only speaks for a name that lives on ONE page,
			// where there is nothing to confuse it with, or for a page that has
			// never been read, where it is the only thing there is. A shared name
			// on a page that HAS been read waits for that page to be read again,
			// which happens on the next opening of the log.
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

	// Slot names that more than one collection log page lists. Static: the
	// taxonomy is a bundled file and does not change while the client is up.
	private static java.util.Set<String> sharedSlotNames;

	private static synchronized java.util.Set<String> sharedSlotNames(
		com.google.gson.Gson gson)
	{
		if (sharedSlotNames != null)
		{
			return sharedSlotNames;
		}
		Map<String, Integer> homes = new LinkedHashMap<>();
		for (Map.Entry<String, Map<String, List<String>>> tab : taxonomy(gson).entrySet())
		{
			for (Map.Entry<String, List<String>> pg : tab.getValue().entrySet())
			{
				java.util.Set<String> onThisPage = new java.util.HashSet<>();
				for (String slot : pg.getValue())
				{
					onThisPage.add(slot.toLowerCase(Locale.ROOT));
				}
				for (String slot : onThisPage)
				{
					homes.merge(slot, 1, Integer::sum);
				}
			}
		}
		java.util.Set<String> shared = new java.util.HashSet<>();
		for (Map.Entry<String, Integer> e : homes.entrySet())
		{
			if (e.getValue() > 1)
			{
				shared.add(e.getKey());
			}
		}
		sharedSlotNames = shared;
		return shared;
	}

	// One named slot on one page, by the same rule the Log tab lights it with. A
	// duplicate-named slot counts as held when any of its copies is lit.
	private static boolean slotHeld(String slot, String page, List<String> pageSlots,
		Obtained ob, java.util.Set<String> sharedNames)
	{
		boolean[] lit = lightSlots(pageSlots, ob.byPage.get(page.toLowerCase(Locale.ROOT)),
			ob.all, sharedNames);
		for (int i = 0; i < pageSlots.size(); i++)
		{
			if (lit[i] && pageSlots.get(i).equalsIgnoreCase(slot))
			{
				return true;
			}
		}
		return false;
	}

	private static long safeLong(com.google.gson.JsonElement e)
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

	// Parse the bundled taxonomy once. Order is preserved, tabs and slots.
	private static synchronized Map<String, Map<String, List<String>>> taxonomy(
		com.google.gson.Gson gson)
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
					new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8),
					JsonObject.class);
				for (Map.Entry<String, com.google.gson.JsonElement> tab : rootTax.entrySet())
				{
					Map<String, List<String>> pages = new LinkedHashMap<>();
					for (Map.Entry<String, com.google.gson.JsonElement> pg
						: tab.getValue().getAsJsonObject().entrySet())
					{
						List<String> slots = new ArrayList<>();
						for (com.google.gson.JsonElement it : pg.getValue().getAsJsonArray())
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
			// A missing or corrupt resource just leaves the browser empty.
		}
		taxonomy = out;
		return out;
	}

	// One line of pace for a skill, or the date it last moved. Days of PLAY, not
	// calendar days: an idle stretch doesn't dilute it.
	private void addPaceLine(JPanel p, String section)
	{
		PaceBook.Pace pace;
		try
		{
			pace = plugin.pace(section);
		}
		catch (RuntimeException e)
		{
			// Not for an unknown skill name: pace() catches that one itself and
			// hands back a Pace that prints nothing. This is the belt for a real
			// fault in there, and it hides one: the section simply shows no pace
			// line.
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

	// Every fold whose state a reader has moved off its default this session, one
	// key apiece: family:section on Stats, family:craft:verb a level under it,
	// pets:page:slot on a collection log pets page, home:xp and home:damage on
	// Home, session:family and session:row:key on the session strip, and the
	// History tab's under history:. A field, not a local, because rebuild() throws
	// the whole panel away several times a minute and a reader's fold has to
	// outlive that. Most foldables start folded and a key here means open; three
	// stand open instead, so for those a key means SHUT: the session strip's family
	// bands (session:family, not the session:row: rows under them), the History
	// tab's progress groups (history:shut:) and its kind bands (history:kind:). The
	// register is dropped whole when the account changes. The preview harness
	// reaches this by name, so a rename here has to be made there too.
	private final java.util.Set<String> openFolds = new java.util.HashSet<>();

	// Home's xp total, broken out per skill.
	private static final String FOLD_HOME_XP = "home:xp";
	private static final String FOLD_HOME_DAMAGE = "home:damage";
	// the styles damageDealt is made of; they read as its breakdown, never as
	// trackers of their own
	private static final List<String> DAMAGE_SPLIT = java.util.Arrays.asList(
		"damageDealtMelee", "damageDealtRanged", "damageDealtMagic");

	/** True while the fold under this key stands open. */
	private boolean foldOpen(String key)
	{
		return openFolds.contains(key);
	}

	/** Open a shut fold or shut an open one, and redraw. Every fold comes here. */
	private void toggleFold(String key)
	{
		if (!openFolds.remove(key))
		{
			openFolds.add(key);
		}
		rebuildInPlace();
	}

	/**
	 * Redraw without moving the reader. Opening a fold or asking a list for the
	 * rest of itself changes what is under the pointer, not where the reader is.
	 * A rebuild that is not marked as one takes them back to the first line, and
	 * on a long view every click would do it.
	 */
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

	// Answered once per rebuild.
	//
	// The Kills board asks the store for the same two things a hundred and
	// seventy times over -- once per source row, and again per column -- and
	// each ask takes the store's lock and hands back a fresh copy; the
	// collection log is deep-copied whole. The record cannot change inside a
	// build, because a build runs on the EDT and so does every write to it, so
	// neither can the answer. Cleared at the top of rebuild(), which makes the
	// memo exactly as fresh as the board on screen.
	private List<LocalStore.SourceRow> buildSources;
	private JsonObject buildClog;
	private Span buildSpan;
	private boolean spanAsked;

	/**
	 * Every source the ledger holds. Shared, so a caller that wants to sort it
	 * takes its own copy first.
	 */
	private List<LocalStore.SourceRow> sources()
	{
		if (buildSources == null)
		{
			buildSources = plugin.dropSources();
		}
		return buildSources;
	}

	/** The collection log as the store holds it. Shared; read, never written. */
	private JsonObject clogNow()
	{
		if (buildClog == null)
		{
			buildClog = plugin.clogSnapshot();
		}
		return buildClog;
	}

	// gp per consumable key, refreshed per rebuild. What the Food and Potions
	// rows put beside the count.
	private Map<String, Long> consumVals = new LinkedHashMap<>();

	// Resource-scoped drops, refreshed per rebuild. Rides the gathered row as a
	// margin. Subtract one from the other and a miner's career reads as zero.
	private long resourcesDropped;

	private String rowValue(Map.Entry<String, Long> e)
	{
		String base = StatRegistry.isGp(e.getKey()) ? gp(e.getValue()) + " gp" : fmt(e.getValue());
		if (e.getKey().equals("resourcesGatheredValue") && resourcesDropped > 0)
		{
			return base + " · " + gp(resourcesDropped) + " dropped";
		}
		// Lifetime only. What a consumable COST is accumulated forever and is not
		// in the history spine, so there is no windowed version of it to print:
		// under a narrowed period this would put a career's gp beside a week's
		// count and invite the reader to divide one by the other.
		Long cv = wholeRecord() ? consumVals.get(e.getKey()) : null;
		return cv != null && cv > 0 ? base + " · " + gp(cv) + " gp" : base;
	}

	/**
	 * Every tracker the record keeps, in one place. The stats table files them by
	 * family and a tab shows one family's half of the sheet, so the whole thing
	 * had nowhere to be asked for. Typing "trackers" into the search asks for it.
	 *
	 * <p>Filed the way the table files them, family then section, so a reader who
	 * knows where a counter lives still finds it where they expect. It reads the
	 * period like every other board.
	 */
	private JPanel buildAllTrackers()
	{
		JPanel p = column();
		p.add(backRow());
		p.add(vgap(4));
		consumVals = plugin.consumableValues();
		Map<String, Long> counters = countersForPeriod();
		if (counters == null)
		{
			p.add(noPeriod());
			return p;
		}
		// Out of THIS board's period, the way buildStats reads it out of its own.
		// Read off a field the other board happened to leave behind, the margin on
		// the gathered row was either missing, on a panel that had never drawn
		// Stats, or from whatever period Stats was last looked at.
		resourcesDropped = counters.getOrDefault("resourcesDroppedValue", 0L);
		Map<String, Map<String, List<Map.Entry<String, Long>>>> filed = new LinkedHashMap<>();
		for (String fam : StatRegistry.FAMILIES)
		{
			filed.put(fam, new LinkedHashMap<>());
		}
		int kept = 0;
		for (Map.Entry<String, Long> e : counters.entrySet())
		{
			if (e.getValue() == null || e.getValue() <= 0 || StatRegistry.hidden(e.getKey()))
			{
				continue;
			}
			Map<String, List<Map.Entry<String, Long>>> fam =
				filed.computeIfAbsent(StatRegistry.family(e.getKey()), k -> new LinkedHashMap<>());
			fam.computeIfAbsent(StatRegistry.subgroup(e.getKey()), k -> new ArrayList<>()).add(e);
			kept++;
		}
		JPanel head = card("Trackers");
		head.add(row("Counters", fmt(kept), accent()));
		head.add(row("Reading", wholeRecord() ? "Lifetime" : window().label, null));
		p.add(head);
		p.add(vgap(6));
		if (kept == 0)
		{
			p.add(note("Nothing tracked inside " + periodInSentence() + "."));
			return p;
		}
		for (Map.Entry<String, Map<String, List<Map.Entry<String, Long>>>> fam : filed.entrySet())
		{
			if (fam.getValue().isEmpty())
			{
				continue;
			}
			p.add(group(fam.getKey()));
			for (Map.Entry<String, List<Map.Entry<String, Long>>> sec : fam.getValue().entrySet())
			{
				List<Map.Entry<String, Long>> rows = sec.getValue();
				rows.sort(StatRegistry::compareRows);
				if (!sec.getKey().isEmpty())
				{
					p.add(ghostRow(sec.getKey(), ""));
				}
				for (Map.Entry<String, Long> e : rows)
				{
					p.add(row(StatRegistry.rowLabel(e.getKey()), rowValue(e), null));
				}
			}
			p.add(vgap(4));
		}
		return p;
	}

	private JPanel buildStats()
	{
		JPanel p = column();
		consumVals = plugin.consumableValues();
		// The tab already chose the family's half of the sheet: the Ledger holds
		// what a life costs and where it went, PvM's fourth board is Combat alone,
		// and Skilling is reached by opening a cell in the grid rather than by a
		// pill here. Offering all four would let a reader stand under one tab
		// reading another tab's board.
		//
		// This was tried once before and reverted, because narrowing it orphaned
		// the Skilling family: the grid carried a tooltip, not a click. Now that
		// the cell opens, nothing is orphaned, and a test holds the invariant that
		// makes it safe -- every craft the registry can file under is a skill the
		// grid draws, so every Skilling counter has a way in.
		// Record's Ledger keeps the two families that are what a life COSTS. The
		// Trackers tab holds every family there is, Combat included: it used to
		// hang off PvM's fourth board, which put damage dealt and deaths one tab
		// away from every other tally with nothing saying why.
		String[] families = tab == Tab.RECORD
			? new String[]{"Ledger & Roads", "Living"}
			: StatRegistry.FAMILIES;
		// Which family is SELECTED is navigation's business, not a builder's:
		// applyCommon already sets it when a tab is opened, and resetting it here
		// would mean a build could silently change what it was asked to draw.
		JPanel pills = new JPanel(new GridLayout(0, 2, 3, 3));
		pills.setBackground(ColorScheme.DARK_GRAY_COLOR);
		for (String fam : families)
		{
			JLabel pill = new JLabel(fam, JLabel.CENTER);
			pill.setOpaque(true);
			pill.setBorder(BorderFactory.createEmptyBorder(2, 7, 2, 7));
			pill.setFont(FontManager.getRunescapeSmallFont());
			boolean on = fam.equals(statsFamily);
			pill.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			pill.setForeground(on ? accent() : ColorScheme.LIGHT_GRAY_COLOR.darker());
			pill.setCursor(java.awt.Cursor.getPredefinedCursor(
				java.awt.Cursor.HAND_CURSOR));
			pill.addMouseListener(clicker(() ->
			{
				statsFamily = fam;
				rebuildInPlace();
			}));
			pills.add(pill);
		}
		if (families.length > 1)
		{
			p.add(pills);
			p.add(vgap(4));
		}


		// Rows file into sections. Generic floor totals (logsChopped,
		// teleportsTotal) head their section instead of listing as a row, and the
		// unresolved remainder reconciles as a ghost "Other".
		Map<String, Long> counters = countersForPeriod();
		if (counters == null)
		{
			p.add(noPeriod());
			return p;
		}
		resourcesDropped = counters.getOrDefault("resourcesDroppedValue", 0L);
		Map<String, List<Map.Entry<String, Long>>> rowsBySection = new LinkedHashMap<>();
		Map<String, Long> floorTotals = new LinkedHashMap<>();
		for (Map.Entry<String, Long> e : counters.entrySet())
		{
			if (e.getValue() == 0 || StatRegistry.hidden(e.getKey())
				|| !StatRegistry.family(e.getKey()).equals(statsFamily))
			{
				continue;
			}
			String sec = StatRegistry.subgroup(e.getKey());
			if (StatRegistry.isFloor(e.getKey()))
			{
				floorTotals.merge(sec, e.getValue(), Long::sum);
				continue;
			}
			rowsBySection.computeIfAbsent(sec, k -> new ArrayList<>()).add(e);
		}
		if (rowsBySection.isEmpty() && floorTotals.isEmpty())
		{
			p.add(note("Nothing tracked here yet."));
			return p;
		}

		// Destinations nest inside the Teleports fold.
		List<Map.Entry<String, Long>> destRows = statsFamily.equals("Ledger & Roads")
			? rowsBySection.remove("Destinations") : null;
		if (destRows != null && !rowsBySection.containsKey("Teleports")
			&& !floorTotals.containsKey("Teleports"))
		{
			rowsBySection.put("Destinations", destRows);   // no host fold, stand alone
			destRows = null;
		}

		List<String> order = sectionOrder(rowsBySection, floorTotals);
		for (String sec : order)
		{
			List<Map.Entry<String, Long>> rows =
				rowsBySection.getOrDefault(sec, new ArrayList<>());
			rows.sort(StatRegistry::compareRows);
			long floor = floorTotals.getOrDefault(sec, 0L);

			if (sec.isEmpty())
			{
				for (Map.Entry<String, Long> e : rows)
				{
					p.add(row(StatRegistry.rowLabel(e.getKey()), rowValue(e), null));
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
			for (Map.Entry<String, Long> e : rows)
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
				// Means aren't "typed" in the craft sense, but the floor still
				// reconciles: unclassified journeys surface as "Other means".
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
				for (Map.Entry<String, Long> e : rows)
				{
					p.add(row(StatRegistry.rowLabel(e.getKey()), rowValue(e), null));
				}
				continue;
			}

			String stateKey = statsFamily + ":" + sec;
			boolean open = foldOpen(stateKey);
			long secGp = 0;
			if (wholeRecord())
			{
				for (Map.Entry<String, Long> e : rows)
				{
					Long cv = consumVals.get(e.getKey());
					if (cv != null)
					{
						secGp += cv;
					}
				}
			}
			// The count stands in both states. The gp beside it is lifetime and so
			// only stands on Lifetime: a collapsed section otherwise paired a
			// week's count with a career's spend, which reads as one arithmetic.
			p.add(quietHead(sec, fmt(total) + (secGp > 0 ? " · " + gp(secGp) + " gp" : ""),
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
					for (Map.Entry<String, Long> e : rows)
					{
						p.add(row(StatRegistry.rowLabel(e.getKey()), rowValue(e), null));
					}
					// A section with no typed rows opens to its floors, one row
					// each: bones buried and bones offered are separate verbs and
					// can't share a row.
					//
					// Taken BEFORE the ghost, and it cancels it. With no typed rows
					// the unresolved remainder IS the floor, so drawing both put the
					// same figure on the board twice: once as a floor row under its
					// own name and once as "Other means" underneath it.
					if (rows.isEmpty() && floor > 0)
					{
						List<Map.Entry<String, Long>> floors = new ArrayList<>();
						for (String fk : StatRegistry.floorKeys(sec))
						{
							long fv = counters.getOrDefault(fk, 0L);
							if (fv > 0 && !StatRegistry.hidden(fk))
							{
								floors.add(new java.util.AbstractMap.SimpleEntry<>(fk, fv));
							}
						}
						floors.sort(StatRegistry::compareRows);
						for (Map.Entry<String, Long> fe : floors)
						{
							p.add(row(StatRegistry.label(fe.getKey()), fmt(fe.getValue()), null));
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

	// Sections in display order: Skilling's crafts rank by weight, the other
	// families keep the registry's fixed order with strays appended.
	private List<String> sectionOrder(Map<String, List<Map.Entry<String, Long>>> rowsBySection,
		Map<String, Long> floorTotals)
	{
		java.util.LinkedHashSet<String> present = new java.util.LinkedHashSet<>();
		present.addAll(rowsBySection.keySet());
		present.addAll(floorTotals.keySet());
		List<String> order = new ArrayList<>();
		if (statsFamily.equals("Skilling"))
		{
			List<String> crafts = new ArrayList<>(present);
			// A craft weighs its floor total when it has one; that is the headline
			// count. Otherwise the sum of its rows.
			crafts.sort(Comparator.comparingLong((String s) ->
			{
				long floor = floorTotals.getOrDefault(s, 0L);
				if (floor > 0)
				{
					return floor;
				}
				long sum = 0;
				for (Map.Entry<String, Long> e
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

	/**
	 * Multi-verb crafts drill one level deeper: Prayer opens to Bones buried ·
	 * Ashes scattered · Ensouled heads, each fold reconciling to its own floor,
	 * with the verbless totals (Ashes sacrificed) as flat rows above. Returns
	 * false under two verb groups, and the caller renders the flat list instead.
	 */
	private boolean addCraftNested(JPanel p, String craft,
		List<Map.Entry<String, Long>> rows, Map<String, Long> counters)
	{
		Map<String, List<Map.Entry<String, Long>>> byVerb = new LinkedHashMap<>();
		List<Map.Entry<String, Long>> leaves = new ArrayList<>();
		for (Map.Entry<String, Long> e : rows)
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
		for (Map.Entry<String, Long> e : leaves)
		{
			p.add(row(StatRegistry.rowLabel(e.getKey()), value(e), null));
		}
		List<String> verbs = new ArrayList<>(byVerb.keySet());
		Map<String, Long> verbTotal = new LinkedHashMap<>();
		for (String verb : verbs)
		{
			String floorKey = StatRegistry.suffixFloor(craft, verb);
			long floorVal = floorKey != null ? counters.getOrDefault(floorKey, 0L) : 0L;
			long sum = 0;
			for (Map.Entry<String, Long> e : byVerb.get(verb))
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
				fmt(verbTotal.getOrDefault(verb, 0L)), stateKey, open));
			if (open)
			{
				long sum = 0;
				for (Map.Entry<String, Long> e : byVerb.get(verb))
				{
					p.add(row(StatRegistry.rowLabel(e.getKey()), rowValue(e), null));
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

	// Destinations sit one level under Teleports: where the roads led.
	private void addDestinationsFold(JPanel p, List<Map.Entry<String, Long>> destRows)
	{
		destRows.sort(StatRegistry::compareRows);
		long sum = 0;
		for (Map.Entry<String, Long> e : destRows)
		{
			sum += e.getValue();
		}
		String stateKey = "Ledger & Roads:Destinations";
		boolean open = foldOpen(stateKey);
		p.add(subHead("Destinations", fmt(sum), stateKey, open));
		if (open)
		{
			for (Map.Entry<String, Long> e : destRows)
			{
				p.add(row(StatRegistry.label(e.getKey()), value(e), null));
			}
		}
	}

	// A second-level fold header: normal case, indented, click to toggle.
	private JPanel subHead(String label, String totalStr, String stateKey, boolean open)
	{
		JPanel head = row(label, totalStr, null);
		JLabel name = (JLabel) ((BorderLayout) head.getLayout())
			.getLayoutComponent(BorderLayout.CENTER);
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(ColorScheme.LIGHT_GRAY_COLOR.darker());
		head.setBorder(BorderFactory.createEmptyBorder(3, 10, 1, 2));
		head.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		head.addMouseListener(clicker(() -> toggleFold(stateKey)));
		return head;
	}

	private static String value(Map.Entry<String, Long> e)
	{
		return StatRegistry.isGp(e.getKey()) ? gp(e.getValue()) + " gp" : fmt(e.getValue());
	}

	// A quiet row for a remainder or an aside.
	private static JPanel ghostRow(String left, String right)
	{
		return ghostRow(left, right, null);
	}

	// The same aside, with a value allowed its own colour: the line still reads as
	// an aside, but a drought can flare.
	private static JPanel ghostRow(String left, String right, Color rightColor)
	{
		JPanel r = row(left, right, rightColor);
		((JLabel) ((BorderLayout) r.getLayout()).getLayoutComponent(BorderLayout.CENTER))
			.setForeground(ColorScheme.LIGHT_GRAY_COLOR.darker().darker());
		return r;
	}

	// How deep the milestone scan reads into the feed; a year-long window still
	// has to find its own entries.
	private static final int HISTORY_FEED_SCAN = 2000;

	// The two reads the History tab lives on, held between rebuilds. The spine is
	// a whole parse of an append-only file and the feed slice is deep-copied under
	// the store's lock. Both are gathered on a worker thread; on the EDT that cost
	// lands as a stall on every pill click.
	private java.util.TreeMap<java.time.LocalDate, HistoryLog.Baseline> historySpine;
	private List<JsonObject> historyFeed = new ArrayList<>();
	// The slayer journey read beside them: the progress card's tasks-completed
	// line counts its closed segments by date, which reach back past the spine.
	private ChronicleApiClient.SlayerJourney historyJourney;
	// What that pair was true of: the day it was read and the newest feed entry
	// it saw. Either one moving means the cache is stale.
	private java.time.LocalDate historyDay;
	private long historyFeedTs;
	private boolean historyGathering;
	// A gather in flight when a different journal mounts must not land; its
	// spine belongs to the account that has gone.
	private int historyEpoch;

	// One gathered pass over the journal's calendar spine and its feed.
	private static final class HistoryData
	{
		final java.util.TreeMap<java.time.LocalDate, HistoryLog.Baseline> spine;
		final List<JsonObject> feed;
		final ChronicleApiClient.SlayerJourney journey;
		final java.time.LocalDate day;

		HistoryData(java.util.TreeMap<java.time.LocalDate, HistoryLog.Baseline> spine,
			List<JsonObject> feed, ChronicleApiClient.SlayerJourney journey,
			java.time.LocalDate day)
		{
			this.spine = spine;
			this.feed = feed;
			this.journey = journey;
			this.day = day;
		}
	}

	/**
	 * Read the spine and the feed slice off the EDT, then mount them. Primed when
	 * the panel is built and whenever a journal mounts; the tab is usually warm
	 * before it is opened. EDT only.
	 */
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
					java.time.LocalDate.now());
			}

			@Override
			protected void done()
			{
				if (epoch != historyEpoch)
				{
					// Another account mounted mid-read; its own gather owns the
					// cache now.
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
					// Not re-asserted. done() runs on the EDT, and setting the
					// interrupt flag on the client's event thread to signal a
					// caller that does not exist is a way to disturb Swing for
					// nothing: the read is simply abandoned and the next
					// rebuild asks again.
					return;
				}
				catch (java.util.concurrent.ExecutionException e)
				{
					// leave the cache cold; the next rebuild asks again
					return;
				}
				historySpine = d.spine;
				historyFeed = d.feed;
				historyJourney = d.journey;
				historyDay = d.day;
				historyFeedTs = newestTs(d.feed);
				// Unconditional. This named View.HISTORY, which viewOf() never
				// returns, so the read landed and nothing was ever redrawn: a
				// reader standing on a board that wants the spine kept the
				// sentence where the board should be. Three boards read it, the
				// skills sheet, Now and the Journal's frontispiece, and rebuild()
				// already declines to run while the panel is hidden, so there is
				// nothing for a view test to save here.
				rebuild();
			}
		}.execute();
	}

	// The newest feed entry's stamp, or 0. The cheap staleness probe.
	private static long newestTs(List<JsonObject> feed)
	{
		return feed.isEmpty() ? 0 : safeLong(feed.get(0).get("ts"));
	}

	// Rows mounted inside a group's list before its "Show more" tail.
	private static final int HIST_LIST_CAP = 6;
	// How much of one capped list is mounted, keyed by the list. A click on the
	// tail raises it; the register is cleared when a journal mounts.
	private final Map<String, Integer> histListShown = new LinkedHashMap<>();

	/**
	 * The period filed into its groups: experience, combat, loot, skilling,
	 * upkeep, travel, achievement, and everything else the plugin tracks, in
	 * that order and only where the period holds something. Each group is a
	 * fold whose head carries the number of lines inside it, the way the site's
	 * tab bar counts a tab.
	 *
	 * <p>Inside a group: its own figures first, then the sections the Stats tab
	 * files the rest of the counters into, each its own fold reconciling to its
	 * floor with the remainder as a ghost "Other". A figure the journal can
	 * name (the slayer tasks, the pets, the log slots, the quests, the diaries,
	 * the combat achievements, the levels) is itself a fold: a click opens it
	 * to those names and their dates. Experience opens to the per-skill gains
	 * ranked. A list longer than {@link #HIST_LIST_CAP} shows its top rows and
	 * a "Show N more" tail.
	 *
	 * <p>The folds are keyed under "history:", apart from the Stats tab's, so a
	 * reader's fold on one tab leaves the other as it was. A group stands open
	 * and its key ("history:shut:") shuts it; the folds under a group, a figure's
	 * names and a section's rows, start shut.
	 */
	private JPanel trackedProgress(HistoryProgress progress,
		List<Map.Entry<String, Long>> gains, Map<String, List<String[]>> named)
	{
		JPanel card = card("Tracked progress");
		for (String name : HistoryProgress.GROUPS)
		{
			HistoryProgress.Group g = progress.group(name);
			boolean experience = "Experience".equals(name);
			List<HistoryProgress.Row> rows = g != null ? g.rows()
				: java.util.Collections.<HistoryProgress.Row>emptyList();
			List<HistoryProgress.Section> secs = g != null ? g.sections()
				: java.util.Collections.<HistoryProgress.Section>emptyList();
			int lines = rows.size() + secs.size() + (experience ? gains.size() : 0);
			if (lines == 0)
			{
				continue;
			}
			// The tab exists to show a period's progress, so it shows it. A group
			// stands open and the fold shuts it, the way the session strip's bands
			// do; the key names that, so a reader of this code is not left working
			// out which way round it is.
			String stateKey = "history:shut:" + name;
			boolean open = !foldOpen(stateKey);
			// the count stands in both states here, unlike the session strip's
			// bands: these lists are capped and paged, so how many the group holds
			// is not something the rows on screen can tell you
			card.add(quietHead(name, fmt(lines), stateKey));
			if (!open)
			{
				continue;
			}
			if (experience)
			{
				int cap = shownCap(GAINS_LIST);
				int mounted = 0;
				for (Map.Entry<String, Long> e : gains)
				{
					if (mounted++ >= cap)
					{
						break;
					}
					card.add(row(StatRegistry.prettify(e.getKey()), "+" + gp(e.getValue()), null));
				}
				// the gains are the group's own rows, not a list one step in,
				// so their tail pages at the same indent they do
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

	// the cap register's key for Experience's ranked gains, which are no
	// counter's rows and so have no key of their own
	private static final String GAINS_LIST = "history:xp";

	// One of a group's figures. Where the journal can name what the figure
	// counts, the row is a fold
	// and the names sit under it, each with the day it happened. An entry the
	// journal counted but cannot name closes the list as a ghost, the way a
	// section closes with its "Other": the head's figure is then accounted for
	// on screen rather than opening to a shorter list than it claims.
	private void addGroupRow(JPanel card, HistoryProgress.Row r, List<String[]> list)
	{
		if (list == null || list.isEmpty())
		{
			card.add(row(r.label(), "+" + figure(r), null));
			return;
		}
		String listKey = "history:list:" + r.key();
		boolean open = foldOpen(listKey);
		JPanel head = row(r.label(), "+" + figure(r), null);
		head.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		head.addMouseListener(clicker(() -> toggleFold(listKey)));
		card.add(head);
		if (open)
		{
			int cap = shownCap(listKey);
			int mounted = 0;
			for (String[] entry : list)
			{
				if (mounted++ >= cap)
				{
					break;
				}
				card.add(nested(ghostRow(entry[0], entry[1])));
			}
			addMore(card, listKey, list.size(), cap);
			// only a count row can be short of its list: a gp row's drill names
			// the items its value is made of, and the two are not the same unit
			long unnamed = r.gp() ? 0 : r.value() - list.size();
			if (unnamed > 0)
			{
				card.add(nested(ghostRow("Not named in the record", "+" + fmt(unnamed))));
			}
		}
	}

	// One of a group's sections, the fold the Stats tab files these counters
	// into: its head carries the section's period total where the rows add up
	// to one figure, and a click opens it to the rows and the leftover "Other".
	private void addGroupSection(JPanel card, HistoryProgress.Section s)
	{
		String stateKey = "history:" + s.family() + ":" + s.name();
		boolean open = foldOpen(stateKey);
		// a section of gp rows totals in gp and every other section counts. One
		// whose rows mix units has no total worth printing, so it says how many
		// it holds instead: a bare heading with nothing beside it tells a reader
		// neither what is inside nor that anything is.
		String total = s.summed()
			? "+" + (s.gp() ? gp(s.total()) + " gp" : fmt(s.total()))
			: fmt(s.rows().size());
		card.add(subHead(s.name(), total, stateKey, open));
		if (!open)
		{
			return;
		}
		int cap = shownCap(stateKey);
		int mounted = 0;
		for (HistoryProgress.Row r : s.rows())
		{
			if (mounted++ >= cap)
			{
				break;
			}
			card.add(nested(row(r.label(), "+" + figure(r), null)));
		}
		addMore(card, stateKey, s.rows().size(), cap);
		if (s.ghost() > 0)
		{
			card.add(nested(ghostRow(s.ghostLabel(), "+" + fmt(s.ghost()))));
		}
	}

	// A row one step in from the fold it sits under, so a section's rows read
	// as that section's and not as the group's own.
	private static JPanel nested(JPanel r)
	{
		r.setBorder(BorderFactory.createEmptyBorder(1, ROW_INSET + 12, 1, ROW_INSET));
		return r;
	}

	private int shownCap(String key)
	{
		Integer n = histListShown.get(key);
		return n == null ? HIST_LIST_CAP : n;
	}

	// The tail under a capped list: what is still folded away, and the click
	// that brings it.
	private void addMore(JPanel card, String key, int size, int cap)
	{
		addMore(card, key, size, cap, true);
	}

	// The same tail, drawn at the indent of the rows it pages: one step in
	// under a list or a section, and flush under the group's own rows.
	private void addMore(JPanel card, String key, int size, int cap, boolean inset)
	{
		if (size <= cap)
		{
			return;
		}
		// The tail names everything still folded away, and one click brings all of
		// it: a list of ninety tasks should not be fifteen clicks that each say six.
		JPanel tail = ghostRow("Show " + fmt(size - cap) + " more", "");
		JPanel more = inset ? nested(tail) : tail;
		more.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		more.addMouseListener(clicker(() ->
		{
			histListShown.put(key, size);
			rebuildInPlace();
		}));
		card.add(more);
	}

	/**
	 * The dates the period's figures reach back to, where that is later than the
	 * period's own opening line and the figures are therefore a part of it.
	 *
	 * @param lootFrom the day the loot rows reach back to when the sittings
	 * supplied them, or null when they cover the period
	 * @param lootFromSittings whether the sittings supplied the loot rows; the
	 * spine is then not their source and its own start date says nothing of them
	 */
	private static String countersSince(
		java.util.SortedMap<java.time.LocalDate, HistoryLog.Baseline> spine,
		java.time.LocalDate startLine, java.time.LocalDate lootFrom, boolean lootFromSittings)
	{
		java.time.LocalDate counters = HistoryLog.firstCarrying(spine, null);
		java.time.LocalDate loot = lootFromSittings
			? lootFrom : HistoryLog.firstCarrying(spine, "dropsReceived");
		StringBuilder note = new StringBuilder();
		java.time.LocalDate since = startLine;
		if (counters != null && (since == null || counters.isAfter(since)))
		{
			note.append("Counters since ").append(counters.format(FULL_DAY));
			since = counters;
		}
		// a loot date handed in was already measured against the period's own
		// start, so it stands whatever the counters say: the counters beginning
		// later does not make the loot boundary untrue
		if (loot != null && (lootFromSittings || since == null || loot.isAfter(since)))
		{
			String what = lootFromSittings ? "loot" : "loot and kills";
			note.append(note.length() == 0
				? Character.toUpperCase(what.charAt(0)) + what.substring(1) + " since "
				: " · " + what + " since ")
				.append(loot.format(FULL_DAY));
		}
		return note.length() == 0 ? null : note.toString();
	}

	// Closed slayer segments dated inside [fromMs, toMs): a segment's ts is its
	// completion instant, in epoch seconds.
	private static long closedTasksBetween(ChronicleApiClient.SlayerJourney j, long fromMs, long toMs)
	{
		long n = 0;
		for (ChronicleApiClient.SlayerTask t : j.tasks)
		{
			if (closedInside(t, fromMs, toMs))
			{
				n++;
			}
		}
		return n;
	}

	// The kills those same segments hold, each task's own count as its completion
	// set it, the kills the loot never saw included. The window is the one
	// closedTasksBetween reads, so the two lines agree on which tasks are the
	// period's.
	private static long closedKillsBetween(ChronicleApiClient.SlayerJourney j, long fromMs, long toMs)
	{
		long n = 0;
		for (ChronicleApiClient.SlayerTask t : j.tasks)
		{
			if (closedInside(t, fromMs, toMs))
			{
				n += t.kills;
			}
		}
		return n;
	}

	// A segment closed inside the window; the one in hand is nobody's yet.
	private static boolean closedInside(ChronicleApiClient.SlayerTask t, long fromMs, long toMs)
	{
		long ms = (long) (t.ts * 1000);
		return !t.inProgress && ms >= fromMs && ms < toMs;
	}

	// Those same segments by name, newest first, each with the kills it took
	// and the day it closed: what the Combat group's tasks line opens to.
	private static List<String[]> closedTaskNames(ChronicleApiClient.SlayerJourney j,
		long fromMs, long toMs)
	{
		List<ChronicleApiClient.SlayerTask> closed = new ArrayList<>();
		for (ChronicleApiClient.SlayerTask t : j.tasks)
		{
			if (closedInside(t, fromMs, toMs))
			{
				closed.add(t);
			}
		}
		closed.sort((a, b) -> Double.compare(b.ts, a.ts));
		List<String[]> out = new ArrayList<>(closed.size());
		for (ChronicleApiClient.SlayerTask t : closed)
		{
			long ms = (long) (t.ts * 1000);
			out.add(new String[]{t.task, fmt(t.kills) + " · " + DAY.format(Instant.ofEpochMilli(ms))});
		}
		return out;
	}

	// The quest itself, out of the line the game announced it in ("You have
	// completed Fallen From Grace!"). A name that arrives clean is left alone.
	static String questName(String raw)
	{
		String q = raw == null ? "" : raw.trim();
		int at = q.toLowerCase(Locale.ROOT).indexOf("you have completed ");
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

	// What one feed entry names, for the list its figure opens to: the item,
	// the pet, the quest, the diary, the task and its tier, the skill and the
	// level it reached. Null where the entry names nothing, which is what an
	// imported milestone carries: the figure still counts it, and the list
	// holds only what the record can actually name.
	private static String feedName(JsonObject e)
	{
		String type = e.has("type") ? e.get("type").getAsString() : "";
		JsonObject d = e.has("data") && e.get("data").isJsonObject()
			? e.getAsJsonObject("data") : new JsonObject();
		switch (type)
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
					? StatRegistry.prettify(d.get("tier").getAsString().toLowerCase(Locale.ROOT))
					+ " · " : "") + d.get("task").getAsString()
					: null;
			case "LEVEL":
				return has(d, "skill")
					? StatRegistry.prettify(d.get("skill").getAsString().toLowerCase(Locale.ROOT))
					+ (has(d, "level") ? " " + d.get("level").getAsString() : "")
					: null;
			default:
				return null;
		}
	}

	// A field that is there and says something.
	private static boolean has(JsonObject o, String key)
	{
		return o.has(key) && !o.get(key).isJsonNull()
			&& !o.get(key).getAsString().trim().isEmpty();
	}

	// The minutes one played session stands for; 0 when it carries none.
	private static long sessionMinutes(JsonObject e)
	{
		JsonObject d = e.has("data") && e.get("data").isJsonObject()
			? e.getAsJsonObject("data") : new JsonObject();
		return Math.max(0, safeLong(d.get("minutes")));
	}

	// The feed's dated entry types the progress card counts, each under the
	// summary key its line reads. Where the spine carries the key too (deaths,
	// collection log slots) the feed's count lays over its delta; the rest
	// never ride the spine and stand on the feed alone.
	private static final Map<String, String> FEED_SUMMARY_KEYS = new LinkedHashMap<>();

	static
	{
		FEED_SUMMARY_KEYS.put("COLLECTION", "clogSlotsObtained");
		FEED_SUMMARY_KEYS.put("DEATH", "deaths");
		FEED_SUMMARY_KEYS.put("PET", "petsObtained");
		FEED_SUMMARY_KEYS.put("QUEST", "questsCompleted");
		FEED_SUMMARY_KEYS.put("DIARY", "diariesCompleted");
		FEED_SUMMARY_KEYS.put("COMBAT_ACHIEVEMENT", "combatAchievements");
		FEED_SUMMARY_KEYS.put("LEVEL", "levelsGained");
	}

	// whether the record was keeping sittings before this window opened
	private static boolean sittingsCover(List<JsonObject> feed, long fromMs)
	{
		long oldest = oldestSessionTs(feed);
		return oldest > 0 && oldest <= fromMs;
	}

	// the earliest day either dated loot source can speak for: the roll where it
	// has been running, the sittings before it existed
	private static long earliestDatedLoot(List<JsonObject> feed, long rollFrom)
	{
		long sittings = oldestSessionTs(feed);
		if (sittings <= 0)
		{
			return rollFrom;
		}
		return rollFrom <= 0 ? sittings : Math.min(sittings, rollFrom);
	}

	// a ranked breakdown as drill rows: what it was, then how many and what for
	private List<String[]> itemLines(List<String[]> ranked)
	{
		List<String[]> out = new ArrayList<>();
		for (String[] r : ranked)
		{
			long qty = safeParse(r[1]);
			long val = safeParse(r[2]);
			out.add(new String[]{r[0],
				fmt(qty) + (val > 0 ? " · " + gp(val) + " gp" : "")});
		}
		return out;
	}

	private List<String[]> sourceLines(List<String[]> ranked)
	{
		List<String[]> out = new ArrayList<>();
		for (String[] r : ranked)
		{
			out.add(new String[]{r[0], fmt(safeParse(r[1])) + " · "
				+ gp(safeParse(r[2])) + " gp"});
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

	// The oldest sitting the record holds. A sitting is the only dated account of
	// a take, so this is the day from which any loot figure can be drawn at all.
	private static long oldestSessionTs(List<JsonObject> feed)
	{
		long oldest = 0;
		for (JsonObject e : feed)
		{
			if (!e.has("type") || !"SESSION".equals(e.get("type").getAsString()))
			{
				continue;
			}
			long ts = safeLong(e.get("ts"));
			if (ts > 0 && (oldest == 0 || ts < oldest))
			{
				oldest = ts;
			}
		}
		return oldest;
	}

	private static long oldestTs(List<JsonObject> feed)
	{
		long oldest = 0;
		for (JsonObject e : feed)
		{
			long ts = safeLong(e.get("ts"));
			if (ts > 0 && (oldest == 0 || ts < oldest))
			{
				oldest = ts;
			}
		}
		return oldest;
	}

	// A progress figure: gp keys in gp, and a second gp figure beside the value
	// when the row carries one, worded as the row says ("3 · 300k gp" for loot
	// left on the floor, "2.5M gp · 300k dropped" for what was gathered).
	private static String figure(HistoryProgress.Row r)
	{
		String base = r.gp() ? gp(r.value()) + " gp" : fmt(r.value());
		return r.gpNote() > 0 ? base + " · " + gp(r.gpNote()) + " " + r.gpNoteWord() : base;
	}

	/**
	 * What the period's loot was worth, five figures in one unit. Counts are the
	 * boards' business; this is the money.
	 */
	private void addLootValues(JPanel p, HistoryProgress progress)
	{
		long received = summaryValue(progress, "lootValue");
		// what was carried away is its own row where the record can date the
		// floor; where it cannot, nothing was left so far as this can say
		HistoryProgress.Row keptRow = summaryRow(progress, "lootKept");
		long kept = keptRow == null ? received : keptRow.value();
		long left = Math.max(0, received - kept);
		JPanel card = card("What it was worth");
		card.add(row("Loot received", gp(received) + " gp", accent()));
		// taken is what was received less what was left where it fell
		card.add(row("Loot taken", gp(kept) + " gp", null));
		card.add(row("Loot left", gp(left) + " gp", null));
		card.add(row("Discarded", gp(summaryValue(progress, "itemsDroppedValue")) + " gp", null));
		card.add(row("Food consumed", gp(summaryValue(progress, "consumedValue")) + " gp", null));
		p.add(card);
		p.add(vgap(6));
	}

	/**
	 * Two bands of the one count. Everything the record counts is sorted into
	 * bosses, monsters, activities and skilling, and a facet draws the two that
	 * are its business: a boss board is not the place to find a pickpocket, and
	 * a thieving total is not a kill.
	 *
	 * <p>The first band carries the icon each of its names is known by, since a
	 * reader knows a boss on sight. The second is plain: it runs to hundreds, and
	 * a list is what hundreds of anything wants to be.
	 */
	private void addKinds(JPanel p, Map<String, Long> beforeKc, Map<String, Long> earliestKc,
		Map<String, Long> nowKc, boolean live, java.time.LocalDate from,
		java.time.LocalDate to, String first, String second)
	{
		boolean whole = wholeRecord();
		// read once a build: the journal is asked for these whole, and a new
		// target mints its counter mid-session
		skilled = null;
		ledgerNames = null;
		sourceKinds.clear();
		Map<String, Long> standing = live ? plugin.killCounts() : nowKc;
		// Both halves off the sitting's own roll entry. The spine is written once
		// a day, so a delta between two of its lines is the DAY under a sitting,
		// and the roll's dated days are the day for the same reason; the entry
		// the sitting keeps for itself is the only thing that knows.
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
		// Lifetime shows what a thing stands at; a period shows only what that
		// period put on it, and a thing the period never touched is not in it.
		Map<String, List<Map.Entry<String, Long>>> byKind = new LinkedHashMap<>();
		for (String name : whole ? standing.keySet() : union(gained.keySet(), worth.keySet()))
		{
			long figure = whole ? standing.getOrDefault(name, 0L) : gained.getOrDefault(name, 0L);
			if (figure <= 0 && paidFor(worth, loose, name) <= 0)
			{
				continue;
			}
			byKind.computeIfAbsent(sourceKind(name), k -> new ArrayList<>())
				.add(new java.util.AbstractMap.SimpleEntry<>(name, figure));
		}
		Comparator<Map.Entry<String, Long>> byPaid = (a, b) ->
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
			List<Map.Entry<String, Long>> rows = byKind.get(kind);
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
			p.add(ghostRow(whole ? "Nothing counted yet." : "Nothing counted this period.", ""));
			p.add(vgap(6));
		}
	}

	private static java.util.Set<String> union(java.util.Set<String> a, java.util.Set<String> b)
	{
		java.util.Set<String> out = new java.util.LinkedHashSet<>(a);
		out.addAll(b);
		return out;
	}

	/**
	 * What each source paid over the window: the dated roll where it reaches, the
	 * whole ledger where the period is the whole record.
	 *
	 * <p>Keyed the way {@code LocalStore.sourceKills} keys the counts, which is
	 * the collection log's spelling wherever the log has a page of the same name.
	 * The ledger says "Tormented Demon" and the log says "Tormented Demons"; read
	 * straight, the two never met and the biggest earner on the record sorted to
	 * the bottom of the board as though it had paid nothing.
	 */
	private Map<String, Long> periodWorth(java.time.LocalDate from, java.time.LocalDate to)
	{
		Map<String, Long> out = new LinkedHashMap<>();
		if (wholeRecord())
		{
			for (LocalStore.SourceRow r : sources())
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

	// The paid figure for a counted name, found under either spelling.
	private static long paidFor(Map<String, Long> worth, Map<String, Long> loose, String name)
	{
		Long exact = worth.get(name);
		return exact != null ? exact : loose.getOrDefault(LocalStore.kindOf(name), 0L);
	}

	// The same figures under the one spelling both sides can agree on.
	private static Map<String, Long> loosely(Map<String, Long> worth)
	{
		Map<String, Long> out = new LinkedHashMap<>();
		for (Map.Entry<String, Long> e : worth.entrySet())
		{
			out.merge(LocalStore.kindOf(e.getKey()), e.getValue(), Long::sum);
		}
		return out;
	}

	/**
	 * What a counted name is. The collection log's own tabs decide most of it: its
	 * Bosses and Raids are bosses, its Clues and Minigames are activities, and its
	 * Other tab is skilling ground but for the pages of it that are something
	 * killed. A name the log has no page for is a monster unless it is plainly
	 * something opened or something caught. The pickpocket targets the log has no
	 * page for at all, so they are named here the way the teleport destinations
	 * are: a closed set the game itself fixes, widened by what the record's own
	 * thieving counters say.
	 */
	private static final java.util.Set<String> PICKPOCKETED = new java.util.HashSet<>(
		java.util.Arrays.asList("man", "woman", "farmer", "master farmer", "hero",
			"paladin", "knight", "knight of ardougne", "ardougne knight", "watchman",
			"yanille watchman", "guard", "market guard", "rogue", "bandit",
			"desert bandit", "pollnivnian bandit", "bearded pollnivnian bandit",
			"menaphite thug", "cave goblin", "h.a.m. member", "male h.a.m. member",
			"female h.a.m. member", "elf", "vyre", "tzhaar-hur", "fremennik citizen",
			"villager", "gnome", "gnome woman", "gnome child", "warrior woman",
			"al kharid warrior", "al-kharid warrior", "wealthy citizen", "martin",
			"martin the master gardener"));

	// The verbs the record counts a skill by, and the skill each of them means.
	// A counter is minted per target ({@code guardPickpockets},
	// {@code redSalamandersTrapped}), so what the game did to a thing is written
	// down beside the thing.
	private static final String[][] SKILLED = {
		{"Pickpockets", "THIEVING"}, {"Trapped", "HUNTER"},
		{"Caught", "HUNTER"}, {"Harvested", "HUNTER"},
	};

	/**
	 * The same question asked of the record rather than of a table: a name the
	 * journal counts under one of those verbs was worked for, not killed, so a
	 * target the table has never heard of still names itself and a game update
	 * that adds one needs no edit here. Letters only, since the counter is camel
	 * case and the ledger's name is not.
	 */
	private Map<String, String> skilledKeys()
	{
		if (skilled == null)
		{
			Map<String, String> found = new LinkedHashMap<>();
			for (String key : counters().keySet())
			{
				for (String[] verb : SKILLED)
				{
					// a failure is spelled like the thing it failed at
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

	/**
	 * A name reduced to what two spellings of it can agree on: its letters, in
	 * lower case, singular. The counter is camel case and plural
	 * ({@code moonlightMothsTrapped}) and the ledger's name is neither
	 * ("Moonlight moth"), so neither side is comparable as it stands.
	 */
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

	// what each name was decided to be, so a board of a hundred and thirty rows
	// decides each of them once and not once a click
	private final Map<String, String> sourceKinds = new LinkedHashMap<>();

	private String sourceKind(String name)
	{
		String known = sourceKinds.get(name);
		if (known != null)
		{
			return known;
		}
		String kind = decideKind(name);
		sourceKinds.put(name, kind);
		return kind;
	}

	private String decideKind(String name)
	{
		if (PICKPOCKETED.contains(name.toLowerCase(Locale.ROOT))
			|| skilledKeys().containsKey(letters(name)))
		{
			return KIND_SKILLING;
		}
		Map<String, Map<String, List<String>>> tax = taxonomy(plugin.gson());
		if (tax != null)
		{
			for (Map.Entry<String, Map<String, List<String>>> tab : tax.entrySet())
			{
				if (!tab.getValue().containsKey(name))
				{
					continue;
				}
				String t = tab.getKey().toLowerCase(Locale.ROOT);
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
		}
		String low = name.toLowerCase(Locale.ROOT);
		return has(low, OPENED) ? KIND_ACTIVITY
			: has(low, GATHERED) ? KIND_SKILLING : KIND_MONSTER;
	}

	/**
	 * The log's Other tab is the one it mixes: a rooftop course sits beside a
	 * demon. These are the pages of it that are something killed; the rest of the
	 * tab is ground a skill was trained on.
	 */
	private static final java.util.Set<String> MONSTER_PAGES = new java.util.HashSet<>(
		java.util.Arrays.asList("Champion's Challenge", "Chompy Bird Hunting",
			"Creature Creation", "Cyclopes", "Elder Chaos Druids", "Glough's Experiments",
			"Revenants", "Slayer", "Tormented Demons", "TzHaar"));

	// The ledger counts sources the log has no page for. Most are monsters, but
	// some are things opened and some are things caught, and neither belongs on
	// a kill board. These words are what say so.
	private static final String[] OPENED = {"chest", "casket", "clue scroll", "high gamble",
		"treasure trail"};
	private static final String[] GATHERED = {"impling", "salvage", "loot sack",
		"reward pool", "reward cart", "ent trunk", "offerings", "herbiboar", "bird nest"};

	private static boolean has(String low, String[] words)
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

	// The item a thing is known by: the dearest it has ever dropped, which is as
	// close to a portrait of it as this record keeps. Zero where the ledger never
	// saw it drop anything.
	private int signatureItem(String source)
	{
		Integer known = signatureItems.get(source);
		if (known != null)
		{
			return known;
		}
		// Dearest first, and the first of them the panel can actually draw wins.
		// Most of the ledger's bag came from the old cloud journal, which kept
		// item NAMES and no ids at all, so reading the dearest row's id straight
		// off returned zero for four sources in five: Zalcano's crystal tool seed,
		// Bloodveld's blood runes, the lot. A named row is looked up by name.
		String own = resolveSourceNamed(source);
		List<LocalStore.BagItem> bag = own == null ? new ArrayList<>()
			: new ArrayList<>(plugin.sourceItems(own));
		bag.sort((a, b) -> Long.compare(b.value, a.value));
		int best = 0;
		for (LocalStore.BagItem b : bag)
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
		// A nothing found before the item cache could name anything is a not-yet,
		// not an answer. Kept, it would pin the row to its fallback for the rest
		// of the session, which is the very retry the index above refuses to
		// spend.
		if (best > 0 || nameIndex() != null)
		{
			signatureItems.put(source, best);
		}
		return best;
	}

	/**
	 * The same portrait for a name the ledger never saw a drop from: an activity
	 * pays in points and reward crates, not in loot events, so its icon comes
	 * from the first item its collection log page lists that the item cache can
	 * name. Zero where the page is unknown or none of its items resolve, which
	 * is the case for a page whose every slot is untradeable.
	 */
	private int pagedItem(String page)
	{
		Map<String, Map<String, List<String>>> tax = taxonomy(plugin.gson());
		if (tax == null)
		{
			return 0;
		}
		for (Map<String, List<String>> pages : tax.values())
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

	// Every item the cache can price, by name. ItemManager.search is a substring
	// scan of the whole price list, so it answers the empty string with all of
	// it in one pass; asking it name by name walks four thousand items every
	// time, and a single source's bag can hold fifty names.
	private Map<String, Integer> itemsByName;

	/**
	 * An item id for an exact name, or zero for a name the cache cannot price,
	 * which is every untradeable. Only an outright match is taken: the search
	 * this is built from is a substring scan and would answer "Ore pack" for
	 * anything holding those two words.
	 */
	private int itemNamed(String name)
	{
		Map<String, Integer> index = nameIndex();
		if (index == null || name == null || name.isEmpty())
		{
			return 0;
		}
		Integer id = index.get(name.toLowerCase(Locale.ROOT));
		return id == null ? 0 : id;
	}

	/**
	 * The name index, or null while the client cannot build one. The item cache
	 * loads its prices over the network on a half-hourly schedule and starts out
	 * empty, so an empty answer is "not yet" and not "no such item": it is not
	 * kept, and the next build asks again.
	 */
	private Map<String, Integer> nameIndex()
	{
		if (itemsByName != null)
		{
			return itemsByName;
		}
		List<net.runelite.http.api.item.ItemPrice> all = plugin.items().search("");
		if (all == null || all.isEmpty())
		{
			return null;
		}
		Map<String, Integer> byName = new java.util.HashMap<>();
		for (net.runelite.http.api.item.ItemPrice price : all)
		{
			if (price.getName() != null)
			{
				byName.putIfAbsent(price.getName().toLowerCase(Locale.ROOT), price.getId());
			}
		}
		itemsByName = byName;
		return itemsByName;
	}

	/**
	 * One band of counted things: a heading that folds, and under it a line for
	 * each, with its count and what it paid. The first band of a facet carries
	 * the icon each thing is known by, since a reader knows a boss on sight; the
	 * second is plain, since it runs to hundreds and a list is what hundreds of
	 * anything wants to be.
	 */
	private void addKindBand(JPanel p, String kind, List<Map.Entry<String, Long>> rows,
		Map<String, Long> worth, Map<String, Long> loose, boolean withIcons)
	{
		if (rows.isEmpty())
		{
			return;
		}
		String stateKey = "history:kind:" + kind;
		boolean open = !foldOpen(stateKey);   // these stand open; the fold shuts them
		p.add(quietHead(kind, open ? "" : fmt(rows.size()), stateKey));
		if (!open)
		{
			p.add(vgap(4));
			return;
		}
		// These bands are the tab's own content rather than a detail under
		// something else, so they stand deeper than the lists a fold opens onto.
		Integer asked = histListShown.get(stateKey);
		int cap = asked == null ? BAND_CAP : asked;
		JPanel card = cardPlain();
		int mounted = 0;
		for (Map.Entry<String, Long> e : rows)
		{
			if (mounted++ >= cap)
			{
				break;
			}
			card.add(kindRow(e.getKey(), e.getValue(),
				paidFor(worth, loose, e.getKey()), withIcons));
		}
		addMore(card, stateKey, rows.size(), cap, false);
		p.add(card);
		p.add(vgap(6));
	}

	// How many rows a band stands before it holds the rest back, and the column an
	// icon band keeps for its icons.
	private static final int BAND_CAP = 12;
	private static final int ICON_W = 22;
	private static final int ICON_H = 18;

	/**
	 * One counted thing on one line: its name, its count, and what it paid over
	 * the period beside it. The count is the figure and the payment is the
	 * accent, so the two read as two things and not as one long number.
	 *
	 * <p>In an icon band the line carries the dearest thing the ledger ever saw
	 * it drop, which is as close to a portrait of it as this record keeps, drawn
	 * small. Every row in such a band keeps the column whether it fills it or
	 * not: a name starting further left than the one above it is exactly the
	 * ragged edge this tab is trying not to have.
	 */
	private JPanel kindRow(String name, long figure, long worth, boolean withIcon)
	{
		JPanel r = new JPanel(new BorderLayout(ROW_GAP, 0));
		r.setOpaque(false);
		r.setAlignmentX(Component.LEFT_ALIGNMENT);
		r.setBorder(BorderFactory.createEmptyBorder(1, ROW_INSET, 1, ROW_INSET));
		r.setToolTipText(name + ", " + fmt(figure)
			+ (worth > 0 ? " · " + fmt(worth) + " gp" : ""));

		JLabel named = new JLabel(name);
		named.setFont(FontManager.getRunescapeFont());
		r.add(named, BorderLayout.CENTER);

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
		JLabel count = new JLabel(fmt(figure));
		count.setFont(FontManager.getRunescapeFont());
		count.setForeground(ColorScheme.LIGHT_GRAY_COLOR.darker());
		figures.add(count);
		if (worth > 0)
		{
			figures.add(javax.swing.Box.createHorizontalStrut(6));
			JLabel paid = new JLabel(gp(worth));
			paid.setFont(FontManager.getRunescapeFont());
			paid.setForeground(accent());
			figures.add(paid);
		}
		r.add(figures, BorderLayout.EAST);

		// the line still drills, the way the kill boards always did
		r.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		r.addMouseListener(clicker(() -> openSourceLoose(name)));
		return r;
	}

	/**
	 * What a line wears, in the order the record can answer it.
	 *
	 * <p>First the thing itself: the dearest item the drop ledger ever saw it
	 * give up. Then, for a page the ledger never saw a drop from, the first item
	 * its collection log page lists. Then the skill it belongs to, which is what
	 * a thieving target and a rooftop course are really about. Last, the sprite
	 * of the facet it sits under, so a line is never a blank column.
	 */
	private void mountKindIcon(JLabel label, String name)
	{
		int item = signatureItem(name);
		if (item > 0)
		{
			mountItem(label, item);
			return;
		}
		net.runelite.api.Skill skill = skillOf(name);
		if (skill != null)
		{
			java.awt.image.BufferedImage img = skillIcon(skill);
			if (img != null)
			{
				dress(label, "skill:" + skill.name(), img, ICON_W, ICON_H);
				return;
			}
		}
		wearSprite(label, kindSprite(sourceKind(name)), ICON_W, ICON_H);
	}

	private final Map<Integer, List<JLabel>> itemWaiting = new LinkedHashMap<>();
	// The images already waited on, held by identity and weakly: the ItemManager
	// caches at most 128 of them and drops the rest, and an id remembered as
	// "already asked" after its image was dropped would never be asked again and
	// its row would stay blank for the session. An evicted image simply falls out
	// of here and the next build asks the new one, once.
	private final java.util.Set<AsyncBufferedImage> itemAsked =
		java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

	/**
	 * An item's image at the size a row wants, shrunk once and kept.
	 *
	 * <p>Asked for once per item, ever, the way the sprites are. An image the
	 * cache already holds does not answer {@code onLoaded} straight away: it
	 * hands the callback to the client thread, so a board of a dozen rows put a
	 * dozen tasks on that thread on every single rebuild, for icons it had
	 * already drawn. That is the queue that made the whole plugin heavy the
	 * first time. Here the scaled icon is kept and every later build just wears
	 * it.
	 */
	private void mountItem(JLabel label, int itemId)
	{
		javax.swing.ImageIcon have = scaledIcons.get("item:" + itemId);
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
		// the image lands on the client thread; the labels are dressed on the EDT
		img.onLoaded(() -> javax.swing.SwingUtilities.invokeLater(() ->
		{
			javax.swing.ImageIcon icon = fit(img, ICON_W, ICON_H);
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

	// The kind's own sprite, the last thing a line can wear: it says at least
	// what sort of thing the line is.
	private static int kindSprite(String kind)
	{
		if (KIND_ACTIVITY.equals(kind))
		{
			return 1053;   // SideiconsInterface.MINIGAMES
		}
		return KIND_SKILLING.equals(kind) ? 775 : 774;   // STATS, else COMBAT
	}

	/**
	 * The skill a counted name belongs to, or null where none does: the
	 * collection log's pages that are one skill's ground, which the log itself
	 * does not say, so they are named here.
	 *
	 * <p>The thieved and trapped names the record can answer for itself are not
	 * asked here. They are all filed under Skilling, which is the second band of
	 * its facet, and only the first band draws icons; asking would be a branch
	 * that can never be reached. {@link #skilledKeys} still decides what they are.
	 */
	private net.runelite.api.Skill skillOf(String name)
	{
		String skill = PAGE_SKILL.get(name);
		if (skill == null)
		{
			return null;
		}
		try
		{
			return net.runelite.api.Skill.valueOf(skill);
		}
		catch (IllegalArgumentException e)
		{
			return null;   // a skill this client's api does not carry yet
		}
	}

	/**
	 * The collection log page that is one skill's ground, and the skill. Only
	 * where a single skill honestly stands for the whole page: a minigame that
	 * is really a fight (Pest Control, Castle Wars, Soul Wars) is left out, since
	 * a wrong skill icon is worse than the generic one it would replace, and so
	 * are the treasure trails, which are a reward stream and not a skill.
	 */
	private static final Map<String, String> PAGE_SKILL = pageSkills();

	private static Map<String, String> pageSkills()
	{
		Map<String, String> m = new LinkedHashMap<>();
		m.put("Aerial Fishing", "FISHING");
		m.put("Barracuda Trials", "SAILING");
		m.put("Brimhaven Agility Arena", "AGILITY");
		m.put("Camdozaal", "MINING");
		m.put("Colossal Wyrm Agility", "AGILITY");
		m.put("Fishing Trawler", "FISHING");
		m.put("Forestry", "WOODCUTTING");
		m.put("Giants' Foundry", "SMITHING");
		m.put("Gnome Restaurant", "COOKING");
		m.put("Guardians of the Rift", "RUNECRAFT");
		m.put("Hallowed Sepulchre", "AGILITY");
		m.put("Hunter Guild", "HUNTER");
		m.put("Magic Training Arena", "MAGIC");
		m.put("Mahogany Homes", "CONSTRUCTION");
		m.put("Mastering Mixology", "HERBLORE");
		m.put("Motherlode Mine", "MINING");
		m.put("Ocean Encounters", "SAILING");
		m.put("Rogues' Den", "THIEVING");
		m.put("Rooftop Agility", "AGILITY");
		m.put("Sailing Miscellaneous", "SAILING");
		m.put("Sea Treasures", "SAILING");
		m.put("Shades of Mort'ton", "FIREMAKING");
		m.put("Shooting Stars", "MINING");
		m.put("Tithe Farm", "FARMING");
		m.put("Vale Totems", "FLETCHING");
		m.put("Volcanic Mine", "MINING");
		return m;
	}

	// Hiscores order. An ORDER only. The grid is built from the client's own
	// skill list, so a skill Jagex adds shows up without an edit here. Overall is
	// drawn separately, as its own headline.
	private static final String[] SKILL_ORDER_NAMES = {
		"ATTACK", "HITPOINTS", "MINING", "STRENGTH", "AGILITY", "SMITHING",
		"DEFENCE", "HERBLORE", "FISHING", "RANGED", "THIEVING", "COOKING",
		"PRAYER", "CRAFTING", "FIREMAKING", "MAGIC", "FLETCHING", "WOODCUTTING",
		"RUNECRAFT", "SLAYER", "FARMING", "CONSTRUCTION", "HUNTER",
	};

	// Every skill the client knows, in the order a player reads them.
	private static List<net.runelite.api.Skill> skillOrder()
	{
		List<net.runelite.api.Skill> out = new ArrayList<>();
		for (String name : SKILL_ORDER_NAMES)
		{
			try
			{
				out.add(net.runelite.api.Skill.valueOf(name));
			}
			catch (IllegalArgumentException dropped)
			{
				// a skill this client no longer has, simply not drawn
			}
		}
		for (net.runelite.api.Skill sk : net.runelite.api.Skill.values())
		{
			if (sk != net.runelite.api.Skill.OVERALL && !out.contains(sk))
			{
				out.add(sk);
			}
		}
		return out;
	}

	/**
	 * Where the skills stand when the period closes: the grid's own cells, the
	 * standing total beside them, and the closing state the period's movement
	 * is measured against.
	 *
	 * <p>The levels come from the closing state's xp, the way the site drew
	 * every period as a snapshot: the year 2022 shows the levels 2022 ended on.
	 * The state carries what the closing line itself omits, and a skill no
	 * complete line ever listed stands at level 1, so the total counts every
	 * skill in the game. Only a period that reaches today reads the live sheet
	 * instead: its closing line is the newest one, and the sheet is that same
	 * state a few minutes fresher. {@code standing} is the sheet's own overall
	 * where it has one, and the summed levels otherwise.
	 *
	 * <p>{@code closed} is the same levels read off the closing line alone,
	 * whatever the sheet says. A period's movement is measured against it and
	 * never against the sheet: the sheet stands a session past the closing
	 * line, and a delta taken from it reads that session's levels into a window
	 * that ended at the line, while the experience beside it stops there.
	 */
	private static final class SkillStand
	{
		final List<net.runelite.api.Skill> order;
		final List<String> keys;
		final Map<net.runelite.api.Skill, Long> levels;
		final long standing;
		final HistoryLog.Levels closed;

		SkillStand(List<net.runelite.api.Skill> order, List<String> keys,
			Map<net.runelite.api.Skill, Long> levels, long standing, HistoryLog.Levels closed)
		{
			this.order = order;
			this.keys = keys;
			this.levels = levels;
			this.standing = standing;
			this.closed = closed;
		}
	}

	/**
	 * A baseline standing at the given experience.
	 *
	 * <p>Marked complete, because the live sheet lists every skill: an incomplete
	 * baseline reads a skill it does not name as undrawn rather than as level one,
	 * and the total tile refuses to name an opening when the two ends disagree
	 * about how many skills were drawn.
	 */
	private static HistoryLog.Baseline baselineAt(Map<String, Long> xp)
	{
		HistoryLog.Baseline at = new HistoryLog.Baseline();
		at.skills.putAll(xp);
		at.complete = true;
		return at;
	}

	private SkillStand skillStand(HistoryLog.Baseline closing, boolean live)
	{
		Map<String, long[]> sheet = live ? plugin.skillSheet() : java.util.Collections.emptyMap();
		List<net.runelite.api.Skill> order = skillOrder();
		List<String> keys = new ArrayList<>();
		for (net.runelite.api.Skill sk : order)
		{
			keys.add(sk.name().toLowerCase(Locale.ROOT));
		}
		HistoryLog.Levels closed = HistoryLog.levels(closing, keys);
		Map<net.runelite.api.Skill, Long> levels =
			new java.util.EnumMap<>(net.runelite.api.Skill.class);
		long total = 0;
		for (net.runelite.api.Skill sk : order)
		{
			String key = sk.name().toLowerCase(Locale.ROOT);
			long[] cur = sheet.get(key);
			// The level the game names, for the total, which is the game's own
			// statistic and stops at 99.
			long level = cur != null && cur[0] > 0 ? cur[0] : closed.of.get(key);
			total += level;
			// And on the whole record, the level the experience has actually
			// reached. Only there: a period reports what MOVED, and a level past
			// 99 cannot move, so a virtual level on a period is a figure that
			// says nothing about the period it is drawn under. Lifetime is the
			// reading that is about where the account stands.
			long shown = wholeRecord() && cur != null && cur.length > 1 && cur[1] > 0
				? PaceBook.virtualLevelAt(cur[1]) : level;
			levels.put(sk, Math.max(level, shown));
		}
		long[] ov = sheet.get("overall");
		return new SkillStand(order, keys, levels,
			ov != null && ov[0] > 0 ? ov[0] : total, closed);
	}

	// The headline keys that read straight off the summary, in the order the
	// strip reads them. The drops line sits between them and carries the loot
	// value beside its count, one line for the pair.
	private static final String[] HEADLINE_KEYS = {"kills", "slayerTasksCompleted"};

	/**
	 * The figures a reader wants first, each a plain labelled row: what the
	 * period cost in time, what it added in experience, and the counts the rest
	 * of the tab breaks down. Only what the period holds.
	 *
	 * <p>The 99s are counted only while the same number of skills speak at each
	 * end of the window: a count taken across a hole one side alone has would
	 * read that hole as a gain.
	 */
	private JPanel headline(HistoryProgress progress, List<Map.Entry<String, Long>> gains,
		SkillStand stand, HistoryLog.Levels opened, long[] played)
	{
		// Named for what it IS: a card headed "The period" over a strip saying
		// "This session" is the panel using two words for one thing.
		JPanel card = card(sessionPeriod() ? "This sitting" : "The period");
		long xp = 0;
		for (Map.Entry<String, Long> g : gains)
		{
			xp += g.getValue();
		}
		// Skills and PvM each carry a fixed set, the same rows whatever the
		// period did. A figure reading zero is an answer; a row that vanishes
		// when it has nothing to say leaves a reader wondering if it was asked.
		if ("Skills".equals(histFacet) || "PvM".equals(histFacet))
		{
			HistoryLog.Levels shut = stand.closed;
			boolean same = shut.drawn == opened.drawn;
			if ("PvM".equals(histFacet))
			{
				card.add(row("Monsters slain", "+" + fmt(summaryValue(progress, "kills")), null));
				card.add(row("Deaths", "+" + fmt(summaryValue(progress, "deaths")), null));
				card.add(row("Slayer tasks completed",
					"+" + fmt(summaryValue(progress, "slayerTasksCompleted")), null));
				return card;
			}
			card.add(row("Time played", hoursMinutes(played[0]), null));
			// Not under the sitting, where the answer is one and saying so is the
			// card telling the reader what the heading above it already did.
			if (!sessionPeriod())
			{
				card.add(row("Sessions", fmt(played[1]), null));
			}
			card.add(row("Experience", "+" + gp(xp), xp > 0 ? accent() : null));
			card.add(row("99s reached",
				fmt(same ? Math.max(0, shut.nines - opened.nines) : 0), null));
			return card;
		}
		if (played[1] > 0)
		{
			card.add(row("Time played", hoursMinutes(played[0]), null));
			if (!sessionPeriod())
			{
				card.add(row("Sessions", fmt(played[1]), null));
			}
		}
		if (xp > 0)
		{
			card.add(row("Experience", "+" + gp(xp), accent()));
		}
		// What the period moved is measured line to line, the two states the
		// experience above was measured between; the sheet draws where the
		// account stands now and nothing else. The opening is named beside the
		// standing figure only where that figure is the close the movement was
		// measured to, so the three never read as an arithmetic that does not
		// reach its own end: a live sheet standing past the closing line draws
		// the standing and the movement, each as itself.
		// The total level is not here: it has a tile of its own under the skill
		// grid, where the whole sheet is already being read.
		HistoryLog.Levels closed = stand.closed;
		boolean paired = closed.drawn == opened.drawn;
		if (paired && closed.nines > opened.nines)
		{
			card.add(row("99s reached", fmt(closed.nines - opened.nines), null));
		}
		for (String key : HEADLINE_KEYS)
		{
			HistoryProgress.Row r = summaryRow(progress, key);
			if (r != null)
			{
				card.add(row(r.label(), "+" + figure(r), null));
			}
		}
		// the drops and what they were worth, one line: the count is the kills
		// that dropped something and the value is what those drops came to
		HistoryProgress.Row drops = summaryRow(progress, "dropsReceived");
		HistoryProgress.Row value = summaryRow(progress, "lootValue");
		if (drops != null)
		{
			// no unit on the pair: "+64,298 · 488.2M gp" wants 212px of a 209px
			// row and took it out of the name, which then read "Drops recei..."
			card.add(row(drops.label(), "+" + fmt(drops.value())
				+ (value != null ? " · " + gp(value.value()) : ""), null));
		}
		else if (value != null)
		{
			card.add(row(value.label(), "+" + figure(value), null));
		}
		HistoryProgress.Row deaths = summaryRow(progress, "deaths");
		if (deaths != null)
		{
			card.add(row(deaths.label(), "+" + figure(deaths), null));
		}
		return card;
	}

	// One summary figure, or zero where the period did not move it.
	private static long summaryValue(HistoryProgress progress, String key)
	{
		HistoryProgress.Row r = summaryRow(progress, key);
		return r == null ? 0 : r.value();
	}

	// One summary figure by key, or null when the period did not move it.
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

	private static String hoursMinutes(long minutes)
	{
		return minutes >= 60 ? (minutes / 60) + "h " + (minutes % 60) + "m" : minutes + "m";
	}

	// The hiscores grid: every skill's level at the period's close and the
	// period's gain. A skill that didn't move keeps its place and says nothing.
	// The headline above it carries the totals.
	private void addSkillGrid(JPanel p, List<Map.Entry<String, Long>> gains, SkillStand stand,
		HistoryLog.Levels opened)
	{
		Map<String, Long> gain = new LinkedHashMap<>();
		for (Map.Entry<String, Long> g : gains)
		{
			gain.put(g.getKey(), g.getValue());
		}
		List<net.runelite.api.Skill> order = stand.order;
		Map<net.runelite.api.Skill, Long> levels = stand.levels;

		// Three across, which is the shape the sheet is read in. The skill's own
		// name will not fit beside its icon at 62px, and the icon is what a
		// reader looks for anyway.
		JPanel grid = new JPanel(new GridLayout(0, 3, 2, 2));
		grid.setBackground(ColorScheme.DARK_GRAY_COLOR);
		grid.setAlignmentX(Component.LEFT_ALIGNMENT);
		for (net.runelite.api.Skill sk : order)
		{
			String key = sk.name().toLowerCase(Locale.ROOT);
			Integer was = opened == null ? null
				: opened.virtual.getOrDefault(key, opened.of.get(key));
			Long from = was == null ? null : Long.valueOf(was.longValue());
			grid.add(skillCell(sk, levels.get(sk), gain.get(key), from));
		}
		p.add(grid);
		p.add(vgap(3));
		// Combat beside Total, the way the game's own panel puts them, and each
		// carrying the reading that belongs to it: the period on the total, the
		// combat counters on the combat level.
		// Side by side while the total is one figure. On a period it becomes
		// three - where the level opened, where it closed, and what moved - and
		// half of a 242 pixel column cannot hold that beside a name, so the two
		// tiles take a row each instead of overlapping in one.
		JPanel combat = combatLevelTile(gain);
		JPanel total = totalLevelTile(stand, opened);
		if (wholeRecord())
		{
			JPanel levels2 = new JPanel(new GridLayout(1, 2, 2, 2));
			levels2.setBackground(ColorScheme.DARK_GRAY_COLOR);
			levels2.setAlignmentX(Component.LEFT_ALIGNMENT);
			levels2.add(combat);
			levels2.add(total);
			p.add(levels2);
		}
		else
		{
			p.add(combat);
			p.add(vgap(2));
			p.add(total);
		}
		p.add(vgap(6));
	}

	/** The combat level, wearing a handful of the combat counters on hover. */
	private JPanel combatLevelTile(Map<String, Long> gain)
	{
		JPanel cell = new JPanel(new BorderLayout(3, 0));
		cell.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		cell.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
		cell.setAlignmentX(Component.LEFT_ALIGNMENT);
		cell.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
		JLabel name = new JLabel("Combat");
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(ColorScheme.LIGHT_GRAY_COLOR.darker());
		cell.add(name, BorderLayout.CENTER);
		int cb = plugin.combatLevel();
		JLabel fig = new JLabel(cb > 0 ? fmt(cb) : "-", JLabel.RIGHT);
		fig.setFont(FontManager.getRunescapeSmallFont());
		// Dim unless the period moved it. A combat level is a function of seven
		// skills, and the record keeps no dated copy of it, so what is asked is
		// whether any of those seven gained: they are the only things that can
		// move it, and none of them moving means it did not.
		fig.setForeground(cb > 0 && (wholeRecord() || combatSkillsMoved(gain))
			? TILE_LIT : ColorScheme.LIGHT_GRAY_COLOR.darker());
		cell.add(fig, BorderLayout.EAST);
		// This tile is the way in to the combat achievements, which is what a
		// reader means when they click the word Combat on a sheet of levels.
		// There was an activity tile doing the job instead, sitting among the
		// clue scrolls and the rifts as though it were one of them.
		Map<String, Long> c = counters();
		long[] ca = combatStanding();
		cell.setToolTipText(tip("Combat",
			new String[]{"Achievement points", "Tiers unlocked", "Damage dealt",
				"Highest hit"},
			new String[]{
				ca[1] > 0 ? fmt(ca[0]) + " / " + fmt(ca[1]) : fmt(ca[0]),
				fmt(ca[2]) + " / 6",
				fmt(c.getOrDefault(chronicle.counters.StatKeys.DAMAGE_DEALT, 0L)),
				fmt(c.getOrDefault(chronicle.counters.StatKeys.HIGHEST_HIT, 0L))}));
		cell.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		cell.addMouseListener(clicker(() ->
		{
			sheetPage = "combat";
			rebuild();
		}));
		return cell;
	}

	// Which kinds of feed line landed inside the window. Answered once a build:
	// three tiles ask, and each ask is a walk of the feed.
	private java.util.Set<String> movedTypes;

	/** Whether the window holds a line of this kind. Lifetime holds everything. */
	private boolean stirred(String type)
	{
		if (wholeRecord())
		{
			return true;
		}
		if (movedTypes == null)
		{
			movedTypes = new java.util.HashSet<>();
			for (JsonObject e : plugin.feedNewest(4000))
			{
				if (insideWindow(safeLong(e.get("ts"))))
				{
					movedTypes.add(typeOf(e));
				}
			}
		}
		return movedTypes.contains(type);
	}

	/**
	 * Whether the period moved what an activity tile counts.
	 *
	 * <p>The tiles carry a STANDING - every clue ever opened, the whole
	 * collection log - because that is what they are the way in to. Standing
	 * still under a heading that names a week reads as the week's work, so a
	 * tile the period never touched is dimmed, the same way a skill that gained
	 * nothing is. Lifetime moved everything, by construction.
	 */
	private boolean activityStirred(String label, String source)
	{
		if (wholeRecord())
		{
			return true;
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
			// the caskets are loot sources, one per tier, so the roll knows
			for (String tier : CLUE_TIERS)
			{
				Long n = rolledKills("Clue Scroll (" + tier + ")");
				if (n != null && n > 0)
				{
					return true;
				}
			}
			return false;
		}
		if (!source.isEmpty())
		{
			Long n = rolledKills(source);
			return n != null && n > 0;
		}
		return false;
	}

	// countersForPeriod walks the spine or the sitting; the skill grid asks it
	// once per cell, which is twenty three times a build.
	private Map<String, Long> buildPeriodCounters;
	private boolean periodCountersAsked;

	private Map<String, Long> periodCounters()
	{
		if (!periodCountersAsked)
		{
			periodCountersAsked = true;
			buildPeriodCounters = countersForPeriod();
		}
		return buildPeriodCounters == null ? java.util.Collections.emptyMap()
			: buildPeriodCounters;
	}

	/**
	 * The handful of counters that say how a craft is going, as a hover card.
	 *
	 * <p>The card used to carry the level and the gain, which are the two things
	 * the cell already draws without being hovered. These are what the cell
	 * cannot say: logs chopped, fish caught, food cooked and food burned. The
	 * whole list is a click away, which the last line says.
	 */
	private String skillTip(String craft, long level, Long gained)
	{
		List<String> labels = new ArrayList<>();
		List<String> figures = new ArrayList<>();
		labels.add("Level");
		figures.add(fmt(level));
		if (gained != null)
		{
			labels.add(wholeRecord() ? "Experience" : "Gained");
			figures.add((wholeRecord() ? "" : "+") + xpShort(gained));
		}
		Map<String, Long> now = periodCounters();
		for (String key : StatRegistry.headlines(craft))
		{
			Long v = now.get(key);
			if (v == null || v <= 0)
			{
				continue;
			}
			labels.add(StatRegistry.rowLabel(key));
			figures.add(fmt(v));
			if (labels.size() >= 6)
			{
				break;
			}
		}
		return tip(craft, labels.toArray(new String[0]),
			figures.toArray(new String[0]));
	}

	/**
	 * Slayer's card, which is not a list of counters: the skill has a whole board
	 * of its own and this is the way in to it.
	 */
	private String slayerTip(long level, Long gained)
	{
		long[] ms = windowMs();
		long[] tally = plugin.onTaskTally(ms[0], ms[1], null, wholeRecord());
		long paid = 0;
		for (LocalStore.BagItem b : plugin.onTaskLoot(ms[0], ms[1], null, wholeRecord()))
		{
			paid += b.value;
		}
		List<String> labels = new ArrayList<>();
		List<String> figures = new ArrayList<>();
		labels.add("Level");
		figures.add(fmt(level));
		if (gained != null)
		{
			labels.add(wholeRecord() ? "Experience" : "Gained");
			figures.add((wholeRecord() ? "" : "+") + xpShort(gained));
		}
		labels.add("Tasks tracked");
		figures.add(fmt(tally[2]));
		labels.add("Kills on task");
		figures.add(fmt(tally[0]));
		labels.add("On-task loot");
		figures.add(gp(paid) + " gp");
		return tip("Slayer", labels.toArray(new String[0]),
			figures.toArray(new String[0]));
	}

	// The seven a combat level is worked out from.
	private static final net.runelite.api.Skill[] COMBAT_SKILLS = {
		net.runelite.api.Skill.ATTACK, net.runelite.api.Skill.STRENGTH,
		net.runelite.api.Skill.DEFENCE, net.runelite.api.Skill.HITPOINTS,
		net.runelite.api.Skill.RANGED, net.runelite.api.Skill.MAGIC,
		net.runelite.api.Skill.PRAYER};

	/**
	 * Whether this period could have moved the combat level.
	 *
	 * <p>Not whether it DID: the record keeps no dated combat level, and the two
	 * ends of a period would have to be worked out from seven experience figures
	 * apiece. Gaining combat experience and not gaining a level is the common
	 * case, so this lights the tile a little more often than it strictly should;
	 * the alternative is leaving it lit always, which is the thing being fixed.
	 */
	private static boolean combatSkillsMoved(Map<String, Long> gain)
	{
		for (net.runelite.api.Skill sk : COMBAT_SKILLS)
		{
			Long g = gain.get(sk.name().toLowerCase(Locale.ROOT));
			if (g != null && g > 0)
			{
				return true;
			}
		}
		return false;
	}

	private JsonObject buildAchievements;

	/** Read once a build: three tiles and up to three boards all ask for it. */
	private JsonObject achievements()
	{
		if (buildAchievements == null)
		{
			buildAchievements = plugin.achievements();
		}
		return buildAchievements;
	}

	/** tiers done, tiers there are, regions finished, regions. */
	private long[] diaryStanding()
	{
		JsonObject d = achievements().has("diaries")
			&& achievements().get("diaries").isJsonObject()
			? achievements().getAsJsonObject("diaries") : new JsonObject();
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

	/**
	 * Every combat achievement point there is, per the bundled table.
	 *
	 * <p>Only ever the fallback. The game states its own total on each completion
	 * and that total moves with each release, so where the journal has witnessed
	 * one it is the authority and this is not consulted.
	 */
	private long bundledPoints()
	{
		bundledCombat = bundle(plugin.gson(), "osrs_combat_achievements.json", bundledCombat);
		if (!bundledCombat.has("_meta") || !bundledCombat.get("_meta").isJsonObject())
		{
			return 0;
		}
		JsonObject meta = bundledCombat.getAsJsonObject("_meta");
		if (!meta.has("totals") || !meta.get("totals").isJsonObject())
		{
			return 0;
		}
		JsonObject totals = meta.getAsJsonObject("totals");
		return totals.has("points") ? safeLong(totals.get("points")) : 0;
	}

	/**
	 * points, points there are, tiers unlocked, completions seen by name.
	 *
	 * <p>The total comes from the GAME where the journal has witnessed a combat
	 * achievement, because the game states its own total on every one of them and
	 * tasks are added between releases: a recent event says 2,624 where the
	 * bundled table says 2,697. The table is the fallback, not the authority.
	 */
	private long[] combatStanding()
	{
		JsonObject c = achievements().has("combat")
			&& achievements().get("combat").isJsonObject()
			? achievements().getAsJsonObject("combat") : new JsonObject();
		long points = c.has("points") ? c.get("points").getAsLong() : 0;
		long tiers = 0;
		if (c.has("tiers") && c.get("tiers").isJsonObject())
		{
			for (String k : c.getAsJsonObject("tiers").keySet())
			{
				if (c.getAsJsonObject("tiers").get(k).getAsLong() > 0)
				{
					tiers++;
				}
			}
		}
		long possible = 0;
		long seen = 0;
		for (JsonObject e : plugin.feedNewest(4000))
		{
			if (!"COMBAT_ACHIEVEMENT".equals(str(e, "type", "")))
			{
				continue;
			}
			seen++;
			JsonObject data = e.has("data") && e.get("data").isJsonObject()
				? e.getAsJsonObject("data") : null;
			if (possible == 0 && data != null && data.has("totalPossiblePoints"))
			{
				possible = data.get("totalPossiblePoints").getAsLong();
			}
		}
		if (possible == 0)
		{
			// The fallback the javadoc above has always promised, which until now
			// was a self-assignment: an account that has never had a combat
			// achievement land while Chronicle was watching had no denominator at
			// all and read as a bare number of points. The table's total is a
			// release behind the game's own, which is why the game wins where it
			// has spoken, but a figure one release out is a better answer than no
			// figure.
			possible = bundledPoints();
		}
		return new long[]{points, possible, tiers, seen};
	}

	/**
	 * The five pages the sheet's activity tiles open. Each starts with a back row,
	 * because a reader was sent here rather than having drilled in.
	 */
	private JPanel buildSheetPage()
	{
		if ("log".equals(sheetPage))
		{
			JPanel p = column();
			p.add(backRow());
			p.add(vgap(4));
			p.add(buildLog());
			return p;
		}
		JPanel p = column();
		p.add(backRow());
		p.add(vgap(4));
		if ("clues".equals(sheetPage))
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

	// Takes the injected Gson rather than making one, as bossRoster and taxonomy
	// beside it do. A fresh Gson is on the Plugin Hub's disallowed list and is
	// what its packager rejects a plugin for, and this one wore a fully qualified
	// name, so it did not answer a grep for "new Gson()".
	private static JsonObject bundle(com.google.gson.Gson gson, String name,
		JsonObject cached)
	{
		if (cached != null)
		{
			return cached;
		}
		try (java.io.InputStreamReader r = new java.io.InputStreamReader(
			ChroniclePanel.class.getResourceAsStream("/chronicle/" + name),
			java.nio.charset.StandardCharsets.UTF_8))
		{
			return gson.fromJson(r, JsonObject.class);
		}
		catch (Exception e)
		{
			return new JsonObject();
		}
	}

	/**
	 * The clue tiers and what each one paid.
	 *
	 * <p>Chronicle has no clue COMPLETION count: it knows the caskets it watched
	 * open, because a casket is a loot source like any other. So a tier's figure is
	 * caskets opened and their worth, and each one opens its own source page with
	 * the items inside.
	 */
	private void buildClues(JPanel p)
	{
		long all = 0;
		long allWorth = 0;
		List<LocalStore.SourceRow> mine = new ArrayList<>();
		for (String tier : CLUE_TIERS)
		{
			for (LocalStore.SourceRow r : sources())
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
		head.add(row("Worth", gp(allWorth) + " gp", accent()));
		head.add(row("Tiers seen", fmt(mine.size()) + " / " + CLUE_TIERS.length, null));
		p.add(head);
		p.add(vgap(6));
		if (mine.isEmpty())
		{
			p.add(note("No clue casket has been opened while Chronicle was watching."));
			return;
		}
		p.add(group("BY TIER"));
		for (String tier : CLUE_TIERS)
		{
			LocalStore.SourceRow r = null;
			for (LocalStore.SourceRow s : mine)
			{
				if (s.name.equalsIgnoreCase("Clue Scroll (" + tier + ")"))
				{
					r = s;
					break;
				}
			}
			if (r == null)
			{
				// Dimmed, like an unfinished diary tier and an undone combat task.
				// The dash alone left a tier nobody has ever opened reading exactly
				// as bright as one they have.
				p.add(row(tier, "-", ColorScheme.LIGHT_GRAY_COLOR.darker(), true));
				continue;
			}
			long n = Math.max(r.kc, r.loots);
			JPanel line = row(tier, fmt(n) + " \u00b7 " + gp(r.value) + " gp", accent());
			line.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
			final String open = r.name;
			line.addMouseListener(clicker(() -> openSource(open)));
			line.setToolTipText(tip(tier + " clues",
				new String[]{"Caskets", "Worth", "Each"},
				new String[]{fmt(n), gp(r.value) + " gp",
					n > 0 ? gp(r.value / n) + " gp" : "-"}));
			p.add(line);
		}
	}

	/**
	 * The combat achievement task ids the game says are done.
	 *
	 * <p>Captured as a plain list of ids rather than as words, so the journal
	 * carries the smallest thing that can answer "which" and the bundled table
	 * supplies the rest. Empty for a journal written before this was captured,
	 * which the board has to handle rather than read as "nothing done".
	 */
	private java.util.Set<Integer> caDone()
	{
		java.util.Set<Integer> out = new java.util.HashSet<>();
		JsonObject c = achievements().has("combat")
			&& achievements().get("combat").isJsonObject()
			? achievements().getAsJsonObject("combat") : null;
		if (c == null || !c.has("tasksDone") || !c.get("tasksDone").isJsonArray())
		{
			return out;
		}
		for (com.google.gson.JsonElement e : c.getAsJsonArray("tasksDone"))
		{
			try
			{
				out.add(e.getAsInt());
			}
			catch (RuntimeException ignored)
			{
				// a non-numeric id is not an id
			}
		}
		return out;
	}

	private void buildQuests(JPanel p)
	{
		JsonObject q = achievements().has("quests")
			&& achievements().get("quests").isJsonObject()
			? achievements().getAsJsonObject("quests") : new JsonObject();
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
		head.add(row("In progress", fmt(going.size()), null));
		head.add(row("Not started", fmt(not.size()), null));
		p.add(head);
		p.add(vgap(6));
		// What is under way first, and open; what is finished and what has not
		// been started are folded. Two hundred and thirteen quests drawn flat is
		// a wall however it is sorted, and of the three lists the one a reader
		// wants on opening the page is the four they are in the middle of.
		addNames(p, "IN PROGRESS", going, true, true);
		addNames(p, "COMPLETE", done, true, false);
		addNames(p, "NOT STARTED", not, false, false);
	}

	/**
	 * A named group of quests, marked the way every other board marks presence.
	 *
	 * <p>The heading used to be the only thing that said which state these were
	 * in. There are over a hundred and fifty quests, with no cap and no fold, so
	 * the heading scrolls off and leaves a wall of names that no longer say
	 * anything about themselves.
	 */
	private void addNames(JPanel p, String heading, List<String> names, boolean held,
		boolean openByDefault)
	{
		if (names.isEmpty())
		{
			return;
		}
		java.util.Collections.sort(names);
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
			p.add(row(n, "", held ? null : ColorScheme.LIGHT_GRAY_COLOR.darker(), !held));
		}
		p.add(vgap(4));
	}

	private void buildDiaries(JPanel p)
	{
		bundledDiaries = bundle(plugin.gson(), "osrs_achievement_diaries.json", bundledDiaries);
		JsonObject tasks = bundledDiaries.has("diaries")
			? bundledDiaries.getAsJsonObject("diaries") : new JsonObject();
		JsonObject mine = achievements().has("diaries")
			&& achievements().get("diaries").isJsonObject()
			? achievements().getAsJsonObject("diaries") : new JsonObject();
		// A journal written before the diaries were captured, or one belonging to an
		// account that has not finished a login yet, carries no tiers at all. That
		// is not the same as having finished none of them, and the difference is
		// the whole board: unguarded, every tier dims and the head reads 0 / 0, so
		// a new install greets its owner by reporting that they have done nothing.
		boolean known = mine.size() > 0;
		JPanel head = card("Achievement diaries");
		if (known)
		{
			long[] d = diaryStanding();
			head.add(row("Tiers done", d[0] + " / " + d[1], accent()));
			head.add(row("Regions finished", fmt(d[2]) + " / " + fmt(d[3]), null));
		}
		p.add(head);
		p.add(vgap(6));
		if (!known)
		{
			p.add(note("Which tiers you have finished arrives when you next log in. "
				+ "Until then this is what each one asks for."));
			p.add(vgap(4));
		}
		// The game states which TIERS are done and never which tasks, so a tier is
		// ticked or it is not, and the tasks under it are what it asks for rather
		// than a checklist of what is left.
		for (String region : tasks.keySet())
		{
			JsonObject tiers = tasks.getAsJsonObject(region);
			String key = region.toLowerCase(Locale.ROOT);
			JsonObject held = null;
			for (String k : mine.keySet())
			{
				if (k.equalsIgnoreCase(key) || key.startsWith(k.toLowerCase(Locale.ROOT)))
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
				// Done is carried by brightness here too, so the count can stay on
				// every row: a finished tier used to say "done" and take its own
				// task count away with it, and it was the one marker in the panel
				// that was a word rather than a weight.
				JPanel line = row(tier.substring(0, 1).toUpperCase(Locale.ROOT)
						+ tier.substring(1),
					fmt(n) + " tasks",
					known && !got ? ColorScheme.LIGHT_GRAY_COLOR.darker() : null,
					known && !got);
				// Hover, not click: the tasks are in the tooltip and there is no
				// board underneath this to open. It carried a hand cursor for a
				// click that was never wired.
				line.setToolTipText(taskTip(region + " " + tier,
					tiers.getAsJsonArray(tier)));
				p.add(line);
			}
			p.add(vgap(4));
		}
	}

	/** "grandmaster" as the game writes it. */
	private static String prettyTier(String tier)
	{
		return tier == null || tier.isEmpty() ? ""
			: Character.toUpperCase(tier.charAt(0)) + tier.substring(1);
	}

	/** A tier's tasks, as the markup a tooltip takes. Capped so it stays readable. */
	private static String taskTip(String title, com.google.gson.JsonArray tasks)
	{
		// Each task now carries what it needs under it, so the same popup holds
		// half as many of them before it runs off the panel.
		final int CAP = 8;
		StringBuilder sb = new StringBuilder("<html><body style='padding:2px'>");
		sb.append("<div style='color:#8f8f8f'>").append(title).append("</div>");
		for (int i = 0; i < tasks.size() && i < CAP; i++)
		{
			JsonObject t = tasks.get(i).getAsJsonObject();
			String task = t.get("task").getAsString();
			sb.append("<div>").append(task.length() > 78 ? task.substring(0, 78) + "..." : task)
				.append("</div>");
			// What the task NEEDS, which is the half a reader is actually weighing
			// when they hover a tier they have not done. The bundle carries it for
			// every one of the 492 tasks and the search row already shows it.
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
		head.add(row("Tiers unlocked", fmt(c[2]) + " / 6", null));
		java.util.Set<Integer> headDone = caDone();
		// The game reports ids; the table names them. Those two populations agree
		// today and stop agreeing the first time Jagex adds a combat achievement,
		// because the varps already carry 672 slots and this jar's table will still
		// hold 655 names for as long as it takes an update to reach the Hub. So the
		// fraction counts only what the table can account for, and the ids it
		// cannot name are said out loud rather than folded into a numerator that
		// would read "661 / 655" over tiers summing to 655.
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
		p.add(head);
		p.add(vgap(6));
		if (unnamed > 0)
		{
			p.add(note("You have also done " + fmt(unnamed) + " combat achievement"
				+ (unnamed == 1 ? "" : "s") + " added to the game since this copy of"
				+ " Chronicle was built. They are counted by the game, not named"
				+ " here, until the plugin updates."));
			p.add(vgap(4));
		}
		// Which tasks are DONE, from the game's own per-task bits rather than from
		// the handful this journal happened to watch land. Empty on a journal
		// written before those bits were captured, and the board then says what each
		// tier asks for rather than pretending nothing is done.
		java.util.Set<Integer> done = caDone();
		boolean known = !done.isEmpty();
		if (!known)
		{
			// Said, not implied. Without it the board draws every task in the same
			// colour and a reader with no reason to think otherwise reads that as
			// an answer rather than as the absence of one.
			p.add(note("Which tasks you have done arrives when you next log in. "
				+ "Until then this is what each tier asks for."));
			p.add(vgap(4));
		}
		// Filed by what they are fought against, not by tier. Six tiers meant one
		// of them was a hundred and seventy three rows to mount the moment it was
		// opened; ninety one sources with six tasks apiece is a list a reader can
		// hold and a fold that costs nothing to open. Each row still names its
		// own tier, so nothing about them is lost.
		java.util.Map<String, java.util.List<JsonObject>> bySource = new java.util.TreeMap<>(
			String.CASE_INSENSITIVE_ORDER);
		for (String id : all.keySet())
		{
			JsonObject task = all.getAsJsonObject(id).deepCopy();
			// the table is KEYED by the game's task id and the rows do not carry it
			task.addProperty("id", Integer.parseInt(id));
			bySource.computeIfAbsent(caSource(task.get("monster").getAsString()),
				k -> new ArrayList<>()).add(task);
		}
		for (Map.Entry<String, java.util.List<JsonObject>> e : bySource.entrySet())
		{
			if (e.getValue().isEmpty())
			{
				continue;
			}
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
			// quietHead, so these read as the same kind of fold as every other one
			// in the panel rather than as a board with its own rules.
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
				// Green done, red not, after the collection log and after the log
				// in the game. Where the game has not said which tasks are done
				// the rows take neither colour: an undone task and a task nobody
				// has asked about look nothing alike, and saying so in red would
				// be six hundred assertions this board cannot make.
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

	/**
	 * A task's name with the heading it already sits under taken off the front.
	 *
	 * <p>Under CHAMBERS OF XERIC, "Chambers of Xeric Veteran" is "Veteran": the
	 * heading said the rest of it, and the name column is about a hundred and
	 * forty pixels wide, so the repeated half was pushing the part that
	 * identifies the task off the end behind an ellipsis. Names that do not
	 * carry the prefix, and the one that IS the prefix, are left alone - a row
	 * reading nothing at all would be worse than a long one.
	 */
	private static String withoutSource(String name, String source)
	{
		if (name == null || source == null || name.length() <= source.length()
			|| !name.regionMatches(true, 0, source, 0, source.length()))
		{
			return name;
		}
		String rest = name.substring(source.length()).trim();
		// a name that differed from its source only by punctuation
		return rest.isEmpty() ? name : rest;
	}

	/**
	 * What a combat achievement is filed under.
	 *
	 * <p>The wiki answers "N/A" for the handful of tasks that name no monster,
	 * which is an answer to a question the reader did not ask. One task has it
	 * today - deal a hundred damage with thralls, which is done wherever you
	 * like - and it drew a heading reading N/A in a list of bosses.
	 */
	private static String caSource(String monster)
	{
		return monster == null || monster.trim().isEmpty()
			|| "N/A".equalsIgnoreCase(monster.trim()) ? "Anywhere" : monster;
	}

	/** The head card's four figures, as the markup a tooltip takes. */
	private String periodTip(long[] played, List<Map.Entry<String, Long>> gains)
	{
		long xp = 0;
		for (Map.Entry<String, Long> g : gains)
		{
			xp += g.getValue();
		}
		return tip(sessionPeriod() ? "This sitting"
			: wholeRecord() ? "Lifetime" : "The period",
			new String[]{"Time played", "Sessions", "Experience"},
			new String[]{hoursMinutes(played[0]), fmt(played[1]), "+" + gp(xp)});
	}

	/**
	 * A hover card in the shape RuneLite's own hiscores panel draws: a titled
	 * block of label and figure rows. That panel builds its with setToolTipText
	 * and HTML, which is the idiom a player has already met, and it costs a board
	 * nothing at rest because the popup overflows the panel rather than reserving
	 * room inside it.
	 */
	private static String tip(String title, String[] labels, String[] figures)
	{
		StringBuilder sb = new StringBuilder("<html><body style='padding:2px'>");
		sb.append("<div style='color:#8f8f8f'>").append(title).append("</div>");
		for (int i = 0; i < labels.length && i < figures.length; i++)
		{
			sb.append("<div>").append(labels[i]).append(": <span style='color:#c8a25a'>")
				.append(figures[i]).append("</span></div>");
		}
		return sb.append("</body></html>").toString();
	}

	/**
	 * The whole sheet in one tile, under the grid rather than in it: with an odd
	 * number of skills a twenty fifth cell would sit alone in a half empty row.
	 */
	private JPanel totalLevelTile(SkillStand stand, HistoryLog.Levels opened)
	{
		// The same reading the headline gave it: an opening is named beside the
		// close only where that close is the figure the movement was measured
		// to, so the three never read as an arithmetic that does not reach its
		// own end, and ends that drew different skills are not compared at all.
		HistoryLog.Levels shut = stand.closed;
		// A lifetime encompasses everything that came before, so it says where the
		// sheet stands and nothing more. Counting the levels up from one and
		// calling it a gain is arithmetic nobody asked for.
		boolean paired = !wholeRecord() && opened != null && shut.drawn == opened.drawn;
		long levels = paired ? shut.total - opened.total : 0;
		String figure = fmt(stand.standing);
		if (levels > 0)
		{
			figure = (stand.standing == shut.total
				? fmt(opened.total) + " to " + figure : figure) + " · +" + fmt(levels);
		}
		JPanel cell = new JPanel(new BorderLayout(3, 0));
		cell.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		cell.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
		cell.setAlignmentX(Component.LEFT_ALIGNMENT);
		cell.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));

		// CENTER, not WEST. BorderLayout gives WEST and EAST each their preferred
		// width and lets them overlap when the row is narrower than the two of
		// them; CENTER takes what is left. On a period the figure grows from
		// "2,235" to "2,231 to 2,235 - +2" and the two were drawn on top of one
		// another, which is how "Total level" came out as T2a2l3ke2t5o1t2a2l.
		JLabel name = new JLabel("Total level");
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(ColorScheme.LIGHT_GRAY_COLOR.darker());
		cell.add(name, BorderLayout.CENTER);

		if (periodTip != null)
		{
			cell.setToolTipText(periodTip);
		}
		JLabel fig = new JLabel(figure, JLabel.RIGHT);
		fig.setFont(FontManager.getRunescapeSmallFont());
		// The same rule the skills above it and the tiles below it follow: a
		// standing that this period did not move reads dim. A lifetime moved all
		// of it, so it is never dimmed there.
		fig.setForeground(levels > 0 ? accent()
			: wholeRecord() ? Color.WHITE : ColorScheme.LIGHT_GRAY_COLOR.darker());
		cell.add(fig, BorderLayout.EAST);
		return cell;
	}

	// One skill: its icon, where it began and where it ended, its name, and the
	// period's gain.
	private JPanel skillCell(net.runelite.api.Skill sk, long level, Long gained, Long from)
	{
		JPanel cell = new JPanel(new BorderLayout(3, 0));
		cell.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		cell.setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 4));
		final String craft = StatRegistry.prettify(sk.name().toLowerCase(Locale.ROOT));
		// The same hover card the boss and activity tiles draw. This was the last
		// tile on the sheet answering in a sentence while the two grids under it
		// answered in a titled block.
		boolean slayer = net.runelite.api.Skill.SLAYER.equals(sk);
		cell.setToolTipText(slayer ? slayerTip(level, gained)
			: skillTip(craft, level, gained));
		// The cell has always carried a tooltip, which is a mouse listener; this
		// is what makes the hand cursor honest. Its counters had no other way in.
		cell.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		// Slayer opens the board it has rather than a drill of its counters: the
		// tasks, what each paid and the kills on them are a whole view already,
		// and a card of slayer counters beside it would be the lesser half.
		cell.addMouseListener(clicker(slayer ? () -> applyTab(View.SLAYER)
			: () -> openSkill(craft)));

		JLabel icon = new JLabel();
		java.awt.image.BufferedImage img = skillIcon(sk);
		if (img != null)
		{
			icon.setIcon(new javax.swing.ImageIcon(img));
		}
		else
		{
			// No sprite cache: the skill's first letters, or the grid is nameless
			// numbers.
			icon.setText(sk.name().substring(0, Math.min(3, sk.name().length())));
			icon.setFont(FontManager.getRunescapeSmallFont());
			icon.setForeground(ColorScheme.LIGHT_GRAY_COLOR.darker());
		}
		cell.add(icon, BorderLayout.WEST);

		JPanel text = new JPanel(new GridLayout(gained != null ? 2 : 1, 1));
		text.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		// Where it began and where it ended, when it moved between them. A
		// lifetime says only where it stands: everything came before it, so
		// naming the start tells a reader what they already assumed.
		boolean climbed = from != null && level > from && !wholeRecord();
		JLabel lvl = new JLabel(level <= 0 ? "-"
			: climbed ? fmt(from) + " to " + fmt(level) : String.valueOf(level));
		lvl.setFont(FontManager.getRunescapeSmallFont());
		lvl.setForeground(gained != null ? Color.WHITE : ColorScheme.LIGHT_GRAY_COLOR.darker());
		text.add(lvl);

		if (gained != null)
		{
			// No unit: it is a skill, so it is experience. And no plus on the
			// whole record, where the figure is what the skill HAS rather than
			// what some period added to it. "+13.6M xp" did not fit the cell and
			// clipped to "+13.6M ...", which spent the room on the one word the
			// reader did not need.
			JLabel g = new JLabel((wholeRecord() ? "" : "+") + xpShort(gained));
			g.setFont(FontManager.getRunescapeSmallFont());
			g.setForeground(accent());
			text.add(g);
		}
		cell.add(text, BorderLayout.CENTER);
		return cell;
	}

	private static final Map<net.runelite.api.Skill, java.awt.image.BufferedImage> SKILL_ICONS =
		new java.util.EnumMap<>(net.runelite.api.Skill.class);

	// The game's own skill icon, loaded once and shared.
	private java.awt.image.BufferedImage skillIcon(net.runelite.api.Skill sk)
	{
		return SKILL_ICONS.computeIfAbsent(sk, s ->
		{
			try
			{
				java.awt.image.BufferedImage img = plugin.skillIcons().getSkillImage(s, true);
				return img;
			}
			catch (Throwable e)   // noqa: no icon is worth the tab it sits on
			{
				return null;   // a dev client without the sprite cache
			}
		});
	}
	private String histFacet = "Skills";
	// True while the sheet is drawing its SECOND band, so the head card that
	// stands over the whole sheet is not drawn again underneath it.
	private boolean sheetBandDrawn;
	// What the total level tile says on hover while the sheet is drawing, in the
	// markup RuneLite's own hiscores panel uses for exactly this.
	private String periodTip;
	// A page the sheet's activity tiles send the reader to: "clues", "log",
	// "quests", "diaries" or "combat". Like showInfo and allTrackers it is
	// somewhere the reader was SENT rather than somewhere they drilled, so it
	// leaves by its own branch of backDetail rather than by popping the stack -
	// last, after everything drilled on top of it has been unwound.
	private String sheetPage;
	// What the period's figures are measured FROM, when that is not simply the eve
	// of the window. Hangs on the period control rather than on the board.
	private String measuredSince;

	private final Map<Integer, java.awt.image.BufferedImage> facetIcons = new LinkedHashMap<>();
	private final java.util.Set<Integer> facetAsked = new java.util.HashSet<>();
	// the labels still waiting on a sprite that has been asked for but has not
	// landed, each with the size it wants it at
	private final Map<Integer, List<Object[]>> facetWaiting = new LinkedHashMap<>();

	/**
	 * Dress a label in a game sprite at the size it asks for, fetching it off the
	 * client thread the first time anyone wants it and no more than that ever.
	 *
	 * <p>Several labels can want one sprite: a line in a band with nothing of its
	 * own to wear takes the sprite of the kind it sits under, and the boss grid
	 * wears one icon for each pair the game pools, so Callisto and Artio wait on
	 * the same fetch. They queue, and the one fetch dresses all of them.
	 *
	 * <p>The sprite cannot simply be asked for: SpriteManager.getSprite asserts
	 * it is on the client thread and a panel is built on the event thread, so
	 * asking threw an AssertionError, which is an Error and not an exception. A
	 * catch written for RuntimeException let it past and the whole tab came out
	 * blank. Nothing here is worth a blank tab, so the fetch is asynchronous and
	 * every throwable below is swallowed.
	 */
	private void wearSprite(JLabel label, int spriteId, int w, int h)
	{
		java.awt.image.BufferedImage have = facetIcons.get(spriteId);
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
			// once, ever: every build used to queue four more tasks on the client
			// thread, and a reader clicking about queued them faster than the
			// client drained them
			if (sm == null || !facetAsked.add(spriteId))
			{
				return;
			}
			sm.getSpriteAsync(spriteId, 0, img -> javax.swing.SwingUtilities.invokeLater(() ->
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
		catch (Throwable ignored)   // noqa: a tab icon is never worth a blank tab
		{
			// the word stands in
		}
	}

	// Every icon the tab has already shrunk, by what it is and how big. Scaling
	// is not free and it happens on the event thread: a band redrawn on every
	// fold click would smooth-scale the same dozen images again each time, which
	// is per-click work of exactly the kind that made this tab heavy before.
	private final Map<String, javax.swing.ImageIcon> scaledIcons = new LinkedHashMap<>();

	/**
	 * An image on a label at the size asked for, its shape kept: a 36x32 item
	 * squeezed into a square column is a squashed item. A width of zero means the
	 * image's own size. The result is kept under the key the caller names it by,
	 * so it is scaled once and worn thereafter.
	 */
	private void dress(JLabel label, String key, java.awt.image.BufferedImage img, int w, int h)
	{
		javax.swing.ImageIcon icon = scaledIcons.get(key);
		if (icon == null)
		{
			icon = w <= 0 || h <= 0 ? new javax.swing.ImageIcon(img) : fit(img, w, h);
			scaledIcons.put(key, icon);
		}
		label.setIcon(icon);
		label.setText("");
	}

	private static javax.swing.ImageIcon fit(java.awt.image.BufferedImage img, int w, int h)
	{
		double scale = Math.min(w / (double) img.getWidth(), h / (double) img.getHeight());
		return new javax.swing.ImageIcon(img.getScaledInstance(
			Math.max(1, (int) Math.round(img.getWidth() * scale)),
			Math.max(1, (int) Math.round(img.getHeight() * scale)),
			java.awt.Image.SCALE_SMOOTH));
	}

	// whether the period on show is the whole record, which needs no opening
	// figure named beside its close
	private boolean wholeRecord()
	{
		return "Lifetime".equals(histGranularity) && histFrom == null;
	}

	/**
	 * The periods the tab offers, widest first, as the list reads them.
	 *
	 * <p>Session is last because it is narrowest, and it is not a date range like
	 * the rest: it is this sitting, which the counters hold exactly. Every other
	 * period is measured between two of the spine's closed baselines, and the
	 * sitting has no closing baseline because it has not closed.
	 */
	static final String[] PERIODS = {"Lifetime", "Year", "Month", "Week", "Day",
		"Session"};

	/** The sitting: what has happened since this client logged in. */
	static final String SESSION = "Session";

	private boolean sessionPeriod()
	{
		return SESSION.equals(histGranularity) && histFrom == null;
	}

	// the visible period's own two ends, kept for the menu
	private java.time.LocalDate periodFrom;
	private java.time.LocalDate periodTo;

	// the choices, built fresh so the tick sits on whichever is current
	private javax.swing.JPopupMenu periodMenu()
	{
		javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
		for (String g : PERIODS)
		{
			javax.swing.JMenuItem item = new javax.swing.JMenuItem(g);
			item.setFont(FontManager.getRunescapeSmallFont());
			if (g.equals(histGranularity) && histFrom == null)
			{
				item.setForeground(accent());
			}
			item.addActionListener(e ->
			{
				histGranularity = g;
				histFrom = null;
				histTo = null;
				histCursor = java.time.LocalDate.now();
				rebuildInPlace();
			});
			menu.add(item);
		}
		// Any two days, from the same list: at Lifetime there is no dateline to
		// click, so this is the only way back to a window of one's own choosing.
		menu.addSeparator();
		javax.swing.JMenuItem exact = new javax.swing.JMenuItem("Exact dates");
		exact.setFont(FontManager.getRunescapeSmallFont());
		if (histFrom != null)
		{
			exact.setForeground(accent());
		}
		exact.addActionListener(e -> onSetExactDates(
			periodFrom != null ? periodFrom : java.time.LocalDate.now().minusDays(6),
			periodTo != null ? periodTo : java.time.LocalDate.now()));
		menu.add(exact);
		return menu;
	}

	/**
	 * The tasks, as a menu. A slayer career names more of them than will fit as
	 * pills in a panel this wide, and the period control already established how
	 * this panel asks a question with more answers than it has room for.
	 */
	private javax.swing.JPopupMenu taskMenu()
	{
		javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
		javax.swing.JMenuItem all = new javax.swing.JMenuItem("Every task");
		all.setFont(FontManager.getRunescapeSmallFont());
		if (lootTask == null)
		{
			all.setForeground(accent());
		}
		all.addActionListener(e ->
		{
			lootTask = null;
			lootKind = null;
			rebuildInPlace();
		});
		menu.add(all);
		menu.addSeparator();
		for (String task : plugin.taskNames())
		{
			javax.swing.JMenuItem item = new javax.swing.JMenuItem(task);
			item.setFont(FontManager.getRunescapeSmallFont());
			if (task.equals(lootTask))
			{
				item.setForeground(accent());
			}
			item.addActionListener(e ->
			{
				lootTask = task;
				// the kinds under one task are not the kinds under all of them
				lootKind = null;
				rebuildInPlace();
			});
			menu.add(item);
		}
		return menu;
	}

	/**
	 * The row that says which TASK is on show and opens the menu. Drawn like the
	 * period row above it, because it is the same kind of control.
	 */
	private JPanel taskPicker()
	{
		JPanel r = row("Task", lootTask == null ? "Every task" : lootTask, accent());
		JLabel name = (JLabel) ((BorderLayout) r.getLayout())
			.getLayoutComponent(BorderLayout.CENTER);
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(ColorScheme.LIGHT_GRAY_COLOR.darker());
		JLabel pick = (JLabel) ((BorderLayout) r.getLayout())
			.getLayoutComponent(BorderLayout.EAST);
		pick.setFont(FontManager.getRunescapeSmallFont());
		pick.setToolTipText("Narrow this board to one task");
		pick.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		// The label needs the listener too, not just the row. Its tooltip registers
		// it with the ToolTipManager, which adds a mouse listener of its own, and
		// from then on the label is the deepest listening component under the
		// pointer: Swing delivers the press there and does not pass it up, so the
		// row's listener never runs. The task name is the half of this row that
		// looks most like the control, and it was the half that did nothing.
		pick.addMouseListener(clicker(() -> taskMenu().show(r, 0, r.getHeight())));
		r.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		r.addMouseListener(clicker(() -> taskMenu().show(r, 0, r.getHeight())));
		return r;
	}

	/** The window the period control is on: its two ends, and what to call it. */
	private static final class Window
	{
		final java.time.LocalDate start;
		final java.time.LocalDate end;
		final String label;

		Window(java.time.LocalDate start, java.time.LocalDate end, String label)
		{
			this.start = start;
			this.end = end;
			this.label = label;
		}
	}

	/**
	 * The period every board is read through. It used to be computed inside the
	 * Progression tab, which is why only that tab could honour it; it is a value
	 * now, so the row above the tabs and the boards below them are reading the
	 * same two dates.
	 */
	private Window window()
	{
		java.time.LocalDate end = histCursor;
		java.time.LocalDate start;
		String label;
		if (histFrom != null && histTo != null)
		{
			start = histFrom;
			end = histTo.isAfter(java.time.LocalDate.now()) ? java.time.LocalDate.now() : histTo;
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
						? java.time.Instant.ofEpochMilli(began)
							.atZone(ZoneId.systemDefault()).toLocalDate()
						: java.time.LocalDate.now();
					end = java.time.LocalDate.now();
					label = "This session";
					break;
				}
				case "Lifetime":
					// everything the record holds, from its first line to today. A
					// period with no earlier line to measure against reads as the
					// account's own beginning, which is what it is.
					start = historySpine == null || historySpine.isEmpty()
						? end.minusYears(30) : historySpine.firstKey();
					end = java.time.LocalDate.now();
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
		// what the period menu's exact-dates entry opens on, since at Lifetime
		// there is no dateline to read them off
		periodFrom = start;
		periodTo = end;
		return new Window(start, end, label);
	}

	/**
	 * The spine's two ends for the window the period row is on. A period is the
	 * distance between two closed baselines, so a window holding fewer than two
	 * has none: the answer is null, and a board has to SAY so rather than quietly
	 * drawing a lifetime under a month's heading.
	 */
	private static final class Span
	{
		final HistoryLog.Baseline opening;
		final HistoryLog.Baseline earliest;
		final HistoryLog.Baseline closing;

		Span(HistoryLog.Baseline opening, HistoryLog.Baseline earliest,
			HistoryLog.Baseline closing)
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

	/**
	 * The spine folded to the window's two ends.
	 *
	 * <p>Not cheap: it walks the whole spine twice, and the Kills board asks for
	 * it seventy one times -- once per cell -- which on a two year record is
	 * fifty milliseconds of the same fold. span() answers it once per build.
	 */
	private Span foldSpan()
	{
		if (historySpine == null)
		{
			gatherHistory();   // lands on a later pass, and rebuilds when it does
			return null;
		}
		if (historySpine.isEmpty())
		{
			return null;
		}
		Window w = window();
		Map.Entry<java.time.LocalDate, HistoryLog.Baseline> from =
			HistoryLog.windowStart(historySpine, w.start, w.end);
		Map.Entry<java.time.LocalDate, HistoryLog.Baseline> at =
			historySpine.floorEntry(w.end);
		if (at == null || from == null || at.getKey().equals(from.getKey()))
		{
			return null;
		}
		return new Span(HistoryLog.stateAt(historySpine, from.getKey()),
			HistoryLog.earliest(historySpine, at.getKey()),
			HistoryLog.stateAt(historySpine, at.getKey()));
	}

	/**
	 * A period's closing figures, brought up to the moment where the period
	 * reaches it.
	 *
	 * <p>The history spine is written once a day. Everything measured against its
	 * newest line therefore stopped at the last time it was written, which for a
	 * period ending today is some hours ago: a board saying what this week has
	 * done was missing everything done since midnight and caught up overnight.
	 *
	 * <p>Merged upward rather than replaced. These figures only ever grow, so the
	 * larger of the two is the later of the two, and a key the live side has
	 * never heard of keeps whatever the spine holds for it.
	 */
	private Map<String, Long> closingNow(Map<String, Long> closing, Map<String, Long> live)
	{
		if (closing == null || live == null || live.isEmpty() || !periodReachesToday())
		{
			return closing;
		}
		Map<String, Long> out = new java.util.HashMap<>(closing);
		for (Map.Entry<String, Long> e : live.entrySet())
		{
			if (e.getValue() != null)
			{
				out.merge(e.getKey(), e.getValue(), Math::max);
			}
		}
		return out;
	}

	/** Whether the period on show runs up to today, rather than ending before it. */
	private boolean periodReachesToday()
	{
		Window w = window();
		return w != null && !w.end.isBefore(java.time.LocalDate.now());
	}

	/**
	 * The counters a board should read. A lifetime is the totals themselves: a
	 * delta measured from the first line the record holds reports nothing for
	 * every total that joined the spine later. Any narrower window is what it
	 * moved. Null where the spine cannot answer the window at all.
	 */
	private Map<String, Long> countersForPeriod()
	{
		if (wholeRecord())
		{
			return counters();
		}
		// The sitting is counted, not measured. Every other period is the distance
		// between two of the spine's closed baselines; this one has no closing
		// baseline because it has not closed, and it needs none - the counters ARE
		// the session, exactly, with nothing subtracted from anything.
		if (sessionPeriod())
		{
			Map<String, Long> out = new LinkedHashMap<>();
			for (Map.Entry<String, Integer> e : plugin.sessionCounters().entrySet())
			{
				if (e.getValue() != null && e.getValue() != 0)
				{
					out.put(e.getKey(), e.getValue().longValue());
				}
			}
			return out;
		}
		Span s = span();
		// The same for every counter: a period reaching today closes on the live
		// totals. Without it the trackers board reported a week that stopped at
		// midnight and caught up overnight.
		return s == null ? null
			: HistoryLog.gained(s.opening.counters, s.earliest.counters,
				closingNow(s.closing.counters, counters()));
	}

	/**
	 * The period's name as it reads INSIDE a sentence.
	 *
	 * <p>Every other label is a proper noun or a date - "Lifetime", "14 Sept -
	 * 20 Sept" - and sits mid-sentence unchanged. The sitting's is a phrase, and
	 * "Nothing taken inside This session." puts a capital in the middle of a
	 * line. The strip above still says it with a capital, because there it opens
	 * its own line.
	 */
	private String periodInSentence()
	{
		String label = window().label;
		return label.startsWith("This ")
			? Character.toLowerCase(label.charAt(0)) + label.substring(1) : label;
	}

	/**
	 * Whether a stamped line belongs to the window, in epoch millis: the feed,
	 * the collection log's new slots, and the slayer journey all date a line
	 * this way rather than by a daily baseline. A lifetime admits everything,
	 * which is what it means.
	 */
	private boolean insideWindow(long ts)
	{
		if (wholeRecord())
		{
			return true;
		}
		if (ts <= 0)
		{
			// An undated line, which is a line written before the journal carried
			// stamps. Admitted by any period measured in days, where it is at
			// worst filed a little loosely. Never admitted by the sitting: a line
			// that cannot say when it happened certainly cannot claim to have
			// happened in the last hour.
			return !sessionPeriod();
		}
		long[] ms = windowMs();
		return ts >= ms[0] && ts <= ms[1];
	}

	/** What a dated board says when the window simply held nothing. */
	private JPanel nothingInWindow(String what)
	{
		return note("No " + what + " inside " + periodInSentence() + ".");
	}

	/**
	 * The loot sources that are one skill's own ground, under the skill.
	 *
	 * <p>A Fishing Trawler casket, a Guardians of the Rift pouch and a Giants'
	 * Foundry reward were filed as "activities", which made them a category of
	 * their own on the sheet and nowhere near the skill they belong to. They are
	 * loot, they already sit on the Loot board by source, and the mapping from a
	 * page to its skill was already here for the icons. So the skill carries them.
	 */
	private List<LocalStore.SourceRow> skillGround(String craft)
	{
		java.util.Set<String> ownTile = new java.util.HashSet<>();
		for (String[] a : ACTIVITIES)
		{
			if (!a[1].isEmpty())
			{
				ownTile.add(a[1].toLowerCase(Locale.ROOT));
			}
		}
		List<LocalStore.SourceRow> out = new ArrayList<>();
		for (LocalStore.SourceRow r : sources())
		{
			// A source with its own node on the sheet is not also a skill's
			// ground: Guardians of the Rift is Runecraft's, but the hiscores give
			// it a node of its own and so do we, and one thing counted in two
			// places on one board is a board that disagrees with itself.
			if (ownTile.contains(r.name.toLowerCase(Locale.ROOT)))
			{
				continue;
			}
			net.runelite.api.Skill sk = skillOf(r.name);
			if (sk != null && sk.name().equalsIgnoreCase(craft) && r.value > 0)
			{
				out.add(r);
			}
		}
		out.sort((x, y) -> Long.compare(y.value, x.value));
		return out;
	}

	/** What the client says a skill stands at now, or null where it has not said. */
	private Long liveXp(String key)
	{
		long[] cur = plugin.skillSheet().get(key);
		return cur != null && cur.length > 1 && cur[1] > 0 ? cur[1] : null;
	}

	/** What THIS sitting earned in one skill. Zero is a real answer. */
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

	/**
	 * One skill under the glass: where it stands, what the window moved it, and
	 * then the counters filed under it. The cell this opens from renders a level
	 * over an xp gain, so xp is the question it asked and xp is answered first.
	 */
	private JPanel buildSkillDetail(String craft)
	{
		JPanel p = column();
		p.add(backRow());
		p.add(vgap(4));
		consumVals = plugin.consumableValues();
		String key = craft.toLowerCase(Locale.ROOT);

		JPanel head = card(craft);
		Span s = span();
		// The same two corrections the sheet grid had. The spine is written once a
		// day, so a drill reading its closing line stood still through an hour of
		// training; and the sitting is not measurable between two of the spine's
		// lines at all, since both of them are today's.
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
			head.add(row("Experience", gp(now), null));
			if (!wholeRecord() && was != null && now > was)
			{
				head.add(row("Gained", "+" + gp(now - was), accent()));
			}
		}
		else
		{
			head.add(row("Level", "-", null));
		}
		p.add(head);
		p.add(vgap(6));

		Map<String, Long> counters = countersForPeriod();
		if (counters == null)
		{
			p.add(noPeriod());
			return p;
		}
		List<Map.Entry<String, Long>> rows = new ArrayList<>();
		for (Map.Entry<String, Long> e : counters.entrySet())
		{
			if (e.getValue() == null || e.getValue() <= 0
				|| !"Skilling".equals(StatRegistry.family(e.getKey()))
				|| !craft.equalsIgnoreCase(StatRegistry.subgroup(e.getKey())))
			{
				continue;
			}
			rows.add(e);
		}
		List<LocalStore.SourceRow> ground = skillGround(craft);
		if (rows.isEmpty() && ground.isEmpty())
		{
			// Seven of the grid's skills file no counters at all: Attack, Strength,
			// Defence, Hitpoints, Ranged, Magic and Slayer. A skill that tracks
			// nothing should say so rather than open on a blank.
			p.add(note("Nothing is tracked under " + craft
				+ (wholeRecord() ? "." : " in " + periodInSentence() + ".")));
			return p;
		}
		rows.sort(StatRegistry::compareRows);
		addPaceLine(p, craft);
		for (Map.Entry<String, Long> e : rows)
		{
			p.add(row(StatRegistry.rowLabel(e.getKey()), rowValue(e), null));
		}
		if (!ground.isEmpty())
		{
			p.add(vgap(6));
			// Named for what it is. These are the ledger's running per-source
			// totals, which no period touches: the block was byte for byte the
			// same under Lifetime and under a sitting, sat directly beneath a
			// "Gained" row that IS the period, and read as the period's.
			p.add(group("WHAT IT HAS EVER PAID"));
			for (LocalStore.SourceRow r : ground)
			{
				JPanel line = row(r.name, gp(r.value) + " gp", accent());
				line.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
				final String open = r.name;
				line.addMouseListener(clicker(() -> openSource(open)));
				p.add(line);
			}
		}
		return p;
	}

	/** What a board says when the window holds too little to be a period. */
	private JPanel noPeriod()
	{
		return note(historySpine == null
			? "Reading your history..."
			: "Nothing closed inside " + periodInSentence() + ". A period is the distance "
				+ "between two baselines, and this window holds fewer than two.");
	}

	/**
	 * The period, on one row above the tabs, because it governs all of them. The
	 * arrows step the window and the label between them opens the list; at
	 * Lifetime the arrows have nowhere to go, so they are not drawn and the label
	 * stands alone. This sitting is the one board it does not govern, and there
	 * the row states its scope rather than offering to change it.
	 */
	private JPanel periodRow()
	{
		final Window w = window();
		JPanel r = new JPanel(new BorderLayout());
		r.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		r.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
		r.setAlignmentX(Component.LEFT_ALIGNMENT);
		r.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
		// The sitting is now and can be nothing else, so the row states its scope
		// rather than offering to change it. It still draws, at the same height in
		// the same place, because a control that vanishes on one tab moves every
		// tab under it.
		if (showingSitting())
		{
			JLabel fixed = new JLabel("This session", JLabel.CENTER);
			fixed.setFont(FontManager.getRunescapeFont());
			fixed.setForeground(ColorScheme.LIGHT_GRAY_COLOR.darker());
			r.add(fixed, BorderLayout.CENTER);
			return r;
		}
		// No arrows on the sitting: there is one, and it is this one. Stepping the
		// cursor off it would name a day and go on calling it "This session".
		if ((!"Lifetime".equals(histGranularity) && !sessionPeriod()) || histFrom != null)
		{
			JLabel back = new JLabel("<");
			JLabel fwd = new JLabel(">");
			for (JLabel arrow : new JLabel[]{back, fwd})
			{
				arrow.setForeground(accent());
				arrow.setFont(FontManager.getRunescapeBoldFont());
				arrow.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
				arrow.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
			}
			back.addMouseListener(clicker(() -> stepPeriod(-1)));
			fwd.addMouseListener(clicker(() -> stepPeriod(1)));
			r.add(back, BorderLayout.WEST);
			r.add(fwd, BorderLayout.EAST);
		}
		JLabel lbl = new JLabel(w.label, JLabel.CENTER);
		lbl.setFont(FontManager.getRunescapeFont());
		lbl.setForeground(accent());
		lbl.setToolTipText("Choose the period");
		lbl.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		lbl.addMouseListener(clicker(() -> periodMenu().show(r, 0, r.getHeight())));
		r.add(lbl, BorderLayout.CENTER);
		return r;
	}

	/**
	 * Whether the sitting ITSELF is what is showing. A drill opened from it keeps
	 * the tab's view but is not that board: the trackers page opened from Now
	 * reads the period like everything else, and a row above it saying "This
	 * session" would be describing the tab rather than what is on screen.
	 */
	private boolean showingSitting()
	{
		return view == View.HOME && !allTrackers && detailSkill == null
			&& detailItem == null && detailSource == null && detailTask < 0
			&& leftBehindSource == null && leftBehindItem == null
			&& searchQuery().isEmpty();
	}

	/** Move the window one granule, or one span where exact dates are set. */
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
			java.time.LocalDate next = stepForward(histCursor);
			histCursor = next.isAfter(java.time.LocalDate.now())
				? java.time.LocalDate.now() : next;
		}
		rebuild();
	}

	private JPanel buildHistory()
	{
		JPanel p = column();
		// The period is above the tabs now and the tabs replaced the facet strip,
		// so this builds no controls of its own; it reads the window like anything
		// else and draws the board the sub-tab asked for.
		Window periodWin = window();
		final java.time.LocalDate pStart = periodWin.start;
		final java.time.LocalDate pEnd = periodWin.end;
		final java.time.LocalDate end = periodWin.end;

		// Ask for a fresh pass when the day has turned or the feed has grown.
		// Probing the newest entry costs one copy; the gather costs thousands,
		// and the stale pair still renders while it runs.
		if (historySpine == null || !java.time.LocalDate.now().equals(historyDay)
			|| newestTs(plugin.feedNewest(1)) != historyFeedTs)
		{
			gatherHistory();
		}
		if (historySpine == null)
		{
			p.add(note("Reading your history…"));
			return p;
		}
		java.util.TreeMap<java.time.LocalDate, HistoryLog.Baseline> hist = historySpine;

		// baselines bounding the period: closing state the day before it began,
		// and the last close inside it. With nothing closed before the window the
		// earliest line on record stands in, the way the site measured from its
		// first snapshot: a fresh record's first week reads from its first day.
		Map.Entry<java.time.LocalDate, HistoryLog.Baseline> before =
			hist.floorEntry(pStart.minusDays(1));
		Map.Entry<java.time.LocalDate, HistoryLog.Baseline> from =
			HistoryLog.windowStart(hist, pStart, end);
		Map.Entry<java.time.LocalDate, HistoryLog.Baseline> at = hist.floorEntry(end);
		if (at == null || from == null || at.getKey().equals(from.getKey()))
		{
			String empty;
			if (hist.isEmpty())
			{
				empty = "The record starts today: baselines close at each login, "
					+ "day rollover and logout, and a period is the distance "
					+ "between two of them.";
			}
			else if (!hist.isEmpty() && hist.firstKey().isBefore(pStart)
				&& ("Day".equals(histGranularity) || "Week".equals(histGranularity)))
			{
				// The imported past resolves by month; day and week windows inside
				// it hold no interior baseline.
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
			// Say what the period is measured from when it is not the eve of the
			// window: the nearest earlier baseline sitting well before it (a month
			// of xp would read as one week's gain), or the earliest line on
			// record when nothing predates the window at all.
			// This is a caveat about the period, not a row of the board, so it
			// hangs on the period control itself and takes no room until asked
			// for. It used to be two lines of prose at the top of every board.
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
			// A key the start line does not carry measures from its earliest
			// recorded value, never from zero: imported baselines predate newer
			// skills and carry no counters, and absence-as-zero painted a
			// lifetime as one week's gain. The first line that holds the key is
			// a recorded value, and the site measured counters the same way.
			HistoryLog.Baseline earliest = HistoryLog.earliest(hist, at.getKey());
			// The states standing at each end of the window, not the bare lines:
			// a line says only what moved that day, and a complete snapshot says
			// a skill it omits stood at zero. stateAt folds the record up to a
			// date into the state that stood on it. The opening is taken at the
			// line the window is measured from, which is the eve of the window
			// when a line predates it and the earliest line on record when none
			// does.
			HistoryLog.Baseline closing = HistoryLog.stateAt(hist, at.getKey());
			HistoryLog.Baseline opening = HistoryLog.stateAt(hist, from.getKey());
			// A period that reaches today closes on the client, not on the spine.
			// The spine's newest line is written when the journal is flushed, so
			// a board measuring to it sat still through an hour of training and
			// moved on logout: the levels above already read live and the GAIN
			// did not, which is the half a reader is watching.
			Map<String, Long> closesOn = closing.skills;
			if (!pEnd.isBefore(java.time.LocalDate.now()))
			{
				Map<String, Long> nowXp = new java.util.HashMap<>(closing.skills);
				for (Map.Entry<String, long[]> e : plugin.skillSheet().entrySet())
				{
					if (e.getValue() != null && e.getValue().length > 1
						&& e.getValue()[1] > 0)
					{
						nowXp.merge(e.getKey(), e.getValue()[1], Math::max);
					}
				}
				closesOn = nowXp;
			}
			List<Map.Entry<String, Long>> gains = new ArrayList<>();
			for (Map.Entry<String, Long> e : HistoryLog.gained(opening.skills,
				earliest.skills, closesOn, opening.complete).entrySet())
			{
				if (!"overall".equals(e.getKey()))
				{
					gains.add(e);
				}
			}
			// The sitting is counted, not measured between two of the spine's
			// lines. A session that began today shares its dates with Day, so a
			// board reading the spine draws Day's figures under the sitting's
			// name; the experience tracker holds what THIS sitting earned, per
			// skill, and that is a different number the moment the day started
			// before the client did.
			if (sessionPeriod())
			{
				gains.clear();
				for (ExperienceStatTracker.SkillGain g : plugin.sessionSkillXp())
				{
					if (g.skill != null && g.xp > 0)
					{
						gains.add(new java.util.AbstractMap.SimpleEntry<>(
							g.skill.name().toLowerCase(Locale.ROOT), g.xp));
					}
				}
			}
			gains.sort(Map.Entry.<String, Long>comparingByValue().reversed());
			// The standing figures are the period's close, its last line, the
			// way the site drew every period as a snapshot. Only a period that
			// reaches today reads the live sheet and ledger: its closing line
			// is the newest one, and they are that state a few minutes fresher.
			boolean live = !pEnd.isBefore(java.time.LocalDate.now());

			// The summary lines that read the journal itself rather than the
			// spine: slayer tasks and slayer kills from the closed segments dated
			// inside the period, and the feed's dated entries counted by type
			// (collection log slots, deaths, pets, quests, diaries, combat
			// achievements, levels) when the feed reaches back past the window's
			// start (a feed that begins inside it cannot say what it missed, and
			// the spine's delta stands where the spine carries the key). All of
			// them reach back past the day the spine first carried them. One walk
			// of the feed serves the counts, the played time and the named lists
			// the groups open to; an entry with no usable stamp is skipped. The
			// milestones themselves are the Journal tab's business: it is the
			// same feed, read day by day, and drawing it twice was clutter.
			// The sitting bounds itself by the moment the client started, not by
			// the midnight before it, or the walk below hands the whole day's
			// feed to a board headed "This session".
			long fromMs = sessionPeriod() ? windowMs()[0]
				: pStart.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
			long toMs = sessionPeriod() ? windowMs()[1]
				: end.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
			Map<String, Long> fromFeed = new java.util.HashMap<>();
			Map<String, List<String[]>> named = new LinkedHashMap<>();
			long[] played = {0, 0};   // minutes, sessions
			// the sessions' own take: drops, their gp, the stacks left, their gp,
			// the kills that left one, and how many of the sittings counted the
			// floor at all. The floor is only an account of this period when
			// every one of them did.
			long[] took = {0, 0, 0, 0, 0, 0};
			// the first sitting the period holds, which is as far back as a
			// figure read off the sittings can reach
			long[] firstSitting = {0};
			for (JsonObject e : historyFeed)
			{
				long ts = safeLong(e.get("ts"));
				if (ts >= fromMs && ts < toMs)
				{
					String type = e.has("type") ? e.get("type").getAsString() : "";
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
						if (firstSitting[0] == 0 || ts < firstSitting[0])
						{
							firstSitting[0] = ts;
						}
						// A session closes with what it took: the loot events it
						// saw and what they were worth. Dated, one line per
						// sitting, and the only account of the take that reaches
						// back before the spine began carrying the journal's own
						// totals.
						JsonObject d = e.has("data") && e.get("data").isJsonObject()
							? e.getAsJsonObject("data") : null;
						if (d != null)
						{
							took[0] += safeLong(d.get("drops"));
							took[1] += safeLong(d.get("dropsGp"));
							took[2] += safeLong(d.get("left"));
							took[3] += safeLong(d.get("leftGp"));
							took[4] += safeLong(d.get("leftKills"));
							if (d.has("leftKills"))
							{
								took[5]++;   // this sitting can speak for the floor
							}
						}
					}
				}
			}
			Map<String, Long> retro = new java.util.HashMap<>();
			boolean[] sessionsHoldTheFloor = {false};
			boolean[] sessionsSpeak = {false};
			if (historyJourney != null)
			{
				retro.put("slayerTasksCompleted", closedTasksBetween(historyJourney, fromMs, toMs));
				retro.put("slayerKills", closedKillsBetween(historyJourney, fromMs, toMs));
				List<String[]> tasks = closedTaskNames(historyJourney, fromMs, toMs);
				if (!tasks.isEmpty())
				{
					named.put("slayerTasksCompleted", tasks);
				}
			}
			long oldest = oldestTs(historyFeed);
			boolean reachesBack = oldest > 0 && oldest < fromMs;
			if (reachesBack)
			{
				// every counted type, a zero included: a type absent from the
				// feed draws no line, whatever the spine's delta says
				for (String key : FEED_SUMMARY_KEYS.values())
				{
					retro.put(key, fromFeed.getOrDefault(key, 0L));
				}
				// What the sessions took, over the spine's own delta: the spine
				// carries the journal's lifetime totals and can only speak for
				// a period once two of its lines hold them, while a session
				// says what it took on the day it ran.
				// Loot is drawn only where something can account for the whole
				// period. Nothing in the record dates a drop: the ledger keeps
				// lifetime totals per source, the feed never carried a loot
				// entry, and the spine only began holding the loot totals partway
				// through this account's life. A sitting is the one dated account
				// of a take, so the sittings can speak for a period only when
				// they reach back to the start of it. Where they do not, no
				// figure is drawn and the note says the day loot can be counted
				// from, because a fortnight of receipts under a heading that
				// says a year is worse than no figure at all.
				// The dated roll first, where it reaches back far enough. It keeps
				// one entry a day holding what was taken and what was left, with
				// the items and sources beside them, so it answers a period
				// exactly and can say what the loot actually was.
				long rollFrom = plugin.lootRollFrom();
				if (sessionPeriod())
				{
					// The roll is dated by DAY, so asked for a sitting it answers
					// with the day the sitting is in. The sitting keeps its own
					// entry in the roll's shape instead, written as the drops
					// land, so it opens the same named lists as any other period.
					LocalStore.LootWindow w = plugin.sessionLootWindow();
					sessionsSpeak[0] = true;
					sessionsHoldTheFloor[0] = true;
					retro.put("dropsReceived", w.loots);
					retro.put("lootValue", w.value);
					retro.put("lootLeftCount", w.left);
					retro.put("lootLeftValue", w.leftValue);
					retro.put("lootLeftKills", w.leftKills);
					if (!w.items.isEmpty())
					{
						named.put("lootValue", itemLines(w.items));
					}
					if (!w.leftItems.isEmpty())
					{
						named.put("lootLeftCount", itemLines(w.leftItems));
					}
					if (!w.sources.isEmpty())
					{
						named.put("dropsReceived", sourceLines(w.sources));
					}
				}
				else if (rollFrom > 0 && rollFrom <= fromMs)
				{
					LocalStore.LootWindow w = plugin.lootBetween(pStart, pEnd);
					sessionsSpeak[0] = true;
					sessionsHoldTheFloor[0] = true;   // the roll dates the floor too
					retro.put("dropsReceived", w.loots);
					retro.put("lootValue", w.value);
					retro.put("lootLeftCount", w.left);
					retro.put("lootLeftValue", w.leftValue);
					retro.put("lootLeftKills", w.leftKills);
					if (!w.items.isEmpty())
					{
						named.put("lootValue", itemLines(w.items));
					}
					if (!w.leftItems.isEmpty())
					{
						named.put("lootLeftCount", itemLines(w.leftItems));
					}
					if (!w.sources.isEmpty())
					{
						named.put("dropsReceived", sourceLines(w.sources));
					}
				}
				else if (sittingsCover(historyFeed, fromMs) && played[1] > 0 && took[0] > 0)
				{
					sessionsSpeak[0] = true;
					retro.put("dropsReceived", took[0]);
					retro.put("lootValue", took[1]);
					retro.put("lootLeftCount", took[2]);
					retro.put("lootLeftValue", took[3]);
					retro.put("lootLeftKills", took[4]);
					// and the floor is this period's only when every one of the
					// sittings counted it: an older sitting that never did would
					// be read as one that left nothing behind
					sessionsHoldTheFloor[0] = took[5] == played[1];
				}
			}
			else
			{
				// the slice begins inside the window, so it cannot say what it
				// missed: the lists would name a part of the period as the whole.
				// A lifetime is the exception, being every day the record holds:
				// its sittings are all the sittings there are.
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

			// The sitting in progress, which is in no dated line yet: a sitting
			// reaches the journal when it CLOSES, so until then this figure was
			// every sitting the reader has had except the one they are having.
			// Counted only where the period reaches today, and zero once logged
			// out, which is exactly when the closing line exists to be counted
			// instead - so the two can never both be in the sum.
			if (!pEnd.isBefore(java.time.LocalDate.now()))
			{
				long running = plugin.sessionElapsedMinutes();
				if (running > 0)
				{
					played[0] += running;
					played[1]++;
				}
			}
			// Over the whole record, the GAME's own figure where it has one. What
			// Chronicle can measure is the time it watched, which is a different
			// thing and a smaller one: it begins the day the plugin was installed
			// and knows nothing of the years before it. The account summary and
			// Hans both quote the game's, and that is the number a player has.
			// A period cannot use it - the game keeps one running total and no
			// account of when any of it was spent - so it is lifetime only.
			if (wholeRecord())
			{
				long theirs = plugin.gamePlaytimeMinutes();
				if (theirs > played[0])
				{
					played[0] = theirs;
					playedIsTheGames = true;
				}
			}

			// What the period tracked: the headline figures first, then the
			// lens detail, then every other figure in its group. The per-source
			// kill counts stay with the Kills toggle; the headline's Kills line
			// is their sum, written on the spine beside the counters off the
			// same per-source base. The note under the headline reads the spine
			// only as far as the period's last line: a period closed before a
			// key joined names no date after its own end.
			// Whether the record can say what this period left on the floor: the
			// tally joined the spine partway through the account's life, and a
			// period that opens before it cannot be told what it kept.
			java.time.LocalDate leftFrom = HistoryLog.firstCarrying(
				hist.headMap(at.getKey(), true), "lootLeftKills");
			boolean leftDated = sessionsSpeak[0]
				? sessionsHoldTheFloor[0]
				: (leftFrom != null && !leftFrom.isAfter(from.getKey()));
			// A window is a delta between the spine's two ends. A lifetime has no
			// earlier end, and a delta measured from the first line the record
			// holds reports nothing for every total that joined the spine later:
			// a board listing 166M of loot sat under a headline reading zero.
			// So a lifetime is the totals themselves.
			boolean whole = "Lifetime".equals(histGranularity) && histFrom == null;
			HistoryProgress progress = HistoryProgress.of(
				whole ? closing.counters
					: HistoryLog.gained(opening.counters, earliest.counters,
						closing.counters),
				null, whole ? new java.util.HashMap<>() : retro, leftDated || whole);
			// The sitting is measured from where the sitting began, which the
			// spine cannot say: it is written once a day, so its nearest earlier
			// line is the eve of TODAY. Measured from that, a level gained this
			// morning in a sitting that has since ended reads as this sitting's -
			// Hunter 91 to 92, and a total level up by one, under a heading
			// saying "This session", for something that happened hours ago.
			//
			// Where the sitting began is exactly derivable and needs no baseline:
			// the experience each skill has now, less what THIS sitting earned.
			// Both ends then come off the same live reading, which also keeps the
			// count of skills drawn on each side equal - the total tile refuses
			// to name an opening when they differ.
			HistoryLog.Baseline sittingOpen = null;
			if (sessionPeriod())
			{
				Map<String, Long> openXp = new java.util.HashMap<>(closesOn);
				for (ExperienceStatTracker.SkillGain g : plugin.sessionSkillXp())
				{
					if (g.skill == null || g.xp <= 0)
					{
						continue;
					}
					String key = g.skill.name().toLowerCase(Locale.ROOT);
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
			// On the sheet the head card is not drawn at all: its four figures are
			// what the total level tile says when the cursor is on it, which costs
			// no room on a 225px board and puts the reading beside the number it
			// describes. Everywhere else it is still a card.
			if (view == View.SHEET)
			{
				periodTip = periodTip(played, gains);
			}
			else if (!sheetBandDrawn)
			{
				p.add(headline(progress, gains, stand, opened, played));
				p.add(vgap(5));
			}
			// What the loot rows actually reach back to. The sittings are the
			// only dated account of a take, so where they do not reach back to
			// the period's start no loot figure was drawn at all, and the note
			// names the day one could be. Where they do, the figures are theirs
			// and the spine's own start date says nothing about them.
			java.time.LocalDate lootSince = null;
			long lootFromTs = earliestDatedLoot(historyFeed, plugin.lootRollFrom());
			if (lootFromTs > 0)
			{
				java.time.LocalDate sat = Instant.ofEpochMilli(lootFromTs)
					.atZone(ZoneId.systemDefault()).toLocalDate();
				if (sat.isAfter(pStart))
				{
					lootSince = sat;
				}
			}
			// Only a window needs it. A lifetime reads the totals themselves, which
			// are whole whatever day the spine began carrying them, so a line
			// saying the record starts in August would be untrue of every figure
			// above it.
			String since = wholeRecord() ? null
				: countersSince(hist.headMap(at.getKey(), true), from.getKey(),
					lootSince, lootFromTs > 0);
			if (since != null)
			{
				p.add(note(since));
				p.add(vgap(5));
			}

			// Four readings of the one period. Each owns its own figures, so a
			// reader looking for a skill is not scrolling past a boss board to
			// find it.
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
				// A window shows what moved in it, read off the spine's own two
				// ends. A lifetime has no earlier end to measure against, and the
				// figure a reader wants there is the total itself, which is what
				// the Stats tab carried before it was folded in here.
				if ("Lifetime".equals(histGranularity) && histFrom == null)
				{
					p.add(buildStats());
				}
				else if (!progress.groups().isEmpty() || !gains.isEmpty())
				{
					p.add(trackedProgress(progress, gains, named));
					p.add(vgap(5));
				}
			}
			else
			{
				addSkillGrid(p, gains, stand, opened);
			}
		}

		return p;
	}

	// Any two dates: a small dialog, ISO or d/M/yyyy, prefilled with the
	// visible period.
	private void onSetExactDates(java.time.LocalDate from, java.time.LocalDate to)
	{
		javax.swing.JTextField fromField = new javax.swing.JTextField(from.toString());
		javax.swing.JTextField toField = new javax.swing.JTextField(to.toString());
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
		java.time.LocalDate f = parseDate(fromField.getText());
		java.time.LocalDate t = parseDate(toField.getText());
		if (f == null || t == null)
		{
			JOptionPane.showMessageDialog(this,
				"Dates read as yyyy-mm-dd (or d/m/yyyy). Nothing changed.");
			return;
		}
		if (t.isBefore(f))
		{
			java.time.LocalDate swap = f;
			f = t;
			t = swap;
		}
		histFrom = f;
		histTo = t;
		rebuild();
	}

	private static java.time.LocalDate parseDate(String text)
	{
		String s = text == null ? "" : text.trim();
		try
		{
			return java.time.LocalDate.parse(s);
		}
		catch (RuntimeException ignored)
		{
			// fall through to d/m/yyyy
		}
		try
		{
			return java.time.LocalDate.parse(s,
				java.time.format.DateTimeFormatter.ofPattern("d/M/yyyy"));
		}
		catch (RuntimeException ignored)
		{
			return null;
		}
	}

	private java.time.LocalDate stepBack(java.time.LocalDate d)
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

	private java.time.LocalDate stepForward(java.time.LocalDate d)
	{
		switch (histGranularity)
		{
			case "Day":
				return d.plusDays(1);
			case "Month":
				return d.withDayOfMonth(1).plusMonths(1).plusMonths(1).minusDays(1);
			case "Year":
				return d.withDayOfYear(1).plusYears(2).minusDays(1);
			case "Week":
			default:
				return d.plusDays(7);
		}
	}

	// The kinds a reader looks for, and the feed types behind each. Kept few. A
	// row per type reads as a database query.
	private static final String[][] JOURNAL_LENSES = {
		{"All"},
		{"Log", "COLLECTION"},
		{"Slayer", "SLAYER"},
		{"Feats", "COMBAT_ACHIEVEMENT", "QUEST", "DIARY", "CLUE", "PET", "LEVEL"},
		{"Deaths", "DEATH"},
		{"Sessions", "SESSION"},
	};
	private String journalLens = "All";
	private int journalShown = 60;

	private JPanel buildJournal()
	{
		JPanel p = column();
		addFrontispiece(p);

		JPanel lenses = new JPanel(new GridLayout(0, 3, 3, 3));
		lenses.setBackground(ColorScheme.DARK_GRAY_COLOR);
		for (String[] lens : JOURNAL_LENSES)
		{
			boolean on = lens[0].equals(journalLens);
			JLabel t = new JLabel(lens[0], JLabel.CENTER);
			t.setOpaque(true);
			t.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
			t.setFont(FontManager.getRunescapeSmallFont());
			t.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			t.setForeground(on ? accent() : ColorScheme.LIGHT_GRAY_COLOR.darker());
			t.setCursor(java.awt.Cursor.getPredefinedCursor(
				java.awt.Cursor.HAND_CURSOR));
			t.addMouseListener(clicker(() ->
			{
				journalLens = lens[0];
				journalShown = 60;
				rebuild();
			}));
			lenses.add(t);
		}
		p.add(lenses);
		p.add(vgap(6));

		java.util.Set<String> wanted = new java.util.HashSet<>();
		for (String[] lens : JOURNAL_LENSES)
		{
			if (lens[0].equals(journalLens))
			{
				wanted.addAll(java.util.Arrays.asList(lens).subList(1, lens.length));
			}
		}
		// Read deep: a lens over the newest fifty finds nothing rare. And with the
		// sitting in progress at the head of it: everything else reaches the feed
		// as it happens, and the sitting was the one thing a reader could watch
		// go by and not see written down until they logged out.
		List<JsonObject> all = plugin.feedWithSitting(4000);
		List<JsonObject> feed = new ArrayList<>();
		for (JsonObject e : all)
		{
			boolean kind = wanted.isEmpty() || (e.has("type")
				&& wanted.contains(e.get("type").getAsString()));
			// The period governs this board too: a dated feed read under a
			// month's heading must list that month and not everything.
			if (kind && insideWindow(safeLong(e.get("ts"))))
			{
				feed.add(e);
			}
		}
		if (feed.isEmpty() && !wholeRecord())
		{
			p.add(nothingInWindow("All".equals(journalLens)
				? "milestones" : journalLens.toLowerCase(Locale.ROOT)));
			return p;
		}
		if (feed.isEmpty())
		{
			p.add(note("All".equals(journalLens)
				? "Milestones (pets, log slots, tasks, quests, deaths) are noted "
					+ "here as they happen."
				: "Nothing of that kind on the record yet."));
			return p;
		}

		String lastDay = null;
		int mounted = 0;
		for (JsonObject e : feed)
		{
			if (mounted++ >= journalShown)
			{
				break;
			}
			long ts = e.has("ts") ? e.get("ts").getAsLong() : 0;
			String day = ts > 0 ? DAY.format(Instant.ofEpochMilli(ts)) : "";
			if (!day.equals(lastDay))
			{
				lastDay = day;
				JLabel g = new JLabel(day.toUpperCase(Locale.ROOT));
				g.setForeground(accent());
				g.setFont(FontManager.getRunescapeSmallFont());
				g.setAlignmentX(Component.LEFT_ALIGNMENT);
				g.setBorder(BorderFactory.createEmptyBorder(7, 2, 3, 0));
				p.add(g);
			}
			p.add(row(feedLine(e), "", null));
		}
		if (feed.size() > journalShown)
		{
			p.add(vgap(6));
			// The one list here that PAGES rather than opening whole. Every other
			// is a couple of hundred rows at the outside; the feed is every event
			// the account has ever had, and mounting all of it would be tens of
			// thousands of rows for a reader who wanted the next few days.
			p.add(moreRow("Read further back", () ->
			{
				journalShown += 60;
				rebuildInPlace();
			}));
		}
		return p;
	}

	// The nameplate at the top of the Journal tab: the account's particulars,
	// stated once.
	private void addFrontispiece(JPanel p)
	{
		String rsn = plugin.displayRsn();
		JPanel plate = card(rsn != null && !rsn.isEmpty()
			? "The journal of " + rsn : "The journal");

		long since = plugin.keptSince();
		java.util.TreeMap<java.time.LocalDate, HistoryLog.Baseline> spine = historySpine;
		if (since > 0)
		{
			plate.add(row("Kept since",
				TASK_DAY.format(Instant.ofEpochMilli(since)), accent()));
		}
		if (spine != null && !spine.isEmpty())
		{
			plate.add(row("Days written", fmt(spine.size()), null));
		}
		Map<String, long[]> sheet = plugin.skillSheet();
		long[] overall = sheet.get("overall");
		int combat = plugin.combatLevel();
		if (overall != null && overall[0] > 0)
		{
			plate.add(row("Total level", fmt(overall[0])
				+ (combat > 0 ? " · combat " + combat : ""), null));
		}
		int[] logStanding = clogStanding();
		int fin = plugin.clogFinished();
		if (logStanding != null)
		{
			plate.add(row("Collection log",
				fmt(logStanding[0]) + " / " + fmt(logStanding[1]), null));
		}
		else if (fin > 0)
		{
			// Not "412 / 412". Standing the obtained count in for the total read as
			// a finished collection log on an account that had simply not synced.
			plate.add(row("Collection log", fmt(fin) + " obtained", null));
		}
		p.add(plate);

		// One margin note, when the record has something to remark on.
		String note = frontispieceNote();
		if (note != null)
		{
			p.add(ghostRow(note, ""));
		}
		p.add(vgap(6));
	}

	// The remark under the plate: the longest chase still owing, or the gap the
	// record just came back from.
	private String frontispieceNote()
	{
		if (grindsCache != null)
		{
			for (ChronicleApiClient.GrindRow g : grindsCache)
			{
				if (g.percentileDry >= 90)
				{
					return "still owed a " + g.item.toLowerCase(Locale.ROOT)
						+ " at " + fmt(g.kc) + " " + g.boss.toLowerCase(Locale.ROOT);
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



	/** Ask for a journal file and hand it to the plugin. Reached from settings. */
	void promptImport()
	{
		javax.swing.JFileChooser fc = new javax.swing.JFileChooser();
		fc.setDialogTitle("Import a Chronicle journal");
		fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
			"Chronicle journal (*.json)", "json"));
		if (fc.showOpenDialog(this) == javax.swing.JFileChooser.APPROVE_OPTION)
		{
			plugin.actionImport(fc.getSelectedFile());
		}
	}

	/**
	 * Combat achievements and diary entries, from the bundled tables rather than
	 * from the record: these are the two things the reader can ask about before
	 * having done them. Short queries are left out by the caller: both halves stop
	 * at two hits, so a two letter prefix cannot flood the panel, but it can put
	 * four arbitrary achievements above the drops and journal lines the reader was
	 * actually typing towards.
	 */
	private int searchAchievements(JPanel p, String ql)
	{
		java.util.Set<Integer> done = caDone();
		bundledCombat = bundle(plugin.gson(), "osrs_combat_achievements.json", bundledCombat);
		JsonObject tasks = bundledCombat.has("tasks")
			? bundledCombat.getAsJsonObject("tasks") : new JsonObject();
		List<JsonObject> caHits = new ArrayList<>();
		for (String id : tasks.keySet())
		{
			JsonObject t = tasks.getAsJsonObject(id);
			if (t.get("name").getAsString().toLowerCase(Locale.ROOT).contains(ql)
				|| t.get("task").getAsString().toLowerCase(Locale.ROOT).contains(ql))
			{
				JsonObject hit = t.deepCopy();
				hit.addProperty("id", Integer.parseInt(id));
				caHits.add(hit);
				if (caHits.size() >= 2)
				{
					break;
				}
			}
		}

		bundledDiaries = bundle(plugin.gson(), "osrs_achievement_diaries.json", bundledDiaries);
		JsonObject diaries = bundledDiaries.has("diaries")
			? bundledDiaries.getAsJsonObject("diaries") : new JsonObject();
		List<String[]> diaryHits = new ArrayList<>();   // {task, region, tier, requirements}
		diarySearch:
		for (String region : diaries.keySet())
		{
			JsonObject tiers = diaries.getAsJsonObject(region);
			for (String tier : tiers.keySet())
			{
				for (com.google.gson.JsonElement e : tiers.getAsJsonArray(tier))
				{
					JsonObject t = e.getAsJsonObject();
					String task = t.get("task").getAsString();
					if (!task.toLowerCase(Locale.ROOT).contains(ql))
					{
						continue;
					}
					diaryHits.add(new String[]{task, region, tier,
						t.has("requirements") ? t.get("requirements").getAsString() : ""});
					if (diaryHits.size() >= 2)
					{
						break diarySearch;
					}
				}
			}
		}

		if (caHits.isEmpty() && diaryHits.isEmpty())
		{
			return 0;
		}
		p.add(group("Achievements"));
		jump(View.SHEET, caHits.isEmpty() ? "diaries" : "combat");
		for (JsonObject t : caHits)
		{
			boolean has = !done.isEmpty() && done.contains(t.get("id").getAsInt());
			JPanel r = row(t.get("name").getAsString(), t.get("monster").getAsString(),
				!done.isEmpty() && !has ? ColorScheme.LIGHT_GRAY_COLOR.darker() : null,
				!done.isEmpty() && !has);
			r.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
			r.setToolTipText(tip(t.get("name").getAsString(),
				new String[]{"Tier", "Where", "Task"},
				new String[]{t.get("tier").getAsString(),
					t.get("monster").getAsString(), t.get("task").getAsString()}));
			r.addMouseListener(clicker(() -> openSheetPage("combat")));
			p.add(r);
			p.add(ghostRow(t.get("task").getAsString(), ""));
		}
		for (String[] d : diaryHits)
		{
			// The tier alone on the right, and the region down on the ghost line
			// with the requirement: a diary entry has no name, so the task text IS
			// the hit, and a region taking a third of the row cut it to "Purchase
			// ..." which answers nothing.
			JPanel r = row(d[0], d[2], null);
			r.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
			r.setToolTipText(tip(d[1] + " " + d[2],
				new String[]{"Task", "Needs"},
				new String[]{d[0], d[3].isEmpty() ? "nothing" : d[3]}));
			r.addMouseListener(clicker(() -> openSheetPage("diaries")));
			p.add(r);
			p.add(ghostRow(d[3].isEmpty() ? d[1] : d[1] + " \u00b7 " + d[3], ""));
		}
		return caHits.size() + diaryHits.size();
	}

	/** The sheet, opened straight onto one of its pages. */
	private void openSheetPage(String page)
	{
		applyTab(Tab.HISCORES);
		sheetPage = page;
		rebuild();
	}

	private JPanel buildSearch(String q)
	{
		JPanel p = column();
		String ql = q.toLowerCase(Locale.ROOT);
		int total = 0;
		searchJump = null;
		searchJumpPage = null;

		// The queries that name a VIEW rather than a thing in the record.
		// Offered while they are being typed, so they are found rather than known.
		String kind = ItemKinds.named(q);
		if (!ql.isEmpty() && ("trackers".startsWith(ql) || "info".startsWith(ql)
			|| kind != null))
		{
			p.add(group("Views"));
			if ("info".startsWith(ql))
			{
				JPanel open = row("Info", "what the journal holds", null);
				open.setCursor(java.awt.Cursor.getPredefinedCursor(
					java.awt.Cursor.HAND_CURSOR));
				open.addMouseListener(clicker(this::openInfo));
				p.add(open);
			}
			if ("trackers".startsWith(ql))
			{
				JPanel open = row("All trackers", "every counter in one place", null);
				open.setCursor(java.awt.Cursor.getPredefinedCursor(
					java.awt.Cursor.HAND_CURSOR));
				open.addMouseListener(clicker(this::openAllTrackers));
				p.add(open);
			}
			// A kind is a question about the LOOT, so it opens the board that
			// holds the loot, already narrowed. Typing "run" is enough.
			if (kind != null)
			{
				final String pick = kind;
				JPanel open = row(kind, "every one you have had", null);
				open.setCursor(java.awt.Cursor.getPredefinedCursor(
					java.awt.Cursor.HAND_CURSOR));
				open.addMouseListener(clicker(() -> openLootKind(pick, false)));
				p.add(open);
				// The slayer half as its own row rather than as a setting the
				// reader has to find, and only where the tasks actually paid
				// some of this kind.
				if (everOnTask() && hasKindOnTask(pick))
				{
					JPanel tasks = row(kind, "from slayer tasks", null);
					tasks.setCursor(java.awt.Cursor.getPredefinedCursor(
						java.awt.Cursor.HAND_CURSOR));
					tasks.addMouseListener(clicker(() -> openLootKind(pick, true)));
					p.add(tasks);
				}
			}
			p.add(vgap(6));
		}

		// Trackers, via the registry: every counter is findable by label or key.
		List<Map.Entry<String, Long>> statHits = new ArrayList<>();
		for (Map.Entry<String, Long> e : counters().entrySet())
		{
			if (e.getValue() != 0 && !StatRegistry.hidden(e.getKey())
				&& (e.getKey().toLowerCase(Locale.ROOT).contains(ql)
				|| StatRegistry.label(e.getKey()).toLowerCase(Locale.ROOT).contains(ql)))
			{
				statHits.add(e);
			}
		}
		statHits.sort(Map.Entry.<String, Long>comparingByValue().reversed());
		if (!statHits.isEmpty())
		{
			p.add(group("Trackers"));
			jump(View.STATS);
			for (int i = 0; i < Math.min(4, statHits.size()); i++)
			{
				Map.Entry<String, Long> e = statHits.get(i);
				String v = StatRegistry.isGp(e.getKey()) ? gp(e.getValue()) + " gp" : fmt(e.getValue());
				p.add(row(StatRegistry.label(e.getKey()), v, null));
				total++;
			}
		}

		// Drops: the item aggregates across every source, with its own sources
		// listed underneath it.
		Map<String, long[]> itemAgg = new LinkedHashMap<>();       // name -> {qty, value}
		Map<String, List<String>> itemSrcs = new LinkedHashMap<>();
		for (LocalStore.SourceRow src : sources())
		{
			for (LocalStore.BagItem b : plugin.sourceItems(src.name))
			{
				if (!b.name.toLowerCase(Locale.ROOT).contains(ql))
				{
					continue;
				}
				long[] agg = itemAgg.computeIfAbsent(b.name, k -> new long[2]);
				agg[0] += b.qty;
				agg[1] += b.value;
				itemSrcs.computeIfAbsent(b.name, k -> new ArrayList<>())
					.add(src.name + (b.qty > 1 ? " ×" + fmt(b.qty) : ""));
			}
		}
		List<String> itemNames = new ArrayList<>(itemAgg.keySet());
		itemNames.sort(Comparator.comparingLong((String n) -> itemAgg.get(n)[1]).reversed());
		List<LocalStore.SourceRow> srcHits = new ArrayList<>();
		for (LocalStore.SourceRow r : sources())
		{
			if (r.name.toLowerCase(Locale.ROOT).contains(ql))
			{
				srcHits.add(r);
			}
		}
		srcHits.sort(Comparator.comparingLong((LocalStore.SourceRow r) -> r.value).reversed());
		if (!itemNames.isEmpty() || !srcHits.isEmpty())
		{
			p.add(group("Drops"));
			jump(View.DROPS);
			for (int i = 0; i < Math.min(2, itemNames.size()); i++)
			{
				String name = itemNames.get(i);
				long[] agg = itemAgg.get(name);
				JPanel r = row(name + " ×" + fmt(agg[0]),
					agg[1] > 0 ? gp(agg[1]) + " gp" : "", accent());
				r.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
				final String itm = name;
				r.addMouseListener(clicker(() -> openItem(itm)));
				p.add(r);
				List<String> srcs = itemSrcs.get(name);
				StringBuilder fromLine = new StringBuilder("from ");
				for (int s = 0; s < Math.min(3, srcs.size()); s++)
				{
					fromLine.append(s > 0 ? " · " : "").append(srcs.get(s));
				}
				if (srcs.size() > 3)
				{
					fromLine.append(" · +").append(srcs.size() - 3);
				}
				p.add(ghostRow(fromLine.toString(), ""));
				total++;
			}
			for (int i = 0; i < Math.min(2, srcHits.size()); i++)
			{
				LocalStore.SourceRow r = srcHits.get(i);
				JPanel rr = row(r.name, (r.kc > 0 ? fmt(r.kc) + " kc · " : "") + gp(r.value) + " gp", null);
				rr.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
				final String src = r.name;
				rr.addMouseListener(clicker(() -> openSource(src)));
				p.add(rr);
				total++;
			}
		}

		// Collection log: the whole taxonomy, with your obtained state.
		Obtained ob = obtained(clogNow());
		// One hit per item: obtaining one whip lights every slot that holds it,
		// so the first page carrying it stands in as its address.
		Map<String, String> slotFirstPage = new LinkedHashMap<>();
		Map<String, Boolean> slotGot = new LinkedHashMap<>();
		clogSearch:
		for (Map.Entry<String, Map<String, List<String>>> tab : taxonomy(plugin.gson()).entrySet())
		{
			for (Map.Entry<String, List<String>> pg : tab.getValue().entrySet())
			{
				for (String slot : pg.getValue())
				{
					if (slot.toLowerCase(Locale.ROOT).contains(ql))
					{
						if (slotFirstPage.putIfAbsent(slot, pg.getKey()) == null)
						{
							// Same rule as the Log tab, or a slot known only from a
							// page scrape reads as obtained there and missing here.
							slotGot.put(slot, slotHeld(slot, pg.getKey(), pg.getValue(), ob,
								sharedSlotNames(plugin.gson())));
						}
						if (slotFirstPage.size() >= 4)
						{
							break clogSearch;
						}
					}
				}
			}
		}
		if (!slotFirstPage.isEmpty())
		{
			// The log is a page WITHIN the sheet, so naming the view alone landed
			// the reader on the top of the sheet with their query cleared.
			p.add(group("Collection log"));
			jump(View.SHEET, "log");
			for (Map.Entry<String, String> hit : slotFirstPage.entrySet())
			{
				boolean got = Boolean.TRUE.equals(slotGot.get(hit.getKey()));
				// The page stays in the right hand column, which is what that
				// column means on every other search row. Held or not is said the
				// way the log itself says it, in green and red, rather than by
				// replacing the address with the word "obtained" - which threw
				// away the page this loop had just worked out, and only for the
				// slots the reader HAS.
				p.add(row(hit.getKey(), hit.getValue(),
					got ? ACCENT_SESSION : ACCENT_RED, true));
				total++;
			}
		}

		// The two bundled tables. A combat achievement is found by its name and a
		// diary entry by what it asks for, since a diary entry has no name, and
		// either way the line underneath is the requirement: what the reader was
		// actually asking when they typed it.
		if (ql.length() >= 3)
		{
			total += searchAchievements(p, ql);
		}

		// Journal milestone lines.
		List<JsonObject> feedHits = new ArrayList<>();
		for (JsonObject e : plugin.feedNewest(500))
		{
			if (feedLine(e).toLowerCase(Locale.ROOT).contains(ql))
			{
				feedHits.add(e);
				if (feedHits.size() >= 4)
				{
					break;
				}
			}
		}
		if (!feedHits.isEmpty())
		{
			p.add(group("Journal"));
			jump(View.JOURNAL);
			for (JsonObject e : feedHits)
			{
				long ts = e.has("ts") ? e.get("ts").getAsLong() : 0;
				p.add(row(feedLine(e), ts > 0 ? DAY.format(Instant.ofEpochMilli(ts)) : "", null));
				total++;
			}
		}

		if (total == 0)
		{
			p.add(note("Nothing matches \"" + q + "\" yet."));
		}
		else
		{
			p.add(vgap(6));
			p.add(ghostRow("enter opens the matching view", ""));
		}
		return p;
	}

	// Where Enter lands: the first group that answered sets the view.
	private View searchJump;
	private String searchJumpPage;

	private void jump(View target)
	{
		jump(target, null);
	}

	// Some boards are a page WITHIN a view, and landing on the view without the
	// page drops the reader one step short of what they typed.
	private void jump(View target, String page)
	{
		if (searchJump == null)
		{
			searchJump = target;
			searchJumpPage = page;
		}
	}

	// ------------------------------------------------------------------
	// Feed rendering
	// ------------------------------------------------------------------

	private static String typeOf(JsonObject e)
	{
		return e.has("type") ? e.get("type").getAsString() : "";
	}

	private static String stamp(JsonObject e)
	{
		long ts = e.has("ts") ? e.get("ts").getAsLong() : 0;
		return ts > 0 ? DAY.format(Instant.ofEpochMilli(ts)) : "";
	}

	private static String feedLine(JsonObject e)
	{
		String type = e.has("type") ? e.get("type").getAsString() : "";
		JsonObject d = e.has("data") && e.get("data").isJsonObject()
			? e.getAsJsonObject("data") : new JsonObject();
		switch (type)
		{
			case "PET":
				return "Pet: " + str(d, "petName", "a new companion");
			case "COLLECTION":
				return "Log slot: " + str(d, "itemName", "new item");
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
				}
				if (drops > 0)
				{
					line.append(" · ").append(fmt(drops)).append(" drops");
					if (dropsGp > 0)
					{
						line.append(" (").append(gp(dropsGp)).append(" gp)");
					}
				}
				return line.toString();
			}
			case "SLAYER":
			{
				// killCount is this task's kills; older entries name the task in
				// slayerTask. Don't fall back to "count": that is the lifetime
				// tasks-completed streak, and it prints here as a kill count.
				String t = str(d, "slayerTask", str(d, "task", ""));
				String kc = str(d, "killCount", "");
				return "Task complete" + (t.isEmpty() ? "" : ": " + t)
					+ (kc.isEmpty() ? "" : ", " + kc + " killed");
			}
			default:
				return type.isEmpty() ? "Milestone" : StatRegistry.prettify(type.toLowerCase(Locale.ROOT));
		}
	}

	private static String str(JsonObject o, String key, String fallback)
	{
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : fallback;
	}

	// ------------------------------------------------------------------
	// Small Swing helpers
	// ------------------------------------------------------------------

	private static JPanel column()
	{
		// Single-column GridBag: BoxLayout drifts mixed alignments. The constraint
		// is applied to every child as it is added.
		JPanel p = new JPanel(new java.awt.GridBagLayout())
		{
			private final java.awt.GridBagConstraints gbc = new java.awt.GridBagConstraints();

			{
				gbc.gridx = 0;
				gbc.gridwidth = java.awt.GridBagConstraints.REMAINDER;
				gbc.weightx = 1;
				gbc.fill = java.awt.GridBagConstraints.HORIZONTAL;
			}

			@Override
			protected void addImpl(Component comp, Object constraints, int index)
			{
				super.addImpl(comp, constraints == null ? gbc : constraints, index);
			}
		};
		p.setBackground(ColorScheme.DARK_GRAY_COLOR);
		return p;
	}

	private static JPanel wrapTop(JPanel body)
	{
		// Scrollable that tracks the viewport width. A long label can't widen the
		// view past the panel. Height stays free for vertical scrolling.
		JPanel wrap = new ScrollColumn();
		wrap.setBackground(ColorScheme.DARK_GRAY_COLOR);
		wrap.add(body, BorderLayout.NORTH);
		return wrap;
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
		public int getScrollableUnitIncrement(java.awt.Rectangle r, int o, int d)
		{
			return 16;
		}

		@Override
		public int getScrollableBlockIncrement(java.awt.Rectangle r, int o, int d)
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

	private static JPanel card(String caption)
	{
		JPanel c = cardPlain();
		JLabel cap = new JLabel(caption.toUpperCase(Locale.ROOT));
		cap.setForeground(ColorScheme.LIGHT_GRAY_COLOR.darker());
		cap.setFont(FontManager.getRunescapeSmallFont());
		cap.setAlignmentX(Component.LEFT_ALIGNMENT);
		c.add(cap);
		c.add(vgap(3));
		return c;
	}

	private static JPanel cardPlain()
	{
		// Unbounded width so the card fills the column.
		JPanel c = new JPanel()
		{
			@Override
			public Dimension getMaximumSize()
			{
				return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
			}
		};
		c.setLayout(new BoxLayout(c, BoxLayout.Y_AXIS));
		c.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		c.setBorder(BorderFactory.createEmptyBorder(6, CARD_INSET, 6, CARD_INSET));
		c.setAlignmentX(Component.LEFT_ALIGNMENT);
		return c;
	}

	private static JPanel row(String left, String right, Color color, boolean colorName)
	{
		JPanel r = row(left, right, color);
		if (colorName && color != null)
		{
			((JLabel) ((BorderLayout) r.getLayout())
				.getLayoutComponent(BorderLayout.CENTER)).setForeground(color);
		}
		return r;
	}

	private static JPanel row(String left, String right, Color rightColor)
	{
		JPanel r = new JPanel(new BorderLayout(ROW_GAP, 0));
		r.setOpaque(false);
		r.setAlignmentX(Component.LEFT_ALIGNMENT);
		r.setBorder(BorderFactory.createEmptyBorder(1, ROW_INSET, 1, ROW_INSET));
		JLabel l = new JLabel(left);
		l.setFont(FontManager.getRunescapeFont());
		r.add(l, BorderLayout.CENTER);
		if (right != null && !right.isEmpty())
		{
			JLabel v = new JLabel(right);
			v.setFont(FontManager.getRunescapeFont());
			v.setForeground(rightColor != null ? rightColor : ColorScheme.LIGHT_GRAY_COLOR.darker());
			r.add(v, BorderLayout.EAST);
		}
		return r;
	}

	private JPanel progress(float frac)
	{
		JPanel outer = new JPanel(new BorderLayout());
		outer.setBackground(ColorScheme.SCROLL_TRACK_COLOR);
		outer.setPreferredSize(new Dimension(10, 4));
		outer.setMinimumSize(new Dimension(10, 4));   // see vgap(): min > pref clips rows
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
		JLabel g = new JLabel(name.toUpperCase(Locale.ROOT));
		g.setForeground(accent());
		g.setFont(FontManager.getRunescapeSmallFont());
		g.setAlignmentX(Component.LEFT_ALIGNMENT);
		g.setBorder(BorderFactory.createEmptyBorder(8, 2, 3, 0));
		return g;
	}

	// Wrap width that fits every context a note appears in: the panel is 242,
	// minus its 16px border, the scrollbar, and a card's own 16px insets.
	private static final int NOTE_WIDTH = 190;

	private static JPanel note(String text)
	{
		// Swing's html JLabel measures at one width and paints at another, which
		// clipped note tails all over the panel. A greedy FontMetrics wrap into
		// plain labels reports an exact preferred height.
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setOpaque(false);
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		Font f = FontManager.getRunescapeSmallFont();
		java.awt.FontMetrics fm = p.getFontMetrics(f);
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
			JLabel lab = new JLabel(l);
			lab.setFont(f);
			lab.setForeground(ColorScheme.LIGHT_GRAY_COLOR.darker());
			lab.setAlignmentX(Component.LEFT_ALIGNMENT);
			p.add(lab);
		}
		return p;
	}

	private static Component vgap(int h)
	{
		JPanel p = new JPanel();
		p.setOpaque(false);
		p.setPreferredSize(new Dimension(1, h));
		// Min must not exceed preferred: when a child is wider than the viewport
		// GridBagLayout recomputes row heights from minimum sizes, and a childless
		// panel's default minimum is 10px, which clips the last row.
		p.setMinimumSize(new Dimension(1, h));
		p.setMaximumSize(new Dimension(Integer.MAX_VALUE, h));
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		return p;
	}

	// Rows are transparent (see row()), so a lift has to take the colour of the
	// card behind them, paint it, and hand the transparency back on the way out.
	private static final int HOVER_LIFT = 15;

	/** The colour actually painted behind a component, which a row never paints itself. */
	private static Color behind(Component c)
	{
		for (Component p = c.getParent(); p != null; p = p.getParent())
		{
			if (p.isOpaque() && p.getBackground() != null)
			{
				return p.getBackground();
			}
		}
		return ColorScheme.DARKER_GRAY_COLOR;
	}

	/**
	 * What a surface reads as under the cursor.
	 *
	 * <p>The client names a hover colour for each of its two grounds, and they
	 * are not a lightening by some amount: DARKER_GRAY hovers to 60 and DARK_GRAY
	 * hovers to 35, which is DARKER. Using its values means a hovered row here
	 * reads the way a hovered row does anywhere else in the client.
	 *
	 * <p>This used to lift the colour of whatever was BEHIND the component by a
	 * fixed fifteen. Two things were wrong with that. A row sitting on a card
	 * came out at 45 and a cell sitting in a grid came out at 55, so one panel
	 * had two hover colours depending on what a thing happened to be sitting in;
	 * and neither was a colour the client uses, so both read as approximately
	 * right and exactly nothing.
	 */
	private static Color hoverOf(Color ground)
	{
		if (ColorScheme.DARKER_GRAY_COLOR.equals(ground))
		{
			return ColorScheme.DARKER_GRAY_HOVER_COLOR;
		}
		if (ColorScheme.DARK_GRAY_COLOR.equals(ground))
		{
			return ColorScheme.DARK_GRAY_HOVER_COLOR;
		}
		return new Color(
			Math.min(255, ground.getRed() + HOVER_LIFT),
			Math.min(255, ground.getGreen() + HOVER_LIFT),
			Math.min(255, ground.getBlue() + HOVER_LIFT));
	}

	// Everything clickable in the panel is wired through here, so the hover is
	// too: a row that goes somewhere answers the cursor, and nothing is drawn for
	// it at rest. The hand cursor alone was a one-pixel tell on a dark panel and
	// readers were not finding the drills.
	/**
	 * One axis of a board, showing the reading it is on. Clicking flips it.
	 *
	 * <p>The pair of pills this replaces spent its dark half naming the reading
	 * the reader had not chosen, which is the one thing on a 225px board nobody
	 * needs to be told.
	 */
	private JPanel toggle(String reading, Runnable flip)
	{
		JPanel cell = new JPanel(new BorderLayout());
		cell.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		cell.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
		JLabel l = new JLabel(reading, JLabel.CENTER);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(accent());
		cell.add(l, BorderLayout.CENTER);
		cell.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		cell.addMouseListener(clicker(flip));
		return cell;
	}

	/**
	 * Whether the pointer is still over the component an exit was delivered for.
	 *
	 * <p>Crossing onto a child fires an exit on the parent while the pointer has
	 * not left it, and the row must stay lit. The event's own coordinates are not
	 * a reliable way to tell: on a quick move off the edge of a grid the exit can
	 * be stamped with a point that still falls inside, and the cell was then left
	 * lit with nothing left to put it back, which is how a sheet ends up with
	 * half a dozen tiles glowing at once.
	 *
	 * <p>So Swing is asked instead, and only where there is a pointer to ask
	 * about: getMousePosition is null both when the pointer is elsewhere and when
	 * there is no pointer at all, and those are opposite answers.
	 */
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
			// headless, or no pointer device: neither question can be answered,
			// and both of these throw rather than returning nothing
		}
		return stillUnder(over, pointerKnown, e.getComponent().contains(e.getPoint()));
	}

	/**
	 * The decision itself, separated from the asking.
	 *
	 * <p>Both questions above throw where there is no display, so the rule could
	 * not otherwise be tested at all: the headless case is the one that falls
	 * back to the event's own coordinates, which is exactly the case worth
	 * pinning down.
	 */
	static boolean stillUnder(boolean overComponent, boolean pointerKnown,
		boolean eventSaysInside)
	{
		if (overComponent)
		{
			return true;    // the pointer is demonstrably on it
		}
		if (pointerKnown)
		{
			return false;   // there is a pointer, and it is somewhere else
		}
		return eventSaysInside;
	}

	/**
	 * The one tile currently wearing a hover, and how to take it off again.
	 *
	 * <p>mouseExited is not a promise. A tooltip drawn over the tile can leave
	 * getMousePosition non-null, so the exit is declined as "still inside"; the
	 * pointer then leaves while the tooltip has it, no second exit is ever
	 * delivered, and the tile stays lit with nothing to put it back. It also
	 * goes missing when a component is taken out of the hierarchy under the
	 * cursor, which a redraw does constantly.
	 *
	 * <p>So lighting is not left to depend on it. At most one tile is lit at a
	 * time and lighting any tile puts the last one back, which corrects a stuck
	 * one the moment the reader touches anything else; and a redraw sweeps it,
	 * which corrects the rest.
	 */
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

			@Override
			public void mousePressed(MouseEvent e)
			{
				r.run();
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				if (lit || !(e.getComponent() instanceof javax.swing.JComponent))
				{
					return;
				}
				// whatever was lit before this is not under the pointer now
				unlight();
				javax.swing.JComponent c = (javax.swing.JComponent) e.getComponent();
				target = c;
				wasOpaque = c.isOpaque();
				wasBackground = c.getBackground();
				// Its OWN ground where it paints one, and what it sits on where it
				// does not. Taken from the parent either way, a tile that paints
				// itself darker than its grid was hovered as though it were the
				// grid, which is why the boss sheet lit differently from the rows.
				Color ground = wasOpaque && wasBackground != null
					? wasBackground : behind(c);
				c.setBackground(hoverOf(ground));
				c.setOpaque(true);
				c.repaint();
				lit = true;
				litNow = this::putBack;
			}

			/** Undo the hover, from wherever the undoing is noticed. */
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

			private javax.swing.JComponent target;

			@Override
			public void mouseExited(MouseEvent e)
			{
				// Crossing onto a child fires an exit on the parent while the
				// pointer is still inside it, and the row must stay lit. Asked of
				// Swing rather than of the event: contains(getPoint()) reads the
				// coordinates the exit was stamped with, which on a fast move off
				// the edge of a grid can still fall inside, and the cell was then
				// left lit with nothing to put it back. getMousePosition is null
				// exactly when the pointer is not over the component.
				if (!lit || !(e.getComponent() instanceof javax.swing.JComponent))
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

	// ------------------------------------------------------------------
	// Formatting
	// ------------------------------------------------------------------

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

	/**
	 * Experience, in a cell about fifty pixels wide.
	 *
	 * <p>gp() keeps the grouped figure up to ten thousand, which is right for a
	 * purse: nobody wants their bank read as 9.7k. In a skill cell it put
	 * "+4,600" beside "+96k" in the same grid, two registers for the same kind
	 * of thing. This has one rule from a thousand up, and the cell's own room is
	 * what sets where that rule starts.
	 */
	private static String xpShort(long n)
	{
		long a = Math.abs(n);
		if (a >= 1_000_000_000L)
		{
			return String.format(Locale.UK, "%.2fB", n / 1_000_000_000.0);
		}
		if (a >= 1_000_000L)
		{
			return String.format(Locale.UK, "%.1fM", n / 1_000_000.0);
		}
		if (a >= 100_000L)
		{
			return String.format(Locale.UK, "%dk", n / 1_000);
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
