/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the
 * BSD 2-Clause License (see LICENSE) are met.
 */
package chronicle;

import chronicle.ChroniclePanel.Tab;
import chronicle.ChroniclePanel.View;
import chronicle.Kit.Line;
import chronicle.Period.Span;
import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;
import static chronicle.Copy.toClipboard;
import static chronicle.Feed.feedName;
import static chronicle.Feed.most;
import static chronicle.Feed.typeOf;
import static chronicle.Json.safeLong;
import static chronicle.Kit.NAME_FLOOR;
import static chronicle.Kit.card;
import static chronicle.Kit.chaseRoom;
import static chronicle.Kit.column;
import static chronicle.Kit.copyHeaderLater;
import static chronicle.Kit.day;
import static chronicle.Kit.facts;
import static chronicle.Kit.fitLine;
import static chronicle.Kit.link;
import static chronicle.Kit.note;
import static chronicle.Kit.reportCopy;
import static chronicle.Kit.row;
import static chronicle.Kit.rowMetrics;
import static chronicle.Kit.value;
import static chronicle.Period.TASK_DAY;
import static chronicle.Period.noon;
import static chronicle.Text.count;
import static chronicle.Text.fmt;
import static chronicle.Text.gp;
import static chronicle.Text.gps;
import static chronicle.Text.hoursMinutes;
import static chronicle.Text.named;
import static chronicle.Text.qtyGp;
import static chronicle.Text.tail;

final class RecapPage
{
	private final ChroniclePanel panel;

	RecapPage(ChroniclePanel panel)
	{
		this.panel = panel;
	}

	JPanel buildRecap()
	{
		JPanel p = column();
		p.add(copyHeaderLater("Recap", take ->
		{
			take.setText("copying");
			copyRecapPicture(take);
		}));
		p.add(recapCard());
		return p;
	}

	private void copyRecapPicture(JLabel take)
	{
		RecapPicture.Facts facts;
		try
		{
			facts = panel.build.summary.facts();
		}
		catch (Throwable t)
		{
			reportCopy(take, false);
			return;
		}
		Set<Integer> want = new LinkedHashSet<>();
		for (RecapPicture.BossLine b : facts.bosses)
		{
			if (b.sprite > 0 && panel.art.sprite(b.sprite) == null)
			{
				want.add(b.sprite);
			}
		}
		for (int id : want)
		{
			panel.art.loadSprite(new JLabel(), id, 22, 22);
		}
		long deadline = System.currentTimeMillis() + 1500;
		Timer wait = new Timer(100, null);
		wait.addActionListener(e ->
		{
			boolean all = true;
			for (int id : want)
			{
				all &= panel.art.sprite(id) != null;
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

	BufferedImage recapImage(RecapPicture.Facts facts)
	{
		try
		{
			return RecapPicture.paint(facts, panel.art::skillIcon, panel.art::sprite);
		}
		catch (Throwable t)
		{
			return null;
		}
	}

	private JPanel recapCard()
	{
		JPanel recap = card(panel.wholeRecord() ? "The whole record" : panel.window().label);
		int held = recap.getComponentCount();

		long[] sat = panel.build.summary.played();
		if (sat[1] > 0)
		{
			cardRow(recap, "Played", hoursMinutes(sat[0]) + " · " + count(sat[1], "sitting"),
				() -> panel.openJournal("Sessions"));
		}
		Entry<LocalDate, Long> busiest = sat[1] > 1 ? panel.build.summary.busiest() : null;
		if (busiest != null)
		{
			final LocalDate day = busiest.getKey();
			cardRow(recap, "Busiest day", TASK_DAY.format(day.atStartOfDay(
				ZoneId.systemDefault()).toInstant()) + " · " + hoursMinutes(busiest.getValue()),
				() -> panel.openJournalOn(noon(day)));
		}

		long[] xp = panel.build.summary.xp();
		if (xp != null && xp[0] > 0)
		{
			String most = panel.build.summary.xpMost();
			cardRow(recap, panel.wholeRecord() ? "Xp" : "Xp gained",
				(panel.wholeRecord() ? "" : "+") + gp(xp[0]) + " xp"
					+ (most != null ? ", most in " + most : ""),
				() -> panel.applyTab(View.SHEET));
		}
		long[] levels = panel.build.summary.levels();
		if (levels != null && (panel.wholeRecord() || levels[1] > levels[0]))
		{
			cardRow(recap, panel.wholeRecord() ? "Total level" : "Levels",
				panel.wholeRecord() ? fmt(levels[1]) : "+" + fmt(levels[1] - levels[0]) + " · " + fmt(levels[1]) + " now",
				() -> panel.applyTab(View.SHEET));
		}

		long[] loot = panel.build.summary.loot();
		if (loot[0] > 0)
		{
			cardRow(recap, "Drops", qtyGp(loot[0], loot[1]),
				() -> panel.applyTab(View.DROPS));
		}
		LocalStore.Row dearest = panel.build.summary.dearest();
		if (dearest != null)
		{
			final String item = dearest.name;
			Line said = new Line();
			said.name(item);
			said.fixed(" · " + gps(dearest.value));
			cardRow(recap, "Dearest drop", said, () -> panel.openItem(item));
		}
		if (loot[2] > 0)
		{
			cardRow(recap, "Left behind", qtyGp(loot[2], loot[3]), () ->
			{
				panel.dropsLeftBehind = true;
				panel.applyTab(View.DROPS);
			});
		}

		Map<String, Long> counters = panel.wholeRecord() ? panel.build.withLedgerSpend(panel.build.lifetime()) : panel.build.periodCounters();
		Runnable toLiving = () ->
		{
			panel.statsFamily = "Living";
			panel.subByTab.put(Tab.RECORD, "Ledger");
			panel.applyCommon();
		};
		long food = counters.getOrDefault("foodEaten", 0L);
		if (food > 0)
		{
			cardRow(recap, "Food", fmt(food)
				+ splitSpend("foodConsumedValue", counters), toLiving);
		}
		long doses = counters.getOrDefault("potionDoses", 0L);
		if (doses > 0)
		{
			cardRow(recap, "Potions", count(doses, "dose")
				+ splitSpend("potionsConsumedValue", counters), toLiving);
		}

		String[] killed = panel.build.summary.killedMost();
		if (killed != null)
		{
			final String who = killed[0];
			Line said = new Line();
			said.name(who);
			said.fixed(" · " + killed[1]);
			cardRow(recap, "Killed most", said, () -> panel.openSourceLoose(who));
		}
		long[] tally = panel.build.taskTally();
		if (tally[2] > 0)
		{
			cardRow(recap, "Tasks", fmt(tally[2]) + tail(tally[3]), () -> panel.openSlayer("Tasks"));
		}

		Map<String, String> firstNamed = new LinkedHashMap<>();
		for (JsonObject e : panel.build.feed.all())
		{
			String name = feedName(e);
			if (name != null && panel.window().inside(safeLong(e.get("ts"))))
			{
				firstNamed.putIfAbsent(typeOf(e), name);
			}
		}
		feedCardRow(recap, firstNamed, "COLLECTION", "Log slot", "Log slots", "Log");
		feedCardRow(recap, firstNamed, "PET", "Pet", "Pets", "Feats");
		feedCardRow(recap, firstNamed, "QUEST", "Quest", "Quests", "Feats");
		feedCardRow(recap, firstNamed, "DIARY", "Diary", "Diaries", "Feats");
		feedCardRow(recap, firstNamed, "COMBAT_ACHIEVEMENT", "Combat achievement",
			"Combat achievements", "Feats");
		feedCardRow(recap, firstNamed, "DEATH", "Death", "Deaths", "Deaths");

		if (recap.getComponentCount() == held)
		{
			recap.add(note(panel.wholeRecord() ? "Nothing on the record yet."
				: panel.window().phrase("Nothing")));
		}
		return recap;
	}

	private String splitSpend(String key, Map<String, Long> counters)
	{
		Span s = panel.build.span();
		if (!panel.wholeRecord() && !panel.sessionPeriod() && (s == null || !s.opening.counters.containsKey(key)))
		{
			return "";
		}
		long spend = counters.getOrDefault(key, 0L);
		return tail(spend);
	}

	private void cardRow(JPanel recap, String left, String right, Runnable go)
	{
		JPanel r = row(left, right);
		link(r, go);
		recap.add(r);
	}

	private void cardRow(JPanel recap, String left, Line right, Runnable go)
	{
		String whole = right.whole();
		String fitted = fitLine(right, right.names, NAME_FLOOR, rowMetrics(),
			chaseRoom(left, rowMetrics()));
		cardRow(recap, left, fitted != null ? fitted : whole, go);
		if (fitted != null && !fitted.equals(whole))
		{
			((JPanel) recap.getComponent(recap.getComponentCount() - 1))
				.setToolTipText(left + ": " + whole);
		}
	}

	private void feedCardRow(JPanel recap, Map<String, String> named,
		String type, String one, String many, String lens)
	{
		long n = panel.build.feed.inWindow(type);
		if (n == 0)
		{
			return;
		}
		String name = named.get(type);
		Line right = new Line();
		right.fixed(fmt(n));
		if (name != null)
		{
			right.fixed(" · ");
			right.name(name);
		}
		cardRow(recap, n == 1 ? one : many, right, () -> panel.openJournal(lens));
	}
}
