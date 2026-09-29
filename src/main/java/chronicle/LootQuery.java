/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import chronicle.LocalStore.BagItem;
import chronicle.LocalStore.SourceRow;
import chronicle.LocalStore.UntakenRow;
import chronicle.Period.Window;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import static chronicle.Feed.*;
import static chronicle.Json.*;
import static chronicle.KillCounts.kindOf;
import static chronicle.Reference.*;
import static chronicle.Ui.*;

@RequiredArgsConstructor
final class LootQuery
{
	private final Board board;

	@RequiredArgsConstructor
	static final class Kind
	{
		final String name;
		long qty;
		long value;
		int distinct;

	}

	Map<String, Tally> dayTotals()
	{
		return board.memo("dayTotals", board.store.loot::dayTotals);
	}

	LootDays.LootWindow lootWindow()
	{
		Window w = board.window();
		return board.period.session() ? board.store.loot.sessionLootWindow() : board.store.loot.lootBetween(w.start, w.end);
	}

	Map<String, List<BagItem>> periodItems()
	{
		Window w = board.window();
		return board.period.session() ? board.store.loot.itemsBySource(null, null) : board.store.loot.itemsBySource(w.start, w.end);
	}

	LootDays.Timing timesInWindow(String name)
	{
		Map<String, LootDays.Timing> times = lootWindow().times;
		String key = keyOf(times.keySet(), name);
		return key == null ? new LootDays.Timing() : times.get(key);
	}

	Tally sourceInWindow(String name)
	{
		Tally t = Tally.find(lootWindow().sources, name, false);
		return t != null ? t : new Tally(name);
	}

	Map<String, Tally> taskItemsEver()
	{
		return board.memo("taskItems", () -> board.store.slayer.onTaskItems(Board.EVER_FROM, Board.EVER_TO));
	}

	boolean everOnTask()
	{
		return !taskItemsEver().isEmpty();
	}

	boolean hasKindOnTask(String kind)
	{
		for (String name : taskItemsEver().keySet())
		{
			String k = ItemKinds.kindOf(name);
			if (Board.UNFILED.equals(kind) ? k == null : kind.equals(k))
			{
				return true;
			}
		}
		return false;
	}

	SlayerLog.TaskTally taskTally()
	{
		Interval ms = board.range();
		SlayerLog.TaskTally tally = board.store.slayer.onTaskTally(ms.from, ms.to, null, board.period.whole());
		tally.loot = Tally.of(board.store.slayer.onTaskLoot(ms.from, ms.to, null, board.period.whole())).value;
		return tally;
	}

	long rolled(String name)
	{
		Long n = rolledKills(name);
		return n == null ? 0 : n;
	}

	Long rolledKills(String name)
	{
		if (board.store.loot.lootRollFrom() <= 0)
		{
			return null;
		}
		Map<String, Long> rolled = board.memo("rolled", () ->
		{
			Map<String, Long> out = new LinkedHashMap<>();
			lootWindow().sources.stream().filter(r -> r.qty > 0).forEach(r -> out.put(kindOf(r.name), r.qty));
			return out;
		});
		return rolled.get(kindOf(name));
	}

	LocalDate rollShortOf()
	{
		long from = board.store.loot.lootRollFrom();
		if (from <= 0)
		{
			return null;
		}
		LocalDate began = dayOf(from);
		return began.isAfter(board.window().start) ? began : null;
	}

	LootDays.LootWindow periodLoot()
	{
		if (!board.period.whole())
		{
			return lootWindow();
		}
		LootDays.LootWindow out = new LootDays.LootWindow();
		for (SourceRow r : board.sources())
		{
			out.loots += r.loots;
			out.value += r.value;
		}
		for (UntakenRow u : board.store.untakenSources())
		{
			out.left += u.qty;
			out.leftValue += u.value;
		}
		return out;
	}

	Tally periodDearest()
	{
		List<Tally> items = board.period.whole() ? List.of() : lootWindow().items;
		return items.isEmpty() ? null : items.get(0);
	}

	Map<String, Long> periodWorth(LocalDate from, LocalDate to)
	{
		Map<String, Long> out = new LinkedHashMap<>();
		if (board.period.whole())
		{
			board.sources().forEach(r -> out.merge(r.name, r.value, Long::sum));
		}
		else
		{
			board.store.loot.lootBetween(from, to).sources.forEach(r -> out.merge(r.name, r.value, Long::sum));
		}
		return out;
	}

	static long paidFor(Map<String, Long> worth, Map<String, Long> loose, String name)
	{
		Long exact = worth.get(name);
		return exact != null ? exact : loose.getOrDefault(kindOf(name), 0L);
	}

	static Map<String, Long> loosely(Map<String, Long> worth)
	{
		Map<String, Long> out = new LinkedHashMap<>();
		worth.forEach((k, v) -> out.merge(kindOf(k), v, Long::sum));
		return out;
	}

	static List<Kind> kindsOf(List<BagItem> bag)
	{
		Map<String, Kind> by = new LinkedHashMap<>();
		for (BagItem b : bag)
		{
			String k = ItemKinds.kindOf(b.name);
			Kind row = by.computeIfAbsent(k == null ? Board.UNFILED : k, Kind::new);
			row.qty += b.qty;
			row.value += b.value;
			row.distinct++;
		}
		List<Kind> out = new ArrayList<>(by.values());
		out.sort(Comparator.comparingLong((Kind k) -> k.value).reversed());
		return out;
	}
}
