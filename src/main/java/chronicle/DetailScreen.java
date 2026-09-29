/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import chronicle.LocalStore.BagItem;
import chronicle.LocalStore.SourceRow;
import chronicle.LocalStore.UntakenRow;
import chronicle.Period.Window;
import com.google.gson.JsonObject;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.swing.JLabel;
import javax.swing.JPanel;
import static chronicle.Json.*;
import static chronicle.Pictures.COPY_MOST;
import static chronicle.Ui.*;

final class DetailScreen extends Screen
{
	void forget()
	{
	}

	JPanel buildLeftBehindDetail(String source, String item)
	{
		JPanel p = column();
		p.add(ui.backRow(null));
		p.add(vgap(4));
		if (source != null)
		{
			UntakenRow left = find(store.untakenSources(), u -> u.name, source, true);
			spaced(p, tallyCard(source.toUpperCase(Locale.ROOT), "Left on the floor",
				count(left == null ? 0 : left.qty, "item"), RED, left == null ? 0 : left.value));
			List<BagItem> bag = store.untakenItemsOf(source);
			if (bag.isEmpty())
			{
				return noted(p, "The count above is older than the itemised record. "
					+ "What this source leaves behind is listed here from now on.");
			}
			p.add(group("Declined"));
			bag.forEach(b -> p.add(link(row(named(b.name, b.qty), gps(b.value), RED), () -> ui.showLeftBehind(null, b.name))));
			return p;
		}
		UntakenRow held = find(store.untakenItems(), u -> u.name, item, true);
		spaced(p, tallyCard(item.toUpperCase(Locale.ROOT), "Left behind",
			"×" + fmt(held == null ? 0 : held.qty), RED, held == null ? 0 : held.value));
		List<UntakenRow> sources = store.untakenSourcesOf(item);
		if (sources.isEmpty())
		{
			return noted(p, "No source itemised for this yet.");
		}
		p.add(group("Left where"));
		sources.forEach(r -> p.add(link(row(r.name, "×" + qtyGp(r.qty, r.value), RED), () -> ui.showLeftBehind(r.name, null))));
		return p;
	}

	JPanel buildItemDetail(String name)
	{
		JPanel p = column();
		long got = 0;
		long worth = 0;
		int itemId = 0;
		List<Tally> from = new ArrayList<>();
		for (SourceRow r : board.sources())
		{
			for (BagItem b : store.sourceItems(r.name))
			{
				if (b.name.equalsIgnoreCase(name))
				{
					got += b.qty;
					worth += b.value;
					itemId = itemId == 0 && b.itemId > 0 ? b.itemId : itemId;
					from.add(new Tally(r.name, b.qty, b.value));
				}
			}
		}
		Tally headRow = period.whole() ? null : Tally.find(board.loot.lootWindow().items, name, false);
		Tally inWindow = period.whole() ? null : headRow != null ? headRow : new Tally(name);
		long other = 0;
		long otherValue = 0;
		if (inWindow != null)
		{
			from.clear();
			other = inWindow.qty;
			otherValue = inWindow.value;
			String spelled = inWindow.name;
			for (Map.Entry<String, List<BagItem>> e : board.loot.periodItems().entrySet())
			{
				for (BagItem b : e.getValue())
				{
					if (b.name.equals(spelled))
					{
						from.add(new Tally(e.getKey(), b.qty, b.value));
						other -= b.qty;
						otherValue -= b.value;
					}
				}
			}
		}
		from.sort((a, b) -> Long.compare(b.qty, a.qty));
		long qty = inWindow != null ? inWindow.qty : got;
		long value = inWindow != null ? inWindow.value : worth;
		spaced(p, ui.backRow(() -> ui.copyPage(() -> buildItemDetail(name))), 4);
		JPanel head = card(name);
		if (itemId > 0)
		{
			JLabel slot = new JLabel();
			slot.setPreferredSize(new Dimension(36, 32));
			slot.setAlignmentX(Component.LEFT_ALIGNMENT);
			plugin.items().getImage(itemId, (int) Math.min(Integer.MAX_VALUE, Math.max(1, qty)), qty > 1).addTo(slot);
			head.add(slot);
		}
		String onTaskAs = keyOf(board.loot.taskItemsEver().keySet(), name);
		String proper = onTaskAs == null ? name : onTaskAs;
		boolean hasTask = board.loot.taskItemsEver().containsKey(proper);
		boolean onTask = hasTask && ui.loot.onTaskOnly;
		if (onTask)
		{
			Interval tw = board.range();
			Tally mine = inWindow == null ? board.loot.taskItemsEver().get(proper)
				: store.slayer.onTaskItems(tw.from, tw.to).getOrDefault(proper, new Tally(proper));
			head.add(row("Obtained on task", "×" + fmt(mine.qty), ACCENT));
			if (inWindow == null || lootSince() == null)
			{
				head.add(row("All sources", "×" + fmt(qty)));
			}
			JPanel priced = worthRow(mine.value);
			priced.setToolTipText("Priced as the drop landed, or in bulk on the day the history was imported");
			head.add(priced);
		}
		else
		{
			head.add(row("Obtained", "×" + fmt(qty), ACCENT));
			if (value > 0)
			{
				head.add(worthRow(value));
			}
		}
		if (inWindow == null && !ui.drawingCopy)
		{
			addDays(head, name, got);
		}
		p.add(head);
		String since = inWindow == null || onTask ? null : lootSince();
		if (inWindow != null && sinceLine(since) != null)
		{
			p.add(vgap(4));
			p.add(note(sinceLine(since)));
		}
		p.add(vgap(6));
		if (hasTask)
		{
			JPanel hold = column();
			hold.add(toggle(ui.loot.onTaskOnly ? "On task" : "All", ui.loot.relens(() -> ui.loot.onTaskOnly = !ui.loot.onTaskOnly)));
			hold.add(vgap(6));
			p.add(hold);
		}
		if (onTask)
		{
			return byTask(p, proper);
		}
		if (from.isEmpty() && other <= 0)
		{
			return inWindow != null ? nothing(p, "dropped", since) : noted(p, "The journal hasn't seen this item drop yet.");
		}
		p.add(group("From"));
		String key = "item:src:" + name;
		ui.capped(p, key, Math.max(ui.drawingCopy ? COPY_MOST : 40, ui.cap(key, 0)), from,
			s -> p.add(link(row(s.name, "×" + fmt(s.qty) + tail(s.value)), () -> ui.openSource(s.name))));
		addOther(p, "×" + fmt(Math.max(0, other)) + tail(Math.max(0, otherValue)), other > 0 || otherValue > 0);
		return p;
	}

	private void addDays(JPanel head, String name, long got)
	{
		LootDays.ItemDays days = store.loot.itemDays(name);
		long count = days.held < got ? 0 : days.days;
		if (count == 1)
		{
			head.add(row("Dropped on", dated(days.first)));
		}
		else if (count > 1)
		{
			head.add(row("First dropped", dated(days.first)));
			head.add(row("Last dropped", dated(days.last)));
			head.add(row("Days it landed", fmt(count)));
		}
	}

	private JPanel byTask(JPanel p, String name)
	{
		Interval w = board.range();
		List<Tally> split = store.slayer.onTaskItemByTask(name, w.from, w.to);
		if (split.isEmpty())
		{
			return noted(p, board.inside("No task paid this"));
		}
		p.add(group("By task"));
		String key = "item:task:" + name;
		ui.capped(p, key, Math.max(ui.drawingCopy ? COPY_MOST : 40, ui.cap(key, 0)), split,
			t -> p.add(row("Task: " + t.name, "×" + fmt(t.qty) + tail(t.value))));
		return p;
	}

	private String lootSince()
	{
		long from = store.loot.lootDetailFrom();
		Window w = board.window();
		if (period.session() || from > 0 && from <= startMs(w.start))
		{
			return null;
		}
		if (from <= 0)
		{
			return Board.UNDATED;
		}
		return !ui.drawingCopy ? "Loot since " + dayOf(from).format(FULL_DAY)
			: "Loot is dated for " + (dayOf(from).isAfter(w.end) ? "none" : "only part") + " of " + board.periodInSentence() + ".";
	}

	private String sinceLine(String since)
	{
		return since != null || period.whole() || !ui.drawingCopy ? since
			: "The figures above are " + board.periodInSentence() + "'s.";
	}

	private JPanel nothing(JPanel p, String what, String since)
	{
		return since != null ? p : noted(p, board.inside("Nothing " + what));
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

	JPanel buildSourceDetail(String name)
	{
		JPanel p = column();
		SourceRow sr = find(board.sources(), r -> r.name, name, false);
		Tally row = period.whole() ? null : Tally.find(board.loot.lootWindow().sources, sr != null ? sr.name : name, sr != null);
		Tally inWindow = period.whole() ? null : row != null ? row : new Tally(name);
		String own = row != null ? row.name : sr != null ? sr.name : name;
		List<BagItem> bag = inWindow == null ? store.sourceItems(own) : new ArrayList<>(board.loot.periodItems().getOrDefault(own, List.of()));
		bag.sort(Comparator.comparingLong((BagItem b) -> b.value).reversed());
		long other = inWindow == null ? 0 : inWindow.value - Tally.of(bag).value;
		boolean unfiled = other > 0 || inWindow != null && !period.session()
			&& store.loot.unfiledSources(board.window().start, board.window().end).contains(own);
		spaced(p, ui.backRow(() -> ui.copyPage(() -> buildSourceDetail(name))), 4);
		JPanel head = card(name);
		if (sr != null)
		{
			sourceHead(head, sr, own, inWindow);
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
		if (bag.isEmpty() && !unfiled)
		{
			return sr == null ? noted(p, "The journal has no drops from this source yet.")
				: inWindow != null ? nothing(p, "from " + name, since)
				: noted(p, "Items fill in as you play. The journal prices each drop the moment it lands.");
		}
		JPanel grid = new JPanel(new GridLayout(0, 5, 3, 3));
		grid.setBackground(DARK);
		bag.stream().filter(b -> b.itemId > 0).limit(10).forEach(b -> grid.add(ui.sprite(b.itemId, b.name, b.qty)));
		if (grid.getComponentCount() > 0)
		{
			spaced(p, grid, 5);
		}
		p.add(group("Loot"));
		int cap = ui.drawingCopy ? COPY_MOST : ui.cap(name, 25);
		ui.loot.addBagRows(p, firstN(bag, cap));
		if (bag.size() > cap)
		{
			p.add(vgap(3));
		}
		ui.addMore(p, name, bag.size(), cap, false);
		addOther(p, gps(Math.max(0, other)), unfiled);
		return p;
	}

	private void sourceHead(JPanel head, SourceRow sr, String own, Tally inWindow)
	{
		boolean killed = board.kills.isKillSource(sr.name);
		long shown = inWindow != null ? inWindow.qty : killed ? board.kills.standingKills(sr) : sr.loots;
		head.add(row(killed ? "Kills" : "Times looted", fmt(shown), ACCENT));
		long worth = inWindow != null ? inWindow.value : sr.value;
		long over = inWindow != null ? inWindow.qty : sr.loots;
		head.add(row("Worth", gps(worth) + (over > 0 ? " · " + perOne(worth, over, killed) : "")));
		if (inWindow == null)
		{
			if (killed)
			{
				addKillSources(head, sr, shown);
			}
			addFloorRow(head, sr.name);
		}
		board.kills.pageLines(sr.name, "pb_lines").forEach(ln -> head.add(row(ln.getKey(), clock(ln.getValue()))));
		board.kills.logLines(sr.name).forEach(ln -> head.add(row(ln.getKey(), fmt(ln.getValue()))));
		if (sr.pb != null)
		{
			JsonObject rec = board.days.records().get(low(sr.name));
			JPanel best = row("Personal best", pb(sr.pb) + (rec != null ? " · set " + day(asLong(rec.get("ts"))) : ""));
			JsonObject data = rec != null ? obj(rec, "data") : new JsonObject();
			if (data.has("was"))
			{
				best.setToolTipText("Was " + pb(data.get("was").getAsDouble()));
			}
			head.add(best);
		}
		LootDays.Timing timed = inWindow == null ? LootDays.Timing.of(sr.timed, sr.timeSum)
			: board.loot.lootWindow().times.getOrDefault(own, new LootDays.Timing());
		if (timed.kills > 0)
		{
			head.add(row("Average kill", pb(timed.seconds / timed.kills) + " · " + fmt(timed.kills) + " timed"));
		}
		long here = board.counts.minutesAt(sr.name, inWindow == null ? board.counts.counters() : board.counts.periodCounters());
		if (here > 0 && board.counts.minutesCoverPeriod())
		{
			boolean rate = killed && shown > 0 && here >= 30;
			head.add(row("Time here", hoursMinutes(here) + (rate ? " · " + rateText(shown * 60.0 / here) + " kills/h" : "")));
		}
		if (sr.firstMs > 0 && !ui.drawingCopy)
		{
			head.add(row("Tracked since", day(sr.firstMs)));
		}
	}

	private void addKillSources(JPanel head, SourceRow sr, long headline)
	{
		String want = KillCounts.chatKind(sr.name);
		Map<String, Long> rows = new LinkedHashMap<>();
		putKind(rows, "Kill Log", KillCounts.killLogCounts(board.clogNow()), want);
		putKind(rows, "Said in chat", store.chatKillCounts(), want);
		putKind(rows, "Collection log", KillCounts.pageKillLines(board.clogNow()), want);
		putKind(rows, "Running count", store.anchoredKills(), want);
		if (sr.loots > 0)
		{
			rows.put("Drops logged", (long) sr.loots);
		}
		Long dropped = board.kills.taskKillsEver().get(sr.name);
		if (dropped != null && dropped > 0)
		{
			rows.put("Dropped on task", dropped);
		}
		if (rows.size() < 2 || !rows.containsValue(headline))
		{
			return;
		}
		String fold = "kcsrc:" + sr.name;
		head.add(ui.quietHead("What says so", "", fold));
		if (ui.foldOpen(fold))
		{
			rows.forEach((label, n) -> head.add(row(label, fmt(n))));
		}
	}

	private static void putKind(Map<String, Long> rows, String label, Map<String, Long> from, String want)
	{
		from.entrySet().stream()
			.filter(e -> KillCounts.chatKind(e.getKey()).equals(want) && e.getValue() > 0)
			.findFirst()
			.ifPresent(e -> rows.put(label, e.getValue()));
	}

	private void addAssignments(JPanel p, String npc)
	{
		Interval w = board.range();
		List<SlayerLog.Assignment> was = store.slayer.onTaskAssignments(npc, w.from, w.to);
		if (was.isEmpty())
		{
			return;
		}
		p.add(group("Killed on task"));
		String key = "ontask:src:" + npc;
		ui.capped(p, key, ui.cap(key, ROW_CAP), was, a -> p.add(row("Task: " + a.task, fmt(a.killsHere))));
		p.add(vgap(6));
	}

	private void addFloorRow(JPanel head, String name)
	{
		for (UntakenRow u : store.untakenSources())
		{
			if (u.qty > 0 && u.name.equalsIgnoreCase(name))
			{
				JPanel r = row("Left behind", qtyGp(u.qty, u.value) + (u.kills > 0 ? " · " + count(u.kills, "kill") : ""));
				r.setToolTipText("Open what was left on the floor");
				head.add(link(r, () -> ui.showLeftBehind(u.name, null)));
				return;
			}
		}
	}
}
