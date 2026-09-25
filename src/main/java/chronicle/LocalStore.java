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
	/**
	 * Which way of reckoning kill counts this build writes to the spine. Each
	 * spine line says the version that wrote it, and on the first login under
	 * a newer one the difference between the two, taken over the same journal,
	 * is written beside the next line so the change never reads as kills.
	 *
	 * <p>Bump it whenever {@link #reconciledKills} or {@link #spineKills} would
	 * give a different figure for the same journal, and keep the old version's
	 * branch: the shift is worked out by running both. KillsVersionTest pins
	 * what the current version gives, and fails on a change that did not bump.
	 *
	 * <p>0: the Plugin Hub's build to 7bd5812, the ledger raised to the page
	 * counter as the floor, and the kills sum over the ledger's own figures.
	 * 1: the page's labelled line and the ledger's own figure as the floor,
	 * and the kills sum over the reconciled figure of each fight the ledger
	 * has seen loot from, found by kind.
	 */
	static final int KILLS_VERSION = 1;
	// runaway guard
	private static final int FEED_CAP = 20000;
	// peak counters: lifetime is max(base, session) rather than base + session.
	static final Set<String> MAX_KEYS = new HashSet<>(
		Arrays.asList("highestHit", "highestHitTaken"));
	// Event types kept as dated feed lines. LOOT and LOOT_UNTAKEN are recorded too,
	// into the drop and untaken ledgers; GROUP_STORAGE isn't kept at all.
	private static final Set<String> FEED_TYPES = new HashSet<>(Arrays.asList(
		"PET", "COLLECTION", "COMBAT_ACHIEVEMENT", "QUEST", "DIARY", "CLUE", "DEATH", "SLAYER",
		"LEVEL",
		"SESSION"));   // SESSION is recorded by the plugin itself, so it stays off the network

	private final ItemManager itemManager;
	private final Gson gson;

	private final Object lock = new Object();
	// What the counts moved since the spine's last line that was not play is laid
	// by in the journal itself, under this key, for the next line. It has to
	// live beside the counts it explains: a pile in memory was lost to a client
	// closed between the journal's flush and the spine's next line, and the
	// counts it explained then read as a day's kills.
	static final String SPINE_ADJ = "spine_adj";
	// The reckoning a version step laid by on SPINE_ADJ brings the spine to, so
	// a login before that line is written does not lay the same step twice.
	static final String SPINE_ADJ_KV = "spine_adj_kv";
	// Set when a reading lays a correction by, so the next tick writes its line;
	// not by one handed back after a failed write, which waits for the write
	// interval rather than retrying every tick.
	private volatile boolean freshAdjust;
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
	private final ArrayDeque<RecentDrop> recentDrops = new ArrayDeque<>();
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

	// ------------------------------------------------------------------
	// Session lifecycle
	// ------------------------------------------------------------------

	/** Mount this account's record, or start one. Runs on the executor; call once
	 *  per login, before anything records. */
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
			trackersBase = loaded.getAsJsonObject("trackers").deepCopy();
			currentRsn = rsn;
			// What an earlier build, or an import, could leave inconsistent: the same
			// item twice in one source's bag, a feed line twice, by-item leavings that
			// no longer sum to the pairs, off-task kills counted toward a slayer task.
			healed += dedupeSourceBags() + dedupeFeed() + reconcileUntaken()
				+ purgeOffTaskMonsters() + splitDays();
			if (healed > 0)
			{
				log.debug("repaired {} journal entries on load", healed);
			}
			// Cleared first: the mirror must describe this account only, or the previous
			// character's ore would credit this one's drops.
			gatheredItems.clear();
			for (JsonElement g : arr(loaded, "gathered_items"))
			{
				long id = asLong(g);
				if (id > 0 && gatheredItems.size() < GATHERED_CAP)
				{
					gatheredItems.add((int) id);
				}
			}
			// Before the store says it is ready: a logout in between would write
			// a line under this build's reckoning with the step not yet laid by.
			settleKillsVersion(dir, rsn);
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

	/** Lay a change that was not play by, for the next line of the spine. */
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

	/**
	 * A correction a spine line failed to carry, put back for the next line of
	 * {@code rsn}'s spine: onto the journal it came from, or nowhere if another
	 * is mounted now.
	 */
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

	/** The change laid by, handed to the line about to be written, and cleared. */
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

	/** A correction a reading laid by since the last line was written. */
	boolean hasFreshAdjust()
	{
		return freshAdjust;
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
			appendFeed(type, data);
		}
	}

	/** One dated line onto the feed, newest last, the cap kept. */
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
			// index 0 is the oldest; the feed is appended in order
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

		// The standing PB when the game restated it, else this kill's own time when
		// it was the record.
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
		// This kill's own time, where the boss timer gave one. Kept as a count
		// and a sum, on the source and on the dated roll, so an average over
		// any period falls out without a row per kill.
		Double killTime = present(data, "killTime")
			&& data.get("killTime").getAsDouble() > 0 ? data.get("killTime").getAsDouble() : null;
		// The game flagging THIS kill as the new best; a best merely restated
		// on the first kill after install was set while nobody was watching.
		boolean newRecord = present(data, "personalBest")
			&& data.get("personalBest").getAsBoolean();

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
			if (killTime != null)
			{
				bump(src, "timed", 1);
				src.addProperty("timeSum", asDouble(src.get("timeSum")) + killTime);
			}
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
				// A record is a dated line of its own: the time it set and the
				// one it beat.
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

	// the breakdown goes off days older than DETAIL_DAYS; their totals stay
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

	// one kill's take, against today
	private void rollTaken(String source, long value, JsonArray priced, Double killTime)
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
			if (killTime != null)
			{
				bump(bySource, "timed", 1);
				bySource.addProperty("timeSum", asDouble(bySource.get("timeSum")) + killTime);
			}
			JsonObject items = sub(into, "items");
			// Its items per source as well as in total, the day's as the
			// sitting's, which is what lets a source's page answer for any
			// period with what it paid it IN. The day's heap stays beside them:
			// an older build reads only that.
			JsonObject mine = sub(bySource, "items");
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

	// one kill's floor, against today
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

	/** What the dated roll holds for a window; every figure zero and every list
	 *  empty when the window holds nothing. */
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
		// per source: {kills the timer timed, their seconds summed}
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
			return first == null ? 0 : LocalDate.parse(first)
				.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
		}
	}

	/** The roll summed over [from, to], both days included. */
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

	/** Each day the roll holds, keyed yyyy-MM-dd: {loots, value, left, leftValue}. */
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

	/**
	 * When one item landed, off the dated roll: {first day, last day, days it
	 * landed}, the days as millis at local midnight; zeros where it never did.
	 */
	long[] itemDays(String name)
	{
		String first = null;
		String last = null;
		int days = 0;
		synchronized (lock)
		{
			JsonObject all = obj(root, "loot_days");
			for (String day : all.keySet())
			{
				if (!all.get(day).isJsonObject() || !dayHolds(all.getAsJsonObject(day), name))
				{
					continue;
				}
				days++;
				first = first == null || day.compareTo(first) < 0 ? day : first;
				last = last == null || day.compareTo(last) > 0 ? day : last;
			}
		}
		return days == 0 ? new long[3] : new long[]{dayMs(first), dayMs(last), days};
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
		synchronized (lock)
		{
			w.add(sessionRoll);
		}
		return w.ranked();
	}

	/**
	 * Each source's own items over the days [from, to], or this sitting's when
	 * {@code from} is null, ranked, under the source's name matched without case.
	 * A drop that fell on a day the roll kept only as one heap has no row here:
	 * what the roll holds for a source less its rows is what went unitemised.
	 */
	Map<String, List<BagItem>> itemsBySource(LocalDate from, LocalDate to)
	{
		Map<String, Map<String, long[]>> by = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
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
						String label = str(e, "n", id);
						long[] t = into.computeIfAbsent(label, k -> new long[2]);
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
		}
		Map<String, List<BagItem>> out = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		by.forEach((source, items) -> out.put(source, bagRows(items, ids, 0)));
		return out;
	}

	/**
	 * Files each day's heap under its sources where the record proves whose each
	 * item was, for days the roll kept only as one heap: on a day with one source
	 * the heap is all its own, and on a day with several an item only one of them
	 * has ever dropped is that one's. Written only where every source's rows then
	 * come to exactly the value the roll holds for it; any other day keeps its
	 * heap and its sources' totals. A day with several sources, any of them
	 * already itemised, is the recorder's and is left alone.
	 */
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
			if (srcs.size() == 0 || heap.size() == 0)
			{
				continue;
			}
			boolean single = srcs.size() == 1;
			Map<String, JsonObject> rows = new HashMap<>();
			for (String source : srcs.keySet())
			{
				if (!single && obj(srcs, source).has("items"))
				{
					continue days;
				}
				rows.put(source, new JsonObject());
			}
			for (String id : heap.keySet())
			{
				String name = str(obj(heap, id), "n", "");
				String owner = null;
				for (String source : srcs.keySet())
				{
					if (single || dropped(obj(obj(drops, source), "items"), name))
					{
						if (owner != null)
						{
							continue days;   // two sources could have dropped it
						}
						owner = source;
					}
				}
				if (owner == null)
				{
					continue days;
				}
				rows.get(owner).add(id, obj(heap, id).deepCopy());
			}
			for (String source : srcs.keySet())
			{
				long v = 0;
				for (var it : rows.get(source).entrySet())
				{
					v += asLong(obj(rows.get(source), it.getKey()).get("v"));
				}
				if (v != asLong(obj(srcs, source).get("value")))
				{
					continue days;
				}
			}
			boolean changed = false;
			for (String source : srcs.keySet())
			{
				if (!rows.get(source).equals(obj(srcs, source).get("items")))
				{
					obj(srcs, source).add("items", rows.get(source));
					changed = true;
				}
			}
			split += changed ? 1 : 0;
		}
		return split;
	}

	// whether a source's lifetime bag holds an item of this name
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

	// fold one day's breakdown into the running tally. `named` reads the stored
	// display name off the entry; a source is named by its own key.
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

	// the timed kills a day's source entries hold, summed per source
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

	// biggest by value first, as {name, qty, value}
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

	/** Refresh the character sheet. Runs on the client thread, and once on the EDT
	 *  from the plugin's shutDown, which banks the session on a settings toggle; the
	 *  lock covers both. {@code collectionLog} is the capture's raw map, converted to
	 *  a tree here. */
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
			// What the counts were before this reading, so what it moved that was
			// not play can be laid by (noteArrival).
			Counts before = collectionLog != null ? countsNow() : null;
			if (collectionLog != null)
			{
				// Anchor from what has just been READ, not from the merged result:
				// a page not opened this session keeps its old number through the
				// merge, and re-anchoring on it would throw away the observations
				// that number has been riding on.
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
				// The session's capture is partial, covering only the pages browsed.
				// Clog data only grows; union it into the stored log.
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

	/** The counts as they stand, and what each fight's figure rests on. */
	@AllArgsConstructor(access = AccessLevel.PACKAGE)
	private static final class Counts
	{
		// the reconciled figures, by the reconciliation's own names
		final Map<String, Long> kills;
		// by kind: 1 a page counter, 2 something the game said; absent, the ledger alone
		final Map<String, Integer> rank;
		// the spine's kills sum, and the kinds it adds up
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
			// The page's raw counter is a word, but not one about kills: Tempoross's
			// held 46, the tail of a 3:46 best time, where the Kill Log says 455.
			for (String name : obj(cl, "kcs").keySet())
			{
				rank.put(chatKind(name), 1);
			}
			// What the game said: the Kill Log, a page's labelled line (not a page
			// merely opened, whose lines can be empty), a chat line and an anchor,
			// the last two by the fight they name, not the words the chat box used.
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

	/**
	 * What a reading just moved that was not play, laid by for the spine.
	 *
	 * <p>A fight's first word of a higher kind brings its whole past with it:
	 * the Kill Log opened for the first time said Tempoross 455 where the page's
	 * counter had 46, and the day it was read claimed 409 kills. So a count that
	 * comes to rest on a better source than it had (the ledger, then a page
	 * counter, then something the game said) moved by a correction, and so did
	 * any count that falls. A fight already resting on a statement moves by
	 * play, kills the ledger did not see (no drop, another device), and those
	 * stay kills. {@code announced} is the kill a chat line announces with its
	 * count: that one was played.
	 *
	 * <p>Fights are matched by kind: a first statement can re-file a fight under
	 * the game's spelling ("Abyssal demons" for the ledger's "Abyssal demon"),
	 * and a name looked up as it stood found nothing and laid nothing by.
	 */
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
				continue;   // a fight new to the record measures from its first figure
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
		// The sum's own step, less what of it was play: a fight that joined or
		// left the sum, or changed its name in it, is in the step and in no row.
		long sumShift = after.sum - before.sum - played;
		if (sumShift != 0)
		{
			adj.counters.put("kills", sumShift);
		}
		addPendingAdjust(adj);
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
				trackersBase = root.getAsJsonObject("trackers").deepCopy();
			}
		}
	}

	/**
	 * Refresh the lifetime tracker counters from this session's live totals. Runs on
	 * the client thread, and once on the EDT from the plugin's shutDown; the lock
	 * covers both. {@code session} is the from-zero session snapshot; lifetime
	 * is base + session (max for the peak counters), so repeat calls never double up.
	 */
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

	/**
	 * Every counter's lifetime figure, from the frozen base and a session, worked
	 * out without writing anything.
	 *
	 * <p>The same arithmetic setTrackers persists, lifted out so a reader can have
	 * it at any moment. The panel used to read the PERSISTED copy, which only
	 * moves when the journal is flushed, so a board showing what this account has
	 * gathered sat still through an hour of gathering and then jumped on logout.
	 */
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
		// epoch ms, 0 = unknown; comes in from the Loot Tracker import's per-source
		// range and extends as play continues.
		final long firstMs;
		final long lastMs;
		// lower-cased name of every item the bag holds a copy of. The dryness book
		// reads it as obtained: a unique already looted is never a chase, whatever the
		// stored log says.
		final Set<String> looted;
		// kills the boss timer gave a time for, and those times summed in seconds
		final long timed;
		final double timeSum;
	}

	/** Every drop source, unsorted (the panel ranks). */
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

	// The names in one source's bag, lower-cased, holding only entries with a copy
	// in hand: a zero-quantity row is a name the bag once knew, not an item owned.
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

	/** Newest feed entries, newest first (deep copies). */
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
			: "n:" + (name == null ? "" : name.toLowerCase(Locale.ROOT));
	}

	/** One source's whole record from the core Loot Tracker's local store, already
	 *  canonicalised and priced by the caller (client thread). */
	@AllArgsConstructor(access = AccessLevel.PACKAGE)
	static final class LootSeed
	{
		final String source;
		final int kills;
		final long firstMs;
		final long lastMs;
		final List<BagItem> items;
	}

	/**
	 * Floor this account's drops with the core Loot Tracker's own lifetime record.
	 * kc and loots take the tracker's event count as a lower bound (a higher
	 * game-reported kc survives), item qty/value match by id then by name, source
	 * value takes the priced sum, first_seen/last_seen extend as min/max. A re-run
	 * can only raise floors it already set.
	 */
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
						// a name-keyed entry picks up its real id here
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
			// Hundreds of rows have just been seeded from the Loot Tracker. None of
			// them is a kill that happened since an anchor was taken, so the
			// baselines move with them rather than the counts.
			rebaseAnchors();
		}
	}

	/** One source's item bag, unsorted. */
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

	/** Left-behind loot, priced at record like drops and aggregated per source
	 *  ({@code untaken: {source: {qty, value, kills}}}). {@code kills} is the
	 *  capture's count of the kills that left at least one of the event's stacks,
	 *  the unit "Drops taken" subtracts in; an imported or older event carries none
	 *  and reads as 0. */
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
			// the same tally keyed by item name
			JsonObject byItem = sub(root, "untaken_items");
			for (BagItem b : perItem)
			{
				JsonObject e = sub(byItem, b.name);
				bump(e, "qty", b.qty);
				bump(e, "value", b.value);
			}
			rollLeft(qty, value, kills, perItem);
			// …and the pairing, so the lens drills from either end: which items a
			// source left, and which sources left an item.
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

	/** A name/qty/value row: untaken sources, untaken items, task monsters. An
	 *  untaken source also carries the kills that left its stacks; 0 elsewhere. */
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
			JsonObject store = sub(root, "consumable_values");
			long cur = present(store, key) ? store.get(key).getAsLong() : 0;
			store.addProperty(key, cur + gp);
			touch();
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

	/** The lifetime base (pre-session) for one counter, or 0 when unknown. */
	long trackerBase(String key)
	{
		synchronized (lock)
		{
			return trackersBase != null && present(trackersBase, key)
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
		JsonObject sl = sub(root, "slayer");
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
		return present(seg, "open") && seg.get("open").getAsBoolean();
	}

	private static boolean namesTask(JsonObject seg, String task)
	{
		return present(seg, "task")
			&& task.equalsIgnoreCase(seg.get("task").getAsString());
	}

	/** A numeric field, or null when absent. */
	private static Long optLong(JsonObject o, String key)
	{
		return o.has(key) && o.get(key).isJsonPrimitive()
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
		String task = str(data, "slayerTask", null);
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
			seg = newSegment(tasks, task);
			seg.addProperty("open", true);
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

	/**
	 * A task completion: close the open segment, opening one first if the whole task
	 * went unwitnessed. The finished line's exact kill count trues up the loot spine;
	 * kills the drops never saw surface as {@code noLootKills}.
	 */
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
			long done = asLong(sl.get("completed"));
			// the streak line's lifetime total wins when it's ahead
			sl.addProperty("completed", streak != null && streak > done ? streak : done + 1);
			touch();
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
	List<BagItem> onTaskLoot(long fromMs, long toMs, boolean includeOpen)
	{
		return onTaskLoot(fromMs, toMs, null, includeOpen);
	}

	/**
	 * Every item the whole ledger holds, summed across sources and ranked by
	 * what it came to. One walk of `drops` under one lock, rather than the
	 * source list plus a bag read per source.
	 */
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

	/**
	 * Every task name the journey holds, newest first and without repeats, which
	 * is the order a picker wants: what you fought lately, first.
	 */
	List<String> taskNames()
	{
		LinkedHashSet<String> names = new LinkedHashSet<>();
		synchronized (lock)
		{
			JsonArray arr = taskArray();
			// the journal keeps them oldest first; a picker wants the newest at
			// the top, so this walks back
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
	@AllArgsConstructor(access = AccessLevel.PACKAGE)
	static final class Assignment
	{
		final String task;
		final long ts;
		final long killsHere;
		final long kills;
		final long value;
	}

	/** Whether a task's stamp puts it inside the window. An unstamped task is in. */
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

	/**
	 * Every item the tasks paid, inside the window: name to {qty, value}.
	 *
	 * <p>One pass over the journey, because the answer is asked of every row of
	 * a board rather than once. It is also the only honest way to ask whether an
	 * item HAS an on-task side at all: 287 of the 655 names on this account do,
	 * and the other 368 must not be offered a filter that would show them
	 * nothing.
	 */
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

	/**
	 * Every monster the tasks were fought against, inside the window: name to
	 * kills. Exact, unlike the loot: a task DOES say how many of each thing it
	 * killed.
	 */
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

	/**
	 * One item's on-task total split by the TASK that paid it, dearest first.
	 * What the FROM list on an item's page becomes when the filter is on task:
	 * the record can name the assignment exactly and the monster not at all.
	 */
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

	/**
	 * Every assignment that included one monster, newest first.
	 *
	 * <p>This is what an NPC's page can honestly show on task. The kills are
	 * that monster's and exact; the worth is the whole assignment's, which is
	 * why the row names the task rather than the monster.
	 */
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

	/**
	 * The same, narrowed to one task by name.
	 *
	 * <p>By NAME rather than by segment: a reader asking what Nechryael have
	 * paid means all eleven of them, not the one that closed on Tuesday.
	 */
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

	/**
	 * The superior forms, which a task's monster roll names but never marks as
	 * one. Bundled as chronicle/store_superiors.json, taken from the site's own
	 * reference (reference/osrs_superiors.json, itself from the wiki) so the two
	 * count the same thing. Matched without case: the journal has "Shadow Wyrm"
	 * and "Malevolent Mage" as the game spelled them at the time.
	 */
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

	/** The journey as the journal knows it, shaped for the panel (newest first). */
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
					// Only the newest segment can be in progress: an older one without a
					// completion was parked or its completion was missed, and is done.
					i == tasks.size() - 1 && isOpen(seg)));
			}
			return new SlayerJourney(
				(int) asLong(sl.get("completed")),
				totalKills, totalValue,
				asLong(sl.get("xp_est")),
				out);
		}
	}

	/** Lifetime gp per consumable counter key, accumulated at each bite or dose. */
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

	/** The per-item side of the uncollected ledger. */
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

	/** {finished, available} as the journal holds them; zero when unknown. */
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
		// Every counter a page carries, by the log's own name for it. Nested the
		// way by_cat is -- page, then label -- and floor-merged the same way, so
		// a page not opened this session keeps what it last said.
		JsonObject kcLines = nested(base, inc, "kc_lines", false);
		if (kcLines.size() > 0)
		{
			out.add("kc_lines", kcLines);
		}
		// Best times, merged the other way about. Every other figure here only
		// grows, so they are floored; a personal best IMPROVES DOWNWARD, and
		// flooring it would pin the worst time ever recorded and never let go.
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
			// Drop ledger: per source, then per item inside it.
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
					// first_seen only ever moves earlier
					if (present(inc, "first_seen"))
					{
						long incFirst = asLong(inc.get("first_seen"));
						long curFirst = asLong(cur.get("first_seen"));
						if (incFirst > 0 && (curFirst == 0 || incFirst < curFirst))
						{
							cur.addProperty("first_seen", incFirst);
						}
					}
					// a PB is the lowest time; the compare is flipped
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
						// The bag is keyed by item id and an export may know only names, so
						// match on name first; otherwise one herb ends up on two lines.
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
								// No name match: file it under its id when the entry
								// carried one, so the panel can draw the sprite, else keep
								// the incoming map key, which on an id-keyed bag is the id;
								// dedupeSourceBags below folds in the plain-name keys an
								// older export left behind
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
				// Another machine's ledger has just been merged in. Those rows are
				// not kills that happened since an anchor was taken here, and the
				// merge can only raise the counts, so the baselines move with them.
				rebaseAnchors();
			}
			// the dated feed, deduplicated on feedKey
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
				// an import interleaves, and the panel reads the feed in stored order
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
			// collection log: the same max-union used on capture
			if (in.has("collection_log") && in.get("collection_log").isJsonObject())
			{
				root.add("collection_log", mergeClog(sub(root, "collection_log"), in.getAsJsonObject("collection_log")));
			}
			// The chat box's counts travel with it. This block names every key it
			// carries, so one left out is dropped in silence.
			if (in.has("chat_kcs") && in.get("chat_kcs").isJsonObject())
			{
				JsonObject mine = sub(root, "chat_kcs");
				for (var e : in.getAsJsonObject("chat_kcs").entrySet())
				{
					raise(mine, e.getKey(), asLong(e.getValue()));
				}
			}
			// The gathered ledger travels too, or ore mined on the other machine goes
			// unrecognised here.
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

	/** Floor one segment's {@code monsters} or {@code items} map into another's. */
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
				// Quantity floors; a price does not. Same argument as the Loot
				// Tracker seed above: the larger of two valuations of the same
				// drop is not a better number, it is whichever day the import
				// ran. A price is seeded into a row that has none and left
				// alone otherwise.
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

	/**
	 * Identity of a feed line: kind, the second it happened in, and subject. The exact
	 * instant is too fine: a re-imported event can land a millisecond off. The second
	 * on its own is too coarse, since a clue casket empties several slots inside it.
	 */
	private static String feedKey(JsonObject e)
	{
		long sec = asLong(e.get("ts")) / 1000L;
		String kind = str(e, "type", "");
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
			if (present(d, field))
			{
				return d.get(field).getAsString().toLowerCase(Locale.ROOT);
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
			// keep whichever says more; a named line beats a bare "imported" marker
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

	/** Raise {@code cur[key]} to {@code inc[key]} when the incoming one is higher. */
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

	/** Floor a flat {name: {qty, value}} store, or a flat {key: number} one. */
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
				floorNumber(curRow, incRow, "kills");   // the untaken store's third figure
			}
			else
			{
				raise(cur, e.getKey(), asLong(e.getValue()));
			}
		}
	}

	/** Items one source was left holding, richest first. */
	List<BagItem> untakenItemsOf(String source)
	{
		synchronized (lock)
		{
			return bagOf(obj(obj(root, "untaken_pairs"), source));
		}
	}

	/** Sources that left a given item on the ground, most first. */
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

	/** One task segment's own loot, richest first. */
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

	/** What a task was actually made of: {monster: kills}, most killed first. */
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

	/** The task segment the panel's journey list calls {@code index}. The journey
	 *  is served newest-first, the store keeps them oldest-first. */
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

	/** Pets the journal has seen drop, newest first. Source and kc survive only on
	 *  rows imported from an older record: a PET event carries the name alone. */
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

	/**
	 * Collapse item entries that name the same thing within one source. The bag is
	 * keyed by item id, a merged-in record may know only names, and an earlier build
	 * filed those alongside the entry already there. The survivor takes the higher of
	 * the two and keeps the id-bearing key. Callers hold {@code lock}. Returns how
	 * many entries were absorbed.
	 */
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
	private static final Pattern KEY_LEVEL =
		Pattern.compile("\\(level[\\s-]*\\d*\\)?",
			Pattern.CASE_INSENSITIVE);

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

	/** Combat level as last gathered, or 0. */
	int combatLevel()
	{
		synchronized (lock)
		{
			return root != null ? (int) asLong(root.get("combat_level")) : 0;
		}
	}

	/** The character sheet's skills: {skill: [level, xp]}, as last gathered. */
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
			return obj(root, "achievements").deepCopy();
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
		// The reconciled FIGURE for each fight, over the ledger's own MEMBERSHIP.
		//
		// Two different questions, and the old fold answered both with sourceKills.
		// Which fights count is the ledger's: a collection log page the ledger never
		// saw loot from is mostly a minigame round, and the Kills list buckets those
		// under Activities rather than showing them here. But what each one COUNTS
		// is the reconciliation's, which applies the Kill Log, the chat line and the
		// anchors over the page counters, and that is the figure the list draws.
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

	/**
	 * The counters one history line carries: the trackers as they stand, with the
	 * {@link #spineExtras spine extras} laid beside them. A fresh copy; the trackers
	 * themselves are untouched.
	 */
	Map<String, Long> spineCounters()
	{
		Map<String, Long> out = trackersSnapshot();
		out.putAll(spineExtras());
		return out;
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
	static Map<String, Long> reconciledKills(JsonObject clog,
		List<SourceRow> sources, Map<String, Long> chat,
		Map<String, Long> anchored)
	{
		return reconciledKills(clog, sources, chat, anchored, KILLS_VERSION);
	}

	/** The same, as the given {@link #KILLS_VERSION} reckoned it. */
	static Map<String, Long> reconciledKills(JsonObject clog,
		List<SourceRow> sources, Map<String, Long> chat,
		Map<String, Long> anchored, int version)
	{
		Map<String, Long> out = clogKillCounts(clog);
		// What the game itself says about the encounter: the Kill Log, and the
		// chat line it prints on the kill. The Kill Log only moves when the
		// player opens an interface, so a number resting on it alone is frozen
		// between visits; the chat line arrives on every kill, with nothing
		// opened and nothing fetched. Both are the game counting and both only
		// count up, so between the two the larger is the later reading.
		Map<String, Long> stated = killLogCounts(clog);
		foldChatCounts(stated, chat, out.keySet());
		// A statement always beats the page counter, which need not be counting
		// kills at all: Wintertodt's page counts rewards claimed and says 1,078
		// where 448 were killed, and the larger of those two is the lie.
		placeByKind(out, stated, false);
		if (version < 1)
		{
			// Version 0: the ledger, raised to the page counter, as the floor.
			placeByKind(out, sourceKills(clog, sources), true);
			placeByKind(out, anchored, false);
			return out;
		}
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
	static Map<String, Long> sourceKills(JsonObject clog,
		List<SourceRow> sources)
	{
		return sourceKills(clog, sources, true);
	}

	/**
	 * The spine's kills sum as the given version reckoned it: version 0 summed
	 * the ledger's own figures, 1 the reconciled figure of each fight the
	 * ledger has seen loot from.
	 */
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

	/**
	 * The reconciled entries the kills sum adds up: each fight the ledger has
	 * seen loot from, found under the name the reconciliation filed it by, once.
	 *
	 * <p>Found by kind, not by name. The reconciliation keeps the first spelling
	 * it meets, which is the Kill Log's or the page's ("Gargoyles", "The Mad
	 * Angel") where the ledger says "Gargoyle" and "Mad Angel"; looking the
	 * ledger's name up as it stands left 25 fights and 18,525 kills out of a
	 * real record's sum, and a slayer task on any of them added nothing to it.
	 */
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

	/**
	 * What moving from version {@code from} to this build's changes, fight by
	 * fight and in the kills sum, worked out over the same journal: the whole
	 * of the step the spine would otherwise read as a day's kills. A fight only
	 * one version knows needs nothing; the spine measures it from its first
	 * figure either way.
	 */
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

	/**
	 * Lay the step from the reckoning the spine's newest line was written under
	 * to this build's by, for the next line, so the change never reads as kills.
	 * A line that does not say was written by the Plugin Hub's build to
	 * 7bd5812, version 0: no other build wrote lines without saying.
	 */
	private void settleKillsVersion(File dir, String rsn)
	{
		Integer newest = HistoryLog.newestKv(gson, dir, rsn);
		if (newest == null)
		{
			return;   // a first line has nothing before it to shift
		}
		int from = newest;
		synchronized (lock)
		{
			// A step already laid by and not yet written brought the spine that far.
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
			// Laid at login, where the first write interval puts it on the spine;
			// nothing for the tick path to hurry.
			freshAdjust = false;
		}
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
			touch();
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

	/**
	 * The kill an anchor was taken at, arriving afterwards as a drop. Re-takes
	 * that anchor's baseline so the row is not read as a kill since.
	 */
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

	/** Every anchored fight at its stated count plus what has been seen since. */
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
		for (var e : obj(root, "kc_anchors").entrySet())
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
		// Held throughout, so no other reading lands between the two counts and
		// has its correction laid by twice, once by each.
		synchronized (lock)
		{
			if (root == null)
			{
				return;
			}
			// A repeat costs nothing: the recount below is the expensive part.
			JsonObject known = obj(root, "chat_kcs");
			if (known.has(subject) && asLong(known.get(subject)) >= tally)
			{
				return;
			}
			Counts before = countsNow();
			revision++;
			sub(root, "chat_kcs").addProperty(subject, tally);
			touch();
			// and as an anchor, so the count keeps moving between announcements
			anchorKill(subject, tally, "chat", rsn);
			noteArrival(before, 1);
		}
	}

	/** Every count the chat box has announced, by the name the game used. */
	Map<String, Long> chatKillCounts()
	{
		synchronized (lock)
		{
			return positives(root, "chat_kcs");
		}
	}

	/**
	 * The game's own Kill Log, by species: a per-encounter tally of lifetime
	 * kills. This is what a kill count MEANS, and it is the only one of the three
	 * sources that is always one: a collection log page's header counter can be
	 * counting something else entirely (Wintertodt's counts rewards claimed), and
	 * a loot tally counts rows rather than kills.
	 */
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
			if (v > 0)   // a non-numeric entry is not a kill count
			{
				out.put(e.getKey(), v);
			}
		}
		return out;
	}

	/**
	 * The collection log's kill counts as the stored log lists them, by page name:
	 * the positive numeric entries of its {@code kcs}, a non-numeric one being no
	 * kill count. Empty with no log.
	 */
	static Map<String, Long> clogKillCounts(JsonObject clog)
	{
		Map<String, Long> out = positives(clog, "kcs");
		// And where the page's own lines were captured, the LABELLED one wins. kcs
		// holds whichever number came first on the page, which is not always the
		// kills: Wintertodt's first line counts rewards claimed. Worse, a reading
		// taken before those labels were captured could be half of a best time,
		// and kcs is floor-merged, so the Gauntlet's 55 would outlive the 31 that
		// corrects it. A named line is not a guess and replaces it outright.
		if (clog != null && clog.has("kcs") && clog.get("kcs").isJsonObject())
		{
			out.putAll(pageKillLines(clog));
		}
		return out;
	}

	/**
	 * Each page's own kill count, by the name the page gives it: the first line
	 * whose label says kills or completions. A page carrying only rewards claimed
	 * or a best time has none, which is the honest answer for it.
	 */
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
		// Names the game's own counts have not met yet still have to find their
		// source, or a chat reading lands beside a page counter for the same thing
		// instead of replacing it.
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
		String n = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
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

	/** The journal's stored collection log, deep-copied for the panel. */
	JsonObject clogSnapshot()
	{
		synchronized (lock)
		{
			return obj(root, "collection_log").deepCopy();
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
			: rsn.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
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
	@AllArgsConstructor(access = AccessLevel.PACKAGE)
	public static final class SlayerJourney
	{
		public final int completedTasks;
		public final long totalKills;
		public final long totalValueGp;
		public final long totalXpEst;
		public final List<SlayerTask> tasks;
	}

	/** One task segment of the journey, newest first. */
	@AllArgsConstructor(access = AccessLevel.PACKAGE)
	public static final class SlayerTask
	{
		public final String task;
		public final long kills;
		public final long assignment;
		public final long noLootKills;
		public final double ts;          // epoch seconds
		public final long totalValue;
		public final boolean inProgress;
	}
}
