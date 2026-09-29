/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import static chronicle.Json.*;

final class SlayerLog
{
	private SlayerLog()
	{
	}

	static JsonObject openSegment(JsonArray tasks, String task)
	{
		if (tasks.size() == 0)
		{
			return null;
		}
		JsonObject last = tasks.get(tasks.size() - 1).getAsJsonObject();
		return isOpen(last) && namesTask(last, task) ? last : null;
	}

	static boolean isOpen(JsonObject seg)
	{
		return present(seg, "open") && seg.get("open").getAsBoolean();
	}

	static boolean namesTask(JsonObject seg, String task)
	{
		return present(seg, "task")
			&& task.equalsIgnoreCase(seg.get("task").getAsString());
	}

	static Long optLong(JsonObject o, String key)
	{
		return o.has(key) && o.get(key).isJsonPrimitive()
			? Long.valueOf(asLong(o.get(key))) : null;
	}

	static JsonObject continuingSegment(JsonArray tasks, String task, Long rem)
	{
		JsonObject seg = openSegment(tasks, task);
		Long lastRem = seg == null ? null : optLong(seg, "last_rem");
		return rem != null && lastRem != null && rem > lastRem ? null : seg;
	}

	static JsonObject graceSegment(JsonArray tasks, String task, Long rem, boolean live)
	{
		long floor = System.currentTimeMillis() / 1000L - SLAYER_FINAL_KILL_GRACE;
		for (int i = tasks.size() - 1; i >= 0; i--)
		{
			if (!tasks.get(i).isJsonObject())
			{
				continue;
			}
			JsonObject seg = tasks.get(i).getAsJsonObject();
			if (asLong(seg.get("ts")) < floor)
			{
				return null;
			}
			if (isOpen(seg) || !namesTask(seg, task))
			{
				continue;
			}
			Long lastRem = optLong(seg, "last_rem");
			return live && (rem == null || lastRem == null || rem > lastRem) ? null : seg;
		}
		return null;
	}

	static JsonObject resumeSegment(JsonArray tasks, String task, Long rem)
	{
		for (int i = tasks.size() - 1; i >= 0; i--)
		{
			if (!tasks.get(i).isJsonObject())
			{
				continue;
			}
			JsonObject seg = tasks.get(i).getAsJsonObject();
			if (!namesTask(seg, task))
			{
				continue;
			}
			Long minRem = optLong(seg, "min_rem");
			if (!isOpen(seg) || rem == null || minRem == null || rem > minRem)
			{
				return null;
			}
			tasks.remove(i);
			tasks.add(seg);
			return seg;
		}
		return null;
	}

	static JsonObject newSegment(JsonArray tasks, String task)
	{
		JsonObject seg = new JsonObject();
		seg.addProperty("task", task);
		seg.addProperty("kills", 0);
		seg.addProperty("assignment", 0);
		seg.addProperty("value", 0);
		tasks.add(seg);
		while (tasks.size() > SLAYER_TASK_CAP)
		{
			tasks.remove(0);
		}
		return seg;
	}

	static long loggedKills(JsonObject seg)
	{
		Long logged = optLong(seg, "logged");
		return logged != null ? logged : Math.max(0, asLong(seg.get("kills")) - asLong(seg.get("noLootKills")));
	}

	static void setNoLootKills(JsonObject seg, long noLoot)
	{
		if (noLoot > 0)
		{
			seg.addProperty("noLootKills", noLoot);
		}
		else
		{
			seg.remove("noLootKills");
		}
	}

	static final int SLAYER_TASK_CAP = 1000;

	static final long SLAYER_FINAL_KILL_GRACE = 30;
}
