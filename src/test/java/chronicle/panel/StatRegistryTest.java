/*
 * Copyright (c) 2026, Chronicle — BSD 2-Clause (see LICENSE).
 */
package chronicle.panel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/** Which family and section a counter key files under, and what it is called. */
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
		// destinations read as place names, punctuation and all
		assertEquals("Varrock", StatRegistry.label("teleportsVarrock"));
		assertEquals("Seers' Village", StatRegistry.label("teleportsSeersVillage"));
		assertEquals("Kourend (Memoirs)", StatRegistry.label("teleportsKharedst"));
	}

	@Test
	public void summaryKeysLiveOffTheStatsTab()
	{
		// spine-only totals: named and priced for the History summary, hidden from
		// every Stats family so a journal carrying them lists nothing extra
		for (String key : StatRegistry.summaryKeys())
		{
			assertTrue(key, StatRegistry.isSummary(key));
			assertTrue(key, StatRegistry.hidden(key));
		}
		assertEquals("Drops received", StatRegistry.label("dropsReceived"));
		assertEquals("Loot value", StatRegistry.label("lootValue"));
		assertEquals("Slayer tasks completed", StatRegistry.label("slayerTasksCompleted"));
		assertEquals("Collection log slots", StatRegistry.label("clogSlotsObtained"));
		assertEquals("Left on the floor", StatRegistry.label("lootLeftCount"));
		assertEquals("Value left on the floor", StatRegistry.label("lootLeftValue"));
		assertEquals("Kills", StatRegistry.label("kills"));
		// derived on the History tab, named here all the same
		assertEquals("Loot kept", StatRegistry.label("lootKept"));
		assertTrue(StatRegistry.isGp("lootValue"));
		assertTrue(StatRegistry.isGp("lootLeftValue"));
		assertFalse(StatRegistry.isGp("dropsReceived"));
		assertFalse(StatRegistry.isGp("lootLeftCount"));
		assertFalse(StatRegistry.isGp("kills"));
		assertFalse(StatRegistry.isSummary("damageDealt"));
		// the spine pair is not the imported lifetime pair
		assertFalse(StatRegistry.isSummary("untakenLootCount"));
		assertFalse(StatRegistry.isSummary("untakenLootValue"));
	}

	@Test
	public void aTypedRowCanSpellItsVerb()
	{
		// where a list holds more than one verb the bare row labels repeat, so
		// the verb goes back on, lower-cased after the name
		assertEquals("Shark cooked", StatRegistry.rowLabelWithVerb("sharkCooked"));
		assertEquals("Shark burned", StatRegistry.rowLabelWithVerb("sharkBurned"));
		assertEquals("Abyssal heads reanimated", StatRegistry.rowLabelWithVerb("abyssalHeadsReanimated"));
		assertEquals("Guard failed pickpockets", StatRegistry.rowLabelWithVerb("guardFailedPickpockets"));
		assertEquals("Iron ore mined", StatRegistry.rowLabelWithVerb("ironOreMined"));
		assertEquals("Ranarr planted", StatRegistry.rowLabelWithVerb("ranarrPlanted"));
		// a key with no verb keeps its row label
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
		// a key no rule claims still shows up, under odds & ends
		assertEquals("Ledger & Roads", StatRegistry.family("clueScrollsCompleted"));
		assertEquals("Odds & ends", StatRegistry.subgroup("clueScrollsCompleted"));
	}

	@Test
	public void skillOwnershipClaimsBeforeSuffixes()
	{
		// Hunter's explicit claim beats Fishing's broad "Caught" suffix
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
		// a floor is the generic total; it heads its section instead of listing as a row
		assertTrue(StatRegistry.isFloor("logsChopped"));
		assertTrue(StatRegistry.isFloor("bonesBuried"));
		assertTrue(StatRegistry.isFloor("teleportsTotal"));
		assertFalse(StatRegistry.isFloor("willowLogsChopped"));
		assertFalse(StatRegistry.isFloor("foodEaten"));   // flat Living row
		// typed rows reconcile against the floor; explicit extras do not
		assertTrue(StatRegistry.typed("willowLogsChopped"));
		assertTrue(StatRegistry.typed("sharkEaten"));
		assertFalse(StatRegistry.typed("foodBurned"));
		assertFalse(StatRegistry.typed("implingsCaught"));
	}

	@Test
	public void typedRowsShedTheirVerb()
	{
		// the section header carries the craft. The row keeps just the item
		assertEquals("Willow", StatRegistry.rowLabel("willowLogsChopped"));
		assertEquals("Wyrm", StatRegistry.rowLabel("wyrmBonesBuried"));
		assertEquals("Shark", StatRegistry.rowLabel("sharkEaten"));
		assertEquals("Wrath", StatRegistry.rowLabel("wrathRunecrafted"));
		// explicit keys keep their full label
		assertEquals("Herbs cleaned", StatRegistry.rowLabel("herbsCleaned"));
	}

	@Test
	public void theTwoPetCountersFileWithTheirCraft()
	{
		// essence is a Runecraft row, not an "Odds & ends" leftover, and it is
		// claimed by name so the Runecrafted floor arithmetic never sees it
		assertEquals("Runecraft", StatRegistry.skillOf("essenceCrafted"));
		assertEquals("Skilling", StatRegistry.family("essenceCrafted"));
		assertEquals("Runecraft", StatRegistry.subgroup("essenceCrafted"));
		assertEquals("Essence crafted", StatRegistry.label("essenceCrafted"));
		assertFalse(StatRegistry.typed("essenceCrafted"));
		assertFalse(StatRegistry.isFloor("essenceCrafted"));

		// a planted crop is a typed Farming row that sheds its verb like the rest
		assertEquals("Farming", StatRegistry.skillOf("potatoPlanted"));
		assertEquals("Skilling", StatRegistry.family("potatoPlanted"));
		assertEquals("Farming", StatRegistry.subgroup("potatoPlanted"));
		assertTrue(StatRegistry.typed("potatoPlanted"));
		assertEquals("Planted", StatRegistry.suffixOf("potatoPlanted"));
		assertEquals("Potato", StatRegistry.rowLabel("potatoPlanted"));
		assertEquals("Bittercap mushroom", StatRegistry.rowLabel("bittercapMushroomPlanted"));
		// and the aggregate it was added beside is untouched
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
		// a key with no home falls to "Odds & ends"; none of these may
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

		// the herb sack: a Herblore floor under typed herb rows
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
		// the unfinished potion stays a named Herblore row
		assertEquals("Herblore", StatRegistry.skillOf("unfinishedPotionsMade"));
		assertFalse(StatRegistry.typed("unfinishedPotionsMade"));
		assertEquals("Unfinished potions made", StatRegistry.rowLabel("unfinishedPotionsMade"));

		// the tanner: a Crafting floor under typed hide rows
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
		// and the armour keys it sits beside are untouched
		assertEquals("Crafting", StatRegistry.skillOf("dhideCrafted"));
		assertFalse(StatRegistry.typed("dhideCrafted"));

		// the Vampyrium pair are named Woodcutting rows
		assertEquals("Woodcutting", StatRegistry.skillOf("letveksShooed"));
		assertEquals("Woodcutting", StatRegistry.skillOf("bloodwoodSapBucketsFilled"));
		assertEquals("Letveks shooed", StatRegistry.rowLabel("letveksShooed"));
		assertEquals("Bloodwood sap buckets filled",
			StatRegistry.rowLabel("bloodwoodSapBucketsFilled"));
		assertFalse(StatRegistry.typed("letveksShooed"));
		assertFalse(StatRegistry.isFloor("bloodwoodSapBucketsFilled"));

		// Tempoross is a named Fishing row
		assertEquals("Fishing", StatRegistry.skillOf("spiritPoolsHarpooned"));
		assertEquals("Skilling", StatRegistry.family("spiritPoolsHarpooned"));
		assertEquals("Spirit pools harpooned", StatRegistry.rowLabel("spiritPoolsHarpooned"));
		assertFalse(StatRegistry.typed("spiritPoolsHarpooned"));

		// thralls: Combat's one fold, a floor with typed rows that shed the suffix
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
		// the flat Combat rows did not move
		assertEquals("", StatRegistry.subgroup("damageDealt"));
	}

	@Test
	public void labelsPolish()
	{
		// keys like logsLogsChopped stutter; polish collapses the doubled word
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
		// the burns reconcile against their own floor, as the cooked rows do
		assertTrue(StatRegistry.isFloor("foodBurned"));
		assertFalse(StatRegistry.typed("foodBurned"));
		assertEquals("foodBurned", StatRegistry.suffixFloor("Cooking", "Burned"));
		assertEquals("Food burned", StatRegistry.label("foodBurned"));
		assertEquals("Burned", StatRegistry.suffixLabel("Burned"));
		assertEquals(java.util.Arrays.asList("foodCooked", "foodBurned"),
			StatRegistry.floorKeys("Cooking"));
		// and a burnt log is still Firemaking's
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
