/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import chronicle.HistoryLog.Baseline;
import com.google.gson.JsonObject;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import javax.swing.JLabel;
import javax.swing.JPanel;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import static chronicle.Feed.*;
import static chronicle.Json.*;
import static chronicle.Pictures.*;
import static chronicle.Reference.*;
import static chronicle.Ui.*;

final class JournalScreen extends Screen
{
	JournalScreen(ChroniclePanel ui, Board board)
	{
		super(ui, board);
	}

	JPanel buildJournal()
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
				ui.rebuild();
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
		if (all.size() >= 4000 && !period.whole() && board.windowMs()[0] < oldestTs(all, false))
		{
			all = plugin.feedWithSitting(Board.JOURNAL_DEEP);
		}
		all = board.withMilestones(all);
		List<JsonObject> feed = new ArrayList<>();
		for (JsonObject e : all)
		{
			boolean kind = wanted.isEmpty() || wanted.contains(typeOf(e));
			if (kind && board.insideWindow(filedAt(e)))
			{
				feed.add(e);
			}
		}
		feed.sort((a, b) -> dayOf(filedAt(b)).compareTo(dayOf(filedAt(a))));
		if (feed.isEmpty() && !period.whole())
		{
			p.add(note(board.inside("No " + ("All".equals(journalLens) ? "milestones" : low(journalLens)))));
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
				JLabel g = styled(new JLabel(day.toUpperCase(Locale.ROOT)), small(), ACCENT);
				g.setAlignmentX(Component.LEFT_ALIGNMENT);
				g.setBorder(pad(7, 2, 3, 0));
				p.add(g);
				String entry = board.dayEntry(dayOf(ts));
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
				ui.rebuildInPlace();
			}));
		}
		return p;
	}

	void addFrontispiece(JPanel p)
	{
		String rsn = plugin.displayRsn();
		JPanel plate = card(rsn != null && !rsn.isEmpty()
			? "The journal of " + rsn : "The journal");

		long since = plugin.keptSince();
		TreeMap<LocalDate, Baseline> spine = board.historySpine;
		if (since > 0)
		{
			plate.add(row("Kept since",
				day(since), ACCENT));
		}
		if (spine != null && !spine.isEmpty())
		{
			JPanel days = row("Days written", fmt(spine.size()));
			days.setToolTipText("The days, as a calendar");
			link(days, ui::openCalendar);
			plate.add(days);
		}
		Map<String, long[]> sheet = plugin.skillSheet();
		long[] overall = sheet.get("overall");
		int combat = store.combatLevel();
		if (overall != null && overall[0] > 0)
		{
			plate.add(row("Total level", fmt(overall[0])
				+ (combat > 0 ? " · combat " + combat : "")));
		}
		int[] logStanding = board.clogStanding();
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
		List<JsonObject> marks = board.milestones();
		if (!marks.isEmpty())
		{
			JsonObject last = marks.get(0);
			plate.add(row("Last milestone", str(last.getAsJsonObject("data"), "text", "")
				+ " · " + day(asLong(last.get("ts")))));
		}
		plate.add(moreRow("what the journal holds", ui::openInfo));
		p.add(plate);

		String note = frontispieceNote();
		if (note != null)
		{
			p.add(ghostRow(note, ""));
		}
		p.add(vgap(6));
	}

	String frontispieceNote()
	{
		if (ui.detail.grinds() != null)
		{
			for (GrindBook.GrindRow g : ui.detail.grinds())
			{
				if (g.percentileDry >= 90)
				{
					return "still owed a " + low(g.item)
						+ " at " + fmt(g.kc) + " " + low(g.boss);
				}
			}
		}
		List<JsonObject> recent = store.feedNewest(2);
		if (recent.size() == 2)
		{
			long days = (asLong(recent.get(0).get("ts")) - asLong(recent.get(1).get("ts"))) / 86_400_000L;
			if (days >= 30)
			{
				return "resumed after " + days + " days away";
			}
		}
		return null;
	}

	static final String[][] JOURNAL_LENSES = {
		{"All"},
		{"Log", "COLLECTION"},
		{"Slayer", "SLAYER"},
		{"Feats", "COMBAT_ACHIEVEMENT", "QUEST", "DIARY", "CLUE", "PET", "LEVEL", "RECORD",
			"MILESTONE"},
		{"Deaths", "DEATH"},
		{"Sessions", "SESSION"},
	};

	String journalLens = "All";

	int journalShown = 60;

	JPanel buildCalendar()
	{
		JPanel p = ui.backPage();
		JPanel head = stepStrip();
		JLabel title = styled(new JLabel(MONTH_YEAR.format(calendarMonth.atDay(1)
			.atStartOfDay(ZoneId.systemDefault()).toInstant()).toUpperCase(Locale.ROOT), JLabel.CENTER),
			FontManager.getRunescapeFont(), ACCENT);
		arrows(head, () ->
		{
			calendarMonth = calendarMonth.minusMonths(1);
			ui.rebuildInPlace();
		}, calendarMonth.isBefore(YearMonth.now()), () ->
		{
			calendarMonth = calendarMonth.plusMonths(1);
			ui.rebuildInPlace();
		}, title);
		spaced(p, head, 4);

		JPanel grid = new JPanel(new GridLayout(0, 7, 2, 2));
		grid.setBackground(DARK);
		grid.setAlignmentX(Component.LEFT_ALIGNMENT);
		for (String d : new String[]{"M", "T", "W", "T", "F", "S", "S"})
		{
			grid.add(styled(new JLabel(d, JLabel.CENTER), small(), DIM));
		}
		Map<LocalDate, long[]> played = board.daysPlayed();
		TreeMap<LocalDate, Baseline> spine = board.historySpine;
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
				cell.setBackground(wash(ACCENT, weight));
				n.setForeground(Color.WHITE);
				cell.setToolTipText(hoursMinutes(t[0]) + " · " + count(t[1], "sitting"));
			}
			else if (onSpine || (t != null && t[1] > 0))
			{
				written++;
				cell.setBackground(wash(ACCENT, 0.18f));
				n.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
				cell.setToolTipText("Written");
			}
			else
			{
				cell.setBackground(DARKER);
				n.setForeground(future ? DARK.brighter()
					: DIM);
			}
			if (!future && (onSpine || t != null))
			{
				final long ts = noon(day);
				link(cell, () -> ui.openJournalOn(ts));
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
			: count(written, "day") + " written"
			+ (monthMinutes > 0 ? " · " + hoursMinutes(monthMinutes) : ""), ""));
		return p;
	}

	static JPanel blankCell()
	{
		JPanel c = new JPanel();
		c.setBackground(DARK);
		c.setPreferredSize(new Dimension(26, 24));
		return c;
	}

	YearMonth calendarMonth = YearMonth.now();

	JPanel buildRecords()
	{
		JPanel p = ui.backPage();
		JPanel book = card("Records");
		int held = book.getComponentCount();

		long[][] best = {{0, 0}, {0, 0}};
		for (JsonObject e : store.feedNewest(Board.FEED_SCAN_DEEP))
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

		TreeMap<LocalDate, Baseline> spine = board.historySpine;
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
					Object[] xp = board.dayXp(day.getKey());
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
		for (Entry<String, long[]> d : board.dayTotals().entrySet())
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

		long hit = board.counters().getOrDefault("highestHit", 0L);
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

	void recordRow(JPanel book, String left, String figure, long ts, String hover)
	{
		JPanel r = row(left, figure + " · " + dated(ts));
		if (hover != null && !hover.isEmpty())
		{
			r.setToolTipText(hover);
		}
		book.add(r);
	}

	static boolean rank(long[][] top, long... e)
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

	JPanel buildInfo()
	{
		JPanel p = column();
		spaced(p, ui.backRow(() -> ui.copyPage(this::buildInfo)), 4);
		Map<String, Long> f = store.journalFacts();

		JPanel loot = facts(card("Loot"), f, ACCENT, "Sources", "sources",
			"Item rows", "itemRows", "Loot events", "lootEvents");
		loot.add(worthRow(f.getOrDefault("lootWorth", 0L)));
		facts(loot, f, null, "Dated days", "lootDays");
		loot.add(row("Left behind", fmt(f.getOrDefault("untakenItems", 0L)) + " items, "
			+ fmt(f.getOrDefault("untakenSources", 0L)) + " sources"));
		spaced(p, loot);

		spaced(p, facts(card("Slayer"), f, ACCENT, "Assignments", "tasks",
			"Closed", "tasksClosed"));

		JPanel log = card("Collection log");
		long availKnown = f.getOrDefault("clogAvailable", 0L);
		log.add(row("Slots filled", fmt(f.getOrDefault("clogSlots", 0L))
			+ (availKnown > 0 ? " of " + fmt(availKnown) : ""), ACCENT));
		spaced(p, facts(log, f, null, "Items named", "clogItems",
			"Pages with a count", "clogPages", "Kill Log lines", "killLogLines",
			"Labelled kill lines", "pageKillLines"));

		spaced(p, facts(card("Counted"), f, ACCENT, "Trackers", "trackers",
			"Skills", "skills", "Feed entries", "feed", "Chat kill counts", "chatCounts",
			"Anchored counts", "anchors"));

		JPanel file = card("On disk");
		file.add(row("Journal", bytes(f.getOrDefault("journalBytes", 0L)), ACCENT));
		file.add(row("History spine", bytes(f.getOrDefault("spineBytes", 0L))));
		p.add(facts(file, f, null, "Schema", "schema"));
		return p;
	}
}
