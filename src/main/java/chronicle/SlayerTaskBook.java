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

/**
 * Whether a killed NPC counts toward a slayer task. A port of the server's
 * {@code _monster_on_task}, backed by the same two tables
 * ({@code chronicle/osrs_slayer_tasks.json}):
 *
 * <ol>
 * <li>NPC id first, and authoritative. An id the table knows is on-task only when it
 *     maps to the live task; there is no name fallback for a known id. This is how
 *     Prifddinas guards count toward Elves while a Varrock guard does not.</li>
 * <li>Name fallback when the id is unknown (or missing): on-task when the task's
 *     root is a substring of the NPC name, or any of the task's listed variant names
 *     is. The root is the task lowercased, minus a leading "the ", minus a trailing
 *     "s" when longer than four characters ("Blue dragons" is "blue dragon", "The
 *     Abyssal Sire" is "abyssal sire").</li>
 * </ol>
 *
 * <p>Task lookup is case-insensitive. Loaded on first use; a missing or unreadable
 * table means no id is known and no task lists variants, so only the root rule holds.
 */
@Slf4j
final class SlayerTaskBook
{
	/** The id argument when the killed NPC's id is not known. */
	static final int UNKNOWN_ID = -1;

	private static final String RESOURCE = "/chronicle/osrs_slayer_tasks.json";

	private static volatile SlayerTaskBook instance;

	// npc id -> task name as the table spells it
	private final Map<Integer, String> npcToTask;
	// lowercased task name -> variant names, lowercased
	private final Map<String, List<String>> variants;

	private SlayerTaskBook(Map<Integer, String> npcToTask, Map<String, List<String>> variants)
	{
		this.npcToTask = npcToTask;
		this.variants = variants;
	}

	/** The bundled table, read once. */
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
				// Not the client's Gson: a bundled table read once, from static callers,
				// has no injector to hand, and the Hub forbids a fresh Gson instance.
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
							// a malformed row is skipped, the rest of the table still loads
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
									// a malformed variant is skipped
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

	/**
	 * True when a kill of {@code npcName} (id {@code npcId}, or {@link #UNKNOWN_ID})
	 * counts toward {@code task}. False when either the name or the task is blank.
	 */
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
		// tier 1: a known id settles it, either way
		String mapped = npcToTask.get(npcId);
		if (mapped != null)
		{
			return mapped.toLowerCase(Locale.ROOT).equals(taskKey);
		}
		// tier 2: the name
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

	/** The task name reduced to what its monsters' names share; {@code task} is lowercased. */
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

	/** Ids the table maps; 0 means it did not load. */
	int idCount()
	{
		return npcToTask.size();
	}

	/** Tasks with a variant list; 0 means it did not load. */
	int taskCount()
	{
		return variants.size();
	}
}
