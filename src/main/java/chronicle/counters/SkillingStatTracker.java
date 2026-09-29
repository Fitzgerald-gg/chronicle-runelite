/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle.counters;

import lombok.RequiredArgsConstructor;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ItemID;
import net.runelite.api.MenuAction;
import net.runelite.api.Skill;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.StatChanged;
import net.runelite.client.util.Text;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@RequiredArgsConstructor
public class SkillingStatTracker implements StatTracker
{
	private final StatStore statStore;
	private final Client client;
	private final SkillDeriver deriver;

	private static final Set<Skill> DERIVABLE = EnumSet.of(
		Skill.WOODCUTTING, Skill.MINING, Skill.FISHING, Skill.COOKING,
		Skill.SMITHING, Skill.FLETCHING, Skill.CRAFTING, Skill.HERBLORE,
		Skill.HUNTER, Skill.RUNECRAFT, Skill.FIREMAKING, Skill.THIEVING,
		Skill.AGILITY, Skill.PRAYER, Skill.FARMING, Skill.CONSTRUCTION,
		Skill.SAILING);
	private static final Set<MenuAction> OBJECT_OPS = EnumSet.of(MenuAction.GAME_OBJECT_FIRST_OPTION,
		MenuAction.GAME_OBJECT_SECOND_OPTION, MenuAction.GAME_OBJECT_THIRD_OPTION,
		MenuAction.GAME_OBJECT_FOURTH_OPTION, MenuAction.GAME_OBJECT_FIFTH_OPTION);
	private static final Set<MenuAction> NPC_OPS = EnumSet.of(MenuAction.NPC_FIRST_OPTION,
		MenuAction.NPC_SECOND_OPTION, MenuAction.NPC_THIRD_OPTION, MenuAction.NPC_FOURTH_OPTION,
		MenuAction.NPC_FIFTH_OPTION);

	private static final int TTL_TICKS = 6;
	private final XpSeen xpSeen = new XpSeen();
	private final EnumMap<Skill, List<Integer>> tickDrops = new EnumMap<>(Skill.class);
	private int lastObjectId = -1;
	private int objectTtl = 0;
	private String lastTargetName = "";
	private int targetTtl = 0;
	private int tickGainedItem = -1;
	private int tickGainedQty = 0;
	private int lastConsumedItem = -1;
	private int lastConsumedQty = 0;
	private int consumedTtl = 0;
	private Map<Integer, Integer> invSnapshot = null;
	private static final int RAKE_TTL_TICKS = 30;
	private static final int RAKE_MAX_PER_EVENT = 3;
	private int rakeTtl = 0;
	private String lastObjectTarget = "";

	@Override
	public void onStatChanged(StatChanged event)
	{
		int delta = DERIVABLE.contains(event.getSkill()) ? xpSeen.gain(event) : 0;
		if (delta > 0)
		{
			tickDrops.computeIfAbsent(event.getSkill(), k -> new ArrayList<>()).add(delta);
		}
	}

	@Override
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		MenuAction a = event.getMenuAction();
		if (a == MenuAction.WIDGET_TARGET_ON_GAME_OBJECT)
		{
			String used = event.getMenuTarget();
			lastObjectTarget = used == null ? "" : Text.removeTags(used);
			return;
		}
		boolean object = OBJECT_OPS.contains(a);
		if (!object && !NPC_OPS.contains(a))
		{
			return;
		}
		String opt = event.getMenuOption();
		if (opt == null)
		{
			return;
		}
		String o = opt.toLowerCase(Locale.ROOT);
		if (o.equals("examine") || o.equals("walk here") || o.equals("cancel")
			|| o.startsWith("talk") || o.equals("attack") || o.startsWith("trade")
			|| o.startsWith("follow") || o.startsWith("pay") || o.startsWith("collect"))
		{
			return;
		}
		lastTargetName = Text.removeTags(event.getMenuTarget());
		targetTtl = TTL_TICKS;
		if (object)
		{
			lastObjectTarget = lastTargetName;
		}
		if (object && (o.contains("chop") || o.contains("mine")))
		{
			lastObjectId = event.getId();
			objectTtl = TTL_TICKS;
		}
		if (object && o.equals("rake"))
		{
			rakeTtl = RAKE_TTL_TICKS;
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
		if (invSnapshot != null)
		{
			StatTracker.rises(invSnapshot, now, (id, d) ->
			{
				tickGainedItem = id;
				tickGainedQty = d;
				if (id == ItemID.WEEDS && rakeTtl > 0)
				{
					statStore.incrementStatBy("patchesRaked", Math.min(d, RAKE_MAX_PER_EVENT));
					rakeTtl = RAKE_TTL_TICKS;
				}
			});
			StatTracker.rises(now, invSnapshot, (id, d) ->
			{
				lastConsumedItem = id;
				lastConsumedQty = d;
				consumedTtl = TTL_TICKS;
			});
		}
		invSnapshot = now;
	}

	@Override
	public void onGameTick(GameTick event)
	{
		if (!tickDrops.isEmpty())
		{
			boolean gained = tickGainedItem > 0;
			boolean consumed = consumedTtl > 0 && lastConsumedItem > 0;
			String target = targetTtl > 0 ? lastTargetName : "";
			for (Map.Entry<Skill, List<Integer>> e : tickDrops.entrySet())
			{
				Skill skill = e.getKey();
				boolean useObj = (skill == Skill.WOODCUTTING || skill == Skill.MINING)
					&& objectTtl > 0 && lastObjectId > 0;
				for (int delta : e.getValue())
				{
					deriver.apply(skill.name(), delta, useObj ? lastObjectId : 0,
						gained ? tickGainedItem : 0, gained ? tickGainedQty : 1, target,
						consumed ? lastConsumedItem : 0, consumed ? lastConsumedQty : 1);
				}
			}
			tickDrops.clear();
		}
		tickGainedItem = -1;
		tickGainedQty = 0;
		objectTtl = Math.max(0, objectTtl - 1);
		targetTtl = Math.max(0, targetTtl - 1);
		consumedTtl = Math.max(0, consumedTtl - 1);
		rakeTtl = Math.max(0, rakeTtl - 1);
	}

	@Override
	public void onChatMessage(ChatMessage event)
	{
		if (StatTracker.gameChat(event))
		{
			deriver.applyChat(event.getMessage(), lastObjectTarget);
		}
	}

	@Override
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();
		if (state == GameState.LOGIN_SCREEN)
		{
			xpSeen.clear();
			invSnapshot = null;
		}
		if (state != GameState.LOGGED_IN)
		{
			tickDrops.clear();
			tickGainedItem = -1;
			tickGainedQty = 0;
			lastConsumedItem = -1;
			lastConsumedQty = 0;
			consumedTtl = 0;
			lastObjectId = -1;
			objectTtl = 0;
			lastTargetName = "";
			targetTtl = 0;
			lastObjectTarget = "";
			rakeTtl = 0;
		}
	}
}
