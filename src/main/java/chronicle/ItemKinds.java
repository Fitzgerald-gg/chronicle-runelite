/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;

final class ItemKinds
{
	private static final List<Rule> RULES = new ArrayList<>();
	private static final List<String> KINDS = new ArrayList<>();
	private static final Map<String, String> answered = new HashMap<>();

	static
	{
		try (InputStream in = ItemKinds.class.getResourceAsStream("/chronicle/osrs_item_kinds.json"))
		{
			JsonObject o = new JsonParser().parse(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
			o.getAsJsonArray("_kinds").forEach(e -> KINDS.add(e.getAsString()));
			for (JsonElement e : o.getAsJsonArray("rules"))
			{
				JsonObject r = e.getAsJsonObject();
				RULES.add(new Rule(r.get("kind").getAsString(), matcher(r.get("match").getAsString(), r.get("value"))));
			}
		}
		catch (Exception ignored)
		{
		}
	}

	@RequiredArgsConstructor
	private static final class Rule
	{
		final String kind;
		final Predicate<String> matches;
	}

	private ItemKinds()
	{
	}

	private static Predicate<String> matcher(String match, JsonElement value)
	{
		switch (match)
		{
			case "in_set":
				Set<String> set = new HashSet<>();
				value.getAsJsonArray().forEach(v -> set.add(v.getAsString().toLowerCase(Locale.ROOT)));
				return name -> set.contains(name.toLowerCase(Locale.ROOT));
			case "endswith":
				String suffix = value.getAsString().toLowerCase(Locale.ROOT);
				return name -> name.toLowerCase(Locale.ROOT).endsWith(suffix);
			case "contains":
				String part = value.getAsString().toLowerCase(Locale.ROOT);
				return name -> name.toLowerCase(Locale.ROOT).contains(part);
			case "regex":
				Pattern regex = Pattern.compile(value.getAsString());
				return name -> regex.matcher(name).find();
			default:
				return name -> false;
		}
	}

	static synchronized String kindOf(String itemName)
	{
		String name = itemName == null ? "" : itemName.trim();
		if (name.isEmpty())
		{
			return null;
		}
		if (!answered.containsKey(name))
		{
			answered.put(name, RULES.stream().filter(r -> r.matches.test(name)).map(r -> r.kind).findFirst().orElse(null));
		}
		return answered.get(name);
	}

	static String named(String query)
	{
		if (query == null || query.trim().isEmpty())
		{
			return null;
		}
		String q = query.trim().toLowerCase(Locale.ROOT);
		return KINDS.stream().filter(k -> k.toLowerCase(Locale.ROOT).startsWith(q)).findFirst().orElse(null);
	}
}
