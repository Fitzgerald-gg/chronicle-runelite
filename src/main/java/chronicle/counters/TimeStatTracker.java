/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle.counters;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
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

/**
 * Files each minute of a sitting under the one activity that owned it.
 *
 * <p>Every tick goes to exactly one of: the NPC the player is fighting, held
 * for a short grace after the last hit so the walk between kills stays with
 * the boss; else the skill of the last xp drop, held for a longer grace so a
 * slow craft keeps its minutes between drops; else idle. A hundred ticks is a
 * minute, and a minute is written as a counter (timeVorkath, timeFishing,
 * timeIdle), which is what lets the spine carry it and any period answer
 * "how long here". Nothing here is a rate: the pages divide.
 */
public class TimeStatTracker implements StatTracker
{
	static final int TICKS_A_MINUTE = 100;
	/** After the last hit or the last look at a monster, how long it still owns the tick. */
	static final int FIGHT_GRACE = 50;
	/** After the last xp drop, how long the skill still owns the tick. */
	static final int SKILL_GRACE = 300;

	private final StatStore store;
	private final Client client;
	private final Map<Skill, Integer> xpSeen = new EnumMap<>(Skill.class);
	private final Map<String, Integer> pending = new HashMap<>();
	private Skill lastSkill;
	private int lastSkillTick = Integer.MIN_VALUE / 2;
	private String lastNpc;
	private int lastNpcTick = Integer.MIN_VALUE / 2;

	public TimeStatTracker(StatStore store, Client client)
	{
		this.store = store;
		this.client = client;
	}

	@Override
	public void onStatChanged(StatChanged event)
	{
		Skill skill = event.getSkill();
		if (skill == null)
		{
			return;
		}
		Integer prev = xpSeen.put(skill, event.getXp());
		// the first reading of a skill is the career total, not a drop
		if (prev == null || event.getXp() <= prev)
		{
			return;
		}
		// A fight's own xp does not make it a craft. Attack and Hitpoints drop
		// on every hit, and seeding the skill branch with them filed the walk
		// after the fight under Attack and then rated the fight's xp over it.
		if (fighting())
		{
			return;
		}
		lastSkill = skill;
		lastSkillTick = client.getTickCount();
	}

	/** Whether a fight still owns the tick. */
	private boolean fighting()
	{
		return lastNpc != null && client.getTickCount() - lastNpcTick <= FIGHT_GRACE;
	}

	/**
	 * A hit I dealt is the one thing that starts a fight.
	 *
	 * <p>Interaction alone is not: a fishing spot, a banker, a pickpocket
	 * target and an impling are all NPCs, and letting any of them claim the
	 * tick filed an hour of fishing under the shoal rather than under Fishing.
	 */
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
		// Still on the thing last hit: the fight goes on through a phase nobody
		// is landing damage in, but it cannot begin from standing near one.
		Player me = client.getLocalPlayer();
		Actor with = me == null ? null : me.getInteracting();
		if (with instanceof NPC && lastNpc != null && lastNpc.equals(with.getName())
			&& now - lastNpcTick <= FIGHT_GRACE)
		{
			lastNpcTick = now;
		}
		String key;
		if (lastNpc != null && now - lastNpcTick <= FIGHT_GRACE)
		{
			key = StatKeys.timeKey(lastNpc);
		}
		else if (lastSkill != null && now - lastSkillTick <= SKILL_GRACE)
		{
			key = StatKeys.timeKey(lastSkill.getName());
		}
		else
		{
			key = StatKeys.TIME_IDLE;
		}
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
		// LOGIN_SCREEN only, as the xp tracker: LOADING and HOPPING keep the
		// same character, and the minutes in hand belong to the sitting.
		if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			xpSeen.clear();
			pending.clear();
			lastSkill = null;
			lastNpc = null;
		}
	}
}
