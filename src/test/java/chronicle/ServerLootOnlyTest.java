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
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.events.GameTick;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.game.ItemStack;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * NPC loot comes only from the game's loot script. The client-side despawn sweep
 * (RuneLite's NpcLootReceived) is not subscribed to at all, and one ServerNpcLoot
 * produces exactly one LOOT row, on the tick it arrives, marked as the server's.
 */
public class ServerLootOnlyTest
{
	private static final String SWEEP_EVENT = "net.runelite.client.events.NpcLootReceived";
	private static final int DUST_DEVIL = 7249;

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

	@Test
	public void noSubscriberTakesTheClientSideSweepEvent()
	{
		boolean serverSubscribed = false;
		for (Method m : ChronicleEventCapture.class.getDeclaredMethods())
		{
			if (m.getAnnotation(Subscribe.class) == null)
			{
				continue;
			}
			for (Class<?> p : m.getParameterTypes())
			{
				assertFalse(m.getName() + " subscribes to " + SWEEP_EVENT,
					SWEEP_EVENT.equals(p.getName()));
				if (p == ServerNpcLoot.class)
				{
					serverSubscribed = true;
				}
			}
		}
		assertTrue("a @Subscribe method must take ServerNpcLoot", serverSubscribed);
	}

	@Test
	public void oneServerLootEmitsExactlyOneServerRowOnTheSameTick()
	{
		NPCComposition comp = Mockito.mock(NPCComposition.class);
		Mockito.when(comp.getName()).thenReturn("Dust devil");
		Mockito.when(comp.getId()).thenReturn(DUST_DEVIL);
		List<ItemStack> items = new ArrayList<>();
		items.add(new ItemStack(526, 1));      // bones
		items.add(new ItemStack(1618, 1));     // uncut diamond

		capture.onServerNpcLoot(new ServerNpcLoot(comp, items));

		// emitted straight away, before any GameTick
		ArgumentCaptor<JsonObject> row = ArgumentCaptor.forClass(JsonObject.class);
		Mockito.verify(store, Mockito.times(1)).record(
			Mockito.eq("LOOT"), row.capture(), Mockito.eq("Tester"));
		JsonObject data = row.getValue();
		assertEquals("server", data.get("lootSource").getAsString());
		assertEquals("Dust devil", data.get("source").getAsString());
		assertEquals(DUST_DEVIL, data.get("npcId").getAsInt());
		assertEquals("NPC", data.get("category").getAsString());
		assertEquals(2, data.getAsJsonArray("items").size());
		assertEquals(526, data.getAsJsonArray("items").get(0).getAsJsonObject().get("id").getAsInt());

		// later ticks add nothing: there is no held copy waiting to be flushed
		for (int t = 101; t <= 105; t++)
		{
			Mockito.when(client.getTickCount()).thenReturn(t);
			capture.onGameTick(new GameTick());
		}
		Mockito.verify(store, Mockito.times(1)).record(
			Mockito.anyString(), Mockito.any(JsonObject.class), Mockito.anyString());
	}
}
