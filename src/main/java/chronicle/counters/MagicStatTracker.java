/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the BSD 2-Clause
 * License (see LICENSE) are met.
 */
package chronicle.counters;

import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ItemID;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;

@RequiredArgsConstructor
public class MagicStatTracker implements StatTracker
{
	private static final int HIGH_ALCH_ANIM = 713;
	private static final int LOW_ALCH_ANIM = 712;

	private static final int OFFERING_CAST_ANIM = 8975;
	private static final int GFX_DEMONIC = 1871;
	private static final int GFX_SINISTER = 1872;
	private static final Map<Integer, String> OFFERING_GFX = Map.of(
		GFX_DEMONIC, "demonicOfferingsCast",
		GFX_SINISTER, "sinisterOfferingsCast");
	private static final Map<Integer, String> OFFERING_SAC = Map.of(
		GFX_DEMONIC, "ashesSacrificed",
		GFX_SINISTER, "bonesSacrificed");
	private static final Set<Integer> OFFERING_RUNES = Set.of(565, 566, 21880);

	private static final Set<Integer> OFFENSIVE_CAST_ANIMS = Set.of(
		711,
		1162,
		727,
		1167,
		7855,
		1978,
		1979,
		8977,
		811,
		708,
		1576,
		724);

	private final StatStore store;
	private final Client client;

	private int lastCoins = -1;
	private int bufferedCoinGain;
	private int alchSeenTick = -1;
	private int lastCastAnim = -1;
	private int lastCastTick = -1;
	private Map<Integer, Integer> invSnap = null;
	private int sacrificeDecThisTick = 0;
	private int offeringCastTick = -1;
	private int prevPrayerXp = -1;
	private int offeringXpThisTick = 0;

	@Override
	public void onAnimationChanged(AnimationChanged event)
	{
		Player me = client.getLocalPlayer();
		if (me == null || event.getActor() != me)
		{
			return;
		}
		int anim = me.getAnimation();
		if (anim == HIGH_ALCH_ANIM || anim == LOW_ALCH_ANIM)
		{
			alchSeenTick = client.getTickCount();
			return;
		}
		if (OFFENSIVE_CAST_ANIMS.contains(anim))
		{
			int tick = client.getTickCount();
			if (anim == lastCastAnim && tick == lastCastTick)
			{
				return;
			}
			lastCastAnim = anim;
			lastCastTick = tick;
			store.incrementStat("offensiveSpellsCast");
			return;
		}
		if (anim == OFFERING_CAST_ANIM)
		{
			offeringCastTick = client.getTickCount();
		}
	}

	@Override
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		Map<Integer, Integer> now = StatTracker.inventory(client, event);
		if (now == null)
		{
			return;
		}
		if (invSnap != null)
		{
			for (Map.Entry<Integer, Integer> e : invSnap.entrySet())
			{
				int dec = e.getValue() - now.getOrDefault(e.getKey(), 0);
				if (dec > 0 && !OFFERING_RUNES.contains(e.getKey()))
				{
					sacrificeDecThisTick += dec;
				}
			}
		}
		invSnap = now;
		int coins = event.getItemContainer().count(ItemID.COINS_995);
		if (lastCoins < 0)
		{
			lastCoins = coins;
			return;
		}
		int gain = coins - lastCoins;
		lastCoins = coins;
		if (gain <= 0)
		{
			return;
		}
		if (isAlchActiveNow())
		{
			store.incrementStatBy("coinsFromAlchemy", gain);
		}
		else
		{
			bufferedCoinGain += gain;
		}
	}

	@Override
	public void onGameTick(GameTick event)
	{
		if (offeringCastTick == client.getTickCount())
		{
			int gfx = activeOfferingColour();
			String castKey = gfx > 0 ? OFFERING_GFX.get(gfx) : null;
			if (castKey != null)
			{
				store.incrementStat(castKey);
				if (sacrificeDecThisTick > 0)
				{
					store.incrementStatBy(OFFERING_SAC.get(gfx), sacrificeDecThisTick);
				}
				if (offeringXpThisTick > 0)
				{
					store.incrementStatBy(
						castKey.equals("demonicOfferingsCast") ? "demonicOfferingXp" : "sinisterOfferingXp",
						offeringXpThisTick);
				}
			}
		}
		sacrificeDecThisTick = 0;
		offeringXpThisTick = 0;
		offeringCastTick = -1;
		if (bufferedCoinGain > 0)
		{
			if (alchSeenTick == client.getTickCount())
			{
				store.incrementStatBy("coinsFromAlchemy", bufferedCoinGain);
			}
			bufferedCoinGain = 0;
		}
	}

	@Override
	public void onStatChanged(StatChanged event)
	{
		if (event.getSkill() != Skill.PRAYER)
		{
			return;
		}
		int xp = event.getXp();
		if (prevPrayerXp < 0)
		{
			prevPrayerXp = xp;
			return;
		}
		int delta = xp - prevPrayerXp;
		prevPrayerXp = xp;
		if (delta > 0)
		{
			offeringXpThisTick += delta;
		}
	}

	@Override
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() != GameState.LOGGED_IN)
		{
			lastCoins = -1;
			bufferedCoinGain = 0;
			prevPrayerXp = -1;
			invSnap = null;
			sacrificeDecThisTick = 0;
			offeringXpThisTick = 0;
			offeringCastTick = -1;
		}
	}

	private int activeOfferingColour()
	{
		Player me = client.getLocalPlayer();
		if (me == null)
		{
			return -1;
		}
		int g = me.getGraphic();
		if (OFFERING_GFX.containsKey(g))
		{
			return g;
		}
		if (me.hasSpotAnim(GFX_DEMONIC))
		{
			return GFX_DEMONIC;
		}
		if (me.hasSpotAnim(GFX_SINISTER))
		{
			return GFX_SINISTER;
		}
		return -1;
	}

	private boolean isAlchActiveNow()
	{
		if (alchSeenTick == client.getTickCount())
		{
			return true;
		}
		Player me = client.getLocalPlayer();
		if (me == null)
		{
			return false;
		}
		int anim = me.getAnimation();
		return anim == HIGH_ALCH_ANIM || anim == LOW_ALCH_ANIM;
	}
}
