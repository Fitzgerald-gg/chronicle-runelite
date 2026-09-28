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
	static final int UNKNOWN_ID = -1;

	private static final String RESOURCE = "/chronicle/osrs_slayer_tasks.json";

	private static volatile SlayerTaskBook instance;

	private final Map<Integer, String> npcToTask;
	private final Map<String, List<String>> variants;

	private SlayerTaskBook(Map<Integer, String> npcToTask, Map<String, List<String>> variants)
	{
		this.npcToTask = npcToTask;
		this.variants = variants;
	}

	static SlayerTaskBook get()
	{
		SlayerTaskBook loaded = instance;
		if (loaded != null)
		{
			return loaded;
		}
		synchronized (SlayerTaskBook.class)
		{
			if (instance == null)
			{
				instance = load();
			}
			return instance;
		}
	}

	private static SlayerTaskBook load()
	{
		Map<Integer, String> ids = new HashMap<>();
		Map<String, List<String>> names = new HashMap<>();
		try (InputStream in = SlayerTaskBook.class.getResourceAsStream(RESOURCE))
		{
			if (in != null)
			{
				JsonElement parsed = new JsonParser().parse(
					new InputStreamReader(in, StandardCharsets.UTF_8));
				JsonObject root = parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
				if (root != null && root.has("npc_to_task") && root.get("npc_to_task").isJsonObject())
				{
					for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("npc_to_task").entrySet())
					{
						try
						{
							ids.put(Integer.parseInt(e.getKey().trim()), e.getValue().getAsString());
						}
						catch (RuntimeException ignored)
						{
						}
					}
				}
				if (root != null && root.has("tasks") && root.get("tasks").isJsonObject())
				{
					for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("tasks").entrySet())
					{
						List<String> vs = new ArrayList<>();
						if (e.getValue().isJsonArray())
						{
							for (JsonElement v : e.getValue().getAsJsonArray())
							{
								try
								{
									String s = v.getAsString().toLowerCase(Locale.ROOT);
									if (!s.isEmpty())
									{
										vs.add(s);
									}
								}
								catch (RuntimeException ignored)
								{
								}
							}
						}
						names.put(e.getKey().toLowerCase(Locale.ROOT), vs);
					}
				}
			}
			else
			{
				log.debug("reference table {} missing", RESOURCE);
			}
		}
		catch (Exception e)
		{
			log.debug("reference table {} unreadable", RESOURCE, e);
		}
		return new SlayerTaskBook(ids, names);
	}

	static boolean onTask(String npcName, int npcId, String task)
	{
		return get().isOnTask(npcName, npcId, task);
	}

	boolean isOnTask(String npcName, int npcId, String task)
	{
		if (task == null || task.trim().isEmpty() || npcName == null || npcName.trim().isEmpty())
		{
			return false;
		}
		String taskKey = task.trim().toLowerCase(Locale.ROOT);
		String mapped = npcToTask.get(npcId);
		if (mapped != null)
		{
			return mapped.toLowerCase(Locale.ROOT).equals(taskKey);
		}
		String name = npcName.trim().toLowerCase(Locale.ROOT);
		if (name.contains(root(taskKey)))
		{
			return true;
		}
		for (String v : variants.getOrDefault(taskKey, Collections.emptyList()))
		{
			if (name.contains(v))
			{
				return true;
			}
		}
		return false;
	}

	static String root(String task)
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

	int idCount()
	{
		return npcToTask.size();
	}

	int taskCount()
	{
		return variants.size();
	}
}
