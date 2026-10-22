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
import chronicle.Kit.Line;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import javax.swing.JLabel;
import javax.swing.JPanel;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import static chronicle.Kit.stepStrip;
import static chronicle.Feed.most;
import static chronicle.Kit.ACCENT_LIFETIME;
import static chronicle.Kit.DARK;
import static chronicle.Kit.DARKER;
import static chronicle.Kit.arrows;
import static chronicle.Kit.card;
import static chronicle.Kit.column;
import static chronicle.Kit.day;
import static chronicle.Kit.dim;
import static chronicle.Kit.facts;
import static chronicle.Kit.figure;
import static chronicle.Kit.ghostRow;
import static chronicle.Kit.link;
import static chronicle.Kit.note;
import static chronicle.Kit.row;
import static chronicle.Kit.small;
import static chronicle.Kit.spaced;
import static chronicle.Kit.styled;
import static chronicle.Kit.wash;
import static chronicle.Kit.worthRow;
import static chronicle.Period.MONTH_YEAR;
import static chronicle.Period.noon;
import static chronicle.Text.count;
import static chronicle.Text.dated;
import static chronicle.Text.fmt;
import static chronicle.Text.hoursMinutes;

final class RecordsPage
{
	private final ChroniclePanel panel;

	RecordsPage(ChroniclePanel panel)
	{
		this.panel = panel;
	}

	JPanel buildInfo()
	{
		JPanel p = column();
		spaced(p, panel.backRow(() -> panel.copyPage(this::buildInfo)), 4);
		Map<String, Long> f = panel.store.journalFacts();

		JPanel loot = facts(card("Loot"), f, ACCENT_LIFETIME, "Sources", "sources",
			"Item rows", "itemRows", "Loot events", "lootEvents");
		loot.add(worthRow(f.getOrDefault("lootWorth", 0L)));
		facts(loot, f, null, "Dated days", "lootDays");
		loot.add(row("Left behind", fmt(f.getOrDefault("untakenItems", 0L)) + " items, "
			+ fmt(f.getOrDefault("untakenSources", 0L)) + " sources"));
		spaced(p, loot);

		spaced(p, facts(card("Slayer"), f, ACCENT_LIFETIME, "Assignments", "tasks",
			"Closed", "tasksClosed"));

		JPanel log = card("Collection log");
		long availKnown = f.getOrDefault("clogAvailable", 0L);
		log.add(row("Slots filled", fmt(f.getOrDefault("clogSlots", 0L))
			+ (availKnown > 0 ? " of " + fmt(availKnown) : ""), ACCENT_LIFETIME));
		spaced(p, facts(log, f, null, "Items named", "clogItems",
			"Pages with a count", "clogPages", "Kill Log lines", "killLogLines",
			"Labelled kill lines", "pageKillLines"));

		spaced(p, facts(card("Counted"), f, ACCENT_LIFETIME, "Trackers", "trackers",
			"Skills", "skills", "Feed entries", "feed", "Chat kill counts", "chatCounts",
			"Anchored counts", "anchors"));

		JPanel file = card("On disk");
		file.add(row("Journal", bytes(f.getOrDefault("journalBytes", 0L)), ACCENT_LIFETIME));
		file.add(row("History spine", bytes(f.getOrDefault("spineBytes", 0L))));
		p.add(facts(file, f, null, "Schema", "schema"));
		return p;
	}

	private static String bytes(long n)
	{
		if (n >= 1024 * 1024)
		{
			return String.format("%.1f MB", n / (1024.0 * 1024.0));
		}
		return n >= 1024 ? fmt(n / 1024) + " KB" : fmt(n) + " B";
	}

	JPanel buildRecords()
	{
		JPanel p = panel.backPage();
		JPanel book = card("Records");
		List<Records.Line> lines = Records.of(panel.build);
		for (Records.Line r : lines)
		{
			JPanel line = row(r.left, r.ts != Records.UNDATED ? r.figure + " · " + dated(r.ts) : r.figure);
			if (r.hover != null && !r.hover.isEmpty())
			{
				line.setToolTipText(r.hover);
			}
			book.add(line);
		}
		if (lines.isEmpty())
		{
			book.add(note("No record set yet."));
		}
		p.add(book);
		return p;
	}

	JPanel buildCalendar()
	{
		JPanel p = panel.backPage();
		JPanel head = stepStrip();
		JLabel title = styled(new JLabel(MONTH_YEAR.format(panel.calendarMonth.atDay(1)
			.atStartOfDay(ZoneId.systemDefault()).toInstant()).toUpperCase(Locale.ROOT), JLabel.CENTER),
			FontManager.getRunescapeFont(), ACCENT_LIFETIME);
		arrows(head, () ->
		{
			panel.calendarMonth = panel.calendarMonth.minusMonths(1);
			panel.rebuildInPlace();
		}, panel.calendarMonth.isBefore(YearMonth.now()), () ->
		{
			panel.calendarMonth = panel.calendarMonth.plusMonths(1);
			panel.rebuildInPlace();
		}, title);
		spaced(p, head, 4);

		JPanel grid = new JPanel(new GridLayout(0, 7, 2, 2));
		grid.setBackground(DARK);
		grid.setAlignmentX(Component.LEFT_ALIGNMENT);
		for (String d : new String[]{"M", "T", "W", "T", "F", "S", "S"})
		{
			grid.add(styled(new JLabel(d, JLabel.CENTER), small(), dim()));
		}
		Map<LocalDate, long[]> played = panel.build.feed.days();
		TreeMap<LocalDate, Baseline> spine = panel.historySpine;
		long most = 1;
		for (int d = 1; d <= panel.calendarMonth.lengthOfMonth(); d++)
		{
			long[] t = played.get(panel.calendarMonth.atDay(d));
			most = Math.max(most, t == null ? 0 : t[0]);
		}
		int lead = panel.calendarMonth.atDay(1).getDayOfWeek().getValue() - 1;
		for (int i = 0; i < lead; i++)
		{
			grid.add(blankCell());
		}
		long monthMinutes = 0;
		int written = 0;
		LocalDate today = LocalDate.now();
		for (int d = 1; d <= panel.calendarMonth.lengthOfMonth(); d++)
		{
			LocalDate day = panel.calendarMonth.atDay(d);
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
				cell.setBackground(wash(ACCENT_LIFETIME, weight));
				n.setForeground(Color.WHITE);
				cell.setToolTipText(hoursMinutes(t[0]) + " · " + count(t[1], "sitting"));
			}
			else if (onSpine || (t != null && t[1] > 0))
			{
				written++;
				cell.setBackground(wash(ACCENT_LIFETIME, 0.18f));
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
				link(cell, () -> panel.openJournalOn(ts));
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

	private static JPanel blankCell()
	{
		JPanel c = new JPanel();
		c.setBackground(DARK);
		c.setPreferredSize(new Dimension(26, 24));
		return c;
	}
}
