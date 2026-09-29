/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.http.api.loottracker.LootRecordType;
import org.junit.BeforeClass;
import org.junit.Test;
import static chronicle.Harness.after;
import static chronicle.Harness.has;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PanelTest
{
	private static final LocalDate TODAY = LocalDate.now();
	private static final String DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK).format(TODAY);
	private static Harness h;

	@BeforeClass
	public static void fixture()
	{
		h = new Harness();
		Map<String, Long> skills = new HashMap<>();
		skills.put("attack", 1_000_000L);
		skills.put("overall", 1_000_000L);
		h.spine(TODAY.minusDays(10), skills, Map.of("kills", 40L), Map.of("Vorkath", 40L));
		h.login();
		h.level(Skill.ATTACK, 90, 5_400_000).level(Skill.SLAYER, 85, 3_300_000).level(Skill.HUNTER, 80, 2_000_000);
		h.level(Skill.HUNTER, 81, 2_200_000);
		h.chat("Your Vorkath kill count is: 50.").chat("Fight duration: 1:40.00. Personal best: 1:30.00");
		h.kill("Vorkath", 8061, 536, 2, 11286, 1).tick();
		h.chat("Your Vorkath kill count is: 51.").chat("Fight duration: 1:20.00 (new personal best)");
		h.kill("Vorkath", 8061, 536, 2).tick();
		h.task("Dust devils", 99, 120).kill("Dust devil", 423, 526, 1, 1618, 1).tick();
		h.task("Dust devils", 98, 120).kill("Dust devil", 423, 526, 1).tick();
		h.loot("Barrows", LootRecordType.EVENT, 4151, 1);
		h.kill("Zulrah", 2042, 526, 1).tick();
		h.chat("You have a funny feeling like you're being followed.");
		h.chat("New item added to your collection log: Abyssal orphan");
		h.chat("You have completed 87 hard Treasure Trails.");
		h.chat("Congratulations, you've completed a Hard combat task: Peach Conjurer (3 points).");
		h.chat("Congratulations! You have completed all of the hard tasks in the Varrock area.");
		h.attackedBy(h.npc("Vorkath", 8061, new WorldPoint(3200, 3205, 0), 7)).dies(null);
		h.pack(385, 2).click("Eat", "Shark", 385).pack(385, 1).tick();
		h.clogPage("Vorkath", Arrays.asList("Obtained: 1/6", "Vorkath kills: 51"), 11286, 1, 0);
		h.sitting(95).save();
	}

	private static List<String> screen(String... path)
	{
		return h.period("Lifetime", TODAY).screen(path);
	}

	private static void shows(List<String> screen, String... lines)
	{
		for (String l : lines)
		{
			assertTrue(l + " not in " + screen, has(screen, l));
		}
	}

	@Test
	public void nowShowsTheSittingAndItsDrops()
	{
		List<String> now = screen("Now");
		shows(now, "THIS SESSION", "RECENT DROPS", "Draconic visage");
		assertEquals("6 · 4.5M gp", after(now, "Drops received"));
	}

	@Test
	public void theJournalWritesTheDayAndEachLensKeepsItsOwn()
	{
		List<String> all = screen("Journal");
		shows(all, "1 sitting · 1h 35m · 6 drops · 4.5M gp", "Died to Vorkath", "Pet: Abyssal orphan",
			"Level: Hunter 81", "Record: Vorkath 1:20, was 1:30", "Diary: Varrock HARD", "CA HARD: Peach Conjurer",
			"Clue: HARD", "Log slot: Abyssal orphan", "Session · 1h 35m");
		Object[][] lenses = {
			{"Log", "Log slot: Abyssal orphan", "Died to Vorkath"},
			{"Feats", "Pet: Abyssal orphan", "Died to Vorkath"},
			{"Deaths", "Died to Vorkath", "Pet: Abyssal orphan"},
			{"Sessions", "Session · 1h 35m", "Log slot: Abyssal orphan"},
		};
		for (Object[] l : lenses)
		{
			List<String> s = screen("Journal", (String) l[0]);
			assertTrue(l[0] + " " + s, has(s, (String) l[1]));
			assertFalse(l[0] + " " + s, has(s, (String) l[2]));
		}
	}

	@Test
	public void theLedgerAndTrackersCarryTheCounters()
	{
		for (List<String> s : Arrays.asList(screen("Ledger"), screen("Trackers")))
		{
			assertEquals("900 gp", after(s, "Consumed value"));
		}
		List<String> all = screen("All trackers");
		assertEquals("1", after(all, "Meals eaten"));
		assertEquals("1 · 900 gp", after(all, "Shark"));
	}

	@Test
	public void theRecapReadsTheWholeRecord()
	{
		List<String> recap = screen("Recap");
		assertEquals("Vorkath · 51", after(recap, "Killed most"));
		assertEquals("6 · 4.5M gp", after(recap, "Drops"));
		assertEquals("1 · Abyssal orphan", after(recap, "Pet"));
		assertEquals("1h 35m · 1 sitting", after(recap, "Played"));
		assertEquals("1", after(recap, "Death"));
	}

	@Test
	public void theSheetStandsOnTheLiveSkills()
	{
		List<String> sheet = screen("Standing");
		assertEquals("90", after(sheet, "ATT"));
		assertEquals("81", after(sheet, "HUN"));
		assertEquals("286", after(sheet, "Total level"));
		assertEquals("0 / 213", after(screen("Standing", "quests"), "Complete"));
		assertEquals("0 / 48", after(screen("Standing", "diaries"), "Tiers done"));
	}

	@Test
	public void lootRanksSourcesByWorthAndReadsByKind()
	{
		List<String> loot = screen("Loot");
		assertTrue(loot.indexOf("Vorkath") < loot.indexOf("Barrows"));
		assertTrue(loot.indexOf("Barrows") < loot.indexOf("Dust devil"));
		assertTrue(loot.indexOf("Dust devil") < loot.indexOf("Zulrah"));
		assertEquals("51 kc · PB 1:20", after(loot, "3.0M gp"));
		assertEquals("Draconic visage · 3.0M gp", after(screen("Loot", "kinds"), "Dearest"));
	}

	@Test
	public void slayerShowsTheTaskTheJourneyAndItsLoot()
	{
		List<String> board = screen("Slayer");
		shows(board, "CURRENT TASK", "Dust devils", "98 left");
		assertEquals("2", after(board, "Kills on task"));
		assertEquals("2,000 gp", after(board, "On-task loot"));
		assertEquals("2", after(screen("Slayer", "Drops"), "Kills logged"));
		List<String> task = screen("Slayer", "task:0");
		assertEquals("2 / 120", after(task, "Kills logged"));
		shows(task, "DUST DEVILS", "Uncut diamond");
	}

	@Test
	public void drillsOpenASourceAnItemAndASkill()
	{
		List<String> vorkath = screen("source:Vorkath");
		assertEquals("51", after(vorkath, "Kills"));
		assertEquals("1:30 · 2 timed", after(vorkath, "Average kill"));
		assertTrue(after(vorkath, "Personal best").startsWith("1:20 · set "));
		shows(vorkath, "Draconic visage", "Dragon bones ×4");
		List<String> visage = screen("item:Draconic visage");
		assertEquals("×1", after(visage, "Obtained"));
		assertEquals(DAY, after(visage, "Dropped on"));
		shows(visage, "FROM", "Vorkath");
		shows(screen("skill:hunter"), "HUNTER", "Experience");
	}

	@Test
	public void theBookPagesReadTheJournal()
	{
		assertEquals("4.5M gp · " + DAY, after(screen("Records"), "Richest day"));
		List<String> info = screen("Info");
		assertEquals("4", after(info, "Sources"));
		assertEquals("6", after(info, "Loot events"));
		assertEquals("1", after(info, "Chat kill counts"));
		shows(screen("Calendar"), DateTimeFormatter.ofPattern("MMMM yyyy", Locale.UK).format(TODAY).toUpperCase(),
			"1h 35m · 1 sitting");
	}

	@Test
	public void searchFindsSourcesItemsLogPagesAndTheJournal()
	{
		List<String> vorkath = screen("search:vorkath");
		shows(vorkath, "BOSSES AND MONSTERS", "51 kc", "COLLECTION LOG", "COMBAT ACHIEVEMENTS", "JOURNAL",
			"Died to Vorkath");
		List<String> whip = screen("search:whip");
		shows(whip, "ITEMS", "Abyssal whip", "1.5M gp · from Barrows");
		assertFalse(has(screen("search:qqqq"), "BOSSES AND MONSTERS"));
	}

	@Test
	public void theSessionPeriodReadsTheSittingAlone()
	{
		List<String> recap = h.period("Session", TODAY).screen("Recap");
		assertEquals("Vorkath · 2", after(recap, "Killed most"));
		assertTrue(after(recap, "Xp gained").startsWith("+200k xp, most in Hunter"));
		assertEquals("2 · 3.0M gp", after(h.period("Session", TODAY).screen("Loot"), "Vorkath"));
	}

	@Test
	public void theRecapBoardAndItsPictureTellTheSameStory()
	{
		List<String> recap = screen("Recap");
		assertEquals("1h 35m · 1 sitting", after(recap, "Played"));
		assertEquals("6 · 4.5M gp", after(recap, "Drops"));
		assertEquals("Vorkath · 51", after(recap, "Killed most"));
		List<String> tiles = h.recapTiles("Recap");
		assertTrue(tiles.toString(), tiles.contains("Boss kills 52"));
		assertTrue(tiles.toString(), tiles.contains("Loot 4.5M gp"));
		java.awt.image.BufferedImage img = h.recapPicture("Recap");
		assertEquals(1920, img.getWidth());
		assertTrue(img.getHeight() > 400 && img.getHeight() <= 1080);
		int ground = img.getRGB(0, 0);
		long drawn = 0;
		for (int x = 0; x < img.getWidth(); x++)
		{
			for (int y = 0; y < img.getHeight(); y++)
			{
				drawn += img.getRGB(x, y) != ground ? 1 : 0;
			}
		}
		assertTrue("drawn " + drawn, drawn > 20_000);
		List<String> week = h.period("Week", TODAY).recapTiles("Recap");
		h.period("Lifetime", TODAY);
		assertTrue(week.toString(), week.contains("Levels +253"));
		assertTrue(week.toString(), week.contains("Boss kills +3"));
	}

	@Test
	public void theSheetPagesCountAgainstTheGamesOwnLists()
	{
		assertEquals("0 / 213", after(screen("Standing", "quests"), "Complete"));
		assertEquals("0 / 48", after(screen("Standing", "diaries"), "Tiers done"));
		assertEquals("0 / 2,697", after(screen("Standing", "combat"), "Points"));
		assertEquals("0 / 6", after(screen("Standing", "clues"), "Tiers seen"));
	}
}
