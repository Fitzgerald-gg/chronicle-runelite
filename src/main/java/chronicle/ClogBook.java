/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the
 * BSD 2-Clause License (see LICENSE) are met.
 */
package chronicle;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import static chronicle.Json.low;
import static chronicle.Json.obj;
import static chronicle.Json.safeLong;

final class ClogBook
{
	private static Map<String, Map<String, List<String>>> taxonomy;
	private static Set<String> sharedSlotNames;

	final JsonObject clog;
	final Map<String, Map<String, List<String>>> tax;
	final Map<String, Long> all = new LinkedHashMap<>();
	final Map<String, Map<String, Long>> byPage = new LinkedHashMap<>();
	final Map<String, Long> kcs;
	private final Set<String> shared;

	ClogBook(JsonObject clog, Gson gson)
	{
		this.clog = clog;
		tax = taxonomy(gson);
		shared = sharedSlotNames(gson);
		kcs = pageCounts(clog);
		if (clog == null)
		{
			return;
		}
		for (Entry<String, JsonElement> e : obj(clog, "clog_items").entrySet())
		{
			all.merge(low(e.getKey()), safeLong(e.getValue()), Math::max);
		}
		for (Entry<String, JsonElement> pg : obj(clog, "by_cat").entrySet())
		{
			if (!pg.getValue().isJsonObject())
			{
				continue;
			}
			Map<String, Long> items = new LinkedHashMap<>();
			for (Entry<String, JsonElement> it : pg.getValue().getAsJsonObject().entrySet())
			{
				items.merge(low(it.getKey()), safeLong(it.getValue()), Math::max);
			}
			byPage.put(low(pg.getKey()), items);
		}
	}

	boolean[] lit(String page, List<String> slots)
	{
		return lightSlots(slots, byPage.get(low(page)), all, shared);
	}

	static int held(boolean[] lit)
	{
		int got = 0;
		for (boolean b : lit)
		{
			got += b ? 1 : 0;
		}
		return got;
	}

	String tabOf(String page)
	{
		for (Entry<String, Map<String, List<String>>> tab : tax.entrySet())
		{
			if (tab.getValue().containsKey(page))
			{
				return tab.getKey();
			}
		}
		return null;
	}

	List<String> unknownPages()
	{
		Set<String> known = new HashSet<>();
		for (Map<String, List<String>> pages : tax.values())
		{
			for (String page : pages.keySet())
			{
				known.add(low(page));
			}
		}
		List<String> out = new ArrayList<>();
		for (String page : byPage.keySet())
		{
			if (!known.contains(page))
			{
				out.add(page);
			}
		}
		Collections.sort(out);
		return out;
	}

	long[] tabStanding(String tab)
	{
		if (clog == null)
		{
			return null;
		}
		JsonObject counts = obj(clog, "cat_counts");
		String key = low(tab);
		long total = counts.has(key + "_total") ? safeLong(counts.get(key + "_total")) : 0;
		if (total <= 0)
		{
			return null;
		}
		return new long[]{counts.has(key + "_obtained") ? safeLong(counts.get(key + "_obtained")) : 0, total};
	}

	static String prettyPage(String key)
	{
		StringBuilder sb = new StringBuilder(key.length());
		boolean head = true;
		for (int i = 0; i < key.length(); i++)
		{
			char c = key.charAt(i);
			sb.append(head ? Character.toUpperCase(c) : c);
			head = c == ' ' || c == '(';
		}
		return sb.toString();
	}

	static Map<String, Long> pageCounts(JsonObject cl)
	{
		Map<String, Long> out = new LinkedHashMap<>();
		if (cl == null)
		{
			return out;
		}
		Set<String> lined = new HashSet<>();
		for (String page : obj(cl, "kc_lines").keySet())
		{
			lined.add(low(page));
		}
		for (Entry<String, JsonElement> e : obj(cl, "kcs").entrySet())
		{
			String key = low(e.getKey());
			if (!lined.contains(key))
			{
				out.merge(key, safeLong(e.getValue()), Math::max);
			}
		}
		for (Entry<String, Long> e : LocalStore.pageKillLines(cl).entrySet())
		{
			out.put(low(e.getKey()), e.getValue());
		}
		return out;
	}

	static boolean[] lightSlots(List<String> slots, Map<String, Long> pageItems,
		Map<String, Long> owned, Set<String> sharedNames)
	{
		boolean[] lit = new boolean[slots.size()];
		Map<String, Integer> dupes = new LinkedHashMap<>();
		for (String slot : slots)
		{
			dupes.merge(low(slot), 1, Integer::sum);
		}
		Map<String, Integer> seen = new LinkedHashMap<>();
		for (int i = 0; i < slots.size(); i++)
		{
			String key = low(slots.get(i));
			long onPage = pageItems != null ? pageItems.getOrDefault(key, 0L) : 0L;
			boolean globalMaySpeak = pageItems == null || !sharedNames.contains(key);
			long global = globalMaySpeak ? owned.getOrDefault(key, 0L) : 0L;
			long have = Math.max(onPage, global);
			if (dupes.get(key) > 1)
			{
				int idx = seen.merge(key, 1, Integer::sum) - 1;
				lit[i] = idx < have;
			}
			else
			{
				lit[i] = have > 0
					|| (pageItems != null && pageItems.containsKey(key))
					|| (globalMaySpeak && owned.containsKey(key));
			}
		}
		return lit;
	}

	static synchronized Set<String> sharedSlotNames(Gson gson)
	{
		if (sharedSlotNames != null)
		{
			return sharedSlotNames;
		}
		Map<String, Integer> homes = new LinkedHashMap<>();
		for (Map<String, List<String>> pages : taxonomy(gson).values())
		{
			for (List<String> slots : pages.values())
			{
				Set<String> onThisPage = new HashSet<>();
				for (String slot : slots)
				{
					onThisPage.add(low(slot));
				}
				for (String slot : onThisPage)
				{
					homes.merge(slot, 1, Integer::sum);
				}
			}
		}
		Set<String> out = new HashSet<>();
		for (Entry<String, Integer> e : homes.entrySet())
		{
			if (e.getValue() > 1)
			{
				out.add(e.getKey());
			}
		}
		sharedSlotNames = out;
		return out;
	}

	static synchronized Map<String, Map<String, List<String>>> taxonomy(Gson gson)
	{
		if (taxonomy != null)
		{
			return taxonomy;
		}
		Map<String, Map<String, List<String>>> out = new LinkedHashMap<>();
		try (InputStream in = ClogBook.class.getResourceAsStream("clog_taxonomy.json"))
		{
			if (in != null)
			{
				JsonObject root = gson.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8),
					JsonObject.class);
				for (Entry<String, JsonElement> tab : root.entrySet())
				{
					Map<String, List<String>> pages = new LinkedHashMap<>();
					for (Entry<String, JsonElement> pg : tab.getValue().getAsJsonObject().entrySet())
					{
						pages.put(pg.getKey(), Json.strs(pg.getValue()));
					}
					out.put(tab.getKey(), pages);
				}
			}
		}
		catch (Exception e)
		{
		}
		taxonomy = out;
		return out;
	}
}
