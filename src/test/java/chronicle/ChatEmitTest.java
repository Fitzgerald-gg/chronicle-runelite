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
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import static org.junit.Assert.assertEquals;

/**
 * A chat line all the way through to a recorded event.
 *
 * <p>COLLECTION, CLUE, COMBAT_ACHIEVEMENT, DIARY and PET are emitted from nowhere
 * but onChatMessage, and the suite tested the regexes alone. A pattern that still
 * matches while the handler around it stops emitting, or emits under a changed
 * kind, is a whole class of milestone going quietly missing from the journal with
 * the corpus staying green.
 */
public class ChatEmitTest
{
	private ChronicleEventCapture capture;
	private LocalStore store;

	@Before
	public void setUp()
	{
		Client client = Mockito.mock(Client.class);
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

	private void said(String text)
	{
		capture.onChatMessage(
			new ChatMessage(null, ChatMessageType.GAMEMESSAGE, "", text, null, 0));
	}

	/** The one event of that kind the store was handed, or a failure. */
	private JsonObject emitted(String kind)
	{
		ArgumentCaptor<JsonObject> cap = ArgumentCaptor.forClass(JsonObject.class);
		Mockito.verify(store, Mockito.atLeastOnce())
			.record(Mockito.eq(kind), cap.capture(), Mockito.eq("Tester"));
		return cap.getValue();
	}

	@Test
	public void aCollectionLogLineIsRecorded()
	{
		said("New item added to your collection log: Dragon pickaxe");
		assertEquals("Dragon pickaxe", emitted("COLLECTION").get("itemName").getAsString());
	}

	@Test
	public void aCombatAchievementIsRecordedWithItsTier()
	{
		said("Congratulations, you've completed a Grandmaster combat task: Insanity.");
		JsonObject d = emitted("COMBAT_ACHIEVEMENT");
		assertEquals("GRANDMASTER", d.get("tier").getAsString());
		assertEquals("Insanity", d.get("task").getAsString());
	}

	/** The points suffix the game appends is not part of the task's name. */
	@Test
	public void theTaskNameLosesItsPointsSuffix()
	{
		said("Congratulations, you've completed a Hard combat task: Peach Conjurer (3 points).");
		assertEquals("Peach Conjurer", emitted("COMBAT_ACHIEVEMENT").get("task").getAsString());
	}

	@Test
	public void aClueCompletionCarriesItsTierAndTally()
	{
		said("You have completed 87 hard Treasure Trails.");
		JsonObject d = emitted("CLUE");
		assertEquals("HARD", d.get("clueType").getAsString());
		assertEquals(87, d.get("clueCount").getAsInt());
	}

	@Test
	public void aDiaryCompletionIsRecorded()
	{
		said("Congratulations! You have completed all of the hard tasks in the "
			+ "Lumbridge & Draynor area.");
		Mockito.verify(store, Mockito.atLeastOnce()).record(
			Mockito.eq("DIARY"), Mockito.any(JsonObject.class), Mockito.eq("Tester"));
	}

	/**
	 * The feeling line names no pet, so it arms a wait rather than emitting. The
	 * collection log line that follows is what gives it a name.
	 */
	@Test
	public void aPetIsRecordedOnceTheLineAfterItNamesIt()
	{
		said("You have a funny feeling like you're being followed.");
		Mockito.verify(store, Mockito.never()).record(
			Mockito.eq("PET"), Mockito.any(JsonObject.class), Mockito.anyString());

		said("New item added to your collection log: Baby mole");
		assertEquals("Baby mole", emitted("PET").get("petName").getAsString());
	}

	/** And a feeling nothing ever named expires instead of hanging about. */
	@Test
	public void anUnnamedPetExpires()
	{
		said("You have a funny feeling like you're being followed.");
		for (int tick = 0; tick < 5; tick++)
		{
			capture.onGameTick(new net.runelite.api.events.GameTick());
		}
		said("New item added to your collection log: Dragon pickaxe");
		Mockito.verify(store, Mockito.never()).record(
			Mockito.eq("PET"), Mockito.any(JsonObject.class), Mockito.anyString());
	}

	/** A line that is nearly one of these is still not one of these. */
	@Test
	public void aNearMissEmitsNothing()
	{
		said("You have a funny feeling like you're being watched.");
		said("You have completed a hard Treasure Trail.");
		Mockito.verify(store, Mockito.never()).record(
			Mockito.anyString(), Mockito.any(JsonObject.class), Mockito.anyString());
	}
}
