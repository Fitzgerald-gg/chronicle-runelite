/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import chronicle.LocalStore.BagItem;
import chronicle.LocalStore.UntakenRow;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import static chronicle.Json.*;
import static chronicle.Merge.*;

final class SlayerLog
{
	private final LocalStore store;

	SlayerLog(LocalStore store)
	{
		this.store = store;
	}

	private static JsonObject openSegment(JsonArray tasks, String task)
	{
		if (tasks.size() == 0)
		{
			return null;
		}
		JsonObject last = tasks.get(tasks.size() - 1).getAsJsonObject();
		return isOpen(last) && namesTask(last, task) ? last : null;
	}

	private static boolean isOpen(JsonObject seg)
	{
		return present(seg, "open") && seg.get("open").getAsBoolean();
	}

	private static boolean namesTask(JsonObject seg, String task)
	{
		return present(seg, "task")
			&& task.equalsIgnoreCase(seg.get("task").getAsString());
	}

	private static Long optLong(JsonObject o, String key)
	{
		return o.has(key) && o.get(key).isJsonPrimitive()
			? Long.valueOf(asLong(o.get(key))) : null;
	}

	private static JsonObject continuingSegment(JsonArray tasks, String task, Long rem)
	{
		JsonObject seg = openSegment(tasks, task);
		Long lastRem = seg == null ? null : optLong(seg, "last_rem");
		return rem != null && lastRem != null && rem > lastRem ? null : seg;
	}

	private static JsonObject graceSegment(JsonArray tasks, String task, Long rem, boolean live)
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

	private static JsonObject resumeSegment(JsonArray tasks, String task, Long rem)
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

	private static JsonObject newSegment(JsonArray tasks, String task)
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

	private static long loggedKills(JsonObject seg)
	{
		Long logged = optLong(seg, "logged");
		return logged != null ? logged : Math.max(0, asLong(seg.get("kills")) - asLong(seg.get("noLootKills")));
	}

	private static void setNoLootKills(JsonObject seg, long noLoot)
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

	private static final int SLAYER_TASK_CAP = 1000;

	static final long SLAYER_FINAL_KILL_GRACE = 30;

	private JsonObject slayerRoot()
	{
		JsonObject sl = sub(store.root, "slayer");
		if (!sl.has("tasks") || !sl.get("tasks").isJsonArray())
		{
			sl.add("tasks", new JsonArray());
		}
		return sl;
	}

	void slayerLoot(JsonObject data, long value, String monster, List<BagItem> items)
	{
		String task = str(data, "slayerTask", null);
		if (task == null || task.isEmpty())
		{
			return;
		}
		Long rem = optLong(data, "slayerTaskRemaining");
		Long initialStamp = optLong(data, "slayerTaskInitial");
		long initial = initialStamp == null ? 0 : initialStamp;
		boolean live = rem != null || initialStamp != null;
		JsonObject sl = slayerRoot();
		JsonArray tasks = sl.getAsJsonArray("tasks");
		JsonObject seg = continuingSegment(tasks, task, rem);
		boolean fold = false;
		if (seg == null)
		{
			seg = graceSegment(tasks, task, rem, live);
			fold = seg != null;
		}
		if (seg == null)
		{
			seg = resumeSegment(tasks, task, rem);
		}
		if (seg == null)
		{
			seg = newSegment(tasks, task);
			seg.addProperty("open", true);
		}
		if (fold)
		{
			long total = asLong(seg.get("kills"));
			long logged = loggedKills(seg) + 1;
			seg.addProperty("logged", logged);
			setNoLootKills(seg, total - logged);
		}
		else
		{
			seg.addProperty("kills", seg.get("kills").getAsLong() + 1);
			seg.addProperty("ts", LocalStore.nowSec());
			long assignment = Math.max(seg.get("assignment").getAsLong(), initial);
			if (rem != null && rem + 1 > assignment)
			{
				assignment = rem + 1;
			}
			seg.addProperty("assignment", assignment);
			if (rem != null)
			{
				seg.addProperty("last_rem", rem);
				Long minRem = optLong(seg, "min_rem");
				seg.addProperty("min_rem", minRem == null ? rem : Math.min(minRem, rem));
			}
		}
		seg.addProperty("value", seg.get("value").getAsLong() + value);
		if (monster != null && !monster.isEmpty())
		{
			bump(sub(seg, "monsters"), monster, 1);
		}
		LocalStore.fileByName(sub(seg, "items"), items, true);
	}

	void recordSlayerCompletion(JsonObject data)
	{
		String task = str(data, "task", null);
		if (task == null || task.isEmpty())
		{
			return;
		}
		Long exact = present(data, "killCount")
			? data.get("killCount").getAsLong() : null;
		Long streak = present(data, "count")
			? data.get("count").getAsLong() : null;
		synchronized (store.lock)
		{
			JsonObject sl = slayerRoot();
			JsonArray tasks = sl.getAsJsonArray("tasks");
			JsonObject seg = openSegment(tasks, task);
			if (seg == null)
			{
				seg = newSegment(tasks, task);
			}
			long logged = seg.get("kills").getAsLong();
			seg.addProperty("logged", logged);
			if (exact != null && exact > 0)
			{
				seg.addProperty("kills", exact);
				seg.addProperty("assignment", exact);
				setNoLootKills(seg, exact - logged);
			}
			seg.addProperty("open", false);
			seg.addProperty("ts", LocalStore.nowSec());
			long done = asLong(sl.get("completed"));
			sl.addProperty("completed", streak != null && streak > done ? streak : done + 1);
			store.touch();
		}
	}

	List<String> taskNames()
	{
		Set<String> names = new LinkedHashSet<>();
		synchronized (store.lock)
		{
			for (JsonObject t : LocalStore.newestFirst(taskArray()))
			{
				if (!taskName(t).trim().isEmpty())
				{
					names.add(taskName(t).trim());
				}
			}
		}
		return new ArrayList<>(names);
	}

	private static boolean taskInside(JsonObject t, long fromMs, long toMs)
	{
		long ms = (long) (asDouble(t.get("ts")) * 1000);
		return !(ms > 0 && (ms < fromMs || ms > toMs));
	}

	private static String taskName(JsonObject t)
	{
		return str(t, "task", "");
	}

	private List<JsonObject> tasksIn(long fromMs, long toMs, String onlyTask, boolean includeOpen)
	{
		List<JsonObject> out = new ArrayList<>();
		for (JsonObject t : objects(taskArray()))
		{
			if ((includeOpen || !isOpen(t)) && taskInside(t, fromMs, toMs)
				&& (onlyTask == null || onlyTask.equalsIgnoreCase(taskName(t))))
			{
				out.add(t);
			}
		}
		return out;
	}

	private JsonArray taskArray()
	{
		return arr(obj(store.root, "slayer"), "tasks");
	}

	Map<String, long[]> onTaskItems(long fromMs, long toMs)
	{
		Map<String, long[]> out = new LinkedHashMap<>();
		synchronized (store.lock)
		{
			tasksIn(fromMs, toMs, null, true).forEach(t -> LocalStore.sumItems(obj(t, "items"), out, null, false));
		}
		return out;
	}

	Map<String, Long> onTaskKills(long fromMs, long toMs)
	{
		Map<String, Long> out = new LinkedHashMap<>();
		synchronized (store.lock)
		{
			for (JsonObject t : tasksIn(fromMs, toMs, null, true))
			{
				obj(t, "monsters").entrySet().forEach(m -> out.merge(m.getKey(), asLong(m.getValue()), Long::sum));
			}
		}
		return out;
	}

	List<Object[]> onTaskItemByTask(String itemName, long fromMs, long toMs)
	{
		Map<String, long[]> by = new LinkedHashMap<>();
		if (itemName == null)
		{
			return new ArrayList<>();
		}
		synchronized (store.lock)
		{
			for (JsonObject t : tasksIn(fromMs, toMs, null, true))
			{
				for (var it : objects(obj(t, "items")))
				{
					if (it.getKey().equalsIgnoreCase(itemName))
					{
						LootDays.add(by, taskName(t), asLong(it.getValue().get("qty")), asLong(it.getValue().get("value")));
					}
				}
			}
		}
		List<Object[]> out = new ArrayList<>();
		by.forEach((task, t) -> out.add(new Object[]{task, t[0], t[1]}));
		out.sort((a, b) -> Long.compare((Long) b[2], (Long) a[2]));
		return out;
	}

	List<Assignment> onTaskAssignments(String npc, long fromMs, long toMs)
	{
		List<Assignment> out = new ArrayList<>();
		if (npc == null)
		{
			return out;
		}
		synchronized (store.lock)
		{
			List<JsonObject> ts = tasksIn(fromMs, toMs, null, true);
			Collections.reverse(ts);
			for (JsonObject t : ts)
			{
				JsonObject mons = obj(t, "monsters");
				if (mons.keySet().stream().anyMatch(npc::equalsIgnoreCase))
				{
					long here = mons.entrySet().stream().filter(m -> m.getKey().equalsIgnoreCase(npc))
						.mapToLong(m -> asLong(m.getValue())).sum();
					out.add(new Assignment(taskName(t), (long) (asDouble(t.get("ts")) * 1000), here,
						asLong(t.get("kills")), asLong(t.get("value"))));
				}
			}
		}
		return out;
	}

	List<BagItem> onTaskLoot(long fromMs, long toMs, String onlyTask, boolean includeOpen)
	{
		Map<String, long[]> summed = new LinkedHashMap<>();
		Map<String, Integer> ids = new LinkedHashMap<>();
		synchronized (store.lock)
		{
			tasksIn(fromMs, toMs, onlyTask, includeOpen).forEach(t ->
				LocalStore.sumItems(obj(t, "items"), summed, ids, false));
		}
		return LocalStore.bagRows(summed, ids, 0);
	}

	static final Set<String> SUPERIORS = new HashSet<>();

	long[] onTaskTally(long fromMs, long toMs, String onlyTask, boolean includeOpen)
	{
		long kills = 0;
		long superiors = 0;
		long tasks = 0;
		synchronized (store.lock)
		{
			for (JsonObject t : tasksIn(fromMs, toMs, onlyTask, includeOpen))
			{
				tasks++;
				kills += asLong(t.get("kills"));
				for (var m : obj(t, "monsters").entrySet())
				{
					if (SUPERIORS.contains(m.getKey().toLowerCase(Locale.ROOT)))
					{
						superiors += asLong(m.getValue());
					}
				}
			}
		}
		return new long[]{kills, superiors, tasks};
	}

	SlayerJourney slayerJourney()
	{
		synchronized (store.lock)
		{
			if (store.root == null)
			{
				return null;
			}
			JsonObject sl = obj(store.root, "slayer");
			JsonArray tasks = taskArray();
			List<SlayerTask> out =
				new ArrayList<>(tasks.size());
			long totalKills = 0;
			long totalValue = 0;
			for (int i = tasks.size() - 1; i >= 0; i--)
			{
				if (!tasks.get(i).isJsonObject())
				{
					continue;
				}
				JsonObject seg = tasks.get(i).getAsJsonObject();
				long kills = asLong(seg.get("kills"));
				long value = asLong(seg.get("value"));
				totalKills += kills;
				totalValue += value;
				out.add(new SlayerTask(
					seg.has("task") ? seg.get("task").getAsString() : "?",
					kills,
					asLong(seg.get("assignment")),
					asLong(seg.get("noLootKills")),
					asLong(seg.get("ts")),
					value,
					i == tasks.size() - 1 && isOpen(seg)));
			}
			return new SlayerJourney(
				(int) asLong(sl.get("completed")),
				totalKills, totalValue,
				asLong(sl.get("xp_est")),
				out);
		}
	}

	void importSlayer(JsonObject incSl)
	{
		JsonObject sl = slayerRoot();
		JsonArray tasks = sl.getAsJsonArray("tasks");
		boolean fresh = tasks.size() == 0;
		for (JsonElement t : arr(incSl, "tasks"))
		{
			if (!t.isJsonObject())
			{
				continue;
			}
			if (fresh)
			{
				tasks.add(t.getAsJsonObject().deepCopy());
				continue;
			}
			JsonObject seg = nearestSegment(tasks, t.getAsJsonObject());
			if (seg != null)
			{
				mergeSegmentDetail(seg, t.getAsJsonObject(), "monsters");
				mergeSegmentDetail(seg, t.getAsJsonObject(), "items");
			}
		}
		while (fresh && tasks.size() > SLAYER_TASK_CAP)
		{
			tasks.remove(0);
		}
		for (String k : new String[]{"completed", "xp_est"})
		{
			if (incSl.has(k))
			{
				raise(sl, k, asLong(incSl.get(k)));
			}
		}
	}

	List<BagItem> slayerTaskItems(int index)
	{
		synchronized (store.lock)
		{
			return LocalStore.bagOf(obj(segmentAt(index), "items"));
		}
	}

	List<UntakenRow> slayerTaskMonsters(int index)
	{
		List<UntakenRow> out = new ArrayList<>();
		synchronized (store.lock)
		{
			obj(segmentAt(index), "monsters").entrySet().forEach(e ->
				out.add(new UntakenRow(e.getKey(), asLong(e.getValue()), 0)));
		}
		out.sort((a, b) -> Long.compare(b.qty, a.qty));
		return out;
	}

	private JsonObject segmentAt(int index)
	{
		synchronized (store.lock)
		{
			JsonArray tasks = taskArray();
			int at = tasks.size() - 1 - index;
			return at >= 0 && at < tasks.size() && tasks.get(at).isJsonObject()
				? tasks.get(at).getAsJsonObject() : null;
		}
	}

	@AllArgsConstructor(access = AccessLevel.PACKAGE)
	static final class Assignment
	{
		final String task;
		final long ts;
		final long killsHere;
		final long kills;
		final long value;
	}

	@AllArgsConstructor(access = AccessLevel.PACKAGE)
	public static final class SlayerJourney
	{
		public final int completedTasks;
		public final long totalKills;
		public final long totalValueGp;
		public final long totalXpEst;
		public final List<SlayerTask> tasks;
	}

	@AllArgsConstructor(access = AccessLevel.PACKAGE)
	public static final class SlayerTask
	{
		public final String task;
		public final long kills;
		public final long assignment;
		public final long noLootKills;
		public final double ts;
		public final long totalValue;
		public final boolean inProgress;
	}
}
