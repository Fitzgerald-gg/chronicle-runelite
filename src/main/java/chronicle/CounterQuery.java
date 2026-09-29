/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import chronicle.counters.StatKeys;
import chronicle.panel.StatRegistry;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import lombok.RequiredArgsConstructor;
import static chronicle.Feed.*;
import static chronicle.Json.*;
import static chronicle.KillCounts.kindOf;
import static chronicle.Reference.*;
import static chronicle.Ui.*;

@RequiredArgsConstructor
final class CounterQuery
{
	private final Board board;

	Map<String, Long> counters()
	{
		return board.plugin.lifetimeCounters();
	}

	Map<String, Long> countersForPeriod()
	{
		if (board.period.whole())
		{
			return withLedgerSpend(counters());
		}
		if (board.period.session())
		{
			Map<String, Long> out = new LinkedHashMap<>();
			board.plugin.sessionView().forEach((k, v) ->
			{
				if (v != null && v != 0)
				{
					out.put(k, v.longValue());
				}
			});
			return out;
		}
		Board.Span s = board.span();
		return s == null ? null : peaksNotDeltas(HistoryLog.gained(s.opening.counters,
			s.earliest.counters, board.closingNow(s.closing.counters, counters())), s);
	}

	Map<String, Long> periodCounters()
	{
		Map<String, Long> c = board.memo("periodCounters", this::countersForPeriod);
		return c == null ? Collections.emptyMap() : c;
	}

	private static Map<String, Long> peaksNotDeltas(Map<String, Long> moved, Board.Span s)
	{
		for (String key : StatRegistry.peakKeys())
		{
			if (!moved.containsKey(key))
			{
				continue;
			}
			long opened = s.opening.counters.getOrDefault(key, 0L);
			long shut = s.closing.counters.getOrDefault(key, 0L);
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

	Map<String, Long> withLedgerSpend(Map<String, Long> base)
	{
		Map<String, Long> out = new LinkedHashMap<>(base);
		long food = 0;
		long potions = 0;
		for (Entry<String, Long> e : board.store.consumableValues().entrySet())
		{
			long v = e.getValue() == null ? 0 : e.getValue();
			if (v > 0 && e.getKey().endsWith("Eaten"))
			{
				food += v;
			}
			else if (v > 0 && e.getKey().endsWith("Doses"))
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

	boolean minutesCoverPeriod()
	{
		if (board.period.whole())
		{
			return false;
		}
		if (board.period.session())
		{
			return true;
		}
		Board.Span s = board.span();
		return s != null && s.opening.counters.keySet().stream().anyMatch(StatKeys::isTime);
	}

	long minutesAt(String name, Map<String, Long> counters)
	{
		long minutes = counters.getOrDefault(StatKeys.timeKey(name), 0L);
		for (String npc : FOUGHT_AS.getOrDefault(kindOf(name), List.of()))
		{
			minutes += counters.getOrDefault(StatKeys.timeKey(npc), 0L);
		}
		return minutes;
	}
}
