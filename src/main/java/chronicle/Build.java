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
import chronicle.LocalStore.SourceRow;
import chronicle.Period.Span;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

final class Build
{
	final LocalStore store;
	final Live live;
	final Gson gson;
	private final Supplier<Period> period;
	private final Supplier<TreeMap<LocalDate, Baseline>> spine;
	private final Supplier<LocalStore.SlayerJourney> journey;
	private final Runnable askHistory;
	final Kills kills = new Kills(this);
	final Feed feed = new Feed(this);
	final Standings standings = new Standings(this);
	final Summary summary = new Summary(this);
	private List<SourceRow> sources;
	private JsonObject clog;
	private ClogBook book;
	private Sources names;
	private Span span;
	private boolean spanAsked;
	private Map<String, Long> periodCounters;
	private boolean periodCountersAsked;
	private LocalStore.LootDays lootDays;
	private LocalStore.Tasks allTasks;
	private Map<String, long[]> taskItems;

	Build(LocalStore store, Live live, Gson gson, Supplier<Period> period,
		Supplier<TreeMap<LocalDate, Baseline>> spine, Supplier<LocalStore.SlayerJourney> journey, Runnable askHistory)
	{
		this.store = store;
		this.live = live;
		this.gson = gson;
		this.period = period;
		this.spine = spine;
		this.journey = journey;
		this.askHistory = askHistory;
	}

	LocalStore.SlayerJourney journey()
	{
		return journey.get();
	}

	TreeMap<LocalDate, Baseline> spine()
	{
		return spine.get();
	}

	Period period()
	{
		return period.get();
	}

	List<SourceRow> sources()
	{
		if (sources == null)
		{
			sources = store.dropSources();
		}
		return sources;
	}

	JsonObject clog()
	{
		if (clog == null)
		{
			clog = store.clogSnapshot();
		}
		return clog;
	}

	ClogBook book()
	{
		if (book == null)
		{
			book = new ClogBook(clog(), gson);
		}
		return book;
	}

	Sources names()
	{
		if (names == null)
		{
			names = new Sources(this);
		}
		return names;
	}

	void forgetNames()
	{
		names = null;
	}

	Span span()
	{
		if (!spanAsked)
		{
			spanAsked = true;
			TreeMap<LocalDate, Baseline> known = spine();
			if (known == null)
			{
				askHistory.run();
			}
			else if (!known.isEmpty())
			{
				span = period().span(known);
			}
		}
		return span;
	}

	LocalStore.LootWindow lootWindow()
	{
		Period w = period();
		return store.loot(w.session ? null : w.start, w.end);
	}

	LocalStore.Tasks allTasks()
	{
		if (allTasks == null)
		{
			allTasks = store.allTasks();
		}
		return allTasks;
	}

	Map<String, long[]> taskItems()
	{
		if (taskItems == null)
		{
			taskItems = allTasks().items();
		}
		return taskItems;
	}

	LocalStore.LootDays lootDays()
	{
		if (lootDays == null)
		{
			lootDays = store.lootDays();
		}
		return lootDays;
	}

	Map<String, Long> lifetime()
	{
		return live.ready() ? store.lifetimeOf(live.sessionView()) : store.trackersSnapshot();
	}

	Map<String, Long> withLedgerSpend(Map<String, Long> base)
	{
		Map<String, Long> out = new LinkedHashMap<>(base);
		long food = 0;
		long potions = 0;
		for (Map.Entry<String, Long> e : store.consumableValues().entrySet())
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

	Map<String, Long> countersForPeriod()
	{
		Period w = period();
		if (w.whole)
		{
			return withLedgerSpend(lifetime());
		}
		if (w.session)
		{
			Map<String, Long> out = new LinkedHashMap<>();
			for (Map.Entry<String, Integer> e : live.sessionView().entrySet())
			{
				if (e.getValue() != null && e.getValue() != 0)
				{
					out.put(e.getKey(), e.getValue().longValue());
				}
			}
			return out;
		}
		Span s = span();
		if (s == null)
		{
			return null;
		}
		return Period.peaksNotDeltas(HistoryLog.gained(s.opening.counters,
			s.earliest.counters, w.closingNow(s.closing.counters, lifetime())), s);
	}

	Map<String, Long> periodCounters()
	{
		if (!periodCountersAsked)
		{
			periodCountersAsked = true;
			periodCounters = countersForPeriod();
		}
		return periodCounters == null ? Collections.emptyMap() : periodCounters;
	}

	String notCounting(boolean kills)
	{
		TreeMap<LocalDate, Baseline> known = spine();
		Period w = period();
		if (w.whole || w.session || known == null)
		{
			return null;
		}
		for (Map.Entry<LocalDate, Baseline> e : known.entrySet())
		{
			if (!(kills ? e.getValue().kcs : e.getValue().counters).isEmpty())
			{
				return w.end.isBefore(e.getKey())
					? "The record keeps no " + (kills ? "kill counts" : "counters")
					+ " before " + e.getKey().format(Period.FULL_DAY) + "."
					: null;
			}
		}
		return null;
	}

	Map<String, Long> closingSkills(Map<String, Long> skills, boolean live)
	{
		Map<String, Long> close = new HashMap<>(skills);
		if (live)
		{
			for (Map.Entry<String, long[]> e : this.live.skillSheet().entrySet())
			{
				if (e.getValue() != null && e.getValue().length > 1 && e.getValue()[1] > 0)
				{
					close.merge(e.getKey(), e.getValue()[1], Math::max);
				}
			}
		}
		return close;
	}

	long[] taskTally()
	{
		Period w = period();
		long[] ms = w.ms();
		LocalStore.Tasks tasks = store.tasks(ms[0], ms[1], null, w.whole);
		long[] tally = Arrays.copyOf(tasks.tally(), 4);
		tally[3] = LocalStore.tallyOf(tasks.loot())[1];
		return tally;
	}
}
