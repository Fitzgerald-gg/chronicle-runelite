/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import chronicle.LocalStore.BagItem;
import com.google.gson.JsonObject;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import static chronicle.Json.*;

final class LootDays
{
	private final LocalStore store;

	LootDays(LocalStore store)
	{
		this.store = store;
	}

	JsonObject dayRoll()
	{
		JsonObject days = sub(store.root, "loot_days");
		String today = LocalDate.now().format(DAY_KEY);
		if (!isObject(days, today))
		{
			days.add(today, new JsonObject());
			pruneDetail(days);
		}
		return days.getAsJsonObject(today);
	}

	static void pruneDetail(JsonObject days)
	{
		String cut = LocalDate.now().minusDays(DETAIL_DAYS).format(DAY_KEY);
		for (var d : objects(days))
		{
			if (d.getKey().compareTo(cut) < 0)
			{
				d.getValue().remove("sources");
				d.getValue().remove("items");
				d.getValue().remove("leftItems");
			}
		}
	}

	static final int DETAIL_DAYS = 400;

	static final DateTimeFormatter DAY_KEY =
		DateTimeFormatter.ofPattern("yyyy-MM-dd");

	void rollTaken(String source, long value, List<BagItem> priced, Double killTime)
	{
		for (JsonObject into : new JsonObject[]{dayRoll(), sessionRoll})
		{
			bump(into, "loots", 1);
			bump(into, "value", value);
			JsonObject bySource = sub(sub(into, "sources"), source);
			bump(bySource, "loots", 1);
			bump(bySource, "value", value);
			LocalStore.timeKill(bySource, killTime);
			JsonObject items = sub(into, "items");
			JsonObject mine = sub(bySource, "items");
			bump(bySource, "filed", 1);
			for (BagItem b : priced)
			{
				String id = String.valueOf(b.itemId);
				tally(sub(items, id), b.name, b.qty, b.value);
				tally(sub(mine, id), b.name, b.qty, b.value);
			}
		}
	}

	static void tally(JsonObject row, String name, long q, long v)
	{
		if (name != null)
		{
			row.addProperty("n", name);
		}
		bump(row, "q", q);
		bump(row, "v", v);
	}

	void rollLeft(long qty, long value, int kills, List<BagItem> perItem)
	{
		for (JsonObject into : new JsonObject[]{dayRoll(), sessionRoll})
		{
			bump(into, "left", qty);
			bump(into, "leftValue", value);
			bump(into, "leftKills", kills);
			JsonObject items = sub(into, "leftItems");
			for (BagItem b : perItem)
			{
				tally(sub(items, b.name), null, b.qty, b.value);
			}
		}
	}

	static final class LootWindow
	{
		long loots;
		long value;
		long left;
		long leftValue;
		long leftKills;
		final List<String[]> items = new ArrayList<>();
		final List<String[]> sources = new ArrayList<>();
		final List<String[]> leftItems = new ArrayList<>();
		final Map<String, double[]> times = new LinkedHashMap<>();
		private final Map<String, long[]> byItem = new LinkedHashMap<>();
		private final Map<String, long[]> bySource = new LinkedHashMap<>();
		private final Map<String, long[]> byLeft = new LinkedHashMap<>();

		void add(JsonObject d)
		{
			loots += asLong(d.get("loots"));
			value += asLong(d.get("value"));
			left += asLong(d.get("left"));
			leftValue += asLong(d.get("leftValue"));
			leftKills += asLong(d.get("leftKills"));
			gather(d, "items", byItem, true);
			gather(d, "sources", bySource, false);
			gather(d, "leftItems", byLeft, true);
			gatherTimes(d, times);
		}

		LootWindow ranked()
		{
			rank(byItem, items);
			rank(bySource, sources);
			rank(byLeft, leftItems);
			return this;
		}
	}

	long lootRollFrom()
	{
		synchronized (store.lock)
		{
			return obj(store.root, "loot_days").keySet().stream().min(String::compareTo).map(LootDays::dayMs).orElse(0L);
		}
	}

	List<JsonObject> daysIn(LocalDate from, LocalDate to)
	{
		String lo = from.format(DAY_KEY);
		String hi = to.format(DAY_KEY);
		List<JsonObject> out = new ArrayList<>();
		for (var d : objects(obj(store.root, "loot_days")))
		{
			if (d.getKey().compareTo(lo) >= 0 && d.getKey().compareTo(hi) <= 0)
			{
				out.add(d.getValue());
			}
		}
		return out;
	}

	LootWindow lootBetween(LocalDate from, LocalDate to)
	{
		LootWindow w = new LootWindow();
		synchronized (store.lock)
		{
			daysIn(from, to).forEach(w::add);
		}
		return w.ranked();
	}

	Map<String, long[]> dayTotals()
	{
		Map<String, long[]> out = new TreeMap<>();
		synchronized (store.lock)
		{
			for (var e : objects(obj(store.root, "loot_days")))
			{
				JsonObject d = e.getValue();
				out.put(e.getKey(), new long[]{asLong(d.get("loots")), asLong(d.get("value")),
					asLong(d.get("left")), asLong(d.get("leftValue"))});
			}
		}
		return out;
	}

	long[] itemDays(String name)
	{
		String first = null;
		String last = null;
		int days = 0;
		long held = 0;
		synchronized (store.lock)
		{
			JsonObject all = obj(store.root, "loot_days");
			for (String day : all.keySet())
			{
				boolean hit = false;
				for (var it : objects(obj(obj(all, day), "items")))
				{
					if (name.equalsIgnoreCase(str(it.getValue(), "n", null)))
					{
						hit = true;
						held += asLong(it.getValue().get("q"));
					}
				}
				if (hit)
				{
					days++;
					first = first == null || day.compareTo(first) < 0 ? day : first;
					last = last == null || day.compareTo(last) > 0 ? day : last;
				}
			}
		}
		return days == 0 ? new long[4] : new long[]{dayMs(first), dayMs(last), days, held};
	}

	static long dayMs(String key)
	{
		return LocalDate.parse(key, DAY_KEY)
			.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
	}

	LootWindow sessionLootWindow()
	{
		LootWindow w = new LootWindow();
		synchronized (store.lock)
		{
			w.add(sessionRoll);
		}
		return w.ranked();
	}

	Map<String, List<BagItem>> itemsBySource(LocalDate from, LocalDate to)
	{
		Map<String, Map<String, long[]>> by = new LinkedHashMap<>();
		Map<String, Integer> ids = new HashMap<>();
		synchronized (store.lock)
		{
			for (JsonObject d : from == null ? List.of(sessionRoll) : daysIn(from, to))
			{
				JsonObject srcs = obj(d, "sources");
				for (String source : srcs.keySet())
				{
					Map<String, long[]> into = by.computeIfAbsent(source, k -> new LinkedHashMap<>());
					JsonObject its = obj(obj(srcs, source), "items");
					for (String id : its.keySet())
					{
						JsonObject e = obj(its, id);
						String label = str(obj(obj(d, "items"), id), "n", str(e, "n", id));
						add(into, label, asLong(e.get("q")), asLong(e.get("v")));
						if (LocalStore.idOf(id) != null)
						{
							ids.putIfAbsent(label, LocalStore.idOf(id));
						}
					}
				}
			}
		}
		Map<String, List<BagItem>> out = new LinkedHashMap<>();
		by.forEach((source, items) -> out.put(source, LocalStore.bagRows(items, ids, 0)));
		return out;
	}

	static void add(Map<String, long[]> into, String key, long qty, long value)
	{
		long[] t = into.computeIfAbsent(key, k -> new long[2]);
		t[0] += qty;
		t[1] += value;
	}

	Set<String> unfiledSources(LocalDate from, LocalDate to)
	{
		Set<String> out = new HashSet<>();
		synchronized (store.lock)
		{
			for (JsonObject d : daysIn(from, to))
			{
				JsonObject srcs = obj(d, "sources");
				for (String source : srcs.keySet())
				{
					if (asLong(obj(srcs, source).get("loots")) > asLong(obj(srcs, source).get("filed")))
					{
						out.add(source);
					}
				}
			}
		}
		return out;
	}

	long lootDetailFrom()
	{
		synchronized (store.lock)
		{
			JsonObject all = obj(store.root, "loot_days");
			String first = null;
			for (String day : all.keySet())
			{
				if (obj(all, day).has("sources") && (first == null || day.compareTo(first) < 0))
				{
					first = day;
				}
			}
			return first == null ? 0 : dayMs(first);
		}
	}

	static void gather(JsonObject day, String key,
		Map<String, long[]> into, boolean named)
	{
		for (var e : objects(obj(day, key)))
		{
			JsonObject o = e.getValue();
			add(into, named && o.has("n") ? o.get("n").getAsString() : e.getKey(),
				asLong(o.get(named ? "q" : "loots")), asLong(o.get(named ? "v" : "value")));
		}
	}

	static void gatherTimes(JsonObject day, Map<String, double[]> into)
	{
		for (var e : objects(obj(day, "sources")))
		{
			long timed = asLong(e.getValue().get("timed"));
			if (timed > 0)
			{
				double[] t = into.computeIfAbsent(e.getKey(), x -> new double[2]);
				t[0] += timed;
				t[1] += asDouble(e.getValue().get("timeSum"));
			}
		}
	}

	static void rank(Map<String, long[]> from, List<String[]> into)
	{
		List<Map.Entry<String, long[]>> rows = new ArrayList<>(from.entrySet());
		rows.sort((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]));
		for (var e : rows)
		{
			into.add(new String[]{e.getKey(), String.valueOf(e.getValue()[0]), String.valueOf(e.getValue()[1])});
		}
	}

	JsonObject sessionRoll = new JsonObject();

	int sessionLoots()
	{
		return (int) sessionFigure("loots");
	}

	long sessionLootValue()
	{
		return sessionFigure("value");
	}

	long sessionFigure(String key)
	{
		synchronized (store.lock)
		{
			return asLong(sessionRoll.get(key));
		}
	}

	long[] sessionUntakenTally()
	{
		return new long[]{sessionFigure("left"), sessionFigure("leftValue")};
	}

	int sessionUntakenKills()
	{
		return (int) sessionFigure("leftKills");
	}
}
