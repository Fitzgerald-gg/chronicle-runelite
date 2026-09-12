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
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
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
import static org.junit.Assert.assertTrue;

/**
 * The "kills" figure on a LOOT_UNTAKEN event: how many kills of the source left
 * at least one stack on the floor, whatever the number of stacks. It is the unit
 * "Drops taken" subtracts from the loot events, so a kill that left two stacks is
 * one kill, a stack that was picked up counts nothing, and a stack knows its kill
 * by tick alone, so two kills on one tick read as one.
 */
public class LeftBehindKillsTest
{
	private static final int SPIKE = 30_000;
	private static final int BONES = 526;
	private static final int DESPAWN = 300;
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

	private void kill(int t, String source)
	{
		tick(t);
		NPCComposition comp = Mockito.mock(NPCComposition.class);
		Mockito.when(comp.getName()).thenReturn(source);
		Mockito.when(comp.getId()).thenReturn(1);
		capture.onServerNpcLoot(new ServerNpcLoot(comp, new ArrayList<ItemStack>()));
	}

	private TileItem spawn(int t, int id)
	{
		tick(t);
		TileItem it = Mockito.mock(TileItem.class);
		Mockito.when(it.getId()).thenReturn(id);
		Mockito.when(it.getQuantity()).thenReturn(1);
		Mockito.when(it.getOwnership()).thenReturn(TileItem.OWNERSHIP_SELF);
		Mockito.when(it.getDespawnTime()).thenReturn(DESPAWN);
		capture.onItemSpawned(new ItemSpawned(tile, it));
		return it;
	}

	// the stack despawns before its scheduled tick: picked up
	private void pickUp(int t, TileItem item)
	{
		tick(t);
		capture.onItemDespawned(new ItemDespawned(tile, item));
	}

	// the stacks reach their own despawn tick on the ground, and the next tick flushes
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

	// an AoE burst: two kills of one source posted on the same tick. A stack knows
	// its kill by tick alone, so they read as one kill.
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

	// A tracked stack that carries no kill (nothing promotes one today; the guard
	// is for a future path that tracks a stack without a kill) counts no kill: the
	// figure is kills, and none is known.
	@Test
	@SuppressWarnings("unchecked")
	public void aStackWithoutAKillCountsNoKill() throws Exception
	{
		Class<?> groundLoot = Class.forName("chronicle.ChronicleEventCapture$GroundLoot");
		Constructor<?> bare = groundLoot.getDeclaredConstructor(int.class, int.class, int.class,
			int.class, boolean.class, String.class);
		bare.setAccessible(true);
		Object stack = bare.newInstance(BONES, 1, DESPAWN, 101, false, "Tester");
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

	// leaving the scene banks whatever is still on the ground as left behind; the
	// sweep carries each stack's kill along, so the figure is the same as if the
	// stacks had timed out
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
		unload.setGameState(GameState.LOADING);
		capture.onGameStateChanged(unload);
		gameTick(113);
		List<JsonObject> rows = untakenRows();
		assertEquals(1, rows.size());
		assertEquals(2, items(rows.get(0)));
		assertEquals(2, kills(rows.get(0)));
	}
}
