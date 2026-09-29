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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import lombok.RequiredArgsConstructor;
import net.runelite.http.api.item.ItemPrice;
import static chronicle.Json.low;

@RequiredArgsConstructor
final class ItemNames
{
	private final ChroniclePlugin plugin;
	private final UnaryOperator<String> ledgerName;
	private final Map<String, Integer> signatures = new LinkedHashMap<>();
	private Map<String, Integer> byName;

	void forget()
	{
		signatures.clear();
	}

	int signature(String source)
	{
		Integer known = signatures.get(source);
		if (known != null)
		{
			return known;
		}
		String own = ledgerName.apply(source);
		List<BagItem> bag = own == null ? new ArrayList<>() : new ArrayList<>(plugin.sourceItems(own));
		bag.sort((a, b) -> Long.compare(b.value, a.value));
		int best = 0;
		for (BagItem b : bag)
		{
			best = b.itemId > 0 ? b.itemId : id(b.name);
			if (best > 0)
			{
				break;
			}
		}
		if (best == 0)
		{
			best = paged(source);
		}
		if (best > 0 || index() != null)
		{
			signatures.put(source, best);
		}
		return best;
	}

	private int paged(String page)
	{
		for (Map<String, List<String>> pages : ClogBook.taxonomy(plugin.gson()).values())
		{
			List<String> slots = pages.get(page);
			if (slots == null)
			{
				continue;
			}
			for (String slot : slots)
			{
				int id = id(slot);
				if (id > 0)
				{
					return id;
				}
			}
			return 0;
		}
		return 0;
	}

	int id(String name)
	{
		Map<String, Integer> index = index();
		if (index == null || name == null || name.isEmpty())
		{
			return 0;
		}
		Integer id = index.get(low(name));
		return id == null ? 0 : id;
	}

	private Map<String, Integer> index()
	{
		if (byName != null)
		{
			return byName;
		}
		List<ItemPrice> all = plugin.items().search("");
		if (all == null || all.isEmpty())
		{
			return null;
		}
		Map<String, Integer> out = new HashMap<>();
		for (ItemPrice price : all)
		{
			if (price.getName() != null)
			{
				out.putIfAbsent(low(price.getName()), price.getId());
			}
		}
		byName = out;
		return out;
	}
}
