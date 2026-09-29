/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle.counters;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.EnumMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.StatChanged;

@RequiredArgsConstructor
public class TimeStatTracker implements StatTracker
{
	private static final int TICKS_A_MINUTE = 100;
	private static final int FIGHT_GRACE = 50;
	private static final int SKILL_GRACE = 300;
	private final StatStore store;
	private final Client client;
	private final XpSeen xpSeen = new XpSeen();
	private final Map<String, Integer> pending = new HashMap<>();
	private final Deque<XpDrop> drops = new ArrayDeque<>();
	private String lastNpc;
	private int lastNpcTick = Integer.MIN_VALUE / 2;

	@Override
	public void onStatChanged(StatChanged event)
	{
		int gain = xpSeen.gain(event);
		if (gain > 0 && !fighting())
		{
			drops.addLast(new XpDrop(client.getTickCount(), event.getSkill(), gain));
		}
	}

	private Skill leading(int now)
	{
		while (!drops.isEmpty() && now - drops.peekFirst().tick > SKILL_GRACE)
		{
			drops.removeFirst();
		}
		if (drops.isEmpty())
		{
			return null;
		}
		Map<Skill, Long> by = new EnumMap<>(Skill.class);
		Skill top = null;
		for (XpDrop d : drops)
		{
			long sum = by.merge(d.skill, (long) d.gain, Long::sum);
			if (top == null || sum > by.get(top))
			{
				top = d.skill;
			}
		}
		return top;
	}

	@RequiredArgsConstructor
	private static final class XpDrop
	{
		final int tick;
		final Skill skill;
		final int gain;
	}

	private boolean fighting()
	{
		return lastNpc != null && client.getTickCount() - lastNpcTick <= FIGHT_GRACE;
	}

	@Override
	public void onHitsplatApplied(HitsplatApplied event)
	{
		Actor on = event.getActor();
		if (on instanceof NPC && on != client.getLocalPlayer() && event.getHitsplat().isMine())
		{
			String name = on.getName();
			if (name != null && !name.isEmpty())
			{
				lastNpc = name;
				lastNpcTick = client.getTickCount();
			}
		}
	}

	@Override
	public void onGameTick(GameTick event)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		int now = client.getTickCount();
		Player me = client.getLocalPlayer();
		Actor with = me == null ? null : me.getInteracting();
		if (with instanceof NPC && fighting() && lastNpc.equals(with.getName()))
		{
			lastNpcTick = now;
		}
		Skill skill = fighting() ? null : leading(now);
		String key = fighting() ? StatKeys.timeKey(lastNpc)
			: skill != null ? StatKeys.timeKey(skill.getName()) : StatKeys.TIME_IDLE;
		int have = pending.merge(key, 1, Integer::sum);
		if (have >= TICKS_A_MINUTE)
		{
			store.incrementStat(key);
			pending.put(key, have - TICKS_A_MINUTE);
		}
	}

	@Override
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			xpSeen.clear();
			pending.clear();
			drops.clear();
			lastNpc = null;
		}
	}
}
