/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the BSD 2-Clause
 * License (see LICENSE) are met.
 */
package chronicle.counters;

import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
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
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;

@Slf4j
@Singleton
public class ChronicleCounters
{
	private final Client client;
	private final StatStore store;
	private final ItemManager itemManager;
	private final SkillDeriver skillDeriver;

	private volatile BiConsumer<String, Integer> consumableSink;

	private volatile GatheredLedger gatheredLedger;

	private volatile StatTracker[] trackers;

	private volatile ExperienceStatTracker experience;

	@Inject
	ChronicleCounters(Client client, StatStore store, ItemManager itemManager,
		SkillDeriver skillDeriver)
	{
		this.client = client;
		this.store = store;
		this.itemManager = itemManager;
		this.skillDeriver = skillDeriver;
	}

	public void setConsumableSink(BiConsumer<String, Integer> sink)
	{
		this.consumableSink = sink;
	}

	public void setGatheredLedger(GatheredLedger ledger)
	{
		this.gatheredLedger = ledger;
		skillDeriver.setGatheredLedger(ledger);
	}

	private StatTracker[] trackers()
	{
		StatTracker[] built = trackers;
		if (built == null)
		{
			ExperienceStatTracker xp = new ExperienceStatTracker(store);
			built = new StatTracker[]{
				new GoldStatTracker(store, client),
				new ItemStatTracker(store, client, itemManager, gatheredLedger),
				new MovementStatTracker(store, client, itemManager),
				new SkillingStatTracker(store, client, skillDeriver),
				new FoodStatTracker(store, client, itemManager, consumableSink),
				new NPCStatTracker(store),
				xp,
				new MagicStatTracker(store, client),
				new RangedStatTracker(store, client),
				new CombatStatTracker(store, client),
				new TimeStatTracker(store, client),
			};
			experience = xp;
			trackers = built;
		}
		return built;
	}

	public void reset()
	{
		trackers = null;
		experience = null;
	}

	public List<ExperienceStatTracker.SkillGain> sessionSkillXp()
	{
		ExperienceStatTracker xp = experience;
		return xp == null ? Collections.emptyList() : xp.sessionGains();
	}

	private void fanOut(Consumer<StatTracker> delivery)
	{
		for (StatTracker t : trackers())
		{
			try
			{
				delivery.accept(t);
			}
			catch (RuntimeException ex)
			{
				log.debug("{} failed on an event", t.getClass().getSimpleName(), ex);
			}
		}
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked e)
	{
		fanOut(t -> t.onMenuOptionClicked(e));
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded e)
	{
		fanOut(t -> t.onWidgetLoaded(e));
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed e)
	{
		fanOut(t -> t.onWidgetClosed(e));
	}

	@Subscribe
	public void onGameTick(GameTick e)
	{
		fanOut(t -> t.onGameTick(e));
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		fanOut(t -> t.onGameStateChanged(e));
	}

	@Subscribe
	public void onChatMessage(ChatMessage e)
	{
		fanOut(t -> t.onChatMessage(e));
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied e)
	{
		fanOut(t -> t.onHitsplatApplied(e));
	}

	@Subscribe
	public void onAnimationChanged(AnimationChanged e)
	{
		fanOut(t -> t.onAnimationChanged(e));
	}

	@Subscribe
	public void onStatChanged(StatChanged e)
	{
		fanOut(t -> t.onStatChanged(e));
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged e)
	{
		fanOut(t -> t.onItemContainerChanged(e));
	}
}
