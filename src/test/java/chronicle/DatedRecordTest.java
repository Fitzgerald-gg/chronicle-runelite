/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.File;
import java.util.List;
import net.runelite.client.game.ItemManager;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A personal best kept as a dated record with what it beat, and every timed
 * kill kept as a count and a sum so an average falls out over any period.
 *
 * <p>Only a best the game flags on the kill is a record set while the journal
 * watched: an old best restated on the first kill after install was set while
 * nobody was, and is not dated to today.
 */
public class DatedRecordTest
{
	@Rule
	public TemporaryFolder dir = new TemporaryFolder();
	private LocalStore store;

	@Before
	public void mount() throws Exception
	{
		ItemManager items = Mockito.mock(ItemManager.class);
		Mockito.when(items.getItemPrice(Mockito.anyInt())).thenReturn(100);
		store = new LocalStore(items, new Gson());
		store.load(dir.getRoot(), "Tester");
	}

	private void kill(double killTime, Double statedBest, boolean newBest)
	{
		JsonObject d = new JsonObject();
		d.addProperty("source", "Vorkath");
		JsonArray items = new JsonArray();
		JsonObject it = new JsonObject();
		it.addProperty("id", 22006);
		it.addProperty("quantity", 1);
		items.add(it);
		d.add("items", items);
		if (killTime > 0)
		{
			d.addProperty("killTime", killTime);
		}
		if (statedBest != null)
		{
			d.addProperty("personalBestTime", statedBest);
		}
		d.addProperty("personalBest", newBest);
		store.record("LOOT", d, "Tester");
	}

	private List<JsonObject> records()
	{
		List<JsonObject> out = new java.util.ArrayList<>();
		for (JsonObject e : store.feedNewest(50))
		{
			if ("RECORD".equals(e.get("type").getAsString()))
			{
				out.add(e);
			}
		}
		return out;
	}

	@Test
	public void aBestRestatedAfterInstallIsNotARecordSetToday()
	{
		kill(80, 72.0, false);
		assertEquals(72.0, source().pb, 0.001);
		assertTrue("an old best was dated to today", records().isEmpty());
		// and a lower best restated later, unflagged, fell while nobody watched
		kill(75, 70.0, false);
		assertEquals(70.0, source().pb, 0.001);
		assertTrue("a best set unwatched was dated to today", records().isEmpty());
	}

	@Test
	public void aBestTheGameFlagsIsADatedLineWithWhatItBeat()
	{
		kill(80, 72.0, false);
		kill(70, 70.0, true);
		List<JsonObject> recs = records();
		assertEquals(1, recs.size());
		JsonObject d = recs.get(0).getAsJsonObject("data");
		assertEquals("Vorkath", d.get("source").getAsString());
		assertEquals(70.0, d.get("time").getAsDouble(), 0.001);
		assertEquals(72.0, d.get("was").getAsDouble(), 0.001);
		assertEquals(70.0, source().pb, 0.001);
		// a slower kill afterwards is not a record
		kill(75, 70.0, false);
		assertEquals(1, records().size());
	}

	@Test
	public void theFirstBestTheJournalWatchedFallHasNothingItBeat()
	{
		kill(65, 65.0, true);
		JsonObject d = records().get(0).getAsJsonObject("data");
		assertFalse(d.has("was"));
	}

	@Test
	public void everyTimedKillCountsTowardTheAverage()
	{
		kill(60, null, false);
		kill(80, null, false);
		kill(100, null, false);
		kill(0, null, false);   // the timer said nothing for this one
		LocalStore.SourceRow v = source();
		assertEquals(3, v.timed);
		assertEquals(240.0, v.timeSum, 0.001);
		assertEquals(4, v.loots);
		// and the dated roll carries the same pair for today
		java.time.LocalDate today = java.time.LocalDate.now();
		LocalStore.LootWindow w = store.lootBetween(today, today);
		double[] t = w.times.get("Vorkath");
		assertEquals(3, (long) t[0]);
		assertEquals(240.0, t[1], 0.001);
		assertEquals(3, (long) store.sessionLootWindow().times.get("Vorkath")[0]);
	}

	private LocalStore.SourceRow source()
	{
		for (LocalStore.SourceRow r : store.dropSources())
		{
			if (r.name.equals("Vorkath"))
			{
				return r;
			}
		}
		throw new AssertionError("no Vorkath row");
	}
}
