/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Json
{
	static JsonObject of(Object... pairs)
	{
		JsonObject o = new JsonObject();
		for (int i = 0; i + 1 < pairs.length; i += 2)
		{
			Object v = pairs[i + 1];
			if (v instanceof Number)
			{
				o.addProperty((String) pairs[i], (Number) v);
			}
			else if (v instanceof Boolean)
			{
				o.addProperty((String) pairs[i], (Boolean) v);
			}
			else
			{
				o.addProperty((String) pairs[i], v == null ? null : v.toString());
			}
		}
		return o;
	}

	private Json()
	{
	}

	static long asLong(JsonElement e)
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

	static double asDouble(JsonElement e)
	{
		try
		{
			return e != null && !e.isJsonNull() ? e.getAsDouble() : 0;
		}
		catch (RuntimeException ex)
		{
			return 0;
		}
	}

	static boolean present(JsonObject o, String key)
	{
		return o.has(key) && !o.get(key).isJsonNull();
	}

	static boolean has(JsonObject o, String key)
	{
		return present(o, key) && !o.get(key).getAsString().trim().isEmpty();
	}

	static boolean isObject(JsonObject o, String key)
	{
		return o.has(key) && o.get(key).isJsonObject();
	}

	static String str(JsonObject o, String key, String fallback)
	{
		return present(o, key) ? o.get(key).getAsString() : fallback;
	}

	static JsonObject obj(JsonObject o, String key)
	{
		return o != null && isObject(o, key) ? o.getAsJsonObject(key) : new JsonObject();
	}

	static JsonArray arr(JsonObject o, String key)
	{
		return o != null && o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : new JsonArray();
	}

	static JsonObject sub(JsonObject parent, String key)
	{
		if (!isObject(parent, key))
		{
			parent.add(key, new JsonObject());
		}
		return parent.getAsJsonObject(key);
	}

	static void bump(JsonObject o, String key, long by)
	{
		o.addProperty(key, asLong(o.get(key)) + by);
	}

	static void raise(JsonObject o, String key, long v)
	{
		if (v > asLong(o.get(key)))
		{
			o.addProperty(key, v);
		}
	}

	static List<Map.Entry<String, JsonObject>> objects(JsonObject o)
	{
		List<Map.Entry<String, JsonObject>> out = new ArrayList<>();
		o.entrySet().stream().filter(e -> e.getValue().isJsonObject())
			.forEach(e -> out.add(Map.entry(e.getKey(), e.getValue().getAsJsonObject())));
		return out;
	}

	static List<JsonObject> objects(JsonArray a)
	{
		List<JsonObject> out = new ArrayList<>();
		for (JsonElement e : a)
		{
			if (e.isJsonObject())
			{
				out.add(e.getAsJsonObject());
			}
		}
		return out;
	}

	static void fill(JsonObject o, String key, Map<String, Long> into)
	{
		for (Map.Entry<String, JsonElement> e : obj(o, key).entrySet())
		{
			try
			{
				into.put(e.getKey(), e.getValue().getAsLong());
			}
			catch (RuntimeException ignored)
			{
			}
		}
	}

	static List<String> strs(JsonElement a)
	{
		List<String> out = new ArrayList<>();
		if (a != null && a.isJsonArray())
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
