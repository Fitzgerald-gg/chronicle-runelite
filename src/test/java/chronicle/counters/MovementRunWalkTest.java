/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the BSD 2-Clause
 * License (see LICENSE) are met.
 */
package chronicle.counters;

import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.game.ItemManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static chronicle.counters.CounterTestKeys.DISTANCE_RAN;
import static chronicle.counters.CounterTestKeys.DISTANCE_WALKED;
import static org.junit.Assert.assertEquals;

public class MovementRunWalkTest
{
	private StatStore store;
	private Client client;
	private Player local;
	private MovementStatTracker tracker;
	private ItemManager items;

	@Before
	public void setUp()
	{
		store = new StatStore();
		client = Mockito.mock(Client.class);
		items = Mockito.mock(ItemManager.class);
		local = Mockito.mock(Player.class);
		Mockito.when(client.getLocalPlayer()).thenReturn(local);
		tracker = new MovementStatTracker(store, client, items);
	}

	private void runToggle(boolean on)
	{
		Mockito.when(client.getVarpValue(VarPlayerID.OPTION_RUN)).thenReturn(on ? 1 : 0);
		Mockito.when(client.getEnergy()).thenReturn(10000);
	}

	private void energy(int hundredthsOfAPercent)
	{
		Mockito.when(client.getEnergy()).thenReturn(hundredthsOfAPercent);
	}

	private void standAt(int x, int y)
	{
		Mockito.when(local.getWorldLocation()).thenReturn(new WorldPoint(x, y, 0));
		tracker.onGameTick(new GameTick());
	}

	private int ran()
	{
		return store.getStat(DISTANCE_RAN);
	}

	private int walked()
	{
		return store.getStat(DISTANCE_WALKED);
	}

	@Test
	public void twoTilesInATickIsARunEvenWhenTheToggleReadsOff()
	{
		runToggle(false);
		standAt(3200, 3200);
		standAt(3202, 3200);

		assertEquals(2, ran());
		assertEquals(0, walked());
	}

	@Test
	public void twoTilesIsARunOnAnEmptyBarToo()
	{
		runToggle(false);
		energy(0);
		standAt(3200, 3200);
		standAt(3200, 3202);

		assertEquals(2, ran());
		assertEquals(0, walked());
	}

	@Test
	public void oneTileWithRunOnIsARun()
	{
		runToggle(true);
		standAt(3200, 3200);
		standAt(3201, 3200);

		assertEquals(1, ran());
		assertEquals(0, walked());
	}

	@Test
	public void oneTileWithRunOffIsAWalk()
	{
		runToggle(false);
		standAt(3200, 3200);
		standAt(3201, 3201);

		assertEquals(0, ran());
		assertEquals(1, walked());
	}

	@Test
	public void oneTileWithRunOnButNoEnergyIsAWalk()
	{
		runToggle(true);
		energy(0);
		standAt(3200, 3200);
		standAt(3201, 3200);

		assertEquals(0, ran());
		assertEquals(1, walked());
	}

	@Test
	public void aRunPathBooksItsTailWithTheRestOfIt()
	{
		runToggle(true);
		standAt(3200, 3200);
		standAt(3202, 3200);
		standAt(3204, 3200);
		standAt(3205, 3200);

		assertEquals(5, ran());
		assertEquals(0, walked());
	}

	@Test
	public void noWidgetIsEverConsulted()
	{
		runToggle(true);
		standAt(3200, 3200);
		standAt(3202, 3200);
		standAt(3203, 3200);

		Mockito.verify(client, Mockito.never()).getWidget(Mockito.anyInt());
		Mockito.verify(client, Mockito.never()).getWidget(Mockito.anyInt(), Mockito.anyInt());
	}
}
