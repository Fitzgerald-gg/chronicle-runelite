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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.game.ItemManager;

/**
 * The on-disk journal: one {@code <slug>.json} per account under
 * {@code .runelite/chronicle/}, loaded at login and rewritten as you play. It is
 * the record the side panel reads, and it never leaves this computer.
 *
 * <p>Threading: {@link #record} and {@link #setCharacter} run on the client thread
 * ({@link #record} prices through {@link ItemManager}), and {@link #setCharacter} and
 * {@link #setTrackers} once more on the EDT, from the plugin's shutDown; {@link #load} and
 * {@link #flush} run on a background executor. The in-memory model is guarded by
 * {@link #lock}, and the file-writing methods hold it only long enough to
 * serialise a string, so the client thread never blocks on I/O.
 */
@Singleton
@Slf4j
class LocalStore implements chronicle.counters.GatheredLedger
{
	static final int SCHEMA = 1;
	// runaway guard
	private static final int FEED_CAP = 20000;
	// peak counters: lifetime is max(base, session) rather than base + session.
	static final java.util.Set<String> MAX_KEYS = new java.util.HashSet<>(
		java.util.Arrays.asList("highestHit", "highestHitTaken"));
	// Event types kept as dated feed lines. LOOT and LOOT_UNTAKEN are recorded too,
	// into the drop and untaken ledgers; GROUP_STORAGE isn't kept at all.
	private static final java.util.Set<String> FEED_TYPES = new java.util.HashSet<>(java.util.Arrays.asList(
		"PET", "COLLECTION", "COMBAT_ACHIEVEMENT", "QUEST", "DIARY", "CLUE", "DEATH", "SLAYER",
		"LEVEL",
		"SESSION"));   // SESSION is recorded by the plugin itself, so it stays off the network

	private final ItemManager itemManager;
	private final Gson gson;

	private final Object lock = new Object();
	private JsonObject root;          // the current account's model (guarded by lock)
	private JsonObject trackersBase;  // lifetime counters frozen at load; +session = lifetime
	private String currentRsn;        // whose model root holds
	private File mountedDir;          // where the journal it came from lives
	private volatile boolean ready;   // true once an account's file has been loaded
	// Why the journal isn't reaching disk, or null. The panel shows it; a stalled
	// journal still looks alive in memory otherwise.
	private volatile String journalWarning;

	// Session tallies for the panel strip and recent-drop row. In memory only,
	// cleared at the account boundary; guarded by lock.
	private int sessionLoots;
	private long sessionLootValue;
	private int sessionUntaken;
	private long sessionUntakenValue;
	// the kills that left at least one stack on the floor, the capture's own count
	private int sessionUntakenKills;
	private final java.util.ArrayDeque<RecentDrop> recentDrops = new java.util.ArrayDeque<>();
	// This sitting's take, in the same shape as one of the dated roll's days, so
	// a board can read it through the same fold. The roll keeps ONE entry a day
	// and a sitting is hours inside one of those, so it could never be asked what
	// this sitting took; this is written beside it as the drops land. In memory
	// only, and gone at the account boundary like the tallies above it.
	private JsonObject sessionRoll = new JsonObject();

	// runaway guard; every log, ore, fish and gem in the game is a few hundred ids,
	// and the journal is rewritten whole on every flush.
	private static final int GATHERED_CAP = 1024;
	// Lock-free mirror of the record's "gathered_items", read on every drop click.
	// The resolver writes it on the client thread while load() rebuilds it on the executor.
	private final java.util.Set<Integer> gatheredItems =
		java.util.concurrent.ConcurrentHashMap.newKeySet();

	static final class RecentDrop
	{
		final int itemId;
		final int quantity;
		final String name;

		RecentDrop(int itemId, int quantity, String name)
		{
			this.itemId = itemId;
			this.quantity = quantity;
			this.name = name;
		}
	}


	@Inject
	LocalStore(ItemManager itemManager, Gson gson)
	{
		this.itemManager = itemManager;
		this.gson = gson;
	}

	// ------------------------------------------------------------------
	// Session lifecycle
	// ------------------------------------------------------------------

	/** Mount this account's record, or start one. Runs on the executor; call once
	 *  per login, before anything records. */
	void load(File dir, String rsn)
	{
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
			catch (Exception e)   // noqa: a torn file is set aside below
			{
				log.warn("local record unreadable: {}", f, e);
			}
			if (loaded == null)
			{
				// The only copy of this account's history, and the next flush would write
				// a blank skeleton over it. Keep the bytes under a dated sidecar.
				setAside(f, "corrupt");
			}
		}
		long fileSchema = loaded != null ? asLong(loaded.get("schema")) : 0;
		if (fileSchema > SCHEMA)
		{
			// A newer schema would be stamped down by normalise() and rewritten. Mount nothing.
			log.warn("journal {} is schema {}; this build reads {}",
				f.getName(), fileSchema, SCHEMA);
			journalWarning = "This journal was written by a newer version of Chronicle. "
				+ "Update the plugin to open it. Nothing on disk has been changed.";
			synchronized (lock)
			{
				// Empty rather than null: a model going null under the panel's reads throws
				// on the EDT. No currentRsn, so flush() can't write it over the real record.
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
			// The counter keys are folded before the base below is frozen: a key
			// left in the base would be restated on the next session total.
			int healed = repairTrackerKeys();
			// Freeze the loaded lifetime counters; setTrackers() recomputes the live
			// total as this base + the current session.
			trackersBase = deepCopy(loaded.getAsJsonObject("trackers"));
			currentRsn = rsn;
			// What an earlier build, or an import, could leave inconsistent: the same
			// item twice in one source's bag, a feed line twice, by-item leavings that
			// no longer sum to the pairs, off-task kills counted toward a slayer task.
			healed += dedupeSourceBags() + dedupeFeed() + reconcileUntaken()
				+ purgeOffTaskMonsters();
			if (healed > 0)
			{
				log.debug("repaired {} journal entries on load", healed);
			}
			// Cleared first: the mirror must describe this account only, or the previous
			// character's ore would credit this one's drops.
			gatheredItems.clear();
			if (loaded.has("gathered_items") && loaded.get("gathered_items").isJsonArray())
			{
				for (JsonElement g : loaded.getAsJsonArray("gathered_items"))
				{
					long id = asLong(g);
					if (id > 0 && gatheredItems.size() < GATHERED_CAP)
					{
						gatheredItems.add((int) id);
					}
				}
			}
			ready = true;
		}
		// Whatever was wrong belongs to the last account. A disk still refusing
		// writes re-states itself on the next flush.
		journalWarning = null;
	}

	/** The account has logged out; a different one must not record onto its model. */
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
			// Account-scoped. load() reads it back from the record.
			gatheredItems.clear();
		}
	}

	boolean isReadyFor(String rsn)
	{
		return ready && rsn != null && rsn.equals(currentRsn);
	}

	// ------------------------------------------------------------------
	// Ingest (client thread)
	// ------------------------------------------------------------------

	/**
	 * Bumped on every call to record(), kept or not, and on every other write to
	 * the model. The panel is rebuilt when the record
	 * moves and left alone when it does not; this is how "moved" is known without
	 * comparing two copies of the whole journal every tick.
	 */
	private volatile long revision;

	long revision()
	{
		return revision;
	}

	/** Fold one captured event into the model. Runs on the client thread. */
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
			JsonObject entry = new JsonObject();
			entry.addProperty("ts", System.currentTimeMillis());
			entry.addProperty("type", type);
			entry.add("data", data);
			synchronized (lock)
			{
				JsonArray feed = root.getAsJsonArray("feed");
				feed.add(entry);
				// index 0 is the oldest; the feed is appended in order
				while (feed.size() > FEED_CAP)
				{
					feed.remove(0);
				}
				root.addProperty("updated_at", nowSec());
			}
		}
	}

	private void recordLoot(JsonObject data)
	{
		String source = data.has("source") && !data.get("source").isJsonNull()
			? data.get("source").getAsString() : "Unknown";
		JsonArray items = data.has("items") && data.get("items").isJsonArray()
			? data.getAsJsonArray("items") : null;
		Integer kc = data.has("killCount") && !data.get("killCount").isJsonNull()
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

		// The standing PB when the game restated it, else this kill's own time when
		// it was the record.
		Double pbCand = null;
		if (data.has("personalBestTime") && !data.get("personalBestTime").isJsonNull())
		{
			pbCand = data.get("personalBestTime").getAsDouble();
		}
		else if (data.has("personalBest") && !data.get("personalBest").isJsonNull()
			&& data.get("personalBest").getAsBoolean()
			&& data.has("killTime") && data.get("killTime").getAsDouble() >= 0)
		{
			pbCand = data.get("killTime").getAsDouble();
		}

		synchronized (lock)
		{
			slayerLoot(data, batchValue, source, priced);
			JsonObject drops = root.getAsJsonObject("drops");
			// Guarded field by field: an older journal's source entry (or a hand edit)
			// can be missing counters, and an exception here is eaten by the event bus.
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
			// The chat box speaks BEFORE the drop lands. "Your Sarachnis kill count
			// is: 52" anchors at 52, and the loot event for that very kill arrives
			// after it, so counting it as an observation SINCE would read 53. The
			// row carries the game's own number for the kill it came from: where
			// that is the number the anchor was taken at, this is that same kill
			// arriving late, and the baseline moves with it rather than the count.
			// A slayer monster's row carries its task counter instead, which is a
			// different quantity and will not match, so its kills still count.
			absorbLaggingKill(source, kc);
			// first_seen/last_seen run as min/max of every kill, epoch ms: an earlier
			// date the Loot Tracker import set stands, a later one only ever extends.
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
			rollTaken(source, batchValue, priced);
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
			}

			if (!src.has("items") || !src.get("items").isJsonObject())
			{
				src.add("items", new JsonObject());
			}
			JsonObject bag = src.getAsJsonObject("items");
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
			root.addProperty("updated_at", nowSec());
		}
	}

	// ------------------------------------------------------------------
	// The dated loot roll
	//
	// A loot event knows when it happened and the ledger above keeps only what
	// it was, so until this roll existed the record could say a player had taken
	// 64,274 drops worth 488M and nothing whatever about when. One entry a day
	// carries the day's take and the day's floor, with a breakdown beside them,
	// which is as fine as any window the panel offers. Per kill would be tens of
	// thousands of rows a year for no extra precision.
	//
	// The breakdown is pruned past DETAIL_DAYS; the day's totals are kept for
	// good, being a few dozen bytes apiece.
	// ------------------------------------------------------------------

	private static final int DETAIL_DAYS = 400;
	private static final java.time.format.DateTimeFormatter DAY_KEY =
		java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd");

	private JsonObject dayRoll()
	{
		if (!root.has("loot_days") || !root.get("loot_days").isJsonObject())
		{
			root.add("loot_days", new JsonObject());
		}
		JsonObject days = root.getAsJsonObject("loot_days");
		String today = java.time.LocalDate.now().format(DAY_KEY);
		if (!days.has(today) || !days.get(today).isJsonObject())
		{
			days.add(today, new JsonObject());
			pruneDetail(days);
		}
		return days.getAsJsonObject(today);
	}

	// the breakdown goes off days older than DETAIL_DAYS; their totals stay
	private static void pruneDetail(JsonObject days)
	{
		String cut = java.time.LocalDate.now().minusDays(DETAIL_DAYS).format(DAY_KEY);
		for (String day : new java.util.ArrayList<>(days.keySet()))
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

	// one kill's take, against today
	private void rollTaken(String source, long value, JsonArray priced)
	{
		// Today and this sitting, the same figures into the same shape. Written
		// here rather than derived later: a drop knows which sitting it landed in
		// only while that sitting is running.
		for (JsonObject into : new JsonObject[]{dayRoll(), sessionRoll})
		{
			bump(into, "loots", 1);
			bump(into, "value", value);
			JsonObject bySource = sub(sub(into, "sources"), source);
			bump(bySource, "loots", 1);
			bump(bySource, "value", value);
			JsonObject items = sub(into, "items");
			// The SITTING keeps its items per source as well as in total, which
			// is what lets a source's own page answer for a sitting. The dated
			// roll deliberately does not: it is written to disk and kept for
			// four hundred days, and sources times items a day is a different
			// order of file. So a longer period can say what a source paid and
			// not what it paid it IN.
			JsonObject mine = into == sessionRoll ? sub(bySource, "items") : null;
			for (JsonElement pe : priced)
			{
				JsonObject p = pe.getAsJsonObject();
				String id = String.valueOf(p.get("id").getAsInt());
				JsonObject it = sub(items, id);
				it.addProperty("n", p.get("name").getAsString());
				bump(it, "q", p.get("qty").getAsLong());
				bump(it, "v", p.get("value").getAsLong());
				if (mine != null)
				{
					JsonObject own = sub(mine, id);
					own.addProperty("n", p.get("name").getAsString());
					bump(own, "q", p.get("qty").getAsLong());
					bump(own, "v", p.get("value").getAsLong());
				}
			}
		}
	}

	// one kill's floor, against today
	private void rollLeft(long qty, long value, int kills, java.util.List<BagItem> perItem)
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

	/** What the dated roll holds for a window; every figure zero and every list
	 *  empty when the window holds nothing. */
	static final class LootWindow
	{
		long loots;
		long value;
		long left;
		long leftValue;
		long leftKills;
		final java.util.List<String[]> items = new java.util.ArrayList<>();
		final java.util.List<String[]> sources = new java.util.ArrayList<>();
		final java.util.List<String[]> leftItems = new java.util.ArrayList<>();
	}

	/** The first day the roll holds, as epoch ms, or 0 when it holds none. A
	 *  window opening before this day has no dated account of its loot. */
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
			return first == null ? 0 : java.time.LocalDate.parse(first)
				.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
		}
	}

	/** The roll summed over [from, to], both days included. */
	LootWindow lootBetween(java.time.LocalDate from, java.time.LocalDate to)
	{
		LootWindow w = new LootWindow();
		java.util.Map<String, long[]> items = new java.util.LinkedHashMap<>();
		java.util.Map<String, long[]> sources = new java.util.LinkedHashMap<>();
		java.util.Map<String, long[]> left = new java.util.LinkedHashMap<>();
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
				JsonObject d = days.getAsJsonObject(day);
				w.loots += asLong(d.get("loots"));
				w.value += asLong(d.get("value"));
				w.left += asLong(d.get("left"));
				w.leftValue += asLong(d.get("leftValue"));
				w.leftKills += asLong(d.get("leftKills"));
				gather(d, "items", items, true);
				gather(d, "sources", sources, false);
				gather(d, "leftItems", left, true);
			}
		}
		rank(items, w.items);
		rank(sources, w.sources);
		rank(left, w.leftItems);
		return w;
	}

	/**
	 * What THIS sitting has taken and left, ranked, in the same shape the dated
	 * roll answers a window with.
	 *
	 * <p>One entry folded through the same gather and rank the roll uses, so the
	 * boards that read a window read a sitting without knowing the difference.
	 * A sitting that has seen no drops answers with every figure zero and every
	 * list empty, which is the true answer and not an absence of one.
	 */
	LootWindow sessionLootWindow()
	{
		LootWindow w = new LootWindow();
		java.util.Map<String, long[]> items = new java.util.LinkedHashMap<>();
		java.util.Map<String, long[]> sources = new java.util.LinkedHashMap<>();
		java.util.Map<String, long[]> left = new java.util.LinkedHashMap<>();
		synchronized (lock)
		{
			JsonObject d = sessionRoll;
			w.loots = asLong(d.get("loots"));
			w.value = asLong(d.get("value"));
			w.left = asLong(d.get("left"));
			w.leftValue = asLong(d.get("leftValue"));
			w.leftKills = asLong(d.get("leftKills"));
			gather(d, "items", items, true);
			gather(d, "sources", sources, false);
			gather(d, "leftItems", left, true);
		}
		rank(items, w.items);
		rank(sources, w.sources);
		rank(left, w.leftItems);
		return w;
	}

	/** What ONE source paid this sitting, ranked, or empty where it paid nothing. */
	java.util.List<BagItem> sessionSourceItems(String source)
	{
		java.util.Map<String, long[]> items = new java.util.LinkedHashMap<>();
		java.util.Map<String, Integer> ids = new java.util.LinkedHashMap<>();
		synchronized (lock)
		{
			if (!sessionRoll.has("sources") || !sessionRoll.get("sources").isJsonObject())
			{
				return new java.util.ArrayList<>();
			}
			JsonObject all = sessionRoll.getAsJsonObject("sources");
			for (String name : all.keySet())
			{
				if (!name.equalsIgnoreCase(source) || !all.get(name).isJsonObject())
				{
					continue;
				}
				JsonObject one = all.getAsJsonObject(name);
				if (!one.has("items") || !one.get("items").isJsonObject())
				{
					continue;
				}
				JsonObject its = one.getAsJsonObject("items");
				for (String id : its.keySet())
				{
					JsonObject e = its.getAsJsonObject(id);
					String label = e.has("n") ? e.get("n").getAsString() : id;
					long[] t = items.computeIfAbsent(label, k -> new long[2]);
					t[0] += asLong(e.get("q"));
					t[1] += asLong(e.get("v"));
					try
					{
						ids.putIfAbsent(label, Integer.parseInt(id));
					}
					catch (NumberFormatException ignored)
					{
						// a name-keyed entry from an older shape
					}
				}
			}
		}
		java.util.List<BagItem> out = new java.util.ArrayList<>();
		for (java.util.Map.Entry<String, long[]> e : items.entrySet())
		{
			out.add(new BagItem(ids.getOrDefault(e.getKey(), 0), e.getKey(),
				e.getValue()[0], e.getValue()[1]));
		}
		out.sort((a, b) -> Long.compare(b.value, a.value));
		return out;
	}

	// fold one day's breakdown into the running tally. `named` reads the stored
	// display name off the entry; a source is named by its own key.
	private static void gather(JsonObject day, String key,
		java.util.Map<String, long[]> into, boolean named)
	{
		if (!day.has(key) || !day.get(key).isJsonObject())
		{
			return;
		}
		JsonObject o = day.getAsJsonObject(key);
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

	// biggest by value first, as {name, qty, value}
	private static void rank(java.util.Map<String, long[]> from, java.util.List<String[]> into)
	{
		java.util.List<java.util.Map.Entry<String, long[]>> rows =
			new java.util.ArrayList<>(from.entrySet());
		rows.sort((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]));
		for (java.util.Map.Entry<String, long[]> e : rows)
		{
			into.add(new String[]{e.getKey(), String.valueOf(e.getValue()[0]),
				String.valueOf(e.getValue()[1])});
		}
	}

	/** Refresh the character sheet. Runs on the client thread, and once on the EDT
	 *  from the plugin's shutDown, which banks the session on a settings toggle; the
	 *  lock covers both. {@code collectionLog} is the capture's raw map, converted to
	 *  a tree here. */
	void setCharacter(String rsn, JsonObject skills, int combatLevel,
		java.util.Map<String, Object> collectionLog, JsonObject achievements)
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
			if (collectionLog != null)
			{
				// Anchor from what has just been READ, not from the merged result:
				// a page not opened this session keeps its old number through the
				// merge, and re-anchoring on it would throw away the observations
				// that number has been riding on.
				Object read = collectionLog.get("slayer_kcs");
				if (read instanceof java.util.Map)
				{
					for (java.util.Map.Entry<?, ?> e : ((java.util.Map<?, ?>) read).entrySet())
					{
						if (e.getKey() != null && e.getValue() instanceof Number)
						{
							anchorKill(String.valueOf(e.getKey()),
								((Number) e.getValue()).longValue(), "log", rsn);
						}
					}
				}
				// The session's capture is partial, covering only the pages browsed.
				// Clog data only grows; union it into the stored log.
				root.add("collection_log", mergeClog(
					root.has("collection_log") && root.get("collection_log").isJsonObject()
						? root.getAsJsonObject("collection_log") : new JsonObject(),
					gson.toJsonTree(collectionLog).getAsJsonObject()));
			}
			if (achievements != null)
			{
				root.add("achievements", achievements);
			}
			root.addProperty("updated_at", nowSec());
		}
	}

	/**
	 * Re-freeze the lifetime base at the current journal values. Called before the
	 * session counter store is cleared (shutdown, or a cloudSync toggle), so the
	 * fresh from-zero session can't take back what was already folded in.
	 */
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
				trackersBase = deepCopy(root.getAsJsonObject("trackers"));
			}
		}
	}

	/**
	 * Refresh the lifetime tracker counters from this session's live totals. Runs on
	 * the client thread, and once on the EDT from the plugin's shutDown; the lock
	 * covers both. {@code session} is the from-zero session snapshot; lifetime
	 * is base + session (max for the peak counters), so repeat calls never double up.
	 */
	void setTrackers(java.util.Map<String, Integer> session, String rsn)
	{
		if (!isReadyFor(rsn) || session == null)
		{
			return;
		}
		synchronized (lock)
		{
			JsonObject tr = new JsonObject();
			for (java.util.Map.Entry<String, Long> e : lifetimeOf(session).entrySet())
			{
				tr.addProperty(e.getKey(), e.getValue());
			}
			root.add("trackers", tr);
			root.addProperty("updated_at", nowSec());
		}
		revision++;
	}

	/**
	 * Every counter's lifetime figure, from the frozen base and a session, worked
	 * out without writing anything.
	 *
	 * <p>The same arithmetic setTrackers persists, lifted out so a reader can have
	 * it at any moment. The panel used to read the PERSISTED copy, which only
	 * moves when the journal is flushed, so a board showing what this account has
	 * gathered sat still through an hour of gathering and then jumped on logout.
	 */
	java.util.Map<String, Long> lifetimeOf(java.util.Map<String, Integer> session)
	{
		java.util.Map<String, Long> out = new java.util.HashMap<>();
		synchronized (lock)
		{
			java.util.Set<String> keys = new java.util.HashSet<>(
				session == null ? java.util.Collections.emptySet() : session.keySet());
			for (java.util.Map.Entry<String, JsonElement> e : trackersBase.entrySet())
			{
				keys.add(e.getKey());
			}
			for (String k : keys)
			{
				long base = trackersBase.has(k) && !trackersBase.get(k).isJsonNull()
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

	// ------------------------------------------------------------------
	// Persist (background executor)
	// ------------------------------------------------------------------

	/** Write the JSON record, or do nothing while no account is mounted. */
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
		catch (Exception e)   // noqa: the model is intact in memory; the next tick retries
		{
			// A full, read-only or locked directory drops every write while the panel,
			// served from memory, goes on looking live. Say so where it is read.
			log.warn("local flush failed", e);
			journalWarning = "Could not write the journal to disk: check free space and "
				+ "permissions on " + dir.getAbsolutePath() + ".";
		}
	}

	/** A fresh record: the identity fields here, the containers from normalise(),
	 *  which is the one place they are enumerated. */
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

	/** An empty drop source, as all three merge paths start one. */
	private static JsonObject newSource()
	{
		JsonObject src = new JsonObject();
		src.addProperty("kc", 0);
		src.addProperty("loots", 0);
		src.addProperty("value", 0);
		src.add("items", new JsonObject());
		return src;
	}

	private JsonObject deepCopy(JsonObject o)
	{
		if (o == null)
		{
			return new JsonObject();
		}
		JsonElement el = gson.fromJson(gson.toJson(o), JsonElement.class);
		return el != null && el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
	}

	/**
	 * Make sure a loaded record has the containers created here, which are the ones
	 * the character sheet, the drops table and the feed are written into.
	 *
	 * <p>NOT every container the ingest paths expect: the later stores (untaken and
	 * its two indexes, the slayer spine, loot_days, consumable_values) create their
	 * own on first write and are guarded at their own call sites. An ingest path
	 * added on the strength of the old promise, reading straight through
	 * getAsJsonObject the way recordLoot does, would find nothing there.
	 */
	private void normalise(JsonObject o, String rsn)
	{
		o.addProperty("schema", SCHEMA);
		o.addProperty("rsn", rsn);
		if (!o.has("first_seen"))
		{
			o.addProperty("first_seen", nowSec());
		}
		ensureObject(o, "skills");
		ensureObject(o, "chat_kcs");
		ensureObject(o, "kc_anchors");
		ensureObject(o, "collection_log");
		ensureObject(o, "achievements");
		ensureObject(o, "drops");
		ensureObject(o, "trackers");
		if (!o.has("feed") || !o.get("feed").isJsonArray())
		{
			o.add("feed", new JsonArray());
		}
	}

	private static void ensureObject(JsonObject o, String key)
	{
		if (!o.has(key) || !o.get(key).isJsonObject())
		{
			o.add(key, new JsonObject());
		}
	}

	private static long nowSec()
	{
		return System.currentTimeMillis() / 1000L;
	}

	// ------------------------------------------------------------------
	// Panel-facing reads (copies only; safe to call from the EDT)
	// ------------------------------------------------------------------

	ItemManager items()
	{
		return itemManager;
	}

	/** Why the journal is not keeping the record, or null while it is. */
	String journalWarning()
	{
		return journalWarning;
	}

	/** Lifetime counters as the journal knows them (base + this session). */
	java.util.Map<String, Long> trackersSnapshot()
	{
		java.util.Map<String, Long> out = new java.util.HashMap<>();
		synchronized (lock)
		{
			if (root != null && root.has("trackers") && root.get("trackers").isJsonObject())
			{
				for (java.util.Map.Entry<String, JsonElement> e
					: root.getAsJsonObject("trackers").entrySet())
				{
					if (!e.getValue().isJsonNull())
					{
						out.put(e.getKey(), e.getValue().getAsLong());
					}
				}
			}
		}
		return out;
	}

	static final class SourceRow
	{
		final String name;
		final int kc;
		final int loots;
		final long value;
		final Double pb;
		// epoch ms, 0 = unknown; comes in from the Loot Tracker import's per-source
		// range and extends as play continues.
		final long firstMs;
		final long lastMs;
		// lower-cased name of every item the bag holds a copy of. The dryness book
		// reads it as obtained: a unique already looted is never a chase, whatever the
		// stored log says.
		final java.util.Set<String> looted;

		SourceRow(String name, int kc, int loots, long value, Double pb,
			long firstMs, long lastMs)
		{
			this(name, kc, loots, value, pb, firstMs, lastMs, java.util.Collections.emptySet());
		}

		SourceRow(String name, int kc, int loots, long value, Double pb,
			long firstMs, long lastMs, java.util.Set<String> looted)
		{
			this.name = name;
			this.kc = kc;
			this.loots = loots;
			this.value = value;
			this.pb = pb;
			this.firstMs = firstMs;
			this.lastMs = lastMs;
			this.looted = looted;
		}
	}

	/** Every drop source, unsorted (the panel ranks). */
	java.util.List<SourceRow> dropSources()
	{
		java.util.List<SourceRow> out = new java.util.ArrayList<>();
		synchronized (lock)
		{
			if (root == null || !root.has("drops"))
			{
				return out;
			}
			for (java.util.Map.Entry<String, JsonElement> e
				: root.getAsJsonObject("drops").entrySet())
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
					lootedNames(src)));
			}
		}
		return out;
	}

	// The names in one source's bag, lower-cased, holding only entries with a copy
	// in hand: a zero-quantity row is a name the bag once knew, not an item owned.
	private static java.util.Set<String> lootedNames(JsonObject src)
	{
		if (!src.has("items") || !src.get("items").isJsonObject())
		{
			return java.util.Collections.emptySet();
		}
		java.util.Set<String> out = new java.util.HashSet<>();
		for (java.util.Map.Entry<String, JsonElement> e
			: src.getAsJsonObject("items").entrySet())
		{
			if (!e.getValue().isJsonObject())
			{
				continue;
			}
			JsonObject it = e.getValue().getAsJsonObject();
			if (asLong(it.get("qty")) <= 0 || !it.has("name") || it.get("name").isJsonNull())
			{
				continue;
			}
			String name = it.get("name").getAsString().trim().toLowerCase(java.util.Locale.ROOT);
			if (!name.isEmpty())
			{
				out.add(name);
			}
		}
		return out.isEmpty() ? java.util.Collections.emptySet() : out;
	}

	/** Newest feed entries, newest first (deep copies). */
	java.util.List<JsonObject> feedNewest(int n)
	{
		java.util.List<JsonObject> out = new java.util.ArrayList<>();
		synchronized (lock)
		{
			if (root == null || !root.has("feed") || !root.get("feed").isJsonArray())
			{
				return out;
			}
			JsonArray feed = root.getAsJsonArray("feed");
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

	java.util.List<RecentDrop> recentDrops()
	{
		synchronized (lock)
		{
			return new java.util.ArrayList<>(recentDrops);
		}
	}

	static final class BagItem
	{
		final int itemId;
		final String name;
		final long qty;
		final long value;

		BagItem(int itemId, String name, long qty, long value)
		{
			this.itemId = itemId;
			this.name = name;
			this.qty = qty;
			this.value = value;
		}
	}

	/**
	 * Canonicalise an item id, then name it and price the stack. Notes and placeholders
	 * price as the real item. Client thread; the value is frozen into the record here
	 * and never repriced.
	 *
	 * <p>An id the ItemManager cannot compose is named after its number instead of
	 * throwing: one unknown id must not abort a caller's whole loop.
	 */
	BagItem price(int id, long qty)
	{
		int canon = itemManager.canonicalize(id);
		String name;
		try
		{
			name = itemManager.getItemComposition(canon).getName();
		}
		catch (Exception e)   // noqa: unknown id, fall back to the raw number
		{
			name = "Item " + id;
		}
		long each = Math.max(0, itemManager.getItemPrice(canon));
		return new BagItem(canon, name, qty, each * qty);
	}

	/** How an item is filed in a source's bag: its id when one is known, else
	 *  {@code n:} and the lowercased name. Both merge paths key the same way or the
	 *  same item lands on two lines. */
	private static String bagKey(int id, String name)
	{
		return id > 0 ? String.valueOf(id)
			: "n:" + (name == null ? "" : name.toLowerCase(java.util.Locale.ROOT));
	}

	/** One source's whole record from the core Loot Tracker's local store, already
	 *  canonicalised and priced by the caller (client thread). */
	static final class LootSeed
	{
		final String source;
		final int kills;
		final long firstMs;
		final long lastMs;
		final java.util.List<BagItem> items;

		LootSeed(String source, int kills, long firstMs, long lastMs,
			java.util.List<BagItem> items)
		{
			this.source = source;
			this.kills = kills;
			this.firstMs = firstMs;
			this.lastMs = lastMs;
			this.items = items;
		}
	}

	/**
	 * Floor this account's drops with the core Loot Tracker's own lifetime record.
	 * kc and loots take the tracker's event count as a lower bound (a higher
	 * game-reported kc survives), item qty/value match by id then by name, source
	 * value takes the priced sum, first_seen/last_seen extend as min/max. A re-run
	 * can only raise floors it already set.
	 */
	void floorLootTracker(java.util.List<LootSeed> seeds, String rsn)
	{
		if (!isReadyFor(rsn) || seeds == null)
		{
			return;
		}
		revision++;
		synchronized (lock)
		{
			JsonObject drops = root.has("drops") && root.get("drops").isJsonObject()
				? root.getAsJsonObject("drops") : new JsonObject();
			root.add("drops", drops);
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
				if (!src.has("items") || !src.get("items").isJsonObject())
				{
					src.add("items", new JsonObject());
				}
				JsonObject items = src.getAsJsonObject("items");
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
						// a name-keyed entry picks up its real id here
						for (java.util.Map.Entry<String, JsonElement> e : items.entrySet())
						{
							if (e.getValue().isJsonObject())
							{
								JsonObject it = e.getValue().getAsJsonObject();
								if (it.has("name") && !it.get("name").isJsonNull()
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
					// A floor on a FACT is fine: the archive counts every drop this
					// account ever took, so a higher quantity is one this journal
					// had not seen. A floor on a PRICE is not the same thing at
					// all. The seed is one day's price multiplied by a lifetime
					// quantity, so taking the larger of two valuations is not
					// recovering anything, it is ratcheting the record upward on
					// whichever day the import happened to run. It moved a
					// Mithril spear from 172 to 231 between one export and the
					// next, and it can only ever go up.
					//
					// So quantity is floored, and a price is only ever SEEDED:
					// written into a row that has none, never over one that has.
					if (b.qty > (hit.has("qty") ? hit.get("qty").getAsLong() : 0))
					{
						hit.addProperty("qty", b.qty);
					}
					if (!hit.has("value") || hit.get("value").getAsLong() <= 0)
					{
						hit.addProperty("value", b.value);
					}
				}
				// The header is the sum of the bag under it, not the seed's own
				// subtotal. Those two had drifted apart on 16 of my sources by
				// 813,301 gp, with Vorkath's header 493,431 above its own rows.
				long bagged = 0;
				for (java.util.Map.Entry<String, JsonElement> e : items.entrySet())
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
			// Hundreds of rows have just been seeded from the Loot Tracker. None of
			// them is a kill that happened since an anchor was taken, so the
			// baselines move with them rather than the counts.
			rebaseAnchors();
		}
	}

	/** One source's item bag, unsorted. */
	java.util.List<BagItem> sourceItems(String source)
	{
		java.util.List<BagItem> out = new java.util.ArrayList<>();
		synchronized (lock)
		{
			if (root == null || !root.has("drops") || !root.get("drops").isJsonObject())
			{
				return out;
			}
			JsonObject drops = root.getAsJsonObject("drops");
			if (!drops.has(source) || !drops.get(source).isJsonObject())
			{
				return out;
			}
			JsonObject src = drops.getAsJsonObject(source);
			if (!src.has("items") || !src.get("items").isJsonObject())
			{
				return out;
			}
			for (java.util.Map.Entry<String, JsonElement> e
				: src.getAsJsonObject("items").entrySet())
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

	/** Left-behind loot, priced at record like drops and aggregated per source
	 *  ({@code untaken: {source: {qty, value, kills}}}). {@code kills} is the
	 *  capture's count of the kills that left at least one of the event's stacks,
	 *  the unit "Drops taken" subtracts in; an imported or older event carries none
	 *  and reads as 0. */
	private void recordUntaken(JsonObject data)
	{
		String source = data.has("source") && !data.get("source").isJsonNull()
			? data.get("source").getAsString() : "Unknown";
		JsonArray items = data.has("items") && data.get("items").isJsonArray()
			? data.getAsJsonArray("items") : null;
		if (items == null)
		{
			return;
		}
		int kills = (int) Math.max(0, data.has("kills") ? asLong(data.get("kills")) : 0);
		long qty = 0;
		long value = 0;
		java.util.List<BagItem> perItem = new java.util.ArrayList<>();
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
			JsonObject untaken = root.has("untaken") && root.get("untaken").isJsonObject()
				? root.getAsJsonObject("untaken") : new JsonObject();
			JsonObject src = untaken.has(source) && untaken.get(source).isJsonObject()
				? untaken.getAsJsonObject(source) : new JsonObject();
			src.addProperty("qty", (src.has("qty") ? src.get("qty").getAsLong() : 0) + qty);
			src.addProperty("value", (src.has("value") ? src.get("value").getAsLong() : 0) + value);
			src.addProperty("kills", (src.has("kills") ? asLong(src.get("kills")) : 0) + kills);
			untaken.add(source, src);
			root.add("untaken", untaken);
			// the same tally keyed by item name
			JsonObject byItem = root.has("untaken_items") && root.get("untaken_items").isJsonObject()
				? root.getAsJsonObject("untaken_items") : new JsonObject();
			for (BagItem b : perItem)
			{
				JsonObject e = byItem.has(b.name) && byItem.get(b.name).isJsonObject()
					? byItem.getAsJsonObject(b.name) : new JsonObject();
				e.addProperty("qty", (e.has("qty") ? e.get("qty").getAsLong() : 0) + b.qty);
				e.addProperty("value", (e.has("value") ? e.get("value").getAsLong() : 0) + b.value);
				byItem.add(b.name, e);
			}
			root.add("untaken_items", byItem);
			rollLeft(qty, value, kills, perItem);
			// …and the pairing, so the lens drills from either end: which items a
			// source left, and which sources left an item.
			JsonObject pairs = root.has("untaken_pairs") && root.get("untaken_pairs").isJsonObject()
				? root.getAsJsonObject("untaken_pairs") : new JsonObject();
			JsonObject bag = pairs.has(source) && pairs.get(source).isJsonObject()
				? pairs.getAsJsonObject(source) : new JsonObject();
			for (BagItem b : perItem)
			{
				JsonObject e = bag.has(b.name) && bag.get(b.name).isJsonObject()
					? bag.getAsJsonObject(b.name) : new JsonObject();
				e.addProperty("id", b.itemId);
				e.addProperty("qty", (e.has("qty") ? asLong(e.get("qty")) : 0) + b.qty);
				e.addProperty("value", (e.has("value") ? asLong(e.get("value")) : 0) + b.value);
				bag.add(b.name, e);
			}
			pairs.add(source, bag);
			root.add("untaken_pairs", pairs);
			sessionUntaken += qty;
			sessionUntakenValue += value;
			sessionUntakenKills += kills;
			root.addProperty("updated_at", nowSec());
		}
	}

	/** A name/qty/value row: untaken sources, untaken items, task monsters. An
	 *  untaken source also carries the kills that left its stacks; 0 elsewhere. */
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

		UntakenRow(String name, long qty, long value, long kills)
		{
			this.name = name;
			this.qty = qty;
			this.value = value;
			this.kills = kills;
		}
	}

	/** Add the price of one consumption to a typed key's lifetime gp. Client thread. */
	void addConsumableValue(String key, long gp, String rsn)
	{
		if (!isReadyFor(rsn) || key == null || key.isEmpty() || gp <= 0)
		{
			return;
		}
		revision++;
		synchronized (lock)
		{
			JsonObject store = root.has("consumable_values") && root.get("consumable_values").isJsonObject()
				? root.getAsJsonObject("consumable_values") : new JsonObject();
			long cur = store.has(key) && !store.get(key).isJsonNull() ? store.get(key).getAsLong() : 0;
			store.addProperty(key, cur + gp);
			root.add("consumable_values", store);
			root.addProperty("updated_at", nowSec());
		}
	}

	/** Remember an item id this account gathered. Client thread, once per resolved
	 *  gathering action; it lives in the record so an ore mined last week and binned
	 *  today still reads as a resource dropped. */
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
			JsonArray ids = root.has("gathered_items") && root.get("gathered_items").isJsonArray()
				? root.getAsJsonArray("gathered_items") : new JsonArray();
			ids.add(itemId);
			root.add("gathered_items", ids);
			root.addProperty("updated_at", nowSec());
		}
	}

	@Override
	public boolean wasGathered(int itemId)
	{
		return itemId > 0 && gatheredItems.contains(itemId);
	}

	/** The lifetime base (pre-session) for one counter, or 0 when unknown. */
	long trackerBase(String key)
	{
		synchronized (lock)
		{
			return trackersBase != null && trackersBase.has(key)
				&& !trackersBase.get(key).isJsonNull()
				? trackersBase.get(key).getAsLong() : 0;
		}
	}

	// ------------------------------------------------------------------
	// The slayer journey (task-by-task, kept locally)
	// ------------------------------------------------------------------

	// runaway guard
	private static final int SLAYER_TASK_CAP = 1000;

	/** The slayer store, created on first use. Callers hold {@code lock}. */
	private JsonObject slayerRoot()
	{
		JsonObject sl = root.has("slayer") && root.get("slayer").isJsonObject()
			? root.getAsJsonObject("slayer") : new JsonObject();
		if (!root.has("slayer") || !root.get("slayer").isJsonObject())
		{
			root.add("slayer", sl);
		}
		if (!sl.has("tasks") || !sl.get("tasks").isJsonArray())
		{
			sl.add("tasks", new JsonArray());
		}
		return sl;
	}

	/**
	 * How long after a completion the finishing kill's loot may still land on it, in
	 * seconds. RuneLite clears the live task on the completing tick, so that kill's
	 * loot arrives after the segment has closed; the server attached it to the
	 * completion within this window and the segmenter folded it into the task.
	 */
	static final long SLAYER_FINAL_KILL_GRACE = 30;

	/** The newest task segment if it is open and names {@code task}, else null.
	 *  Callers hold {@code lock}. */
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
		return seg.has("open") && !seg.get("open").isJsonNull() && seg.get("open").getAsBoolean();
	}

	private static boolean namesTask(JsonObject seg, String task)
	{
		return seg.has("task") && !seg.get("task").isJsonNull()
			&& task.equalsIgnoreCase(seg.get("task").getAsString());
	}

	/** A numeric field, or null when absent. */
	private static Long optLong(JsonObject o, String key)
	{
		return o.has(key) && !o.get(key).isJsonNull() && o.get(key).isJsonPrimitive()
			? Long.valueOf(asLong(o.get(key))) : null;
	}

	/**
	 * The newest segment when this kill continues it: open, the same task, and the
	 * live counter not having jumped back up. A strict upward jump in
	 * {@code slayerTaskRemaining} between consecutive same-task kills is a fresh
	 * assignment even when no completion line was seen (finished on another client,
	 * both lines missed), so two back-to-back same-monster tasks split on the counter
	 * alone instead of merging into one bloated row. Callers hold {@code lock}.
	 */
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

	/**
	 * A segment closed by a completion within the finishing-kill grace that this kill
	 * belongs to, else null. The finishing kill carries the task alone: its counter
	 * went with the task, so a kill stamped by a live task ({@code live}) is the next
	 * assignment unless its counter still continues the segment's. Callers hold
	 * {@code lock}.
	 */
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

	/**
	 * A parked segment this kill resumes, moved to the end so it is the current one
	 * again, else null. Switching to a different monster parks the task; returning to
	 * it with a counter that continues downward ({@code rem <= min_rem}) is the same
	 * assignment, so the two runs are one task rather than two rows. A higher counter
	 * is a fresh assignment and supersedes the parked run. Only the newest segment of
	 * the task is eligible, and only while no completion has closed it. Callers hold
	 * {@code lock}.
	 */
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

	/** One on-task kill: extend, fold, resume or open the task segment it belongs
	 *  to. {@code data} is the loot event; a kill with no task stamp is not on task.
	 *  Callers hold lock. */
	private void slayerLoot(JsonObject data, long value, String monster, JsonArray items)
	{
		String task = data.has("slayerTask") && !data.get("slayerTask").isJsonNull()
			? data.get("slayerTask").getAsString() : null;
		if (task == null || task.isEmpty())
		{
			return;
		}
		Long rem = optLong(data, "slayerTaskRemaining");
		Long initialStamp = optLong(data, "slayerTaskInitial");
		long initial = initialStamp == null ? 0 : initialStamp;
		// stamped while the task was live, counter and all
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
			seg = new JsonObject();
			seg.addProperty("task", task);
			seg.addProperty("kills", 0);
			seg.addProperty("assignment", 0);
			seg.addProperty("value", 0);
			seg.addProperty("open", true);
			tasks.add(seg);
			while (tasks.size() > SLAYER_TASK_CAP)
			{
				tasks.remove(0);
			}
		}
		if (fold)
		{
			// The finishing kill, landing after its completion closed the segment: the
			// game's count already stands as kills, so it fills a no-drop rather than
			// adding a kill, and the completion instant stays the segment's date.
			long total = asLong(seg.get("kills"));
			long logged = loggedKills(seg) + 1;
			seg.addProperty("logged", logged);
			setNoLootKills(seg, total - logged);
		}
		else
		{
			seg.addProperty("kills", seg.get("kills").getAsLong() + 1);
			seg.addProperty("ts", nowSec());
			// RuneLite's initialAmount is restored from config and can be stale; the
			// counter is self-proving (remaining is stamped post-decrement), so any
			// reading R means the assignment was at least R + 1. The higher bound wins.
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
		// What the task was made of: a "blue dragons" assignment takes brutals too,
		// and its loot is a different question from the monster's lifetime bag.
		if (monster != null && !monster.isEmpty())
		{
			JsonObject mons = seg.has("monsters") && seg.get("monsters").isJsonObject()
				? seg.getAsJsonObject("monsters") : new JsonObject();
			mons.addProperty(monster, (mons.has(monster) ? asLong(mons.get(monster)) : 0) + 1);
			seg.add("monsters", mons);
		}
		if (items != null)
		{
			JsonObject bag = seg.has("items") && seg.get("items").isJsonObject()
				? seg.getAsJsonObject("items") : new JsonObject();
			for (JsonElement ie : items)
			{
				JsonObject it = ie.getAsJsonObject();
				String name = it.get("name").getAsString();
				JsonObject row = bag.has(name) && bag.get(name).isJsonObject()
					? bag.getAsJsonObject(name) : new JsonObject();
				row.addProperty("id", it.get("id").getAsInt());
				row.addProperty("qty", (row.has("qty") ? asLong(row.get("qty")) : 0)
					+ it.get("qty").getAsLong());
				row.addProperty("value", (row.has("value") ? asLong(row.get("value")) : 0)
					+ it.get("value").getAsLong());
				bag.add(name, row);
			}
			seg.add("items", bag);
		}
	}

	/** The kills the loot actually witnessed: kept apart from the game's own count
	 *  once a completion has set that. A segment closed before the split kept only
	 *  the total and its no-drops, which recover it. */
	private static long loggedKills(JsonObject seg)
	{
		Long logged = optLong(seg, "logged");
		if (logged != null)
		{
			return logged;
		}
		long kills = seg.has("kills") ? asLong(seg.get("kills")) : 0;
		long noLoot = seg.has("noLootKills") ? asLong(seg.get("noLootKills")) : 0;
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

	/**
	 * A task completion: close the open segment, opening one first if the whole task
	 * went unwitnessed. The finished line's exact kill count trues up the loot spine;
	 * kills the drops never saw surface as {@code noLootKills}.
	 */
	private void recordSlayerCompletion(JsonObject data)
	{
		String task = data.has("task") && !data.get("task").isJsonNull()
			? data.get("task").getAsString() : null;
		if (task == null || task.isEmpty())
		{
			return;
		}
		Long exact = data.has("killCount") && !data.get("killCount").isJsonNull()
			? data.get("killCount").getAsLong() : null;
		Long streak = data.has("count") && !data.get("count").isJsonNull()
			? data.get("count").getAsLong() : null;
		synchronized (lock)
		{
			JsonObject sl = slayerRoot();
			JsonArray tasks = sl.getAsJsonArray("tasks");
			JsonObject seg = openSegment(tasks, task);
			if (seg == null)
			{
				seg = new JsonObject();
				seg.addProperty("task", task);
				seg.addProperty("kills", 0);
				seg.addProperty("assignment", 0);
				seg.addProperty("value", 0);
				tasks.add(seg);
				while (tasks.size() > SLAYER_TASK_CAP)
				{
					tasks.remove(0);
				}
			}
			// The finished line's N is the game's own number: it becomes the task's
			// kills and its size, and the kills the loot witnessed are kept apart as
			// logged, so a surviving double-emit never shows more than N.
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
			long done = sl.has("completed") ? asLong(sl.get("completed")) : 0;
			// the streak line's lifetime total wins when it's ahead
			sl.addProperty("completed", streak != null && streak > done ? streak : done + 1);
			root.addProperty("updated_at", nowSec());
		}
	}

	/**
	 * Every item the slayer journey logged inside a window, summed across tasks and
	 * ranked by what it came to. A task carries the stamp of its own close, so this
	 * is a filter over the journey rather than a delta: pass the whole of time to
	 * get the lot.
	 *
	 * <p>On-task only by construction. The ledger's per-source totals cannot tell a
	 * task kill from a stray one; the journey only ever holds the former.
	 *
	 * <p>A segment is one bucket carrying one stamp, and its contents can span
	 * days, so a bounded window can only honestly claim the segments that CLOSED
	 * inside it. An open one is stamped with its most recent kill and is dragged
	 * whole into any window that catches a single kill of it, which put a task
	 * handed out on Tuesday under a heading reading "This session". Unbounded, an
	 * open task is simply part of the record and counts.
	 */
	java.util.List<BagItem> onTaskLoot(long fromMs, long toMs, boolean includeOpen)
	{
		return onTaskLoot(fromMs, toMs, null, includeOpen);
	}

	/**
	 * Every item the whole ledger holds, summed across sources and ranked by
	 * what it came to. One walk of `drops` under one lock, rather than the
	 * source list plus a bag read per source.
	 */
	java.util.List<BagItem> allLoot()
	{
		java.util.Map<String, long[]> summed = new java.util.LinkedHashMap<>();
		java.util.Map<String, Integer> ids = new java.util.LinkedHashMap<>();
		synchronized (lock)
		{
			if (root == null || !root.has("drops") || !root.get("drops").isJsonObject())
			{
				return new java.util.ArrayList<>();
			}
			for (java.util.Map.Entry<String, JsonElement> src
				: root.getAsJsonObject("drops").entrySet())
			{
				if (!src.getValue().isJsonObject())
				{
					continue;
				}
				JsonObject o = src.getValue().getAsJsonObject();
				if (!o.has("items") || !o.get("items").isJsonObject())
				{
					continue;
				}
				for (java.util.Map.Entry<String, JsonElement> it
					: o.getAsJsonObject("items").entrySet())
				{
					if (!it.getValue().isJsonObject())
					{
						continue;
					}
					JsonObject v = it.getValue().getAsJsonObject();
					String name = v.has("name") && !v.get("name").isJsonNull()
						? v.get("name").getAsString() : null;
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
		java.util.List<BagItem> out = new java.util.ArrayList<>();
		for (java.util.Map.Entry<String, long[]> e : summed.entrySet())
		{
			out.add(new BagItem(ids.getOrDefault(e.getKey(), -1), e.getKey(),
				e.getValue()[0], e.getValue()[1]));
		}
		out.sort(java.util.Comparator.comparingLong((BagItem b) -> b.value).reversed());
		return out;
	}

	/**
	 * Every task name the journey holds, newest first and without repeats, which
	 * is the order a picker wants: what you fought lately, first.
	 */
	java.util.List<String> taskNames()
	{
		java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
		synchronized (lock)
		{
			if (root == null || !root.has("slayer") || !root.get("slayer").isJsonObject())
			{
				return new java.util.ArrayList<>();
			}
			JsonObject sl = root.getAsJsonObject("slayer");
			if (!sl.has("tasks") || !sl.get("tasks").isJsonArray())
			{
				return new java.util.ArrayList<>();
			}
			JsonArray arr = sl.getAsJsonArray("tasks");
			// the journal keeps them oldest first; a picker wants the newest at
			// the top, so this walks back
			for (int i = arr.size() - 1; i >= 0; i--)
			{
				JsonElement e = arr.get(i);
				if (!e.isJsonObject())
				{
					continue;
				}
				JsonObject t = e.getAsJsonObject();
				if (t.has("task") && !t.get("task").isJsonNull())
				{
					String n = t.get("task").getAsString().trim();
					if (!n.isEmpty())
					{
						names.add(n);
					}
				}
			}
		}
		return new java.util.ArrayList<>(names);
	}

	/**
	 * One slayer assignment, as an NPC's page reads it.
	 *
	 * <p>The take is the TASK's, not this monster's: a task carries one items
	 * map and a separate monsters map, and nothing inside it links a drop to
	 * the thing that dropped it. On this account one "Blue dragons" assignment
	 * holds 45 blue dragons, 3 babies and 97 Vorkath, so cutting its loot up by
	 * monster would hand a reader Vorkath's drops under a blue dragon. The row
	 * is labelled for the task for that reason.
	 */
	static final class Assignment
	{
		final String task;
		final long ts;
		final long killsHere;
		final long kills;
		final long value;

		Assignment(String task, long ts, long killsHere, long kills, long value)
		{
			this.task = task;
			this.ts = ts;
			this.killsHere = killsHere;
			this.kills = kills;
			this.value = value;
		}
	}

	/** Whether a task's stamp puts it inside the window. An unstamped task is in. */
	private static boolean taskInside(JsonObject t, long fromMs, long toMs)
	{
		long ms = (long) (asDouble(t.get("ts")) * 1000);
		return !(ms > 0 && (ms < fromMs || ms > toMs));
	}

	private static String taskName(JsonObject t)
	{
		return t.has("task") && !t.get("task").isJsonNull()
			? t.get("task").getAsString() : "";
	}

	private JsonArray taskArray()
	{
		if (root == null || !root.has("slayer") || !root.get("slayer").isJsonObject())
		{
			return new JsonArray();
		}
		JsonObject sl = root.getAsJsonObject("slayer");
		return sl.has("tasks") && sl.get("tasks").isJsonArray()
			? sl.getAsJsonArray("tasks") : new JsonArray();
	}

	/**
	 * Every item the tasks paid, inside the window: name to {qty, value}.
	 *
	 * <p>One pass over the journey, because the answer is asked of every row of
	 * a board rather than once. It is also the only honest way to ask whether an
	 * item HAS an on-task side at all: 287 of the 655 names on this account do,
	 * and the other 368 must not be offered a filter that would show them
	 * nothing.
	 */
	java.util.Map<String, long[]> onTaskItems(long fromMs, long toMs)
	{
		java.util.Map<String, long[]> out = new java.util.LinkedHashMap<>();
		synchronized (lock)
		{
			for (JsonElement e : taskArray())
			{
				if (!e.isJsonObject())
				{
					continue;
				}
				JsonObject t = e.getAsJsonObject();
				if (!taskInside(t, fromMs, toMs)
					|| !t.has("items") || !t.get("items").isJsonObject())
				{
					continue;
				}
				for (java.util.Map.Entry<String, JsonElement> it
					: t.getAsJsonObject("items").entrySet())
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

	/**
	 * Every monster the tasks were fought against, inside the window: name to
	 * kills. Exact, unlike the loot: a task DOES say how many of each thing it
	 * killed.
	 */
	java.util.Map<String, Long> onTaskKills(long fromMs, long toMs)
	{
		java.util.Map<String, Long> out = new java.util.LinkedHashMap<>();
		synchronized (lock)
		{
			for (JsonElement e : taskArray())
			{
				if (!e.isJsonObject())
				{
					continue;
				}
				JsonObject t = e.getAsJsonObject();
				if (!taskInside(t, fromMs, toMs)
					|| !t.has("monsters") || !t.get("monsters").isJsonObject())
				{
					continue;
				}
				for (java.util.Map.Entry<String, JsonElement> m
					: t.getAsJsonObject("monsters").entrySet())
				{
					out.merge(m.getKey(), asLong(m.getValue()), Long::sum);
				}
			}
		}
		return out;
	}

	/**
	 * One item's on-task total split by the TASK that paid it, dearest first.
	 * What the FROM list on an item's page becomes when the filter is on task:
	 * the record can name the assignment exactly and the monster not at all.
	 */
	java.util.List<Object[]> onTaskItemByTask(String itemName, long fromMs, long toMs)
	{
		java.util.Map<String, long[]> by = new java.util.LinkedHashMap<>();
		if (itemName == null)
		{
			return new java.util.ArrayList<>();
		}
		synchronized (lock)
		{
			for (JsonElement e : taskArray())
			{
				if (!e.isJsonObject())
				{
					continue;
				}
				JsonObject t = e.getAsJsonObject();
				if (!taskInside(t, fromMs, toMs)
					|| !t.has("items") || !t.get("items").isJsonObject())
				{
					continue;
				}
				JsonObject items = t.getAsJsonObject("items");
				for (java.util.Map.Entry<String, JsonElement> it : items.entrySet())
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
		java.util.List<Object[]> out = new java.util.ArrayList<>();
		for (java.util.Map.Entry<String, long[]> e : by.entrySet())
		{
			out.add(new Object[]{e.getKey(), e.getValue()[0], e.getValue()[1]});
		}
		out.sort((a, b) -> Long.compare((Long) b[2], (Long) a[2]));
		return out;
	}

	/**
	 * Every assignment that included one monster, newest first.
	 *
	 * <p>This is what an NPC's page can honestly show on task. The kills are
	 * that monster's and exact; the worth is the whole assignment's, which is
	 * why the row names the task rather than the monster.
	 */
	java.util.List<Assignment> onTaskAssignments(String npc, long fromMs, long toMs)
	{
		java.util.List<Assignment> out = new java.util.ArrayList<>();
		if (npc == null)
		{
			return out;
		}
		synchronized (lock)
		{
			JsonArray tasks = taskArray();
			for (int i = tasks.size() - 1; i >= 0; i--)
			{
				if (!tasks.get(i).isJsonObject())
				{
					continue;
				}
				JsonObject t = tasks.get(i).getAsJsonObject();
				if (!taskInside(t, fromMs, toMs)
					|| !t.has("monsters") || !t.get("monsters").isJsonObject())
				{
					continue;
				}
				long here = 0;
				boolean found = false;
				for (java.util.Map.Entry<String, JsonElement> m
					: t.getAsJsonObject("monsters").entrySet())
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

	/**
	 * The same, narrowed to one task by name.
	 *
	 * <p>By NAME rather than by segment: a reader asking what Nechryael have
	 * paid means all eleven of them, not the one that closed on Tuesday.
	 */
	java.util.List<BagItem> onTaskLoot(long fromMs, long toMs, String onlyTask,
		boolean includeOpen)
	{
		java.util.Map<String, long[]> summed = new java.util.LinkedHashMap<>();
		java.util.Map<String, Integer> ids = new java.util.LinkedHashMap<>();
		synchronized (lock)
		{
			if (root == null || !root.has("slayer") || !root.get("slayer").isJsonObject())
			{
				return new java.util.ArrayList<>();
			}
			JsonObject sl = root.getAsJsonObject("slayer");
			if (!sl.has("tasks") || !sl.get("tasks").isJsonArray())
			{
				return new java.util.ArrayList<>();
			}
			for (JsonElement e : sl.getAsJsonArray("tasks"))
			{
				if (!e.isJsonObject())
				{
					continue;
				}
				JsonObject t = e.getAsJsonObject();
				if (!includeOpen && isOpen(t))
				{
					continue;
				}
				long ms = (long) (asDouble(t.get("ts")) * 1000);
				if (ms > 0 && (ms < fromMs || ms > toMs))
				{
					continue;
				}
				if (onlyTask != null && !onlyTask.equalsIgnoreCase(
					t.has("task") && !t.get("task").isJsonNull()
						? t.get("task").getAsString() : ""))
				{
					continue;
				}
				if (!t.has("items") || !t.get("items").isJsonObject())
				{
					continue;
				}
				for (java.util.Map.Entry<String, JsonElement> it
					: t.getAsJsonObject("items").entrySet())
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
		java.util.List<BagItem> out = new java.util.ArrayList<>();
		for (java.util.Map.Entry<String, long[]> e : summed.entrySet())
		{
			out.add(new BagItem(ids.getOrDefault(e.getKey(), 0), e.getKey(),
				e.getValue()[0], e.getValue()[1]));
		}
		out.sort(java.util.Comparator.comparingLong((BagItem b) -> b.value).reversed());
		return out;
	}

	/**
	 * The superior forms, which a task's monster roll names but never marks as
	 * one. Taken from the site's own reference (reference/osrs_superiors.json,
	 * itself from the wiki) so the two count the same thing. Matched without
	 * case: the journal has "Shadow Wyrm" and "Malevolent Mage" as the game
	 * spelled them at the time.
	 */
	private static final java.util.Set<String> SUPERIORS = new java.util.HashSet<>(
		java.util.Arrays.asList(
		"abhorrent spectre", "ancient custodian", "basilisk sentinel",
		"blood-starved venator", "cave abomination", "chasm crawler",
		"choke devil", "cockathrice", "colossal hydra", "crushing hand",
		"dire gryphon", "dreadborn araxyte", "elder aquanite",
		"flaming pyrelord", "giant rockslug", "greater abyssal demon",
		"guardian drake", "infernal pyrelord", "insatiable bloodveld",
		"insatiable mutated bloodveld", "king kurask", "magma strykewyrm",
		"malevolent mage", "marble gargoyle", "monstrous basilisk",
		"mutated terrorbird", "mutated tortoise", "nechryarch", "night beast",
		"nuclear smoke devil", "repugnant spectre", "screaming banshee",
		"screaming twisted banshee", "shadow wyrm", "spiked turoth",
		"vitreous chilled jelly", "vitreous jelly", "vitreous warped jelly"));

	/**
	 * What the on-task loot was killed out of, inside the same window: the kills
	 * that dropped something, how many of those were a superior, and how many
	 * TASKS the loot came off. Kills that dropped nothing are counted separately
	 * by the task and are not here, which is why this reads lower than a slayer
	 * counter.
	 *
	 * <p>Narrowed by task name where one is given, so the figures belong to the
	 * same bag the board is showing rather than to every task in the window.
	 */
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
			if (root == null || !root.has("slayer") || !root.get("slayer").isJsonObject())
			{
				return new long[]{0, 0, 0};
			}
			JsonObject sl = root.getAsJsonObject("slayer");
			if (!sl.has("tasks") || !sl.get("tasks").isJsonArray())
			{
				return new long[]{0, 0, 0};
			}
			for (JsonElement e : sl.getAsJsonArray("tasks"))
			{
				if (!e.isJsonObject())
				{
					continue;
				}
				JsonObject t = e.getAsJsonObject();
				if (!includeOpen && isOpen(t))
				{
					continue;
				}
				long ms = (long) (asDouble(t.get("ts")) * 1000);
				if (ms > 0 && (ms < fromMs || ms > toMs))
				{
					continue;
				}
				if (onlyTask != null && !onlyTask.equalsIgnoreCase(
					t.has("task") && !t.get("task").isJsonNull()
						? t.get("task").getAsString() : ""))
				{
					continue;
				}
				tasks++;
				kills += asLong(t.get("kills"));
				if (!t.has("monsters") || !t.get("monsters").isJsonObject())
				{
					continue;
				}
				for (java.util.Map.Entry<String, JsonElement> m
					: t.getAsJsonObject("monsters").entrySet())
				{
					if (SUPERIORS.contains(m.getKey().toLowerCase(java.util.Locale.ROOT)))
					{
						superiors += asLong(m.getValue());
					}
				}
			}
		}
		return new long[]{kills, superiors, tasks};
	}

	/** The journey as the journal knows it, shaped for the panel (newest first). */
	SlayerJourney slayerJourney()
	{
		synchronized (lock)
		{
			if (root == null)
			{
				return null;
			}
			JsonObject sl = root.has("slayer") && root.get("slayer").isJsonObject()
				? root.getAsJsonObject("slayer") : new JsonObject();
			JsonArray tasks = sl.has("tasks") && sl.get("tasks").isJsonArray()
				? sl.getAsJsonArray("tasks") : new JsonArray();
			java.util.List<SlayerTask> out =
				new java.util.ArrayList<>(tasks.size());
			long totalKills = 0;
			long totalValue = 0;
			for (int i = tasks.size() - 1; i >= 0; i--)
			{
				if (!tasks.get(i).isJsonObject())
				{
					continue;
				}
				JsonObject seg = tasks.get(i).getAsJsonObject();
				long kills = seg.has("kills") ? asLong(seg.get("kills")) : 0;
				long value = seg.has("value") ? asLong(seg.get("value")) : 0;
				totalKills += kills;
				totalValue += value;
				out.add(new SlayerTask(
					seg.has("task") ? seg.get("task").getAsString() : "?",
					kills,
					seg.has("assignment") ? asLong(seg.get("assignment")) : 0,
					seg.has("noLootKills") ? asLong(seg.get("noLootKills")) : 0,
					seg.has("ts") ? asLong(seg.get("ts")) : 0,
					value,
					// Only the newest segment can be in progress: an older one without a
					// completion was parked or its completion was missed, and is done.
					i == tasks.size() - 1 && isOpen(seg)));
			}
			return new SlayerJourney(
				(int) (sl.has("completed") ? asLong(sl.get("completed")) : 0),
				totalKills, totalValue,
				sl.has("xp_est") ? asLong(sl.get("xp_est")) : 0,
				out);
		}
	}

	/** Lifetime gp per consumable counter key, accumulated at each bite or dose. */
	java.util.Map<String, Long> consumableValues()
	{
		java.util.Map<String, Long> out = new java.util.LinkedHashMap<>();
		synchronized (lock)
		{
			if (root != null && root.has("consumable_values")
				&& root.get("consumable_values").isJsonObject())
			{
				for (java.util.Map.Entry<String, JsonElement> e
					: root.getAsJsonObject("consumable_values").entrySet())
				{
					try
					{
						out.put(e.getKey(), e.getValue().getAsLong());
					}
					catch (RuntimeException ignored)
					{
						// non-numeric, skip
					}
				}
			}
		}
		return out;
	}

	/** The per-item side of the uncollected ledger. */
	java.util.List<UntakenRow> untakenItems()
	{
		java.util.List<UntakenRow> out = new java.util.ArrayList<>();
		synchronized (lock)
		{
			if (root == null || !root.has("untaken_items")
				|| !root.get("untaken_items").isJsonObject())
			{
				return out;
			}
			for (java.util.Map.Entry<String, JsonElement> e
				: root.getAsJsonObject("untaken_items").entrySet())
			{
				if (!e.getValue().isJsonObject())
				{
					continue;
				}
				JsonObject it = e.getValue().getAsJsonObject();
				out.add(new UntakenRow(e.getKey(),
					it.has("qty") ? it.get("qty").getAsLong() : 0,
					it.has("value") ? it.get("value").getAsLong() : 0));
			}
		}
		return out;
	}

	java.util.List<UntakenRow> untakenSources()
	{
		java.util.List<UntakenRow> out = new java.util.ArrayList<>();
		synchronized (lock)
		{
			if (root == null || !root.has("untaken") || !root.get("untaken").isJsonObject())
			{
				return out;
			}
			for (java.util.Map.Entry<String, JsonElement> e
				: root.getAsJsonObject("untaken").entrySet())
			{
				if (!e.getValue().isJsonObject())
				{
					continue;
				}
				JsonObject src = e.getValue().getAsJsonObject();
				out.add(new UntakenRow(e.getKey(),
					src.has("qty") ? src.get("qty").getAsLong() : 0,
					src.has("value") ? src.get("value").getAsLong() : 0,
					src.has("kills") ? asLong(src.get("kills")) : 0));
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

	/** This session's kills that left at least one stack on the floor: what the
	 *  Home strip takes off the loot events for "Drops taken". */
	int sessionUntakenKills()
	{
		synchronized (lock)
		{
			return sessionUntakenKills;
		}
	}

	/** A COLLECTION event lights its slot at once: the item joins clog_items and
	 *  the finished tally rises when it's new. The next full log open reconciles it. */
	private void recordClogSlot(JsonObject data)
	{
		String name = data.has("itemName") && !data.get("itemName").isJsonNull()
			? data.get("itemName").getAsString() : null;
		if (name == null || name.isEmpty())
		{
			return;
		}
		synchronized (lock)
		{
			JsonObject cl = root.has("collection_log") && root.get("collection_log").isJsonObject()
				? root.getAsJsonObject("collection_log") : new JsonObject();
			root.add("collection_log", cl);
			JsonObject items = cl.has("clog_items") && cl.get("clog_items").isJsonObject()
				? cl.getAsJsonObject("clog_items") : new JsonObject();
			cl.add("clog_items", items);
			boolean known = false;
			for (java.util.Map.Entry<String, JsonElement> e : items.entrySet())
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
			root.addProperty("updated_at", nowSec());
		}
	}

	/** {finished, available} as the journal holds them; zero when unknown. */
	int[] clogFraction()
	{
		synchronized (lock)
		{
			if (root == null || !root.has("collection_log")
				|| !root.get("collection_log").isJsonObject())
			{
				return new int[]{0, 0};
			}
			JsonObject cl = root.getAsJsonObject("collection_log");
			return new int[]{
				cl.has("finished") ? (int) asLong(cl.get("finished")) : 0,
				cl.has("available") ? (int) asLong(cl.get("available")) : 0};
		}
	}

	private static JsonObject mergeClog(JsonObject base, JsonObject inc)
	{
		JsonObject out = new JsonObject();
		JsonObject byCat = new JsonObject();
		for (JsonObject src : new JsonObject[]{base, inc})
		{
			if (src.has("by_cat") && src.get("by_cat").isJsonObject())
			{
				for (java.util.Map.Entry<String, JsonElement> pg
					: src.getAsJsonObject("by_cat").entrySet())
				{
					if (!pg.getValue().isJsonObject())
					{
						continue;
					}
					JsonObject tgt = byCat.has(pg.getKey())
						? byCat.getAsJsonObject(pg.getKey()) : new JsonObject();
					for (java.util.Map.Entry<String, JsonElement> it
						: pg.getValue().getAsJsonObject().entrySet())
					{
						long n = asLong(it.getValue());
						if (n > (tgt.has(it.getKey()) ? asLong(tgt.get(it.getKey())) : 0))
						{
							tgt.addProperty(it.getKey(), n);
						}
					}
					byCat.add(pg.getKey(), tgt);
				}
			}
		}
		out.add("by_cat", byCat);
		// Every counter a page carries, by the log's own name for it. Nested the
		// way by_cat is -- page, then label -- and floor-merged the same way, so
		// a page not opened this session keeps what it last said.
		JsonObject kcLines = new JsonObject();
		for (JsonObject src : new JsonObject[]{base, inc})
		{
			if (!src.has("kc_lines") || !src.get("kc_lines").isJsonObject())
			{
				continue;
			}
			for (java.util.Map.Entry<String, JsonElement> pg
				: src.getAsJsonObject("kc_lines").entrySet())
			{
				if (!pg.getValue().isJsonObject())
				{
					continue;
				}
				JsonObject tgt = kcLines.has(pg.getKey())
					? kcLines.getAsJsonObject(pg.getKey()) : new JsonObject();
				for (java.util.Map.Entry<String, JsonElement> ln
					: pg.getValue().getAsJsonObject().entrySet())
				{
					long n = asLong(ln.getValue());
					if (n > (tgt.has(ln.getKey()) ? asLong(tgt.get(ln.getKey())) : 0))
					{
						tgt.addProperty(ln.getKey(), n);
					}
				}
				kcLines.add(pg.getKey(), tgt);
			}
		}
		if (kcLines.size() > 0)
		{
			out.add("kc_lines", kcLines);
		}
		// Best times, merged the other way about. Every other figure here only
		// grows, so they are floored; a personal best IMPROVES DOWNWARD, and
		// flooring it would pin the worst time ever recorded and never let go.
		JsonObject pbLines = new JsonObject();
		for (JsonObject src : new JsonObject[]{base, inc})
		{
			if (!src.has("pb_lines") || !src.get("pb_lines").isJsonObject())
			{
				continue;
			}
			for (java.util.Map.Entry<String, JsonElement> pg
				: src.getAsJsonObject("pb_lines").entrySet())
			{
				if (!pg.getValue().isJsonObject())
				{
					continue;
				}
				JsonObject tgt = pbLines.has(pg.getKey())
					? pbLines.getAsJsonObject(pg.getKey()) : new JsonObject();
				for (java.util.Map.Entry<String, JsonElement> ln
					: pg.getValue().getAsJsonObject().entrySet())
				{
					long n = asLong(ln.getValue());
					if (n > 0 && (!tgt.has(ln.getKey()) || n < asLong(tgt.get(ln.getKey()))))
					{
						tgt.addProperty(ln.getKey(), n);
					}
				}
				pbLines.add(pg.getKey(), tgt);
			}
		}
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
					for (java.util.Map.Entry<String, JsonElement> e
						: src.getAsJsonObject(mapKey).entrySet())
					{
						long n = asLong(e.getValue());
						if (n > (merged.has(e.getKey()) ? asLong(merged.get(e.getKey())) : 0))
						{
							merged.addProperty(e.getKey(), n);
						}
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
			long a = base.has(numKey) ? asLong(base.get(numKey)) : 0;
			long b = inc.has(numKey) ? asLong(inc.get(numKey)) : 0;
			out.addProperty(numKey, Math.max(a, b));
		}
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

	/**
	 * Merge another Chronicle journal into this account's record. Every store floors:
	 * per-key max, earliest wins on first_seen, best wins on a personal best. An
	 * import is the same account seen from somewhere else, so its history overlaps
	 * this one and summing would double every shared kill; flooring makes a repeat
	 * import a no-op. Runs off the client thread.
	 *
	 * @return a short summary of what came across.
	 */
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
			// Lifetime counters: floor the frozen base AND the shown values, so the
			// import survives the next session recompute.
			if (in.has("trackers") && in.get("trackers").isJsonObject())
			{
				JsonObject tr = root.has("trackers") && root.get("trackers").isJsonObject()
					? root.getAsJsonObject("trackers") : new JsonObject();
				for (java.util.Map.Entry<String, JsonElement> e
					: in.getAsJsonObject("trackers").entrySet())
				{
					long v = asLong(e.getValue());
					if (v <= 0)
					{
						continue;
					}
					if (v > (trackersBase.has(e.getKey()) ? asLong(trackersBase.get(e.getKey())) : 0))
					{
						trackersBase.addProperty(e.getKey(), v);
						counters++;
					}
					if (v > (tr.has(e.getKey()) ? asLong(tr.get(e.getKey())) : 0))
					{
						tr.addProperty(e.getKey(), v);
					}
				}
				root.add("trackers", tr);
			}
			// Drop ledger: per source, then per item inside it.
			if (in.has("drops") && in.get("drops").isJsonObject())
			{
				JsonObject drops = root.getAsJsonObject("drops");
				for (java.util.Map.Entry<String, JsonElement> e
					: in.getAsJsonObject("drops").entrySet())
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
					// first_seen only ever moves earlier
					if (inc.has("first_seen") && !inc.get("first_seen").isJsonNull())
					{
						long incFirst = asLong(inc.get("first_seen"));
						long curFirst = cur.has("first_seen") ? asLong(cur.get("first_seen")) : 0;
						if (incFirst > 0 && (curFirst == 0 || incFirst < curFirst))
						{
							cur.addProperty("first_seen", incFirst);
						}
					}
					// a PB is the lowest time; the compare is flipped
					if (inc.has("pb") && !inc.get("pb").isJsonNull())
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
						// The bag is keyed by item id and an export may know only names, so
						// match on name first; otherwise one herb ends up on two lines.
						java.util.Map<String, String> byName = new java.util.HashMap<>();
						for (java.util.Map.Entry<String, JsonElement> be : bag.entrySet())
						{
							if (be.getValue().isJsonObject())
							{
								JsonObject b = be.getValue().getAsJsonObject();
								if (b.has("name") && !b.get("name").isJsonNull())
								{
									byName.put(b.get("name").getAsString()
										.toLowerCase(java.util.Locale.ROOT), be.getKey());
								}
							}
						}
						for (java.util.Map.Entry<String, JsonElement> ie
							: inc.getAsJsonObject("items").entrySet())
						{
							if (!ie.getValue().isJsonObject())
							{
								continue;
							}
							JsonObject incItem = ie.getValue().getAsJsonObject();
							String incName = incItem.has("name") && !incItem.get("name").isJsonNull()
								? incItem.get("name").getAsString() : ie.getKey();
							String key = byName.get(incName.toLowerCase(java.util.Locale.ROOT));
							if (key == null)
							{
								// No name match: file it under its id when the entry
								// carried one, so the panel can draw the sprite, else keep
								// the incoming map key, which on an id-keyed bag is the id;
								// dedupeSourceBags below folds in the plain-name keys an
								// older export left behind
								key = incItem.has("id") && incItem.get("id").getAsInt() > 0
									? bagKey(incItem.get("id").getAsInt(), incName)
									: ie.getKey();
							}
							JsonObject curItem = bag.has(key) && bag.get(key).isJsonObject()
								? bag.getAsJsonObject(key) : new JsonObject();
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
							bag.add(key, curItem);
							byName.put(incName.toLowerCase(java.util.Locale.ROOT), key);
						}
						cur.add("items", bag);
					}
				}
				// Another machine's ledger has just been merged in. Those rows are
				// not kills that happened since an anchor was taken here, and the
				// merge can only raise the counts, so the baselines move with them.
				rebaseAnchors();
			}
			// the dated feed, deduplicated on feedKey
			if (in.has("feed") && in.get("feed").isJsonArray())
			{
				JsonArray feed = root.getAsJsonArray("feed");
				java.util.Set<String> seen = new java.util.HashSet<>();
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
				// an import interleaves, and the panel reads the feed in stored order
				java.util.List<JsonObject> all = new java.util.ArrayList<>(feed.size());
				for (JsonElement e : feed)
				{
					if (e.isJsonObject())
					{
						all.add(e.getAsJsonObject());
					}
				}
				all.sort(java.util.Comparator.comparingLong(
					o -> o.has("ts") ? asLong(o.get("ts")) : 0));
				JsonArray rebuilt = new JsonArray();
				for (int i = Math.max(0, all.size() - FEED_CAP); i < all.size(); i++)
				{
					rebuilt.add(all.get(i));
				}
				root.add("feed", rebuilt);
			}
			// collection log: the same max-union used on capture
			if (in.has("collection_log") && in.get("collection_log").isJsonObject())
			{
				JsonObject cl = root.has("collection_log") && root.get("collection_log").isJsonObject()
					? root.getAsJsonObject("collection_log") : new JsonObject();
				root.add("collection_log", mergeClog(cl, in.getAsJsonObject("collection_log")));
			}
			// The chat box's counts travel with it. This block names every key it
			// carries, so one left out is dropped in silence.
			if (in.has("chat_kcs") && in.get("chat_kcs").isJsonObject())
			{
				ensureObject(root, "chat_kcs");
				JsonObject mine = root.getAsJsonObject("chat_kcs");
				for (java.util.Map.Entry<String, JsonElement> e
					: in.getAsJsonObject("chat_kcs").entrySet())
				{
					long n = asLong(e.getValue());
					if (n > (mine.has(e.getKey()) ? asLong(mine.get(e.getKey())) : 0))
					{
						mine.addProperty(e.getKey(), n);
					}
				}
			}
			// The gathered ledger travels too, or ore mined on the other machine goes
			// unrecognised here.
			if (in.has("gathered_items") && in.get("gathered_items").isJsonArray())
			{
				JsonArray have = root.has("gathered_items") && root.get("gathered_items").isJsonArray()
					? root.getAsJsonArray("gathered_items") : new JsonArray();
				java.util.Set<Integer> seen = new java.util.HashSet<>();
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
						// not an item id
					}
				}
				root.add("gathered_items", have);
			}
			for (String store : new String[]{"untaken", "untaken_items", "consumable_values"})
			{
				floorNestedStore(store, in);
			}
			// The left-behind pairing is two levels deep.
			if (in.has("untaken_pairs") && in.get("untaken_pairs").isJsonObject())
			{
				JsonObject pairs = root.has("untaken_pairs") && root.get("untaken_pairs").isJsonObject()
					? root.getAsJsonObject("untaken_pairs") : new JsonObject();
				for (java.util.Map.Entry<String, JsonElement> e
					: in.getAsJsonObject("untaken_pairs").entrySet())
				{
					if (!e.getValue().isJsonObject())
					{
						continue;
					}
					JsonObject bag = pairs.has(e.getKey()) && pairs.get(e.getKey()).isJsonObject()
						? pairs.getAsJsonObject(e.getKey()) : new JsonObject();
					for (java.util.Map.Entry<String, JsonElement> ie
						: e.getValue().getAsJsonObject().entrySet())
					{
						if (!ie.getValue().isJsonObject())
						{
							continue;
						}
						JsonObject incItem = ie.getValue().getAsJsonObject();
						JsonObject curItem = bag.has(ie.getKey()) && bag.get(ie.getKey()).isJsonObject()
							? bag.getAsJsonObject(ie.getKey()) : new JsonObject();
						floorNumber(curItem, incItem, "qty");
						floorNumber(curItem, incItem, "value");
						if (!curItem.has("id") && incItem.has("id"))
						{
							curItem.add("id", incItem.get("id"));
						}
						bag.add(ie.getKey(), curItem);
					}
					pairs.add(e.getKey(), bag);
				}
				root.add("untaken_pairs", pairs);
			}
			// The spine copies only into an empty one: two overlapping task lists
			// can't be reconciled segment by segment.
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
					// an imported spine is bounded like a grown one
					while (tasks.size() > SLAYER_TASK_CAP)
					{
						tasks.remove(0);
					}
				}
				else if (incSl.has("tasks") && incSl.get("tasks").isJsonArray())
				{
					// A spine already stands: take only detail this one lacks (monsters,
					// items). Matched on task and a nearby ts; the two sides round the
					// instant differently, so equality would match nothing.
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
					if (incSl.has(k) && asLong(incSl.get(k)) > (sl.has(k) ? asLong(sl.get(k)) : 0))
					{
						sl.addProperty(k, asLong(incSl.get(k)));
					}
				}
			}
			dedupeSourceBags();
			dedupeFeed();
			root.addProperty("updated_at", nowSec());
		}
		return sources + " sources · " + String.format(java.util.Locale.UK, "%,d", events)
			+ " journal entries · " + counters + " counters";
	}

	/** How far two records of the same task instant may drift and still be it. */
	private static final long SEGMENT_MATCH_SECONDS = 60;

	/** The local segment an incoming one describes, or null if none does. */
	private static JsonObject nearestSegment(JsonArray tasks, JsonObject inc)
	{
		String task = inc.has("task") ? inc.get("task").getAsString() : null;
		if (task == null)
		{
			return null;
		}
		long ts = inc.has("ts") ? asLong(inc.get("ts")) : 0;
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

	/** Floor one segment's {@code monsters} or {@code items} map into another's. */
	private static void mergeSegmentDetail(JsonObject seg, JsonObject inc, String key)
	{
		if (!inc.has(key) || !inc.get(key).isJsonObject())
		{
			return;
		}
		JsonObject cur = seg.has(key) && seg.get(key).isJsonObject()
			? seg.getAsJsonObject(key) : new JsonObject();
		for (java.util.Map.Entry<String, JsonElement> e : inc.getAsJsonObject(key).entrySet())
		{
			if (e.getValue().isJsonObject())
			{
				JsonObject incRow = e.getValue().getAsJsonObject();
				JsonObject curRow = cur.has(e.getKey()) && cur.get(e.getKey()).isJsonObject()
					? cur.getAsJsonObject(e.getKey()) : new JsonObject();
				// Quantity floors; a price does not. Same argument as the Loot
				// Tracker seed above: the larger of two valuations of the same
				// drop is not a better number, it is whichever day the import
				// ran. A price is seeded into a row that has none and left
				// alone otherwise.
				floorNumber(curRow, incRow, "qty");
				if (!curRow.has("value") || asLong(curRow.get("value")) <= 0)
				{
					floorNumber(curRow, incRow, "value");
				}
				if (!curRow.has("id") && incRow.has("id"))
				{
					curRow.add("id", incRow.get("id"));
				}
				cur.add(e.getKey(), curRow);
			}
			else
			{
				long v = asLong(e.getValue());
				if (v > (cur.has(e.getKey()) ? asLong(cur.get(e.getKey())) : 0))
				{
					cur.addProperty(e.getKey(), v);
				}
			}
		}
		seg.add(key, cur);
	}

	/**
	 * Identity of a feed line: kind, the second it happened in, and subject. The exact
	 * instant is too fine: a re-imported event can land a millisecond off. The second
	 * on its own is too coarse, since a clue casket empties several slots inside it.
	 */
	private static String feedKey(JsonObject e)
	{
		long sec = (e.has("ts") ? asLong(e.get("ts")) : 0) / 1000L;
		String kind = e.has("type") && !e.get("type").isJsonNull()
			? e.get("type").getAsString() : "";
		return kind + "|" + sec + "|" + feedSubject(e);
	}

	/** What a feed line is about: the thing it names, or failing that the payload. */
	private static String feedSubject(JsonObject e)
	{
		if (!e.has("data") || !e.get("data").isJsonObject())
		{
			return "";
		}
		JsonObject d = e.getAsJsonObject("data");
		// Tried in order. questName/killerName/area are what the capture writes for
		// QUEST, DEATH and DIARY; the last four turn up only in older journals.
		for (String field : new String[]{"itemName", "petName", "questName",
			"killerName", "area", "skill", "task", "monster",
			"name", "quest", "diary", "achievement"})
		{
			if (d.has(field) && !d.get(field).isJsonNull())
			{
				return d.get(field).getAsString().toLowerCase(java.util.Locale.ROOT);
			}
		}
		// an imported line can carry only a marker; then the payload is the identity
		JsonObject bare = d.deepCopy();
		bare.remove("imported");
		bare.remove("type");
		return bare.toString();
	}

	/**
	 * Collapse feed lines that describe the same moment; the fuller line survives.
	 * Timestamps can differ by a millisecond across an import, so a record can hold
	 * one log slot twice. Callers hold {@code lock}. Returns how many were absorbed.
	 */
	private int dedupeFeed()
	{
		if (root == null || !root.has("feed") || !root.get("feed").isJsonArray())
		{
			return 0;
		}
		JsonArray feed = root.getAsJsonArray("feed");
		java.util.Map<String, JsonObject> best = new java.util.LinkedHashMap<>();
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
			// keep whichever says more; a named line beats a bare "imported" marker
			if (payloadSize(o) > payloadSize(held))
			{
				best.put(key, o);
			}
		}
		if (absorbed > 0)
		{
			java.util.List<JsonObject> kept = new java.util.ArrayList<>(best.values());
			kept.sort(java.util.Comparator.comparingLong(
				o -> o.has("ts") ? asLong(o.get("ts")) : 0));
			JsonArray rebuilt = new JsonArray();
			for (JsonObject o : kept)
			{
				rebuilt.add(o);
			}
			root.add("feed", rebuilt);
		}
		return absorbed;
	}

	private static int payloadSize(JsonObject e)
	{
		return e.has("data") && e.get("data").isJsonObject()
			? e.getAsJsonObject("data").size() : 0;
	}

	/** Raise {@code cur[key]} to {@code inc[key]} when the incoming one is higher. */
	private static void floorNumber(JsonObject cur, JsonObject inc, String key)
	{
		if (!inc.has(key) || inc.get(key).isJsonNull())
		{
			return;
		}
		long v = asLong(inc.get(key));
		if (v > (cur.has(key) ? asLong(cur.get(key)) : 0))
		{
			cur.addProperty(key, v);
		}
	}

	/** Floor a flat {name: {qty, value}} store, or a flat {key: number} one. */
	private void floorNestedStore(String name, JsonObject in)
	{
		if (!in.has(name) || !in.get(name).isJsonObject())
		{
			return;
		}
		JsonObject cur = root.has(name) && root.get(name).isJsonObject()
			? root.getAsJsonObject(name) : new JsonObject();
		for (java.util.Map.Entry<String, JsonElement> e : in.getAsJsonObject(name).entrySet())
		{
			if (e.getValue().isJsonObject())
			{
				JsonObject incRow = e.getValue().getAsJsonObject();
				JsonObject curRow = cur.has(e.getKey()) && cur.get(e.getKey()).isJsonObject()
					? cur.getAsJsonObject(e.getKey()) : new JsonObject();
				floorNumber(curRow, incRow, "qty");
				floorNumber(curRow, incRow, "value");
				floorNumber(curRow, incRow, "kills");   // the untaken store's third figure
				cur.add(e.getKey(), curRow);
			}
			else
			{
				long v = asLong(e.getValue());
				if (v > (cur.has(e.getKey()) ? asLong(cur.get(e.getKey())) : 0))
				{
					cur.addProperty(e.getKey(), v);
				}
			}
		}
		root.add(name, cur);
	}

	/** Items one source was left holding, richest first. */
	java.util.List<BagItem> untakenItemsOf(String source)
	{
		java.util.List<BagItem> out = new java.util.ArrayList<>();
		synchronized (lock)
		{
			if (root == null || !root.has("untaken_pairs") || !root.get("untaken_pairs").isJsonObject())
			{
				return out;
			}
			JsonObject pairs = root.getAsJsonObject("untaken_pairs");
			if (!pairs.has(source) || !pairs.get(source).isJsonObject())
			{
				return out;
			}
			for (java.util.Map.Entry<String, JsonElement> e : pairs.getAsJsonObject(source).entrySet())
			{
				JsonObject r = e.getValue().getAsJsonObject();
				out.add(new BagItem(r.has("id") ? r.get("id").getAsInt() : -1, e.getKey(),
					asLong(r.get("qty")), asLong(r.get("value"))));
			}
		}
		out.sort((a, b) -> Long.compare(b.value, a.value));
		return out;
	}

	/** Sources that left a given item on the ground, most first. */
	java.util.List<UntakenRow> untakenSourcesOf(String item)
	{
		java.util.List<UntakenRow> out = new java.util.ArrayList<>();
		synchronized (lock)
		{
			if (root == null || !root.has("untaken_pairs") || !root.get("untaken_pairs").isJsonObject())
			{
				return out;
			}
			for (java.util.Map.Entry<String, JsonElement> e
				: root.getAsJsonObject("untaken_pairs").entrySet())
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

	/** One task segment's own loot, richest first. */
	java.util.List<BagItem> slayerTaskItems(int index)
	{
		java.util.List<BagItem> out = new java.util.ArrayList<>();
		JsonObject seg = segmentAt(index);
		if (seg != null && seg.has("items") && seg.get("items").isJsonObject())
		{
			for (java.util.Map.Entry<String, JsonElement> e : seg.getAsJsonObject("items").entrySet())
			{
				JsonObject r = e.getValue().getAsJsonObject();
				out.add(new BagItem(r.has("id") ? r.get("id").getAsInt() : -1, e.getKey(),
					asLong(r.get("qty")), asLong(r.get("value"))));
			}
		}
		out.sort((a, b) -> Long.compare(b.value, a.value));
		return out;
	}

	/** What a task was actually made of: {monster: kills}, most killed first. */
	java.util.List<UntakenRow> slayerTaskMonsters(int index)
	{
		java.util.List<UntakenRow> out = new java.util.ArrayList<>();
		JsonObject seg = segmentAt(index);
		if (seg != null && seg.has("monsters") && seg.get("monsters").isJsonObject())
		{
			for (java.util.Map.Entry<String, JsonElement> e : seg.getAsJsonObject("monsters").entrySet())
			{
				out.add(new UntakenRow(e.getKey(), asLong(e.getValue()), 0));
			}
		}
		out.sort((a, b) -> Long.compare(b.qty, a.qty));
		return out;
	}

	/** The task segment the panel's journey list calls {@code index}. The journey
	 *  is served newest-first, the store keeps them oldest-first. */
	private JsonObject segmentAt(int index)
	{
		synchronized (lock)
		{
			if (root == null || !root.has("slayer") || !root.get("slayer").isJsonObject())
			{
				return null;
			}
			JsonObject sl = root.getAsJsonObject("slayer");
			if (!sl.has("tasks") || !sl.get("tasks").isJsonArray())
			{
				return null;
			}
			JsonArray tasks = sl.getAsJsonArray("tasks");
			int at = tasks.size() - 1 - index;
			return at >= 0 && at < tasks.size() && tasks.get(at).isJsonObject()
				? tasks.get(at).getAsJsonObject() : null;
		}
	}

	/** Pets the journal has seen drop, newest first. Source and kc survive only on
	 *  rows imported from an older record: a PET event carries the name alone. */
	java.util.List<PetRow> pets()
	{
		java.util.List<PetRow> out = new java.util.ArrayList<>();
		synchronized (lock)
		{
			if (root == null || !root.has("feed") || !root.get("feed").isJsonArray())
			{
				return out;
			}
			java.util.Set<String> seen = new java.util.HashSet<>();
			JsonArray feed = root.getAsJsonArray("feed");
			for (int i = feed.size() - 1; i >= 0; i--)
			{
				if (!feed.get(i).isJsonObject())
				{
					continue;
				}
				JsonObject e = feed.get(i).getAsJsonObject();
				if (!"PET".equals(e.has("type") ? e.get("type").getAsString() : "")
					|| !e.has("data") || !e.get("data").isJsonObject())
				{
					continue;
				}
				JsonObject d = e.getAsJsonObject("data");
				String name = d.has("petName") && !d.get("petName").isJsonNull()
					? d.get("petName").getAsString() : null;
				if (name == null || name.isEmpty() || !seen.add(name.toLowerCase(java.util.Locale.ROOT)))
				{
					continue;
				}
				out.add(new PetRow(name,
					d.has("source") && !d.get("source").isJsonNull() ? d.get("source").getAsString() : null,
					d.has("killCount") && !d.get("killCount").isJsonNull() ? asLong(d.get("killCount")) : 0,
					e.has("ts") ? asLong(e.get("ts")) : 0));
			}
		}
		out.sort((a, b) -> Long.compare(b.ts, a.ts));
		return out;
	}

	static final class PetRow
	{
		final String name;
		final String source;
		final long kc;
		final long ts;

		PetRow(String name, String source, long kc, long ts)
		{
			this.name = name;
			this.source = source;
			this.kc = kc;
			this.ts = ts;
		}
	}

	/**
	 * Rebuild the per-item untaken totals from the source-and-item pairs.
	 *
	 * <p>The same leavings are stored three ways: by source, by item, and by the pair.
	 * The pairs carry the detail and the other two are sums of them, so anything that
	 * edits one store without the others leaves the by-item view claiming more than the
	 * by-source view of the same drops. This only runs when the pairs cover every source
	 * the by-source store knows, which is what makes them safe to sum from; a journal
	 * written before pairs existed is left alone. Callers hold {@code lock}. Returns how
	 * many item rows it corrected.
	 */
	private int reconcileUntaken()
	{
		if (root == null || !root.has("untaken_pairs") || !root.get("untaken_pairs").isJsonObject()
			|| !root.has("untaken_items") || !root.get("untaken_items").isJsonObject())
		{
			return 0;
		}
		JsonObject pairs = root.getAsJsonObject("untaken_pairs");
		if (pairs.size() == 0)
		{
			return 0;
		}
		if (root.has("untaken") && root.get("untaken").isJsonObject())
		{
			for (java.util.Map.Entry<String, JsonElement> se
				: root.getAsJsonObject("untaken").entrySet())
			{
				if (!pairs.has(se.getKey()))
				{
					return 0;
				}
			}
		}
		JsonObject rebuilt = new JsonObject();
		for (java.util.Map.Entry<String, JsonElement> pe : pairs.entrySet())
		{
			if (!pe.getValue().isJsonObject())
			{
				continue;
			}
			for (java.util.Map.Entry<String, JsonElement> ie
				: pe.getValue().getAsJsonObject().entrySet())
			{
				if (!ie.getValue().isJsonObject())
				{
					continue;
				}
				JsonObject inc = ie.getValue().getAsJsonObject();
				if (!rebuilt.has(ie.getKey()))
				{
					JsonObject fresh = new JsonObject();
					fresh.addProperty("qty", 0);
					fresh.addProperty("value", 0);
					rebuilt.add(ie.getKey(), fresh);
				}
				JsonObject cur = rebuilt.getAsJsonObject(ie.getKey());
				cur.addProperty("qty", asLong(cur.get("qty")) + asLong(inc.get("qty")));
				cur.addProperty("value", asLong(cur.get("value")) + asLong(inc.get("value")));
			}
		}
		JsonObject was = root.getAsJsonObject("untaken_items");
		int corrected = 0;
		for (java.util.Map.Entry<String, JsonElement> e : rebuilt.entrySet())
		{
			JsonElement before = was.get(e.getKey());
			if (before == null || !before.equals(e.getValue()))
			{
				corrected++;
			}
		}
		for (java.util.Map.Entry<String, JsonElement> e : was.entrySet())
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

	/**
	 * Drop off-task monsters from slayer task segments. An earlier build stamped every
	 * NPC kill made while a task was live as a task kill, so a Man or an impling killed
	 * mid-task sits in the segment's {@code monsters} and inflates its {@code kills},
	 * which is the count the page shows and the completion trues up against. Each
	 * monster is put to the same on-task test capture now applies
	 * ({@link SlayerTaskBook}); the segment stores names only, so it runs on the name
	 * tier alone. Those that fail are removed and their counts taken off {@code kills}.
	 * The segment's {@code items} and {@code value} are aggregated across its kills and
	 * cannot be separated per monster, so they are left as they are. A segment whose
	 * monsters all fail is kept with zero kills rather than deleted: it may be a
	 * loot-less completion.
	 *
	 * <p>Only open segments are examined. A closed one was either imported from the
	 * server, which filtered it with the id tier this name-only store cannot re-run
	 * (Prifddinas guards on an Elves task are "Guard" here and would be thrown out),
	 * or its count was already trued up by the completion line. Callers hold
	 * {@code lock}. Returns how many segments changed.
	 */
	private int purgeOffTaskMonsters()
	{
		if (root == null || !root.has("slayer") || !root.get("slayer").isJsonObject())
		{
			return 0;
		}
		JsonObject sl = root.getAsJsonObject("slayer");
		if (!sl.has("tasks") || !sl.get("tasks").isJsonArray())
		{
			return 0;
		}
		int changed = 0;
		for (JsonElement te : sl.getAsJsonArray("tasks"))
		{
			if (!te.isJsonObject())
			{
				continue;
			}
			JsonObject seg = te.getAsJsonObject();
			boolean open = seg.has("open") && !seg.get("open").isJsonNull()
				&& seg.get("open").getAsBoolean();
			if (!open || !seg.has("task") || seg.get("task").isJsonNull()
				|| !seg.has("monsters") || !seg.get("monsters").isJsonObject())
			{
				continue;
			}
			String task = seg.get("task").getAsString();
			JsonObject mons = seg.getAsJsonObject("monsters");
			long offTask = 0;
			java.util.List<String> drop = new java.util.ArrayList<>();
			for (java.util.Map.Entry<String, JsonElement> me : mons.entrySet())
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
			long kills = seg.has("kills") ? asLong(seg.get("kills")) : 0;
			seg.addProperty("kills", Math.max(0, kills - offTask));
			changed++;
		}
		return changed;
	}

	/**
	 * Collapse item entries that name the same thing within one source. The bag is
	 * keyed by item id, a merged-in record may know only names, and an earlier build
	 * filed those alongside the entry already there. The survivor takes the higher of
	 * the two and keeps the id-bearing key. Callers hold {@code lock}. Returns how
	 * many entries were absorbed.
	 */
	private int dedupeSourceBags()
	{
		if (root == null || !root.has("drops") || !root.get("drops").isJsonObject())
		{
			return 0;
		}
		int absorbed = 0;
		for (java.util.Map.Entry<String, JsonElement> se
			: root.getAsJsonObject("drops").entrySet())
		{
			if (!se.getValue().isJsonObject())
			{
				continue;
			}
			JsonObject src = se.getValue().getAsJsonObject();
			if (!src.has("items") || !src.get("items").isJsonObject())
			{
				continue;
			}
			JsonObject bag = src.getAsJsonObject("items");
			java.util.Map<String, String> keep = new java.util.HashMap<>();
			java.util.List<String> drop = new java.util.ArrayList<>();
			for (java.util.Map.Entry<String, JsonElement> ie : bag.entrySet())
			{
				if (!ie.getValue().isJsonObject())
				{
					continue;
				}
				JsonObject it = ie.getValue().getAsJsonObject();
				String name = it.has("name") && !it.get("name").isJsonNull()
					? it.get("name").getAsString().toLowerCase(java.util.Locale.ROOT)
					: ie.getKey().toLowerCase(java.util.Locale.ROOT);
				String held = keep.get(name);
				if (held == null)
				{
					keep.put(name, ie.getKey());
					continue;
				}
				// Prefer the numeric (id) key as the survivor.
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

	// the combat level an earlier build left inside a pickpocket key
	private static final java.util.regex.Pattern KEY_LEVEL =
		java.util.regex.Pattern.compile("\\(level[\\s-]*\\d*\\)?",
			java.util.regex.Pattern.CASE_INSENSITIVE);

	/**
	 * Fold the counter keys an earlier build minted wrong into the keys the mint
	 * writes now, so the panel shows one row where it showed two: the plain tree
	 * keyed as logsLogsChopped beside normalLogsChopped, a pickpocket target that
	 * kept its combat level (guard(level21)Pickpockets), and a double-underscore
	 * probe written as a counter. The sum survives, the old key goes, and a second
	 * pass finds nothing. Callers hold {@code lock}. Returns how many keys went.
	 */
	private int repairTrackerKeys()
	{
		if (root == null || !root.has("trackers") || !root.get("trackers").isJsonObject())
		{
			return 0;
		}
		JsonObject tr = root.getAsJsonObject("trackers");
		int folded = 0;
		for (java.util.Map.Entry<String, JsonElement> e
			: new java.util.ArrayList<>(tr.entrySet()))
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
		if (s == null || s.isEmpty())
		{
			return false;
		}
		for (int i = 0; i < s.length(); i++)
		{
			if (!Character.isDigit(s.charAt(i)))
			{
				return false;
			}
		}
		return true;
	}

	/** Combat level as last gathered, or 0. */
	int combatLevel()
	{
		synchronized (lock)
		{
			return root != null && root.has("combat_level") && !root.get("combat_level").isJsonNull()
				? (int) asLong(root.get("combat_level")) : 0;
		}
	}

	/** The character sheet's skills: {skill: [level, xp]}, as last gathered. */
	java.util.Map<String, long[]> skillSheet()
	{
		java.util.Map<String, long[]> out = new java.util.LinkedHashMap<>();
		synchronized (lock)
		{
			if (root == null || !root.has("skills") || !root.get("skills").isJsonObject())
			{
				return out;
			}
			for (java.util.Map.Entry<String, JsonElement> e
				: root.getAsJsonObject("skills").entrySet())
			{
				if (!e.getValue().isJsonObject())
				{
					continue;
				}
				JsonObject o = e.getValue().getAsJsonObject();
				out.put(e.getKey(), new long[]{
					o.has("level") ? asLong(o.get("level")) : 0,
					o.has("xp") ? asLong(o.get("xp")) : 0});
			}
		}
		return out;
	}

	/**
	 * The account's achievement state as last gathered: {@code quests} by name against
	 * their state, {@code diaries} by region against each tier's completion, and
	 * {@code combat} points plus per-tier status. Deep-copied for the panel.
	 *
	 * <p>Empty where the sheet has never been gathered, which is not the same as an
	 * unmet requirement but is read as one: a chase printed off an unknown unlock is a
	 * claim the journal cannot make.
	 */
	JsonObject achievements()
	{
		synchronized (lock)
		{
			if (root == null || !root.has("achievements")
				|| !root.get("achievements").isJsonObject())
			{
				return new JsonObject();
			}
			return root.getAsJsonObject("achievements").deepCopy();
		}
	}

	/**
	 * Journal-derived totals for the history spine, keyed as the History summary
	 * reads them: dropsReceived (loot events across every source), lootValue (their
	 * gp), lootLeftCount and lootLeftValue (items left on the floor and their gp,
	 * from the untaken ledger, the same tally the Left behind lens shows),
	 * lootLeftKills (the kills that left at least one stack, from the same ledger,
	 * the figure "Drops taken" subtracts from dropsReceived in one unit), kills
	 * (each fight's count as {@link #reconciledKills} gives it, which is the figure
	 * the History tab's Kills list draws, summed over the sources the ledger has
	 * actually seen loot from; a collection log page it never saw is not counted,
	 * most of those being minigame rounds, which the list files elsewhere too),
	 * slayerTasksCompleted,
	 * clogSlotsObtained (the log's own obtained count when the journal holds one,
	 * else the distinct names the stored pages list).
	 * {@link #spineCounters} merges them into a copy of the trackers for each line;
	 * nothing here reaches the trackers, so the Stats tab never sees them.
	 */
	java.util.Map<String, Long> spineExtras()
	{
		long loots = 0;
		long value = 0;
		java.util.List<SourceRow> sources = dropSources();
		for (SourceRow r : sources)
		{
			loots += r.loots;
			value += r.value;
		}
		// The reconciled FIGURE for each fight, over the ledger's own MEMBERSHIP.
		//
		// Two different questions, and the old fold answered both with sourceKills.
		// Which fights count is the ledger's: a collection log page the ledger never
		// saw loot from is mostly a minigame round, and the Kills list buckets those
		// under Activities rather than showing them here. But what each one COUNTS
		// is the reconciliation's, which applies the Kill Log, the chat line and the
		// anchors over the page counters, and that is the figure the list draws.
		JsonObject cl = clogSnapshot();
		java.util.Map<String, Long> reconciled =
			reconciledKills(cl, sources, chatKillCounts(), anchoredKills());
		long kills = 0;
		for (String name : sourceKills(cl, sources).keySet())
		{
			kills += reconciled.getOrDefault(name, 0L);
		}
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
		java.util.Map<String, Long> out = new java.util.LinkedHashMap<>();
		out.put("dropsReceived", loots);
		out.put("lootValue", value);
		out.put("lootLeftCount", left);
		out.put("lootLeftValue", leftValue);
		out.put("lootLeftKills", leftKills);
		out.put("kills", kills);
		out.put("slayerTasksCompleted", journey != null ? journey.completedTasks : 0L);
		out.put("clogSlotsObtained", (long) (finished > 0 ? finished : obtainedSlots(clogSnapshot())));
		return out;
	}

	/**
	 * The counters one history line carries: the trackers as they stand, with the
	 * {@link #spineExtras spine extras} laid beside them. A fresh copy; the trackers
	 * themselves are untouched.
	 */
	java.util.Map<String, Long> spineCounters()
	{
		java.util.Map<String, Long> out = trackersSnapshot();
		out.putAll(spineExtras());
		return out;
	}

	/**
	 * Kills per drop source, the figure the History tab's Kills list and its
	 * summary line share: for each source the most any record has seen of it, its
	 * kill-count line, the loot events it logged (an ordinary slayer monster has
	 * no kill-count line, so only its loots grow) and the collection log's count
	 * for the page of the same name. Keyed by the log's spelling where a source
	 * matches a page ("Tormented Demons" for the ledger's "Tormented Demon"), else
	 * the ledger's own; two sources that name one page fold into one entry, and a
	 * source with nothing counted stays out. A page the ledger never saw loot from
	 * is not here: {@code ChroniclePlugin.killCounts} lays those beside.
	 */
	java.util.Map<String, Long> sourceKills()
	{
		return sourceKills(clogSnapshot(), dropSources());
	}

	/**
	 * Every count of a fight the record holds, folded into one figure each.
	 *
	 * <p>Static and given everything it needs, so the preview harness reconciles
	 * exactly the way the client does. It used to reconcile differently: the
	 * stub built its map from the page counters and the ledger and never applied
	 * the Kill Log at all, so a panel test could watch a page print the ledger's
	 * number while the real client printed the game's.
	 */
	static java.util.Map<String, Long> reconciledKills(JsonObject clog,
		java.util.List<SourceRow> sources, java.util.Map<String, Long> chat,
		java.util.Map<String, Long> anchored)
	{
		java.util.Map<String, Long> out = clogKillCounts(clog);
		// What the game itself says about the encounter: the Kill Log, and the
		// chat line it prints on the kill. The Kill Log only moves when the
		// player opens an interface, so a number resting on it alone is frozen
		// between visits; the chat line arrives on every kill, with nothing
		// opened and nothing fetched. Both are the game counting and both only
		// count up, so between the two the larger is the later reading.
		java.util.Map<String, Long> stated = killLogCounts(clog);
		foldChatCounts(stated, chat, out.keySet());
		// A statement always beats the page counter, which need not be counting
		// kills at all: Wintertodt's page counts rewards claimed and says 1,078
		// where 448 were killed, and the larger of those two is the lie.
		placeByKind(out, stated, false);
		// The page's own LABELLED line is a statement too, and the step above just
		// overwrote it. clogKillCounts lets that line replace the raw counter
		// precisely because a named line is not a guess; handing the result to a
		// Kill Log that has not been opened since would undo the correction and
		// pull a page back down to a staler reading. Both only count up, so the
		// later of the two is the larger.
		placeByKind(out, pageKillLines(clog), true);
		// The ledger is then a floor over that. A bare statement was true when
		// somebody last opened an interface and knows nothing of what has
		// happened since, so it may not pull down a count the ledger has
		// actually watched: Abyssal demons read 1,798 from a stale Kill Log
		// beside 2,346 seen.
		//
		// The LEDGER's own figure, not the one sourceKills raises to the page
		// counter: the counters were step one and have already been weighed
		// against the statements. Letting them back in here re-admits the reading
		// those statements exist to overrule.
		placeByKind(out, ledgerKills(clog, sources), true);
		// And an anchored count is a statement carrying its own observations
		// forward. It knows what has happened since, so it IS the count.
		placeByKind(out, anchored, false);
		return out;
	}

	static java.util.Map<String, Long> sourceKills(JsonObject clog,
		java.util.List<SourceRow> sources)
	{
		return sourceKills(clog, sources, true);
	}

	/**
	 * What the ledger alone has watched of each source, under the log's spelling.
	 *
	 * <p>The same fold as {@link #sourceKills}, without raising a source to the
	 * page's counter. {@link #reconciledKills} wants this one: it has already
	 * weighed the counters against the game's own statements, and a floor that
	 * carries the counter back in would re-admit exactly what those statements
	 * were applied to overrule.
	 */
	static java.util.Map<String, Long> ledgerKills(JsonObject clog,
		java.util.List<SourceRow> sources)
	{
		return sourceKills(clog, sources, false);
	}

	private static java.util.Map<String, Long> sourceKills(JsonObject clog,
		java.util.List<SourceRow> sources, boolean raiseToPage)
	{
		java.util.Map<String, Long> paged = clogKillCounts(clog);
		java.util.Map<String, String> byKind = new java.util.HashMap<>();
		for (String name : paged.keySet())
		{
			byKind.put(kindOf(name), name);
		}
		java.util.Map<String, Long> stated = killLogCounts(clog);
		java.util.Map<String, Long> statedByKind = new java.util.HashMap<>();
		for (java.util.Map.Entry<String, Long> e : stated.entrySet())
		{
			statedByKind.putIfAbsent(kindOf(e.getKey()), e.getValue());
		}
		java.util.Map<String, Long> out = new java.util.LinkedHashMap<>();
		for (SourceRow r : sources)
		{
			long kills = Math.max(r.kc, r.loots);
			// TWO STATEMENTS AGREEING BEAT A TALLY OF ROWS. The ledger keeps both
			// the count the game gave for a kill and the number of loot events it
			// wrote down, and they can part: Zalcano holds 2,024 rows against
			// 2,023 kills, because a drop imported from the old cloud journal
			// carried no kill with it. Where the Kill Log independently says the
			// same number the rows are the odd one out, and a row is not a kill.
			// Where the two statements DIFFER, the ledger's is a partial count of
			// some kind -- a task counter, not a lifetime -- and is no evidence
			// against the rows at all, so nothing is clamped.
			Long agreed = r.kc > 0 ? statedByKind.get(kindOf(r.name)) : null;
			if (agreed != null && agreed.longValue() == r.kc && r.loots > r.kc)
			{
				kills = r.kc;
			}
			String name = r.name;
			String known = byKind.get(kindOf(r.name));
			if (known != null)
			{
				// The page's spelling either way: that is naming, not counting.
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

	// ── anchored kill counts ───────────────────────────────────────────────

	/** chat 3, Kill Log 2, page 1: which statement outranks which. */
	private static int anchorRank(String src)
	{
		return "chat".equals(src) ? 3 : "log".equals(src) ? 2 : 1;
	}

	/**
	 * A kill count the game stated, and what this journal had observed of that
	 * same fight at the moment it was stated.
	 *
	 * <p>Both halves matter. The count alone is a snapshot: the Kill Log is read
	 * by opening an interface, so a number resting on it is frozen until the
	 * player goes and looks again, and Abyssal demons sat on 1,798 while the game
	 * itself had reached 2,523. The observation alone is live but partial: the
	 * ledger only ever saw the kills that dropped something, and only since
	 * tracking began. Held together they are neither: the stated count carries
	 * everything that happened before it, and the observations since carry
	 * everything after, so the number moves on its own without ever re-counting a
	 * kill the statement already counted.
	 *
	 * <p>Rank decides which statement stands. The chat box speaks on the kill
	 * itself, the Kill Log when it is opened; a statement of equal or higher rank
	 * REPLACES an older one outright, even when its number is lower, because this
	 * is a dated reading superseding a dated reading rather than a guess at a
	 * maximum. A lower rank never displaces a higher one.
	 */
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
			ensureObject(root, "kc_anchors");
			JsonObject all = root.getAsJsonObject("kc_anchors");
			JsonObject was = all.has(name) && all.get(name).isJsonObject()
				? all.getAsJsonObject(name) : null;
			if (was != null)
			{
				int had = anchorRank(was.has("src") ? was.get("src").getAsString() : "page");
				if (anchorRank(src) < had)
				{
					return;
				}
				// the same statement again, unchanged, must not re-baseline: the
				// observations since it are what the number is riding on
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
			root.addProperty("updated_at", nowSec());
		}
	}

	/**
	 * What the ledger has observed of a fight, under any spelling of its name.
	 * The MAXIMUM of the matching sources and never their sum: the Kill Log says
	 * "Abyssal demons" where the ledger says "Abyssal demon", and one kill must
	 * not be billed twice for being written down twice.
	 */
	private long observedFor(String name)
	{
		if (root == null || !root.has("drops") || !root.get("drops").isJsonObject())
		{
			return 0;
		}
		String kind = kindOf(name);
		long best = 0;
		for (java.util.Map.Entry<String, JsonElement> e
			: root.getAsJsonObject("drops").entrySet())
		{
			if (!e.getValue().isJsonObject() || !kindOf(e.getKey()).equals(kind))
			{
				continue;
			}
			best = Math.max(best, asLong(e.getValue().getAsJsonObject().get("loots")));
		}
		return best;
	}

	/**
	 * The kill an anchor was taken at, arriving afterwards as a drop. Re-takes
	 * that anchor's baseline so the row is not read as a kill since.
	 */
	private void absorbLaggingKill(String source, Integer stated)
	{
		if (stated == null || root == null || !root.has("kc_anchors")
			|| !root.get("kc_anchors").isJsonObject())
		{
			return;
		}
		String kind = kindOf(source);
		for (java.util.Map.Entry<String, JsonElement> e
			: root.getAsJsonObject("kc_anchors").entrySet())
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

	/** Every anchored fight at its stated count plus what has been seen since. */
	java.util.Map<String, Long> anchoredKills()
	{
		java.util.Map<String, Long> out = new java.util.LinkedHashMap<>();
		synchronized (lock)
		{
			if (root == null || !root.has("kc_anchors")
				|| !root.get("kc_anchors").isJsonObject())
			{
				return out;
			}
			for (java.util.Map.Entry<String, JsonElement> e
				: root.getAsJsonObject("kc_anchors").entrySet())
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
				// Never negative. A journal restored from a backup, or carried to a
				// machine that watched less of it, has fewer observations than the
				// anchor was taken beside; that is not minus four kills.
				long since = Math.max(0, observedFor(e.getKey()) - asLong(a.get("obs")));
				out.put(e.getKey(), n + since);
			}
		}
		return out;
	}

	/**
	 * Re-take every anchor's observation baseline without touching what was
	 * stated. For the moments the ledger's counts move without a kill happening:
	 * the Loot Tracker import seeding hundreds of rows at once, and a journal
	 * merged in from another machine. Left alone, those would read as kills.
	 */
	private void rebaseAnchors()
	{
		if (root == null || !root.has("kc_anchors") || !root.get("kc_anchors").isJsonObject())
		{
			return;
		}
		for (java.util.Map.Entry<String, JsonElement> e
			: root.getAsJsonObject("kc_anchors").entrySet())
		{
			if (e.getValue().isJsonObject())
			{
				e.getValue().getAsJsonObject().addProperty("obs", observedFor(e.getKey()));
			}
		}
	}

	/**
	 * A kill count the game announced in the chat box, under the name it used.
	 * Stored raw: "subdued Wintertodt" is what was said, and mapping that onto a
	 * source is the reader's job, so a mapping that turns out wrong can be fixed
	 * later without the reading being lost.
	 *
	 * <p>Floor-merged like every other count. The game only ever counts up, and a
	 * line from an older session must not pull a later one back.
	 */
	void noteKillCount(String subject, int tally, String rsn)
	{
		if (subject == null || subject.isEmpty() || tally <= 0 || !isReadyFor(rsn))
		{
			return;
		}
		revision++;
		synchronized (lock)
		{
			if (root == null)
			{
				return;
			}
			ensureObject(root, "chat_kcs");
			JsonObject m = root.getAsJsonObject("chat_kcs");
			if (m.has(subject) && asLong(m.get(subject)) >= tally)
			{
				return;
			}
			m.addProperty(subject, tally);
			root.addProperty("updated_at", nowSec());
		}
		// and as an anchor, so the count keeps moving between announcements
		anchorKill(subject, tally, "chat", rsn);
	}

	/** Every count the chat box has announced, by the name the game used. */
	java.util.Map<String, Long> chatKillCounts()
	{
		java.util.Map<String, Long> out = new java.util.LinkedHashMap<>();
		synchronized (lock)
		{
			if (root == null || !root.has("chat_kcs") || !root.get("chat_kcs").isJsonObject())
			{
				return out;
			}
			for (java.util.Map.Entry<String, JsonElement> e
				: root.getAsJsonObject("chat_kcs").entrySet())
			{
				long v = asLong(e.getValue());
				if (v > 0)
				{
					out.put(e.getKey(), v);
				}
			}
		}
		return out;
	}

	/**
	 * The game's own Kill Log, by species: a per-encounter tally of lifetime
	 * kills. This is what a kill count MEANS, and it is the only one of the three
	 * sources that is always one: a collection log page's header counter can be
	 * counting something else entirely (Wintertodt's counts rewards claimed), and
	 * a loot tally counts rows rather than kills.
	 */
	static java.util.Map<String, Long> killLogCounts(JsonObject clog)
	{
		java.util.Map<String, Long> out = new java.util.LinkedHashMap<>();
		if (clog == null || !clog.has("slayer_kcs") || !clog.get("slayer_kcs").isJsonObject())
		{
			return out;
		}
		for (java.util.Map.Entry<String, JsonElement> e
			: clog.getAsJsonObject("slayer_kcs").entrySet())
		{
			try
			{
				long v = e.getValue().getAsLong();
				if (v > 0)
				{
					out.put(e.getKey(), v);
				}
			}
			catch (RuntimeException ignored)
			{
				// a non-numeric entry is not a kill count
			}
		}
		return out;
	}

	/**
	 * The collection log's kill counts as the stored log lists them, by page name:
	 * the positive numeric entries of its {@code kcs}, a non-numeric one being no
	 * kill count. Empty with no log.
	 */
	static java.util.Map<String, Long> clogKillCounts(JsonObject clog)
	{
		java.util.Map<String, Long> out = new java.util.LinkedHashMap<>();
		if (clog == null || !clog.has("kcs") || !clog.get("kcs").isJsonObject())
		{
			return out;
		}
		for (java.util.Map.Entry<String, JsonElement> e : clog.getAsJsonObject("kcs").entrySet())
		{
			try
			{
				long v = e.getValue().getAsLong();
				if (v > 0)
				{
					out.put(e.getKey(), v);
				}
			}
			catch (RuntimeException ignored)
			{
				// a non-numeric entry is not a kill count
			}
		}
		// And where the page's own lines were captured, the LABELLED one wins. kcs
		// holds whichever number came first on the page, which is not always the
		// kills: Wintertodt's first line counts rewards claimed. Worse, a reading
		// taken before those labels were captured could be half of a best time,
		// and kcs is floor-merged, so the Gauntlet's 55 would outlive the 31 that
		// corrects it. A named line is not a guess and replaces it outright.
		for (java.util.Map.Entry<String, Long> e : pageKillLines(clog).entrySet())
		{
			out.put(e.getKey(), e.getValue());
		}
		return out;
	}

	/**
	 * Each page's own kill count, by the name the page gives it: the first line
	 * whose label says kills or completions. A page carrying only rewards claimed
	 * or a best time has none, which is the honest answer for it.
	 */
	static java.util.Map<String, Long> pageKillLines(JsonObject clog)
	{
		java.util.Map<String, Long> out = new java.util.LinkedHashMap<>();
		if (clog == null || !clog.has("kc_lines") || !clog.get("kc_lines").isJsonObject())
		{
			return out;
		}
		for (java.util.Map.Entry<String, JsonElement> pg
			: clog.getAsJsonObject("kc_lines").entrySet())
		{
			if (!pg.getValue().isJsonObject())
			{
				continue;
			}
			for (java.util.Map.Entry<String, JsonElement> ln
				: pg.getValue().getAsJsonObject().entrySet())
			{
				String label = ln.getKey().toLowerCase(java.util.Locale.ROOT);
				if (!label.contains("kill") && !label.contains("completion"))
				{
					continue;
				}
				long v = asLong(ln.getValue());
				if (v > 0)
				{
					// the first such line on the page: the Gauntlet's own count
					// comes before the corrupted one, which is its own page
					out.put(pg.getKey(), v);
					break;
				}
			}
		}
		return out;
	}

	/**
	 * Fold the chat box's counts into a set of counts already keyed by source.
	 *
	 * <p>Folded into the game's OWN counts, not into everything known: the
	 * collection log's page counter is not always counting kills, and taking the
	 * larger of the two would keep the lie forever because the lie is the larger.
	 * Wintertodt's page counts rewards claimed and says 1,078 where 448 were
	 * killed. The Kill Log and this line are both the game counting the encounter,
	 * so between those two the larger is simply the later reading.
	 *
	 * <p>The game names things its own way on these lines, so each reading is
	 * tried against the names already in hand under three readings: as said, with
	 * its leading word dropped ("subdued Wintertodt" is Wintertodt), and as a
	 * chest ("Your Barrows chest count" is the Barrows Chests page). A reading
	 * that matches nothing is still carried in under its own name rather than
	 * dropped: a source can be announced in chat long before it has a log page or
	 * a loot row, and a first kill should not have to wait for one.
	 */
	static void foldChatCounts(java.util.Map<String, Long> out,
		java.util.Map<String, Long> chat)
	{
		foldChatCounts(out, chat, java.util.Collections.emptySet());
	}

	static void foldChatCounts(java.util.Map<String, Long> out,
		java.util.Map<String, Long> chat, java.util.Set<String> vocabulary)
	{
		if (out == null || chat == null || chat.isEmpty())
		{
			return;
		}
		java.util.Map<String, String> byKind = new java.util.HashMap<>();
		for (String name : out.keySet())
		{
			byKind.putIfAbsent(chatKind(name), name);
		}
		// Names the game's own counts have not met yet still have to find their
		// source, or a chat reading lands beside a page counter for the same thing
		// instead of replacing it.
		for (String name : vocabulary)
		{
			byKind.putIfAbsent(chatKind(name), name);
		}
		for (java.util.Map.Entry<String, Long> e : chat.entrySet())
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

	/**
	 * Place stated counts onto names already in hand, one row per fight.
	 *
	 * <p>The Kill Log writes "Abyssal demons" where the ledger writes "Abyssal
	 * demon", and putting them in side by side left the same monster listed
	 * twice with two different numbers -- 32 such pairs on a real journal. Each
	 * statement lands on the name already there.
	 *
	 * <p>{@code floor} is the difference between a statement that knows what has
	 * happened since it and one that does not. An anchored count carries its own
	 * observations forward, so it IS the count and replaces what is there. A bare
	 * Kill Log reading is only what was true when somebody last opened that
	 * interface: it may not pull a live count down, because the kills the ledger
	 * has watched since are real. Abyssal demons read 1,798 from a stale log
	 * beside 2,346 the ledger had actually seen.
	 */
	static void placeByKind(java.util.Map<String, Long> out,
		java.util.Map<String, Long> stated, boolean floor)
	{
		if (out == null || stated == null || stated.isEmpty())
		{
			return;
		}
		java.util.Map<String, String> byKind = new java.util.HashMap<>();
		for (String name : out.keySet())
		{
			byKind.putIfAbsent(chatKind(name), name);
		}
		for (java.util.Map.Entry<String, Long> e : stated.entrySet())
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

	/** kindOf, with a leading "the" dropped: the chat box says Gauntlet, the log says The Gauntlet. */
	static String chatKind(String name)
	{
		String n = kindOf(name);
		return n.startsWith("the ") ? n.substring(4) : n;
	}

	/**
	 * Loose identity for a source: the collection log says "Tormented Demons"
	 * where the ledger says "Tormented Demon", and they are one thing.
	 */
	static String kindOf(String name)
	{
		String n = name == null ? "" : name.trim().toLowerCase(java.util.Locale.ROOT);
		// "Jellies" is the Kill Log's name for the ledger's "Jelly", and
		// stripping one s leaves "jellie", which meets nothing. It is the only
		// name on my own record that the bare rule cannot bridge, out of 32
		// that reach no ledger source at all.
		//
		// Only this one extra case. The obvious next rule, ves to f, turns "The
		// Fight Caves" into "the fight caf", and nothing in the record needs it.
		if (n.endsWith("ies"))
		{
			return n.substring(0, n.length() - 3) + "y";
		}
		return n.endsWith("s") ? n.substring(0, n.length() - 1) : n;
	}

	// Distinct item names the stored log calls obtained: clog_items plus every
	// page's own capture (a page only lists what is lit). Zero with no log. The
	// stand-in for a journal with no header count: the union lags the game's own
	// figure while a page sits unvisited.
	static int obtainedSlots(JsonObject cl)
	{
		java.util.Set<String> names = new java.util.HashSet<>();
		if (cl == null)
		{
			return 0;
		}
		if (cl.has("clog_items") && cl.get("clog_items").isJsonObject())
		{
			for (java.util.Map.Entry<String, JsonElement> e
				: cl.getAsJsonObject("clog_items").entrySet())
			{
				names.add(e.getKey().toLowerCase(java.util.Locale.ROOT));
			}
		}
		if (cl.has("by_cat") && cl.get("by_cat").isJsonObject())
		{
			for (java.util.Map.Entry<String, JsonElement> pg
				: cl.getAsJsonObject("by_cat").entrySet())
			{
				if (!pg.getValue().isJsonObject())
				{
					continue;
				}
				for (java.util.Map.Entry<String, JsonElement> it
					: pg.getValue().getAsJsonObject().entrySet())
				{
					names.add(it.getKey().toLowerCase(java.util.Locale.ROOT));
				}
			}
		}
		return names.size();
	}

	/** The journal's stored collection log, deep-copied for the panel. */
	JsonObject clogSnapshot()
	{
		synchronized (lock)
		{
			if (root == null || !root.has("collection_log")
				|| !root.get("collection_log").isJsonObject())
			{
				return new JsonObject();
			}
			return root.getAsJsonObject("collection_log").deepCopy();
		}
	}

	/**
	 * Carry an account's journal across an in-game rename: move {@code <oldslug>.json}
	 * and {@code <oldslug>.history.jsonl} onto the new name's slugs. True when the
	 * journal itself moved.
	 *
	 * <p>A file already under the new slug is set aside, not mounted: freed names get
	 * taken, so it may be a stranger's record. Nothing is deleted. Journal and spine
	 * move together.
	 */
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
			// nothing to carry; leave whatever is under the new name alone
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

	/** Move a file aside under a dated sidecar name, keeping every byte. False when
	 *  it could not be moved. */
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
		catch (Exception e)   // noqa: best-effort; a false return tells the caller
		{
			log.warn("could not set aside {}", f, e);
			return false;
		}
	}

	static String slug(String rsn)
	{
		// ROOT: a Turkish JVM lowercases I to a dotless ı, which the strip then eats.
		String s = rsn == null ? ""
			: rsn.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
		s = s.replaceAll("(^-+|-+$)", "");
		return s.isEmpty() ? "profile" : s;
	}

	/**
	 * What the journal HOLDS, counted. Every other board answers a question
	 * about the account; this answers one about the record itself.
	 *
	 * <p>Raw counts only, and no dates and no name: the whole point is a page
	 * somebody can hand over. One pass, one lock.
	 */
	java.util.Map<String, Long> journalFacts()
	{
		java.util.Map<String, Long> out = new java.util.LinkedHashMap<>();
		synchronized (lock)
		{
			if (root == null)
			{
				return out;
			}
			out.put("schema", asLong(root.get("schema")));
			JsonObject drops = root.has("drops") && root.get("drops").isJsonObject()
				? root.getAsJsonObject("drops") : new JsonObject();
			long rows = 0;
			long loots = 0;
			long worth = 0;
			for (java.util.Map.Entry<String, JsonElement> e : drops.entrySet())
			{
				if (!e.getValue().isJsonObject())
				{
					continue;
				}
				JsonObject o = e.getValue().getAsJsonObject();
				loots += asLong(o.get("loots"));
				worth += asLong(o.get("value"));
				if (o.has("items") && o.get("items").isJsonObject())
				{
					rows += o.getAsJsonObject("items").size();
				}
			}
			out.put("sources", (long) drops.size());
			out.put("itemRows", rows);
			out.put("lootEvents", loots);
			out.put("lootWorth", worth);
			out.put("lootDays", (long) size(root, "loot_days"));
			out.put("untakenSources", (long) size(root, "untaken"));
			out.put("untakenItems", (long) size(root, "untaken_items"));

			JsonObject sl = root.has("slayer") && root.get("slayer").isJsonObject()
				? root.getAsJsonObject("slayer") : new JsonObject();
			out.put("tasks", sl.has("tasks") && sl.get("tasks").isJsonArray()
				? (long) sl.getAsJsonArray("tasks").size() : 0L);
			out.put("tasksClosed", asLong(sl.get("completed")));

			JsonObject cl = root.has("collection_log")
				&& root.get("collection_log").isJsonObject()
				? root.getAsJsonObject("collection_log") : new JsonObject();
			out.put("clogSlots", asLong(cl.get("finished")));
			out.put("clogAvailable", asLong(cl.get("available")));
			out.put("clogItems", (long) size(cl, "clog_items"));
			out.put("clogPages", (long) size(cl, "kcs"));
			out.put("killLogLines", (long) size(cl, "slayer_kcs"));
			out.put("pageKillLines", (long) size(cl, "kc_lines"));

			out.put("trackers", (long) size(root, "trackers"));
			out.put("skills", (long) size(root, "skills"));
			out.put("feed", root.has("feed") && root.get("feed").isJsonArray()
				? (long) root.getAsJsonArray("feed").size() : 0L);
			out.put("chatCounts", (long) size(root, "chat_kcs"));
			out.put("anchors", (long) size(root, "kc_anchors"));
		}
		File f = mountedDir == null || currentRsn == null
			? null : jsonPath(mountedDir, currentRsn);
		out.put("journalBytes", f != null && f.isFile() ? f.length() : 0L);
		File spine = mountedDir == null || currentRsn == null ? null
			: new File(mountedDir, slug(currentRsn) + ".history.jsonl");
		out.put("spineBytes", spine != null && spine.isFile() ? spine.length() : 0L);
		return out;
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
		try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp))
		{
			out.write(content.getBytes(StandardCharsets.UTF_8));
			// The move below is atomic over the file's name only; without forcing the
			// bytes down first, a power cut leaves that name pointing at a torn record.
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


	/** The slayer journey the journal computes for the panel, from its on-disk task array. */
	public static final class SlayerJourney
	{
		public final int completedTasks;
		public final long totalKills;
		public final long totalValueGp;
		public final long totalXpEst;
		public final java.util.List<SlayerTask> tasks;

		SlayerJourney(int completedTasks, long totalKills, long totalValueGp,
			long totalXpEst, java.util.List<SlayerTask> tasks)
		{
			this.completedTasks = completedTasks;
			this.totalKills = totalKills;
			this.totalValueGp = totalValueGp;
			this.totalXpEst = totalXpEst;
			this.tasks = tasks;
		}
	}

	/** One task segment of the journey, newest first. */
	public static final class SlayerTask
	{
		public final String task;
		public final long kills;
		public final long assignment;
		public final long noLootKills;
		public final double ts;          // epoch seconds
		public final long totalValue;
		public final boolean inProgress;

		SlayerTask(String task, long kills, long assignment, long noLootKills,
			double ts, long totalValue, boolean inProgress)
		{
			this.task = task;
			this.kills = kills;
			this.assignment = assignment;
			this.noLootKills = noLootKills;
			this.ts = ts;
			this.totalValue = totalValue;
			this.inProgress = inProgress;
		}
	}
}
