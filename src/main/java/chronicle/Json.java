/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the
 * BSD 2-Clause License (see LICENSE) are met.
 */
package chronicle;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class Json
{
	private Json()
	{
	}

	static JsonObject obj(JsonObject o, String key)
	{
		return o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key) : new JsonObject();
	}

	static String str(JsonObject o, String key, String fallback)
	{
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : fallback;
	}

	static boolean has(JsonObject o, String key)
	{
		return o.has(key) && !o.get(key).isJsonNull()
			&& !o.get(key).getAsString().trim().isEmpty();
	}

	static long safeLong(JsonElement e)
	{
		try
		{
			return e != null && !e.isJsonNull() ? e.getAsLong() : 0;
		}
		catch (RuntimeException ex)
		{
			return 0;
		}
	}

	static String low(String s)
	{
		return s.toLowerCase(Locale.ROOT);
	}

	static String keyOf(Set<String> keys, String name)
	{
		if (keys.contains(name))
		{
			return name;
		}
		for (String k : keys)
		{
			if (k.equalsIgnoreCase(name))
			{
				return k;
			}
		}
		return null;
	}

	static JsonElement getIgnoreCase(JsonObject o, String key)
	{
		String k = keyOf(o.keySet(), key);
		return k == null ? null : o.get(k);
	}

	static JsonObject table(String name)
	{
		try (InputStreamReader in = new InputStreamReader(Json.class.getResourceAsStream(
			name), StandardCharsets.UTF_8))
		{
			return new JsonParser().parse(in).getAsJsonObject();
		}
		catch (Exception ex)
		{
			return new JsonObject();
		}
	}

	static List<String> strs(JsonElement a)
	{
		List<String> out = new ArrayList<>();
		if (a != null)
		{
			a.getAsJsonArray().forEach(n -> out.add(n.getAsString()));
		}
		return out;
	}

	static Map<String, String> strMap(JsonObject t, String key)
	{
		Map<String, String> out = new LinkedHashMap<>();
		obj(t, key).entrySet().forEach(e -> out.put(e.getKey(), e.getValue().getAsString()));
		return out;
	}
}
