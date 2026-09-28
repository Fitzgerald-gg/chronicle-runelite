/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the BSD 2-Clause
 * License (see LICENSE) are met.
 */
package chronicle.counters;

import lombok.RequiredArgsConstructor;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.ItemContainer;
import net.runelite.api.ItemID;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.widgets.InterfaceID;

@RequiredArgsConstructor
public class GoldStatTracker implements StatTracker
{
	private static final int IDLE = -1;

	private final StatStore statStore;
	private final Client client;

	private int coinsLastTick = IDLE;

	@Override
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (event.getGroupId() == InterfaceID.SHOP_INVENTORY)
		{
			coinsLastTick = packCoins();
		}
	}

	@Override
	public void onWidgetClosed(WidgetClosed event)
	{
		if (event.getGroupId() == InterfaceID.SHOP_INVENTORY)
		{
			coinsLastTick = IDLE;
		}
	}

	@Override
	public void onGameTick(GameTick event)
	{
		if (coinsLastTick == IDLE)
		{
			return;
		}

		int coins = packCoins();
		if (coins == IDLE)
		{
			return;
		}

		int change = coins - coinsLastTick;
		if (change < 0)
		{
			statStore.incrementStatBy("coinsSpentAtShops", -change);
		}
		else if (change > 0)
		{
			statStore.incrementStatBy("coinsEarnedAtShops", change);
		}
		coinsLastTick = coins;
	}

	@Override
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() != GameState.LOGGED_IN)
		{
			coinsLastTick = IDLE;
		}
	}

	private int packCoins()
	{
		ItemContainer pack = client.getItemContainer(InventoryID.INVENTORY);
		return pack == null ? IDLE : pack.count(ItemID.COINS_995);
	}
}
