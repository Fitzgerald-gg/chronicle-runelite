/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the
 * BSD 2-Clause License (see LICENSE) are met.
 */
package chronicle;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.game.ItemManager;

@Singleton
@Slf4j
class LocalStore implements chronicle.counters.GatheredLedger
{
	static final int SCHEMA = 1;
	static final int KILLS_VERSION = 1;
	private static final int FEED_CAP = 20000;
	static final Set<String> MAX_KEYS = new HashSet<>(
		Arrays.asList("highestHit", "highestHitTaken"));
	private static final Set<String> FEED_TYPES = new HashSet<>(Arrays.asList(
		"PET", "COLLECTION", "COMBAT_ACHIEVEMENT", "QUEST", "DIARY", "CLUE", "DEATH", "SLAYER",
		"LEVEL",
		"SESSION"));

	private final ItemManager itemManager;
	private final Gson gson;

	private final Object lock = new Object();
	static final String SPINE_ADJ = "spine_adj";
	static final String SPINE_ADJ_KV = "spine_adj_kv";
	private volatile boolean freshAdjust;
	private JsonObject root;
	private JsonObject trackersBase;
	private String currentRsn;
	private File mountedDir;
	private volatile boolean ready;
	private volatile String journalWarning;

	private int sessionLoots;
	private long sessionLootValue;
	private int sessionUntaken;
	private long sessionUntakenValue;
	private int sessionUntakenKills;
	private final ArrayDeque<RecentDrop> recentDrops = new ArrayDeque<>();
	private JsonObject sessionRoll = new JsonObject();

	private static final int GATHERED_CAP = 1024;
	private final Set<Integer> gatheredItems =
		ConcurrentHashMap.newKeySet();

	@AllArgsConstructor(access = AccessLevel.PACKAGE)
	static final class RecentDrop
	{
		final int itemId;
		final int quantity;
		final String name;
	}

	@Inject
	LocalStore(ItemManager itemManager, Gson gson)
	{
		this.itemManager = itemManager;
		this.gson = gson;
	}

	void load(File dir, String rsn)
	{
		freshAdjust = false;
		JsonObject loaded = null;
		mountedDir = dir;
		File f = jsonPath(dir, rsn);
		if (f.isFile())
		{
			try
			{
				String txt = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
				JsonElement el = gson.fromJson(txt, JsonElement.class);
				if (el != null && el.isJsonObject())
				{
					loaded = el.getAsJsonObject();
				}
			}
			catch (Exception e)
			{
				log.warn("local record unreadable: {}", f, e);
			}
			if (loaded == null)
			{
				setAside(f, "corrupt");
			}
		}
		long fileSchema = loaded != null ? asLong(loaded.get("schema")) : 0;
		if (fileSchema > SCHEMA)
		{
			log.warn("journal {} is schema {}; this build reads {}",
				f.getName(), fileSchema, SCHEMA);
			journalWarning = "This journal was written by a newer version of Chronicle. "
				+ "Update the plugin to open it. Nothing on disk has been changed.";
			synchronized (lock)
			{
				root = skeleton(rsn);
				trackersBase = new JsonObject();
				currentRsn = null;
				ready = false;
				gatheredItems.clear();
			}
			return;
		}
		if (loaded == null)
		{
			loaded = skeleton(rsn);
		}
		normalise(loaded, rsn);
		synchronized (lock)
		{
			root = loaded;
			int healed = repairTrackerKeys();
			trackersBase = loaded.getAsJsonObject("trackers").deepCopy();
			currentRsn = rsn;
			healed += dedupeSourceBags() + dedupeFeed() + reconcileUntaken()
				+ purgeOffTaskMonsters() + splitDays();
			if (healed > 0)
			{
				log.debug("repaired {} journal entries on load", healed);
			}
			gatheredItems.clear();
			for (JsonElement g : arr(loaded, "gathered_items"))
			{
				long id = asLong(g);
				if (id > 0 && gatheredItems.size() < GATHERED_CAP)
				{
					gatheredItems.add((int) id);
				}
			}
			settleKillsVersion(dir, rsn);
			ready = true;
		}
		journalWarning = null;
	}

	void endSession()
	{
		ready = false;
		synchronized (lock)
		{
			sessionLoots = 0;
			sessionLootValue = 0;
			sessionUntaken = 0;
			sessionUntakenValue = 0;
			sessionUntakenKills = 0;
			sessionRoll = new JsonObject();
			recentDrops.clear();
			gatheredItems.clear();
		}
	}

	void addPendingAdjust(HistoryLog.Adjust adj)
	{
		if (adj == null || adj.isEmpty())
		{
			return;
		}
		synchronized (lock)
		{
			if (root == null)
			{
				return;
			}
			HistoryLog.Adjust all = HistoryLog.Adjust.from(root.get(SPINE_ADJ));
			all.add(adj);
			if (all.isEmpty())
			{
				root.remove(SPINE_ADJ);
			}
			else
			{
				root.add(SPINE_ADJ, all.toJson());
			}
			freshAdjust = true;
		}
	}

	void restorePendingAdjust(String rsn, HistoryLog.Adjust adj)
	{
		synchronized (lock)
		{
			if (rsn == null || !rsn.equals(currentRsn))
			{
				return;
			}
			boolean fresh = freshAdjust;
			addPendingAdjust(adj);
			freshAdjust = fresh;
		}
	}

	HistoryLog.Adjust takePendingAdjust()
	{
		synchronized (lock)
		{
			freshAdjust = false;
			if (root == null)
			{
				return new HistoryLog.Adjust();
			}
			HistoryLog.Adjust out = HistoryLog.Adjust.from(root.get(SPINE_ADJ));
			root.remove(SPINE_ADJ);
			root.remove(SPINE_ADJ_KV);
			return out;
		}
	}

	boolean hasPendingAdjust()
	{
		synchronized (lock)
		{
			return root != null && root.has(SPINE_ADJ);
		}
	}

	boolean hasFreshAdjust()
	{
		return freshAdjust;
	}

	boolean isReadyFor(String rsn)
	{
		return ready && rsn != null && rsn.equals(currentRsn);
	}

	private volatile long revision;

	long revision()
	{
		return revision;
	}

	void record(String type, JsonObject data, String rsn)
	{
		if (!isReadyFor(rsn) || type == null || data == null)
		{
			return;
		}
		revision++;
		if ("LOOT".equals(type))
		{
			recordLoot(data);
			return;
		}
		if ("LOOT_UNTAKEN".equals(type))
		{
			recordUntaken(data);
			return;
		}
		if (FEED_TYPES.contains(type))
		{
			if ("COLLECTION".equals(type))
			{
				recordClogSlot(data);
			}
			if ("SLAYER".equals(type))
			{
				recordSlayerCompletion(data);
			}
			appendFeed(type, data);
		}
	}

	private void appendFeed(String type, JsonObject data)
	{
		JsonObject entry = new JsonObject();
		entry.addProperty("ts", System.currentTimeMillis());
		entry.addProperty("type", type);
		entry.add("data", data);
		synchronized (lock)
		{
			JsonArray feed = root.getAsJsonArray("feed");
			feed.add(entry);
			while (feed.size() > FEED_CAP)
			{
				feed.remove(0);
			}
			touch();
		}
	}

	private void recordLoot(JsonObject data)
	{
		String source = str(data, "source", "Unknown");
		JsonArray items = data.has("items") && data.get("items").isJsonArray()
			? data.getAsJsonArray("items") : null;
		Integer kc = present(data, "killCount")
			? data.get("killCount").getAsInt() : null;

		JsonArray priced = new JsonArray();
		long batchValue = 0;
		if (items != null)
		{
			for (JsonElement ie : items)
			{
				if (!ie.isJsonObject())
				{
					continue;
				}
				JsonObject it = ie.getAsJsonObject();
				if (!it.has("id"))
				{
					continue;
				}
				BagItem b = price(it.get("id").getAsInt(),
					it.has("quantity") ? it.get("quantity").getAsInt() : 1);
				batchValue += b.value;
				JsonObject p = new JsonObject();
				p.addProperty("id", b.itemId);
				p.addProperty("name", b.name);
				p.addProperty("qty", b.qty);
				p.addProperty("value", b.value);
				priced.add(p);
			}
		}

		Double pbCand = null;
		if (present(data, "personalBestTime"))
		{
			pbCand = data.get("personalBestTime").getAsDouble();
		}
		else if (present(data, "personalBest")
			&& data.get("personalBest").getAsBoolean()
			&& data.has("killTime") && data.get("killTime").getAsDouble() >= 0)
		{
			pbCand = data.get("killTime").getAsDouble();
		}
		Double killTime = present(data, "killTime")
			&& data.get("killTime").getAsDouble() > 0 ? data.get("killTime").getAsDouble() : null;
		boolean newRecord = present(data, "personalBest")
			&& data.get("personalBest").getAsBoolean();

		synchronized (lock)
		{
			slayerLoot(data, batchValue, source, priced);
			JsonObject drops = root.getAsJsonObject("drops");
			JsonObject src = drops.has(source) && drops.get(source).isJsonObject()
				? drops.getAsJsonObject(source) : null;
			if (src == null)
			{
				src = newSource();
				drops.add(source, src);
			}
			if (kc != null)
			{
				src.addProperty("kc", Math.max(asLong(src.get("kc")), kc.longValue()));
			}
			src.addProperty("loots", asLong(src.get("loots")) + 1);
			src.addProperty("value", asLong(src.get("value")) + batchValue);
			if (killTime != null)
			{
				bump(src, "timed", 1);
				src.addProperty("timeSum", asDouble(src.get("timeSum")) + killTime);
			}
			absorbLaggingKill(source, kc);
			long nowMs = System.currentTimeMillis();
			long firstSeen = asLong(src.get("first_seen"));
			if (firstSeen <= 0 || nowMs < firstSeen)
			{
				src.addProperty("first_seen", nowMs);
			}
			if (nowMs > asLong(src.get("last_seen")))
			{
				src.addProperty("last_seen", nowMs);
			}
			rollTaken(source, batchValue, priced, killTime);
			sessionLoots++;
			sessionLootValue += batchValue;
			for (JsonElement pe : priced)
			{
				JsonObject p = pe.getAsJsonObject();
				recentDrops.addFirst(new RecentDrop(p.get("id").getAsInt(),
					p.get("qty").getAsInt(), p.get("name").getAsString()));
			}
			while (recentDrops.size() > 10)
			{
				recentDrops.removeLast();
			}
			double bestPb = asDouble(src.get("pb"));
			if (pbCand != null && pbCand > 0 && (bestPb <= 0 || pbCand < bestPb))
			{
				src.addProperty("pb", pbCand);
				if (newRecord)
				{
					JsonObject rec = new JsonObject();
					rec.addProperty("source", source);
					rec.addProperty("time", pbCand);
					if (bestPb > 0)
					{
						rec.addProperty("was", bestPb);
					}
					appendFeed("RECORD", rec);
				}
			}

			JsonObject bag = sub(src, "items");
			for (JsonElement pe : priced)
			{
				JsonObject p = pe.getAsJsonObject();
				String key = String.valueOf(p.get("id").getAsInt());
				if (bag.has(key) && bag.get(key).isJsonObject())
				{
					JsonObject cur = bag.getAsJsonObject(key);
					cur.addProperty("qty", asLong(cur.get("qty")) + p.get("qty").getAsLong());
					cur.addProperty("value", asLong(cur.get("value")) + p.get("value").getAsLong());
					cur.addProperty("name", p.get("name").getAsString());
				}
				else
				{
					bag.add(key, p);
				}
			}
			touch();
		}
	}

	private static final int DETAIL_DAYS = 400;
	private static final DateTimeFormatter DAY_KEY =
		DateTimeFormatter.ofPattern("yyyy-MM-dd");

	private JsonObject dayRoll()
	{
		JsonObject days = sub(root, "loot_days");
		String today = LocalDate.now().format(DAY_KEY);
		if (!days.has(today) || !days.get(today).isJsonObject())
		{
			days.add(today, new JsonObject());
			pruneDetail(days);
		}
		return days.getAsJsonObject(today);
	}

	private static void pruneDetail(JsonObject days)
	{
		String cut = LocalDate.now().minusDays(DETAIL_DAYS).format(DAY_KEY);
		for (String day : new ArrayList<>(days.keySet()))
		{
			if (day.compareTo(cut) >= 0 || !days.get(day).isJsonObject())
			{
				continue;
			}
			JsonObject d = days.getAsJsonObject(day);
			d.remove("sources");
			d.remove("items");
			d.remove("leftItems");
		}
	}

	private static boolean present(JsonObject o, String key)
	{
		return o.has(key) && !o.get(key).isJsonNull();
	}

	private static void bump(JsonObject o, String key, long by)
	{
		o.addProperty(key, asLong(o.get(key)) + by);
	}

	private static JsonObject sub(JsonObject parent, String key)
	{
		if (!parent.has(key) || !parent.get(key).isJsonObject())
		{
			parent.add(key, new JsonObject());
		}
		return parent.getAsJsonObject(key);
	}

	private static JsonObject obj(JsonObject o, String key)
	{
		return o != null && o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key) : new JsonObject();
	}

	private void rollTaken(String source, long value, JsonArray priced, Double killTime)
	{
		for (JsonObject into : new JsonObject[]{dayRoll(), sessionRoll})
		{
			bump(into, "loots", 1);
			bump(into, "value", value);
			JsonObject bySource = sub(sub(into, "sources"), source);
			bump(bySource, "loots", 1);
			bump(bySource, "value", value);
			if (killTime != null)
			{
				bump(bySource, "timed", 1);
				bySource.addProperty("timeSum", asDouble(bySource.get("timeSum")) + killTime);
			}
			JsonObject items = sub(into, "items");
			JsonObject mine = sub(bySource, "items");
			bump(bySource, "filed", 1);
			for (JsonElement pe : priced)
			{
				JsonObject p = pe.getAsJsonObject();
				String id = String.valueOf(p.get("id").getAsInt());
				JsonObject it = sub(items, id);
				it.addProperty("n", p.get("name").getAsString());
				bump(it, "q", p.get("qty").getAsLong());
				bump(it, "v", p.get("value").getAsLong());
				JsonObject own = sub(mine, id);
				own.addProperty("n", p.get("name").getAsString());
				bump(own, "q", p.get("qty").getAsLong());
				bump(own, "v", p.get("value").getAsLong());
			}
		}
	}

	private void rollLeft(long qty, long value, int kills, List<BagItem> perItem)
	{
		for (JsonObject into : new JsonObject[]{dayRoll(), sessionRoll})
		{
			bump(into, "left", qty);
			bump(into, "leftValue", value);
			bump(into, "leftKills", kills);
			JsonObject items = sub(into, "leftItems");
			for (BagItem b : perItem)
			{
				JsonObject it = sub(items, b.name);
				bump(it, "q", b.qty);
				bump(it, "v", b.value);
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
		synchronized (lock)
		{
			if (!root.has("loot_days") || !root.get("loot_days").isJsonObject())
			{
				return 0;
			}
			String first = null;
			for (String day : root.getAsJsonObject("loot_days").keySet())
			{
				if (first == null || day.compareTo(first) < 0)
				{
					first = day;
				}
			}
			return first == null ? 0 : LocalDate.parse(first)
				.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
		}
	}

	LootWindow lootBetween(LocalDate from, LocalDate to)
	{
		LootWindow w = new LootWindow();
		synchronized (lock)
		{
			if (!root.has("loot_days") || !root.get("loot_days").isJsonObject())
			{
				return w;
			}
			String lo = from.format(DAY_KEY);
			String hi = to.format(DAY_KEY);
			JsonObject days = root.getAsJsonObject("loot_days");
			for (String day : days.keySet())
			{
				if (day.compareTo(lo) < 0 || day.compareTo(hi) > 0
					|| !days.get(day).isJsonObject())
				{
					continue;
				}
				w.add(days.getAsJsonObject(day));
			}
		}
		return w.ranked();
	}

	Map<String, long[]> dayTotals()
	{
		Map<String, long[]> out = new TreeMap<>();
		synchronized (lock)
		{
			JsonObject days = obj(root, "loot_days");
			for (String day : days.keySet())
			{
				if (days.get(day).isJsonObject())
				{
					JsonObject d = days.getAsJsonObject(day);
					out.put(day, new long[]{asLong(d.get("loots")), asLong(d.get("value")),
						asLong(d.get("left")), asLong(d.get("leftValue"))});
				}
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
		synchronized (lock)
		{
			JsonObject all = obj(root, "loot_days");
			for (String day : all.keySet())
			{
				JsonObject items = obj(obj(all, day), "items");
				if (!dayHolds(obj(all, day), name))
				{
					continue;
				}
				for (String id : items.keySet())
				{
					held += name.equalsIgnoreCase(str(obj(items, id), "n", "")) ? asLong(obj(items, id).get("q")) : 0;
				}
				days++;
				first = first == null || day.compareTo(first) < 0 ? day : first;
				last = last == null || day.compareTo(last) > 0 ? day : last;
			}
		}
		return days == 0 ? new long[4] : new long[]{dayMs(first), dayMs(last), days, held};
	}

	private static boolean dayHolds(JsonObject day, String name)
	{
		for (var it : obj(day, "items").entrySet())
		{
			if (it.getValue().isJsonObject() && it.getValue().getAsJsonObject().has("n")
				&& name.equalsIgnoreCase(it.getValue().getAsJsonObject().get("n").getAsString()))
			{
				return true;
			}
		}
		return false;
	}

	private static long dayMs(String key)
	{
		return LocalDate.parse(key, DAY_KEY)
			.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
	}

	LootWindow sessionLootWindow()
	{
		LootWindow w = new LootWindow();
		synchronized (lock)
		{
			w.add(sessionRoll);
		}
		return w.ranked();
	}

	Map<String, List<BagItem>> itemsBySource(LocalDate from, LocalDate to)
	{
		Map<String, Map<String, long[]>> by = new LinkedHashMap<>();
		Map<String, Integer> ids = new HashMap<>();
		synchronized (lock)
		{
			List<JsonObject> days = new ArrayList<>();
			if (from == null)
			{
				days.add(sessionRoll);
			}
			else
			{
				JsonObject all = obj(root, "loot_days");
				for (String day : all.keySet())
				{
					if (day.compareTo(from.format(DAY_KEY)) >= 0 && day.compareTo(to.format(DAY_KEY)) <= 0)
					{
						days.add(obj(all, day));
					}
				}
			}
			for (JsonObject d : days)
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
						long[] t = into.computeIfAbsent(label, k -> new long[2]);
						t[0] += asLong(e.get("q"));
						t[1] += asLong(e.get("v"));
						try
						{
							ids.putIfAbsent(label, Integer.parseInt(id));
						}
						catch (NumberFormatException ignored)
						{
						}
					}
				}
			}
		}
		Map<String, List<BagItem>> out = new LinkedHashMap<>();
		by.forEach((source, items) -> out.put(source, bagRows(items, ids, 0)));
		return out;
	}

	Set<String> unfiledSources(LocalDate from, LocalDate to)
	{
		Set<String> out = new HashSet<>();
		synchronized (lock)
		{
			JsonObject all = obj(root, "loot_days");
			for (String day : all.keySet())
			{
				if (day.compareTo(from.format(DAY_KEY)) < 0 || day.compareTo(to.format(DAY_KEY)) > 0)
				{
					continue;
				}
				JsonObject srcs = obj(obj(all, day), "sources");
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
		synchronized (lock)
		{
			JsonObject all = obj(root, "loot_days");
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

	private int splitDays()
	{
		int split = 0;
		JsonObject drops = obj(root, "drops");
		JsonObject all = obj(root, "loot_days");
		days:
		for (String day : all.keySet())
		{
			JsonObject srcs = obj(obj(all, day), "sources");
			JsonObject heap = obj(obj(all, day), "items");
			Map<String, long[]> rest = new LinkedHashMap<>();
			for (String id : heap.keySet())
			{
				rest.put(id, new long[]{asLong(obj(heap, id).get("q")), asLong(obj(heap, id).get("v"))});
			}
			Map<String, Long> owed = new HashMap<>();
			for (String source : srcs.keySet())
			{
				JsonObject its = obj(obj(srcs, source), "items");
				long v = asLong(obj(srcs, source).get("value"));
				for (String id : its.keySet())
				{
					long[] r = rest.get(id);
					if (r == null)
					{
						continue days;
					}
					r[0] -= asLong(obj(its, id).get("q"));
					r[1] -= asLong(obj(its, id).get("v"));
					v -= asLong(obj(its, id).get("v"));
				}
				if (v < 0)
				{
					continue days;
				}
				if (asLong(obj(srcs, source).get("loots")) > asLong(obj(srcs, source).get("filed")))
				{
					owed.put(source, v);
				}
			}
			rest.values().removeIf(r -> r[0] == 0 && r[1] == 0);
			if (owed.isEmpty() && rest.isEmpty())
			{
				continue;
			}
			Map<String, JsonObject> rows = new HashMap<>();
			for (Map.Entry<String, long[]> r : rest.entrySet())
			{
				String id = r.getKey();
				String name = str(obj(heap, id), "n", "");
				String owner = owed.size() == 1 ? owed.keySet().iterator().next() : null;
				for (String source : owner != null ? new HashSet<String>() : srcs.keySet())
				{
					JsonObject bag = obj(obj(drops, source), "items");
					if (bag.has(id) || dropped(bag, name))
					{
						if (owner != null || r.getValue()[1] == 0)
						{
							continue days;
						}
						owner = source;
					}
				}
				if (owner == null || !owed.containsKey(owner) || r.getValue()[0] < 0 || r.getValue()[1] < 0)
				{
					continue days;
				}
				JsonObject row = new JsonObject();
				row.addProperty("n", name);
				row.addProperty("q", r.getValue()[0]);
				row.addProperty("v", r.getValue()[1]);
				rows.computeIfAbsent(owner, k -> new JsonObject()).add(id, row);
			}
			for (Map.Entry<String, Long> o : owed.entrySet())
			{
				long v = 0;
				for (String id : rows.getOrDefault(o.getKey(), new JsonObject()).keySet())
				{
					v += asLong(obj(rows.get(o.getKey()), id).get("v"));
				}
				if (v != o.getValue())
				{
					continue days;
				}
			}
			for (String source : owed.keySet())
			{
				obj(srcs, source).addProperty("filed", asLong(obj(srcs, source).get("loots")));
				JsonObject its = sub(obj(srcs, source), "items");
				JsonObject add = rows.getOrDefault(source, new JsonObject());
				for (String id : add.keySet())
				{
					JsonObject own = sub(its, id);
					own.addProperty("n", str(obj(add, id), "n", ""));
					bump(own, "q", asLong(obj(add, id).get("q")));
					bump(own, "v", asLong(obj(add, id).get("v")));
				}
			}
			split++;
		}
		return split;
	}

	private static boolean dropped(JsonObject bag, String name)
	{
		for (String key : bag.keySet())
		{
			if (name.equalsIgnoreCase(str(obj(bag, key), "name", key)))
			{
				return true;
			}
		}
		return false;
	}

	private static void gather(JsonObject day, String key,
		Map<String, long[]> into, boolean named)
	{
		JsonObject o = obj(day, key);
		for (String k : o.keySet())
		{
			if (!o.get(k).isJsonObject())
			{
				continue;
			}
			JsonObject e = o.getAsJsonObject(k);
			String name = named && e.has("n") ? e.get("n").getAsString() : k;
			long[] t = into.computeIfAbsent(name, x -> new long[2]);
			t[0] += asLong(e.get(named ? "q" : "loots"));
			t[1] += asLong(e.get(named ? "v" : "value"));
		}
	}

	private static void gatherTimes(JsonObject day, Map<String, double[]> into)
	{
		JsonObject o = obj(day, "sources");
		for (String k : o.keySet())
		{
			if (!o.get(k).isJsonObject() || asLong(o.getAsJsonObject(k).get("timed")) <= 0)
			{
				continue;
			}
			double[] t = into.computeIfAbsent(k, x -> new double[2]);
			t[0] += asLong(o.getAsJsonObject(k).get("timed"));
			t[1] += asDouble(o.getAsJsonObject(k).get("timeSum"));
		}
	}

	private static void rank(Map<String, long[]> from, List<String[]> into)
	{
		List<Map.Entry<String, long[]>> rows =
			new ArrayList<>(from.entrySet());
		rows.sort((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]));
		for (var e : rows)
		{
			into.add(new String[]{e.getKey(), String.valueOf(e.getValue()[0]),
				String.valueOf(e.getValue()[1])});
		}
	}

	void setCharacter(String rsn, JsonObject skills, int combatLevel,
		Map<String, Object> collectionLog, JsonObject achievements)
	{
		if (!isReadyFor(rsn))
		{
			return;
		}
		revision++;
		synchronized (lock)
		{
			if (skills != null)
			{
				root.add("skills", skills);
			}
			if (combatLevel > 0)
			{
				root.addProperty("combat_level", combatLevel);
			}
			Counts before = collectionLog != null ? countsNow() : null;
			if (collectionLog != null)
			{
				Object read = collectionLog.get("slayer_kcs");
				if (read instanceof Map)
				{
					for (var e : ((Map<?, ?>) read).entrySet())
					{
						if (e.getKey() != null && e.getValue() instanceof Number)
						{
							anchorKill(String.valueOf(e.getKey()),
								((Number) e.getValue()).longValue(), "log", rsn);
						}
					}
				}
				root.add("collection_log", mergeClog(sub(root, "collection_log"),
					gson.toJsonTree(collectionLog).getAsJsonObject()));
			}
			if (achievements != null)
			{
				root.add("achievements", achievements);
			}
			touch();
			if (before != null)
			{
				noteArrival(before, 0);
			}
		}
	}

	@AllArgsConstructor(access = AccessLevel.PACKAGE)
	private static final class Counts
	{
		final Map<String, Long> kills;
		final Map<String, Integer> rank;
		final long sum;
		final Set<String> summed;
	}

	private Counts countsNow()
	{
		synchronized (lock)
		{
			JsonObject cl = clogSnapshot();
			List<SourceRow> sources = dropSources();
			Map<String, Long> chat = chatKillCounts();
			Map<String, Long> anchored = anchoredKills();
			Map<String, Long> kills = reconciledKills(cl, sources, chat, anchored);
			Map<String, Integer> rank = new HashMap<>();
			for (String name : obj(cl, "kcs").keySet())
			{
				rank.put(chatKind(name), 1);
			}
			Map<String, Long> said = killLogCounts(cl);
			said.putAll(pageKillLines(cl));
			Set<String> vocabulary = clogKillCounts(cl).keySet();
			foldChatCounts(said, chat, vocabulary);
			foldChatCounts(said, anchored, vocabulary);
			for (String name : said.keySet())
			{
				rank.put(chatKind(name), 2);
			}
			Set<String> summed = new HashSet<>();
			long sum = 0;
			for (String key : spineKillKeys(cl, sources, kills))
			{
				summed.add(chatKind(key));
				sum += kills.getOrDefault(key, 0L);
			}
			return new Counts(kills, rank, sum, summed);
		}
	}

	private void noteArrival(Counts before, long announced)
	{
		Counts after = countsNow();
		Map<String, Long> was = new HashMap<>();
		before.kills.forEach((k, v) -> was.merge(chatKind(k), v, Math::max));
		HistoryLog.Adjust adj = new HistoryLog.Adjust();
		long played = 0;
		for (var e : after.kills.entrySet())
		{
			String kind = chatKind(e.getKey());
			Long then = was.get(kind);
			if (then == null)
			{
				continue;
			}
			long moved = e.getValue() - then;
			boolean risen = after.rank.getOrDefault(kind, 0) > before.rank.getOrDefault(kind, 0);
			long notPlay = moved < 0 ? moved : risen ? Math.max(0, moved - announced) : 0;
			if (notPlay != 0)
			{
				adj.kcs.merge(e.getKey(), notPlay, Long::sum);
			}
			if (after.summed.contains(kind) && before.summed.contains(kind))
			{
				played += moved - notPlay;
			}
		}
		long sumShift = after.sum - before.sum - played;
		if (sumShift != 0)
		{
			adj.counters.put("kills", sumShift);
		}
		addPendingAdjust(adj);
	}

	void rebase(String rsn)
	{
		if (!isReadyFor(rsn))
		{
			return;
		}
		revision++;
		synchronized (lock)
		{
			if (root.has("trackers") && root.get("trackers").isJsonObject())
			{
				trackersBase = root.getAsJsonObject("trackers").deepCopy();
			}
		}
	}

	void setTrackers(Map<String, Integer> session, String rsn)
	{
		if (!isReadyFor(rsn) || session == null)
		{
			return;
		}
		synchronized (lock)
		{
			JsonObject tr = new JsonObject();
			for (var e : lifetimeOf(session).entrySet())
			{
				tr.addProperty(e.getKey(), e.getValue());
			}
			root.add("trackers", tr);
			touch();
		}
		revision++;
	}

	Map<String, Long> lifetimeOf(Map<String, Integer> session)
	{
		Map<String, Long> out = new HashMap<>();
		synchronized (lock)
		{
			Set<String> keys = new HashSet<>(
				session == null ? Collections.emptySet() : session.keySet());
			for (var e : trackersBase.entrySet())
			{
				keys.add(e.getKey());
			}
			for (String k : keys)
			{
				long base = present(trackersBase, k)
					? trackersBase.get(k).getAsLong() : 0;
				Integer s = session == null ? null : session.get(k);
				long sess = s != null ? s.longValue() : 0;
				long life = MAX_KEYS.contains(k) ? Math.max(base, sess) : base + sess;
				if (life != 0)
				{
					out.put(k, life);
				}
			}
		}
		return out;
	}

	void flush(File dir)
	{
		String json;
		String rsn;
		synchronized (lock)
		{
			if (root == null || currentRsn == null)
			{
				return;
			}
			json = gson.toJson(root);
			rsn = currentRsn;
		}
		try
		{
			if (!dir.isDirectory() && !dir.mkdirs())
			{
				log.debug("could not create local dir {}", dir);
			}
			writeAtomic(jsonPath(dir, rsn), json);
			journalWarning = null;
		}
		catch (Exception e)
		{
			log.warn("local flush failed", e);
			journalWarning = "Could not write the journal to disk: check free space and "
				+ "permissions on " + dir.getAbsolutePath() + ".";
		}
	}

	private JsonObject skeleton(String rsn)
	{
		JsonObject o = new JsonObject();
		o.addProperty("schema", SCHEMA);
		o.addProperty("rsn", rsn);
		o.addProperty("first_seen", nowSec());
		o.addProperty("updated_at", nowSec());
		normalise(o, rsn);
		return o;
	}

	private static JsonObject newSource()
	{
		JsonObject src = new JsonObject();
		src.addProperty("kc", 0);
		src.addProperty("loots", 0);
		src.addProperty("value", 0);
		src.add("items", new JsonObject());
		return src;
	}

	private void normalise(JsonObject o, String rsn)
	{
		o.addProperty("schema", SCHEMA);
		o.addProperty("rsn", rsn);
		if (!o.has("first_seen"))
		{
			o.addProperty("first_seen", nowSec());
		}
		sub(o, "skills");
		sub(o, "chat_kcs");
		sub(o, "kc_anchors");
		sub(o, "collection_log");
		sub(o, "achievements");
		sub(o, "drops");
		sub(o, "trackers");
		if (!o.has("feed") || !o.get("feed").isJsonArray())
		{
			o.add("feed", new JsonArray());
		}
	}

	private static long nowSec()
	{
		return System.currentTimeMillis() / 1000L;
	}

	private void touch()
	{
		root.addProperty("updated_at", nowSec());
	}

	ItemManager items()
	{
		return itemManager;
	}

	String journalWarning()
	{
		return journalWarning;
	}

	Map<String, Long> trackersSnapshot()
	{
		Map<String, Long> out = new HashMap<>();
		synchronized (lock)
		{
			for (var e : obj(root, "trackers").entrySet())
			{
				if (!e.getValue().isJsonNull())
				{
					out.put(e.getKey(), e.getValue().getAsLong());
				}
			}
		}
		return out;
	}

	@AllArgsConstructor(access = AccessLevel.PACKAGE)
	static final class SourceRow
	{
		final String name;
		final int kc;
		final int loots;
		final long value;
		final Double pb;
		final long firstMs;
		final long lastMs;
		final Set<String> looted;
		final long timed;
		final double timeSum;
	}

	List<SourceRow> dropSources()
	{
		List<SourceRow> out = new ArrayList<>();
		synchronized (lock)
		{
			if (root == null || !root.has("drops"))
			{
				return out;
			}
			for (var e : root.getAsJsonObject("drops").entrySet())
			{
				if (!e.getValue().isJsonObject())
				{
					continue;
				}
				JsonObject src = e.getValue().getAsJsonObject();
				out.add(new SourceRow(e.getKey(),
					src.has("kc") ? src.get("kc").getAsInt() : 0,
					src.has("loots") ? src.get("loots").getAsInt() : 0,
					src.has("value") ? src.get("value").getAsLong() : 0,
					src.has("pb") ? src.get("pb").getAsDouble() : null,
					src.has("first_seen") ? src.get("first_seen").getAsLong() : 0,
					src.has("last_seen") ? src.get("last_seen").getAsLong() : 0,
					lootedNames(src), asLong(src.get("timed")), asDouble(src.get("timeSum"))));
			}
		}
		return out;
	}

	private static Set<String> lootedNames(JsonObject src)
	{
		Set<String> out = new HashSet<>();
		for (var e : obj(src, "items").entrySet())
		{
			if (!e.getValue().isJsonObject())
			{
				continue;
			}
			JsonObject it = e.getValue().getAsJsonObject();
			if (asLong(it.get("qty")) <= 0 || !present(it, "name"))
			{
				continue;
			}
			String name = it.get("name").getAsString().trim().toLowerCase(Locale.ROOT);
			if (!name.isEmpty())
			{
				out.add(name);
			}
		}
		return out.isEmpty() ? Collections.emptySet() : out;
	}

	List<JsonObject> feedNewest(int n)
	{
		List<JsonObject> out = new ArrayList<>();
		synchronized (lock)
		{
			JsonArray feed = arr(root, "feed");
			for (int i = feed.size() - 1; i >= 0 && out.size() < n; i--)
			{
				if (feed.get(i).isJsonObject())
				{
					out.add(feed.get(i).getAsJsonObject().deepCopy());
				}
			}
		}
		return out;
	}

	int sessionLoots()
	{
		synchronized (lock)
		{
			return sessionLoots;
		}
	}

	long sessionLootValue()
	{
		synchronized (lock)
		{
			return sessionLootValue;
		}
	}

	List<RecentDrop> recentDrops()
	{
		synchronized (lock)
		{
			return new ArrayList<>(recentDrops);
		}
	}

	@AllArgsConstructor(access = AccessLevel.PACKAGE)
	static final class BagItem
	{
		final int itemId;
		final String name;
		final long qty;
		final long value;
	}

	BagItem price(int id, long qty)
	{
		int canon = itemManager.canonicalize(id);
		String name;
		try
		{
			name = itemManager.getItemComposition(canon).getName();
		}
		catch (Exception e)
		{
			name = "Item " + id;
		}
		long each = Math.max(0, itemManager.getItemPrice(canon));
		return new BagItem(canon, name, qty, each * qty);
	}

	private static String bagKey(int id, String name)
	{
		return id > 0 ? String.valueOf(id)
			: "n:" + (name == null ? "" : name.toLowerCase(Locale.ROOT));
	}

	@AllArgsConstructor(access = AccessLevel.PACKAGE)
	static final class LootSeed
	{
		final String source;
		final int kills;
		final long firstMs;
		final long lastMs;
		final List<BagItem> items;
	}

	void floorLootTracker(List<LootSeed> seeds, String rsn)
	{
		if (!isReadyFor(rsn) || seeds == null)
		{
			return;
		}
		revision++;
		synchronized (lock)
		{
			JsonObject drops = sub(root, "drops");
			for (LootSeed seed : seeds)
			{
				if (seed.source == null || seed.source.isEmpty())
				{
					continue;
				}
				JsonObject src = drops.has(seed.source) && drops.get(seed.source).isJsonObject()
					? drops.getAsJsonObject(seed.source) : null;
				if (src == null)
				{
					src = newSource();
					drops.add(seed.source, src);
				}
				if (seed.kills > (src.has("kc") ? src.get("kc").getAsInt() : 0))
				{
					src.addProperty("kc", seed.kills);
				}
				if (seed.kills > (src.has("loots") ? src.get("loots").getAsInt() : 0))
				{
					src.addProperty("loots", seed.kills);
				}
				if (seed.firstMs > 0)
				{
					long cur = src.has("first_seen") ? src.get("first_seen").getAsLong() : Long.MAX_VALUE;
					if (seed.firstMs < cur)
					{
						src.addProperty("first_seen", seed.firstMs);
					}
				}
				if (seed.lastMs > 0)
				{
					long cur = src.has("last_seen") ? src.get("last_seen").getAsLong() : 0;
					if (seed.lastMs > cur)
					{
						src.addProperty("last_seen", seed.lastMs);
					}
				}
				JsonObject items = sub(src, "items");
				for (BagItem b : seed.items)
				{
					JsonObject hit = null;
					if (b.itemId > 0 && items.has(String.valueOf(b.itemId))
						&& items.get(String.valueOf(b.itemId)).isJsonObject())
					{
						hit = items.getAsJsonObject(String.valueOf(b.itemId));
					}
					if (hit == null)
					{
						for (var e : items.entrySet())
						{
							if (e.getValue().isJsonObject())
							{
								JsonObject it = e.getValue().getAsJsonObject();
								if (present(it, "name")
									&& b.name.equalsIgnoreCase(it.get("name").getAsString()))
								{
									hit = it;
									if (b.itemId > 0 && e.getKey().startsWith("n:"))
									{
										items.remove(e.getKey());
										hit.addProperty("id", b.itemId);
										items.add(String.valueOf(b.itemId), hit);
									}
									break;
								}
							}
						}
					}
					if (hit == null)
					{
						hit = new JsonObject();
						hit.addProperty("id", b.itemId);
						hit.addProperty("name", b.name);
						hit.addProperty("qty", 0);
						hit.addProperty("value", 0);
						items.add(bagKey(b.itemId, b.name), hit);
					}
					if (b.qty > (hit.has("qty") ? hit.get("qty").getAsLong() : 0))
					{
						hit.addProperty("qty", b.qty);
					}
					if (!hit.has("value") || hit.get("value").getAsLong() <= 0)
					{
						hit.addProperty("value", b.value);
					}
				}
				long bagged = 0;
				for (var e : items.entrySet())
				{
					if (e.getValue().isJsonObject())
					{
						bagged += asLong(e.getValue().getAsJsonObject().get("value"));
					}
				}
				if (bagged > (src.has("value") ? src.get("value").getAsLong() : 0))
				{
					src.addProperty("value", bagged);
				}
			}
			rebaseAnchors();
		}
	}

	List<BagItem> sourceItems(String source)
	{
		List<BagItem> out = new ArrayList<>();
		synchronized (lock)
		{
			for (var e : obj(obj(obj(root, "drops"), source), "items").entrySet())
			{
				if (!e.getValue().isJsonObject())
				{
					continue;
				}
				JsonObject it = e.getValue().getAsJsonObject();
				int id;
				try
				{
					id = Integer.parseInt(e.getKey());
				}
				catch (NumberFormatException ex)
				{
					id = -1;
				}
				out.add(new BagItem(id,
					it.has("name") ? it.get("name").getAsString() : e.getKey(),
					it.has("qty") ? it.get("qty").getAsLong() : 0,
					it.has("value") ? it.get("value").getAsLong() : 0));
			}
		}
		return out;
	}

	private void recordUntaken(JsonObject data)
	{
		String source = str(data, "source", "Unknown");
		JsonArray items = data.has("items") && data.get("items").isJsonArray()
			? data.getAsJsonArray("items") : null;
		if (items == null)
		{
			return;
		}
		int kills = (int) Math.max(0, asLong(data.get("kills")));
		long qty = 0;
		long value = 0;
		List<BagItem> perItem = new ArrayList<>();
		for (JsonElement ie : items)
		{
			if (!ie.isJsonObject() || !ie.getAsJsonObject().has("id"))
			{
				continue;
			}
			JsonObject it = ie.getAsJsonObject();
			BagItem b = price(it.get("id").getAsInt(),
				it.has("quantity") ? it.get("quantity").getAsInt() : 1);
			qty += b.qty;
			value += b.value;
			perItem.add(b);
		}
		synchronized (lock)
		{
			JsonObject src = sub(sub(root, "untaken"), source);
			bump(src, "qty", qty);
			bump(src, "value", value);
			bump(src, "kills", kills);
			JsonObject byItem = sub(root, "untaken_items");
			for (BagItem b : perItem)
			{
				JsonObject e = sub(byItem, b.name);
				bump(e, "qty", b.qty);
				bump(e, "value", b.value);
			}
			rollLeft(qty, value, kills, perItem);
			JsonObject bag = sub(sub(root, "untaken_pairs"), source);
			for (BagItem b : perItem)
			{
				JsonObject e = sub(bag, b.name);
				e.addProperty("id", b.itemId);
				bump(e, "qty", b.qty);
				bump(e, "value", b.value);
			}
			sessionUntaken += qty;
			sessionUntakenValue += value;
			sessionUntakenKills += kills;
			touch();
		}
	}

	@AllArgsConstructor(access = AccessLevel.PACKAGE)
	static final class UntakenRow
	{
		final String name;
		final long qty;
		final long value;
		final long kills;

		UntakenRow(String name, long qty, long value)
		{
			this(name, qty, value, 0);
		}
	}

	void addConsumableValue(String key, long gp, String rsn)
	{
		if (!isReadyFor(rsn) || key == null || key.isEmpty() || gp <= 0)
		{
			return;
		}
		revision++;
		synchronized (lock)
		{
			JsonObject store = sub(root, "consumable_values");
			long cur = present(store, key) ? store.get(key).getAsLong() : 0;
			store.addProperty(key, cur + gp);
			touch();
		}
	}

	@Override
	public void noteGathered(int itemId)
	{
		if (itemId <= 0 || !ready || gatheredItems.contains(itemId)
			|| gatheredItems.size() >= GATHERED_CAP)
		{
			return;
		}
		revision++;
		synchronized (lock)
		{
			if (root == null || currentRsn == null || !gatheredItems.add(itemId))
			{
				return;
			}
			JsonArray ids = arr(root, "gathered_items");
			ids.add(itemId);
			root.add("gathered_items", ids);
			touch();
		}
	}

	@Override
	public boolean wasGathered(int itemId)
	{
		return itemId > 0 && gatheredItems.contains(itemId);
	}

	long trackerBase(String key)
	{
		synchronized (lock)
		{
			return trackersBase != null && present(trackersBase, key)
				? trackersBase.get(key).getAsLong() : 0;
		}
	}

	private static final int SLAYER_TASK_CAP = 1000;

	private JsonObject slayerRoot()
	{
		JsonObject sl = sub(root, "slayer");
		if (!sl.has("tasks") || !sl.get("tasks").isJsonArray())
		{
			sl.add("tasks", new JsonArray());
		}
		return sl;
	}

	static final long SLAYER_FINAL_KILL_GRACE = 30;

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
		if (seg == null)
		{
			return null;
		}
		Long lastRem = optLong(seg, "last_rem");
		return rem != null && lastRem != null && rem > lastRem ? null : seg;
	}

	private static JsonObject graceSegment(JsonArray tasks, String task, Long rem, boolean live)
	{
		long floor = nowSec() - SLAYER_FINAL_KILL_GRACE;
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

	private void slayerLoot(JsonObject data, long value, String monster, JsonArray items)
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
			seg.addProperty("ts", nowSec());
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
		if (items != null)
		{
			JsonObject bag = sub(seg, "items");
			for (JsonElement ie : items)
			{
				JsonObject it = ie.getAsJsonObject();
				JsonObject row = sub(bag, it.get("name").getAsString());
				row.addProperty("id", it.get("id").getAsInt());
				bump(row, "qty", it.get("qty").getAsLong());
				bump(row, "value", it.get("value").getAsLong());
			}
		}
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
		if (logged != null)
		{
			return logged;
		}
		long kills = asLong(seg.get("kills"));
		long noLoot = asLong(seg.get("noLootKills"));
		return Math.max(0, kills - noLoot);
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

	private void recordSlayerCompletion(JsonObject data)
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
		synchronized (lock)
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
			seg.addProperty("ts", nowSec());
			long done = asLong(sl.get("completed"));
			sl.addProperty("completed", streak != null && streak > done ? streak : done + 1);
			touch();
		}
	}

	List<BagItem> onTaskLoot(long fromMs, long toMs, boolean includeOpen)
	{
		return onTaskLoot(fromMs, toMs, null, includeOpen);
	}

	List<BagItem> allLoot()
	{
		Map<String, long[]> summed = new LinkedHashMap<>();
		Map<String, Integer> ids = new LinkedHashMap<>();
		synchronized (lock)
		{
			JsonObject drops = obj(root, "drops");
			for (String s : drops.keySet())
			{
				for (var it : obj(obj(drops, s), "items").entrySet())
				{
					if (!it.getValue().isJsonObject())
					{
						continue;
					}
					JsonObject v = it.getValue().getAsJsonObject();
					String name = str(v, "name", null);
					if (name == null || name.isEmpty())
					{
						continue;
					}
					long[] tot = summed.computeIfAbsent(name, k -> new long[2]);
					tot[0] += asLong(v.get("qty"));
					tot[1] += asLong(v.get("value"));
					if (!ids.containsKey(name) && v.has("id"))
					{
						ids.put(name, (int) asLong(v.get("id")));
					}
				}
			}
		}
		return bagRows(summed, ids, -1);
	}

	private static List<BagItem> bagRows(Map<String, long[]> summed,
		Map<String, Integer> ids, int noId)
	{
		List<BagItem> out = new ArrayList<>();
		for (var e : summed.entrySet())
		{
			out.add(new BagItem(ids.getOrDefault(e.getKey(), noId), e.getKey(),
				e.getValue()[0], e.getValue()[1]));
		}
		out.sort((a, b) -> Long.compare(b.value, a.value));
		return out;
	}

	List<String> taskNames()
	{
		LinkedHashSet<String> names = new LinkedHashSet<>();
		synchronized (lock)
		{
			JsonArray arr = taskArray();
			for (int i = arr.size() - 1; i >= 0; i--)
			{
				if (arr.get(i).isJsonObject())
				{
					String n = taskName(arr.get(i).getAsJsonObject()).trim();
					if (!n.isEmpty())
					{
						names.add(n);
					}
				}
			}
		}
		return new ArrayList<>(names);
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
		for (JsonElement e : taskArray())
		{
			if (e.isJsonObject() && (includeOpen || !isOpen(e.getAsJsonObject()))
				&& taskInside(e.getAsJsonObject(), fromMs, toMs)
				&& (onlyTask == null || onlyTask.equalsIgnoreCase(taskName(e.getAsJsonObject()))))
			{
				out.add(e.getAsJsonObject());
			}
		}
		return out;
	}

	private JsonArray taskArray()
	{
		JsonObject sl = obj(root, "slayer");
		return arr(sl, "tasks");
	}

	Map<String, long[]> onTaskItems(long fromMs, long toMs)
	{
		Map<String, long[]> out = new LinkedHashMap<>();
		synchronized (lock)
		{
			for (JsonObject t : tasksIn(fromMs, toMs, null, true))
			{
				for (var it : obj(t, "items").entrySet())
				{
					if (!it.getValue().isJsonObject())
					{
						continue;
					}
					JsonObject v = it.getValue().getAsJsonObject();
					long[] tot = out.computeIfAbsent(it.getKey(), k -> new long[2]);
					tot[0] += asLong(v.get("qty"));
					tot[1] += asLong(v.get("value"));
				}
			}
		}
		return out;
	}

	Map<String, Long> onTaskKills(long fromMs, long toMs)
	{
		Map<String, Long> out = new LinkedHashMap<>();
		synchronized (lock)
		{
			for (JsonObject t : tasksIn(fromMs, toMs, null, true))
			{
				for (var m : obj(t, "monsters").entrySet())
				{
					out.merge(m.getKey(), asLong(m.getValue()), Long::sum);
				}
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
		synchronized (lock)
		{
			for (JsonObject t : tasksIn(fromMs, toMs, null, true))
			{
				for (var it : obj(t, "items").entrySet())
				{
					if (!it.getKey().equalsIgnoreCase(itemName)
						|| !it.getValue().isJsonObject())
					{
						continue;
					}
					JsonObject v = it.getValue().getAsJsonObject();
					long[] tot = by.computeIfAbsent(taskName(t), k -> new long[2]);
					tot[0] += asLong(v.get("qty"));
					tot[1] += asLong(v.get("value"));
				}
			}
		}
		List<Object[]> out = new ArrayList<>();
		for (var e : by.entrySet())
		{
			out.add(new Object[]{e.getKey(), e.getValue()[0], e.getValue()[1]});
		}
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
		synchronized (lock)
		{
			List<JsonObject> ts = tasksIn(fromMs, toMs, null, true);
			Collections.reverse(ts);
			for (JsonObject t : ts)
			{
				long here = 0;
				boolean found = false;
				for (var m : obj(t, "monsters").entrySet())
				{
					if (m.getKey().equalsIgnoreCase(npc))
					{
						here += asLong(m.getValue());
						found = true;
					}
				}
				if (!found)
				{
					continue;
				}
				out.add(new Assignment(taskName(t),
					(long) (asDouble(t.get("ts")) * 1000), here,
					asLong(t.get("kills")), asLong(t.get("value"))));
			}
		}
		return out;
	}

	List<BagItem> onTaskLoot(long fromMs, long toMs, String onlyTask,
		boolean includeOpen)
	{
		Map<String, long[]> summed = new LinkedHashMap<>();
		Map<String, Integer> ids = new LinkedHashMap<>();
		synchronized (lock)
		{
			for (JsonObject t : tasksIn(fromMs, toMs, onlyTask, includeOpen))
			{
				for (var it : obj(t, "items").entrySet())
				{
					if (!it.getValue().isJsonObject())
					{
						continue;
					}
					JsonObject v = it.getValue().getAsJsonObject();
					long[] tot = summed.computeIfAbsent(it.getKey(), k -> new long[2]);
					tot[0] += asLong(v.get("qty"));
					tot[1] += asLong(v.get("value"));
					if (!ids.containsKey(it.getKey()) && v.has("id"))
					{
						ids.put(it.getKey(), (int) asLong(v.get("id")));
					}
				}
			}
		}
		return bagRows(summed, ids, 0);
	}

	private static final Set<String> SUPERIORS = new HashSet<>();

	static
	{
		try (InputStream in = LocalStore.class.getResourceAsStream("/chronicle/store_superiors.json"))
		{
			for (JsonElement e : new com.google.gson.JsonParser().parse(
				new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray())
			{
				SUPERIORS.add(e.getAsString());
			}
		}
		catch (Exception e)
		{
			log.warn("superiors table unreadable", e);
		}
	}

	long[] onTaskTally(long fromMs, long toMs, boolean includeOpen)
	{
		return onTaskTally(fromMs, toMs, null, includeOpen);
	}

	long[] onTaskTally(long fromMs, long toMs, String onlyTask, boolean includeOpen)
	{
		long kills = 0;
		long superiors = 0;
		long tasks = 0;
		synchronized (lock)
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
		synchronized (lock)
		{
			if (root == null)
			{
				return null;
			}
			JsonObject sl = obj(root, "slayer");
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

	Map<String, Long> consumableValues()
	{
		Map<String, Long> out = new LinkedHashMap<>();
		synchronized (lock)
		{
			if (root != null)
			{
				HistoryLog.fill(root, "consumable_values", out);
			}
		}
		return out;
	}

	List<UntakenRow> untakenItems()
	{
		return untakenRows("untaken_items", false);
	}

	List<UntakenRow> untakenSources()
	{
		return untakenRows("untaken", true);
	}

	private List<UntakenRow> untakenRows(String key, boolean kills)
	{
		List<UntakenRow> out = new ArrayList<>();
		synchronized (lock)
		{
			for (var e : obj(root, key).entrySet())
			{
				if (!e.getValue().isJsonObject())
				{
					continue;
				}
				JsonObject it = e.getValue().getAsJsonObject();
				out.add(new UntakenRow(e.getKey(),
					it.has("qty") ? it.get("qty").getAsLong() : 0,
					it.has("value") ? it.get("value").getAsLong() : 0,
					kills ? asLong(it.get("kills")) : 0));
			}
		}
		return out;
	}

	long[] sessionUntakenTally()
	{
		synchronized (lock)
		{
			return new long[]{sessionUntaken, sessionUntakenValue};
		}
	}

	int sessionUntakenKills()
	{
		synchronized (lock)
		{
			return sessionUntakenKills;
		}
	}

	private void recordClogSlot(JsonObject data)
	{
		String name = str(data, "itemName", null);
		if (name == null || name.isEmpty())
		{
			return;
		}
		synchronized (lock)
		{
			JsonObject cl = sub(root, "collection_log");
			JsonObject items = sub(cl, "clog_items");
			boolean known = false;
			for (var e : items.entrySet())
			{
				if (e.getKey().equalsIgnoreCase(name))
				{
					known = true;
					break;
				}
			}
			if (!known)
			{
				items.addProperty(name, 1);
				long fin = cl.has("finished") ? cl.get("finished").getAsLong() : 0;
				cl.addProperty("finished", fin + 1);
			}
			touch();
		}
	}

	int[] clogFraction()
	{
		synchronized (lock)
		{
			JsonObject cl = obj(root, "collection_log");
			return new int[]{(int) asLong(cl.get("finished")), (int) asLong(cl.get("available"))};
		}
	}

	private static JsonObject mergeClog(JsonObject base, JsonObject inc)
	{
		JsonObject out = new JsonObject();
		out.add("by_cat", nested(base, inc, "by_cat", false));
		JsonObject kcLines = nested(base, inc, "kc_lines", false);
		if (kcLines.size() > 0)
		{
			out.add("kc_lines", kcLines);
		}
		JsonObject pbLines = nested(base, inc, "pb_lines", true);
		if (pbLines.size() > 0)
		{
			out.add("pb_lines", pbLines);
		}
		for (String mapKey : new String[]{"kcs", "clog_items", "cat_counts", "slayer_kcs"})
		{
			JsonObject merged = new JsonObject();
			for (JsonObject src : new JsonObject[]{base, inc})
			{
				if (src.has(mapKey) && src.get(mapKey).isJsonObject())
				{
					for (var e : src.getAsJsonObject(mapKey).entrySet())
					{
						raise(merged, e.getKey(), asLong(e.getValue()));
					}
				}
			}
			if (merged.size() > 0)
			{
				out.add(mapKey, merged);
			}
		}
		for (String numKey : new String[]{"finished", "available"})
		{
			out.addProperty(numKey, Math.max(asLong(base.get(numKey)), asLong(inc.get(numKey))));
		}
		return out;
	}

	private static JsonObject nested(JsonObject base, JsonObject inc, String key, boolean least)
	{
		JsonObject all = new JsonObject();
		for (JsonObject src : new JsonObject[]{base, inc})
		{
			if (!src.has(key) || !src.get(key).isJsonObject())
			{
				continue;
			}
			for (var pg : src.getAsJsonObject(key).entrySet())
			{
				if (!pg.getValue().isJsonObject())
				{
					continue;
				}
				JsonObject tgt = sub(all, pg.getKey());
				for (var ln : pg.getValue().getAsJsonObject().entrySet())
				{
					long n = asLong(ln.getValue());
					if (least ? n > 0 && (!tgt.has(ln.getKey()) || n < asLong(tgt.get(ln.getKey())))
						: n > asLong(tgt.get(ln.getKey())))
					{
						tgt.addProperty(ln.getKey(), n);
					}
				}
			}
		}
		return all;
	}

	private static long asLong(JsonElement e)
	{
		try
		{
			return e != null && !e.isJsonNull() ? e.getAsLong() : 0;
		}
		catch (RuntimeException ex)
		{
			return 0;
		}
	}

	private static double asDouble(JsonElement e)
	{
		try
		{
			return e != null && !e.isJsonNull() ? e.getAsDouble() : 0;
		}
		catch (RuntimeException ex)
		{
			return 0;
		}
	}

	private static String str(JsonObject o, String key, String def)
	{
		return present(o, key) ? o.get(key).getAsString() : def;
	}

	String importJournal(JsonObject in, String rsn)
	{
		if (!isReadyFor(rsn) || in == null)
		{
			return null;
		}
		int sources = 0;
		int events = 0;
		int counters = 0;
		synchronized (lock)
		{
			if (in.has("trackers") && in.get("trackers").isJsonObject())
			{
				JsonObject tr = sub(root, "trackers");
				for (var e : in.getAsJsonObject("trackers").entrySet())
				{
					long v = asLong(e.getValue());
					if (v <= 0)
					{
						continue;
					}
					if (v > asLong(trackersBase.get(e.getKey())))
					{
						trackersBase.addProperty(e.getKey(), v);
						counters++;
					}
					raise(tr, e.getKey(), v);
				}
			}
			if (in.has("drops") && in.get("drops").isJsonObject())
			{
				JsonObject drops = root.getAsJsonObject("drops");
				for (var e : in.getAsJsonObject("drops").entrySet())
				{
					if (!e.getValue().isJsonObject())
					{
						continue;
					}
					JsonObject inc = e.getValue().getAsJsonObject();
					JsonObject cur = drops.has(e.getKey()) && drops.get(e.getKey()).isJsonObject()
						? drops.getAsJsonObject(e.getKey()) : newSource();
					if (!drops.has(e.getKey()))
					{
						drops.add(e.getKey(), cur);
						sources++;
					}
					floorNumber(cur, inc, "kc");
					floorNumber(cur, inc, "loots");
					floorNumber(cur, inc, "value");
					floorNumber(cur, inc, "last_seen");
					if (present(inc, "first_seen"))
					{
						long incFirst = asLong(inc.get("first_seen"));
						long curFirst = asLong(cur.get("first_seen"));
						if (incFirst > 0 && (curFirst == 0 || incFirst < curFirst))
						{
							cur.addProperty("first_seen", incFirst);
						}
					}
					if (present(inc, "pb"))
					{
						double incPb = inc.get("pb").getAsDouble();
						if (incPb > 0 && (!cur.has("pb") || incPb < cur.get("pb").getAsDouble()))
						{
							cur.addProperty("pb", incPb);
						}
					}
					if (inc.has("items") && inc.get("items").isJsonObject())
					{
						JsonObject bag = cur.has("items") && cur.get("items").isJsonObject()
							? cur.getAsJsonObject("items") : new JsonObject();
						Map<String, String> byName = new HashMap<>();
						for (var be : bag.entrySet())
						{
							if (be.getValue().isJsonObject())
							{
								JsonObject b = be.getValue().getAsJsonObject();
								if (present(b, "name"))
								{
									byName.put(b.get("name").getAsString()
										.toLowerCase(Locale.ROOT), be.getKey());
								}
							}
						}
						for (var ie : inc.getAsJsonObject("items").entrySet())
						{
							if (!ie.getValue().isJsonObject())
							{
								continue;
							}
							JsonObject incItem = ie.getValue().getAsJsonObject();
							String incName = str(incItem, "name", ie.getKey());
							String key = byName.get(incName.toLowerCase(Locale.ROOT));
							if (key == null)
							{
								key = incItem.has("id") && incItem.get("id").getAsInt() > 0
									? bagKey(incItem.get("id").getAsInt(), incName)
									: ie.getKey();
							}
							JsonObject curItem = sub(bag, key);
							floorNumber(curItem, incItem, "qty");
							floorNumber(curItem, incItem, "value");
							if (!curItem.has("name"))
							{
								curItem.addProperty("name", incName);
							}
							if (!curItem.has("id") && incItem.has("id"))
							{
								curItem.add("id", incItem.get("id"));
							}
							byName.put(incName.toLowerCase(Locale.ROOT), key);
						}
						cur.add("items", bag);
					}
				}
				rebaseAnchors();
			}
			if (in.has("feed") && in.get("feed").isJsonArray())
			{
				JsonArray feed = root.getAsJsonArray("feed");
				Set<String> seen = new HashSet<>();
				for (JsonElement e : feed)
				{
					if (e.isJsonObject())
					{
						seen.add(feedKey(e.getAsJsonObject()));
					}
				}
				for (JsonElement e : in.getAsJsonArray("feed"))
				{
					if (!e.isJsonObject() || !seen.add(feedKey(e.getAsJsonObject())))
					{
						continue;
					}
					feed.add(e.getAsJsonObject().deepCopy());
					events++;
				}
				List<JsonObject> all = new ArrayList<>(feed.size());
				for (JsonElement e : feed)
				{
					if (e.isJsonObject())
					{
						all.add(e.getAsJsonObject());
					}
				}
				setFeed(all, FEED_CAP);
			}
			if (in.has("collection_log") && in.get("collection_log").isJsonObject())
			{
				root.add("collection_log", mergeClog(sub(root, "collection_log"), in.getAsJsonObject("collection_log")));
			}
			if (in.has("chat_kcs") && in.get("chat_kcs").isJsonObject())
			{
				JsonObject mine = sub(root, "chat_kcs");
				for (var e : in.getAsJsonObject("chat_kcs").entrySet())
				{
					raise(mine, e.getKey(), asLong(e.getValue()));
				}
			}
			if (in.has("gathered_items") && in.get("gathered_items").isJsonArray())
			{
				JsonArray have = arr(root, "gathered_items");
				Set<Integer> seen = new HashSet<>();
				for (JsonElement g : have)
				{
					seen.add(g.getAsInt());
				}
				for (JsonElement g : in.getAsJsonArray("gathered_items"))
				{
					try
					{
						int id = g.getAsInt();
						if (seen.add(id))
						{
							have.add(id);
							gatheredItems.add(id);
						}
					}
					catch (RuntimeException ignored)
					{
					}
				}
				root.add("gathered_items", have);
			}
			for (String store : new String[]{"untaken", "untaken_items", "consumable_values"})
			{
				floorNestedStore(store, in);
			}
			if (in.has("untaken_pairs") && in.get("untaken_pairs").isJsonObject())
			{
				JsonObject pairs = sub(root, "untaken_pairs");
				for (var e : in.getAsJsonObject("untaken_pairs").entrySet())
				{
					if (!e.getValue().isJsonObject())
					{
						continue;
					}
					JsonObject bag = sub(pairs, e.getKey());
					for (var ie : e.getValue().getAsJsonObject().entrySet())
					{
						if (!ie.getValue().isJsonObject())
						{
							continue;
						}
						JsonObject incItem = ie.getValue().getAsJsonObject();
						JsonObject curItem = sub(bag, ie.getKey());
						floorNumber(curItem, incItem, "qty");
						floorNumber(curItem, incItem, "value");
						if (!curItem.has("id") && incItem.has("id"))
						{
							curItem.add("id", incItem.get("id"));
						}
					}
				}
			}
			if (in.has("slayer") && in.get("slayer").isJsonObject())
			{
				JsonObject incSl = in.getAsJsonObject("slayer");
				JsonObject sl = slayerRoot();
				JsonArray tasks = sl.getAsJsonArray("tasks");
				if (tasks.size() == 0 && incSl.has("tasks") && incSl.get("tasks").isJsonArray())
				{
					for (JsonElement t : incSl.getAsJsonArray("tasks"))
					{
						if (t.isJsonObject())
						{
							tasks.add(t.getAsJsonObject().deepCopy());
						}
					}
					while (tasks.size() > SLAYER_TASK_CAP)
					{
						tasks.remove(0);
					}
				}
				else if (incSl.has("tasks") && incSl.get("tasks").isJsonArray())
				{
					for (JsonElement t : incSl.getAsJsonArray("tasks"))
					{
						if (!t.isJsonObject())
						{
							continue;
						}
						JsonObject incSeg = t.getAsJsonObject();
						JsonObject seg = nearestSegment(tasks, incSeg);
						if (seg == null)
						{
							continue;
						}
						mergeSegmentDetail(seg, incSeg, "monsters");
						mergeSegmentDetail(seg, incSeg, "items");
					}
				}
				for (String k : new String[]{"completed", "xp_est"})
				{
					if (incSl.has(k))
					{
						raise(sl, k, asLong(incSl.get(k)));
					}
				}
			}
			dedupeSourceBags();
			dedupeFeed();
			touch();
		}
		return sources + " sources · " + String.format(Locale.UK, "%,d", events)
			+ " journal entries · " + counters + " counters";
	}

	private static final long SEGMENT_MATCH_SECONDS = 60;

	private static JsonObject nearestSegment(JsonArray tasks, JsonObject inc)
	{
		String task = inc.has("task") ? inc.get("task").getAsString() : null;
		if (task == null)
		{
			return null;
		}
		long ts = asLong(inc.get("ts"));
		JsonObject best = null;
		long bestGap = Long.MAX_VALUE;
		for (JsonElement e : tasks)
		{
			if (!e.isJsonObject())
			{
				continue;
			}
			JsonObject seg = e.getAsJsonObject();
			if (!seg.has("task") || !task.equalsIgnoreCase(seg.get("task").getAsString()))
			{
				continue;
			}
			long gap = Math.abs(asLong(seg.get("ts")) - ts);
			if (gap <= SEGMENT_MATCH_SECONDS && gap < bestGap)
			{
				bestGap = gap;
				best = seg;
			}
		}
		return best;
	}

	private static void mergeSegmentDetail(JsonObject seg, JsonObject inc, String key)
	{
		if (!inc.has(key) || !inc.get(key).isJsonObject())
		{
			return;
		}
		JsonObject cur = sub(seg, key);
		for (var e : inc.getAsJsonObject(key).entrySet())
		{
			if (e.getValue().isJsonObject())
			{
				JsonObject incRow = e.getValue().getAsJsonObject();
				JsonObject curRow = sub(cur, e.getKey());
				floorNumber(curRow, incRow, "qty");
				if (asLong(curRow.get("value")) <= 0)
				{
					floorNumber(curRow, incRow, "value");
				}
				if (!curRow.has("id") && incRow.has("id"))
				{
					curRow.add("id", incRow.get("id"));
				}
			}
			else
			{
				raise(cur, e.getKey(), asLong(e.getValue()));
			}
		}
	}

	private static String feedKey(JsonObject e)
	{
		long sec = asLong(e.get("ts")) / 1000L;
		String kind = str(e, "type", "");
		return kind + "|" + sec + "|" + feedSubject(e);
	}

	private static String feedSubject(JsonObject e)
	{
		if (!e.has("data") || !e.get("data").isJsonObject())
		{
			return "";
		}
		JsonObject d = e.getAsJsonObject("data");
		for (String field : new String[]{"itemName", "petName", "questName",
			"killerName", "area", "skill", "task", "monster",
			"name", "quest", "diary", "achievement"})
		{
			if (present(d, field))
			{
				return d.get(field).getAsString().toLowerCase(Locale.ROOT);
			}
		}
		JsonObject bare = d.deepCopy();
		bare.remove("imported");
		bare.remove("type");
		return bare.toString();
	}

	private int dedupeFeed()
	{
		JsonArray feed = arr(root, "feed");
		Map<String, JsonObject> best = new LinkedHashMap<>();
		int absorbed = 0;
		for (JsonElement e : feed)
		{
			if (!e.isJsonObject())
			{
				continue;
			}
			JsonObject o = e.getAsJsonObject();
			String key = feedKey(o);
			JsonObject held = best.get(key);
			if (held == null)
			{
				best.put(key, o);
				continue;
			}
			absorbed++;
			if (payloadSize(o) > payloadSize(held))
			{
				best.put(key, o);
			}
		}
		if (absorbed > 0)
		{
			setFeed(new ArrayList<>(best.values()), Integer.MAX_VALUE);
		}
		return absorbed;
	}

	private void setFeed(List<JsonObject> all, int cap)
	{
		all.sort(Comparator.comparingLong(o -> asLong(o.get("ts"))));
		JsonArray rebuilt = new JsonArray();
		for (int i = Math.max(0, all.size() - cap); i < all.size(); i++)
		{
			rebuilt.add(all.get(i));
		}
		root.add("feed", rebuilt);
	}

	private static int payloadSize(JsonObject e)
	{
		return e.has("data") && e.get("data").isJsonObject()
			? e.getAsJsonObject("data").size() : 0;
	}

	private static void floorNumber(JsonObject cur, JsonObject inc, String key)
	{
		if (present(inc, key))
		{
			raise(cur, key, asLong(inc.get(key)));
		}
	}

	private static void raise(JsonObject cur, String key, long v)
	{
		if (v > asLong(cur.get(key)))
		{
			cur.addProperty(key, v);
		}
	}

	private void floorNestedStore(String name, JsonObject in)
	{
		if (!in.has(name) || !in.get(name).isJsonObject())
		{
			return;
		}
		JsonObject cur = sub(root, name);
		for (var e : in.getAsJsonObject(name).entrySet())
		{
			if (e.getValue().isJsonObject())
			{
				JsonObject incRow = e.getValue().getAsJsonObject();
				JsonObject curRow = sub(cur, e.getKey());
				floorNumber(curRow, incRow, "qty");
				floorNumber(curRow, incRow, "value");
				floorNumber(curRow, incRow, "kills");
			}
			else
			{
				raise(cur, e.getKey(), asLong(e.getValue()));
			}
		}
	}

	List<BagItem> untakenItemsOf(String source)
	{
		synchronized (lock)
		{
			return bagOf(obj(obj(root, "untaken_pairs"), source));
		}
	}

	List<UntakenRow> untakenSourcesOf(String item)
	{
		List<UntakenRow> out = new ArrayList<>();
		synchronized (lock)
		{
			for (var e : obj(root, "untaken_pairs").entrySet())
			{
				if (!e.getValue().isJsonObject())
				{
					continue;
				}
				JsonObject bag = e.getValue().getAsJsonObject();
				if (bag.has(item) && bag.get(item).isJsonObject())
				{
					JsonObject r = bag.getAsJsonObject(item);
					out.add(new UntakenRow(e.getKey(), asLong(r.get("qty")), asLong(r.get("value"))));
				}
			}
		}
		out.sort((a, b) -> Long.compare(b.value, a.value));
		return out;
	}

	List<BagItem> slayerTaskItems(int index)
	{
		return bagOf(obj(segmentAt(index), "items"));
	}

	private static List<BagItem> bagOf(JsonObject bag)
	{
		List<BagItem> out = new ArrayList<>();
		for (var e : bag.entrySet())
		{
			JsonObject r = e.getValue().getAsJsonObject();
			out.add(new BagItem(r.has("id") ? r.get("id").getAsInt() : -1, e.getKey(),
				asLong(r.get("qty")), asLong(r.get("value"))));
		}
		out.sort((a, b) -> Long.compare(b.value, a.value));
		return out;
	}

	List<UntakenRow> slayerTaskMonsters(int index)
	{
		List<UntakenRow> out = new ArrayList<>();
		JsonObject seg = segmentAt(index);
		if (seg != null && seg.has("monsters") && seg.get("monsters").isJsonObject())
		{
			for (var e : seg.getAsJsonObject("monsters").entrySet())
			{
				out.add(new UntakenRow(e.getKey(), asLong(e.getValue()), 0));
			}
		}
		out.sort((a, b) -> Long.compare(b.qty, a.qty));
		return out;
	}

	private JsonObject segmentAt(int index)
	{
		synchronized (lock)
		{
			JsonArray tasks = taskArray();
			int at = tasks.size() - 1 - index;
			return at >= 0 && at < tasks.size() && tasks.get(at).isJsonObject()
				? tasks.get(at).getAsJsonObject() : null;
		}
	}

	List<PetRow> pets()
	{
		List<PetRow> out = new ArrayList<>();
		synchronized (lock)
		{
			Set<String> seen = new HashSet<>();
			JsonArray feed = arr(root, "feed");
			for (int i = feed.size() - 1; i >= 0; i--)
			{
				if (!feed.get(i).isJsonObject())
				{
					continue;
				}
				JsonObject e = feed.get(i).getAsJsonObject();
				JsonObject d = obj(e, "data");
				if (!"PET".equals(e.has("type") ? e.get("type").getAsString() : ""))
				{
					continue;
				}
				String name = str(d, "petName", null);
				if (name == null || name.isEmpty() || !seen.add(name.toLowerCase(Locale.ROOT)))
				{
					continue;
				}
				out.add(new PetRow(name,
					str(d, "source", null),
					asLong(d.get("killCount")),
					asLong(e.get("ts"))));
			}
		}
		out.sort((a, b) -> Long.compare(b.ts, a.ts));
		return out;
	}

	@AllArgsConstructor(access = AccessLevel.PACKAGE)
	static final class PetRow
	{
		final String name;
		final String source;
		final long kc;
		final long ts;
	}

	private int reconcileUntaken()
	{
		JsonObject pairs = obj(root, "untaken_pairs");
		if (pairs.size() == 0 || !root.has("untaken_items") || !root.get("untaken_items").isJsonObject())
		{
			return 0;
		}
		for (var se : obj(root, "untaken").entrySet())
		{
			if (!pairs.has(se.getKey()))
			{
				return 0;
			}
		}
		JsonObject rebuilt = new JsonObject();
		for (var pe : pairs.entrySet())
		{
			if (!pe.getValue().isJsonObject())
			{
				continue;
			}
			for (var ie : pe.getValue().getAsJsonObject().entrySet())
			{
				if (!ie.getValue().isJsonObject())
				{
					continue;
				}
				JsonObject inc = ie.getValue().getAsJsonObject();
				JsonObject cur = sub(rebuilt, ie.getKey());
				bump(cur, "qty", asLong(inc.get("qty")));
				bump(cur, "value", asLong(inc.get("value")));
			}
		}
		JsonObject was = root.getAsJsonObject("untaken_items");
		int corrected = 0;
		for (var e : rebuilt.entrySet())
		{
			JsonElement before = was.get(e.getKey());
			if (before == null || !before.equals(e.getValue()))
			{
				corrected++;
			}
		}
		for (var e : was.entrySet())
		{
			if (!rebuilt.has(e.getKey()))
			{
				corrected++;
			}
		}
		if (corrected > 0)
		{
			root.add("untaken_items", rebuilt);
		}
		return corrected;
	}

	private int purgeOffTaskMonsters()
	{
		int changed = 0;
		for (JsonElement te : taskArray())
		{
			if (!te.isJsonObject())
			{
				continue;
			}
			JsonObject seg = te.getAsJsonObject();
			if (!isOpen(seg) || !present(seg, "task")
				|| !seg.has("monsters") || !seg.get("monsters").isJsonObject())
			{
				continue;
			}
			String task = seg.get("task").getAsString();
			JsonObject mons = seg.getAsJsonObject("monsters");
			long offTask = 0;
			List<String> drop = new ArrayList<>();
			for (var me : mons.entrySet())
			{
				if (!SlayerTaskBook.onTask(me.getKey(), SlayerTaskBook.UNKNOWN_ID, task))
				{
					drop.add(me.getKey());
					offTask += asLong(me.getValue());
				}
			}
			if (drop.isEmpty())
			{
				continue;
			}
			for (String name : drop)
			{
				mons.remove(name);
			}
			long kills = asLong(seg.get("kills"));
			seg.addProperty("kills", Math.max(0, kills - offTask));
			changed++;
		}
		return changed;
	}

	private int dedupeSourceBags()
	{
		JsonObject drops = obj(root, "drops");
		int absorbed = 0;
		for (String s : drops.keySet())
		{
			JsonObject bag = obj(obj(drops, s), "items");
			Map<String, String> keep = new HashMap<>();
			List<String> drop = new ArrayList<>();
			for (var ie : bag.entrySet())
			{
				if (!ie.getValue().isJsonObject())
				{
					continue;
				}
				JsonObject it = ie.getValue().getAsJsonObject();
				String name = str(it, "name", ie.getKey()).toLowerCase(Locale.ROOT);
				String held = keep.get(name);
				if (held == null)
				{
					keep.put(name, ie.getKey());
					continue;
				}
				String winner = held;
				String loser = ie.getKey();
				if (!isNumeric(held) && isNumeric(ie.getKey()))
				{
					winner = ie.getKey();
					loser = held;
					keep.put(name, winner);
				}
				JsonObject w = bag.getAsJsonObject(winner);
				JsonObject l = bag.getAsJsonObject(loser);
				floorNumber(w, l, "qty");
				floorNumber(w, l, "value");
				if (!w.has("name") && l.has("name"))
				{
					w.add("name", l.get("name"));
				}
				drop.add(loser);
			}
			for (String k : drop)
			{
				bag.remove(k);
				absorbed++;
			}
		}
		return absorbed;
	}

	private static final Pattern KEY_LEVEL =
		Pattern.compile("\\(level[\\s-]*\\d*\\)?",
			Pattern.CASE_INSENSITIVE);

	private int repairTrackerKeys()
	{
		JsonObject tr = obj(root, "trackers");
		int folded = 0;
		for (var e : new ArrayList<>(tr.entrySet()))
		{
			String key = e.getKey();
			String into;
			if (key.startsWith("__"))
			{
				into = "";
			}
			else if (key.equals("logsLogsChopped"))
			{
				into = "normalLogsChopped";
			}
			else if (key.contains("(level"))
			{
				into = KEY_LEVEL.matcher(key).replaceAll("");
			}
			else
			{
				continue;
			}
			long v = asLong(e.getValue());
			tr.remove(key);
			if (!into.isEmpty() && !into.equals(key))
			{
				tr.addProperty(into, asLong(tr.get(into)) + v);
			}
			folded++;
		}
		return folded;
	}

	private static boolean isNumeric(String s)
	{
		return s != null && !s.isEmpty() && s.chars().allMatch(Character::isDigit);
	}

	int combatLevel()
	{
		synchronized (lock)
		{
			return root != null ? (int) asLong(root.get("combat_level")) : 0;
		}
	}

	Map<String, long[]> skillSheet()
	{
		Map<String, long[]> out = new LinkedHashMap<>();
		synchronized (lock)
		{
			for (var e : obj(root, "skills").entrySet())
			{
				if (!e.getValue().isJsonObject())
				{
					continue;
				}
				JsonObject o = e.getValue().getAsJsonObject();
				out.put(e.getKey(), new long[]{
					asLong(o.get("level")),
					asLong(o.get("xp"))});
			}
		}
		return out;
	}

	JsonObject achievements()
	{
		synchronized (lock)
		{
			return obj(root, "achievements").deepCopy();
		}
	}

	Map<String, Long> spineExtras()
	{
		long loots = 0;
		long value = 0;
		List<SourceRow> sources = dropSources();
		for (SourceRow r : sources)
		{
			loots += r.loots;
			value += r.value;
		}
		JsonObject cl = clogSnapshot();
		Map<String, Long> reconciled =
			reconciledKills(cl, sources, chatKillCounts(), anchoredKills());
		long kills = spineKills(cl, sources, reconciled, KILLS_VERSION);
		long left = 0;
		long leftValue = 0;
		long leftKills = 0;
		for (UntakenRow u : untakenSources())
		{
			left += u.qty;
			leftValue += u.value;
			leftKills += u.kills;
		}
		SlayerJourney journey = slayerJourney();
		int finished = clogFraction()[0];
		Map<String, Long> out = new LinkedHashMap<>();
		out.put("dropsReceived", loots);
		out.put("lootValue", value);
		out.put("lootLeftCount", left);
		out.put("lootLeftValue", leftValue);
		out.put("lootLeftKills", leftKills);
		out.put("kills", kills);
		out.put("slayerTasksCompleted", journey != null ? journey.completedTasks : 0L);
		out.put("clogSlotsObtained", (long) (finished > 0 ? finished : obtainedSlots(cl)));
		return out;
	}

	Map<String, Long> spineCounters()
	{
		Map<String, Long> out = trackersSnapshot();
		out.putAll(spineExtras());
		return out;
	}

	static Map<String, Long> reconciledKills(JsonObject clog,
		List<SourceRow> sources, Map<String, Long> chat,
		Map<String, Long> anchored)
	{
		return reconciledKills(clog, sources, chat, anchored, KILLS_VERSION);
	}

	static Map<String, Long> reconciledKills(JsonObject clog,
		List<SourceRow> sources, Map<String, Long> chat,
		Map<String, Long> anchored, int version)
	{
		Map<String, Long> out = clogKillCounts(clog);
		Map<String, Long> stated = killLogCounts(clog);
		foldChatCounts(stated, chat, out.keySet());
		placeByKind(out, stated, false);
		if (version < 1)
		{
			placeByKind(out, sourceKills(clog, sources), true);
			placeByKind(out, anchored, false);
			return out;
		}
		placeByKind(out, pageKillLines(clog), true);
		placeByKind(out, ledgerKills(clog, sources), true);
		placeByKind(out, anchored, false);
		return out;
	}

	static Map<String, Long> sourceKills(JsonObject clog,
		List<SourceRow> sources)
	{
		return sourceKills(clog, sources, true);
	}

	static long spineKills(JsonObject clog, List<SourceRow> sources,
		Map<String, Long> reconciled, int version)
	{
		long kills = 0;
		if (version < 1)
		{
			for (long k : sourceKills(clog, sources).values())
			{
				kills += k;
			}
			return kills;
		}
		for (String key : spineKillKeys(clog, sources, reconciled))
		{
			kills += reconciled.getOrDefault(key, 0L);
		}
		return kills;
	}

	static Set<String> spineKillKeys(JsonObject clog, List<SourceRow> sources,
		Map<String, Long> reconciled)
	{
		Map<String, String> byKind = new HashMap<>();
		for (String key : reconciled.keySet())
		{
			byKind.putIfAbsent(chatKind(key), key);
		}
		Set<String> out = new LinkedHashSet<>();
		for (String name : sourceKills(clog, sources).keySet())
		{
			String key = reconciled.containsKey(name) ? name : byKind.get(chatKind(name));
			if (key != null)
			{
				out.add(key);
			}
		}
		return out;
	}

	HistoryLog.Adjust definitionShift(int from)
	{
		HistoryLog.Adjust adj = new HistoryLog.Adjust();
		if (from >= KILLS_VERSION)
		{
			return adj;
		}
		JsonObject cl = clogSnapshot();
		List<SourceRow> sources = dropSources();
		Map<String, Long> chat = chatKillCounts();
		Map<String, Long> anchored = anchoredKills();
		Map<String, Long> was = reconciledKills(cl, sources, chat, anchored, from);
		Map<String, Long> now = reconciledKills(cl, sources, chat, anchored, KILLS_VERSION);
		for (var e : now.entrySet())
		{
			Long w = was.get(e.getKey());
			if (w != null && w.longValue() != e.getValue())
			{
				adj.kcs.put(e.getKey(), e.getValue() - w);
			}
		}
		long sum = spineKills(cl, sources, now, KILLS_VERSION) - spineKills(cl, sources, was, from);
		if (sum != 0)
		{
			adj.counters.put("kills", sum);
		}
		return adj;
	}

	private void settleKillsVersion(File dir, String rsn)
	{
		Integer newest = HistoryLog.newestKv(gson, dir, rsn);
		if (newest == null)
		{
			return;
		}
		int from = newest;
		synchronized (lock)
		{
			if (root.has(SPINE_ADJ_KV) && root.has(SPINE_ADJ))
			{
				from = Math.max(from, (int) asLong(root.get(SPINE_ADJ_KV)));
			}
			if (from >= KILLS_VERSION)
			{
				return;
			}
			addPendingAdjust(definitionShift(from));
			if (hasPendingAdjust())
			{
				root.addProperty(SPINE_ADJ_KV, KILLS_VERSION);
			}
			freshAdjust = false;
		}
	}

	static Map<String, Long> ledgerKills(JsonObject clog,
		List<SourceRow> sources)
	{
		return sourceKills(clog, sources, false);
	}

	private static Map<String, Long> sourceKills(JsonObject clog,
		List<SourceRow> sources, boolean raiseToPage)
	{
		Map<String, Long> paged = clogKillCounts(clog);
		Map<String, String> byKind = new HashMap<>();
		for (String name : paged.keySet())
		{
			byKind.put(kindOf(name), name);
		}
		Map<String, Long> stated = killLogCounts(clog);
		Map<String, Long> statedByKind = new HashMap<>();
		for (var e : stated.entrySet())
		{
			statedByKind.putIfAbsent(kindOf(e.getKey()), e.getValue());
		}
		Map<String, Long> out = new LinkedHashMap<>();
		for (SourceRow r : sources)
		{
			long kills = Math.max(r.kc, r.loots);
			Long agreed = r.kc > 0 ? statedByKind.get(kindOf(r.name)) : null;
			if (agreed != null && agreed.longValue() == r.kc && r.loots > r.kc)
			{
				kills = r.kc;
			}
			String name = r.name;
			String known = byKind.get(kindOf(r.name));
			if (known != null)
			{
				if (raiseToPage)
				{
					kills = Math.max(kills, paged.get(known));
				}
				name = known;
			}
			if (kills > 0)
			{
				out.merge(name, kills, Math::max);
			}
		}
		return out;
	}

	private static int anchorRank(String src)
	{
		return "chat".equals(src) ? 3 : "log".equals(src) ? 2 : 1;
	}

	void anchorKill(String name, long stated, String src, String rsn)
	{
		if (name == null || name.isEmpty() || stated <= 0 || !isReadyFor(rsn))
		{
			return;
		}
		synchronized (lock)
		{
			if (root == null)
			{
				return;
			}
			JsonObject all = sub(root, "kc_anchors");
			JsonObject was = all.has(name) && all.get(name).isJsonObject()
				? all.getAsJsonObject(name) : null;
			if (was != null)
			{
				int had = anchorRank(was.has("src") ? was.get("src").getAsString() : "page");
				if (anchorRank(src) < had)
				{
					return;
				}
				if (anchorRank(src) == had && asLong(was.get("n")) == stated)
				{
					return;
				}
			}
			JsonObject a = new JsonObject();
			a.addProperty("n", stated);
			a.addProperty("ts", System.currentTimeMillis());
			a.addProperty("src", src);
			a.addProperty("obs", observedFor(name));
			all.add(name, a);
			touch();
		}
	}

	private long observedFor(String name)
	{
		String kind = kindOf(name);
		long best = 0;
		for (var e : obj(root, "drops").entrySet())
		{
			if (!e.getValue().isJsonObject() || !kindOf(e.getKey()).equals(kind))
			{
				continue;
			}
			best = Math.max(best, asLong(e.getValue().getAsJsonObject().get("loots")));
		}
		return best;
	}

	private void absorbLaggingKill(String source, Integer stated)
	{
		if (stated == null)
		{
			return;
		}
		String kind = kindOf(source);
		for (var e : obj(root, "kc_anchors").entrySet())
		{
			if (!e.getValue().isJsonObject() || !kindOf(e.getKey()).equals(kind))
			{
				continue;
			}
			JsonObject a = e.getValue().getAsJsonObject();
			if (asLong(a.get("n")) == stated.longValue())
			{
				a.addProperty("obs", observedFor(e.getKey()));
			}
		}
	}

	Map<String, Long> anchoredKills()
	{
		Map<String, Long> out = new LinkedHashMap<>();
		synchronized (lock)
		{
			for (var e : obj(root, "kc_anchors").entrySet())
			{
				if (!e.getValue().isJsonObject())
				{
					continue;
				}
				JsonObject a = e.getValue().getAsJsonObject();
				long n = asLong(a.get("n"));
				if (n <= 0)
				{
					continue;
				}
				long since = Math.max(0, observedFor(e.getKey()) - asLong(a.get("obs")));
				out.put(e.getKey(), n + since);
			}
		}
		return out;
	}

	private void rebaseAnchors()
	{
		for (var e : obj(root, "kc_anchors").entrySet())
		{
			if (e.getValue().isJsonObject())
			{
				e.getValue().getAsJsonObject().addProperty("obs", observedFor(e.getKey()));
			}
		}
	}

	void noteKillCount(String subject, int tally, String rsn)
	{
		if (subject == null || subject.isEmpty() || tally <= 0 || !isReadyFor(rsn))
		{
			return;
		}
		synchronized (lock)
		{
			if (root == null)
			{
				return;
			}
			JsonObject known = obj(root, "chat_kcs");
			if (known.has(subject) && asLong(known.get(subject)) >= tally)
			{
				return;
			}
			Counts before = countsNow();
			revision++;
			sub(root, "chat_kcs").addProperty(subject, tally);
			touch();
			anchorKill(subject, tally, "chat", rsn);
			noteArrival(before, 1);
		}
	}

	Map<String, Long> chatKillCounts()
	{
		synchronized (lock)
		{
			return positives(root, "chat_kcs");
		}
	}

	static Map<String, Long> killLogCounts(JsonObject clog)
	{
		return positives(clog, "slayer_kcs");
	}

	private static Map<String, Long> positives(JsonObject o, String key)
	{
		Map<String, Long> out = new LinkedHashMap<>();
		for (var e : obj(o, key).entrySet())
		{
			long v = asLong(e.getValue());
			if (v > 0)
			{
				out.put(e.getKey(), v);
			}
		}
		return out;
	}

	static Map<String, Long> clogKillCounts(JsonObject clog)
	{
		Map<String, Long> out = positives(clog, "kcs");
		if (clog != null && clog.has("kcs") && clog.get("kcs").isJsonObject())
		{
			out.putAll(pageKillLines(clog));
		}
		return out;
	}

	static Map<String, Long> pageKillLines(JsonObject clog)
	{
		Map<String, Long> out = new LinkedHashMap<>();
		for (var pg : obj(clog, "kc_lines").entrySet())
		{
			if (!pg.getValue().isJsonObject())
			{
				continue;
			}
			for (var ln : pg.getValue().getAsJsonObject().entrySet())
			{
				String label = ln.getKey().toLowerCase(Locale.ROOT);
				if (!label.contains("kill") && !label.contains("completion"))
				{
					continue;
				}
				long v = asLong(ln.getValue());
				if (v > 0)
				{
					out.put(pg.getKey(), v);
					break;
				}
			}
		}
		return out;
	}

	static void foldChatCounts(Map<String, Long> out,
		Map<String, Long> chat, Set<String> vocabulary)
	{
		if (out == null || chat == null || chat.isEmpty())
		{
			return;
		}
		Map<String, String> byKind = new HashMap<>();
		for (String name : out.keySet())
		{
			byKind.putIfAbsent(chatKind(name), name);
		}
		for (String name : vocabulary)
		{
			byKind.putIfAbsent(chatKind(name), name);
		}
		for (var e : chat.entrySet())
		{
			String said = e.getKey();
			String known = byKind.get(chatKind(said));
			if (known == null)
			{
				int space = said.indexOf(' ');
				if (space > 0)
				{
					known = byKind.get(chatKind(said.substring(space + 1)));
				}
			}
			if (known == null)
			{
				known = byKind.get(chatKind(said + " chests"));
			}
			out.merge(known != null ? known : said, e.getValue(), Math::max);
		}
	}

	static void placeByKind(Map<String, Long> out,
		Map<String, Long> stated, boolean floor)
	{
		if (out == null || stated == null || stated.isEmpty())
		{
			return;
		}
		Map<String, String> byKind = new HashMap<>();
		for (String name : out.keySet())
		{
			byKind.putIfAbsent(chatKind(name), name);
		}
		for (var e : stated.entrySet())
		{
			String known = byKind.get(chatKind(e.getKey()));
			String name = known != null ? known : e.getKey();
			if (floor)
			{
				out.merge(name, e.getValue(), Math::max);
			}
			else
			{
				out.put(name, e.getValue());
			}
			byKind.putIfAbsent(chatKind(name), name);
		}
	}

	static String chatKind(String name)
	{
		String n = kindOf(name);
		return n.startsWith("the ") ? n.substring(4) : n;
	}

	static String kindOf(String name)
	{
		String n = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
		if (n.endsWith("ies"))
		{
			return n.substring(0, n.length() - 3) + "y";
		}
		return n.endsWith("s") ? n.substring(0, n.length() - 1) : n;
	}

	static int obtainedSlots(JsonObject cl)
	{
		Set<String> names = new HashSet<>();
		for (var e : obj(cl, "clog_items").entrySet())
		{
			names.add(e.getKey().toLowerCase(Locale.ROOT));
		}
		for (var pg : obj(cl, "by_cat").entrySet())
		{
			if (!pg.getValue().isJsonObject())
			{
				continue;
			}
			for (var it : pg.getValue().getAsJsonObject().entrySet())
			{
				names.add(it.getKey().toLowerCase(Locale.ROOT));
			}
		}
		return names.size();
	}

	JsonObject clogSnapshot()
	{
		synchronized (lock)
		{
			return obj(root, "collection_log").deepCopy();
		}
	}

	static boolean migrateJournalFiles(File dir, String oldName, String newName)
	{
		String oldSlug = slug(oldName);
		String newSlug = slug(newName);
		if (oldSlug.equals(newSlug))
		{
			return false;
		}
		File journal = new File(dir, oldSlug + ".json");
		if (!journal.isFile())
		{
			return false;
		}
		File target = new File(dir, newSlug + ".json");
		if (target.exists() && !setAside(target, "conflict"))
		{
			return false;
		}
		if (!journal.renameTo(target))
		{
			log.warn("journal rename failed: {} -> {}", journal, target);
			return false;
		}
		File history = new File(dir, oldSlug + HistoryLog.SPINE_SUFFIX);
		File historyTarget = new File(dir, newSlug + HistoryLog.SPINE_SUFFIX);
		if (history.isFile() && (!historyTarget.exists() || setAside(historyTarget, "conflict"))
			&& !history.renameTo(historyTarget))
		{
			log.warn("history rename failed: {} -> {}", history, historyTarget);
		}
		return true;
	}

	private static boolean setAside(File f, String tag)
	{
		File aside = new File(f.getParentFile(),
			f.getName() + "." + tag + "-" + System.currentTimeMillis());
		try
		{
			Files.move(f.toPath(), aside.toPath(), StandardCopyOption.REPLACE_EXISTING);
			log.warn("kept {} as {}", f.getName(), aside.getName());
			return true;
		}
		catch (Exception e)
		{
			log.warn("could not set aside {}", f, e);
			return false;
		}
	}

	static String slug(String rsn)
	{
		String s = rsn == null ? ""
			: rsn.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
		s = s.replaceAll("(^-+|-+$)", "");
		return s.isEmpty() ? "profile" : s;
	}

	Map<String, Long> journalFacts()
	{
		Map<String, Long> out = new LinkedHashMap<>();
		synchronized (lock)
		{
			if (root == null)
			{
				return out;
			}
			out.put("schema", asLong(root.get("schema")));
			JsonObject drops = obj(root, "drops");
			long rows = 0;
			long loots = 0;
			long worth = 0;
			for (var e : drops.entrySet())
			{
				if (!e.getValue().isJsonObject())
				{
					continue;
				}
				JsonObject o = e.getValue().getAsJsonObject();
				loots += asLong(o.get("loots"));
				worth += asLong(o.get("value"));
				rows += size(o, "items");
			}
			out.put("sources", (long) drops.size());
			out.put("itemRows", rows);
			out.put("lootEvents", loots);
			out.put("lootWorth", worth);
			out.put("lootDays", (long) size(root, "loot_days"));
			out.put("untakenSources", (long) size(root, "untaken"));
			out.put("untakenItems", (long) size(root, "untaken_items"));

			JsonObject sl = obj(root, "slayer");
			out.put("tasks", (long) arr(sl, "tasks").size());
			out.put("tasksClosed", asLong(sl.get("completed")));

			JsonObject cl = obj(root, "collection_log");
			out.put("clogSlots", asLong(cl.get("finished")));
			out.put("clogAvailable", asLong(cl.get("available")));
			out.put("clogItems", (long) size(cl, "clog_items"));
			out.put("clogPages", (long) size(cl, "kcs"));
			out.put("killLogLines", (long) size(cl, "slayer_kcs"));
			out.put("pageKillLines", (long) size(cl, "kc_lines"));

			out.put("trackers", (long) size(root, "trackers"));
			out.put("skills", (long) size(root, "skills"));
			out.put("feed", (long) arr(root, "feed").size());
			out.put("chatCounts", (long) size(root, "chat_kcs"));
			out.put("anchors", (long) size(root, "kc_anchors"));
		}
		File f = mountedDir == null || currentRsn == null
			? null : jsonPath(mountedDir, currentRsn);
		out.put("journalBytes", f != null && f.isFile() ? f.length() : 0L);
		File spine = mountedDir == null || currentRsn == null ? null
			: new File(mountedDir, slug(currentRsn) + HistoryLog.SPINE_SUFFIX);
		out.put("spineBytes", spine != null && spine.isFile() ? spine.length() : 0L);
		return out;
	}

	private static JsonArray arr(JsonObject o, String key)
	{
		return o != null && o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : new JsonArray();
	}

	private static int size(JsonObject o, String key)
	{
		return o.has(key) && o.get(key).isJsonObject()
			? o.getAsJsonObject(key).size() : 0;
	}

	private static File jsonPath(File dir, String rsn)
	{
		return new File(dir, slug(rsn) + ".json");
	}

	private static void writeAtomic(File dest, String content) throws IOException
	{
		File tmp = new File(dest.getParentFile(), dest.getName() + ".tmp");
		try (FileOutputStream out = new FileOutputStream(tmp))
		{
			out.write(content.getBytes(StandardCharsets.UTF_8));
			out.getFD().sync();
		}
		try
		{
			Files.move(tmp.toPath(), dest.toPath(),
				StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		catch (IOException atomicUnsupported)
		{
			Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
		}
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
