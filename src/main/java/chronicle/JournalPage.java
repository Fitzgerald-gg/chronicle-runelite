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
import com.google.gson.JsonObject;
import java.awt.Component;
import java.awt.GridLayout;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import javax.swing.JLabel;
import javax.swing.JPanel;
import static chronicle.Feed.filedAt;
import static chronicle.Feed.typeOf;
import static chronicle.Json.low;
import static chronicle.Json.safeLong;
import static chronicle.Json.str;
import static chronicle.Kit.ACCENT_LIFETIME;
import static chronicle.Kit.DARK;
import static chronicle.Kit.boardRowRoom;
import static chronicle.Kit.card;
import static chronicle.Kit.column;
import static chronicle.Kit.day;
import static chronicle.Kit.ghostRow;
import static chronicle.Kit.link;
import static chronicle.Kit.moreRow;
import static chronicle.Kit.note;
import static chronicle.Kit.noted;
import static chronicle.Kit.pad;
import static chronicle.Kit.row;
import static chronicle.Kit.small;
import static chronicle.Kit.spaced;
import static chronicle.Kit.styled;
import static chronicle.Kit.vgap;
import static chronicle.Kit.wrapClauses;
import static chronicle.Period.DAY;
import static chronicle.Period.dayOf;
import static chronicle.Text.fmt;

final class JournalPage
{
	private static final String[][] JOURNAL_LENSES = {
		{"All"},
		{"Log", "COLLECTION"},
		{"Slayer", "SLAYER"},
		{"Feats", "COMBAT_ACHIEVEMENT", "QUEST", "DIARY", "CLUE", "PET", "LEVEL", "RECORD",
			"MILESTONE"},
		{"Deaths", "DEATH"},
		{"Sessions", "SESSION"},
	};

	private final ChroniclePanel panel;

	JournalPage(ChroniclePanel panel)
	{
		this.panel = panel;
	}

	JPanel buildJournal()
	{
		JPanel p = column();
		addFrontispiece(p);

		JPanel lenses = new JPanel(new GridLayout(0, 3, 3, 3));
		lenses.setBackground(DARK);
		for (String[] lens : JOURNAL_LENSES)
		{
			lenses.add(panel.pill(lens[0], lens[0].equals(panel.journalLens), 4, null, () ->
			{
				panel.journalLens = lens[0];
				panel.journalShown = 60;
				panel.rebuild();
			}));
		}
		spaced(p, lenses);

		Set<String> wanted = new HashSet<>();
		for (String[] lens : JOURNAL_LENSES)
		{
			if (lens[0].equals(panel.journalLens))
			{
				wanted.addAll(Arrays.asList(lens).subList(1, lens.length));
			}
		}
		List<JsonObject> all = panel.build.feed.withSitting();
		all = panel.build.feed.withMilestones(all);
		List<JsonObject> feed = new ArrayList<>();
		for (JsonObject e : all)
		{
			boolean kind = wanted.isEmpty() || wanted.contains(typeOf(e));
			if (kind && panel.window().inside(filedAt(e)))
			{
				feed.add(e);
			}
		}
		feed.sort((a, b) -> dayOf(filedAt(b)).compareTo(dayOf(filedAt(a))));
		if (feed.isEmpty() && !panel.wholeRecord())
		{
			p.add(note(panel.window().phrase("No " + ("All".equals(panel.journalLens) ? "milestones" : low(panel.journalLens)))));
			return p;
		}
		if (feed.isEmpty())
		{
			return noted(p, "All".equals(panel.journalLens)
				? "Milestones (pets, log slots, tasks, quests, deaths) are noted "
					+ "here as they happen."
				: "Nothing of that kind on the record yet.");
		}

		String lastDay = null;
		for (JsonObject e : Sources.firstN(feed, panel.journalShown))
		{
			long ts = filedAt(e);
			String day = ts > 0 ? DAY.format(Instant.ofEpochMilli(ts)) : "";
			if (!day.equals(lastDay))
			{
				lastDay = day;
				JLabel g = styled(new JLabel(day.toUpperCase(Locale.ROOT)), small(), ACCENT_LIFETIME);
				g.setAlignmentX(Component.LEFT_ALIGNMENT);
				g.setBorder(pad(7, 2, 3, 0));
				p.add(g);
				String entry = panel.build.feed.dayEntry(dayOf(ts));
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
				String[] parts = Feed.sessionParts(e);
				JPanel sr = row(parts[0], parts[1]);
				sr.setToolTipText(parts[2]);
				p.add(sr);
			}
			else
			{
				p.add(row(Feed.line(e), ""));
			}
		}
		if (feed.size() > panel.journalShown)
		{
			p.add(vgap(6));
			p.add(moreRow("Read further back", () ->
			{
				panel.journalShown += 60;
				panel.rebuildInPlace();
			}));
		}
		return p;
	}

	private void addFrontispiece(JPanel p)
	{
		String rsn = panel.live.displayRsn();
		JPanel plate = card(rsn != null && !rsn.isEmpty()
			? "The journal of " + rsn : "The journal");

		long since = panel.store.keptSince();
		TreeMap<LocalDate, Baseline> spine = panel.historySpine;
		if (since > 0)
		{
			plate.add(row("Kept since",
				day(since), ACCENT_LIFETIME));
		}
		if (spine != null && !spine.isEmpty())
		{
			JPanel days = row("Days written", fmt(spine.size()));
			days.setToolTipText("The days, as a calendar");
			link(days, panel::openCalendar);
			plate.add(days);
		}
		Map<String, long[]> sheet = panel.live.skillSheet();
		long[] overall = sheet.get("overall");
		int combat = panel.store.combatLevel();
		if (overall != null && overall[0] > 0)
		{
			plate.add(row("Total level", fmt(overall[0])
				+ (combat > 0 ? " · combat " + combat : "")));
		}
		int[] logStanding = panel.clog.clogStanding();
		int fin = panel.live.clogFinished();
		if (logStanding != null)
		{
			plate.add(row("Collection log",
				fmt(logStanding[0]) + " / " + fmt(logStanding[1])));
		}
		else if (fin > 0)
		{
			plate.add(row("Collection log", fmt(fin) + " obtained"));
		}
		List<JsonObject> marks = panel.build.feed.milestones();
		if (!marks.isEmpty())
		{
			JsonObject last = marks.get(0);
			plate.add(row("Last milestone", str(last.getAsJsonObject("data"), "text", "")
				+ " · " + day(safeLong(last.get("ts")))));
		}
		plate.add(moreRow("what the journal holds", panel::openInfo));
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
		if (panel.grindsCache != null)
		{
			for (GrindBook.GrindRow g : panel.grindsCache)
			{
				if (g.percentileDry >= 90)
				{
					return "still owed a " + low(g.item)
						+ " at " + fmt(g.kc) + " " + low(g.boss);
				}
			}
		}
		List<JsonObject> recent = panel.store.feedNewest(2);
		if (recent.size() == 2)
		{
			long days = (safeLong(recent.get(0).get("ts")) - safeLong(recent.get(1).get("ts"))) / 86_400_000L;
			if (days >= 30)
			{
				return "resumed after " + days + " days away";
			}
		}
		return null;
	}
}
