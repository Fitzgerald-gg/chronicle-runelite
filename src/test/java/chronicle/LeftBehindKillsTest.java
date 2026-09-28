/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the
 * BSD 2-Clause License (see LICENSE) are met.
 */
package chronicle;

import com.google.gson.JsonObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemDespawned;
import net.runelite.api.events.ItemSpawned;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.game.ItemStack;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

public class LeftBehindKillsTest
{
	private static final int SPIKE = 30_000;
	private static final int BONES = 526;
	private static final int DESPAWN = 300;
	private static final WorldPoint HERE = new WorldPoint(3200, 3200, 0);
	private static final String BEAR = "Corrupted Bear";
	private static final String WOLF = "Wolf";

	private ChronicleEventCapture capture;
	private Client client;
	private LocalStore store;
	private Tile tile;

	@Before
	public void setUp()
	{
		client = Mockito.mock(Client.class);
		store = Mockito.mock(LocalStore.class);
		Player me = Mockito.mock(Player.class);
		tile = Mockito.mock(Tile.class);
		Mockito.when(me.getName()).thenReturn("Tester");
		Mockito.when(client.getLocalPlayer()).thenReturn(me);
		Mockito.when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		Mockito.when(store.isReadyFor("Tester")).thenReturn(true);
		tick(100);
		capture = new ChronicleEventCapture(client, Mockito.mock(ClientThread.class),
			Mockito.mock(ConfigManager.class), Mockito.mock(ChronicleConfig.class),
			Mockito.mock(ChronicleApiClient.class), store);
	}

	private void tick(int t)
	{
		Mockito.when(client.getTickCount()).thenReturn(t);
	}

	private void gameTick(int t)
	{
		tick(t);
		capture.onGameTick(new GameTick());
	}

	private static WorldPoint tileAt(int x, int y)
	{
		return new WorldPoint(3200 + x, 3200 + y, 0);
	}

	private void kill(int t, String source)
	{
		tick(t);
		NPCComposition comp = Mockito.mock(NPCComposition.class);
		Mockito.when(comp.getName()).thenReturn(source);
		Mockito.when(comp.getId()).thenReturn(1);
		capture.onServerNpcLoot(new ServerNpcLoot(comp, new ArrayList<ItemStack>()));
	}

	private void death(int t, int index, String name, WorldPoint at, int size)
	{
		tick(t);
		NPC npc = Mockito.mock(NPC.class);
		Mockito.when(npc.getIndex()).thenReturn(index);
		Mockito.when(npc.getName()).thenReturn(name);
		Mockito.when(npc.getWorldLocation()).thenReturn(at);
		NPCComposition comp = Mockito.mock(NPCComposition.class);
		Mockito.when(comp.getSize()).thenReturn(size);
		Mockito.when(npc.getTransformedComposition()).thenReturn(comp);
		capture.onActorDeath(new ActorDeath(npc));
	}

	private void kill(int t, String source, int index, WorldPoint at)
	{
		death(t - 1, index, source, at, 1);
		kill(t, source);
	}

	private TileItem spawn(int t, int id)
	{
		return spawn(t, id, tile);
	}

	private TileItem spawnAt(int t, int id, WorldPoint at)
	{
		return spawnAt(t, id, at, TileItem.OWNERSHIP_SELF);
	}

	private TileItem spawnAt(int t, int id, WorldPoint at, int ownership)
	{
		Tile where = Mockito.mock(Tile.class);
		Mockito.when(where.getWorldLocation()).thenReturn(at);
		return spawn(t, id, where, ownership);
	}

	private TileItem spawn(int t, int id, Tile where)
	{
		return spawn(t, id, where, TileItem.OWNERSHIP_SELF);
	}

	private TileItem spawn(int t, int id, Tile where, int ownership)
	{
		tick(t);
		TileItem it = Mockito.mock(TileItem.class);
		Mockito.when(it.getId()).thenReturn(id);
		Mockito.when(it.getQuantity()).thenReturn(1);
		Mockito.when(it.getOwnership()).thenReturn(ownership);
		Mockito.when(it.getDespawnTime()).thenReturn(DESPAWN);
		capture.onItemSpawned(new ItemSpawned(where, it));
		return it;
	}

	private void pickUp(int t, TileItem item)
	{
		tick(t);
		capture.onItemDespawned(new ItemDespawned(tile, item));
	}

	private void leave(TileItem... items)
	{
		tick(DESPAWN);
		for (TileItem it : items)
		{
			capture.onItemDespawned(new ItemDespawned(tile, it));
		}
		gameTick(DESPAWN);
	}

	private List<JsonObject> untakenRows()
	{
		ArgumentCaptor<JsonObject> row = ArgumentCaptor.forClass(JsonObject.class);
		Mockito.verify(store, Mockito.atLeast(0)).record(
			Mockito.eq("LOOT_UNTAKEN"), row.capture(), Mockito.eq("Tester"));
		return row.getAllValues();
	}

	private static JsonObject rowFor(List<JsonObject> rows, String source)
	{
		for (JsonObject r : rows)
		{
			if (r.has("source") && source.equals(r.get("source").getAsString()))
			{
				return r;
			}
		}
		throw new AssertionError("no LOOT_UNTAKEN row for " + source + " in " + rows);
	}

	private static int kills(JsonObject row)
	{
		return row.get("kills").getAsInt();
	}

	private static int items(JsonObject row)
	{
		return row.getAsJsonArray("items").size();
	}

	@Test
	public void twoStacksFromOneKillAreOneKill()
	{
		kill(100, BEAR);
		TileItem a = spawn(101, SPIKE);
		TileItem b = spawn(101, BONES);
		gameTick(101);
		leave(a, b);
		List<JsonObject> rows = untakenRows();
		assertEquals(1, rows.size());
		assertEquals(2, items(rows.get(0)));
		assertEquals(1, kills(rows.get(0)));
	}

	@Test
	public void stacksFromTwoKillsOnDifferentTicksAreTwoKills()
	{
		kill(100, BEAR);
		TileItem a = spawn(101, BONES);
		gameTick(101);
		kill(110, BEAR);
		TileItem b = spawn(111, BONES);
		gameTick(111);
		leave(a, b);
		List<JsonObject> rows = untakenRows();
		assertEquals(1, rows.size());
		assertEquals(BEAR, rows.get(0).get("source").getAsString());
		assertEquals(2, items(rows.get(0)));
		assertEquals(2, kills(rows.get(0)));
	}

	@Test
	public void aStackPickedUpContributesNoKill()
	{
		kill(100, BEAR);
		TileItem a = spawn(101, BONES);
		gameTick(101);
		kill(110, BEAR);
		TileItem b = spawn(111, BONES);
		gameTick(111);
		pickUp(150, b);
		leave(a);
		List<JsonObject> rows = untakenRows();
		assertEquals(1, rows.size());
		assertEquals(1, items(rows.get(0)));
		assertEquals(1, kills(rows.get(0)));
	}

	@Test
	public void aKillWhoseStacksWereAllTakenSendsNothing()
	{
		kill(100, BEAR);
		TileItem a = spawn(101, BONES);
		TileItem b = spawn(101, SPIKE);
		gameTick(101);
		pickUp(150, a);
		pickUp(151, b);
		gameTick(DESPAWN);
		assertTrue(untakenRows().isEmpty());
	}

	@Test
	public void twoKillsOnOneTickCollapseToOneKill()
	{
		kill(100, BEAR);
		kill(100, BEAR);
		TileItem a = spawn(101, BONES);
		TileItem b = spawn(101, SPIKE);
		gameTick(101);
		leave(a, b);
		List<JsonObject> rows = untakenRows();
		assertEquals(1, rows.size());
		assertEquals(2, items(rows.get(0)));
		assertEquals(1, kills(rows.get(0)));
	}

	@Test
	public void eachSourceCountsItsOwnKills()
	{
		kill(100, BEAR);
		TileItem a = spawn(101, BONES);
		gameTick(101);
		kill(110, WOLF);
		TileItem b = spawn(111, BONES);
		gameTick(111);
		kill(120, WOLF);
		TileItem c = spawn(121, BONES);
		gameTick(121);
		leave(a, b, c);
		List<JsonObject> rows = untakenRows();
		assertEquals(2, rows.size());
		assertEquals(1, items(rowFor(rows, BEAR)));
		assertEquals(1, kills(rowFor(rows, BEAR)));
		assertEquals(2, items(rowFor(rows, WOLF)));
		assertEquals(2, kills(rowFor(rows, WOLF)));
	}

	@Test
	@SuppressWarnings("unchecked")
	public void aStackWithoutAKillCountsNoKill() throws Exception
	{
		Class<?> groundLoot = Class.forName("chronicle.ChronicleEventCapture$GroundLoot");
		Constructor<?> bare = groundLoot.getDeclaredConstructor(int.class, int.class, int.class,
			int.class, boolean.class, String.class, WorldPoint.class);
		bare.setAccessible(true);
		Object stack = bare.newInstance(BONES, 1, DESPAWN, 101, false, "Tester", null);
		Field tracked = ChronicleEventCapture.class.getDeclaredField("groundLoot");
		tracked.setAccessible(true);
		TileItem it = Mockito.mock(TileItem.class);
		((Map<TileItem, Object>) tracked.get(capture)).put(it, stack);
		leave(it);
		List<JsonObject> rows = untakenRows();
		assertEquals(1, rows.size());
		assertEquals(1, items(rows.get(0)));
		assertEquals(0, kills(rows.get(0)));
	}

	@Test
	public void theStacksSweptUpAtAnUnloadKeepTheirKills()
	{
		kill(100, BEAR);
		spawn(101, BONES);
		gameTick(101);
		kill(110, BEAR);
		spawn(111, BONES);
		gameTick(111);
		tick(112);
		GameStateChanged unload = new GameStateChanged();
		unload.setGameState(GameState.HOPPING);
		capture.onGameStateChanged(unload);
		gameTick(113);
		List<JsonObject> rows = untakenRows();
		assertEquals(1, rows.size());
		assertEquals(2, items(rows.get(0)));
		assertEquals(2, kills(rows.get(0)));
	}

	@Test
	public void aSceneReloadIsNotLeavingTheLoot()
	{
		kill(100, BEAR);
		TileItem bones = spawn(101, BONES);
		gameTick(101);

		GameStateChanged reload = new GameStateChanged();
		reload.setGameState(GameState.LOADING);
		capture.onGameStateChanged(reload);
		gameTick(102);

		capture.onItemSpawned(new ItemSpawned(tile, bones));
		gameTick(103);

		pickUp(104, bones);
		gameTick(105);
		assertEquals("a reload is not a departure", 0, untakenRows().size());
	}

	@Test
	public void theReplayedStackIsNotASecondDrop()
	{
		kill(100, BEAR);
		TileItem bones = spawn(101, BONES);
		gameTick(101);
		GameStateChanged reload = new GameStateChanged();
		reload.setGameState(GameState.LOADING);
		capture.onGameStateChanged(reload);
		capture.onItemSpawned(new ItemSpawned(tile, bones));
		gameTick(102);

		leave(bones);
		List<JsonObject> rows = untakenRows();
		assertEquals(1, rows.size());
		assertEquals("the replay was counted as a second stack", 1, items(rows.get(0)));
	}

	@Test
	public void aStackWalkedAwayFromIsStillCountedWhenItsTimeIsUp()
	{
		kill(100, BEAR);
		spawn(101, BONES);
		gameTick(101);
		GameStateChanged reload = new GameStateChanged();
		reload.setGameState(GameState.LOADING);
		capture.onGameStateChanged(reload);
		gameTick(102);
		assertEquals("counted before its time was up", 0, untakenRows().size());

		gameTick(DESPAWN + 10);
		List<JsonObject> rows = untakenRows();
		assertEquals(1, rows.size());
		assertEquals(1, items(rows.get(0)));
		assertEquals("the kill it came from was lost", 1, kills(rows.get(0)));
	}

	@Test
	public void lootFetchedOnASecondTripIsNotAbandoned()
	{
		kill(100, BEAR);
		TileItem first = spawnAt(101, BONES, HERE);
		gameTick(101);

		GameStateChanged away = new GameStateChanged();
		away.setGameState(GameState.LOADING);
		capture.onGameStateChanged(away);
		gameTick(102);

		TileItem rebuilt = spawnAt(140, BONES, HERE);
		assertNotSame(first, rebuilt);
		gameTick(141);

		pickUp(142, rebuilt);
		gameTick(143);
		assertEquals("going back for it was read as leaving it",
			0, untakenRows().size());

		gameTick(DESPAWN + 10);
		assertEquals("the sweep banked a stack already taken",
			0, untakenRows().size());
	}

	@Test
	public void twoKillsOnOneTickAtDifferentTilesAreTwoKills()
	{
		WorldPoint t1 = tileAt(0, 0);
		WorldPoint t2 = tileAt(5, 0);
		kill(100, BEAR, 1, t1);
		kill(100, BEAR, 2, t2);
		TileItem a = spawnAt(101, BONES, t1);
		TileItem b = spawnAt(101, SPIKE, t2);
		gameTick(101);
		leave(a, b);
		List<JsonObject> rows = untakenRows();
		assertEquals(1, rows.size());
		assertEquals(BEAR, rows.get(0).get("source").getAsString());
		assertEquals(2, items(rows.get(0)));
		assertEquals(2, kills(rows.get(0)));
	}

	@Test
	public void adjacentKillsOnOneTickEachKeepTheirOwnStack()
	{
		WorldPoint t1 = tileAt(0, 0);
		WorldPoint t2 = tileAt(1, 0);
		kill(100, BEAR, 1, t1);
		kill(100, BEAR, 2, t2);
		TileItem a = spawnAt(101, BONES, t1);
		TileItem b = spawnAt(101, SPIKE, t2);
		gameTick(101);
		leave(a, b);
		List<JsonObject> rows = untakenRows();
		assertEquals(1, rows.size());
		assertEquals(2, items(rows.get(0)));
		assertEquals(2, kills(rows.get(0)));
	}

	@Test
	public void aLateStackFilesUnderItsOwnKillNotTheLatest()
	{
		WorldPoint t1 = tileAt(0, 0);
		WorldPoint t2 = tileAt(5, 0);
		kill(100, BEAR, 1, t1);
		TileItem a = spawnAt(101, BONES, t1);
		kill(101, BEAR, 2, t2);
		gameTick(101);
		TileItem b = spawnAt(102, BONES, t2);
		gameTick(102);
		leave(a, b);
		List<JsonObject> rows = untakenRows();
		assertEquals(1, rows.size());
		assertEquals(2, items(rows.get(0)));
		assertEquals(2, kills(rows.get(0)));
	}

	@Test
	public void twoSourcesOnOneTickEachCountTheirOwnKill()
	{
		WorldPoint t1 = tileAt(0, 0);
		WorldPoint t2 = tileAt(5, 0);
		kill(100, BEAR, 1, t1);
		kill(100, WOLF, 2, t2);
		TileItem a = spawnAt(101, BONES, t1);
		TileItem b = spawnAt(101, BONES, t2);
		gameTick(101);
		leave(a, b);
		List<JsonObject> rows = untakenRows();
		assertEquals(2, rows.size());
		assertEquals(1, items(rowFor(rows, BEAR)));
		assertEquals(1, kills(rowFor(rows, BEAR)));
		assertEquals(1, items(rowFor(rows, WOLF)));
		assertEquals(1, kills(rowFor(rows, WOLF)));
	}

	@Test
	public void aStackOneTileOffTheFootprintStillFindsItsKill()
	{
		WorldPoint t1 = tileAt(0, 0);
		WorldPoint t2 = tileAt(5, 0);
		kill(100, BEAR, 1, t1);
		kill(100, BEAR, 2, t2);
		TileItem a = spawnAt(101, BONES, tileAt(-1, -1));
		TileItem b = spawnAt(101, SPIKE, tileAt(6, 1));
		gameTick(101);
		leave(a, b);
		List<JsonObject> rows = untakenRows();
		assertEquals(1, rows.size());
		assertEquals(2, items(rows.get(0)));
		assertEquals(2, kills(rows.get(0)));
	}

	@Test
	public void aStackAnywhereOnALargeFootprintFindsItsKill()
	{
		death(99, 1, BEAR, tileAt(0, 0), 3);
		death(99, 2, BEAR, tileAt(10, 0), 3);
		kill(100, BEAR);
		kill(100, BEAR);
		TileItem a = spawnAt(101, BONES, tileAt(2, 2));
		TileItem b = spawnAt(101, SPIKE, tileAt(12, 2));
		gameTick(101);
		leave(a, b);
		List<JsonObject> rows = untakenRows();
		assertEquals(1, rows.size());
		assertEquals(2, items(rows.get(0)));
		assertEquals(2, kills(rows.get(0)));
	}

	@Test
	public void aStackNoDeathMatchesFallsBackToTheTickRule()
	{
		kill(100, BEAR, 1, tileAt(0, 0));
		kill(100, BEAR, 2, tileAt(5, 0));
		TileItem a = spawnAt(101, BONES, tileAt(2, 0));
		TileItem b = spawnAt(101, SPIKE, tileAt(3, 0));
		gameTick(101);
		leave(a, b);
		List<JsonObject> rows = untakenRows();
		assertEquals(1, rows.size());
		assertEquals(BEAR, rows.get(0).get("source").getAsString());
		assertEquals(2, items(rows.get(0)));
		assertEquals(1, kills(rows.get(0)));
	}

	@Test
	public void aDeathOfASourceThatNeverArmedIsIgnored()
	{
		WorldPoint t1 = tileAt(0, 0);
		death(99, 7, WOLF, t1, 1);
		kill(100, BEAR);
		TileItem a = spawnAt(101, BONES, t1);
		gameTick(101);
		leave(a);
		List<JsonObject> rows = untakenRows();
		assertEquals(1, rows.size());
		assertEquals(BEAR, rows.get(0).get("source").getAsString());
		assertEquals(1, items(rows.get(0)));
		assertEquals(1, kills(rows.get(0)));
	}

	@Test
	public void aDeathWithoutAKillArmsNothing()
	{
		WorldPoint t1 = tileAt(0, 0);
		death(99, 7, WOLF, t1, 1);
		TileItem a = spawnAt(101, BONES, t1);
		gameTick(101);
		gameTick(105);
		leave(a);
		assertTrue(untakenRows().isEmpty());
	}

	@Test
	public void aDeathAfterTheSpawnIsNotItsKill()
	{
		WorldPoint t1 = tileAt(0, 0);
		WorldPoint t2 = tileAt(5, 0);
		TileItem a = spawnAt(101, BONES, t1);
		TileItem b = spawnAt(101, SPIKE, t2);
		gameTick(101);
		kill(101, BEAR);
		death(102, 5, BEAR, t1, 1);
		death(102, 6, BEAR, t2, 1);
		gameTick(102);
		leave(a, b);
		List<JsonObject> rows = untakenRows();
		assertEquals(1, rows.size());
		assertEquals(2, items(rows.get(0)));
		assertEquals(1, kills(rows.get(0)));
	}

	@Test
	public void aGroupOwnedStackHoldsItsDeathToItsOwnTick()
	{
		WorldPoint t1 = tileAt(0, 0);
		kill(99, WOLF, 7, t1);
		kill(101, BEAR);
		TileItem a = spawnAt(101, BONES, t1, TileItem.OWNERSHIP_GROUP);
		gameTick(101);
		leave(a);
		List<JsonObject> rows = untakenRows();
		assertEquals(1, rows.size());
		assertEquals(BEAR, rows.get(0).get("source").getAsString());
		assertEquals(1, kills(rows.get(0)));
	}

	@Test
	public void aDeathDoesNotOutliveTheSession()
	{
		WorldPoint t1 = tileAt(0, 0);
		death(99, 7, WOLF, t1, 1);
		GameStateChanged login = new GameStateChanged();
		login.setGameState(GameState.LOGGING_IN);
		capture.onGameStateChanged(login);
		kill(100, WOLF);
		TileItem a = spawnAt(101, BONES, t1);
		gameTick(101);
		kill(103, WOLF);
		TileItem b = spawnAt(104, BONES, t1);
		gameTick(104);
		leave(a, b);
		List<JsonObject> rows = untakenRows();
		assertEquals(1, rows.size());
		assertEquals(2, items(rows.get(0)));
		assertEquals(2, kills(rows.get(0)));
	}

	@Test
	public void aDeathOlderThanTheWindowIsNotUsed()
	{
		WorldPoint t1 = tileAt(0, 0);
		death(80, 7, WOLF, t1, 1);
		kill(100, WOLF);
		TileItem a = spawnAt(101, BONES, t1);
		gameTick(101);
		kill(110, WOLF);
		TileItem b = spawnAt(111, BONES, t1);
		gameTick(111);
		leave(a, b);
		List<JsonObject> rows = untakenRows();
		assertEquals(1, rows.size());
		assertEquals(2, items(rows.get(0)));
		assertEquals(2, kills(rows.get(0)));
	}
}
