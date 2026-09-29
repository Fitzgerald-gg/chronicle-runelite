/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import chronicle.SlayerLog.SlayerJourney;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.game.ItemManager;
import static chronicle.JournalFile.*;
import static chronicle.Json.*;
import static chronicle.KillCounts.*;
import static chronicle.Merge.*;
import static chronicle.SlayerLog.*;

@Singleton
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
class LocalStore implements chronicle.counters.GatheredLedger
{
	private static final int SCHEMA = 1;
	private static final int FEED_CAP = 20000;
	static final Set<String> MAX_KEYS = Set.of("highestHit", "highestHitTaken");
	private static final Set<String> FEED_TYPES = Set.of("PET", "COLLECTION", "COMBAT_ACHIEVEMENT", "QUEST", "DIARY", "CLUE",
		"DEATH", "SLAYER", "LEVEL", "SESSION");

	private final ItemManager itemManager;
	private final Gson gson;

	final Object lock = new Object();
	final LootDays loot = new LootDays(this);
	final SlayerLog slayer = new SlayerLog(this);
	private static final String SPINE_ADJ = "spine_adj";
	private volatile boolean freshAdjust;
	JsonObject root;
	private JsonObject trackersBase;
	private String currentRsn;
	private File mountedDir;
	private volatile boolean ready;
	@Getter(AccessLevel.PACKAGE)
	private volatile String journalWarning;

	private final ArrayDeque<RecentDrop> recentDrops = new ArrayDeque<>();

	private static final int GATHERED_CAP = 1024;
	private final Set<Integer> gatheredItems = ConcurrentHashMap.newKeySet();

	@AllArgsConstructor(access = AccessLevel.PACKAGE)
	static final class RecentDrop
	{
		final int itemId;
		final int quantity;
		final String name;
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
			log.warn("journal {} is schema {}; this build reads {}", f.getName(), fileSchema, SCHEMA);
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
			loot.sessionRoll = new JsonObject();
			recentDrops.clear();
			gatheredItems.clear();
		}
	}

	private void addPendingAdjust(HistoryLog.Adjust adj)
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

	@Getter(AccessLevel.PACKAGE)
	private volatile long revision;

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
				slayer.recordSlayerCompletion(data);
			}
			appendFeed(type, data);
		}
	}

	private void appendFeed(String type, JsonObject data)
	{
		JsonObject entry = Json.of("ts", System.currentTimeMillis(), "type", type);
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
			slayer.slayerLoot(data, batchValue, source, priced);
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
			loot.rollTaken(source, batchValue, priced, killTime);
			priced.forEach(b -> recentDrops.addFirst(new RecentDrop(b.itemId, (int) b.qty, b.name)));
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
					JsonObject rec = Json.of("source", source, "time", pbCand);
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

	static void timeKill(JsonObject o, Double killTime)
	{
		if (killTime != null)
		{
			bump(o, "timed", 1);
			o.addProperty("timeSum", asDouble(o.get("timeSum")) + killTime);
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
							anchorKill(String.valueOf(e.getKey()), ((Number) e.getValue()).longValue(), "log", rsn);
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
			obj(cl, "kcs").keySet().forEach(name -> rank.put(chatKind(name), 1));
			Map<String, Long> said = killLogCounts(cl);
			said.putAll(pageKillLines(cl));
			Set<String> vocabulary = clogKillCounts(cl).keySet();
			foldChatCounts(said, chat, vocabulary);
			foldChatCounts(said, anchored, vocabulary);
			said.keySet().forEach(name -> rank.put(chatKind(name), 2));
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
			lifetimeOf(session).entrySet().forEach(e -> tr.addProperty(e.getKey(), e.getValue()));
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
		JsonObject o = Json.of("schema", SCHEMA, "rsn", rsn, "first_seen", nowSec(), "updated_at", nowSec());
		normalise(o, rsn);
		return o;
	}

	private static JsonObject newSource()
	{
		JsonObject src = Json.of("kc", 0, "loots", 0, "value", 0);
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

	static long nowSec()
	{
		return System.currentTimeMillis() / 1000L;
	}

	void touch()
	{
		root.addProperty("updated_at", nowSec());
	}

	ItemManager items()
	{
		return itemManager;
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

	static Integer idOf(String key)
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
			loot.rollLeft(qty, value, kills, perItem);
			fileByName(sub(sub(root, "untaken_pairs"), source), perItem, true);
			touch();
		}
	}

	static void fileByName(JsonObject bag, List<BagItem> items, boolean withId)
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

	List<BagItem> allLoot()
	{
		Map<String, Tally> summed = new LinkedHashMap<>();
		Map<String, Integer> ids = new LinkedHashMap<>();
		synchronized (lock)
		{
			objects(obj(root, "drops")).forEach(src -> sumItems(obj(src.getValue(), "items"), summed, ids, true));
		}
		return bagRows(summed, ids, -1);
	}

	static void sumItems(JsonObject bag, Map<String, Tally> summed, Map<String, Integer> ids, boolean byName)
	{
		for (var e : objects(bag))
		{
			JsonObject v = e.getValue();
			String key = byName ? str(v, "name", "") : e.getKey();
			if (byName && key.isEmpty())
			{
				continue;
			}
			Tally.add(summed, key, asLong(v.get("qty")), asLong(v.get("value")));
			if (ids != null && !ids.containsKey(key) && v.has("id"))
			{
				ids.put(key, (int) asLong(v.get("id")));
			}
		}
	}

	static List<BagItem> bagRows(Map<String, Tally> summed, Map<String, Integer> ids, int noId)
	{
		List<BagItem> out = new ArrayList<>();
		summed.values().forEach(t -> out.add(new BagItem(ids.getOrDefault(t.name, noId), t.name, t.qty, t.value)));
		out.sort((a, b) -> Long.compare(b.value, a.value));
		return out;
	}

	static
	{
		try (InputStream in = LocalStore.class.getResourceAsStream("/chronicle/store_superiors.json"))
		{
			for (JsonElement e : new com.google.gson.JsonParser().parse(
				new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray())
			{
				SlayerLog.SUPERIORS.add(e.getAsString());
			}
		}
		catch (Exception e)
		{
			log.warn("superiors table unreadable", e);
		}
	}

	Map<String, Long> consumableValues()
	{
		Map<String, Long> out = new LinkedHashMap<>();
		synchronized (lock)
		{
			if (root != null)
			{
				fill(root, "consumable_values", out);
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

	Fraction clogFraction()
	{
		synchronized (lock)
		{
			JsonObject cl = obj(root, "collection_log");
			return new Fraction(asLong(cl.get("finished")), asLong(cl.get("available")));
		}
	}

	static List<JsonObject> newestFirst(JsonArray a)
	{
		List<JsonObject> out = objects(a);
		Collections.reverse(out);
		return out;
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
			obj(in, "chat_kcs").entrySet().forEach(e -> raise(sub(root, "chat_kcs"), e.getKey(), asLong(e.getValue())));
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
				objects(in.getAsJsonObject("untaken_pairs")).forEach(e ->
					mergeRows(sub(pairs, e.getKey()), e.getValue(), false));
			}
			if (isObject(in, "slayer"))
			{
				slayer.importSlayer(in.getAsJsonObject("slayer"));
			}
			touch();
		}
		return sources + " sources · " + String.format(Locale.UK, "%,d", events)
			+ " journal entries · " + counters + " counters";
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

	static List<BagItem> bagOf(JsonObject bag)
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

	Map<String, SkillRow> skillSheet()
	{
		Map<String, SkillRow> out = new LinkedHashMap<>();
		synchronized (lock)
		{
			objects(obj(root, "skills")).forEach(e ->
				out.put(e.getKey(), new SkillRow(asLong(e.getValue().get("level")), asLong(e.getValue().get("xp")))));
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

	private Map<String, Long> spineExtras()
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
		Map<String, Long> reconciled = reconciledKills(cl, sources, chatKillCounts(), anchoredKills());
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
		SlayerJourney journey = slayer.slayerJourney();
		long finished = clogFraction().done;
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

	private void anchorKill(String name, long stated, String src, String rsn)
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
			JsonObject a = Json.of("n", stated, "ts", System.currentTimeMillis(), "src", src, "obs", observedFor(name));
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
		objects(obj(root, "kc_anchors")).forEach(e -> e.getValue().addProperty("obs", observedFor(e.getKey())));
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

	private static int obtainedSlots(JsonObject cl)
	{
		Set<String> names = new HashSet<>();
		obj(cl, "clog_items").keySet().forEach(k -> names.add(k.toLowerCase(Locale.ROOT)));
		objects(obj(cl, "by_cat")).forEach(pg ->
			pg.getValue().keySet().forEach(k -> names.add(k.toLowerCase(Locale.ROOT))));
		return names.size();
	}

	JsonObject clogSnapshot()
	{
		synchronized (lock)
		{
			return obj(root, "collection_log").deepCopy();
		}
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
}
