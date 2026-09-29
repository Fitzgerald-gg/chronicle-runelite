/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import chronicle.counters.Tables;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.RequiredArgsConstructor;
import net.runelite.api.Client;
import net.runelite.api.Quest;
import net.runelite.api.gameval.VarbitID;

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class AchievementSync
{
	private static final String[] DIARY_TIERS = {"easy", "medium", "hard", "elite"};

	private static final String[] DIARY_REGIONS = {
		"ardougne", "desert", "falador", "fremennik", "kandarin",
		"kourend", "lumbridge", "morytania", "varrock", "western", "wilderness",
	};
	private static final int[] DIARY_VARBITS = {
		4458, 4483, 4462, 4491, 4475, 7925, 4495, 4487, 4479, 4471, 4466,
	};

	private static final String[] CA_TIERS = {
		"easy", "medium", "hard", "elite", "master", "grandmaster",
	};
	private static final int[] CA_TASK_COMPLETED = {
		3116, 3117, 3118, 3119, 3120, 3121, 3122, 3123, 3124, 3125, 3126, 3127, 3128,
		3387, 3718, 3773, 3774, 4204, 4496, 4721, 5673,
	};

	private static final int[] CA_TIER_STATUS = {12863, 12864, 12865, 12866, 12867, 12868};
	private final Client client;
	private final Gson gson;
	private JsonObject bundledDiaries;
	private volatile String lastSynced;
	private volatile JsonObject cached;
	private volatile int cachedTick = -1;

	private synchronized int tierSize(String tier, int fallback)
	{
		if (bundledDiaries == null)
		{
			bundledDiaries = Tables.load("osrs_achievement_diaries.json");
		}
		try
		{
			return bundledDiaries.getAsJsonObject("diaries").getAsJsonObject("Karamja").getAsJsonArray(tier).size();
		}
		catch (RuntimeException e)
		{
			return fallback;
		}
	}

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
		JsonObject karamja = Json.of("easy", client.getVarbitValue(VarbitID.KARAMJA_EASY_COUNT) >= tierSize("easy", 10),
			"medium", client.getVarbitValue(VarbitID.KARAMJA_MED_COUNT) >= tierSize("medium", 19),
			"hard", client.getVarbitValue(VarbitID.KARAMJA_HARD_COUNT) >= tierSize("hard", 10),
			"elite", client.getVarbitValue(VarbitID.KARAMJA_DIARY_ELITE_COMPLETE) != 0);
		diaries.add("karamja", karamja);

		JsonObject combat = new JsonObject();
		combat.addProperty("points", client.getVarbitValue(VarbitID.CA_POINTS));
		JsonObject tiers = new JsonObject();
		for (int i = 0; i < CA_TIERS.length; i++)
		{
			tiers.addProperty(CA_TIERS[i], client.getVarbitValue(CA_TIER_STATUS[i]));
		}
		combat.add("tiers", tiers);
		JsonArray done = new JsonArray();
		for (int word = 0; word < CA_TASK_COMPLETED.length; word++)
		{
			int bits = client.getVarpValue(CA_TASK_COMPLETED[word]);
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

	void markSynced(JsonObject snap)
	{
		lastSynced = snap.toString();
	}

	void reset()
	{
		lastSynced = null;
		cachedTick = -1;
		cached = null;
	}
}
