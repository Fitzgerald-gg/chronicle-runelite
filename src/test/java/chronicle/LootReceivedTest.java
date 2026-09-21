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

/**
 * The core Loot Tracker's own event, and the only way non-NPC loot reaches the
 * journal: clue caskets, Barrows and raid chests, implings, the Tempoross and
 * Wintertodt reward pools, Hespori, pickpockets.
 *
 * <p>No test called this handler. Every one of those sources entered the record
 * through code the suite never ran, and the two kinds it deliberately REFUSES
 * are the kinds that would double-count or carry another player's name.
 */
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

	/**
	 * NPC loot arrives through onServerNpcLoot as well, and recording it here too
	 * would count every ordinary kill twice.
	 */
	@Test
	public void npcLootIsRefusedBecauseTheOtherHandlerHasIt()
	{
		capture.onLootReceived(loot("Vorkath", LootRecordType.NPC));
		Mockito.verify(store, Mockito.never()).record(
			Mockito.anyString(), Mockito.any(JsonObject.class), Mockito.anyString());
	}

	/**
	 * And PLAYER loot is refused outright. A kill in the wilderness carries the
	 * victim's display name and their inventory, and this plugin records its own
	 * account and nothing else. This is the hard rule, not a preference.
	 */
	@Test
	public void playerLootIsNeverRecorded()
	{
		capture.onLootReceived(loot("SomeOtherPlayer", LootRecordType.PLAYER));
		Mockito.verify(store, Mockito.never()).record(
			Mockito.anyString(), Mockito.any(JsonObject.class), Mockito.anyString());
	}
}
