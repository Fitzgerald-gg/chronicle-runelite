/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the BSD 2-Clause
 * License (see LICENSE) are met.
 */
package chronicle.counters;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.Skill;
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
import net.runelite.client.game.ItemManager;

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

	static boolean gameChat(ChatMessage event)
	{
		ChatMessageType type = event.getType();
		return type == ChatMessageType.SPAM
			|| type == ChatMessageType.GAMEMESSAGE
			|| type == ChatMessageType.MESBOX;
	}

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

	// walks `to`, handing on each item it holds more of than `from`
	static void rises(Map<Integer, Integer> from, Map<Integer, Integer> to, BiConsumer<Integer, Integer> each)
	{
		for (Map.Entry<Integer, Integer> e : to.entrySet())
		{
			int d = e.getValue() - from.getOrDefault(e.getKey(), 0);
			if (d > 0)
			{
				each.accept(e.getKey(), d);
			}
		}
	}

	static int worth(ItemManager items, int canonicalId, int qty)
	{
		long each = items.getItemPrice(canonicalId);
		return each <= 0 ? 0 : (int) Math.min(each * qty, Integer.MAX_VALUE);
	}

	final class XpSeen
	{
		private final Map<Skill, Integer> seen = new EnumMap<>(Skill.class);

		// 0 on a skill's first reading, which only sets the baseline
		int gain(StatChanged event)
		{
			if (event.getSkill() == null)
			{
				return 0;
			}
			Integer prev = seen.put(event.getSkill(), event.getXp());
			return prev == null ? 0 : event.getXp() - prev;
		}

		void clear()
		{
			seen.clear();
		}
	}
}
