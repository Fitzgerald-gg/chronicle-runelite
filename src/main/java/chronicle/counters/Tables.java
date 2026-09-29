/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle.counters;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class Tables
{
	private Tables()
	{
	}

	public static JsonElement read(String name)
	{
		InputStream in = Tables.class.getResourceAsStream("/chronicle/" + name);
		if (in == null)
		{
			log.warn("bundled table {} is missing", name);
			return JsonNull.INSTANCE;
		}
		try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8))
		{
			return new JsonParser().parse(r);
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("bundled table {} is unreadable", name, e);
			return JsonNull.INSTANCE;
		}
	}

	public static JsonObject load(String name)
	{
		JsonElement e = read(name);
		return e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
	}

	public static JsonArray array(String name)
	{
		JsonElement e = read(name);
		return e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
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

	public static Map<String, String> map(JsonObject o, String key)
	{
		Map<String, String> out = new LinkedHashMap<>();
		o.getAsJsonObject(key).entrySet().forEach(e -> out.put(e.getKey(), e.getValue().getAsString()));
		return out;
	}
}
