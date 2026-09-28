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
		final String option = event.getMenuOption();
		if ("Examine".equals(option))
		{
			statStore.incrementStat("examines");
		}
		else if ("Drop".equals(option))
		{
			statStore.incrementStat("itemsDiscarded");
			recordDroppedValue(event);
		}
	}

	private void recordDroppedValue(MenuOptionClicked event)
	{
		if (!event.isItemOp())
		{
			return;
		}
		final int itemId = event.getItemId();
		if (itemId <= 0)
		{
			return;
		}
		int qty = 1;
		final ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
		if (inv != null)
		{
			final Item slotItem = inv.getItem(event.getParam0());
			if (slotItem != null && slotItem.getId() == itemId)
			{
				qty = Math.max(1, slotItem.getQuantity());
			}
		}
		final int canonical = itemManager.canonicalize(itemId);
		final long each = itemManager.getItemPrice(canonical);
		if (each <= 0)
		{
			return;
		}
		final long value = each * qty;
		final int banked = value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
		statStore.incrementStatBy("itemsDroppedValue", banked);
		if (gatheredLedger != null && gatheredLedger.wasGathered(canonical))
		{
			statStore.incrementStatBy("resourcesDroppedValue", banked);
		}
	}

	@Override
	public void onChatMessage(ChatMessage event)
	{
		switch (event.getType())
		{
			case SPAM:
			case GAMEMESSAGE:
			case MESBOX:
				break;
			default:
				return;
		}

		final String message = event.getMessage();

		if (message.contains("You pick a") || message.contains("You pick some"))
		{
			final int from = message.lastIndexOf(' ') + 1;
			final int dot = message.indexOf('.', from);
			if (dot < 0)
			{
				return;
			}
			final String picked = message.substring(from, dot);
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
}
