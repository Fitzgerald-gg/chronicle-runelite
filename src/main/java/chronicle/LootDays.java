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
import lombok.RequiredArgsConstructor;
import static chronicle.Json.*;

@RequiredArgsConstructor
final class LootDays
{
	private final LocalStore store;
	private static final int DETAIL_DAYS = 400;
	private static final DateTimeFormatter DAY_KEY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

	JsonObject sessionRoll = new JsonObject();

	private JsonObject dayRoll()
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

	private static void pruneDetail(JsonObject days)
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

	private static void tally(JsonObject row, String name, long q, long v)
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
			perItem.forEach(b -> tally(sub(items, b.name), null, b.qty, b.value));
		}
	}

	static final class LootWindow
	{
		long loots;
		long value;
		long left;
		long leftValue;
		long leftKills;
		final List<Tally> items = new ArrayList<>();
		final List<Tally> sources = new ArrayList<>();
		final List<Tally> leftItems = new ArrayList<>();
		final Map<String, Timing> times = new LinkedHashMap<>();
		private final Map<String, Tally> byItem = new LinkedHashMap<>();
		private final Map<String, Tally> bySource = new LinkedHashMap<>();
		private final Map<String, Tally> byLeft = new LinkedHashMap<>();

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
			items.addAll(Tally.ranked(byItem));
			sources.addAll(Tally.ranked(bySource));
			leftItems.addAll(Tally.ranked(byLeft));
			return this;
		}
	}

	static final class Timing
	{
		long kills;
		double seconds;

		static Timing of(long kills, double seconds)
		{
			Timing t = new Timing();
			t.kills = kills;
			t.seconds = seconds;
			return t;
		}
	}

	@RequiredArgsConstructor
	static final class ItemDays
	{
		static final ItemDays NONE = new ItemDays(0, 0, 0, 0);

		final long first;
		final long last;
		final int days;
		final long held;
	}

	long lootRollFrom()
	{
		synchronized (store.lock)
		{
			return obj(store.root, "loot_days").keySet().stream().min(String::compareTo).map(LootDays::dayMs).orElse(0L);
		}
	}

	private List<JsonObject> daysIn(LocalDate from, LocalDate to)
	{
		String lo = from.format(DAY_KEY);
		String hi = to.format(DAY_KEY);
		List<JsonObject> out = new ArrayList<>();
		objects(obj(store.root, "loot_days")).stream().filter(d -> d.getKey().compareTo(lo) >= 0 && d.getKey().compareTo(hi) <= 0)
			.forEach(d -> out.add(d.getValue()));
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

	Map<String, Tally> dayTotals()
	{
		Map<String, Tally> out = new TreeMap<>();
		synchronized (store.lock)
		{
			objects(obj(store.root, "loot_days")).forEach(e ->
				out.put(e.getKey(), new Tally(e.getKey(), asLong(e.getValue().get("loots")), asLong(e.getValue().get("value")))));
		}
		return out;
	}

	ItemDays itemDays(String name)
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
		return days == 0 ? ItemDays.NONE : new ItemDays(dayMs(first), dayMs(last), days, held);
	}

	private static long dayMs(String key)
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
		Map<String, Map<String, Tally>> by = new LinkedHashMap<>();
		Map<String, Integer> ids = new HashMap<>();
		synchronized (store.lock)
		{
			for (JsonObject d : from == null ? List.of(sessionRoll) : daysIn(from, to))
			{
				JsonObject srcs = obj(d, "sources");
				for (String source : srcs.keySet())
				{
					Map<String, Tally> into = by.computeIfAbsent(source, k -> new LinkedHashMap<>());
					JsonObject its = obj(obj(srcs, source), "items");
					for (String id : its.keySet())
					{
						JsonObject e = obj(its, id);
						String label = str(obj(obj(d, "items"), id), "n", str(e, "n", id));
						Tally.add(into, label, asLong(e.get("q")), asLong(e.get("v")));
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

	Set<String> unfiledSources(LocalDate from, LocalDate to)
	{
		Set<String> out = new HashSet<>();
		synchronized (store.lock)
		{
			for (JsonObject d : daysIn(from, to))
			{
				JsonObject srcs = obj(d, "sources");
				srcs.keySet().stream().filter(source -> asLong(obj(srcs, source).get("loots")) > asLong(obj(srcs, source).get("filed")))
					.forEach(out::add);
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

	private static void gather(JsonObject day, String key, Map<String, Tally> into, boolean named)
	{
		for (var e : objects(obj(day, key)))
		{
			JsonObject o = e.getValue();
			Tally.add(into, named && o.has("n") ? o.get("n").getAsString() : e.getKey(),
				asLong(o.get(named ? "q" : "loots")), asLong(o.get(named ? "v" : "value")));
		}
	}

	private static void gatherTimes(JsonObject day, Map<String, Timing> into)
	{
		for (var e : objects(obj(day, "sources")))
		{
			long timed = asLong(e.getValue().get("timed"));
			if (timed > 0)
			{
				Timing t = into.computeIfAbsent(e.getKey(), x -> new Timing());
				t.kills += timed;
				t.seconds += asDouble(e.getValue().get("timeSum"));
			}
		}
	}
}
