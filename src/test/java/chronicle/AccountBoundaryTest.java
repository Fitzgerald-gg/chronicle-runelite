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
import static org.junit.Assert.assertTrue;

public class AccountBoundaryTest
{
	private LocalStore store;
	private File dir;

	@Before
	public void setUp() throws Exception
	{
		ItemManager im = Mockito.mock(ItemManager.class);
		store = new LocalStore(im, new Gson());
		dir = Files.createTempDirectory("chronicle-boundary").toFile();
	}

	private static Map<String, Integer> session(String key, int n)
	{
		Map<String, Integer> m = new HashMap<>();
		m.put(key, n);
		return m;
	}

	@Test
	public void endSessionStopsOwnershipButKeepsTheModelMounted()
	{
		store.load(dir, "Alpha");
		store.setTrackers(session("tilesWalked", 500), "Alpha");
		store.endSession();
		assertEquals(500L, (long) store.trackersSnapshot().get("tilesWalked"));
		assertFalse(store.isReadyFor("Alpha"));
		assertFalse(store.isReadyFor("Beta"));
	}

	@Test
	public void anotherAccountsWritesAreRefusedUntilItsJournalIsMounted()
	{
		store.load(dir, "Alpha");
		store.setTrackers(session("tilesWalked", 500), "Alpha");
		store.endSession();
		store.setTrackers(session("tilesWalked", 7), "Beta");
		store.record("PET", new com.google.gson.JsonObject(), "Beta");
		assertEquals(500L, (long) store.trackersSnapshot().get("tilesWalked"));
		store.load(dir, "Beta");
		assertTrue(store.isReadyFor("Beta"));
		assertFalse(store.isReadyFor("Alpha"));
		assertTrue(store.trackersSnapshot().isEmpty());
	}

	@Test
	public void reloadingALiveAccountWouldDoubleCountItsSession()
	{
		store.load(dir, "Alpha");
		store.setTrackers(session("tilesWalked", 500), "Alpha");
		store.flush(dir);
		assertEquals(500L, (long) store.trackersSnapshot().get("tilesWalked"));

		store.load(dir, "Alpha");
		store.setTrackers(session("tilesWalked", 500), "Alpha");
		assertEquals(1000L, (long) store.trackersSnapshot().get("tilesWalked"));
	}

	@Test
	public void aMountedAccountFoldsItsSessionOnceHoweverOftenItIsRecomputed()
	{
		store.load(dir, "Alpha");
		store.setTrackers(session("tilesWalked", 100), "Alpha");
		store.setTrackers(session("tilesWalked", 250), "Alpha");
		store.setTrackers(session("tilesWalked", 400), "Alpha");
		assertEquals(400L, (long) store.trackersSnapshot().get("tilesWalked"));
	}
}
