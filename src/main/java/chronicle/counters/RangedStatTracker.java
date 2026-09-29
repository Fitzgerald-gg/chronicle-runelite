/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle.counters;

import lombok.RequiredArgsConstructor;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.GameState;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;

@RequiredArgsConstructor
public class RangedStatTracker implements StatTracker
{
	private static final int MAX_PER_TICK = 20;
	private final StatStore store;
	private final Client client;
	private int wornAmmoId = -1;
	private int wornAmmoQty;
	private int pendingConsume;
	private int packAmmoAtTickStart;

	@Override
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getItemContainer() != client.getItemContainer(InventoryID.WORN))
		{
			return;
		}
		Item ammo = event.getItemContainer().getItem(EquipmentInventorySlot.AMMO.getSlotIdx());
		int id = ammo != null ? ammo.getId() : -1;
		int qty = ammo != null ? ammo.getQuantity() : 0;

		if (id == wornAmmoId && id != -1 && qty < wornAmmoQty)
		{
			pendingConsume += wornAmmoQty - qty;
		}
		wornAmmoId = id;
		wornAmmoQty = qty;
	}

	@Override
	public void onGameTick(GameTick event)
	{
		int packAmmoNow = wornAmmoId != -1 ? packCount(wornAmmoId) : 0;
		if (pendingConsume > 0)
		{
			int movedToPack = Math.max(0, packAmmoNow - packAmmoAtTickStart);
			int consumed = pendingConsume - movedToPack;
			if (consumed > 0 && consumed <= MAX_PER_TICK)
			{
				store.incrementStatBy("ammoConsumed", consumed);
			}
			pendingConsume = 0;
		}
		packAmmoAtTickStart = packAmmoNow;
	}

	@Override
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() != GameState.LOGGED_IN)
		{
			wornAmmoId = -1;
			wornAmmoQty = 0;
			pendingConsume = 0;
			packAmmoAtTickStart = 0;
		}
	}

	private int packCount(int itemId)
	{
		ItemContainer pack = client.getItemContainer(InventoryID.INV);
		return pack == null ? 0 : pack.count(itemId);
	}
}
