/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the BSD 2-Clause
 * License (see LICENSE) are met.
 */
package chronicle;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Quest;
import net.runelite.api.gameval.VarbitID;

/**
 * Snapshot of the account's achievement state: every quest's progress, each
 * diary tier's completion, and combat-achievement points plus per-tier status.
 * The journal's character sheet reads it, and the push loop sends it whole
 * whenever it differs from the last copy the server acked. Enum names and
 * varbit values go in as-is; nothing is graded here.
 *
 * <p>All reads are on the client thread, and the quest sweep runs a clientscript
 * per quest. One snapshot is built per game tick and shared by both callers.
 */
@Singleton
public class AchievementSync
{
	private static final String[] DIARY_TIERS = {"easy", "medium", "hard", "elite"};

	// Diaries whose four tiers each have a completion varbit, easy to elite per row.
	// Karamja is missing three of those varbits; it gets built by hand in snapshot().
	private static final String[] DIARY_REGIONS = {
		"ardougne", "desert", "falador", "fremennik", "kandarin",
		"kourend", "lumbridge", "morytania", "varrock", "western", "wilderness",
	};
	// Each row's four completion varbits are consecutive ids, easy first, so a row
	// is its easy varbit and a tier is read at that id plus its index.
	private static final int[] DIARY_VARBITS = {
		4458, 4483, 4462, 4491, 4475, 7925, 4495, 4487, 4479, 4471, 4466,
	};

	private static final String[] CA_TIERS = {
		"easy", "medium", "hard", "elite", "master", "grandmaster",
	};
	/**
	 * One bit per combat achievement task, in the game's own task-id order.
	 *
	 * <p>The game states points and which tiers are unlocked, and nothing about
	 * WHICH tasks are done, so the journal could only name the ones it happened to
	 * watch land: seventeen of a hundred. These varps carry the lot. Task id N is
	 * bit N%32 of CA_TASK_COMPLETED_(N/32), and the bundled table is keyed by that
	 * same id, so the two line up without a lookup table between them.
	 *
	 * <p>Listed one by one rather than by arithmetic on the first id. Only the first
	 * thirteen are contiguous, 3116 to 3128; the remaining eight were allotted as
	 * tasks were added over the years and land at 3387, 3718, 3773, 3774, 4204,
	 * 4496, 4721 and 5673. A loop over a base would walk straight off the end of
	 * the run and read varps belonging to something else entirely, and every bit
	 * it found there would tick a combat achievement at random.
	 */
	private static final int[] CA_TASK_COMPLETED = {
		3116, 3117, 3118, 3119, 3120, 3121, 3122, 3123, 3124, 3125, 3126, 3127, 3128,
		3387, 3718, 3773, 3774, 4204, 4496, 4721, 5673,
	};

	private static final int[] CA_TIER_STATUS = {12863, 12864, 12865, 12866, 12867, 12868};

	private final Client client;
	// RuneLite's own, injected: the Hub rejects a plugin that builds its own.
	private final Gson gson;

	// JSON of the last snapshot the server acked. Fields are built in a fixed order,
	// which is what makes plain string equality a sound change gate. Written on an
	// HTTP callback thread.
	private volatile String lastSynced;

	// The tick's snapshot, shared by every caller in that tick. cached is stored
	// before cachedTick, so a matching tick means the object is visible.
	private volatile JsonObject cached;
	private volatile int cachedTick = -1;

	@Inject
	public AchievementSync(Client client, Gson gson)
	{
		this.client = client;
		this.gson = gson;
	}

	// The bundled diary table, read once. Only Karamja needs it, and only because
	// its tiers are the ones the game will not answer for directly.
	private JsonObject bundledDiaries;

	/**
	 * How many tasks a Karamja tier holds, per the bundled table.
	 *
	 * <p>Falls back to the figure that was hardcoded here, so a missing or
	 * unreadable bundle leaves the behaviour exactly as it was rather than
	 * reporting every tier finished at zero.
	 */
	private synchronized int tierSize(String tier, int fallback)
	{
		if (bundledDiaries == null)
		{
			try (InputStreamReader r = new InputStreamReader(
				AchievementSync.class.getResourceAsStream(
					"/chronicle/osrs_achievement_diaries.json"),
				StandardCharsets.UTF_8))
			{
				bundledDiaries = gson.fromJson(r, JsonObject.class);
			}
			catch (Exception e)
			{
				bundledDiaries = new JsonObject();
			}
			if (bundledDiaries == null)
			{
				bundledDiaries = new JsonObject();
			}
		}
		if (!bundledDiaries.has("diaries") || !bundledDiaries.get("diaries").isJsonObject())
		{
			return fallback;
		}
		JsonObject all = bundledDiaries.getAsJsonObject("diaries");
		for (String region : all.keySet())
		{
			if (!"karamja".equalsIgnoreCase(region))
			{
				continue;
			}
			JsonObject tiers = all.getAsJsonObject(region);
			return tiers.has(tier) && tiers.get(tier).isJsonArray()
				? tiers.getAsJsonArray(tier).size() : fallback;
		}
		return fallback;
	}

	// Client thread only. Every caller in a tick gets the same object, read-only.
	JsonObject snapshot()
	{
		int tick = client.getTickCount();
		JsonObject hit = cached;
		if (hit != null && cachedTick == tick)
		{
			return hit;
		}
		JsonObject quests = new JsonObject();
		for (Quest quest : Quest.values())
		{
			quests.addProperty(quest.getName(), quest.getState(client).name());
		}
		JsonObject diaries = new JsonObject();
		for (int r = 0; r < DIARY_REGIONS.length; r++)
		{
			JsonObject region = new JsonObject();
			for (int t = 0; t < DIARY_TIERS.length; t++)
			{
				region.addProperty(DIARY_TIERS[t], client.getVarbitValue(DIARY_VARBITS[r] + t) != 0);
			}
			diaries.add(DIARY_REGIONS[r], region);
		}
		// Karamja easy, medium and hard have no completion varbit; the game gives a
		// count of tasks done and nothing else, so a tier is finished once that
		// count reaches the number of tasks the tier holds. That number is in the
		// bundled table, and repeating it here as a literal meant a regenerated
		// table would move one copy and not the other: add a Karamja easy task and
		// the tier would read finished at ten of eleven. Elite has a real varbit.
		JsonObject karamja = new JsonObject();
		karamja.addProperty("easy",
			client.getVarbitValue(VarbitID.KARAMJA_EASY_COUNT) >= tierSize("easy", 10));
		karamja.addProperty("medium",
			client.getVarbitValue(VarbitID.KARAMJA_MED_COUNT) >= tierSize("medium", 19));
		karamja.addProperty("hard",
			client.getVarbitValue(VarbitID.KARAMJA_HARD_COUNT) >= tierSize("hard", 10));
		karamja.addProperty("elite", client.getVarbitValue(VarbitID.KARAMJA_DIARY_ELITE_COMPLETE) != 0);
		diaries.add("karamja", karamja);

		JsonObject combat = new JsonObject();
		combat.addProperty("points", client.getVarbitValue(VarbitID.CA_POINTS));
		JsonObject tiers = new JsonObject();
		for (int i = 0; i < CA_TIERS.length; i++)
		{
			tiers.addProperty(CA_TIERS[i], client.getVarbitValue(CA_TIER_STATUS[i]));
		}
		combat.add("tiers", tiers);
		// The words the panel reads come from the bundled table; these are just the
		// ids, so the journal carries the smallest thing that can answer "which".
		JsonArray done = new JsonArray();
		for (int word = 0; word < CA_TASK_COMPLETED.length; word++)
		{
			int bits = client.getVarpValue(CA_TASK_COMPLETED[word]);
			if (bits == 0)
			{
				continue;
			}
			for (int bit = 0; bit < 32; bit++)
			{
				if ((bits & (1 << bit)) != 0)
				{
					done.add(word * 32 + bit);
				}
			}
		}
		combat.add("tasksDone", done);

		JsonObject root = new JsonObject();
		root.add("quests", quests);
		root.add("diaries", diaries);
		root.add("combat", combat);
		cached = root;
		cachedTick = tick;
		return root;
	}

	boolean changedSince(JsonObject snap)
	{
		return !snap.toString().equals(lastSynced);
	}

	// Call on the server's ack only.
	void markSynced(JsonObject snap)
	{
		lastSynced = snap.toString();
	}

	// Account boundary: the next login syncs afresh under its own name.
	void reset()
	{
		lastSynced = null;
		cachedTick = -1;
		cached = null;
	}
}
