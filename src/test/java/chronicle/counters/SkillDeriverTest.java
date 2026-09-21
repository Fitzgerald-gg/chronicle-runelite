/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle.counters;

import com.google.gson.Gson;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.ItemComposition;
import net.runelite.client.game.ItemManager;
import org.junit.Test;
import org.mockito.Mockito;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Tuple fields: skill|xp|objId|itemId|qty|target|consumedId|consumedQty. */
public class SkillDeriverTest
{
	private final Map<Integer, String> names = new HashMap<>();
	// GE price the mocked ItemManager reports; a test moves it mid-run
	private final Map<Integer, Integer> prices = new HashMap<>();
	private final Set<Integer> gathered = new HashSet<>();
	private final SkillDeriver d;

	public SkillDeriverTest()
	{
		ItemManager im = Mockito.mock(ItemManager.class);
		Mockito.when(im.canonicalize(Mockito.anyInt()))
			.thenAnswer(inv -> inv.getArgument(0));
		Mockito.when(im.getItemPrice(Mockito.anyInt()))
			.thenAnswer(inv -> prices.getOrDefault((Integer) inv.getArgument(0), 0));
		Mockito.when(im.getItemComposition(Mockito.anyInt())).thenAnswer(inv ->
		{
			ItemComposition c = Mockito.mock(ItemComposition.class);
			Mockito.when(c.getName()).thenReturn(names.getOrDefault(
				(Integer) inv.getArgument(0), ""));
			return c;
		});
		d = new SkillDeriver(im, new StatStore(), new Gson());
		d.setGatheredLedger(new GatheredLedger()
		{
			@Override
			public void noteGathered(int itemId)
			{
				gathered.add(itemId);
			}

			@Override
			public boolean wasGathered(int itemId)
			{
				return gathered.contains(itemId);
			}
		});
	}

	private Map<String, Integer> derive(String tuple)
	{
		List<Map.Entry<String, Integer>> pairs = d.derive(tuple);
		if (pairs == null)
		{
			return null;
		}
		Map<String, Integer> out = new HashMap<>();
		for (Map.Entry<String, Integer> p : pairs)
		{
			out.merge(p.getKey(), p.getValue(), Integer::sum);
		}
		return out;
	}

	@Test
	public void arrowShaftsTypeByConsumedLog()
	{
		names.put(52, "Arrow shaft");
		names.put(1517, "Maple logs");
		Map<String, Integer> got = derive("FLETCHING|20||52|60||1517");
		assertEquals((Integer) 1, got.get("logsFletched"));
		assertEquals((Integer) 1, got.get("mapleLogsFletched"));
		assertEquals((Integer) 60, got.get("arrowShaftsFletched"));
	}

	@Test
	public void headlessArrows()
	{
		names.put(53, "Headless arrow");
		assertEquals((Integer) 15, derive("FLETCHING|15||53|15||").get("headlessArrowsFletched"));
	}

	@Test
	public void gemsCutIncludingFailures()
	{
		names.put(1623, "Sapphire");
		assertEquals((Integer) 1, derive("CRAFTING|50||1623|1||").get("gemsCut"));
		names.put(1633, "Crushed gem");
		assertEquals((Integer) 1, derive("CRAFTING|1||1633|1||").get("gemsCut"));
	}

	@Test
	public void prayerVerbsSplitByRatio()
	{
		names.put(90, "Abyssal ashes");
		assertEquals((Integer) 1, derive("PRAYER|85|||||90").get("abyssalAshesScattered"));
		assertEquals((Integer) 1, derive("PRAYER|255|||||90").get("abyssalAshesSacrificed"));
		assertEquals((Integer) 2, derive("PRAYER|510|||||90").get("abyssalAshesSacrificed"));
		names.put(536, "Dragon bones");
		assertEquals((Integer) 1, derive("PRAYER|72|||||536").get("dragonBonesBuried"));
		assertEquals((Integer) 1, derive("PRAYER|216|||||536").get("dragonBonesSacrificed"));
		assertEquals((Integer) 1, derive("PRAYER|252|||||536").get("bonesOffered"));
	}

	@Test
	public void ensouledReanimations()
	{
		assertEquals((Integer) 1, derive("PRAYER|1300|||||").get("abyssalHeadsReanimated"));
		names.put(13508, "Ensouled abyssal head");
		assertEquals((Integer) 1, derive("PRAYER|1300|||||13508").get("abyssalHeadsReanimated"));
	}

	@Test
	public void agilityLaps()
	{
		Map<String, Integer> got = derive("AGILITY|625|||||");
		assertEquals((Integer) 1, got.get("agilityObstacles"));
		assertEquals((Integer) 1, got.get("ardougneLaps"));
		assertNull(derive("AGILITY|39|||||").get("ardougneLaps"));
	}

	@Test
	public void hunterSpeciesAndBirdhouses()
	{
		names.put(10033, "Chinchompa");
		assertEquals((Integer) 1, derive("HUNTER|198||10033|1||").get("greyChinchompasTrapped"));
		names.put(53, "Feather");
		assertEquals((Integer) 1, derive("HUNTER|612||53|30||").get("yewBirdhousesEmptied"));
		assertEquals((Integer) 1, derive("HUNTER|1950|||||").get("herbiboarsHarvested"));
	}

	@Test
	public void thievingClassifier()
	{
		assertEquals((Integer) 1, derive("THIEVING|43||||Master Farmer|").get("masterFarmerPickpockets"));
		assertEquals((Integer) 1, derive("THIEVING|16||||Tea stall|").get("teaStallsThieved"));
		assertEquals((Integer) 1, derive("THIEVING|675|||||").get("pyramidPlunderUrns"));
	}

	@Test
	public void smithingPerMetalAndCannonballs()
	{
		names.put(2353, "Steel bar");
		assertEquals((Integer) 1, derive("SMITHING|17||2353|1||").get("steelBarsSmelted"));
		names.put(2, "Cannonball");
		assertEquals((Integer) 4, derive("SMITHING|25||2|4||").get("cannonballsSmithed"));
	}

	@Test
	public void gatheringTypedByItem()
	{
		names.put(1515, "Yew logs");
		Map<String, Integer> got = derive("WOODCUTTING|175||1515|1||");
		assertEquals((Integer) 1, got.get("logsChopped"));
		assertEquals((Integer) 1, got.get("yewLogsChopped"));
		names.put(21622, "Volcanic ash");
		assertEquals((Integer) 6, derive("MINING|10||21622|6||").get("volcanicAshMined"));
		names.put(1625, "Uncut opal");
		assertEquals((Integer) 1, derive("MINING|65||1625|1||").get("gemRockMined"));
	}

	@Test
	public void salvageRidesTheItemNotTheXp()
	{
		names.put(32847, "Small salvage");
		Map<String, Integer> pulled = derive("SAILING|10||32847|1||");
		assertEquals((Integer) 1, pulled.get("salvagePulled"));
		assertEquals((Integer) 1, pulled.get("smallSalvagePulled"));
		// opulent salvage is 200 xp flat, 205 under a keg of horizons lure
		names.put(32861, "Opulent salvage");
		assertEquals((Integer) 1, derive("SAILING|205||32861|1||").get("opulentSalvagePulled"));
		// sorting consumes the salvage; the consumed id names the row
		names.put(1625, "Uncut opal");
		Map<String, Integer> sorted = derive("SAILING|95||1625|1||32861");
		assertEquals((Integer) 1, sorted.get("salvageSorted"));
		assertEquals((Integer) 1, sorted.get("opulentSalvageSorted"));
		assertNull(sorted.get("salvagePulled"));
	}

	@Test
	public void barracudaTrialsRideTheBareCompletionLump()
	{
		Map<String, Integer> marlin = derive("SAILING|1250|||||");
		assertEquals((Integer) 1, marlin.get("barracudaTrialsCompleted"));
		assertEquals((Integer) 1, marlin.get("temporTantrumTrialsCompleted"));
		assertEquals((Integer) 1, derive("SAILING|6200|||||").get("jubblyJiveTrialsCompleted"));
		assertEquals((Integer) 1, derive("SAILING|16050|||||").get("gwenithGlideTrialsCompleted"));
		// 150 is a lost teak crate or a medium casket as often as a Tempor
		// Tantrum. Off the ladder.
		assertNull(derive("SAILING|150|||||"));
		// courier deliveries pay 385 too; only the coin bag separates them
		names.put(32950, "Medium port coin bag");
		Map<String, Integer> port = derive("SAILING|385||32950|1||");
		assertEquals((Integer) 1, port.get("portTasksCompleted"));
		assertNull(port.get("barracudaTrialsCompleted"));
		assertEquals((Integer) 1, derive("SAILING|385|||||").get("temporTantrumTrialsCompleted"));
		// trawling, sail trimming and cannon fire drop bare xp with no completion behind it
		assertNull(derive("SAILING|9|||||"));
	}

	@Test
	public void chatChannelResiduals()
	{
		StatStore store = new StatStore();
		ItemManager im = Mockito.mock(ItemManager.class);
		SkillDeriver cd = new SkillDeriver(im, store, new Gson());
		cd.applyChat("You fail to pick the Master Farmer's pocket.");
		assertEquals(1, store.getStat("failedPickPockets"));
		assertEquals(1, store.getStat("masterFarmerFailedPickpockets"));
		cd.applyChat("You accidentally burn the shark.");
		assertEquals(1, store.getStat("foodBurned"));
		cd.applyChat("You plant 3 potato seeds.");
		assertEquals(1, store.getStat("seedsPlanted"));
		cd.applyChat("Rooftop lap count: 42.");
		assertEquals(1, store.getStat("rooftopAgilityLaps"));
		cd.applyChat("Your Ardougne lap count is: 100.");
		assertEquals(1, store.getStat("normalAgilityLaps"));
	}

	@Test
	public void essenceIsCountedApartFromTheRunesItMakes()
	{
		names.put(7936, "Pure essence");
		names.put(561, "Nature rune");
		// 27 essence, two nature runes apiece: the altar was asked 27 times
		Map<String, Integer> got = derive("RUNECRAFT|243||561|54||7936|27");
		assertEquals((Integer) 54, got.get("runesCrafted"));
		assertEquals((Integer) 54, got.get("natureRunecrafted"));
		assertEquals((Integer) 27, got.get("essenceCrafted"));
		// rune essence is the same craft and shares the counter
		names.put(1436, "Rune essence");
		names.put(556, "Air rune");
		assertEquals((Integer) 14, derive("RUNECRAFT|70||556|28||1436|14").get("essenceCrafted"));
		// blood runes come off fragments, one rune to a fragment
		names.put(7938, "Dark essence fragments");
		names.put(565, "Blood rune");
		assertEquals((Integer) 1, derive("RUNECRAFT|24||565|1||7938|1").get("essenceCrafted"));
		// a craft whose rune never resolved still counts the essence behind it
		assertEquals((Integer) 5, derive("RUNECRAFT|25|||||7936|5").get("essenceCrafted"));
		// a tuple from before the count existed reads as the one item it meant
		assertEquals((Integer) 1, derive("RUNECRAFT|5||556|1||7936").get("essenceCrafted"));
	}

	@Test
	public void theRiftMinigameAndTheDarkAltarAreNotCrafts()
	{
		// Guardians of the Rift altars take guardian essence and roll no pet
		names.put(26879, "Guardian essence");
		names.put(556, "Air rune");
		Map<String, Integer> got = derive("RUNECRAFT|50||556|50||26879|25");
		assertEquals((Integer) 50, got.get("runesCrafted"));
		assertNull(got.get("essenceCrafted"));
		// venerating dense essence eats essence and makes no rune at all
		names.put(13445, "Dense essence block");
		names.put(13446, "Dark essence block");
		assertNull(derive("RUNECRAFT|2||13446|1||13445|1"));
		// nor does a tiara, though the talisman leaves the pack on the way
		names.put(5525, "Air tiara");
		names.put(1438, "Air talisman");
		assertNull(derive("RUNECRAFT|25||5525|1||1438|1"));
	}

	@Test
	public void plantingIsCountedByThePatchAndByTheCrop()
	{
		StatStore store = new StatStore();
		SkillDeriver cd = chatDeriver(store);
		// three seeds go in, but the patch was planted once
		cd.applyChat("You plant 3 potato seeds in the allotment.");
		assertEquals(1, store.getStat("seedsPlanted"));
		assertEquals(1, store.getStat("potatoPlanted"));
		cd.applyChat("You plant a guam seed in the herb patch.");
		assertEquals(1, store.getStat("guamPlanted"));
		// saplings and spores name their crop the same way
		cd.applyChat("You plant an oak sapling in the tree patch.");
		assertEquals(1, store.getStat("oakPlanted"));
		cd.applyChat("You plant a bittercap mushroom spore in the mushroom patch.");
		assertEquals(1, store.getStat("bittercapMushroomPlanted"));
		cd.applyChat("You plant a seaweed spore in the seaweed patch.");
		assertEquals(1, store.getStat("seaweedPlanted"));
		assertEquals(5, store.getStat("seedsPlanted"));
		// a line that names no seed leaves the aggregate to carry it alone
		cd.applyChat("You plant the explosive.");
		assertEquals(6, store.getStat("seedsPlanted"));
		assertEquals(0, store.getStat("explosivePlanted"));
	}

	// a deriver on a fresh store, for the chat lines that need no item lookup
	private static SkillDeriver chatDeriver(StatStore store)
	{
		return new SkillDeriver(Mockito.mock(ItemManager.class), store, new Gson());
	}

	@Test
	public void herbsIntoTheSackAreCountedByHerb()
	{
		StatStore store = new StatStore();
		SkillDeriver cd = chatDeriver(store);
		cd.applyChat("You put the grimy guam leaf herb into your herb sack.");
		assertEquals(1, store.getStat("herbsSacked"));
		assertEquals(1, store.getStat("guamLeafSacked"));
		cd.applyChat("You put the grimy ranarr weed herb into your herb sack.");
		assertEquals(1, store.getStat("ranarrWeedSacked"));
		// the grimy prefix and the trailing "herb" are both unconfirmed, so a line
		// without either still names its herb
		cd.applyChat("You put the Toadflax into your herb sack.");
		assertEquals(1, store.getStat("toadflaxSacked"));
		assertEquals(3, store.getStat("herbsSacked"));
		assertEquals(0, store.getStat("grimyGuamLeafSacked"));
	}

	@Test
	public void letveksShooedAndSpiritPoolsHarpooned()
	{
		StatStore store = new StatStore();
		SkillDeriver cd = chatDeriver(store);
		cd.applyChat("You gently shoo the letvek away.");
		assertEquals(1, store.getStat("letveksShooed"));
		cd.applyChat("The glowing fish scatter, shedding their magical scales.");
		assertEquals(1, store.getStat("spiritPoolsHarpooned"));
	}

	@Test
	public void aBucketOfSapCountsOnlyAtABloodwoodTree()
	{
		StatStore store = new StatStore();
		SkillDeriver cd = chatDeriver(store);
		String line = "You fill the bucket with sap.";
		cd.applyChat(line, "Bloodwood tree");
		assertEquals(1, store.getStat("bloodwoodSapBucketsFilled"));
		// the same line at an evergreen, and with no tree known at all
		cd.applyChat(line, "Knife -> Evergreen");
		cd.applyChat(line);
		assertEquals(1, store.getStat("bloodwoodSapBucketsFilled"));
		cd.applyChat(line, "Engorged bloodwood tree");
		assertEquals(2, store.getStat("bloodwoodSapBucketsFilled"));
	}

	@Test
	public void thrallsAreCountedByTierAndKind()
	{
		StatStore store = new StatStore();
		SkillDeriver cd = chatDeriver(store);
		String[] tiers = {"lesser", "superior", "greater"};
		String[] kinds = {"ghostly", "skeletal", "zombified"};
		for (String tier : tiers)
		{
			for (String kind : kinds)
			{
				cd.applyChat("You resurrect a " + tier + " " + kind + " thrall.");
			}
		}
		assertEquals(9, store.getStat("thrallsSummoned"));
		assertEquals(1, store.getStat("lesserGhostlyThrallsSummoned"));
		assertEquals(1, store.getStat("lesserSkeletalThrallsSummoned"));
		assertEquals(1, store.getStat("lesserZombifiedThrallsSummoned"));
		assertEquals(1, store.getStat("superiorGhostlyThrallsSummoned"));
		assertEquals(1, store.getStat("superiorSkeletalThrallsSummoned"));
		assertEquals(1, store.getStat("superiorZombifiedThrallsSummoned"));
		assertEquals(1, store.getStat("greaterGhostlyThrallsSummoned"));
		assertEquals(1, store.getStat("greaterSkeletalThrallsSummoned"));
		assertEquals(1, store.getStat("greaterZombifiedThrallsSummoned"));
		// a thrall the line names no tier for still counts the floor alone
		cd.applyChat("You resurrect a thrall.");
		assertEquals(10, store.getStat("thrallsSummoned"));
	}

	@Test
	public void hidesTannedOneAtATimeAndByTheBatch()
	{
		StatStore store = new StatStore();
		SkillDeriver cd = chatDeriver(store);
		cd.applyChat("The tanner tans your cowhide.");
		assertEquals(1, store.getStat("hidesTanned"));
		assertEquals(1, store.getStat("cowhideTanned"));
		cd.applyChat("The tanner tans your cowhide for you.");
		assertEquals(2, store.getStat("cowhideTanned"));
		// the batch form names the count and the plural; both land on the same key
		cd.applyChat("The tanner tans 27 green dragonhides for you.");
		assertEquals(29, store.getStat("hidesTanned"));
		assertEquals(27, store.getStat("greenDragonhideTanned"));
		cd.applyChat("The tanner tans 5 snake hides for you.");
		assertEquals(5, store.getStat("snakeHideTanned"));
		assertEquals(34, store.getStat("hidesTanned"));
		assertEquals(0, store.getStat("greenDragonhidesTanned"));
	}

	@Test
	public void unfinishedPotionsComeOffTheVialLine()
	{
		StatStore store = new StatStore();
		SkillDeriver cd = chatDeriver(store);
		cd.applyChat("You put the Guam leaf into the vial of water.");
		assertEquals(1, store.getStat("unfinishedPotionsMade"));
		// the sack line also starts "You put the"; it is not a potion
		cd.applyChat("You put the grimy guam leaf herb into your herb sack.");
		assertEquals(1, store.getStat("unfinishedPotionsMade"));
		// nor is any other "You put the" line the gate forwards (a synthetic one:
		// the vial is what makes the line a potion, not the opening words)
		cd.applyChat("You put the coins into the coffer.");
		assertEquals(1, store.getStat("unfinishedPotionsMade"));
	}

	@Test
	public void absorbsAndFloors()
	{
		// lamps and gauntlet internals derive nothing
		assertNull(derive("RUNECRAFT|500|||||2528"));
		assertNull(derive("WOODCUTTING|10||23838|1||"));
		// runecraft veneration (non-rune gain) derives nothing
		names.put(13446, "Dark essence block");
		assertNull(derive("RUNECRAFT|2||13446|1||"));
		// firemaking with a missed consumed falls to the bare-xp ladder
		assertEquals((Integer) 1, derive("FIREMAKING|303|||||").get("magicLogsBurned"));
	}

	@Test
	public void aGatheredResourceIsValuedAtTheMomentItIsGathered()
	{
		names.put(1515, "Yew logs");
		prices.put(1515, 240);
		Map<String, Integer> got = derive("WOODCUTTING|175||1515|1||");
		assertEquals((Integer) 1, got.get("logsChopped"));
		assertEquals((Integer) 1, got.get("yewLogsChopped"));
		assertEquals((Integer) 240, got.get("resourcesGatheredValue"));

		// the price moves and the banked 240 isn't revisited
		prices.put(1515, 5);
		assertEquals((Integer) 5,
			derive("WOODCUTTING|175||1515|1||").get("resourcesGatheredValue"));
	}

	@Test
	public void theWholeCatchIsValuedNotTheAction()
	{
		names.put(3150, "Karambwanji");
		prices.put(3150, 30);
		// one action can land 4, and the value follows qty
		Map<String, Integer> got = derive("FISHING|5||3150|4||");
		assertEquals((Integer) 4, got.get("karambwanjiCaught"));
		assertEquals((Integer) 120, got.get("resourcesGatheredValue"));
	}

	@Test
	public void anUnpricedGatherAddsNothingButIsStillRemembered()
	{
		names.put(434, "Clay");
		Map<String, Integer> got = derive("MINING|5||434|1||");
		assertEquals((Integer) 1, got.get("rocksMined"));
		assertNull(got.get("resourcesGatheredValue"));
		// an item the GE can't price still goes in the ledger
		assertTrue(gathered.contains(434));
	}

	@Test
	public void cookingIsNotAGather()
	{
		names.put(385, "Shark");
		prices.put(385, 800);
		Map<String, Integer> got = derive("COOKING|210||385|1||");
		assertEquals((Integer) 1, got.get("foodCooked"));
		assertEquals((Integer) 1, got.get("sharkCooked"));
		// the shark was valued when it was caught; valuing it again on the range
		// counts the one catch twice
		assertNull(got.get("resourcesGatheredValue"));
		assertFalse(gathered.contains(385));
	}

	@Test
	public void aGatherTheResolverCannotNameIsNotValued()
	{
		// the xp names the tree, but a nest resolves to no woodcutting token, so
		// nothing is valued or noted as gathered
		names.put(11941, "Bird nest");
		prices.put(11941, 4000);
		Map<String, Integer> got = derive("WOODCUTTING|175||11941|1||");
		assertEquals((Integer) 1, got.get("logsChopped"));
		assertNull(got.get("resourcesGatheredValue"));
		assertFalse(gathered.contains(11941));
	}

	@Test
	public void burnsAreCountedByTheFoodTheLineNames()
	{
		StatStore store = new StatStore();
		SkillDeriver cd = chatDeriver(store);
		cd.applyChat("You accidentally burn the shark.");
		cd.applyChat("You accidentally burn the moonlight antelope.");
		// a cake is no fish: the rule reads the name off the line, not off a list
		cd.applyChat("You accidentally burn the cake.");
		// the cooked row is aliased to the singular, so the burnt one sits beside it
		cd.applyChat("You accidentally burn the shrimps.");
		// the one line that runs on past the food (wiki, verbatim)
		cd.applyChat("You accidentally burn the karambwanji to ashes.");
		assertEquals(5, store.getStat("foodBurned"));
		assertEquals(1, store.getStat("sharkBurned"));
		assertEquals(1, store.getStat("moonlightAntelopeBurned"));
		assertEquals(1, store.getStat("cakeBurned"));
		assertEquals(1, store.getStat("shrimpBurned"));
		assertEquals(0, store.getStat("shrimpsBurned"));
		assertEquals(1, store.getStat("karambwanjiBurned"));
		assertEquals(0, store.getStat("karambwanjiToAshesBurned"));
	}

	@Test
	public void theLeapingFishAndTheSacredEelTypeWithoutARawPrefix()
	{
		names.put(11328, "Leaping trout");
		names.put(11330, "Leaping salmon");
		names.put(11332, "Leaping sturgeon");
		names.put(13339, "Sacred eel");
		// the xp on each is a members collision, so only the item can name the row
		assertEquals((Integer) 1, derive("FISHING|50||11328|1||").get("leapingTroutCaught"));
		assertEquals((Integer) 1, derive("FISHING|70||11330|1||").get("leapingSalmonCaught"));
		assertEquals((Integer) 1, derive("FISHING|80||11332|1||").get("leapingSturgeonCaught"));
		Map<String, Integer> eel = derive("FISHING|105||13339|1||");
		assertEquals((Integer) 1, eel.get("sacredEelCaught"));
		assertEquals((Integer) 1, eel.get("fishCaught"));
		// two eels in one tick put the xp off the ladder; the item still names them
		Map<String, Integer> pair = derive("FISHING|210||13339|2||");
		assertEquals((Integer) 2, pair.get("sacredEelCaught"));
		assertEquals((Integer) 2, pair.get("fishCaught"));
		// minnows still count one to a minnow
		names.put(21356, "Minnow");
		Map<String, Integer> minnows = derive("FISHING|26||21356|12||");
		assertEquals((Integer) 12, minnows.get("minnowCaught"));
		assertEquals((Integer) 12, minnows.get("fishCaught"));
	}

	@Test
	public void thePlainTreeIsNormalOnTheItemPathAsOnTheLadder()
	{
		names.put(1511, "Logs");
		Map<String, Integer> byItem = derive("WOODCUTTING|25||1511|1||");
		assertEquals((Integer) 1, byItem.get("normalLogsChopped"));
		assertNull(byItem.get("logsLogsChopped"));
		// a full pack drops the log on the ground, and the xp ladder names the same key
		assertEquals((Integer) 1, derive("WOODCUTTING|25|||||").get("normalLogsChopped"));
	}

	@Test
	public void pickpocketTargetsShedTheirCombatLevel()
	{
		// the menu target reads "Guard  (level-21)" once the colour tags are gone
		Map<String, Integer> got = derive("THIEVING|47||||Guard  (level-21)|");
		assertEquals((Integer) 1, got.get("pickPockets"));
		assertEquals((Integer) 1, got.get("guardPickpockets"));
		for (String key : got.keySet())
		{
			assertFalse(key, key.contains("(level"));
		}
		assertEquals((Integer) 1, derive("THIEVING|84||||Knight of Ardougne (level 46)|")
			.get("knightOfArdougnePickpockets"));
		// a level with no name in front of it robs nobody in particular
		assertEquals(1, derive("THIEVING|47||||(level-21)|").size());
		assertEquals("Guard", SkillDeriver.npcName("Guard  (level-21)"));
		assertEquals("Guard", SkillDeriver.npcName("Guard (level 21)"));
		assertEquals("Guard", SkillDeriver.npcName("Guard level-21"));
		assertEquals("Master Farmer", SkillDeriver.npcName("Master Farmer"));
		StatStore store = new StatStore();
		SkillDeriver cd = chatDeriver(store);
		cd.applyChat("You fail to pick the Guard's pocket.");
		assertEquals(1, store.getStat("guardFailedPickpockets"));
	}
}
