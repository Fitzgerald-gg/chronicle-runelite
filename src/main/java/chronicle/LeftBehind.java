/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.TileItem;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ItemDespawned;
import net.runelite.api.events.ItemSpawned;
import net.runelite.api.events.MenuOptionClicked;

@RequiredArgsConstructor
class LeftBehind
{
	private static final int KILL_ARM_TICKS = 3;
	private static final int TIMEOUT_GRACE = 5;
	private final Map<TileItem, GroundLoot> groundLoot = new IdentityHashMap<>();
	private final Map<TileItem, GroundLoot> pendingSelf = new IdentityHashMap<>();
	private final List<GroundLoot> untakenBatch = new ArrayList<>();
	private final Set<TileItem> unloaded = Collections.newSetFromMap(new IdentityHashMap<>());
	private final List<RecentKill> recentKills = new ArrayList<>();
	private static final int DEATH_MEMORY_TICKS = 10;
	private final List<RecentDeath> recentDeaths = new ArrayList<>();
	private static final int DROP_WINDOW_TICKS = 2;
	private final List<RecentDrop> recentDrops = new ArrayList<>();
	private final Client client;

	void reset()
	{
		groundLoot.clear();
		pendingSelf.clear();
		recentKills.clear();
		recentDeaths.clear();
		recentDrops.clear();
	}

	void stateChanged(GameState state)
	{
		if (state == GameState.LOADING)
		{
			unloaded.addAll(groundLoot.keySet());
		}
		if (state == GameState.HOPPING || state == GameState.LOGIN_SCREEN)
		{
			untakenBatch.addAll(groundLoot.values());
			groundLoot.clear();
			unloaded.clear();
		}
	}

	@RequiredArgsConstructor
	private static final class RecentDrop
	{
		private final int id;
		private final int tick;
		private final WorldPoint at;
	}

	@RequiredArgsConstructor
	private static final class RecentKill
	{
		private final int tick;
		private final String source;
	}

	@RequiredArgsConstructor
	private static final class RecentDeath
	{
		private final int tick;
		private final int index;
		private final String name;
		private final WorldArea footprint;
	}

	@RequiredArgsConstructor
	private static final class GroundLoot
	{
		private final int id;
		private final int qty;
		private final int despawnTick;
		private final int spawnTick;
		private final boolean group;
		private final String owner;
		private final WorldPoint at;
		private final String source;
		private final int killTick;
		private final int killIndex;

		private GroundLoot(int id, int qty, int despawnTick, int spawnTick, boolean group, String owner, WorldPoint at)
		{
			this(id, qty, despawnTick, spawnTick, group, owner, at, null, -1, -1);
		}

		private GroundLoot withKill(String source, int killTick, int killIndex)
		{
			return new GroundLoot(id, qty, despawnTick, spawnTick, group, owner, at, source, killTick, killIndex);
		}
	}

	List<JsonObject> tick(String owner)
	{
		reconcileKillLoot();
		sweepTimedOutLoot();
		List<JsonObject> out = new ArrayList<>();
		if (untakenBatch.isEmpty() || owner == null)
		{
			return out;
		}
		Map<String, JsonArray> bySource = new HashMap<>();
		Map<String, Set<String>> killsBySource = new HashMap<>();
		for (GroundLoot it : untakenBatch)
		{
			if (!owner.equals(it.owner))
			{
				continue;
			}
			JsonObject o = Json.of("id", it.id, "quantity", it.qty);
			String src = it.source == null ? "" : it.source;
			bySource.computeIfAbsent(src, k -> new JsonArray()).add(o);
			if (it.killTick >= 0)
			{
				killsBySource.computeIfAbsent(src, k -> new HashSet<>())
					.add(it.killIndex + "@" + it.killTick);
			}
		}
		untakenBatch.clear();
		for (Map.Entry<String, JsonArray> e : bySource.entrySet())
		{
			JsonObject data = new JsonObject();
			if (!e.getKey().isEmpty())
			{
				data.addProperty("source", e.getKey());
			}
			data.add("items", e.getValue());
			Set<String> kills = killsBySource.get(e.getKey());
			data.addProperty("kills", kills == null ? 0 : kills.size());
			out.add(data);
		}
		return out;
	}

	private TileItem trackedStack(WorldPoint where, int id, int qty)
	{
		if (where == null)
		{
			return null;
		}
		for (TileItem key : unloaded)
		{
			GroundLoot g = groundLoot.get(key);
			if (g != null && g.id == id && g.qty == qty && where.equals(g.at))
			{
				return key;
			}
		}
		return null;
	}

	private void sweepTimedOutLoot()
	{
		if (groundLoot.isEmpty())
		{
			return;
		}
		int now = client.getTickCount();
		List<TileItem> gone = new ArrayList<>();
		for (Map.Entry<TileItem, GroundLoot> e : groundLoot.entrySet())
		{
			GroundLoot g = e.getValue();
			if (g.despawnTick > 0 && now > g.despawnTick + TIMEOUT_GRACE)
			{
				untakenBatch.add(g);
				gone.add(e.getKey());
			}
		}
		for (TileItem it : gone)
		{
			groundLoot.remove(it);
			unloaded.remove(it);
		}
	}

	private void reconcileKillLoot()
	{
		if (pendingSelf.isEmpty())
		{
			return;
		}
		int now = client.getTickCount();
		List<TileItem> done = new ArrayList<>();
		for (Map.Entry<TileItem, GroundLoot> e : pendingSelf.entrySet())
		{
			GroundLoot g = e.getValue();
			RecentKill kill = killFor(g);
			if (kill != null)
			{
				RecentDeath death = deathFor(g);
				groundLoot.put(e.getKey(), death != null ? g.withKill(death.name, death.tick, death.index)
					: g.withKill(kill.source, kill.tick, -1));
				done.add(e.getKey());
			}
			else if (now - g.spawnTick > KILL_ARM_TICKS)
			{
				done.add(e.getKey());
			}
		}
		done.forEach(pendingSelf::remove);
	}

	private static boolean armedFor(RecentKill k, GroundLoot g)
	{
		int since = g.spawnTick - k.tick;
		return since >= 0 && since <= (g.group ? 0 : KILL_ARM_TICKS);
	}

	private RecentKill killFor(GroundLoot g)
	{
		RecentKill best = null;
		for (RecentKill k : recentKills)
		{
			if (armedFor(k, g) && (best == null || k.tick > best.tick))
			{
				best = k;
			}
		}
		return best;
	}

	private RecentDeath deathFor(GroundLoot g)
	{
		if (g.at == null || recentDeaths.isEmpty())
		{
			return null;
		}
		RecentDeath best = null;
		int bestDistance = 0;
		for (RecentDeath d : recentDeaths)
		{
			int since = g.spawnTick - d.tick;
			if (since < 0 || since > DEATH_MEMORY_TICKS
				|| recentKills.stream().noneMatch(k -> armedFor(k, g) && d.name.equals(k.source)))
			{
				continue;
			}
			int distance = d.footprint.distanceTo(g.at);
			if (distance > 1)
			{
				continue;
			}
			if (best == null || distance < bestDistance || (distance == bestDistance && d.tick > best.tick))
			{
				best = d;
				bestDistance = distance;
			}
		}
		return best;
	}

	void armKill(String source)
	{
		int now = client.getTickCount();
		recentKills.removeIf(k -> now - k.tick > KILL_ARM_TICKS);
		recentKills.add(new RecentKill(now, source));
	}

	void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (!event.isItemOp() || !"Drop".equals(event.getMenuOption()) || event.getItemId() <= 0)
		{
			return;
		}
		int now = client.getTickCount();
		Player me = client.getLocalPlayer();
		recentDrops.removeIf(d -> now - d.tick > DROP_WINDOW_TICKS);
		recentDrops.add(new RecentDrop(event.getItemId(), now, me == null ? null : me.getWorldLocation()));
	}

	private boolean isOwnDrop(TileItem it, WorldPoint at, int now)
	{
		if (recentDrops.isEmpty())
		{
			return false;
		}
		Player me = client.getLocalPlayer();
		WorldPoint mine = me == null ? null : me.getWorldLocation();
		for (int i = 0; i < recentDrops.size(); i++)
		{
			RecentDrop d = recentDrops.get(i);
			if (d.id != it.getId() || now < d.tick || now - d.tick > DROP_WINDOW_TICKS)
			{
				continue;
			}
			if (at != null && mine != null && !at.equals(mine) && !at.equals(d.at))
			{
				continue;
			}
			recentDrops.remove(i);
			return true;
		}
		return false;
	}

	void onItemSpawned(ItemSpawned event)
	{
		TileItem it = event.getItem();
		if (it == null)
		{
			return;
		}
		boolean group = it.getOwnership() == TileItem.OWNERSHIP_GROUP;
		if (!group && it.getOwnership() != TileItem.OWNERSHIP_SELF)
		{
			return;
		}
		if (groundLoot.containsKey(it))
		{
			return;
		}
		int now = client.getTickCount();
		WorldPoint at = event.getTile() == null ? null : event.getTile().getWorldLocation();
		TileItem prior = trackedStack(at, it.getId(), it.getQuantity());
		if (prior != null)
		{
			groundLoot.put(it, groundLoot.remove(prior));
			unloaded.remove(prior);
			return;
		}
		if (!isOwnDrop(it, at, now))
		{
			pendingSelf.put(it, new GroundLoot(it.getId(), it.getQuantity(), it.getDespawnTime(), now, group, ChronicleEventCapture.playerName(client), at));
		}
	}

	void onItemDespawned(ItemDespawned event)
	{
		pendingSelf.remove(event.getItem());
		GroundLoot g = groundLoot.remove(event.getItem());
		unloaded.remove(event.getItem());
		if (g == null)
		{
			return;
		}
		if (g.despawnTick > 0 && client.getTickCount() >= g.despawnTick - 1)
		{
			untakenBatch.add(g);
		}
	}

	void rememberDeath(NPC npc)
	{
		int now = client.getTickCount();
		recentDeaths.removeIf(d -> now - d.tick > DEATH_MEMORY_TICKS);
		String name;
		WorldPoint at;
		int size;
		try
		{
			name = npc.getName();
			at = npc.getWorldLocation();
			NPCComposition comp = npc.getTransformedComposition();
			if (comp == null)
			{
				comp = npc.getComposition();
			}
			size = comp == null ? 1 : Math.max(1, comp.getSize());
		}
		catch (RuntimeException ignored)
		{
			return;
		}
		if (name == null || name.isEmpty() || at == null)
		{
			return;
		}
		recentDeaths.add(new RecentDeath(now, npc.getIndex(), name, new WorldArea(at, size, size)));
	}
}
