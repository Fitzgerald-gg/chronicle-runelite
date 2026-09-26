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
	// the time of day a sitting began, on the Now caption
	private static final DateTimeFormatter CLOCK =
		DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());
	private static final DateTimeFormatter TASK_DAY =
		DateTimeFormatter.ofPattern("d MMM yy", Locale.UK).withZone(ZoneId.systemDefault());
	private static final DateTimeFormatter FULL_DAY =
		DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK).withZone(ZoneId.systemDefault());
	private static final DateTimeFormatter MONTH_YEAR =
		DateTimeFormatter.ofPattern("MMMM yyyy", Locale.UK).withZone(ZoneId.systemDefault());
	// The client's two grounds: the panel's own, and a card's.
	private static final Color DARK = ColorScheme.DARK_GRAY_COLOR;
	private static final Color DARKER = ColorScheme.DARKER_GRAY_COLOR;
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
		HOME, DROPS, SLAYER, LOG, STATS, HISTORY, JOURNAL, KILLS, SHEET, RECAP
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
		RECORD, STANDING, LOOT, TRACKERS
	}

	private static final Map<Tab, String[]> SUBS = new EnumMap<>(Tab.class);

	static
	{
		// Named for what it holds rather than for its first child: two of the
		// three are lifetime, and a tab called "This session" whose Journal lists
		// other sittings is a worse lie than the scrolling it was meant to fix.
		SUBS.put(Tab.RECORD, new String[]{"Now", "Journal", "Ledger", "Recap"});
		// The bosses and the skills were two boards built from ONE widget that
		// behaved in opposite ways on a click: a boss opened a card under its own
		// grid row, a skill took over the screen. They are one sheet now, in the
		// order the game's own hiscores panel puts them, which is the layout a
		// player already knows: the skills, the combat and total levels, the
		// activities, then the bosses.
		// No sub-tabs: the sheet is one board. Its activity tiles are the way into
		// the collection log, the diaries, the combat achievements and the quests,
		// which is what freed a whole tab for the loot.
		SUBS.put(Tab.STANDING, new String[0]);
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
	// the sub-tab each tab was last left on, so coming back lands where you were
	private final Map<Tab, String> subByTab = new EnumMap<>(Tab.class);
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
	// The counts-of-the-record page, behind the Journal's nameplate: it answers
	// questions about the JOURNAL rather than about the account, and a tab for
	// it would be a tab most readers never want.
	private boolean showInfo;
	// The two other pages a reader is sent to: the records book behind the
	// Total level cell, and the calendar behind the nameplate's Days written.
	private boolean showRecords;
	private boolean showCalendar;
	private YearMonth calendarMonth = YearMonth.now();
	private final java.util.ArrayDeque<String[]> detailStack = new java.util.ArrayDeque<>();
	private String statsFamily = StatRegistry.FAMILIES[0];
	private int dropsShown = ROW_CAP;
	private String clogTab = "Bosses";
	private String clogPageSel;

	// Whether the journal is reaching disk. Nothing else in the panel shows it:
	// the views are served from memory and look the same either way.
	/**
	 * The one row of the chrome that reports rather than acts, and that only
	 * exists while it has something to report.
	 *
	 * <p>It used to be a green pip reading "logging", on every board, always,
	 * and it carried no information: the plugin is always logging while the
	 * panel is open, so the pip said "I exist". The only content it ever had was
	 * the absence of gold or red - and absence is expressed by an absent row.
	 * So it is hidden while nothing is wrong, and when something is it becomes
	 * a band: a tinted full-width strip with the words on it, amber for a plugin
	 * we capture through being switched off and red for the journal not saving.
	 * The amber one fixes itself on click.
	 */
	private final JPanel band = new JPanel(new BorderLayout());
	private final JLabel bandText = new JLabel();
	// the tab opens on the whole record; a window is a narrowing of it
	private String histGranularity = "Lifetime";
	// The period's END date (inclusive); the stepper moves it by one granule.
	private LocalDate histCursor = LocalDate.now();
	// Exact dates: non-null overrides the granularity pills. Set by clicking the
	// period label, cleared by any pill.
	private LocalDate histFrom;
	private LocalDate histTo;
	// The bundled taxonomy: tab -> page -> ordered slot names. Parsed lazily, the
	// first time any board asks for it.
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

		// ── search ────────────────────────────────────────────────────────
		searchField.setIcon(IconTextField.Icon.SEARCH);
		searchField.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 16, 28));
		searchField.setBackground(DARKER);
		searchField.setHoverBackgroundColor(ColorScheme.DARK_GRAY_HOVER_COLOR);
		searchDebounce = new Timer(150, e -> onSearchChanged());
		searchDebounce.setRepeats(false);
		// Enter opens the first row on screen. The list is the resolver: it is
		// built top-down as Views, Trackers, Drops, Collection log, Achievements,
		// Journal, and a second resolver here with an order of its own sent
		// Enter somewhere the reader could not see.
		searchField.addActionListener(e ->
		{
			if (searchQuery().isEmpty())
			{
				return;
			}
			// typed and entered inside the debounce: draw the list first
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
		// ── tabs, then search ──
		periodHolder.setBackground(DARK);
		// The strip's other rows centre themselves; a LEFT aligned holder among
		// them is pushed right by BoxLayout and loses the width its label needs,
		// which is how "September 2026" came to draw as "September 20...".
		periodHolder.setAlignmentX(Component.CENTER_ALIGNMENT);
		// BoxLayout hands a component its maximum, and a JPanel's default maximum
		// is its preferred, which for an empty holder is nothing at all. Left to
		// that, the row would never take a pixel of the strip.
		periodHolder.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
		periodHolder.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 16, 22));
		spaced(north, periodHolder, 3);

		tabGroup.setLayout(new GridLayout(1, 4, 2, 0));
		// Each tab wears what it holds: a ledger page for the written record, the
		// game's own skills chart for where the account stands, a chest for the
		// loot and a tally for the counters. "Hiscores" promised a ranking against
		// other players, and there is none; the swords it wore dated from when
		// the board was PvM alone.
		addTab("tab_record.png", "Record", Tab.RECORD);
		addTab("tab_standing.png", "Standing", Tab.STANDING);
		addTab("tab_loot.png", "Loot", Tab.LOOT);
		addTab("tab_trackers.png", "Trackers", Tab.TRACKERS);
		spaced(north, tabGroup, 7);
		spaced(north, searchField, 8);

		add(north, BorderLayout.NORTH);
		add(display, BorderLayout.CENTER);

		// Hung once. Every rebuild swaps what is inside the view, so the viewport
		// keeps the reader's position by simply never being told to move, and the
		// bar keeps its own faded-out state instead of being born again at full
		// brightness on every push.
		canvas.setBackground(DARK);
		scrollPane.setBorder(null);
		scrollPane.getVerticalScrollBar().setUnitIncrement(14);
		overlayBar(scrollPane);
		display.add(scrollPane, BorderLayout.CENTER);

		// Home refreshes on a slow tick while it is the visible view. Nothing in
		// the panel rebuilds per game tick.
		homeTicker = new Timer(3000, e ->
		{
			// A page opened from Home leaves the view on HOME, and a tick rebuilds
			// the whole board every three seconds under a reader. On the trackers
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

		band.setAlignmentX(Component.CENTER_ALIGNMENT);
		band.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
		band.setBorder(pad(3, 8, 3, 8));
		bandText.setFont(small());
		band.add(bandText, BorderLayout.CENTER);
		band.setVisible(false);
		north.add(band);

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

	/** A tab pill: lit in the accent when on, and a tooltip where there is one. */
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
				return Tab.STANDING;
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
	private static synchronized List<Boss> bossRoster(Gson gson)
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
		catch (Exception ex)   // a missing resource leaves the board empty
		{
			// the same silence the taxonomy keeps: an empty board, not a stack trace
		}
		bossRoster = out;
		return out;
	}

	// The three fight tables below are bundled in panel_fights.json.
	private static final JsonObject FIGHTS = table("panel_fights.json");

	/**
	 * Where the hiscores and the collection log name one fight differently. One
	 * page counts both Gauntlets, and each of them is its own row on the board.
	 */
	private static final Map<String, String> LOG_PAGE_FOR = strMap(FIGHTS, "logPage");
	// Where a fight's takings are filed under another name entirely. NOT the
	// creatures inside it -- a crystalline bear's shards are not the
	// Gauntlet's loot -- but the payout at the end of it, which the ledger
	// files against the fight that hands it over. The loot tracker names the
	// NPC that dropped it rather than the encounter, so the Guardians' loot
	// sits under Dusk and the Titans' under whichever king fell last.
	private static final Map<String, List<String>> PAYS_OUT = new LinkedHashMap<>();
	// A fight keyed by kind, against the NPC names its minutes are filed under.
	//
	// The minutes are filed under the NPC the hit landed on, and half the
	// roster is not named after one: a raid is named for the place, Barrows
	// for the chest at the end of it, and a few fights are named for what
	// the log calls them rather than what stands there. Everything in it is
	// PART OF THE FIGHT -- the boss under another name, or a minion that is
	// only alive during it -- so its minutes are that fight's minutes. A boss and
	// the things it calls up: alive only inside the fight, so the minutes spent
	// on them were spent on it.
	private static final Map<String, List<String>> FOUGHT_AS = new LinkedHashMap<>();

	static
	{
		obj(FIGHTS, "paysOut").entrySet().forEach(e -> PAYS_OUT.put(e.getKey(), strs(e.getValue())));
		obj(FIGHTS, "foughtAs").entrySet().forEach(e ->
			FOUGHT_AS.put(kindOf(e.getKey()), strs(e.getValue())));
	}

	/**
	 * A table bundled beside the class, read once at class load. A missing
	 * resource reads as an empty table, as a missing roster does.
	 */
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

	// A bundled list of names, in the order it was written; empty where absent.
	private static List<String> strs(JsonElement a)
	{
		List<String> out = new ArrayList<>();
		if (a != null)
		{
			a.getAsJsonArray().forEach(n -> out.add(n.getAsString()));
		}
		return out;
	}

	// A bundled name-to-name table, in the order it was written.
	private static Map<String, String> strMap(JsonObject t, String key)
	{
		Map<String, String> out = new LinkedHashMap<>();
		obj(t, key).entrySet().forEach(e -> out.put(e.getKey(), e.getValue().getAsString()));
		return out;
	}

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
	private long bossKills(String name)
	{
		JsonObject cl = clogNow();
		long best = Math.max(0, lookup(cl, "slayer_kcs", name));
		String kind = kindOf(name);
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

	/**
	 * A count out of one of the clog snapshot's maps, found without caring how the
	 * key was cased. The journal writes page names as the game spells them, but
	 * nothing guarantees that of an imported or older snapshot, and a board that
	 * silently reads zero for a boss it has the count for is worse than a slow
	 * one. Minus one where the map has no such key at all.
	 */
	private static long lookup(JsonObject clog, String map, String key)
	{
		if (clog == null)
		{
			return -1;
		}
		JsonElement v = getIgnoreCase(obj(clog, map), key);
		return v == null ? -1 : safeLong(v);
	}

	/** A member by its key as written, else by its key in any case; null for neither. */
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
			rolledKcs = new LinkedHashMap<>();
			// The sitting keeps its own entry in this same shape, so it is
			// counted the same way rather than declining to answer.
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

	/**
	 * Whether any cell on the board is counted off the roll rather than the
	 * spine, and the roll does not reach the window's start. Those cells are a
	 * floor over part of the window, which the board has to say once at the top
	 * rather than leave as a number meaning something different from its
	 * neighbours.
	 */
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

	/**
	 * Every counter this fight's log page carries, by the name the log gives it.
	 * They belong on the card because they answer a different question than the
	 * kill count does: Wintertodt's "Rewards claimed" said 1,078 where 447 were
	 * killed. A counter that only repeats the kill count is left out, however
	 * many of them the page holds, and a line naming another fight has already
	 * gone to that fight in {@link #lineBelongsTo}, so the Gauntlet's card never
	 * carries the Corrupted Gauntlet's completion count.
	 */
	private List<Entry<String, Long>> logLines(String boss)
	{
		List<Entry<String, Long>> out = pageLines(boss, "kc_lines");
		// A line that only restates the count the card already carries is noise.
		// What is worth reading beside it is a line counting something ELSE:
		// Wintertodt's rewards claimed against its kills.
		long kills = bossKills(boss);
		out.removeIf(ln ->
		{
			String said = low(ln.getKey());
			return ln.getValue() == kills
				&& (said.contains("kill") || said.contains("completion"));
		});
		return out;
	}

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

	/**
	 * The page's lines that are this fight's, as the page wrote them: from
	 * kc_lines, or from pb_lines for its best times in seconds.
	 */
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
		return killKinds().contains(kindOf(name))
			|| taskKillsEver().containsKey(name);
	}

	// Per build: every kind the record knows to be killed rather than searched,
	// gathered or opened. Built once instead of walking the roster and the Kill
	// Log again for every row of a board.
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

	/** One source's takings, openable. */

	/** "Reward cart (Wintertodt)" is Wintertodt's, and is not a boss of its own. */
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

	/** m:ss, or h:mm:ss past the hour. */
	private static String clock(long seconds)
	{
		long h = seconds / 3600;
		long m = (seconds % 3600) / 60;
		long s = seconds % 60;
		return h > 0 ? String.format(Locale.UK, "%d:%02d:%02d", h, m, s)
			: String.format(Locale.UK, "%d:%02d", m, s);
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
		// A line naming no fight at all belongs to whoever the page is: "Personal
		// Best" on the Gauntlet's page is the Gauntlet's. A fight read off
		// somebody else's page has to be named to claim one, and a qualifier that
		// names it is enough -- "Personal Best Corrupted" never says Gauntlet.
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
		String n = name == null ? "" : low(name.trim());
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

	/**
	 * The activities band, which is also the way into everything the sheet does
	 * not draw itself.
	 *
	 * <p>label, the source it reads, and the page a click opens or "" for a tile
	 * that is only a figure. Four of these carry a destination, and between them
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
	 * with no drop source behind them at all - Collections, Quests and Diaries -
	 * fell through to a generic tab icon and were indistinguishable from each
	 * other.
	 *
	 * <p>Four of the six ARE hiscores rows, so they carry their own art and it
	 * is the same art the official panel draws; taking it from the enum means it
	 * follows the client rather than a number written down here. The other two
	 * are not on the hiscores and take the game's own tab icons.
	 */
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
		JPanel grid = grid3();
		for (String[] a : ACTIVITIES)
		{
			grid.add(activityCell(a[0], a[1], a[2]));
		}
		spaced(p, grid);
		return p;
	}

	/**
	 * An activity tile's door: its loot, where the ledger holds any, else its
	 * page of the collection log. Soul Wars pays in zeal, which is spent rather
	 * than dropped, so its tile opened a loot page saying the journal had no
	 * drops from it; the page that says what it has given is the log's.
	 */
	private void openActivity(String source)
	{
		if (resolveSourceNamed(source) == null && openLogPage(source))
		{
			return;
		}
		openSourceLoose(source);
	}

	/**
	 * The collection log, opened on one of its pages under the tab that holds
	 * it; false, and nothing opened, when no tab has a page of that name.
	 */
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
			// The tile says what it is named for. The Rift's page opens on rifts
			// searched, which is the count the log keeps for the page, and a
			// tile wearing the hiscores' Rifts closed read that number: 5,218
			// beside a page that says 2,073 were closed.
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
			// The tiles with no board of their own ARE loot sources - the rift's
			// rewards, Soul Wars' - and the figure they carry is that source's
			// count. So they open it, rather than being the only things on the
			// sheet that say a number and do nothing when you press them.
			link(cell, () -> openActivity(source));
		}
		cell.add(icon, BorderLayout.WEST);
		long moved = activityMoved(label, source);
		boolean lit = figure > 0 && moved != 0;
		JLabel fig = styled(new JLabel(figure > 0 ? fmt(figure) : "-", JLabel.RIGHT), small(),
			lit ? TILE_LIT : dim());
		// Standing over movement, which is the shape the skill cells beside it
		// use. A tile going bright said only THAT the period moved it, and the
		// reader had to hold two visits to the sheet in their head to work out by
		// how much.
		//
		// Always CENTER, one row or two. BorderLayout gives WEST and EAST their
		// preferred widths and lets them overlap on a narrow row; CENTER takes
		// what the icon leaves. Putting the moved ones in one and the still ones
		// in the other would have them sitting at different widths in one grid.
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

	/**
	 * The note for a window that closes before the record began keeping kill
	 * counts, or counters: null where it was keeping them.
	 *
	 * <p>The imported past carries skills and nothing else. A board reading a
	 * window inside it found no kill moved and said nothing was killed, which
	 * is a claim about the account the record cannot make: it was not counting.
	 */
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
			return noted(p, "The boss roster did not load.");
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
				String unkept = notCounting(true);
				return noted(p, unkept != null ? unkept : "Nothing on the boss sheet was killed inside "
					+ periodInSentence() + ".");
			}
			roster = had;
		}
		// One grid, no longer split around an opened cell: what that cell used to
		// expand into is the hover now, so nothing is inserted mid-sheet and
		// nothing below it moves when a boss is pressed. Built before anything is
		// added, because building is what discovers whether a cell had to fall
		// back to the roll.
		JPanel opening = bossSheet(roster);
		LocalDate shortFrom = rollUsed ? rollShortOf() : null;
		if (shortFrom != null)
		{
			// A cell the spine cannot date is counted from what the kills
			// dropped, and that reaches back only so far. Said once at the top
			// rather than left as a number meaning something its neighbours do
			// not: those cells are a floor, over part of the window.
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

		JLabel icon = new JLabel();
		if (b.sprite > 0)
		{
			// 24, not the sprite's own 25: a cell leaves about 32px beside the
			// icon and a five figure count needs 32 of them.
			wearSprite(icon, b.sprite, 24, 24);
		}
		cell.add(icon, BorderLayout.WEST);

		JLabel fig = styled(new JLabel(kc > 0 ? fmt(kc) : "-", JLabel.RIGHT), small(),
			kc > 0 ? TILE_LIT : dim());
		cell.add(fig, BorderLayout.EAST);
		// Straight to the loot, which is where a reader pressing a boss means to
		// go, and not always a page of its own name: a skilling boss pays out
		// through a cart or a pool.
		final String open = bossLootSource(b);
		link(cell, () -> openSourceLoose(open));
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
		String kind = kindOf(b.name);
		SourceRow src = null;
		// What the fight is paid out through as well as the fight itself. A
		// skilling boss hands its loot over in a container -- "Reward cart
		// (Wintertodt)", "Reward pool (Tempoross)", the casket beside it -- and
		// looking only for a source of the boss's own name found none of it, so
		// four and a half million gp sat in the journal under a card saying no
		// loot had reached it.
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
		// The period first, on a narrowed board: the cell prints the window's
		// kills and this card said only lifetimes, with no word saying so.
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
		// the best time, which is a time
		for (Entry<String, Long> pb : pageLines(b.name, "pb_lines"))
		{
			labels.add(pb.getKey());
			figures.add(clock(pb.getValue()));
		}
		// and the ordinary time, over the kills the timer spoke for
		double[] timed = wholeRecord() && src != null ? new double[]{src.timed, src.timeSum}
			: sourceTimesInWindow(b.name);
		if (timed[0] > 0)
		{
			labels.add("Average kill");
			figures.add(pb(timed[1] / timed[0]) + " · " + fmt((long) timed[0]) + " timed");
		}
		// Only where the minutes cover the whole period, as on the page it opens.
		long here = minutesAt(b.name, wholeRecord() ? counters() : periodCounters());
		if (here > 0 && minutesCoverPeriod())
		{
			labels.add("Time here");
			figures.add(hoursMinutes(here));
		}
		// what the page itself counts, which need not be kills at all
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

	private String paidFigure(SourceRow r)
	{
		return fmt(tallyOf(plugin.sourceItems(r.name))[0]) + " \u00b7 " + gps(r.value);
	}

	/**
	 * Where a boss's loot actually lives, which is not always under its own name:
	 * a skilling boss pays out through a cart or a pool, and that is the page a
	 * reader pressing the tile wants.
	 */
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
		// the dearest of the names it pays out through
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

	/** Whether a fight's takings are filed under {@code source}, a name of its own NPC. */
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
			// Or the reader has hold of the scroll bar. The pane is kept, but the
			// body swap under it can still change the bar's extent under a drag,
			// so the redraw waits until the thumb is let go. Both are owed and paid
			// by the timer, which is what that timer is now mostly for.
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
	private Point lastPointer;

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
			// headless, or the panel is not on screen: not being read either way
			now = null;
		}
		lastPointer = now;
		return now != null && now.equals(was);
	}

	/** Whether the reader has hold of the scroll bar this instant. */
	private boolean scrollHeld()
	{
		JScrollPane pane = scrollPane;
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
		milestones = null;
		landedSlots = null;
		records = null;
		daysPlayed = null;
		dayTotals = null;
		// Set only by buildStats, but read by the all-trackers board too. Left
		// standing it carried one period's dropped figure onto another period's
		// gathered row, and stepping the period never moved it.
		resourcesDropped = 0;
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
		// Cleared in addKinds too, and for the same reason those three above are
		// here: that board is not the only thing that asks for them, and it is
		// not the first thing drawn.
		skilled = null;
		ledgerNames = null;
		sourceKinds.clear();
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
		paintBand(plugin.journalWarning(), plugin.captureWarning(),
			plugin.captureWarningWhy());
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

	/** The board the current view draws when no overlay covers it. */
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

	// The strip of sub-tabs above the board, held by name so it can be taken down
	// without taking the scroll pane with it.
	private JPanel aboveBoard;

	private void onSearchChanged()
	{
		// a group opened for one query is closed for the next
		drillShown.keySet().removeIf(k -> k.startsWith("search:"));
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

	// Whether a press on the band can put the thing right. Only the amber
	// state can: a plugin can be switched back on from here, a full disk cannot
	// be emptied from here.
	private boolean bandFixes;

	/**
	 * Show the band, or hide it, for what is wrong.
	 *
	 * <p>Worst first. A disk that will not take the journal is red and stops
	 * everything; a plugin we capture through being switched off is amber and
	 * loses one kind of capture, which the reader can fix with the same click
	 * that tells them about it.
	 */
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
		// The press is hung only while it can do something, and taken down when
		// it cannot: a red band with a listener and no hand cursor is a control
		// that lies about itself twice. Tooltip and click both on the band and
		// neither on the label - a tooltip registers a mouse listener, and a
		// label wearing one swallows the click meant for the row it sits in.
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

	/** A colour laid thinly over the panel's ground: the band's tint. */
	private static Color wash(Color c)
	{
		Color g = DARK;
		double a = 0.22;
		return new Color(
			(int) Math.round(g.getRed() + (c.getRed() - g.getRed()) * a),
			(int) Math.round(g.getGreen() + (c.getGreen() - g.getGreen()) * a),
			(int) Math.round(g.getBlue() + (c.getBlue() - g.getBlue()) * a));
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

	// A row that doubles as a fold head: the pinned xp total, the damage, the
	// Deaths. Closed it is the row it has always been; open, the name takes the
	// accent this panel's other fold heads use. The state is a key in the panel's
	// one fold register, same as every other fold: the home ticker rebuilds every
	// three seconds and would otherwise shut the fold on the reader between one
	// glance and the next.
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
			JPanel r = row(g.skill.getName(), right);
			r.setBorder(pad(1, 10, 1, 2));
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
			spaced(p, note(stalled));
		}

		ChronicleEventCapture.SlayerView task = plugin.slayerView();
		if (task != null && plugin.slayerSeenThisSession())
		{
			addTaskCard(p, task, "Slayer task", ACCENT_SESSION);
		}

		// The session strip: pinned rows, then whatever else moved, ranked. The
		// caption says when the sitting began and how long it has run, which the
		// board named for the sitting never said; the ticker keeps it current.
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
				// damage carries its own split, the way xp carries its skills:
				// the three styles are that one figure broken up, not three more
				// trackers, so they open from it rather than standing beside it
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
			// the kills whose loot was all picked up: the loot events less the kills
			// that left a stack behind, one unit both ways. "Left behind" below
			// counts stacks, so it is not what this subtracts.
			//
			// Drawn only where it DIFFERS from the line above it. Taking
			// everything is the ordinary case, and there it repeated the received
			// count verbatim - the same number twice, one under the other, saying
			// nothing the first had not. It appears exactly when Left behind
			// does, so the two arrive together and mean something, or neither is
			// on screen.
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
		// Everything else the session moved, one row to a tracker, under the
		// family it belongs to. Where a tracker has a parent total the parent is
		// the row: a herb sack run says "Herbs sacked" once, and the twelve herbs
		// fold out under that row rather than standing as twelve rows of their own.
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
			// LEFT, to match the caption beside it.
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
	 * What the sitting produced that a player would tell a friend: its levels,
	 * and any log slot or pet that landed. Read off the feed since the sitting
	 * began; before this a level was the one thing Now could not say, and the
	 * reader went to the Journal for it.
	 */
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

	/** A row naming up to two things on its right, and the rest on hover. */
	private JPanel namedRow(String label, List<String> names, Color color)
	{
		String right = names.size() <= 2 ? String.join(" · ", names) : "+" + names.size();
		JPanel r = row(label, right, color);
		r.setToolTipText(String.join(" · ", names));
		return r;
	}

	/**
	 * The rest of what the session moved, under quiet family headings, one row to
	 * a tracker. A heading carries how many trackers it holds and folds away on a
	 * click; it holds no figure of its own, since the sum of an arrow shaft and a
	 * herbiboar is not a number anybody wants. Returns the lines mounted.
	 */
	private int addSessionMovers(JPanel strip, Map<String, Integer> sess,
		Set<String> shownKeys)
	{
		Map<String, List<Entry<String, Long>>> byFamily = new LinkedHashMap<>();
		// what each parent is standing for, so its row can open on them
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
			boolean open = !foldOpen(stateKey);   // these stand open; the fold shuts them
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

	/**
	 * One tracker's line. A tracker standing for others, the herb sack for its
	 * herbs and the teleport total for the places it reached, opens on them: the
	 * parent is what the session moved, and the breakdown is what it moved it on.
	 * What the parent counted and its children could not name reconciles as
	 * "Other" rather than going missing. Returns the lines mounted.
	 */
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


	/**
	 * A heading that names a band and folds it away. It is structure, not a
	 * figure, so it stays quiet in both states and the accent is left to mean one
	 * thing: what this session earned. Open, the rows beneath speak for it and it
	 * carries nothing; shut, it says how many rows it is holding.
	 */
	private JPanel quietHead(String name, String count, String stateKey)
	{
		// subHead's heading, upper case and not indented
		JPanel head = subHead(name.toUpperCase(Locale.ROOT), count, stateKey);
		head.setBorder(pad(7, 2, 1, 2));
		return head;
	}

	// one tracker, one line: its name and its figure, in the one register every
	// other line in the strip uses
	private JPanel sessionRow(String key, long v)
	{
		return row(StatRegistry.label(key),
			StatRegistry.isGp(key) ? gps(v) : fmt(v));
	}

	private static final String UNDATED = "No loot has been dated yet. The roll keeps one entry a "
		+ "day and starts with the next drop that lands.";

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
		// The roll keeps the window's ITEMS beside its sources, so the kind lens
		// answers a period with the same two screens it draws over the whole
		// ledger: what the month paid in runes, rather than what every month did.
		if (!dropsLeftBehind && dropsByKind)
		{
			if (w.items.isEmpty())
			{
				return noted(p, "Nothing taken inside " + periodInSentence() + ".");
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
				+ gps(safeParse(r[2])));
			final String name = r[0];
			// the drill is that source's or item's whole record, which is a
			// different screen and says so by carrying its own dateline
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

	/**
	 * The roll's own rows as a bag. The roll holds a name, a count and a worth
	 * per item and no id, which is all the kind lens reads: the taxonomy is
	 * keyed by name, and so is the drill the row opens.
	 */
	private static List<BagItem> bagOf(List<String[]> rows)
	{
		List<BagItem> bag = new ArrayList<>();
		for (String[] r : rows)
		{
			bag.add(new BagItem(0, r[0], safeParse(r[1]), safeParse(r[2])));
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
				: startMs(LocalDate.now()),
				System.currentTimeMillis()};
		}
		Window w = window();
		return new long[]{
			startMs(w.start),
			startMs(w.end.plusDays(1)) - 1};
	}

	// Whether the loot on show is narrowed to what slayer tasks logged. A lens,
	// like the two beside it, and READ ONLY by a board that also draws the
	// control: a number that changes with no control on screen to explain why is
	// worse than a number the reader cannot narrow at all.
	private boolean onTaskOnly;


	/** What the tasks paid inside the period, as a bag the kind lens can read. */
	private List<BagItem> onTaskBag()
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

	// Answered once per rebuild, like the sources and the collection log above.
	// A board of two hundred rows asks whether each of them has an on-task side,
	// and the answer is one pass over ninety three tasks.
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
		List<JPanel> axes = new ArrayList<>();
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
		lens.setBackground(DARK);
		for (JPanel a : axes)
		{
			lens.add(a);
		}
		spaced(p, lens);
		// Taken BEFORE the period branch, because the tasks are not the roll. The
		// roll is dated day by day and only begins where it begins; a task
		// carries the stamp of its own close, so the on-task reading answers a
		// window on its own and must not be gated by a roll that cannot.
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
		List<SourceRow> sources = new ArrayList<>(sources());
		sources.sort(Comparator.comparingLong((SourceRow r) -> r.value).reversed());
		if (sources.isEmpty())
		{
			return noted(p, "Drops appear here as you play: every kill, priced as it lands.");
		}
		// The head this reading drew over a window and not over the whole record,
		// which left the same board with two shapes depending on the period, and
		// left the other half of the coin carrying a head it did not.
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
			// The same two figures the page behind this card shows. It used to
			// take its own from the ledger alone and disagree with the page it
			// opens on 42 sources: Nechryael read 686 kc here and 1,236 there.
			// "kc" is a kill count, and the Rift is searched rather than killed.
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
		final List<BagItem> bag = plugin.allLoot();
		if (bag.isEmpty())
		{
			return noted(p, "Drops appear here as you play: every kill, priced as it lands.");
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

	/** The item worth the most in a bag, opening its page. */
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
		// The one item that made the most, which the ranked kinds bury inside
		// whichever kind holds it: a single unique can sit under "Everything
		// else", below a bulk kind like Runes.
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

	/**
	 * One kind of a bag, opened out: what it came to, the way back, and then
	 * its items. Every board that offers kinds drills through here.
	 */
	private JPanel kindDrill(JPanel p, List<BagItem> bag, String key)
	{
		final List<BagItem> kept = ofKind(bag);
		final long[] mine = tallyOf(kept);
		// The head is the KIND's, not the bag's. It used to carry the whole
		// bag's totals under the kind's name, which is a number that is wrong
		// in the most believable way available.
		JPanel head = card(lootKind);
		bagRows(head, kept, mine);
		spaced(p, head);
		p.add(backToKinds(kept.size()));
		if (kept.isEmpty())
		{
			// reachable: a kind opened at one period, or under every task, and
			// then a narrower one chosen that holds none of it. A board that
			// draws nothing at all leaves the reader wondering what broke.
			return noted(p, "Nothing of this kind here.");
		}
		p.add(copyHeader(lootKind, () -> copyPicture(
			lootPicture(lootKind, kept, mine, false), true)));
		addBagRows(p, kept, drillShown.getOrDefault(key + lootKind, ROW_CAP),
			key + lootKind);
		return p;
	}

	// The uncollected ledger: what was walked past, by source and by item.
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
		// The same head the Received reading draws, because this is the other half
		// of one board. Red belongs to the head as the board's single gesture: a
		// colour on every row of a list says nothing the list does not already
		// say, and it made one lens read in two palettes a click apart, since the
		// windowed path never used it at all.
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
		// By item or by source, the same two-line card the Received reading draws
		// for a source: the name and what it came to on top, the count and the
		// rate under it. This is the half of the coin that reads as the other half.
		final boolean byItem = dropsByKind;
		List<UntakenRow> list = byItem ? items : rows;
		String key = byItem ? "left:item" : "left:source";
		final int cap = drillShown.getOrDefault(key, ROW_CAP);
		int shown = 0;
		for (UntakenRow r : list)
		{
			if (shown++ >= cap)
			{
				// It used to stop here without a word. Every list in the panel
				// stops somewhere; the ones a reader can walk past say so.
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

	// The dryness ledger, read once per session off the first source opened.
	private List<GrindBook.GrindRow> grindsCache;
	private boolean grindsFetching;

	// The journey fetches once per session on first open; null = not yet asked.
	private SlayerJourney journeyCache;
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
		// disown any gather still reading the old journal
		historyEpoch++;
		historyGathering = false;
		detailItem = null;
		detailSource = null;
		detailStack.clear();
		drillShown.clear();
		histListShown.clear();
		openFolds.clear();
		// the icons are read off this account's ledger and counters; the kinds,
		// the lifetime task memos and the waiting labels the rebuild forgets first
		signatureItems.clear();
		scaledIcons.clear();
		gatherHistory();
		rebuild();
	}

	/** Stop the repeating timers. Called from the plugin's shutDown. */
	void shutdown()
	{
		homeTicker.stop();
		searchDebounce.stop();
	}

	// Which of the Slayer tab's three boards is up. A boolean held two and could
	// not hold a third. Sticky, like every other lens in the panel: applyTab clears
	// what is paged out and what is drilled into, never which lens a reader chose.
	private String slayerLens = "Tasks";
	private int slayerShown = ROW_CAP;

	/**
	 * The live task card is the open segment on disk, which already has a page:
	 * kills logged, worth, when it started, what was killed and what dropped.
	 * The card was inert and the only way in was the Tasks list.
	 */
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
			// not read yet: read it, then come back here
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
					rebuildInPlace();   // the record moved; the reader did not
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
		// windowMs, not a second copy of it: this had its own pair of midnights
		// and so reported the whole day's task loot under the sitting.
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
		spaced(p, onTaskHead(qty, value, tally));
		p.add(taskPicker());

		// Otherwise the kinds, which is what makes this board readable: two
		// hundred and eighty seven rows became sixteen, and the question a
		// reader actually has -- what has slayer paid me in runes -- is one of
		// them rather than a scroll.
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

	/**
	 * One kind's whole list as a picture: the head, then every item, uncapped.
	 * What is on screen is capped; what gets shared never is. With kinds, the
	 * ledger's kinds instead of the items.
	 */
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

	/** The summary as a picture: the head, then the kinds. */
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

	/**
	 * How this panel says a list goes on, and how it opens.
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
		link(more, reveal);
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
		link(r, go);
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

	/** One slot of a sprite grid: the item's picture, named on hover, opening its page. */
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

	/** The three rows every bag's head card carries. */
	private void bagRows(JPanel head, java.util.Collection<?> bag, long[] sum)
	{
		head.add(row("Items", fmt(sum[0]), accent()));
		head.add(worthRow(sum[1]));
		head.add(row("Distinct items", fmt(bag.size())));
	}

	/** The way back out of one kind, and how many distinct items it holds. */
	private JPanel backToKinds(int held)
	{
		return backRow("< All kinds", fmt(held) + (held == 1 ? " item" : " items"), () ->
		{
			lootKind = null;
			rebuildInPlace();
		});
	}

	/**
	 * A bag as rows, capped, with the cap as a button rather than a dead line.
	 * Every list in the panel stops somewhere; this is the one shape they all
	 * use to say so.
	 */
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

	/**
	 * A bag narrowed to the kind on show, or the whole bag where none is.
	 *
	 * <p>The kind is asked of the item's NAME rather than its id, because that
	 * is what the taxonomy is keyed by and what the row already shows.
	 */
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
		head.add(worthRow(value));
		// How many TASKS this is the take from, which is the thing that makes
		// the rest of the card mean anything: 81M gp is a different sentence
		// over four hundred tasks than over four. A count of distinct items sat
		// here and said nothing the list below it did not already say.
		if (tally != null && tally.length > 2)
		{
			head.add(row("Tasks", fmt(tally[2])));
		}
		if (tally != null && tally.length > 0 && tally[0] > 0)
		{
			head.add(row("Kills logged", fmt(tally[0])));
		}
		// Nothing to say to an account that never unlocked Bigger and Badder.
		if (tally != null && tally.length > 1 && tally[1] > 0)
		{
			head.add(row("Superiors", fmt(tally[1])));
		}
		return head;
	}

	/** The small grey "copy" on a row's right; null where the row has no right. */
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

	/** What the copy label says once pressed. */
	private void reportCopy(JLabel take, boolean ok)
	{
		take.setText(ok ? "copied" : "cannot copy");
		take.setForeground(ok ? accent() : ColorScheme.PROGRESS_ERROR_COLOR);
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

	/**
	 * The copy head all three share: a press hands the copier the label, so a
	 * copy that answers later can write the outcome back when it has one.
	 */
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

	/**
	 * A section head with a copy on its right. A board that is not a drill has no
	 * back row to hang one on, and it should still be shareable.
	 */
	private JPanel copyHeader(String title, BooleanSupplier copy)
	{
		return copyHeaderLater(title, take -> reportCopy(take, copy.getAsBoolean()));
	}

	/**
	 * The game's own count per monster, as last scraped from the Kill Log
	 * interface. Its own board now: it answers "how many of these have I
	 * killed", which the task journey never does.
	 */
	private JPanel addKillLog(JPanel p)
	{
		List<Entry<String, Long>> kcs = new ArrayList<>(LocalStore.killLogCounts(clogNow()).entrySet());
		if (kcs.isEmpty())
		{
			// Behind a pill this is a screen of its own and has to say something.
			// Stacked at the foot of the journey it never did: both guards fell
			// through in silence and the card was simply never built.
			return noted(p, "No kill log yet. It copies itself the next time you open "
				+ "the Slayer Kill Log in game.");
		}
		kcs.sort(Entry.<String, Long>comparingByValue().reversed());
		JPanel card = card("Kill log");
		// Capped like every other list, and opened like every other list: it
		// used to stop at twenty and send the reader to the search box for the
		// rest, which made the search the only door to most of the log.
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

	// Has the journey moved since the copy on screen? A finished task, a new
	// one, or another kill on the newest one is everything the block shows.
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

	/**
	 * One task read against the account's own record of the same assignment:
	 * what it usually pays and the best it ever did, from the closed tasks
	 * before this one. Two figures and a date; nothing about whether it was
	 * worth doing.
	 */
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
		// an average of one is not a usual
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

	// One task on its own: what it was made of and what it dropped. The
	// monster's whole lifetime bag is a button away at the bottom.
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

		// What the assignment was made of: brutals, superiors and a boss detour
		// all count toward one task.
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

	// The Left behind lens drilled from either end: what was left at a source,
	// or where an item was left.
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
			// The headline is the source's own tally. The rows below start later,
			// so they don't sum to it.
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
		// The period governs this board too. A task carries the stamp of its own
		// close, so a window is a filter over the journey rather than a delta,
		// and the headline counts what it admitted rather than the lifetime.
		List<SlayerTask> shown = new ArrayList<>();
		List<Integer> where = new ArrayList<>();
		for (int i = 0; i < j.tasks.size(); i++)
		{
			SlayerTask t = j.tasks.get(i);
			// An open task carries its latest kill's stamp, so any window that
			// caught one kill would take its whole run: a bounded window takes
			// closed tasks only, the rule the store's on-task readers keep
			// (onTaskLoot), so this head agrees with the Drops board beside it.
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
		// Only an imported legacy journal carries this; nothing writes it now.
		if (j.totalXpEst > 0)
		{
			head.add(row("Slayer xp (est.)", gp(j.totalXpEst)));
		}
		spaced(p, head);
		for (int k = 0; k < shown.size() && k < slayerShown; k++)
		{
			SlayerTask t = shown.get(k);
			JPanel card = cardPlain();
			// Lit name, no suffix: the card has no room for one.
			// the drill indexes the WHOLE journey, not the window's slice of it
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
		showRecords = false;
		showCalendar = false;
	}

	/** Leave the sent page, any drill and the search, for a page of its own. */
	private void leaveAll()
	{
		leaveSentPage();
		detailItem = null;
		detailSource = null;
		detailSkill = null;
		detailTask = -1;
		clearSearch();
	}

	/** The account's bests, each with its date. Behind the Total level cell. */
	void openRecords()
	{
		leaveAll();
		showRecords = true;
		rebuild();
	}

	/** The days written, as a month. Behind the nameplate's Days written row. */
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
		// A total of zero is a total nobody has told us, so it is not printed as
		// one. This card reports what the record HOLDS, so the slots still stand.
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

	/** Label and fact-key pairs as rows on a card, the first in the lead colour. */
	private static JPanel facts(JPanel c, Map<String, Long> f, Color lead, String... rows)
	{
		for (int i = 0; i < rows.length; i += 2)
		{
			c.add(row(rows[i], fmt(f.getOrDefault(rows[i + 1], 0L)), i == 0 ? lead : null));
		}
		return c;
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
		leaveAll();
		showInfo = true;
		rebuild();
	}

	/** Every tracker the record keeps, in one place. */
	void openAllTrackers()
	{
		leaveAll();
		allTrackers = true;
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

	/** What the roll says of one item inside the window, as {qty, worth}. */
	private long[] itemInWindow(String name)
	{
		return rowOf(lootWindow().items, name);
	}

	/** The timed kills the roll holds for one source inside the window: {count, seconds}. */
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

	/**
	 * What the roll says one source paid inside the window, as {count, worth}.
	 *
	 * <p>The sitting reads its own entry and every other period the dated days,
	 * which is the same split every other board makes.
	 */
	private long[] sourceInWindow(String name)
	{
		return rowOf(lootWindow().sources, name);
	}

	/** One name's row of a roll list, as {count, worth}, or zeros. */
	private static long[] rowOf(List<String[]> rows, String name)
	{
		String[] r = rowFor(rows, name, false);
		return r == null ? new long[]{0, 0} : new long[]{safeParse(r[1]), safeParse(r[2])};
	}

	// A name's row of a roll list: its own spelling, else (not exact) any case of
	// it. The game has monsters whose names differ only by case, kept apart.
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
		// The ledger's own spelling of the SAME thing, and nothing looser: a
		// plural against a singular, "Jellies" against "Jelly", or the container
		// a fight pays out through.
		//
		// NOT a name that merely contains this one. The ledger's "Dagannoth"
		// sits inside "Dagannoth Rex", so all three kings opened the ordinary
		// dagannoth's six hundred kills instead of their own page, and "King
		// Black Dragon" would have opened "Black dragon" the same way. A click
		// landing on a different monster's loot is worse than landing on an
		// empty page, which is what a source with no drops honestly has.
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
		if (showInfo || showRecords || showCalendar)
		{
			showInfo = false;
			showRecords = false;
			showCalendar = false;
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

	/** An accented row that goes somewhere: the way back, or out of a lens. */
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

	/**
	 * One flavour on the clipboard: the picture itself, which is the one thing
	 * every chat window takes, or encoded PNG bytes, handed out fresh each time
	 * the board is read.
	 */
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

	/** False where there is no desktop clipboard to reach, rather than throwing. */
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
		catch (Throwable ignored)   // noqa: headless, or a desktop that refuses
		{
			return false;
		}
	}

	/** image/png as bytes, which is the only honest way to say PNG on a Mac. */
	private static final DataFlavor PNG_BYTES = pngFlavor();
	private static boolean pngNativeMapped;

	private static DataFlavor pngFlavor()
	{
		try
		{
			return new DataFlavor("image/png;class=java.io.InputStream");
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
			return clip(PNG_BYTES, () -> new java.io.ByteArrayInputStream(png));
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
	private static Image pageImage(JPanel page, int width)
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
		g.setColor(DARKER);
		g.fillRect(0, h - band, w, band);
		g.setColor(ColorScheme.LIGHT_GRAY_COLOR);
		g.setFont(small());
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
	static Image copyImage(JPanel page)
	{
		return copyImage(page, false);
	}

	static Image copyImage(JPanel page, boolean tall)
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
		grid.setBackground(DARK);
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
			cell.setBackground(DARK);
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
		// What THIS period's roll says of the item, where the period is not the
		// whole record: the head off the day's heap, the sources beneath off
		// what each kept of it, and whatever the heap holds beyond them as Other.
		final long[] inWindow = wholeRecord() ? null : itemInWindow(name);
		long other = 0;
		long otherValue = 0;
		if (inWindow != null)
		{
			srcs.clear();
			other = inWindow[0];
			otherValue = inWindow[1];
			// the spelling the head read, so the rows are of the same item
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
		// read before the row is built: the copy hands back every source, not the
		// forty the page mounts
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
		// offered on the account's whole record, read over the page's own window
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
			// The same row the other reading carries. What it is NOT is a price
			// frozen at the drop, for anything imported: a drop that lands now
			// is priced once and written to both trees identically, but the
			// history that came in from the server was valued in bulk on the
			// day it was imported, and the two halves of that import were
			// valued by different means. That is why 19 of my 287 on-task items
			// price higher than the same item does across the whole ledger, and
			// why a Mithril spear reads 7,000 each here against 231 there.
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
		// When it landed, off the dated roll. Lifetime standings, so they stay
		// off a narrowed page rather than sitting under figures that are not; and
		// only where the dated days hold every one, since a first dated day is
		// otherwise the roll's and not the item's. Never in a picture: the first
		// is near enough the day the plugin was installed.
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
		// the copy lifts itemSourceCap; a reader lifts the drill's own cap
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

	/** Each source's own items for the period on show: the sitting's, or its days'. */
	private Map<String, List<BagItem>> periodItems()
	{
		Window w = window();
		return sessionPeriod() ? plugin.itemsBySource(null, null) : plugin.itemsBySource(w.start, w.end);
	}

	// Where a period reaches back before the roll keeps its loot by source: the
	// day it can be read from. A picture says so without the day, which is near
	// enough the day the plugin was installed.
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

	// The since line, or in a picture, which leaves the period strip behind, the
	// period the figures are.
	private String sinceLine(String since)
	{
		return since != null || wholeRecord() || !drawingCopy ? since
			: "The figures above are " + periodInSentence() + "'s.";
	}

	// A period's page with nothing in it, said once: a since line already says why.
	private JPanel nothing(JPanel p, String what, String since)
	{
		return since != null ? p : noted(p, "Nothing " + what + " inside " + periodInSentence() + ".");
	}

	// What a period paid on days the roll kept as one heap, not under a source.
	private static void addOther(JPanel p, String figure, boolean show)
	{
		if (show)
		{
			JPanel r = ghostRow("Other", figure);
			r.setToolTipText("Dropped on days the record kept whole rather than by source");
			p.add(r);
		}
	}

	/**
	 * The ledger's own spelling of an item name.
	 *
	 * <p>The task bag is keyed by the name the game gave the drop, and a page
	 * can be opened from a search box where the reader typed it in any case at
	 * all. Matching on the bag's own key is what makes "fire rune" find it.
	 */
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
	private long standingKills(SourceRow sr)
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
			for (Entry<String, Long> e : plugin.killCounts().entrySet())
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

	/** One map's figure for this source, where it has one. */
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
			p.add(row("Task: " + a.task, fmt(a.killsHere)));
		}
		p.add(vgap(6));
	}

	/** What one source's kills left on the floor, lifetime, opening the twin page. */
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

	/**
	 * Whether the minutes filed under {@code key} cover the whole of this
	 * period, so a rate drawn from them divides two figures of one span.
	 *
	 * <p>The minutes begin the day the tracker first files one. A lifetime
	 * would pair them with kills the account had before this plugin existed,
	 * and a window straddling that first day pairs a whole window's kills with
	 * part of its minutes. Either reads as a rate and is not one, so the row
	 * says the hours alone until the record can divide honestly.
	 */
	private boolean minutesCoverPeriod()
	{
		// The whole record reads the counters as they stand, not a step from an
		// opening line, so no line can say its minutes reach back far enough:
		// a spine begun on install day carried the time keys from its first
		// line, and a level 93 skill was drawn beside "Time 19m".
		if (wholeRecord())
		{
			return false;
		}
		if (sessionPeriod())
		{
			return true;   // the sitting's own minutes over the sitting's own figures
		}
		Span s = span();
		if (s == null)
		{
			return false;
		}
		// Any minutes key at all: they all begin on the day the tracker files
		// its first minute, so one of them standing on the opening line says
		// the whole window is inside the measured era. A fight first met inside
		// the window has no key of its own there and is still measured whole.
		for (String key : s.opening.counters.keySet())
		{
			if (StatKeys.isTime(key))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * The minutes one fight owns, its own name and every NPC it is fought as.
	 *
	 * <p>A minute is filed under the NPC the hit landed on, and half the roster
	 * is not named after one: a raid is named for the place, Barrows for the
	 * chest at the end of it. Without the table a boss page simply drew no time
	 * row, which is silence rather than a wrong figure, but silence about an
	 * hour the reader spent there.
	 */
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

	// The source under the glass: kills tracked, the take, and its whole bag.
	private JPanel buildSourceDetail(String name)
	{
		JPanel p = column();
		SourceRow found = null;
		for (SourceRow r : sources())
		{
			// its own spelling first: the game has monsters named apart only by case
			if (r.name.equals(name))
			{
				found = r;
				break;
			}
			found = found == null && r.name.equalsIgnoreCase(name) ? r : found;
		}
		final SourceRow sr = found;
		// What THIS period's roll says this source paid, where the period is not
		// the whole record. The page below reads these in place of the ledger's
		// lifetime figures, so clicking a source on a board narrowed to a week
		// opens that week rather than silently opening everything.
		// A source the ledger holds reads its own row alone: the roll files a drop
		// under the ledger's very spelling, and another case of it is another monster.
		String[] row = wholeRecord() ? null : rowFor(lootWindow().sources, sr != null ? sr.name : name, sr != null);
		long[] inWindow = wholeRecord() ? null
			: row == null ? new long[2] : new long[]{safeParse(row[1]), safeParse(row[2])};
		// Read before the row is built, because the copy hands back the WHOLE
		// page: every loot line, not the twenty five the page mounts. A period
		// reads its own items: the sitting and each day keep them per source.
		String own = row != null ? row[0] : sr != null ? sr.name : name;
		final List<BagItem> bag = inWindow == null ? plugin.sourceItems(own)
			: new ArrayList<>(periodItems().getOrDefault(own, new ArrayList<>()));
		bag.sort(Comparator.comparingLong((BagItem b) -> b.value).reversed());
		// what the period paid on days the roll kept only as one heap, worth
		// something or not
		final long other = inWindow == null ? 0 : inWindow[1] - tallyOf(bag)[1];
		final boolean unfiled = other > 0 || inWindow != null && !sessionPeriod()
			&& plugin.unfiledSources(window().start, window().end).contains(own);
		spaced(p, backRow(() -> copySourcePage(name)), 4);
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
			// its page said 1,236, which is the game's own Kill Log figure.
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
			head.add(row("Worth", gps(worth)
				+ (over > 0 ? " · " + gp(worth / Math.max(1, over))
					+ (killed ? " gp/drop" : " gp each") : "")));
			// The reconciled kill count and the log's own lines are lifetime
			// standings, and stay off a narrowed page rather than sitting under
			// figures that are not.
			if (inWindow == null)
			{
				addKillSources(head, sr, killed ? shown : -1);
				// The floor is the same fight. What this source's kills left
				// behind sat on a twin page under the other lens, found by
				// going back and looking for the same name in a different list.
				addFloorRow(head, sr.name);
			}
			// what the log's own page counts for it, in the log's own words
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
				// Dated where the journal watched it fall, the beaten time on hover.
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
			// Every timed kill, not the fastest: the period's average over the
			// kills the boss timer spoke for.
			double[] timed = inWindow == null ? new double[]{sr.timed, sr.timeSum}
				: lootWindow().times.getOrDefault(own, new double[2]);
			if (timed[0] > 0)
			{
				head.add(row("Average kill", pb(timed[1] / timed[0]) + " · "
					+ fmt((long) timed[0]) + " timed"));
			}
			// How long was spent here, off the minutes the trackers file under
			// the fight; the period's, like every figure above it, and the
			// kills an hour once there is half an hour to divide. Only where
			// the minutes cover the whole period: they began the day the
			// tracker did, so a lifetime read "19m" beside two thousand kills,
			// which looks broken because as a lifetime's time it is.
			long here = minutesAt(sr.name, inWindow == null ? counters() : periodCounters());
			if (here > 0 && minutesCoverPeriod())
			{
				boolean rate = killed && shown > 0 && here >= 30;
				head.add(row("Time here", hoursMinutes(here)
					+ (rate ? " · " + rateText(shown * 60.0 / here) + " kills/h" : "")));
			}
			// Not on a picture. It is the reader's own bookkeeping rather than
			// anything about the fight, and on an account whose ledger carries
			// imported rows it can name a day before the account existed, which
			// is a date nobody should be handing out with a screenshot.
			if (sr.firstMs > 0 && !drawingCopy)
			{
				head.add(row("Tracked since",
					day(sr.firstMs)));
			}
			// The chase, when the dryness ledger knows one for this source. Not
			// on a picture: how dry somebody is running is the most personal
			// line on the page, and a screenshot of a drop is not the place to
			// volunteer it.
			if (grindsCache == null && !grindsFetching && !drawingCopy)
			{
				grindsFetching = true;
				plugin.fetchGrinds(rows2 -> SwingUtilities.invokeLater(() ->
				{
					grindsFetching = false;
					if (rows2 == null)
					{
						return;   // store not mounted; retry on the next rebuild
					}
					grindsCache = rows2;
					if (sr.name.equals(detailSource))
					{
						rebuildInPlace();   // the record moved; the reader did not
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
				// Opens the item, as the identical grid on Now does. It was the
				// one sprite grid in the panel a reader could not click: same
				// five setup lines, same tooltip naming the item, and then
				// nothing behind it.
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

	/**
	 * What the log gained inside the window, read off the journal rather than off
	 * the log. Every slot is dated as it lands, so "what did I log this month" is
	 * a question the record can answer even though the log itself holds no dates
	 * at all.
	 */
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
			head.add(row("Open your log in game once for the total", ""));
		}
		else
		{
			head.add(row("Open your log in game once to fill this in", ""));
		}
		spaced(p, head);

		Map<String, Map<String, List<String>>> tax = taxonomy(plugin.gson());
		// Three per row; five clips the names.
		JPanel pills = new JPanel(new GridLayout(0, 3, 3, 3));
		pills.setBackground(DARK);
		for (String tab : tax.keySet())
		{
			// The game's own count for this tab, which the capture has always
			// taken and shipped and the panel has never shown. It goes on the
			// hover rather than on the pill: five pills across a 242 pixel column
			// have no room for a fraction, and "16 / 611" beside "Clues" would
			// cost the word itself.
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
		// The page's own kill line, not the unlabelled figure beside it. That
		// figure is whatever the page happened to put first, and on the pages that
		// carry more than one line it is not a kill count at all: Wintertodt's is
		// rewards claimed, 1,078 against 447 killed, and Tempoross's is a personal
		// best, 46, which is a TIME being printed as a number of kills. LocalStore
		// already knows the rule, and a page whose only lines are rewards or a best
		// time honestly has no kill count to show.
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
			// Everything the page's header said, in the game's own words, since
			// the row itself can only carry the one figure that is a kill count.
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
				// Pets pages have a dated line to put under a slot. Source and
				// count only appear on rows an older record supplied; the plugin's
				// own pet emit carries the name alone.
				boolean petPage = low(page).contains("pet");
				Map<String, LocalStore.PetRow> known = petPage
					? petsByName() : Collections.emptyMap();
				// And a pet still out there gets the same line read the other way:
				// what has been killed for it, and how much of the field holds it
				// by that point. Only where the rate book prices the pet and the
				// journal has a kill count; the rest of the page is untouched.
				Map<String, GrindBook.PetChase> chases = petPage
					? plugin.petChases(slots) : Collections.emptyMap();
				// What each slot has to say for itself, settled before a row is
				// mounted: seventy of these lines at once is a wall, so the page is
				// a list of names and each one gives its line up only when asked.
				// The note above them has to know whether any of them has one.
				List<List<JPanel>> detail = new ArrayList<>();
				boolean anyDetail = false;
				for (int i = 0; i < slots.size(); i++)
				{
					String key = low(slots.get(i));
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
					spaced(drill, note("Click pet to see odds. Skilling odds are based "
						+ "on current level."), 3);
				}
				Map<String, Long> landed = landedSlots();
				for (int i = 0; i < slots.size(); i++)
				{
					String slot = slots.get(i);
					// A pet the journal recorded lights even if its page was never
					// opened. Green owned, red missing, as in game.
					JPanel r = row(slot, "",
						lit[i] || known.get(low(slot)) != null
							? ACCENT_SESSION : ACCENT_RED, true);
					// A lit slot says when it landed: every slot enters the feed
					// dated, and only pets were giving the date up.
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
						// Nothing under it to uncover, so it is not a fold: no hand
						// cursor promising one, and no click that does nothing.
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

		// Pages the GAME has and this release's list does not. Jagex adds a
		// collection log page and a plugin update takes as long as it takes, so
		// without this the reader's own capture of that page is on their disk and
		// nowhere on screen for a month. It cannot be filed under a tab, because
		// the scrape records a page's title and not which tab it sat under, so it
		// is shown once, on Other, where the game itself puts what does not fit.
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
		return sourceLine(chase.sources, chase.sources.size(), "").whole();
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
		protected void paintTrack(java.awt.Graphics g, JComponent c,
			Rectangle bounds)
		{
			// The board is the track.
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
		public void uninstallUI(JComponent c)
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
		Collections.reverse(order);
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
			sb.append(", mostly ").append(low(chase.sources.get(0).boss));
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
					? day(pet.ts) : ""));
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
			if (c instanceof JComponent)
			{
				((JComponent) c).setToolTipText(tip);
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
		for (Skill sk : Skill.values())
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
			out.putIfAbsent(low(r.name), r);
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

	/**
	 * Which slots of a page the player holds. A slot lights when its name is in
	 * the page's own capture or the whole-log obtained set; duplicate-named slots
	 * (My Notes' 26 "Ancient page" entries) light positionally, k copies lighting
	 * the first k, as the game does.
	 */
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

	// Parse the bundled taxonomy once. Order is preserved, tabs and slots.
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
	private final Set<String> openFolds = new HashSet<>();

	// Home's xp total, broken out per skill.
	private static final String FOLD_HOME_XP = "home:xp";
	private static final String FOLD_HOME_DAMAGE = "home:damage";
	// the styles damageDealt is made of; they read as its breakdown, never as
	// trackers of their own
	private static final List<String> DAMAGE_SPLIT = Arrays.asList(
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
	private List<SourceRow> buildSources;
	private JsonObject buildClog;
	private Span buildSpan;
	private boolean spanAsked;

	/**
	 * Every source the ledger holds. Shared, so a caller that wants to sort it
	 * takes its own copy first.
	 */
	private List<SourceRow> sources()
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

	/**
	 * The whole record's spend on food and potions, off the ledger that prices
	 * every meal and dose the record holds. The trackers' own spend began when
	 * the plugin started writing it, and the ledger also carries everything
	 * eaten and drunk before that: the Living board said "Consumed value 632k"
	 * over Food and Potions heads adding to 2.16M. Whole record only. A window
	 * is the spine's own difference, and closingNow folds counters() into
	 * every window reaching today, so this never goes into counters() itself.
	 */
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
		// Lifetime only. What a consumable COST is accumulated forever and is not
		// in the history spine, so there is no windowed version of it to print:
		// under a narrowed period this would put a career's gp beside a week's
		// count and invite the reader to divide one by the other.
		Long cv = wholeRecord() ? consumVals.get(e.getKey()) : null;
		return cv != null && cv > 0 ? base + " · " + gps(cv) : base;
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
		JPanel p = backPage();
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
			// under the section it heads everywhere else: Meals eaten with Food
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

	/** The Deaths row as a fold: who dealt them, ranked, with the last date each did. */
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
			// the whole sheet in one place, which was reachable only by typing
			p.add(moreRow("every counter in one place", this::openAllTrackers));
		}
		p.add(vgap(4));

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
			// Meals eaten and doses drunk head Food and Potions rather than
			// standing beside them: they were counted before the per-item keys
			// were, so the heads read 2,410 and 741 under rows of 2,420 and 1,479,
			// and what no item can claim is the heads' "Other".
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
			// Named for both things it is empty OF. This board is one family of
			// four under one period of six, and "here" named neither, so a
			// reader on Living for a week that ate nothing was told the record
			// held nothing at all.
			String unkept = notCounting(false);
			return noted(p, unkept != null ? unkept : wholeRecord()
				? "Nothing under " + statsFamily + " yet."
				: "Nothing under " + statsFamily + " inside " + periodInSentence() + ".");
		}

		// Destinations nest inside the Teleports fold.
		List<Entry<String, Long>> destRows = statsFamily.equals("Ledger & Roads")
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
				// The period's own spend, written at the bite and the dose, and
				// only where the split was already being written when the window
				// opened: a whole window's count beside part of its spend is one
				// figure pretending to account for the other.
				String gpKey = sec.equals("Food") ? "foodConsumedValue" : "potionsConsumedValue";
				Span s = span();
				if (sessionPeriod() || (s != null && s.opening.counters.containsKey(gpKey)))
				{
					secGp = counters.getOrDefault(gpKey, 0L);
				}
			}
			// The count stands in both states, and the gp beside it is the same
			// period's: the lifetime priced by the record, a window by the split
			// the trackers write. A week's count never sits beside a career's spend.
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

	// Sections in display order: Skilling's crafts rank by weight, the other
	// families keep the registry's fixed order with strays appended.
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

	/**
	 * Multi-verb crafts drill one level deeper: Prayer opens to Bones buried ·
	 * Ashes scattered · Ensouled heads, each fold reconciling to its own floor,
	 * with the verbless totals (Ashes sacrificed) as flat rows above. Returns
	 * false under two verb groups, and the caller renders the flat list instead.
	 */
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

	// Destinations sit one level under Teleports: where the roads led.
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

	// A second-level fold header: normal case, indented, click to toggle.
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
		part(r, BorderLayout.CENTER)
			.setForeground(dim().darker());
		return r;
	}

	// How deep the milestone scan reads into the feed; a year-long window still
	// has to find its own entries.
	private static final int HISTORY_FEED_SCAN = 2000;
	// Deeper still for the walks that look for one type of entry, which can sit
	// a long way down a feed that is mostly loot.
	private static final int FEED_SCAN_DEEP = 4000;

	// The two reads the History tab lives on, held between rebuilds. The spine is
	// a whole parse of an append-only file and the feed slice is deep-copied under
	// the store's lock. Both are gathered on a worker thread; on the EDT that cost
	// lands as a stall on every pill click.
	private TreeMap<LocalDate, Baseline> historySpine;
	private List<JsonObject> historyFeed = new ArrayList<>();
	// The slayer journey read beside them: the progress card's tasks-completed
	// line counts its closed segments by date, which reach back past the spine.
	private SlayerJourney historyJourney;
	// What that pair was true of: the day it was read and the newest feed entry
	// it saw. Either one moving means the cache is stale.
	private LocalDate historyDay;
	private long historyFeedTs;
	private boolean historyGathering;
	// A gather in flight when a different journal mounts must not land; its
	// spine belongs to the account that has gone.
	private int historyEpoch;

	// One gathered pass over the journal's calendar spine and its feed.
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
					LocalDate.now());
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
				//
				// In place: the read lands whenever the feed grows, a level or a
				// log slot, and a plain rebuild sent a reader halfway down the
				// sheet back to its top a moment after they levelled.
				rebuildInPlace();
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
				for (Entry<String, Long> e : firstN(gains, cap))
				{
					card.add(row(prettify(e.getKey()), "+" + gp(e.getValue())));
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
	// counts, the row is a fold and the names sit under it, each with the day
	// it happened. An entry the journal counted but cannot name closes the
	// list as a ghost, the way a section closes with its "Other": the head's
	// figure is then accounted for on screen rather than opening to a shorter
	// list than it claims.
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

	// A row one step in from the fold it sits under, so a section's rows read
	// as that section's and not as the group's own.
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

	// The tail under a capped list: what is still folded away, and the click
	// that brings it. Drawn at the indent of the rows it pages: one step in
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
		link(more, () ->
		{
			histListShown.put(key, size);
			rebuildInPlace();
		});
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
		// a loot date handed in was already measured against the period's own
		// start, so it stands whatever the counters say: the counters beginning
		// later does not make the loot boundary untrue
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

	// A segment closed inside [fromMs, toMs); the one in hand is nobody's yet.
	// A segment's ts is its completion instant, in epoch seconds.
	private static boolean closedInside(SlayerTask t, long fromMs, long toMs)
	{
		long ms = (long) (t.ts * 1000);
		return !t.inProgress && ms >= fromMs && ms < toMs;
	}

	// Those same segments by name, newest first, each with the kills it took
	// and the day it closed: what the Combat group's tasks line opens to.
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

	// The quest itself, out of the line the game announced it in ("You have
	// completed Fallen From Grace!"). A name that arrives clean is left alone.
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

	// What one feed entry names, for the list its figure opens to: the item,
	// the pet, the quest, the diary, the task and its tier, the skill and the
	// level it reached. Null where the entry names nothing, which is what an
	// imported milestone carries: the figure still counts it, and the list
	// holds only what the record can actually name.
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

	// A field that is there and says something.
	private static boolean has(JsonObject o, String key)
	{
		return o.has(key) && !o.get(key).isJsonNull()
			&& !o.get(key).getAsString().trim().isEmpty();
	}

	// The minutes one played session stands for; 0 when it carries none.
	private static long sessionMinutes(JsonObject e)
	{
		JsonObject d = obj(e, "data");
		return Math.max(0, safeLong(d.get("minutes")));
	}

	/**
	 * When a sitting began: the line's own start where it carries one, else
	 * the moment it closed less its minutes. The minutes are whole, so a start
	 * worked out this way is up to a minute late and never early.
	 */
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

	/**
	 * The moment a line is filed under. A sitting belongs to the day it began,
	 * everywhere: two and a half hours played from half nine closed at two
	 * minutes past midnight, and the next day's line counted its time while
	 * the day before kept its xp. Everything else is filed when it happened.
	 */
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

	// The panel's name tables below are bundled in panel_kinds.json, which is
	// read before the first of them.
	private static final JsonObject KINDS = table("panel_kinds.json");

	// The feed's dated entry types the progress card counts, each under the
	// summary key its line reads. Where the spine carries the key too (deaths,
	// collection log slots) the feed's count lays over its delta; the rest
	// never ride the spine and stand on the feed alone.
	private static final Map<String, String> FEED_SUMMARY_KEYS = strMap(KINDS, "feedSummaryKeys");

	// whether the record was keeping sittings before this window opened
	private static boolean sittingsCover(List<JsonObject> feed, long fromMs)
	{
		long oldest = oldestTs(feed, true);
		return oldest > 0 && oldest <= fromMs;
	}

	// the earliest day either dated loot source can speak for: the roll where it
	// has been running, the sittings before it existed
	private static long earliestDatedLoot(List<JsonObject> feed, long rollFrom)
	{
		long sittings = oldestTs(feed, true);
		if (sittings <= 0)
		{
			return rollFrom;
		}
		return rollFrom <= 0 ? sittings : Math.min(sittings, rollFrom);
	}

	// a ranked breakdown as drill rows: what it was, then how many and what for,
	// a zero worth left unsaid unless every line is to carry one
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

	/**
	 * The oldest entry of any type, or with sittings the oldest sitting the
	 * record holds; 0 for none. A sitting is the only dated account of a take,
	 * so its oldest is the day from which any loot figure can be drawn at all.
	 */
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

	// A progress figure: gp keys in gp, and a second gp figure beside the value
	// when the row carries one, worded as the row says ("3 · 300k gp" for loot
	// left on the floor, "2.5M gp · 300k dropped" for what was gathered).
	private static String figure(HistoryProgress.Row r)
	{
		String base = r.gp() ? gps(r.value()) : fmt(r.value());
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
		card.add(row("Loot received", gps(received), accent()));
		// taken is what was received less what was left where it fell
		card.add(row("Loot taken", gps(kept)));
		card.add(row("Loot left", gps(left)));
		card.add(row("Discarded", gps(summaryValue(progress, "itemsDroppedValue"))));
		// Upkeep, which is the name HistoryProgress already gives this key: the
		// figure counts every potion dose drunk as well as every meal, and "Food"
		// filed a night on brews and restores under the wrong word.
		card.add(row("Upkeep", gps(summaryValue(progress, "consumedValue"))));
		spaced(p, card);
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
		Map<String, Long> nowKc, boolean live, LocalDate from,
		LocalDate to, String first, String second)
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

	// The paid figure for a counted name, found under either spelling.
	private static long paidFor(Map<String, Long> worth, Map<String, Long> loose, String name)
	{
		Long exact = worth.get(name);
		return exact != null ? exact : loose.getOrDefault(kindOf(name), 0L);
	}

	// The same figures under the one spelling both sides can agree on.
	private static Map<String, Long> loosely(Map<String, Long> worth)
	{
		Map<String, Long> out = new LinkedHashMap<>();
		for (Entry<String, Long> e : worth.entrySet())
		{
			out.merge(kindOf(e.getKey()), e.getValue(), Long::sum);
		}
		return out;
	}

	/**
	 * The pickpocket targets, which the log has no page for at all, so they are
	 * named in panel_kinds.json the way the teleport destinations are: a closed set the game
	 * itself fixes, widened by what the record's own thieving counters say.
	 */
	private static final Set<String> PICKPOCKETED = new HashSet<>(strs(KINDS.get("pickpocketed")));

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
		return sourceKinds.computeIfAbsent(name, this::decideKind);
	}

	/**
	 * What a counted name is. The collection log's own tabs decide most of it: its
	 * Bosses and Raids are bosses, its Clues and Minigames are activities, and its
	 * Other tab is skilling ground but for the pages of it that are something
	 * killed. A name the log has no page for is a monster unless it is plainly
	 * something opened or something caught.
	 */
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

	/**
	 * The log's Other tab is the one it mixes: a rooftop course sits beside a
	 * demon. These are the pages of it that are something killed; the rest of the
	 * tab is ground a skill was trained on.
	 */
	private static final Set<String> MONSTER_PAGES = new HashSet<>(strs(KINDS.get("monsterPages")));

	// The ledger counts sources the log has no page for. Most are monsters, but
	// some are things opened and some are things caught, and neither belongs on
	// a kill board. These words are what say so.
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
		Integer id = index.get(low(name));
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

	/**
	 * One band of counted things: a heading that folds, and under it a line for
	 * each, with its count and what it paid. The first band of a facet carries
	 * the icon each thing is known by, since a reader knows a boss on sight; the
	 * second is plain, since it runs to hundreds and a list is what hundreds of
	 * anything wants to be.
	 */
	private void addKindBand(JPanel p, String kind, List<Entry<String, Long>> rows,
		Map<String, Long> worth, Map<String, Long> loose, boolean withIcons)
	{
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
		for (Entry<String, Long> e : firstN(rows, cap))
		{
			card.add(kindRow(e.getKey(), e.getValue(),
				paidFor(worth, loose, e.getKey()), withIcons));
		}
		addMore(card, stateKey, rows.size(), cap, false);
		spaced(p, card);
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

		// the line still drills, the way the kill boards always did
		link(r, () -> openSourceLoose(name));
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
	// The images already waited on, held by identity and weakly: the ItemManager
	// caches at most 128 of them and drops the rest, and an id remembered as
	// "already asked" after its image was dropped would never be asked again and
	// its row would stay blank for the session. An evicted image simply falls out
	// of here and the next build asks the new one, once.
	private final Set<AsyncBufferedImage> itemAsked =
		Collections.newSetFromMap(new java.util.WeakHashMap<>());

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
		// the image lands on the client thread; the labels are dressed on the EDT
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
	 * does not say, so they are named in panel_kinds.json.
	 *
	 * <p>The thieved and trapped names the record can answer for itself are not
	 * asked here. They are all filed under Skilling, which is the second band of
	 * its facet, and only the first band draws icons; asking would be a branch
	 * that can never be reached. {@link #skilledKeys} still decides what they are.
	 */
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
	private static final Map<String, String> PAGE_SKILL = strMap(KINDS, "pageSkills");

	// Hiscores order. An ORDER only. The grid is built from the client's own
	// skill list, so a skill Jagex adds shows up without an edit here. Overall is
	// drawn separately, as its own headline.
	private static final List<String> SKILL_ORDER_NAMES = strs(KINDS.get("skillOrder"));

	// Every skill the client knows, in the order a player reads them.
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
				// a skill this client no longer has, simply not drawn
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

	/**
	 * A baseline standing at the given experience.
	 *
	 * <p>Marked complete, because the live sheet lists every skill: an incomplete
	 * baseline reads a skill it does not name as undrawn rather than as level one,
	 * and the total tile refuses to name an opening when the two ends disagree
	 * about how many skills were drawn.
	 */
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
	// strip reads them. The drops line follows them, carrying the loot value
	// beside its count, one line for the pair; deaths closes the card.
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
	private JPanel headline(HistoryProgress progress, List<Entry<String, Long>> gains,
		SkillStand stand, HistoryLog.Levels opened, long[] played)
	{
		// Named for what it IS: a card headed "The period" over a strip saying
		// "This session" is the panel using two words for one thing.
		JPanel card = card(sessionPeriod() ? "This sitting" : "The period");
		long xp = 0;
		for (Entry<String, Long> g : gains)
		{
			xp += g.getValue();
		}
		// Skills and PvM each carry a fixed set, the same rows whatever the
		// period did. A figure reading zero is an answer; a row that vanishes
		// when it has nothing to say leaves a reader wondering if it was asked.
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
			// Not under the sitting, where the answer is one and saying so is the
			// card telling the reader what the heading above it already did.
			if (!sessionPeriod())
			{
				card.add(row("Sessions", fmt(played[1])));
			}
		}
		if (fixed || xp > 0)
		{
			card.add(row("Experience", "+" + gp(xp), xp > 0 ? accent() : null));
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
		// the drops and what they were worth, one line: the count is the kills
		// that dropped something and the value is what those drops came to
		HistoryProgress.Row drops = summaryRow(progress, "dropsReceived");
		HistoryProgress.Row value = summaryRow(progress, "lootValue");
		if (drops != null)
		{
			// no unit on the pair: "+64,298 · 488.2M gp" wants 212px of a 209px
			// row and took it out of the name, which then read "Drops recei..."
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

	/** A rate as a figure: one decimal under ten, whole above. */
	private static String rateText(double perHour)
	{
		return perHour >= 10 ? fmt(Math.round(perHour)) : String.format(Locale.UK, "%.1f", perHour);
	}

	/** A count and its noun, the noun made plural where the count is not one. */
	private static String count(long n, String one)
	{
		return fmt(n) + " " + (n == 1 ? one : one + "s");
	}

	private static String hoursMinutes(long minutes)
	{
		return minutes >= 60 ? (minutes / 60) + "h " + (minutes % 60) + "m" : minutes + "m";
	}

	// The hiscores grid: every skill's level at the period's close and the
	// period's gain. A skill that didn't move keeps its place and says nothing.
	// The headline above it carries the totals.
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

		// Three across, which is the shape the sheet is read in. The skill's own
		// name will not fit beside its icon at 62px, and the icon is what a
		// reader looks for anyway.
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
		// Combat beside Total, the way the game's own panel puts them, and each
		// carrying the reading that belongs to it: the period on the total, the
		// combat counters on the combat level.
		// Side by side while the total is one figure. On a period it becomes
		// three - where the level opened, where it closed, and what moved - and
		// half of a 242 pixel column cannot hold that beside a name, so the two
		// tiles take a row each instead of overlapping in one.
		JPanel combat = combatLevelTile(gain, opened);
		JPanel total = totalLevelTile(stand, opened);
		// The one cell on the sheet that was not a door, and the hover is where
		// it says so: a door with no sign on it is a door nobody opens.
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

	/** The combat level, wearing a handful of the combat counters on hover. */
	private JPanel combatLevelTile(Map<String, Long> gain, HistoryLog.Levels opened)
	{
		JPanel cell = levelTile("Combat");
		int cb = plugin.combatLevel();
		// Where it opened, when the period moved it: the shape the total level
		// tile beside this one already draws. Worked out from the seven skills'
		// opening levels, since the record keeps no dated combat level, and only
		// where all seven are there to work it out from.
		Integer was = openingCombat(opened);
		boolean climbed = !wholeRecord() && was != null && cb > was;
		JLabel fig = new JLabel(cb > 0 ? (climbed ? fmt(was) + " to " + fmt(cb) : fmt(cb)) : "-",
			JLabel.RIGHT);
		fig.setFont(small());
		// Dim unless the period moved it. A combat level is a function of seven
		// skills, and the record keeps no dated copy of it, so what is asked is
		// whether any of those seven gained: they are the only things that can
		// move it, and none of them moving means it did not.
		fig.setForeground(cb > 0 && (wholeRecord() || combatSkillsMoved(gain))
			? TILE_LIT : dim());
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
				fmt(c.getOrDefault(StatKeys.DAMAGE_DEALT, 0L)),
				fmt(c.getOrDefault(StatKeys.HIGHEST_HIT, 0L))}));
		link(cell, () ->
		{
			sheetPage = "combat";
			rebuild();
		});
		return cell;
	}

	// Which kinds of feed line landed inside the window. Answered once a build:
	// three tiles ask, and each ask is a walk of the feed.
	private Map<String, Long> movedTypes;

	/** How many lines of this kind the window holds. */
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

	/**
	 * Whether the period moved what an activity tile counts.
	 *
	 * <p>The tiles carry a STANDING - every clue ever opened, the whole
	 * collection log - because that is what they are the way in to. Standing
	 * still under a heading that names a week reads as the week's work, so a
	 * tile the period never touched is dimmed, the same way a skill that gained
	 * nothing is. Lifetime moved everything, by construction.
	 */
	private long activityMoved(String label, String source)
	{
		if (wholeRecord())
		{
			return -1;   // everything, which is not a movement to state
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
			// The roll counts what paid out, which is not what a tile reading one
			// of its page's own lines counts: a period can say THAT the rift was
			// played, never how many of the searches closed one.
			return rolled > 0 && namedLine(source, label) > 0 ? -1 : rolled;
		}
		return 0;
	}

	/** The source's log page line named exactly as the tile is, or 0. */
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
		return buildPeriodCounters == null ? Collections.emptyMap()
			: buildPeriodCounters;
	}

	/**
	 * The handful of top-level counters that say how a craft is going, as a
	 * hover card.
	 *
	 * <p>Not the level and not the experience: the cell under the pointer draws
	 * both, and a hover that repeats what it is over is a hover that says
	 * nothing. Not the typed rows either - "Guard: 2" and "Maple: 47" are the
	 * drill-in's business - so a card of five or six totals reads as an overview
	 * and not as the first page of the list it sits above.
	 *
	 * <p>Floors first in the table's own order, since Prayer's bones buried,
	 * ashes scattered and heads reanimated are curated to read together; then
	 * the named keys by size, since Fletching's fifteen are not, and a card that
	 * took them alphabetically would carry darts and bolts over the nine hundred
	 * thousand arrow shafts. For the period the strip is set to, so a week's
	 * card is that week's. Where the period moved none of them the card carries
	 * the craft's name alone, which is what the dimmed cell beneath it already
	 * said.
	 */
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

	/**
	 * Slayer's card, which is not a list of counters: the skill has a whole board
	 * of its own and this is the way in to it.
	 */
	private String slayerTip()
	{
		long[] ms = windowMs();
		long[] tally = plugin.onTaskTally(ms[0], ms[1], null, wholeRecord());
		long paid = tallyOf(plugin.onTaskLoot(ms[0], ms[1], null, wholeRecord()))[1];
		return tip("Slayer", new String[]{"Tasks tracked", "Kills on task", "On-task loot"},
			new String[]{fmt(tally[2]), fmt(tally[0]), gps(paid)});
	}

	/** The combat level the seven opening levels work out to, or null short of all seven. */
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
		// attack, strength, defence, hitpoints, ranged, magic, prayer
		return net.runelite.api.Experience.getCombatLevel(lv[0], lv[1], lv[2], lv[3],
			lv[5], lv[4], lv[6]);
	}

	// The seven a combat level is worked out from.
	private static final Skill[] COMBAT_SKILLS = {
		Skill.ATTACK, Skill.STRENGTH,
		Skill.DEFENCE, Skill.HITPOINTS,
		Skill.RANGED, Skill.MAGIC,
		Skill.PRAYER};

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
		return safeLong(obj(obj(bundledCombat, "_meta"), "totals").get("points"));
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

	// Takes the injected Gson rather than making one, as bossRoster and taxonomy
	// beside it do. A fresh Gson is on the Plugin Hub's disallowed list and is
	// what its packager rejects a plugin for, and this one wore a fully qualified
	// name, so it did not answer a grep for "new Gson()".
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
				// Dimmed, like an unfinished diary tier and an undone combat task.
				// The dash alone left a tier nobody has ever opened reading exactly
				// as bright as one they have.
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

	/**
	 * The combat achievement task ids the game says are done.
	 *
	 * <p>Captured as a plain list of ids rather than as words, so the journal
	 * carries the smallest thing that can answer "which" and the bundled table
	 * supplies the rest. Empty for a journal written before this was captured,
	 * which the board has to handle rather than read as "nothing done".
	 */
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
				// a non-numeric id is not an id
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
			head.add(row("Regions finished", fmt(d[2]) + " / " + fmt(d[3])));
		}
		spaced(p, head);
		if (!known)
		{
			spaced(p, note("Which tiers you have finished arrives when you next log in. "
				+ "Until then this is what each one asks for."), 4);
		}
		// The game states which TIERS are done and never which tasks, so a tier is
		// ticked or it is not, and the tasks under it are what it asks for rather
		// than a checklist of what is left.
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
				// Done is carried by brightness here too, so the count can stay on
				// every row: a finished tier used to say "done" and take its own
				// task count away with it, and it was the one marker in the panel
				// that was a word rather than a weight.
				JPanel line = row(prettyTier(tier),
					fmt(n) + " tasks",
					known && !got ? dim() : null,
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
	private static String taskTip(String title, JsonArray tasks)
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
		head.add(row("Tiers unlocked", fmt(c[2]) + " / 6"));
		Set<Integer> headDone = caDone();
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
		spaced(p, head);
		if (unnamed > 0)
		{
			spaced(p, note("You have also done " + fmt(unnamed) + " combat achievement"
				+ (unnamed == 1 ? "" : "s") + " added to the game since this copy of"
				+ " Chronicle was built. They are counted by the game, not named"
				+ " here, until the plugin updates."), 4);
		}
		// Which tasks are DONE, from the game's own per-task bits rather than from
		// the handful this journal happened to watch land. Empty on a journal
		// written before those bits were captured, and the board then says what each
		// tier asks for rather than pretending nothing is done.
		Set<Integer> done = headDone;
		boolean known = !done.isEmpty();
		if (!known)
		{
			// Said, not implied. Without it the board draws every task in the same
			// colour and a reader with no reason to think otherwise reads that as
			// an answer rather than as the absence of one.
			spaced(p, note("Which tasks you have done arrives when you next log in. "
				+ "Until then this is what each tier asks for."), 4);
		}
		// Filed by what they are fought against, not by tier. Six tiers meant one
		// of them was a hundred and seventy three rows to mount the moment it was
		// opened; ninety one sources with six tasks apiece is a list a reader can
		// hold and a fold that costs nothing to open. Each row still names its
		// own tier, so nothing about them is lost.
		Map<String, List<JsonObject>> bySource = new TreeMap<>(
			String.CASE_INSENSITIVE_ORDER);
		for (String id : all.keySet())
		{
			JsonObject task = all.getAsJsonObject(id).deepCopy();
			// the table is KEYED by the game's task id and the rows do not carry it
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

	/** The head card's three figures, as the markup a tooltip takes. */
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

	/** One more line on the foot of a hover card already built. */
	private static String tipLine(String card, String line)
	{
		return card.replace("</body>", "<div style='color:#8f8f8f'>" + line + "</div></body>");
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
			// Clipped as taskTip clips: a figure is a number or a name, and the
			// one place a sentence reaches this - a combat task's description -
			// threw a tooltip across the monitor.
			String figure = figures[i].length() > 78 ? figures[i].substring(0, 78) + "..."
				: figures[i];
			sb.append("<div>").append(labels[i]).append(": <span style='color:#c8a25a'>")
				.append(figure).append("</span></div>");
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
		JPanel cell = levelTile("Total level");
		if (periodTip != null)
		{
			cell.setToolTipText(periodTip);
		}
		JLabel fig = new JLabel(figure, JLabel.RIGHT);
		fig.setFont(small());
		// The same rule the skills above it and the tiles below it follow: a
		// standing that this period did not move reads dim. A lifetime moved all
		// of it, so it is never dimmed there.
		fig.setForeground(levels > 0 ? accent()
			: wholeRecord() ? Color.WHITE : dim());
		cell.add(fig, BorderLayout.EAST);
		return cell;
	}

	// One skill: its icon, where it began and where it ended, its name, and the
	// period's gain.
	private JPanel skillCell(Skill sk, long level, Long gained, Long from)
	{
		JPanel cell = tile(3, 4);
		final String craft = prettify(low(sk.name()));
		// The same hover card the boss and activity tiles draw. This was the last
		// tile on the sheet answering in a sentence while the two grids under it
		// answered in a titled block.
		boolean slayer = Skill.SLAYER.equals(sk);
		cell.setToolTipText(slayer ? slayerTip() : skillTip(craft));
		// The cell has always carried a tooltip, which is a mouse listener; this
		// is what makes the hand cursor honest. Its counters had no other way in.
		// Slayer opens the board it has rather than a drill of its counters: the
		// tasks, what each paid and the kills on them are a whole view already,
		// and a card of slayer counters beside it would be the lesser half.
		// The hover promised the tasks, the kills on them and what they paid, so
		// the click lands on the Tasks lens rather than on whichever lens the
		// slayer board was last left on: the lens is sticky by design and this
		// is the one way in that names a destination.
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
			// No sprite cache: the skill's first letters, or the grid is nameless
			// numbers.
			icon.setText(sk.name().substring(0, Math.min(3, sk.name().length())));
			styled(icon, small(), dim());
		}
		cell.add(icon, BorderLayout.WEST);

		JPanel text = new JPanel(new GridLayout(gained != null ? 2 : 1, 1));
		text.setBackground(DARKER);
		// Where it began and where it ended, when it moved between them. A
		// lifetime says only where it stands: everything came before it, so
		// naming the start tells a reader what they already assumed.
		boolean climbed = from != null && level > from && !wholeRecord();
		JLabel lvl = styled(new JLabel(level <= 0 ? "-"
			: climbed ? fmt(from) + " to " + fmt(level) : String.valueOf(level)), small(),
			gained != null ? Color.WHITE : dim());
		text.add(lvl);

		if (gained != null)
		{
			// No unit: it is a skill, so it is experience. And no plus on the
			// whole record, where the figure is what the skill HAS rather than
			// what some period added to it. "+13.6M xp" did not fit the cell and
			// clipped to "+13.6M ...", which spent the room on the one word the
			// reader did not need.
			JLabel g = styled(new JLabel((wholeRecord() ? "" : "+") + xpShort(gained)), small(),
				accent());
			text.add(g);
		}
		cell.add(text, BorderLayout.CENTER);
		return cell;
	}

	private static final Map<Skill, BufferedImage> SKILL_ICONS =
		new EnumMap<>(Skill.class);

	// The game's own skill icon, loaded once and shared.
	private BufferedImage skillIcon(Skill sk)
	{
		return SKILL_ICONS.computeIfAbsent(sk, s ->
		{
			try
			{
				return plugin.skillIcons().getSkillImage(s, true);
			}
			catch (Throwable e)   // noqa: no icon is worth the tab it sits on
			{
				return null;   // a dev client without the sprite cache
			}
		});
	}

	private String histFacet = "Skills";
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

	private final Map<Integer, BufferedImage> facetIcons = new LinkedHashMap<>();
	private final Set<Integer> facetAsked = new HashSet<>();
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
			// once, ever: every build used to queue four more tasks on the client
			// thread, and a reader clicking about queued them faster than the
			// client drained them
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
		catch (Throwable ignored)   // noqa: a tab icon is never worth a blank tab
		{
			// the word stands in
		}
	}

	// Every icon the tab has already shrunk, by what it is and how big. Scaling
	// is not free and it happens on the event thread: a band redrawn on every
	// fold click would smooth-scale the same dozen images again each time, which
	// is per-click work of exactly the kind that made this tab heavy before.
	private final Map<String, ImageIcon> scaledIcons = new LinkedHashMap<>();

	/**
	 * An image on a label at the size asked for, its shape kept: a 36x32 item
	 * squeezed into a square column is a squashed item. A width of zero means the
	 * image's own size. The result is kept under the key the caller names it by,
	 * so it is scaled once and worn thereafter.
	 */
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
	private LocalDate periodFrom;
	private LocalDate periodTo;

	// the choices, built fresh so the tick sits on whichever is current
	private JPopupMenu periodMenu()
	{
		JPopupMenu menu = new JPopupMenu();
		for (String g : PERIODS)
		{
			menuItem(menu, g, g.equals(histGranularity) && histFrom == null, () ->
			{
				// The cursor becomes the END of the window that was on screen,
				// clamped to today. Every pick used to reset it to today, so a
				// reader who stepped Day back to the twelfth to find something
				// and then picked Week to read the week around it was thrown
				// back to the present. From Lifetime or the sitting the end is
				// today anyway, so nothing changes there.
				LocalDate keep = window().end;
				LocalDate today = LocalDate.now();
				histGranularity = g;
				histFrom = null;
				histTo = null;
				histCursor = keep.isAfter(today) ? today : keep;
				rebuildInPlace();
			});
		}
		// Any two days, from the same list: at Lifetime there is no dateline to
		// click, so this is the only way back to a window of one's own choosing.
		menu.addSeparator();
		menuItem(menu, "Exact dates", histFrom != null, () -> onSetExactDates(
			periodFrom != null ? periodFrom : LocalDate.now().minusDays(6),
			periodTo != null ? periodTo : LocalDate.now()));
		return menu;
	}

	// One choice in a menu, lit where it is the current one; a click runs go.
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

	/**
	 * The tasks, as a menu. A slayer career names more of them than will fit as
	 * pills in a panel this wide, and the period control already established how
	 * this panel asks a question with more answers than it has room for.
	 */
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
		// the kinds under one task are not the kinds under all of them
		lootKind = null;
		rebuildInPlace();
	}

	/**
	 * The row that says which TASK is on show and opens the menu. Drawn like the
	 * period row above it, because it is the same kind of control.
	 */
	private JPanel taskPicker()
	{
		JPanel r = row("Task", lootTask == null ? "Every task" : lootTask, accent());
		JLabel name = styled(part(r, BorderLayout.CENTER), small(), dim());
		JLabel pick = part(r, BorderLayout.EAST);
		pick.setFont(small());
		pick.setToolTipText("Narrow this board to one task");
		// The label needs the listener too, not just the row. Its tooltip registers
		// it with the ToolTipManager, which adds a mouse listener of its own, and
		// from then on the label is the deepest listening component under the
		// pointer: Swing delivers the press there and does not pass it up, so the
		// row's listener never runs. The task name is the half of this row that
		// looks most like the control, and it was the half that did nothing.
		link(pick, () -> taskMenu().show(r, 0, r.getHeight()));
		link(r, () -> taskMenu().show(r, 0, r.getHeight()));
		return r;
	}

	/** The window the period control is on: its two ends, and what to call it. */
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

	/** The loot the period holds: the sitting's own entry, or the dated roll's window. */
	private LocalStore.LootWindow lootWindow()
	{
		Window w = window();
		return sessionPeriod() ? plugin.sessionLootWindow() : plugin.lootBetween(w.start, w.end);
	}

	/**
	 * The period every board is read through. It used to be computed inside the
	 * Progression tab, which is why only that tab could honour it; it is a value
	 * now, so the row above the tabs and the boards below them are reading the
	 * same two dates.
	 */
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
					// everything the record holds, from its first line to today. A
					// period with no earlier line to measure against reads as the
					// account's own beginning, which is what it is.
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

	/**
	 * Whether a window that holds no closing line of its own can still be read,
	 * because it reaches today and the client is the closing figure.
	 *
	 * <p>Today's baseline is not written until the day rolls over or the client
	 * closes. Until then a window ending today has only the line it opened on,
	 * and every board measuring between two lines read as empty: the sheet drew
	 * no skills, the Kills board said the window held fewer than two baselines,
	 * and the counters answered nothing, on a day the player had plainly been
	 * playing. The live sheet, ledger and trackers close it instead.
	 *
	 * <p>The opening still has to be the eve of the window, or the gain would
	 * carry days the heading does not name: a record three days cold measures
	 * three days and would print them under today's date. A sitting is exempt,
	 * being counted from the trackers rather than measured between two lines.
	 */
	private boolean closesOnTheClient(Entry<LocalDate, Baseline> from,
		LocalDate start, LocalDate end)
	{
		if (from == null || end.isBefore(LocalDate.now()))
		{
			return false;
		}
		return sessionPeriod() || !from.getKey().isBefore(start.minusDays(1));
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

	/** Whether the period on show runs up to today, rather than ending before it. */
	private boolean periodReachesToday()
	{
		Window w = window();
		return w != null && !w.end.isBefore(LocalDate.now());
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
			return withLedgerSpend(counters());
		}
		// The sitting is counted, not measured. Every other period is the distance
		// between two of the spine's closed baselines; this one has no closing
		// baseline because it has not closed, and it needs none - the counters ARE
		// the session, exactly, with nothing subtracted from anything.
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
		// The same for every counter: a period reaching today closes on the live
		// totals. Without it the trackers board reported a week that stopped at
		// midnight and caught up overnight.
		if (s == null)
		{
			return null;
		}
		return peaksNotDeltas(HistoryLog.gained(s.opening.counters,
			s.earliest.counters, closingNow(s.closing.counters, counters())), s);
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
	 * A high-water counter answers a period with its PEAK, not with a difference.
	 *
	 * <p>Highest hit is a record, not a tally. Subtracting one record from
	 * another is the arithmetic every other counter wants and the one thing this
	 * kind must not have: a best of 68 at the start of a week and 75 at the end
	 * printed "Highest hit 7", which nobody hit. The store has always known this
	 * - LocalStore.MAX_KEYS takes a max where a lifetime would otherwise sum -
	 * and StatRegistry has carried the same two names under a comment saying a
	 * period delta of one means nothing. Nothing read it.
	 *
	 * <p>Where the record ROSE inside the window, the closing figure is the
	 * period's own best by definition: it beat everything that came before it.
	 * Where it did not rise, the period's best is simply not in the record - the
	 * spine keeps the running maximum and not the hits under it - so the row is
	 * dropped rather than answered with a nought or with an older record.
	 */
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
			// A source with its own node on the sheet is not also a skill's
			// ground: Guardians of the Rift is Runecraft's, but the hiscores give
			// it a node of its own and so do we, and one thing counted in two
			// places on one board is a board that disagrees with itself.
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
		JPanel p = backPage();
		consumVals = plugin.consumableValues();
		String key = low(craft);

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
		// the minutes the trackers filed under this craft, where they cover the
		// whole period, and the xp per hour they come to where the gain is known
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
			// Seven of the grid's skills file no counters at all: Attack, Strength,
			// Defence, Hitpoints, Ranged, Magic and Slayer. A skill that tracks
			// nothing should say so rather than open on a blank.
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
			// Named for what it is. These are the ledger's running per-source
			// totals, which no period touches: the block was byte for byte the
			// same under Lifetime and under a sitting, sat directly beneath a
			// "Gained" row that IS the period, and read as the period's.
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

	/** What a board says when the window holds too little to be a period. */
	private JPanel noPeriod()
	{
		return note(historySpine == null
			? "Reading your history..."
			: "Nothing closed inside " + periodInSentence() + ". A period is the distance "
				+ "between two baselines, and this window holds fewer than two.");
	}

	/** The strip stating its scope rather than offering to change it. */
	private static JPanel fixedPeriod(JPanel r, String scope)
	{
		JLabel fixed = styled(new JLabel(scope, JLabel.CENTER), FontManager.getRunescapeFont(),
			dim());
		r.add(fixed, BorderLayout.CENTER);
		return r;
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
		JPanel r = stepStrip();
		// The sitting is now and can be nothing else, so the row states its scope
		// rather than offering to change it. It still draws, at the same height in
		// the same place, because a control that vanishes on one tab moves every
		// tab under it.
		if (showingSitting())
		{
			return fixedPeriod(r, "This session");
		}
		// A search reads the whole record whatever the period says, so the strip
		// states that rather than offering arrows that redraw the same list.
		if (!searchQuery().isEmpty())
		{
			return fixedPeriod(r, "Whole record");
		}
		// No arrows on the sitting: there is one, and it is this one. Stepping the
		// cursor off it would name a day and go on calling it "This session".
		if ((!"Lifetime".equals(histGranularity) && !sessionPeriod()) || histFrom != null)
		{
			// The log has no forward. A reader arrives on the present window and
			// returns to it every time they pick a period, so this arrow spent
			// most of its life computing the next granule, finding it after today
			// and clamping straight back - a press that redrew the same board,
			// from a control wearing the same accent and the same hand cursor as
			// the live one beside it. Drawn dim and inert instead, which is the
			// rule the row already keeps for Lifetime and for the sitting, and
			// still drawn so the label between them does not slide.
			arrows(r, () -> stepPeriod(-1), canStepForward(), () -> stepPeriod(1), null);
		}
		JLabel lbl = styled(new JLabel(w.label, JLabel.CENTER), FontManager.getRunescapeFont(),
			accent());
		lbl.setToolTipText("Choose the period");
		link(lbl, () -> periodMenu().show(r, 0, r.getHeight()));
		r.add(lbl, BorderLayout.CENTER);
		return r;
	}

	/** The strip the period and the calendar's month sit on. */
	private static JPanel stepStrip()
	{
		JPanel r = new JPanel(new BorderLayout());
		r.setBackground(DARKER);
		r.setBorder(pad(3, 8, 3, 8));
		r.setAlignmentX(Component.LEFT_ALIGNMENT);
		r.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
		return r;
	}

	/**
	 * "<" and ">" on a strip, around {@code title} where there is one; ">" dim
	 * and inert unless {@code ahead}.
	 */
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

	/**
	 * Whether there is anywhere forward to go.
	 *
	 * <p>With exact dates this also closes a hole. Stepping moved BOTH ends by
	 * the span with no clamp, while window() pins only the end to today, so
	 * pressing on past the present first collapsed the window onto one end and
	 * then inverted it - and the dateline, built from the unpinned start and the
	 * pinned end, printed backwards: "22 Sept - 20 Sept".
	 */
	private boolean canStepForward()
	{
		if (histFrom != null && histTo != null)
		{
			return histTo.isBefore(LocalDate.now());
		}
		return !stepForward(histCursor).isAfter(LocalDate.now());
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
			LocalDate next = stepForward(histCursor);
			histCursor = next.isAfter(LocalDate.now())
				? LocalDate.now() : next;
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
		final LocalDate pStart = periodWin.start;
		final LocalDate pEnd = periodWin.end;
		final boolean live = !pEnd.isBefore(LocalDate.now());

		// Ask for a fresh pass when the day has turned or the feed has grown.
		// Probing the newest entry costs one copy; the gather costs thousands,
		// and the stale pair still renders while it runs.
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

		// baselines bounding the period: closing state the day before it began,
		// and the last close inside it. With nothing closed before the window the
		// earliest line on record stands in, the way the site measured from its
		// first snapshot: a fresh record's first week reads from its first day.
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
			Baseline earliest = HistoryLog.earliest(hist, at.getKey());
			// The states standing at each end of the window, not the bare lines:
			// a line says only what moved that day, and a complete snapshot says
			// a skill it omits stood at zero. stateAt folds the record up to a
			// date into the state that stood on it. The opening is taken at the
			// line the window is measured from, which is the eve of the window
			// when a line predates it and the earliest line on record when none
			// does.
			Baseline closing = HistoryLog.stateAt(hist, at.getKey());
			Baseline opening = HistoryLog.stateAt(hist, from.getKey());
			// A period that reaches today closes on the client, not on the spine.
			// The spine's newest line is written when the journal is flushed, so
			// a board measuring to it sat still through an hour of training and
			// moved on logout: the levels above already read live and the GAIN
			// did not, which is the half a reader is watching.
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
						gains.add(new AbstractMap.SimpleEntry<>(
							low(g.skill.name()), g.xp));
					}
				}
			}
			gains.sort(Entry.<String, Long>comparingByValue().reversed());
			// The standing figures are the period's close, its last line, the
			// way the site drew every period as a snapshot. Only a period that
			// reaches today reads the live sheet and ledger: its closing line
			// is the newest one, and they are that state a few minutes fresher.

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
				: startMs(pStart);
			long toMs = sessionPeriod() ? windowMs()[1]
				: startMs(pEnd.plusDays(1));
			Map<String, Long> fromFeed = new HashMap<>();
			Map<String, List<String[]>> named = new LinkedHashMap<>();
			long[] played = {0, 0};   // minutes, sessions
			// the sessions' own take: drops, their gp, the stacks left, their gp,
			// the kills that left one, and how many of the sittings counted the
			// floor at all. The floor is only an account of this period when
			// every one of them did.
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
						// A session closes with what it took: the loot events it
						// saw and what they were worth. Dated, one line per
						// sitting, and the only account of the take that reaches
						// back before the spine began carrying the journal's own
						// totals.
						JsonObject d = obj(e, "data");
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
			Map<String, Long> retro = new HashMap<>();
			boolean sessionsHoldTheFloor = false;
			boolean sessionsSpeak = false;
			if (historyJourney != null)
			{
				// The closed segments and the kills they hold, each task's own count
				// as its completion set it, the kills the loot never saw included:
				// one walk, so the two lines agree on which tasks are the period's.
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
				LocalStore.LootWindow dated = null;
				if (sessionPeriod())
				{
					// The roll is dated by DAY, so asked for a sitting it answers
					// with the day the sitting is in. The sitting keeps its own
					// entry in the roll's shape instead, written as the drops
					// land, so it opens the same named lists as any other period.
					dated = plugin.sessionLootWindow();
				}
				else if (rollFrom > 0 && rollFrom <= fromMs)
				{
					dated = plugin.lootBetween(pStart, pEnd);
				}
				if (dated != null)
				{
					sessionsSpeak = true;
					sessionsHoldTheFloor = true;   // the roll dates the floor too
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
					// and the floor is this period's only when every one of the
					// sittings counted it: an older sitting that never did would
					// be read as one that left nothing behind
					sessionsHoldTheFloor = took[5] == played[1];
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
			// Counted in the period it began in, as its closing line will be, even
			// once midnight has passed and that period no longer reaches today;
			// and zero once logged out, which is exactly when the closing line
			// exists to be counted instead - so the two can never both be in the sum.
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
			LocalDate leftFrom = HistoryLog.firstCarrying(
				hist.headMap(at.getKey(), true), "lootLeftKills");
			boolean leftDated = sessionsSpeak
				? sessionsHoldTheFloor
				: (leftFrom != null && !leftFrom.isAfter(from.getKey()));
			// A window is a delta between the spine's two ends. A lifetime has no
			// earlier end, and a delta measured from the first line the record
			// holds reports nothing for every total that joined the spine later:
			// a board listing 166M of loot sat under a headline reading zero.
			// So a lifetime is the totals themselves.
			boolean whole = wholeRecord();
			HistoryProgress progress = HistoryProgress.of(
				whole ? withLedgerSpend(closing.counters)
					: HistoryLog.gained(opening.counters, earliest.counters,
						closing.counters),
				null, whole ? new HashMap<>() : retro, leftDated || whole);
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
			// On the sheet the head card is not drawn at all: its four figures are
			// what the total level tile says when the cursor is on it, which costs
			// no room on a 225px board and puts the reading beside the number it
			// describes. Everywhere else it is still a card.
			if (view == View.SHEET)
			{
				periodTip = periodTip(played, gains);
			}
			else
			{
				spaced(p, headline(progress, gains, stand, opened, played), 5);
			}
			// What the loot rows actually reach back to. The sittings are the
			// only dated account of a take, so where they do not reach back to
			// the period's start no loot figure was drawn at all, and the note
			// names the day one could be. Where they do, the figures are theirs
			// and the spine's own start date says nothing about them.
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
			// Only a window needs it. A lifetime reads the totals themselves, which
			// are whole whatever day the spine began carrying them, so a line
			// saying the record starts in August would be untrue of every figure
			// above it.
			String since = wholeRecord() ? null
				: countersSince(hist.headMap(at.getKey(), true), from.getKey(),
					lootSince, lootFromTs > 0);
			if (since != null)
			{
				spaced(p, note(since), 5);
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

	// Any two dates: a small dialog, ISO or d/M/yyyy, prefilled with the
	// visible period.
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
			// fall through to d/m/yyyy
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

	// The kinds a reader looks for, and the feed types behind each. Kept few. A
	// row per type reads as a database query.
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

	/**
	 * The Journal on the day of one line, the period following it. The search
	 * reads the whole feed and the board reads the window, so a hit found under
	 * September and opened landed on "nothing in September" with the line the
	 * reader had just been shown nowhere on screen.
	 */
	private void openJournalOn(long ts)
	{
		if (ts > 0)
		{
			histGranularity = "Day";
			histFrom = null;
			histTo = null;
			histCursor = dayOf(ts);
		}
		// a lens left on Deaths would open a slayer line's day without it
		journalLens = "All";
		applyTab(View.JOURNAL);
	}

	// The thresholds the game never announces, dated to the day the spine first
	// stood past them. Worked out once a build from the closing baselines,
	// newest first; lines of the record rather than entries of the feed.
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
					// A partial line speaks only for the axes it can: it carries
					// no total level or count of 99s. Those keep the last standing
					// that did, or the next complete line's crossing would have
					// nothing to be measured against and go unrecorded.
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

	/** What one baseline stands at, on every axis a milestone is drawn on. */
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

	/** Every threshold {@code now} stands past that {@code prev} stood short of. */
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

	/** A threshold as the round figure it is: 10M, not gp()'s 10.0M. */
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

	/** The feed with the milestones merged in by date; a live sitting at the head stays there. */
	private List<JsonObject> withMilestones(List<JsonObject> feed)
	{
		List<JsonObject> marks = milestones();
		if (marks.isEmpty())
		{
			return feed;
		}
		List<JsonObject> out = new ArrayList<>(feed.size() + marks.size());
		int m = 0;
		// The head is the sitting in progress when there is one, and that is
		// later than everything however it is stamped. With no sitting it is an
		// ordinary line, and skipping it filed a newer milestone under it.
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

	// When each log slot first landed, off the feed: item name (lower) to the
	// earliest COLLECTION line naming it.
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
					// newest first, so the last write is the first landing
					landedSlots.put(low(d.get("itemName").getAsString()),
						safeLong(e.get("ts")));
				}
			}
		}
		return landedSlots;
	}

	// The newest RECORD line per source (lower), for the date a best was set.
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

	// Every day a sitting was written, off the feed: {minutes, sittings}. Once a
	// build; the calendar, the day lines and the recap all read it.
	private Map<LocalDate, long[]> daysPlayed;
	// and the roll by day, keyed as the roll keys it
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
				// the day it BEGAN (sittingStart)
				LocalDate day = dayOf(sittingStart(e));
				// A sitting that ran past midnight touches every day it spans.
				long ended = safeLong(e.get("ts"));
				if (ended > 0)
				{
					LocalDate last = dayOf(ended);
					for (LocalDate on = day; last.isAfter(day) && !on.isAfter(last); on = on.plusDays(1))
					{
						crossedDays.add(on);
					}
				}
				// {minutes, sittings, xp, sittings saying their xp, drops, their
				// gp, sittings saying their drops, sittings saying their skills}
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
					t[7]++;   // no skills because none moved, which says them all
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

	// each day's xp by skill, off the sittings that began on it; filled with daysPlayed
	private Map<LocalDate, Map<String, Long>> daySkills;
	// the days a sitting crossing midnight touched; filled with daysPlayed
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

	/** The xp one day added over the day before it: {total, most} with the skill that moved most. */
	private Object[] dayXp(LocalDate day)
	{
		TreeMap<LocalDate, Baseline> spine = historySpine;
		if (spine == null)
		{
			return null;
		}
		Baseline at = spine.get(day);
		Entry<LocalDate, Baseline> before = spine.lowerEntry(day);
		// Only against the day before it. A week away and the next line carries
		// the whole gap, and attributing that to the first day back is a figure
		// nobody earned in a day.
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

	/** How wide a row's text can run on a board, outside any card. */
	static int boardRowRoom()
	{
		return PluginPanel.PANEL_WIDTH + PluginPanel.SCROLLBAR_WIDTH
			- 2 * PANEL_INSET - scrollbarWidth() - 2 * ROW_INSET;
	}

	/**
	 * Clauses joined by " · ", packed into as few lines as fit {@code room}. A
	 * line breaks only between clauses, so no figure is ever split from its
	 * unit.
	 */
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

	/**
	 * One day's line, under its heading in the Journal: sittings and minutes,
	 * xp and the skill most of it went to, drops and their gp. Figures only,
	 * each clause present only where the record has one; null for a day with
	 * none of them.
	 */
	private String dayEntry(LocalDate day)
	{
		List<String> clauses = new ArrayList<>();
		long[] sat = daysPlayed().get(day);
		if (sat != null && sat[1] > 0)
		{
			clauses.add(sat[1] + (sat[1] == 1 ? " sitting" : " sittings")
				+ (sat[0] > 0 ? " · " + hoursMinutes(sat[0]) : ""));
		}
		// On a day a sitting crossed midnight into or out of, the xp and the
		// drops of the sittings filed under it, where every one says its own, so
		// the line adds up to the rows beneath it: the spine's day and the loot
		// record's are midnight to midnight and part such a sitting. On any
		// other day the spine's and the roll's, which History and Records read
		// too, and which count a sitting the client closed without writing.
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

	/**
	 * The period as one plate. A dozen figures at most, every one a door to
	 * the board it was read off, and nothing on it that is not a figure: the
	 * record composed, not described.
	 */
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

	/**
	 * The Recap's copy, which is not a picture of the card: the whole period on
	 * one sheet, up to 1920 by 1080. The card is a dozen doors; a picture has
	 * none, so it carries what they open onto.
	 *
	 * <p>The boss icons are the game's sprites, fetched on the client thread,
	 * and a sheet that has not drawn the bosses yet has not asked for them. So
	 * the ones it lacks are asked for and the picture is taken when they have
	 * landed, or after a moment and a half without them: a name with no icon
	 * beside it is worth more than a copy that never arrives.
	 */
	private void copyRecapPicture(JLabel take)
	{
		RecapPicture.Facts facts;
		try
		{
			facts = recapFacts();
		}
		catch (Throwable t)   // noqa: a picture is never worth the panel
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

	/** The picture itself, so that a preview can be drawn without a clipboard. */
	BufferedImage recapImage(RecapPicture.Facts facts)
	{
		try
		{
			return RecapPicture.paint(facts, this::skillIcon, facetIcons::get);
		}
		catch (Throwable t)   // noqa: a picture is never worth the panel
		{
			return null;
		}
	}

	/**
	 * Everything the Recap's picture says, each figure read the way the board it
	 * belongs to reads it and over the same window: the skills as the sheet
	 * measures them, the bosses as their cells count them, the loot off the
	 * roll the Loot board reads, the counters off the Trackers' own period.
	 */
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

	/**
	 * Every skill at both ends of the period. The end is the sheet's close,
	 * the live figures where the period reaches today; the start is the base
	 * the sheet's own gain is measured from, and is left blank where the record
	 * holds none. Levels past 99 are for the whole record only, as on the sheet.
	 */
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

	/** A combat level from real levels by skill key, or null without all seven. */
	private static Integer combatOf(Map<String, Integer> levels)
	{
		HistoryLog.Levels l = new HistoryLog.Levels();
		l.of.putAll(levels);
		return openingCombat(l);
	}

	/**
	 * The bosses: on the whole record every one killed, in the hiscores' order;
	 * over a period every one the period moved, most first, with both ends where
	 * the record's two lines subtract to the cell's own figure.
	 */
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

	/**
	 * The ten monsters killed most that are not a boss, nor a part of a boss's
	 * fight, nor something opened or caught: what the Kills board would call a
	 * monster, with what each paid over the same window.
	 */
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

	/** A monster in the Kills board's sense, and no part of any boss's fight. */
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

	/**
	 * The loot: the ledger on the whole record, the sitting's own roll, and
	 * over a period the dated roll, which answers only a window it reaches all
	 * the way back into. Naming the part it can see as the whole would be
	 * worse than saying nothing, and the day it begins is left off: a shared
	 * picture does not say when anybody started keeping a record.
	 */
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

	/** Slayer as the Recap's card counts it, and the clue caskets by tier. */
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

	// how many rows each family carries on the picture
	private static final Map<String, Integer> RECAP_TRACKER_ROWS = new LinkedHashMap<>();

	static
	{
		RECAP_TRACKER_ROWS.put("Combat", 8);
		RECAP_TRACKER_ROWS.put("Skilling", 10);
		RECAP_TRACKER_ROWS.put("Living", 6);
		RECAP_TRACKER_ROWS.put("Ledger & Roads", 10);
	}

	/**
	 * The counters by family, off the Trackers' own period: the totals a family
	 * heads with rather than every typed row under them, since a picture has
	 * room for what a skill did and not for every log it did it to.
	 */
	private void recapTrackers(RecapPicture.Facts f)
	{
		// on the whole record, the trackers with the ledger's spend, as the board has them
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

	/**
	 * What the period achieved, by name: on the whole record the pets, the
	 * milestones and the bests; over a period every level, log slot, pet,
	 * quest, diary, task and death the journal dated inside it.
	 */
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
					// the task alone: "Master · Vorkath Master · Easy · ..." ran the
					// tiers and the names together
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
		// The levels off the skills above them rather than off the journal's
		// level lines, which began with the plugin: a year that took Cooking
		// from 91 to 98 named three levels and not one of those.
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

	/** A list with each repeat folded into its first: six hats read "Chompy bird hat ×6". */
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

	/**
	 * The figures across the top, each the same figure the card beneath the
	 * copy prints, so the card and the picture never disagree.
	 */
	private void recapTiles(RecapPicture.Facts f)
	{
		long[] sat = sittingsInWindow(new LocalDate[1]);
		long minutes = sat[0];
		int sittings = (int) sat[1];
		// The whole record's time is the game's own count where that is the
		// larger, as on the sheet: the sittings are only the hours this record
		// has watched, and 88 hours beside 256M xp reads as a mistake.
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

	/**
	 * The period's {minutes, sittings, busiest day's minutes}, the busiest day
	 * itself into {@code busiest}. A sitting in progress is ONE sitting and is
	 * measured by its own clock: daysPlayed is keyed by day, and a day's noon
	 * falls outside the sitting's own window, so reading it there counted the
	 * whole day or nothing at all.
	 */
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

		// the xp and the levels, off the spine
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

		// the loot, off the roll or the ledger
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

		// What it cost to stay alive, in the Ledger's own two sections and its
		// own words, since that is the board these open onto.
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

		// the fights
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

		// the lines the feed dated inside the window
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

	/**
	 * What a period's food or potions cost, where the split was already being
	 * written when the window opened; empty otherwise, since a whole window's
	 * count beside part of its spend is one figure pretending to account for
	 * the other.
	 */
	private String splitSpend(String key, Map<String, Long> counters)
	{
		// the whole record's is the ledger's, which prices every meal it holds
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

	/**
	 * A plate row whose figure carries a name, the name giving way first. The
	 * row's label is the one word saying what the figure is, and a long quest
	 * or task name squeezed it to "Comb..." beside a figure nobody could then
	 * place. The hover keeps the whole line.
	 */
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

	/** One kind of feed line on the plate: its count and the first one named; a door to its lens. */
	private void feedPlateRow(JPanel plate, Map<String, long[]> lines, Map<String, String> named,
		String type, String one, String many, String lens)
	{
		long[] n = lines.get(type);
		if (n == null || n[0] == 0)
		{
			return;
		}
		String name = named.get(type);
		// the count, and the first one the period named, or for a death who dealt it
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

	/** The period's xp as {gained}: the whole record's total, or the spine's delta. */
	private long[] periodXp()
	{
		if (wholeRecord())
		{
			long[] overall = plugin.skillSheet().get("overall");
			return overall != null && overall.length > 1 ? new long[]{overall[1]} : null;
		}
		// A sitting is not measurable between two of the spine's lines: both of
		// them are today's. The trackers hold it instead, as everywhere else.
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

	/** The skill the period's xp mostly went to. */
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

	/** The period's levels as {gained, standing}, or the standing alone on the whole record. */
	private long[] periodLevels()
	{
		if (wholeRecord())
		{
			long[] overall = plugin.skillSheet().get("overall");
			return overall != null && overall[0] > 0 ? new long[]{overall[0], overall[0]} : null;
		}
		// The sitting counts the levels the feed dated inside it, for the same
		// reason its xp is read off the trackers.
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

	/**
	 * A dated period's xp per skill, measured the way the sheet measures it:
	 * between the folded states at its two ends, a skill the opening lacks
	 * taken from its earliest recorded value, and a period reaching today
	 * closed on the live sheet. The Recap read two bare lines instead and
	 * disagreed with the sheet by four million on a year of imported months.
	 */
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

	/** The xp a period closes on: its last state, or the live sheet where it reaches today. */
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

	/** The period's loot as {drops, gp, left, leftGp}. */
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

	/** The single item worth most inside a narrowed period, {name, value}; null on the whole record. */
	private String[] periodDearest()
	{
		if (wholeRecord())
		{
			return null;
		}
		LocalStore.LootWindow win = lootWindow();
		return win.items.isEmpty() ? null : new String[]{win.items.get(0)[0], win.items.get(0)[2]};
	}

	/** What was killed most inside the period, {name, count as text}. */
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
		// The Kills board's own arithmetic, not a closing minus an opening: a
		// species the window first put on the record has no opening line, and
		// subtracting read it as none killed rather than as all of them.
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

	/**
	 * The account's bests, each with its date and what it beat on hover. Read
	 * off the record: the sittings, the spine's day-to-day deltas, and the
	 * roll by day. Nothing here is a goal; every line already happened.
	 */
	private JPanel buildRecords()
	{
		JPanel p = backPage();
		JPanel book = card("Records");
		int held = book.getComponentCount();

		// the longest sitting
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

		// the spine, day against the day before it
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

		// the roll by day
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

		// the one record the counters keep, undated
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

	/** Files {@code e} = {value, ts, ...} into {best, runner-up}; true when it is the new best. */
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

	/**
	 * The days written, as one month at a time: a cell for every day, shaded
	 * by how long the account sat that day, and a door from each written day
	 * to the Journal on it. Arrows step the month; nothing here scrolls.
	 */
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

	/** A colour laid over the panel's dark grey at a weight from 0 to 1. */
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
		// Read deep: a lens over the newest fifty finds nothing rare. And with the
		// sitting in progress at the head of it: everything else reaches the feed
		// as it happens, and the sitting was the one thing a reader could watch
		// go by and not see written down until they logged out.
		List<JsonObject> all = plugin.feedWithSitting(4000);
		// Deeper when the period starts before the newest four thousand lines
		// reach: search reads the whole journal, and a hit it opened on an older
		// day found the day empty.
		if (all.size() >= 4000 && !wholeRecord() && windowMs()[0] < oldestTs(all, false))
		{
			all = plugin.feedWithSitting(JOURNAL_DEEP);
		}
		all = withMilestones(all);
		List<JsonObject> feed = new ArrayList<>();
		for (JsonObject e : all)
		{
			boolean kind = wanted.isEmpty() || wanted.contains(typeOf(e));
			// The period governs this board too: a dated feed read under a
			// month's heading must list that month and not everything.
			if (kind && insideWindow(filedAt(e)))
			{
				feed.add(e);
			}
		}
		// A sitting that crossed midnight sits under the day it began, at the top
		// of it: it is the last thing to have closed on that day. Stable, so a
		// day keeps its own newest-first order.
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
				// The day's own line under its heading: its sittings, its xp and
				// its loot, as figures, each clause only where the record has one.
				String entry = dayEntry(dayOf(ts));
				if (entry != null)
				{
					// A long day wraps at its clauses. As one label it ran off the
					// column at "most i..." and the drops never showed at all.
					for (String line : wrapClauses(entry, boardRowRoom()))
					{
						p.add(ghostRow(line, ""));
					}
				}
			}
			if ("SESSION".equals(typeOf(e)))
			{
				// Two columns, like every row on the panel: as one sentence the
				// line ran past the column on any longer sitting and cut at
				// "75 drops (". The sentence is the hover.
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
			// Not "412 / 412". Standing the obtained count in for the total read as
			// a finished collection log on an account that had simply not synced.
			plate.add(row("Collection log", fmt(fin) + " obtained"));
		}
		// The latest threshold crossed, so it is seen without scrolling for it.
		List<JsonObject> marks = milestones();
		if (!marks.isEmpty())
		{
			JsonObject last = marks.get(0);
			plate.add(row("Last milestone", str(last.getAsJsonObject("data"), "text", "")
				+ " · " + day(safeLong(last.get("ts")))));
		}
		// The way to what the journal holds: the one page that was reachable
		// only by typing its name into the search box.
		plate.add(moreRow("what the journal holds", this::openInfo));
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

	/** Ask for a journal file and hand it to the plugin. Reached from settings. */
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

	/**
	 * The name nearest a query that matched nothing: a source, an item or a log
	 * slot within two edits of the query, whole or by one of its words. Null
	 * where nothing is that close, or the query is too short to be near anything.
	 */
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
		// every name the search itself answers to, so a slip on any of them is caught
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

	/** Edits between two strings, or {@code cap} once they are at least that far apart. */
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

	/** One thing a search found: its row, where it goes, and how well it answered. */
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

	// Rows a group shows before its "Show N more".
	private static final int SEARCH_CAP = 4;

	// Other names the reader types for a skill.
	private static final Map<String, String[]> SKILL_ALIASES = new LinkedHashMap<>();

	static
	{
		SKILL_ALIASES.put("runecraft", new String[]{"runecrafting", "rc"});
		SKILL_ALIASES.put("hitpoints", new String[]{"hp"});
		SKILL_ALIASES.put("woodcutting", new String[]{"wc"});
		SKILL_ALIASES.put("firemaking", new String[]{"fm"});
		SKILL_ALIASES.put("construction", new String[]{"con"});
	}

	/**
	 * How well a name answers a query: 0 the name itself (or its singular), 1
	 * the name begins with it, 2 every word of the query begins a word of the
	 * name, in any order ("hard clue" finds Clue Scroll (Hard)), 3 anywhere
	 * inside it, -1 not at all. Apostrophes are nobody's to type.
	 */
	static int matchScore(String ql, String name)
	{
		if (name == null || ql.isEmpty())
		{
			return -1;
		}
		// Exact on the letters typed, apostrophe and all: "zulrah's" names the
		// scales, and read without it, it named Zulrah in the plural.
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
			return -1;   // apostrophes alone ask for nothing
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

	// Compiled once: a search scores some six thousand names a keystroke.
	private static final Pattern NAME_WORDS = Pattern.compile("[^a-z0-9]+");
	private static final Pattern QUERY_WORDS = Pattern.compile("\\s+");

	/**
	 * Whether a query is a name's initials, a leading "the" or "of" optional:
	 * cox, tob, toa, kbd, cg and gg are what players type for them.
	 */
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

	/** The best of several names' answers; -1 when none answers. */
	/**
	 * A skill or a page in Go to, when the query is close to its name or is
	 * one of its other names typed whole.
	 */
	private static void goTo(List<Hit> go, String ql, String name, String figure, Runnable to,
		long weight, String... also)
	{
		// A close answer only: "king" found inside Cooking took Enter from King
		// Black Dragon, which it names outright.
		int sc = matchScore(ql, name);
		sc = sc > 2 ? -1 : sc;
		// another name only as typed whole: "logs" is the item kind, not the
		// plural of the log's nickname
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

	/**
	 * One group: its heading, its best answers first, and a "Show N more" for
	 * the rest. Every row is one line in one shape, the name on the left and
	 * one short figure on the right; anything more is the hover. Returns how
	 * many it found.
	 */
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
			// the name gives way to the figure, in the font's own ellipsis, and
			// the hover keeps it whole
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

	/**
	 * A hover of any length, in a column: a Swing tooltip does not wrap, and a
	 * diary task with its requirements drew one line of several thousand
	 * pixels off the side of the screen.
	 */
	static String wrappedTip(String text)
	{
		if (text == null || text.length() <= 60 || text.startsWith("<html>"))
		{
			return text;
		}
		String safe = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
		return "<html><body style='width:220px'>" + safe + "</body></html>";
	}

	/** The sheet, opened straight onto one of its pages. */
	private void openSheetPage(String page)
	{
		applyTab(Tab.STANDING);
		sheetPage = page;
		rebuild();
	}

	// As deep as the journal keeps: the store's own cap on the feed.
	private static final int JOURNAL_DEEP = 20_000;

	/**
	 * Ask for the slayer journey while a search is in the box, so the tasks
	 * group reads the latest; the answer redraws the search, and only when the
	 * journey moved, so an unchanged journal cannot start a loop.
	 */
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

	// The journal as search reads it, lowered once and without apostrophes:
	// {lowered, line, filed ms}. Rebuilt when the feed's newest line moves or
	// the spine the milestones come from is read again, not per keystroke.
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

	/**
	 * Search, over the whole record and the bundled tables, one group of
	 * answers under the next: where to go, the fights, the slayer tasks, the
	 * items, the collection log, the quests, the combat achievements, the diary
	 * tasks, the trackers and the journal. Named things come before what is
	 * inside them, so "hunter" opens the skill and "kraken" opens Kraken, and
	 * Enter opens the first row drawn.
	 */
	private JPanel buildSearch(String q)
	{
		JPanel p = column();
		String ql = low(q);
		int total = 0;
		searchFirst = null;

		// Where to go: the skills, the pages, the kinds of item.
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

		// The fights: the ledger's sources, the bosses the sheet lists, and the
		// Kill Log's monsters, once each however many of those name it.
		// The sheet's bosses first, by their own names, each counting the source
		// it opens as seen: Tempoross opens its reward pool, and listed under
		// both names it was one fight twice.
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

		// The slayer tasks, one row a task however often it was given.
		List<Hit> tasks = new ArrayList<>();
		// The board's own read when it has been opened, else the one the History
		// mount made, and a read asked for so the next keystroke has the latest:
		// read from the board alone, this group was empty until Slayer had been
		// visited that session.
		final SlayerJourney journey = journeyCache != null ? journeyCache : historyJourney;
		fetchJourneyForSearch();
		if (journey != null)
		{
			Map<String, int[]> byTask = new LinkedHashMap<>();   // {times, newest index}
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
					seen[1] = i;   // newest first, so the first seen is the newest
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
						journeyCache = journey;   // the list the index was read from
					}
					applyTab(View.SLAYER);
					detailTask = at;
					rebuild();
				}, matchScore(ql, name), seen[0]));
			}
		}
		total += searchGroup(p, "Slayer tasks", tasks);

		// The items: every bag, and what was left on the floor.
		Map<String, long[]> itemAgg = new LinkedHashMap<>();       // name -> {qty, value}
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

		// The collection log: its pages, and every slot, in the log's own green and red.
		List<Hit> log = new ArrayList<>();
		Obtained ob = obtained(clogNow());
		Set<String> slotSeen = new HashSet<>();
		for (Map<String, List<String>> tab : taxonomy(plugin.gson()).values())
		{
			for (Entry<String, List<String>> pg : tab.entrySet())
			{
				final String page = pg.getKey();
				int ps = matchScore(ql, page);
				// Lit once for the page, as the Log tab lights it: copies of one name
				// light positionally, so holding one Ancient page is 1 of 26, not 26.
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
					// a page is the named thing the slots are inside, so it stands
					// a place above a slot that answers as well
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

		// The quests, as the quest board has them.
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

		// The two bundled tables, found by what they ask for as well as by name:
		// they are the things a reader can look up before having done them.
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
						// the tier on the right and the region in the hover: "Lumbridge &
						// Draynor medium" left the task three words to be read by
						diaryHits.add(new Hit(firstSentence(task), low(tier), null,
							region + " " + low(tier) + ": " + task
								+ (needs.isEmpty() || "None".equalsIgnoreCase(needs) ? "" : " Needs: " + needs),
							() -> openSheetPage("diaries"), 3, 0));
					}
				}
			}
			total += searchGroup(p, "Diary tasks", diaryHits);
		}

		// The trackers, below the named things so a counter named after a fight
		// ("Wyrm bones sacrificed") never takes Enter from the fight.
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

		// The journal, every line of it and the milestones, newest first.
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
			// The record often holds the thing one letter away and said nothing.
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

	/**
	 * A diary task to its first sentence: the rest is a note the hover keeps. A
	 * full stop ends one only between a lower-case letter and a capital, so "W.
	 * Ardougne", "the H.A.M. Hideout" and "the Crazy Arc. [sic]" stay whole.
	 */
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

	// Where Enter lands: the first row the search drew.
	private Runnable searchFirst;

	/** A search row is a door: the hand, the click, and the first drawn is where Enter lands. */
	private void door(JPanel r, Runnable go)
	{
		link(r, go);
		if (searchFirst == null)
		{
			searchFirst = go;
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

	/** A closed sitting as {left, right, sentence}: the row draws two columns
	 *  and hands the sentence to the hover. */
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

	/** The skill a sitting's xp mostly went to, where the line kept its split. */
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
				// killCount is this task's kills; older entries name the task in
				// slayerTask. Don't fall back to "count": that is the lifetime
				// tasks-completed streak, and it prints here as a kill count.
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

	// ------------------------------------------------------------------
	// Small Swing helpers
	// ------------------------------------------------------------------

	private static JPanel column()
	{
		// Single-column GridBag: BoxLayout drifts mixed alignments. The constraint
		// is applied to every child as it is added.
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

	/** A card whose caption carries a note on its right, in the caption's grey. */
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
		JLabel g = styled(new JLabel(name.toUpperCase(Locale.ROOT)), small(), accent());
		g.setAlignmentX(Component.LEFT_ALIGNMENT);
		g.setBorder(pad(8, 2, 3, 0));
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

	/** The sheet's three-across grid of tiles. */
	private static JPanel grid3()
	{
		JPanel grid = new JPanel(new GridLayout(0, 3, 2, 2));
		grid.setBackground(DARK);
		grid.setAlignmentX(Component.LEFT_ALIGNMENT);
		return grid;
	}

	/** A dark tile, inset v top and bottom and h either side. */
	// A full-width level tile and its name.
	private static JPanel levelTile(String title)
	{
		JPanel cell = tile(4, 6);
		cell.setAlignmentX(Component.LEFT_ALIGNMENT);
		cell.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
		// CENTER, not WEST. BorderLayout gives WEST and EAST each their preferred
		// width and lets them overlap when the row is narrower than the two of
		// them; CENTER takes what is left. On a period the figure grows from
		// "2,235" to "2,231 to 2,235 - +2" and the two were drawn on top of one
		// another, which is how "Total level" came out as T2a2l3ke2t5o1t2a2l.
		JLabel name = styled(new JLabel(title), small(), dim());
		cell.add(name, BorderLayout.CENTER);
		return cell;
	}

	// The first cap of a list, or all of it where it is shorter.
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

	/** The label a row holds at one side of its BorderLayout. */
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

	/** A drill's page: a column under its back row. */
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
		return DARKER;
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
		cell.setBackground(DARKER);
		cell.setBorder(pad(2, 4, 2, 4));
		JLabel l = styled(new JLabel(reading, JLabel.CENTER), small(), accent());
		cell.add(l, BorderLayout.CENTER);
		link(cell, flip);
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

	// Everything clickable in the panel is wired through here, so the hover is
	// too: a row that goes somewhere answers the cursor, and nothing is drawn for
	// it at rest. The hand cursor alone was a one-pixel tell on a dark panel and
	// readers were not finding the drills.
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
				// whatever was lit before this is not under the pointer now
				unlight();
				JComponent c = (JComponent) e.getComponent();
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

			@Override
			public void mouseExited(MouseEvent e)
			{
				// see stillUnder
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

	private static String low(String s)
	{
		return s.toLowerCase(Locale.ROOT);
	}

	private static String gps(long n)
	{
		return gp(n) + " gp";
	}

	// An item's name, with its count where there is more than one.
	private static String named(String name, long qty)
	{
		return name + (qty > 1 ? " \u00d7" + fmt(qty) : "");
	}

	// A worth after a count, or nothing where there is none.
	private static String tail(long v)
	{
		return v > 0 ? " · " + gps(v) : "";
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
