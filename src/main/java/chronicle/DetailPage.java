/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the
 * BSD 2-Clause License (see LICENSE) are met.
 */
package chronicle;

import chronicle.LocalStore.BagItem;
import chronicle.LocalStore.SlayerJourney;
import chronicle.LocalStore.SlayerTask;
import chronicle.LocalStore.SourceRow;
import chronicle.LocalStore.UntakenRow;
import chronicle.Period.Span;
import chronicle.counters.StatKeys;
import chronicle.panel.StatRegistry;
import com.google.gson.JsonObject;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.Consumer;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import net.runelite.api.Skill;
import net.runelite.client.util.AsyncBufferedImage;
import static chronicle.ChroniclePanel.ACTIVITIES;
import static chronicle.ChroniclePanel.UNDATED;
import static chronicle.ChroniclePanel.perOne;
import static chronicle.Copy.COPY_MOST;
import static chronicle.Json.has;
import static chronicle.Json.keyOf;
import static chronicle.Json.low;
import static chronicle.Json.safeLong;
import static chronicle.Kit.ACCENT_LIFETIME;
import static chronicle.Kit.ACCENT_RED;
import static chronicle.Kit.DARK;
import static chronicle.Kit.ROW_CAP;
import static chronicle.Kit.card;
import static chronicle.Kit.column;
import static chronicle.Kit.day;
import static chronicle.Kit.figure;
import static chronicle.Kit.ghostRow;
import static chronicle.Kit.group;
import static chronicle.Kit.link;
import static chronicle.Kit.linkRow;
import static chronicle.Kit.note;
import static chronicle.Kit.noted;
import static chronicle.Kit.row;
import static chronicle.Kit.spaced;
import static chronicle.Kit.tallyCard;
import static chronicle.Kit.toggle;
import static chronicle.Kit.value;
import static chronicle.Kit.vgap;
import static chronicle.Kit.worthRow;
import static chronicle.Period.FULL_DAY;
import static chronicle.Period.dayOf;
import static chronicle.Period.startMs;
import static chronicle.Text.count;
import static chronicle.Text.dated;
import static chronicle.Text.fmt;
import static chronicle.Text.gp;
import static chronicle.Text.gps;
import static chronicle.Text.hoursMinutes;
import static chronicle.Text.named;
import static chronicle.Text.pb;
import static chronicle.Text.qtyGp;
import static chronicle.Text.rateText;
import static chronicle.Text.tail;

final class DetailPage
{
	private final ChroniclePanel panel;

	DetailPage(ChroniclePanel panel)
	{
		this.panel = panel;
	}

	private void fetchGrinds(Consumer<List<GrindBook.GrindRow>> onDone)
	{
		if (!panel.live.ready())
		{
			onDone.accept(null);
			return;
		}
		final JsonObject clog = panel.store.clogSnapshot();
		final List<SourceRow> sources = panel.store.dropSources();
		panel.executor.submit(() -> onDone.accept(panel.grindBook.grinds(clog, sources)));
	}

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
		link(bestRow, () -> panel.showTask(at));
		head.add(bestRow);
	}

	JPanel buildTaskDetail(int index)
	{
		JPanel p = column();
		p.add(linkRow("< Back", "", () ->
		{
			panel.detailTask = -1;
			panel.rebuild();
		}));
		p.add(vgap(4));
		SlayerJourney j = panel.journeyCache;
		SlayerTask t = j != null && index >= 0 && index < j.tasks.size()
			? j.tasks.get(index) : null;
		if (t == null)
		{
			return noted(p, "That task is no longer in the journal.");
		}
		JPanel head = card(t.task.toUpperCase(Locale.ROOT));
		String kills = t.inProgress && t.assignment > t.kills
			? fmt(t.kills) + " / " + fmt(t.assignment) : fmt(t.kills);
		head.add(row("Kills logged", kills, ACCENT_LIFETIME));
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

		LocalStore.TaskView seg = panel.store.allTasks().at(index);
		List<UntakenRow> monsters = seg.fought();
		if (!monsters.isEmpty())
		{
			p.add(group("Killed"));
			for (UntakenRow m : monsters)
			{
				JPanel r = row(m.name, "×" + fmt(m.qty));
				link(r, () -> panel.openSourceLoose(m.name));
				p.add(r);
			}
			p.add(vgap(6));
		}

		List<BagItem> bag = seg.bag();
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
				link(r, () -> panel.openItem(it.name));
				p.add(r);
			}
		}
		p.add(vgap(8));
		JPanel all = row("All kills of " + t.task, "", ACCENT_LIFETIME, true);
		link(all, () ->
		{
			panel.detailTask = -1;
			panel.openSourceLoose(t.task);
		});
		p.add(all);
		return p;
	}

	JPanel buildLeftBehindDetail()
	{
		JPanel p = column();
		LocalStore.Untaken untaken = panel.store.untaken();
		p.add(linkRow("< Back", "", () -> panel.showLeftBehind(null, null)));
		p.add(vgap(4));

		if (panel.leftBehindSource != null)
		{
			List<BagItem> bag = untaken.itemsOf(panel.leftBehindSource);
			UntakenRow left = Sources.find(untaken.sources, u -> u.name, panel.leftBehindSource, true);
			spaced(p, tallyCard(panel.leftBehindSource.toUpperCase(Locale.ROOT), "Left on the floor",
				count(left == null ? 0 : left.qty, "item"), ACCENT_RED, left == null ? 0 : left.value));
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
				link(r, () -> panel.showLeftBehind(null, b.name));
				p.add(r);
			}
			return p;
		}

		List<UntakenRow> sources = untaken.sourcesOf(panel.leftBehindItem);
		UntakenRow held = Sources.find(untaken.items, u -> u.name, panel.leftBehindItem, true);
		spaced(p, tallyCard(panel.leftBehindItem.toUpperCase(Locale.ROOT), "Left behind",
			"×" + fmt(held == null ? 0 : held.qty), ACCENT_RED, held == null ? 0 : held.value));
		if (sources.isEmpty())
		{
			return noted(p, "No source itemised for this yet.");
		}
		p.add(group("Left where"));
		for (UntakenRow r : sources)
		{
			JPanel row = row(r.name, "×" + qtyGp(r.qty, r.value), ACCENT_RED);
			link(row, () -> panel.showLeftBehind(r.name, null));
			p.add(row);
		}
		return p;
	}

	JPanel buildItemDetail(String name)
	{
		JPanel p = column();
		long got = 0;
		long worth = 0;
		int found = 0;
		final List<Object[]> srcs = new ArrayList<>();
		for (SourceRow r : panel.build.sources())
		{
			for (BagItem b : panel.store.sourceItems(r.name))
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
		final LocalStore.LootWindow win = panel.wholeRecord() ? null : panel.build.lootWindow();
		final LocalStore.Row headRow = win == null ? null : win.item(name);
		final long[] inWindow = win == null ? null
			: headRow == null ? new long[2] : new long[]{headRow.qty, headRow.value};
		long other = 0;
		long otherValue = 0;
		if (inWindow != null)
		{
			srcs.clear();
			other = inWindow[0];
			otherValue = inWindow[1];
			String spelled = headRow != null ? headRow.name : name;
			for (Map.Entry<String, List<BagItem>> e : win.bySource.entrySet())
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
		spaced(p, panel.backRow(() -> panel.copyPage(() -> buildItemDetail(name))), 4);
		JPanel head = card(name);
		if (itemId > 0)
		{
			JLabel slot = new JLabel();
			slot.setPreferredSize(new Dimension(36, 32));
			slot.setAlignmentX(Component.LEFT_ALIGNMENT);
			AsyncBufferedImage img = panel.store.items().getImage(itemId,
				(int) Math.min(Integer.MAX_VALUE, Math.max(1, qty)), qty > 1);
			img.addTo(slot);
			head.add(slot);
		}
		final boolean hasTask = panel.build.taskItems().containsKey(properName(name));
		long[] tw = panel.window().ms();
		long[] mine = inWindow == null ? panel.build.taskItems().get(properName(name))
			: panel.store.tasks(tw[0], tw[1], null, true).items().getOrDefault(properName(name), new long[2]);
		if (hasTask && panel.onTaskOnly)
		{
			head.add(row("Obtained on task", "×" + fmt(mine[0]), ACCENT_LIFETIME));
			if (inWindow == null || lootSince() == null)
			{
				head.add(row("All sources", "×" + fmt(qty)));
			}
			JPanel priced = worthRow(mine[1]);
			priced.setToolTipText("Priced as the drop landed, or in bulk on the day "
				+ "the history was imported");
			head.add(priced);
		}
		else
		{
			head.add(row("Obtained", "×" + fmt(qty), ACCENT_LIFETIME));
			if (value > 0)
			{
				head.add(worthRow(value));
			}
		}
		if (inWindow == null && !panel.drawingCopy)
		{
			long[] days = panel.build.lootDays().itemDays(name);
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
		String since = inWindow == null || hasTask && panel.onTaskOnly ? null : lootSince();
		if (inWindow != null && sinceLine(since) != null)
		{
			p.add(vgap(4));
			p.add(note(sinceLine(since)));
		}
		p.add(vgap(6));
		if (hasTask)
		{
			JPanel hold = column();
			hold.add(toggle(panel.onTaskOnly ? "On task" : "All", panel.relens(() -> panel.onTaskOnly = !panel.onTaskOnly)));
			hold.add(vgap(6));
			p.add(hold);
		}
		if (hasTask && panel.onTaskOnly)
		{
			return byTaskRows(p, properName(name));
		}
		if (srcs.isEmpty() && other <= 0)
		{
			return inWindow != null ? nothing(p, "dropped", since)
				: noted(p, "The journal hasn't seen this item drop yet.");
		}
		p.add(group("From"));
		final int srcCap = Math.max(panel.drawingCopy ? COPY_MOST : 40, panel.drillShown.getOrDefault("item:src:" + name, 0));
		for (Object[] s : Sources.firstN(srcs, srcCap))
		{
			JPanel r = row((String) s[0], "×" + fmt((long) s[1])
				+ tail((long) s[2]));
			final String src = (String) s[0];
			link(r, () -> panel.openSource(src));
			p.add(r);
		}
		panel.drillMore(p, "item:src:" + name, srcs.size(), srcCap);
		addOther(p, "×" + fmt(Math.max(0, other)) + tail(Math.max(0, otherValue)), other > 0 || otherValue > 0);
		return p;
	}

	private String lootSince()
	{
		long from = panel.build.lootDays().detailFrom();
		Period w = panel.window();
		return panel.sessionPeriod() || from > 0 && from <= startMs(w.start) ? null
			: from <= 0 ? UNDATED
			: !panel.drawingCopy ? "Loot since " + dayOf(from).format(FULL_DAY)
			: "Loot is dated for " + (dayOf(from).isAfter(w.end) ? "none" : "only part") + " of "
			+ panel.window().inSentence() + ".";
	}

	private String sinceLine(String since)
	{
		return since != null || panel.wholeRecord() || !panel.drawingCopy ? since
			: "The figures above are " + panel.window().inSentence() + "'s.";
	}

	private JPanel nothing(JPanel p, String what, String since)
	{
		return since != null ? p : noted(p, panel.window().phrase("Nothing " + what));
	}

	private static void addOther(JPanel p, String figure, boolean show)
	{
		if (show)
		{
			JPanel r = ghostRow("Other", figure);
			r.setToolTipText("Dropped on days the record kept whole rather than by source");
			p.add(r);
		}
	}

	private String properName(String typed)
	{
		String key = keyOf(panel.build.taskItems().keySet(), typed);
		return key == null ? typed : key;
	}

	private JPanel byTaskRows(JPanel p, String name)
	{
		long[] w = panel.window().ms();
		List<LocalStore.Row> split = panel.store.tasks(w[0], w[1], null, true).byTask(name);
		if (split.isEmpty())
		{
			return noted(p, panel.window().phrase("No task paid this"));
		}
		p.add(group("By task"));
		final int taskCap = Math.max(panel.drawingCopy ? COPY_MOST : 40, panel.drillShown.getOrDefault("item:task:" + name, 0));
		for (LocalStore.Row t : Sources.firstN(split, taskCap))
		{
			p.add(row("Task: " + t.name, "×" + fmt(t.qty) + tail(t.value)));
		}
		panel.drillMore(p, "item:task:" + name, split.size(), taskCap);
		return p;
	}

	private void addKillSources(JPanel head, SourceRow sr, long headline)
	{
		if (headline < 0)
		{
			return;
		}
		Map<String, Long> rows = panel.build.kills.sayers(sr);
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
		head.add(panel.quietHead("What says so", "", key));
		if (!panel.foldOpen(key))
		{
			return;
		}
		for (Entry<String, Long> e : rows.entrySet())
		{
			head.add(row(e.getKey(), fmt(e.getValue())));
		}
	}

	private void addAssignments(JPanel p, String npc)
	{
		long[] w = panel.window().ms();
		List<LocalStore.Assignment> was = panel.store.tasks(w[0], w[1], null, true).assignments(npc);
		if (was.isEmpty())
		{
			return;
		}
		p.add(group("Killed on task"));
		int cap = panel.drillShown.getOrDefault("ontask:src:" + npc, ROW_CAP);
		for (LocalStore.Assignment a : Sources.firstN(was, cap))
		{
			p.add(row("Task: " + a.task, fmt(a.killsHere)));
		}
		panel.drillMore(p, "ontask:src:" + npc, was.size(), cap);
		p.add(vgap(6));
	}

	private void addFloorRow(JPanel head, String name)
	{
		for (UntakenRow u : panel.store.untaken().sources)
		{
			if (u.qty > 0 && u.name.equalsIgnoreCase(name))
			{
				JPanel r = row("Left behind", qtyGp(u.qty, u.value)
					+ (u.kills > 0 ? " · " + count(u.kills, "kill") : ""));
				r.setToolTipText("Open what was left on the floor");
				link(r, () ->
				{
					panel.detailSource = null;
					panel.leftBehindSource = u.name;
					panel.rebuild();
				});
				head.add(r);
				return;
			}
		}
	}

	JPanel buildSourceDetail(String name)
	{
		JPanel p = column();
		final SourceRow sr = Sources.find(panel.build.sources(), r -> r.name, name, false);
		LocalStore.LootWindow win = panel.wholeRecord() ? null : panel.build.lootWindow();
		LocalStore.Row row = win == null ? null : win.source(sr != null ? sr.name : name, sr != null);
		long[] inWindow = win == null ? null : row == null ? new long[2] : new long[]{row.qty, row.value};
		String own = row != null ? row.name : sr != null ? sr.name : name;
		final List<BagItem> bag = win == null ? panel.store.sourceItems(own)
			: new ArrayList<>(win.bySource.getOrDefault(own, new ArrayList<>()));
		bag.sort(Comparator.comparingLong((BagItem b) -> b.value).reversed());
		final long other = inWindow == null ? 0 : inWindow[1] - LocalStore.tallyOf(bag)[1];
		final boolean unfiled = other > 0 || win != null && win.unfiled.contains(own);
		spaced(p, panel.backRow(() -> panel.copyPage(() -> buildSourceDetail(name))), 4);
		JPanel head = card(name);
		if (sr != null)
		{
			boolean killed = panel.build.kills.isKillSource(sr.name);
			long shown = inWindow != null ? inWindow[0]
				: killed ? panel.build.kills.standingKills(sr) : sr.loots;
			head.add(row(killed ? "Kills" : "Times looted", fmt(shown), ACCENT_LIFETIME));
			long worth = inWindow != null ? inWindow[1] : sr.value;
			long over = inWindow != null ? inWindow[0] : sr.loots;
			head.add(row("Worth", gps(worth)
				+ (over > 0 ? " · " + perOne(worth, over, killed) : "")));
			if (inWindow == null)
			{
				addKillSources(head, sr, killed ? shown : -1);
				addFloorRow(head, sr.name);
			}
			for (Entry<String, Long> pbLine : panel.build.kills.pageLines(sr.name, "pb_lines"))
			{
				head.add(row(pbLine.getKey(), pb(pbLine.getValue())));
			}
			for (Entry<String, Long> ln : panel.build.kills.logLines(sr.name))
			{
				head.add(row(ln.getKey(), fmt(ln.getValue())));
			}
			if (sr.pb != null)
			{
				JsonObject rec = panel.build.feed.records().get(low(sr.name));
				JsonObject recData = rec != null ? rec.getAsJsonObject("data") : null;
				JPanel best = row("Personal best", pb(sr.pb) + (rec != null
					? " · set " + day(safeLong(rec.get("ts"))) : ""));
				if (recData != null && recData.has("was"))
				{
					best.setToolTipText("Was " + pb(recData.get("was").getAsDouble()));
				}
				head.add(best);
			}
			double[] timed = win == null ? new double[]{sr.timed, sr.timeSum}
				: row == null ? new double[2] : new double[]{row.timed, row.timeSum};
			if (timed[0] > 0)
			{
				head.add(row("Average kill", pb(timed[1] / timed[0]) + " · "
					+ fmt((long) timed[0]) + " timed"));
			}
			long here = panel.build.kills.minutesAt(sr.name, inWindow == null ? panel.build.lifetime() : panel.build.periodCounters());
			if (here > 0 && panel.build.kills.minutesCoverPeriod())
			{
				boolean rate = killed && shown > 0 && here >= 30;
				head.add(row("Time here", hoursMinutes(here)
					+ (rate ? " · " + rateText(shown * 60.0 / here) + " kills/h" : "")));
			}
			if (sr.firstMs > 0 && !panel.drawingCopy)
			{
				head.add(row("Tracked since",
					day(sr.firstMs)));
			}
			if (panel.grindsCache == null && !panel.grindsFetching && !panel.drawingCopy)
			{
				panel.grindsFetching = true;
				fetchGrinds(rows2 -> SwingUtilities.invokeLater(() ->
				{
					panel.grindsFetching = false;
					if (rows2 == null)
					{
						return;
					}
					panel.grindsCache = rows2;
					if (sr.name.equals(panel.detailSource))
					{
						panel.rebuildInPlace();
					}
				}));
			}
			if (panel.grindsCache != null && !panel.drawingCopy)
			{
				for (GrindBook.GrindRow g : panel.grindsCache)
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
				grid.add(panel.sprite(b.itemId, b.name, b.qty));
			}
			if (sprites > 0)
			{
				spaced(p, grid, 5);
			}
			p.add(group("Loot"));
			int cap = panel.drawingCopy ? COPY_MOST : panel.drillShown.getOrDefault(name, 25);
			panel.addBagRows(p, Sources.firstN(bag, cap));
			if (bag.size() > cap)
			{
				p.add(vgap(3));
			}
			panel.drillMore(p, name, bag.size(), cap);
			addOther(p, gps(Math.max(0, other)), unfiled);
			return p;
		}
		return sr == null ? noted(p, "The journal has no drops from this source yet.")
			: inWindow != null ? nothing(p, "from " + name, since)
			: noted(p, "Items fill in as you play. The journal prices each drop the "
			+ "moment it lands.");
	}

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
		for (SourceRow r : panel.build.sources())
		{
			if (ownTile.contains(low(r.name)))
			{
				continue;
			}
			Skill sk = Sources.skillOf(r.name);
			if (sk != null && sk.name().equalsIgnoreCase(craft) && r.value > 0)
			{
				out.add(r);
			}
		}
		out.sort((x, y) -> Long.compare(y.value, x.value));
		return out;
	}

	private Long liveXp(String key)
	{
		long[] cur = panel.live.skillSheet().get(key);
		return cur != null && cur.length > 1 && cur[1] > 0 ? cur[1] : null;
	}

	JPanel buildSkillDetail(String craft)
	{
		JPanel p = panel.backPage();
		panel.consumVals = panel.store.consumableValues();
		String key = low(craft);

		JPanel head = card(craft);
		Span s = panel.build.span();
		Long now = liveXp(key);
		if (now == null)
		{
			now = s == null ? null : s.closing.skills.get(key);
		}
		Long was = panel.sessionPeriod()
			? (now == null ? null : Math.max(0, now - panel.build.summary.sessionXp(key)))
			: (s == null ? null : s.opening.skills.get(key));
		if (now != null && now > 0)
		{
			head.add(row("Level", String.valueOf(PaceBook.levelAt(now)), ACCENT_LIFETIME));
			head.add(row("Experience", gp(now)));
			if (!panel.wholeRecord() && was != null && now > was)
			{
				head.add(row("Gained", "+" + gp(now - was), ACCENT_LIFETIME));
			}
		}
		else
		{
			head.add(row("Level", "-"));
		}
		long minutes = (panel.wholeRecord() ? panel.build.lifetime() : panel.build.periodCounters())
			.getOrDefault(StatKeys.timeKey(craft), 0L);
		if (minutes > 0 && panel.build.kills.minutesCoverPeriod())
		{
			long gained = !panel.wholeRecord() && was != null && now != null && now > was ? now - was : 0;
			boolean rate = gained > 0 && minutes >= 30;
			head.add(row("Time", hoursMinutes(minutes)
				+ (rate ? " · " + gp(Math.round(gained * 60.0 / minutes)) + " xp/h" : "")));
		}
		spaced(p, head);

		Map<String, Long> counters = panel.build.countersForPeriod();
		if (counters == null)
		{
			p.add(panel.noPeriod());
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
			String unkept = panel.build.notCounting(false);
			return noted(p, unkept != null ? unkept : "Nothing is tracked under " + craft
				+ (panel.wholeRecord() ? "." : " in " + panel.window().inSentence() + "."));
		}
		rows.sort(StatRegistry::compareRows);
		panel.addPaceLine(p, craft);
		panel.statRows(p, rows);
		if (!ground.isEmpty())
		{
			p.add(vgap(6));
			p.add(group("WHAT IT HAS EVER PAID"));
			for (SourceRow r : ground)
			{
				JPanel line = row(r.name, gps(r.value), ACCENT_LIFETIME);
				link(line, () -> panel.openSource(r.name));
				p.add(line);
			}
		}
		return p;
	}
}
