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
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.game.ItemStack;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The game posts NPC loot for a successful pickpocket as well as the chat line. That
 * loot is not a kill: on the line's tick it emits nothing and arms no untaken
 * tracking. The PICKPOCKET entry itself comes from the Loot Tracker.
 */
public class PickpocketLootTest
{
	private static final int MAN = 3106;

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

	private Object get(String field) throws Exception
	{
		Field f = ChronicleEventCapture.class.getDeclaredField(field);
		f.setAccessible(true);
		return f.get(capture);
	}

	private int armedKills() throws Exception
	{
		return ((List<?>) get("recentKills")).size();
	}

	private void pickpocketLine()
	{
		capture.onChatMessage(new ChatMessage(null, ChatMessageType.GAMEMESSAGE, "",
			"You pick the man's pocket.", null, 0));
	}

	private ServerNpcLoot manLoot()
	{
		NPCComposition comp = Mockito.mock(NPCComposition.class);
		Mockito.when(comp.getName()).thenReturn("Man");
		Mockito.when(comp.getId()).thenReturn(MAN);
		List<ItemStack> items = new ArrayList<>();
		items.add(new ItemStack(995, 3));   // coins
		return new ServerNpcLoot(comp, items);
	}

	@Test
	public void lootOnThePickpocketTickEmitsNothingAndArmsNoKill() throws Exception
	{
		pickpocketLine();
		capture.onServerNpcLoot(manLoot());
		Mockito.verify(store, Mockito.never()).record(
			Mockito.anyString(), Mockito.any(JsonObject.class), Mockito.anyString());
		assertEquals(0, armedKills());
	}

	@Test
	public void theSameLootOnTheNextTickIsAKill() throws Exception
	{
		pickpocketLine();
		Mockito.when(client.getTickCount()).thenReturn(101);
		capture.onServerNpcLoot(manLoot());
		Mockito.verify(store, Mockito.times(1)).record(
			Mockito.eq("LOOT"), Mockito.any(JsonObject.class), Mockito.eq("Tester"));
		assertEquals(1, armedKills());
	}

	@Test
	public void thePickpocketTickDoesNotOutliveTheSession() throws Exception
	{
		pickpocketLine();
		assertEquals(100, get("pickpocketTick"));
		capture.reset();
		assertTrue((int) get("pickpocketTick") < 0);
	}
}
