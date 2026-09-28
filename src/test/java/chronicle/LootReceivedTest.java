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
import java.util.ArrayList;
import java.util.Collection;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.http.api.loottracker.LootRecordType;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LootReceivedTest
{
	private ChronicleEventCapture capture;
	private Client client;
	private LocalStore store;

	@Before
	public void setUp()
	{
		client = Mockito.mock(Client.class);
		store = Mockito.mock(LocalStore.class);
		Player me = Mockito.mock(Player.class);
		Mockito.when(me.getName()).thenReturn("Tester");
		Mockito.when(client.getLocalPlayer()).thenReturn(me);
		Mockito.when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		Mockito.when(client.getTickCount()).thenReturn(100);
		capture = new ChronicleEventCapture(client, Mockito.mock(ClientThread.class),
			Mockito.mock(ConfigManager.class), Mockito.mock(ChronicleConfig.class),
			Mockito.mock(ChronicleApiClient.class), store);
	}

	private static LootReceived loot(String name, LootRecordType type)
	{
		Collection<ItemStack> items = new ArrayList<>();
		items.add(new ItemStack(995, 5000));
		return new LootReceived(name, 1, type, items, 1, null);
	}

	private JsonObject recorded()
	{
		ArgumentCaptor<JsonObject> cap = ArgumentCaptor.forClass(JsonObject.class);
		Mockito.verify(store, Mockito.times(1))
			.record(Mockito.eq("LOOT"), cap.capture(), Mockito.eq("Tester"));
		return cap.getValue();
	}

	@Test
	public void anEventChestIsRecordedUnderItsOwnName()
	{
		capture.onLootReceived(loot("Barrows", LootRecordType.EVENT));
		JsonObject data = recorded();
		assertEquals("Barrows", data.get("source").getAsString());
		assertEquals("EVENT", data.get("category").getAsString());
		assertTrue("the items have to travel with it", data.has("items"));
	}

	@Test
	public void aPickpocketKeepsItsOwnCategory()
	{
		capture.onLootReceived(loot("Man", LootRecordType.PICKPOCKET));
		assertEquals("PICKPOCKET", recorded().get("category").getAsString());
	}

	@Test
	public void npcLootIsRefusedBecauseTheOtherHandlerHasIt()
	{
		capture.onLootReceived(loot("Vorkath", LootRecordType.NPC));
		Mockito.verify(store, Mockito.never()).record(
			Mockito.anyString(), Mockito.any(JsonObject.class), Mockito.anyString());
	}

	@Test
	public void playerLootIsNeverRecorded()
	{
		capture.onLootReceived(loot("SomeOtherPlayer", LootRecordType.PLAYER));
		Mockito.verify(store, Mockito.never()).record(
			Mockito.anyString(), Mockito.any(JsonObject.class), Mockito.anyString());
	}
}
