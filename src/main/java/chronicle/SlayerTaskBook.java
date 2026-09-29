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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

@Slf4j
final class SlayerTaskBook
{

	private final Map<Integer, String> npcToTask = new HashMap<>();
	private final Map<String, List<String>> variants = new HashMap<>();

	private static final class Holder
	{
		static final SlayerTaskBook BOOK = new SlayerTaskBook();
	}

	static SlayerTaskBook get()
	{
		return Holder.BOOK;
	}

	private SlayerTaskBook()
	{
		try (InputStream in = SlayerTaskBook.class.getResourceAsStream("/chronicle/osrs_slayer_tasks.json"))
		{
			JsonObject root = new JsonParser().parse(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
			for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("npc_to_task").entrySet())
			{
				npcToTask.put(Integer.parseInt(e.getKey()), e.getValue().getAsString().toLowerCase(Locale.ROOT));
			}
			for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("tasks").entrySet())
			{
				List<String> vs = new ArrayList<>();
				for (JsonElement v : e.getValue().getAsJsonArray())
				{
					vs.add(v.getAsString().toLowerCase(Locale.ROOT));
				}
				variants.put(e.getKey().toLowerCase(Locale.ROOT), vs);
			}
		}
		catch (Exception e)
		{
			log.debug("slayer task table unreadable", e);
		}
	}

	static boolean onTask(String npcName, int npcId, String task)
	{
		if (task == null || task.trim().isEmpty() || npcName == null || npcName.trim().isEmpty())
		{
			return false;
		}
		SlayerTaskBook book = get();
		String taskKey = task.trim().toLowerCase(Locale.ROOT);
		String mapped = book.npcToTask.get(npcId);
		if (mapped != null)
		{
			return mapped.equals(taskKey);
		}
		String name = npcName.trim().toLowerCase(Locale.ROOT);
		return name.contains(root(taskKey))
			|| book.variants.getOrDefault(taskKey, Collections.emptyList()).stream().anyMatch(name::contains);
	}

	private static String root(String task)
	{
		String r = task.trim();
		if (r.startsWith("the "))
		{
			r = r.substring(4);
		}
		if (r.length() > 4 && r.endsWith("s"))
		{
			r = r.substring(0, r.length() - 1);
		}
		return r;
	}
}
