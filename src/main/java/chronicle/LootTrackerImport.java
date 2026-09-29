/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import chronicle.LocalStore.BagItem;
import chronicle.LocalStore.LootSeed;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.http.api.loottracker.LootRecordType;

@Slf4j
@Singleton
class LootTrackerImport
{
	private static final String DONE = "lootTrackerImported";
	private static final String GROUP = "loottracker";

	@Inject
	private ConfigManager configs;

	@Inject
	private ClientThread thread;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private Gson gson;

	@Inject
	private LocalStore store;

	private volatile boolean running;

	void run(ChroniclePlugin plugin)
	{
		if (running || "true".equals(configs.getRSProfileConfiguration(ChronicleConfig.GROUP, DONE)))
		{
			return;
		}
		running = true;
		String who = plugin.localName();
		String profile = configs.getRSProfileKey();
		executor.submit(() ->
		{
			List<LootSeed> raw;
			try
			{
				raw = read(profile);
			}
			catch (RuntimeException e)
			{
				running = false;
				log.debug("loot tracker archive read failed", e);
				return;
			}
			thread.invoke(() -> adopt(plugin, who, raw));
		});
	}

	private List<LootSeed> read(String profile)
	{
		List<LootSeed> out = new ArrayList<>();
		List<String> keys;
		try
		{
			keys = configs.getRSProfileConfigurationKeys(GROUP, profile, "drops_");
		}
		catch (RuntimeException e)
		{
			return out;
		}
		for (String key : keys == null ? List.<String>of() : keys)
		{
			String json = wanted(key) ? configs.getConfiguration(GROUP, profile, key) : null;
			if (json == null || json.isEmpty())
			{
				continue;
			}
			try
			{
				JsonObject o = gson.fromJson(json, JsonObject.class);
				String source = Json.str(o, "name", null);
				if (source == null || source.isEmpty())
				{
					continue;
				}
				List<BagItem> items = new ArrayList<>();
				JsonArray drops = o.has("drops") && o.get("drops").isJsonArray() ? o.getAsJsonArray("drops") : new JsonArray();
				for (int i = 0; i + 1 < drops.size(); i += 2)
				{
					int id = drops.get(i).getAsInt();
					long qty = drops.get(i + 1).getAsLong();
					if (id > 0 && qty > 0)
					{
						items.add(new BagItem(id, null, qty, 0));
					}
				}
				out.add(new LootSeed(source, o.has("kills") ? o.get("kills").getAsInt() : 0,
					o.has("first") ? o.get("first").getAsLong() : 0,
					o.has("last") ? o.get("last").getAsLong() : 0, items));
			}
			catch (RuntimeException e)
			{
				log.debug("loot tracker record parse failed: {}", key, e);
			}
		}
		return out;
	}

	private static boolean wanted(String key)
	{
		return key != null && Arrays.stream(LootRecordType.values())
			.anyMatch(t -> t != LootRecordType.PLAYER && key.startsWith("drops_" + t.name() + "_"));
	}

	private void adopt(ChroniclePlugin plugin, String who, List<LootSeed> raw)
	{
		try
		{
			if (who == null || !who.equals(plugin.localName()) || !store.isReadyFor(who))
			{
				return;
			}
			List<LootSeed> seeds = new ArrayList<>(raw.size());
			long events = 0;
			for (LootSeed s : raw)
			{
				List<BagItem> items = new ArrayList<>(s.items.size());
				for (BagItem b : s.items)
				{
					items.add(store.price(b.itemId, b.qty));
				}
				seeds.add(new LootSeed(s.source, s.kills, s.firstMs, s.lastMs, items));
				events += s.kills;
			}
			if (!seeds.isEmpty())
			{
				store.floorLootTracker(seeds, who);
				plugin.chat("Chronicle: adopted " + seeds.size() + " sources · "
					+ String.format(Locale.UK, "%,d", events) + " loot events from your Loot Tracker.");
				plugin.refreshPanel();
			}
			configs.setRSProfileConfiguration(ChronicleConfig.GROUP, DONE, true);
		}
		finally
		{
			running = false;
		}
	}
}
