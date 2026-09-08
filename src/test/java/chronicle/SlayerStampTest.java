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
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.slayer.SlayerPluginService;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The slayer stamp lands only on kills that count toward the live task, while a
 * task being live at all still marks the session as a slayer's and keeps the task
 * identity the completion line falls back to.
 */
public class SlayerStampTest
{
	private ChronicleEventCapture capture;
	private SlayerPluginService slayer;

	@Before
	public void setUp() throws Exception
	{
		capture = new ChronicleEventCapture(Mockito.mock(Client.class),
			Mockito.mock(ClientThread.class), Mockito.mock(ConfigManager.class),
			Mockito.mock(ChronicleConfig.class), Mockito.mock(ChronicleApiClient.class),
			Mockito.mock(LocalStore.class));
		slayer = Mockito.mock(SlayerPluginService.class);
		Mockito.when(slayer.getRemainingAmount()).thenReturn(196);
		Mockito.when(slayer.getInitialAmount()).thenReturn(200);
		Mockito.when(slayer.getTaskLocation()).thenReturn("Catacombs of Kourend");
		set("slayerService", slayer);
	}

	private void set(String field, Object value) throws Exception
	{
		Field f = ChronicleEventCapture.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(capture, value);
	}

	private Object get(String field) throws Exception
	{
		Field f = ChronicleEventCapture.class.getDeclaredField(field);
		f.setAccessible(true);
		return f.get(capture);
	}

	private JsonObject stamp(String npc, int id)
	{
		JsonObject data = new JsonObject();
		data.addProperty("source", npc);
		data.addProperty("npcId", id);
		capture.stampSlayer(data, npc, id);
		return data;
	}

	@Test
	public void onTaskKillCarriesTheWholeStamp()
	{
		Mockito.when(slayer.getTask()).thenReturn("Nechryael");
		JsonObject d = stamp("Nechryael", 11);
		assertEquals("Nechryael", d.get("slayerTask").getAsString());
		assertEquals(196, d.get("slayerTaskRemaining").getAsInt());
		assertEquals(200, d.get("slayerTaskInitial").getAsInt());
		assertEquals("Catacombs of Kourend", d.get("slayerTaskLocation").getAsString());
	}

	@Test
	public void offTaskKillsDuringATaskAreNotStamped()
	{
		Mockito.when(slayer.getTask()).thenReturn("Nechryael");
		for (String npc : new String[]{"Man", "Baby impling", "Eclectic impling"})
		{
			JsonObject d = stamp(npc, SlayerTaskBook.UNKNOWN_ID);
			assertFalse(npc, d.has("slayerTask"));
			assertFalse(npc, d.has("slayerTaskRemaining"));
			assertFalse(npc, d.has("slayerTaskInitial"));
			assertFalse(npc, d.has("slayerTaskLocation"));
		}
	}

	@Test
	public void guardOnElvesGoesByItsId()
	{
		Mockito.when(slayer.getTask()).thenReturn("Elves");
		assertTrue(stamp("Guard", 9183).has("slayerTask"));   // Prifddinas
		assertFalse(stamp("Guard", 3010).has("slayerTask"));  // Varrock
	}

	@Test
	public void anOffTaskKillStillMarksTheSessionAndRemembersTheTask() throws Exception
	{
		Mockito.when(slayer.getTask()).thenReturn("Nechryael");
		assertFalse(capture.slayerSeenThisSession());
		JsonObject d = stamp("Man", SlayerTaskBook.UNKNOWN_ID);
		assertFalse(d.has("slayerTask"));
		assertTrue(capture.slayerSeenThisSession());
		assertEquals("Nechryael", get("lastSlayerTask"));
	}

	@Test
	public void noTaskMeansNoStampAndNoSideEffects() throws Exception
	{
		Mockito.when(slayer.getTask()).thenReturn("");
		JsonObject d = stamp("Nechryael", 11);
		assertFalse(d.has("slayerTask"));
		assertFalse(capture.slayerSeenThisSession());
		assertEquals(null, get("lastSlayerTask"));
	}
}
