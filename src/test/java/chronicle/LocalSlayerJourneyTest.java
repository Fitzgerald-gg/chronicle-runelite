/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.File;
import java.nio.file.Files;
import net.runelite.api.ItemComposition;
import net.runelite.client.game.ItemManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Slayer journey rules: on-task loot opens or extends the newest segment, a
 * completion closes it and trues the kills up from the finished line, and the
 * streak line floors the lifetime total. Then the server's segmenter rules the
 * local rewrite has to honour: the finishing kill lands on its task within the
 * grace, a completed task's kills are the game's number with the witnessed count
 * kept apart, the counter corrects a stale initial, a counter reset splits two
 * same-monster tasks, a juggled task resumes its parked run, and only the newest
 * segment is ever in progress.
 */
public class LocalSlayerJourneyTest
{
	private LocalStore store;
	private File dir;

	@Before
	public void setUp() throws Exception
	{
		ItemManager im = Mockito.mock(ItemManager.class);
		Mockito.when(im.canonicalize(Mockito.anyInt())).thenAnswer(inv -> inv.getArgument(0));
		Mockito.when(im.getItemPrice(Mockito.anyInt())).thenReturn(100);
		ItemComposition comp = Mockito.mock(ItemComposition.class);
		Mockito.when(comp.getName()).thenReturn("Rune dagger");
		Mockito.when(im.getItemComposition(Mockito.anyInt())).thenReturn(comp);
		store = new LocalStore(im, new Gson());
		dir = Files.createTempDirectory("chronicle-test").toFile();
		store.load(dir, "Tester");
	}

	private void onTaskKill(String task, int initial, int itemId, int qty)
	{
		kill(task, task, initial, null, itemId, qty);
	}

	/** A kill stamped with the live counter, as capture stamps every on-task kill. */
	private void countedKill(String task, int initial, int remaining)
	{
		kill(task, task, initial, remaining, 1, 1);
	}

	/** The finishing kill: the task was cleared before its loot landed, so capture
	 *  stamps the task alone, no counter. */
	private void finishingKill(String task)
	{
		kill(task, task, null, null, 1, 1);
	}

	private void kill(String source, String task, Integer initial, Integer remaining,
		int itemId, int qty)
	{
		JsonObject data = new JsonObject();
		data.addProperty("source", source);
		data.addProperty("slayerTask", task);
		if (initial != null)
		{
			data.addProperty("slayerTaskInitial", initial);
		}
		if (remaining != null)
		{
			data.addProperty("slayerTaskRemaining", remaining);
		}
		JsonArray items = new JsonArray();
		JsonObject it = new JsonObject();
		it.addProperty("id", itemId);
		it.addProperty("quantity", qty);
		items.add(it);
		data.add("items", items);
		store.record("LOOT", data, "Tester");
	}

	private void completion(String task, Integer exactKills, Integer streak)
	{
		JsonObject data = new JsonObject();
		data.addProperty("task", task);
		if (exactKills != null)
		{
			data.addProperty("killCount", exactKills);
		}
		if (streak != null)
		{
			data.addProperty("count", streak);
		}
		store.record("SLAYER", data, "Tester");
	}

	@Test
	public void lootOpensAndExtendsOneSegment()
	{
		onTaskKill("Dust devils", 120, 1, 1);
		onTaskKill("Dust devils", 120, 1, 2);
		ChronicleApiClient.SlayerJourney j = store.slayerJourney();
		assertEquals(1, j.tasks.size());
		ChronicleApiClient.SlayerTask t = j.tasks.get(0);
		assertEquals("Dust devils", t.task);
		assertEquals(2, t.kills);
		assertEquals(120, t.assignment);
		assertTrue(t.inProgress);
		assertEquals(300, t.totalValue);   // 100gp × (1+2)
	}

	@Test
	public void completionClosesAndTruesUp()
	{
		onTaskKill("Nechryael", 150, 1, 1);
		onTaskKill("Nechryael", 150, 1, 1);
		completion("Nechryael", 150, 214);
		ChronicleApiClient.SlayerJourney j = store.slayerJourney();
		assertEquals(1, j.tasks.size());
		ChronicleApiClient.SlayerTask t = j.tasks.get(0);
		assertFalse(t.inProgress);
		assertEquals(150, t.kills);          // trued up to the finished line
		assertEquals(148, t.noLootKills);    // 150 exact − 2 witnessed
		assertEquals(214, j.completedTasks); // streak line is authoritative
		onTaskKill("Nechryael", 130, 1, 1);
		j = store.slayerJourney();
		assertEquals(2, j.tasks.size());
		assertTrue(j.tasks.get(0).inProgress);   // newest first
		assertEquals(1, j.tasks.get(0).kills);
	}

	@Test
	public void completionWithoutStreakIncrements()
	{
		completion("Kalphite", 90, null);
		completion("Kalphite", 80, null);
		assertEquals(2, store.slayerJourney().completedTasks);
	}

	private static long monsterCount(LocalStore store, int index, String monster)
	{
		for (LocalStore.UntakenRow r : store.slayerTaskMonsters(index))
		{
			if (r.name.equals(monster))
			{
				return r.qty;
			}
		}
		return 0;
	}

	@Test
	public void theFinishingKillLandsOnTheTaskItCompleted()
	{
		countedKill("Nechryael", 150, 2);
		countedKill("Nechryael", 150, 1);
		completion("Nechryael", 150, 214);
		finishingKill("Nechryael");
		ChronicleApiClient.SlayerJourney j = store.slayerJourney();
		assertEquals(1, j.tasks.size());
		ChronicleApiClient.SlayerTask t = j.tasks.get(0);
		assertFalse(t.inProgress);
		assertEquals(150, t.kills);            // the game's number stands
		assertEquals(147, t.noLootKills);      // 150 - 3 witnessed, the last one folded in
		assertEquals(300, t.totalValue);       // its loot is in the bag
		assertEquals(3, monsterCount(store, 0, "Nechryael"));
		assertEquals(3, store.slayerTaskItems(0).get(0).qty);
	}

	@Test
	public void theNextSameMonsterTaskIsNotFoldedIntoTheGrace()
	{
		countedKill("Nechryael", 150, 1);
		completion("Nechryael", 150, 214);
		finishingKill("Nechryael");
		countedKill("Nechryael", 130, 129);   // the new assignment's first kill, seconds later
		ChronicleApiClient.SlayerJourney j = store.slayerJourney();
		assertEquals(2, j.tasks.size());
		assertTrue(j.tasks.get(0).inProgress);
		assertEquals(1, j.tasks.get(0).kills);
		assertEquals(130, j.tasks.get(0).assignment);
		assertFalse(j.tasks.get(1).inProgress);
		assertEquals(150, j.tasks.get(1).kills);
		assertEquals(148, j.tasks.get(1).noLootKills);
	}

	@Test
	public void aFinishingKillBeyondTheGraceIsANewTask() throws Exception
	{
		// a task the server closed a day ago, in the shape it wrote
		File journal = new File(dir, "tester.json");
		long old = System.currentTimeMillis() / 1000L - 86_400;
		Files.write(journal.toPath(), ("{\"schema\":1,\"rsn\":\"Tester\",\"slayer\":{\"tasks\":["
			+ "{\"task\":\"Nechryael\",\"kills\":150,\"assignment\":150,\"value\":5,"
			+ "\"noLootKills\":148,\"open\":false,\"ts\":" + old + "}]}}").getBytes("UTF-8"));
		store.load(dir, "Tester");
		finishingKill("Nechryael");
		ChronicleApiClient.SlayerJourney j = store.slayerJourney();
		assertEquals(2, j.tasks.size());
		assertEquals(1, j.tasks.get(0).kills);
		assertTrue(j.tasks.get(0).inProgress);
		assertEquals(150, j.tasks.get(1).kills);
		assertEquals(148, j.tasks.get(1).noLootKills);
	}

	@Test
	public void aTaskClosedBeforeTheSplitStillTakesItsFinishingKill() throws Exception
	{
		// closed by the earlier build: total and no-drops only, no logged field
		File journal = new File(dir, "tester.json");
		long now = System.currentTimeMillis() / 1000L;
		Files.write(journal.toPath(), ("{\"schema\":1,\"rsn\":\"Tester\",\"slayer\":{\"tasks\":["
			+ "{\"task\":\"Nechryael\",\"kills\":150,\"assignment\":150,\"value\":5,"
			+ "\"noLootKills\":148,\"open\":false,\"ts\":" + now + "}]}}").getBytes("UTF-8"));
		store.load(dir, "Tester");
		finishingKill("Nechryael");
		ChronicleApiClient.SlayerJourney j = store.slayerJourney();
		assertEquals(1, j.tasks.size());
		assertEquals(150, j.tasks.get(0).kills);
		assertEquals(147, j.tasks.get(0).noLootKills);
	}

	@Test
	public void aCompletedTasksKillsAreTheGamesNumber()
	{
		// a surviving double-emit: three witnessed on a task the game says was two
		countedKill("Kalphite", 2, 1);
		countedKill("Kalphite", 2, 1);
		countedKill("Kalphite", 2, 0);
		completion("Kalphite", 2, null);
		ChronicleApiClient.SlayerJourney j = store.slayerJourney();
		assertEquals(2, j.tasks.get(0).kills);
		assertEquals(2, j.tasks.get(0).assignment);
		assertEquals(0, j.tasks.get(0).noLootKills);
		assertEquals(2, j.totalKills);
	}

	@Test
	public void theCounterCorrectsAStaleInitial()
	{
		countedKill("Abyssal demons", 222, 284);   // initial restored stale from config
		countedKill("Abyssal demons", 222, 283);
		ChronicleApiClient.SlayerTask t = store.slayerJourney().tasks.get(0);
		assertEquals(2, t.kills);
		assertEquals(285, t.assignment);   // remaining is post-decrement, so 284 proves 285
		assertTrue(t.inProgress);
	}

	@Test
	public void aCounterResetSplitsTwoSameMonsterTasks()
	{
		// both completion lines missed: the counter alone must split them
		countedKill("Dust devils", 120, 2);
		countedKill("Dust devils", 120, 1);
		countedKill("Dust devils", 120, 0);
		countedKill("Dust devils", 130, 129);
		countedKill("Dust devils", 130, 128);
		ChronicleApiClient.SlayerJourney j = store.slayerJourney();
		assertEquals(2, j.tasks.size());
		assertEquals(2, j.tasks.get(0).kills);
		assertEquals(130, j.tasks.get(0).assignment);
		assertTrue(j.tasks.get(0).inProgress);
		assertEquals(3, j.tasks.get(1).kills);
		assertFalse(j.tasks.get(1).inProgress);   // done, whether or not we saw the line
	}

	@Test
	public void aJuggledTaskResumesItsParkedRun()
	{
		countedKill("Hellhounds", 100, 50);
		countedKill("Hellhounds", 100, 49);
		countedKill("Greater demons", 80, 30);   // a Konar task in between
		countedKill("Hellhounds", 100, 48);      // back on it: the counter continues
		countedKill("Hellhounds", 100, 47);
		ChronicleApiClient.SlayerJourney j = store.slayerJourney();
		assertEquals(2, j.tasks.size());
		assertEquals("Hellhounds", j.tasks.get(0).task);   // one task, now the newest
		assertEquals(4, j.tasks.get(0).kills);
		assertTrue(j.tasks.get(0).inProgress);
		assertEquals("Greater demons", j.tasks.get(1).task);
		assertEquals(1, j.tasks.get(1).kills);
		assertFalse(j.tasks.get(1).inProgress);   // parked, and only the newest may be lit
		completion("Hellhounds", 51, null);
		j = store.slayerJourney();
		assertEquals(51, j.tasks.get(0).kills);
		assertEquals(47, j.tasks.get(0).noLootKills);
		assertFalse(j.tasks.get(0).inProgress);
		assertFalse(j.tasks.get(1).inProgress);
	}

	@Test
	public void aFreshAssignmentSupersedesTheParkedRun()
	{
		countedKill("Hellhounds", 100, 50);
		countedKill("Greater demons", 80, 30);
		countedKill("Hellhounds", 120, 119);   // a higher counter: a new assignment
		ChronicleApiClient.SlayerJourney j = store.slayerJourney();
		assertEquals(3, j.tasks.size());
		assertEquals(1, j.tasks.get(0).kills);
		assertEquals(120, j.tasks.get(0).assignment);
		assertTrue(j.tasks.get(0).inProgress);
		assertFalse(j.tasks.get(1).inProgress);
		assertFalse(j.tasks.get(2).inProgress);
	}

	@Test
	public void onlyTheNewestSegmentIsInProgress()
	{
		onTaskKill("Bloodveld", 200, 1, 1);   // no counter at all: the oldest capture shape
		onTaskKill("Jellies", 100, 1, 1);
		ChronicleApiClient.SlayerJourney j = store.slayerJourney();
		assertEquals(2, j.tasks.size());
		assertTrue(j.tasks.get(0).inProgress);
		assertFalse(j.tasks.get(1).inProgress);
	}
}
