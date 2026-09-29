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
import java.util.Objects;
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
	private volatile boolean freshAdjust;
	private JsonObject root;
	private JsonObject trackersBase;
	private String currentRsn;
	private File mountedDir;
	private volatile boolean ready;
	private volatile String journalWarning;

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
			trackersBase = loaded.getAsJsonObject("trackers").deepCopy();
			currentRsn = rsn;
			gatheredItems.clear();
			for (JsonElement g : arr(loaded, "gathered_items"))
			{
				long id = asLong(g);
				if (id > 0 && gatheredItems.size() < GATHERED_CAP)
				{
					gatheredItems.add((int) id);
				}
			}
			ready = true;
		}
		journalWarning = null;
	}

	void endSession()
	{
		ready = false;
		synchronized (lock)
		{
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
		Integer kc = present(data, "killCount") ? data.get("killCount").getAsInt() : null;
		List<BagItem> priced = Objects.requireNonNullElse(priced(data), List.of());
		long batchValue = priced.stream().mapToLong(b -> b.value).sum();
		boolean newRecord = present(data, "personalBest") && data.get("personalBest").getAsBoolean();
		Double pbCand = present(data, "personalBestTime") ? Double.valueOf(data.get("personalBestTime").getAsDouble())
			: newRecord && data.has("killTime") && data.get("killTime").getAsDouble() >= 0
			? Double.valueOf(data.get("killTime").getAsDouble()) : null;
		Double killTime = present(data, "killTime") && data.get("killTime").getAsDouble() > 0
			? Double.valueOf(data.get("killTime").getAsDouble()) : null;

		synchronized (lock)
		{
			slayerLoot(data, batchValue, source, priced);
			JsonObject src = sourceIn(root.getAsJsonObject("drops"), source);
			if (kc != null)
			{
				src.addProperty("kc", Math.max(asLong(src.get("kc")), kc.longValue()));
			}
			bump(src, "loots", 1);
			bump(src, "value", batchValue);
			timeKill(src, killTime);
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
			for (BagItem b : priced)
			{
				recentDrops.addFirst(new RecentDrop(b.itemId, (int) b.qty, b.name));
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
			for (BagItem b : priced)
			{
				String key = String.valueOf(b.itemId);
				JsonObject cur = isObject(bag, key) ? bag.getAsJsonObject(key) : null;
				if (cur == null)
				{
					cur = new JsonObject();
					cur.addProperty("id", b.itemId);
					bag.add(key, cur);
				}
				cur.addProperty("name", b.name);
				bump(cur, "qty", b.qty);
				bump(cur, "value", b.value);
			}
			touch();
		}
	}

	private List<BagItem> priced(JsonObject data)
	{
		if (!data.has("items") || !data.get("items").isJsonArray())
		{
			return null;
		}
		List<BagItem> out = new ArrayList<>();
		for (JsonElement ie : data.getAsJsonArray("items"))
		{
			if (ie.isJsonObject() && ie.getAsJsonObject().has("id"))
			{
				JsonObject it = ie.getAsJsonObject();
				out.add(price(it.get("id").getAsInt(), it.has("quantity") ? it.get("quantity").getAsInt() : 1));
			}
		}
		return out;
	}

	private static JsonObject sourceIn(JsonObject drops, String name)
	{
		if (!isObject(drops, name))
		{
			drops.add(name, newSource());
		}
		return drops.getAsJsonObject(name);
	}

	private static void timeKill(JsonObject o, Double killTime)
	{
		if (killTime != null)
		{
			bump(o, "timed", 1);
			o.addProperty("timeSum", asDouble(o.get("timeSum")) + killTime);
		}
	}

	private static final int DETAIL_DAYS = 400;
	private static final DateTimeFormatter DAY_KEY =
		DateTimeFormatter.ofPattern("yyyy-MM-dd");

	private JsonObject dayRoll()
	{
		JsonObject days = sub(root, "loot_days");
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
		if (!isObject(parent, key))
		{
			parent.add(key, new JsonObject());
		}
		return parent.getAsJsonObject(key);
	}

	private static JsonObject obj(JsonObject o, String key)
	{
		return o != null && isObject(o, key) ? o.getAsJsonObject(key) : new JsonObject();
	}

	private void rollTaken(String source, long value, List<BagItem> priced, Double killTime)
	{
		for (JsonObject into : new JsonObject[]{dayRoll(), sessionRoll})
		{
			bump(into, "loots", 1);
			bump(into, "value", value);
			JsonObject bySource = sub(sub(into, "sources"), source);
			bump(bySource, "loots", 1);
			bump(bySource, "value", value);
			timeKill(bySource, killTime);
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
		synchronized (lock)
		{
			return obj(root, "loot_days").keySet().stream().min(String::compareTo).map(LocalStore::dayMs).orElse(0L);
		}
	}

	private List<JsonObject> daysIn(LocalDate from, LocalDate to)
	{
		String lo = from.format(DAY_KEY);
		String hi = to.format(DAY_KEY);
		List<JsonObject> out = new ArrayList<>();
		for (var d : objects(obj(root, "loot_days")))
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
		synchronized (lock)
		{
			daysIn(from, to).forEach(w::add);
		}
		return w.ranked();
	}

	Map<String, long[]> dayTotals()
	{
		Map<String, long[]> out = new TreeMap<>();
		synchronized (lock)
		{
			for (var e : objects(obj(root, "loot_days")))
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
		synchronized (lock)
		{
			JsonObject all = obj(root, "loot_days");
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
						if (idOf(id) != null)
						{
							ids.putIfAbsent(label, idOf(id));
						}
					}
				}
			}
		}
		Map<String, List<BagItem>> out = new LinkedHashMap<>();
		by.forEach((source, items) -> out.put(source, bagRows(items, ids, 0)));
		return out;
	}

	private static void add(Map<String, long[]> into, String key, long qty, long value)
	{
		long[] t = into.computeIfAbsent(key, k -> new long[2]);
		t[0] += qty;
		t[1] += value;
	}

	Set<String> unfiledSources(LocalDate from, LocalDate to)
	{
		Set<String> out = new HashSet<>();
		synchronized (lock)
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

	private static void gather(JsonObject day, String key,
		Map<String, long[]> into, boolean named)
	{
		for (var e : objects(obj(day, key)))
		{
			JsonObject o = e.getValue();
			add(into, named && o.has("n") ? o.get("n").getAsString() : e.getKey(),
				asLong(o.get(named ? "q" : "loots")), asLong(o.get(named ? "v" : "value")));
		}
	}

	private static void gatherTimes(JsonObject day, Map<String, double[]> into)
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

	private static void rank(Map<String, long[]> from, List<String[]> into)
	{
		List<Map.Entry<String, long[]>> rows = new ArrayList<>(from.entrySet());
		rows.sort((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]));
		for (var e : rows)
		{
			into.add(new String[]{e.getKey(), String.valueOf(e.getValue()[0]), String.valueOf(e.getValue()[1])});
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
			if (isObject(root, "trackers"))
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
			Set<String> keys = new HashSet<>(session == null ? Set.of() : session.keySet());
			keys.addAll(trackersBase.keySet());
			for (String k : keys)
			{
				long base = asLong(trackersBase.get(k));
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
		for (String key : new String[]{"skills", "chat_kcs", "kc_anchors", "collection_log", "achievements", "drops", "trackers"})
		{
			sub(o, key);
		}
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
			for (var e : objects(obj(root, "drops")))
			{
				JsonObject src = e.getValue();
				out.add(new SourceRow(e.getKey(), (int) asLong(src.get("kc")), (int) asLong(src.get("loots")),
					asLong(src.get("value")), src.has("pb") ? src.get("pb").getAsDouble() : null,
					asLong(src.get("first_seen")), asLong(src.get("last_seen")),
					lootedNames(src), asLong(src.get("timed")), asDouble(src.get("timeSum"))));
			}
		}
		return out;
	}

	private static Set<String> lootedNames(JsonObject src)
	{
		Set<String> out = new HashSet<>();
		for (var e : objects(obj(src, "items")))
		{
			String name = str(e.getValue(), "name", "").trim().toLowerCase(Locale.ROOT);
			if (asLong(e.getValue().get("qty")) > 0 && !name.isEmpty())
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
		return (int) sessionFigure("loots");
	}

	long sessionLootValue()
	{
		return sessionFigure("value");
	}

	private long sessionFigure(String key)
	{
		synchronized (lock)
		{
			return asLong(sessionRoll.get(key));
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
				JsonObject src = sourceIn(drops, seed.source);
				raise(src, "kc", seed.kills);
				raise(src, "loots", seed.kills);
				if (seed.firstMs > 0 && seed.firstMs < (src.has("first_seen") ? asLong(src.get("first_seen")) : Long.MAX_VALUE))
				{
					src.addProperty("first_seen", seed.firstMs);
				}
				raise(src, "last_seen", seed.lastMs);
				JsonObject items = sub(src, "items");
				for (BagItem b : seed.items)
				{
					String id = String.valueOf(b.itemId);
					JsonObject hit = b.itemId > 0 && isObject(items, id)
						? items.getAsJsonObject(id) : null;
					String named = hit == null ? keyNamed(items, b.name) : null;
					if (named != null)
					{
						hit = items.getAsJsonObject(named);
						if (b.itemId > 0 && named.startsWith("n:"))
						{
							items.remove(named);
							hit.addProperty("id", b.itemId);
							items.add(id, hit);
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
					raise(hit, "qty", b.qty);
					if (asLong(hit.get("value")) <= 0)
					{
						hit.addProperty("value", b.value);
					}
				}
				raise(src, "value", objects(items).stream().mapToLong(e -> asLong(e.getValue().get("value"))).sum());
			}
			rebaseAnchors();
		}
	}

	private static String keyNamed(JsonObject bag, String name)
	{
		for (var e : objects(bag))
		{
			if (name.equalsIgnoreCase(str(e.getValue(), "name", null)))
			{
				return e.getKey();
			}
		}
		return null;
	}

	private static Integer idOf(String key)
	{
		try
		{
			return Integer.parseInt(key);
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	List<BagItem> sourceItems(String source)
	{
		List<BagItem> out = new ArrayList<>();
		synchronized (lock)
		{
			for (var e : objects(obj(obj(obj(root, "drops"), source), "items")))
			{
				JsonObject it = e.getValue();
				out.add(new BagItem(Objects.requireNonNullElse(idOf(e.getKey()), -1), it.has("name") ? it.get("name").getAsString() : e.getKey(),
					asLong(it.get("qty")), asLong(it.get("value"))));
			}
		}
		return out;
	}

	private void recordUntaken(JsonObject data)
	{
		String source = str(data, "source", "Unknown");
		List<BagItem> perItem = priced(data);
		if (perItem == null)
		{
			return;
		}
		int kills = (int) Math.max(0, asLong(data.get("kills")));
		long qty = perItem.stream().mapToLong(b -> b.qty).sum();
		long value = perItem.stream().mapToLong(b -> b.value).sum();
		synchronized (lock)
		{
			JsonObject src = sub(sub(root, "untaken"), source);
			bump(src, "qty", qty);
			bump(src, "value", value);
			bump(src, "kills", kills);
			fileByName(sub(root, "untaken_items"), perItem, false);
			rollLeft(qty, value, kills, perItem);
			fileByName(sub(sub(root, "untaken_pairs"), source), perItem, true);
			touch();
		}
	}

	private static void fileByName(JsonObject bag, List<BagItem> items, boolean withId)
	{
		for (BagItem b : items)
		{
			JsonObject row = sub(bag, b.name);
			if (withId)
			{
				row.addProperty("id", b.itemId);
			}
			bump(row, "qty", b.qty);
			bump(row, "value", b.value);
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
			bump(sub(root, "consumable_values"), key, gp);
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
			return trackersBase != null ? asLong(trackersBase.get(key)) : 0;
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
		Long lastRem = seg == null ? null : optLong(seg, "last_rem");
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

	private void slayerLoot(JsonObject data, long value, String monster, List<BagItem> items)
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
		fileByName(sub(seg, "items"), items, true);
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

	List<BagItem> allLoot()
	{
		Map<String, long[]> summed = new LinkedHashMap<>();
		Map<String, Integer> ids = new LinkedHashMap<>();
		synchronized (lock)
		{
			for (var src : objects(obj(root, "drops")))
			{
				sumItems(obj(src.getValue(), "items"), summed, ids, true);
			}
		}
		return bagRows(summed, ids, -1);
	}

	private static void sumItems(JsonObject bag, Map<String, long[]> summed, Map<String, Integer> ids, boolean byName)
	{
		for (var e : objects(bag))
		{
			JsonObject v = e.getValue();
			String key = byName ? str(v, "name", "") : e.getKey();
			if (byName && key.isEmpty())
			{
				continue;
			}
			add(summed, key, asLong(v.get("qty")), asLong(v.get("value")));
			if (ids != null && !ids.containsKey(key) && v.has("id"))
			{
				ids.put(key, (int) asLong(v.get("id")));
			}
		}
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
		Set<String> names = new LinkedHashSet<>();
		synchronized (lock)
		{
			for (JsonObject t : newestFirst(taskArray()))
			{
				if (!taskName(t).trim().isEmpty())
				{
					names.add(taskName(t).trim());
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
		return arr(obj(root, "slayer"), "tasks");
	}

	Map<String, long[]> onTaskItems(long fromMs, long toMs)
	{
		Map<String, long[]> out = new LinkedHashMap<>();
		synchronized (lock)
		{
			for (JsonObject t : tasksIn(fromMs, toMs, null, true))
			{
				sumItems(obj(t, "items"), out, null, false);
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
				for (var it : objects(obj(t, "items")))
				{
					if (it.getKey().equalsIgnoreCase(itemName))
					{
						add(by, taskName(t), asLong(it.getValue().get("qty")), asLong(it.getValue().get("value")));
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
		synchronized (lock)
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
		synchronized (lock)
		{
			for (JsonObject t : tasksIn(fromMs, toMs, onlyTask, includeOpen))
			{
				sumItems(obj(t, "items"), summed, ids, false);
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
			for (var e : objects(obj(root, key)))
			{
				JsonObject it = e.getValue();
				out.add(new UntakenRow(e.getKey(), asLong(it.get("qty")), asLong(it.get("value")),
					kills ? asLong(it.get("kills")) : 0));
			}
		}
		return out;
	}

	long[] sessionUntakenTally()
	{
		return new long[]{sessionFigure("left"), sessionFigure("leftValue")};
	}

	int sessionUntakenKills()
	{
		return (int) sessionFigure("leftKills");
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
			if (items.keySet().stream().noneMatch(name::equalsIgnoreCase))
			{
				items.addProperty(name, 1);
				bump(cl, "finished", 1);
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
		for (String key : new String[]{"kc_lines", "pb_lines"})
		{
			JsonObject lines = nested(base, inc, key, key.equals("pb_lines"));
			if (lines.size() > 0)
			{
				out.add(key, lines);
			}
		}
		for (String mapKey : new String[]{"kcs", "clog_items", "cat_counts", "slayer_kcs"})
		{
			JsonObject merged = new JsonObject();
			for (JsonObject src : new JsonObject[]{base, inc})
			{
				for (var e : obj(src, mapKey).entrySet())
				{
					raise(merged, e.getKey(), asLong(e.getValue()));
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
			for (var pg : objects(obj(src, key)))
			{
				JsonObject tgt = sub(all, pg.getKey());
				for (var ln : pg.getValue().entrySet())
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

	private static List<Map.Entry<String, JsonObject>> objects(JsonObject o)
	{
		List<Map.Entry<String, JsonObject>> out = new ArrayList<>();
		for (var e : o.entrySet())
		{
			if (e.getValue().isJsonObject())
			{
				out.add(Map.entry(e.getKey(), e.getValue().getAsJsonObject()));
			}
		}
		return out;
	}

	private static List<JsonObject> objects(JsonArray a)
	{
		List<JsonObject> out = new ArrayList<>();
		for (JsonElement e : a)
		{
			if (e.isJsonObject())
			{
				out.add(e.getAsJsonObject());
			}
		}
		return out;
	}

	private static List<JsonObject> newestFirst(JsonArray a)
	{
		List<JsonObject> out = objects(a);
		Collections.reverse(out);
		return out;
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
			for (var e : obj(in, "trackers").entrySet())
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
				raise(sub(root, "trackers"), e.getKey(), v);
			}
			if (isObject(in, "drops"))
			{
				JsonObject drops = root.getAsJsonObject("drops");
				for (var e : objects(in.getAsJsonObject("drops")))
				{
					sources += drops.has(e.getKey()) ? 0 : 1;
					importSource(sourceIn(drops, e.getKey()), e.getValue());
				}
				rebaseAnchors();
			}
			if (in.has("feed") && in.get("feed").isJsonArray())
			{
				Set<String> seen = new HashSet<>();
				List<JsonObject> all = new ArrayList<>();
				for (JsonElement e : root.getAsJsonArray("feed"))
				{
					if (e.isJsonObject())
					{
						seen.add(feedKey(e.getAsJsonObject()));
						all.add(e.getAsJsonObject());
					}
				}
				for (JsonElement e : in.getAsJsonArray("feed"))
				{
					if (e.isJsonObject() && seen.add(feedKey(e.getAsJsonObject())))
					{
						all.add(e.getAsJsonObject().deepCopy());
						events++;
					}
				}
				setFeed(all, FEED_CAP);
			}
			if (isObject(in, "collection_log"))
			{
				root.add("collection_log", mergeClog(sub(root, "collection_log"), in.getAsJsonObject("collection_log")));
			}
			for (var e : obj(in, "chat_kcs").entrySet())
			{
				raise(sub(root, "chat_kcs"), e.getKey(), asLong(e.getValue()));
			}
			if (in.has("gathered_items") && in.get("gathered_items").isJsonArray())
			{
				JsonArray have = arr(root, "gathered_items");
				Set<Integer> seen = new HashSet<>();
				have.forEach(g -> seen.add(g.getAsInt()));
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
				if (isObject(in, store))
				{
					mergeRows(sub(root, store), in.getAsJsonObject(store), false);
				}
			}
			if (isObject(in, "untaken_pairs"))
			{
				JsonObject pairs = sub(root, "untaken_pairs");
				for (var e : objects(in.getAsJsonObject("untaken_pairs")))
				{
					mergeRows(sub(pairs, e.getKey()), e.getValue(), false);
				}
			}
			if (isObject(in, "slayer"))
			{
				importSlayer(in.getAsJsonObject("slayer"));
			}
			touch();
		}
		return sources + " sources · " + String.format(Locale.UK, "%,d", events)
			+ " journal entries · " + counters + " counters";
	}

	private static void importSource(JsonObject cur, JsonObject inc)
	{
		for (String k : new String[]{"kc", "loots", "value", "last_seen"})
		{
			floorNumber(cur, inc, k);
		}
		long incFirst = asLong(inc.get("first_seen"));
		long curFirst = asLong(cur.get("first_seen"));
		if (incFirst > 0 && (curFirst == 0 || incFirst < curFirst))
		{
			cur.addProperty("first_seen", incFirst);
		}
		double incPb = asDouble(inc.get("pb"));
		if (incPb > 0 && (!cur.has("pb") || incPb < cur.get("pb").getAsDouble()))
		{
			cur.addProperty("pb", incPb);
		}
		if (!isObject(inc, "items"))
		{
			return;
		}
		JsonObject bag = sub(cur, "items");
		for (var ie : objects(inc.getAsJsonObject("items")))
		{
			JsonObject incItem = ie.getValue();
			String incName = str(incItem, "name", ie.getKey());
			String key = keyNamed(bag, incName);
			if (key == null)
			{
				key = incItem.has("id") && incItem.get("id").getAsInt() > 0
					? bagKey(incItem.get("id").getAsInt(), incName) : ie.getKey();
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
		}
	}

	private void importSlayer(JsonObject incSl)
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

	private static final long SEGMENT_MATCH_SECONDS = 60;

	private static JsonObject nearestSegment(JsonArray tasks, JsonObject inc)
	{
		String task = inc.has("task") ? inc.get("task").getAsString() : null;
		JsonObject best = null;
		long bestGap = SEGMENT_MATCH_SECONDS + 1;
		for (JsonElement e : tasks)
		{
			JsonObject seg = e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
			if (task != null && seg.has("task") && task.equalsIgnoreCase(seg.get("task").getAsString())
				&& Math.abs(asLong(seg.get("ts")) - asLong(inc.get("ts"))) < bestGap)
			{
				bestGap = Math.abs(asLong(seg.get("ts")) - asLong(inc.get("ts")));
				best = seg;
			}
		}
		return best;
	}

	private static void mergeSegmentDetail(JsonObject seg, JsonObject inc, String key)
	{
		if (isObject(inc, key))
		{
			mergeRows(sub(seg, key), inc.getAsJsonObject(key), true);
		}
	}

	private static void mergeRows(JsonObject cur, JsonObject inc, boolean keepValue)
	{
		for (var e : inc.entrySet())
		{
			if (!e.getValue().isJsonObject())
			{
				raise(cur, e.getKey(), asLong(e.getValue()));
				continue;
			}
			JsonObject incRow = e.getValue().getAsJsonObject();
			JsonObject curRow = sub(cur, e.getKey());
			floorNumber(curRow, incRow, "qty");
			if (!keepValue || asLong(curRow.get("value")) <= 0)
			{
				floorNumber(curRow, incRow, "value");
			}
			floorNumber(curRow, incRow, "kills");
			if (!curRow.has("id") && incRow.has("id"))
			{
				curRow.add("id", incRow.get("id"));
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
		if (!isObject(e, "data"))
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
			for (var e : objects(obj(root, "untaken_pairs")))
			{
				if (isObject(e.getValue(), item))
				{
					JsonObject r = e.getValue().getAsJsonObject(item);
					out.add(new UntakenRow(e.getKey(), asLong(r.get("qty")), asLong(r.get("value"))));
				}
			}
		}
		out.sort((a, b) -> Long.compare(b.value, a.value));
		return out;
	}

	List<BagItem> slayerTaskItems(int index)
	{
		synchronized (lock)
		{
			return bagOf(obj(segmentAt(index), "items"));
		}
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
		synchronized (lock)
		{
			for (var e : obj(segmentAt(index), "monsters").entrySet())
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
			for (JsonObject e : newestFirst(arr(root, "feed")))
			{
				JsonObject d = obj(e, "data");
				String name = str(d, "petName", "");
				if ("PET".equals(e.has("type") ? e.get("type").getAsString() : "")
					&& !name.isEmpty() && seen.add(name.toLowerCase(Locale.ROOT)))
				{
					out.add(new PetRow(name, str(d, "source", null), asLong(d.get("killCount")), asLong(e.get("ts"))));
				}
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
			for (var e : objects(obj(root, "skills")))
			{
				out.put(e.getKey(), new long[]{asLong(e.getValue().get("level")), asLong(e.getValue().get("xp"))});
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
		long kills = spineKills(cl, sources, reconciled);
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
		Map<String, Long> out = clogKillCounts(clog);
		Map<String, Long> stated = killLogCounts(clog);
		foldChatCounts(stated, chat, out.keySet());
		placeByKind(out, stated, false);
		placeByKind(out, pageKillLines(clog), true);
		placeByKind(out, ledgerKills(clog, sources), true);
		placeByKind(out, respelled(anchored, out.keySet()), false);
		return out;
	}

	static long spineKills(JsonObject clog, List<SourceRow> sources,
		Map<String, Long> reconciled)
	{
		long kills = 0;
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
		for (String name : sourceKills(clog, sources, true).keySet())
		{
			String key = reconciled.containsKey(name) ? name : byKind.get(chatKind(name));
			if (key != null)
			{
				out.add(key);
			}
		}
		return out;
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
			if (isObject(all, name))
			{
				JsonObject was = all.getAsJsonObject(name);
				int had = anchorRank(was.has("src") ? was.get("src").getAsString() : "page");
				if (anchorRank(src) < had || (anchorRank(src) == had && asLong(was.get("n")) == stated))
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
		long best = 0;
		for (var e : objects(obj(root, "drops")))
		{
			if (kindOf(e.getKey()).equals(kindOf(name)))
			{
				best = Math.max(best, asLong(e.getValue().get("loots")));
			}
		}
		return best;
	}

	private void absorbLaggingKill(String source, Integer stated)
	{
		for (var e : stated == null ? List.<Map.Entry<String, JsonObject>>of() : objects(obj(root, "kc_anchors")))
		{
			if (kindOf(e.getKey()).equals(kindOf(source)) && asLong(e.getValue().get("n")) == stated.longValue())
			{
				e.getValue().addProperty("obs", observedFor(e.getKey()));
			}
		}
	}

	Map<String, Long> anchoredKills()
	{
		Map<String, Long> out = new LinkedHashMap<>();
		synchronized (lock)
		{
			for (var e : objects(obj(root, "kc_anchors")))
			{
				long n = asLong(e.getValue().get("n"));
				if (n > 0)
				{
					out.put(e.getKey(), n + Math.max(0, observedFor(e.getKey()) - asLong(e.getValue().get("obs"))));
				}
			}
		}
		return out;
	}

	private void rebaseAnchors()
	{
		for (var e : objects(obj(root, "kc_anchors")))
		{
			e.getValue().addProperty("obs", observedFor(e.getKey()));
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
		if (clog != null && isObject(clog, "kcs"))
		{
			out.putAll(pageKillLines(clog));
		}
		return out;
	}

	static Map<String, Long> pageKillLines(JsonObject clog)
	{
		Map<String, Long> out = new LinkedHashMap<>();
		for (var pg : objects(obj(clog, "kc_lines")))
		{
			for (var ln : pg.getValue().entrySet())
			{
				String label = ln.getKey().toLowerCase(Locale.ROOT);
				if ((label.contains("kill") || label.contains("completion")) && asLong(ln.getValue()) > 0)
				{
					out.put(pg.getKey(), asLong(ln.getValue()));
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
			out.merge(spokenAs(byKind, e.getKey()), e.getValue(), Math::max);
		}
	}

	private static String spokenAs(Map<String, String> byKind, String said)
	{
		String known = byKind.get(chatKind(said));
		int space = said.indexOf(' ');
		if (known == null && space > 0)
		{
			known = byKind.get(chatKind(said.substring(space + 1)));
		}
		if (known == null)
		{
			known = byKind.get(chatKind(said + " chests"));
		}
		return known != null ? known : said;
	}

	static Map<String, Long> respelled(Map<String, Long> said, Set<String> names)
	{
		Map<String, String> byKind = new HashMap<>();
		for (String name : names)
		{
			byKind.putIfAbsent(chatKind(name), name);
		}
		Map<String, Long> out = new LinkedHashMap<>();
		for (var e : said.entrySet())
		{
			out.merge(spokenAs(byKind, e.getKey()), e.getValue(), Math::max);
		}
		return out;
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
		obj(cl, "clog_items").keySet().forEach(k -> names.add(k.toLowerCase(Locale.ROOT)));
		for (var pg : objects(obj(cl, "by_cat")))
		{
			pg.getValue().keySet().forEach(k -> names.add(k.toLowerCase(Locale.ROOT)));
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
			for (var e : objects(drops))
			{
				loots += asLong(e.getValue().get("loots"));
				worth += asLong(e.getValue().get("value"));
				rows += obj(e.getValue(), "items").size();
			}
			out.put("sources", (long) drops.size());
			out.put("itemRows", rows);
			out.put("lootEvents", loots);
			out.put("lootWorth", worth);
			out.put("lootDays", (long) obj(root, "loot_days").size());
			out.put("untakenSources", (long) obj(root, "untaken").size());
			out.put("untakenItems", (long) obj(root, "untaken_items").size());
			JsonObject sl = obj(root, "slayer");
			out.put("tasks", (long) arr(sl, "tasks").size());
			out.put("tasksClosed", asLong(sl.get("completed")));
			JsonObject cl = obj(root, "collection_log");
			out.put("clogSlots", asLong(cl.get("finished")));
			out.put("clogAvailable", asLong(cl.get("available")));
			out.put("clogItems", (long) obj(cl, "clog_items").size());
			out.put("clogPages", (long) obj(cl, "kcs").size());
			out.put("killLogLines", (long) obj(cl, "slayer_kcs").size());
			out.put("pageKillLines", (long) obj(cl, "kc_lines").size());
			out.put("trackers", (long) obj(root, "trackers").size());
			out.put("skills", (long) obj(root, "skills").size());
			out.put("feed", (long) arr(root, "feed").size());
			out.put("chatCounts", (long) obj(root, "chat_kcs").size());
			out.put("anchors", (long) obj(root, "kc_anchors").size());
		}
		File f = mountedDir == null || currentRsn == null ? null : jsonPath(mountedDir, currentRsn);
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

	private static boolean isObject(JsonObject o, String key)
	{
		return o.has(key) && o.get(key).isJsonObject();
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
