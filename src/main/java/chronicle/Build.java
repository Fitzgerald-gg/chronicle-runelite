/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the
 * BSD 2-Clause License (see LICENSE) are met.
 */
package chronicle;

import chronicle.LocalStore.SourceRow;
import chronicle.Period.Span;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.function.Supplier;

final class Build
{
	final ChroniclePlugin plugin;
	private final Supplier<Period> period;
	private final Supplier<Span> fold;
	final Kills kills = new Kills(this);
	private List<SourceRow> sources;
	private JsonObject clog;
	private ClogBook book;
	private Sources names;
	private Span span;
	private boolean spanAsked;

	Build(ChroniclePlugin plugin, Supplier<Period> period, Supplier<Span> fold)
	{
		this.plugin = plugin;
		this.period = period;
		this.fold = fold;
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
			span = fold.get();
		}
		return span;
	}

	LocalStore.LootWindow lootWindow()
	{
		Period w = period();
		return w.session ? plugin.sessionLootWindow() : plugin.lootBetween(w.start, w.end);
	}
}
