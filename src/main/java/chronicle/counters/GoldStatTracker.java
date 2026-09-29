/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle.counters;

import lombok.RequiredArgsConstructor;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;

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
		if (event.getGroupId() == InterfaceID.SHOPSIDE)
		{
			coinsLastTick = packCoins();
		}
	}

	@Override
	public void onWidgetClosed(WidgetClosed event)
	{
		if (event.getGroupId() == InterfaceID.SHOPSIDE)
		{
			coinsLastTick = IDLE;
		}
	}

	@Override
	public void onGameTick(GameTick event)
	{
		int coins = coinsLastTick == IDLE ? IDLE : packCoins();
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
		ItemContainer pack = client.getItemContainer(InventoryID.INV);
		return pack == null ? IDLE : pack.count(ItemID.COINS);
	}
}
