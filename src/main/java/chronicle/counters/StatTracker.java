/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the BSD 2-Clause
 * License (see LICENSE) are met.
 */
package chronicle.counters;

import java.util.HashMap;
import java.util.Map;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;

/**
 * A lifetime-counter tracker. {@link ChronicleCounters} hands every subscribed event to
 * each tracker, so a tracker overrides only the handlers it needs and the rest no-op here.
 * Tallies go into the shared {@link StatStore}.
 */
public interface StatTracker
{
	default void onMenuOptionClicked(MenuOptionClicked event) {}

	default void onWidgetLoaded(WidgetLoaded event) {}

	default void onWidgetClosed(WidgetClosed event) {}

	default void onGameTick(GameTick event) {}

	default void onGameStateChanged(GameStateChanged event) {}

	default void onChatMessage(ChatMessage event) {}

	default void onHitsplatApplied(HitsplatApplied event) {}

	default void onAnimationChanged(AnimationChanged event) {}

	default void onStatChanged(StatChanged event) {}

	default void onItemContainerChanged(ItemContainerChanged event) {}

	// the three channels the game's own lines arrive on
	static boolean gameChat(ChatMessage event)
	{
		ChatMessageType type = event.getType();
		return type == ChatMessageType.SPAM
			|| type == ChatMessageType.GAMEMESSAGE
			|| type == ChatMessageType.MESBOX;
	}

	// the pack as item id to quantity, or null when the change is to another container
	static Map<Integer, Integer> inventory(Client client, ItemContainerChanged event)
	{
		if (event.getItemContainer() != client.getItemContainer(InventoryID.INVENTORY))
		{
			return null;
		}
		Map<Integer, Integer> now = new HashMap<>();
		for (Item it : event.getItemContainer().getItems())
		{
			if (it != null && it.getId() >= 0)
			{
				now.merge(it.getId(), it.getQuantity(), Integer::sum);
			}
		}
		return now;
	}
}
