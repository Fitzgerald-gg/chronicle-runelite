/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the BSD 2-Clause
 * License (see LICENSE) are met.
 */
package chronicle.panel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The History tab's period model: the summary keeps its fixed order and shows
 * only what moved, and everything else files into the Stats tab's families and
 * sections with a period total on each.
 */
public class HistoryProgressTest
{
	private static Map<String, Long> map(Object... kv)
	{
		Map<String, Long> m = new LinkedHashMap<>();
		for (int i = 0; i < kv.length; i += 2)
		{
			m.put((String) kv[i], ((Number) kv[i + 1]).longValue());
		}
		return m;
	}

	private static HistoryProgress of(Map<String, Long> counters)
	{
		return HistoryProgress.of(counters, StatRegistry::isGp);
	}

	private static List<String> labels(List<HistoryProgress.Row> rows)
	{
		List<String> out = new ArrayList<>();
		for (HistoryProgress.Row r : rows)
		{
			out.add(r.label());
		}
		return out;
	}

	private static HistoryProgress.Row row(HistoryProgress p, String label)
	{
		for (HistoryProgress.Row r : p.summary())
		{
			if (r.label().equals(label))
			{
				return r;
			}
		}
		return null;
	}

	private static HistoryProgress.Section section(HistoryProgress p, String name)
	{
		for (HistoryProgress.Section s : p.sections())
		{
			if (s.name().equals(name))
			{
				return s;
			}
		}
		return null;
	}

	private static List<String> names(HistoryProgress p)
	{
		List<String> out = new ArrayList<>();
		for (HistoryProgress.Group g : p.groups())
		{
			out.add(g.name());
		}
		return out;
	}

	private static List<String> sectionNames(List<HistoryProgress.Section> sections)
	{
		List<String> out = new ArrayList<>();
		for (HistoryProgress.Section s : sections)
		{
			out.add(s.name());
		}
		return out;
	}

	private static List<String> sectionNames(HistoryProgress p)
	{
		return sectionNames(p.sections());
	}

	// every summary key moved, listed backwards to prove the order is the
	// model's, not the input's
	private static Map<String, Long> everything()
	{
		return map(
			"itemsDroppedValue", 18, "resourcesDroppedValue", 5, "resourcesGatheredValue", 17,
			"consumedValue", 16, "coinsFromAlchemy", 15, "coinsEarnedAtShops", 14,
			"coinsSpentAtShops", 13, "distanceWalked", 12, "distanceRan", 11,
			"clogSlotsObtained", 9, "levelsGained", 26, "combatAchievements", 25,
			"diariesCompleted", 24, "questsCompleted", 23, "petsObtained", 22,
			"deaths", 8, "damageDealtMagic", 1, "damageDealtRanged", 2,
			"damageDealtMelee", 4, "damageDealt", 7, "slayerKills", 5, "slayerTasksCompleted", 6,
			"kills", 3,
			"lootLeftKills", 1, "lootLeftValue", 5, "lootLeftCount", 4, "lootValue", 2000,
			"dropsReceived", 3);
	}

	@Test
	public void summaryKeepsItsFixedOrderAndShowsOnlyWhatMoved()
	{
		HistoryProgress p = of(everything());
		assertEquals(Arrays.asList(
			"Drops received", "Drops taken", "Loot value", "Left on the floor", "Loot kept", "Kills",
			"Slayer tasks completed", "Slayer kills", "Damage dealt", "· by melee", "· by ranged",
			"· by magic",
			"Deaths", "Pets", "Quests completed", "Diaries completed", "Combat achievements",
			"Levels gained", "Collection log slots", "Distance run", "Distance walked",
			"Spent at shops", "Earned at shops", "Coins from alchemy", "Consumed value",
			"Gathered", "Value dropped"),
			labels(p.summary()));
		// gp rows carry the flag, counts do not
		assertTrue(row(p, "Loot value").gp());
		assertTrue(row(p, "Consumed value").gp());
		assertTrue(row(p, "Spent at shops").gp());
		assertFalse(row(p, "Deaths").gp());
		assertFalse(row(p, "Pets").gp());
		assertFalse(row(p, "Levels gained").gp());
		assertFalse(row(p, "Distance run").gp());

		// a figure that did not move is no row; a zero is not a row either
		HistoryProgress few = of(map("deaths", 2, "damageDealt", 0, "kills", 4));
		assertEquals(Arrays.asList("Kills", "Deaths"), labels(few.summary()));
	}

	@Test
	public void everySummaryLabelIsTheRegistrys()
	{
		// one table names a key for both tabs: the summary never words a key
		// itself
		HistoryProgress p = of(everything());
		assertEquals(27, p.summary().size());
		for (HistoryProgress.Row r : p.summary())
		{
			assertEquals(r.key(), StatRegistry.label(r.key()), r.label());
		}
	}

	@Test
	public void theFeedsFiguresLineUpAfterDeathsAndNeverFile()
	{
		// the five keys the History tab counts off the feed alone: summary keys
		// every one, so a journal carrying a counter by that name never files it
		// as a section
		String[] feed = {"petsObtained", "questsCompleted", "diariesCompleted",
			"combatAchievements", "levelsGained"};
		for (String key : feed)
		{
			assertTrue(key, HistoryProgress.summaryKey(key));
		}
		// handed in as retroactive figures over a spine that never carried them,
		// they make their own lines, right after Deaths and before the log slots,
		// in the order they were named and at the figure each was given
		HistoryProgress p = HistoryProgress.of(map("deaths", 3, "clogSlotsObtained", 9),
			StatRegistry::isGp,
			map("levelsGained", 6, "combatAchievements", 4, "diariesCompleted", 1,
				"questsCompleted", 2, "petsObtained", 5));
		assertEquals(Arrays.asList("Deaths", "Pets", "Quests completed", "Diaries completed",
			"Combat achievements", "Levels gained", "Collection log slots"), labels(p.summary()));
		assertEquals(5, row(p, "Pets").value());
		assertEquals(2, row(p, "Quests completed").value());
		assertEquals(1, row(p, "Diaries completed").value());
		assertEquals(4, row(p, "Combat achievements").value());
		assertEquals(6, row(p, "Levels gained").value());
		assertTrue(p.sections().isEmpty());

		// a zero for one of them is no line, and a zero for deaths takes the
		// spine's line away too: the feed said nothing happened
		HistoryProgress zero = HistoryProgress.of(map("deaths", 3, "kills", 2),
			StatRegistry::isGp, map("deaths", 0, "petsObtained", 0, "questsCompleted", 1));
		assertEquals(Arrays.asList("Kills", "Quests completed"), labels(zero.summary()));

		// as counters they are summary lines all the same, never sections
		HistoryProgress counted = of(map("petsObtained", 1, "levelsGained", 2, "hitsMissed", 3));
		assertEquals(Arrays.asList("Pets", "Levels gained"), labels(counted.summary()));
		assertEquals(Collections.singletonList("Combat"), sectionNames(counted));
	}

	@Test
	public void lootKeptIsValueLessTheFloorAndTheFloorRowCarriesItsGp()
	{
		HistoryProgress p = of(map("lootValue", 1000, "lootLeftCount", 3, "lootLeftValue", 250));
		HistoryProgress.Row floor = row(p, "Left on the floor");
		assertEquals(3, floor.value());
		assertFalse(floor.gp());
		assertEquals(250, floor.gpNote());
		assertEquals("gp", floor.gpNoteWord());
		HistoryProgress.Row kept = row(p, "Loot kept");
		assertEquals(750, kept.value());
		assertTrue(kept.gp());
		assertEquals(0, kept.gpNote());

		// nothing left behind: the floor row is absent and all of it was kept
		HistoryProgress none = of(map("lootValue", 1000));
		assertNull(row(none, "Left on the floor"));
		assertEquals(1000, row(none, "Loot kept").value());

		// a count without a value carries no gp note
		HistoryProgress bare = of(map("lootValue", 40, "lootLeftCount", 2));
		assertEquals(0, row(bare, "Left on the floor").gpNote());

		// more left than received: nothing was kept
		HistoryProgress upside = of(map("lootValue", 100, "lootLeftCount", 1, "lootLeftValue", 150));
		assertNull(row(upside, "Loot kept"));
	}

	@Test
	public void dropsTakenIsReceivedLessTheKillsThatLeftLoot()
	{
		// one unit both ways: the loot events less the kills that left a stack,
		// never the items on the floor
		HistoryProgress p = of(map("dropsReceived", 10, "lootLeftKills", 3, "lootLeftCount", 40));
		assertEquals(Arrays.asList("Drops received", "Drops taken", "Left on the floor"),
			labels(p.summary()));
		HistoryProgress.Row taken = row(p, "Drops taken");
		assertEquals("dropsTaken", taken.key());
		assertEquals(7, taken.value());
		assertFalse(taken.gp());
		assertEquals(0, taken.gpNote());
		assertTrue(p.sections().isEmpty());

		// it sits right after Drops received, before the gp lines
		HistoryProgress full = of(map("lootValue", 500, "kills", 4, "dropsReceived", 10,
			"lootLeftKills", 3));
		assertEquals(Arrays.asList("Drops received", "Drops taken", "Loot value", "Loot kept",
			"Kills"), labels(full.summary()));

		// nothing left behind: every drop was taken
		assertEquals(10, row(of(map("dropsReceived", 10)), "Drops taken").value());

		// more kills left something than the period received: floored, and still
		// drawn, since the period did receive
		HistoryProgress floor = of(map("dropsReceived", 2, "lootLeftKills", 5));
		assertEquals(Arrays.asList("Drops received", "Drops taken"), labels(floor.summary()));
		assertEquals(0, row(floor, "Drops taken").value());

		// nothing received: no line, and the kills figure is a summary key that
		// never files as a section of its own
		HistoryProgress none = of(map("lootLeftKills", 5));
		assertTrue(none.summary().isEmpty());
		assertTrue(none.sections().isEmpty());
		assertTrue(HistoryProgress.summaryKey("lootLeftKills"));
		assertFalse(HistoryProgress.summaryKey("dropsTaken"));

		// a retroactive figure for the kills replaces the spine's delta
		HistoryProgress retro = HistoryProgress.of(map("dropsReceived", 10, "lootLeftKills", 3),
			StatRegistry::isGp, map("lootLeftKills", 1));
		assertEquals(9, row(retro, "Drops taken").value());
	}

	@Test
	public void theImportedUntakenPairIsNotTheFloor()
	{
		// untakenLootCount and untakenLootValue are a lifetime figure carried in
		// from an older record, not this build's ledger: they never make the
		// floor line and never come off what was kept. The registry files them
		// where the Stats tab does.
		HistoryProgress p = of(map("lootValue", 1000, "untakenLootCount", 3, "untakenLootValue", 250));
		assertNull(row(p, "Left on the floor"));
		assertEquals(1000, row(p, "Loot kept").value());
		assertEquals(Arrays.asList("Loot value", "Loot kept"), labels(p.summary()));
		assertFalse(HistoryProgress.summaryKey("untakenLootCount"));
		assertFalse(HistoryProgress.summaryKey("untakenLootValue"));
	}

	@Test
	public void killsReadTheLedgersTallyOnTheSpine()
	{
		HistoryProgress p = of(map("kills", 15));
		assertEquals(Collections.singletonList("Kills"), labels(p.summary()));
		assertEquals(15, row(p, "Kills").value());
		assertFalse(row(p, "Kills").gp());
		assertTrue(p.sections().isEmpty());

		assertNull(row(of(map("kills", 0)), "Kills"));
	}

	@Test
	public void theDamageSplitRidesUnderItsParent()
	{
		HistoryProgress p = of(map("hitsBlocked", 5, "damageDealtRanged", 30,
			"damageDealtMelee", 60, "damageDealt", 100));
		// the split sits right after Damage dealt with the registry's "· by"
		// labels, never as orphaned rows under Combat
		assertEquals(Arrays.asList("Damage dealt", "· by melee", "· by ranged"), labels(p.summary()));
		assertEquals(60, row(p, "· by melee").value());
		assertEquals(Collections.singletonList("Combat"), sectionNames(p));
		assertEquals(Collections.singletonList("Hits blocked"), labels(section(p, "Combat").rows()));
	}

	@Test
	public void whatWasDroppedRidesTheGatheredRow()
	{
		HistoryProgress p = of(map("resourcesGatheredValue", 1000, "resourcesDroppedValue", 300));
		HistoryProgress.Row gathered = row(p, "Gathered");
		assertEquals(1000, gathered.value());
		assertTrue(gathered.gp());
		assertEquals(300, gathered.gpNote());
		assertEquals("dropped", gathered.gpNoteWord());
		assertEquals(Collections.singletonList("Gathered"), labels(p.summary()));
		assertTrue(p.sections().isEmpty());

		// nothing gathered: the dropped figure has no row to ride and shows nowhere
		HistoryProgress none = of(map("resourcesDroppedValue", 300));
		assertTrue(none.summary().isEmpty());
		assertTrue(none.sections().isEmpty());
		// gathered without a drop: no note
		assertEquals(0, row(of(map("resourcesGatheredValue", 1000)), "Gathered").gpNote());
	}

	@Test
	public void peakAndHiddenKeysNeverFile()
	{
		HistoryProgress p = of(map("highestHit", 5, "highestHitTaken", 9, "totalXpGained", 100,
			"resourcesDroppedValue", 30, "_diag", 3, "hitsMissed", 4));
		assertEquals(Collections.singletonList("Combat"), sectionNames(p));
		assertEquals(Collections.singletonList("Hits missed"), labels(section(p, "Combat").rows()));
		assertTrue(p.summary().isEmpty());
	}

	@Test
	public void summaryKeysNeverAppearInSections()
	{
		Map<String, Long> c = everything();
		HistoryProgress p = of(c);
		assertEquals(27, p.summary().size());
		assertTrue(p.sections().toString(), p.sections().isEmpty());
		for (String k : c.keySet())
		{
			assertTrue(k, HistoryProgress.summaryKey(k));
		}

		// the family still lists what the summary leaves it
		c.put("damageTaken", 300L);
		HistoryProgress with = of(c);
		assertEquals(Collections.singletonList("Combat"), sectionNames(with));
		assertEquals(Collections.singletonList("Damage taken"),
			labels(section(with, "Combat").rows()));
	}

	@Test
	public void teleportsAreASectionNotASummaryLine()
	{
		// one place for one figure: the Teleports fold carries the period's
		// total, its means under it and the rest as "Other means", as on the
		// Stats tab. A summary line would read a second number for the same word.
		HistoryProgress p = of(map("teleportsTotal", 10, "teleportsViaJewellery", 6,
			"teleportsVarrock", 4));
		assertNull(row(p, "Teleports"));
		assertFalse(HistoryProgress.summaryKey("teleportsTotal"));
		HistoryProgress.Section tele = section(p, "Teleports");
		assertEquals(10, tele.total());
		assertEquals(Collections.singletonList("· by jewellery"), labels(tele.rows()));
		assertEquals(4, tele.ghost());
		assertEquals("Other means", tele.ghostLabel());
		assertEquals(4, section(p, "Destinations").total());
	}

	@Test
	public void aTypedCraftReconcilesToItsFloor()
	{
		HistoryProgress p = of(map("logsChopped", 10, "oakLogsChopped", 3, "willowLogsChopped", 6));
		HistoryProgress.Section wc = section(p, "Woodcutting");
		assertEquals("Skilling", wc.family());
		assertEquals(10, wc.total());
		assertEquals(Arrays.asList("Willow", "Oak"), labels(wc.rows()));
		assertEquals(6, wc.rows().get(0).value());
		assertEquals(1, wc.ghost());
		assertEquals("Other", wc.ghostLabel());
		// the floor is the head, never a row
		assertFalse(labels(wc.rows()).contains("Logs chopped"));

		// rows that account for the whole floor leave no ghost
		HistoryProgress even = of(map("logsChopped", 9, "oakLogsChopped", 3, "willowLogsChopped", 6));
		assertEquals(0, section(even, "Woodcutting").ghost());
	}

	@Test
	public void theHeadIsNeverLessThanTheRowsUnderIt()
	{
		// a named row outside the floor (herbiboars are not creatures trapped)
		// lifts the head past the floor, as the Stats tab heads it: rows plus the
		// ghost, or the floor, whichever is more
		HistoryProgress p = of(map("creaturesTrapped", 100, "redChinchompaTrapped", 90,
			"herbiboarsHarvested", 50));
		HistoryProgress.Section hunter = section(p, "Hunter");
		assertEquals(Arrays.asList("Red chinchompa", "Herbiboars harvested"), labels(hunter.rows()));
		assertEquals(10, hunter.ghost());
		assertEquals(150, hunter.total());

		// failed pickpockets are typed rows the successes' floor never counted
		HistoryProgress th = of(map("pickPockets", 10, "guardPickpockets", 8,
			"guardFailedPickpockets", 5));
		assertEquals(13, section(th, "Thieving").total());
		assertEquals(0, section(th, "Thieving").ghost());
	}

	@Test
	public void aSectionHoldingMoreThanOneVerbNamesEachRowsVerb()
	{
		// cooked and burnt share a name; the Stats tab nests them by verb, and a
		// breakdown that repeats "Shark" with two numbers is not one
		HistoryProgress p = of(map("foodCooked", 13, "sharkCooked", 10, "foodBurned", 3,
			"sharkBurned", 3, "fishCaught", 20, "sharkCaught", 20));
		HistoryProgress.Section cook = section(p, "Cooking");
		assertEquals(Arrays.asList("Shark cooked", "Shark burned"), labels(cook.rows()));
		assertEquals(16, cook.total());
		assertEquals(3, cook.ghost());
		// a single-verb section keeps the bare row label
		assertEquals(Collections.singletonList("Shark"), labels(section(p, "Fishing").rows()));

		HistoryProgress pr = of(map("abyssalAshesSacrificed", 5, "abyssalHeadsReanimated", 2,
			"dragonBonesBuried", 4, "dragonBonesOffered", 1));
		assertEquals(Arrays.asList("Abyssal ashes sacrificed", "Dragon bones buried",
			"Abyssal heads reanimated", "Dragon bones offered"), labels(section(pr, "Prayer").rows()));
	}

	@Test
	public void aSectionWithoutAFloorSumsItsRows()
	{
		HistoryProgress p = of(map("dartsFletched", 40, "arrowsFletched", 10));
		HistoryProgress.Section fl = section(p, "Fletching");
		assertEquals(50, fl.total());
		assertEquals(Arrays.asList("Darts fletched", "Arrows fletched"), labels(fl.rows()));
		assertEquals(0, fl.ghost());
	}

	@Test
	public void aFloorWithoutTypedRowsOpensToItsFloors()
	{
		HistoryProgress p = of(map("bonesBuried", 5, "ashesScattered", 3));
		HistoryProgress.Section pr = section(p, "Prayer");
		assertEquals(8, pr.total());
		assertEquals(Arrays.asList("Bones buried", "Ashes scattered"), labels(pr.rows()));
		assertEquals(0, pr.ghost());
	}

	@Test
	public void sectionsFollowTheStatsTabOrder()
	{
		Map<String, Long> c = map(
			"examines", 2,                 // Ledger: Odds & ends
			"teleportsVarrock", 4,         // Ledger: Destinations, its own section here
			"teleportsTotal", 10,          // Ledger: Teleports, the floor
			"teleportsViaJewellery", 6,    // Ledger: Teleports
			"dartsFletched", 500,          // Skilling: Fletching, the bigger craft, listed second
			"logsChopped", 20, "oakLogsChopped", 20,   // Skilling: Woodcutting
			"damageTaken", 30,             // Combat, sectionless
			"vialsShattered", 1,           // Living, sectionless
			"sharkEaten", 7, "foodEaten", 7);   // Living: Food
		HistoryProgress p = of(c);
		assertEquals(Arrays.asList("Living", "Food", "Combat", "Fletching", "Woodcutting",
			"Teleports", "Destinations", "Odds & ends"), sectionNames(p));
		// a family's sectionless rows carry the family's name and file under it
		assertEquals("Living", section(p, "Living").family());
		assertEquals("Ledger & Roads", section(p, "Destinations").family());
		assertEquals(Collections.singletonList("Varrock"), labels(section(p, "Destinations").rows()));
		// teleports head with the floor and reconcile the means to it
		assertEquals(10, section(p, "Teleports").total());
		assertEquals(4, section(p, "Teleports").ghost());
	}

	@Test
	public void aFlatKeyThatHeadsAFoldInItsFamilyFilesOnceAsTheFloor()
	{
		// potionDoses is Living's "Doses drunk" and the Potions floor, foodEaten
		// its "Meals eaten" and the Food floor: each heads its fold and is never
		// a row beside it, so one figure shows once
		HistoryProgress p = of(map("potionDoses", 225, "prayerDoses", 140, "foodEaten", 20,
			"sharkEaten", 16, "vialsShattered", 3));
		assertEquals(Arrays.asList("Living", "Food", "Potions"), sectionNames(p));
		assertEquals(Collections.singletonList("Vials shattered"),
			labels(section(p, "Living").rows()));
		HistoryProgress.Section potions = section(p, "Potions");
		assertEquals(225, potions.total());
		assertEquals(Collections.singletonList("Prayer"), labels(potions.rows()));
		assertEquals(85, potions.ghost());
		HistoryProgress.Section food = section(p, "Food");
		assertEquals(20, food.total());
		assertEquals(Collections.singletonList("Shark"), labels(food.rows()));
		assertEquals(4, food.ghost());
		for (HistoryProgress.Section s : p.sections())
		{
			assertFalse(s.name(), labels(s.rows()).contains("Doses drunk"));
			assertFalse(s.name(), labels(s.rows()).contains("Meals eaten"));
		}

		// the floor alone, with no typed row under it: the fold heads with it
		// and opens to the floor's own name, as Prayer opens to its verbs
		HistoryProgress bare = of(map("potionDoses", 225));
		assertEquals(Collections.singletonList("Potions"), sectionNames(bare));
		assertEquals(225, section(bare, "Potions").total());
		assertEquals(Collections.singletonList("Doses drunk"),
			labels(section(bare, "Potions").rows()));
	}

	@Test
	public void theHeadCarriesAFigureOnlyWhereTheRowsAddUp()
	{
		HistoryProgress p = of(map(
			"vialsShattered", 3, "hitpointsRegenerated", 500,   // Living's flat list: mixed units
			"damageTaken", 300, "hitsMissed", 40,                // Combat's flat list: mixed
			"examines", 2, "cabbagesPicked", 9,                  // Odds & ends: mixed
			"untakenLootValue", 1000,                            // The purse: gp
			"tilesRan", 800,                                     // On foot: tiles
			"dartsFletched", 40, "arrowsFletched", 10,           // Fletching: one craft's actions
			"logsChopped", 10, "oakLogsChopped", 3,              // Woodcutting: a floor
			"teleportsVarrock", 4));                             // Destinations
		assertFalse(section(p, "Living").summed());
		assertFalse(section(p, "Combat").summed());
		assertFalse(section(p, "Odds & ends").summed());
		assertTrue(section(p, "The purse").summed());
		assertTrue(section(p, "The purse").gp());
		assertTrue(section(p, "On foot").summed());
		assertTrue(section(p, "Fletching").summed());
		assertFalse(section(p, "Fletching").gp());
		assertTrue(section(p, "Woodcutting").summed());
		assertTrue(section(p, "Destinations").summed());

		// a flat list whose rows are all gp does add up, in gp
		HistoryProgress gp = of(map("potionsConsumedValue", 700, "foodConsumedValue", 300));
		assertTrue(section(gp, "Living").summed());
		assertTrue(section(gp, "Living").gp());
		assertEquals(1000, section(gp, "Living").total());
		// and one gp row beside a count is mixed again
		HistoryProgress mixed = of(map("potionsConsumedValue", 700, "vialsShattered", 3));
		assertFalse(section(mixed, "Living").summed());
		assertFalse(section(mixed, "Living").gp());
	}

	@Test
	public void retroactiveFiguresReplaceTheSpinesDelta()
	{
		Map<String, Long> spine = map("kills", 3, "slayerTasksCompleted", 6, "clogSlotsObtained", 9);
		Map<String, Long> journal = map("slayerTasksCompleted", 2, "clogSlotsObtained", 0,
			"logsChopped", 50);
		HistoryProgress p = HistoryProgress.of(spine, StatRegistry::isGp, journal);
		// the journal's figure stands in for the spine's, a zero included; a key
		// the summary does not read is ignored, and never files as a section
		assertEquals(Arrays.asList("Kills", "Slayer tasks completed"), labels(p.summary()));
		assertEquals(2, row(p, "Slayer tasks completed").value());
		assertNull(row(p, "Collection log slots"));
		assertTrue(p.sections().isEmpty());
		// a figure the spine never carried still makes its line
		HistoryProgress fresh = HistoryProgress.of(map("kills", 3), StatRegistry::isGp,
			map("slayerTasksCompleted", 4));
		assertEquals(4, row(fresh, "Slayer tasks completed").value());
		// no journal figures: the spine's deltas as they were
		HistoryProgress plain = HistoryProgress.of(spine, StatRegistry::isGp, null);
		assertEquals(6, row(plain, "Slayer tasks completed").value());
		assertEquals(9, row(plain, "Collection log slots").value());
		// and the caller's map is left alone
		assertEquals(Long.valueOf(6), spine.get("slayerTasksCompleted"));
	}

	@Test
	public void emptyInputYieldsNothing()
	{
		HistoryProgress p = of(Collections.emptyMap());
		assertTrue(p.summary().isEmpty());
		assertTrue(p.sections().isEmpty());
		HistoryProgress nulls = HistoryProgress.of(null, null);
		assertTrue(nulls.summary().isEmpty());
		assertTrue(nulls.sections().isEmpty());
	}
	@Test
	public void everySummaryKeyFilesUnderExactlyOneGroup()
	{
		HistoryProgress p = of(everything());
		// nothing the summary draws is out of reach, and nothing is drawn twice
		List<String> filed = new ArrayList<>();
		for (HistoryProgress.Group g : p.groups())
		{
			for (HistoryProgress.Row r : g.rows())
			{
				assertFalse(r.key() + " files twice", filed.contains(r.key()));
				filed.add(r.key());
			}
		}
		List<String> summary = new ArrayList<>();
		for (HistoryProgress.Row r : p.summary())
		{
			summary.add(r.key());
		}
		Collections.sort(filed);
		Collections.sort(summary);
		assertEquals(summary, filed);
		// and each names the group it files under
		for (HistoryProgress.Group g : p.groups())
		{
			for (HistoryProgress.Row r : g.rows())
			{
				assertEquals(r.key(), g.name(), HistoryProgress.groupOfKey(r.key()));
			}
		}
	}

	@Test
	public void theGroupsReadInTheirFixedOrderAndHoldTheirOwn()
	{
		Map<String, Long> all = everything();
		all.put("fishCaught", 50L);   // the one group the summary keys cannot fill
		HistoryProgress p = of(all);
		assertEquals(Arrays.asList(HistoryProgress.GROUPS), names(p));
		assertEquals(Collections.singletonList("Levels gained"),
			labels(p.group("Experience").rows()));
		assertEquals(Arrays.asList("Kills", "Slayer tasks completed", "Slayer kills",
			"Damage dealt", "· by melee", "· by ranged", "· by magic", "Deaths"),
			labels(p.group("Combat").rows()));
		assertEquals(Arrays.asList("Drops received", "Drops taken", "Left on the floor",
			"Loot value", "Loot kept", "Pets", "Collection log slots"),
			labels(p.group("Loot").rows()));
		assertEquals(Collections.singletonList("Consumed value"),
			labels(p.group("Upkeep").rows()));
		assertEquals(Arrays.asList("Distance run", "Distance walked"),
			labels(p.group("Travel").rows()));
		assertEquals(Arrays.asList("Quests completed", "Diaries completed",
			"Combat achievements"), labels(p.group("Achievement").rows()));
		assertEquals(Arrays.asList("Gathered", "Value dropped", "Coins from alchemy",
			"Spent at shops", "Earned at shops"), labels(p.group("The rest").rows()));
	}

	@Test
	public void aGroupWithNothingInItIsAbsent()
	{
		HistoryProgress p = of(map("kills", 12, "fishCaught", 50));
		assertEquals(Arrays.asList("Combat", "Skilling"), names(p));
		assertNull(p.group("Loot"));
		assertNull(p.group("The rest"));
		assertTrue(HistoryProgress.of(map(), StatRegistry::isGp).groups().isEmpty());
	}

	@Test
	public void everySectionFilesUnderExactlyOneGroup()
	{
		HistoryProgress p = of(map(
			"damageTaken", 40_000, "thrallsSummoned", 20, "lesserGhostlyThrallsSummoned", 12,
			"fishCaught", 50, "sharkCaught", 30,
			"foodEaten", 20, "sharkEaten", 16, "vialsShattered", 3,
			"untakenLootValue", 900_000, "examines", 30,
			"teleportsTotal", 60, "teleportsVarrock", 18, "tilesRan", 40_000));
		// every section the model files is inside one group, and the group is
		// the one the model names for it
		int filed = 0;
		for (HistoryProgress.Group g : p.groups())
		{
			for (HistoryProgress.Section s : g.sections())
			{
				assertEquals(s.name(), g.name(), HistoryProgress.groupOf(s));
				filed++;
			}
		}
		// Combat's and Living's flat lists are rows of their group rather than
		// sections, so two of the model's sections file as rows instead
		assertEquals(p.sections().size() - 2, filed);
		assertEquals(Collections.singletonList("Thralls"),
			sectionNames(p.group("Combat").sections()));
		assertEquals(Collections.singletonList("Fishing"),
			sectionNames(p.group("Skilling").sections()));
		assertEquals(Collections.singletonList("Food"),
			sectionNames(p.group("Upkeep").sections()));
		assertEquals(Arrays.asList("On foot", "Teleports", "Destinations"),
			sectionNames(p.group("Travel").sections()));
		assertEquals(Arrays.asList("The purse", "Odds & ends"),
			sectionNames(p.group("The rest").sections()));
	}

	@Test
	public void aFamilysFlatListRunsOnAsItsGroupsRows()
	{
		HistoryProgress p = of(map(
			"damageDealt", 60_000, "damageTaken", 40_000, "hitsMissed", 600,
			"consumedValue", 90_000, "vialsShattered", 3, "foodEaten", 20));
		// the flat rows follow the group's own figures, biggest first, and no
		// section repeats the family's name
		assertEquals(Arrays.asList("Damage dealt", "Damage taken", "Hits missed"),
			labels(p.group("Combat").rows()));
		assertTrue(p.group("Combat").sections().isEmpty());
		assertEquals(Arrays.asList("Consumed value", "Vials shattered"),
			labels(p.group("Upkeep").rows()));
		assertEquals(Collections.singletonList("Food"),
			sectionNames(p.group("Upkeep").sections()));
		// while the model's own sections are unchanged: the flat list is still
		// a section there, for the Stats tab's sake
		assertTrue(sectionNames(p.sections()).contains("Combat"));
		assertTrue(sectionNames(p.sections()).contains("Living"));
	}
}
