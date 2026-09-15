/*
 * Copyright (c) 2026, Fitzgerald
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES ARE DISCLAIMED.
 */
package chronicle;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * What KIND of thing an item is: a rune, a herb, a seed, a bar.
 *
 * <p>The game does not say. An item has a name, an id and a price, and nothing
 * that groups "Fire rune" with "Blood rune" while keeping "Rune scimitar" out
 * of it. So the grouping is bundled, generated from the item list the cache
 * holds, as five other taxonomies here already are.
 *
 * <p>The rules are ORDERED and the first match wins, which is the whole trick:
 * "Rune arrow" is claimed by Ammunition before the rune rule can see it, and
 * "Rune essence" by Materials. A ledger of eight hundred names has a dozen of
 * these traps in it, and every one of them reads as a rune to anything simpler.
 *
 * <p>Names are matched case-insensitively against the item's DISPLAY name, not
 * its id: ids move between revisions and a noted item has its own, while the
 * name is what the reader typed and what the row shows.
 */
final class ItemKinds
{
	private static final String RESOURCE = "/chronicle/osrs_item_kinds.json";

	/** One rule: a kind, how to match, and what to match against. */
	private static final class Rule
	{
		String kind;
		String match;
		String value;
		Set<String> valueSet;
		Pattern regex;
	}

	private static List<Rule> rules;
	private static List<String> kinds;
	// Answered once per name. A board of three hundred rows asks for each of
	// them on every rebuild, and a miss is the common answer, so most names
	// walk all 65 rules (18 of them regex) before coming back null.
	private static final Map<String, String> answered = new LinkedHashMap<>();

	private ItemKinds()
	{
	}

	/** The kinds, in the order the strip should offer them. */
	static synchronized List<String> kinds()
	{
		load();
		return kinds;
	}

	/**
	 * The kind an item belongs to, or null where none of them claims it.
	 *
	 * <p>Null is a real answer and the commonest one: most of what drops is a
	 * unique, a cosmetic or a quest item, and filing those under a made-up kind
	 * would be worse than leaving them where the reader can still search by name.
	 */
	static synchronized String kindOf(String itemName)
	{
		if (itemName == null)
		{
			return null;
		}
		String name = itemName.trim();
		if (name.isEmpty())
		{
			return null;
		}
		if (answered.containsKey(name))
		{
			return answered.get(name);
		}
		load();
		String low = name.toLowerCase(Locale.ROOT);
		String hit = null;
		for (Rule r : rules)
		{
			boolean match = false;
			switch (r.match == null ? "" : r.match)
			{
				case "in_set":
					match = r.valueSet != null && r.valueSet.contains(low);
					break;
				case "endswith":
					match = low.endsWith(r.value);
					break;
				case "contains":
					match = low.contains(r.value);
					break;
				case "regex":
					match = r.regex != null && r.regex.matcher(name).find();
					break;
				default:
					break;
			}
			if (match)
			{
				hit = r.kind;
				break;
			}
		}
		answered.put(name, hit);
		return hit;
	}

	/** Whether a name is worth offering as a kind: a prefix of one, as typed. */
	static synchronized String named(String query)
	{
		if (query == null || query.trim().isEmpty())
		{
			return null;
		}
		String q = query.trim().toLowerCase(Locale.ROOT);
		for (String kind : kinds())
		{
			if (kind.toLowerCase(Locale.ROOT).startsWith(q))
			{
				return kind;
			}
		}
		return null;
	}

	private static void load()
	{
		if (rules != null)
		{
			return;
		}
		List<Rule> read = new ArrayList<>();
		List<String> order = new ArrayList<>();
		try (InputStream in = ItemKinds.class.getResourceAsStream(RESOURCE))
		{
			if (in != null)
			{
				JsonObject o = new JsonParser()
					.parse(new InputStreamReader(in, StandardCharsets.UTF_8))
					.getAsJsonObject();
				if (o.has("_kinds") && o.get("_kinds").isJsonArray())
				{
					for (JsonElement e : o.getAsJsonArray("_kinds"))
					{
						order.add(e.getAsString());
					}
				}
				if (o.has("rules") && o.get("rules").isJsonArray())
				{
					for (JsonElement e : o.getAsJsonArray("rules"))
					{
						Rule r = rule(e);
						if (r != null)
						{
							read.add(r);
						}
					}
				}
			}
		}
		catch (Exception ignored)   // noqa: a missing taxonomy costs the filter, not the panel
		{
			// read stays as far as it got; every name then answers null
		}
		rules = read;
		kinds = Collections.unmodifiableList(order);
	}

	private static Rule rule(JsonElement el)
	{
		if (!el.isJsonObject())
		{
			return null;
		}
		JsonObject o = el.getAsJsonObject();
		Rule r = new Rule();
		r.kind = o.has("kind") ? o.get("kind").getAsString() : null;
		r.match = o.has("match") ? o.get("match").getAsString() : null;
		if (r.kind == null || r.match == null || !o.has("value"))
		{
			return null;
		}
		JsonElement v = o.get("value");
		if (v.isJsonArray())
		{
			r.valueSet = new HashSet<>();
			for (JsonElement one : (JsonArray) v)
			{
				r.valueSet.add(one.getAsString().toLowerCase(Locale.ROOT));
			}
			return r;
		}
		r.value = v.getAsString().toLowerCase(Locale.ROOT);
		if ("regex".equals(r.match))
		{
			try
			{
				r.regex = Pattern.compile(o.get("value").getAsString());
			}
			catch (PatternSyntaxException e)
			{
				return null;   // a rule that cannot compile never matches
			}
		}
		return r;
	}
}
