/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.Gson;
import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import net.runelite.client.game.ItemManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class LocalStoreRebaseTest
{
	private LocalStore store;
	private File dir;

	@Before
	public void setUp() throws Exception
	{
		store = new LocalStore(Mockito.mock(ItemManager.class), new Gson());
		dir = Files.createTempDirectory("chronicle-rebase").toFile();
	}

	private static Map<String, Integer> session(String key, int n)
	{
		Map<String, Integer> m = new HashMap<>();
		m.put(key, n);
		return m;
	}

	private static Map<String, Integer> clearedStore()
	{
		return new HashMap<>();
	}

	private void mountWithLifetime(String rsn, String key, int n)
	{
		store.load(dir, rsn);
		store.setTrackers(session(key, n), rsn);
		store.flush(dir);
		store.load(dir, rsn);
	}

	@Test
	public void rebaseBanksTheFoldedSessionSoAClearedStoreCannotTakeItBack()
	{
		mountWithLifetime("Alpha", "tilesWalked", 500);
		assertEquals(500L, store.trackerBase("tilesWalked"));

		store.setTrackers(session("tilesWalked", 120), "Alpha");
		assertEquals(620L, (long) store.trackersSnapshot().get("tilesWalked"));

		store.rebase("Alpha");
		assertEquals(620L, store.trackerBase("tilesWalked"));

		store.setTrackers(clearedStore(), "Alpha");
		assertEquals(620L, (long) store.trackersSnapshot().get("tilesWalked"));
	}

	@Test
	public void withoutRebaseAClearedStoreRollsTheJournalBackToTheLoginValues()
	{
		mountWithLifetime("Alpha", "tilesWalked", 500);
		store.setTrackers(session("tilesWalked", 120), "Alpha");
		assertEquals(620L, (long) store.trackersSnapshot().get("tilesWalked"));

		store.setTrackers(clearedStore(), "Alpha");
		assertEquals(500L, (long) store.trackersSnapshot().get("tilesWalked"));
	}

	@Test
	public void repeatedRebasesReFreezeTheBaseRatherThanAccumulateOntoIt()
	{
		mountWithLifetime("Alpha", "tilesWalked", 500);
		store.setTrackers(session("tilesWalked", 120), "Alpha");

		store.rebase("Alpha");
		store.rebase("Alpha");

		store.setTrackers(clearedStore(), "Alpha");
		assertEquals(620L, (long) store.trackersSnapshot().get("tilesWalked"));
	}

	@Test
	public void aLifetimePeakSurvivesTheBlindWindowWithoutBeingSummed()
	{
		mountWithLifetime("Alpha", "highestHit", 90);

		store.setTrackers(session("highestHit", 110), "Alpha");
		assertEquals(110L, (long) store.trackersSnapshot().get("highestHit"));

		store.rebase("Alpha");

		store.setTrackers(session("highestHit", 0), "Alpha");
		assertEquals(110L, (long) store.trackersSnapshot().get("highestHit"));

		store.setTrackers(session("highestHit", 100), "Alpha");
		assertEquals(110L, (long) store.trackersSnapshot().get("highestHit"));
	}

	@Test
	public void rebaseIsRefusedWhileNoAccountOwnsTheMountedModel()
	{
		store.load(dir, "Alpha");
		store.setTrackers(session("tilesWalked", 500), "Alpha");
		store.endSession();
		assertFalse(store.isReadyFor("Alpha"));

		store.rebase("Beta");
		store.rebase("Alpha");
		assertEquals(0L, store.trackerBase("tilesWalked"));

		store.load(dir, "Beta");
		store.setTrackers(session("tilesWalked", 7), "Beta");
		assertEquals(7L, (long) store.trackersSnapshot().get("tilesWalked"));
	}
}
