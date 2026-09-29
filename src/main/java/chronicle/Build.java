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
import com.google.gson.JsonObject;
import java.time.LocalDate;
import java.util.List;
import java.util.TreeMap;
import java.util.function.Supplier;

final class Build
{
	final ChroniclePlugin plugin;
	private final Supplier<Period> period;
	private final Supplier<TreeMap<LocalDate, Baseline>> spine;
	private final Runnable askHistory;
	final Kills kills = new Kills(this);
	final Feed feed = new Feed(this);
	final Standings standings = new Standings(this);
	private List<SourceRow> sources;
	private JsonObject clog;
	private ClogBook book;
	private Sources names;
	private Span span;
	private boolean spanAsked;

	Build(ChroniclePlugin plugin, Supplier<Period> period,
		Supplier<TreeMap<LocalDate, Baseline>> spine, Runnable askHistory)
	{
		this.plugin = plugin;
		this.period = period;
		this.spine = spine;
		this.askHistory = askHistory;
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
			sources = plugin.dropSources();
		}
		return sources;
	}

	JsonObject clog()
	{
		if (clog == null)
		{
			clog = plugin.clogSnapshot();
		}
		return clog;
	}

	ClogBook book()
	{
		if (book == null)
		{
			book = new ClogBook(clog(), plugin.gson());
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
		return w.session ? plugin.sessionLootWindow() : plugin.lootBetween(w.start, w.end);
	}
}
