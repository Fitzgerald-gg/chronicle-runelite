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
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.game.ItemManager;

@RequiredArgsConstructor
public class ItemStatTracker implements StatTracker
{
	private final StatStore statStore;
	private final Client client;
	private final ItemManager itemManager;
	private final GatheredLedger gatheredLedger;

	@Override
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if ("Examine".equals(event.getMenuOption()))
		{
			statStore.incrementStat("examines");
		}
		else if ("Drop".equals(event.getMenuOption()))
		{
			statStore.incrementStat("itemsDiscarded");
			if (event.isItemOp() && event.getItemId() > 0)
			{
				recordDroppedValue(event);
			}
		}
	}

	private void recordDroppedValue(MenuOptionClicked event)
	{
		int itemId = event.getItemId();
		int qty = 1;
		ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
		Item slotItem = inv == null ? null : inv.getItem(event.getParam0());
		if (slotItem != null && slotItem.getId() == itemId)
		{
			qty = Math.max(1, slotItem.getQuantity());
		}
		int canonical = itemManager.canonicalize(itemId);
		int value = StatTracker.worth(itemManager, canonical, qty);
		if (value <= 0)
		{
			return;
		}
		statStore.incrementStatBy("itemsDroppedValue", value);
		if (gatheredLedger != null && gatheredLedger.wasGathered(canonical))
		{
			statStore.incrementStatBy("resourcesDroppedValue", value);
		}
	}

	@Override
	public void onChatMessage(ChatMessage event)
	{
		String message = event.getMessage();
		if (!StatTracker.gameChat(event) || !(message.contains("You pick a") || message.contains("You pick some")))
		{
			return;
		}
		int from = message.lastIndexOf(' ') + 1;
		int dot = message.indexOf('.', from);
		String picked = dot < 0 ? "" : message.substring(from, dot);
		if ("cabbage".equals(picked))
		{
			statStore.incrementStat("cabbagesPicked");
		}
		else if ("flax".equals(picked))
		{
			statStore.incrementStat("flaxGathered");
		}
	}
}
