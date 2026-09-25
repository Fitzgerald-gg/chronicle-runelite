/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the BSD 2-Clause
 * License (see LICENSE) are met.
 */
package chronicle.counters;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Fixed lookup tables bundled under /chronicle/ and read once at class init. Not the
 * client's Gson: static callers have no injector to hand, and the Hub forbids a fresh
 * Gson instance. A missing or unreadable file reads as an empty object.
 */
public final class Tables
{
	private Tables()
	{
	}

	public static JsonObject load(String name)
	{
		try (Reader r = new InputStreamReader(Tables.class.getResourceAsStream("/chronicle/" + name),
			StandardCharsets.UTF_8))
		{
			return new JsonParser().parse(r).getAsJsonObject();
		}
		catch (Exception e)
		{
			return new JsonObject();
		}
	}

	public static String[] strings(JsonElement a)
	{
		JsonArray arr = a.getAsJsonArray();
		String[] out = new String[arr.size()];
		for (int i = 0; i < out.length; i++)
		{
			out[i] = arr.get(i).getAsString();
		}
		return out;
	}

	public static Set<String> set(JsonObject o, String key)
	{
		return new HashSet<>(Arrays.asList(strings(o.get(key))));
	}

	// in file order, which a first-match table relies on
	public static Map<String, String> map(JsonObject o, String key)
	{
		Map<String, String> out = new LinkedHashMap<>();
		for (Map.Entry<String, JsonElement> e : o.getAsJsonObject(key).entrySet())
		{
			out.put(e.getKey(), e.getValue().getAsString());
		}
		return out;
	}
}
