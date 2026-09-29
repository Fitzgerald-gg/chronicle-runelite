/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import chronicle.counters.Tables;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import static chronicle.Json.obj;

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
		JsonObject root = Tables.load("osrs_slayer_tasks.json");
		try
		{
			obj(root, "npc_to_task").entrySet().forEach(e ->
				npcToTask.put(Integer.parseInt(e.getKey()), e.getValue().getAsString().toLowerCase(Locale.ROOT)));
			for (Map.Entry<String, JsonElement> e : obj(root, "tasks").entrySet())
			{
				List<String> vs = new ArrayList<>();
				e.getValue().getAsJsonArray().forEach(v -> vs.add(v.getAsString().toLowerCase(Locale.ROOT)));
				variants.put(e.getKey().toLowerCase(Locale.ROOT), vs);
			}
		}
		catch (RuntimeException e)
		{
			log.warn("slayer task table unreadable", e);
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
