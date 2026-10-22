/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import net.runelite.api.HitsplatID;
import net.runelite.api.MenuAction;
import net.runelite.api.NPC;
import net.runelite.api.Skill;
import net.runelite.api.VarPlayer;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.widgets.InterfaceID;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class TrackerTest
{
	private static final int AMMO = 13;

	private final Harness h = new Harness().login()
		.item(1515, "Yew logs", 300)
		.item(1712, "Amulet of glory(4)", 12000);

	@Test
	public void chatLinesMoveTheirCounters()
	{
		Object[][] cases = {
			{"You drink some of your prayer potion.", "potionDoses", 1},
			{"You drink some of your prayer potion.", "prayerPotionDoses", 1},
			{"You pick a cabbage.", "cabbagesPicked", 1},
			{"You accidentally burn the shark.", "sharkBurned", 1},
			{"You fail to pick the Master Farmer's pocket.", "masterFarmerFailedPickpockets", 1},
			{"Rooftop lap count: 42.", "rooftopAgilityLaps", 1},
			{"Oh dear, you are dead!", "deaths", 1},
		};
		for (Object[] c : cases)
		{
			Harness fresh = new Harness().login();
			fresh.chat((String) c[0]);
			assertEquals((String) c[0], (long) (int) c[2], fresh.tracker((String) c[1]));
		}
	}

	@Test
	public void eatingCountsTheFoodAndWhatItCost()
	{
		h.pack(385, 2).click("Eat", "Shark", 385).pack(385, 1).tick();
		h.pack(385, 0).tick();
		assertEquals(1, h.tracker("foodEaten"));
		assertEquals(1, h.tracker("sharkEaten"));
		assertEquals(900, h.tracker("foodConsumedValue"));
		assertEquals(900, h.journal().getAsJsonObject("consumable_values").get("sharkEaten").getAsInt());
	}

	@Test
	public void aDoseIsPricedOffThePotionThatLeftThePack()
	{
		h.pack(2434, 1).chat("You drink some of your prayer potion.").pack(139, 1).tick();
		assertEquals(2000, h.tracker("potionsConsumedValue"));
	}

	@Test
	public void hitpointsCreepingUpWithoutFoodIsRegeneration()
	{
		h.hp(20).tick().hp(21).tick().hp(22).tick();
		assertEquals(2, h.tracker("hitpointsRegenerated"));
	}

	@Test
	public void stepsAreWalkedOrRunAndAJumpIsATeleport()
	{
		h.varp(VarPlayerID.OPTION_RUN, 0).tick();
		h.walk(1, 0).walk(0, 1).walk(2, 0);
		assertEquals(2, h.tracker("distanceWalked"));
		assertEquals(2, h.tracker("distanceRan"));

		h.click("Edgeville", "Amulet of glory(4)", 1712).teleport(new WorldPoint(3087, 3496, 0));
		h.click("Cast", "Varrock Teleport", 0).teleport(new WorldPoint(3212, 3424, 0));
		h.click("Cast", "Varrock Teleport", 0).click("Walk here", "", 0).teleport(new WorldPoint(3087, 3496, 0));
		h.animate(3265, -1);
		assertEquals(3, h.tracker("teleportsTotal"));
		assertEquals(1, h.tracker("teleportsEdgeville"));
		assertEquals(1, h.tracker("teleportsViaJewellery"));
		assertEquals(1, h.tracker("teleportsVarrock"));
		assertEquals(1, h.tracker("teleportsFairyRing"));
	}

	@Test
	public void alchemyAndOffensiveCastsAreCounted()
	{
		h.pack(995, 100).animate(713, -1).pack(995, 1100).tick();
		h.animate(-1, -1).pack(995, 1500).tick();
		h.animate(711, -1).animate(711, -1).tick().animate(711, -1);
		assertEquals(1000, h.tracker("coinsFromAlchemy"));
		assertEquals(2, h.tracker("offensiveSpellsCast"));
	}

	@Test
	public void ammoLeavingTheQuiverIsSpentUnlessItLandedInThePack()
	{
		h.pack().worn(AMMO, 892, 100).tick();
		h.worn(AMMO, 892, 99).tick().worn(AMMO, 892, 97).tick();
		h.worn(AMMO, 892, 96).pack(892, 1).tick();
		h.worn(AMMO, 892, 0).tick();
		assertEquals(3, h.tracker("ammoConsumed"));
	}

	@Test
	public void hitsAreDealtTakenBlockedAndTheHighestKept()
	{
		NPC goblin = h.npc("Goblin", 3029, new WorldPoint(3201, 3200, 0), 1);
		h.xp(Skill.ATTACK, 1).xp(Skill.ATTACK, 40);
		h.hit(goblin, HitsplatID.DAMAGE_ME, 12).hit(goblin, HitsplatID.DAMAGE_ME, 30).hit(goblin, HitsplatID.BLOCK_ME, 0);
		h.hit(null, HitsplatID.DAMAGE_ME, 7).hit(null, HitsplatID.BLOCK_ME, 0).hit(null, HitsplatID.POISON, 4);
		h.varp(VarPlayer.SPECIAL_ATTACK_PERCENT, 1000).tick().varp(VarPlayer.SPECIAL_ATTACK_PERCENT, 500).tick();
		assertEquals(42, h.tracker("damageDealt"));
		assertEquals(42, h.tracker("damageDealtMelee"));
		assertEquals(30, h.tracker("highestHit"));
		assertEquals(1, h.tracker("hitsMissed"));
		assertEquals(7, h.tracker("damageTaken"));
		assertEquals(1, h.tracker("hitsBlocked"));
		assertEquals(4, h.tracker("poisonDamageTaken"));
		assertEquals(1, h.tracker("specialAttacksUsed"));
	}

	@Test
	public void aMinuteBelongsToTheFightOrTheSkillThatOwnedIt()
	{
		NPC vorkath = h.npc("Vorkath", 8061, new WorldPoint(3205, 3200, 0), 7);
		h.hit(vorkath, HitsplatID.DAMAGE_ME, 10).fight(vorkath).ticks(100);
		h.fight(null).ticks(60).xp(Skill.FISHING, 1).xp(Skill.FISHING, 50).ticks(100);
		assertEquals(1, h.tracker("timeVorkath"));
		assertEquals(1, h.tracker("timeFishing"));
	}

	@Test
	public void coinsChangingAtAShopAreSpendingOrEarning()
	{
		h.pack(995, 1000).widget(InterfaceID.SHOP_INVENTORY).tick();
		h.pack(995, 400).tick().pack(995, 450).tick();
		assertEquals(600, h.tracker("coinsSpentAtShops"));
		assertEquals(50, h.tracker("coinsEarnedAtShops"));
	}

	@Test
	public void droppedItemsAreValuedAndGatheredOnesCountedApart()
	{
		h.click("Examine", "Goblin", 0).click("Pet", "Cat", 0).ticks(2);
		h.pack(1515, 0).click("Chop down", "Yew tree", 0, MenuAction.GAME_OBJECT_FIRST_OPTION, 0, 0)
			.pack(1515, 1).xp(Skill.WOODCUTTING, 1).xp(Skill.WOODCUTTING, 175).tick();
		h.click("Drop", "Yew logs", 1515, MenuAction.CC_OP, 0, InventoryID.INV).click("Drop", "Bones", 526);
		assertEquals(1, h.tracker("examines"));
		assertEquals(1, h.tracker("animalsPetted"));
		assertEquals(2, h.tracker("itemsDiscarded"));
		assertEquals(400, h.tracker("itemsDroppedValue"));
		assertEquals(300, h.tracker("resourcesDroppedValue"));
		assertEquals(1, h.tracker("yewLogsChopped"));
		assertEquals(175, h.tracker("totalXpGained"));
	}

	@Test
	public void theSkillingDeriverTypesAnXpDropByWhatMovedWithIt()
	{
		h.pack(1517, 1).xp(Skill.FLETCHING, 1).pack(52, 60).xp(Skill.FLETCHING, 20).tick();
		h.pack(1623, 1).xp(Skill.CRAFTING, 1).pack(1607, 1).xp(Skill.CRAFTING, 50).tick();
		h.xp(Skill.AGILITY, 1).xp(Skill.AGILITY, 625).tick();
		h.click("Pickpocket", "Master Farmer", 0, MenuAction.NPC_THIRD_OPTION, 0, 0)
			.xp(Skill.THIEVING, 1).xp(Skill.THIEVING, 43).tick();
		assertEquals(1, h.tracker("mapleLogsFletched"));
		assertEquals(60, h.tracker("arrowShaftsFletched"));
		assertEquals(1, h.tracker("gemsCut"));
		assertEquals(1, h.tracker("ardougneLaps"));
		assertEquals(1, h.tracker("masterFarmerPickpockets"));
	}
}
