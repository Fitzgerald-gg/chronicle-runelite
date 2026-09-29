/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle.counters;

import java.util.Set;
import lombok.RequiredArgsConstructor;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.HitsplatID;
import net.runelite.api.Skill;
import net.runelite.api.VarPlayer;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.StatChanged;

import static chronicle.counters.StatKeys.DAMAGE_DEALT;
import static chronicle.counters.StatKeys.HIGHEST_HIT;

@RequiredArgsConstructor
public class CombatStatTracker implements StatTracker
{
	private static final Set<Integer> DAMAGE_SPLATS = Set.of(
		HitsplatID.DAMAGE_ME, HitsplatID.DAMAGE_ME_CYAN, HitsplatID.DAMAGE_ME_ORANGE,
		HitsplatID.DAMAGE_ME_YELLOW, HitsplatID.DAMAGE_ME_WHITE,
		HitsplatID.DAMAGE_MAX_ME, HitsplatID.DAMAGE_MAX_ME_CYAN, HitsplatID.DAMAGE_MAX_ME_ORANGE,
		HitsplatID.DAMAGE_MAX_ME_YELLOW, HitsplatID.DAMAGE_MAX_ME_WHITE);

	private final StatStore store;
	private final Client client;
	private int prevSpecEnergy = -1;
	private String lastStyleKey;
	private int lastStyleTick = -1;

	@Override
	public void onGameTick(GameTick tick)
	{
		int cur = client.getVarpValue(VarPlayer.SPECIAL_ATTACK_PERCENT);
		if (prevSpecEnergy >= 0 && cur < prevSpecEnergy)
		{
			store.incrementStat("specialAttacksUsed");
		}
		prevSpecEnergy = cur;
	}

	@Override
	public void onGameStateChanged(GameStateChanged e)
	{
		if (e.getGameState() == GameState.LOGGED_IN || e.getGameState() == GameState.LOGIN_SCREEN)
		{
			prevSpecEnergy = -1;
		}
	}

	@Override
	public void onHitsplatApplied(HitsplatApplied event)
	{
		int type = event.getHitsplat().getHitsplatType();
		int amount = event.getHitsplat().getAmount();
		boolean onSelf = event.getActor() == client.getLocalPlayer();
		if (onSelf)
		{
			recordDamageToSelf(type, amount);
		}
		else if (DAMAGE_SPLATS.contains(type))
		{
			recordDamageDealt(event.getActor(), amount);
		}
		if (type == HitsplatID.DAMAGE_ME && onSelf)
		{
			store.incrementStatBy("damageTaken", amount);
		}
		else if (type == HitsplatID.BLOCK_ME)
		{
			store.incrementStat(onSelf ? "hitsBlocked" : "hitsMissed");
		}
	}

	private void recordDamageToSelf(int type, int amount)
	{
		if (type == HitsplatID.POISON)
		{
			store.incrementStatBy("poisonDamageTaken", amount);
		}
		else if (type == HitsplatID.VENOM)
		{
			store.incrementStatBy("venomDamageTaken", amount);
		}
		if (DAMAGE_SPLATS.contains(type) && amount > store.getStat("highestHitTaken"))
		{
			store.setStat("highestHitTaken", amount);
		}
	}

	@Override
	public void onChatMessage(ChatMessage event)
	{
		if (StatTracker.gameChat(event) && event.getMessage().contains("Oh dear, you are dead!"))
		{
			store.incrementStat("deaths");
		}
	}

	@Override
	public void onStatChanged(StatChanged e)
	{
		Skill sk = e.getSkill();
		String style = sk == Skill.ATTACK || sk == Skill.STRENGTH ? "damageDealtMelee"
			: sk == Skill.RANGED ? "damageDealtRanged"
			: sk == Skill.MAGIC ? "damageDealtMagic" : null;
		if (style != null)
		{
			lastStyleKey = style;
			lastStyleTick = client.getTickCount();
		}
	}

	private void recordDamageDealt(Actor target, int amount)
	{
		store.incrementStatBy(DAMAGE_DEALT, amount);
		if (lastStyleKey != null && client.getTickCount() - lastStyleTick <= 2)
		{
			store.incrementStatBy(lastStyleKey, amount);
		}
		if (amount > store.getStat(HIGHEST_HIT) && target != null && target.getCombatLevel() > 0)
		{
			store.setStat(HIGHEST_HIT, amount);
		}
	}
}
