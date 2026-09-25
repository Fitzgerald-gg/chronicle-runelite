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
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Skill;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.StatChanged;
import net.runelite.client.game.ItemManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;

/**
 * The tick that carries a craft to the deriver. What left the pack is half the
 * story; how much of it left is the other half, and an altar eats a whole pack
 * of essence on one click.
 */
public class SkillingStatTrackerTest
{
	private static final int PURE_ESSENCE = 7936;
	private static final int GUARDIAN_ESSENCE = 26879;
	private static final int NATURE_RUNE = 561;

	private final Map<Integer, String> names = new HashMap<>();
	private StatStore store;
	private ItemContainer pack;
	private SkillingStatTracker tracker;
	private int career;

	@Before
	public void setUp()
	{
		names.put(PURE_ESSENCE, "Pure essence");
		names.put(GUARDIAN_ESSENCE, "Guardian essence");
		names.put(NATURE_RUNE, "Nature rune");

		store = new StatStore();
		Client client = Mockito.mock(Client.class);
		pack = Mockito.mock(ItemContainer.class);
		Mockito.when(client.getItemContainer(InventoryID.INVENTORY)).thenReturn(pack);

		ItemManager items = Mockito.mock(ItemManager.class);
		Mockito.when(items.canonicalize(Mockito.anyInt())).thenAnswer(inv -> inv.getArgument(0));
		Mockito.when(items.getItemComposition(Mockito.anyInt())).thenAnswer(inv ->
		{
			ItemComposition c = Mockito.mock(ItemComposition.class);
			Mockito.when(c.getName()).thenReturn(names.getOrDefault(
				(Integer) inv.getArgument(0), ""));
			return c;
		});

		career = 1_000_000;
		tracker = new SkillingStatTracker(store, client, new SkillDeriver(items, store));
	}

	private void holds(Item... contents)
	{
		Mockito.when(pack.getItems()).thenReturn(contents);
		tracker.onItemContainerChanged(new ItemContainerChanged(InventoryID.INVENTORY.getId(), pack));
	}

	// the drop is a career total, so the tracker needs a reading to measure from
	private void xp(int delta)
	{
		tracker.onStatChanged(new StatChanged(Skill.RUNECRAFT, career, 1, 1));
		career += delta;
		tracker.onStatChanged(new StatChanged(Skill.RUNECRAFT, career, 1, 1));
	}

	@Test
	public void theEssenceThatLeftThePackIsWhatTheCraftIsCountedIn()
	{
		holds(new Item(PURE_ESSENCE, 27));
		xp(243);
		// 27 essence in, 54 nature runes out
		holds(new Item(NATURE_RUNE, 54));
		tracker.onGameTick(new GameTick());

		assertEquals(54, store.getStat("runesCrafted"));
		assertEquals(54, store.getStat("natureRunecrafted"));
		assertEquals(27, store.getStat("essenceCrafted"));
	}

	@Test
	public void theRiftsOwnEssenceIsCarriedButNeverCounted()
	{
		holds(new Item(GUARDIAN_ESSENCE, 25));
		xp(50);
		holds(new Item(NATURE_RUNE, 50));
		tracker.onGameTick(new GameTick());

		assertEquals(50, store.getStat("runesCrafted"));
		assertEquals(0, store.getStat("essenceCrafted"));
	}

	@Test
	public void aCraftWithNothingSeenLeavingThePackCountsNoEssence()
	{
		// no inventory reading at all: the tuple's consumed fields go out empty
		xp(243);
		tracker.onGameTick(new GameTick());

		assertEquals(1, store.getStat("runesCrafted"));
		assertEquals(0, store.getStat("essenceCrafted"));
	}

	private void chat(String line)
	{
		tracker.onChatMessage(new ChatMessage(null, ChatMessageType.GAMEMESSAGE, "", line, null, 0));
	}

	private void click(MenuAction action, String option, String target)
	{
		MenuEntry entry = Mockito.mock(MenuEntry.class);
		Mockito.when(entry.getType()).thenReturn(action);
		Mockito.when(entry.getOption()).thenReturn(option);
		Mockito.when(entry.getTarget()).thenReturn(target);
		tracker.onMenuOptionClicked(new MenuOptionClicked(entry));
	}

	@Test
	public void theChatGateLetsEachCountedLineThrough()
	{
		// the deriver only sees what the prefix list lets past
		chat("You put the grimy guam leaf herb into your herb sack.");
		assertEquals(1, store.getStat("herbsSacked"));
		assertEquals(1, store.getStat("guamLeafSacked"));
		chat("You gently shoo the letvek away.");
		assertEquals(1, store.getStat("letveksShooed"));
		chat("The tanner tans 27 cowhides for you.");
		assertEquals(27, store.getStat("hidesTanned"));
		assertEquals(27, store.getStat("cowhideTanned"));
		chat("You put the Guam leaf into the vial of water.");
		assertEquals(1, store.getStat("unfinishedPotionsMade"));
		chat("You resurrect a greater skeletal thrall.");
		assertEquals(1, store.getStat("thrallsSummoned"));
		assertEquals(1, store.getStat("greaterSkeletalThrallsSummoned"));
		chat("The glowing fish scatter, shedding their magical scales.");
		assertEquals(1, store.getStat("spiritPoolsHarpooned"));
		// a channel the game never says these on stays shut
		tracker.onChatMessage(new ChatMessage(null, ChatMessageType.PUBLICCHAT, "someone",
			"You resurrect a greater skeletal thrall.", null, 0));
		assertEquals(1, store.getStat("thrallsSummoned"));
	}

	@Test
	public void sapIsCreditedToTheTreeTheLastObjectClickNamed()
	{
		String line = "You fill the bucket with sap.";
		// no tree clicked yet: the line could be an evergreen's
		chat(line);
		assertEquals(0, store.getStat("bloodwoodSapBucketsFilled"));

		click(MenuAction.GAME_OBJECT_FIRST_OPTION, "Chop", "<col=ffff>Bloodwood tree");
		chat(line);
		assertEquals(1, store.getStat("bloodwoodSapBucketsFilled"));
		// a tap is one click for many buckets: the memory outlives the target TTL
		for (int i = 0; i < 20; i++)
		{
			tracker.onGameTick(new GameTick());
		}
		chat(line);
		assertEquals(2, store.getStat("bloodwoodSapBucketsFilled"));

		// the knife on an evergreen is an item used on an object, and it names
		// the evergreen: the next bucket is not bloodwood's
		click(MenuAction.WIDGET_TARGET_ON_GAME_OBJECT, "Use",
			"<col=ff9040>Knife</col><col=ffffff> -> <col=ffff>Evergreen");
		chat(line);
		assertEquals(2, store.getStat("bloodwoodSapBucketsFilled"));

		click(MenuAction.GAME_OBJECT_FIRST_OPTION, "Chop", "Engorged bloodwood tree");
		chat(line);
		assertEquals(3, store.getStat("bloodwoodSapBucketsFilled"));
		// an NPC click is not a change of tree
		click(MenuAction.NPC_FIRST_OPTION, "Pickpocket", "Guard");
		chat(line);
		assertEquals(4, store.getStat("bloodwoodSapBucketsFilled"));

		// a logout forgets the tree with everything else click-local
		GameStateChanged out = new GameStateChanged();
		out.setGameState(GameState.LOGIN_SCREEN);
		tracker.onGameStateChanged(out);
		chat(line);
		assertEquals(4, store.getStat("bloodwoodSapBucketsFilled"));
	}
}
