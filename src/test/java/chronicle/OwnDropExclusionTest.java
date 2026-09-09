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
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemDespawned;
import net.runelite.api.events.ItemSpawned;
import net.runelite.api.events.MenuOptionClicked;
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
 * An item the player drops within a few ticks of a kill spawns self-owned, exactly
 * like the kill's loot. A "Drop" click just before the spawn marks it as the
 * player's own, so leaving it there is not a LOOT_UNTAKEN row against the monster.
 */
public class OwnDropExclusionTest
{
	private static final int SPIKE = 30_000;
	private static final int DESPAWN = 200;
	private static final String BEAR = "Corrupted Bear";

	private ChronicleEventCapture capture;
	private Client client;
	private LocalStore store;
	private Player me;
	private Tile tile;

	@Before
	public void setUp()
	{
		client = Mockito.mock(Client.class);
		store = Mockito.mock(LocalStore.class);
		me = Mockito.mock(Player.class);
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

	private Object get(String field) throws Exception
	{
		Field f = ChronicleEventCapture.class.getDeclaredField(field);
		f.setAccessible(true);
		return f.get(capture);
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

	private void click(int t, String option, int id)
	{
		tick(t);
		MenuEntry entry = Mockito.mock(MenuEntry.class);
		Mockito.when(entry.isItemOp()).thenReturn(true);
		Mockito.when(entry.getOption()).thenReturn(option);
		Mockito.when(entry.getItemId()).thenReturn(id);
		capture.onMenuOptionClicked(new MenuOptionClicked(entry));
	}

	private void drop(int t, int id)
	{
		click(t, "Drop", id);
	}

	private TileItem spawn(int t, int id)
	{
		return spawn(t, id, TileItem.OWNERSHIP_SELF);
	}

	private TileItem spawn(int t, int id, int ownership)
	{
		tick(t);
		TileItem it = Mockito.mock(TileItem.class);
		Mockito.when(it.getId()).thenReturn(id);
		Mockito.when(it.getQuantity()).thenReturn(1);
		Mockito.when(it.getOwnership()).thenReturn(ownership);
		Mockito.when(it.getDespawnTime()).thenReturn(DESPAWN);
		capture.onItemSpawned(new ItemSpawned(tile, it));
		return it;
	}

	// the item reaches its own despawn tick on the ground, and the next tick flushes.
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

	@Test
	public void killLootLeftBehindIsBookedAgainstTheKill()
	{
		kill(100, BEAR);
		TileItem spike = spawn(101, SPIKE);
		gameTick(101);
		leave(spike);
		List<JsonObject> rows = untakenRows();
		assertEquals(1, rows.size());
		assertEquals(BEAR, rows.get(0).get("source").getAsString());
		assertEquals(1, rows.get(0).getAsJsonArray("items").size());
		assertEquals(SPIKE, rows.get(0).getAsJsonArray("items").get(0)
			.getAsJsonObject().get("id").getAsInt());
	}

	@Test
	public void anItemDroppedNextToTheKillIsNotUntakenLoot()
	{
		kill(100, BEAR);
		drop(100, SPIKE);
		TileItem spike = spawn(101, SPIKE);
		gameTick(101);
		leave(spike);
		assertTrue(untakenRows().isEmpty());
	}

	@Test
	public void aDropClickExplainsOnlyOneSpawn()
	{
		kill(100, BEAR);
		drop(100, SPIKE);
		TileItem first = spawn(101, SPIKE);
		TileItem second = spawn(101, SPIKE);
		gameTick(101);
		leave(first, second);
		List<JsonObject> rows = untakenRows();
		assertEquals(1, rows.size());
		assertEquals(1, rows.get(0).getAsJsonArray("items").size());
	}

	@Test
	public void aDropClickOlderThanTheWindowExplainsNothing()
	{
		drop(97, SPIKE);
		kill(100, BEAR);
		TileItem spike = spawn(101, SPIKE);
		gameTick(101);
		leave(spike);
		assertEquals(1, untakenRows().size());
	}

	@Test
	public void aDropOnAnotherTileIsTheKills()
	{
		Mockito.when(me.getWorldLocation()).thenReturn(new WorldPoint(3200, 3200, 0));
		Mockito.when(tile.getWorldLocation()).thenReturn(new WorldPoint(3201, 3200, 0));
		kill(100, BEAR);
		drop(100, SPIKE);
		TileItem spike = spawn(101, SPIKE);
		gameTick(101);
		leave(spike);
		assertEquals(1, untakenRows().size());
	}

	@Test
	public void aDropAtOurFeetIsOurs()
	{
		Mockito.when(me.getWorldLocation()).thenReturn(new WorldPoint(3200, 3200, 0));
		Mockito.when(tile.getWorldLocation()).thenReturn(new WorldPoint(3200, 3200, 0));
		kill(100, BEAR);
		drop(100, SPIKE);
		TileItem spike = spawn(101, SPIKE);
		gameTick(101);
		leave(spike);
		assertTrue(untakenRows().isEmpty());
	}

	@Test
	public void dropClicksDoNotOutliveTheSession() throws Exception
	{
		drop(100, SPIKE);
		assertEquals(1, ((List<?>) get("recentDrops")).size());
		capture.reset();
		assertTrue(((List<?>) get("recentDrops")).isEmpty());
	}

	// The live order. The item spawns while the tick's packets are read, our own
	// GameTick runs before LootManager's (the event bus orders equal-priority
	// subscribers by class name), and only then does LootManager post the kill,
	// on the same tick count. The spawn has to survive that first kill-less tick.
	@Test
	public void theKillIsPostedAfterTheSpawnAndOurOwnGameTick()
	{
		TileItem spike = spawn(100, SPIKE);
		gameTick(100);
		kill(100, BEAR);
		gameTick(101);
		leave(spike);
		List<JsonObject> rows = untakenRows();
		assertEquals(1, rows.size());
		assertEquals(BEAR, rows.get(0).get("source").getAsString());
	}

	@Test
	public void aGroupOwnedSpawnIsBookedInTheLiveOrderToo()
	{
		TileItem spike = spawn(100, SPIKE, TileItem.OWNERSHIP_GROUP);
		gameTick(100);
		kill(100, BEAR);
		gameTick(101);
		leave(spike);
		List<JsonObject> rows = untakenRows();
		assertEquals(1, rows.size());
		assertEquals(BEAR, rows.get(0).get("source").getAsString());
	}

	// Only "Drop" puts an item on the ground. Any other option on the same item
	// (here Destroy) explains nothing, so the kill's spawn stays untaken loot.
	@Test
	public void onlyADropClickExplainsASpawn()
	{
		kill(100, BEAR);
		click(100, "Destroy", SPIKE);
		TileItem spike = spawn(101, SPIKE);
		gameTick(101);
		leave(spike);
		assertEquals(1, untakenRows().size());
	}

	@Test
	public void aDropClickWithoutAnItemIsNotRemembered() throws Exception
	{
		drop(100, 0);
		assertEquals(0, ((List<?>) get("recentDrops")).size());
	}

	@Test
	public void aDropTwoTicksBeforeItsSpawnIsStillOurs()
	{
		drop(99, SPIKE);
		kill(100, BEAR);
		TileItem spike = spawn(101, SPIKE);
		gameTick(101);
		leave(spike);
		assertEquals(0, untakenRows().size());
	}

	@Test
	public void aDropThreeTicksBeforeItsSpawnIsTheKills()
	{
		drop(98, SPIKE);
		kill(100, BEAR);
		TileItem spike = spawn(101, SPIKE);
		gameTick(101);
		leave(spike);
		assertEquals(1, untakenRows().size());
	}

	@Test
	public void aDropOptionOffAnInventoryItemIsNotRemembered() throws Exception
	{
		tick(100);
		MenuEntry entry = Mockito.mock(MenuEntry.class);
		Mockito.when(entry.isItemOp()).thenReturn(false);
		Mockito.when(entry.getOption()).thenReturn("Drop");
		Mockito.when(entry.getItemId()).thenReturn(SPIKE);
		capture.onMenuOptionClicked(new MenuOptionClicked(entry));
		assertEquals(0, ((List<?>) get("recentDrops")).size());
	}

	@Test
	public void aDropWhileRunningLandsWhereTheClickWasMade()
	{
		// clicked at 3200,3200; by the time the spawn is read the player is a tile on
		Mockito.when(me.getWorldLocation()).thenReturn(new WorldPoint(3200, 3200, 0));
		kill(100, BEAR);
		drop(100, SPIKE);
		Mockito.when(me.getWorldLocation()).thenReturn(new WorldPoint(3201, 3200, 0));
		Mockito.when(tile.getWorldLocation()).thenReturn(new WorldPoint(3200, 3200, 0));
		TileItem spike = spawn(101, SPIKE);
		gameTick(101);
		leave(spike);
		assertEquals(0, untakenRows().size());
	}
}
