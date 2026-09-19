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
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import static org.junit.Assert.assertEquals;

/**
 * Group storage: a three-event state machine on the client thread, with no test.
 *
 * <p>Open arms it, the FIRST sync of the temp container is the baseline, later
 * syncs are the running state, and close diffs the two into one event. The
 * product that consumes it is the GIM bot's shared-storage ledger, so a break
 * here is silent until somebody asks the bot what the group has.
 */
public class GroupStorageTest
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

	private void open()
	{
		WidgetLoaded e = new WidgetLoaded();
		e.setGroupId(InterfaceID.SHARED_BANK);
		capture.onWidgetLoaded(e);
	}

	private void close()
	{
		WidgetClosed e = new WidgetClosed(InterfaceID.SHARED_BANK, 0, true);
		capture.onWidgetClosed(e);
	}

	/** One server sync of the shared-storage temp container. */
	private void sync(int... idsAndQtys)
	{
		// Item is final, so these are the real thing rather than mocks.
		Item[] items = new Item[idsAndQtys.length / 2];
		for (int i = 0; i < items.length; i++)
		{
			items[i] = new Item(idsAndQtys[i * 2], idsAndQtys[i * 2 + 1]);
		}
		ItemContainer c = Mockito.mock(ItemContainer.class);
		Mockito.when(c.getItems()).thenReturn(items);
		capture.onItemContainerChanged(
			new ItemContainerChanged(InventoryID.INV_GROUP_TEMP, c));
	}

	private JsonObject recorded()
	{
		ArgumentCaptor<JsonObject> cap = ArgumentCaptor.forClass(JsonObject.class);
		Mockito.verify(store, Mockito.times(1))
			.record(Mockito.eq("GROUP_STORAGE"), cap.capture(), Mockito.eq("Tester"));
		return cap.getValue();
	}

	@Test
	public void aDepositAndAWithdrawalTravelInOneEvent()
	{
		open();
		sync(995, 1000, 314, 50);        // the opening sync: coins and feathers
		sync(995, 1500, 314, 20);        // deposited 500 coins, took 30 feathers
		close();

		JsonObject d = recorded();
		assertEquals(1, d.getAsJsonArray("deposits").size());
		assertEquals(1, d.getAsJsonArray("withdrawals").size());
		JsonObject dep = d.getAsJsonArray("deposits").get(0).getAsJsonObject();
		assertEquals(995, dep.get("id").getAsInt());
		assertEquals(500, dep.get("quantity").getAsInt());
		JsonObject wd = d.getAsJsonArray("withdrawals").get(0).getAsJsonObject();
		assertEquals(314, wd.get("id").getAsInt());
		assertEquals(30, wd.get("quantity").getAsInt());
	}

	/** Opened, looked at, closed: nothing moved, so nothing is recorded. */
	@Test
	public void lookingIsNotAnEvent()
	{
		open();
		sync(995, 1000);
		sync(995, 1000);
		close();
		Mockito.verify(store, Mockito.never()).record(
			Mockito.eq("GROUP_STORAGE"), Mockito.any(JsonObject.class), Mockito.anyString());
	}

	/** The container never synced, so nothing was ever observed to diff. */
	@Test
	public void aStorageThatNeverSyncedRecordsNothing()
	{
		open();
		close();
		Mockito.verify(store, Mockito.never()).record(
			Mockito.eq("GROUP_STORAGE"), Mockito.any(JsonObject.class), Mockito.anyString());
	}

	/** A sync arriving while the interface is shut belongs to something else. */
	@Test
	public void syncsOutsideTheInterfaceAreIgnored()
	{
		sync(995, 1000);
		open();
		sync(995, 1000);
		sync(995, 4000);
		close();
		assertEquals("the baseline is the first sync AFTER opening, not before",
			3000, recorded().getAsJsonArray("deposits").get(0)
				.getAsJsonObject().get("quantity").getAsInt());
	}
}
