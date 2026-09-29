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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import javax.swing.JLabel;
import javax.swing.JPanel;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import static chronicle.Feed.*;
import static chronicle.Json.*;
import static chronicle.Ui.*;

final class JournalScreen extends Screen
{
	private static final int PAGE = 60;
	private static final int FEED_SCAN = 4000;
	private static final Map<String, List<String>> LENSES = new java.util.LinkedHashMap<>();

	static
	{
		LENSES.put("All", List.of());
		LENSES.put("Log", List.of("COLLECTION"));
		LENSES.put("Slayer", List.of("SLAYER"));
		LENSES.put("Feats", List.of("COMBAT_ACHIEVEMENT", "QUEST", "DIARY", "CLUE", "PET", "LEVEL", "RECORD", "MILESTONE"));
		LENSES.put("Deaths", List.of("DEATH"));
		LENSES.put("Sessions", List.of("SESSION"));
	}

	String journalLens = "All";
	YearMonth calendarMonth = YearMonth.now();
	private int journalShown = PAGE;

	JPanel buildJournal()
	{
		JPanel p = column();
		addFrontispiece(p);
		JPanel lenses = new JPanel(new GridLayout(0, 3, 3, 3));
		lenses.setBackground(DARK);
		for (String lens : LENSES.keySet())
		{
			lenses.add(pill(lens, lens.equals(journalLens), 4, null, () ->
			{
				journalLens = lens;
				journalShown = PAGE;
				ui.rebuild();
			}));
		}
		spaced(p, lenses);
		List<String> wanted = LENSES.getOrDefault(journalLens, List.of());
		List<JsonObject> all = plugin.feedWithSitting(FEED_SCAN);
		if (all.size() >= FEED_SCAN && !period.whole() && board.range().from < oldestTs(all, false))
		{
			all = plugin.feedWithSitting(Board.JOURNAL_DEEP);
		}
		List<JsonObject> feed = new ArrayList<>();
		board.withMilestones(all).stream().filter(e -> (wanted.isEmpty() || wanted.contains(typeOf(e))) && board.insideWindow(filedAt(e)))
			.forEach(feed::add);
		feed.sort((a, b) -> dayOf(filedAt(b)).compareTo(dayOf(filedAt(a))));
		if (feed.isEmpty())
		{
			return noted(p, !period.whole() ? board.inside("No " + ("All".equals(journalLens) ? "milestones" : low(journalLens)))
				: "All".equals(journalLens) ? "Milestones (pets, log slots, tasks, quests, deaths) are noted here as they happen."
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
					wrapClauses(entry, boardRowRoom()).forEach(line -> p.add(ghostRow(line, "")));
				}
			}
			if ("SESSION".equals(typeOf(e)))
			{
				JPanel sr = row(sessionTitle(e), sessionFigures(e));
				sr.setToolTipText(feedLine(e));
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
				journalShown += PAGE;
				ui.rebuildInPlace();
			}));
		}
		return p;
	}

	private void addFrontispiece(JPanel p)
	{
		String rsn = plugin.displayRsn();
		JPanel plate = card(rsn != null && !rsn.isEmpty() ? "The journal of " + rsn : "The journal");
		long since = plugin.keptSince();
		if (since > 0)
		{
			plate.add(row("Kept since", day(since), ACCENT));
		}
		TreeMap<LocalDate, Baseline> spine = board.historySpine;
		if (spine != null && !spine.isEmpty())
		{
			JPanel days = row("Days written", fmt(spine.size()));
			days.setToolTipText("The days, as a calendar");
			plate.add(link(days, ui::openCalendar));
		}
		SkillRow overall = plugin.skillSheet().get("overall");
		int combat = store.combatLevel();
		if (overall != null && overall.level > 0)
		{
			plate.add(row("Total level", fmt(overall.level) + (combat > 0 ? " · combat " + combat : "")));
		}
		Fraction log = board.clogStanding();
		int fin = plugin.clogFinished();
		if (log != null)
		{
			plate.add(row("Collection log", fmt(log.done) + " / " + fmt(log.of)));
		}
		else if (fin > 0)
		{
			plate.add(row("Collection log", fmt(fin) + " obtained"));
		}
		List<JsonObject> marks = board.milestones();
		if (!marks.isEmpty())
		{
			JsonObject last = marks.get(0);
			plate.add(row("Last milestone", str(obj(last, "data"), "text", "") + " · " + day(asLong(last.get("ts")))));
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

	private String frontispieceNote()
	{
		List<JsonObject> recent = store.feedNewest(2);
		long days = recent.size() < 2 ? 0 : (asLong(recent.get(0).get("ts")) - asLong(recent.get(1).get("ts"))) / 86_400_000L;
		return days >= 30 ? "resumed after " + days + " days away" : null;
	}

	JPanel buildCalendar()
	{
		JPanel p = ui.backPage();
		JPanel head = stepStrip();
		JLabel title = styled(new JLabel(MONTH_YEAR.format(calendarMonth.atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant())
			.toUpperCase(Locale.ROOT), JLabel.CENTER), FontManager.getRunescapeFont(), ACCENT);
		arrows(head, () -> stepMonth(-1), calendarMonth.isBefore(YearMonth.now()), () -> stepMonth(1), title);
		spaced(p, head, 4);
		JPanel grid = new JPanel(new GridLayout(0, 7, 2, 2));
		grid.setBackground(DARK);
		grid.setAlignmentX(Component.LEFT_ALIGNMENT);
		for (String d : new String[]{"M", "T", "W", "T", "F", "S", "S"})
		{
			grid.add(styled(new JLabel(d, JLabel.CENTER), small(), DIM));
		}
		Map<LocalDate, Board.DayPlay> played = board.daysPlayed();
		long most = 1;
		for (int d = 1; d <= calendarMonth.lengthOfMonth(); d++)
		{
			Board.DayPlay t = played.get(calendarMonth.atDay(d));
			most = Math.max(most, t == null ? 0 : t.minutes);
		}
		for (int i = 1; i < calendarMonth.atDay(1).getDayOfWeek().getValue(); i++)
		{
			grid.add(blankCell());
		}
		long minutes = 0;
		int written = 0;
		for (int d = 1; d <= calendarMonth.lengthOfMonth(); d++)
		{
			LocalDate day = calendarMonth.atDay(d);
			Board.DayPlay t = played.get(day);
			boolean onSpine = board.historySpine != null && board.historySpine.containsKey(day);
			minutes += t != null ? t.minutes : 0;
			written += t != null && t.minutes > 0 || onSpine || t != null && t.sittings > 0 ? 1 : 0;
			grid.add(dayCell(day, t, onSpine, most));
		}
		while (grid.getComponentCount() % 7 != 0)
		{
			grid.add(blankCell());
		}
		spaced(p, grid, 4);
		p.add(ghostRow(written == 0 ? "nothing written this month"
			: count(written, "day") + " written" + (minutes > 0 ? " · " + hoursMinutes(minutes) : ""), ""));
		return p;
	}

	private void stepMonth(int by)
	{
		calendarMonth = calendarMonth.plusMonths(by);
		ui.rebuildInPlace();
	}

	private JPanel dayCell(LocalDate day, Board.DayPlay t, boolean onSpine, long most)
	{
		boolean future = day.isAfter(LocalDate.now());
		JPanel cell = new JPanel(new BorderLayout());
		cell.setPreferredSize(new Dimension(26, 24));
		JLabel n = new JLabel(String.valueOf(day.getDayOfMonth()), JLabel.CENTER);
		n.setFont(small());
		if (t != null && t.minutes > 0)
		{
			cell.setBackground(wash(ACCENT, 0.25f + 0.75f * Math.min(1f, (float) t.minutes / most)));
			n.setForeground(Color.WHITE);
			cell.setToolTipText(hoursMinutes(t.minutes) + " · " + count(t.sittings, "sitting"));
		}
		else if (onSpine || t != null && t.sittings > 0)
		{
			cell.setBackground(wash(ACCENT, 0.18f));
			n.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			cell.setToolTipText("Written");
		}
		else
		{
			cell.setBackground(DARKER);
			n.setForeground(future ? DARK.brighter() : DIM);
		}
		if (!future && (onSpine || t != null))
		{
			link(cell, () -> ui.openJournalOn(noon(day)));
		}
		cell.add(n, BorderLayout.CENTER);
		return cell;
	}

	private static JPanel blankCell()
	{
		JPanel c = new JPanel();
		c.setBackground(DARK);
		c.setPreferredSize(new Dimension(26, 24));
		return c;
	}

	JPanel buildRecords()
	{
		JPanel p = ui.backPage();
		JPanel book = card("Records");
		int held = book.getComponentCount();
		long[][] sitting = {{0, 0}, {0, 0}};
		for (JsonObject e : store.feedNewest(Board.FEED_SCAN_DEEP))
		{
			if ("SESSION".equals(typeOf(e)))
			{
				rank(sitting, sessionMinutes(e), sittingStart(e));
			}
		}
		if (sitting[0][0] > 0)
		{
			record(book, "Longest sitting", hoursMinutes(sitting[0][0]), sitting[0][1],
				sitting[1][0] > 0 ? "Was " + hoursMinutes(sitting[1][0]) + " · " + dated(sitting[1][1]) : null);
		}
		if (board.historySpine != null && board.historySpine.size() > 1)
		{
			spineRecords(book, board.historySpine);
		}
		long[][] rich = {{0, 0}, {0, 0}};
		long[][] busy = {{0, 0}, {0, 0}};
		for (Entry<String, Tally> d : board.dayTotals().entrySet())
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
			rank(rich, d.getValue().value, ts, d.getValue().qty);
			rank(busy, d.getValue().qty, ts, d.getValue().value);
		}
		if (rich[0][0] > 0)
		{
			record(book, "Richest day", gps(rich[0][0]), rich[0][1], count(rich[0][2], "drop")
				+ (rich[1][0] > 0 ? " · was " + gp(rich[1][0]) + " · " + dated(rich[1][1]) : ""));
		}
		if (busy[0][0] > 0)
		{
			record(book, "Most drops in a day", fmt(busy[0][0]), busy[0][1], gps(busy[0][2])
				+ (busy[1][0] > 0 ? " · was " + fmt(busy[1][0]) + " · " + dated(busy[1][1]) : ""));
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

	private void spineRecords(JPanel book, TreeMap<LocalDate, Baseline> spine)
	{
		long[][] xp = {{0, 0}, {0, 0}};
		long[][] kills = {{0, 0}, {0, 0}};
		String xpSkill = null;
		int run = 0;
		int longest = 0;
		LocalDate runEnd = null;
		Entry<LocalDate, Baseline> before = null;
		for (Entry<LocalDate, Baseline> day : spine.entrySet())
		{
			run = before != null && before.getKey().plusDays(1).equals(day.getKey()) ? run + 1 : 1;
			if (run > longest)
			{
				longest = run;
				runEnd = day.getKey();
			}
			if (before != null)
			{
				long ts = noon(day.getKey());
				Board.XpGain gained = board.dayXp(day.getKey());
				if (rank(xp, gained == null ? 0 : gained.total, ts))
				{
					xpSkill = gained.top;
				}
				long killed = 0;
				for (Entry<String, Long> k : day.getValue().kcs.entrySet())
				{
					Long was = before.getValue().kcs.get(k.getKey());
					killed += was != null && k.getValue() > was ? k.getValue() - was : 0;
				}
				rank(kills, killed, ts);
			}
			before = day;
		}
		if (xp[0][0] > 0)
		{
			record(book, "Biggest day", "+" + gp(xp[0][0]) + " xp", xp[0][1], (xpSkill != null ? "Most in " + xpSkill : "")
				+ (xp[1][0] > 0 ? (xpSkill != null ? " · " : "") + "was +" + gp(xp[1][0]) + " · " + dated(xp[1][1]) : ""));
		}
		if (kills[0][0] > 0)
		{
			record(book, "Most kills in a day", fmt(kills[0][0]), kills[0][1],
				kills[1][0] > 0 ? "Was " + fmt(kills[1][0]) + " · " + dated(kills[1][1]) : null);
		}
		if (longest > 1)
		{
			record(book, "Longest run of days written", fmt(longest) + " days", noon(runEnd), null);
		}
	}

	private static void record(JPanel book, String left, String figure, long ts, String hover)
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

	JPanel buildInfo()
	{
		JPanel p = column();
		spaced(p, ui.backRow(() -> ui.copyPage(this::buildInfo)), 4);
		Map<String, Long> f = store.journalFacts();
		JPanel loot = facts(card("Loot"), f, ACCENT, "Sources", "sources", "Item rows", "itemRows", "Loot events", "lootEvents");
		loot.add(worthRow(f.getOrDefault("lootWorth", 0L)));
		facts(loot, f, null, "Dated days", "lootDays");
		loot.add(row("Left behind", fmt(f.getOrDefault("untakenItems", 0L)) + " items, "
			+ fmt(f.getOrDefault("untakenSources", 0L)) + " sources"));
		spaced(p, loot);
		spaced(p, facts(card("Slayer"), f, ACCENT, "Assignments", "tasks", "Closed", "tasksClosed"));
		JPanel log = card("Collection log");
		long available = f.getOrDefault("clogAvailable", 0L);
		log.add(row("Slots filled", fmt(f.getOrDefault("clogSlots", 0L)) + (available > 0 ? " of " + fmt(available) : ""), ACCENT));
		spaced(p, facts(log, f, null, "Items named", "clogItems", "Pages with a count", "clogPages",
			"Kill Log lines", "killLogLines", "Labelled kill lines", "pageKillLines"));
		spaced(p, facts(card("Counted"), f, ACCENT, "Trackers", "trackers", "Skills", "skills", "Feed entries", "feed",
			"Chat kill counts", "chatCounts", "Anchored counts", "anchors"));
		JPanel file = card("On disk");
		file.add(row("Journal", bytes(f.getOrDefault("journalBytes", 0L)), ACCENT));
		file.add(row("History spine", bytes(f.getOrDefault("spineBytes", 0L))));
		p.add(facts(file, f, null, "Schema", "schema"));
		return p;
	}
}
