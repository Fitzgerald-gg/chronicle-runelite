/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle.panel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Set;
import org.junit.Test;

public class StatRegistryTest
{
	@Test
	public void prettifyReadsWell()
	{
		assertEquals("Tiles walked", StatRegistry.label("tilesWalked"));
		assertEquals("Vials shattered", StatRegistry.prettify("vialsShattered"));
		assertEquals("Chompy birds plucked", StatRegistry.prettify("chompyBirdsPlucked"));
	}

	@Test
	public void teleportFamiliesLabelAsAnnotations()
	{
		assertEquals("· by jewellery", StatRegistry.label("teleportsViaJewellery"));
		assertEquals("Teleports", StatRegistry.label("teleportsTotal"));
		assertEquals("Varrock", StatRegistry.label("teleportsVarrock"));
		assertEquals("Seers' Village", StatRegistry.label("teleportsSeersVillage"));
	}

	@SuppressWarnings("unchecked")
	public static Set<String> summaryKeys() throws Exception
	{
		Field f = StatRegistry.class.getDeclaredField("SUMMARY");
		f.setAccessible(true);
		return Collections.unmodifiableSet((Set<String>) f.get(null));
	}

	@Test
	public void summaryKeysLiveOffTheStatsTab() throws Exception
	{
		for (String key : summaryKeys())
		{
			assertTrue(key, StatRegistry.hidden(key));
		}
	}

	@Test
	public void summaryAndDerivedKeysAreNamed() throws Exception
	{
		assertEquals("Drops received", StatRegistry.label("dropsReceived"));
		assertEquals("Loot value", StatRegistry.label("lootValue"));
		assertEquals("Slayer tasks completed", StatRegistry.label("slayerTasksCompleted"));
		assertEquals("Slayer kills", StatRegistry.label("slayerKills"));
		assertFalse(StatRegistry.isGp("slayerKills"));
		assertEquals("Pets", StatRegistry.label("petsObtained"));
		assertEquals("Quests completed", StatRegistry.label("questsCompleted"));
		assertEquals("Diaries completed", StatRegistry.label("diariesCompleted"));
		assertEquals("Combat achievements", StatRegistry.label("combatAchievements"));
		assertEquals("Levels gained", StatRegistry.label("levelsGained"));
		for (String key : new String[]{"petsObtained", "questsCompleted", "diariesCompleted",
			"combatAchievements", "levelsGained"})
		{
			assertFalse(key, StatRegistry.isGp(key));
		}
		assertEquals("Collection log slots", StatRegistry.label("clogSlotsObtained"));
		assertEquals("Left on the floor", StatRegistry.label("lootLeftCount"));
		assertEquals("Value left on the floor", StatRegistry.label("lootLeftValue"));
		assertEquals("Kills", StatRegistry.label("kills"));
		assertEquals("Kills that left loot", StatRegistry.label("lootLeftKills"));
		assertTrue(summaryKeys().contains("lootLeftKills"));
		assertFalse(StatRegistry.isGp("lootLeftKills"));
		assertEquals("Loot kept", StatRegistry.label("lootKept"));
		assertEquals("Drops taken", StatRegistry.label("dropsTaken"));
		assertFalse(StatRegistry.isGp("dropsTaken"));
		assertFalse(summaryKeys().contains("dropsTaken"));
		assertTrue(StatRegistry.isGp("lootValue"));
		assertTrue(StatRegistry.isGp("lootLeftValue"));
		assertFalse(StatRegistry.isGp("dropsReceived"));
		assertFalse(StatRegistry.isGp("lootLeftCount"));
		assertFalse(StatRegistry.isGp("kills"));
		assertFalse(summaryKeys().contains("damageDealt"));
		assertFalse(summaryKeys().contains("untakenLootCount"));
		assertFalse(summaryKeys().contains("untakenLootValue"));
	}

	@Test
	public void aTypedRowCanSpellItsVerb()
	{
		assertEquals("Shark cooked", StatRegistry.rowLabelWithVerb("sharkCooked"));
		assertEquals("Shark burned", StatRegistry.rowLabelWithVerb("sharkBurned"));
		assertEquals("Abyssal heads reanimated", StatRegistry.rowLabelWithVerb("abyssalHeadsReanimated"));
		assertEquals("Guard failed pickpockets", StatRegistry.rowLabelWithVerb("guardFailedPickpockets"));
		assertEquals("Iron ore mined", StatRegistry.rowLabelWithVerb("ironOreMined"));
		assertEquals("Ranarr planted", StatRegistry.rowLabelWithVerb("ranarrPlanted"));
		assertEquals("Herbs cleaned", StatRegistry.rowLabelWithVerb("herbsCleaned"));
		assertEquals("Lesser ghostly", StatRegistry.rowLabelWithVerb("lesserGhostlyThrallsSummoned"));
		assertEquals("Shark", StatRegistry.rowLabelWithVerb("sharkEaten"));
	}

	@Test
	public void facetsMatchTheSite()
	{
		assertEquals("Combat", StatRegistry.family("damageDealt"));
		assertEquals("Living", StatRegistry.family("hitpointsRegenerated"));
		assertEquals("Living", StatRegistry.family("divinePotionDamage"));
		assertEquals("Living", StatRegistry.family("foodEaten"));
		assertEquals("Living", StatRegistry.family("sharkEaten"));
		assertEquals("Ledger & Roads", StatRegistry.family("ammoConsumed"));
		assertEquals("Ledger & Roads", StatRegistry.family("distanceWalked"));
		assertEquals("Ledger & Roads", StatRegistry.family("teleportsVarrock"));
		assertEquals("Ledger & Roads", StatRegistry.family("coinsFromAlchemy"));
		assertEquals("Ledger & Roads", StatRegistry.family("untakenLootCount"));
		assertEquals("Skilling", StatRegistry.family("willowLogsChopped"));
		assertEquals("Skilling", StatRegistry.family("bonesBuried"));
		assertEquals("Skilling", StatRegistry.family("wyrmBonesBuried"));
		assertEquals("Skilling", StatRegistry.family("creaturesTrapped"));
		assertEquals("Ledger & Roads", StatRegistry.family("clueScrollsCompleted"));
		assertEquals("Odds & ends", StatRegistry.subgroup("clueScrollsCompleted"));
	}

	@Test
	public void skillOwnershipClaimsBeforeSuffixes()
	{
		assertEquals("Hunter", StatRegistry.skillOf("implingsCaught"));
		assertEquals("Fishing", StatRegistry.skillOf("anglerfishCaught"));
		assertEquals("Cooking", StatRegistry.skillOf("foodBurned"));
		assertEquals("Firemaking", StatRegistry.skillOf("willowLogsBurned"));
		assertEquals("Thieving", StatRegistry.skillOf("guardPickpockets"));
		assertEquals("Thieving", StatRegistry.skillOf("guardFailedPickpockets"));
		assertEquals("Prayer", StatRegistry.skillOf("trollHeadsReanimated"));
		assertEquals("Runecraft", StatRegistry.skillOf("wrathRunecrafted"));
		assertEquals("Smithing", StatRegistry.skillOf("steelBarsSmelted"));
	}

	@Test
	public void floorsHeadSectionsNotRows()
	{
		assertTrue(StatRegistry.isFloor("logsChopped"));
		assertTrue(StatRegistry.isFloor("bonesBuried"));
		assertTrue(StatRegistry.isFloor("teleportsTotal"));
		assertFalse(StatRegistry.isFloor("willowLogsChopped"));
		assertFalse(StatRegistry.isFloor("foodEaten"));
		assertTrue(StatRegistry.typed("willowLogsChopped"));
		assertTrue(StatRegistry.typed("sharkEaten"));
		assertFalse(StatRegistry.typed("foodBurned"));
		assertFalse(StatRegistry.typed("implingsCaught"));
	}

	@Test
	public void typedRowsShedTheirVerb()
	{
		assertEquals("Willow", StatRegistry.rowLabel("willowLogsChopped"));
		assertEquals("Wyrm", StatRegistry.rowLabel("wyrmBonesBuried"));
		assertEquals("Shark", StatRegistry.rowLabel("sharkEaten"));
		assertEquals("Wrath", StatRegistry.rowLabel("wrathRunecrafted"));
		assertEquals("Herbs cleaned", StatRegistry.rowLabel("herbsCleaned"));
	}

	@Test
	public void theTwoPetCountersFileWithTheirCraft()
	{
		assertEquals("Runecraft", StatRegistry.skillOf("essenceCrafted"));
		assertEquals("Skilling", StatRegistry.family("essenceCrafted"));
		assertEquals("Runecraft", StatRegistry.subgroup("essenceCrafted"));
		assertEquals("Essence crafted", StatRegistry.label("essenceCrafted"));
		assertFalse(StatRegistry.typed("essenceCrafted"));
		assertFalse(StatRegistry.isFloor("essenceCrafted"));

		assertEquals("Farming", StatRegistry.skillOf("potatoPlanted"));
		assertEquals("Skilling", StatRegistry.family("potatoPlanted"));
		assertEquals("Farming", StatRegistry.subgroup("potatoPlanted"));
		assertTrue(StatRegistry.typed("potatoPlanted"));
		assertEquals("Planted", StatRegistry.suffixOf("potatoPlanted"));
		assertEquals("Potato", StatRegistry.rowLabel("potatoPlanted"));
		assertEquals("Bittercap mushroom", StatRegistry.rowLabel("bittercapMushroomPlanted"));
		assertEquals("Farming", StatRegistry.skillOf("seedsPlanted"));
		assertFalse(StatRegistry.typed("seedsPlanted"));
		assertNull(StatRegistry.suffixOf("seedsPlanted"));
		assertEquals("Seeds planted", StatRegistry.rowLabel("seedsPlanted"));
	}

	@Test
	public void sailingFilesUnderSkilling()
	{
		assertEquals("Sailing", StatRegistry.skillOf("salvagePulled"));
		assertEquals("Sailing", StatRegistry.skillOf("opulentSalvageSorted"));
		assertEquals("Sailing", StatRegistry.skillOf("gwenithGlideTrialsCompleted"));
		assertEquals("Sailing", StatRegistry.skillOf("portTasksCompleted"));
		assertEquals("Skilling", StatRegistry.family("smallSalvagePulled"));
		assertEquals("Sailing", StatRegistry.subgroup("smallSalvagePulled"));
		assertTrue(StatRegistry.isFloor("salvagePulled"));
		assertTrue(StatRegistry.isFloor("barracudaTrialsCompleted"));
		assertFalse(StatRegistry.isFloor("temporTantrumTrialsCompleted"));
		assertEquals("barracudaTrialsCompleted",
			StatRegistry.suffixFloor("Sailing", "TrialsCompleted"));
		assertEquals("salvageSorted", StatRegistry.suffixFloor("Sailing", "SalvageSorted"));
		assertEquals("Fremennik", StatRegistry.rowLabel("fremennikSalvagePulled"));
		assertEquals("Tempor tantrum", StatRegistry.rowLabel("temporTantrumTrialsCompleted"));
		assertEquals("Port tasks", StatRegistry.rowLabel("portTasksCompleted"));
	}

	@Test
	public void theChatCountedKeysHaveHomes()
	{
		String[] all = {"herbsSacked", "guamLeafSacked", "ranarrWeedSacked",
			"letveksShooed", "bloodwoodSapBucketsFilled", "thrallsSummoned",
			"lesserGhostlyThrallsSummoned", "greaterZombifiedThrallsSummoned",
			"hidesTanned", "cowhideTanned", "greenDragonhideTanned",
			"unfinishedPotionsMade", "spiritPoolsHarpooned"};
		for (String key : all)
		{
			assertFalse(key, StatRegistry.hidden(key));
			assertFalse(key, StatRegistry.family(key).equals("Ledger & Roads"));
			assertFalse(key, StatRegistry.subgroup(key).equals("Odds & ends"));
			assertFalse(key, StatRegistry.subgroup(key).isEmpty());
		}

		assertEquals("Herblore", StatRegistry.skillOf("herbsSacked"));
		assertTrue(StatRegistry.isFloor("herbsSacked"));
		assertEquals("Herbs sacked", StatRegistry.label("herbsSacked"));
		assertEquals("Herblore", StatRegistry.subgroup("guamLeafSacked"));
		assertTrue(StatRegistry.typed("guamLeafSacked"));
		assertEquals("Sacked", StatRegistry.suffixOf("guamLeafSacked"));
		assertEquals("Guam leaf", StatRegistry.rowLabel("guamLeafSacked"));
		assertEquals("Ranarr weed", StatRegistry.rowLabel("ranarrWeedSacked"));
		assertEquals("herbsSacked", StatRegistry.suffixFloor("Herblore", "Sacked"));
		assertEquals("Herbs sacked", StatRegistry.suffixLabel("Sacked"));
		assertEquals("Herblore", StatRegistry.skillOf("unfinishedPotionsMade"));
		assertFalse(StatRegistry.typed("unfinishedPotionsMade"));
		assertEquals("Unfinished potions made", StatRegistry.rowLabel("unfinishedPotionsMade"));

		assertEquals("Crafting", StatRegistry.skillOf("hidesTanned"));
		assertTrue(StatRegistry.isFloor("hidesTanned"));
		assertEquals("Hides tanned", StatRegistry.label("hidesTanned"));
		assertEquals("Crafting", StatRegistry.subgroup("cowhideTanned"));
		assertTrue(StatRegistry.typed("cowhideTanned"));
		assertEquals("Tanned", StatRegistry.suffixOf("greenDragonhideTanned"));
		assertEquals("Cowhide", StatRegistry.rowLabel("cowhideTanned"));
		assertEquals("Green dragonhide", StatRegistry.rowLabel("greenDragonhideTanned"));
		assertEquals("hidesTanned", StatRegistry.suffixFloor("Crafting", "Tanned"));
		assertEquals("Hides tanned", StatRegistry.suffixLabel("Tanned"));
		assertEquals("Crafting", StatRegistry.skillOf("dhideCrafted"));
		assertFalse(StatRegistry.typed("dhideCrafted"));

		assertEquals("Woodcutting", StatRegistry.skillOf("letveksShooed"));
		assertEquals("Woodcutting", StatRegistry.skillOf("bloodwoodSapBucketsFilled"));
		assertEquals("Letveks shooed", StatRegistry.rowLabel("letveksShooed"));
		assertEquals("Bloodwood sap buckets filled",
			StatRegistry.rowLabel("bloodwoodSapBucketsFilled"));
		assertFalse(StatRegistry.typed("letveksShooed"));
		assertFalse(StatRegistry.isFloor("bloodwoodSapBucketsFilled"));

		assertEquals("Fishing", StatRegistry.skillOf("spiritPoolsHarpooned"));
		assertEquals("Skilling", StatRegistry.family("spiritPoolsHarpooned"));
		assertEquals("Spirit pools harpooned", StatRegistry.rowLabel("spiritPoolsHarpooned"));
		assertFalse(StatRegistry.typed("spiritPoolsHarpooned"));

		assertEquals("Combat", StatRegistry.family("thrallsSummoned"));
		assertEquals("Combat", StatRegistry.family("lesserGhostlyThrallsSummoned"));
		assertEquals("Thralls", StatRegistry.subgroup("thrallsSummoned"));
		assertEquals("Thralls", StatRegistry.subgroup("greaterZombifiedThrallsSummoned"));
		assertTrue(StatRegistry.isFloor("thrallsSummoned"));
		assertFalse(StatRegistry.isFloor("lesserGhostlyThrallsSummoned"));
		assertTrue(StatRegistry.typed("lesserGhostlyThrallsSummoned"));
		assertFalse(StatRegistry.typed("thrallsSummoned"));
		assertNull(StatRegistry.skillOf("lesserGhostlyThrallsSummoned"));
		assertEquals("Thralls raised", StatRegistry.label("thrallsSummoned"));
		assertEquals("Lesser ghostly", StatRegistry.rowLabel("lesserGhostlyThrallsSummoned"));
		assertEquals("Greater zombified", StatRegistry.rowLabel("greaterZombifiedThrallsSummoned"));
		assertEquals(java.util.Collections.singletonList("thrallsSummoned"),
			StatRegistry.floorKeys("Thralls"));
		assertEquals(java.util.Arrays.asList("", "Thralls"), StatRegistry.fixedSections("Combat"));
		assertEquals("", StatRegistry.subgroup("damageDealt"));
	}

	@Test
	public void labelsPolish()
	{
		assertEquals("Logs chopped", StatRegistry.prettify("logsLogsChopped"));
		assertEquals("Guard (lvl 21) pickpockets",
			StatRegistry.prettify("guard(level21)Pickpockets"));
	}

	@Test
	public void hiddenKeys()
	{
		assertTrue(StatRegistry.hidden("__probe"));
		assertTrue(StatRegistry.hidden("totalXpGained"));
		assertTrue(StatRegistry.hidden("demonicOfferingXp"));
		assertFalse(StatRegistry.hidden("damageDealt"));
	}

	@Test
	public void gpKeysDetected()
	{
		assertTrue(StatRegistry.isGp("itemsDroppedValue"));
		assertFalse(StatRegistry.isGp("damageDealt"));
		assertEquals("The purse", StatRegistry.subgroup("itemsDroppedValue"));
	}

	@Test
	public void burnedFoodFilesUnderCookingBesideTheCookedRows()
	{
		String[] burns = {"sharkBurned", "moonlightAntelopeBurned", "cakeBurned",
			"karambwanjiBurned"};
		for (String key : burns)
		{
			assertFalse(key, StatRegistry.hidden(key));
			assertEquals(key, "Skilling", StatRegistry.family(key));
			assertEquals(key, "Cooking", StatRegistry.skillOf(key));
			assertEquals(key, "Cooking", StatRegistry.subgroup(key));
			assertTrue(key, StatRegistry.typed(key));
			assertEquals(key, "Burned", StatRegistry.suffixOf(key));
		}
		assertEquals("Shark", StatRegistry.rowLabel("sharkBurned"));
		assertEquals("Moonlight antelope", StatRegistry.rowLabel("moonlightAntelopeBurned"));
		assertEquals("Cake", StatRegistry.rowLabel("cakeBurned"));
		assertEquals("Karambwanji", StatRegistry.rowLabel("karambwanjiBurned"));
		assertTrue(StatRegistry.isFloor("foodBurned"));
		assertFalse(StatRegistry.typed("foodBurned"));
		assertEquals("foodBurned", StatRegistry.suffixFloor("Cooking", "Burned"));
		assertEquals("Food burned", StatRegistry.label("foodBurned"));
		assertEquals("Burned", StatRegistry.suffixLabel("Burned"));
		assertEquals(java.util.Arrays.asList("foodCooked", "foodBurned"),
			StatRegistry.floorKeys("Cooking"));
		assertEquals("Firemaking", StatRegistry.skillOf("willowLogsBurned"));
		assertEquals("LogsBurned", StatRegistry.suffixOf("willowLogsBurned"));
		assertEquals("Firemaking", StatRegistry.subgroup("logsBurned"));
	}

	@Test
	public void theRetypedKeysHaveHomes()
	{
		String[] all = {"leapingTroutCaught", "leapingSalmonCaught", "leapingSturgeonCaught",
			"sacredEelCaught", "normalLogsChopped", "guardPickpockets"};
		for (String key : all)
		{
			assertFalse(key, StatRegistry.hidden(key));
			assertEquals(key, "Skilling", StatRegistry.family(key));
			assertFalse(key, StatRegistry.subgroup(key).equals("Odds & ends"));
			assertTrue(key, StatRegistry.typed(key));
		}
		assertEquals("Fishing", StatRegistry.skillOf("leapingTroutCaught"));
		assertEquals("Leaping sturgeon", StatRegistry.rowLabel("leapingSturgeonCaught"));
		assertEquals("Sacred eel", StatRegistry.rowLabel("sacredEelCaught"));
		assertEquals("Woodcutting", StatRegistry.skillOf("normalLogsChopped"));
		assertEquals("Normal", StatRegistry.rowLabel("normalLogsChopped"));
		assertEquals("Thieving", StatRegistry.skillOf("guardPickpockets"));
		assertEquals("Guard", StatRegistry.rowLabel("guardPickpockets"));
	}
}
